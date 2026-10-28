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
package org.apache.phoenix.vectorharness;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.TreeMap;
import org.apache.hadoop.hbase.TableName;
import org.apache.hadoop.hbase.client.Admin;
import org.apache.phoenix.end2end.ParallelStatsDisabledIT;
import org.apache.phoenix.end2end.ParallelStatsDisabledTest;
import org.apache.phoenix.exception.SQLExceptionCode;
import org.apache.phoenix.hbase.index.metrics.MetricsVectorIndexSource;
import org.apache.phoenix.hbase.index.metrics.MetricsVectorIndexSource.HnswMetric;
import org.apache.phoenix.index.vector.GenerationSummary;
import org.apache.phoenix.index.vector.VectorIndexRebuilder;
import org.apache.phoenix.vectorharness.config.HarnessConfig;
import org.apache.phoenix.vectorharness.config.QueryShape;
import org.apache.phoenix.vectorharness.config.TableLayout;
import org.apache.phoenix.vectorharness.corpus.AttributeModel;
import org.apache.phoenix.vectorharness.corpus.DocAttributes;
import org.apache.phoenix.vectorharness.corpus.PanelQuery;
import org.apache.phoenix.vectorharness.observe.IndexFsck;
import org.apache.phoenix.vectorharness.observe.IvfCentroidObserver;
import org.apache.phoenix.vectorharness.observe.MetricCatalog;
import org.apache.phoenix.vectorharness.observe.MetricSnapshot;
import org.apache.phoenix.vectorharness.observe.PlanClass;
import org.apache.phoenix.vectorharness.observe.PlanClassifier;
import org.apache.phoenix.vectorharness.observe.RegistryJmxReader;
import org.apache.phoenix.vectorharness.observe.StorageFootprint;
import org.apache.phoenix.vectorharness.observe.VectorIndexMetricsObserver;
import org.apache.phoenix.vectorharness.result.EventLog;
import org.apache.phoenix.vectorharness.truth.AccuracyScorer;
import org.apache.phoenix.vectorharness.truth.ConditionPlanner;
import org.apache.phoenix.vectorharness.truth.NeighborList;
import org.apache.phoenix.vectorharness.truth.QueryCondition;
import org.apache.phoenix.vectorharness.truth.QueryScore;
import org.apache.phoenix.vectorharness.variant.ExactVariant;
import org.apache.phoenix.vectorharness.variant.HnswVariant;
import org.apache.phoenix.vectorharness.variant.IndexInfo;
import org.apache.phoenix.vectorharness.variant.IndexVariant;
import org.apache.phoenix.vectorharness.variant.SweepPoint;
import org.apache.phoenix.vectorharness.variant.Variants;
import org.apache.phoenix.vectorharness.workload.QueryExecutor;
import org.apache.phoenix.vectorharness.workload.QuerySpec;
import org.apache.phoenix.vectorharness.workload.RowWriter;
import org.apache.phoenix.vectorharness.workload.Schema;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.FixMethodOrder;
import org.junit.Test;
import org.junit.experimental.categories.Category;
import org.junit.runners.MethodSorters;

/**
 * Tests the basic functions of the IVF and HNSW vector indexes with harness components and plain
 * JDBC instead of the harness command line, so that a failure points at the index: the tables are
 * created by {@link Schema} and loaded by {@link RowWriter}, the variants issue their DDL and
 * builds, queries are rendered by the query factory and run by {@link QueryExecutor}, ground truth
 * is a client-side brute force over the corpus, and plans, fsck reports, centroid generations, and
 * metrics are read by the harness observers.
 * <p>
 * The tests share one index per variant and metric, built on first use. They run in name order, so
 * that the tests that change an index (compaction, rebuild) follow the tests that measure it, and
 * the tests that write rows remove them again.
 */
