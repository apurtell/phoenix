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
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * An in-memory test launcher implementation that records launch requests without spawning actual
 * operating system processes. Tests explicitly drive the lifecycle markers of each simulated child
 * process by invoking {@link Launch#arm} and {@link Launch#started}, and terminate it by invoking
 * {@link Launch#exit}.
 */
public final class FakeLauncher implements Launcher {
  public final List<Launch> launches = new ArrayList<>();
  /** An exception that is thrown on the next launch invocation when configured. */
  public IOException failure;

  @Override
  public Handle launch(List<String> args, File workDir, ExitListener listener) throws IOException {
    if (failure != null) {
      IOException e = failure;
      failure = null;
      throw e;
    }
    Launch launch = new Launch(args, workDir, listener);
    launches.add(launch);
    return launch;
  }

  /** Returns the most recently recorded launch invocation. */
  public Launch last() {
    return launches.get(launches.size() - 1);
  }

  /** Represents a single simulated child process handle created by a workload launch request. */
  public static final class Launch implements Handle {
    public final List<String> args;
    public final File workDir;
    private final ExitListener listener;
    /** Records the lines written by the workload to the standard input stream of the child. */
    public final List<String> sent = new ArrayList<>();
    public int destroys;
    public int kills;
    private boolean exited;

    Launch(List<String> args, File workDir, ExitListener listener) {
      this.args = Collections.unmodifiableList(new ArrayList<>(args));
      this.workDir = workDir;
      this.listener = listener;
    }

    public boolean validates() {
      return args.contains("-validate");
    }

    /**
     * Returns the argument value immediately following the specified option in the launch
     * arguments, or null if no such value exists.
     */
    public String value(String option) {
      int at = args.indexOf(option);
      return at < 0 || at + 1 >= args.size() ? null : args.get(at + 1);
    }

    @Override
    public void destroy() {
      destroys++;
    }

    @Override
    public void destroyForcibly() {
      kills++;
    }

    @Override
    public void send(String line) {
      sent.add(line);
    }

    /** Simulates the child process reaching and holding at its start gate. */
    public void arm() {
      listener.armed();
    }

    /** Simulates the start gate of the child process opening at the specified timestamp. */
    public void started(long startedMs) {
      listener.started(startedMs);
    }

    /**
     * Writes the output reports produced by a Pherf run child process to
     * RESULTS_HDR/LATENCY_run.json and, when verdict rows are provided, to
     * RESULTS_HDR/VERDICT_run.tsv.
     */
    public void results(String latency, String... verdict) {
      File dir = new File(workDir, PherfWorkload.RESULTS + "_HDR");
      try {
        Files.createDirectories(dir.toPath());
        Files.write(new File(dir, "LATENCY_run.json").toPath(),
          latency.getBytes(StandardCharsets.UTF_8));
        if (verdict.length > 0) {
          Files.write(new File(dir, "VERDICT_run.tsv").toPath(), Arrays.asList(verdict),
            StandardCharsets.UTF_8);
        }
      } catch (IOException e) {
        throw new UncheckedIOException(e);
      }
    }

    /**
     * Simulates termination of the child process with the given exit status and trailing output
     * lines.
     */
    public void exit(int status, String... lines) {
      if (exited) {
        throw new IllegalStateException("the child already exited");
      }
      exited = true;
      listener.exited(status, Arrays.asList(lines));
    }
  }
}
