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

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import org.apache.phoenix.pherf.distributed.identity.DataPlaneIdentity;
import org.apache.phoenix.pherf.latency.LatencyRecorders;

/**
 * Represents the workload assignment for a single slot within one step of a distributed run. The
 * documents map each file name to its text content, and the scenario is one of those documents. The
 * args supply Pherf options, and the share defines the portion of the step totals that this slot
 * executes. The properties supply Pherf configuration properties, placing the step properties under
 * the run-level properties. Both the documents and the property values may contain the slot tokens
 * defined by the data-plane identity.
 * <p>
 * A slot in step 0 also carries slices of the later steps. Its prepare phase validates those slices
 * as well, ensuring that an invalid slice in any step aborts the run before the first start.
 */
public final class Assignment {
  /**
   * Defines the allowed pattern for a document name. The agent writes each document as a file with
   * this name within a single directory.
   */
  public static final Pattern DOCUMENT_NAME = Pattern.compile("(?!\\.)[A-Za-z0-9._-]{1,128}");
  /**
   * Identifies the Pherf results directory property, which the agent sets to a directory of its
   * own.
   */
  public static final String RESULTS_DIR_PROPERTY = "pherf.default.results.dir";
  /**
   * Identifies the Pherf interval export period property, which the agent sets because it reads the
   * exported results.
   */
  public static final String INTERVAL_PROPERTY = LatencyRecorders.INTERVAL_PROPERTY;
  /** Lists the Pherf properties set by the agent, which a plan is not permitted to set. */
  public static final List<String> AGENT_PROPERTIES =
    Collections.unmodifiableList(Arrays.asList(RESULTS_DIR_PROPERTY, INTERVAL_PROPERTY));

  private String runId;
  private int step;
  private DataPlaneIdentity identity;
  private String scenario;
  private Map<String, String> documents;
  private List<String> args;
  private Share share;
  private Map<String, String> properties;
  private List<Assignment> validations;

  private Assignment() {
  }

  public Assignment(String runId, int step, DataPlaneIdentity identity, String scenario,
    Map<String, String> documents, List<String> args, Share share, Map<String, String> properties) {
    this(runId, step, identity, scenario, documents, args, share, properties,
      Collections.<Assignment> emptyList());
  }

  /**
   * @param validations the workload slices from later steps that the prepare phase of this slot
   *                    validates.
   */
  public Assignment(String runId, int step, DataPlaneIdentity identity, String scenario,
    Map<String, String> documents, List<String> args, Share share, Map<String, String> properties,
    List<Assignment> validations) {
    this.runId = runId;
    this.step = step;
    this.identity = identity;
    this.scenario = scenario;
    this.documents = new LinkedHashMap<>(documents);
    this.args = new ArrayList<>(args);
    this.share = share;
    this.properties = new LinkedHashMap<>(properties);
    this.validations = validations.isEmpty() ? null : new ArrayList<>(validations);
  }

  /**
   * Returns a copy of this assignment with replacement documents, properties, and validations, such
   * as after resolving slot tokens.
   */
  public Assignment resolved(Map<String, String> resolvedDocuments,
    Map<String, String> resolvedProperties, List<Assignment> resolvedValidations) {
    return new Assignment(runId, step, identity, scenario, resolvedDocuments, getArgs(), share,
      resolvedProperties, resolvedValidations);
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

  /** Returns the file name of the scenario document. */
  public String getScenario() {
    return scenario;
  }

  public Map<String, String> getDocuments() {
    return documents == null
      ? Collections.<String, String> emptyMap()
      : Collections.unmodifiableMap(documents);
  }

  public List<String> getArgs() {
    return args == null ? Collections.<String> emptyList() : Collections.unmodifiableList(args);
  }

  public Share getShare() {
    return share == null ? new Share(null, null, null, null) : share;
  }

  public Map<String, String> getProperties() {
    return properties == null
      ? Collections.<String, String> emptyMap()
      : Collections.unmodifiableMap(properties);
  }

  /**
   * Returns the slices of later steps that the prepare phase of this slot validates, ordered by
   * step and slot.
   */
  public List<Assignment> getValidations() {
    return validations == null
      ? Collections.<Assignment> emptyList()
      : Collections.unmodifiableList(validations);
  }
}
