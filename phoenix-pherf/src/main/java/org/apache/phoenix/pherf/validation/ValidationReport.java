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
package org.apache.phoenix.pherf.validation;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonPrimitive;
import com.google.gson.JsonSerializer;
import java.io.File;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Encapsulates the findings produced by a single validation run in a fixed order. Each finding
 * carries a rule id that remains unchanged across versions so that tools can reliably match it.
 */
public final class ValidationReport {
  public static final String FORMAT = "pherf-validation";
  /**
   * Specifies the JSON report format version, which increments only when an older reader cannot
   * parse the new format.
   */
  public static final int VERSION = 1;

  /** Enumerates the validation rules and their stable rule ids. */
  public enum Rule {
    XML_PARSE("xml.parse"),
    XML_UNKNOWN_ELEMENT("xml.unknown-element"),
    XML_UNKNOWN_ATTRIBUTE("xml.unknown-attribute"),
    XML_VALUE("xml.value"),
    XML_PROFILE("xml.profile"),
    QUERY_KIND("query.kind"),
    QUERYSET_WARMUP("queryset.warmup"),
    OPENLOOP_RATE("openloop.rate"),
    OPENLOOP_SEED("openloop.seed"),
    OPENLOOP_MIX("openloop.mix"),
    PREDICATE_PARSE("predicate.parse"),
    PREDICATE_CASING("predicate.casing"),
    KEY_COVERAGE("key.coverage"),
    COGEN_SETTINGS("cogen.settings"),
    COGEN_WALK("cogen.walk"),
    COGEN_WRITER_BLOCKS("cogen.writer-blocks"),
    COGEN_OFFSETS("cogen.offsets"),
    COGEN_WATERMARK("cogen.watermark"),
    COGEN_RANGE("cogen.range"),
    PREDICATE_IN_LIST("predicate.in-list"),
    PREDICATE_ROW_VALUE("predicate.row-value"),
    PREDICATE_RANGE_BOUNDS("predicate.range-bounds");

    private final String id;

    Rule(String id) {
      this.id = id;
    }

    public String id() {
      return id;
    }
  }

  /** Represents a single validation rule violation recorded against a specific target object. */
  public static final class Finding {
    private final Rule rule;
    private final String file;
    private final Integer line;
    private final String where;
    private final String message;

    Finding(Rule rule, String file, Integer line, String where, String message) {
      this.rule = rule;
      this.file = file;
      this.line = line;
      this.where = where;
      this.message = message;
    }

    public Rule getRule() {
      return rule;
    }

    public String getFile() {
      return file;
    }

    /**
     * Returns the source line number of the offending element in the file, or null when the rule
     * applies to the model as a whole.
     */
    public Integer getLine() {
      return line;
    }

    /**
     * Returns the XML element path, or the identifying names of the scenario, the query set, and
     * the query.
     */
    public String getWhere() {
      return where;
    }

    public String getMessage() {
      return message;
    }

    @Override
    public String toString() {
      return rule.id() + " " + file + (line == null ? "" : ":" + line) + " " + where + ": "
        + message;
    }
  }

  private final List<String> files = new ArrayList<>();
  private final List<String> schemaFiles = new ArrayList<>();
  private final List<Finding> findings = new ArrayList<>();

  void add(Rule rule, String file, Integer line, String where, String message) {
    findings.add(new Finding(rule, file, line, where, message));
  }

  void addAll(ValidationReport part) {
    findings.addAll(part.findings);
  }

  void addFile(String file) {
    files.add(file);
  }

  void addSchemaFile(String file) {
    schemaFiles.add(file);
  }

  public List<Finding> getFindings() {
    return Collections.unmodifiableList(findings);
  }

  /**
   * Removes all findings reported for unrecognized XML names under the xml.unknown-element and
   * xml.unknown-attribute rules while retaining all other findings. Because the runtime XML parser
   * ignores unknown elements and attributes, waiving these findings allows scenarios written for a
   * newer version of Pherf to execute.
   * @return the removed findings in report order
   */
  public List<Finding> waiveUnknownNames() {
    List<Finding> waived = new ArrayList<>();
    findings
      .removeIf(f -> (f.rule == Rule.XML_UNKNOWN_ELEMENT || f.rule == Rule.XML_UNKNOWN_ATTRIBUTE)
        && waived.add(f));
    return waived;
  }

  /** Returns the scenario and profile files inspected during validation. */
  public List<String> getFiles() {
    return Collections.unmodifiableList(files);
  }

  /** Returns the schema files that supplied DDL statements for validation. */
  public List<String> getSchemaFiles() {
    return Collections.unmodifiableList(schemaFiles);
  }

  /** Returns the total number of findings, where every finding represents a validation error. */
  public int getErrors() {
    return findings.size();
  }

  /** Prints one formatted line for each finding followed by an overall summary line. */
  public void print(PrintStream out, Path report) {
    for (Finding finding : findings) {
      out.println(finding);
    }
    out.println("Validation: " + findings.size() + " error(s) in " + files.size() + " file(s)."
      + " Report: " + report);
  }

  /**
   * Writes the JSON validation report named VALIDATION_label.json in the specified directory. The
   * content is first written to a temporary file and then moved atomically into place to ensure
   * that readers never observe a partially written report.
   */
  public Path write(File dir, String label) throws IOException {
    Json json = new Json();
    json.label = label;
    json.files = files;
    json.schemaFiles = schemaFiles;
    json.errors = findings.size();
    json.findings = findings;
    // Serialize each finding's rule as its stable rule id string in the JSON output.
    String text = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping()
      .registerTypeAdapter(Rule.class,
        (JsonSerializer<Rule>) (rule, type, context) -> new JsonPrimitive(rule.id()))
      .create().toJson(json) + "\n";
    String name = "VALIDATION_" + label + ".json";
    Path target = new File(dir, name).toPath();
    Path temporary = new File(dir, name + ".tmp").toPath();
    Files.write(temporary, text.getBytes(StandardCharsets.UTF_8));
    Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE,
      StandardCopyOption.REPLACE_EXISTING);
    return target;
  }

  // Represents the version 1 JSON report payload. Gson serializes these fields in declaration
  // order, serializes each finding's fields in the declaration order of Finding, and omits any
  // field whose value is null.
  @SuppressWarnings("unused")
  private static final class Json {
    private final String format = FORMAT;
    private final int version = VERSION;
    private String label;
    private List<String> files;
    private List<String> schemaFiles;
    private int errors;
    private List<Finding> findings;
  }
}
