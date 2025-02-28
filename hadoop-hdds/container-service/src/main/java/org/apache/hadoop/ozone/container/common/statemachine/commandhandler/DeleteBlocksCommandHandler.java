/**
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with this
 * work for additional information regarding copyright ownership.  The ASF
 * licenses this file to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * <p>
 * http://www.apache.org/licenses/LICENSE-2.0
 * <p>
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */
package org.apache.hadoop.ozone.container.common.statemachine.commandhandler;

import com.google.common.util.concurrent.ThreadFactoryBuilder;
import org.apache.hadoop.hdds.conf.ConfigurationSource;
import org.apache.hadoop.hdds.protocol.datanode.proto.ContainerProtos.ContainerType;
import org.apache.hadoop.hdds.protocol.proto
    .StorageContainerDatanodeProtocolProtos.SCMCommandProto;
import org.apache.hadoop.hdds.scm.container.common.helpers
    .StorageContainerException;
import org.apache.hadoop.hdds.protocol.proto
    .StorageContainerDatanodeProtocolProtos.ContainerBlocksDeletionACKProto;
import org.apache.hadoop.hdds.protocol.proto
    .StorageContainerDatanodeProtocolProtos.ContainerBlocksDeletionACKProto
    .DeleteBlockTransactionResult;
import org.apache.hadoop.hdds.protocol.proto
    .StorageContainerDatanodeProtocolProtos.DeletedBlocksTransaction;
import org.apache.hadoop.hdds.utils.db.Table;
import org.apache.hadoop.ozone.OzoneConsts;
import org.apache.hadoop.ozone.container.common.helpers.BlockData;
import org.apache.hadoop.ozone.container.common.helpers.ChunkInfoList;
import org.apache.hadoop.ozone.container.common.helpers
    .DeletedContainerBlocksSummary;
import org.apache.hadoop.ozone.container.common.interfaces.Container;
import org.apache.hadoop.ozone.container.keyvalue.KeyValueContainerData;
import org.apache.hadoop.ozone.container.keyvalue.helpers.BlockUtils;
import org.apache.hadoop.ozone.container.common.impl.ContainerSet;
import org.apache.hadoop.ozone.container.common.statemachine
    .SCMConnectionManager;
import org.apache.hadoop.ozone.container.common.statemachine.StateContext;
import org.apache.hadoop.ozone.container.metadata.DatanodeStore;
import org.apache.hadoop.ozone.container.metadata.DatanodeStoreSchemaTwoImpl;
import org.apache.hadoop.ozone.container.ozoneimpl.OzoneContainer;
import org.apache.hadoop.ozone.protocol.commands.CommandStatus;
import org.apache.hadoop.ozone.protocol.commands.DeleteBlockCommandStatus;
import org.apache.hadoop.ozone.protocol.commands.DeleteBlocksCommand;
import org.apache.hadoop.ozone.protocol.commands.SCMCommand;
import org.apache.hadoop.util.Daemon;
import org.apache.hadoop.util.Time;
import org.apache.hadoop.hdds.utils.db.BatchOperation;
import org.apache.hadoop.ozone.container.common.utils.ReferenceCountedDB;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import static org.apache.hadoop.hdds.protocol.datanode.proto.ContainerProtos
    .Result.CONTAINER_NOT_FOUND;
import static org.apache.hadoop.ozone.OzoneConsts.SCHEMA_V1;
import static org.apache.hadoop.ozone.OzoneConsts.SCHEMA_V2;

/**
 * Handle block deletion commands.
 */
public class DeleteBlocksCommandHandler implements CommandHandler {

  public static final Logger LOG =
      LoggerFactory.getLogger(DeleteBlocksCommandHandler.class);

  private final ContainerSet containerSet;
  private final ConfigurationSource conf;
  private int invocationCount;
  private long totalTime;
  private final ExecutorService executor;
  private final LinkedBlockingQueue<DeleteCmdInfo> deleteCommandQueues;
  private final Daemon handlerThread;

  public DeleteBlocksCommandHandler(ContainerSet cset,
      ConfigurationSource conf, int threadPoolSize, int queueLimit) {
    this.containerSet = cset;
    this.conf = conf;
    this.executor = new ThreadPoolExecutor(
        0, threadPoolSize, 60, TimeUnit.SECONDS,
        new LinkedBlockingQueue<>(),
        new ThreadFactoryBuilder().setDaemon(true)
            .setNameFormat("DeleteBlocksCommandHandlerThread-%d")
            .build());
    this.deleteCommandQueues = new LinkedBlockingQueue<>(queueLimit);
    handlerThread = new Daemon(new DeleteCmdWorker());
    handlerThread.start();
  }

