package io.phaseanalyzer;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.*;
import java.util.*;
import java.util.zip.*;
import javax.xml.parsers.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.w3c.dom.*;

class ExcelReportTest {
  @TempDir Path dir;
  private final long start = java.time.Instant.parse("2026-01-02T03:04:05Z").toEpochMilli();
  private final String uuid = "12345678-1234-1234-1234-123456789abc";

  private ReportSnapshot fixture(boolean empty, boolean missing) throws Exception {
    Path data = dir.resolve(UUID.randomUUID() + ".jtl");
    try (var store = new SampleStore(data)) {
      if (!empty)
        for (int i = 0; i < 5; i++)
          store.append(
              new Sample(
                  start + i * 1000,
                  100 + i * 100,
                  "API " + "long label ".repeat(12),
                  "Scope",
                  "local",
                  i != 0,
                  1024,
                  512,
                  0,
                  0,
                  2,
                  i == 0 ? "CUSTOM_FAILURE" : "CUSTOM_OK",
                  false,
                  start + i * 1000 + 100 + i * 100));
    }
    List<Window> complete = List.of(new Window("Complete execution", start, start + 5000));
    var windows = new LinkedHashMap<String, List<Window>>();
    windows.put(
        "Ramp-up", missing ? List.of() : List.of(new Window("Ramp-up", start, start + 2000)));
    windows.put(
        "Steady load",
        missing ? List.of() : List.of(new Window("Steady load", start + 2000, start + 4000)));
    windows.put(
        "Ramp-down",
        missing ? List.of() : List.of(new Window("Ramp-down", start + 4000, start + 6000)));
    windows.put("Complete execution", complete);
    var settings = new Analyzer.Settings(complete, "All", "Requests", false, 500, 2000);
    var result = Analyzer.ranges(data, settings, windows);
    List<ReportSnapshot.Phase> phases = new ArrayList<>();
    for (String name : ReportSnapshot.PHASES) {
      var view =
          result.views().stream()
              .filter(v -> v.window().phase().equals(name))
              .findFirst()
              .orElse(null);
      var metric =
          view == null ? Map.<String, Object>of() : view.rows().get(view.rows().size() - 1);
      List<Window> configured = windows.get(name);
      List<Window> effective =
          configured.stream()
              .map(w -> new Window(w.phase(), w.start(), Math.min(start + 5000, w.end())))
              .toList();
      phases.add(
          new ReportSnapshot.Phase(
              name,
              configured.isEmpty()
                  ? "Unavailable"
                  : name.equals("Ramp-down") ? "Partially captured" : "Completed",
              configured.isEmpty() ? null : name.equals("Complete execution") ? 5.0 : 2.0,
              view == null ? 0 : view.durationSeconds(),
              metric,
              configured,
              effective,
              configured.isEmpty() ? "Schedule not available" : ""));
    }
    var m = new LinkedHashMap<String, Object>();
    m.put("jmxFilename", "Actual Plan.jmx");
    m.put("executionStart", start);
    m.put("executionEnd", start + 5000);
    m.put("reportTimeZone", "Asia/Kolkata");
    m.put("executionStatus", "Completed");
    m.put("captureStatus", "Completed");
    m.put("detectionMode", "Automatic");
    m.put("environment", Map.of("jmeter", "5.6.3", "java", "17", "os", "Windows"));
    var groups =
        List.of(
            new RunSession.Group(
                "tg:" + uuid,
                "org.apache.jmeter.threads.ThreadGroup",
                start,
                Schedule.manual(0, 2000, 2000, 2000, 2),
                ""));
    m.put("groups", groups);
    m.put(
        "threadGroups",
        List.of(
            new GroupDiscovery.Entry(
                "tg:" + uuid,
                "Long Group " + "name ".repeat(20),
                "Label",
                "org.apache.jmeter.threads.ThreadGroup",
                true,
                groups.get(0).schedule(),
                "",
                Map.of("internalDebugObject", "{raw=secret}"))));
    m.put("schemaVersion", "DO_NOT_DISPLAY");
    m.put("debugOnly", "DO_NOT_DISPLAY");
    return new ReportSnapshot("1.1", uuid, m, settings, result, List.of(), List.of(), phases, 0);
  }

