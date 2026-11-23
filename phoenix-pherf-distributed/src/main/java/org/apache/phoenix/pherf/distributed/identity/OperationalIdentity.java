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

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Encapsulates the operational identity of an agent process for diagnostics, logs, and distributed
 * traces. While the agent id remains stable across restarts (for example, matching the pod name in
 * Kubernetes), a fresh incarnation identifier is generated each time the process starts. This
 * operational identity is strictly decoupled from the data plane and never alters generated data.
 */
public final class OperationalIdentity {
  /** Defines the allowed lexical pattern for a valid operational id. */
  public static final Pattern ID = Pattern.compile("[A-Za-z0-9._-]{1,63}");

  private final String id;
  private final String incarnation;

  public OperationalIdentity(String id, String incarnation) {
    check(id);
    if (incarnation == null || incarnation.isEmpty()) {
      throw new IllegalArgumentException("agent " + id + " has no incarnation");
    }
    this.id = id;
    this.incarnation = incarnation;
  }

  /** Creates a new operational identity with a freshly generated process incarnation. */
  public static OperationalIdentity create(String id) {
    return new OperationalIdentity(id, UUID.randomUUID().toString());
  }

  /** Validates the supplied id and fails if it does not conform to the operational id format. */
  public static void check(String id) {
    if (id == null || !ID.matcher(id).matches()) {
      throw new IllegalArgumentException(
        "operational id '" + id + "' does not match " + ID.pattern());
    }
  }

  /**
   * Resolves the operational id from PHERF_AGENT_ID, falling back to HOSTNAME and then to the name
   * of the local host.
   */
  public static String resolveId(Map<String, String> env) {
    for (String name : new String[] { "PHERF_AGENT_ID", "HOSTNAME" }) {
      String value = env.get(name);
      if (value != null && !value.trim().isEmpty()) {
        return value.trim();
      }
    }
    try {
      return InetAddress.getLocalHost().getHostName();
    } catch (UnknownHostException e) {
      throw new IllegalArgumentException(
        "no PHERF_AGENT_ID, no HOSTNAME and no local host name: " + e.getMessage(), e);
    }
  }

  public String getId() {
    return id;
  }

  public String getIncarnation() {
    return incarnation;
  }

  @Override
  public String toString() {
    return id + " (" + incarnation + ")";
  }
}
