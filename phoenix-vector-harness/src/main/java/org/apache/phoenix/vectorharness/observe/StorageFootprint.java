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
package org.apache.phoenix.vectorharness.observe;

import java.io.IOException;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FileStatus;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.hbase.TableName;
import org.apache.hadoop.hbase.client.Admin;
import org.apache.hadoop.hbase.util.CommonFSUtils;
import org.apache.phoenix.jdbc.PhoenixConnection;
import org.apache.phoenix.vectorharness.HarnessContext;

/**
 * Measures the bytes that a table occupies on the cluster filesystem: the store files of each
 * column family under the table directory, and the MOB files under the MOB directory of the table,
 * where HNSW segment payloads live. Vector index metrics leave storage out on purpose, so the
 * footprint comes from the filesystem. Data in memstores is not counted; callers that want it
 * counted flush first.
 */
public final class StorageFootprint {

  static final String MOB_DIR = "mobdir";

  private StorageFootprint() {
  }

  /** Returns the physical HBase table name of a Phoenix table or index. */
  public static TableName physicalName(Connection conn, String fullName) throws SQLException {
    return TableName.valueOf(
      conn.unwrap(PhoenixConnection.class).getTableNoCache(fullName).getPhysicalName().getBytes());
  }

  /** Flushes a table, so that its memstores reach store files. */
  public static void flush(Connection conn, String fullName) throws SQLException, IOException {
    try (Admin admin = HarnessContext.admin(conn)) {
      admin.flush(physicalName(conn, fullName));
    }
  }

  /**
   * Measures a table.
   * @return total, families (bytes by family), and mob (bytes of MOB files)
   */
  public static Map<String, Object> measure(Connection conn, String fullName)
    throws SQLException, IOException {
    Configuration conf = HarnessContext.configuration(conn);
    TableName tn = physicalName(conn, fullName);
    Path root = CommonFSUtils.getRootDir(conf);
    FileSystem fs = root.getFileSystem(conf);
    Path tableDir = CommonFSUtils.getTableDir(root, tn);
    Map<String, Long> families = new TreeMap<>();
    long storeBytes = 0;
    int regions = 0;
    if (!fs.exists(tableDir)) {
      // A missing directory means the configuration does not name the cluster's filesystem, not
      // an empty table; report no total rather than a false zero.
      Map<String, Object> m = new LinkedHashMap<>();
      m.put("table", tn.getNameAsString());
      m.put("error", "No table directory " + tableDir + "; the client configuration may not"
        + " name the cluster's hbase.rootdir");
      return m;
    }
    for (FileStatus region : fs.listStatus(tableDir)) {
      String name = region.getPath().getName();
      if (!region.isDirectory() || name.startsWith(".")) {
        continue;
      }
      regions++;
      for (FileStatus family : fs.listStatus(region.getPath())) {
        String f = family.getPath().getName();
        if (!family.isDirectory() || f.startsWith(".") || f.equals("recovered.edits")) {
          continue;
        }
        long bytes = fs.getContentSummary(family.getPath()).getLength();
        families.merge(f, bytes, Long::sum);
        storeBytes += bytes;
      }
    }
    Path mobDir =
      new Path(new Path(new Path(new Path(root, MOB_DIR), "data"), tn.getNamespaceAsString()),
        tn.getQualifierAsString());
    long mobBytes = fs.exists(mobDir) ? fs.getContentSummary(mobDir).getLength() : 0;
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("table", tn.getNameAsString());
    m.put("regions_on_disk", regions);
    m.put("store_bytes", storeBytes);
    m.put("families", new LinkedHashMap<String, Object>(families));
    m.put("mob_bytes", mobBytes);
    m.put("total_bytes", storeBytes + mobBytes);
    return m;
  }
}
