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

import static org.apache.phoenix.pherf.rules.IndexHash.below;
import static org.apache.phoenix.pherf.rules.IndexHash.draw;
import static org.apache.phoenix.pherf.rules.IndexHash.unit;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.math.BigDecimal;
import java.math.MathContext;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import org.apache.commons.lang3.StringUtils;
import org.apache.hadoop.hbase.util.Bytes;
import org.apache.phoenix.pherf.PherfConstants;
import org.apache.phoenix.pherf.configuration.Column;
import org.apache.phoenix.pherf.configuration.DataSequence;
import org.apache.phoenix.pherf.configuration.DataTypeMapping;

import org.apache.phoenix.thirdparty.com.google.common.base.Preconditions;

/**
 * Computes the deterministic, index-based value for a single column rule. The generated value is a
 * pure function of the global row index and the column seed. Type evaluation branch order and value
 * bounds match those of {@link RulesApplier}, with the following differences:
 * <ul>
 * <li>SEQUENTIAL rules apply the formula of the corresponding Sequential generator with the counter
 * set equal to the row index, and this support also applies to BIGINT and UNSIGNED_LONG.</li>
 * <li>SEQUENTIAL DATE and TIMESTAMP rules start at 1970-01-01 00:00:00.000 rather than at the
 * current time.</li>
 * <li>A weighted LIST rule selects its entry from the exact cumulative running sum of the
 * weights.</li>
 * <li>Because the current date is not a function of the row index, any rule that uses it is
 * rejected.</li>
 * <li>DECIMAL values and array elements use a decimal text representation that does not depend on
 * the JDK.</li>
 * </ul>
 * The constructor validates the rule configuration so that an invalid rule fails immediately at its
 * first use. Null probability evaluation is not part of the generated value; see {@link #isNull}.
 */
public final class IndexedRule implements IndexedDataGenerator {
  private static final String ALPHANUMERIC =
    "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz";
  // The number of significant decimal digits required to recover the exact same double or float
  // value on round-trip parsing.
  private static final int DOUBLE_DIGITS = 17;
  private static final int FLOAT_DIGITS = 9;
  private static final DateTimeFormatter DATES =
    DateTimeFormatter.ofPattern(PherfConstants.DEFAULT_DATE_PATTERN).withZone(ZoneOffset.UTC);

  private enum Kind {
    LIST,
    SEQUENTIAL_LIST,
    SEQUENTIAL_TEXT,
    TEXT,
    DOCUMENT,
    ENCODED,
    NUMBER_ARRAY,
    CONSTANT,
    DECIMAL,
    INTEGER,
    SEQUENTIAL_INTEGER,
    LONG,
    DATE,
    SEQUENTIAL_DATE
  }

  private final Column rule;
  private final DataTypeMapping type;
  private final Kind kind;
  private final String prefix;
  private final int length;
  private long min;
  private long max;
  private double minDouble;
  private double maxDouble;
  private String constant;
  private List<DataValue> values;
  private boolean weighted;
  // Holds the formatted text for each list entry, or null when the entry specifies a date range.
  private String[] texts;
  // Stores the first and last epoch millisecond bounds for each list entry that defines a date
  // range.
  private long[][] ranges;

  public IndexedRule(Column rule) {
    this.rule = rule;
    this.type = rule.getType();
    this.prefix = rule.getPrefix() == null ? "" : rule.getPrefix();
    this.length = rule.getLength();
    this.kind = kind();
  }

  /**
   * Determines whether the rule's configured null chance causes the value for the specified row to
   * be null.
   */
  public static boolean isNull(Column rule, long index, long seed) {
    int chance = rule.getNullChance();
    return chance != Integer.MIN_VALUE
      && below(draw(IndexHash.rowSeed(seed, index), 0), 100) < chance;
  }

