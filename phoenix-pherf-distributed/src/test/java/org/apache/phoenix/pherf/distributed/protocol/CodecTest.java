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
package org.apache.phoenix.pherf.distributed.protocol;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.google.gson.JsonObject;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collection;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.HdrHistogram.Histogram;
import org.HdrHistogram.HistogramLogWriter;
import org.apache.phoenix.pherf.distributed.identity.DataPlaneIdentity;
import org.apache.phoenix.pherf.latency.LatencyRecorders;
import org.junit.Test;

public class CodecTest {
  private static final String AGENT = "a1";
  private static final String INCARNATION = "8c4f1b8e-3f5e-4d5a-9a43-0f6d2a1b7c11";

  private static <T extends Message> T traced(T message, String trace) {
    message.setTrace(trace);
    if (message instanceof AgentMessage) {
      ((AgentMessage) message).setAgent(AGENT);
      ((AgentMessage) message).setIncarnation(INCARNATION);
    }
    return message;
  }

  private static Histogram pinned() {
    return new Histogram(LatencyRecorders.LOWEST_US, LatencyRecorders.HIGHEST_US,
      LatencyRecorders.DIGITS);
  }

  private static Histogram sampleHistogram() {
    Histogram histogram = pinned();
    for (long value = 1; value <= 5000; value++) {
      histogram.recordValue(value * 37 % 100000 + 1);
    }
    histogram.recordValue(LatencyRecorders.HIGHEST_US);
    return histogram;
  }

  /** Constructs one representative sample of each message type with every field populated. */
  private static List<Message> samples() {
    Map<String, String> documents = new LinkedHashMap<>();
    documents.put("scenario.xml", "<datamodel name=\"d\" seed=\"${pherf.slot.seed}\"/>");
    Map<String, String> properties = new LinkedHashMap<>();
    properties.put("pherf.default.hdr.intervalMs", "1000");
    Assignment later = new Assignment("20260101T000000000Z-1", 3,
      DataPlaneIdentity.derive(42, 3, 750), "scenario.xml", documents, Arrays.asList("-q"),
      new Share(60000L, 2L, 11L, 125L), properties);
    Assignment assignment =
      new Assignment("20260101T000000000Z-1", 2, DataPlaneIdentity.derive(42, 3, 1000),
        "scenario.xml", documents, Arrays.asList("-q", "-disableSchemaApply"),
        new Share(60000L, 4L, 33L, 250L), properties, Arrays.asList(later));
    Histogram histogram = sampleHistogram();
    IntervalFrame frame = new IntervalFrame(1, "query:s:q1", LatencyRecorders.STEADY, 1000, 2000,
      histogram.getTotalCount(), RecorderConfig.of(histogram), HistogramBlob.encode(histogram));
    Map<String, Long> counts = new LinkedHashMap<>();
    counts.put("query:s:q1|steady", histogram.getTotalCount());
    JsonObject schedule = new JsonObject();
    schedule.addProperty("scenario", "s");
    schedule.addProperty("startMs", 1767225601000L);
    FinalReport report = new FinalReport(1767225600000L, 1767225602000L, counts,
      Arrays.asList(schedule), Arrays.asList("pherf.health.client_pool\tPASS\tcount\tpherf\tok"));
    return Arrays.asList(traced(new Register(), "a1/control/1"),
      traced(
        new Heartbeat(2,
          new Status(Phase.DONE, "20260101T000000000Z-1", 2, 9, "none", 1767225601003L, report),
          new ClockSample(1767225601000L, 1767225601001L, 1767225601002L, 1767225601004L)),
        "a1/control/2"),
      traced(new Goodbye(), "a1/control/3"), traced(new Registered(), "a1/control/1"),
      traced(new Wait(), "a1/control/2"), traced(new Assign(assignment), "a1/control/4"),
      traced(new Launch("20260101T000000000Z-1", 2), "a1/control/5"),
      traced(new Start("20260101T000000000Z-1", 2, 1767225601000L), "a1/control/5"),
      traced(new Cancel("20260101T000000000Z-1"), "a1/control/6"),
      traced(new Release("20260101T000000000Z-1"), "a1/control/7"),
      traced(new Shutdown(), "a1/control/8"), traced(new Reregister(), "a1/control/9"),
      traced(new Rejected("replaced"), "a1/control/10"),
      traced(new Intervals("20260101T000000000Z-1", 2, Arrays.asList(frame)), "a1/telemetry/1"),
      traced(new Ack("20260101T000000000Z-1", 2, 1), "a1/telemetry/1"));
  }

