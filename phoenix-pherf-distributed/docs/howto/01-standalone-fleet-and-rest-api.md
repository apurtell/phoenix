<!--
  Licensed to the Apache Software Foundation (ASF) under one or more
  contributor license agreements.  See the NOTICE file distributed with
  this work for additional information regarding copyright ownership.
  The ASF licenses this file to You under the Apache License, Version 2.0
  (the "License"); you may not use this file except in compliance with
  the License.  You may obtain a copy of the License at

      http://www.apache.org/licenses/LICENSE-2.0

  Unless required by applicable law or agreed to in writing, software
  distributed under the License is distributed on an "AS IS" BASIS,
  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
  See the License for the specific language governing permissions and
  limitations under the License.
-->

# How to deploy a standalone controller and agents and use the REST API (D01)

## The question

Can operators deploy a Distributed Pherf controller alongside an agent fleet and rely on the
controller to reject an invalid execution plan before any agent generates database traffic? This
HOWTO walks through deploying the standalone Compose configuration for the pherf image, examining
the environment contract and lifecycle management script, exploring the REST API and its structured
error contract, submitting malformed plans that fail submission validation, and restarting the
controller to verify which state persists on disk.

No running database is required for this guide. `PHERF_ZOOKEEPER` points to a dummy quorum because
an agent only connects to ZooKeeper when Pherf actively executes a workload, which no step in this
guide performs. The fleet-wide validation demonstrated in step 14 invokes Pherf's offline
validator, which operates without opening any external connections.

## Before you start

**Audience.** This guide is designed for prospective users who want to inspect the controller and
agent architecture before running full workloads, as well as contributors modifying the module, the
container image, or the operational scripts.

**Prerequisites.**

- A Docker engine supporting Compose v2 (`docker compose`) with at least 5.5 GiB of available
  memory for containers.
- Both `curl` and `jq` version 1.6 or later installed on the host machine.
- A current pherf image built from your working tree (covered in step 1). Building the image
  requires the Phoenix harness build; refer to [H02][h02] for the underlying build prerequisites
  (JDK 17 and Maven).
- Port `127.0.0.1:8780` must be free of any other listening process.

| Cost | Value |
|---|---|
| Wall time | Roughly 5 minutes, plus about 10 minutes to build the image when it is out of date. |
| Docker memory | 5632 MiB in container limits: 1024 for the controller and 3 x 1536 for agents. |
| Disk | Under 1 GiB in total: the image takes about 800 MB and the run volume uses a few KB. |
| Files written | `$HOWTO/base.json`, `plan-*.json`, `agents-*.json`, and the Compose volume. |

Initialize the session by exporting the required environment and path variables:

```sh
export PATH="$PWD/dev/harness/bin:$PATH"
export PX_OUT="${PX_OUT:-$HOME/phoenix-harness}"
HOWTO="$PX_OUT/howto/d01"; mkdir -p "$HOWTO"
C=dev/harness/docker/pherf/compose.yaml
PF=http://127.0.0.1:8780/v1
```

The variable `PF` defines the base URL for the controller REST API, and every request in this
HOWTO targets it.

## Method

**Hypothesis.** The standalone deployment initializes cleanly with one controller and three agents,
and all three agents register in the `IDLE` state. Every malformed plan submission returns an HTTP
400 response identifying the offending JSON path. A plan that is structurally valid JSON but whose
per-agent workload share fails the Pherf validator transitions to `ABORTED` before any `launch`
command is issued. Restarting the controller preserves all registered tests and completed runs from
disk, while the live agents automatically re-register.

**Controls.** The procedure uses a single container image, one Compose specification, a dummy
ZooKeeper quorum, and an initially empty run volume. Because no database is involved, the results
are completely independent of cluster health or storage performance.

**Noise.** There is no timing noise that affects the outcome because this HOWTO verifies
deterministic functional behavior rather than latency or throughput. Agent registration timestamps
and measured clock offsets naturally fluctuate across executions.

**What to check and why.** Verify the exit status of every process and lifecycle script verb, the
HTTP status code and structured error code returned by each REST endpoint, and the resulting run
state. Automated clients such as the harness driver rely on these values as a stable programmatic
contract. By contrast, human-readable error message strings are diagnostic text rather than part of
the contract and may evolve in future builds.

