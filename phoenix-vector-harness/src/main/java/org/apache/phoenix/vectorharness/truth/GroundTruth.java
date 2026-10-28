/*
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
package org.apache.phoenix.vectorharness.truth;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * Serialized cache of exact nearest neighbor lists for panel queries and condition keys over a
 * specific row population and metric.
 */
public final class GroundTruth {

  private static final int MAGIC = 0x56484754; // "VHGT"
  private static final int VERSION = 1;

  private final String fingerprint;
  private final String panelDigest;
  private final long rows;
  private final String metric;
  private final int capacity;
  private final List<Map<String, NeighborList>> lists;

  public GroundTruth(String fingerprint, String panelDigest, long rows, String metric, int capacity,
    List<Map<String, NeighborList>> lists) {
    this.fingerprint = fingerprint;
    this.panelDigest = panelDigest;
    this.rows = rows;
    this.metric = metric;
    this.capacity = capacity;
    List<Map<String, NeighborList>> copy = new ArrayList<>(lists.size());
    for (Map<String, NeighborList> m : lists) {
      copy.add(Collections.unmodifiableMap(new LinkedHashMap<>(m)));
    }
    this.lists = Collections.unmodifiableList(copy);
  }

  public String fingerprint() {
    return fingerprint;
  }

  public String panelDigest() {
    return panelDigest;
  }

  /** Returns the base row count over which the truth was computed. */
  public long rows() {
    return rows;
  }

  public String metric() {
    return metric;
  }

  /** Returns the maximum neighbor capacity per list. */
  public int capacity() {
    return capacity;
  }

  /** Returns the number of panel queries. */
  public int queries() {
    return lists.size();
  }

  /** Returns the neighbor list for a query and condition, or null if uncomputed. */
  public NeighborList get(int query, String conditionKey) {
    if (query < 0 || query >= lists.size()) {
      return null;
    }
    return lists.get(query).get(conditionKey);
  }

  /** Returns all condition neighbor lists for a panel query. */
  public Map<String, NeighborList> forQuery(int query) {
    return lists.get(query);
  }

  /** Returns true if this ground truth instance matches the given execution parameters. */
  public boolean matches(String fingerprint, String panelDigest, long rows, String metric,
    int capacity) {
    return this.fingerprint.equals(fingerprint) && this.panelDigest.equals(panelDigest)
      && this.rows == rows && this.metric.equals(metric) && this.capacity >= capacity;
  }

  /** Atomically serializes the ground truth dataset to disk with gzip compression. */
  public void write(Path file) throws IOException {
    Files.createDirectories(file.toAbsolutePath().getParent());
    Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
    try (DataOutputStream out = new DataOutputStream(
      new BufferedOutputStream(new GZIPOutputStream(Files.newOutputStream(tmp)), 1 << 16))) {
      out.writeInt(MAGIC);
      out.writeInt(VERSION);
      out.writeUTF(fingerprint);
      out.writeUTF(panelDigest);
      out.writeLong(rows);
      out.writeUTF(metric);
      out.writeInt(capacity);
      out.writeInt(lists.size());
      for (Map<String, NeighborList> m : lists) {
        out.writeInt(m.size());
        for (Map.Entry<String, NeighborList> e : m.entrySet()) {
          out.writeUTF(e.getKey());
          NeighborList l = e.getValue();
          out.writeBoolean(l.complete());
          out.writeInt(l.size());
          for (int i = 0; i < l.size(); i++) {
            out.writeLong(l.ordinal(i));
            out.writeDouble(l.distance(i));
          }
        }
      }
    }
    Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
  }

  /** Deserializes a ground truth dataset from disk. */
  public static GroundTruth read(Path file) throws IOException {
    try (DataInputStream in = new DataInputStream(
      new BufferedInputStream(new GZIPInputStream(Files.newInputStream(file)), 1 << 16))) {
      if (in.readInt() != MAGIC) {
        throw new IOException("Not a ground truth file: " + file);
      }
      int version = in.readInt();
      if (version != VERSION) {
        throw new IOException("Unsupported ground truth version " + version + " in " + file);
      }
      String fp = in.readUTF();
      String pd = in.readUTF();
      long rows = in.readLong();
      String metric = in.readUTF();
      int capacity = in.readInt();
      int q = in.readInt();
      List<Map<String, NeighborList>> lists = new ArrayList<>(q);
      for (int i = 0; i < q; i++) {
        int n = in.readInt();
        Map<String, NeighborList> m = new LinkedHashMap<>();
        for (int j = 0; j < n; j++) {
          String key = in.readUTF();
          boolean complete = in.readBoolean();
          int size = in.readInt();
          long[] o = new long[size];
          double[] d = new double[size];
          for (int x = 0; x < size; x++) {
            o[x] = in.readLong();
            d[x] = in.readDouble();
          }
          m.put(key, new NeighborList(o, d, complete));
        }
        lists.add(m);
      }
      return new GroundTruth(fp, pd, rows, metric, capacity, lists);
    }
  }
}
