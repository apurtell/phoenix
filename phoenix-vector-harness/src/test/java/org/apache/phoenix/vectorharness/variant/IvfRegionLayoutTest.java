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
package org.apache.phoenix.vectorharness.variant;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import org.apache.hadoop.hbase.HConstants;
import org.apache.hadoop.hbase.util.Bytes;
import org.apache.phoenix.vectorharness.config.HarnessConfig;
import org.junit.Test;

public class IvfRegionLayoutTest {

  private static IvfRegionLayout.Plan plan(String... kv) {
    Properties p = new Properties();
    p.setProperty("ivf.lists", "8");
    for (int i = 0; i < kv.length; i += 2) {
      p.setProperty(kv[i], kv[i + 1]);
    }
    return IvfRegionLayout.plan(HarnessConfig.of("t", p));
  }

  @Test
  public void testStepIsTheCeilingOfListsOverRegions() {
    assertEquals(4, IvfRegionLayout.step(8, 2));
    assertEquals(3, IvfRegionLayout.step(10, 4));
    assertEquals(1, IvfRegionLayout.step(3, 8));
    assertEquals(1, IvfRegionLayout.step(0, 4));
    assertEquals(1, IvfRegionLayout.step(8, 0));
  }

  @Test
  public void testEvenSplitIds() {
    assertEquals(Arrays.asList(4), IvfRegionLayout.evenSplitIds(0, 8, 2));
    assertEquals(Arrays.asList(2, 4, 6), IvfRegionLayout.evenSplitIds(0, 8, 4));
    assertEquals(Arrays.asList(3, 6, 9), IvfRegionLayout.evenSplitIds(0, 10, 4));
    // A step of two lists reaches the end of the range after five regions.
    assertEquals(Arrays.asList(2, 4, 6, 8), IvfRegionLayout.evenSplitIds(0, 10, 6));
    assertEquals(Arrays.asList(1, 2), IvfRegionLayout.evenSplitIds(0, 3, 8));
    assertEquals(Arrays.asList(12), IvfRegionLayout.evenSplitIds(8, 8, 2));
    assertTrue(IvfRegionLayout.evenSplitIds(0, 8, 1).isEmpty());
    assertTrue(IvfRegionLayout.evenSplitIds(0, 1, 4).isEmpty());
  }

  @Test
  public void testFutureRangeSplitIdsStartAtTheRange() {
    assertEquals(Arrays.asList(8, 12), IvfRegionLayout.futureRangeSplitIds(8, 8, 2));
    assertEquals(Arrays.asList(1024, 1280, 1536, 1792),
      IvfRegionLayout.futureRangeSplitIds(1024, 1024, 4));
    assertEquals(Arrays.asList(8), IvfRegionLayout.futureRangeSplitIds(8, 8, 1));
  }

  @Test
  public void testMissingSplitIds() {
    // The DDL points already give an even layout.
    assertTrue(IvfRegionLayout.missingSplitIds(Arrays.asList(4), 0, 8, 2).isEmpty());
    assertTrue(IvfRegionLayout.missingSplitIds(Arrays.asList(2, 4, 6), 0, 8, 4).isEmpty());
    // K-means returns more lists than requested, so the last region gets too many lists.
    assertEquals(Arrays.asList(9),
      IvfRegionLayout.missingSplitIds(Arrays.asList(2, 4, 6), 0, 12, 4));
    // No boundaries gives even points over the full range.
    assertEquals(Arrays.asList(2, 4, 6),
      IvfRegionLayout.missingSplitIds(Collections.emptyList(), 0, 8, 4));
    assertEquals(Arrays.asList(3, 5, 8),
      IvfRegionLayout.missingSplitIds(Collections.emptyList(), 0, 10, 4));
    // Boundaries outside a shifted range do not count; a boundary at the range start does not
    // divide the range.
    assertEquals(Arrays.asList(12), IvfRegionLayout.missingSplitIds(Arrays.asList(4, 8), 8, 8, 2));
    assertTrue(IvfRegionLayout.missingSplitIds(Arrays.asList(4, 8, 12), 8, 8, 2).isEmpty());
    assertTrue(IvfRegionLayout.missingSplitIds(Collections.emptyList(), 0, 8, 1).isEmpty());
    assertTrue(IvfRegionLayout.missingSplitIds(Collections.emptyList(), 0, 1, 4).isEmpty());
  }

  @Test
  public void testMissingSplitIdsLeaveAFewExtraListsInTheLastRegion() {
    // The defaults request 1024 lists in 16 regions, so the DDL splits at steps of 64.
    List<Integer> ddl = IvfRegionLayout.evenSplitIds(0, 1024, 16);
    assertEquals(15, ddl.size());
    // Training returns 1034 lists. The last region holds 74 lists, and one region stays.
    assertTrue(IvfRegionLayout.missingSplitIds(ddl, 0, 1034, 16).isEmpty());
    // Training returns 1100 lists. The last region holds 140 lists, about two even shares.
    assertEquals(Arrays.asList(1030), IvfRegionLayout.missingSplitIds(ddl, 0, 1100, 16));
  }