**What invalidates the result.** Running against a stale image that does not match your working
tree (step 2 verifies the image tag) or having another Compose project bound to port 8780 will
invalidate the observations.

## Concepts

Consult the [module README][readme] for the complete specification. The core architectural
principles are summarized below:

- **Two processes.** The controller (`ControllerMain`) maintains the active agent registry, test
  definitions, and run states, exposing the public REST API on port 8780 and listening for agent
  traffic on port 8790. The controller never initiates outbound connections to agents. Each agent
  (`AgentMain`) establishes two outbound connections to port 8790 and exposes no listening socket
  of its own.
- **The agent channel.** Requests to `POST /v1/channel/control` handle agent registration,
  heartbeat long-polling, and command delivery, where the controller parks each poll for at most
  1 second. Requests to `POST /v1/channel/telemetry` stream interval latency histograms. Port 8790
  is reserved exclusively for agent communication.
- **Resident agents.** Agent processes remain resident across multiple test runs, terminating only
  upon receiving SIGTERM or an explicit `shutdown` command. When signalled with SIGTERM, an agent
  cancels any active workload, transmits a `goodbye` message, and terminates within 5 seconds.
- **Exit status** of both processes: 0 indicates a clean requested stop, 1 indicates an unexpected
  runtime error, and 2 indicates an invalid configuration or an agent registration rejected by the
  controller.
- **Operational identity.** Each agent is identified by a logical id (`PHERF_AGENT_ID`, defaulting
  to the host name) paired with a fresh incarnation generated on every process startup. When a
  known agent id registers with a new incarnation, the controller replaces the prior registration
  record.
- **Version rule.** The controller and all agents in a fleet are deployed from a single build.
  Parsers ignore unrecognized fields for forward compatibility, whereas any incompatible schema
  change migrates the endpoint paths to `/v2/`. Any message carrying a mismatched protocol version
  `v` is refused with HTTP 400 and error code `rejected`.

## Steps

### Build the image and deploy

1. Build the pherf container image from your working tree if it is not already current. You can
   build it using px-cluster build, which also compiles the harness container image:

   ```sh
   px-cluster -e PX_PHERF=1 build
   ```

   ```text
   ... the JRE loads com.sun.net.httpserver.HttpServer from jdk.httpserver
   ```

   During the first runtime verification step of the image build, the container JRE loads the
   built-in JDK HTTP server. Any base JRE lacking the `jdk.httpserver` module fails the build at
   this step because both listeners depend on it.

2. Resolve the computed image tag from the harness configuration and verify that the image is
   present in the local Docker daemon.

   ```sh
   export PX_PHERF_IMAGE_TAG="$(px-cluster config PX_PHERF_IMAGE_TAG | cut -f 2)"
   docker image inspect "$PX_PHERF_IMAGE_TAG" --format '{{.Config.User}} {{.Config.Entrypoint}}'
   ```

   ```text
   501:20 [/usr/bin/tini -- /opt/pherf/bin/pherf-distributed.sh]
   ```

   The container executes as an unprivileged user matching the uid and gid of the build host. The
   init binary tini runs as PID 1, forwarding termination signals to the primary JVM and reaping
   any Pherf child JVMs.

3. Verify that startup validation enforces the environment contract and rejects an incomplete
   configuration. Starting an agent requires both `PHERF_CONTROLLER` and `PHERF_ZOOKEEPER`.

   ```sh
   docker run --rm "$PX_PHERF_IMAGE_TAG" agent start
   docker run --rm -e PHERF_CONTROLLER=http://controller:8790 "$PX_PHERF_IMAGE_TAG" agent start
   ```

   ```text
   ... ERROR [main] [] agent.AgentMain: bad configuration: PHERF_CONTROLLER is not set; use
   http://host:8790
   ... ERROR [main] [] agent.AgentMain: bad configuration: PHERF_ZOOKEEPER is not set; use the
   ZooKeeper quorum of the cluster, host:port
   ```

   The process validates its environment variables at startup before attempting any network
   connections. The [Environment][env] section of the module README documents every supported
   variable along with the invalid values that cause the process to exit with code 2.