  /**
   * Fails the assertion if any field of the message or of a nested value it holds is null or empty.
   * Because a subsequent slice contains no slices of its own, its nested validations are expected
   * to be absent.
   */
  private static void assertComplete(String path, Object value) throws IllegalAccessException {
    if (path.endsWith(".rxMs") || path.endsWith(".txMs")) {
      // Only the controller populates timestamps on a control reply; this behavior is verified by
      // stampsRoundTripAndAreAbsentElsewhere.
      return;
    }
    if (path.endsWith(".validations[].validations")) {
      assertNull(path, value);
      return;
    }
    assertNotNull(path + " is null", value);
    if (value instanceof Collection) {
      assertTrue(path + " is empty", !((Collection<?>) value).isEmpty());
      for (Object element : (Collection<?>) value) {
        assertComplete(path + "[]", element);
      }
      return;
    }
    if (value instanceof Map) {
      assertTrue(path + " is empty", !((Map<?, ?>) value).isEmpty());
      return;
    }
    if (!value.getClass().getName().startsWith("org.apache.phoenix.pherf.distributed")) {
      return;
    }
    for (Class<?> type = value.getClass(); type != Object.class; type = type.getSuperclass()) {
      for (Field field : type.getDeclaredFields()) {
        if (Modifier.isStatic(field.getModifiers()) || field.isSynthetic()) {
          continue;
        }
        field.setAccessible(true);
        assertComplete(path + "." + field.getName(), field.get(value));
      }
    }
  }

  @Test
  public void everyMessageTypeRoundTrips() throws Exception {
    Set<MessageType> covered = EnumSet.noneOf(MessageType.class);
    for (Message sample : samples()) {
      assertComplete(sample.type().wire(), sample);
      String json = Codec.encode(sample);
      Message decoded = Codec.decode(json);
      assertSame(sample.getClass(), decoded.getClass());
      assertEquals(sample.type(), decoded.type());
      assertEquals(json, Codec.encode(decoded));
      assertTrue(json, json.startsWith("{\"v\":1,\"type\":\"" + sample.type().wire() + "\""));
      covered.add(decoded.type());
    }
    assertEquals(EnumSet.allOf(MessageType.class), covered);
    assertEquals(15, MessageType.values().length);
  }

  /**
   * Verifies that controller receive and transmit timestamps on a control reply survive round-trip
   * serialization and are omitted from messages that do not carry them.
   */
  @Test
  public void stampsRoundTripAndAreAbsentElsewhere() throws Exception {
    Message reply = traced(new Wait(), "a1/control/2");
    reply.setStamps(1767225601001L, 1767225601999L);
    String json = Codec.encode(reply);
    assertEquals("{\"v\":1,\"type\":\"wait\",\"trace\":\"a1/control/2\","
      + "\"rxMs\":1767225601001,\"txMs\":1767225601999}", json);
    Message decoded = Codec.decode(json);
    assertEquals(Long.valueOf(1767225601001L), decoded.getRxMs());
    assertEquals(Long.valueOf(1767225601999L), decoded.getTxMs());
    String ack = Codec.encode(traced(new Ack("r", 0, 1), "a1/telemetry/1"));
    assertTrue(ack, !ack.contains("rxMs") && !ack.contains("txMs"));
    Message plain = Codec.decode(ack);
    assertNull(plain.getRxMs());
    assertNull(plain.getTxMs());
  }