  @Test
  public void testExpectedListsFollowTheOutgoingGeneration() {
    assertEquals(1024, IvfRegionLayout.expectedLists(1024, 1000));
    assertEquals(1100, IvfRegionLayout.expectedLists(1024, 1100));
    // With the expected count, the splits before the rebuild fit the new lists, so the region
    // count stays the same across rebuilds.
    int target = IvfRegionLayout.rebuildTarget(16, 17);
    int lists = IvfRegionLayout.expectedLists(1024, 1100);
    List<Integer> boundaries = IvfRegionLayout.futureRangeSplitIds(1100, lists, target);
    assertEquals(target, boundaries.size());
    assertTrue(IvfRegionLayout.topUpSplitIds(boundaries, 1100, 1100, target, true).isEmpty());
  }

  @Test
  public void testSplitRowsSortByCentroidId() {
    for (int id : new int[] { Integer.MIN_VALUE, -1, 0, 1, 4, 1024, Integer.MAX_VALUE }) {
      byte[] row = IvfRegionLayout.splitRow(id);
      assertEquals(Bytes.SIZEOF_INT, row.length);
      assertEquals(Integer.valueOf(id), IvfRegionLayout.leadingId(row));
      assertEquals(Integer.valueOf(id), IvfRegionLayout.leadingId(Bytes.add(row, new byte[3])));
    }
    assertTrue(Bytes.compareTo(IvfRegionLayout.splitRow(-1), IvfRegionLayout.splitRow(0)) < 0);
    assertTrue(Bytes.compareTo(IvfRegionLayout.splitRow(3), IvfRegionLayout.splitRow(4)) < 0);
    assertTrue(Bytes.compareTo(IvfRegionLayout.splitRow(255), IvfRegionLayout.splitRow(256)) < 0);
    assertNull(IvfRegionLayout.leadingId(HConstants.EMPTY_BYTE_ARRAY));
    assertNull(IvfRegionLayout.leadingId(new byte[3]));
    assertNull(IvfRegionLayout.leadingId(null));
  }

  @Test
  public void testListsInRegion() {
    byte[] open = HConstants.EMPTY_BYTE_ARRAY;
    byte[] four = IvfRegionLayout.splitRow(4);
    assertEquals(8, IvfRegionLayout.listsInRegion(open, open, 0, 8));
    assertEquals(4, IvfRegionLayout.listsInRegion(open, four, 0, 8));
    assertEquals(4, IvfRegionLayout.listsInRegion(four, open, 0, 8));
    assertEquals(2, IvfRegionLayout.listsInRegion(IvfRegionLayout.splitRow(2), four, 0, 8));
    // A region of a future range holds no lists of the current range.
    assertEquals(0, IvfRegionLayout.listsInRegion(IvfRegionLayout.splitRow(8), open, 0, 8));
    // A midkey boundary inside list 3 counts list 3 in both regions.
    byte[] midkey = Bytes.add(IvfRegionLayout.splitRow(3), new byte[] { 5 });
    assertEquals(4, IvfRegionLayout.listsInRegion(open, midkey, 0, 8));
    assertEquals(5, IvfRegionLayout.listsInRegion(midkey, open, 0, 8));
    // A SPLIT ON key with zero bytes after the ID is the same boundary as the bare ID.
    byte[] padded = Bytes.add(four, new byte[2]);
    assertEquals(4, IvfRegionLayout.listsInRegion(open, padded, 0, 8));
    assertEquals(4, IvfRegionLayout.listsInRegion(padded, open, 0, 8));
  }

  @Test
  public void testBoundaryIdAcceptsZeroPaddedSplitKeys() {
    byte[] four = IvfRegionLayout.splitRow(4);
    assertEquals(Integer.valueOf(4), IvfRegionLayout.boundaryId(four));
    assertEquals(Integer.valueOf(4), IvfRegionLayout.boundaryId(Bytes.add(four, new byte[2])));
    // A midkey inside a list is not a list boundary.
    assertNull(IvfRegionLayout.boundaryId(Bytes.add(four, new byte[] { 0, 5 })));
    assertNull(IvfRegionLayout.boundaryId(new byte[3]));
    assertNull(IvfRegionLayout.boundaryId(HConstants.EMPTY_BYTE_ARRAY));
  }

