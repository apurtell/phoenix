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
package org.apache.phoenix.vectorharness.workload;

import java.io.BufferedWriter;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.phoenix.mapreduce.CsvBulkLoadTool;
import org.apache.phoenix.vectorharness.HarnessContext;
import org.apache.phoenix.vectorharness.config.TableLayout;
import org.apache.phoenix.vectorharness.corpus.Corpus;
import org.apache.phoenix.vectorharness.corpus.DocAttributes;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The bulk load alternative of the load phase. The MapReduce CSV bulk load tool stages HFiles
 * directly and bypasses the region observers, so it populates no index structures inline; for HNSW
 * the build then rebuilds every region from its rows. The bulk load tools read no VECTOR column, so
 * DOC_PROFILE, whose vector is inside its BSON document, is bulk loaded from CSV with the document
 * as extended JSON, and DOC_CORPUS falls back to JDBC batches.
 */
public final class BulkLoader {

  private static final Logger LOG = LoggerFactory.getLogger(BulkLoader.class);

  private final HarnessContext ctx;

  public BulkLoader(HarnessContext ctx) {
    this.ctx = ctx;
  }

  /** Loads base rows [from, to) of every layout; returns facts for the load state. */
  public Map<String, Object> load(long from, long to) throws Exception {
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("mode", "bulkload");
    if (ctx.config().layouts().contains(TableLayout.CORPUS)) {
      Loader.Result r = new Loader(ctx, Collections.singleton(TableLayout.CORPUS),
        ctx.config().ingestThreads(), ctx.config().ingestBatchSize(), 0).load(from, to, null);
      Map<String, Object> corpus = new LinkedHashMap<>(r.toMap());
      corpus.put("mode", "jdbc (bulk load tools read no VECTOR column)");
      m.put(TableLayout.CORPUS.baseName(), corpus);
    }
    if (ctx.config().layouts().contains(TableLayout.PROFILE)) {
      m.put(TableLayout.PROFILE.baseName(), bulkLoadProfile(from, to));
    }
    return m;
  }

  private Map<String, Object> bulkLoadProfile(long from, long to) throws Exception {
    Map<String, Object> m = new LinkedHashMap<>();
    long start = System.nanoTime();
    Corpus corpus = ctx.corpus();
    try (Connection conn = ctx.connection()) {
      Configuration conf = new Configuration(HarnessContext.configuration(conn));
      FileSystem fs = FileSystem.get(conf);
      Path dir = new Path("/tmp/vector-harness-bulkload/" + ctx.tables().table(TableLayout.PROFILE)
        + "-" + System.currentTimeMillis());
      Path file = new Path(dir, "part-00000.csv");
      try (BufferedWriter w = new BufferedWriter(
        new OutputStreamWriter(fs.create(file), StandardCharsets.UTF_8), 1 << 20)) {
        for (long i = from; i < to; i++) {
          DocAttributes a = corpus.attributes(i);
          String doc = profileJson(a, corpus.vector(i));
          w.write(a.tenantId());
          w.write(',');
          w.write(a.docId());
          w.write(",\"");
          w.write(doc.replace("\"", "\"\""));
          w.write("\"\n");
        }
      }
      m.put("csv_seconds", (System.nanoTime() - start) / 1e9);
      long toolStart = System.nanoTime();
      CsvBulkLoadTool tool = new CsvBulkLoadTool();
      tool.setConf(conf);
      int status = tool.run(new String[] { "--input", dir.toString(), "--schema",
        ctx.tables().schema(), "--table", ctx.tables().table(TableLayout.PROFILE) });
      m.put("tool_status", status);
      m.put("tool_seconds", (System.nanoTime() - toolStart) / 1e9);
      if (status != 0) {
        throw new IllegalStateException("CsvBulkLoadTool failed with status " + status);
      }
      try {
        fs.delete(dir, true);
      } catch (Exception e) {
        LOG.debug("Removing {}", dir, e);
      }
    }
    m.put("rows", to - from);
    m.put("seconds", (System.nanoTime() - start) / 1e9);
    return m;
  }

  /** Returns the DOC_PROFILE document of a row as extended JSON, as the CSV loader reads it. */
  String profileJson(DocAttributes a, float[] vector) {
    org.bson.BsonDocument doc = new org.bson.BsonDocument();
    doc.put("category",
      new org.bson.BsonString(ctx.corpus().attributeModel().categories().term(a.category())));
    doc.put("acl_tag",
      new org.bson.BsonString(ctx.corpus().attributeModel().aclTags().term(a.aclTag())));
    doc.put("source",
      new org.bson.BsonString(ctx.corpus().attributeModel().sources().term(a.source())));
    doc.put("search", new org.bson.BsonDocument("embedding",
      new org.bson.BsonBinary(org.bson.BinaryVector.floatVector(vector))));
    return doc.toJson();
  }
}
