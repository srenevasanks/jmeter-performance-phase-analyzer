package io.phaseanalyzer;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import kg.apc.jmeter.threads.*;
import org.apache.jmeter.control.*;
import org.apache.jmeter.engine.StandardJMeterEngine;
import org.apache.jmeter.samplers.*;
import org.apache.jmeter.testelement.TestPlan;
import org.apache.jmeter.testelement.property.CollectionProperty;
import org.apache.jmeter.threads.AbstractThreadGroup;
import org.apache.jmeter.util.JMeterUtils;
import org.apache.jorphan.collections.ListedHashTree;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

class JMeterIntegrationTest {
  @Test
  void modernConcurrencyRealExecution() throws Exception {
    var g = new com.blazemeter.jmeter.threads.concurrency.ConcurrencyThreadGroup();
    g.setName("Concurrency");
    g.setUnit("S");
    g.setTargetLevel("2");
    g.setRampUp("1");
    g.setHold("2");
    g.setSteps("2");
    execute(List.of(g), false, false);
  }

  @Test
  void modernArrivalsRealExecution() throws Exception {
    var g = new com.blazemeter.jmeter.threads.arrivals.ArrivalsThreadGroup();
    g.setName("Arrivals");
    g.setUnit("S");
    g.setTargetLevel("4");
    g.setRampUp("1");
    g.setHold("2");
    g.setSteps("2");
    g.setConcurrencyLimit("10");
    execute(List.of(g), false, false);
  }

  @Test
  void modernFreeFormRealExecution() throws Exception {
    var g = new com.blazemeter.jmeter.threads.arrivals.FreeFormArrivalsThreadGroup();
    g.setName("FreeForm");
    g.setUnit("S");
    g.setConcurrencyLimit("10");
    var rows =
        new CollectionProperty(
            com.blazemeter.jmeter.threads.arrivals.FreeFormArrivalsThreadGroup.SCHEDULE,
            new ArrayList<>());
    rows.addItem(new CollectionProperty("row", List.of("3", "3", "3")));
    g.setProperty(rows);
    execute(List.of(g), false, false);
  }

  @Test
  void modernOpenModelRealExecution() throws Exception {
    var g = new org.apache.jmeter.threads.openmodel.OpenModelThreadGroup();
    g.setName("OpenModel");
    g.setScheduleString("rate(5/sec) random_arrivals(3 sec) rate(5/sec) pause(1 sec)");
    g.setRandomSeedString("12345");
    execute(List.of(g), false, false);
  }

  @Test
  void http200CanFailAnAssertion() throws Exception {
    ListedHashTree tree = new ListedHashTree();
    var root = tree.add(new TestPlan("Assertions"));
    PhaseAnalyzerListener listener = new PhaseAnalyzerListener();
    listener.setProperty("phase.output", dir.toString());
    root.add(listener);
    var sampler = new RealSampler();
    sampler.setName("HTTP-code-200 assertion failure");
    var assertion = new org.apache.jmeter.assertions.ResponseAssertion();
    assertion.setTestFieldResponseCode();
    assertion.setToEqualsType();
    assertion.addTestString("500");
    root.add(standard("G", 1, 0)).add(sampler).add(assertion);
    StandardJMeterEngine engine = new StandardJMeterEngine();
    engine.configure(tree);
    engine.runTest();
    assertTimeoutPreemptively(
        Duration.ofSeconds(10),
        () -> {
          while (listener.session() == null
              || !listener.session().isFinalized())
            Thread.sleep(30);
        });
    long[] count = {0};
    SampleStore.scan(
        listener.session().store.path,
        s -> {
          assertEquals("200", s.code());
          assertFalse(s.success());
          count[0]++;
        });
    assertTrue(count[0] > 0);
  }

  public static class NativeCounter extends org.apache.jmeter.testelement.AbstractTestElement
      implements SampleListener, org.apache.jmeter.engine.util.NoThreadClone {
    final java.util.concurrent.atomic.AtomicLong count =
        new java.util.concurrent.atomic.AtomicLong();

    public void sampleOccurred(SampleEvent event) {
      count.incrementAndGet();
    }

    public void sampleStarted(SampleEvent e) {}

    public void sampleStopped(SampleEvent e) {}
  }

