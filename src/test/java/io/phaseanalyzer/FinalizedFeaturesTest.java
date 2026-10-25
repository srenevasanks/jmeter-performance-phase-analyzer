package io.phaseanalyzer;

import static org.junit.jupiter.api.Assertions.*;

import com.blazemeter.jmeter.threads.arrivals.*;
import com.blazemeter.jmeter.threads.concurrency.ConcurrencyThreadGroup;
import java.nio.file.*;
import java.util.*;
import java.util.zip.*;
import javax.xml.parsers.*;
import org.apache.jmeter.testelement.TestPlan;
import org.apache.jmeter.testelement.property.CollectionProperty;
import org.apache.jmeter.util.JMeterUtils;
import org.apache.jorphan.collections.ListedHashTree;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

class FinalizedFeaturesTest {
  @Test
  void partialSnapshotsUseACollectionBarrierAndDoNotInventFutureMetrics() throws Exception {
    RunSession run = new RunSession(dir);
    try {
      PhaseAnalyzerListener c = new PhaseAnalyzerListener();
      c.setProperty("phase.automatic", false);
      c.setProperty("phase.up", 3600L);
      c.setProperty("phase.hold", 3600L);
      for (int i = 0; i < 100; i++)
        run.accept(
            new Sample(
                run.start, 0, "batch", "G", "local", i % 2 == 0, 10, 5, 0, 0, 1, "200", false));
      ReportSnapshot s = ReportSnapshot.build(c, run);
      assertEquals("Partial Report", s.metadata().get("executionStatus"));
      assertEquals(
          100, ((Number) s.phase("Complete execution").metrics().get("samples")).longValue());
      assertEquals(
          50, ((Number) s.phase("Complete execution").metrics().get("failed")).longValue());
      assertEquals("Not started", s.phase("Steady load").status());
      assertTrue(s.phase("Steady load").metrics().isEmpty());
      Path folder = dir.resolve("partial");
      Reports.exportSnapshot(s, folder);
      assertTrue(Files.readString(folder.resolve("report.html")).contains("Partial Report"));
    } finally {
      run.stopAccepting("Stopped");
      run.finish();
    }
  }

  @Test
  void delayedConfiguredGroupsAreIncludedBeforeTheirFirstRuntimeSample() throws Exception {
    ListedHashTree tree = new ListedHashTree();
    var a = group("A");
    var b = group("B");
    b.setDelay(60);
    tree.add(a);
    tree.add(b);
    var catalog = GroupDiscovery.scan(tree);
    RunSession run = new RunSession(dir);
    try {
      run.prime(catalog, true);
      PhaseAnalyzerListener c = new PhaseAnalyzerListener();
      c.setProperty("phase.catalog", Reports.mapper().writeValueAsString(catalog));
      run.accept(
          new Sample(
              run.start,
              0,
              "first",
              GroupDiscovery.key(a),
              "local",
              true,
              1,
              1,
              0,
              0,
              1,
              "200",
              false));
      ReportSnapshot s = ReportSnapshot.build(c, run);
      assertEquals(2, run.groups.size());
      assertEquals("Unavailable", s.phase("Steady load").status());
      assertTrue(s.phase("Steady load").metrics().isEmpty());
      assertTrue(s.phase("Complete execution").configuredSeconds() >= 63);
    } finally {
      run.stopAccepting("Stopped");
      run.finish();
    }
  }

  @TempDir Path dir;

  @BeforeAll
  static void init() {
    JMeterUtils.loadJMeterProperties("src/test/resources/jmeter-test.properties");
  }

  private org.apache.jmeter.threads.ThreadGroup group(String name) {
    var g = new org.apache.jmeter.threads.ThreadGroup();
    g.setName(name);
    g.setEnabled(true);
    g.setNumThreads(2);
    g.setRampUp(1);
    g.setDuration(3);
    g.setScheduler(true);
    return g;
  }

  @Test
  void discoveryOnlyActualGroupsAndDuplicateIdentitySurvivesRename() {
    ListedHashTree tree = new ListedHashTree();
    var root = tree.add(new TestPlan("Plan"));
    var a = group("Same");
    var b = group("Same");
    var disabled = group("Disabled");
    disabled.setEnabled(false);
    root.add(a);
    root.add(b);
    root.add(disabled);
    var first = GroupDiscovery.scan(tree);
    assertEquals(3, first.size());
    assertNotEquals(first.get(0).key(), first.get(1).key());
    assertNotEquals(first.get(0).label(), first.get(1).label());
    assertFalse(first.get(2).enabled());
    a.setName("Renamed");
    var second = GroupDiscovery.scan(tree);
    assertEquals(first.get(0).key(), second.get(0).key());
    assertEquals("Renamed", second.get(0).name());
    root.remove(b);
    assertEquals(2, GroupDiscovery.scan(tree).size());
  }

