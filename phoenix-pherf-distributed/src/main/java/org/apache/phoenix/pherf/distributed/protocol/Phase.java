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
package org.apache.phoenix.pherf.distributed.protocol;

/**
 * Defines the execution phase that an agent reports in its status. In the LAUNCHED phase, the run
 * child process has started and is performing its warmup. In the ARMED phase, the run child process
 * is holding at its start gate. In the RUNNING phase, the agent has received its scheduled start
 * time.
 */
public enum Phase {
  IDLE,
  PREPARING,
  READY,
  LAUNCHED,
  ARMED,
  RUNNING,
  DONE,
  FAILED;

  /**
   * Returns true while the assigned workload of the agent can still be active: PREPARING, READY,
   * LAUNCHED, ARMED or RUNNING.
   */
  public boolean active() {
    return this == PREPARING || this == READY || this == LAUNCHED || this == ARMED
      || this == RUNNING;
  }

  /**
   * Returns true while the run child process of the agent can be live, which includes the LAUNCHED,
   * ARMED, and RUNNING phases.
   */
  public boolean launched() {
    return this == LAUNCHED || this == ARMED || this == RUNNING;
  }
}