@Category(ParallelStatsDisabledTest.class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
public class VectorIndexFunctionsIT extends ParallelStatsDisabledIT {

  private static final int K = VectorIndexFixture.K;
  /** The panel queries of the accuracy and filter checks. */
  private static final int PANEL = 16;
  /** The relative tie tolerance of the recall scores. */
  private static final double TIE_EPSILON = 1e-6;
  /** The mean recall that a wide HNSW beam must reach. */
  private static final double MIN_HNSW_RECALL = 0.9;
  /** The share of the rows that the filters select. */
  private static final double SELECTIVITY = 0.25;

  private static final Map<String, VectorIndexFixture> FIXTURES = new LinkedHashMap<>();
  private static Path output;

  @BeforeClass
  public static synchronized void setUpOutput() throws Exception {
    output = tmpFolder.newFolder("vh-functions").toPath();
    HarnessContext.setClusterConfiguration(getUtility().getConfiguration());
  }

  @AfterClass
  public static synchronized void tearDownFixtures() {
    for (VectorIndexFixture f : FIXTURES.values()) {
      f.close();
    }
    FIXTURES.clear();
    HarnessContext.setClusterConfiguration(null);
  }

  /** Returns the shared fixture of a variant and metric, building it on first use. */
  private static synchronized VectorIndexFixture fixture(String variant, String metric,
    boolean multiTenant) throws Exception {
    String suffix =
      "FN_" + variant.toUpperCase(Locale.ROOT) + "_" + metric + (multiTenant ? "_MT" : "");
    VectorIndexFixture f = FIXTURES.get(suffix);
    if (f == null) {
      Properties p = VectorIndexFixture.profile(getUrl(), output, variant, metric, suffix);
      if (multiTenant) {
        p.setProperty("table.multi_tenant", "true");
        p.setProperty("corpus.tenants", "3");
      }
      f = VectorIndexFixture.create(suffix, p);
      FIXTURES.put(suffix, f);
    }
    return f;
  }

  private static QueryCondition unfiltered() {
    return QueryCondition.builder(QueryShape.UNFILTERED).build();
  }

  private static QueryExecutor.Result execute(QueryExecutor exec, QuerySpec spec) {
    QueryExecutor.Result r = exec.execute(spec, 0);
    assertNull(spec.sql() + " failed: " + r.error(), r.error());
    return r;
  }

  private static boolean contains(long[] ordinals, long ordinal) {
    for (long o : ordinals) {
      if (o == ordinal) {
        return true;
      }
    }
    return false;
  }

  /**
   * Checks that every returned row is a distinct corpus row, that the engine ranks ascending, and
   * that each distance the engine returns equals the client-side distance of the row.
   */
  private static void assertEngineDistances(VectorIndexFixture f, PanelQuery q, QuerySpec spec,
    QueryExecutor.Result r) {
    long[] o = r.ordinals();
    double[] d = r.distances();
    double[] client = new double[o.length];
    Set<Long> seen = new java.util.HashSet<>();
    for (int i = 0; i < o.length; i++) {
      String at = f + " " + spec.sql() + " row " + i + " (ordinal " + o[i] + ")";
      assertTrue(at + " is not a corpus row", o[i] >= 0 && o[i] < f.rows());
      assertTrue(at + " is returned twice", seen.add(o[i]));
      client[i] = f.metric.distance(q.vector(), f.corpus.vector(o[i]));
      double tolerance = 1e-4 * Math.abs(client[i]) + 1e-5;
      assertEquals(at + " distance", client[i], d[i], tolerance);
      if (i > 0) {
        assertTrue(at + " ranks before a nearer row: " + Arrays.toString(d),
          d[i] >= d[i - 1] - 1e-6);
      }
    }
  }

  /**
   * Runs the unfiltered panel at one breadth, checks the engine distances, and returns the mean
   * recall against the brute force top-k; an exact breadth must find the top-k of every query.
   */
  private static double checkAccuracy(VectorIndexFixture f, SweepPoint point, boolean exact)
    throws Exception {
    List<PanelQuery> panel = f.panel(PANEL);
    QueryCondition none = unfiltered();
    List<NeighborList> truth = f.truth(panel, none, K);
    AccuracyScorer scorer = new AccuracyScorer(TIE_EPSILON);
    double sum = 0;
    try (QueryExecutor exec = new QueryExecutor(f.ctx)) {
      for (int i = 0; i < panel.size(); i++) {
        PanelQuery q = panel.get(i);
        QuerySpec spec = f.spec(q, none, point);
        QueryExecutor.Result r = execute(exec, spec);
        assertEquals(f + " " + spec.sql() + " rows", K, r.ordinals().length);
        assertEngineDistances(f, q, spec, r);
        QueryScore s = scorer.score(r.ordinals(), f.trueDistances(q.vector(), r.ordinals()),
          truth.get(i), Double.NaN, 0);
        if (exact) {
          assertEquals(f + " " + point + " query " + i + " returned "
            + Arrays.toString(r.ordinals()) + ", exact " + truth.get(i), 1.0, s.recall(), 0.0);
        }
        sum += s.recall();
      }
    }
    double mean = sum / panel.size();
    System.out.println(f + " " + point + " unfiltered mean recall " + mean);
    return mean;
  }

  private static void assertIvfBuilt(VectorIndexFixture f, String metric) throws Exception {
    IndexInfo info = f.info();
    assertTrue(f + " " + info.toMap(), info.active());
    assertNotNull(f + " is untrained", info.activeGeneration());
    assertEquals("IVF", info.algorithm());
    assertEquals(metric, info.metric());
    assertEquals(Integer.valueOf(VectorIndexFixture.DIM), info.dimension());
    // The split heuristic of k-means can train more centroids than the requested lists.
    assertTrue(f + " lists " + info.ivfLists(), info.ivfLists() >= 8);
    assertEquals(f.build.toString(), info.ivfLists(), f.build.get("effective_lists"));
  }

  private static void assertHnswBuilt(VectorIndexFixture f, String metric) throws Exception {
    IndexInfo info = f.info();
    assertTrue(f + " " + info.toMap(), info.active());
    assertEquals("HNSW", info.algorithm());
    assertEquals(metric, info.metric());
    try (Connection conn = f.ctx.connection()) {
      HnswVariant.SegmentCount sc = HnswVariant.segments(conn, f.fullTable(), f.fullIndex());
      assertEquals(sc.toMap().toString(), 2, sc.regions());
      assertEquals(sc.toMap().toString(), sc.regions(), sc.bases());
      assertEquals(sc.toMap().toString(), (long) VectorIndexFixture.ROWS,
        ((Number) sc.toMap().get("base_rows")).longValue());
    }
  }

  @Test
  public void test01IvfL2Accuracy() throws Exception {
    VectorIndexFixture f = fixture("ivf", "L2", false);
    assertIvfBuilt(f, "L2");
    // Probing every centroid is exhaustive over the index, so it must find the exact top-k.
    checkAccuracy(f, f.point("probes=all"), true);
  }

  @Test
  public void test02IvfCosineAccuracy() throws Exception {
    VectorIndexFixture f = fixture("ivf", "COSINE", false);
    assertIvfBuilt(f, "COSINE");
    checkAccuracy(f, f.point("probes=all"), true);
  }

  @Test
  public void test03IvfInnerProductAccuracy() throws Exception {
    VectorIndexFixture f = fixture("ivf", "INNER_PRODUCT", false);
    assertIvfBuilt(f, "INNER_PRODUCT");
    checkAccuracy(f, f.point("probes=all"), true);
  }

  @Test
  public void test04HnswL2Accuracy() throws Exception {
    VectorIndexFixture f = fixture("hnsw", "L2", false);
    assertHnswBuilt(f, "L2");
    double recall = checkAccuracy(f, f.point("ef=256"), false);
    assertTrue(f + " ef=256 mean recall " + recall, recall >= MIN_HNSW_RECALL);
  }

  @Test
  public void test05HnswCosineAccuracy() throws Exception {
    VectorIndexFixture f = fixture("hnsw", "COSINE", false);
    assertHnswBuilt(f, "COSINE");
    double recall = checkAccuracy(f, f.point("ef=256"), false);
    assertTrue(f + " ef=256 mean recall " + recall, recall >= MIN_HNSW_RECALL);
  }

  /**
   * Runs a covered CATEGORY range, an uncovered SOURCE range, a DOC_ID range, and a distance radius
   * for every panel query. Every returned row must satisfy its predicate; an exact breadth must
   * find the filtered top-k, and an approximate one most of it.
   */
  private static void checkFilters(VectorIndexFixture f, SweepPoint point, boolean exact)
    throws Exception {
    List<PanelQuery> panel = f.panel(PANEL);
    AttributeModel attrs = f.corpus.attributeModel();
    ConditionPlanner planner = new ConditionPlanner(f.ctx.config(), attrs);
    List<NeighborList> unfiltered = f.truth(panel, unfiltered(), K);
    List<List<QueryCondition>> conds = new ArrayList<>();
    for (int i = 0; i < panel.size(); i++) {
      List<QueryCondition> c = new ArrayList<>();
      int[] cat = attrs.categories().rangeForSelectivity(SELECTIVITY, i * 7919);
      c.add(QueryCondition.builder(QueryShape.COVERED_FILTER)
        .range(QueryCondition.Attribute.CATEGORY, cat[0], cat[1]).build());
      int[] src = attrs.sources().rangeForSelectivity(SELECTIVITY, i * 7919 + 3);
      c.add(QueryCondition.builder(QueryShape.UNCOVERED_FILTER)
        .range(QueryCondition.Attribute.SOURCE, src[0], src[1]).build());
      // A DOC_ID range without the leading TENANT_ID is a filter, not a key range.
      c.add(QueryCondition.builder(QueryShape.PK_RANGE)
        .docIds(AttributeModel.docIdBound(0.2), AttributeModel.docIdBound(0.6)).build());
      c.addAll(planner.distanceBound(unfiltered.get(i)));
      conds.add(c);
    }
    List<Map<String, NeighborList>> truth = f.truth(panel, conds, K);
    AccuracyScorer scorer = new AccuracyScorer(TIE_EPSILON);
    Map<QueryShape, double[]> recall = new TreeMap<>();
    try (QueryExecutor exec = new QueryExecutor(f.ctx)) {
      for (int i = 0; i < panel.size(); i++) {
        PanelQuery q = panel.get(i);
        for (QueryCondition c : conds.get(i)) {
          QuerySpec spec = f.spec(q, c, point);
          QueryExecutor.Result r = execute(exec, spec);
          assertEngineDistances(f, q, spec, r);
          for (long o : r.ordinals()) {
            DocAttributes a = f.corpus.attributes(o);
            assertTrue(f + " " + spec.sql() + " returned " + a + ", which fails " + c.key(),
              c.test(a));
            if (c.hasRadius()) {
              double d = f.metric.distance(q.vector(), f.corpus.vector(o));
              assertTrue(
                f + " " + spec.sql() + " returned " + a + " at " + d + ", outside " + c.radius(),
                d < c.radius() + 1e-5 * (1 + Math.abs(c.radius())));
            }
          }
          NeighborList t = truth.get(i).get(c.key());
          assertTrue(c.key() + " selects rows", t.size() > 0);
          QueryScore s = scorer.score(r.ordinals(), f.trueDistances(q.vector(), r.ordinals()), t,
            c.hasRadius() ? c.radius() : Double.NaN, 0);
          if (exact) {
            assertEquals(f + " " + spec.sql() + " rows", t.size(), r.ordinals().length);
            assertEquals(f + " " + point + " query " + i + " " + c.key() + " returned "
              + Arrays.toString(r.ordinals()) + ", exact " + t, 1.0, s.recall(), 0.0);
          }
          double[] acc = recall.computeIfAbsent(c.shape(), k -> new double[2]);
          acc[0] += s.recall();
          acc[1]++;
        }
      }
    }
    assertEquals(recall.keySet().toString(), 4, recall.size());
    for (Map.Entry<QueryShape, double[]> e : recall.entrySet()) {
      double mean = e.getValue()[0] / e.getValue()[1];
      System.out.println(f + " " + point + " " + e.getKey().id() + " mean recall " + mean);
      assertTrue(f + " " + point + " " + e.getKey().id() + " mean recall " + mean,
        mean >= MIN_HNSW_RECALL);
    }
  }

  @Test
  public void test10IvfFilters() throws Exception {
    VectorIndexFixture f = fixture("ivf", "L2", false);
    checkFilters(f, f.point("probes=all"), true);
  }

  @Test
  public void test11HnswFilters() throws Exception {
    VectorIndexFixture f = fixture("hnsw", "L2", false);
    checkFilters(f, f.point("ef=256"), false);
  }

  /**
   * Writes a row whose vector equals a query vector through the live write path, with no flush,
   * then moves it to a far query vector, then deletes it. Each change must be visible to the next
   * query at the default breadth.
   */
  private static void checkFreshness(VectorIndexFixture f) throws Exception {
    SweepPoint point = f.defaultPoint();
    List<PanelQuery> panel = f.panel(PANEL);
    PanelQuery q1 = panel.get(0);
    PanelQuery q2 = null;
    double far = -1;
    for (PanelQuery q : panel) {
      double d = f.metric.distance(q1.vector(), q.vector());
      if (d > far) {
        far = d;
        q2 = q;
      }
    }
    QueryCondition none = unfiltered();
    List<NeighborList> truth = f.truth(Arrays.asList(q1, q2), none, K);
    // The moved row must leave the top-k of its old vector, and must not enter that of q2 early.
    assertTrue("q2 is too near q1", far > truth.get(0).distance(K - 1));
    assertTrue("q1 is too near q2", far > truth.get(1).distance(K - 1));
    long ordinal = f.rows() + 17;
    DocAttributes a = f.corpus.attributes(ordinal);
    try (Connection conn = f.ctx.connection();
      RowWriter w =
        new RowWriter(conn, f.names, EnumSet.of(TableLayout.CORPUS), f.corpus.attributeModel());
      QueryExecutor exec = new QueryExecutor(f.ctx)) {
      conn.setAutoCommit(false);
      QuerySpec s1 = f.spec(q1, none, point);
      QuerySpec s2 = f.spec(q2, none, point);
      try {
        w.upsert(a, q1.vector());
        w.commit();
        QueryExecutor.Result r = execute(exec, s1);
        assertEquals(
          f + " inserted row is not the top-1 of its vector: " + Arrays.toString(r.ordinals()),
          ordinal, r.ordinals()[0]);
        assertEquals(0.0, r.distances()[0], 1e-5);
        assertFalse(f + " inserted row is near q2",
          contains(execute(exec, s2).ordinals(), ordinal));

        w.upsert(a, q2.vector());
        w.commit();
        r = execute(exec, s2);
        assertEquals(
          f + " moved row is not the top-1 at its new vector: " + Arrays.toString(r.ordinals()),
          ordinal, r.ordinals()[0]);
        assertEquals(0.0, r.distances()[0], 1e-5);
        r = execute(exec, s1);
        assertFalse(f + " moved row is still found at its old vector: "
          + Arrays.toString(r.ordinals()) + " " + Arrays.toString(r.distances()),
          contains(r.ordinals(), ordinal));

        w.delete(a);
        w.commit();
        assertFalse(f + " deleted row is found at its last vector",
          contains(execute(exec, s2).ordinals(), ordinal));
        assertFalse(f + " deleted row is found at its first vector",
          contains(execute(exec, s1).ordinals(), ordinal));
        assertEquals(K, execute(exec, s2).ordinals().length);
      } finally {
        w.delete(a);
        w.commit();
      }
    }
  }

  @Test
  public void test20IvfFreshness() throws Exception {
    checkFreshness(fixture("ivf", "L2", false));
  }

  @Test
  public void test21HnswFreshness() throws Exception {
    checkFreshness(fixture("hnsw", "L2", false));
  }

  /** One option set of the DDL validation test, with the error Phoenix must raise, or null. */
  private static final class DdlCase {
    final String variant;
    final SQLExceptionCode expected;
    final String[] settings;

    DdlCase(String variant, SQLExceptionCode expected, String... settings) {
      this.variant = variant;
      this.expected = expected;
      this.settings = settings;
    }

    @Override
    public String toString() {
      return variant + " " + Arrays.toString(settings);
    }
  }

  /**
   * For option sets inside and outside the documented ranges, the harness validation of the variant
   * and Phoenix must agree: the harness refuses exactly the profiles whose DDL Phoenix rejects, and
   * accepts those whose DDL Phoenix accepts.
   */
  @Test
  public void test30DdlValidationAgrees() throws Exception {
    SQLExceptionCode invalid = SQLExceptionCode.INVALID_VECTOR_INDEX_PARAMS;
    List<DdlCase> cases = Arrays.asList(new DdlCase("hnsw", invalid, "hnsw.m", "2"),
      new DdlCase("hnsw", invalid, "hnsw.m", "100"),
      new DdlCase("hnsw", invalid, "hnsw.ef_construction", "8"),
      new DdlCase("hnsw", invalid, "hnsw.ef_construction", "1000"),
      new DdlCase("hnsw", invalid, "hnsw.alpha", "0.5"),
      new DdlCase("hnsw", invalid, "hnsw.alpha", "3.0"),
      new DdlCase("hnsw", SQLExceptionCode.VECTOR_QUANTIZATION_DIMENSION_MISMATCH,
        "hnsw.quantization", "PQ", "hnsw.pq_segments", "5"),
      new DdlCase("hnsw", invalid, "hnsw.quantization", "PQ", "hnsw.pq_segments", "300"),
      new DdlCase("hnsw", invalid, "hnsw.quantization", "PQ"),
      new DdlCase("hnsw", SQLExceptionCode.UNSUPPORTED_VECTOR_QUANTIZATION_TYPE,
        "hnsw.quantization", "INT4"),
      new DdlCase("hnsw", SQLExceptionCode.UNSUPPORTED_VECTOR_DISTANCE_METRIC, "index.metric",
        "INNER_PRODUCT"),
      new DdlCase("ivf", invalid, "ivf.lists", "8", "ivf.sample_size", "4"),
      new DdlCase("ivf", invalid, "ivf.lists", "0"),
      new DdlCase("hnsw", null, "hnsw.m", "4", "hnsw.ef_construction", "16", "hnsw.alpha", "1.0"),
      new DdlCase("hnsw", null, "hnsw.m", "64", "hnsw.ef_construction", "512", "hnsw.alpha", "2.0"),
      new DdlCase("hnsw", null, "hnsw.quantization", "PQ", "hnsw.pq_segments", "4"),
      new DdlCase("hnsw", null, "hnsw.quantization", "SQ8", "index.metric", "COSINE"), new DdlCase(
        "ivf", null, "ivf.lists", "8", "ivf.sample_size", "8", "index.metric", "INNER_PRODUCT"));
    Properties base = VectorIndexFixture.profile(getUrl(), output, "hnsw", "L2", "FN_DDL");
    try (HarnessContext ctx = VectorIndexFixture.open(base); Connection conn = ctx.connection()) {
      Schema schema = new Schema(ctx.config(), ctx.tables(), ctx.corpus().dim());
      schema.drop(conn, TableLayout.CORPUS);
      schema.create(conn, TableLayout.CORPUS, Collections.<byte[]> emptyList());
    }
    List<String> disagreements = new ArrayList<>();
    for (DdlCase c : cases) {
      Properties p = new Properties();
      p.putAll(base);
      p.setProperty("run.variant", c.variant);
      for (int i = 0; i < c.settings.length; i += 2) {
        p.setProperty(c.settings[i], c.settings[i + 1]);
      }
      try (HarnessContext ctx = VectorIndexFixture.open(p); Connection conn = ctx.connection()) {
        IndexVariant v = Variants.of(ctx.config().variant());
        Schema schema = new Schema(ctx.config(), ctx.tables(), ctx.corpus().dim());
        List<String> problems = v.validate(ctx, ctx.corpus().dim(), VectorIndexFixture.ROWS);
        String ddl = v.createIndexDdl(ctx, schema, TableLayout.CORPUS);
        SQLException rejected = null;
        try {
          conn.createStatement().execute(ddl);
        } catch (SQLException e) {
          rejected = e;
        } finally {
          if (rejected == null) {
            conn.createStatement()
              .execute("DROP INDEX IF EXISTS " + ctx.tables().index(TableLayout.CORPUS) + " ON "
                + ctx.tables().fullTable(TableLayout.CORPUS));
          }
        }
        if (c.expected == null) {
          assertNull(c + ": Phoenix rejects " + ddl, rejected);
          if (!problems.isEmpty()) {
            disagreements.add(c + ": Phoenix accepts, the harness refuses " + problems);
          }
        } else {
          assertNotNull(c + ": Phoenix accepts " + ddl, rejected);
          assertEquals(c + ": " + rejected.getMessage(), c.expected.getErrorCode(),
            rejected.getErrorCode());
          if (problems.isEmpty()) {
            disagreements
              .add(c + ": Phoenix rejects (" + rejected.getMessage() + "), the harness accepts");
          }
        }
      }
    }
    assertTrue(String.join("\n", disagreements), disagreements.isEmpty());
  }

  /**
   * Checks with EXPLAIN that each query shape of the profile takes the plan that the variant
   * expects at the default breadth, that the NO_INDEX hint gives an exact plan, and that the
   * breadth of each sweep point shows in the plan.
   */
  private static void checkPlans(VectorIndexFixture f) throws Exception {
    HarnessConfig c = f.ctx.config();
    ConditionPlanner planner = new ConditionPlanner(c, f.corpus.attributeModel());
    List<PanelQuery> panel = f.panel(4);
    List<NeighborList> unfiltered = f.truth(panel, unfiltered(), K);
    IndexInfo info = f.info();
    SweepPoint dflt = f.variant.defaultPoint(f.ctx, info);
    boolean ivf = f.variant.id().equals("ivf");
    Set<QueryShape> seen = EnumSet.noneOf(QueryShape.class);
    try (QueryExecutor exec = new QueryExecutor(f.ctx)) {
      for (int i = 0; i < panel.size(); i++) {
        PanelQuery q = panel.get(i);
        List<QueryCondition> conds = new ArrayList<>(planner.relational(q));
        conds.addAll(planner.distanceBound(unfiltered.get(i)));
        for (QueryCondition cond : conds) {
          seen.add(cond.shape());
          QuerySpec spec = f.spec(q, cond, dflt);
          String plan = exec.explain(spec);
          PlanClassifier.Classification cls = PlanClassifier.classify(plan);
          PlanClass expected = f.variant.expectedPlan(cond.shape(), c.multiTenant());
          assertEquals(f + " " + cond.family() + ":\n" + plan, expected, cls.planClass());
          assertEngineDistances(f, q, spec, execute(exec, spec));
          if (ivf && cond.shape() == QueryShape.UNFILTERED) {
            assertEquals(f + " default probes:\n" + plan, dflt.value(), cls.probes());
            assertEquals(f + " centroids:\n" + plan, info.ivfLists().intValue(), cls.centroids());
          }
          if (ivf && cond.shape() == QueryShape.COVERED_FILTER) {
            assertFalse(f + " covered filter merges data rows:\n" + plan, cls.serverMerge());
          }
          if (ivf && cond.shape() == QueryShape.UNCOVERED_FILTER) {
            assertTrue(f + " uncovered filter without data rows:\n" + plan, cls.serverMerge());
          }
          QuerySpec noIndex =
            f.queries.build(TableLayout.CORPUS, q, cond, K, dflt, ExactVariant.NO_INDEX);
          String exactPlan = exec.explain(noIndex);
          PlanClass exactClass = PlanClassifier.classify(exactPlan).planClass();
          assertTrue(f + " " + cond.family() + " NO_INDEX:\n" + exactPlan, exactClass.exact());
          if (!expected.exact()) {
            assertEquals(f + " " + cond.family() + " NO_INDEX:\n" + exactPlan, PlanClass.EXACT,
              exactClass);
          }
        }
      }
      assertEquals(
        EnumSet.of(QueryShape.UNFILTERED, QueryShape.COVERED_FILTER, QueryShape.UNCOVERED_FILTER,
          QueryShape.TENANT_SCOPED, QueryShape.PK_RANGE, QueryShape.DISTANCE_BOUND),
        seen);
      for (SweepPoint p : f.variant.sweep(f.ctx, info)) {
        String plan = exec.explain(f.spec(panel.get(0), unfiltered(), p));
        PlanClassifier.Classification cls = PlanClassifier.classify(plan);
        if (ivf) {
          assertEquals(f + " " + p + ":\n" + plan, PlanClass.IVF_PROBE, cls.planClass());
          assertEquals(f + " " + p + ":\n" + plan, p.value(), cls.probes());
          assertEquals(f + " " + p + ":\n" + plan, info.ivfLists().intValue(), cls.centroids());
          if (p.value() >= info.ivfLists()) {
            assertEquals(f + " " + p + " expands:\n" + plan, 1, cls.batches());
          } else {
            assertTrue(f + " " + p + " does not expand:\n" + plan, cls.batches() > 1);
          }
        } else {
          assertEquals(f + " " + p + ":\n" + plan, PlanClass.HNSW_SEARCH, cls.planClass());
          assertEquals(f + " " + p + ":\n" + plan, Math.max(p.value(), K), cls.candidates());
        }
      }
    }
  }

  @Test
  public void test40IvfPlans() throws Exception {
    checkPlans(fixture("ivf", "L2", false));
  }

  @Test
  public void test41HnswPlans() throws Exception {
    checkPlans(fixture("hnsw", "L2", false));
  }

  /** Returns the result rows of the unfiltered panel at one breadth. */
  private static List<long[]> results(VectorIndexFixture f, SweepPoint point) {
    List<long[]> out = new ArrayList<>();
    try (QueryExecutor exec = new QueryExecutor(f.ctx)) {
      for (PanelQuery q : f.panel(PANEL)) {
        long[] o = execute(exec, f.spec(q, unfiltered(), point)).ordinals().clone();
        Arrays.sort(o);
        out.add(o);
      }
    }
    return out;
  }

  /**
   * Flushes and major compacts the data table of an HNSW index. Each region then writes a delta
   * segment on its base; the results must not change, and an fsck and the inspect commands must
   * find one exact base segment per region and no fault.
   */
  @Test
  public void test50HnswDurability() throws Exception {
    VectorIndexFixture f = fixture("hnsw", "L2", false);
    SweepPoint wide = f.point("ef=256");
    List<long[]> before = results(f, wide);
    try (Connection conn = f.ctx.connection()) {
      TableName tn = StorageFootprint.physicalName(conn, f.fullTable());
      try (Admin admin = getUtility().getAdmin()) {
        admin.flush(tn);
      }
      getUtility().compact(tn, true);
      HnswVariant.SegmentCount sc = null;
      long deadline = System.currentTimeMillis() + 60_000;
      while (System.currentTimeMillis() < deadline) {
        sc = HnswVariant.segments(conn, f.fullTable(), f.fullIndex());
        if (sc.deltas() >= sc.regions()) {
          break;
        }
        Thread.sleep(200);
      }
      assertNotNull(sc);
      assertEquals("one base per region after compaction: " + sc.toMap(), sc.regions(), sc.bases());
      assertTrue("a compaction delta per region: " + sc.toMap(), sc.deltas() >= sc.regions());

      List<long[]> after = results(f, wide);
      for (int i = 0; i < before.size(); i++) {
        assertArrayEquals(f + " query " + i + " changed after compaction", before.get(i),
          after.get(i));
      }

      Map<String, Object> fsck = IndexFsck.fsck(conn, f.fullTable(), f.fullIndex());
      @SuppressWarnings("unchecked")
      List<String> findings = (List<String>) fsck.get("findings");
      assertEquals(fsck.toString(), Boolean.FALSE, fsck.get("errors"));
      for (String finding : findings) {
        assertFalse(finding, finding.startsWith("[ERROR]") || finding.startsWith("[WARN]"));
      }
      Map<String, Object> coverage = IndexFsck
        .summarizeCoverage(IndexFsck.inspect(conn, f.fullTable(), f.fullIndex(), "coverage", null));
      assertEquals(coverage.toString(), sc.regions(), coverage.get("regions"));
      assertEquals(coverage.toString(), sc.regions(), coverage.get("covered"));
      assertEquals(coverage.toString(), sc.regions(), coverage.get("exact"));
      assertEquals(coverage.toString(), 0, coverage.get("retirable"));
      Map<String, Object> list = IndexFsck
        .summarizeList(IndexFsck.inspect(conn, f.fullTable(), f.fullIndex(), "list", null));
      assertEquals(list.toString(), sc.regions(), list.get("base_segments"));
      assertEquals(list.toString(), (long) VectorIndexFixture.ROWS, list.get("base_rows"));
      assertEquals(list.toString(), sc.deltas(), list.get("delta_segments"));
    }
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> activeGeneration(Map<String, Object> sample) {
    List<Map<String, Object>> gens = (List<Map<String, Object>>) sample.get("generations");
    assertEquals(sample.toString(), 1, gens.size());
    assertEquals(sample.toString(), "active", gens.get(0).get("role"));
    return gens.get(0);
  }

  @SuppressWarnings("unchecked")
  private static long population(Map<String, Object> generation, String source) {
    return ((Number) ((Map<String, Object>) generation.get(source)).get("population")).longValue();
  }

  /**
   * An explicit rebuild of an IVF index trains a new centroid generation, migrates every row, and
   * cuts over; full probing must stay exact, and the centroid observer must see the cutover.
   */
  @Test
  public void test60IvfRebuild() throws Exception {
    VectorIndexFixture f = fixture("ivf", "L2", false);
    IndexInfo before = f.info();
    IvfCentroidObserver observer = new IvfCentroidObserver(() -> f.ctx.connection(),
      Collections.singletonList(f.fullIndex()), null, f.events);
    try {
      @SuppressWarnings("unchecked")
      Map<String, Object> s0 = (Map<String, Object>) observer.sample(true).get(f.fullIndex());
      assertEquals(s0.toString(), before.activeGeneration(), s0.get("active_generation"));
      assertEquals(s0.toString(), (long) VectorIndexFixture.ROWS,
        population(activeGeneration(s0), "exact"));
      try (Connection conn = f.ctx.connection()) {
        conn.createStatement()
          .execute("ALTER INDEX " + f.index() + " ON " + f.fullTable() + " REBUILD");
      }
      IndexInfo after = f.info();
      assertTrue(after.toMap().toString(), after.active());
      assertFalse(after.toMap().toString(), after.rebuildInProgress());
      assertTrue(before.activeGeneration() + " -> " + after.activeGeneration(),
        after.activeGeneration() > before.activeGeneration());
      @SuppressWarnings("unchecked")
      Map<String, Object> s1 = (Map<String, Object>) observer.sample(true).get(f.fullIndex());
      assertEquals(s1.toString(), after.activeGeneration(), s1.get("active_generation"));
      Map<String, Object> gen = activeGeneration(s1);
      assertEquals(gen.toString(), GenerationSummary.ACTIVE, gen.get("rebuild_state"));
      assertEquals(gen.toString(), VectorIndexRebuilder.MANUAL_REASON, gen.get("trigger_reason"));
      assertEquals(gen.toString(), (long) VectorIndexFixture.ROWS, population(gen, "exact"));
      assertEquals(gen.toString(), (long) VectorIndexFixture.ROWS, population(gen, "scorecard"));
      List<EventLog.Event> cutovers = f.events.events("ivf-cutover");
      assertEquals(f.events.events().toString(), 1, cutovers.size());
      assertTrue(cutovers.get(0).detail(),
        cutovers.get(0).detail().endsWith("-> " + after.activeGeneration()));
    } finally {
      observer.close();
    }
    checkAccuracy(f, f.point("probes=all"), true);
    checkFilters(f, f.point("probes=all"), true);
  }

  private static double value(MetricSnapshot s, String key) {
    Double v = s.get(key);
    return v == null ? 0.0 : v;
  }

  private static String key(HnswMetric m, String index) {
    return MetricCatalog.hnsw(m).scopedName(index);
  }

  /**
   * The in-process registry of the vector index metrics must count the HNSW searches of queries,
   * once per region scan, and the IVF and HNSW index maintenance of writes. IVF queries have no
   * server-side counter, so the IVF counters must not move on queries.
   */
  @Test
  public void test70MetricsRegistry() throws Exception {
    VectorIndexFixture ivf = fixture("ivf", "L2", false);
    VectorIndexFixture hnsw = fixture("hnsw", "L2", false);
    String hi = hnsw.fullIndex();
    String assignments =
      MetricsVectorIndexSource.VECTOR_CENTROID_ASSIGNMENTS + "." + ivf.fullIndex();
    int regions;
    try (Connection conn = hnsw.ctx.connection()) {
      regions = HnswVariant.segments(conn, hnsw.fullTable(), hi).regions();
    }
    int n = 8;
    try (
      VectorIndexMetricsObserver observer = new VectorIndexMetricsObserver(new RegistryJmxReader(),
        Arrays.asList(ivf.fullIndex(), hi), null, null)) {
      assertEquals("registry", observer.mode());
      MetricSnapshot s0 = observer.snapshot("before-queries");
      assertEquals(1, s0.serversReporting());
      for (VectorIndexFixture f : Arrays.asList(ivf, hnsw)) {
        SweepPoint point = f.defaultPoint();
        try (QueryExecutor exec = new QueryExecutor(f.ctx)) {
          for (PanelQuery q : f.panel(n)) {
            execute(exec, f.spec(q, unfiltered(), point));
          }
        }
      }
      MetricSnapshot s1 = observer.snapshot("after-queries");
      double searches =
        value(s1, key(HnswMetric.SEARCHES, hi)) - value(s0, key(HnswMetric.SEARCHES, hi));
      assertTrue(
        "HNSW searches " + searches + " for " + n + " queries over " + regions + " regions",
        searches >= n * regions);
      assertEquals("one search time sample per search", searches,
        value(s1, key(HnswMetric.SEARCH_TIME, hi)) - value(s0, key(HnswMetric.SEARCH_TIME, hi)),
        0.0);
      assertTrue("nodes visited", value(s1, key(HnswMetric.NODES_VISITED, hi))
          > value(s0, key(HnswMetric.NODES_VISITED, hi)));
      assertTrue("the server value sums the indexes",
        value(s1, key(HnswMetric.SEARCHES, null)) - value(s0, key(HnswMetric.SEARCHES, null))
            >= searches);
      assertEquals("open HNSW regions", regions, value(s1, key(HnswMetric.REGIONS, hi)), 0.0);
      assertEquals("uncovered HNSW regions", 0.0, value(s1, key(HnswMetric.REGIONS_UNCOVERED, hi)),
        0.0);
      assertEquals("IVF queries assign no vectors", value(s0, assignments), value(s1, assignments),
        0.0);

      List<DocAttributes> written = new ArrayList<>();
      try {
        for (VectorIndexFixture f : Arrays.asList(ivf, hnsw)) {
          DocAttributes a = f.corpus.attributes(f.rows() + 29);
          written.add(a);
          write(f, a, f.panel(1).get(0).vector());
        }
        MetricSnapshot s2 = observer.snapshot("after-writes");
        assertEquals("IVF assignments of one upsert", 1.0,
          value(s2, assignments) - value(s1, assignments), 0.0);
        assertTrue("HNSW changes applied", value(s2, key(HnswMetric.CHANGES_APPLIED, hi))
          - value(s1, key(HnswMetric.CHANGES_APPLIED, hi)) >= 1);
      } finally {
        for (int i = 0; i < written.size(); i++) {
          delete(Arrays.asList(ivf, hnsw).get(i), written.get(i));
        }
      }
    }
  }

  private static void write(VectorIndexFixture f, DocAttributes a, float[] vector)
    throws SQLException {
    try (Connection conn = f.ctx.connection(); RowWriter w =
      new RowWriter(conn, f.names, EnumSet.of(TableLayout.CORPUS), f.corpus.attributeModel())) {
      conn.setAutoCommit(false);
      w.upsert(a, vector);
      w.commit();
    }
  }

  private static void delete(VectorIndexFixture f, DocAttributes a) throws SQLException {
    try (Connection conn = f.ctx.connection(); RowWriter w =
      new RowWriter(conn, f.names, EnumSet.of(TableLayout.CORPUS), f.corpus.attributeModel())) {
      conn.setAutoCommit(false);
      w.delete(a);
      w.commit();
    }
  }

  /**
   * A multi-tenant IVF index queried through tenant connections returns only the rows of the
   * tenant, and full probing finds the exact top-k of the tenant. A global query of the same index
   * scans the whole index, which is exact across the tenants.
   */
  @Test
  public void test80MultiTenantIvf() throws Exception {
    VectorIndexFixture f = fixture("ivf", "L2", true);
    assertIvfBuilt(f, "L2");
    SweepPoint all = f.point("probes=all");
    List<PanelQuery> panel = f.panel(8);
    AttributeModel attrs = f.corpus.attributeModel();
    AccuracyScorer scorer = new AccuracyScorer(TIE_EPSILON);
    assertEquals(3, attrs.tenants().size());
    try (QueryExecutor exec = new QueryExecutor(f.ctx)) {
      for (int t = 0; t < attrs.tenants().size(); t++) {
        QueryCondition cond = QueryCondition.builder(QueryShape.TENANT_SCOPED).tenant(t).build();
        List<NeighborList> truth = f.truth(panel, cond, K);
        for (int i = 0; i < panel.size(); i++) {
          PanelQuery q = panel.get(i);
          QuerySpec spec = f.spec(q, cond, all);
          assertEquals(attrs.tenants().term(t), spec.tenantId());
          if (i == 0) {
            String plan = exec.explain(spec);
            assertEquals(f + " tenant " + t + ":\n" + plan,
              f.variant.expectedPlan(QueryShape.TENANT_SCOPED, true),
              PlanClassifier.classify(plan).planClass());
          }
          QueryExecutor.Result r = execute(exec, spec);
          assertEquals(spec.sql(), K, r.ordinals().length);
          assertEngineDistances(f, q, spec, r);
          for (long o : r.ordinals()) {
            assertEquals(
              f + " tenant " + t + " query " + i + " returned " + o + " of another tenant", t,
              f.corpus.attributes(o).tenant());
          }
          QueryScore s = scorer.score(r.ordinals(), f.trueDistances(q.vector(), r.ordinals()),
            truth.get(i), Double.NaN, 0);
          assertEquals(f + " tenant " + t + " query " + i + " returned "
            + Arrays.toString(r.ordinals()) + ", exact " + truth.get(i), 1.0, s.recall(), 0.0);
        }
      }
      QueryCondition global = QueryCondition.builder(QueryShape.GLOBAL).build();
      List<NeighborList> truth = f.truth(panel, global, K);
      SweepPoint dflt = f.defaultPoint();
      for (int i = 0; i < panel.size(); i++) {
        PanelQuery q = panel.get(i);
        QuerySpec spec = f.spec(q, global, dflt);
        assertNull(spec.tenantId());
        if (i == 0) {
          String plan = exec.explain(spec);
          assertEquals(f + " global:\n" + plan, f.variant.expectedPlan(QueryShape.GLOBAL, true),
            PlanClassifier.classify(plan).planClass());
        }
        QueryExecutor.Result r = execute(exec, spec);
        assertEngineDistances(f, q, spec, r);
        QueryScore s = scorer.score(r.ordinals(), f.trueDistances(q.vector(), r.ordinals()),
          truth.get(i), Double.NaN, 0);
        assertEquals(f + " global query " + i + " returned " + Arrays.toString(r.ordinals())
          + ", exact " + truth.get(i), 1.0, s.recall(), 0.0);
      }
    }
  }
}
