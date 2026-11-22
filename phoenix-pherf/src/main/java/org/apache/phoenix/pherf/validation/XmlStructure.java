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

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.xml.XMLConstants;
import javax.xml.bind.DatatypeConverter;
import javax.xml.bind.JAXBContext;
import javax.xml.bind.JAXBException;
import javax.xml.bind.SchemaOutputResolver;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;
import javax.xml.transform.Result;
import javax.xml.transform.dom.DOMResult;
import javax.xml.transform.stream.StreamSource;
import org.apache.phoenix.pherf.configuration.DataModel;
import org.apache.phoenix.pherf.configuration.WorkloadProfile;
import org.apache.phoenix.pherf.configuration.XMLConfigParser;
import org.apache.phoenix.pherf.util.ResourceList;
import org.apache.phoenix.pherf.validation.ValidationReport.Rule;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;

/**
 * Defines the structural validation rules for scenario and profile configuration files. The
 * vocabulary is derived directly from the schema that JAXB generates for the configuration classes,
 * ensuring that it always stays consistent with the parser. The traversal inspects only element
 * names, attribute names, and value types. It disregards element ordering and required items, which
 * the parser likewise ignores, so the generated schema is never used directly as a validation
 * schema.
 */
final class XmlStructure {
  private static final Set<String> FREE_NAMESPACES = new HashSet<>(
    Arrays.asList(XMLConstants.W3C_XML_SCHEMA_INSTANCE_NS_URI, XMLConstants.XML_NS_URI));

  /** Represents the child elements and attributes declared by a single complex type. */
  private static final class Type {
    // Each element type entry stores the name of a named type, a built-in "xs:name" type, or an
    // inline Type instance.
    private final Map<String, Object> elements = new HashMap<>();
    private final Set<String> repeated = new HashSet<>();
    private final Map<String, String> attributes = new HashMap<>();
    private String base;
  }

  /**
   * Encapsulates the element names and type definitions for configuration files that share a single
   * root element.
   */
  static final class Vocabulary {
    private final String root;
    private final Map<String, Type> types = new HashMap<>();
    private final Map<String, List<String>> enums = new HashMap<>();
    private String rootType;

    private Vocabulary(String root) {
      this.root = root;
    }

    String root() {
      return root;
    }
  }

  /** Lazily initializes and caches the vocabularies once per JVM upon first access. */
  private static final class Holder {
    private static final Vocabulary SCENARIO = vocabulary(DataModel.class, "datamodel");
    private static final Vocabulary PROFILE = vocabulary(WorkloadProfile.class, "workloadProfile");
  }

  private XmlStructure() {
  }

  static Vocabulary scenario() {
    return Holder.SCENARIO;
  }

  static Vocabulary profile() {
    return Holder.PROFILE;
  }

  private static Vocabulary vocabulary(Class<?> root, String rootName) {
    final List<DOMResult> results = new ArrayList<>();
    try {
      JAXBContext.newInstance(root).generateSchema(new SchemaOutputResolver() {
        @Override
        public Result createOutput(String namespace, String file) {
          DOMResult result = new DOMResult();
          result.setSystemId(file);
          results.add(result);
          return result;
        }
      });
    } catch (JAXBException | IOException e) {
      throw new IllegalStateException("Cannot make the schema of " + root.getName(), e);
    }
    Vocabulary vocabulary = new Vocabulary(rootName);
    for (DOMResult result : results) {
      Element schema = ((Document) result.getNode()).getDocumentElement();
      // Only the schema without a target namespace declares the root elements read by the parser.
      boolean local = schema.getAttribute("targetNamespace").isEmpty();
      for (Element child : children(schema)) {
        String name = child.getAttribute("name");
        switch (localName(child)) {
          case "element":
            if (local && name.equals(rootName)) {
              vocabulary.rootType = typeName(child, child.getAttribute("type"));
            }
            break;
          case "complexType":
            vocabulary.types.put(name, complexType(child));
            break;
          case "simpleType":
            List<String> values = new ArrayList<>();
            collectEnumerations(child, values);
            vocabulary.enums.put(name, values);
            break;
          default:
            break;
        }
      }
    }
    if (vocabulary.rootType == null) {
      throw new IllegalStateException("The schema of " + root.getName() + " has no " + rootName);
    }
    return vocabulary;
  }