  /**
   * Validates the rule configuration and resolves its execution kind by following the branch
   * evaluation order of the standard rule path.
   */
  private Kind kind() {
    String name = rule.getName();
    List<DataValue> list = rule.getDataValues();
    boolean hasValues = list != null && !list.isEmpty();
    boolean sequential = DataSequence.SEQUENTIAL.equals(rule.getDataSequence());
    if (type == null) {
      throw new IllegalArgumentException("No type for column " + name);
    }
    switch (type) {
      case VARCHAR:
      case VARBINARY:
      case CHAR:
      case JSON:
      case BSON:
      case VARBINARY_ENCODED:
        if (sequential) {
          if (type == DataTypeMapping.VARBINARY_ENCODED) {
            throw noSequence(name);
          }
          if (type == DataTypeMapping.JSON || type == DataTypeMapping.BSON) {
            // Because a raw counter string is not a valid document, sequential document generation
            // requires an explicit value list.
            Preconditions.checkArgument(hasValues, "SEQUENTIAL %s needs a value list on %s", type,
              name);
          }
          if (hasValues) {
            values = list;
            return Kind.SEQUENTIAL_LIST;
          }
          return Kind.SEQUENTIAL_TEXT;
        }
        if (hasValues) {
          return list(list);
        }
        Preconditions.checkArgument(length > 0, "length needs to be > 0");
        if (type == DataTypeMapping.JSON || type == DataTypeMapping.BSON) {
          return Kind.DOCUMENT;
        }
        return type == DataTypeMapping.VARBINARY_ENCODED ? Kind.ENCODED : Kind.TEXT;
      case FLOAT_ARRAY:
      case DOUBLE_ARRAY:
        if (sequential) {
          throw noSequence(name);
        }
        if (hasValues) {
          constant = join(list);
          return Kind.CONSTANT;
        }
        min = rule.getMinValue();
        max = rule.getMaxValue();
        Preconditions.checkArgument(length > 0,
          "length (the element count) needs to be > 0 for " + name);
        Preconditions.checkArgument(min != Long.MIN_VALUE && max != Long.MIN_VALUE && max >= min,
          "minValue and maxValue need to be set, with maxValue >= minValue, for " + name);
        return Kind.NUMBER_ARRAY;
      case VARCHAR_ARRAY:
        constant = hasValues ? join(list) : "";
        return Kind.CONSTANT;
      case DECIMAL:
        if (hasValues) {
          return list(list);
        }
        int precision = rule.getPrecision();
        minDouble = rule.getMinValue();
        Preconditions.checkArgument((precision > 0) && (precision <= 18),
          "Precision must be between 0 and 18");
        Preconditions.checkArgument(minDouble >= 0,
          "minvalue must be set in configuration for decimal");
        Preconditions.checkArgument(rule.getMaxValue() > 0,
          "maxValue must be set in configuration decimal");
        maxDouble =
          Math.min(rule.getMaxValue(), Double.parseDouble(StringUtils.repeat('9', precision)));
        Preconditions.checkArgument(maxDouble >= minDouble,
          "Start value must be smaller or equal to end value, for " + name);
        return Kind.DECIMAL;
      case TINYINT:
      case INTEGER:
        if (hasValues) {
          return list(list);
        }
        if (sequential) {
          return sequentialInteger();
        }
        min = (int) rule.getMinValue();
        max = (int) rule.getMaxValue();
        if (type == DataTypeMapping.TINYINT) {
          Preconditions.checkArgument((min >= -128) && (min <= 128),
            "min value need to be set in configuration for tinyints " + name);
          Preconditions.checkArgument((max >= -128) && (max <= 128),
            "max value need to be set in configuration for tinyints " + name);
        }
        Preconditions.checkArgument(max >= min,
          "maxValue " + max + " is less than minValue " + min + " for " + name);
        return Kind.INTEGER;
      case BIGINT:
      case UNSIGNED_LONG:
        if (hasValues) {
          return list(list);
        }
        if (sequential) {
          return sequentialInteger();
        }
        min = rule.getMinValue();
        max = rule.getMaxValue();
        if (type == DataTypeMapping.UNSIGNED_LONG) {
          Preconditions.checkArgument((min > 0) && (max > 0),
            "min and max values need to be set in configuration for unsigned_longs " + name);
        }
        Preconditions.checkArgument(max >= min,
          "Start value must be smaller or equal to end value, for " + name);
        Preconditions.checkArgument(min >= 0,
          "Both range values must be non-negative, for " + name);
        return Kind.LONG;
      case DATE:
      case TIMESTAMP:
        if (hasValues) {
          return list(list);
        }
        if (sequential) {
          return Kind.SEQUENTIAL_DATE;
        }
        if (rule.getUseCurrentDate()) {
          throw notIndexed(name);
        }
        int minYear = (int) rule.getMinValue();
        int maxYear = (int) rule.getMaxValue();
        Preconditions.checkArgument((minYear > 0) && (maxYear > 0),
          "min and max values need to be set in configuration for date/timestamps " + name);
        min = epochMillis(minYear + "-01-01 00:00:00.000") + 1;
        max = epochMillis(maxYear + "-12-31 23:59:59.999") - 1;
        Preconditions.checkArgument(max >= min, "minValue is after maxValue for " + name);
        return Kind.DATE;
      default:
        throw new IllegalArgumentException(
          "Data value could not be generated for some reason. Please check configs: type " + type
            + " of " + name + " has no index based value");
    }
  }

