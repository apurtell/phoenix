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
 * Represents a control reply instructing the agent to immediately launch the run child process for
 * its prepared slot. The child process warms up and then holds at its start gate until a start
 * command arrives.
 */
public final class Launch extends Message {
  private String runId;
  private int step;

  private Launch() {
    super(MessageType.LAUNCH);
  }

  public Launch(String runId, int step) {
    this();
    this.runId = runId;
    this.step = step;
  }

  public String getRunId() {
    return runId;
  }

  public int getStep() {
    return step;
  }
}
