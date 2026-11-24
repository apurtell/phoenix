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

# How to cancel runs, handle agent faults and restart the controller (D03)

## The question

When an operator halts an active run, an agent terminates unexpectedly mid-run, or the controller
restarts, does the distributed fleet fail safely? In this design, failing safely guarantees that no
run is reported complete when it has lost data, no telemetry frame is counted twice, agents return
to the idle state autonomously, the diagnostic evidence of each ended run remains readable, and
subsequent runs require no manual repair. This HOWTO cancels active runs, kills an agent and the
controller during execution, and inspects the run states, output bundles, and agent table following
each fault.

## Before you start

**Audience.** Operators who execute fleet replicates or fault-injection suites, and contributors
who modify the controller implementation.

**Prerequisites.**

- [D02][d02], steps 1 to 9, completed on a cluster that remains running: the tier is operational,
  the table `PX_HOWTO.D02` contains 6000 rows, and `$PX_OUT/howto/d02/plan-run.json` is present on
  disk. If you already executed the clean-up phase of D02, run steps 1 to 9 again before
  proceeding.
- `curl`, `jq` 1.6 or later, and `shasum`.

| Cost | Value |
|---|---|
| Wall time | approximately 5 minutes |
| Docker memory | 12800 MiB across container limits, matching D02 |
| Disk | a few MiB of storage |
| Files written | `$HOWTO/plan-long.json`, `agents-*.json`, `sha-*.txt` |

Initialize the session by setting the environment variables and shell helper functions from D02:

```sh
export PATH="$PWD/dev/harness/bin:$PATH"
export PX_OUT="${PX_OUT:-$HOME/phoenix-harness}"
HOWTO="$PX_OUT/howto/d03"; mkdir -p "$HOWTO"
D02="$PX_OUT/howto/d02"
PF=http://127.0.0.1:8780/v1
create() { curl -fsS -X POST -H 'Content-Type: application/json; charset=utf-8' \
  --data-binary "@$1" "$PF/tests" | jq -r .id; }
submit() { curl -fsS -X POST -d '' "$PF/tests/$1/runs" | jq -r .runId; }
state() { curl -fsS "$PF/tests/$1/runs/$2/status" | jq -c '{state, reason}'; }
until_running() {
  until curl -fsS "$PF/tests/$1/runs/$2/status" |
    jq -e '[.steps[0].slots[].state] | all(. == "RUNNING")' >/dev/null; do sleep 1; done
}
until_ended() {
  while [ "$(curl -fsS "$PF/tests/$1/runs/$2/status" | jq -r .state)" = RUNNING ]; do
    sleep 1
  done
}
until_idle() {
  local i
  for i in $(seq 30); do
    curl -fsS "$PF/agents" | jq -e '[.agents[].state] | all(. == "IDLE")' >/dev/null && return 0
    sleep 1
  done
  return 1
}
```

The `submit TEST` helper launches a run and outputs its identifier. The `until_running TEST RUN`
function polls until every slot reaches `RUNNING`, while `until_ended TEST RUN` polls until the
overall run state is no longer `RUNNING`. Because `until_running` enforces no timeout bound, if a
run terminates before its slots enter execution, interrupt the loop with Ctrl-C and inspect the
outcome via `state TEST RUN`. Finally, `until_idle` polls until every agent in the registry reports
`IDLE`, waiting up to a maximum bound of 30 s before returning exit status 1.

## Method

**Hypothesis.** Cancelling an active run transitions it to `CANCELLED` within about 5 seconds and
commits its diagnostic bundle. When an agent dies unexpectedly, the controller transitions the run
to `FAILED` within about 10 seconds, and the restarted agent rejoins the registry as a new record.
Restarting the controller marks any in-flight run as failed without producing a manifest, while
continuing to serve every previously ended run identically, byte for byte. None of these scenarios
requires a manual agent reset.

**Controls.** The experiment reuses the D02 tier, database table, and run plan, configured with an
extended execution window (60 s) to ensure that each injected fault lands squarely within the
measured window. Only one fault is introduced at a time.