  private static Type complexType(Element node) {
    Type type = new Type();
    addParticles(node, type);
    return type;
  }

  /**
   * Populates a type definition with the elements and attributes declared under a schema type node,
   * traversing through nested model groups and type extensions.
   */
  private static void addParticles(Element node, Type type) {
    for (Element child : children(node)) {
      switch (localName(child)) {
        case "element":
          String name = child.getAttribute("name");
          Element anonymous = first(child, "complexType");
          type.elements.put(name,
            anonymous != null ? complexType(anonymous)
              : child.hasAttribute("type") ? typeName(child, child.getAttribute("type"))
              : "xs:anyType");
          if ("unbounded".equals(child.getAttribute("maxOccurs"))) {
            type.repeated.add(name);
          }
          break;
        case "attribute":
          type.attributes.put(child.getAttribute("name"),
            child.hasAttribute("type") ? typeName(child, child.getAttribute("type")) : "xs:string");
          break;
        case "extension":
          type.base = typeName(child, child.getAttribute("base"));
          addParticles(child, type);
          break;
        default:
          addParticles(child, type);
          break;
      }
    }
  }

  private static void collectEnumerations(Element node, List<String> values) {
    for (Element child : children(node)) {
      if (localName(child).equals("enumeration")) {
        values.add(child.getAttribute("value"));
      } else {
        collectEnumerations(child, values);
      }
    }
  }

  /**
   * Resolves a qualified type reference to either a built-in schema type formatted as "xs:name" or
   * the local name of a user-defined named type.
   */
  private static String typeName(Node context, String qualified) {
    int colon = qualified.indexOf(':');
    String prefix = colon < 0 ? null : qualified.substring(0, colon);
    String local = qualified.substring(colon + 1);
    return XMLConstants.W3C_XML_SCHEMA_NS_URI.equals(context.lookupNamespaceURI(prefix))
      ? "xs:" + local
      : local;
  }

  private static List<Element> children(Node node) {
    List<Element> elements = new ArrayList<>();
    for (Node child = node.getFirstChild(); child != null; child = child.getNextSibling()) {
      if (child instanceof Element) {
        elements.add((Element) child);
      }
    }
    return elements;
  }

  private static Element first(Element node, String name) {
    for (Element child : children(node)) {
      if (localName(child).equals(name)) {
        return child;
      }
    }
    return null;
  }

  private static String localName(Node node) {
    String name = node.getLocalName() != null ? node.getLocalName() : node.getNodeName();
    return name.substring(name.indexOf(':') + 1);
  }

  /** Tracks the state of a single open XML element during the document traversal. */
  private static final class Frame {
    private final Object type;
    private final String name;
    private final String path;
    private final Map<String, Integer> counts = new HashMap<>();
    private final StringBuilder text = new StringBuilder();
    private final int line;

    private Frame(Object type, String name, String path, int line) {
      this.type = type;
      this.name = name;
      this.path = path;
      this.line = line;
    }
  }

  /** Represents the overall result of traversing and validating an XML file. */
  enum Walked {
    /** Indicates that the file is structurally readable by the parser without fatal errors. */
    OK,
    /**
     * Indicates that at least one invalid value causes parsing of the file to fail; the recorded
     * value findings identify the offending entries.
     */
    VALUE_FAILS_PARSE,
    /**
     * Indicates that the file cannot be read, is not well-formed XML, contains a disallowed DTD, or
     * declares an unexpected root element.
     */
    NOT_READ
  }

