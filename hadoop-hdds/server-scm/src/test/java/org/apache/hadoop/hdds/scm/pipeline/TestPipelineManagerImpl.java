/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.hadoop.hdds.scm.pipeline;

import com.google.common.base.Supplier;
import org.apache.hadoop.fs.FileUtil;
import org.apache.hadoop.hdds.HddsConfigKeys;
import org.apache.hadoop.hdds.client.RatisReplicationConfig;
import org.apache.hadoop.hdds.conf.OzoneConfiguration;
import org.apache.hadoop.hdds.protocol.DatanodeDetails;
import org.apache.hadoop.hdds.protocol.proto.HddsProtos;
import org.apache.hadoop.hdds.protocol.proto.HddsProtos.ReplicationFactor;
import org.apache.hadoop.hdds.protocol.proto.StorageContainerDatanodeProtocolProtos;
import org.apache.hadoop.hdds.scm.HddsTestUtils;
import org.apache.hadoop.hdds.scm.container.ContainerID;
import org.apache.hadoop.hdds.scm.container.ContainerInfo;
import org.apache.hadoop.hdds.scm.container.ContainerManager;
import org.apache.hadoop.hdds.scm.container.ContainerReplica;
import org.apache.hadoop.hdds.scm.container.MockNodeManager;
import org.apache.hadoop.hdds.scm.exceptions.SCMException;
import org.apache.hadoop.hdds.scm.ha.SCMHADBTransactionBuffer;
import org.apache.hadoop.hdds.scm.ha.SCMHADBTransactionBufferStub;
import org.apache.hadoop.hdds.scm.ha.SCMHAManagerStub;
import org.apache.hadoop.hdds.scm.ha.SCMContext;
import org.apache.hadoop.hdds.scm.ha.SCMServiceManager;
import org.apache.hadoop.hdds.scm.metadata.SCMDBDefinition;
import org.apache.hadoop.hdds.scm.node.NodeStatus;
import org.apache.hadoop.hdds.scm.safemode.SCMSafeModeManager;
import org.apache.hadoop.hdds.scm.server.SCMDatanodeHeartbeatDispatcher;
import org.apache.hadoop.hdds.scm.server.StorageContainerManager;
import org.apache.hadoop.hdds.server.events.EventQueue;
import org.apache.hadoop.hdds.utils.db.DBStore;
import org.apache.hadoop.hdds.utils.db.DBStoreBuilder;
import org.apache.hadoop.hdds.utils.db.Table;
import org.apache.hadoop.metrics2.MetricsRecordBuilder;
import org.apache.hadoop.ozone.container.common.SCMTestUtils;
import org.apache.ozone.test.GenericTestUtils;
import org.apache.ozone.test.TestClock;
import org.apache.ratis.protocol.exceptions.NotLeaderException;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static org.apache.hadoop.hdds.scm.ScmConfigKeys.OZONE_DATANODE_PIPELINE_LIMIT;
import static org.apache.hadoop.hdds.scm.ScmConfigKeys.OZONE_DATANODE_PIPELINE_LIMIT_DEFAULT;
import static org.apache.hadoop.hdds.scm.ScmConfigKeys.OZONE_SCM_PIPELINE_ALLOCATED_TIMEOUT;
import static org.apache.hadoop.hdds.scm.pipeline.Pipeline.PipelineState.ALLOCATED;
import static org.apache.hadoop.test.MetricsAsserts.getLongCounter;
import static org.apache.hadoop.test.MetricsAsserts.getMetrics;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Tests for PipelineManagerImpl.
 */
public class TestPipelineManagerImpl {
  private OzoneConfiguration conf;
  private File testDir;
  private DBStore dbStore;
  private MockNodeManager nodeManager;
  private int maxPipelineCount;
  private SCMContext scmContext;
  private SCMServiceManager serviceManager;
  private StorageContainerManager scm;
  private TestClock testClock;