**Noise.** The exact moment at which a fault occurs within the execution window varies by several
seconds. Verification therefore asserts deterministic states, failure reasons, and content hashes
rather than elapsed durations, with the sole exception of the heartbeat eviction deadline, which
enforces a floor of 10 s.

**What to check and why.** We inspect the run state and reason, the individual slot states, the
bundle manifest state, the `pherf.fleet.agents` verdict row, the live agent registry table, and the
sha256 digests of the generated reports. Automated drivers evaluate these exact artifacts to
determine whether a test replicate is valid and countable.

**What invalidates the result.** Injecting a fault before the slots reach `RUNNING` exercises the
preparation path rather than the active execution path. The shell helpers explicitly wait for
`RUNNING` first so that every fault targets active workload execution.

## Steps

### Cancel a run

1. Create a test definition configured with an execution window of 60 s.

   ```sh
   jq '.steps[0].durationMs = 60000' "$D02/plan-run.json" >"$HOWTO/plan-long.json"
   T1="$(create "$HOWTO/plan-long.json")"
   ```

2. Submit a run, wait until all slots enter execution, and issue a cancellation request.

   ```sh
   R1="$(submit "$T1")"; until_running "$T1" "$R1"
   curl -fsS -X POST -d '' "$PF/tests/$T1/runs/$R1/cancel" | jq -c '{state, reason}'
   until_idle
   curl -fsS "$PF/agents" | jq -r '.agents[] | "\(.id) \(.state)"'
   ```

   ```text
   {"state":"CANCELLED","reason":"cancelled"}
   pherf-agent-0 IDLE
   pherf-agent-1 IDLE
   ```

   Each participating agent receives the `cancel` directive, delivers SIGTERM to its Pherf child
   process, escalates to SIGKILL after 5 s if the child remains alive, and reports back as idle
   only after the child process has terminated. Cancelling a run does not mark any agent as
   faulted.

3. Inspect the output bundle of the cancelled run. Even when cancelled, a run produces a committed
   bundle and a summary report.

   ```sh
   B="$PF/tests/$T1/runs/$R1/bundle"
   curl -fsS "$B/manifest.json" | jq -c '.run | {state, result, reason}'
   curl -fsS "$B/drivers/pherf/results_HDR/VERDICT_run.tsv" | grep fleet.agents | cut -f 1,2
   curl -fsS "$B/status.json" | jq -c '[.steps[0].slots[].state]'
   ```

   ```text
   {"state":"CANCELLED","result":"FAIL","reason":"cancelled"}
   pherf.fleet.agents	FAIL
   ["CANCELLED","CANCELLED"]
   ```

   The top-level `CANCELLED` state alone does not indicate whether workload traffic occurred;
   instead, the individual slot states, dispatch timestamps, and retained telemetry frames record
   how much work executed prior to termination. Because `pherf.fleet.agents` evaluates to FAIL, no
   automated driver can mistake a cancelled run for a valid replicate.

4. Issue a second cancellation request against the same run.

   ```sh
   curl -sS -o "$HOWTO/cancel-again.json" -w 'HTTP %{http_code}\n' -X POST -d '' \
     "$PF/tests/$T1/runs/$R1/cancel"
   jq -c '{state, reason}' "$HOWTO/cancel-again.json"
   ```

   ```text
   HTTP 200
   {"state":"CANCELLED","reason":"cancelled"}
   ```

   Cancelling an already cancelled run is idempotent and returns HTTP 200 along with the stored run
   view. Step 9 demonstrates the HTTP 409 conflict returned when attempting to cancel a run that
   terminated in any other state.

5. End the test while a run is actively executing.

   ```sh
   R2="$(submit "$T1")"; until_running "$T1" "$R2"
   curl -fsS -X POST -d '' "$PF/tests/$T1/end" | jq -c '{id, state, runs}'
   state "$T1" "$R2"
   until_idle
   ```

   ```text
   {"id":"t-20261124T000322309Z-2","state":"ENDED","runs":["20261124T000322326Z-2",
   "20261124T000331623Z-3"]}
   {"state":"CANCELLED","reason":"cancelled: the test ended"}
   ```

   Invoking `end` automatically cancels any `RUNNING` run associated with the test and closes the
   test to future run submissions. As in step 2, the agents return to idle within about 5 s of the
   cancellation. Submitting a new run before the agents finish exiting returns `409 step 0 needs 2
   agents but 0 are free`, so `until_idle` prevents step 6 from encountering that 409.

