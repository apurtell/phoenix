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

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import org.apache.phoenix.pherf.distributed.identity.DataPlaneIdentity;

/**
 * Represents the work assignment for a single slot within one step of a distributed run. The
 * document map associates each file name with its textual content, while the property map supplies
 * Pherf configuration properties; both maps may include the slot tokens defined by the data-plane
 * identity.
 */
public final class Assignment {
  private String runId;
  private int step;
  private DataPlaneIdentity identity;
  private Map<String, String> documents;
  private Map<String, String> properties;

  private Assignment() {
  }

  public Assignment(String runId, int step, DataPlaneIdentity identity,
    Map<String, String> documents, Map<String, String> properties) {
    this.runId = runId;
    this.step = step;
    this.identity = identity;
    this.documents = new LinkedHashMap<>(documents);
    this.properties = new LinkedHashMap<>(properties);
  }

  public String getRunId() {
    return runId;
  }

  public int getStep() {
    return step;
  }

  public DataPlaneIdentity getIdentity() {
    return identity;
  }

  public Map<String, String> getDocuments() {
    return documents == null
      ? Collections.<String, String> emptyMap()
      : Collections.unmodifiableMap(documents);
  }

  public Map<String, String> getProperties() {
    return properties == null
      ? Collections.<String, String> emptyMap()
      : Collections.unmodifiableMap(properties);
  }
}
