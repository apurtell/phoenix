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

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.apache.phoenix.vectorharness.corpus.VecsFile.Format;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class VecsFileTest {

  @Rule
  public TemporaryFolder tmp = new TemporaryFolder();

  private Path file(String name) {
    return tmp.getRoot().toPath().resolve(name);
  }

  @Test
  public void testFormatOf() {
    assertSame(Format.FVECS, Format.of(Paths.get("/x/sift_base.fvecs")));
    assertSame(Format.FVECS, Format.of(Paths.get("SIFT_BASE.FVECS")));
    assertSame(Format.BVECS, Format.of(Paths.get("bigann_base.bvecs")));
    assertSame(Format.IVECS, Format.of(Paths.get("gt.ivecs")));
    assertEquals(4, Format.FVECS.elementBytes());
    assertEquals(1, Format.BVECS.elementBytes());
    assertEquals(4, Format.IVECS.elementBytes());
    for (String bad : new String[] { "base.hdf5", "fvecs", "base.fvecs.gz" }) {
      try {
        Format.of(Paths.get(bad));
        fail("Expected an exception for " + bad);
      } catch (IllegalArgumentException e) {
        assertTrue(e.getMessage().contains(bad));
      }
    }
  }

  @Test
  public void testFvecsRoundTrip() throws IOException {
    List<float[]> vectors = new ArrayList<>();
    SplitMix64 rnd = new SplitMix64(1L);
    for (int i = 0; i < 100; i++) {
      float[] v = new float[7];
      for (int j = 0; j < v.length; j++) {
        v[j] = (float) rnd.nextGaussian() * 100;
      }
      vectors.add(v);
    }
    vectors.add(new float[] { 0f, -0f, Float.MAX_VALUE, -Float.MIN_VALUE, Float.NaN,
      Float.POSITIVE_INFINITY, 1e-30f });
    Path p = file("vectors.fvecs");
    VecsFile.writeFvecs(p, vectors);
    assertEquals(101L * (4 + 4 * 7), Files.size(p));
    try (VecsFile f = new VecsFile(p)) {
      assertEquals(p, f.path());
      assertSame(Format.FVECS, f.format());
      assertEquals(7, f.dim());
      assertEquals(101L, f.count());
      for (int i = 0; i < vectors.size(); i++) {
        assertTrue("record " + i, Arrays.equals(vectors.get(i), f.floats(i)));
      }
      // Random access in any order
      assertTrue(Arrays.equals(vectors.get(50), f.floats(50)));
      assertTrue(Arrays.equals(vectors.get(3), f.floats(3)));
    }
  }

  @Test
  public void testBvecsRoundTrip() throws IOException {
    List<int[]> vectors = new ArrayList<>();
    for (int i = 0; i < 20; i++) {
      int[] v = new int[16];
      for (int j = 0; j < 16; j++) {
        v[j] = (i * 16 + j) % 256;
      }
      vectors.add(v);
    }
    Path p = file("vectors.bvecs");
    VecsFile.writeBvecs(p, vectors);
    assertEquals(20L * (4 + 16), Files.size(p));
    try (VecsFile f = new VecsFile(p)) {
      assertSame(Format.BVECS, f.format());
      assertEquals(16, f.dim());
      assertEquals(20L, f.count());
      for (int i = 0; i < 20; i++) {
        float[] got = f.floats(i);
        for (int j = 0; j < 16; j++) {
          // Bytes are unsigned
          assertEquals(vectors.get(i)[j], got[j], 0.0f);
        }
      }
      assertEquals(255.0f, f.floats(15)[15], 0.0f);
    }
  }

  @Test
  public void testIvecsRoundTrip() throws IOException {
    List<int[]> vectors = new ArrayList<>();
    vectors.add(new int[] { 0, 1, 2, 3, 4 });
    vectors.add(new int[] { -1, Integer.MAX_VALUE, Integer.MIN_VALUE, 999_999, 42 });
    vectors.add(new int[] { 5, 4, 3, 2, 1 });
    Path p = file("gt.ivecs");
    VecsFile.writeIvecs(p, vectors);
    try (VecsFile f = new VecsFile(p)) {
      assertSame(Format.IVECS, f.format());
      assertEquals(5, f.dim());
      assertEquals(3L, f.count());
      for (int i = 0; i < 3; i++) {
        assertArrayEquals(vectors.get(i), f.ints(i));
      }
      assertArrayEquals(new float[] { 0, 1, 2, 3, 4 }, f.floats(0), 0.0f);
      assertEquals(999_999f, f.floats(1)[3], 0.0f);
    }
  }

  @Test
  public void testIntsRequireIvecs() throws IOException {
    Path p = file("v.fvecs");
    VecsFile.writeFvecs(p, Arrays.asList(new float[] { 1, 2 }));
    try (VecsFile f = new VecsFile(p)) {
      f.ints(0);
      fail("Expected an exception");
    } catch (IllegalStateException e) {
      assertTrue(e.getMessage().contains("ivecs"));
    }
  }

  @Test
  public void testRecordBounds() throws IOException {
    Path p = file("v.fvecs");
    VecsFile.writeFvecs(p, Arrays.asList(new float[] { 1, 2 }, new float[] { 3, 4 }));
    try (VecsFile f = new VecsFile(p)) {
      for (long bad : new long[] { -1L, 2L, Long.MAX_VALUE }) {
        try {
          f.floats(bad);
          fail("Expected an exception for " + bad);
        } catch (IndexOutOfBoundsException e) {
          // expected
        }
      }
    }
  }

  @Test
  public void testRejectsSizeThatIsNotAMultipleOfTheRecord() throws IOException {
    Path p = file("bad.fvecs");
    VecsFile.writeFvecs(p, Arrays.asList(new float[] { 1, 2, 3 }, new float[] { 4, 5, 6 }));
    Files.write(p, new byte[] { 1, 2, 3 }, StandardOpenOption.APPEND);
    try {
      new VecsFile(p).close();
      fail("Expected an exception");
    } catch (IOException e) {
      assertTrue(e.getMessage(), e.getMessage().contains("not a multiple"));
    }
    // A truncated last record
    Path q = file("short.ivecs");
    VecsFile.writeIvecs(q, Arrays.asList(new int[] { 1, 2, 3 }, new int[] { 4, 5, 6 }));
    byte[] all = Files.readAllBytes(q);
    Files.write(q, Arrays.copyOf(all, all.length - 4));
    try {
      new VecsFile(q).close();
      fail("Expected an exception");
    } catch (IOException e) {
      assertTrue(e.getMessage(), e.getMessage().contains("not a multiple"));
    }
  }

  @Test
  public void testRejectsEmptyAndInvalidHeaders() throws IOException {
    Path empty = file("empty.fvecs");
    Files.write(empty, new byte[0]);
    expectIOException(empty, "Empty");
    Path tiny = file("tiny.fvecs");
    Files.write(tiny, new byte[] { 1, 0 });
    expectIOException(tiny, "Empty");
    for (int dim : new int[] { 0, -3, (1 << 20) + 1 }) {
      Path p = file("dim" + dim + ".fvecs");
      Files.write(p,
        ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putInt(dim).putInt(0).array());
      expectIOException(p, "Invalid dimension");
    }
  }

  @Test
  public void testRejectsBigEndianFile() throws IOException {
    // A big-endian header of dimension 4 reads as 67108864, which is not a valid dimension.
    Path p = file("be.fvecs");
    ByteBuffer bb = ByteBuffer.allocate(20).order(ByteOrder.BIG_ENDIAN);
    bb.putInt(4).putFloat(1).putFloat(2).putFloat(3).putFloat(4);
    Files.write(p, bb.array());
    expectIOException(p, "Invalid dimension");
  }

  @Test
  public void testDetectsInconsistentRecordDimension() throws IOException {
    // Two records of 12 bytes, the second claiming dimension 5
    Path p = file("mixed.fvecs");
    ByteBuffer bb = ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN);
    bb.putInt(2).putFloat(1).putFloat(2);
    bb.putInt(5).putFloat(3).putFloat(4);
    Files.write(p, bb.array());
    try (VecsFile f = new VecsFile(p)) {
      assertEquals(2L, f.count());
      assertArrayEquals(new float[] { 1, 2 }, f.floats(0), 0.0f);
      try {
        f.floats(1);
        fail("Expected an exception");
      } catch (IllegalStateException e) {
        assertTrue(e.getMessage(), e.getMessage().contains("dimension 5"));
      }
    }
  }

  @Test
  public void testRejectsWrongExtension() throws IOException {
    Path p = file("v.bin");
    Files.write(p, new byte[8]);
    try {
      new VecsFile(p).close();
      fail("Expected an exception");
    } catch (IllegalArgumentException e) {
      assertTrue(e.getMessage().contains("v.bin"));
    }
  }

  @Test
  public void testManyRecords() throws IOException {
    List<float[]> vectors = new ArrayList<>();
    for (int i = 0; i < 20000; i++) {
      vectors.add(new float[] { i, -i, i * 0.5f });
    }
    Path p = file("many.fvecs");
    VecsFile.writeFvecs(p, vectors);
    try (VecsFile f = new VecsFile(p)) {
      assertEquals(20000L, f.count());
      for (int i = 0; i < 20000; i += 997) {
        assertArrayEquals(vectors.get(i), f.floats(i), 0.0f);
      }
      assertArrayEquals(vectors.get(19999), f.floats(19999), 0.0f);
    }
  }

  private static void expectIOException(Path p, String message) {
    try {
      new VecsFile(p).close();
      fail("Expected an exception for " + p);
    } catch (IOException e) {
      assertTrue(e.getMessage(), e.getMessage().contains(message));
    }
  }
}