  @Test
  void clonedGroupGetsNewDiscoveryIdentity() {
    var a = group("copy");
    ListedHashTree tree = new ListedHashTree();
    tree.add(a);
    GroupDiscovery.scan(tree);
    var copy = (org.apache.jmeter.threads.ThreadGroup) a.clone();
    tree.add(copy);
    var entries = GroupDiscovery.scan(tree);
    assertNotEquals(entries.get(0).key(), entries.get(1).key());
  }

  @Test
  void disabledParentExcludesNestedGroup() {
    var plan = new TestPlan("disabled");
    plan.setEnabled(false);
    ListedHashTree tree = new ListedHashTree();
    tree.add(plan).add(group("Child"));
    assertFalse(GroupDiscovery.scan(tree).get(0).enabled());
  }

  @Test
  void concurrencyConfigurationReader() throws Exception {
    ConcurrencyThreadGroup g = new ConcurrencyThreadGroup();
    g.setUnit("S");
    g.setTargetLevel("4");
    g.setRampUp("2");
    g.setHold("3");
    g.setSteps("2");
    Schedule s = ScheduleAdapters.read(g);
    assertEquals("users", s.loadUnit());
    assertEquals(2, s.expected(500));
    assertEquals(4, s.expected(1500));
    assertEquals(
        List.of(new Window("Ramp-up", 0, 2000), new Window("Steady load", 2000, 5000)),
        s.windows(0));
    assertFalse(s.windows(0).stream().anyMatch(w -> w.phase().equals("Ramp-down")));
  }

  @Test
  void arrivalsAndUnitsAreNotConcurrency() throws Exception {
    ArrivalsThreadGroup g = new ArrivalsThreadGroup();
    g.setUnit("M");
    g.setTargetLevel("120");
    g.setRampUp("1");
    g.setHold("2");
    g.setSteps("0");
    Schedule s = ScheduleAdapters.read(g);
    assertEquals("arrivals/sec", s.loadUnit());
    assertEquals(2, s.expected(90000));
    assertEquals(new Window("Steady load", 60000, 180000), s.windows(0).get(1));
  }

  @Test
  void freeFormRowsAndFractionalGuard() throws Exception {
    FreeFormArrivalsThreadGroup g = new FreeFormArrivalsThreadGroup();
    g.setUnit("S");
    CollectionProperty rows =
        new CollectionProperty(FreeFormArrivalsThreadGroup.SCHEDULE, new ArrayList<>());
    rows.addItem(new CollectionProperty("one", List.of("0", "5", "2")));
    rows.addItem(new CollectionProperty("two", List.of("5", "5", "3")));
    g.setProperty(rows);
    Schedule s = ScheduleAdapters.read(g);
    assertEquals(5, s.expected(3000));
    assertEquals(new Window("Steady load", 2000, 5000), s.windows(0).get(1));
  }

  @Test
  void openModelUsesNativeParserAndPause() throws Exception {
    var g = new org.apache.jmeter.threads.openmodel.OpenModelThreadGroup();
    g.setScheduleString(
        "rate(0) random_arrivals(2 sec) rate(4/sec) random_arrivals(3 sec) rate(4/sec)"
            + " random_arrivals(1 sec) rate(0) pause(1 sec)");
    Schedule s = ScheduleAdapters.read(g);
    assertEquals("arrivals/sec", s.loadUnit());
    assertEquals(7000, s.end());
    assertEquals(4, s.expected(3000));
    assertEquals(0, s.expected(6500));
    assertEquals(new Window("Steady load", 2000, 5000), s.windows(0).get(1));
    g.setScheduleString("not_a_valid_schedule");
    assertThrows(Exception.class, () -> ScheduleAdapters.read(g));
  }

  @Test
  void setupAndTeardownNeedRealScheduler() throws Exception {
    for (var g :
        List.of(
            new org.apache.jmeter.threads.SetupThreadGroup(),
            new org.apache.jmeter.threads.PostThreadGroup())) {
      g.setNumThreads(1);
      g.setScheduler(true);
      g.setDuration(2);
      g.setRampUp(0);
      assertTrue(
          ScheduleAdapters.read(g).windows(0).stream()
              .anyMatch(w -> w.phase().equals("Steady load")));
      g.setScheduler(false);
      assertThrows(IllegalArgumentException.class, () -> ScheduleAdapters.read(g));
    }
  }

