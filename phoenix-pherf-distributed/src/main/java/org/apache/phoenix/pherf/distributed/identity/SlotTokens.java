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
package org.apache.phoenix.pherf.distributed.identity;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Substitutes data-plane slot tokens within scenario documents and property definitions, supporting
 * ${pherf.slot.seed}, ${pherf.slot.offset}, and ${pherf.slot.prefix}. Encountering any other
 * ${pherf.slot.*} token results in an error, while all surrounding text is preserved unchanged. By
 * design, no token exposes the operational id.
 */
public final class SlotTokens {
  private static final Pattern TOKEN = Pattern.compile("\\$\\{pherf\\.slot\\.([^}]*)\\}");

  private SlotTokens() {
  }

  /**
   * Returns the input text with every recognized slot token replaced by its resolved slot value.
   * @throws IllegalArgumentException when an unknown slot token is present, naming that token
   */
  public static String resolve(String text, DataPlaneIdentity identity) {
    Matcher matcher = TOKEN.matcher(text);
    StringBuffer resolved = new StringBuffer();
    while (matcher.find()) {
      String value;
      switch (matcher.group(1)) {
        case "seed":
          value = Long.toString(identity.getSeed());
          break;
        case "offset":
          value = Long.toString(identity.getOffset());
          break;
        case "prefix":
          value = identity.getPrefix();
          break;
        default:
          throw new IllegalArgumentException("unknown slot token " + matcher.group()
            + "; the slot tokens are ${pherf.slot.seed}, ${pherf.slot.offset} and"
            + " ${pherf.slot.prefix}");
      }
      matcher.appendReplacement(resolved, Matcher.quoteReplacement(value));
    }
    matcher.appendTail(resolved);
    return resolved.toString();
  }

  /**
   * Returns a new map containing the original keys unchanged and each entry value resolved against
   * the given slot identity.
   */
  public static Map<String, String> resolve(Map<String, String> values,
    DataPlaneIdentity identity) {
    Map<String, String> resolved = new LinkedHashMap<>();
    for (Map.Entry<String, String> entry : values.entrySet()) {
      resolved.put(entry.getKey(), resolve(entry.getValue(), identity));
    }
    return resolved;
  }
}