  @Before
  public void init() throws Exception {
    testClock = new TestClock(Instant.now(), ZoneOffset.UTC);
    conf = SCMTestUtils.getConf();
    testDir = GenericTestUtils.getTestDir(
        TestPipelineManagerImpl.class.getSimpleName() + UUID.randomUUID());
    conf.set(HddsConfigKeys.OZONE_METADATA_DIRS,
        GenericTestUtils.getRandomizedTempPath());
    scm = HddsTestUtils.getScm(conf);
    conf.set(HddsConfigKeys.OZONE_METADATA_DIRS, testDir.getAbsolutePath());
    dbStore = DBStoreBuilder.createDBStore(conf, new SCMDBDefinition());
    nodeManager = new MockNodeManager(true, 20);
    maxPipelineCount = nodeManager.getNodeCount(
        HddsProtos.NodeOperationalState.IN_SERVICE,
        HddsProtos.NodeState.HEALTHY) *
        conf.getInt(OZONE_DATANODE_PIPELINE_LIMIT,
            OZONE_DATANODE_PIPELINE_LIMIT_DEFAULT) /
        HddsProtos.ReplicationFactor.THREE.getNumber();
    scmContext = new SCMContext.Builder().setIsInSafeMode(true)
        .setLeader(true).setIsPreCheckComplete(true)
        .setSCM(scm).build();
    serviceManager = new SCMServiceManager();
  }

  @After
  public void cleanup() throws Exception {
    if (dbStore != null) {
      dbStore.close();
    }
    FileUtil.fullyDelete(testDir);
  }

  private PipelineManagerImpl createPipelineManager(boolean isLeader)
      throws IOException {
    return PipelineManagerImpl.newPipelineManager(conf,
        SCMHAManagerStub.getInstance(isLeader),
        new MockNodeManager(true, 20),
        SCMDBDefinition.PIPELINES.getTable(dbStore),
        new EventQueue(),
        scmContext,
        serviceManager,
        testClock);
  }

  private PipelineManagerImpl createPipelineManager(
      boolean isLeader, SCMHADBTransactionBuffer buffer) throws IOException {
    return PipelineManagerImpl.newPipelineManager(conf,
        SCMHAManagerStub.getInstance(isLeader, buffer),
        new MockNodeManager(true, 20),
        SCMDBDefinition.PIPELINES.getTable(dbStore),
        new EventQueue(),
        SCMContext.emptyContext(),
        serviceManager,
        new TestClock(Instant.now(), ZoneOffset.UTC));
  }

  @Test
  public void testCreatePipeline() throws Exception {
    SCMHADBTransactionBuffer buffer1 =
        new SCMHADBTransactionBufferStub(dbStore);
    PipelineManagerImpl pipelineManager =
        createPipelineManager(true, buffer1);
    Assert.assertTrue(pipelineManager.getPipelines().isEmpty());
    Pipeline pipeline1 = pipelineManager.createPipeline(
        RatisReplicationConfig.getInstance(ReplicationFactor.THREE));
    Assert.assertEquals(1, pipelineManager.getPipelines().size());
    Assert.assertTrue(pipelineManager.containsPipeline(pipeline1.getId()));

    Pipeline pipeline2 = pipelineManager.createPipeline(
        RatisReplicationConfig.getInstance(ReplicationFactor.ONE));
    Assert.assertEquals(2, pipelineManager.getPipelines().size());
    Assert.assertTrue(pipelineManager.containsPipeline(pipeline2.getId()));
    buffer1.close();
    pipelineManager.close();

    SCMHADBTransactionBuffer buffer2 =
        new SCMHADBTransactionBufferStub(dbStore);
    PipelineManagerImpl pipelineManager2 =
        createPipelineManager(true, buffer2);
    // Should be able to load previous pipelines.
    Assert.assertFalse(pipelineManager2.getPipelines().isEmpty());
    Assert.assertEquals(2, pipelineManager.getPipelines().size());
    Pipeline pipeline3 = pipelineManager2.createPipeline(
        RatisReplicationConfig.getInstance(ReplicationFactor.THREE));
    buffer2.close();
    Assert.assertEquals(3, pipelineManager2.getPipelines().size());
    Assert.assertTrue(pipelineManager2.containsPipeline(pipeline3.getId()));

    pipelineManager2.close();
  }

  @Test
  public void testCreatePipelineShouldFailOnFollower() throws Exception {
    PipelineManagerImpl pipelineManager = createPipelineManager(false);
    Assert.assertTrue(pipelineManager.getPipelines().isEmpty());
    try {
      pipelineManager
          .createPipeline(RatisReplicationConfig
              .getInstance(ReplicationFactor.THREE));
    } catch (NotLeaderException ex) {
      pipelineManager.close();
      return;
    }
    // Should not reach here.
    Assert.fail();
  }

