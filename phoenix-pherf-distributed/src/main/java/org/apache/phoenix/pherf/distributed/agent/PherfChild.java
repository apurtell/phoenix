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
package org.apache.phoenix.pherf.distributed.agent;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import org.apache.phoenix.pherf.Pherf;
import org.apache.phoenix.pherf.workload.StartGate;

/**
 * Provides the main entry point for an agent's Pherf child process. This wrapper delegates
 * execution to Pherf.main with the supplied arguments and propagates Pherf's exit status. When
 * Pherf.main returns normally, the child process explicitly exits with status 0 so that any
 * lingering threads left by Pherf cannot keep the child alive. When standard input reaches
 * end-of-stream, indicating that the parent agent has terminated, the child halts immediately with
 * status 1 so that it never outlives its parent agent.
 * <p>
 * The child also installs the Pherf start gate to synchronize workload execution. The first call to
 * the gate prints {@link #ARMED} to standard output. Every call then awaits the start epoch, which
 * is read from the first valid line of standard input as a timestamp in milliseconds on the agent
 * wall clock, and sleeps until the wall clock reaches that epoch. Once the gate opens, the first
 * call prints {@link #STARTED} along with the opening timestamp, while any subsequent call that
 * arrives after the epoch returns immediately. A -validate child process never invokes the start
 * gate.
 */
public final class PherfChild {
  /** Marker line emitted when the child process reaches and holds at its start gate. */
  static final String ARMED = "pherf-child armed";
  /** Marker prefix emitted when the start gate opens, followed by a space and the time in ms. */
  static final String STARTED = "pherf-child started";

  private static final AtomicBoolean ARMING = new AtomicBoolean();
  private static final CountDownLatch EPOCH = new CountDownLatch(1);
  private static volatile long epochMs;

  private PherfChild() {
  }

  public static void main(String[] args) {
    Thread orphan = new Thread(PherfChild::readInput, "pherf-child-stdin");
    orphan.setDaemon(true);
    orphan.start();
    StartGate.set(PherfChild::await);
    Pherf.main(args);
    System.exit(0);
  }

  /**
   * Reads the start epoch from standard input, and halts when end-of-stream indicates that the
   * parent agent has terminated.
   */
  private static void readInput() {
    try (BufferedReader in =
      new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8))) {
      String line;
      while ((line = in.readLine()) != null) {
        long epoch;
        try {
          epoch = Long.parseLong(line.trim());
        } catch (NumberFormatException e) {
          System.err.println("pherf child: not a start epoch: " + line);
          continue;
        }
        if (EPOCH.getCount() > 0) {
          epochMs = epoch;
          EPOCH.countDown();
        }
      }
    } catch (IOException e) {
      // A broken pipe on standard input likewise indicates that the parent agent has terminated.
    }
    Runtime.getRuntime().halt(1);
  }

  /** Blocks workload execution at the start gate until the configured start epoch is reached. */
  private static void await() throws InterruptedException {
    boolean first = ARMING.compareAndSet(false, true);
    if (first) {
      System.out.println(ARMED);
      System.out.flush();
    }
    EPOCH.await();
    long epoch = epochMs;
    long now;
    while ((now = System.currentTimeMillis()) < epoch) {
      Thread.sleep(epoch - now);
    }
    if (first) {
      System.out.println(STARTED + " " + now);
      System.out.flush();
    }
  }
}