  /**
   * Traverses a single XML file and records validation findings for every element or attribute name
   * absent from the model and every value that fails to parse as its declared type. Each unknown
   * element is reported once at its declaration, and its nested content is skipped without further
   * diagnostics.
   */
  static Walked walk(Vocabulary vocabulary, Path path, String file, ValidationReport report) {
    boolean[] fatal = new boolean[1];
    try (InputStream in = ResourceList.openResource(path)) {
      XMLStreamReader reader =
        XMLConfigParser.secureInputFactory().createXMLStreamReader(new StreamSource(in));
      Deque<Frame> open = new ArrayDeque<>();
      int skipped = 0;
      while (reader.hasNext()) {
        int event = reader.next();
        if (event == XMLStreamConstants.START_ELEMENT) {
          int line = reader.getLocation().getLineNumber();
          String name = reader.getLocalName();
          if (skipped > 0) {
            skipped++;
          } else if (open.isEmpty()) {
            if (!name.equals(vocabulary.root) || !isEmpty(reader.getNamespaceURI())) {
              report.add(Rule.XML_PARSE, file, line, vocabulary.root,
                "the root element is <" + name + ">, not <" + vocabulary.root + ">");
              return Walked.NOT_READ;
            }
            Frame root = new Frame(vocabulary.rootType, name, name, line);
            attributes(vocabulary, reader, root, file, report, fatal);
            open.push(root);
          } else {
            Frame parent = open.peek();
            Object type =
              isEmpty(reader.getNamespaceURI()) ? element(vocabulary, parent.type, name) : null;
            if (type == null) {
              report.add(Rule.XML_UNKNOWN_ELEMENT, file, line, parent.path + "/" + name,
                "element <" + name + "> is not a child of <" + parent.name
                  + "> in the Pherf model, so the parser ignores it and its content");
              skipped = 1;
              continue;
            }
            int count = parent.counts.merge(name, 1, Integer::sum);
            String step = repeated(vocabulary, parent.type, name) ? name + "[" + count + "]" : name;
            Frame frame = new Frame(type, name, parent.path + "/" + step, line);
            attributes(vocabulary, reader, frame, file, report, fatal);
            open.push(frame);
          }
        } else if (event == XMLStreamConstants.END_ELEMENT) {
          if (skipped > 0) {
            skipped--;
          } else {
            Frame frame = open.pop();
            if (simple(vocabulary, frame.type)) {
              value(vocabulary, (String) frame.type, frame.text.toString(), false, file, frame.line,
                frame.path, report, fatal);
            }
          }
        } else if (
          (event == XMLStreamConstants.CHARACTERS || event == XMLStreamConstants.CDATA)
            && skipped == 0 && !open.isEmpty()
        ) {
          open.peek().text.append(reader.getText());
        }
      }
      return fatal[0] ? Walked.VALUE_FAILS_PARSE : Walked.OK;
    } catch (IOException | XMLStreamException e) {
      Integer line =
        e instanceof XMLStreamException && ((XMLStreamException) e).getLocation() != null
          ? ((XMLStreamException) e).getLocation().getLineNumber()
          : null;
      report.add(Rule.XML_PARSE, file, line, vocabulary.root,
        "the file cannot be read as XML without a DTD: " + e.getMessage());
      return Walked.NOT_READ;
    }
  }

  private static void attributes(Vocabulary vocabulary, XMLStreamReader reader, Frame frame,
    String file, ValidationReport report, boolean[] fatal) {
    for (int i = 0; i < reader.getAttributeCount(); i++) {
      String namespace = reader.getAttributeNamespace(i);
      if (namespace != null && FREE_NAMESPACES.contains(namespace)) {
        continue;
      }
      String name = reader.getAttributeLocalName(i);
      String type = isEmpty(namespace) ? attribute(vocabulary, frame.type, name) : null;
      String where = frame.path + "/@" + name;
      if (type == null) {
        report.add(Rule.XML_UNKNOWN_ATTRIBUTE, file, frame.line, where,
          "attribute " + name + " is not an attribute of <" + frame.name
            + "> in the Pherf model, so the parser ignores it");
      } else {
        value(vocabulary, type, reader.getAttributeValue(i), true, file, frame.line, where, report,
          fatal);
      }
    }
  }