  private Kind sequentialInteger() {
    min = rule.getMinValue();
    max = rule.getMaxValue();
    return Kind.SEQUENTIAL_INTEGER;
  }

  /**
   * Validates a configured value list and records either the text representation or the date range
   * bounds for each entry.
   */
  private Kind list(List<DataValue> list) {
    int sum = 0;
    for (DataValue value : list) {
      sum += value.getDistribution();
    }
    Preconditions.checkArgument((sum == 100) || (sum == 0),
      "Distributions need to add up to 100 or not exist.");
    boolean dates = type == DataTypeMapping.DATE || type == DataTypeMapping.TIMESTAMP;
    values = list;
    weighted = sum == 100;
    texts = new String[list.size()];
    ranges = new long[list.size()][];
    for (int i = 0; i < list.size(); i++) {
      DataValue entry = list.get(i);
      if (entry.getValue() != null) {
        texts[i] = dates
          ? DATES.format(Instant.ofEpochMilli(epochMillis(entry.getValue())))
          : entry.getValue();
      } else if (entry.getUseCurrentDate()) {
        throw notIndexed(rule.getName());
      } else {
        Preconditions.checkArgument((entry.getMinValue() != null) || (entry.getMaxValue() != null),
          "Both min/maxValue tags must be set if value tag is not used");
        Preconditions.checkArgument((entry.getType() == DataTypeMapping.DATE),
          "Currently on DATE is supported for ranged random values");
        ranges[i] =
          new long[] { epochMillis(entry.getMinValue()) + 1, epochMillis(entry.getMaxValue()) - 1 };
        Preconditions.checkArgument(ranges[i][1] >= ranges[i][0],
          "minValue is after maxValue in the value list of " + rule.getName());
      }
    }
    return Kind.LIST;
  }

  /**
   * Returns true when the generated value strictly increases with the global index over the range
   * [0, last], which guarantees that the values within any index interval are bounded by the values
   * at its two endpoints. SEQUENTIAL DATE and TIMESTAMP rules always increase monotonically,
   * whereas a SEQUENTIAL integer rule increases until it wraps at maxValue - minValue + 1.
   */
  public boolean increasing(long last) {
    if (kind == Kind.SEQUENTIAL_DATE) {
      return true;
    }
    if (kind != Kind.SEQUENTIAL_INTEGER) {
      return false;
    }
    try {
      long values = Math.addExact(Math.subtractExact(max, min), 1);
      return last < values;
    } catch (ArithmeticException e) {
      return false;
    }
  }

  @Override
  public DataValue valueAt(long index, long seed) {
    return new DataValue(type, text(index, IndexHash.rowSeed(seed, index)));
  }

  private String text(long index, long row) {
    switch (kind) {
      case LIST:
        return pick(row);
      case SEQUENTIAL_LIST:
        return values.get((int) below(index, values.size())).getValue();
      case SEQUENTIAL_TEXT:
        int padded = rule.getLengthExcludingPrefix();
        String counter =
          StringUtils.right(StringUtils.leftPad(String.valueOf(index), padded, "x"), padded);
        return StringUtils.left(prefix + counter, length);
      case TEXT:
        return StringUtils.left(prefix + alphanumeric(row, 1, length), length);
      case DOCUMENT:
        return document(row);
      case ENCODED:
        byte[] bytes = new byte[length];
        for (int j = 0; j < length; j++) {
          bytes[j] = (byte) draw(row, 1 + j);
        }
        return Bytes.toHex(bytes);
      case NUMBER_ARRAY:
        List<String> elements = new ArrayList<>();
        for (int j = 0; j < length; j++) {
          double v = min + unit(draw(row, 1 + j)) * ((double) max - min);
          elements.add(type == DataTypeMapping.FLOAT_ARRAY
            ? digits((float) v, FLOAT_DIGITS)
            : digits(v, DOUBLE_DIGITS));
        }
        return String.join(",", elements);
      case CONSTANT:
        return constant;
      case DECIMAL:
        double d = minDouble == maxDouble
          ? minDouble
          : minDouble + unit(draw(row, 1)) * (maxDouble - minDouble);
        return digits(d, DOUBLE_DIGITS);
      case INTEGER:
        return String.valueOf(min + below(draw(row, 1), max - min + 1));
      case SEQUENTIAL_INTEGER:
        return String.valueOf((index % (max - min + 1)) + min);
      case LONG:
        return String.valueOf(min == max ? min : min + below(draw(row, 1), max - min));
      case DATE:
        return date(row, 1, min, max);
      case SEQUENTIAL_DATE:
        return DATES.format(Instant.ofEpochSecond(index));
      default:
        throw new IllegalStateException(kind.toString());
    }
  }

