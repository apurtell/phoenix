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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.apache.phoenix.pherf.distributed.controller.Controller;
import org.junit.Test;

/**
 * Verifies RQ-244 by exercising the lifecycle script src/main/bin/pherf-distributed.sh against real
 * JVM processes. Each lifecycle verb for every role reports the expected process state and exit
 * status when configured via the PHERF_* environment variables, while streaming the process log to
 * standard output. The class path excludes test-classes so that the launched processes load the
 * shipped conf/log4j2.properties logging configuration.
 */
public class ScriptTest {
  private static final long TIMEOUT_MS = 60000;
  private static final String REST = "REST API listening on port ";
  private static final String AGENT = "agent channel listening on port ";

  /** Captures the exit status and standard output produced by a single script invocation. */
  private static final class Result {
    final int status;
    final String output;

    Result(int status, String output) {
      this.status = status;
      this.output = output;
    }

    @Override
    public String toString() {
      return status + ": " + output;
    }
  }

  private static File moduleDir() throws Exception {
    File classes =
      new File(Controller.class.getProtectionDomain().getCodeSource().getLocation().toURI());
    return classes.getParentFile().getParentFile();
  }

  private static File script() throws Exception {
    return new File(moduleDir(), "src/main/bin/pherf-distributed.sh");
  }

  /**
   * Returns the class path of the test JVM, excluding the test classes and their associated log
   * configuration.
   */
  private static String classPath() {
    String full =
      System.getProperty("surefire.test.class.path", System.getProperty("java.class.path"));
    List<String> kept = new ArrayList<>();
    for (String entry : full.split(File.pathSeparator)) {
      if (!entry.isEmpty() && !entry.replace('\\', '/').endsWith("/test-classes")) {
        kept.add(entry);
      }
    }
    return String.join(File.pathSeparator, kept);
  }

  private static Map<String, String> env(File tmp) {
    Map<String, String> env = new HashMap<>();
    env.put("PHERF_CLASSPATH", classPath());
    env.put("JAVA_HOME", System.getProperty("java.home"));
    // The lifecycle script forwards TMPDIR to the JVM as the java.io.tmpdir system property.
    env.put("PHERF_JAVA_OPTS", "-Xmx256m");
    env.put("TMPDIR", tmp.getPath());
    return env;
  }

  private static Result run(Map<String, String> env, String... args) throws Exception {
    try (ChildProcess call = ChildProcess.script("script", script(), env, args)) {
      int status = call.waitFor(TIMEOUT_MS);
      return new Result(status, call.output());
    }
  }

  /** Extracts and returns the process identifier (pid) reported in a status output line. */
  private static String pid(Result status) {
    assertEquals(status.toString(), 0, status.status);
    String prefix = "running as pid ";
    assertTrue(status.toString(), status.output.contains(prefix));
    return status.output.substring(status.output.indexOf(prefix) + prefix.length()).trim();
  }

  private static int port(ChildProcess process, String prefix) throws InterruptedException {
    String line = process.awaitLine(prefix, TIMEOUT_MS);
    return Integer.parseInt(line.substring(line.indexOf(prefix) + prefix.length()).trim());
  }

