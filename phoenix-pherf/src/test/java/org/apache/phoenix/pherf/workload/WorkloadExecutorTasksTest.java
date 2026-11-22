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
package org.apache.phoenix.pherf.workload;

import static org.junit.Assert.assertEquals;

import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.Timeout;

/**
 * Verifies the task accounting metrics of the Pherf thread pool for RQ-201. A submitted task is
 * recorded as having waited for a thread whenever the number of outstanding tasks at the moment of
 * submission exceeds the configured thread count. Because a task ceases to be counted as
 * outstanding before its future completes, a caller that submits tasks and awaits each one in
 * sequence will never observe a thread wait.
 */
public class WorkloadExecutorTasksTest {
  @Rule
  public Timeout timeout = Timeout.seconds(120);

  private static WorkloadExecutor executor(int threads) throws Exception {
    Properties properties = new Properties();
    properties.setProperty("pherf.default.threadpool", String.valueOf(threads));
    return new WorkloadExecutor(properties, new ArrayList<Workload>(), true);
  }

  private static Workload workload(Callable<Void> callable) {
    return new Workload() {
      @Override
      public Callable<Void> execute() {
        return callable;
      }

      @Override
      public void complete() {
      }
    };
  }

  @Test
  public void testThirdTaskOnTwoThreadsWaits() throws Exception {
    WorkloadExecutor executor = executor(2);
    try {
      CountDownLatch started = new CountDownLatch(2);
      CountDownLatch release = new CountDownLatch(1);
      List<Future<?>> futures = new ArrayList<>();
      for (int i = 0; i < 2; i++) {
        futures.add(executor.getPool().submit(() -> {
          started.countDown();
          release.await();
          return null;
        }));
      }
      started.await(30, TimeUnit.SECONDS);
      assertEquals(0, executor.getWaitedTasks());
      futures.add(executor.getPool().submit(() -> {
      }));
      assertEquals(3, executor.getSubmittedTasks());
      assertEquals(1, executor.getWaitedTasks());
      assertEquals(3, executor.getPeakTasks());
      release.countDown();
      for (Future<?> future : futures) {
        future.get();
      }
      // Once the previously running tasks complete and the pool becomes idle again, a newly
      // submitted task acquires a thread immediately without waiting.
      executor.getPool().submit(() -> null).get();
      assertEquals(4, executor.getSubmittedTasks());
      assertEquals(1, executor.getWaitedTasks());
      assertEquals(3, executor.getPeakTasks());
    } finally {
      executor.shutdown();
    }
  }

  /**
   * Verifies that when 500 tasks are submitted and awaited sequentially through both add() and
   * getPool(), no task is ever forced to wait for a thread.
   */
  @Test
  public void testSequentialTasksNeverWait() throws Exception {
    WorkloadExecutor executor = executor(1);
    try {
      for (int i = 0; i < 250; i++) {
        Workload workload = workload(() -> null);
        executor.add(workload);
        executor.get(workload);
        executor.getPool().submit(() -> {
        }).get();
      }
      assertEquals(500, executor.getSubmittedTasks());
      assertEquals(0, executor.getWaitedTasks());
      assertEquals(1, executor.getPeakTasks());
    } finally {
      executor.shutdown();
    }
  }

  /**
   * Models the execution pattern of QueryExecutor, in which a parent workload running on the pool
   * submits its child runner tasks to the same pool and blocks until they finish. When the pool is
   * provisioned with runners + 1 threads, every task executes immediately and no task waits.
   */
  @Test
  public void testQueryExecutorPatternNeverWaits() throws Exception {
    int runners = 3;
    WorkloadExecutor executor = executor(runners + 1);
    try {
      for (int i = 0; i < 100; i++) {
        Workload workload = workload(() -> {
          List<Future<?>> threads = new ArrayList<>();
          for (int r = 0; r < runners; r++) {
            threads.add(executor.getPool().submit(() -> null));
          }
          for (Future<?> thread : threads) {
            thread.get();
          }
          return null;
        });
        executor.add(workload);
        executor.get(workload);
      }
      assertEquals(100 * (runners + 1), executor.getSubmittedTasks());
      assertEquals(0, executor.getWaitedTasks());
      assertEquals(runners + 1, executor.getPeakTasks());
    } finally {
      executor.shutdown();
    }
  }
}