### Lose an agent in the middle of a run

6. Record the current agent registry table, then launch a run under a newly created test.

   ```sh
   curl -fsS "$PF/agents" >"$HOWTO/agents-before-kill.json"
   T2="$(create "$HOWTO/plan-long.json")"
   R3="$(submit "$T2")"; until_running "$T2" "$R3"
   ```

7. Terminate agent 1 abruptly without a graceful shutdown, and observe the run lifecycle.

   ```sh
   px-cluster kill pherf-agent 1; date -u +%T
   until_ended "$T2" "$R3"; date -u +%T
   state "$T2" "$R3"
   curl -fsS "$PF/tests/$T2/runs/$R3/status" | jq -c '[.steps[0].slots[] | {agent, state}]'
   until_idle
   curl -fsS "$PF/agents" | jq -r '.agents[] | "\(.id) \(.state)"'
   ```

   ```text
   ... INFO  px-cluster: recorded planned kill pherf-agent/1 in .../planned-px-default.tsv
   00:14:12
   00:14:23
   {"state":"FAILED","reason":"agent pherf-agent-1 missed its heartbeat deadline"}
   [{"agent":"pherf-agent-0","state":"CANCELLED"},{"agent":"pherf-agent-1","state":"FAILED"}]
   pherf-agent-0 IDLE
   ```

   The two timestamps are separated by roughly 10 to 12 seconds (11 s in this run). Because the
   agent process died abruptly, it sent no departure notice. The controller evicts any agent from
   which it receives no request for more than 10 seconds of monotonic time. Eviction fails the
   deceased agent's slot and consequently fails the run, while the surviving agent receives
   `cancel` and returns to idle. Without waiting on `until_idle`, `pherf-agent-0` may transiently
   still report `RUNNING` because it transitions to idle only after its child process exits. The
   controller records the run as `FAILED` rather than `ABORTED` because the step had already passed
   `launch`, meaning workload traffic reached the database. Eviction also purges the agent entry,
   so `pherf-agent-1` disappears from the registry table.

   The keepalive protocol design ensures that this deadline remains safe for healthy agents. The
   controller holds each control poll for at most 1 second before replying, and the agent issues
   its next poll immediately, causing a live agent to contact the controller every second. Because
   the client read timeout on an agent request is 5 seconds—exceeding the hold duration while
   remaining well below the eviction threshold—a single lost reply triggers a retry after about 6
   seconds, before either side evicts the other. The idle long-poll period is therefore strictly
   shorter than the reaper deadline. Symmetrically, an agent that receives no response on either
   stream for more than 10 seconds cancels its local execution, ensuring that an evicted agent does
   not leave its Pherf child running against the database.

8. Restart the terminated agent and compare the resulting registry records.

   ```sh
   px-pherf start >/dev/null
   jq -r '.agents[] | "\(.id) \(.incarnation)"' "$HOWTO/agents-before-kill.json"
   curl -fsS "$PF/agents" | jq -r '.agents[] | "\(.id) \(.incarnation) \(.state)"'
   ```

   ```text
   pherf-agent-0 895bf17e-bb2c-49cd-8526-66f4963d3a86
   pherf-agent-1 3efb3ca5-1db4-4f94-972e-29ac6c006409
   pherf-agent-0 895bf17e-bb2c-49cd-8526-66f4963d3a86 IDLE
   pherf-agent-1 09460a84-a3be-4b00-a089-261104d778b3 IDLE
   ```

   Because `px-pherf start` writes its preflight checks and readiness summary table to standard
   error, those lines still appear in the terminal above this output excerpt. Invoking
   `px-pherf start` starts the stopped instance in the agent group and waits until every agent
   reaches `IDLE`. Because `pherf-agent-1` is a newly started process, it advertises a new
   incarnation identifier and is stored as a distinct registry record. Any stale frames arriving
   from a previous incarnation are acknowledged and discarded without being held, ensuring that no
   telemetry frame is ever counted twice. In-place agent restarts during an active run are not
   supported: if an agent holding an active slot registers with a new incarnation, the controller
   immediately fails that slot.

