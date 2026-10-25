package io.phaseanalyzer;

import static org.junit.jupiter.api.Assertions.*;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

class AnalysisTest {
  @Test
  void strictPolicyUsesActualCompletionIncludingIdleTime() throws Exception {
    Sample paused =
        new Sample(1900, 50, "paused", "G", "local", true, 1, 1, 0, 0, 1, "200", false, 2100);
    Window w = new Window("hold", 1000, 2000);
    assertFalse(w.includes(paused, true));
    Path file = store(List.of(paused));
    List<Sample> loaded = new ArrayList<>();
    SampleStore.scan(file, loaded::add);
    assertEquals(2100, loaded.get(0).end());
    assertFalse(w.includes(loaded.get(0), true));
  }

  @Test
  void exportCannotOverwriteOriginal() throws Exception {
    Path input = dir.resolve("00-Steady-load-Requests.jtl");
    try (var store = new SampleStore(input)) {
      store.append(s(0, 10, true, "a"));
    }
    String original = Files.readString(input);
    assertThrows(
        IOException.class,
        () ->
            Reports.export(
                input,
                dir,
                new Analyzer.Settings(
                    List.of(new Window("Steady load", 0, 1000)),
                    "All",
                    "Requests",
                    false,
                    500,
                    2000),
                Map.of(),
                null));
    assertEquals(original, Files.readString(input));
  }

  @TempDir Path dir;

  Sample s(long start, long elapsed, boolean ok, String label) {
    return new Sample(
        start, elapsed, label, "G", "local", ok, 1024, 512, 10, 5, 2, ok ? "200" : "500", false);
  }

  Path store(List<Sample> samples) throws Exception {
    Path p = dir.resolve(UUID.randomUUID() + ".jtl");
    try (var store = new SampleStore(p)) {
      for (var s : samples) store.append(s);
    }
    return p;
  }

  Map<String, Object> total(Path p, Window w, boolean strict) throws Exception {
    var rows =
        Analyzer.analyze(p, new Analyzer.Settings(List.of(w), "All", "Requests", strict, 200, 400))
            .views()
            .get(0)
            .rows();
    return rows.get(rows.size() - 1);
  }

  @Test
  void independentReferenceDataset() throws Exception {
    // Independently specified arithmetic: sum=1500, mean=300, variance=20000, nearest-rank p50=300.
    Path p =
        store(
            List.of(
                s(0, 100, true, "A"),
                s(1000, 200, true, "A"),
                s(2000, 300, false, "B"),
                s(3000, 400, true, "B"),
                s(4000, 500, true, "B")));
    Map<String, Object> m = total(p, new Window("Steady load", 0, 10000), false);
    assertEquals(5L, m.get("samples"));
    assertEquals(300.0, (Double) m.get("averageMs"), 1e-12);
    assertEquals(Math.sqrt(20000), (Double) m.get("stddevMs"), 1e-10);
    assertEquals(300L, m.get("medianMs"));
    for (String key : List.of("p90Ms", "p95Ms", "p99Ms")) assertEquals(500L, m.get(key));
    assertEquals(20.0, m.get("errorPercent"));
    assertEquals(.5, m.get("samplesPerSecond"));
    assertEquals(.5, m.get("receivedKBPerSecond"));
    assertEquals(.25, m.get("sentKBPerSecond"));
    assertEquals(.5, m.get("apdex"));
    assertEquals(Map.of("200", 4L, "500", 1L), m.get("responseCodes"));
  }

  @Test
  void boundaryRulesAndIntersection() {
    Window w = new Window("hold", 1000, 2000);
    assertTrue(w.includes(s(1000, 200, true, "a"), false));
    assertFalse(w.includes(s(2000, 0, true, "a"), false));
    assertTrue(w.includes(s(1900, 200, true, "a"), false));
    assertFalse(w.includes(s(1900, 200, true, "a"), true));
    assertTrue(w.includes(s(1900, 100, true, "a"), true));
    assertEquals(
        new Window("Steady load", 1500, 2000),
        Window.intersection(w, new Window("hold", 1500, 3000)));
    assertEquals(3000, Window.intersection(w, new Window("hold", 3000, 4000)).end());
  }

