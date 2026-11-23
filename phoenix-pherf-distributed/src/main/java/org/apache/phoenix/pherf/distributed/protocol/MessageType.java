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

/**
 * This enumeration defines the message types of catalogue version 1, associating each entry with
 * its wire name and message class.
 */
public enum MessageType {
  REGISTER("register", Register.class),
  HEARTBEAT("heartbeat", Heartbeat.class),
  GOODBYE("goodbye", Goodbye.class),
  REGISTERED("registered", Registered.class),
  WAIT("wait", Wait.class),
  ASSIGN("assign", Assign.class),
  LAUNCH("launch", Launch.class),
  START("start", Start.class),
  CANCEL("cancel", Cancel.class),
  RELEASE("release", Release.class),
  SHUTDOWN("shutdown", Shutdown.class),
  REREGISTER("reregister", Reregister.class),
  REJECTED("rejected", Rejected.class),
  INTERVALS("intervals", Intervals.class),
  ACK("ack", Ack.class);

  private final String wire;
  private final Class<? extends Message> type;

  MessageType(String wire, Class<? extends Message> type) {
    this.wire = wire;
    this.type = type;
  }

  public String wire() {
    return wire;
  }

  public Class<? extends Message> type() {
    return type;
  }

  /**
   * Returns the message type that corresponds to the specified wire name, or null if the name does
   * not match any known type.
   */
  public static MessageType forWire(String wire) {
    for (MessageType value : values()) {
      if (value.wire.equals(wire)) {
        return value;
      }
    }
    return null;
  }
}
