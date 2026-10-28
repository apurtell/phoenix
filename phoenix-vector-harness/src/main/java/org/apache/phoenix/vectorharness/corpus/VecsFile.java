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

import java.io.Closeable;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Random access to a TEXMEX vector file: {@code .fvecs} (float32 elements), {@code .bvecs}
 * (unsigned byte elements), or {@code .ivecs} (int32 elements). Each record is a little-endian
 * int32 dimension followed by that many elements, and every record of a file has the same
 * dimension. The file is memory mapped in chunks, so files larger than 2 GB are supported.
 */
public final class VecsFile implements Closeable {

  /** Element formats of the TEXMEX files. */
  public enum Format {
    FVECS(4),
    BVECS(1),
    IVECS(4);

    private final int elementBytes;

    Format(int elementBytes) {
      this.elementBytes = elementBytes;
    }

    public int elementBytes() {
      return elementBytes;
    }

    /** Returns the format of a file from its extension. */
    public static Format of(Path file) {
      String name = file.getFileName().toString().toLowerCase(Locale.ROOT);
      if (name.endsWith(".fvecs")) {
        return FVECS;
      }
      if (name.endsWith(".bvecs")) {
        return BVECS;
      }
      if (name.endsWith(".ivecs")) {
        return IVECS;
      }
      throw new IllegalArgumentException("Not a TEXMEX vector file: " + file);
    }
  }

  private static final long MAX_CHUNK = 1L << 30;

  private final Path path;
  private final Format format;
  private final FileChannel channel;
  private final int dim;
  private final long recordBytes;
  private final long count;
  private final long recordsPerChunk;
  private final List<MappedByteBuffer> chunks = new ArrayList<>();

  public VecsFile(Path path) throws IOException {
    this.path = path;
    this.format = Format.of(path);
    this.channel = FileChannel.open(path, StandardOpenOption.READ);
    long size = channel.size();
    if (size < 4) {
      channel.close();
      throw new IOException("Empty vector file: " + path);
    }
    ByteBuffer head = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN);
    channel.read(head, 0);
    head.flip();
    this.dim = head.getInt();
    if (dim <= 0 || dim > 1 << 20) {
      channel.close();
      throw new IOException("Invalid dimension " + dim + " in " + path);
    }
    this.recordBytes = 4L + (long) dim * format.elementBytes();
    if (size % recordBytes != 0) {
      channel.close();
      throw new IOException(String.format(Locale.ROOT,
        "Size %d of %s is not a multiple of the record size %d", size, path, recordBytes));
    }
    this.count = size / recordBytes;
    this.recordsPerChunk = Math.max(1, MAX_CHUNK / recordBytes);
    for (long start = 0; start < count; start += recordsPerChunk) {
      long n = Math.min(recordsPerChunk, count - start);
      MappedByteBuffer buf =
        channel.map(FileChannel.MapMode.READ_ONLY, start * recordBytes, n * recordBytes);
      buf.order(ByteOrder.LITTLE_ENDIAN);
      chunks.add(buf);
    }
  }

  public Path path() {
    return path;
  }

  public Format format() {
    return format;
  }

  public int dim() {
    return dim;
  }

  /** Returns the number of records. */
  public long count() {
    return count;
  }

  /** Returns record i as floats; byte and int elements are widened. */
  public float[] floats(long i) {
    ByteBuffer buf = record(i);
    int base = buf.position();
    float[] out = new float[dim];
    switch (format) {
      case FVECS:
        for (int j = 0; j < dim; j++) {
          out[j] = buf.getFloat(base + 4 + 4 * j);
        }
        break;
      case BVECS:
        for (int j = 0; j < dim; j++) {
          out[j] = buf.get(base + 4 + j) & 0xff;
        }
        break;
      case IVECS:
        for (int j = 0; j < dim; j++) {
          out[j] = buf.getInt(base + 4 + 4 * j);
        }
        break;
      default:
        throw new IllegalStateException();
    }
    return out;
  }

  /** Returns record i as ints, for ground truth files. */
  public int[] ints(long i) {
    if (format != Format.IVECS) {
      throw new IllegalStateException("Not an ivecs file: " + path);
    }
    ByteBuffer buf = record(i);
    int base = buf.position();
    int[] out = new int[dim];
    for (int j = 0; j < dim; j++) {
      out[j] = buf.getInt(base + 4 + 4 * j);
    }
    return out;
  }

  private ByteBuffer record(long i) {
    if (i < 0 || i >= count) {
      throw new IndexOutOfBoundsException("Record " + i + " outside [0, " + count + ") of " + path);
    }
    int chunk = (int) (i / recordsPerChunk);
    long within = (i % recordsPerChunk) * recordBytes;
    ByteBuffer buf = chunks.get(chunk).duplicate().order(ByteOrder.LITTLE_ENDIAN);
    buf.position((int) within);
    int d = buf.getInt((int) within);
    if (d != dim) {
      throw new IllegalStateException(
        "Record " + i + " of " + path + " has dimension " + d + ", expected " + dim);
    }
    return buf;
  }

  @Override
  public void close() throws IOException {
    chunks.clear();
    channel.close();
  }

  /** Writes float vectors as an fvecs file, for tests and for exporting synthetic corpora. */
  public static void writeFvecs(Path path, List<float[]> vectors) throws IOException {
    try (OutputStream out = Files.newOutputStream(path)) {
      for (float[] v : vectors) {
        ByteBuffer bb = ByteBuffer.allocate(4 + 4 * v.length).order(ByteOrder.LITTLE_ENDIAN);
        bb.putInt(v.length);
        for (float f : v) {
          bb.putFloat(f);
        }
        out.write(bb.array());
      }
    }
  }

  /** Writes byte vectors as a bvecs file. Elements must be in [0, 255]. */
  public static void writeBvecs(Path path, List<int[]> vectors) throws IOException {
    try (OutputStream out = Files.newOutputStream(path)) {
      for (int[] v : vectors) {
        ByteBuffer bb = ByteBuffer.allocate(4 + v.length).order(ByteOrder.LITTLE_ENDIAN);
        bb.putInt(v.length);
        for (int x : v) {
          bb.put((byte) x);
        }
        out.write(bb.array());
      }
    }
  }

  /** Writes int vectors as an ivecs file. */
  public static void writeIvecs(Path path, List<int[]> vectors) throws IOException {
    try (OutputStream out = Files.newOutputStream(path)) {
      for (int[] v : vectors) {
        ByteBuffer bb = ByteBuffer.allocate(4 + 4 * v.length).order(ByteOrder.LITTLE_ENDIAN);
        bb.putInt(v.length);
        for (int x : v) {
          bb.putInt(x);
        }
        out.write(bb.array());
      }
    }
  }
}
