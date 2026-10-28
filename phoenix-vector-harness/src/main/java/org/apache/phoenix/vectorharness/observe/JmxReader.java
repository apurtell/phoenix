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

import java.io.Closeable;
import java.util.Map;

/**
 * Reads the attributes of a JMX bean on every RegionServer. A server whose bean is absent maps to
 * null, which observers record as unknown, never as zero.
 */
public interface JmxReader extends Closeable {

  /** The object name of the vector index metrics source. */
  String VECTOR_INDEX_BEAN = "Hadoop:service=HBase,name=RegionServer,sub=VectorIndex";
  String SERVER_BEAN = "Hadoop:service=HBase,name=RegionServer,sub=Server";
  String IPC_BEAN = "Hadoop:service=HBase,name=RegionServer,sub=IPC";
  String JVM_BEAN = "Hadoop:service=HBase,name=JvmMetrics";
  String OS_BEAN = "java.lang:type=OperatingSystem";
  String DIRECT_BUFFER_BEAN = "java.nio:type=BufferPool,name=direct";

  /** Returns a short name of the reader, such as http or registry. */
  String mode();

  /**
   * Returns the numeric attributes of a bean by server. The server keys are stable across calls.
   */
  Map<String, Map<String, Number>> read(String objectName);

  @Override
  void close();
}
