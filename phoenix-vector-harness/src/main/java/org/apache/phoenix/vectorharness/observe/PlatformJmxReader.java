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
package org.apache.phoenix.vectorharness.observe;

import java.lang.management.ManagementFactory;
import java.util.LinkedHashMap;
import java.util.Map;
import javax.management.Attribute;
import javax.management.AttributeList;
import javax.management.MBeanAttributeInfo;
import javax.management.MBeanInfo;
import javax.management.MBeanServer;
import javax.management.ObjectName;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Reads beans from the platform MBean server of this JVM. Every RegionServer in this JVM shares one
 * metrics source, so the values are cluster totals under the single server key "local".
 */
public final class PlatformJmxReader implements JmxReader {

  private static final Logger LOG = LoggerFactory.getLogger(PlatformJmxReader.class);
  public static final String LOCAL = "local";

  private final MBeanServer server = ManagementFactory.getPlatformMBeanServer();

  @Override
  public String mode() {
    return "local";
  }

  @Override
  public Map<String, Map<String, Number>> read(String objectName) {
    Map<String, Map<String, Number>> out = new LinkedHashMap<>();
    try {
      ObjectName name = new ObjectName(objectName);
      if (!server.isRegistered(name)) {
        out.put(LOCAL, null);
        return out;
      }
      MBeanInfo info = server.getMBeanInfo(name);
      String[] names = new String[info.getAttributes().length];
      int i = 0;
      for (MBeanAttributeInfo a : info.getAttributes()) {
        names[i++] = a.getName();
      }
      AttributeList list = server.getAttributes(name, names);
      Map<String, Number> attrs = new LinkedHashMap<>();
      for (Object o : list) {
        Attribute a = (Attribute) o;
        if (a.getValue() instanceof Number) {
          attrs.put(a.getName(), (Number) a.getValue());
        }
      }
      out.put(LOCAL, attrs);
    } catch (Exception e) {
      LOG.debug("Reading {} failed", objectName, e);
      out.put(LOCAL, null);
    }
    return out;
  }

  @Override
  public void close() {
  }
}