  @Override
  public void handle(SCMCommand command, OzoneContainer container,
      StateContext context, SCMConnectionManager connectionManager) {
    if (command.getType() != SCMCommandProto.Type.deleteBlocksCommand) {
      LOG.warn("Skipping handling command, expected command "
              + "type {} but found {}",
          SCMCommandProto.Type.deleteBlocksCommand, command.getType());
      return;
    }

    try {
      DeleteCmdInfo cmd = new DeleteCmdInfo((DeleteBlocksCommand) command,
          container, context, connectionManager);
      deleteCommandQueues.add(cmd);
    } catch (IllegalStateException e) {
      LOG.warn("Command is discarded because of the command queue is full");
      return;
    }
  }

  /**
   * The count returned here, is the count of queued SCM delete block commands.
   * We get at most one such command per heartbeat, but the command can contain
   * many blocks to delete.
   * @return The number of SCM delete block commands pending in the queue.
   */
  @Override
  public int getQueuedCount() {
    return deleteCommandQueues.size();
  }

  /**
   * A delete command info.
   */
  public static final class DeleteCmdInfo {
    private DeleteBlocksCommand cmd;
    private StateContext context;
    private OzoneContainer container;
    private SCMConnectionManager connectionManager;

    public DeleteCmdInfo(DeleteBlocksCommand command, OzoneContainer container,
        StateContext context, SCMConnectionManager connectionManager) {
      this.cmd = command;
      this.context = context;
      this.container = container;
      this.connectionManager = connectionManager;
    }
    public DeleteBlocksCommand getCmd() {
      return this.cmd;
    }
    public StateContext getContext() {
      return this.context;
    }
    public OzoneContainer getContainer() {
      return this.container;
    }
    public SCMConnectionManager getConnectionManager() {
      return this.connectionManager;
    }
  }

  /**
   * Process delete commands.
   */
  public final class DeleteCmdWorker implements Runnable {

    @Override
    public void run() {
      while (true) {
        while (!deleteCommandQueues.isEmpty()) {
          DeleteCmdInfo cmd = deleteCommandQueues.poll();
          try {
            processCmd(cmd);
          } catch (Throwable e) {
            LOG.error("taskProcess failed.", e);
          }
        }

        try {
          Thread.sleep(2000);
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
        }
      }
    }
  }

  /**
   * Process one delete transaction.
   */
  public final class ProcessTransactionTask implements Runnable {
    private DeletedBlocksTransaction tx;
    private ContainerBlocksDeletionACKProto.Builder result;

    public ProcessTransactionTask(DeletedBlocksTransaction transaction,
        ContainerBlocksDeletionACKProto.Builder resultBuilder) {
      this.result = resultBuilder;
      this.tx = transaction;
    }

    @Override
    public void run() {
      DeleteBlockTransactionResult.Builder txResultBuilder =
          DeleteBlockTransactionResult.newBuilder();
      txResultBuilder.setTxID(tx.getTxID());
      long containerId = tx.getContainerID();
      int newDeletionBlocks = 0;
      try {
        Container cont = containerSet.getContainer(containerId);
        if (cont == null) {
          throw new StorageContainerException("Unable to find the " +
              "container " + containerId, CONTAINER_NOT_FOUND);
        }

        ContainerType containerType = cont.getContainerType();
        switch (containerType) {
        case KeyValueContainer:
          KeyValueContainerData containerData = (KeyValueContainerData)
              cont.getContainerData();
          cont.writeLock();
          try {
            if (containerData.getSchemaVersion().equals(SCHEMA_V1)) {
              markBlocksForDeletionSchemaV1(containerData, tx);
            } else if (containerData.getSchemaVersion().equals(SCHEMA_V2)) {
              markBlocksForDeletionSchemaV2(containerData, tx,
                  newDeletionBlocks, tx.getTxID());
            } else {
              throw new UnsupportedOperationException(
                  "Only schema version 1 and schema version 2 are "
                      + "supported.");
            }
          } finally {
            cont.writeUnlock();
          }
          txResultBuilder.setContainerID(containerId)
              .setSuccess(true);
          break;
        default:
          LOG.error(
              "Delete Blocks Command Handler is not implemented for " +
                  "containerType {}", containerType);
        }
      } catch (IOException e) {
        LOG.warn("Failed to delete blocks for container={}, TXID={}",
            tx.getContainerID(), tx.getTxID(), e);
        txResultBuilder.setContainerID(containerId)
            .setSuccess(false);
      }
      result.addResults(txResultBuilder.build());
    }
  }

