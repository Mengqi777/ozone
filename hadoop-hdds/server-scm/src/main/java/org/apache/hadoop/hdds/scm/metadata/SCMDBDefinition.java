/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 *  with the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.hadoop.hdds.scm.metadata;

import java.math.BigInteger;
import java.security.cert.X509Certificate;

import com.google.protobuf.ByteString;
import org.apache.hadoop.hdds.protocol.proto.StorageContainerDatanodeProtocolProtos.DeletedBlocksTransaction;
import org.apache.hadoop.hdds.scm.ScmConfigKeys;
import org.apache.hadoop.hdds.scm.container.ContainerID;
import org.apache.hadoop.hdds.scm.container.ContainerInfo;
import org.apache.hadoop.hdds.scm.container.common.helpers.MoveDataNodePair;
import org.apache.hadoop.hdds.security.x509.certificate.CertInfo;
import org.apache.hadoop.hdds.security.x509.crl.CRLInfoCodec;
import org.apache.hadoop.hdds.utils.TransactionInfo;
import org.apache.hadoop.hdds.security.x509.crl.CRLInfo;
import org.apache.hadoop.hdds.scm.pipeline.Pipeline;
import org.apache.hadoop.hdds.scm.pipeline.PipelineID;
import org.apache.hadoop.hdds.utils.TransactionInfoCodec;
import org.apache.hadoop.hdds.utils.db.DBColumnFamilyDefinition;
import org.apache.hadoop.hdds.utils.db.DBDefinition;
import org.apache.hadoop.hdds.utils.db.LongCodec;
import org.apache.hadoop.hdds.utils.db.StringCodec;

/**
 * Class defines the structure and types of the scm.db.
 */
public class SCMDBDefinition implements DBDefinition {

  public static final DBColumnFamilyDefinition<Long, DeletedBlocksTransaction>
      DELETED_BLOCKS =
      new DBColumnFamilyDefinition<>(
          "deletedBlocks",
          Long.class,
          new LongCodec(),
          DeletedBlocksTransaction.class,
          new DeletedBlocksTransactionCodec());

  public static final DBColumnFamilyDefinition<BigInteger, X509Certificate>
      VALID_CERTS =
      new DBColumnFamilyDefinition<>(
          "validCerts",
          BigInteger.class,
          new BigIntegerCodec(),
          X509Certificate.class,
          new X509CertificateCodec());

  public static final DBColumnFamilyDefinition<BigInteger, X509Certificate>
      VALID_SCM_CERTS =
      new DBColumnFamilyDefinition<>(
          "validSCMCerts",
          BigInteger.class,
          new BigIntegerCodec(),
          X509Certificate.class,
          new X509CertificateCodec());

  /**
   * This column family is Deprecated in favor of REVOKED_CERTS_V2.
   */
  @Deprecated
  public static final DBColumnFamilyDefinition<BigInteger, X509Certificate>
      REVOKED_CERTS =
      new DBColumnFamilyDefinition<>(
          "revokedCerts",
          BigInteger.class,
          new BigIntegerCodec(),
          X509Certificate.class,
          new X509CertificateCodec());

  public static final DBColumnFamilyDefinition<BigInteger, CertInfo>
      REVOKED_CERTS_V2 =
      new DBColumnFamilyDefinition<>(
          "revokedCertsV2",
          BigInteger.class,
          new BigIntegerCodec(),
          CertInfo.class,
          new CertInfoCodec());

  public static final DBColumnFamilyDefinition<PipelineID, Pipeline>
      PIPELINES =
      new DBColumnFamilyDefinition<>(
          "pipelines",
          PipelineID.class,
          new PipelineIDCodec(),
          Pipeline.class,
          new PipelineCodec());

  public static final DBColumnFamilyDefinition<ContainerID, ContainerInfo>
      CONTAINERS =
      new DBColumnFamilyDefinition<>(
          "containers",
          ContainerID.class,
          new ContainerIDCodec(),
          ContainerInfo.class,
          new ContainerInfoCodec());

  public static final DBColumnFamilyDefinition<String, TransactionInfo>
      TRANSACTIONINFO =
      new DBColumnFamilyDefinition<>(
          "scmTransactionInfos",
          String.class,
          new StringCodec(),
          TransactionInfo.class,
          new TransactionInfoCodec());

  public static final DBColumnFamilyDefinition<Long, CRLInfo> CRLS =
      new DBColumnFamilyDefinition<>(
          "crls",
          Long.class,
          new LongCodec(),
          CRLInfo.class,
          new CRLInfoCodec());

  public static final DBColumnFamilyDefinition<String, Long>
      CRL_SEQUENCE_ID =
      new DBColumnFamilyDefinition<>(
          "crlSequenceId",
          String.class,
          new StringCodec(),
          Long.class,
          new LongCodec());

  public static final DBColumnFamilyDefinition<String, Long>
      SEQUENCE_ID =
      new DBColumnFamilyDefinition<>(
          "sequenceId",
          String.class,
          new StringCodec(),
          Long.class,
          new LongCodec());

  public static final DBColumnFamilyDefinition<ContainerID,
      MoveDataNodePair>
      MOVE =
      new DBColumnFamilyDefinition<>(
          "move",
          ContainerID.class,
          new ContainerIDCodec(),
          MoveDataNodePair.class,
          new MoveDataNodePairCodec());

  public static final DBColumnFamilyDefinition<String, ByteString>
      STATEFUL_SERVICE_CONFIG =
      new DBColumnFamilyDefinition<>(
          "statefulServiceConfig",
          String.class,
          new StringCodec(),
          ByteString.class,
          new ByteStringCodec());

  @Override
  public String getName() {
    return "scm.db";
  }

  @Override
  public String getLocationConfigKey() {
    return ScmConfigKeys.OZONE_SCM_DB_DIRS;
  }

  @Override
  public DBColumnFamilyDefinition[] getColumnFamilies() {
    return new DBColumnFamilyDefinition[] {DELETED_BLOCKS, VALID_CERTS,
        VALID_SCM_CERTS, REVOKED_CERTS, REVOKED_CERTS_V2, PIPELINES, CONTAINERS,
        TRANSACTIONINFO, CRLS, CRL_SEQUENCE_ID, SEQUENCE_ID, MOVE,
        STATEFUL_SERVICE_CONFIG};
  }
}