4. Launch the standalone topology consisting of one controller and three agents configured with a
   dummy ZooKeeper quorum.

   ```sh
   export PHERF_ZOOKEEPER=zk.invalid:2181
   docker compose -f "$C" up -d --wait
   docker compose -f "$C" ps --format '{{.Name}}\t{{.State}}\t{{.Health}}\t{{.Ports}}'
   ```

   ```text
    Container px-pherf-standalone-agent-1 Healthy
   px-pherf-standalone-agent-1       running  healthy  8780/tcp, 8790/tcp
   px-pherf-standalone-agent-2       running  healthy  8780/tcp, 8790/tcp
   px-pherf-standalone-agent-3       running  healthy  8780/tcp, 8790/tcp
   px-pherf-standalone-controller-1  running  healthy  127.0.0.1:8780->8780/tcp
   ```

   Both environment variables must remain exported for every Compose invocation, including `down`,
   because the Compose file enforces their presence via `${NAME:?...}` parameter expansion. Note
   the key characteristics of this deployment:

   - The Compose project name is `px-pherf-standalone`, and it carries no harness labels because it
     is independent of any managed harness cluster.
   - The controller container is capped at 1024 MiB of memory and each agent is capped at 1536 MiB
     with equal swap limits, totaling 1024 + 3 x 1536 = 5632 MiB (5.5 GiB). Passing
     `--scale agent=N` adjusts the number of agent replicas.
   - Both service roles configure `/opt/pherf/healthcheck.sh controller|agent` to run every 5 s
     with a 60 s startup grace period. The controller probe issues a single `GET /v1/agents`
     request, while the agent probe verifies local process liveness via
     `pherf-distributed.sh agent status` and then issues one request to the agent channel.
   - Only the REST API port is exposed to the host on `127.0.0.1:8780`, keeping the internal agent
     channel on port 8790 isolated within the private Compose network.

   Container health does not by itself guarantee that an agent has completed registration. The
   fleet is ready for work only when `GET /v1/agents` returns the expected number of registered
   agents (verified in step 7).

5. Confirm that the internal agent channel on port 8790 cannot be reached from the host network.

   ```sh
   curl -sS --max-time 2 http://127.0.0.1:8790/v1/channel/control
   ```

   ```text
   curl: (7) Failed to connect to 127.0.0.1 port 8790 ...
   ```

   The curl invocation exits with status 7 because no process on the host listens on port 8790.

6. Inspect the running process tree and active JVM heap flags inside one of the agent containers.

   ```sh
   docker compose -f "$C" exec agent ps -eo pid,user,args | grep -E '[t]ini|[A]gentMain' |
     cut -c 1-96
   ```

   ```text
       1 pherf    /usr/bin/tini -- /opt/pherf/bin/pherf-distributed.sh agent start
     ... pherf    /opt/java/openjdk/bin/java -Djava.io.tmpdir=/tmp -XX:MaxRAMPercentage=35 ...
   ```

   The deployment configures `PHERF_JAVA_OPTS` with `-XX:MaxRAMPercentage=35` for an agent and 50
   for the controller. Because an agent launches Pherf workloads in a child JVM that inherits the
   same options, the two JVMs inside an agent container can each allocate up to 35% of the
   1536 MiB container limit as heap (70% combined), leaving the remaining capacity for non-heap
   memory. Always size `PHERF_JAVA_OPTS` on an agent to accommodate both the resident agent and one
   Pherf child JVM simultaneously rather than sizing for the agent process alone.

### Read the agents and the script verbs

7. Query the controller for the list of registered agents.

   ```sh
   curl -fsS "$PF/agents" | tee "$HOWTO/agents-before.json" |
     jq -r '.agents[] | "\(.id) \(.state) \(.incarnation) \(.offsetMs) \(.delayMs)"'
   ```

   ```text
   3156095d3d66 IDLE 84b950cb-9c30-4aae-9858-af0f46edbd7f 20 49
   982b8b7f8e69 IDLE b3cfd988-4f7e-4fe2-8c30-575ad2287e5c 20 47
   9837e11f37c6 IDLE bb5d79f0-a8a7-49cd-bcf8-58e009bb1ffb 20 48
   ```

   The output columns display the agent id, operational state, incarnation, `offsetMs`, and
   `delayMs`. Because the Compose file does not explicitly set `PHERF_AGENT_ID`, each agent uses
   its container host name as its id. A fresh incarnation identifier is generated every time an
   agent process starts. Here, `offsetMs` represents the estimated clock offset of the agent
   (agent minus controller), and `delayMs` records the round-trip delay of its best control
   exchange. The controller continuously derives both metrics from heartbeat polls, and [D02][d02]
   uses them to coordinate step start times.