  private void processCmd(DeleteCmdInfo cmd) {
    LOG.debug("Processing block deletion command.");
    ContainerBlocksDeletionACKProto blockDeletionACK = null;
    long startTime = Time.monotonicNow();
    boolean cmdExecuted = false;
    try {
      // move blocks to deleting state.
      // this is a metadata update, the actual deletion happens in another
      // recycling thread.
      List<DeletedBlocksTransaction> containerBlocks =
          cmd.getCmd().blocksTobeDeleted();

      DeletedContainerBlocksSummary summary =
          DeletedContainerBlocksSummary.getFrom(containerBlocks);
      LOG.info("Start to delete container blocks, TXIDs={}, "
              + "numOfContainers={}, numOfBlocks={}",
          summary.getTxIDSummary(),
          summary.getNumOfContainers(),
          summary.getNumOfBlocks());

      ContainerBlocksDeletionACKProto.Builder resultBuilder =
          ContainerBlocksDeletionACKProto.newBuilder();
      List<Future> futures = new ArrayList<>();
      for (int i = 0; i < containerBlocks.size(); i++) {
        DeletedBlocksTransaction tx = containerBlocks.get(i);
        Future future = executor.submit(
            new ProcessTransactionTask(tx, resultBuilder));
        futures.add(future);
      }

      // Wait for tasks to finish
      futures.forEach(f -> {
        try {
          f.get();
        } catch (InterruptedException | ExecutionException e) {
          LOG.error("task failed.", e);
          Thread.currentThread().interrupt();
        }
      });

      resultBuilder.setDnId(cmd.getContext().getParent().getDatanodeDetails()
          .getUuid().toString());
      blockDeletionACK = resultBuilder.build();

      // Send ACK back to SCM as long as meta updated
      // TODO Or we should wait until the blocks are actually deleted?
      if (!containerBlocks.isEmpty()) {
        if (LOG.isDebugEnabled()) {
          LOG.debug("Sending following block deletion ACK to SCM");
          for (DeleteBlockTransactionResult result : blockDeletionACK
              .getResultsList()) {
            LOG.debug("{} : {}", result.getTxID(), result.getSuccess());
          }
        }
      }
      cmdExecuted = true;
    } finally {
      final ContainerBlocksDeletionACKProto deleteAck =
          blockDeletionACK;
      final boolean status = cmdExecuted;
      Consumer<CommandStatus> statusUpdater = (cmdStatus) -> {
        cmdStatus.setStatus(status);
        ((DeleteBlockCommandStatus)cmdStatus).setBlocksDeletionAck(deleteAck);
      };
      updateCommandStatus(cmd.getContext(), cmd.getCmd(), statusUpdater, LOG);
      long endTime = Time.monotonicNow();
      totalTime += endTime - startTime;
      invocationCount++;
    }
  }

  /**
   * Move a bunch of blocks from a container to deleting state. This is a meta
   * update, the actual deletes happen in async mode.
   *
   * @param containerData - KeyValueContainerData
   * @param delTX a block deletion transaction.
   * @throws IOException if I/O error occurs.
   */

  private void markBlocksForDeletionSchemaV2(
      KeyValueContainerData containerData, DeletedBlocksTransaction delTX,
      int newDeletionBlocks, long txnID) throws IOException {
    long containerId = delTX.getContainerID();
    if (!isTxnIdValid(containerId, containerData, delTX)) {
      return;
    }
    try (ReferenceCountedDB containerDB = BlockUtils
        .getDB(containerData, conf)) {
      DatanodeStore ds = containerDB.getStore();
      DatanodeStoreSchemaTwoImpl dnStoreTwoImpl =
          (DatanodeStoreSchemaTwoImpl) ds;
      Table<Long, DeletedBlocksTransaction> delTxTable =
          dnStoreTwoImpl.getDeleteTransactionTable();
      try (BatchOperation batch = containerDB.getStore().getBatchHandler()
          .initBatchOperation()) {
        delTxTable.putWithBatch(batch, txnID, delTX);
        newDeletionBlocks += delTX.getLocalIDList().size();
        updateMetaData(containerData, delTX, newDeletionBlocks, containerDB,
            batch);
        containerDB.getStore().getBatchHandler().commitBatchOperation(batch);
      }
    }
  }