  @Test
  public void testUpdatePipelineStates() throws Exception {
    SCMHADBTransactionBuffer buffer = new SCMHADBTransactionBufferStub(dbStore);
    PipelineManagerImpl pipelineManager =
        createPipelineManager(true, buffer);
    Table<PipelineID, Pipeline> pipelineStore =
        SCMDBDefinition.PIPELINES.getTable(dbStore);
    Pipeline pipeline = pipelineManager.createPipeline(
        RatisReplicationConfig.getInstance(ReplicationFactor.THREE));
    Assert.assertEquals(1, pipelineManager.getPipelines().size());
    Assert.assertTrue(pipelineManager.containsPipeline(pipeline.getId()));
    Assert.assertEquals(ALLOCATED, pipeline.getPipelineState());
    buffer.flush();
    Assert.assertEquals(ALLOCATED,
        pipelineStore.get(pipeline.getId()).getPipelineState());
    PipelineID pipelineID = pipeline.getId();

    pipelineManager.openPipeline(pipelineID);
    pipelineManager.addContainerToPipeline(pipelineID, ContainerID.valueOf(1));
    Assert.assertTrue(pipelineManager
        .getPipelines(RatisReplicationConfig
                .getInstance(ReplicationFactor.THREE),
            Pipeline.PipelineState.OPEN).contains(pipeline));
    buffer.flush();
    Assert.assertTrue(pipelineStore.get(pipeline.getId()).isOpen());

    pipelineManager.deactivatePipeline(pipeline.getId());
    Assert.assertEquals(Pipeline.PipelineState.DORMANT,
        pipelineManager.getPipeline(pipelineID).getPipelineState());
    buffer.flush();
    Assert.assertEquals(Pipeline.PipelineState.DORMANT,
        pipelineStore.get(pipeline.getId()).getPipelineState());
    Assert.assertFalse(pipelineManager
        .getPipelines(RatisReplicationConfig
            .getInstance(ReplicationFactor.THREE),
            Pipeline.PipelineState.OPEN).contains(pipeline));
    Assert.assertEquals(1, pipelineManager.getPipelineCount(
        RatisReplicationConfig.getInstance(ReplicationFactor.THREE),
            Pipeline.PipelineState.DORMANT));

    pipelineManager.activatePipeline(pipeline.getId());
    Assert.assertTrue(pipelineManager
        .getPipelines(RatisReplicationConfig
            .getInstance(ReplicationFactor.THREE),
            Pipeline.PipelineState.OPEN).contains(pipeline));
    Assert.assertEquals(1, pipelineManager.getPipelineCount(
        RatisReplicationConfig.getInstance(ReplicationFactor.THREE),
            Pipeline.PipelineState.OPEN));
    buffer.flush();
    Assert.assertTrue(pipelineStore.get(pipeline.getId()).isOpen());
    pipelineManager.close();
  }

  @Test
  public void testOpenPipelineShouldFailOnFollower() throws Exception {
    PipelineManagerImpl pipelineManager = createPipelineManager(true);
    Pipeline pipeline = pipelineManager.createPipeline(
        RatisReplicationConfig.getInstance(ReplicationFactor.THREE));
    Assert.assertEquals(1, pipelineManager.getPipelines().size());
    Assert.assertTrue(pipelineManager.containsPipeline(pipeline.getId()));
    Assert.assertEquals(ALLOCATED, pipeline.getPipelineState());
    // Change to follower
    assert pipelineManager.getScmhaManager() instanceof SCMHAManagerStub;
    ((SCMHAManagerStub) pipelineManager.getScmhaManager()).setIsLeader(false);
    try {
      pipelineManager.openPipeline(pipeline.getId());
    } catch (NotLeaderException ex) {
      pipelineManager.close();
      return;
    }
    // Should not reach here.
    Assert.fail();
  }

  @Test
  public void testActivatePipelineShouldFailOnFollower() throws Exception {
    PipelineManagerImpl pipelineManager = createPipelineManager(true);
    Pipeline pipeline = pipelineManager.createPipeline(
        RatisReplicationConfig.getInstance(ReplicationFactor.THREE));
    Assert.assertEquals(1, pipelineManager.getPipelines().size());
    Assert.assertTrue(pipelineManager.containsPipeline(pipeline.getId()));
    Assert.assertEquals(ALLOCATED, pipeline.getPipelineState());
    // Change to follower
    assert pipelineManager.getScmhaManager() instanceof SCMHAManagerStub;
    ((SCMHAManagerStub) pipelineManager.getScmhaManager()).setIsLeader(false);
    try {
      pipelineManager.activatePipeline(pipeline.getId());
    } catch (NotLeaderException ex) {
      pipelineManager.close();
      return;
    }
    // Should not reach here.
    Assert.fail();
  }