  @Test
  void emptyAndSingleAndFailure() throws Exception {
    Map<String, Object> e = total(store(List.of()), new Window("empty", 0, 0), false);
    assertNull(e.get("averageMs"));
    assertNull(e.get("samplesPerSecond"));
    Map<String, Object> m =
        total(store(List.of(s(0, 123, false, "a"))), new Window("all", 0, 1000), false);
    assertEquals(0.0, m.get("stddevMs"));
    assertEquals(123L, m.get("p99Ms"));
    assertEquals(100.0, m.get("errorPercent"));
    assertEquals(0.0, m.get("apdex"));
  }

  @Test
  void csvQuotedMultilineAndCorruption() throws Exception {
    String label = "a,\"b\"\nnext";
    Path p = store(List.of(s(0, 100, true, label)));
    List<Sample> list = new ArrayList<>();
    SampleStore.scan(p, list::add);
    assertEquals(label, list.get(0).label());
    Path bad = dir.resolve("bad.jtl");
    Files.writeString(bad, "timeStamp,elapsed,label,success\n1,10,\"bad,true");
    assertThrows(IOException.class, () -> SampleStore.scan(bad, x -> {}));
  }

  @Test
  void missingFieldsAreUnavailable() throws Exception {
    Path p = dir.resolve("minimal.jtl");
    Files.writeString(p, "timeStamp,elapsed,label,success\n1000,10,a,true\n");
    Map<String, Object> m = total(p, new Window("all", 0, 2000), false);
    assertNull(m.get("receivedBytes"));
    assertNull(m.get("averageConnectMs"));
  }

  @Test
  void externalSortBeyondMemoryChunk() throws Exception {
    try (var q = new ExactQuantiles(dir)) {
      for (int i = 150000; i > 0; i--) q.add(i);
      assertArrayEquals(new long[] {75000, 135000, 142500, 148500}, q.finish());
    }
  }

  @Test
  void htmlEscapesScriptInjectionAndFilteredCounts() throws Exception {
    Path p =
        store(
            List.of(
                s(0, 100, true, "</script><script>alert(1)</script>"), s(1000, 100, true, "b")));
    Path out = dir.resolve("report");
    Reports.export(
        p,
        out,
        new Analyzer.Settings(
            List.of(new Window("Steady load", 0, 500), new Window("Complete execution", 0, 2000)),
            "All",
            "Requests",
            false,
            500,
            2000),
        Map.of("warnings", List.of()),
        null);
    String html = Files.readString(out.resolve("report.html"));
    assertFalse(html.contains("<script>alert(1)"));
    assertTrue(html.contains("\\u003c/script>"));
    long[] count = {0};
    SampleStore.scan(out.resolve("00-Steady-load-Requests.jtl"), s -> count[0]++);
    assertEquals(1, count[0]);
    assertTrue(Reports.mapper().readTree(out.resolve("report.json").toFile()).has("analysis"));
  }

  @Test
  void manualDurationsAndObservedCandidates() {
    assertThrows(IllegalArgumentException.class, () -> Schedule.manual(0, -1, 0, 0, 1));
    var schedule = Schedule.manual(1000, 2000, 3000, 1000, 10);
    assertEquals(5.0, schedule.expected(2000));
    assertEquals(10.0, schedule.expected(4000));
    assertEquals(5.0, schedule.expected(6500));
    assertEquals(new Window("Steady load", 3000, 6000), schedule.windows(0).get(1));
    List<Timeline.Point> p =
        List.of(
            new Timeline.Point(0, 10, 0, null, null, null, 10.0),
            new Timeline.Point(10000, 10, 0, null, null, null, 10.0),
            new Timeline.Point(20000, 10, 0, null, null, null, null));
    var d = Timeline.detect(p, 10, 5, 15);
    assertEquals(1, d.candidates().size());
    assertEquals(20, d.withinToleranceSeconds());
  }

  @Test
  void transactionViewsStaySeparate() throws Exception {
    Sample parent = new Sample(0, 500, "T", "G", "local", true, 2048, 1024, 0, 0, 1, "200", true);
    Path p = store(List.of(parent, s(0, 100, true, "child")));
    var r =
        Analyzer.analyze(
            p,
            new Analyzer.Settings(
                List.of(new Window("all", 0, 1000)), "All", "Separate", false, 500, 2000));
    assertEquals(2, r.views().size());
    assertEquals(1, r.views().get(0).included());
    assertEquals(1, r.views().get(1).included());
  }
}
