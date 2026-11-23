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
package org.apache.phoenix.pherf.distributed.controller;

import java.io.File;
import java.io.IOException;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.apache.phoenix.pherf.distributed.clock.SystemClock;
import org.apache.phoenix.pherf.distributed.protocol.Protocol;
import org.apache.phoenix.pherf.distributed.transport.http.ControllerServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Entry point for the controller process. Configuration is supplied through environment variables:
 * PHERF_RUN_DIR (required; specifies the data directory, which is created when it does not exist),
 * PHERF_REST_PORT (defaults to 8780), PHERF_AGENT_PORT (defaults to 8790), and PHERF_BIND_ADDRESS
 * (defaults to all network interfaces; accepts an IP literal or a host name). Specifying port 0
 * selects an available free port. The process holds an exclusive lock on
 * PHERF_RUN_DIR/controller.lock for its entire lifetime so that two controllers never share a run
 * directory. If another process currently holds the lock, startup waits up to {@link #LOCK_WAIT_MS}
 * to acquire it, accommodating Kubernetes pod replacements in which a new pod starts before the
 * previous pod finishes terminating. Upon receiving SIGTERM, the controller shuts down its
 * listeners and terminates with exit status 0, leaving the next controller instance to mark any
 * in-flight runs as failed. The process terminates with status 2 on a configuration fault
 * (including a lock that remains held for the entire wait window) and with status 1 on an
 * unexpected error.
 * <p>
 * Each listener closes any connection that fails to deliver its request line, headers, and body
 * within {@link #REQUEST_SECONDS}. Because this deadline ceases once the request body is read, a
 * held agent poll is not constrained by the timeout.
 */
public final class ControllerMain {
  /** Names the lock file created inside the run directory. */
  static final String LOCK = "controller.lock";
  /**
   * Maximum duration to wait for an already-held run-directory lock, in milliseconds. A deleted or
   * evicted controller pod is granted the harness stop grace period (PX_TIMEOUT_STOP, 120 s by
   * default) before the kubelet forcibly terminates it, and the kernel releases the file lock when
   * the process exits. The additional 30 s margin accommodates forced termination and lock release,
   * matching the allowance that the harness applies to pod deletion.
   */
  static final long LOCK_WAIT_MS = 150000L;
  /** Polling interval between successive attempts to acquire a contended lock, in milliseconds. */
  static final long LOCK_POLL_MS = 500L;
  /** Specifies the maximum time allowed to read a single incoming request, in seconds. */
  static final int REQUEST_SECONDS = 60;
  private static final Logger LOG = LoggerFactory.getLogger(ControllerMain.class);
  /**
   * Holds the exclusive lock on the run directory in a static field so that the lock and its
   * underlying file channel remain open for the lifetime of the process.
   */
  private static FileLock runDirLock;

  private ControllerMain() {
  }

  public static void main(String[] args) throws InterruptedException {
    // The JDK HTTP server implementation reads this system property once when instantiating its
    // first server instance.
    System.setProperty("sun.net.httpserver.maxReqTime", Integer.toString(REQUEST_SECONDS));
    int restPort;
    int agentPort;
    InetAddress bind;
    File runDir;
    try {
      restPort = port("PHERF_REST_PORT", System.getenv("PHERF_REST_PORT"), Protocol.REST_PORT);
      agentPort = port("PHERF_AGENT_PORT", System.getenv("PHERF_AGENT_PORT"), Protocol.AGENT_PORT);
      bind = bindAddress(System.getenv("PHERF_BIND_ADDRESS"));
      runDir = runDir(System.getenv("PHERF_RUN_DIR"));
      runDirLock = lock(runDir);
    } catch (IllegalArgumentException e) {
      LOG.error("bad configuration: {}", e.getMessage());
      System.exit(2);
      return;
    }
    SystemClock clock = new SystemClock("pherf-controller-clock");
    Controller controller = new Controller(clock, runDir);
    TestTable tests = new TestTable(controller, clock);
    ControllerServer server;
    try {
      server = new ControllerServer(controller, tests, bind, restPort, agentPort);
    } catch (IOException e) {
      LOG.error("cannot listen on ports {} and {}: {}", restPort, agentPort, e.toString());
      System.exit(1);
      return;
    }
    // On SIGTERM, stop the listeners and halt the runtime with exit code 0 rather than allowing
    // the JVM to exit with signal status 143. Active runs in flight are not terminated on shutdown,
    // so a graceful stop is treated identically to a crash for those runs.
    Runtime.getRuntime().addShutdownHook(new Thread(() -> {
      LOG.info("controller stopping");
      controller.close();
      server.close();
      clock.close();
      Runtime.getRuntime().halt(0);
    }, "pherf-controller-stop"));
    server.start();
    controller.start();
    LOG.info("REST API listening on port {}", server.restPort());
    LOG.info("agent channel listening on port {}", server.agentPort());
    new CountDownLatch(1).await();
  }

  /** Resolves and returns the run directory, creating it if it does not exist. */
  static File runDir(String text) {
    if (text == null || text.trim().isEmpty()) {
      throw new IllegalArgumentException("PHERF_RUN_DIR is not set");
    }
    File dir = new File(text.trim()).getAbsoluteFile();
    try {
      Files.createDirectories(dir.toPath());
    } catch (IOException e) {
      throw new IllegalArgumentException("PHERF_RUN_DIR " + dir + " is not a directory: " + e, e);
    }
    return dir;
  }

  /**
   * Acquires the exclusive run-directory lock retained for the lifetime of the process, waiting up
   * to {@link #LOCK_WAIT_MS} if another controller instance currently holds the lock.
   * @throws IllegalArgumentException if the lock remains held for the entire wait period.
   */
  static FileLock lock(File runDir) throws InterruptedException {
    return lock(runDir, LOCK_WAIT_MS, LOCK_POLL_MS);
  }

  /**
   * Acquires the exclusive lock on the run directory, polling every pollMs for up to waitMs while
   * another controller holds the lock and logging the wait once.
   * @throws IllegalArgumentException if the lock remains held after waitMs has elapsed.
   */
  static FileLock lock(File runDir, long waitMs, long pollMs) throws InterruptedException {
    File dir = runDir.getAbsoluteFile();
    long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(waitMs);
    boolean logged = false;
    while (true) {
      FileLock lock = tryLock(new File(dir, LOCK));
      if (lock != null) {
        return lock;
      }
      // Evaluate the remaining budget in nanoseconds so the wait loop never expires before waitMs.
      long leftNs = deadline - System.nanoTime();
      if (leftNs <= 0) {
        throw new IllegalArgumentException("another controller uses PHERF_RUN_DIR " + dir);
      }
      if (!logged) {
        LOG.info("another controller holds {}, waiting up to {} ms for it", new File(dir, LOCK),
          waitMs);
        logged = true;
      }
      // Round the remaining wait duration up to the next whole millisecond before sleeping.
      Thread.sleep(Math.min(pollMs, TimeUnit.NANOSECONDS.toMillis(leftNs) + 1));
    }
  }

  /**
   * Attempts a single non-blocking acquisition of the exclusive lock on the specified file,
   * returning null if another controller currently holds it.
   * @throws IllegalArgumentException if the lock file cannot be opened or locked.
   */
  private static FileLock tryLock(File file) {
    FileChannel channel = null;
    FileLock lock = null;
    try {
      channel =
        FileChannel.open(file.toPath(), StandardOpenOption.CREATE, StandardOpenOption.WRITE);
      lock = channel.tryLock();
    } catch (OverlappingFileLockException e) {
      // This JVM already holds the lock, which is treated identically to another process holding
      // it.
    } catch (IOException e) {
      close(channel);
      throw new IllegalArgumentException("cannot lock " + file + ": " + e, e);
    }
    if (lock == null) {
      close(channel);
    }
    return lock;
  }

  private static void close(FileChannel channel) {
    if (channel != null) {
      try {
        channel.close();
      } catch (IOException e) {
        // Ignore close failures because the channel is being discarded without holding a lock.
      }
    }
  }

  /**
   * Resolves the network bind address for both listeners, or returns null to bind to all network
   * interfaces.
   */
  static InetAddress bindAddress(String text) {
    if (text == null || text.trim().isEmpty()) {
      return null;
    }
    try {
      return InetAddress.getByName(text.trim());
    } catch (UnknownHostException e) {
      throw new IllegalArgumentException("PHERF_BIND_ADDRESS is not an address: " + text.trim(), e);
    }
  }

  /**
   * Parses and returns the port number specified by the given environment variable, or returns the
   * default port when the variable is unset or blank.
   */
  static int port(String name, String text, int defaultPort) {
    if (text == null || text.trim().isEmpty()) {
      return defaultPort;
    }
    try {
      int port = Integer.parseInt(text.trim());
      if (port >= 0 && port <= 65535) {
        return port;
      }
    } catch (NumberFormatException e) {
      // Fall through to throw the diagnostic exception below, whose message names the invalid
      // value.
    }
    throw new IllegalArgumentException(name + " is not a port: " + text);
  }
}
