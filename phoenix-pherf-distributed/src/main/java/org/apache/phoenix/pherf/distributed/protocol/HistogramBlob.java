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
package org.apache.phoenix.pherf.distributed.protocol;

import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.Base64;
import java.util.zip.DataFormatException;
import java.util.zip.Deflater;
import org.HdrHistogram.Histogram;

/**
 * Encodes and decodes an HDR histogram as compressed base64 text. The serialized representation is
 * identical to the payload that HistogramLogWriter writes for a histogram, using deflate level 9
 * compression and standard base64 encoding, which allows an interval log line to be transmitted as
 * it is.
 */
public final class HistogramBlob {
  private HistogramBlob() {
  }

  public static String encode(Histogram histogram) {
    ByteBuffer buffer = ByteBuffer.allocate(histogram.getNeededByteBufferCapacity());
    int length = histogram.encodeIntoCompressedByteBuffer(buffer, Deflater.BEST_COMPRESSION);
    return Base64.getEncoder().encodeToString(Arrays.copyOf(buffer.array(), length));
  }

  public static Histogram decode(String blob) throws ProtocolException {
    try {
      return Histogram
        .decodeFromCompressedByteBuffer(ByteBuffer.wrap(Base64.getDecoder().decode(blob)), 0);
    } catch (IllegalArgumentException | DataFormatException | IndexOutOfBoundsException e) {
      throw new ProtocolException("bad histogram: " + e.getMessage(), e);
    }
  }
}