8. Exercise the lifecycle script verbs inside the running containers.

   ```sh
   docker compose -f "$C" exec controller pherf-distributed.sh controller status
   docker compose -f "$C" exec agent pherf-distributed.sh agent status
   docker compose -f "$C" exec -e PHERF_AGENT_ID=nobody agent pherf-distributed.sh agent status
   ```

   ```text
   controller running as pid 7
   agent running as pid 7
   agent not running
   ```

   The `status` verb exits with code 0 when the role process is live and code 3 when it is not.
   Inside a container, tini runs as pid 1 and the lifecycle script replaces its own shell process
   with the JVM at pid 7. The third command queries an agent id that has no pid file. The supported
   script verbs are `start`, `stop`, `restart`, and `status`:

   - `start` runs in the foreground and replaces itself with the JVM so that log output streams
     directly to standard output and signals reach the JVM. It exits with code 1 if a live process
     for that role already exists.
   - `stop` sends TERM, waits up to 15 seconds for termination, and then escalates to KILL (exiting
     with code 1 after sending KILL).
   - A recorded pid is considered live only when the process responds and its command line matches
     both the main class and the role id, ensuring that a recycled pid belonging to another process
     is never treated as live.
   - In a containerized deployment, restart services using `docker restart` or
     `docker compose restart` rather than invoking the `restart` verb, because `stop` terminates
     the primary JVM—and therefore the container itself—before `start` can run.

### Tour the REST API and its error contract

9. Inspect the REST error contract. Every non-2xx HTTP response returns a JSON payload formatted as
   `{"error": "<code>", "message": "<text>"}`, where the error codes form the stable client
   contract.

   ```sh
   A="$(jq -r '.agents[0].id' "$HOWTO/agents-before.json")"
   curl -sS -w ' HTTP %{http_code}\n' "$PF/tests/t-nope/runs/r-nope/status"
   curl -sS -i -X DELETE "$PF/agents" | grep -E '^HTTP|^Allow|error'
   curl -sS -w ' HTTP %{http_code}\n' -X POST -d '{"x": 1}' "$PF/agents/$A/reset"
   ```

   ```text
   {"error":"not_found","message":"no test t-nope"} HTTP 404
   HTTP/1.1 405 Method Not Allowed
   Allow: GET
   {"error":"method_not_allowed","message":"DELETE is not allowed; use GET"}
   {"error":"bad_request","message":"the body of this request must be empty or {}"} HTTP 400
   ```

   - Requesting an identifier that either fails pattern validation or is unknown to the controller
     produces HTTP 404 with code `not_found`. The controller never constructs filesystem paths
     directly from input.
   - Sending an unsupported HTTP method returns HTTP 405 with code `method_not_allowed` and an
     `Allow` header.
   - Endpoints that take no input parameters accept only an empty payload or `{}`, returning HTTP
     400 with code `bad_request` for any other request body.
   - The remaining error codes are HTTP 409 `conflict`, HTTP 413 `too_large` (for a request body
     exceeding 16 MiB), and HTTP 500 `internal`.

10. Issue a reset request against an agent that has no active fault. Resetting a healthy agent
    succeeds as an idempotent no-op.

    ```sh
    curl -fsS -X POST -d '' "$PF/agents/$A/reset" | jq -c '{id, state, fault}'
    ```

    ```text
    {"id":"3156095d3d66","state":"IDLE","fault":null}
    ```

    See [D03][d03] for the rules governing `FAULTED` agents and the conditions under which a reset
    returns HTTP 409.

### Send plans that the submit check refuses