  private static void assertSameHistogram(Histogram expected, Histogram actual) {
    assertEquals(expected, actual);
    assertEquals(expected.getTotalCount(), actual.getTotalCount());
    for (double percentile : new double[] { 50, 99, 99.9 }) {
      assertEquals(expected.getValueAtPercentile(percentile),
        actual.getValueAtPercentile(percentile));
    }
    assertEquals(expected.getMaxValue(), actual.getMaxValue());
    assertEquals(expected.getMinValue(), actual.getMinValue());
    assertEquals(RecorderConfig.of(expected), RecorderConfig.of(actual));
    assertEquals(RecorderConfig.PINNED, RecorderConfig.of(actual));
  }

  @Test
  public void histogramRoundTripsAsCompressedBase64() throws Exception {
    Histogram sample = sampleHistogram();
    Histogram empty = pinned();
    Histogram longest = pinned();
    longest.recordValue(LatencyRecorders.HIGHEST_US);
    for (Histogram histogram : Arrays.asList(sample, empty, longest)) {
      String blob = HistogramBlob.encode(histogram);
      assertTrue(blob, blob.matches("[A-Za-z0-9+/]+=*"));
      assertSameHistogram(histogram, HistogramBlob.decode(blob));
    }
    Histogram decoded = HistogramBlob.decode(HistogramBlob.encode(longest));
    assertEquals(1, decoded.getTotalCount());
    assertTrue(decoded.valuesAreEquivalent(LatencyRecorders.HIGHEST_US, decoded.getMaxValue()));
  }

  @Test
  public void blobEqualsTheHlogPayload() throws Exception {
    Histogram histogram = sampleHistogram();
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    PrintStream out = new PrintStream(bytes, true, StandardCharsets.UTF_8.name());
    new HistogramLogWriter(out).outputIntervalHistogram(1.0, 2.0, histogram, 1.0);
    String line = new String(bytes.toByteArray(), StandardCharsets.UTF_8).trim();
    String payload = line.substring(line.lastIndexOf(',') + 1);
    assertEquals(payload, HistogramBlob.encode(histogram));
  }

  @Test
  public void unknownFieldIsIgnored() throws Exception {
    Message decoded = Codec.decode("{\"v\":1,\"type\":\"cancel\",\"trace\":\"a1/control/3\","
      + "\"runId\":\"r1\",\"futureField\":{\"x\":[1,2]}}");
    assertEquals(MessageType.CANCEL, decoded.type());
    assertEquals("r1", ((Cancel) decoded).getRunId());
    assertEquals("a1/control/3", decoded.getTrace());
  }

  private static void assertRefused(String json, String reason) {
    try {
      Codec.decode(json);
      fail("decoded " + json);
    } catch (ProtocolException e) {
      assertTrue(e.getMessage(), e.getMessage().contains(reason));
    }
  }

  @Test
  public void otherVersionIsRefused() {
    assertRefused("{\"v\":2,\"type\":\"wait\"}", "unsupported protocol version 2");
    assertRefused("{\"type\":\"wait\"}", "no protocol version");
    assertRefused("{\"v\":\"1\",\"type\":\"wait\"}", "unsupported protocol version");
  }

  @Test
  public void unknownTypeIsRefused() {
    assertRefused("{\"v\":1,\"type\":\"teleport\"}", "unknown message type 'teleport'");
    assertRefused("{\"v\":1}", "no message type");
    assertRefused("{\"v\":1,\"type\":\"wait\"", "bad JSON");
    assertRefused("[1]", "not a JSON object");
    assertRefused("{\"v\":1,\"type\":\"ack\",\"heldSeq\":\"many\"}", "bad ack message");
  }
}
