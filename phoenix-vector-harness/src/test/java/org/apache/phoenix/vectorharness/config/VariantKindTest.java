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

import org.junit.Test;

public class VariantKindTest {

  @Test
  public void testIds() {
    assertEquals("exact", VariantKind.EXACT.id());
    assertEquals("ivf", VariantKind.IVF.id());
    assertEquals("hnsw", VariantKind.HNSW.id());
  }

  @Test
  public void testParse() {
    assertEquals(VariantKind.EXACT, VariantKind.parse("exact"));
    assertEquals(VariantKind.IVF, VariantKind.parse(" IVF "));
    assertEquals(VariantKind.HNSW, VariantKind.parse("Hnsw"));
    for (VariantKind v : VariantKind.values()) {
      assertEquals(v, VariantKind.parse(v.id()));
    }
  }

  @Test
  public void testParseUnknown() {
    for (String bad : new String[] { "ivfpq", "", "diskann" }) {
      try {
        VariantKind.parse(bad);
        fail("Expected an exception for " + bad);
      } catch (IllegalArgumentException e) {
        assertTrue(e.getMessage(), e.getMessage().contains("exact, ivf, or hnsw"));
      }
    }
  }
}
