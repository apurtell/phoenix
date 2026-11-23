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

import java.io.DataInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;
import org.apache.phoenix.pherf.distributed.transport.http.ControllerServer;
import org.junit.Test;

/**
 * Verifies requirements RQ-218 and RQ-221 by inspecting the bytecode constant pools of the main
 * production classes. This design validation ensures that network dependencies on the JDK HTTP
 * server and HttpURLConnection remain strictly isolated within the HTTP transport adapters, and
 * confirms that no class directly opens a raw server socket.
 */
public class NetworkUseTest {
  private static final String BASE = "org/apache/phoenix/pherf/distributed/";
  private static final String HTTP = BASE + "transport/http/";

  /** Extracts and returns all UTF-8 entries from the constant pool of a single class file. */
  private static List<String> utf8Constants(File file) throws IOException {
    List<String> constants = new ArrayList<>();
    try (InputStream in = new FileInputStream(file);
      DataInputStream data = new DataInputStream(in)) {
      if (data.readInt() != 0xCAFEBABE) {
        throw new IOException("not a class file: " + file);
      }
      data.readUnsignedShort();
      data.readUnsignedShort();
      int count = data.readUnsignedShort();
      for (int i = 1; i < count; i++) {
        int tag = data.readUnsignedByte();
        switch (tag) {
          case 1:
            constants.add(data.readUTF());
            break;
          case 3:
          case 4:
          case 9:
          case 10:
          case 11:
          case 12:
          case 17:
          case 18:
            data.skipBytes(4);
            break;
          case 5:
          case 6:
            data.skipBytes(8);
            i++;
            break;
          case 7:
          case 8:
          case 16:
          case 19:
          case 20:
            data.skipBytes(2);
            break;
          case 15:
            data.skipBytes(3);
            break;
          default:
            throw new IOException("unknown constant tag " + tag + " in " + file);
        }
      }
    }
    return constants;
  }

  private static void collect(File dir, String prefix, Map<String, List<String>> classes)
    throws IOException {
    File[] files = dir.listFiles();
    if (files == null) {
      return;
    }
    for (File file : files) {
      if (file.isDirectory()) {
        collect(file, prefix + file.getName() + "/", classes);
      } else if (file.getName().endsWith(".class")) {
        classes.put(prefix + file.getName(), utf8Constants(file));
      }
    }
  }

  /** Identifies all top-level class names whose constant pool entries contain the given text. */
  private static TreeSet<String> users(Map<String, List<String>> classes, String text) {
    TreeSet<String> users = new TreeSet<>();
    for (Map.Entry<String, List<String>> entry : classes.entrySet()) {
      for (String constant : entry.getValue()) {
        if (constant.contains(text)) {
          String name = entry.getKey().replaceAll("(\\$.*)?\\.class$", "");
          users.add(name);
          break;
        }
      }
    }
    return users;
  }

  @Test
  public void onlyTheHttpAdaptersTouchTheNetwork() throws Exception {
    File classes =
      new File(ControllerServer.class.getProtectionDomain().getCodeSource().getLocation().toURI());
    Map<String, List<String>> constants = new TreeMap<>();
    collect(classes, "", constants);
    assertTrue(constants.keySet().toString(),
      constants.containsKey(BASE + "agent/AgentRuntime.class"));

    TreeSet<String> server = new TreeSet<>();
    server.add(HTTP + "ChannelHandler");
    server.add(HTTP + "ControllerServer");
    server.add(HTTP + "RestHandler");
    assertEquals(server, users(constants, "com/sun/net/httpserver/"));
    TreeSet<String> client = new TreeSet<>();
    client.add(HTTP + "HttpAgentTransport");
    assertEquals(client, users(constants, "java/net/HttpURLConnection"));
    assertEquals(client, users(constants, "java/net/URLConnection"));
    for (String forbidden : new String[] { "java/net/ServerSocket", "Ljava/net/Socket;",
      "java/nio/channels/ServerSocketChannel", "java/nio/channels/SocketChannel",
      "java/net/DatagramSocket" }) {
      assertEquals(forbidden, new TreeSet<String>(), users(constants, forbidden));
    }
    for (Map.Entry<String, List<String>> entry : constants.entrySet()) {
      assertTrue(entry.getKey(), !entry.getValue().contains("java/net/Socket"));
    }
    for (String user : users(constants, "java/net/")) {
      assertTrue(user, !user.startsWith(BASE + "agent/") || user.endsWith("/AgentMain"));
    }
  }
}
