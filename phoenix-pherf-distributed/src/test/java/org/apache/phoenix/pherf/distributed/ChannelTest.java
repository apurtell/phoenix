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
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.apache.phoenix.pherf.distributed.controller.RunView;
import org.apache.phoenix.pherf.distributed.protocol.Codec;
import org.apache.phoenix.pherf.distributed.protocol.Message;
import org.apache.phoenix.pherf.distributed.protocol.MessageType;
import org.apache.phoenix.pherf.distributed.protocol.Protocol;
import org.apache.phoenix.pherf.distributed.transport.InProcessNetwork;
import org.junit.Test;

/**
 * Verifies RQ-218 by confirming that every agent opens both a control stream and a telemetry
 * stream, and that each stream transports only its designated message types. In this communication
 * model, the controller never dials an agent and no agent listens for connections; AgentChannel
 * only responds to requests, a property that NetworkUseTest verifies on the class files.
 */
public class ChannelTest {
  private static final Set<MessageType> CONTROL = new HashSet<>(Arrays.asList(MessageType.REGISTER,
    MessageType.HEARTBEAT, MessageType.GOODBYE, MessageType.REGISTERED, MessageType.WAIT,
    MessageType.ASSIGN, MessageType.START, MessageType.CANCEL, MessageType.RELEASE,
    MessageType.SHUTDOWN, MessageType.REREGISTER, MessageType.REJECTED));
  private static final Set<MessageType> TELEMETRY =
    new HashSet<>(Arrays.asList(MessageType.INTERVALS, MessageType.ACK));

  @Test
  public void controlCarriesRegistrationHeartbeatsAndAssignmentTelemetryUsesTheSecondStream()
    throws Exception {
    Simulation simulation = new Simulation();
    simulation.agent("a1");
    simulation.agent("a2");
    simulation.runFor(1000);
    Map<String, String> properties = new LinkedHashMap<>();
    properties.put("fake.intervals", "5");
    String runId = simulation.controller.submit(Simulation.spec(7, properties, 2));
    simulation.runFor(15000);

    Map<String, List<String>> connections = new LinkedHashMap<>();
    Map<String, Set<MessageType>> types = new LinkedHashMap<>();
    for (InProcessNetwork.Event event : simulation.network.events()) {
      if (event.kind.equals(InProcessNetwork.CONNECT)) {
        connections.computeIfAbsent(event.agent, k -> new ArrayList<>()).add(event.stream);
        continue;
      }
      Message message = Codec.decode(event.json);
      Set<MessageType> allowed = event.stream.equals(Protocol.CONTROL) ? CONTROL : TELEMETRY;
      assertTrue(event.toString(), allowed.contains(message.type()));
      types.computeIfAbsent(event.agent + " " + event.stream, k -> new HashSet<>())
        .add(message.type());
    }
    for (String agent : Arrays.asList("a1", "a2")) {
      assertEquals(Arrays.asList(Protocol.CONTROL, Protocol.TELEMETRY), connections.get(agent));
      assertTrue(types.get(agent + " control").containsAll(Arrays.asList(MessageType.REGISTER,
        MessageType.REGISTERED, MessageType.HEARTBEAT, MessageType.ASSIGN, MessageType.START)));
      assertEquals(TELEMETRY, types.get(agent + " telemetry"));
    }
    assertEquals(2, connections.size());

    RunView run = simulation.run(runId);
    assertEquals(RunView.State.COMPLETE, run.getState());
    for (RunView.SlotView slot : run.getSteps().get(0).getSlots()) {
      assertEquals(5, slot.getHeldSeq());
      assertEquals(5, slot.getFinalSeq());
      assertEquals(5, slot.getFrames());
    }
    assertEquals(Arrays.asList(1L, 2L, 3L, 4L, 5L),
      simulation.workload("a1").emitted.subList(0, 5));
  }
}