  @Test
  public void testDeactivatePipelineShouldFailOnFollower() throws Exception {
    PipelineManagerImpl pipelineManager = createPipelineManager(true);
    Pipeline pipeline = pipelineManager.createPipeline(
        RatisReplicationConfig.getInstance(ReplicationFactor.THREE));
    Assert.assertEquals(1, pipelineManager.getPipelines().size());
    Assert.assertTrue(pipelineManager.containsPipeline(pipeline.getId()));
    Assert.assertEquals(ALLOCATED, pipeline.getPipelineState());
    // Change to follower
    assert pipelineManager.getScmhaManager() instanceof SCMHAManagerStub;
    ((SCMHAManagerStub) pipelineManager.getScmhaManager()).setIsLeader(false);
    try {
      pipelineManager.deactivatePipeline(pipeline.getId());
    } catch (NotLeaderException ex) {
      pipelineManager.close();
      return;
    }
    // Should not reach here.
    Assert.fail();
  }

  @Test
  public void testRemovePipeline() throws Exception {
    PipelineManagerImpl pipelineManager = createPipelineManager(true);
    pipelineManager.setScmContext(scmContext);
    // Create a pipeline
    Pipeline pipeline = pipelineManager.createPipeline(
        RatisReplicationConfig.getInstance(ReplicationFactor.THREE));
    Assert.assertEquals(1, pipelineManager.getPipelines().size());
    Assert.assertTrue(pipelineManager.containsPipeline(pipeline.getId()));
    Assert.assertEquals(ALLOCATED, pipeline.getPipelineState());

    // Open the pipeline
    pipelineManager.openPipeline(pipeline.getId());
    ContainerManager containerManager = scm.getContainerManager();
    ContainerInfo containerInfo = HddsTestUtils.
            getContainer(HddsProtos.LifeCycleState.CLOSED, pipeline.getId());
    ContainerID containerID = containerInfo.containerID();
    //Add Container to ContainerMap
    containerManager.getContainerStateManager().
            addContainer(containerInfo.getProtobuf());
    //Add Container to PipelineStateMap
    pipelineManager.addContainerToPipeline(pipeline.getId(), containerID);
    Assert.assertTrue(pipelineManager
        .getPipelines(RatisReplicationConfig
            .getInstance(ReplicationFactor.THREE),
            Pipeline.PipelineState.OPEN).contains(pipeline));

    try {
      pipelineManager.removePipeline(pipeline);
      fail();
    } catch (IOException ioe) {
      // Should not be able to remove the OPEN pipeline.
      Assert.assertEquals(1, pipelineManager.getPipelines().size());
    } catch (Exception e) {
      Assert.fail("Should not reach here.");
    }

    // Destroy pipeline
    pipelineManager.closePipeline(pipeline, false);
    try {
      pipelineManager.getPipeline(pipeline.getId());
      fail("Pipeline should not have been retrieved");
    } catch (PipelineNotFoundException e) {
      // There may be pipelines created by BackgroundPipelineCreator
      // exist in pipelineManager, just ignore them.
    }

    pipelineManager.close();
  }

  @Test
  public void testClosePipelineShouldFailOnFollower() throws Exception {
    PipelineManagerImpl pipelineManager = createPipelineManager(true);
    pipelineManager.setScmContext(scmContext);
    Pipeline pipeline = pipelineManager.createPipeline(
        RatisReplicationConfig.getInstance(ReplicationFactor.THREE));
    Assert.assertEquals(1, pipelineManager.getPipelines().size());
    Assert.assertTrue(pipelineManager.containsPipeline(pipeline.getId()));
    Assert.assertEquals(ALLOCATED, pipeline.getPipelineState());
    // Change to follower
    assert pipelineManager.getScmhaManager() instanceof SCMHAManagerStub;
    ((SCMHAManagerStub) pipelineManager.getScmhaManager()).setIsLeader(false);
    try {
      pipelineManager.closePipeline(pipeline, false);
    } catch (NotLeaderException ex) {
      pipelineManager.close();
      return;
    }
    // Should not reach here.
    Assert.fail();
  }