  @Test
  void stopCaptureOnlyAndRestartDoNotStopOrMixJMeterExecution() throws Exception {
    TestPlan plan = new TestPlan("Capture isolation");
    ListedHashTree tree = new ListedHashTree();
    var root = tree.add(plan);
    PhaseAnalyzerListener listener = new PhaseAnalyzerListener();
    listener.setProperty("phase.output", dir.toString());
    listener.setProperty("phase.autoExport", false);
    listener.setProperty("phase.automatic", false);
    listener.setProperty("phase.hold", 8L);
    NativeCounter nativeCounter = new NativeCounter();
    root.add(nativeCounter);
    root.add(listener);
    var g = standard("G", 1, 0);
    g.setRampUp(0);
    g.setDuration(6);
    RealSampler sampler = new RealSampler();
    sampler.setName("Request");
    root.add(g).add(sampler);
    StandardJMeterEngine engine = new StandardJMeterEngine();
    engine.configure(tree);
    engine.runTest();
    assertTimeoutPreemptively(
        Duration.ofSeconds(5),
        () -> {
          while (listener.session() == null || listener.session().store.count < 10)
            Thread.sleep(20);
        });
    PhaseAnalyzerListener guiCopy = (PhaseAnalyzerListener) listener.clone();
    assertTrue(guiCopy.testRunning(), "GUI copy must see the running listener");
    assertNotNull(guiCopy.observedUsers("All"));
    RunSession first = guiCopy.stopCapture().get(5, java.util.concurrent.TimeUnit.SECONDS);
    long frozen = first.store.count, nativeBefore = nativeCounter.count.get();
    assertEquals("Stopped", first.captureStatus);
    Thread.sleep(250);
    assertTrue(engine.isActive(), "Stop Capture must not stop JMeter");
    assertTrue(nativeCounter.count.get() > nativeBefore, "JMeter must continue generating samples");
    assertEquals(frozen, first.store.count, "Old capture must remain frozen");
    ReportSnapshot stopped = ReportSnapshot.build(listener, first);
    Reports.exportSnapshot(stopped, first.directory.resolve("stopped-report"));
    assertTrue(Files.exists(first.directory.resolve("stopped-report/report.xlsx")));
    assertEquals("Stopped", stopped.metadata().get("executionStatus"));
    RunSession second = guiCopy.startCapture();
    assertNotEquals(first.directory, second.directory);
    assertEquals(first.testStart, second.testStart);
    assertTimeoutPreemptively(
        Duration.ofSeconds(5),
        () -> {
          while (second.store.count < 8) Thread.sleep(20);
        });
    assertEquals(frozen, first.store.count);
    assertTrue(engine.isActive());
    listener.stopCapture().get(5, java.util.concurrent.TimeUnit.SECONDS);
    Set<String> oldSamples = new HashSet<>();
    SampleStore.scan(first.store.path, s -> oldSamples.add(s.start() + "/" + s.elapsed()));
    SampleStore.scan(
        second.store.path,
        s ->
            assertFalse(
                oldSamples.contains(s.start() + "/" + s.elapsed()),
                "New capture must not replay old records"));
    assertTimeoutPreemptively(
        Duration.ofSeconds(10),
        () -> {
          while (engine.isActive()) Thread.sleep(25);
        });
    assertFalse(guiCopy.testRunning(), "GUI copies must not retain a stale running flag after test end");
    assertEquals("Stopped", second.captureStatus);
    assertTrue(nativeCounter.count.get() > first.store.count + second.store.count);
  }

  @TempDir Path dir;

  @BeforeAll
  static void init() {
    JMeterUtils.loadJMeterProperties("src/test/resources/jmeter-test.properties");
    JMeterUtils.setLocale(Locale.ENGLISH);
  }

  public static class RealSampler extends AbstractSampler {
    private static final long serialVersionUID = 1L;

    @Override
    public SampleResult sample(Entry entry) {
      SampleResult r = new SampleResult();
      r.setSampleLabel(getName());
      r.sampleStart();
      try {
        Thread.sleep(20);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
      r.setSuccessful(true);
      r.setResponseCode("200");
      r.setBytes(1024);
      r.setSentBytes(512);
      r.sampleEnd();
      return r;
    }
  }

  org.apache.jmeter.threads.ThreadGroup standard(String name, int users, int delay) {
    var g = new org.apache.jmeter.threads.ThreadGroup();
    g.setName(name);
    g.setNumThreads(users);
    g.setRampUp(1);
    g.setScheduler(true);
    g.setDuration(3);
    g.setDelay(delay);
    LoopController l = new LoopController();
    l.setLoops(-1);
    l.setContinueForever(true);
    g.setSamplerController(l);
    return g;
  }

