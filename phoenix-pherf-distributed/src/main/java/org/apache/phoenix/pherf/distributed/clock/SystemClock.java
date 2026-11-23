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
package org.apache.phoenix.pherf.distributed.clock;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * Provides access to the system wall clock and the JVM monotonic clock, backed by a single daemon
 * thread that executes scheduled timers.
 */
public final class SystemClock implements Clock, AutoCloseable {
  private final ScheduledExecutorService timers;

  public SystemClock(String threadName) {
    this.timers = Executors.newSingleThreadScheduledExecutor(action -> {
      Thread thread = new Thread(action, threadName);
      thread.setDaemon(true);
      return thread;
    });
  }

  @Override
  public long millis() {
    return System.currentTimeMillis();
  }

  @Override
  public long monotonicMs() {
    return System.nanoTime() / 1_000_000;
  }

  @Override
  public Timer schedule(long delayMs, Runnable action) {
    ScheduledFuture<?> future = timers.schedule(action, delayMs, TimeUnit.MILLISECONDS);
    return () -> future.cancel(false);
  }

  @Override
  public void close() {
    timers.shutdownNow();
  }
}