  @Test
  public void testPipelineReport() throws Exception {
    PipelineManagerImpl pipelineManager = createPipelineManager(true);
    pipelineManager.setScmContext(scmContext);
    SCMSafeModeManager scmSafeModeManager =
        new SCMSafeModeManager(conf, new ArrayList<>(), null, pipelineManager,
            new EventQueue(), serviceManager, scmContext);
    Pipeline pipeline = pipelineManager
        .createPipeline(RatisReplicationConfig
            .getInstance(ReplicationFactor.THREE));

    // pipeline is not healthy until all dns report
    List<DatanodeDetails> nodes = pipeline.getNodes();
    Assert.assertFalse(
        pipelineManager.getPipeline(pipeline.getId()).isHealthy());
    // get pipeline report from each dn in the pipeline
    PipelineReportHandler pipelineReportHandler =
        new PipelineReportHandler(scmSafeModeManager, pipelineManager,
            SCMContext.emptyContext(), conf);
    nodes.subList(0, 2).forEach(dn -> sendPipelineReport(dn, pipeline,
        pipelineReportHandler, false));
    sendPipelineReport(nodes.get(nodes.size() - 1), pipeline,
        pipelineReportHandler, true);

    // pipeline is healthy when all dns report
    Assert
        .assertTrue(pipelineManager.getPipeline(pipeline.getId()).isHealthy());
    // pipeline should now move to open state
    Assert
        .assertTrue(pipelineManager.getPipeline(pipeline.getId()).isOpen());

    // close the pipeline
    pipelineManager.closePipeline(pipeline, false);

    // pipeline report for destroyed pipeline should be ignored
    nodes.subList(0, 2).forEach(dn -> sendPipelineReport(dn, pipeline,
        pipelineReportHandler, false));
    sendPipelineReport(nodes.get(nodes.size() - 1), pipeline,
        pipelineReportHandler, true);

    try {
      pipelineManager.getPipeline(pipeline.getId());
      fail("Pipeline should not have been retrieved");
    } catch (PipelineNotFoundException e) {
      // should reach here
    }

    // clean up
    pipelineManager.close();
  }

  @Test
  public void testPipelineCreationFailedMetric() throws Exception {
    PipelineManagerImpl pipelineManager = createPipelineManager(true);

    // No pipeline at start
    MetricsRecordBuilder metrics = getMetrics(
        SCMPipelineMetrics.class.getSimpleName());
    long numPipelineAllocated = getLongCounter("NumPipelineAllocated",
        metrics);
    Assert.assertEquals(0, numPipelineAllocated);

    // 3 DNs are unhealthy.
    // Create 5 pipelines (Use up 15 Datanodes)

    for (int i = 0; i < maxPipelineCount; i++) {
      Pipeline pipeline = pipelineManager
          .createPipeline(RatisReplicationConfig
              .getInstance(ReplicationFactor.THREE));
      Assert.assertNotNull(pipeline);
    }

    metrics = getMetrics(
        SCMPipelineMetrics.class.getSimpleName());
    numPipelineAllocated = getLongCounter("NumPipelineAllocated", metrics);
    Assert.assertEquals(maxPipelineCount, numPipelineAllocated);

    long numPipelineCreateFailed = getLongCounter(
        "NumPipelineCreationFailed", metrics);
    Assert.assertEquals(0, numPipelineCreateFailed);

    //This should fail...
    try {
      pipelineManager
          .createPipeline(RatisReplicationConfig
              .getInstance(ReplicationFactor.THREE));
      fail();
    } catch (SCMException ioe) {
      // pipeline creation failed this time.
      Assert.assertEquals(SCMException.ResultCodes.FAILED_TO_FIND_SUITABLE_NODE,
          ioe.getResult());
    }

    metrics = getMetrics(
        SCMPipelineMetrics.class.getSimpleName());
    numPipelineAllocated = getLongCounter("NumPipelineAllocated", metrics);
    Assert.assertEquals(maxPipelineCount, numPipelineAllocated);

    numPipelineCreateFailed = getLongCounter(
        "NumPipelineCreationFailed", metrics);
    Assert.assertEquals(1, numPipelineCreateFailed);

    // clean up
    pipelineManager.close();
  }

