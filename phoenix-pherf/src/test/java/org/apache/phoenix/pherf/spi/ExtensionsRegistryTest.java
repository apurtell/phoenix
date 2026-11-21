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
package org.apache.phoenix.pherf.spi;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.File;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Collections;
import java.util.ServiceConfigurationError;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/**
 * Verifies that a service registration failure is cached so that every subsequent access to the
 * registry fails consistently with the same error.
 */
public class ExtensionsRegistryTest {
  @Rule
  public TemporaryFolder folder = new TemporaryFolder();

  @Test
  public void testLoadFailureIsKept() throws Exception {
    File services = new File(folder.getRoot(), "META-INF/services");
    Files.createDirectories(services.toPath());
    Files.write(new File(services, MetricsSink.class.getName()).toPath(),
      Collections.singletonList("org.apache.phoenix.pherf.spi.NoSuchSink"), StandardCharsets.UTF_8);
    Extensions.Registry<MetricsSink> registry = new Extensions.Registry<>(MetricsSink.class);

    Thread thread = Thread.currentThread();
    ClassLoader original = thread.getContextClassLoader();
    try (URLClassLoader loader = new URLClassLoader(new URL[] { folder.getRoot().toURI().toURL() },
      getClass().getClassLoader())) {
      thread.setContextClassLoader(loader);
      assertLoadFails(registry);
    } finally {
      thread.setContextClassLoader(original);
    }
    // Even after the registration is removed from the class path, the registry retains its failed
    // state rather than attempting to reload.
    assertLoadFails(registry);
    assertEquals(Collections.emptyList(), new Extensions.Registry<>(MetricsSink.class).get());
  }

  private static void assertLoadFails(Extensions.Registry<MetricsSink> registry) {
    try {
      registry.get();
      fail("The registry loaded a class that does not exist");
    } catch (IllegalStateException e) {
      assertTrue(e.getMessage(), e.getMessage().contains(MetricsSink.class.getName()));
      assertTrue(String.valueOf(e.getCause()), e.getCause() instanceof ServiceConfigurationError);
      assertTrue(e.getCause().getMessage(), e.getCause().getMessage().contains("NoSuchSink"));
    }
  }
}
