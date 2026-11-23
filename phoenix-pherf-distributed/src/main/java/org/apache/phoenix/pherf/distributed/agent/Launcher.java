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

import java.io.File;
import java.io.IOException;
import java.util.List;

/** Launches and manages a single Pherf child process executing an agent workload. */
public interface Launcher {
  /**
   * Asynchronously starts a Pherf process with the specified arguments in the given work directory
   * and returns immediately. The exit listener is invoked exactly once from an arbitrary thread,
   * which may occur before this method returns.
   */
  Handle launch(List<String> args, File workDir, ExitListener listener) throws IOException;

  /** Represents a running child process launched by the agent. */
  interface Handle {
    /** Writes a single line to the standard input stream of the child process. */
    void send(String line) throws IOException;

    /** Requests graceful termination of the child process via SIGTERM. */
    void destroy();

    /** Forcibly terminates the child process via SIGKILL. */
    void destroyForcibly();
  }

  /** Receives lifecycle marker events and termination notifications from a child process. */
  interface ExitListener {
    /** Reports the final exit status and the trailing output lines captured from the child. */
    void exited(int status, List<String> lastLines);

    /** Reports that the child process is holding at its start gate. */
    default void armed() {
    }

    /** Reports that the child process opened its start gate at the given agent clock time. */
    default void started(long startedMs) {
    }
  }
}
