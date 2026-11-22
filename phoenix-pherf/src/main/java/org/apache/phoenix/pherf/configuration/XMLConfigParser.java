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
package org.apache.phoenix.pherf.configuration;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import javax.xml.bind.JAXBContext;
import javax.xml.bind.JAXBException;
import javax.xml.bind.Marshaller;
import javax.xml.bind.Unmarshaller;
import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;
import javax.xml.transform.stream.StreamSource;
import org.apache.phoenix.pherf.PherfConstants;
import org.apache.phoenix.pherf.exception.FileLoaderException;
import org.apache.phoenix.pherf.rules.DataValue;
import org.apache.phoenix.pherf.util.ResourceList;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class XMLConfigParser {

  private static final Logger LOGGER = LoggerFactory.getLogger(XMLConfigParser.class);
  private String filePattern;
  private List<DataModel> dataModels;
  private List<Scenario> scenarios;
  private ResourceList resourceList;
  private Collection<Path> paths;

  public XMLConfigParser(String pattern) throws Exception {
    init(pattern);
  }

  /**
   * Initializes the configuration parser using the data models already parsed by the pre-run
   * validation gate for the given pattern, avoiding a redundant parse of the scenario files while
   * emitting the same console output as a parser that reads the files directly.
   * @param pattern the resource pattern matched by the validation gate; getScenarios() re-reads the
   *                files matching this pattern
   * @param models  the parsed data model for each scenario file, in file order
   */
  public XMLConfigParser(String pattern, Map<Path, DataModel> models) {
    this.filePattern = pattern;
    this.dataModels = new ArrayList<>();
    this.resourceList = new ResourceList(PherfConstants.RESOURCE_SCENARIO);
    this.paths = new ArrayList<>(models.keySet());
    for (Map.Entry<Path, DataModel> entry : models.entrySet()) {
      System.out.println("Adding model for path:" + entry.getKey().toString());
      updateDataValueType(entry.getValue());
      this.dataModels.add(entry.getValue());
    }
  }

  public List<DataModel> getDataModels() {
    return dataModels;
  }

  public DataModel getDataModelByName(String name) {
    for (DataModel dataModel : getDataModels()) {
      if (dataModel.getName().equals(name)) {
        return dataModel;
      }
    }
    return null;
  }

  public Scenario getScenarioByName(String name) throws Exception {
    for (Scenario scenario : getScenarios()) {
      if (scenario.getName().equals(name)) {
        return scenario;
      }
    }
    return null;
  }

  public synchronized Collection<Path> getPaths(String strPattern) throws Exception {
    if (paths != null) {
      return paths;
    }
    paths = getResources(strPattern);
    return paths;
  }

  public synchronized List<Scenario> getScenarios() throws Exception {
    if (scenarios != null) {
      return scenarios;
    }

    scenarios = Collections.synchronizedList(new ArrayList<Scenario>());
    for (Path path : getPaths(getFilePattern())) {
      try {
        List<Scenario> scenarioList = XMLConfigParser.readDataModel(path).getScenarios();
        for (Scenario scenario : scenarioList) {
          scenarios.add(scenario);
        }
      } catch (JAXBException e) {
        LOGGER.error("Unable to parse scenario file " + path, e);
        throw e;
      }
    }
    return scenarios;
  }

  public String getFilePattern() {
    return filePattern;
  }

  /**
   * Unmarshall an XML data file
   * @param file Name of File
   * @return {@link org.apache.phoenix.pherf.configuration.DataModel} Returns DataModel from XML
   *         configuration
   */
  // TODO Remove static calls
  public static DataModel readDataModel(Path file)
    throws JAXBException, XMLStreamException, IOException {
    DataModel dataModel = parse(file);
    resolveProfiles(dataModel, file);
    return dataModel;
  }

  /**
   * Unmarshals a scenario configuration file and binds scenario-level tenant identifiers without
   * resolving referenced workload profiles. The JAXB unmarshaller silently ignores unknown elements
   * and attributes, whereas the offline validator explicitly reports them.
   */
  public static DataModel parse(Path file) throws JAXBException, XMLStreamException, IOException {
    XMLInputFactory xif = secureInputFactory();
    JAXBContext jaxbContext = JAXBContext.newInstance(DataModel.class);
    Unmarshaller jaxbUnmarshaller = jaxbContext.createUnmarshaller();
    LOGGER.info("Open config file: " + file);
    DataModel dataModel;
    try (InputStream in = ResourceList.openResource(file)) {
      XMLStreamReader xmlReader = xif.createXMLStreamReader(new StreamSource(in));
      dataModel = (DataModel) jaxbUnmarshaller.unmarshal(xmlReader);
    }
    bindScenarioTenants(dataModel);
    return dataModel;
  }

  /**
   * Creates an XML input factory configured to disallow both DTD processing and external entity
   * resolution.
   */
  public static XMLInputFactory secureInputFactory() {
    XMLInputFactory xif = XMLInputFactory.newInstance();
    xif.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false);
    xif.setProperty(XMLInputFactory.SUPPORT_DTD, false);
    return xif;
  }

  /**
   * Resolves and copies the open loop schedule and operation mix from the referenced workload
   * profile into each query set that specifies one. The profile path may be relative to the
   * scenario file or absolute, and is loaded through the same mechanism as the scenario file,
   * whether from the file system or from the class path. Each query set reads its own independent
   * copy of the profile.
   * @throws IllegalArgumentException if profile resolution fails, with a diagnostic message that
   *                                  identifies the scenario, the query set, and the profile path.
   */
  private static void resolveProfiles(DataModel dataModel, Path file) {
    for (Scenario scenario : dataModel.getScenarios()) {
      int position = 0;
      for (QuerySet querySet : scenario.getQuerySet()) {
        position++;
        if (querySet.getProfile() != null) {
          resolveProfile(scenario, position, querySet, file);
        }
      }
    }
  }

  /**
   * Resolves the path of a workload profile referenced by a scenario configuration file.
   */
  public static Path profilePath(Path file, String profile) {
    return file.resolveSibling(profile.trim()).normalize();
  }

  /**
   * Resolves the workload profile for a single query set and copies its open-loop schedule and
   * operation mix into that query set, where query set positions start at 1.
   * @throws IllegalArgumentException if profile resolution fails, with a message identifying the
   *                                  scenario, the query set, and the profile path.
   */
  public static void resolveProfile(Scenario scenario, int position, QuerySet querySet, Path file) {
    Path path = profilePath(file, querySet.getProfile());
    String where =
      "profile " + path + " of querySet " + position + " of scenario " + scenario.getName() + ": ";
    if (querySet.getOpenLoop() != null) {
      throw new IllegalArgumentException(where + "the querySet also has its own openLoop;"
        + " the profile is the only source of the openLoop");
    }
    if (querySet.getMix() != null) {
      throw new IllegalArgumentException(
        where + "the querySet also has its own mix; the profile is the only source of the mix");
    }
    WorkloadProfile profile = readProfile(path, where);
    if (profile.getOpenLoop() == null) {
      throw new IllegalArgumentException(where + "the profile has no openLoop");
    }
    querySet.setOpenLoop(profile.getOpenLoop());
    querySet.setMix(profile.getMix());
  }

  /**
   * Reads and unmarshals a single workload profile file; JAXB rejects any XML document whose root
   * element is other than workloadProfile.
   */
  private static WorkloadProfile readProfile(Path path, String where) {
    try (InputStream in = ResourceList.openResource(path)) {
      XMLStreamReader xmlReader = secureInputFactory().createXMLStreamReader(new StreamSource(in));
      return (WorkloadProfile) JAXBContext.newInstance(WorkloadProfile.class).createUnmarshaller()
        .unmarshal(xmlReader);
    } catch (IOException | XMLStreamException | JAXBException e) {
      throw new IllegalArgumentException(where + "cannot read the profile: " + e, e);
    }
  }

  /**
   * Propagates the scenario tenant identifier down to any constituent query that does not define
   * its own tenant and is not configured to execute over a global connection.
   */
  private static void bindScenarioTenants(DataModel dataModel) {
    for (Scenario scenario : dataModel.getScenarios()) {
      if (scenario.getTenantId() == null) {
        continue;
      }
      for (QuerySet querySet : scenario.getQuerySet()) {
        for (Query query : querySet.getQuery()) {
          if (query.getTenantId() == null && !query.isUseGlobalConnection()) {
            query.setTenantId(scenario.getTenantId());
          }
        }
      }
    }
  }

  // TODO Remove static calls
  public static String parseSchemaName(String fullTableName) {
    String ret = null;
    if (fullTableName.contains(".")) {
      ret = fullTableName.substring(0, fullTableName.indexOf("."));
    }
    return ret;
  }

  // TODO Remove static calls
  public static String parseTableName(String fullTableName) {
    String ret = fullTableName;
    if (fullTableName.contains(".")) {
      ret = fullTableName.substring(fullTableName.indexOf(".") + 1, fullTableName.length());
    }
    // Remove any quotes that may be needed for multi-tenant tables
    ret = ret.replaceAll("\"", "");
    return ret;
  }

  // TODO Remove static calls
  @SuppressWarnings("unused")
  public static void writeDataModel(DataModel data, OutputStream output) throws JAXBException {
    // create JAXB context and initializing Marshaller
    JAXBContext jaxbContext = JAXBContext.newInstance(DataModel.class);
    Marshaller jaxbMarshaller = jaxbContext.createMarshaller();

    // for getting nice formatted output
    jaxbMarshaller.setProperty(Marshaller.JAXB_FORMATTED_OUTPUT, Boolean.TRUE);

    // Writing to console
    jaxbMarshaller.marshal(data, output);
  }

  private void init(String pattern) throws Exception {
    if (dataModels != null) {
      return;
    }
    this.filePattern = pattern;
    this.dataModels = new ArrayList<>();
    this.resourceList = new ResourceList(PherfConstants.RESOURCE_SCENARIO);
    this.paths = getResources(this.filePattern);
    if (this.paths.isEmpty()) {
      throw new FileLoaderException(
        "Could not load the resource files using the pattern: " + pattern);
    }
    for (Path path : this.paths) {
      System.out.println("Adding model for path:" + path.toString());
      DataModel dataModel = XMLConfigParser.readDataModel(path);
      updateDataValueType(dataModel);
      this.dataModels.add(dataModel);
    }
  }

  /** Propagates each data mapping column's data type to its nested data values. */
  public static void updateDataValueType(DataModel dataModel) {
    for (Column column : dataModel.getDataMappingColumns()) {
      if (column.getDataValues() != null) {
        // DataValue type is inherited from the column
        for (DataValue value : column.getDataValues()) {
          value.setType(column.getType());
        }
      }
    }
  }

  private Collection<Path> getResources(String pattern) throws Exception {
    return resourceList.getResourceList(pattern);
  }
}