  @Test
  void manualWindowsDoNotInventLoad() {
    Schedule s = Schedule.phases(0, 1000, 2000, 0);
    assertTrue(Double.isNaN(s.expected(1500)));
    assertEquals("unavailable", s.loadUnit());
    assertThrows(IllegalArgumentException.class, () -> Schedule.phases(0, -1, 0, 0));
  }

  @Test
  void disjointWindowMetricsCountOnceAndUseUnionDenominator() throws Exception {
    Path file = dir.resolve("union.jtl");
    try (var store = new SampleStore(file)) {
      store.append(sample(1000, 100, true));
      store.append(sample(3000, 300, false));
      store.append(sample(5000, 500, true));
    }
    var settings = new Analyzer.Settings(List.of(), "All", "Requests", false, 500, 2000);
    var result =
        Analyzer.ranges(
            file,
            settings,
            Map.of(
                "Steady load",
                List.of(
                    new Window("Steady load", 0, 2000),
                    new Window("Steady load", 1000, 2000),
                    new Window("Steady load", 4000, 6000))));
    var v = result.views().get(0);
    assertEquals(2, v.included());
    assertEquals(4, v.durationSeconds());
    assertEquals(.5, v.rows().get(v.rows().size() - 1).get("samplesPerSecond"));
    assertEquals(300.0, v.rows().get(v.rows().size() - 1).get("averageMs"));
  }

  private Sample sample(long start, long elapsed, boolean ok) {
    return new Sample(
        start,
        elapsed,
        "Label",
        "G",
        "local",
        ok,
        1024,
        512,
        10,
        5,
        1,
        ok ? "200" : "assertion-failed",
        false);
  }

  private ReportSnapshot snapshot() throws Exception {
    RunSession run = new RunSession(dir);
    PhaseAnalyzerListener config = new PhaseAnalyzerListener();
    config.setProperty("phase.automatic", false);
    config.setProperty("phase.up", 0L);
    config.setProperty("phase.hold", 2L);
    config.setProperty("phase.down", 0L);
    config.setProperty("phase.testPlan", "Report fixture");
    long t = run.start;
    run.store.append(sample(t, 100, true));
    run.store.append(sample(t + 1, 300, false));
    run.stopAccepting("Stopped");
    run.finish();
    run.end = t + 1000;
    return ReportSnapshot.build(config, run);
  }

  @Test
  void sevenSheetXlsxAndHtmlShareMetricsAndSnapshot() throws Exception {
    ReportSnapshot s = snapshot();
    Path html = dir.resolve("report.html"), xlsx = dir.resolve("report.xlsx");
    Reports.writeHtml(s, html);
    ExcelReport.write(s, xlsx);
    String text = Files.readString(html);
    assertTrue(text.contains(s.snapshotId()));
    assertTrue(text.contains("9. Execution Configuration"));
    assertTrue(text.contains("8. Response Codes Summary"));
    assertTrue(text.contains("\"averageMs\":200.0"));
    DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
    factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
    factory.setNamespaceAware(true);
    try (ZipFile zip = new ZipFile(xlsx.toFile())) {
      String workbook =
          new String(
              zip.getInputStream(zip.getEntry("xl/workbook.xml")).readAllBytes(),
              java.nio.charset.StandardCharsets.UTF_8);
      for (String name : ExcelReport.SHEETS) assertTrue(workbook.contains(name));
      for (var e : Collections.list(zip.entries()))
        if (e.getName().endsWith(".xml")) factory.newDocumentBuilder().parse(zip.getInputStream(e));
      String complete =
          new String(
              zip.getInputStream(zip.getEntry("xl/worksheets/sheet5.xml")).readAllBytes(),
              java.nio.charset.StandardCharsets.UTF_8);
      assertTrue(complete.contains("<v>200.0</v>"));
      assertFalse(complete.contains(s.snapshotId()));
      assertFalse(complete.contains("<f>"));
    }
    assertEquals("Not configured", s.phase("Ramp-down").status());
    assertEquals("Stopped", s.phase("Complete execution").status());
  }

  @Test
  void missingExportDirectoryAndFailureHandling() throws Exception {
    ReportSnapshot s = snapshot();
    Path blocker = dir.resolve("not-directory");
    Files.writeString(blocker, "data");
    assertThrows(
        java.io.IOException.class, () -> ExcelReport.write(s, blocker.resolve("report.xlsx")));
    assertThrows(
        java.io.IOException.class, () -> Reports.writeHtml(s, blocker.resolve("report.html")));
  }
}