  private void markBlocksForDeletionSchemaV1(
      KeyValueContainerData containerData, DeletedBlocksTransaction delTX)
      throws IOException {
    long containerId = delTX.getContainerID();
    if (!isTxnIdValid(containerId, containerData, delTX)) {
      return;
    }
    int newDeletionBlocks = 0;
    try (ReferenceCountedDB containerDB = BlockUtils
        .getDB(containerData, conf)) {
      Table<String, BlockData> blockDataTable =
          containerDB.getStore().getBlockDataTable();
      Table<String, ChunkInfoList> deletedBlocksTable =
          containerDB.getStore().getDeletedBlocksTable();

      try (BatchOperation batch = containerDB.getStore().getBatchHandler()
          .initBatchOperation()) {
        for (Long blkLong : delTX.getLocalIDList()) {
          String blk = blkLong.toString();
          BlockData blkInfo = blockDataTable.get(blk);
          if (blkInfo != null) {
            String deletingKey = OzoneConsts.DELETING_KEY_PREFIX + blk;
            if (blockDataTable.get(deletingKey) != null
                || deletedBlocksTable.get(blk) != null) {
              if (LOG.isDebugEnabled()) {
                LOG.debug(String.format(
                    "Ignoring delete for block %s in container %d."
                        + " Entry already added.", blk, containerId));
              }
              continue;
            }
            // Found the block in container db,
            // use an atomic update to change its state to deleting.
            blockDataTable.putWithBatch(batch, deletingKey, blkInfo);
            blockDataTable.deleteWithBatch(batch, blk);
            newDeletionBlocks++;
            if (LOG.isDebugEnabled()) {
              LOG.debug("Transited Block {} to DELETING state in container {}",
                  blk, containerId);
            }
          } else {
            if (LOG.isDebugEnabled()) {
              LOG.debug("Block {} not found or already under deletion in"
                  + " container {}, skip deleting it.", blk, containerId);
            }
          }
        }
        updateMetaData(containerData, delTX, newDeletionBlocks, containerDB,
            batch);
        containerDB.getStore().getBatchHandler().commitBatchOperation(batch);
      } catch (IOException e) {
        // if some blocks failed to delete, we fail this TX,
        // without sending this ACK to SCM, SCM will resend the TX
        // with a certain number of retries.
        throw new IOException(
            "Failed to delete blocks for TXID = " + delTX.getTxID(), e);
      }
    }
  }

  private void updateMetaData(KeyValueContainerData containerData,
      DeletedBlocksTransaction delTX, int newDeletionBlocks,
      ReferenceCountedDB containerDB, BatchOperation batchOperation)
      throws IOException {
    if (newDeletionBlocks > 0) {
      // Finally commit the DB counters.
      Table<String, Long> metadataTable =
          containerDB.getStore().getMetadataTable();

      // In memory is updated only when existing delete transactionID is
      // greater.
      if (delTX.getTxID() > containerData.getDeleteTransactionId()) {
        // Update in DB pending delete key count and delete transaction ID.
        metadataTable
            .putWithBatch(batchOperation, OzoneConsts.DELETE_TRANSACTION_KEY,
                delTX.getTxID());
      }

      long pendingDeleteBlocks =
          containerData.getNumPendingDeletionBlocks() + newDeletionBlocks;
      metadataTable
          .putWithBatch(batchOperation, OzoneConsts.PENDING_DELETE_BLOCK_COUNT,
              pendingDeleteBlocks);

      // update pending deletion blocks count and delete transaction ID in
      // in-memory container status
      containerData.updateDeleteTransactionId(delTX.getTxID());
      containerData.incrPendingDeletionBlocks(newDeletionBlocks);
    }
  }

  private boolean isTxnIdValid(long containerId,
      KeyValueContainerData containerData, DeletedBlocksTransaction delTX) {
    boolean b = true;
    if (LOG.isDebugEnabled()) {
      LOG.debug("Processing Container : {}, DB path : {}", containerId,
          containerData.getMetadataPath());
    }

    if (delTX.getTxID() <= containerData.getDeleteTransactionId()) {
      if (LOG.isDebugEnabled()) {
        LOG.debug(String.format("Ignoring delete blocks for containerId: %d."
                + " Outdated delete transactionId %d < %d", containerId,
            delTX.getTxID(), containerData.getDeleteTransactionId()));
      }
      b = false;
    }
    return b;
  }

  @Override
  public SCMCommandProto.Type getCommandType() {
    return SCMCommandProto.Type.deleteBlocksCommand;
  }

  @Override
  public int getInvocationCount() {
    return this.invocationCount;
  }

  @Override
  public long getAverageRunTime() {
    if (invocationCount > 0) {
      return totalTime / invocationCount;
    }
    return 0;
  }

  @Override
  public void stop() {
    if (executor != null) {
      try {
        executor.shutdown();
        if (!executor.awaitTermination(3, TimeUnit.SECONDS)) {
          executor.shutdownNow();
        }
      } catch (InterruptedException ie) {
        // Ignore, we don't really care about the failure.
        Thread.currentThread().interrupt();
      }
    }

    if (handlerThread != null) {
      try {
        handlerThread.interrupt();
        handlerThread.join(3000);
      } catch (InterruptedException ie) {
        // Ignore, we don't really care about the failure.
        Thread.currentThread().interrupt();
      }
    }
  }
}
