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

import java.io.IOException;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.regex.Pattern;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FileStatus;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.hbase.ClusterMetrics;
import org.apache.hadoop.hbase.HRegionLocation;
import org.apache.hadoop.hbase.RegionMetrics;
import org.apache.hadoop.hbase.ServerName;
import org.apache.hadoop.hbase.Size;
import org.apache.hadoop.hbase.TableName;
import org.apache.hadoop.hbase.client.Admin;
import org.apache.hadoop.hbase.client.CompactionState;
import org.apache.hadoop.hbase.client.RegionInfo;
import org.apache.hadoop.hbase.client.RegionLocator;
import org.apache.hadoop.hbase.util.Bytes;
import org.apache.hadoop.hbase.util.CommonFSUtils;
import org.apache.phoenix.schema.SortOrder;
import org.apache.phoenix.schema.types.PInteger;
import org.apache.phoenix.vectorharness.HarnessContext;
import org.apache.phoenix.vectorharness.config.HarnessConfig;
import org.apache.phoenix.vectorharness.config.Setting;
import org.apache.phoenix.vectorharness.observe.StorageFootprint;
import org.apache.phoenix.vectorharness.result.EventLog;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Region layout of an IVF index table. A production index starts in one region and splits as it
 * grows, early in its life. The harness gives the index the region count of that steady state, so
 * the query plan and the cost per query match a naturally split index.
 * <p>
 * The static methods plan the layout and compute split points. They do not touch the cluster. An
 * instance applies splits to one index table through the HBase Admin API, spreads the regions
 * across the region servers, and measures the result. Each step records an {@link #EVENT} event
 * with the phase of the run.
 * <p>
 * The row key of an unsalted, single-tenant IVF index starts with the centroid ID. The ID is a
 * 4-byte INTEGER with the sign bit flipped. Thus a split at the encoded ID puts whole posting lists
 * on each side. A salt byte or a tenant ID before the centroid ID prevents splits at centroid IDs.
 * For such an index the harness splits the largest region at its midkey, as natural growth does.
 */
public final class IvfRegionLayout implements AutoCloseable {

  private static final Logger LOG = LoggerFactory.getLogger(IvfRegionLayout.class);

  /**
   * The first generation numbers its centroids from 0. Each later generation starts after the IDs
   * of the generation that it replaces.
   */
  public static final int FIRST_GENERATION_FIRST_ID = 0;

  /** Event kind of each split, compaction, and balance step. */
  public static final String EVENT = "index-presplit";

  /** Phase of the splits after the first build. */
  public static final String PHASE_FIRST_BUILD = "first-build";

  /** Phase of the splits of the empty ID range before an explicit rebuild. */
  public static final String PHASE_BEFORE_REBUILD = "before-rebuild";

  /** Phase of the splits after an explicit rebuild promotes its generation. */
  public static final String PHASE_AFTER_PROMOTION = "after-promotion";

  /** Phase of the splits after an automatic rebuild promotes its generation. */
  public static final String PHASE_AUTO_PROMOTION = "auto-promotion";

  /** Longest wait for one split, including the compactions that make the region splittable. */
  public static final long DEFAULT_SPLIT_TIMEOUT_MS = TimeUnit.MINUTES.toMillis(5);

  /** Longest wait for the compactions after one round of splits. */
  public static final long DEFAULT_COMPACTION_TIMEOUT_MS = TimeUnit.MINUTES.toMillis(10);

  /** Longest wait for the region moves that spread a generation across the region servers. */
  public static final long DEFAULT_BALANCE_TIMEOUT_MS = TimeUnit.MINUTES.toMillis(5);

  /** Longest run of the midkey split routine. */
  public static final long DEFAULT_MIDKEY_TIMEOUT_MS = TimeUnit.MINUTES.toMillis(60);

  static final long POLL_MS = 1000;

  /**
   * File name of a reference file: the name of the parent store file, a dot, and the encoded name
   * of the parent region. A region with reference files cannot split until a compaction rewrites
   * them and the store discards the compacted files.
   */
  static final Pattern REFERENCE = Pattern.compile("^([0-9a-f]+(?:_SeqId_[0-9]+_)?)\\.(.+)$");

  /** Method that gives the regions of an IVF index. */
  public enum Mode {
    /** The index stays in one region. */
    NONE,
    /** The salt buckets give the regions. The harness does not split the index. */
    SALTED,
    /** The CREATE statement has SPLIT ON at centroid IDs of the first generation. */
    SPLIT_ON,
    /**
     * After the build, the harness splits the largest region at its midkey until the target count.
     */
    MIDKEY
  }

  /** Region layout of the first generation that the profile asks for. */
  public static final class Plan {
    private final Mode mode;
    private final int regions;
    private final int step;
    private final Integer saltBuckets;
    private final boolean inheritedSalt;
    private final List<Integer> splitIds;
    private final List<String> notes;

    Plan(Mode mode, int regions, int step, Integer saltBuckets, boolean inheritedSalt,
      List<Integer> splitIds, List<String> notes) {
      this.mode = mode;
      this.regions = regions;
      this.step = step;
      this.saltBuckets = saltBuckets;
      this.inheritedSalt = inheritedSalt;
      this.splitIds = Collections.unmodifiableList(new ArrayList<>(splitIds));
      this.notes = Collections.unmodifiableList(new ArrayList<>(notes));
    }

    public Mode mode() {
      return mode;
    }

    /** Returns the target region count. */
    public int regions() {
      return regions;
    }

    /** Returns the lists per region of the SPLIT ON points, or 0 for other modes. */
    public int step() {
      return step;
    }

    /** Returns the salt bucket count of the index, or null for an unsalted index. */
    public Integer saltBuckets() {
      return saltBuckets;
    }

    /** Returns true if the index takes its salt from the data table. */
    public boolean inheritedSalt() {
      return inheritedSalt;
    }

    /** Returns the SPLIT ON centroid IDs of the CREATE statement. */
    public List<Integer> splitIds() {
      return splitIds;
    }

    /** Returns the remarks about the layout for the build record and the warnings. */
    public List<String> notes() {
      return notes;
    }

    /** Returns true if the row key starts with the centroid ID. */
    public boolean centroidKeys() {
      return mode == Mode.NONE || mode == Mode.SPLIT_ON;
    }

    public Map<String, Object> toMap() {
      Map<String, Object> m = new LinkedHashMap<>();
      m.put("mode", mode.name());
      m.put("target_regions", regions);
      if (mode == Mode.SPLIT_ON) {
        m.put("step", step);
        m.put("split_on", splitIds);
      }
      m.put("salt_buckets", saltBuckets);
      m.put("inherited_salt", inheritedSalt);
      m.put("notes", notes);
      return m;
    }
  }

  // Planning and split point arithmetic

  /** Returns the region layout of the first generation for the profile. */
  public static Plan plan(HarnessConfig c) {
    int regions = c.ivfRegions();
    Integer explicitSalt = c.ivfSaltBuckets();
    boolean inherited = explicitSalt == null && c.saltBuckets() > 0;
    Integer salt = explicitSalt != null
      ? (explicitSalt > 0 ? explicitSalt : null)
      : (inherited ? Integer.valueOf(c.saltBuckets()) : null);
    List<String> notes = new ArrayList<>();
    if (salt != null) {
      if (inherited) {
        notes.add("the IVF index inherits SALT_BUCKETS=" + salt + " of the data table, so the"
          + " salt buckets give its regions; set " + Setting.IVF_SALT_BUCKETS.key()
          + "=0 to split it by centroid ID");
      }
      if (inherited && c.isSet(Setting.IVF_REGIONS) && regions > 1) {
        notes.add(Setting.IVF_REGIONS.key() + "=" + regions + " has no effect on a salted index");
      }
      return new Plan(Mode.SALTED, salt, 0, salt, inherited, Collections.emptyList(), notes);
    }
    if (regions <= 1) {
      return new Plan(Mode.NONE, 1, 0, null, false, Collections.emptyList(), notes);
    }
    if (c.multiTenant()) {
      notes.add("a tenant ID leads the row key of the multi-tenant IVF index, so the harness"
        + " splits the largest region at its midkey after the build");
      return new Plan(Mode.MIDKEY, regions, 0, null, false, Collections.emptyList(), notes);
    }
    int lists = c.ivfLists();
    int step = step(lists, regions);
    List<Integer> ids = evenSplitIds(FIRST_GENERATION_FIRST_ID, lists, regions);
    if (ids.size() + 1 < regions) {
      notes.add(Setting.IVF_LISTS.key() + "=" + lists + " in steps of " + step + " lists gives "
        + (ids.size() + 1) + " regions, fewer than " + Setting.IVF_REGIONS.key() + "=" + regions);
    }
    return new Plan(Mode.SPLIT_ON, regions, step, null, false, ids, notes);
  }

  /** Returns the lists per region for an even layout: the ceiling of lists over regions. */
  public static int step(int lists, int regions) {
    if (lists <= 0 || regions <= 0) {
      return 1;
    }
    return (int) Math.max(1L, ((long) lists + regions - 1) / regions);
  }

  /**
   * Returns the even split points over the IDs from {@code firstId} for {@code lists} lists. The
   * points are {@code firstId + i * step} below the end of the range. The range gets at most
   * {@code regions} regions. The range start is not a point.
   */
  public static List<Integer> evenSplitIds(int firstId, int lists, int regions) {
    List<Integer> out = new ArrayList<>();
    int step = step(lists, regions);
    long end = (long) firstId + lists;
    for (int i = 1; i < regions; i++) {
      long id = (long) firstId + (long) i * step;
      if (id >= end || id > Integer.MAX_VALUE) {
        break;
      }
      out.add((int) id);
    }
    return out;
  }

  /**
   * Returns the split points for the ID range of a future generation. The range is still empty, so
   * these splits move no data. The first point is the range start. It separates the new range from
   * the outgoing generation. The even points of {@link #evenSplitIds} follow.
   */
  public static List<Integer> futureRangeSplitIds(int firstId, int lists, int regions) {
    List<Integer> out = new ArrayList<>();
    out.add(firstId);
    out.addAll(evenSplitIds(firstId, lists, regions));
    return out;
  }

  /**
   * Returns the split points that the populated ID range needs to reach an even layout. The range
   * holds {@code count} lists from {@code firstId}, and its even share is count over regions lists.
   * A region gets the rounded count of even shares that it holds, so a region below one and a half
   * shares stays whole. Thus a few extra lists from training do not add a region. The existing
   * boundaries stay. Boundaries outside the range do not count.
   */
  public static List<Integer> missingSplitIds(Collection<Integer> boundaries, int firstId,
    int count, int regions) {
    List<Integer> out = new ArrayList<>();
    if (count <= 1 || regions <= 1) {
      return out;
    }
    long end = (long) firstId + count;
    TreeSet<Long> cuts = new TreeSet<>();
    cuts.add((long) firstId);
    for (Integer b : boundaries) {
      if (b != null && b > firstId && b < end) {
        cuts.add((long) b);
      }
    }
    cuts.add(end);
    Long previous = null;
    for (long cut : cuts) {
      if (previous != null) {
        long len = cut - previous;
        long pieces = Math.round((double) len * regions / count);
        for (long j = 1; j < pieces; j++) {
          out.add((int) (previous + Math.round((double) j * len / pieces)));
        }
      }
      previous = cut;
    }
    return out;
  }

  /**
   * Returns the split points that the populated ID range of a promoted generation needs. The range
   * holds {@code count} lists from {@code firstId}. If {@code cutStart} is true and the range start
   * is not a boundary, the start is the first point. This point separates the new posting lists
   * from the lists of earlier generations. The points of {@link #missingSplitIds} follow.
   */
  public static List<Integer> topUpSplitIds(Collection<Integer> boundaries, int firstId, int count,
    int regions, boolean cutStart) {
    List<Integer> out = new ArrayList<>();
    if (count <= 0 || regions <= 1) {
      return out;
    }
    if (cutStart && !boundaries.contains(firstId)) {
      out.add(firstId);
    }
    out.addAll(missingSplitIds(boundaries, firstId, count, regions));
    return out;
  }

  /**
   * Returns the region target for a rebuilt generation. The new generation gets at least the
   * profile target, and at least as many regions as the outgoing generation fills.
   */
  public static int rebuildTarget(int regions, int outgoingRegions) {
    return Math.max(Math.max(1, regions), outgoingRegions);
  }

  /**
   * Returns the list count that the splits before a rebuild assume. Training can return more lists
   * than requested, and a rebuild of similar data usually returns a similar count. Thus the larger
   * of the requested and the outgoing count gives the best estimate. A low estimate leaves a large
   * last region, and the top-up after the promotion then adds a region at each rebuild.
   */
  public static int expectedLists(int requestedLists, int outgoingLists) {
    return Math.max(requestedLists, outgoingLists);
  }

  /**
   * Returns the first centroid ID of the generation after the one with {@code count} lists from
   * {@code firstId}, or null if the ID is out of the INTEGER range. The product uses the same rule.
   */
  public static Integer nextFirstId(int firstId, int count) {
    long next = (long) firstId + count;
    return next > Integer.MAX_VALUE ? null : Integer.valueOf((int) next);
  }

  /**
   * Returns true if a new generation is active since the last layout. An unknown generation on
   * either side is not a promotion.
   */
  public static boolean promoted(Long laidOut, Long active) {
    return laidOut != null && active != null && !active.equals(laidOut);
  }

  /** Returns the region boundary row for a centroid ID. */
  public static byte[] splitRow(int centroidId) {
    return PInteger.INSTANCE.toBytes(centroidId);
  }

  /** Returns the centroid ID at the start of a row key, or null if the key is too short. */
  public static Integer leadingId(byte[] key) {
    if (key == null || key.length < Bytes.SIZEOF_INT) {
      return null;
    }
    return PInteger.INSTANCE.getCodec().decodeInt(key, 0, SortOrder.getDefault());
  }

  /**
   * Returns the centroid ID if the key marks the start of a posting list, or null. Phoenix fills a
   * SPLIT ON key with zero bytes for the primary key columns after the centroid ID. Thus the ID
   * alone, or the ID with only zero bytes after it, marks the start of the list.
   */
  public static Integer boundaryId(byte[] key) {
    if (key == null || key.length < Bytes.SIZEOF_INT) {
      return null;
    }
    for (int i = Bytes.SIZEOF_INT; i < key.length; i++) {
      if (key[i] != 0) {
        return null;
      }
    }
    return leadingId(key);
  }

  /** Returns the split row of the list if the key marks the start of a list, else the key. */
  private static byte[] listStart(byte[] key) {
    Integer id = boundaryId(key);
    return id == null ? key : splitRow(id);
  }

  /**
   * Returns the count of posting lists that have rows in the region from {@code start} to
   * {@code end}. The lists are the {@code count} IDs from {@code firstId}. An empty key is open. A
   * list that a midkey boundary cuts counts in both regions.
   */
  public static int listsInRegion(byte[] start, byte[] end, int firstId, int count) {
    start = listStart(start);
    end = listStart(end);
    int n = 0;
    for (long id = firstId; id < (long) firstId + count && id <= Integer.MAX_VALUE; id++) {
      boolean beforeEnd = end.length == 0 || Bytes.compareTo(splitRow((int) id), end) < 0;
      boolean afterStart = start.length == 0 || id == Integer.MAX_VALUE
        || Bytes.compareTo(splitRow((int) id + 1), start) > 0;
      if (beforeEnd && afterStart) {
        n++;
      }
    }
    return n;
  }

  /** Returns the coefficient of variation of the sizes, or null if there are none. */
  public static Double sizeCv(List<Long> sizes) {
    if (sizes.isEmpty()) {
      return null;
    }
    double mean = 0;
    for (long s : sizes) {
      mean += s;
    }
    mean /= sizes.size();
    if (mean == 0) {
      return 0.0;
    }
    double var = 0;
    for (long s : sizes) {
      var += (s - mean) * (s - mean);
    }
    return Math.sqrt(var / sizes.size()) / mean;
  }

  /**
   * Returns true if the regions per server are near the even share. Each server must hold between
   * the floor and the ceiling of the even share. With at least two regions per server, one region
   * more or less is acceptable. Such a small difference does not justify more region moves.
   */
  public static boolean evenlySpread(Collection<Integer> perServer, int servers) {
    if (servers <= 1) {
      return true;
    }
    int regions = 0;
    int max = 0;
    for (int n : perServer) {
      regions += n;
      max = Math.max(max, n);
    }
    // A server without a region can be absent from the map.
    int min = perServer.size() < servers ? 0 : Collections.min(perServer);
    int slack = regions >= 2 * servers ? 1 : 0;
    int floor = regions / servers;
    int ceil = (regions + servers - 1) / servers;
    return max <= ceil + slack && min >= floor - slack;
  }

  // Cluster operations

  private final TableName table;
  private final String phase;
  private final EventLog eventLog;
  private final Admin admin;
  private final Configuration conf;
  private final List<Map<String, Object>> events = new ArrayList<>();
  private long splitTimeoutMs = DEFAULT_SPLIT_TIMEOUT_MS;
  private long compactionTimeoutMs = DEFAULT_COMPACTION_TIMEOUT_MS;
  private long balanceTimeoutMs = DEFAULT_BALANCE_TIMEOUT_MS;
  private long midkeyTimeoutMs = DEFAULT_MIDKEY_TIMEOUT_MS;
  private long stopAtMillis = Long.MAX_VALUE;

  private IvfRegionLayout(Connection conn, TableName table, String phase, EventLog eventLog)
    throws SQLException {
    this.table = table;
    this.phase = phase;
    this.eventLog = eventLog;
    this.admin = HarnessContext.admin(conn);
    this.conf = HarnessContext.configuration(conn);
  }

  /**
   * Opens the layout of an IVF index table.
   * @param phase    the part of the run that changes the layout, such as first-build
   * @param eventLog the event log of the run, or null to record the events only here
   */
  public static IvfRegionLayout open(Connection conn, String fullIndexName, String phase,
    EventLog eventLog) throws SQLException {
    return new IvfRegionLayout(conn, StorageFootprint.physicalName(conn, fullIndexName), phase,
      eventLog);
  }

  /** Sets the timeouts in milliseconds. A value of 0 or less keeps the current value. */
  public IvfRegionLayout timeouts(long splitMs, long compactionMs, long balanceMs, long midkeyMs) {
    splitTimeoutMs = splitMs > 0 ? splitMs : splitTimeoutMs;
    compactionTimeoutMs = compactionMs > 0 ? compactionMs : compactionTimeoutMs;
    balanceTimeoutMs = balanceMs > 0 ? balanceMs : balanceTimeoutMs;
    midkeyTimeoutMs = midkeyMs > 0 ? midkeyMs : midkeyTimeoutMs;
    return this;
  }

  /**
   * Sets a wall time limit for all steps. A layout under load stops at the end of its window, and
   * the next window continues it.
   */
  public IvfRegionLayout stopAt(long wallMillis) {
    stopAtMillis = wallMillis;
    return this;
  }

  /** Returns true if the wall time limit of {@link #stopAt} has passed. */
  public boolean stopped() {
    return System.currentTimeMillis() >= stopAtMillis;
  }

  public TableName table() {
    return table;
  }

  /** Returns the events that this layout recorded. */
  public List<Map<String, Object>> events() {
    return new ArrayList<>(events);
  }

  /** Returns the online regions of the table in key order. */
  public List<RegionInfo> regions() throws IOException {
    List<RegionInfo> out = new ArrayList<>();
    for (RegionInfo r : admin.getRegions(table)) {
      if (!r.isOffline() && !r.isSplitParent()) {
        out.add(r);
      }
    }
    out.sort(Comparator.comparing(RegionInfo::getStartKey, Bytes.BYTES_COMPARATOR));
    return out;
  }

  /** Returns the centroid IDs of the region start keys that mark the start of a list. */
  public List<Integer> boundaryIds() throws IOException {
    List<Integer> out = new ArrayList<>();
    for (RegionInfo r : regions()) {
      Integer id = boundaryId(r.getStartKey());
      if (id != null) {
        out.add(id);
      }
    }
    return out;
  }

  /**
   * Splits the table at the centroid IDs in ascending order. An ID that is already a boundary needs
   * no split. Before each split, the target region compacts away its reference files. For an empty
   * ID range, split at the range start first. Then the later splits divide a region without store
   * files, and they move no data.
   * @return the split summary: the IDs split, the IDs skipped, the seconds, and the failures
   */
  public Map<String, Object> splitAt(Collection<Integer> centroidIds)
    throws IOException, InterruptedException {
    long t0 = System.nanoTime();
    List<Integer> split = new ArrayList<>();
    List<Integer> existing = new ArrayList<>();
    List<Integer> failed = new ArrayList<>();
    for (int id : new TreeSet<>(centroidIds)) {
      byte[] row = splitRow(id);
      RegionInfo target = regionFor(row);
      // A SPLIT ON boundary sorts just after the row, so it is the end key of the target.
      if (
        target == null || Bytes.equals(target.getStartKey(), row)
          || Integer.valueOf(id).equals(boundaryId(target.getEndKey()))
      ) {
        existing.add(id);
        continue;
      }
      if (splitRegion(target, row, "centroid " + id, deadline(splitTimeoutMs))) {
        split.add(id);
      } else {
        failed.add(id);
      }
    }
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("requested", new ArrayList<>(new TreeSet<>(centroidIds)));
    m.put("split", split);
    m.put("existing", existing);
    m.put("failed", failed);
    m.put("seconds", (System.nanoTime() - t0) / 1e9);
    return m;
  }

  /**
   * Splits regions at their midkeys until the table has the target count of populated regions. Each
   * round splits the largest regions first and stops at the target. HBase picks each midkey from
   * the largest store file, as in a split by size. Between rounds, the daughters compact away their
   * reference files, so they can split again. The routine stops at the timeout or when no region
   * can split.
   * @return the routine summary: rounds, splits, populated regions, seconds, and the outcome
   */
  public Map<String, Object> splitLargest(int targetRegions)
    throws IOException, InterruptedException {
    return splitLargest(targetRegions, null, null);
  }

  /**
   * Splits regions at their midkeys until the target count of regions holds the lists of one
   * generation. Only the regions with IDs of the {@code count} lists from {@code firstId} count and
   * split. A midkey can fall below the range, so a round can leave the count unchanged. The next
   * round then splits the remaining region again. A null range means the whole table.
   */
  public Map<String, Object> splitLargest(int targetRegions, Integer firstId, Integer count)
    throws IOException, InterruptedException {
    long t0 = System.nanoTime();
    long deadline = deadline(midkeyTimeoutMs);
    int rounds = 0;
    int splits = 0;
    boolean compactedForCandidates = false;
    String outcome = "done";
    while (true) {
      admin.flush(table);
      List<RegionInfo> regions = regions();
      Map<String, Long> sizes = sizes(regions);
      int populated = populated(regions, sizes, firstId, count);
      if (populated >= targetRegions) {
        break;
      }
      if (System.currentTimeMillis() >= deadline) {
        outcome = stopped() ? "window ended" : "timed out";
        record("midkey-timeout", populated + " populated regions of " + targetRegions);
        break;
      }
      Set<String> withReferences = referenceRegions(regions);
      List<RegionInfo> candidates = new ArrayList<>();
      for (RegionInfo r : regions) {
        Long size = sizes.get(r.getEncodedName());
        if (
          size != null && size > 0 && inRange(r, firstId, count)
            && (withReferences == null || !withReferences.contains(r.getEncodedName()))
        ) {
          candidates.add(r);
        }
      }
      candidates
        .sort((a, b) -> Long.compare(sizes.get(b.getEncodedName()), sizes.get(a.getEncodedName())));
      if (candidates.isEmpty()) {
        // Only reference files can block all candidates. Without them, or after one compaction
        // that did not help, no region can split.
        boolean noReferences = withReferences != null && withReferences.isEmpty();
        if (noReferences || compactedForCandidates || !awaitNoReferences(deadline)) {
          outcome = "no splittable region";
          record("midkey-stalled", "no populated region without reference files");
          break;
        }
        compactedForCandidates = true;
        Thread.sleep(POLL_MS);
        continue;
      }
      compactedForCandidates = false;
      int made = 0;
      for (RegionInfo r : candidates) {
        if (populated + made >= targetRegions || System.currentTimeMillis() >= deadline) {
          break;
        }
        String what = "midkey of " + Bytes.toStringBinary(r.getStartKey());
        if (splitRegion(r, null, what, Math.min(deadline, deadline(splitTimeoutMs)))) {
          made++;
        }
      }
      if (made == 0) {
        if (System.currentTimeMillis() >= deadline) {
          continue;
        }
        outcome = "no splittable region";
        record("midkey-stalled", "no region accepted a midkey split");
        break;
      }
      rounds++;
      splits += made;
      awaitNoReferences(Math.min(deadline, deadline(compactionTimeoutMs)));
    }
    List<RegionInfo> regions = regions();
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("target_regions", targetRegions);
    m.put("rounds", rounds);
    m.put("splits", splits);
    m.put("populated_regions", populated(regions, sizes(regions), firstId, count));
    if (firstId != null && count != null) {
      m.put("first_id", firstId);
      m.put("lists", count);
    }
    m.put("outcome", outcome);
    m.put("seconds", (System.nanoTime() - t0) / 1e9);
    return m;
  }

  /**
   * Spreads the regions of one generation across the live region servers. The range is the
   * {@code count} lists from {@code firstId}, or the whole table if the range is null. A split
   * opens both daughters on the server of the parent. The balancer evens out the region count of
   * the whole table, and the regions of other generations count too. Thus the balancer can leave
   * most regions of a new generation on one server. The harness does not rely on it. It moves
   * regions of the range from the busiest server to the idlest server until the counts are even.
   * The wait ends with a warning event at the timeout.
   * @return the wait summary: balanced or not, moves, seconds, and the regions per server
   */
  public Map<String, Object> awaitBalanced(Integer firstId, Integer count)
    throws IOException, InterruptedException {
    long t0 = System.nanoTime();
    long deadline = deadline(balanceTimeoutMs);
    List<ServerName> servers = liveServers();
    boolean enabled = admin.isBalancerEnabled();
    int moves = 0;
    boolean balanced;
    Map<String, Integer> perServer;
    while (true) {
      Map<ServerName, List<RegionInfo>> inRange = new TreeMap<>();
      Map<ServerName, List<RegionInfo>> outside = new TreeMap<>();
      placement(servers, firstId, count, inRange, outside);
      perServer = counts(inRange);
      balanced = evenlySpread(perServer.values(), servers.size());
      if (balanced || System.currentTimeMillis() >= deadline) {
        break;
      }
      int made = moveOne(inRange, outside);
      if (made > 0) {
        moves += made;
        continue;
      }
      Thread.sleep(POLL_MS);
    }
    double secs = (System.nanoTime() - t0) / 1e9;
    String moved = moves == 0 ? "" : ", " + moves + " moves";
    if (balanced) {
      record("balanced", String.format(Locale.ROOT, "%s after %.1f s%s", perServer, secs, moved));
    } else {
      record("balance-timeout",
        String.format(Locale.ROOT, "%s after %.1f s%s", perServer, secs, moved));
      LOG.warn("The regions of {} are not spread across {} servers after {} s: {}", table,
        servers.size(), secs, perServer);
    }
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("balanced", balanced);
    m.put("balancer_enabled", enabled);
    m.put("servers", servers.size());
    m.put("moves", moves);
    m.put("seconds", secs);
    m.put("regions_per_server", perServer);
    m.put("table_regions_per_server", regionsPerServer(null, null));
    return m;
  }

  /**
   * Moves one region of the range from the server with the most regions of the range to the server
   * with the fewest. If the move leaves the idlest server with more regions of the table than the
   * busiest server, a region outside the range moves back. Thus the table stays even, and the
   * balancer has no reason to move the new regions again. The exchange is rare, because an even
   * outgoing generation and an even new range give an even table.
   * @return the count of moves, or 0 if the range is even or the move fails
   */
  private int moveOne(Map<ServerName, List<RegionInfo>> inRange,
    Map<ServerName, List<RegionInfo>> outside) throws IOException {
    ServerName busiest = null;
    ServerName idlest = null;
    for (Map.Entry<ServerName, List<RegionInfo>> e : inRange.entrySet()) {
      int n = e.getValue().size();
      if (busiest == null || n > inRange.get(busiest).size()) {
        busiest = e.getKey();
      }
      if (idlest == null || n < inRange.get(idlest).size()) {
        idlest = e.getKey();
      }
    }
    if (busiest == null || inRange.get(busiest).size() - inRange.get(idlest).size() <= 1) {
      return 0;
    }
    RegionInfo region = inRange.get(busiest).get(0);
    try {
      admin.move(region.getEncodedNameAsBytes(), idlest);
      record("move", region.getEncodedName() + " to " + idlest.getAddress());
      List<RegionInfo> back = outside.getOrDefault(idlest, Collections.emptyList());
      int busiestTotal = inRange.get(busiest).size() - 1
        + outside.getOrDefault(busiest, Collections.emptyList()).size();
      int idlestTotal = inRange.get(idlest).size() + 1 + back.size();
      if (idlestTotal - busiestTotal > 1 && !back.isEmpty()) {
        RegionInfo other = back.get(0);
        admin.move(other.getEncodedNameAsBytes(), busiest);
        record("move", other.getEncodedName() + " to " + busiest.getAddress());
        return 2;
      }
      return 1;
    } catch (IOException e) {
      // A region in transition refuses the move. The next poll tries again.
      LOG.debug("Move of {} to {} failed", region.getEncodedName(), idlest, e);
      return 0;
    }
  }

  /**
   * Sorts the online regions of the table by live server into the regions of the range and the
   * other regions. Each live server appears in the range map, also without regions.
   */
  private void placement(List<ServerName> servers, Integer firstId, Integer count,
    Map<ServerName, List<RegionInfo>> inRange, Map<ServerName, List<RegionInfo>> outside)
    throws IOException {
    for (ServerName s : servers) {
      inRange.put(s, new ArrayList<>());
    }
    try (RegionLocator locator = admin.getConnection().getRegionLocator(table)) {
      for (HRegionLocation loc : locator.getAllRegionLocations()) {
        RegionInfo r = loc.getRegion();
        ServerName s = loc.getServerName();
        if (s == null || r.isSplitParent() || r.isOffline() || !inRange.containsKey(s)) {
          continue;
        }
        (inRange(r, firstId, count) ? inRange : outside).computeIfAbsent(s, k -> new ArrayList<>())
          .add(r);
      }
    }
  }

  private static Map<String, Integer> counts(Map<ServerName, List<RegionInfo>> byServer) {
    Map<String, Integer> out = new TreeMap<>();
    for (Map.Entry<ServerName, List<RegionInfo>> e : byServer.entrySet()) {
      out.merge(e.getKey().getAddress().toString(), e.getValue().size(), Integer::sum);
    }
    return out;
  }

  /**
   * Returns the layout statistics: the region count, the split points, the lists per region, the
   * store bytes per region, and their coefficient of variation. The regions per server count the
   * regions of the populated generation for an index keyed by centroid ID. The table count per
   * server follows.
   * @param centroidKeys true if the row key starts with the centroid ID
   * @param firstId      the first centroid ID of the populated generation, or null if unknown
   * @param count        the list count of the populated generation, or null if unknown
   */
  public Map<String, Object> stats(boolean centroidKeys, Integer firstId, Integer count)
    throws IOException {
    admin.flush(table);
    List<RegionInfo> regions = regions();
    Map<String, Long> sizes = sizes(regions);
    List<Object> points = new ArrayList<>();
    List<Long> bytes = new ArrayList<>();
    List<Integer> lists = new ArrayList<>();
    List<Long> populatedBytes = new ArrayList<>();
    boolean listed = centroidKeys && firstId != null && count != null;
    for (int i = 0; i < regions.size(); i++) {
      RegionInfo r = regions.get(i);
      if (i > 0) {
        byte[] start = r.getStartKey();
        Integer id = centroidKeys ? boundaryId(start) : null;
        points.add(id != null ? id : Bytes.toStringBinary(start));
      }
      Long size = sizes.get(r.getEncodedName());
      bytes.add(size);
      int n = listed ? listsInRegion(r.getStartKey(), r.getEndKey(), firstId, count) : -1;
      lists.add(n);
      if (listed ? n > 0 : size != null && size > 0) {
        populatedBytes.add(size == null ? 0L : size);
      }
    }
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("index_regions", regions.size());
    m.put("populated_regions", populatedBytes.size());
    m.put("split_points", points);
    m.put("lists_per_region", listed ? lists : null);
    m.put("region_bytes", bytes);
    m.put("size_cv", sizeCv(populatedBytes));
    m.put("regions_per_server",
      listed ? regionsPerServer(firstId, count) : regionsPerServer(null, null));
    m.put("table_regions_per_server", regionsPerServer(null, null));
    m.put("servers", liveServers().size());
    return m;
  }

  /** Splits one region at the row, or at its midkey if the row is null. */
  private boolean splitRegion(RegionInfo region, byte[] row, String what, long deadline)
    throws IOException, InterruptedException {
    RegionInfo target = region;
    boolean compacted = false;
    long t0 = System.nanoTime();
    while (true) {
      if (!compacted && Boolean.TRUE.equals(hasReferences(target))) {
        compact(target);
        compacted = true;
      }
      awaitRegionWithoutReferences(target, deadline);
      long left = deadline - System.currentTimeMillis();
      try {
        if (row == null) {
          admin.splitRegionAsync(target.getRegionName()).get(Math.max(1, left),
            TimeUnit.MILLISECONDS);
        } else {
          admin.splitRegionAsync(target.getRegionName(), row).get(Math.max(1, left),
            TimeUnit.MILLISECONDS);
        }
        record("split", String.format(Locale.ROOT, "%s of %s in %.1f s", what,
          target.getEncodedName(), (System.nanoTime() - t0) / 1e9));
        return true;
      } catch (ExecutionException | IOException e) {
        if (System.currentTimeMillis() >= deadline) {
          record("split-failed", what + " of " + target.getEncodedName() + ": " + cause(e));
          return false;
        }
        LOG.debug("Split of {} at {} failed; retrying", target.getEncodedName(), what, e);
        // The region can be busy, or it can still hold compacted reference files.
        if (!compacted) {
          compact(target);
          compacted = true;
        }
        Thread.sleep(POLL_MS);
        RegionInfo current = regionFor(row != null ? row : target.getStartKey());
        if (current == null) {
          record("split-failed", what + ": the region is gone");
          return false;
        }
        if (row != null && Bytes.equals(current.getStartKey(), row)) {
          return true;
        }
        if (row == null && !current.getEncodedName().equals(target.getEncodedName())) {
          // Another split changed the region, so the midkey round picks again.
          return true;
        }
        target = current;
      } catch (TimeoutException e) {
        record("split-failed", what + " of " + target.getEncodedName() + ": timed out");
        return false;
      }
    }
  }

  /** Waits until no region has reference files. Returns false at the deadline. */
  private boolean awaitNoReferences(long deadline) throws IOException, InterruptedException {
    long t0 = System.nanoTime();
    Set<String> requested = new HashSet<>();
    while (true) {
      List<RegionInfo> regions = regions();
      Set<String> withReferences = referenceRegions(regions);
      if (withReferences == null) {
        // The store files are out of reach. Compact all regions and wait for the compactions.
        for (RegionInfo r : regions) {
          compact(r);
        }
        boolean done = awaitCompactions(regions, deadline);
        record("compacted", String.format(Locale.ROOT, "%d regions in %.1f s", regions.size(),
          (System.nanoTime() - t0) / 1e9));
        return done;
      }
      if (withReferences.isEmpty()) {
        if (!requested.isEmpty()) {
          record("compacted", String.format(Locale.ROOT, "%d regions in %.1f s", requested.size(),
            (System.nanoTime() - t0) / 1e9));
        }
        return true;
      }
      if (System.currentTimeMillis() >= deadline) {
        record("compaction-timeout", withReferences.size() + " regions keep reference files");
        return false;
      }
      for (RegionInfo r : regions) {
        if (withReferences.contains(r.getEncodedName()) && requested.add(r.getEncodedName())) {
          compact(r);
        }
      }
      Thread.sleep(POLL_MS);
    }
  }

  /** Waits until the region has no reference files, or until the deadline. */
  private void awaitRegionWithoutReferences(RegionInfo region, long deadline)
    throws IOException, InterruptedException {
    while (System.currentTimeMillis() < deadline) {
      Boolean refs = hasReferences(region);
      if (refs == null || !refs) {
        return;
      }
      Thread.sleep(POLL_MS);
    }
  }

  /** Waits until no compaction runs in the regions. Returns false at the deadline. */
  private boolean awaitCompactions(List<RegionInfo> regions, long deadline)
    throws IOException, InterruptedException {
    // A compaction request can take a moment to start, so one poll passes first.
    Thread.sleep(POLL_MS);
    while (System.currentTimeMillis() < deadline) {
      boolean busy = false;
      for (RegionInfo r : regions) {
        try {
          if (admin.getCompactionStateForRegion(r.getRegionName()) != CompactionState.NONE) {
            busy = true;
            break;
          }
        } catch (IOException e) {
          LOG.debug("The compaction state of {} is unknown", r.getEncodedName(), e);
        }
      }
      if (!busy) {
        return true;
      }
      Thread.sleep(POLL_MS);
    }
    return false;
  }

  private void compact(RegionInfo r) throws IOException {
    try {
      admin.majorCompactRegion(r.getRegionName());
    } catch (IOException e) {
      LOG.debug("Compaction request for {} failed", r.getEncodedName(), e);
    }
  }

  /** Returns the region that holds the row, or null if the table has no regions. */
  private RegionInfo regionFor(byte[] row) throws IOException {
    RegionInfo found = null;
    for (RegionInfo r : regions()) {
      if (Bytes.compareTo(r.getStartKey(), row) <= 0) {
        found = r;
      }
    }
    return found;
  }

  /** Returns the count of regions with data, in the ID range if the range is not null. */
  private static int populated(List<RegionInfo> regions, Map<String, Long> sizes, Integer firstId,
    Integer count) {
    int n = 0;
    for (RegionInfo r : regions) {
      Long s = sizes.get(r.getEncodedName());
      if (s != null && s > 0 && inRange(r, firstId, count)) {
        n++;
      }
    }
    return n;
  }

  /** Returns true if the region holds IDs of the range, or if the range is null. */
  private static boolean inRange(RegionInfo r, Integer firstId, Integer count) {
    return firstId == null || count == null
      || listsInRegion(r.getStartKey(), r.getEndKey(), firstId, count) > 0;
  }

  /** Returns the count of regions that hold IDs of the {@code count} lists from {@code firstId}. */
  public int populatedRegions(int firstId, int count) throws IOException {
    int n = 0;
    for (RegionInfo r : regions()) {
      if (listsInRegion(r.getStartKey(), r.getEndKey(), firstId, count) > 0) {
        n++;
      }
    }
    return n;
  }

  /**
   * Returns the store bytes of each region by encoded name. The store files on the file system give
   * exact sizes. If they are out of reach, the region metrics give sizes in whole megabytes. A
   * small region with store files then gets one byte, so it still counts as populated.
   */
  Map<String, Long> sizes(List<RegionInfo> regions) throws IOException {
    Map<String, Long> out = storeBytes(regions);
    if (out != null) {
      return out;
    }
    out = new LinkedHashMap<>();
    for (ServerName server : liveServers()) {
      for (RegionMetrics rm : admin.getRegionMetrics(server, table)) {
        String name = RegionInfo.encodeRegionName(rm.getRegionName());
        long bytes = (long) (rm.getStoreFileSize().get(Size.Unit.BYTE)
          + rm.getMemStoreSize().get(Size.Unit.BYTE));
        out.put(name, bytes == 0 && rm.getStoreFileCount() > 0 ? 1L : bytes);
      }
    }
    return out;
  }

  /** Returns the store bytes of each region from the file system, or null if out of reach. */
  private Map<String, Long> storeBytes(List<RegionInfo> regions) {
    try {
      Path tableDir = CommonFSUtils.getTableDir(CommonFSUtils.getRootDir(conf), table);
      FileSystem fs = tableDir.getFileSystem(conf);
      if (!fs.exists(tableDir)) {
        return null;
      }
      Map<String, Long> out = new LinkedHashMap<>();
      for (RegionInfo r : regions) {
        long bytes = 0;
        for (FileStatus family : families(fs, new Path(tableDir, r.getEncodedName()))) {
          bytes += fs.getContentSummary(family.getPath()).getLength();
        }
        out.put(r.getEncodedName(), bytes);
      }
      return out;
    } catch (IOException | RuntimeException e) {
      LOG.debug("The store files of {} are out of reach", table, e);
      return null;
    }
  }

  /** Returns the encoded names of the regions with reference files, or null if out of reach. */
  private Set<String> referenceRegions(List<RegionInfo> regions) {
    Set<String> out = new HashSet<>();
    for (RegionInfo r : regions) {
      Boolean refs = hasReferences(r);
      if (refs == null) {
        return null;
      }
      if (refs) {
        out.add(r.getEncodedName());
      }
    }
    return out;
  }

  /** Returns true if the region has reference files, or null if its files are out of reach. */
  private Boolean hasReferences(RegionInfo r) {
    try {
      Path tableDir = CommonFSUtils.getTableDir(CommonFSUtils.getRootDir(conf), table);
      FileSystem fs = tableDir.getFileSystem(conf);
      if (!fs.exists(tableDir)) {
        return null;
      }
      for (FileStatus family : families(fs, new Path(tableDir, r.getEncodedName()))) {
        for (FileStatus f : fs.listStatus(family.getPath())) {
          if (f.isFile() && REFERENCE.matcher(f.getPath().getName()).matches()) {
            return true;
          }
        }
      }
      return false;
    } catch (IOException | RuntimeException e) {
      LOG.debug("The store files of {} are out of reach", r.getEncodedName(), e);
      return null;
    }
  }

  private static List<FileStatus> families(FileSystem fs, Path regionDir) throws IOException {
    List<FileStatus> out = new ArrayList<>();
    if (!fs.exists(regionDir)) {
      return out;
    }
    for (FileStatus family : fs.listStatus(regionDir)) {
      String name = family.getPath().getName();
      if (family.isDirectory() && !name.startsWith(".") && !name.equals("recovered.edits")) {
        out.add(family);
      }
    }
    return out;
  }

  private List<ServerName> liveServers() throws IOException {
    ClusterMetrics cm = admin.getClusterMetrics(EnumSet.of(ClusterMetrics.Option.LIVE_SERVERS));
    List<ServerName> out = new ArrayList<>(cm.getLiveServerMetrics().keySet());
    Collections.sort(out);
    return out;
  }

  /**
   * Returns the count of regions on each live server, by host and port. Only the regions with IDs
   * of the {@code count} lists from {@code firstId} count, or all regions if the range is null.
   */
  public Map<String, Integer> regionsPerServer(Integer firstId, Integer count) throws IOException {
    Map<ServerName, List<RegionInfo>> inRange = new TreeMap<>();
    placement(liveServers(), firstId, count, inRange, new TreeMap<>());
    return counts(inRange);
  }

  /** Records an event of the layout outside the split, compaction, and balance steps. */
  public void event(String action, String detail) {
    record(action, detail);
  }

  private void record(String action, String detail) {
    Map<String, Object> e = new LinkedHashMap<>();
    e.put("wall_ms", System.currentTimeMillis());
    e.put("phase", phase);
    e.put("action", action);
    e.put("detail", detail);
    events.add(e);
    if (eventLog != null) {
      eventLog.event(EVENT, phase + " " + action + " " + table + ": " + detail);
    }
    LOG.info("{} {} {}: {}", phase, action, table, detail);
  }

  /** Returns the wall time after the timeout, but not after the limit of {@link #stopAt}. */
  private long deadline(long timeoutMs) {
    return Math.min(System.currentTimeMillis() + timeoutMs, stopAtMillis);
  }

  private static String cause(Exception e) {
    Throwable t = e instanceof ExecutionException && e.getCause() != null ? e.getCause() : e;
    return t.getClass().getSimpleName() + ": " + t.getMessage();
  }

  @Override
  public void close() throws IOException {
    admin.close();
  }
}