  @Test
  public void testPipelineOpenOnlyWhenLeaderReported() throws Exception {
    SCMHADBTransactionBuffer buffer1 =
        new SCMHADBTransactionBufferStub(dbStore);
    PipelineManagerImpl pipelineManager =
        createPipelineManager(true, buffer1);

    Pipeline pipeline = pipelineManager
        .createPipeline(RatisReplicationConfig
            .getInstance(ReplicationFactor.THREE));
    // close manager
    buffer1.close();
    pipelineManager.close();
    // new pipeline manager loads the pipelines from the db in ALLOCATED state
    pipelineManager = createPipelineManager(true);
    Assert.assertEquals(Pipeline.PipelineState.ALLOCATED,
        pipelineManager.getPipeline(pipeline.getId()).getPipelineState());

    SCMSafeModeManager scmSafeModeManager =
        new SCMSafeModeManager(new OzoneConfiguration(), new ArrayList<>(),
            null, pipelineManager, new EventQueue(),
            serviceManager, scmContext);
    PipelineReportHandler pipelineReportHandler =
        new PipelineReportHandler(scmSafeModeManager, pipelineManager,
            SCMContext.emptyContext(), conf);

    // Report pipelines with leaders
    List<DatanodeDetails> nodes = pipeline.getNodes();
    Assert.assertEquals(3, nodes.size());
    // Send report for all but no leader
    nodes.forEach(dn -> sendPipelineReport(dn, pipeline, pipelineReportHandler,
        false));

    Assert.assertEquals(Pipeline.PipelineState.ALLOCATED,
        pipelineManager.getPipeline(pipeline.getId()).getPipelineState());

    nodes.subList(0, 2).forEach(dn -> sendPipelineReport(dn, pipeline,
        pipelineReportHandler, false));
    sendPipelineReport(nodes.get(nodes.size() - 1), pipeline,
        pipelineReportHandler, true);

    Assert.assertEquals(Pipeline.PipelineState.OPEN,
        pipelineManager.getPipeline(pipeline.getId()).getPipelineState());

    pipelineManager.close();
  }

  @Test
  public void testScrubPipelines() throws Exception {
    // Allocated pipelines should not be scrubbed for 50 seconds.
    conf.setTimeDuration(
        OZONE_SCM_PIPELINE_ALLOCATED_TIMEOUT, 50, TimeUnit.SECONDS);

    PipelineManagerImpl pipelineManager = createPipelineManager(true);
    pipelineManager.setScmContext(scmContext);
    Pipeline allocatedPipeline = pipelineManager
        .createPipeline(RatisReplicationConfig
            .getInstance(ReplicationFactor.THREE));
    // At this point, pipeline is not at OPEN stage.
    Assert.assertEquals(Pipeline.PipelineState.ALLOCATED,
        allocatedPipeline.getPipelineState());

    // pipeline should be seen in pipelineManager as ALLOCATED.
    Assert.assertTrue(pipelineManager
        .getPipelines(RatisReplicationConfig
            .getInstance(ReplicationFactor.THREE),
            Pipeline.PipelineState.ALLOCATED).contains(allocatedPipeline));

    Pipeline closedPipeline = pipelineManager
        .createPipeline(RatisReplicationConfig
            .getInstance(ReplicationFactor.THREE));
    pipelineManager.openPipeline(closedPipeline.getId());
    pipelineManager.closePipeline(closedPipeline, true);

    // pipeline should be seen in pipelineManager as CLOSED.
    Assert.assertTrue(pipelineManager
        .getPipelines(RatisReplicationConfig
                .getInstance(ReplicationFactor.THREE),
            Pipeline.PipelineState.CLOSED).contains(closedPipeline));

    // Set the clock to "now". All pipelines were created before this.
    testClock.set(Instant.now());

    pipelineManager.scrubPipelines();

    // The allocatedPipeline should not be scrubbed as the interval has not
    // yet passed.
    Assert.assertTrue(pipelineManager
        .getPipelines(RatisReplicationConfig
            .getInstance(ReplicationFactor.THREE),
            Pipeline.PipelineState.ALLOCATED).contains(allocatedPipeline));

    // The closedPipeline should be scrubbed, as they are scrubbed immediately
    Assert.assertFalse(pipelineManager
        .getPipelines(RatisReplicationConfig
                .getInstance(ReplicationFactor.THREE),
            Pipeline.PipelineState.CLOSED).contains(closedPipeline));

    testClock.fastForward((60000));

    pipelineManager.scrubPipelines();

    // The allocatedPipeline should now be scrubbed as the interval has passed
    Assert.assertFalse(pipelineManager
        .getPipelines(RatisReplicationConfig
                .getInstance(ReplicationFactor.THREE),
            Pipeline.PipelineState.ALLOCATED).contains(allocatedPipeline));

    pipelineManager.close();
  }

