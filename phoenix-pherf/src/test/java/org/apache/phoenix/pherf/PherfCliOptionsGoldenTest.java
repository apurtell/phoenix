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
package org.apache.phoenix.pherf;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import org.apache.phoenix.pherf.run.Golden;
import org.junit.Test;

import org.apache.phoenix.thirdparty.org.apache.commons.cli.Option;
import org.apache.phoenix.thirdparty.org.apache.commons.cli.Options;

/**
 * Verifies that the Pherf command-line interface preserves every option defined in the golden file
 * with identical attributes. Future changes may introduce additional options without requiring
 * modifications to this test.
 */
public class PherfCliOptionsGoldenTest {
  private static final String GOLDEN = "pherf-cli-options.golden";

  @Test
  public void testOptionSetUnchanged() throws Exception {
    Field field = Pherf.class.getDeclaredField("options");
    field.setAccessible(true);
    Options options = (Options) field.get(null);
    List<String> actual = new ArrayList<>();
    StringBuilder text = new StringBuilder();
    for (Option option : options.getOptions()) {
      String line = option.getOpt() + "|" + option.getLongOpt() + "|" + option.hasArg() + "|"
        + option.isRequired() + "|" + option.getDescription().replace("\n", "\\n");
      actual.add(line);
      text.append(line).append('\n');
    }
    Golden.capture(getClass().getSimpleName(), GOLDEN, text.toString());

    List<String> golden = Golden.lines(GOLDEN);
    assertEquals(28, golden.size());
    for (String line : golden) {
      assertTrue("Option changed or removed: " + line, actual.contains(line));
    }
  }
}
