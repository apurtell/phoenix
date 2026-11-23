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

import java.io.IOException;
import org.apache.phoenix.pherf.Pherf;

/**
 * Provides the main entry point for an agent's Pherf child process. This wrapper delegates
 * execution to Pherf.main with the supplied arguments and propagates Pherf's exit status. When
 * Pherf.main returns normally, the process explicitly exits with status 0 so that any lingering
 * threads left by Pherf cannot keep the child JVM alive. If standard input closes, indicating that
 * the parent agent has terminated, the child halts immediately with exit status 1 so that it never
 * outlives its parent agent.
 */
public final class PherfChild {
  private PherfChild() {
  }

  public static void main(String[] args) {
    Thread orphan = new Thread(PherfChild::haltAtEndOfInput, "pherf-child-stdin");
    orphan.setDaemon(true);
    orphan.start();
    Pherf.main(args);
    System.exit(0);
  }

  private static void haltAtEndOfInput() {
    try {
      while (System.in.read() >= 0) {
        // The parent agent never writes to standard input, so any received bytes are ignored and
        // only end-of-stream is significant.
      }
    } catch (IOException e) {
      // A broken pipe on standard input likewise indicates that the parent agent has terminated.
    }
    Runtime.getRuntime().halt(1);
  }
}
