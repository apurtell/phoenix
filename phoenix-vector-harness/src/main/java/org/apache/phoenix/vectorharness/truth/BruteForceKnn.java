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

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongConsumer;
import org.apache.phoenix.vectorharness.config.Metric;
import org.apache.phoenix.vectorharness.corpus.DocAttributes;

/**
 * Exact k nearest neighbors by a double precision linear scan, independent of Phoenix and JVector.
 * One scan serves every panel query and every condition: each row's distance to a query is computed
 * once, and the row is offered to the accumulator of each condition of that query that it passes.
 * Worker threads scan disjoint blocks of ordinals with private accumulators, which are merged at
 * the end; the total order of the accumulators makes the result independent of the thread count and
 * the block order.
 */
public final class BruteForceKnn {

  static final int BLOCK = 2048;

  private final Metric metric;
  private final int threads;

  public BruteForceKnn(Metric metric, int threads) {
    this.metric = metric;
    this.threads = Math.max(1, threads);
  }

  /**
   * Computes the neighbor lists.
   * @param queries    the query vectors
   * @param conditions the conditions of each query, in the same order
   * @param rows       the rows to scan
   * @param capacity   the length of each list
   * @param progress   receives the number of rows scanned so far, or null
   * @return one map per query from condition key to neighbor list
   */
  public List<Map<String, NeighborList>> compute(List<float[]> queries,
    List<List<QueryCondition>> conditions, RowSource rows, int capacity, LongConsumer progress) {
    if (queries.size() != conditions.size()) {
      throw new IllegalArgumentException("queries and conditions differ in length");
    }
    final int q = queries.size();
    final float[][] qv = queries.toArray(new float[0][]);
    final QueryCondition[][] conds = new QueryCondition[q][];
    for (int i = 0; i < q; i++) {
      conds[i] = conditions.get(i).toArray(new QueryCondition[0]);
    }
    final long limit = rows.ordinalLimit();
    final AtomicLong nextBlock = new AtomicLong();
    final AtomicLong scanned = new AtomicLong();
    int workers = (int) Math.max(1, Math.min(threads, (limit + BLOCK - 1) / BLOCK));
    ExecutorService pool = Executors.newFixedThreadPool(workers, r -> {
      Thread t = new Thread(r, "truth-scan");
      t.setDaemon(true);
      return t;
    });
    List<Future<TopK[][]>> futures = new ArrayList<>();
    try {
      for (int w = 0; w < workers; w++) {
        futures.add(pool.submit(() -> {
          TopK[][] acc = new TopK[q][];
          for (int i = 0; i < q; i++) {
            acc[i] = new TopK[conds[i].length];
            for (int c = 0; c < conds[i].length; c++) {
              acc[i][c] = new TopK(capacity);
            }
          }
          long b;
          while ((b = nextBlock.getAndIncrement()) * BLOCK < limit) {
            long from = b * BLOCK;
            long to = Math.min(limit, from + BLOCK);
            rows.scan(from, to,
              (ordinal, vector, attrs) -> offer(qv, conds, acc, ordinal, vector, attrs));
            long s = scanned.addAndGet(to - from);
            if (progress != null) {
              progress.accept(s);
            }
          }
          return acc;
        }));
      }
      TopK[][] total = null;
      for (Future<TopK[][]> f : futures) {
        TopK[][] acc = f.get();
        if (total == null) {
          total = acc;
        } else {
          for (int i = 0; i < q; i++) {
            for (int c = 0; c < conds[i].length; c++) {
              total[i][c].merge(acc[i][c]);
            }
          }
        }
      }
      List<Map<String, NeighborList>> out = new ArrayList<>(q);
      for (int i = 0; i < q; i++) {
        Map<String, NeighborList> m = new LinkedHashMap<>();
        for (int c = 0; c < conds[i].length; c++) {
          m.put(conds[i][c].key(), total[i][c].toList());
        }
        out.add(m);
      }
      return out;
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("Interrupted while computing ground truth", e);
    } catch (ExecutionException e) {
      throw new IllegalStateException("Ground truth scan failed", e.getCause());
    } finally {
      pool.shutdownNow();
    }
  }

  private void offer(float[][] qv, QueryCondition[][] conds, TopK[][] acc, long ordinal,
    float[] vector, DocAttributes attrs) {
    for (int i = 0; i < qv.length; i++) {
      QueryCondition[] cs = conds[i];
      double d = Double.NaN;
      for (int c = 0; c < cs.length; c++) {
        QueryCondition cond = cs[c];
        if (!cond.test(attrs)) {
          continue;
        }
        if (Double.isNaN(d)) {
          d = metric.distance(qv[i], vector);
        }
        if (cond.withinRadius(d)) {
          acc[i][c].offer(ordinal, d);
        }
      }
    }
  }

  /** Computes the neighbors of one query under one condition. */
  public NeighborList single(float[] query, QueryCondition condition, RowSource rows,
    int capacity) {
    List<float[]> qs = new ArrayList<>(1);
    qs.add(query);
    List<List<QueryCondition>> cs = new ArrayList<>(1);
    List<QueryCondition> one = new ArrayList<>(1);
    one.add(condition);
    cs.add(one);
    return compute(qs, cs, rows, capacity, null).get(0).get(condition.key());
  }
}
