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

import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import org.apache.phoenix.vectorharness.HarnessContext;
import org.apache.phoenix.vectorharness.config.TableLayout;
import org.apache.phoenix.vectorharness.corpus.Corpus;
import org.apache.phoenix.vectorharness.corpus.DocAttributes;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Ingests base rows of the corpus through concurrent JDBC batches of parameterized UPSERT
 * statements. Workers claim batches of consecutive ordinals, generate each row from the corpus,
 * write it to every layout, and commit the batch. Because rows are a function of their ordinals,
 * the result does not depend on the thread count or the batch size. A batch that fails is retried a
 * few times before the load fails. An optional rate throttles streaming ingest.
 */
public final class Loader {

  private static final Logger LOG = LoggerFactory.getLogger(Loader.class);
  static final int RETRIES = 3;

  /**
   * Receives each committed batch of ordinals [from, to). Inside the write gate, a batch first
   * starts, then commits, and finishes whether or not it committed.
   */
  public interface BatchListener {
    void committed(long from, long to);

    /** Called before the first write of a batch. */
    default void starting(long from, long to) {
    }

    /** Called after a batch committed and its listener returned, or after it failed. */
    default void finished(long from, long to) {
    }
  }

  /** The outcome of a load. */
  public static final class Result {
    private final long rows;
    private final long batches;
    private final long retries;
    private final double seconds;

    Result(long rows, long batches, long retries, double seconds) {
      this.rows = rows;
      this.batches = batches;
      this.retries = retries;
      this.seconds = seconds;
    }

    public long rows() {
      return rows;
    }

    public long batches() {
      return batches;
    }

    public long retries() {
      return retries;
    }

    public double seconds() {
      return seconds;
    }

    public double rowsPerSecond() {
      return seconds > 0 ? rows / seconds : Double.NaN;
    }

    public Map<String, Object> toMap() {
      Map<String, Object> m = new LinkedHashMap<>();
      m.put("rows", rows);
      m.put("batches", batches);
      m.put("retries", retries);
      m.put("seconds", seconds);
      m.put("rows_per_second", rowsPerSecond());
      return m;
    }
  }

  private final HarnessContext ctx;
  private final Set<TableLayout> layouts;
  private final int threads;
  private final int batchSize;
  private final Throttle throttle;
  private final AtomicBoolean stopped = new AtomicBoolean();
  private volatile WriteGate gate;

  public Loader(HarnessContext ctx, Set<TableLayout> layouts, int threads, int batchSize,
    double ratePerSec) {
    this.ctx = ctx;
    this.layouts = layouts;
    this.threads = Math.max(1, threads);
    this.batchSize = Math.max(1, batchSize);
    this.throttle = new Throttle(ratePerSec);
  }

  /**
   * Makes every batch pass through a write gate, from its first write to the return of its
   * listener, so that quiesced sampling also holds streaming ingest.
   */
  public Loader withGate(WriteGate g) {
    this.gate = g;
    return this;
  }

  /** Asks a running load to stop after its current batches. */
  public void stop() {
    stopped.set(true);
  }

  /** Loads the base rows [from, to). */
  public Result load(long from, long to, BatchListener listener)
    throws SQLException, InterruptedException {
    final Corpus corpus = ctx.corpus();
    final AtomicLong next = new AtomicLong(from);
    final AtomicLong rows = new AtomicLong();
    final AtomicLong batches = new AtomicLong();
    final AtomicLong retries = new AtomicLong();
    final AtomicLong reported = new AtomicLong(System.nanoTime());
    long start = System.nanoTime();
    int workers = (int) Math.max(1, Math.min(threads, (to - from + batchSize - 1) / batchSize));
    ExecutorService pool = Executors.newFixedThreadPool(workers, r -> {
      Thread t = new Thread(r, "vh-load");
      t.setDaemon(true);
      return t;
    });
    List<Future<Void>> futures = new ArrayList<>();
    try {
      for (int w = 0; w < workers; w++) {
        futures.add(pool.submit(() -> {
          try (Connection conn = ctx.connection()) {
            conn.setAutoCommit(false);
            try (RowWriter writer =
              new RowWriter(conn, ctx.tables(), layouts, corpus.attributeModel())) {
              long b;
              while (!stopped.get() && (b = next.getAndAdd(batchSize)) < to) {
                long e = Math.min(to, b + batchSize);
                long slot = 0;
                if (!throttle.unthrottled()) {
                  slot = throttle.claim((int) (e - b));
                  Throttle.awaitSlot(slot);
                }
                WriteGate g = gate;
                if (g != null && !g.enter()) {
                  return null;
                }
                if (g != null && !throttle.unthrottled() && g.reopenedAfter(slot)) {
                  // Drop the slots a quiesced sample held up, rather than replay them at once.
                  throttle.reset();
                }
                try {
                  if (listener != null) {
                    listener.starting(b, e);
                  }
                  writeBatch(writer, corpus, b, e, retries);
                  rows.addAndGet(e - b);
                  batches.incrementAndGet();
                  if (listener != null) {
                    listener.committed(b, e);
                  }
                } finally {
                  try {
                    if (listener != null) {
                      listener.finished(b, e);
                    }
                  } finally {
                    if (g != null) {
                      g.exit();
                    }
                  }
                }
                long now = System.nanoTime();
                long last = reported.get();
                if (now - last > 30_000_000_000L && reported.compareAndSet(last, now)) {
                  double s = (now - start) / 1e9;
                  LOG.info("Loaded {} of {} rows ({} rows/s)", rows.get(), to - from,
                    String.format("%.0f", rows.get() / s));
                }
              }
            }
          }
          return null;
        }));
      }
      for (Future<Void> f : futures) {
        f.get();
      }
    } catch (ExecutionException e) {
      stopped.set(true);
      Throwable cause = e.getCause();
      if (cause instanceof SQLException) {
        throw (SQLException) cause;
      }
      throw new SQLException("Load failed", cause);
    } finally {
      pool.shutdownNow();
    }
    return new Result(rows.get(), batches.get(), retries.get(), (System.nanoTime() - start) / 1e9);
  }

  private void writeBatch(RowWriter writer, Corpus corpus, long from, long to, AtomicLong retries)
    throws SQLException, InterruptedException {
    for (int attempt = 0;; attempt++) {
      try {
        for (long i = from; i < to; i++) {
          DocAttributes a = corpus.attributes(i);
          writer.upsert(a, corpus.vector(i));
        }
        writer.commit();
        return;
      } catch (SQLException e) {
        try {
          writer.connection().rollback();
        } catch (SQLException rollback) {
          LOG.debug("Rollback after a failed batch", rollback);
        }
        if (attempt >= RETRIES) {
          throw e;
        }
        retries.incrementAndGet();
        LOG.warn("Batch [{}, {}) failed, retrying: {}", from, to, e.getMessage());
        Thread.sleep(1000L << attempt);
      }
    }
  }
}
