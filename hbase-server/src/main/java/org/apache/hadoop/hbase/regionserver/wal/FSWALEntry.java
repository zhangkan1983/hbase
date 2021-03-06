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
package org.apache.hadoop.hbase.regionserver.wal;


import com.google.common.collect.Sets;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Set;

import org.apache.hadoop.hbase.Cell;
import org.apache.hadoop.hbase.CellUtil;
import org.apache.hadoop.hbase.HRegionInfo;
import org.apache.hadoop.hbase.classification.InterfaceAudience;
import org.apache.hadoop.hbase.regionserver.MultiVersionConcurrencyControl;
import org.apache.hadoop.hbase.util.Bytes;
import org.apache.hadoop.hbase.util.CollectionUtils;
import org.apache.hadoop.hbase.wal.WAL.Entry;
import org.apache.hadoop.hbase.wal.WALKey;

/**
 * A WAL Entry for {@link AbstractFSWAL} implementation.  Immutable.
 * A subclass of {@link Entry} that carries extra info across the ring buffer such as
 * region sequence id (we want to use this later, just before we write the WAL to ensure region
 * edits maintain order).  The extra info added here is not 'serialized' as part of the WALEdit
 * hence marked 'transient' to underline this fact.  It also adds mechanism so we can wait on
 * the assign of the region sequence id.  See #stampRegionSequenceId().
 */
@InterfaceAudience.Private
class FSWALEntry extends Entry {
  // The below data members are denoted 'transient' just to highlight these are not persisted;
  // they are only in memory and held here while passing over the ring buffer.
  private final transient long txid;
  private final transient boolean inMemstore;
  private final transient HRegionInfo hri;
  private final transient Set<byte[]> familyNames;
  // In the new WAL logic, we will rewrite failed WAL entries to new WAL file, so we need to avoid
  // calling stampRegionSequenceId again.
  private transient boolean stamped = false;

  FSWALEntry(final long txid, final WALKey key, final WALEdit edit,
      final HRegionInfo hri, final boolean inMemstore) {
    super(key, edit);
    this.inMemstore = inMemstore;
    this.hri = hri;
    this.txid = txid;
    if (inMemstore) {
      // construct familyNames here to reduce the work of log sinker.
      ArrayList<Cell> cells = this.getEdit().getCells();
      if (CollectionUtils.isEmpty(cells)) {
        this.familyNames = Collections.<byte[]> emptySet();
      } else {
        Set<byte[]> familySet = Sets.newTreeSet(Bytes.BYTES_COMPARATOR);
        for (Cell cell : cells) {
          if (!CellUtil.matchingFamily(cell, WALEdit.METAFAMILY)) {
            // TODO: Avoid this clone?
            familySet.add(CellUtil.cloneFamily(cell));
          }
        }
        this.familyNames = Collections.unmodifiableSet(familySet);
      }
    } else {
      this.familyNames = Collections.<byte[]> emptySet();
    }
  }

  public String toString() {
    return "sequence=" + this.txid + ", " + super.toString();
  };

  boolean isInMemstore() {
    return this.inMemstore;
  }

  HRegionInfo getHRegionInfo() {
    return this.hri;
  }

  /**
   * @return The transaction id of this edit.
   */
  long getTxid() {
    return this.txid;
  }

  /**
   * Here is where a WAL edit gets its sequenceid.
   * SIDE-EFFECT is our stamping the sequenceid into every Cell AND setting the sequenceid into the
   * MVCC WriteEntry!!!!
   * @return The sequenceid we stamped on this edit.
   */
  long stampRegionSequenceId() throws IOException {
    if (stamped) {
      return getKey().getSequenceId();
    }
    stamped = true;
    long regionSequenceId = WALKey.NO_SEQUENCE_ID;
    WALKey key = getKey();
    MultiVersionConcurrencyControl.WriteEntry we = key.getPreAssignedWriteEntry();
    boolean preAssigned = (we != null);
    if (!preAssigned) {
      MultiVersionConcurrencyControl mvcc = key.getMvcc();
      if (mvcc != null) {
        we = mvcc.begin();
      }
    }
    if (we != null) {
      regionSequenceId = we.getWriteNumber();
    }

    if (!this.getEdit().isReplay() && inMemstore) {
      for (Cell c:getEdit().getCells()) {
        CellUtil.setSequenceId(c, regionSequenceId);
      }
    }
    if (!preAssigned) {
      key.setWriteEntry(we);
    }
    return regionSequenceId;
  }

  /**
   * @return the family names which are effected by this edit.
   */
  Set<byte[]> getFamilyNames() {
    return familyNames;
  }
}