  RunSession execute(List<AbstractThreadGroup> groups, boolean parent, boolean interrupt)
      throws Exception {
    TestPlan plan = new TestPlan("Integration");
    ListedHashTree tree = new ListedHashTree();
    var planTree = tree.add(plan);
    PhaseAnalyzerListener listener = new PhaseAnalyzerListener();
    listener.setName("Performance Phase Analyzer");
    listener.setProperty("phase.output", dir.toString());
    listener.setProperty("phase.mode", parent ? "Separate" : "Requests");
    planTree.add(listener);
    for (AbstractThreadGroup g : groups) {
      var groupTree = planTree.add(g);
      RealSampler s = new RealSampler();
      s.setName("Request");
      if (parent) {
        TransactionController transaction = new TransactionController();
        transaction.setName("Business transaction");
        transaction.setGenerateParentSample(true);
        groupTree.add(transaction).add(s);
      } else groupTree.add(s);
    }
    StandardJMeterEngine engine = new StandardJMeterEngine();
    engine.configure(tree);
    engine.runTest();
    if (interrupt) {
      Thread.sleep(700);
      engine.stopTest(true);
    }
    assertTimeoutPreemptively(
        Duration.ofSeconds(15),
        () -> {
          while (listener.session() == null
              || listener.session().end == 0
              || !listener.session().isFinalized())
            Thread.sleep(50);
        });
    RunSession run = listener.session();
    Reports.exportSnapshot(ReportSnapshot.build(listener, run), run.directory);
    assertTrue(run.store.count > 0);
    assertEquals(0, run.dropped.get());
    assertEquals(
        groups.size(),
        run.groups.size(),
        "Runtime ThreadListener group identity must be available");
    assertTrue(Files.size(run.directory.resolve("load.csv")) > 50);
    assertTrue(Files.exists(run.directory.resolve("report.html")));
    return run;
  }

  @Test
  void standardRealExecutionAndRepeatedRun() throws Exception {
    RunSession first = execute(List.of(standard("G", 2, 0)), false, false);
    assertNotNull(first.groups.get("G").schedule());
    assertTrue(first.store.count > 20);
    RunSession second = execute(List.of(standard("G", 1, 0)), false, false);
    assertNotEquals(first.directory, second.directory);
  }

  @Test
  void ultimateRealExecution() throws Exception {
    UltimateThreadGroup g = new UltimateThreadGroup();
    g.setName("Ultimate");
    CollectionProperty rows = new CollectionProperty("ultimatethreadgroupdata", new ArrayList<>());
    rows.addItem(new CollectionProperty("row", List.of("2", "0", "1", "1", "1")));
    g.setData(rows);
    LoopController l = new LoopController();
    l.setLoops(-1);
    l.setContinueForever(true);
    g.setSamplerController(l);
    RunSession r = execute(List.of(g), false, false);
    assertEquals(
        "Ultimate 3.1.1 discrete combined rows", r.groups.get("Ultimate").schedule().method());
  }

  @Test
  void steppingRealExecution() throws Exception {
    SteppingThreadGroup g = new SteppingThreadGroup();
    g.setName("Stepping");
    g.setNumThreads("2");
    g.setThreadGroupDelay("0");
    g.setInUserCount("1");
    g.setInUserCountBurst("1");
    g.setInUserPeriod("0");
    g.setRampUp("1");
    g.setFlightTime("1");
    g.setOutUserCount("1");
    g.setOutUserPeriod("1");
    LoopController l = new LoopController();
    l.setLoops(-1);
    l.setContinueForever(true);
    g.setSamplerController(l);
    execute(List.of(g), false, false);
  }

  @Test
  void multipleGroupsUseCommonIntersection() throws Exception {
    RunSession r = execute(List.of(standard("A", 1, 0), standard("B", 1, 1)), false, false);
    var windows = r.windows("All", 0, 0, 0, 0, true, null, null);
    assertTrue(windows.stream().anyMatch(w -> w.phase().equals("Steady load")));
    assertTrue(
        windows.stream()
            .filter(w -> w.phase().equals("Steady load"))
            .allMatch(w -> w.end() >= w.start()));
  }

  @Test
  void parentTransactionsDoNotDoubleCountRequests() throws Exception {
    RunSession r = execute(List.of(standard("G", 1, 0)), true, false);
    long[] counts = {0, 0};
    Set<String> requests = new HashSet<>();
    SampleStore.scan(
        r.store.path,
        s -> {
          counts[s.transaction() ? 1 : 0]++;
          if (!s.transaction())
            assertEquals("Request", s.label(), "A stopped parent must not enter the request view");
          if (!s.transaction())
            assertTrue(
                requests.add(s.start() + "/" + s.label() + "/" + s.elapsed()),
                "Duplicate request record");
        });
    assertTrue(
        Math.abs(counts[0] - counts[1]) <= 1,
        "Scheduler may stop one final transaction before its parent is emitted");
    assertTrue(counts[0] > 0);
  }

  @Test
  void interruptedExecutionClipsEffectiveWindow() throws Exception {
    RunSession r = execute(List.of(standard("G", 1, 0)), false, true);
    assertTrue(r.end - r.start < 2500);
    var w = r.windows("G", 0, 0, 0, 0, true, null, null);
    assertTrue(w.stream().allMatch(x -> x.end() <= r.end));
  }
}