9. Attempt to cancel the failed run.

   ```sh
   curl -sS -w ' HTTP %{http_code}\n' -X POST -d '' "$PF/tests/$T2/runs/$R3/cancel"
   ```

   ```text
   {"error":"conflict","message":"run 20261124T001408436Z-5 is FAILED, not RUNNING"} HTTP 409
   ```

   Attempting to cancel a run that has already terminated in any state other than `CANCELLED`
   returns HTTP 409.

### Faults and reset

10. Review the state transition rules governing `FAULTED`. The controller marks an agent faulted
    only when it detects a protocol or execution anomaly originating within the agent process
    itself after the slot has passed `launch`:

    - the agent transmits a histogram frame that fails to decode;
    - the controller detects lost or duplicated telemetry, indicated by a cumulative frame total
      that does not equal the previously held count plus the incoming frame;
    - the agent reports `DONE` without supplying a final report;
    - the agent unexpectedly transitions to idle while still assigned to an active slot.

    When an agent enters `FAULTED`, the controller dispatches `release` so the agent process can
    return to idle, but the scheduler assigns it no further slots until it is explicitly reset. By
    contrast, the following events do not fault an agent:

    - an operational error reported by the agent itself, such as a failure in its Pherf child
      process (caused by a workload or target cluster error) or a failure on its telemetry stream,
      because cluster faults during a fault-injection suite routinely trigger such errors and the
      subsequent replicate requires the full fleet;
    - a failure occurring prior to launch (such as a validation finding or a slice calculation
      error), which represents a test plan error rather than an agent defect;
    - an operator-initiated or test-end cancellation.

    Instead of marking the record faulted, a heartbeat eviction, a graceful `goodbye`, a new
    incarnation, or a misconfigured recorder removes the record outright, allowing the process to
    rejoin as a fresh record (step 8). When a faulted agent leaves the fleet short of required
    capacity, submitting a run returns `409 step 0 needs N agents but M are free; faulted: <ids>`.
    To return the agent to service, either replace its process or clear the fault via `POST
    /v1/agents/{id}/reset`. Because the controller does not persist agent fault state to disk, all
    agents register cleanly with no fault after a controller restart.

    No step in this HOWTO artificially induces a fault state, because every triggering condition
    reflects an internal defect in the agent process or corruption along the in-frame network path
    that cannot be synthesized through external harness commands. The module unit tests
    (`RestApiTest` and `FailureRuleTest`) verify those transitions directly, whereas this HOWTO
    demonstrates the administrative reset contract.

11. Reset an idle agent that has no fault, and then attempt a reset while the agent holds an
    active run slot.

    ```sh
    curl -fsS -X POST -d '' "$PF/agents/pherf-agent-0/reset" | jq -c '{id, state, fault}'
    R4="$(submit "$T2")"; until_running "$T2" "$R4"
    curl -sS -w ' HTTP %{http_code}\n' -X POST -d '' "$PF/agents/pherf-agent-0/reset"
    ```

    ```text
    {"id":"pherf-agent-0","state":"IDLE","fault":null}
    {"error":"conflict","message":"agent pherf-agent-0 has a slot of run 20261124T001433732Z-6"}
    HTTP 409
    ```

    Resetting an idle agent that has no recorded fault succeeds as an idempotent no-op. Because a
    reset never interrupts active execution, attempting to reset an agent that holds an assigned
    slot or is in the process of leaving returns HTTP 409. Leave run `R4` executing for the next
    step.

### Restart the controller in the middle of a run