  @Test
  public void testSizeCv() {
    assertNull(IvfRegionLayout.sizeCv(Collections.emptyList()));
    assertEquals(0.0, IvfRegionLayout.sizeCv(Arrays.asList(0L, 0L)), 0.0);
    assertEquals(0.0, IvfRegionLayout.sizeCv(Arrays.asList(10L, 10L, 10L)), 0.0);
    assertEquals(1.0, IvfRegionLayout.sizeCv(Arrays.asList(0L, 20L)), 1e-9);
    assertEquals(0.5, IvfRegionLayout.sizeCv(Arrays.asList(10L, 30L)), 1e-9);
  }

  @Test
  public void testEvenlySpread() {
    assertTrue(IvfRegionLayout.evenlySpread(Arrays.asList(4), 1));
    assertTrue(IvfRegionLayout.evenlySpread(Arrays.asList(2, 2), 2));
    assertTrue(IvfRegionLayout.evenlySpread(Arrays.asList(1, 1, 1), 3));
    assertTrue(IvfRegionLayout.evenlySpread(Arrays.asList(2, 1, 1), 3));
    // At two or more regions per server one region more or less is acceptable.
    assertTrue(IvfRegionLayout.evenlySpread(Arrays.asList(3, 1), 2));
    assertTrue(IvfRegionLayout.evenlySpread(Arrays.asList(6, 5, 4), 3));
    assertFalse(IvfRegionLayout.evenlySpread(Arrays.asList(7, 5, 3), 3));
    // Below two regions per server the spread must be exact.
    assertFalse(IvfRegionLayout.evenlySpread(Arrays.asList(2, 1), 3));
    // A server without regions of the table is not in the map.
    assertFalse(IvfRegionLayout.evenlySpread(Arrays.asList(4), 2));
    assertTrue(IvfRegionLayout.evenlySpread(Arrays.asList(1), 2));
  }

  @Test
  public void testPlanSplitsAnUnsaltedSingleTenantIndex() {
    IvfRegionLayout.Plan p = plan("ivf.regions", "2");
    assertEquals(IvfRegionLayout.Mode.SPLIT_ON, p.mode());
    assertEquals(2, p.regions());
    assertEquals(4, p.step());
    assertEquals(Arrays.asList(4), p.splitIds());
    assertNull(p.saltBuckets());
    assertTrue(p.centroidKeys());
    assertTrue(p.notes().toString(), p.notes().isEmpty());
    Map<String, Object> m = p.toMap();
    assertEquals("SPLIT_ON", m.get("mode"));
    assertEquals(2, m.get("target_regions"));
    assertEquals(Arrays.asList(4), m.get("split_on"));
    // The data table regions give the default target.
    assertEquals(Arrays.asList(2, 4, 6), plan("table.regions", "4").splitIds());
    // An explicit zero salt overrides the salt of the data table.
    p = plan("table.salt_buckets", "4", "ivf.salt_buckets", "0", "ivf.regions", "2");
    assertEquals(IvfRegionLayout.Mode.SPLIT_ON, p.mode());
    assertEquals(Arrays.asList(4), p.splitIds());
  }

  @Test
  public void testPlanNotesTooFewLists() {
    IvfRegionLayout.Plan p = plan("ivf.lists", "3", "ivf.regions", "8");
    assertEquals(IvfRegionLayout.Mode.SPLIT_ON, p.mode());
    assertEquals(Arrays.asList(1, 2), p.splitIds());
    assertEquals(p.notes().toString(), 1, p.notes().size());
    assertTrue(p.notes().get(0), p.notes().get(0).contains("gives 3 regions"));
  }

  @Test
  public void testPlanOfOneRegion() {
    IvfRegionLayout.Plan p = plan("ivf.regions", "1");
    assertEquals(IvfRegionLayout.Mode.NONE, p.mode());
    assertEquals(1, p.regions());
    assertTrue(p.splitIds().isEmpty());
    assertTrue(p.centroidKeys());
    assertTrue(p.notes().isEmpty());
  }

  @Test
  public void testPlanSplitsAMultiTenantIndexAtMidkeys() {
    IvfRegionLayout.Plan p = plan("table.multi_tenant", "true", "ivf.regions", "4");
    assertEquals(IvfRegionLayout.Mode.MIDKEY, p.mode());
    assertEquals(4, p.regions());
    assertTrue(p.splitIds().isEmpty());
    assertFalse(p.centroidKeys());
    assertEquals(1, p.notes().size());
    assertEquals(IvfRegionLayout.Mode.NONE,
      plan("table.multi_tenant", "true", "ivf.regions", "1").mode());
  }