11. Create a baseline plan file and define two shell helper functions. The baseline plan satisfies
    the controller's plan parser because the controller does not parse the embedded scenario XML
    text at submission time.

    ```sh
    cat >"$HOWTO/base.json" <<'EOF'
    {"seed": 8070, "runClass": "validation",
     "steps": [{"agents": 2, "scenario": "s.xml", "documents": {"s.xml": "<datamodel/>"},
                "args": ["-q", "-disableSchemaApply"],
                "rate": 100, "threads": 2, "rows": 1000}]}
    EOF
    plan() { jq -c "$1" "$HOWTO/base.json" | curl -sS -X POST \
      -H 'Content-Type: application/json; charset=utf-8' --data-binary @- "$PF/tests"; echo; }
    create() { curl -fsS -X POST -H 'Content-Type: application/json; charset=utf-8' \
      --data-binary "@$1" "$PF/tests" | jq -r .id; }
    ```

    The `plan` helper mutates the baseline plan and prints the controller's raw response, whereas
    `create` posts a plan file and prints the resulting test id.

12. Submit a representative set of invalid plans to trigger each validation rule. The parser in
    `RunSpec.parse` enforces strict validation and prefixes every error message with the exact JSON
    path of the offending element.

    ```sh
    plan '.steps[0].durationMS = 1000'
    plan '.steps[0].weights = [1, 2, 3]'
    plan '.steps[0].args += ["-targetRate", "50"]'
    plan '.steps[0].args += ["--label", "x"]'
    plan '.steps[0].args += ["-z=zk:2181"]'
    plan '.steps[0].args += ["-analyze"]'
    plan '.steps[0].args += ["-t"]'
    plan '.properties = {"pherf.default.results.dir": "/tmp/x"}'
    plan 'del(.steps[0].rows) | .steps[0].documents["s.xml"] = "${pherf.slot.offset}"'
    plan '.steps[0].rate = 1'
    plan '.properties = {"pherf.verdict.noisyHost": "maybe"}'
    ```

    ```text
    {"error":"bad_request","message":"steps[0].durationMS: unknown key"}
    {"error":"bad_request","message":"steps[0].weights: 3 weights for 2 agents"}
    {"error":"bad_request","message":"steps[0].args[2]: -targetRate belongs to the agent; use
    the step field rate"}
    {"error":"bad_request","message":"steps[0].args[2]: -label belongs to the agent; the agent
    sets it"}
    {"error":"bad_request","message":"steps[0].args[2]: -z belongs to the agent; the agent takes
    it from PHERF_ZOOKEEPER"}
    {"error":"bad_request","message":"steps[0].args[2]: -analyze is not allowed in a distributed
    run"}
    {"error":"bad_request","message":"steps[0].args[2]: -t is not allowed in a distributed run"}
    {"error":"bad_request","message":"properties[\"pherf.default.results.dir\"]: belongs to the
    agent"}
    {"error":"bad_request","message":"steps[0]: ${pherf.slot.offset} needs rows"}
    {"error":"bad_request","message":"steps[0].rate: slot 1 gets a share of 0 of 1"}
    {"error":"bad_request","message":"properties: pherf.verdict.noisyHost=maybe is not true or
    false"}
    ```

    Each response above is emitted as a single line of output (wrapped here for readability) and
    illustrates the plan validation rules:

    - Unknown keys, duplicate keys, non-integer numbers, and values of the wrong JSON type are all
      treated as errors so that a misspelled field is never silently ignored.
    - The agent exclusively sets `-scenarioFile`, `-label`, `-z`, `-runDurationMs`, `-threadCount`,
      `-targetRate`, `-rowCountOverride`, and `-validate`. The validator detects each option across
      all syntax variations: `-x`, `--x`, and `-x=v`. Specify the rate, duration, threads, and rows
      as structured step fields so that the controller partitions them across slots.
    - Modes that bypass scenario validation or route through the query server are refused in
      distributed runs: `-analyze`, `-compare`, `-listFiles`, `-h`, `-t`, and `-s`, along with
      their long option names.
    - The agent manages the properties `pherf.default.results.dir` and
      `pherf.default.hdr.intervalMs`.
    - Using the `${pherf.slot.offset}` placeholder requires `rows` to be specified on the step
      because the offset is calculated as a cumulative sum of row shares.
    - Any partitioning that yields a share of 0 is refused because Pherf treats 0 as "no override,"
      which would cause that slot to execute the unpartitioned value from its XML document. A row
      share exceeding 2147483647 is also refused.
    - Any run-level `pherf.verdict.*` property must carry a value accepted by Pherf
      ([VERDICT.md][verdict]).
    - The serialized `assign` command for slot 0 must fit at least 1 KiB below the 16 MiB message
      body limit.