  private Document xml(ZipFile zip, String path) throws Exception {
    var f = DocumentBuilderFactory.newInstance();
    f.setNamespaceAware(true);
    f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
    return f.newDocumentBuilder().parse(zip.getInputStream(zip.getEntry(path)));
  }

  private Element cell(Document doc, String ref) {
    var cells = doc.getElementsByTagNameNS("*", "c");
    for (int i = 0; i < cells.getLength(); i++) {
      var e = (Element) cells.item(i);
      if (e.getAttribute("r").equals(ref)) return e;
    }
    throw new AssertionError(ref);
  }

  private String text(ZipFile z, String path) throws Exception {
    return new String(
        z.getInputStream(z.getEntry(path)).readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
  }

  @Test
  void structuredWorkbookKeepsExactMetricsAndPercentUnits() throws Exception {
    var s = fixture(false, false);
    String before = Reports.mapper().writeValueAsString(s);
    Path x = dir.resolve("result.xlsx");
    ExcelReport.write(s, x);
    assertEquals(before, Reports.mapper().writeValueAsString(s));
    try (var zip = new ZipFile(x.toFile())) {
      for (var entry : Collections.list(zip.entries()))
        if (entry.getName().endsWith(".xml") || entry.getName().endsWith(".rels"))
          xml(zip, entry.getName());
      assertEquals(7, xml(zip, "xl/workbook.xml").getElementsByTagNameNS("*", "sheet").getLength());
      for (int i = 1; i <= 7; i++) {
        String sheet = text(zip, "xl/worksheets/sheet" + i + ".xml");
        for (String raw :
            List.of(
                uuid,
                "Snapshot ID",
                "schemaVersion",
                "DO_NOT_DISPLAY",
                "Window[",
                "internalDebugObject")) assertFalse(sheet.contains(raw), raw);
      }
      Document summary = xml(zip, "xl/worksheets/sheet1.xml");
      assertEquals("5", cell(summary, "K5").getTextContent());
      assertEquals("20.0", cell(summary, "K9").getTextContent());
      double timestamp = Double.parseDouble(cell(summary, "D8").getTextContent());
      double expected =
          java.time.Duration.between(
                      java.time.LocalDateTime.of(1899, 12, 30, 0, 0),
                      java.time.LocalDateTime.of(2026, 1, 2, 8, 34, 5))
                  .toMillis()
              / 86400000.0;
      assertEquals(expected, timestamp, 0.00000001);
      String styles = text(zip, "xl/styles.xml");
      assertTrue(styles.contains("0.00&quot;%&quot;"));
      assertTrue(styles.contains("#,##0"));
      assertNotNull(zip.getEntry("xl/charts/chart1.xml"));
      Document chart = xml(zip, "xl/charts/chart1.xml");
      assertEquals(3, chart.getElementsByTagNameNS("*", "ser").getLength());
      assertTrue(text(zip, "xl/worksheets/sheet4.xml").contains("capture cutoff precedes"));
      assertTrue(text(zip, "xl/worksheets/sheet6.xml").contains("Not available"));
      assertFalse(text(zip, "xl/worksheets/sheet6.xml").contains("Internal Server Error"));
    }
  }

  @Test
  void nativeTablesExcludeTotalsFromFiltersAndPreserveLongLabels() throws Exception {
    var s = fixture(false, false);
    Path x = dir.resolve("tables.xlsx");
    ExcelReport.write(s, x);
    try (var zip = new ZipFile(x.toFile())) {
      Document sheet = xml(zip, "xl/worksheets/sheet5.xml");
      Element pane = (Element) sheet.getElementsByTagNameNS("*", "pane").item(0);
      assertEquals("2", pane.getAttribute("xSplit"));
      assertEquals("13", pane.getAttribute("ySplit"));
      Document table = xml(zip, "xl/tables/table4.xml");
      Element root = table.getDocumentElement();
      assertEquals("1", root.getAttribute("totalsRowCount"));
      assertEquals("A13:Q15", root.getAttribute("ref"));
      assertEquals(
          "A13:Q14",
          ((Element) table.getElementsByTagNameNS("*", "autoFilter").item(0)).getAttribute("ref"));
      assertTrue(cell(sheet, "B14").getTextContent().contains("long label ".repeat(12)));
      assertEquals("TOTAL", cell(sheet, "B15").getTextContent());
      assertEquals("1", cell(sheet, "Q15").getTextContent());
    }
  }

  @Test
  void zeroSamplesMissingPhasesRemainUnavailableAndHaveNoChart() throws Exception {
    var s = fixture(true, true);
    Path x = dir.resolve("empty.xlsx");
    ExcelReport.write(s, x);
    try (var zip = new ZipFile(x.toFile())) {
      assertNull(zip.getEntry("xl/charts/chart1.xml"));
      var summary = xml(zip, "xl/worksheets/sheet1.xml");
      assertEquals("N/A", cell(summary, "K9").getTextContent());
      assertEquals("N/A", cell(summary, "B23").getTextContent());
      assertTrue(text(zip, "xl/worksheets/sheet2.xml").contains("Schedule not available"));
    }
  }

  @Test
  void allNineGroupTypesKeepNativeUnitsAndUnsupportedPhases() throws Exception {
    var base = fixture(false, false);
    var metadata = new LinkedHashMap<>(base.metadata());
    String[] classes = {
      "org.apache.jmeter.threads.ThreadGroup",
      "kg.apc.jmeter.threads.UltimateThreadGroup",
      "kg.apc.jmeter.threads.SteppingThreadGroup",
      "com.blazemeter.jmeter.threads.concurrency.ConcurrencyThreadGroup",
      "com.blazemeter.jmeter.threads.arrivals.ArrivalsThreadGroup",
      "com.blazemeter.jmeter.threads.arrivals.FreeFormArrivalsThreadGroup",
      "org.apache.jmeter.threads.openmodel.OpenModelThreadGroup",
      "org.apache.jmeter.threads.SetupThreadGroup",
      "org.apache.jmeter.threads.PostThreadGroup"
    };
    List<GroupDiscovery.Entry> entries = new ArrayList<>();
    for (int i = 0; i < classes.length; i++) {
      Schedule schedule =
          i == 4 || i == 5 || i == 6
              ? new Schedule(
                  "Configured arrival profile",
                  List.of(),
                  List.of(),
                  List.of(new Window("Steady load", 0, 5000)),
                  List.of(new Schedule.Stage(0, 5000, 2.5, 2.5)),
                  "arrivals/sec")
              : Schedule.manual(0, 1000, 4000, 0, 2);
      entries.add(
          new GroupDiscovery.Entry(
              "group" + i, "Group " + i, "Label", classes[i], i == 0, schedule, "", Map.of()));
    }
    metadata.put("threadGroups", entries);
    metadata.put("groups", List.of());
    var s =
        new ReportSnapshot(
            base.schemaVersion(),
            base.snapshotId(),
            metadata,
            base.settings(),
            base.analysis(),
            base.timeline(),
            base.expected(),
            base.phases(),
            base.unclassified());
    Path x = dir.resolve("groups.xlsx");
    ExcelReport.write(s, x);
    try (var zip = new ZipFile(x.toFile())) {
      Document sheet = xml(zip, "xl/worksheets/sheet7.xml");
      for (int i = 0; i < 9; i++)
        assertEquals("Group " + i, cell(sheet, "A" + (19 + i)).getTextContent());
      for (int i : List.of(4, 5, 6)) {
        assertEquals("N/A", cell(sheet, "E" + (19 + i)).getTextContent());
        assertEquals("2.5", cell(sheet, "F" + (19 + i)).getTextContent());
        assertEquals("N/A", cell(sheet, "I" + (19 + i)).getTextContent());
      }
      assertEquals("Disabled", cell(sheet, "C20").getTextContent());
      assertEquals(
          "N/A",
          cell(sheet, "I19").getTextContent(),
          "An absent zero-length ramp-down is not a captured zero-duration phase");
    }
  }
}