  /**
   * Selects an entry from a value list, resolving weighted distributions by choosing the first
   * entry whose cumulative running weight sum strictly exceeds a deterministic draw in [0, 100).
   */
  private String pick(long row) {
    long h = draw(row, 1);
    int k = 0;
    if (weighted) {
      long u = below(h, 100);
      int sum = 0;
      for (k = 0; k < values.size(); k++) {
        sum += values.get(k).getDistribution();
        if (sum > u) {
          break;
        }
      }
    } else {
      k = (int) below(h, values.size());
    }
    return texts[k] != null ? texts[k] : date(row, 2, ranges[k][0], ranges[k][1]);
  }

  private static String date(long row, int k, long first, long last) {
    return DATES.format(Instant.ofEpochMilli(first + below(draw(row, k), last - first + 1)));
  }

  /**
   * Formats a double into a decimal string that does not depend on the JDK by rounding the exact
   * binary value to a fixed number of significant digits without an exponent. Double.toString
   * changed behavior in JDK 19 and is therefore not used. The retained significant digits are
   * sufficient to recover the identical double or float value upon parsing.
   */
  private static String digits(double v, int significant) {
    return new BigDecimal(v).round(new MathContext(significant)).stripTrailingZeros()
      .toPlainString();
  }

  /**
   * Generates a JSON document matching the fixed document structure of the standard rule path,
   * consuming deterministic random draws sequentially in the declaration order of the fields.
   */
  private String document(long row) {
    int k = 1;
    JsonObject doc = new JsonObject();
    doc.addProperty("s", StringUtils.left(prefix + alphanumeric(row, k, length), length));
    k += length;
    doc.addProperty("n", (int) below(draw(row, k++), 1000000));
    doc.addProperty("d", unit(draw(row, k++)) * 1000);
    doc.addProperty("b", (draw(row, k++) & 1) == 1);
    JsonArray a = new JsonArray();
    for (int i = 0; i < 3; i++) {
      a.add((int) below(draw(row, k++), 1000));
    }
    doc.add("a", a);
    JsonObject o = new JsonObject();
    o.addProperty("s", alphanumeric(row, k, length));
    k += length;
    o.addProperty("n", (int) below(draw(row, k), 1000000));
    doc.add("o", o);
    return doc.toString();
  }

  /**
   * Builds a random alphanumeric string by sampling characters using sequential draw indices
   * starting at first, first + 1, and so on.
   */
  private static String alphanumeric(long row, int first, int count) {
    StringBuilder text = new StringBuilder(count);
    for (int j = 0; j < count; j++) {
      text.append(ALPHANUMERIC.charAt((int) below(draw(row, first + j), ALPHANUMERIC.length())));
    }
    return text.toString();
  }

  private static String join(List<DataValue> list) {
    List<String> texts = new ArrayList<>();
    for (DataValue value : list) {
      texts.add(value.getValue());
    }
    return String.join(",", texts);
  }

  private static long epochMillis(String date) {
    return ZonedDateTime.parse(date, DATES).toInstant().toEpochMilli();
  }

  private IllegalArgumentException noSequence(String name) {
    return new IllegalArgumentException(
      String.format("No rule based generator supported for column type %s on %s", type, name));
  }

  private static IllegalArgumentException notIndexed(String name) {
    return new IllegalArgumentException(
      "The current date of " + name + " is not a function of the row index");
  }
}