13. Observe the boundary of the option syntax check. Because commons-cli also allows a short option
    and its value to be concatenated into a single token (for example, `-zhost:2181`), the submit
    check does not intercept that combined form.

    ```sh
    T_Z="$(plan '.steps[0].args += ["-zhost:2181"]' | jq -r .id)"; echo "$T_Z"
    curl -fsS -X POST -d '' "$PF/tests/$T_Z/end" | jq -c '{id, state}'
    curl -fsS -X POST -d '' "$PF/tests/$T_Z/end" | jq -c '{id, state}'
    ```

    ```text
    t-20261123T235507002Z-1
    {"id":"t-20261123T235507002Z-1","state":"ENDED"}
    {"id":"t-20261123T235507002Z-1","state":"ENDED"}
    ```

    The controller accepts the plan, which is a known limitation of the submit check (round D2).
    Never supply an agent-owned option in any syntax, because the agent owns the ZooKeeper quorum
    configuration. The subsequent commands immediately end the test. Once a test has ended, it
    accepts no further runs, and sending a second `end` request idempotently returns HTTP 200.

### See the free-agent check and the fleet validation

14. Scale the agent fleet down to 2 replicas and then submit a run for a plan requiring 3 agents.
    Note that the modified plan also increases the thread count to 3: splitting 2 threads across 3
    agents assigns slot 2 a share of 0, causing the controller to reject the test at submission
    time with HTTP 400 and `steps[0].threads: slot 2 gets a share of 0 of 2`.

    ```sh
    docker compose -f "$C" up -d --wait --scale agent=2
    docker compose -f "$C" logs controller | grep 'said goodbye'
    jq '.steps[0].agents = 3 | .steps[0].threads = 3' "$HOWTO/base.json" >"$HOWTO/plan-3.json"
    T3="$(create "$HOWTO/plan-3.json")"
    curl -sS -w ' HTTP %{http_code}\n' -X POST -d '' "$PF/tests/$T3/runs"
    ```

    ```text
    controller-1  | ... INFO  [pherf-controller-http-4] [982b8b7f8e69] controller.Controller:
    agent 982b8b7f8e69 said goodbye
    {"error":"conflict","message":"step 0 needs 3 agents but 2 are free"} HTTP 409
    ```

    When scaled down, the removed agent received SIGTERM, transmitted `goodbye`, and left the
    controller's agent table immediately. Submitting a run whose step requires more agents than are
    currently free fails with HTTP 409. Whenever one or more agents are in the `FAULTED` state, the
    error message appends `; faulted: <ids>`.

