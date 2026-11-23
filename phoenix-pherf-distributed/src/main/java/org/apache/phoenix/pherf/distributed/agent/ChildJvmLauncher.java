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
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import org.apache.phoenix.pherf.distributed.protocol.Protocol;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Launches a Pherf execution in an isolated child JVM via {@link PherfChild}. The spawned process
 * inherits the agent's JVM arguments and environment variables, using the assigned work directory
 * as its current working directory. The child classpath prepends the overlay and slice directories
 * from the work directory so that run-specific pherf.properties and scenario documents take
 * precedence. Standard output and error streams from the child process are forwarded to the agent
 * log.
 */
public final class ChildJvmLauncher implements Launcher {
  private static final Logger LOG = LoggerFactory.getLogger(ChildJvmLauncher.class);
  /** Defines the maximum number of trailing output lines included in an error report. */
  static final int TAIL_LINES = 5;

  private final String agent;
  private final List<String> jvmArgs;
  private final String classPath;

  /**
   * Initializes the launcher to propagate the current JVM options to the child while excluding any
   * debug agent configuration.
   */
  public ChildJvmLauncher(String agent, String classPath) {
    this.agent = agent;
    this.jvmArgs = new ArrayList<>();
    for (String arg : ManagementFactory.getRuntimeMXBean().getInputArguments()) {
      if (!arg.contains("jdwp")) {
        jvmArgs.add(arg);
      }
    }
    this.classPath = classPath;
  }

  @Override
  public Handle launch(List<String> args, File workDir, ExitListener listener) throws IOException {
    List<String> command = new ArrayList<>();
    command.add(new File(System.getProperty("java.home"), "bin/java").getPath());
    command.addAll(jvmArgs);
    command.add("-cp");
    command.add(new File(workDir, PherfWorkload.OVERLAY).getPath() + File.pathSeparator
      + new File(workDir, PherfWorkload.SLICE).getPath() + File.pathSeparator + classPath);
    command.add(PherfChild.class.getName());
    command.addAll(args);
    // The child process terminates when its standard input stream closes, so the agent holds the
    // pipe open without writing to it.
    Process process =
      new ProcessBuilder(command).directory(workDir).redirectErrorStream(true).start();
    Thread watcher = new Thread(() -> Protocol.withAgent(agent, () -> watch(process, listener)),
      "pherf-child-" + agent);
    watcher.setDaemon(true);
    watcher.start();
    return new Handle() {
      @Override
      public void destroy() {
        process.destroy();
      }

      @Override
      public void destroyForcibly() {
        process.destroyForcibly();
      }
    };
  }

  /** Relays child output to the log until the stream ends, then reports the process exit. */
  private static void watch(Process process, ExitListener listener) {
    ArrayDeque<String> last = new ArrayDeque<>();
    try (BufferedReader out =
      new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
      String line;
      while ((line = out.readLine()) != null) {
        LOG.info("pherf: {}", line);
        if (last.size() == TAIL_LINES) {
          last.removeFirst();
        }
        last.addLast(line);
      }
    } catch (IOException e) {
      LOG.warn("cannot read the output of the pherf child: {}", e.toString());
    }
    int status;
    while (true) {
      try {
        status = process.waitFor();
        break;
      } catch (InterruptedException e) {
        // The watcher thread is solely responsible for delivering the exit report, so it resumes
        // waiting until the process terminates.
      }
    }
    try {
      listener.exited(status, new ArrayList<>(last));
    } catch (RuntimeException e) {
      LOG.error("the exit report of the pherf child failed", e);
    }
  }
}
