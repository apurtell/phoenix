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
import static org.junit.Assert.assertNotEquals;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Consumer;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class DigestsTest {

  @Rule
  public TemporaryFolder tmp = new TemporaryFolder();

  private static String digest(Consumer<MessageDigest> body) {
    MessageDigest md = Digests.sha256();
    body.accept(md);
    return Digests.hex(md.digest());
  }

  private static String sha256(byte[] bytes) {
    MessageDigest md = Digests.sha256();
    md.update(bytes);
    return Digests.hex(md.digest());
  }

  @Test
  public void testHex() {
    assertEquals("", Digests.hex(new byte[0]));
    assertEquals("000ff0ff7f80",
      Digests.hex(new byte[] { 0x00, 0x0f, (byte) 0xf0, (byte) 0xff, 0x7f, (byte) 0x80 }));
  }

  @Test
  public void testSha256KnownValue() {
    assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
      sha256("abc".getBytes(StandardCharsets.US_ASCII)));
    assertEquals(64, digest(md -> {
    }).length());
  }

  @Test
  public void testLongIsLittleEndian() {
    byte[] le =
      ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong(0x0102030405060708L).array();
    assertEquals(sha256(le), digest(md -> Digests.update(md, 0x0102030405060708L)));
  }

  @Test
  public void testStringsAreLengthPrefixed() {
    String ab = digest(md -> {
      Digests.update(md, "ab");
      Digests.update(md, "c");
    });
    String a = digest(md -> {
      Digests.update(md, "a");
      Digests.update(md, "bc");
    });
    assertNotEquals(ab, a);
    byte[] utf8 = "h\u00e9".getBytes(StandardCharsets.UTF_8);
    ByteBuffer bb = ByteBuffer.allocate(8 + utf8.length).order(ByteOrder.LITTLE_ENDIAN);
    bb.putLong(utf8.length).put(utf8);
    assertEquals(sha256(bb.array()), digest(md -> Digests.update(md, "h\u00e9")));
  }

  @Test
  public void testFloatArrays() {
    float[] v = { 1.0f, -2.5f, 0.0f };
    ByteBuffer bb = ByteBuffer.allocate(4 + 12).order(ByteOrder.LITTLE_ENDIAN);
    bb.putInt(3).putFloat(1.0f).putFloat(-2.5f).putFloat(0.0f);
    assertEquals(sha256(bb.array()), digest(md -> Digests.update(md, v)));
    // Bit patterns, so -0.0 differs from 0.0
    assertNotEquals(digest(md -> Digests.update(md, new float[] { 0.0f })),
      digest(md -> Digests.update(md, new float[] { -0.0f })));
    // The length is part of the digest
    assertNotEquals(digest(md -> {
      Digests.update(md, new float[] { 1, 2 });
      Digests.update(md, new float[] { 3 });
    }), digest(md -> {
      Digests.update(md, new float[] { 1 });
      Digests.update(md, new float[] { 2, 3 });
    }));
  }

  @Test
  public void testMapsAreOrderIndependent() {
    Map<String, String> a = new LinkedHashMap<>();
    a.put("x", "1");
    a.put("y", "2");
    Map<String, String> b = new LinkedHashMap<>();
    b.put("y", "2");
    b.put("x", "1");
    assertEquals(digest(md -> Digests.update(md, a)), digest(md -> Digests.update(md, b)));
    Map<String, String> c = new LinkedHashMap<>(a);
    c.put("y", "3");
    assertNotEquals(digest(md -> Digests.update(md, a)), digest(md -> Digests.update(md, c)));
    Map<String, String> d = new LinkedHashMap<>(a);
    d.put("z", "");
    assertNotEquals(digest(md -> Digests.update(md, a)), digest(md -> Digests.update(md, d)));
  }

  @Test
  public void testDocAttributes() {
    DocAttributes a =
      new DocAttributes(7L, 2, "t0002", "deadbeef000000000007", 11, 22, 3, 1000L, "body");
    String base = digest(md -> Digests.update(md, a));
    assertEquals(base, digest(md -> Digests.update(md,
      new DocAttributes(7L, 2, "t0002", "deadbeef000000000007", 11, 22, 3, 1000L, "body"))));
    DocAttributes[] changed =
      { new DocAttributes(8L, 2, "t0002", "deadbeef000000000007", 11, 22, 3, 1000L, "body"),
        new DocAttributes(7L, 2, "t0003", "deadbeef000000000007", 11, 22, 3, 1000L, "body"),
        new DocAttributes(7L, 2, "t0002", "deadbeef000000000008", 11, 22, 3, 1000L, "body"),
        a.withTerms(12, 22, 3), a.withTerms(11, 23, 3), a.withTerms(11, 22, 4),
        new DocAttributes(7L, 2, "t0002", "deadbeef000000000007", 11, 22, 3, 1001L, "body"),
        new DocAttributes(7L, 2, "t0002", "deadbeef000000000007", 11, 22, 3, 1000L, "bodx") };
    for (DocAttributes c : changed) {
      assertNotEquals(c.toString(), base, digest(md -> Digests.update(md, c)));
    }
  }

  @Test
  public void testUpdateFile() throws IOException {
    byte[] data = new byte[200_000];
    for (int i = 0; i < data.length; i++) {
      data[i] = (byte) (i * 31 + 7);
    }
    Path file = tmp.newFile("data.bin").toPath();
    Files.write(file, data);
    for (long limit : new long[] { -1L, 0L, 1L, 65_535L, 65_536L, 65_537L, 150_000L, 200_000L,
      10_000_000L }) {
      long n = limit < 0 ? data.length : Math.min(limit, data.length);
      String expected = sha256(Arrays.copyOf(data, (int) n));
      String actual = digest(md -> {
        try {
          Digests.updateFile(md, file, limit);
        } catch (IOException e) {
          throw new RuntimeException(e);
        }
      });
      assertEquals("limit " + limit, expected, actual);
    }
  }
}