15. Submit a plan that is structurally valid JSON but whose per-agent workload share fails the
    Pherf validator. The closed-loop scenario fixture from the module test suite defines 2 reader
    workers. With weights of 999:1 across 1000 rows, slot 1 receives only 1 row, leaving reader 1
    of 2 on that slot with no committed row to read.

    ```sh
    jq -n --rawfile s phoenix-pherf-distributed/src/test/resources/fleet/closed_loop_scenario.xml \
      '{seed: 8070, runClass: "validation",
        steps: [{agents: 2, weights: [999, 1], scenario: "closed_loop_scenario.xml",
                 documents: {"closed_loop_scenario.xml": $s},
                 args: ["-q", "-disableSchemaApply"], rows: 1000}]}' >"$HOWTO/plan-abort.json"
    T_AB="$(create "$HOWTO/plan-abort.json")"
    R_AB="$(curl -fsS -X POST -d '' "$PF/tests/$T_AB/runs" | jq -r .runId)"
    sleep 20
    curl -fsS "$PF/tests/$T_AB/runs/$R_AB/status" | jq -c '{state, reason},
      (.steps[0].slots[] | {slot: .identity.slot, state, rows: .share.rows})'
    ```

    ```text
    {"state":"ABORTED","reason":"agent 9837e11f37c6: cogen.walk /tmp/pherf-agent-9837e11f37c6-...
    /slice/closed_loop_scenario.xml scenario fleetClosedLoop querySet 1: SELECT: worker 1 of 2
    has no committed row at the start, with 1 committed rows in all; cogen.walk ... SCAN: worker
    1 of 2 has no committed block at the start, with 1 committed blocks in all"}
    {"slot":0,"state":"CANCELLED","rows":999}
    {"slot":1,"state":"FAILED","rows":1}
    ```

    If the status query still reports `RUNNING`, wait a few more seconds and poll again. Each
    assigned agent executed Pherf's offline validator in a child JVM against its own share, passing
    the same launch options planned for the run (`-rowCountOverride 999` and `1`). Because slot 1
    failed validation, the run transitioned to `ABORTED`:

    - `ABORTED` guarantees that no step received a `launch` command and the run generated no
      database traffic. Neither slot reached `ARMED`, and neither agent opened a connection to the
      dummy ZooKeeper quorum.
    - The failure reason records the first slot error, formatted as `agent <id>: ` followed by the
      validator finding, while the remaining slot received a `cancel` command.
    - During step 0 preparation, each participating agent also validates the slices for all later
      steps, ensuring that an invalid slice in any step aborts the run before the first `start`.
    - The share override options mutate the in-memory data models before the validation rules run,
      so the rules validate the effective per-slot share. Invoking `-validate` with launch
      overrides performs a two-phase check covering both the authored files as written and the
      models after overrides are applied. See [P02][p02], step 11.

    A standalone Pherf workload whose queries find no matching rows still exits with code 0
    (round D2). Consequently, the offline validator and the post-run verdict—not Pherf's process
    exit status—are what detect and reject such plans.

### Restart the controller

16. Record the active agent table, restart the controller container, and query the existing tests
    and runs.

    ```sh
    curl -fsS "$PF/agents" >"$HOWTO/agents-before-restart.json"
    docker compose -f "$C" restart controller
    sleep 8
    curl -fsS "$PF/tests/$T_AB/runs/$R_AB/status" | jq -c '{runId, state}'
    curl -sS -w ' HTTP %{http_code}\n' -X POST -d '' "$PF/tests/$T3/runs"
    ```

    ```text
    {"runId":"20261123T235516631Z-1","state":"ABORTED"}
    {"error":"conflict","message":"step 0 needs 3 agents but 2 are free"} HTTP 409
    ```

    The restarted controller restored both tests from `PHERF_RUN_DIR`, and the completed run
    retained its terminal state from its persisted `status.json`. All durable controller state is
    stored inside the run volume:

    - `tests/<testId>/plan.json` is written once at submission, whereas `tests/<testId>/test.json`
      serves as the atomic commit point for a test.
    - `<runId>/` contains the run bundle directory, writing `status.json` as state changes and
      committing `manifest.json` last.
    - Every file is persisted by writing to a temporary `.tmp` file, forcing it to disk, and
      performing an atomic move into place.

17. Verify that the resident agents automatically re-registered and that sequence numbers continue
    monotonically.

    ```sh
    jq -r '.agents[] | "\(.id) \(.incarnation) \(.registeredMs)"' \
      "$HOWTO/agents-before-restart.json"
    curl -fsS "$PF/agents" | jq -r '.agents[] | "\(.id) \(.incarnation) \(.registeredMs)"'
    create "$HOWTO/base.json"
    ```

    ```text
    3156095d3d66 84b950cb-9c30-4aae-9858-af0f46edbd7f 1795478081906
    9837e11f37c6 bb5d79f0-a8a7-49cd-bcf8-58e009bb1ffb 1795478081953
    3156095d3d66 84b950cb-9c30-4aae-9858-af0f46edbd7f 1795478152099
    9837e11f37c6 bb5d79f0-a8a7-49cd-bcf8-58e009bb1ffb 1795478152100
    t-20261123T235559304Z-4
    ```

    The controller does not persist its agent table on disk. Following the restart, each agent
    received a `reregister` response on its control poll and re-registered within roughly 6 seconds
    as a new table entry with an updated `registeredMs` timestamp. Each agent's incarnation
    remained identical because the agent processes themselves did not restart. Test and run
    sequence counters resume above the highest index present in the run directory so that
    identifiers never repeat. Any run that was actively in flight when the controller restarted is
    transitioned to `FAILED`, as shown in [D03][d03].

