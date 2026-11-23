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

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import java.util.Map;

/**
 * Provides JSON serialization and deserialization for protocol messages. During decoding, a reader
 * ignores unknown fields but rejects any message with malformed JSON, an unknown type, or a version
 * other than {@link Protocol#VERSION}, throwing an exception that names the cause. During encoding,
 * the envelope fields are placed first on the wire so that log lines are easy to read.
 */
public final class Codec {
  private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();
  private static final String[] ENVELOPE = { "v", "type", "trace", "agent", "incarnation" };

  private Codec() {
  }

  public static String encode(Message message) {
    JsonObject fields = GSON.toJsonTree(message).getAsJsonObject();
    JsonObject ordered = new JsonObject();
    for (String key : ENVELOPE) {
      JsonElement value = fields.remove(key);
      if (value != null) {
        ordered.add(key, value);
      }
    }
    for (Map.Entry<String, JsonElement> field : fields.entrySet()) {
      ordered.add(field.getKey(), field.getValue());
    }
    return GSON.toJson(ordered);
  }

  public static Message decode(String json) throws ProtocolException {
    JsonObject fields;
    try {
      JsonElement tree = JsonParser.parseString(json);
      if (!tree.isJsonObject()) {
        throw new ProtocolException("not a JSON object");
      }
      fields = tree.getAsJsonObject();
    } catch (JsonParseException e) {
      throw new ProtocolException("bad JSON: " + e.getMessage(), e);
    }
    JsonElement version = fields.get("v");
    if (version == null) {
      throw new ProtocolException("no protocol version");
    }
    if (
      !(version instanceof JsonPrimitive) || !((JsonPrimitive) version).isNumber()
        || !String.valueOf(Protocol.VERSION).equals(version.getAsString())
    ) {
      throw new ProtocolException("unsupported protocol version " + version
        + "; this reader reads version " + Protocol.VERSION);
    }
    JsonElement typeName = fields.get("type");
    if (typeName == null || !typeName.isJsonPrimitive()) {
      throw new ProtocolException("no message type");
    }
    MessageType type = MessageType.forWire(typeName.getAsString());
    if (type == null) {
      throw new ProtocolException("unknown message type '" + typeName.getAsString() + "'");
    }
    try {
      return GSON.fromJson(fields, type.type());
    } catch (JsonParseException | IllegalStateException | NumberFormatException e) {
      throw new ProtocolException("bad " + type.wire() + " message: " + e.getMessage(), e);
    }
  }
}