  @Test
  public void testScrubPipelinesShouldFailOnFollower() throws Exception {
    conf.setTimeDuration(
        OZONE_SCM_PIPELINE_ALLOCATED_TIMEOUT, 10, TimeUnit.SECONDS);

    PipelineManagerImpl pipelineManager = createPipelineManager(true);
    pipelineManager.setScmContext(scmContext);
    Pipeline pipeline = pipelineManager
        .createPipeline(RatisReplicationConfig
            .getInstance(ReplicationFactor.THREE));
    // At this point, pipeline is not at OPEN stage.
    Assert.assertEquals(Pipeline.PipelineState.ALLOCATED,
        pipeline.getPipelineState());

    // pipeline should be seen in pipelineManager as ALLOCATED.
    Assert.assertTrue(pipelineManager
        .getPipelines(RatisReplicationConfig
                .getInstance(ReplicationFactor.THREE),
            Pipeline.PipelineState.ALLOCATED).contains(pipeline));

    // Change to follower
    assert pipelineManager.getScmhaManager() instanceof SCMHAManagerStub;
    ((SCMHAManagerStub) pipelineManager.getScmhaManager()).setIsLeader(false);

    testClock.fastForward(20000);
    try {
      pipelineManager.scrubPipelines();
    } catch (NotLeaderException ex) {
      pipelineManager.close();
      return;
    }
    // Should not reach here.
    Assert.fail();
  }

  @Test
  public void testPipelineNotCreatedUntilSafeModePrecheck() throws Exception {
    // No timeout for pipeline scrubber.
    conf.setTimeDuration(
        OZONE_SCM_PIPELINE_ALLOCATED_TIMEOUT, -1,
        TimeUnit.MILLISECONDS);

    scmContext.updateSafeModeStatus(
        new SCMSafeModeManager.SafeModeStatus(true, false));

    PipelineManagerImpl pipelineManager = createPipelineManager(true);
    try {
      pipelineManager
          .createPipeline(RatisReplicationConfig
              .getInstance(ReplicationFactor.THREE));
      fail("Pipelines should not have been created");
    } catch (IOException e) {
      // No pipeline is created.
      Assert.assertTrue(pipelineManager.getPipelines().isEmpty());
    }

    // Ensure a pipeline of factor ONE can be created - no exceptions should be
    // raised.
    Pipeline pipeline = pipelineManager
        .createPipeline(RatisReplicationConfig
            .getInstance(ReplicationFactor.ONE));
    Assert.assertTrue(pipelineManager
        .getPipelines(RatisReplicationConfig
            .getInstance(ReplicationFactor.ONE))
        .contains(pipeline));

    // Simulate safemode check exiting.
    scmContext.updateSafeModeStatus(
        new SCMSafeModeManager.SafeModeStatus(true, true));
    GenericTestUtils.waitFor(new Supplier<Boolean>() {
      @Override
      public Boolean get() {
        return pipelineManager.getPipelines().size() != 0;
      }
    }, 100, 10000);
    pipelineManager.close();
  }

  @Test
  public void testSafeModeUpdatedOnSafemodeExit() throws Exception {
    // No timeout for pipeline scrubber.
    conf.setTimeDuration(
        OZONE_SCM_PIPELINE_ALLOCATED_TIMEOUT, -1,
        TimeUnit.MILLISECONDS);

    PipelineManagerImpl pipelineManager = createPipelineManager(true);

    scmContext.updateSafeModeStatus(
        new SCMSafeModeManager.SafeModeStatus(true, false));
    Assert.assertTrue(pipelineManager.getSafeModeStatus());
    Assert.assertFalse(pipelineManager.isPipelineCreationAllowed());

    // First pass pre-check as true, but safemode still on
    // Simulate safemode check exiting.
    scmContext.updateSafeModeStatus(
        new SCMSafeModeManager.SafeModeStatus(true, true));
    Assert.assertTrue(pipelineManager.getSafeModeStatus());
    Assert.assertTrue(pipelineManager.isPipelineCreationAllowed());

    // Then also turn safemode off
    scmContext.updateSafeModeStatus(
        new SCMSafeModeManager.SafeModeStatus(false, true));
    Assert.assertFalse(pipelineManager.getSafeModeStatus());
    Assert.assertTrue(pipelineManager.isPipelineCreationAllowed());
    pipelineManager.close();
  }

