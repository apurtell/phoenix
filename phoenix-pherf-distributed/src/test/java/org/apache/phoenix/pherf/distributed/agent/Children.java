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
package org.apache.phoenix.pherf.distributed.agent;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Collections;
import java.util.Properties;
import org.apache.phoenix.pherf.spi.ConnectionProvider;

/**
 * Configures real Pherf child processes for the test cases that execute one, corresponding to the
 * documented process exceptions.
 */
public final class Children {
  private Children() {
  }

  /**
   * Creates a launcher that spawns real Pherf child processes. The overlay directory registers
   * {@link RecordingConnectionProvider} so that a spawned child process opens no real connection.
   */
  public static Launcher launcher(String agent, File overlay) throws IOException {
    File services = new File(overlay, "META-INF/services");
    Files.createDirectories(services.toPath());
    Files.write(new File(services, ConnectionProvider.class.getName()).toPath(),
      Collections.singletonList(RecordingConnectionProvider.class.getName()),
      StandardCharsets.UTF_8);
    String classPath = overlay.getPath() + File.pathSeparator
      + System.getProperty("surefire.test.class.path", System.getProperty("java.class.path"));
    return new ChildJvmLauncher(agent, classPath);
  }

  /** Loads and returns the default pherf.properties configuration shipped by phoenix-pherf. */
  public static Properties shipped() throws IOException {
    return AgentMain.shippedProperties();
  }
}