12. Compute cryptographic digests of the reports for all previously ended runs. Both the cancelled
    runs and the failed run possess committed bundles on disk.

    ```sh
    sha() { curl -fsS "$PF/tests/$1/runs/$2/report" | shasum -a 256; }
    { sha "$T1" "$R1"; sha "$T1" "$R2"; sha "$T2" "$R3"; } >"$HOWTO/sha-before.txt"
    cat "$HOWTO/sha-before.txt"
    ```

    ```text
    a1ecf01d30f6027ef4a80a8cace592cdfea31c5bffd633ace964987f90acb2b2  -
    4d13ff73217785a775175631ac46d4699ea1effdac9408f2bff055716b8a3c99  -
    b3ec1fe61d8064712c7d73e5b54e5362088920cc7db534bafe9205e4465ba70a  -
    ```

    Each output line records the sha256 digest of a single run report archive.

13. Terminate the controller process abruptly while `R4` is executing, and then start the tier
    again.

    ```sh
    px-cluster kill pherf-controller 0
    px-pherf start
    ```

    ```text
    ... INFO  px-pherf: the tier runs: one controller and 2 IDLE agents
    pherf-agent-0	IDLE	895bf17e-bb2c-49cd-8526-66f4963d3a86
    pherf-agent-1	IDLE	09460a84-a3be-4b00-a089-261104d778b3
    ```

    The `kill` command terminates the controller process without a graceful shutdown while
    preserving the container instance and its persistent volume. Deprived of controller responses
    for more than 10 seconds, the agents autonomously cancel their active workload execution. Once
    the restarted controller begins accepting connections, each polling agent receives
    `reregister`, discards any buffered telemetry frames, and completes a fresh registration. The
    agent incarnation identifiers remain unchanged because the agent processes themselves never
    restarted.

14. Inspect the status of the run that was in flight during the outage, and recompute the report
    digests.

    ```sh
    state "$T2" "$R4"
    curl -sS -w ' HTTP %{http_code}\n' "$PF/tests/$T2/runs/$R4/report"
    { sha "$T1" "$R1"; sha "$T1" "$R2"; sha "$T2" "$R3"; } >"$HOWTO/sha-after.txt"
    diff "$HOWTO/sha-before.txt" "$HOWTO/sha-after.txt" && echo "reports byte-equal"
    ```

    ```text
    {"state":"FAILED","reason":"the controller stopped during the run"}
    {"error":"not_found","message":"run 20261124T001433732Z-6 has no committed bundle"}
    HTTP 404
    reports byte-equal
    ```

    During startup recovery, the controller scans and restores its persistent run directory. Any
    run directory containing `manifest.json` had already reached a terminal state, and its status
    view is loaded from `status.json`. By contrast, a run directory lacking a manifest and an
    ended `status.json` was still in flight when the process terminated: on restart,
    startup recovery transitions the run to `FAILED` with the reason `the controller stopped during
    the run`, and writes no manifest, so the run has neither a report nor a bundle. Every
    previously ended run continues to serve its frozen report, byte for byte.

15. Submit a new run under the existing test, which remains `OPEN` after recovery.

    ```sh
    R5="$(submit "$T2")"; echo "$R5"
    curl -fsS -X POST -d '' "$PF/tests/$T2/runs/$R5/cancel" | jq -c '{state}'
    ```

    ```text
    20261124T001448412Z-7
    {"state":"CANCELLED"}
    ```

    The run sequence number increments above the highest identifier discovered in the persistent
    run directory. Cancelling this verification run immediately simply saves waiting through the
    full window duration.

    By design, a run that was in flight during a controller restart is failed rather than resumed.
    Its partially merged histograms resided exclusively in volatile memory, and its participating
    agents aborted their work and discarded their buffered frames when re-registering. Moreover,
    its open-loop arrival schedule suffered an unrecoverable temporal gap, and a coordinated start
    epoch cannot be reissued in the middle of a step. Recovery classifies the run as `FAILED`
    rather than `ABORTED` because the new controller instance cannot prove that no step had already
    received `launch`. Delivering SIGTERM to the controller produces the exact same outcome as an
    abrupt crash: the controller performs no shutdown finalization, ensuring a single recovery
    code path.

