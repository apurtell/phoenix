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

import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.apache.phoenix.pherf.distributed.agent.AgentRuntime;
import org.apache.phoenix.pherf.distributed.agent.Workload;
import org.apache.phoenix.pherf.distributed.clock.Clock;
import org.apache.phoenix.pherf.distributed.clock.SkewedClock;
import org.apache.phoenix.pherf.distributed.clock.VirtualClock;
import org.apache.phoenix.pherf.distributed.controller.AgentView;
import org.apache.phoenix.pherf.distributed.controller.Controller;
import org.apache.phoenix.pherf.distributed.controller.RunSpec;
import org.apache.phoenix.pherf.distributed.controller.RunView;
import org.apache.phoenix.pherf.distributed.controller.TestTable;
import org.apache.phoenix.pherf.distributed.identity.OperationalIdentity;
import org.apache.phoenix.pherf.distributed.transport.InProcessNetwork;

/**
 * Coordinates a deterministic simulation environment consisting of a single controller and its
 * connected agents operating over a virtual clock and an in-process network transport. Each
 * simulated agent executes the production AgentRuntime coupled with a FakeWorkload, and all
 * scheduling and message processing execute synchronously on the calling test thread.
 */
public final class Simulation {
  public static final String ENDPOINT = "controller:8790";
  /** Defines the initial simulation epoch timestamp corresponding to 2026-01-01T00:00:00Z. */
  public static final long START_MS = 1767225600000L;

  public final VirtualClock clock = new VirtualClock(START_MS);
  public final InProcessNetwork network = new InProcessNetwork(clock);
  /**
   * Holds the root run directory used by the controller, initialized as a new temporary directory
   * under target.
   */
  public final File runDir = runDir();
  /**
   * Holds the active simulated controller process instance, which is replaced when
   * {@link #restartController()} is invoked.
   */
  public Controller controller = new Controller(clock, runDir);
  /**
   * Holds the test table for the active controller process, which is replaced when
   * {@link #restartController()} is invoked.
   */
  public TestTable tests = new TestTable(controller, clock);
  private final Map<String, Workload> workloads = new LinkedHashMap<>();
  private final Map<String, AgentRuntime> runtimes = new LinkedHashMap<>();
  private int incarnations;

  public Simulation() {
    network.listen(ENDPOINT, controller);
    controller.start();
  }

  /**
   * Instantiates and starts a simulated agent process assigned a fresh incarnation identifier,
   * allowing a subsequent agent process to reuse the same logical agent id.
   */
  public AgentRuntime agent(String id) {
    return agent(id, new FakeWorkload(clock));
  }

  /** Instantiates and starts a simulated agent process configured with its own workload. */
  public AgentRuntime agent(String id, Workload workload) {
    return agent(id, clock, workload);
  }

  /**
   * Instantiates and starts a simulated agent process on a host whose wall clock is skewMs ahead of
   * the controller host, configured with a FakeWorkload driven by that skewed host clock. Each
   * simulated host maintains its own independent monotonic clock origin.
   */
  public AgentRuntime skewedAgent(String id, long skewMs) {
    SkewedClock host = new SkewedClock(clock, skewMs, 7919L * (runtimes.size() + 1));
    return agent(id, host, new FakeWorkload(host));
  }

  /** Instantiates and starts a simulated agent process on a host with its own dedicated clock. */
  public AgentRuntime agent(String id, Clock hostClock, Workload workload) {
    String incarnation = UUID
      .nameUUIDFromBytes((id + "#" + ++incarnations).getBytes(StandardCharsets.UTF_8)).toString();
    AgentRuntime runtime = new AgentRuntime(new OperationalIdentity(id, incarnation), hostClock,
      network.connect(id, ENDPOINT), workload);
    workloads.put(id, workload);
    runtimes.put(id, runtime);
    runtime.start();
    return runtime;
  }

  /**
   * Stops the active controller process and starts a new controller instance bound to the same
   * endpoint and run directory. The new process retains only the state that it restores from the
   * run directory.
   */
  public void restartController() {
    controller.close();
    controller = new Controller(clock, runDir);
    tests = new TestTable(controller, clock);
    network.listen(ENDPOINT, controller);
    controller.start();
  }

  /** Returns the most recent agent process runtime started under the specified identifier. */
  public AgentRuntime agentRuntime(String id) {
    return runtimes.get(id);
  }

  public FakeWorkload workload(String id) {
    return (FakeWorkload) workloads.get(id);
  }

  /**
   * Advances the simulation by delivering pending network messages and firing due timers until the
   * virtual clock reaches the current time plus the specified duration in milliseconds (now + ms).
   */
  public void runFor(long ms) {
    runTo(clock.millis() + ms);
  }

  /**
   * Drives the simulation forward by delivering queued messages and executing due timers in
   * chronological order until the virtual clock reaches the specified target timestamp.
   */
  public void runTo(long endMs) {
    network.drain();
    Long next;
    while ((next = clock.nextDue()) != null && next <= endMs) {
      clock.advanceTo(next);
      network.drain();
    }
    clock.advanceTo(endMs);
    network.drain();
  }

  public AgentView view(String id) {
    for (AgentView view : controller.agents()) {
      if (view.getId().equals(id)) {
        return view;
      }
    }
    return null;
  }

  public List<String> agentIds() {
    List<String> ids = new ArrayList<>();
    for (AgentView view : controller.agents()) {
      ids.add(view.getId());
    }
    return ids;
  }

  public RunView run(String runId) {
    return controller.run(runId);
  }

  /** Returns the output bundle directory corresponding to the specified run identifier. */
  public File bundle(String runId) {
    return new File(runDir, runId);
  }

  /** Creates and returns a new empty temporary run directory under the module target directory. */
  public static File runDir() {
    try {
      File target =
        new File(Simulation.class.getProtectionDomain().getCodeSource().getLocation().toURI())
          .getParentFile();
      File runs = new File(target, "test-runs");
      Files.createDirectories(runs.toPath());
      return Files.createTempDirectory(runs.toPath(), "runs-").toFile();
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  /**
   * Constructs a run specification containing one execution step for each specified agent count,
   * configuring every step with the provided step properties.
   */
  public static RunSpec spec(long seed, Map<String, String> properties, int... agents) {
    Plans plan = Plans.plan(seed);
    for (int count : agents) {
      plan.step(Plans.step(count).scenario("scenario.xml", "<datamodel name=\"fake\"/>")
        .properties(properties));
    }
    return plan.spec();
  }

  public static RunSpec spec(int... agents) {
    return spec(7, new LinkedHashMap<String, String>(), agents);
  }

  /** Counts the total number of logged messages matching the specified event kind and type. */
  public int count(String kind, String type) {
    int count = 0;
    for (InProcessNetwork.Event event : network.events()) {
      if (event.kind.equals(kind) && event.json.contains("\"type\":\"" + type + "\"")) {
        count++;
      }
    }
    return count;
  }
}
