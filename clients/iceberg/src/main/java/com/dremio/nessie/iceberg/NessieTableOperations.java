/*
 * Copyright (C) 2020 Dremio
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.dremio.nessie.iceberg;

import org.apache.hadoop.conf.Configuration;
import org.apache.iceberg.BaseMetastoreTableOperations;
import org.apache.iceberg.TableMetadata;
import org.apache.iceberg.exceptions.CommitFailedException;
import org.apache.iceberg.hadoop.HadoopFileIO;
import org.apache.iceberg.io.FileIO;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.dremio.nessie.client.NessieClient;
import com.dremio.nessie.error.NessieNotFoundException;
import com.dremio.nessie.model.Contents;
import com.dremio.nessie.model.IcebergTable;
import com.dremio.nessie.model.ImmutableIcebergTable;
import com.dremio.nessie.model.ImmutablePutContents;
import com.dremio.nessie.model.NessieObjectKey;

/**
 * Nessie implementation of Iceberg TableOperations.
 */
public class NessieTableOperations extends BaseMetastoreTableOperations {

  private static final Logger logger = LoggerFactory.getLogger(NessieTableOperations.class);

  private final Configuration conf;
  private final NessieClient client;
  private final NessieObjectKey key;
  private UpdateableReference reference;
  private IcebergTable table;
  private HadoopFileIO fileIO;

  /**
   * Create a nessie table operations given a table identifier.
   */
  public NessieTableOperations(
      Configuration conf,
      NessieObjectKey key,
      UpdateableReference reference,
      NessieClient client) {
    this.conf = conf;
    this.key = key;
    this.reference = reference;
    this.client = client;
  }

  @Override
  protected void doRefresh() {
    // break reference with parent (to avoid cross-over refresh)
    // TODO, confirm this is correct behavior.
    reference = reference.clone();

    if (reference.refresh()) {
      boolean failed = false;
      try {
        Contents c = client.getContentsApi().getObjectForReference(reference.getHash(), key);
        if (!(c instanceof IcebergTable)) {
          failed = true;
        }
        this.table = (IcebergTable) c;
        refreshFromMetadataLocation(table.getMetadataLocation(), 2);
      } catch (NessieNotFoundException ex) {
        failed = true;
      }

      if (failed) {
        table = null;
        fileIO = null;
      }
    }
  }

  @Override
  protected void doCommit(TableMetadata base, TableMetadata metadata) {
    reference.checkMutable();

    String newMetadataLocation = writeNewMetadata(metadata, currentVersion() + 1);

    try {
      IcebergTable table = ImmutableIcebergTable.builder().metadataLocation(newMetadataLocation).build();
      client.getContentsApi().setContents(key, "iceberg commit",
          ImmutablePutContents.builder().branch(reference.getAsBranch()).contents(table).build());
    } catch (Throwable e) {
      io().deleteFile(newMetadataLocation);
      throw new CommitFailedException(e, "failed");
    }
  }

  @Override
  public FileIO io() {
    if (fileIO == null) {
      fileIO = new HadoopFileIO(conf);
    }

    return fileIO;
  }

}
