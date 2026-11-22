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
package org.apache.phoenix.pherf.rules;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import org.apache.phoenix.pherf.configuration.Column;
import org.apache.phoenix.pherf.configuration.DataModel;
import org.apache.phoenix.pherf.configuration.DataTypeMapping;
import org.apache.phoenix.pherf.configuration.Scenario;
import org.apache.phoenix.pherf.configuration.XMLConfigParser;
import org.apache.phoenix.pherf.workload.cogen.CoGenFixtures;
import org.apache.phoenix.pherf.workload.cogen.IndexDecoder;
import org.junit.Test;

/**
 * Verifies RQ-183: a custom generator class that does not implement the index interface continues
 * to produce values from its own sequence under co-generation, whereas a generator class that
 * implements the index interface produces index-based values under co-generation while retaining
 * its own sequence on the standard rule evaluation path.
 */
public class IndexedGeneratorTest {
  private static final Column G1 = CoGenFixtures.column("G1", DataTypeMapping.VARCHAR);
  private static final Column G2 = CoGenFixtures.column("G2", DataTypeMapping.VARCHAR);

  /**
   * Implements a stateful rule-based data generator that emits values from its own internal
   * sequence without implementing the index interface.
   */
  public static class Plain implements RuleBasedDataGenerator {
    private final AtomicLong next = new AtomicLong();
    private final Column rule;

    public Plain(Column rule) {
      this.rule = rule;
    }

    @Override
    public DataValue getDataValue() {
      return new DataValue(rule.getType(), "plain" + next.getAndIncrement());
    }
  }

  /**
   * Implements a rule-based data generator that maintains its own sequence and also implements the
   * index interface for deterministic index-based generation.
   */
  public static class Indexed implements RuleBasedDataGenerator, IndexedDataGenerator {
    private final AtomicLong next = new AtomicLong();

    public Indexed(Column rule) {
    }

    @Override
    public DataValue getDataValue() {
      return new DataValue(DataTypeMapping.VARCHAR, "rule" + next.getAndIncrement());
    }

    @Override
    public DataValue valueAt(long index, long seed) {
      return new DataValue(DataTypeMapping.VARCHAR, "index" + index + "/" + seed);
    }
  }

  @Test
  public void testPlainClassKeepsItsValues() throws Exception {
    XMLConfigParser parser = CoGenFixtures.parser();
    RulesApplier rules = rules(parser);
    Scenario scenario = CoGenFixtures.scenario(parser, CoGenFixtures.CLASSES);
    for (int i = 0; i < 3; i++) {
      assertEquals("plain" + i, rules.getDataForRule(scenario, G1).getValue());
    }
    // Under co-generation, the generator continues to emit its own sequential values regardless of
    // the requested index.
    IndexDecoder decoder = CoGenFixtures.decoder(parser, CoGenFixtures.CLASSES, null);
    long[] indexes = { 5, 0, 9 };
    for (int i = 0; i < indexes.length; i++) {
      assertEquals("plain" + i, decoder.value(G1, indexes[i]).getValue());
    }
  }

  /**
   * Verifies that statement decoding maintains a separate instance of any generator class that does
   * not implement the index interface, ensuring that read statement evaluations sharing the writer
   * JVM do not advance the sequence used for row generation.
   */
  @Test
  public void testStatementsDoNotMoveTheRowSequence() throws Exception {
    IndexDecoder decoder =
      CoGenFixtures.decoder(CoGenFixtures.parser(), CoGenFixtures.CLASSES, null);
    List<Column> columns = Collections.singletonList(G1);
    int read = 0;
    for (int i = 0; i < 6; i++) {
      assertEquals("plain" + i, decoder.row(columns, i).get(0).getValue());
      for (int k = 0; k < 3; k++) {
        assertEquals("G1 = 'plain" + read++ + "'", decoder.statement("G1 = [G1]", () -> 0));
      }
    }
    assertEquals("plain6", decoder.value(G1, 0).getValue());
  }

  @Test
  public void testIndexedClassGivesIndexValues() throws Exception {
    XMLConfigParser parser = CoGenFixtures.parser();
    RulesApplier rules = rules(parser);
    Scenario scenario = CoGenFixtures.scenario(parser, CoGenFixtures.CLASSES);
    for (int i = 0; i < 3; i++) {
      assertEquals("rule" + i, rules.getDataForRule(scenario, G2).getValue());
    }
    IndexDecoder decoder = CoGenFixtures.decoder(parser, CoGenFixtures.CLASSES, null);
    long seed = IndexHash.columnSeed(0, "G2");
    for (long i = 4; i >= 0; i--) {
      assertEquals("index" + i + "/" + seed, decoder.value(G2, i).getValue());
      assertEquals("index" + i + "/" + seed, decoder.value(G2, i).getValue());
    }
  }

  /**
   * Verifies that when a configured generator class cannot be instantiated, the resulting exception
   * message identifies both the target column name and the offending class name.
   */
  @Test
  public void testBadClassNamesColumnAndClass() {
    for (String name : new String[] { "org.apache.phoenix.pherf.NoSuchGenerator",
      String.class.getName() }) {
      Column rule = CoGenFixtures.column("G9", DataTypeMapping.VARCHAR);
      rule.setGeneratorClass(name);
      try {
        RulesApplier.newGenerator(rule);
        fail("made " + name);
      } catch (IllegalArgumentException e) {
        assertTrue(e.getMessage(), e.getMessage().contains("G9") && e.getMessage().contains(name));
      }
    }
  }

  private static RulesApplier rules(XMLConfigParser parser) {
    DataModel model = parser.getDataModels().get(0);
    return new RulesApplier(model, 1);
  }
}
