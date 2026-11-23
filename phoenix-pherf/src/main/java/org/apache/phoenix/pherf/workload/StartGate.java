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

/**
 * Coordinates the start of steady-state workload execution through a single synchronization hook
 * that holds the first measured dispatch until a host process opens the gate. The open-loop
 * dispatcher calls {@link #await()} after all warmup operations complete and before its arrival
 * schedule starts, while a closed-loop runner calls it after its warmup executions complete and
 * before its first measured execution begins. When no hook is installed, await() returns
 * immediately so that standalone Pherf behavior remains unchanged. A distributed agent installs
 * this hook in its Pherf child to align all agents to start at a single shared epoch.
 */
public final class StartGate {
  /**
   * Synchronization hook whose {@link #await()} method blocks until the workload run is permitted
   * to start.
   */
  public interface Gate {
    void await() throws InterruptedException;
  }

  private static volatile Gate gate;

  private StartGate() {
  }

  /** Installs the active start gate hook, or clears any installed hook when passed null. */
  public static void set(Gate hook) {
    gate = hook;
  }

  /** Returns the currently installed start gate hook, or null if no hook is configured. */
  public static Gate get() {
    return gate;
  }

  /**
   * Blocks on the installed start gate hook until the gate opens, or returns immediately when no
   * hook is set.
   */
  public static void await() throws InterruptedException {
    Gate hook = gate;
    if (hook != null) {
      hook.await();
    }
  }
}