  @Test
  public void testPlanOfASaltedIndex() {
    IvfRegionLayout.Plan p = plan("table.salt_buckets", "4");
    assertEquals(IvfRegionLayout.Mode.SALTED, p.mode());
    assertEquals(4, p.regions());
    assertEquals(Integer.valueOf(4), p.saltBuckets());
    assertTrue(p.inheritedSalt());
    assertFalse(p.centroidKeys());
    assertEquals(p.notes().toString(), 1, p.notes().size());
    p = plan("table.salt_buckets", "4", "ivf.regions", "8");
    assertEquals(p.notes().toString(), 2, p.notes().size());
    p = plan("ivf.salt_buckets", "3");
    assertEquals(IvfRegionLayout.Mode.SALTED, p.mode());
    assertEquals(3, p.regions());
    assertFalse(p.inheritedSalt());
    assertTrue(p.notes().isEmpty());
    assertEquals(Boolean.FALSE, p.toMap().get("inherited_salt"));
    assertFalse(p.toMap().containsKey("split_on"));
  }

  @Test
  public void testTopUpSplitIdsAfterAPromotion() {
    // The pre-split before the rebuild gave the range [8, 16) two regions.
    assertTrue(IvfRegionLayout.topUpSplitIds(Arrays.asList(4, 8, 12), 8, 8, 2, true).isEmpty());
    // Training returned 12 lists. The last region holds 8 lists, below one and a half even shares.
    assertTrue(IvfRegionLayout.topUpSplitIds(Arrays.asList(4, 8, 12), 8, 12, 2, true).isEmpty());
    // Training returned 16 lists. The last region holds 12 lists, one and a half even shares.
    assertEquals(Arrays.asList(18),
      IvfRegionLayout.topUpSplitIds(Arrays.asList(4, 8, 12), 8, 16, 2, true));
    // Without the pre-split, the range start separates the new lists from the earlier ones.
    assertEquals(Arrays.asList(8, 12),
      IvfRegionLayout.topUpSplitIds(Arrays.asList(4), 8, 8, 2, true));
    // The first generation starts in the first region, so it needs no cut at its start.
    assertEquals(Arrays.asList(4), IvfRegionLayout.topUpSplitIds(Collections.<Integer> emptyList(),
      IvfRegionLayout.FIRST_GENERATION_FIRST_ID, 8, 2, false));
    assertTrue(IvfRegionLayout.topUpSplitIds(Arrays.asList(4), 8, 8, 1, true).isEmpty());
    assertTrue(IvfRegionLayout.topUpSplitIds(Arrays.asList(4), 8, 0, 2, true).isEmpty());
  }

  @Test
  public void testRebuildTargetKeepsTheOutgoingRegionCount() {
    assertEquals(2, IvfRegionLayout.rebuildTarget(2, 1));
    assertEquals(3, IvfRegionLayout.rebuildTarget(2, 3));
    assertEquals(1, IvfRegionLayout.rebuildTarget(0, 0));
  }

  @Test
  public void testNextFirstIdFollowsTheOutgoingRange() {
    assertEquals(Integer.valueOf(8), IvfRegionLayout.nextFirstId(0, 8));
    assertEquals(Integer.valueOf(20), IvfRegionLayout.nextFirstId(8, 12));
    assertEquals(Integer.valueOf(Integer.MAX_VALUE),
      IvfRegionLayout.nextFirstId(Integer.MAX_VALUE - 8, 8));
    assertNull(IvfRegionLayout.nextFirstId(Integer.MAX_VALUE - 8, 9));
  }

  @Test
  public void testPromotedNeedsTwoKnownGenerations() {
    assertTrue(IvfRegionLayout.promoted(1L, 2L));
    assertFalse(IvfRegionLayout.promoted(2L, 2L));
    assertFalse(IvfRegionLayout.promoted(null, 2L));
    assertFalse(IvfRegionLayout.promoted(1L, null));
  }

  @Test
  public void testRebuildSplitsGiveEachRegionAnEvenShare() {
    // The outgoing generation has 8 lists from 0 in two regions. The new range starts at 8.
    Integer first = IvfRegionLayout.nextFirstId(0, 8);
    int target = IvfRegionLayout.rebuildTarget(2, 2);
    List<Integer> ids = IvfRegionLayout.futureRangeSplitIds(first, 8, target);
    assertEquals(Arrays.asList(8, 12), ids);
    List<Integer> boundaries = new ArrayList<>(Arrays.asList(4));
    boundaries.addAll(ids);
    byte[] start = IvfRegionLayout.splitRow(8);
    byte[] mid = IvfRegionLayout.splitRow(12);
    assertEquals(4, IvfRegionLayout.listsInRegion(start, mid, 8, 8));
    assertEquals(4, IvfRegionLayout.listsInRegion(mid, HConstants.EMPTY_END_ROW, 8, 8));
    assertEquals(0,
      IvfRegionLayout.listsInRegion(HConstants.EMPTY_START_ROW, IvfRegionLayout.splitRow(4), 8, 8));
    assertTrue(IvfRegionLayout.topUpSplitIds(boundaries, 8, 8, target, true).isEmpty());
  }
}
