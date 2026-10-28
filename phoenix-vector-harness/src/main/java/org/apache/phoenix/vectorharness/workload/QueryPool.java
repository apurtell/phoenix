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

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;
import org.apache.phoenix.vectorharness.HarnessContext;

/**
 * Runs batches of queries closed loop on a fixed set of worker threads, each with its own
 * {@link QueryExecutor}, so that connections and prepared statements are reused across batches.
 */
public final class QueryPool implements AutoCloseable {

  private final ExecutorService pool;
  private final ThreadLocal<QueryExecutor> executors;
  private final List<QueryExecutor> all = new ArrayList<>();

  public QueryPool(HarnessContext ctx, int threads, String name) {
    this.pool = Executors.newFixedThreadPool(Math.max(1, threads), r -> {
      Thread t = new Thread(r, name);
      t.setDaemon(true);
      return t;
    });
    this.executors = ThreadLocal.withInitial(() -> {
      QueryExecutor e = new QueryExecutor(ctx);
      synchronized (all) {
        all.add(e);
      }
      return e;
    });
  }

  /** Returns the executor of the calling worker thread. */
  public QueryExecutor executor() {
    return executors.get();
  }

  /**
   * Runs every query once and waits for all of them; the consumer receives each query and its
   * result on the worker thread.
   */
  public void runAll(List<QuerySpec> specs, BiConsumer<QuerySpec, QueryExecutor.Result> consumer)
    throws InterruptedException {
    List<Future<?>> futures = new ArrayList<>(specs.size());
    for (QuerySpec q : specs) {
      futures.add(pool.submit(() -> {
        QueryExecutor.Result r = executors.get().execute(q, 0);
        consumer.accept(q, r);
      }));
    }
    for (Future<?> f : futures) {
      try {
        f.get();
      } catch (java.util.concurrent.ExecutionException e) {
        throw new IllegalStateException("Query worker failed", e.getCause());
      }
    }
  }

  @Override
  public void close() {
    pool.shutdown();
    try {
      pool.awaitTermination(60, TimeUnit.SECONDS);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
    pool.shutdownNow();
    synchronized (all) {
      for (QueryExecutor e : all) {
        e.close();
      }
      all.clear();
    }
  }
}