18. Verify exclusive run directory locking by starting a second controller instance against the
    same volume.

    ```sh
    docker compose -f "$C" run --rm --no-deps -T controller
    ```

    ```text
    ... ERROR [main] [] controller.ControllerMain: bad configuration: another controller uses
    PHERF_RUN_DIR /var/lib/pherf/run
    ```

    An active controller holds an exclusive lock on `controller.lock` for its entire lifetime,
    so a second controller instance pointed at the same directory logs a single waiting message and
    polls for up to 150 s to acquire the lock before terminating with exit code 2. Because
    `docker compose run` does not publish service ports by default, the second container does not
    compete for port 8780. Note that the `start` script verb writes its process id to
    `controller.pid` before the JVM checks the lock, so the failed startup leaves its own pid
    recorded in that file. The file lock—not the pid file—is what prevents a second controller from
    operating on a shared run directory. Because both containerized JVMs run as pid 7 inside their
    respective containers, running `controller status` in the first container continues to report
    `running as pid 7`.

## Security

Distributed Pherf provides neither authentication nor TLS encryption. Any client able to reach port
8780 can initiate database load and read run bundles, and any process able to reach port 8790 can
join the fleet and receive test plans. Restrict network exposure through `PHERF_BIND_ADDRESS` and
deployment topology: the standalone Compose file binds port 8780 exclusively to `127.0.0.1` and
does not publish port 8790. The REST listener bounds each request body at 16 MiB, validates every
identifier against its expected pattern, serves only regular files located inside a run directory,
and closes any connection that fails to transmit its full request within 60 seconds. It does not
bound the number of concurrent requests. See [Security][security] in the module README for
additional details.

## How to read the result

- An HTTP 400 response naming a JSON path indicates a plan validation error that requires fixing
  the plan. An HTTP 409 indicates a state conflict in which the fleet, test, or run is not in a
  state that permits the call. An HTTP 404 indicates an unknown or malformed id.
- `ABORTED` represents a safe pre-flight failure in which no agent sent database traffic. `FAILED`
  means that at least one step received `launch`, so traffic may have reached the database.
- After a controller restart, previously ended runs report identical state, and the agent fleet
  recovers automatically without operator intervention.

## How to reproduce it

Execute the steps using the same repository tree and image tag against a fresh run volume (run
`down -v` first). Generated identifiers and timestamps vary from run to run, whereas state
transitions, error codes, and validation messages remain deterministic.

## Clean up

1. Tear down the deployment and remove its volume. Both required environment variables must still
   be exported in your shell.

   ```sh
   docker compose -f "$C" down -v
   ```

2. Confirm that no containers, volumes, or networks belonging to the project remain.

   ```sh
   L=label=com.docker.compose.project=px-pherf-standalone
   docker ps -aq --filter "$L" | wc -l
   docker volume ls -q --filter "$L" | wc -l
   docker network ls -q --filter "$L" | wc -l
   ```

   Each command outputs a count of 0.

3. Delete the temporary files created during this HOWTO.

   ```sh
   rm -rf "$HOWTO"
   ```

## See also

- [D02: run a plan on a fleet][d02], the next HOWTO in this series.
- [The module README][readme], specifically [Plans][plans], [Agent channel][channel],
  [REST API][rest], [Persistence and restart][persist], and [Scripts][scripts].
- [P02: author a scenario and validate it offline][p02].
- [The Distributed Pherf HOWTO index](README.md).

[readme]: ../../README.md
[env]: ../../README.md#environment
[plans]: ../../README.md#plans
[channel]: ../../README.md#agent-channel
[rest]: ../../README.md#rest-api
[persist]: ../../README.md#persistence-and-restart
[scripts]: ../../README.md#scripts
[security]: ../../README.md#security
[verdict]: ../../../phoenix-pherf/VERDICT.md
[d02]: 02-first-fleet-run.md
[d03]: 03-faults-cancel-and-restart.md
[h02]: ../../../dev/harness/docs/howto/02-first-cluster-and-smoke.md
[p02]: ../../../phoenix-pherf/docs/howto/02-scenario-authoring-and-validation.md