  private static String read(File file) throws Exception {
    return new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8).trim();
  }

  @Test
  public void controllerAndAgentVerbs() throws Exception {
    assertTrue(script().canExecute());
    File runDir = Simulation.runDir();
    File tmp = Simulation.runDir();
    Map<String, String> controllerEnv = env(tmp);
    controllerEnv.put("PHERF_RUN_DIR", runDir.getPath());
    controllerEnv.put("PHERF_REST_PORT", "0");
    controllerEnv.put("PHERF_AGENT_PORT", "0");
    File controllerPid = new File(runDir, "controller.pid");
    String stopped;
    try (ChildProcess controller =
      ChildProcess.script("controller", script(), controllerEnv, "controller", "start")) {
      port(controller, REST);
      int agentPort = port(controller, AGENT);
      String pid = pid(run(controllerEnv, "controller", "status"));
      assertEquals(read(controllerPid), pid);
      Result second = run(controllerEnv, "controller", "start");
      assertEquals(second.toString(), 1, second.status);
      assertTrue(second.output, second.output.contains("already running as pid " + pid));

      Map<String, String> agentEnv = env(tmp);
      agentEnv.put("PHERF_CONTROLLER", "http://127.0.0.1:" + agentPort);
      agentEnv.put("PHERF_ZOOKEEPER", "script-zk");
      agentEnv.put("PHERF_AGENT_ID", "script-1");
      try (
        ChildProcess agent = ChildProcess.script("agent", script(), agentEnv, "agent", "start")) {
        agent.awaitLine("agent script-1 registered", TIMEOUT_MS);
        controller.awaitLine("agent script-1 registered", TIMEOUT_MS);
        String agentPid = pid(run(agentEnv, "agent", "status"));
        assertEquals(read(new File(tmp, "pherf-agent-script-1.pid")), agentPid);
        assertEquals(1, run(agentEnv, "agent", "start").status);
        // The work directory of the agent is created inside TMPDIR.
        String[] work = tmp.list((dir, name) -> name.startsWith("pherf-agent-script-1-"));
        assertEquals(1, work.length);
        assertTrue(new File(tmp, work[0]).isDirectory());
        // When a pid file for another agent references this running process, the role id does not
        // match.
        Map<String, String> otherEnv = new HashMap<>(agentEnv);
        otherEnv.put("PHERF_AGENT_ID", "script-2");
        Files.write(new File(tmp, "pherf-agent-script-2.pid").toPath(),
          (agentPid + "\n").getBytes(StandardCharsets.UTF_8));
        assertEquals(3, run(otherEnv, "agent", "status").status);
        assertEquals(0, run(otherEnv, "agent", "stop").status);
        assertFalse(new File(tmp, "pherf-agent-script-2.pid").exists());
        assertEquals(agentPid, pid(run(agentEnv, "agent", "status")));
        Result stop = run(agentEnv, "agent", "stop");
        assertEquals(stop.toString(), 0, stop.status);
        assertEquals(agent.output(), 0, agent.waitFor(TIMEOUT_MS));
        controller.awaitLine("agent script-1 said goodbye", TIMEOUT_MS);
        Result status = run(agentEnv, "agent", "status");
        assertEquals(status.toString(), 3, status.status);
        assertTrue(status.output, status.output.contains("not running"));
      }

      Result stop = run(controllerEnv, "controller", "stop");
      assertEquals(stop.toString(), 0, stop.status);
      assertEquals(controller.output(), 0, controller.waitFor(TIMEOUT_MS));
      stopped = pid;
    }
    Result status = run(controllerEnv, "controller", "status");
    assertEquals(status.toString(), 3, status.status);
    assertFalse(controllerPid.exists());
    // When a stale pid file references a process that has already terminated, the stop command
    // removes the pid file and exits successfully.
    Files.write(controllerPid.toPath(), (stopped + "\n").getBytes(StandardCharsets.UTF_8));
    assertEquals(3, run(controllerEnv, "controller", "status").status);
    assertEquals(0, run(controllerEnv, "controller", "stop").status);
    assertFalse(controllerPid.exists());

    // The restart command stops the running instance and starts a new process in the foreground.
    try (ChildProcess first =
      ChildProcess.script("first", script(), controllerEnv, "controller", "start")) {
      port(first, REST);
      String before = pid(run(controllerEnv, "controller", "status"));
      try (ChildProcess restarted =
        ChildProcess.script("restarted", script(), controllerEnv, "controller", "restart")) {
        port(restarted, REST);
        assertEquals(first.output(), 0, first.waitFor(TIMEOUT_MS));
        String after = pid(run(controllerEnv, "controller", "status"));
        assertNotEquals(before, after);
        assertEquals(0, run(controllerEnv, "controller", "stop").status);
        assertEquals(restarted.output(), 0, restarted.waitFor(TIMEOUT_MS));
      }
    }
  }

  @Test
  public void usageErrorsExitTwo() throws Exception {
    Map<String, String> env = env(Simulation.runDir());
    Result noRunDir = run(env, "controller", "start");
    assertEquals(noRunDir.toString(), 2, noRunDir.status);
    assertTrue(noRunDir.output, noRunDir.output.contains("PHERF_RUN_DIR is not set"));
    env.put("PHERF_RUN_DIR", Simulation.runDir().getPath());
    for (String[] args : new String[][] { {}, { "controller" }, { "controller", "explode" },
      { "nobody", "start" }, { "controller", "start", "extra" } }) {
      Result result = run(env, args);
      assertEquals(result.toString(), 2, result.status);
      assertTrue(result.output, result.output.contains("usage: pherf-distributed.sh"));
    }
  }
}