### The run bound

The distributed module enforces no internal wall-clock time limit of its own on a run (round D7,
decision e). Instead, Pherf governs when each child process concludes, including when the target
database becomes unresponsive:

- On a slow cluster, an open-loop workload configured with `lagPolicy="LATE"` executes every
  scheduled arrival from its measurement window, continuing past the nominal window boundary until
  the accumulated backlog drains completely. No fixed grace period after `durationMs` can be
  universally correct: any chosen cutoff either prematurely fails a healthy run on a saturated
  fleet or waits too long to be useful. Configuring `lagPolicy="DROP"` together with `maxLagMs`
  explicitly bounds that backlog.
- On a stopped cluster, the first failed database operation halts the open-loop generator, which
  releases no further arrivals and aborts any operations already queued. The run then concludes as
  soon as the operations currently in flight terminate—namely, when their Phoenix client timeouts
  expire. A fault-injection scenario that requires rapid termination configures short client
  timeouts within the `phoenixProperties` map of its query set.
- Any external caller requiring a hard wall-clock deadline cancels the run explicitly via the REST
  `cancel` endpoint (step 2). The harness driver `pherf-distributed` issues this cancellation
  automatically when its verb time limit expires; [H15][h15] demonstrates that scenario and
  documents its known limitation (the resulting harness bundle contains no fleet report).

This design summary is drawn from the module README and the D7 and H3 architecture records; no
interactive step in this guide executes it.

## How to read the result

- A run state of `COMPLETE` paired with `pherf.fleet.agents` PASS is the sole indicator of a valid
  replicate. Terminal states of `CANCELLED`, `FAILED`, and `ABORTED` still commit a diagnostic
  bundle (except when a run was in flight during a controller restart), preserving readable failure
  evidence while ensuring that none of those runs is counted.
- Agent loss appears as `FAILED` with `missed its heartbeat deadline`, graceful agent stop as `left
  the fleet`, an agent restart as `restarted`. None of these events faults the agent.
- An HTTP 409 response containing `faulted:` is the only condition requiring operator intervention:
  reset or replace the specific agents listed in the message.

## How to reproduce it

Execute the steps in the exact sequence presented above using the D02 tier configuration and test
plan. Across independent executions, the generated run identifiers, wall-clock timestamps, and
agent incarnation UUIDs will vary, whereas the state transitions, failure reasons, and report hash
equality remain invariant.

## Clean up

1. Close the active test definition.

   ```sh
   curl -fsS -X POST -d '' "$PF/tests/$T2/end" | jq -c '{id, state}'
   ```

   ```text
   {"id":"t-20261124T001408421Z-6","state":"ENDED"}
   ```

2. Tear down the local cluster and delete the working directories for D02 and D03. Skip this
   entire step—including the `rm` command—if you are continuing directly to [D04][d04], as D04
   reuses the active cluster alongside `$PX_OUT/howto/d02/plan-run.json` and removes both working
   directories upon completion.

   ```sh
   px-cluster down
   rm -rf "$HOWTO" "$D02"
   ```

   ```text
   ... INFO  px-cluster: cluster default is down
   ```

## See also

- [D04: scale out the load generator][d04], the next HOWTO in this series.
- [H15: replicates, A/B and faults under fleet load][h15], together with [H09: fault injection][h09]
  for the harness recovery gate mechanics.
- Relevant sections of the module README: [Cancel, faults and reset][cfr],
  [Persistence and restart][persist], [Keepalives][keep], and [Guarantees][guar].

[d02]: 02-first-fleet-run.md
[d04]: 04-load-generator-scale-out.md
[h09]: ../../../dev/harness/docs/howto/09-fault-injection-and-recovery.md
[h15]: ../../../dev/harness/docs/howto/15-fleet-replicates-ab-and-faults.md
[cfr]: ../../README.md#cancel-faults-and-reset
[persist]: ../../README.md#persistence-and-restart
[keep]: ../../README.md#keepalives
[guar]: ../../README.md#guarantees
