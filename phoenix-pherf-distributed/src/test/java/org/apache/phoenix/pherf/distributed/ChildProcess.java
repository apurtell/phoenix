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
package org.apache.phoenix.pherf.distributed;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * Manages a child process that runs either a JVM executing a single main class on the test class
 * path or bash executing a script. Both standard output and standard error from the child process
 * are captured into a single shared line queue. The test suite starts processes exclusively through
 * this class. Each child process inherits the test environment with all PHERF_* variables removed
 * and its own environment variables added.
 */
public final class ChildProcess implements AutoCloseable {
  private final String name;
  private final Process process;
  private final BlockingQueue<String> lines = new LinkedBlockingQueue<>();
  private final List<String> seen = new ArrayList<>();
  private final Thread reader;

  private ChildProcess(String name, Process process) {
    this.name = name;
    this.process = process;
    this.reader = new Thread(() -> {
      try (BufferedReader in = new BufferedReader(
        new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
        String line;
        while ((line = in.readLine()) != null) {
          lines.add(line);
        }
      } catch (IOException e) {
        lines.add("read error: " + e);
      }
    }, "child-" + name);
    reader.setDaemon(true);
    reader.start();
  }

  /**
   * Launches the specified main class in a child JVM with the provided environment variables added
   * to the inherited environment.
   */
  public static ChildProcess start(String name, Class<?> main, Map<String, String> env)
    throws IOException {
    String classPath =
      System.getProperty("surefire.test.class.path", System.getProperty("java.class.path"));
    ProcessBuilder builder = new ProcessBuilder(Arrays.asList(
      new File(System.getProperty("java.home"), "bin/java").getPath(),
      // Propagate the temporary directory so the agent creates its working directory inside the
      // test build directory.
      "-Djava.io.tmpdir=" + System.getProperty("java.io.tmpdir"), "-cp", classPath, main.getName()))
        .redirectErrorStream(true);
    builder.environment().keySet().removeIf(key -> key.startsWith("PHERF_"));
    builder.environment().putAll(env);
    return new ChildProcess(name, builder.start());
  }

  /**
   * Launches bash to execute the specified script and arguments with the provided environment
   * variables added to the inherited environment.
   */
  public static ChildProcess script(String name, File script, Map<String, String> env,
    String... args) throws IOException {
    List<String> command = new ArrayList<>();
    command.add("bash");
    command.add(script.getPath());
    command.addAll(Arrays.asList(args));
    ProcessBuilder builder = new ProcessBuilder(command).redirectErrorStream(true);
    builder.environment().keySet().removeIf(key -> key.startsWith("PHERF_"));
    builder.environment().putAll(env);
    return new ChildProcess(name, builder.start());
  }

  /**
   * Blocks until the child process emits an output line containing the specified text, and returns
   * that matching line.
   */
  public String awaitLine(String text, long timeoutMs) throws InterruptedException {
    synchronized (seen) {
      for (String line : seen) {
        if (line.contains(text)) {
          return line;
        }
      }
    }
    long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs);
    while (true) {
      long left = deadline - System.nanoTime();
      String line = left > 0 ? lines.poll(left, TimeUnit.NANOSECONDS) : null;
      if (line == null) {
        throw new AssertionError(name + " printed no line with '" + text + "' in " + timeoutMs
          + " ms; output:\n" + output());
      }
      synchronized (seen) {
        seen.add(line);
      }
      if (line.contains(text)) {
        return line;
      }
    }
  }

  /** Requests graceful termination of the child process by sending a SIGTERM signal. */
  public void terminate() {
    process.destroy();
  }

  /** Forcefully terminates the child process by sending a SIGKILL signal to simulate a crash. */
  public void kill() {
    process.destroyForcibly();
  }

  /** Waits for the child process to exit and returns its exit status code. */
  public int waitFor(long timeoutMs) throws InterruptedException {
    if (!process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)) {
      process.destroyForcibly().waitFor();
      throw new AssertionError(
        name + " did not exit in " + timeoutMs + " ms; output:\n" + output());
    }
    // Wait for the reader thread to reach the end of the stream so that all output is captured.
    reader.join(timeoutMs);
    return process.exitValue();
  }

  /** Returns all output lines that have been read from the child process so far. */
  public String output() {
    lines.drainTo(seen);
    synchronized (seen) {
      return String.join("\n", seen);
    }
  }

  @Override
  public void close() throws InterruptedException {
    if (process.isAlive()) {
      process.destroyForcibly().waitFor();
    }
  }
}