  @Test
  public void testAddContainerWithClosedPipeline() throws Exception {
    GenericTestUtils.LogCapturer logCapturer = GenericTestUtils.LogCapturer.
            captureLogs(LoggerFactory.getLogger(PipelineStateMap.class));
    SCMHADBTransactionBuffer buffer = new SCMHADBTransactionBufferStub(dbStore);
    PipelineManagerImpl pipelineManager =
            createPipelineManager(true, buffer);
    Table<PipelineID, Pipeline> pipelineStore =
            SCMDBDefinition.PIPELINES.getTable(dbStore);
    Pipeline pipeline = pipelineManager.createPipeline(
            RatisReplicationConfig
                .getInstance(HddsProtos.ReplicationFactor.THREE));
    PipelineID pipelineID = pipeline.getId();
    pipelineManager.addContainerToPipeline(pipelineID, ContainerID.valueOf(1));
    pipelineManager.getStateManager().updatePipelineState(
            pipelineID.getProtobuf(), HddsProtos.PipelineState.PIPELINE_CLOSED);
    buffer.flush();
    Assert.assertTrue(pipelineStore.get(pipelineID).isClosed());
    pipelineManager.addContainerToPipelineSCMStart(pipelineID,
            ContainerID.valueOf(2));
    assertTrue(logCapturer.getOutput().contains("Container " +
            ContainerID.valueOf(2) + " in open state for pipeline=" +
            pipelineID + " in closed state"));
  }

  @Test
  public void testPipelineCloseFlow() throws IOException {
    GenericTestUtils.LogCapturer logCapturer = GenericTestUtils.LogCapturer
            .captureLogs(LoggerFactory.getLogger(PipelineManagerImpl.class));
    PipelineManagerImpl pipelineManager = createPipelineManager(true);
    pipelineManager.setScmContext(scmContext);
    Pipeline pipeline = pipelineManager.createPipeline(
            RatisReplicationConfig
                .getInstance(HddsProtos.ReplicationFactor.THREE));
    PipelineID pipelineID = pipeline.getId();
    ContainerManager containerManager = scm.getContainerManager();
    ContainerInfo containerInfo = HddsTestUtils.
        getContainer(HddsProtos.LifeCycleState.CLOSED, pipelineID);
    ContainerID containerID = containerInfo.containerID();
    //Add Container to ContainerMap
    containerManager.getContainerStateManager().
            addContainer(containerInfo.getProtobuf());
    //Add Container to PipelineStateMap
    pipelineManager.addContainerToPipeline(pipelineID, containerID);
    pipelineManager.closePipeline(pipeline, false);
    String containerExpectedOutput = "Container " + containerID +
            " closed for pipeline=" + pipelineID;
    String pipelineExpectedOutput =
        "Pipeline " + pipeline + " moved to CLOSED state";
    String logOutput = logCapturer.getOutput();
    assertTrue(logOutput.contains(containerExpectedOutput));
    assertTrue(logOutput.contains(pipelineExpectedOutput));

    int containerLogIdx = logOutput.indexOf(containerExpectedOutput);
    int pipelineLogIdx = logOutput.indexOf(pipelineExpectedOutput);
    assertTrue(containerLogIdx < pipelineLogIdx);
  }

  @Test
  public void testCreatePipelineForRead() throws IOException {
    PipelineManager pipelineManager = createPipelineManager(true);
    List<DatanodeDetails> dns = nodeManager
        .getNodes(NodeStatus.inServiceHealthy())
        .stream()
        .limit(3)
        .collect(Collectors.toList());
    Set<ContainerReplica> replicas = createContainerReplicasList(dns);
    Pipeline pipeline = pipelineManager.createPipelineForRead(
        RatisReplicationConfig.getInstance(ReplicationFactor.THREE), replicas);
    Assert.assertEquals(3, pipeline.getNodes().size());
    for (DatanodeDetails dn : pipeline.getNodes())  {
      Assert.assertTrue(dns.contains(dn));
    }
  }

  private Set<ContainerReplica> createContainerReplicasList(
      List <DatanodeDetails> dns) {
    Set<ContainerReplica> replicas = new HashSet<>();
    for (DatanodeDetails dn : dns) {
      ContainerReplica r = ContainerReplica.newBuilder()
          .setBytesUsed(1)
          .setContainerID(ContainerID.valueOf(1))
          .setContainerState(StorageContainerDatanodeProtocolProtos
              .ContainerReplicaProto.State.CLOSED)
          .setKeyCount(1)
          .setOriginNodeId(UUID.randomUUID())
          .setSequenceId(1)
          .setReplicaIndex(0)
          .setDatanodeDetails(dn)
          .build();
      replicas.add(r);
    }
    return replicas;
  }

  private void sendPipelineReport(
      DatanodeDetails dn, Pipeline pipeline,
      PipelineReportHandler pipelineReportHandler,
      boolean isLeader) {
    SCMDatanodeHeartbeatDispatcher.PipelineReportFromDatanode report =
        HddsTestUtils.getPipelineReportFromDatanode(dn, pipeline.getId(),
            isLeader);
    pipelineReportHandler.onMessage(report, new EventQueue());
  }
}
