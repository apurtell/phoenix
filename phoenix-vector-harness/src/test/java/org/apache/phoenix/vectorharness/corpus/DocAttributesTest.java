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
package org.apache.phoenix.vectorharness.corpus;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class DocAttributesTest {

  private static DocAttributes doc() {
    return new DocAttributes(7L, 2, "t0002", "deadbeef000000000007", 11, 22, 3, 1000L, "body");
  }

  @Test
  public void testAccessors() {
    DocAttributes a = doc();
    assertEquals(7L, a.ordinal());
    assertEquals(2, a.tenant());
    assertEquals("t0002", a.tenantId());
    assertEquals("deadbeef000000000007", a.docId());
    assertEquals(11, a.source());
    assertEquals(22, a.category());
    assertEquals(3, a.aclTag());
    assertEquals(1000L, a.createdAtMillis());
    assertEquals("body", a.body());
  }

  @Test
  public void testWithTerms() {
    DocAttributes a = doc();
    DocAttributes b = a.withTerms(5, 6, 7);
    assertNotSame(a, b);
    assertEquals(5, b.source());
    assertEquals(6, b.category());
    assertEquals(7, b.aclTag());
    assertEquals(a.ordinal(), b.ordinal());
    assertEquals(a.tenant(), b.tenant());
    assertEquals(a.tenantId(), b.tenantId());
    assertEquals(a.docId(), b.docId());
    assertEquals(a.createdAtMillis(), b.createdAtMillis());
    assertEquals(a.body(), b.body());
    // The original is unchanged
    assertEquals(11, a.source());
    assertEquals(22, a.category());
    assertEquals(3, a.aclTag());
    assertEquals(a, a.withTerms(11, 22, 3));
  }

  @Test
  public void testEqualsAndHashCode() {
    DocAttributes a = doc();
    DocAttributes b = doc();
    assertEquals(a, a);
    assertEquals(a, b);
    assertEquals(a.hashCode(), b.hashCode());
    assertFalse(a.equals(null));
    assertFalse(a.equals("t0002"));
    DocAttributes[] different =
      { new DocAttributes(8L, 2, "t0002", "deadbeef000000000007", 11, 22, 3, 1000L, "body"),
        new DocAttributes(7L, 3, "t0002", "deadbeef000000000007", 11, 22, 3, 1000L, "body"),
        new DocAttributes(7L, 2, "t0003", "deadbeef000000000007", 11, 22, 3, 1000L, "body"),
        new DocAttributes(7L, 2, "t0002", "deadbeef000000000008", 11, 22, 3, 1000L, "body"),
        a.withTerms(12, 22, 3), a.withTerms(11, 23, 3), a.withTerms(11, 22, 4),
        new DocAttributes(7L, 2, "t0002", "deadbeef000000000007", 11, 22, 3, 1001L, "body"),
        new DocAttributes(7L, 2, "t0002", "deadbeef000000000007", 11, 22, 3, 1000L, "other"),
        new DocAttributes(7L, 2, "t0002", "deadbeef000000000007", 11, 22, 3, 1000L, null) };
    for (DocAttributes d : different) {
      assertNotEquals(d.toString(), a, d);
      assertNotEquals(d.toString(), d, a);
    }
    DocAttributes nullBody =
      new DocAttributes(7L, 2, "t0002", "deadbeef000000000007", 11, 22, 3, 1000L, null);
    assertEquals(nullBody,
      new DocAttributes(7L, 2, "t0002", "deadbeef000000000007", 11, 22, 3, 1000L, null));
  }

  @Test
  public void testToString() {
    String s = doc().toString();
    assertTrue(s, s.contains("t0002"));
    assertTrue(s, s.contains("deadbeef000000000007"));
    assertTrue(s, s.contains("#7"));
  }
}
