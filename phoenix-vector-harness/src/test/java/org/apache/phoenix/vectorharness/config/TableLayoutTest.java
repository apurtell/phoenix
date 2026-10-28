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
package org.apache.phoenix.vectorharness.config;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.util.EnumSet;
import org.junit.Test;

public class TableLayoutTest {

  @Test
  public void testNames() {
    assertEquals("DOC_CORPUS", TableLayout.CORPUS.baseName());
    assertEquals("DOC_PROFILE", TableLayout.PROFILE.baseName());
    assertEquals("corpus", TableLayout.CORPUS.id());
    assertEquals("profile", TableLayout.PROFILE.id());
  }

  @Test
  public void testParseList() {
    assertEquals(EnumSet.of(TableLayout.CORPUS, TableLayout.PROFILE),
      TableLayout.parseList("corpus,profile"));
    assertEquals(EnumSet.of(TableLayout.CORPUS), TableLayout.parseList(" Corpus "));
    assertEquals(EnumSet.of(TableLayout.CORPUS), TableLayout.parseList("DOC_CORPUS"));
    assertEquals(EnumSet.of(TableLayout.PROFILE), TableLayout.parseList("doc_profile,profile"));
    assertEquals(EnumSet.noneOf(TableLayout.class), TableLayout.parseList(""));
    assertEquals(EnumSet.noneOf(TableLayout.class), TableLayout.parseList(" , "));
    for (TableLayout l : TableLayout.values()) {
      assertEquals(EnumSet.of(l), TableLayout.parseList(l.id()));
      assertEquals(EnumSet.of(l), TableLayout.parseList(l.baseName()));
    }
  }

  @Test
  public void testParseUnknown() {
    try {
      TableLayout.parseList("corpus,columnar");
      fail("Expected an exception");
    } catch (IllegalArgumentException e) {
      assertTrue(e.getMessage(), e.getMessage().contains("columnar"));
    }
  }
}