  /**
   * Validates a textual value and reports any input that the parser cannot interpret as written.
   * The parser treats an invalid enum value as absent and an invalid boolean value as false. A
   * malformed number inside element text defaults to 0, whereas a malformed number in an attribute
   * causes parsing of the file to fail. An integer value outside the bounds of its target type
   * wraps around.
   */
  private static void value(Vocabulary vocabulary, String type, String text, boolean attribute,
    String file, int line, String where, ValidationReport report, boolean[] fatal) {
    String start = "the value '" + text + "' is not ";
    List<String> values = vocabulary.enums.get(type);
    String message = null;
    if (values != null) {
      // JAXB matches enum constants by exact string equality without trimming whitespace.
      if (!values.contains(text)) {
        message =
          start + "one of " + String.join(", ", values) + ", so the parser reads it as" + " absent";
      }
    } else if (type.equals("xs:boolean")) {
      String value = text.trim();
      if (
        !value.equals("true") && !value.equals("false") && !value.equals("1") && !value.equals("0")
      ) {
        message = start + "a boolean (true, false, 1 or 0), so the parser reads it as false";
      }
    } else if (NUMBERS.containsKey(type)) {
      String number = NUMBERS.get(type);
      Long read = null;
      try {
        // Parse numeric values using the standard JAXB datatype conversion rules.
        switch (type) {
          case "xs:long":
            DatatypeConverter.parseLong(text);
            break;
          case "xs:double":
            DatatypeConverter.parseDouble(text);
            break;
          case "xs:float":
            DatatypeConverter.parseFloat(text);
            break;
          default:
            read = (long) DatatypeConverter.parseInt(text);
            break;
        }
      } catch (IllegalArgumentException e) {
        fatal[0] |= attribute;
        message = start + number
          + (attribute
            ? ", so the parse of the file fails with a NumberFormatException"
            : ", so the parser reads it as 0");
      }
      if (read != null && !inRange(type, text.trim())) {
        message = start + number + " in range, so the parser reads it as "
          + (type.equals("xs:short") ? (short) (long) read
            : type.equals("xs:byte") ? (byte) (long) read
            : read);
      }
    }
    if (message != null) {
      report.add(Rule.XML_VALUE, file, line, where, message);
    }
  }

  /** Maps XML Schema numeric type names to their human-readable descriptions for messages. */
  private static final Map<String, String> NUMBERS = new HashMap<>();

  static {
    NUMBERS.put("xs:int", "an int");
    NUMBERS.put("xs:short", "a short");
    NUMBERS.put("xs:byte", "a byte");
    NUMBERS.put("xs:long", "a long");
    NUMBERS.put("xs:double", "a double");
    NUMBERS.put("xs:float", "a float");
  }

  /**
   * Returns true if the given text represents a single signed decimal integer within the valid
   * bounds of its target type.
   */
  private static boolean inRange(String type, String text) {
    if (!text.matches("[+-]?[0-9]+")) {
      return false;
    }
    try {
      long value = Long.parseLong(text.startsWith("+") ? text.substring(1) : text);
      switch (type) {
        case "xs:short":
          return value >= Short.MIN_VALUE && value <= Short.MAX_VALUE;
        case "xs:byte":
          return value >= Byte.MIN_VALUE && value <= Byte.MAX_VALUE;
        default:
          return value >= Integer.MIN_VALUE && value <= Integer.MAX_VALUE;
      }
    } catch (NumberFormatException e) {
      return false;
    }
  }

  private static Type complex(Vocabulary vocabulary, Object type) {
    return type instanceof Type ? (Type) type : vocabulary.types.get(type);
  }

  private static boolean simple(Vocabulary vocabulary, Object type) {
    return complex(vocabulary, type) == null;
  }

  /** Returns the inheritance chain starting from a complex type through its base types in order. */
  private static List<Type> chain(Vocabulary vocabulary, Object parent) {
    List<Type> chain = new ArrayList<>();
    for (Type type = complex(vocabulary, parent); type != null; type =
      type.base == null ? null : vocabulary.types.get(type.base)) {
      chain.add(type);
    }
    return chain;
  }

  /**
   * Resolves the type definition of a named child element by searching the parent type and its base
   * types, or returns null if the element is not declared.
   */
  private static Object element(Vocabulary vocabulary, Object parent, String name) {
    return chain(vocabulary, parent).stream().map(type -> type.elements.get(name))
      .filter(child -> child != null).findFirst().orElse(null);
  }

  private static boolean repeated(Vocabulary vocabulary, Object parent, String name) {
    return chain(vocabulary, parent).stream().anyMatch(type -> type.repeated.contains(name));
  }

  /**
   * Resolves the type name of a named attribute by searching the parent type and its base types, or
   * returns null if the attribute is not declared.
   */
  private static String attribute(Vocabulary vocabulary, Object parent, String name) {
    return chain(vocabulary, parent).stream().map(type -> type.attributes.get(name))
      .filter(attribute -> attribute != null).findFirst().orElse(null);
  }

  private static boolean isEmpty(String text) {
    return text == null || text.isEmpty();
  }
}
