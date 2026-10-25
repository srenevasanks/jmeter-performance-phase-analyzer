package io.phaseanalyzer;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.zip.*;

/**
 * Native OOXML writer using JDK ZIP/XML, avoiding conflicts with JMeter's existing libraries. No
 * macros, formulas, links or external dependencies.
 */
public final class ExcelReport {
  public static final List<String> SHEETS =
      List.of(
          "Execution Summary",
          "Ramp-up",
          "Steady Load",
          "Ramp-down",
          "Complete Execution",
          "Response Codes",
          "Execution Configuration");
  public static final List<String> KEYS =
      List.of(
          "label",
          "samples",
          "averageMs",
          "medianMs",
          "p90Ms",
          "p95Ms",
          "p99Ms",
          "minMs",
          "maxMs",
          "stddevMs",
          "errorPercent",
          "samplesPerSecond",
          "receivedKBPerSecond",
          "sentKBPerSecond",
          "successful",
          "failed");
  public static final List<String> HEADERS =
      List.of(
          "Thread Group",
          "Label",
          "# Samples",
          "Average (ms)",
          "Median (ms)",
          "P90 (ms)",
          "P95 (ms)",
          "P99 (ms)",
          "Min (ms)",
          "Max (ms)",
          "Std. Dev. (ms)",
          "Error %",
          "Throughput (samples/sec)",
          "Received KB/sec",
          "Sent KB/sec",
          "Successful",
          "Failed");

  // All workbook presentation is owned here. Calculation and capture objects remain unchanged.
  private static final String NS = "http://schemas.openxmlformats.org/spreadsheetml/2006/main";
  private static final String REL =
      "http://schemas.openxmlformats.org/officeDocument/2006/relationships";
  private static final int TEXT = 0,
      TITLE = 1,
      SECTION = 2,
      LABEL = 3,
      COUNT = 4,
      DECIMAL = 5,
      PERCENT = 6,
      DATE = 7,
      NOTE = 8,
      HEADER = 9,
      TOTAL_TEXT = 10,
      TOTAL_COUNT = 11,
      TOTAL_DECIMAL = 12,
      TOTAL_PERCENT = 13,
      STATUS = 14,
      FAILURE = 15,
      DURATION = 16;
  private static final String[] COLORS = {"C58A18", "24835B", "BA4141", "3479B7"};

  private record Cell(Object value, int style) {}

  private record Table(int header, int last, int columns, boolean totals, String name) {}

  private record Group(
      String key, String name, String type, Boolean enabled, Schedule schedule, String note) {}

  private static final class Sheet {
    final String name, color;
    final double[] widths;
    final SortedMap<Integer, SortedMap<Integer, Cell>> rows = new TreeMap<>();
    final Map<Integer, Double> heights = new HashMap<>();
    final List<String> merges = new ArrayList<>();
    final List<Table> tables = new ArrayList<>();
    int freezeRows, freezeColumns;
    boolean chart;

    Sheet(String name, String color, double... widths) {
      this.name = name;
      this.color = color;
      this.widths = widths;
    }

    void cell(int row, int col, Object value, int style) {
      rows.computeIfAbsent(row, k -> new TreeMap<>()).put(col, new Cell(value, style));
    }

    void merge(int row, int first, int last, Object value, int style) {
      cell(row, first, value, style);
      if (last > first) merges.add(column(first) + row + ":" + column(last) + row);
      double width = 0;
      for (int i = first; i <= last; i++) width += widths[i];
      if (value instanceof String text) {
        int lines = 0;
        for (String line : text.split("\n", -1))
          lines += Math.max(1, (int) Math.ceil(line.length() / Math.max(1, width - 3)));
        heights.merge(row, Math.min(409.0, Math.max(22, lines * 14 + 8.0)), Math::max);
      }
    }

    void title(String title, String subtitle) {
      merge(1, 0, widths.length - 1, title, TITLE);
      heights.put(1, 30.0);
      merge(2, 0, Math.min(widths.length - 1, 11), subtitle, NOTE);
    }

    void section(int row, String label, int last) {
      merge(row, 0, last, label, SECTION);
      heights.put(row, 24.0);
    }

    void header(int row, List<String> labels) {
      for (int c = 0; c < labels.size(); c++) cell(row, c, labels.get(c), HEADER);
      heights.put(row, 48.0);
    }

    void pair(int row, int col, int labelEnd, int valueEnd, String label, Object value, int style) {
      merge(row, col, labelEnd, label, LABEL);
      merge(row, labelEnd + 1, valueEnd, value, style);
    }

    void note(int row, String text, int last) {
      merge(row, 0, last, text, NOTE);
    }

    int last() {
      return rows.isEmpty() ? 1 : rows.lastKey();
    }
  }

  private ExcelReport() {}

  public static void write(ReportSnapshot snapshot, Path destination) throws IOException {
    var groups = groups(snapshot);
    List<Sheet> sheets = new ArrayList<>();
    sheets.add(summary(snapshot, groups));
    for (int i = 0; i < ReportSnapshot.PHASES.size(); i++) sheets.add(phase(snapshot, groups, i));
    sheets.add(codes(snapshot));
    sheets.add(configuration(snapshot, groups));
    Path parent = destination.toAbsolutePath().getParent();
    Files.createDirectories(parent);
    Path temp = Files.createTempFile(parent, "phase-xlsx-", ".tmp");
    try {
      try (var zip = new ZipOutputStream(Files.newOutputStream(temp), StandardCharsets.UTF_8)) {
        writePackage(zip, sheets, snapshot);
      }
      try {
        Files.move(temp, destination, StandardCopyOption.REPLACE_EXISTING);
      } catch (AccessDeniedException denied) {
        Files.copy(temp, destination, StandardCopyOption.REPLACE_EXISTING);
      }
    } finally {
      Files.deleteIfExists(temp);
    }
  }

  private static Sheet summary(ReportSnapshot s, List<Group> groups) {
    Sheet sh = new Sheet(SHEETS.get(0), "334155", 21, 14, 14, 14, 12, 12, 14, 14, 14, 12, 18, 24);
    sh.title(
        "Performance Phase Analyzer",
        scope(s, groups) + ". Times shown in " + zone(s).getId() + ".");
    sh.merge(4, 0, 5, "Report information", SECTION);
    sh.merge(4, 7, 11, "Overall performance", SECTION);
    Map<String, Object> m = complete(s);
    String[] labels = {
      "Test plan / JMX filename",
      "Thread Group / analysis scope",
      "Thread Group type",
      "Execution start",
      "Execution end",
      "Actual captured duration",
      "Configured duration",
      "Execution status",
      "Capture status",
      "Phase detection mode",
      "JMeter version",
      "Java version",
      "Operating system"
    };
    Object[] values = {
      meta(s, "jmxFilename"),
      scope(s, groups),
      selectedType(s, groups),
      date(s, "executionStart"),
      date(s, "executionEnd"),
      seconds(s.phase("Complete execution"), false),
      seconds(s.phase("Complete execution"), true),
      meta(s, "executionStatus"),
      meta(s, "captureStatus"),
      meta(s, "detectionMode"),
      environment(s, "jmeter"),
      environment(s, "java"),
      environment(s, "os")
    };
    for (int i = 0; i < labels.length; i++)
      sh.pair(
          5 + i,
          0,
          2,
          5,
          labels[i],
          values[i],
          i == 3 || i == 4 ? DATE : i == 5 || i == 6 ? DURATION : i == 7 || i == 8 ? STATUS : TEXT);
    String[] kpis = {
      "Total samples",
      "Successful samples",
      "Failed samples",
      "Success rate",
      "Error rate",
      "Average response time (ms)",
      "P90 response time (ms)",
      "P95 response time (ms)",
      "P99 response time (ms)",
      "Throughput (samples/sec)"
    };
    Object[] numbers = {
      m.get("samples"),
      m.get("successful"),
      m.get("failed"),
      success(m),
      m.get("errorPercent"),
      m.get("averageMs"),
      m.get("p90Ms"),
      m.get("p95Ms"),
      m.get("p99Ms"),
      m.get("samplesPerSecond")
    };
    for (int i = 0; i < kpis.length; i++)
      sh.pair(
          5 + i,
          7,
          9,
          11,
          kpis[i],
          numbers[i],
          i < 3 ? (i == 2 && positive(numbers[i]) ? FAILURE : COUNT) : i < 5 ? PERCENT : DECIMAL);
    sh.note(
        19,
        "Timing and metrics cover the captured execution interval. JMeter execution status and"
            + " individual phase capture status are independent.",
        11);
    sh.section(21, "Phase comparison", 11);
    sh.header(
        22,
        List.of(
            "Phase",
            "Configured duration",
            "Effective duration",
            "Samples",
            "Successful",
            "Failed",
            "Average (ms)",
            "P90 (ms)",
            "P95 (ms)",
            "Error rate",
            "Throughput (samples/sec)",
            "Capture status"));
    int row = 23;
    for (String name : ReportSnapshot.PHASES) {
      var p = s.phase(name);
      Map<String, Object> pm = p == null ? Map.of() : p.metrics();
      Object[] data = {
        displayPhase(name),
        seconds(p, true),
        seconds(p, false),
        pm.get("samples"),
        pm.get("successful"),
        pm.get("failed"),
        pm.get("averageMs"),
        pm.get("p90Ms"),
        pm.get("p95Ms"),
        pm.get("errorPercent"),
        pm.get("samplesPerSecond"),
        p == null ? "Unavailable" : p.status()
      };
      for (int c = 0; c < data.length; c++)
        sh.cell(
            row,
            c,
            data[c],
            c == 0
                ? LABEL
                : c == 1 || c == 2
                    ? DURATION
                    : c <= 5
                        ? (c == 5 && positive(data[c]) ? FAILURE : COUNT)
                        : c == 9 ? PERCENT : c == 11 ? STATUS : DECIMAL);
      sh.heights.put(row++, 32.0);
    }
    sh.note(28, coverage(s), 11);
    List<ReportSnapshot.Phase> valid = chartPhases(s);
    if (valid.size() >= 2) {
      sh.section(30, "Phase response-time comparison (ms)", 11);
      sh.chart = true;
    } else
      sh.note(
          30,
          "Response-time chart omitted: fewer than two phases have valid Average, P90 and P95"
              + " values.",
          11);
    return sh;
  }

  private static Sheet phase(ReportSnapshot s, List<Group> groups, int index) {
    String name = ReportSnapshot.PHASES.get(index);
    var p = s.phase(name);
    Sheet sh =
        new Sheet(
            SHEETS.get(index + 1),
            COLORS[index],
            32,
            50,
            14,
            16,
            16,
            16,
            16,
            16,
            16,
            16,
            18,
            14,
            23,
            21,
            19,
            16,
            16);
    sh.title(
        displayPhase(name),
        scope(s, groups) + ". " + population(s) + ". Times shown in " + zone(s).getId() + ".");
    Map<String, Object> m = p == null ? Map.of() : p.metrics();
    sh.pair(4, 0, 1, 3, "Configured phase duration", seconds(p, true), DURATION);
    sh.pair(4, 5, 7, 10, "Effective captured duration", seconds(p, false), DURATION);
    sh.pair(5, 0, 1, 3, "Capture status", p == null ? "Unavailable" : p.status(), STATUS);
    sh.pair(5, 5, 7, 10, "Samples", m.get("samples"), COUNT);
    sh.pair(6, 0, 1, 3, "Success rate", success(m), PERCENT);
    sh.pair(6, 5, 7, 10, "Error rate", m.get("errorPercent"), PERCENT);
    sh.pair(7, 0, 1, 3, "Average response time (ms)", m.get("averageMs"), DECIMAL);
    sh.pair(7, 5, 7, 10, "P95 response time (ms)", m.get("p95Ms"), DECIMAL);
    sh.pair(8, 0, 1, 3, "Throughput (samples/sec)", m.get("samplesPerSecond"), DECIMAL);
    sh.pair(8, 5, 7, 10, "Phase", displayPhase(name), TEXT);
    sh.note(10, phaseExplanation(p, s), 10);
    int row = 12;
    boolean any = false;
    for (var view : s.analysis().views())
      if (view.window().phase().equals(name)) {
        any = true;
        sh.section(row++, "Aggregate results by API — " + view.kind(), 16);
        int header = row++;
        sh.header(header, HEADERS);
        for (var metric : view.rows()) {
          boolean total = "TOTAL".equals(metric.get("label"));
          sh.cell(row, 0, scope(s, groups), total ? TOTAL_TEXT : TEXT);
          for (int c = 0; c < KEYS.size(); c++) {
            String key = KEYS.get(c);
            Object value = metric.get(key);
            int style =
                key.equals("label")
                    ? (total ? TOTAL_TEXT : TEXT)
                    : key.equals("errorPercent")
                        ? (total ? TOTAL_PERCENT : PERCENT)
                        : Set.of("samples", "successful", "failed").contains(key)
                            ? (key.equals("failed") && positive(value)
                                ? FAILURE
                                : total ? TOTAL_COUNT : COUNT)
                            : total ? TOTAL_DECIMAL : DECIMAL;
            sh.cell(row, c + 1, value, style);
          }
          String label = Objects.toString(metric.get("label"), "");
          sh.heights.put(
              row,
              Math.min(
                  409.0,
                  Math.max(
                      24,
                      14.0
                              * Math.max(
                                  (int) Math.ceil(scope(s, groups).length() / 29.0),
                                  (int) Math.ceil(label.length() / 47.0))
                          + 8)));
          row++;
        }
        if (row > header + 1)
          sh.tables.add(
              new Table(
                  header,
                  row - 1,
                  17,
                  true,
                  "Phase" + (index + 1) + "Population" + sh.tables.size()));
        row += 2;
      }
    if (!any) {
      sh.section(row++, "Aggregate results by API", 16);
      sh.header(row++, HEADERS);
      sh.note(row, "No aggregate samples are available for this phase and capture.", 10);
    }
    sh.freezeRows = 13;
    sh.freezeColumns = 2;
    return sh;
  }

  private static Sheet codes(ReportSnapshot s) {
    Sheet sh = new Sheet(SHEETS.get(5), "64748B", 23, 58, 22, 22);
    sh.title(
        "Response codes", "Recorded sampler codes. Codes are not assumed to be HTTP statuses.");
    Map<String, Object> m = complete(s);
    String[] labels = {
      "Total samples", "Successful samples", "Failed samples", "Overall error rate"
    };
    Object[] data = {m.get("samples"), m.get("successful"), m.get("failed"), m.get("errorPercent")};
    for (int i = 0; i < 4; i++)
      sh.pair(
          4 + i,
          0,
          1,
          3,
          labels[i],
          data[i],
          i == 3 ? PERCENT : i == 2 && positive(data[i]) ? FAILURE : COUNT);
    sh.section(9, "Response code distribution", 3);
    sh.header(10, List.of("Response code", "Description", "Count", "Percentage"));
    long n = m.get("samples") instanceof Number num ? num.longValue() : 0;
    int row = 11;
    Object raw = m.get("responseCodes");
    if (raw instanceof Map<?, ?> map)
      for (var e : map.entrySet()) {
        sh.cell(row, 0, String.valueOf(e.getKey()), TEXT);
        sh.cell(row, 1, "Not available", TEXT);
        sh.cell(row, 2, e.getValue(), COUNT);
        sh.cell(
            row,
            3,
            n > 0 && e.getValue() instanceof Number num ? num.doubleValue() * 100 / n : null,
            PERCENT);
        row++;
      }
    if (row > 11) {
      sh.cell(row, 0, "TOTAL", TOTAL_TEXT);
      sh.cell(row, 1, "Captured sample population", TOTAL_TEXT);
      sh.cell(row, 2, m.get("samples"), TOTAL_COUNT);
      sh.cell(row, 3, n > 0 ? 100.0 : null, TOTAL_PERCENT);
      sh.tables.add(new Table(10, row, 4, true, "ResponseCodes"));
    } else sh.note(row++, "No response-code samples were captured.", 3);
    sh.note(
        row + 2,
        "Descriptions and per-code failure classifications are not retained by this snapshot. No"
            + " HTTP descriptions or error classifications have been inferred.",
        3);
    sh.freezeRows = 10;
    sh.freezeColumns = 1;
    return sh;
  }

  private static Sheet configuration(ReportSnapshot s, List<Group> groups) {
    Sheet sh = new Sheet(SHEETS.get(6), "64748B", 34, 30, 25, 26, 23, 23, 24, 24, 24, 25, 62);
    sh.title(
        "Execution configuration",
        "Readable configuration and analytical windows. Times shown in " + zone(s).getId() + ".");
    sh.section(4, "Test configuration", 10);
    String[] labels = {
      "JMX filename",
      "JMeter version",
      "Java version",
      "Environment / operating system",
      "Phase detection mode",
      "Selected Thread Group / scope",
      "Configured target load",
      "Execution start",
      "Execution end",
      "Capture status"
    };
    Object[] values = {
      meta(s, "jmxFilename"),
      environment(s, "jmeter"),
      environment(s, "java"),
      environment(s, "os"),
      meta(s, "detectionMode"),
      scope(s, groups),
      target(s, groups),
      date(s, "executionStart"),
      date(s, "executionEnd"),
      meta(s, "captureStatus")
    };
    for (int i = 0; i < labels.length; i++)
      sh.pair(5 + i, 0, 1, 5, labels[i], values[i], i == 7 || i == 8 ? DATE : TEXT);
    int row = 17;
    sh.section(row++, "Thread Group configuration", 10);
    int header = row++;
    sh.header(
        header,
        List.of(
            "Thread Group name",
            "Thread Group type",
            "Enabled at capture",
            "Scheduling model",
            "Configured users / concurrency",
            "Configured arrival rate (arrivals/sec)",
            "Ramp-up duration",
            "Steady duration",
            "Ramp-down duration",
            "Total configured duration",
            "Detection status / notes"));
    for (Group g : groups) {
      Schedule schedule = g.schedule();
      boolean arrivals = schedule != null && schedule.loadUnit().equals("arrivals/sec");
      Object peak = schedule == null ? null : peak(schedule);
      Object[] valuesRow = {
        g.name(),
        g.type(),
        g.enabled() == null ? "N/A" : g.enabled() ? "Enabled" : "Disabled",
        schedule == null
            ? null
            : arrivals ? "Open workload / arrival rate" : "Closed workload / users",
        arrivals ? null : peak,
        arrivals ? peak : null,
        phaseDuration(schedule, "Ramp-up"),
        phaseDuration(schedule, "Steady load"),
        phaseDuration(schedule, "Ramp-down"),
        schedule == null ? null : schedule.end() / 86400000.0,
        groupNote(g)
      };
      for (int c = 0; c < valuesRow.length; c++)
        sh.cell(
            row, c, valuesRow[c], c >= 6 && c <= 9 ? DURATION : c == 4 || c == 5 ? DECIMAL : TEXT);
      sh.heights.put(
          row,
          Math.min(
              409,
              Math.max(
                  54,
                  14
                          * Math.max(
                              Math.ceil(g.name().length() / 31.0),
                              Math.ceil(groupNote(g).length() / 59.0))
                      + 10)));
      row++;
    }
    if (!groups.isEmpty())
      sh.tables.add(new Table(header, row - 1, 11, false, "ThreadGroupConfiguration"));
    else sh.note(row++, "Thread Group configuration was not retained in this capture.", 10);
    row += 2;
    sh.section(row++, "Phase windows", 10);
    header = row++;
    List<String> windowHeaders =
        List.of(
            "Phase",
            "Configured start",
            "Configured end",
            "Effective start",
            "Effective end",
            "Effective duration",
            "Status / notes");
    sh.header(header, windowHeaders);
    for (String name : ReportSnapshot.PHASES) {
      var p = s.phase(name);
      int count =
          p == null ? 1 : Math.max(1, Math.max(p.configured().size(), p.effective().size()));
      for (int i = 0; i < count; i++) {
        Window c = p != null && i < p.configured().size() ? p.configured().get(i) : null;
        Window e = p != null && i < p.effective().size() ? p.effective().get(i) : null;
        Object[] data = {
          displayPhase(name),
          c == null ? null : date(s, c.start()),
          c == null ? null : date(s, c.end()),
          e == null ? null : date(s, e.start()),
          e == null ? null : date(s, e.end()),
          e == null ? null : e.seconds() / 86400.0,
          p == null ? "Unavailable" : p.status()
        };
        for (int col = 0; col < data.length; col++)
          sh.cell(row, col, data[col], col >= 1 && col <= 4 ? DATE : col == 5 ? DURATION : TEXT);
        sh.heights.put(row++, 32.0);
      }
    }
    sh.tables.add(new Table(header, row - 1, 7, false, "PhaseWindows"));
    row += 2;
    sh.section(row++, "Limitations and warnings", 10);
    sh.note(row++, coverage(s), 10);
    for (String name : ReportSnapshot.PHASES) {
      var p = s.phase(name);
      if (p == null || !p.status().equals("Completed"))
        sh.note(row++, displayPhase(name) + ": " + phaseExplanation(p, s), 10);
    }
    LinkedHashSet<String> warnings = new LinkedHashSet<>();
    for (String warning : s.analysis().warnings()) warnings.add(readable(warning));
    var root = node(s.metadata());
    for (var warning : root.path("warnings"))
      if (warning.isTextual()) warnings.add(readable(warning.asText()));
    for (String warning : warnings) if (!warning.isBlank()) sh.note(row++, warning, 10);
    sh.note(
        row++,
        "Rates use effective analytical-window duration, including idle time. Response percentiles"
            + " and aggregate statistics retain the analyzer's existing definitions. KB = 1,024"
            + " bytes.",
        10);
    sh.note(
        row,
        "Window rows list configured and effective intervals independently by position. When"
            + " interval splitting differs, each column pair retains its own boundaries; rows do"
            + " not imply one-to-one matching.",
        10);
    sh.freezeRows = 3;
    sh.freezeColumns = 1;
    return sh;
  }

  private static Map<String, Object> complete(ReportSnapshot s) {
    var p = s.phase("Complete execution");
    return p == null ? Map.of() : p.metrics();
  }

  private static String displayPhase(String phase) {
    return phase.equals("Steady load")
        ? "Steady Load"
        : phase.equals("Complete execution") ? "Complete Execution" : phase;
  }

  private static Object seconds(ReportSnapshot.Phase p, boolean configured) {
    if (p == null) return null;
    Double n =
        configured
            ? p.configuredSeconds()
            : p.configured().isEmpty() && !p.name().equals("Complete execution")
                ? null
                : p.capturedSeconds();
    return n == null ? null : n / 86400;
  }

  private static Object success(Map<String, Object> m) {
    return m.get("samples") instanceof Number n
            && n.longValue() > 0
            && m.get("errorPercent") instanceof Number e
        ? 100.0 - e.doubleValue()
        : null;
  }

  private static boolean positive(Object value) {
    return value instanceof Number n && n.doubleValue() > 0;
  }

  private static String population(ReportSnapshot s) {
    return readable(Objects.toString(s.metadata().get("population"), s.settings().mode()));
  }

  private static Object meta(ReportSnapshot s, String key) {
    Object value = s.metadata().get(key);
    return value instanceof String text ? readable(text) : value instanceof Number ? value : null;
  }

  private static Object environment(ReportSnapshot s, String key) {
    var value = node(s.metadata()).path("environment").path(key);
    return value.isTextual() ? readable(value.asText()) : null;
  }

  private static java.time.ZoneId zone(ReportSnapshot s) {
    try {
      return java.time.ZoneId.of(Objects.toString(s.metadata().get("reportTimeZone"), "UTC"));
    } catch (java.time.DateTimeException ignored) {
      return java.time.ZoneOffset.UTC;
    }
  }

  private static Object date(ReportSnapshot s, String key) {
    Object value = s.metadata().get(key);
    return value instanceof Number n ? date(s, n.longValue()) : null;
  }

  private static Double date(ReportSnapshot s, long epoch) {
    if (epoch <= 0) return null;
    var local = java.time.Instant.ofEpochMilli(epoch).atZone(zone(s)).toLocalDateTime();
    return java.time.Duration.between(java.time.LocalDateTime.of(1899, 12, 30, 0, 0), local)
            .toMillis()
        / 86400000.0;
  }

  private static com.fasterxml.jackson.databind.JsonNode node(Object object) {
    return Reports.mapper().valueToTree(object);
  }

  private static List<Group> groups(ReportSnapshot s) {
    var metadata = node(s.metadata());
    var result = new LinkedHashMap<String, Group>();
    for (var entry : metadata.path("threadGroups")) {
      String key = entry.path("key").asText();
      String name = readable(entry.path("name").asText("Name unavailable"));
      result.put(
          key,
          new Group(
              key,
              name,
              type(entry.path("implementation").asText()),
              entry.path("enabled").isBoolean() ? entry.path("enabled").asBoolean() : null,
              schedule(entry.path("schedule")),
              readable(entry.path("warning").asText())));
    }
    for (var entry : metadata.path("groups")) {
      String key = entry.path("name").asText();
      Group prior = result.get(key);
      result.put(
          key,
          new Group(
              key,
              prior == null
                  ? (key.startsWith("tg:") ? "Name unavailable" : readable(key))
                  : prior.name(),
              type(entry.path("className").asText()),
              prior == null ? null : prior.enabled(),
              schedule(entry.path("schedule")),
              readable(entry.path("warning").asText())));
    }
    return List.copyOf(result.values());
  }

  private static Schedule schedule(com.fasterxml.jackson.databind.JsonNode value) {
    if (!value.isObject()) return null;
    try {
      return Reports.mapper().treeToValue(value, Schedule.class);
    } catch (Exception ignored) {
      return null;
    }
  }

  private static String type(String name) {
    return switch (name.substring(name.lastIndexOf('.') + 1)) {
      case "ThreadGroup" -> "Standard Thread Group";
      case "UltimateThreadGroup" -> "Ultimate Thread Group";
      case "SteppingThreadGroup" -> "Stepping Thread Group";
      case "ConcurrencyThreadGroup" -> "Concurrency Thread Group";
      case "ArrivalsThreadGroup" -> "Arrivals Thread Group";
      case "FreeFormArrivalsThreadGroup" -> "Free-Form Arrivals Thread Group";
      case "OpenModelThreadGroup" -> "Open Model Thread Group";
      case "SetupThreadGroup" -> "setUp Thread Group";
      case "PostThreadGroup" -> "tearDown Thread Group";
      default -> name.isBlank() ? "N/A" : name.substring(name.lastIndexOf('.') + 1);
    };
  }

  private static List<Group> selected(ReportSnapshot s, List<Group> groups) {
    if (!s.settings().group().equals("All"))
      return groups.stream().filter(g -> s.settings().group().equals(g.key())).toList();
    Set<String> captured = new HashSet<>();
    for (var g : node(s.metadata()).path("groups")) captured.add(g.path("name").asText());
    return groups.stream()
        .filter(
            g -> captured.isEmpty() ? Boolean.TRUE.equals(g.enabled()) : captured.contains(g.key()))
        .toList();
  }

  private static String scope(ReportSnapshot s, List<Group> groups) {
    if (s.settings().group().equals("All")) return "All captured Thread Groups";
    return selected(s, groups).stream()
        .findFirst()
        .map(Group::name)
        .orElseGet(
            () ->
                s.settings().group().startsWith("tg:")
                    ? "Selected Thread Group (name unavailable)"
                    : readable(s.settings().group()));
  }

  private static String selectedType(ReportSnapshot s, List<Group> groups) {
    var types = selected(s, groups).stream().map(Group::type).distinct().toList();
    return types.size() == 1
        ? types.get(0)
        : types.isEmpty() ? "N/A" : "Multiple Thread Group types";
  }

  private static String target(ReportSnapshot s, List<Group> groups) {
    var selection = selected(s, groups);
    if (selection.size() != 1 || selection.get(0).schedule() == null) return "N/A";
    Schedule schedule = selection.get(0).schedule();
    return String.format(Locale.ROOT, "%,.2f %s", peak(schedule), schedule.loadUnit());
  }

  private static double peak(Schedule schedule) {
    return schedule.profile().isEmpty()
        ? schedule.peak()
        : schedule.profile().stream()
            .mapToDouble(v -> Math.max(v.from(), v.to()))
            .max()
            .orElse(Double.NaN);
  }

  private static Double phaseDuration(Schedule s, String phase) {
    if (s == null) return null;
    List<Window> windows =
        s.windows(0).stream().filter(w -> w.phase().equals(phase) && w.end() > w.start()).toList();
    return windows.isEmpty()
        ? null
        : Analyzer.normalize(windows).stream().mapToDouble(Window::seconds).sum() / 86400;
  }

  private static String groupNote(Group g) {
    String note =
        g.schedule() == null
            ? "Schedule unavailable"
            : g.schedule().loadUnit().equals("arrivals/sec")
                ? "Native arrival-rate schedule. Arrival rate is not concurrency."
                : "Native user schedule.";
    if (g.schedule() != null && phaseDuration(g.schedule(), "Ramp-down") == null)
      note += " No configured gradual ramp-down phase.";
    if (!g.note().isBlank()) note += " " + g.note();
    return note;
  }

  private static String phaseExplanation(ReportSnapshot.Phase p, ReportSnapshot s) {
    if (p == null) return "Phase information is unavailable in this capture.";
    String status = p.status(),
        explanation = p.explanation() == null ? "" : readable(p.explanation());
    String note =
        switch (status) {
          case "Not configured" ->
              "No analytical window is configured for this phase. A native schedule may omit this"
                  + " phase.";
          case "Unavailable" -> "Phase detection or selection is unavailable for this capture.";
          case "Not started", "Not captured" ->
              "No part of this phase window was captured at the snapshot cutoff.";
          case "Partially captured" ->
              "The capture cutoff precedes the configured phase end. Metrics cover only effective"
                  + " captured intervals. This does not change the reported JMeter execution"
                  + " outcome.";
          case "Running" ->
              "Capture was in progress at the snapshot cutoff. Metrics cover accepted samples"
                  + " only.";
          default ->
              "Metrics use this phase's effective captured intervals; missing values are shown as"
                  + " N/A.";
        };
    return note + (explanation.isBlank() ? "" : " " + explanation);
  }

  private static String coverage(ReportSnapshot s) {
    return "Complete Execution retains all eligible captured "
        + population(s)
        + " samples. Samples outside the selected phase windows: "
        + String.format(Locale.ROOT, "%,d", s.unclassified())
        + ". Initial delays, gaps and boundary rules can make phase totals differ from Complete"
        + " Execution. Overlapping analytical inclusions: "
        + Objects.toString(s.metadata().get("overlappingAnalyticalInclusions"), "N/A")
        + ".";
  }

  private static String readable(String value) {
    if (value == null) return "";
    return value.replaceAll(
        "(?:tg:)?[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}",
        "[identifier omitted]");
  }

  private static List<ReportSnapshot.Phase> chartPhases(ReportSnapshot s) {
    return s.phases().stream()
        .filter(
            p ->
                positive(p.metrics().get("samples"))
                    && List.of("averageMs", "p90Ms", "p95Ms").stream()
                        .allMatch(
                            k ->
                                p.metrics().get(k) instanceof Number n
                                    && Double.isFinite(n.doubleValue())))
        .toList();
  }

  private static void writePackage(ZipOutputStream zip, List<Sheet> sheets, ReportSnapshot s)
      throws IOException {
    int totalTables = sheets.stream().mapToInt(sh -> sh.tables.size()).sum();
    boolean chart = sheets.get(0).chart;
    StringBuilder ct =
        new StringBuilder(
            "<?xml version=\"1.0\"?><Types"
                + " xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\"><Default"
                + " Extension=\"rels\""
                + " ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/><Default"
                + " Extension=\"xml\" ContentType=\"application/xml\"/>");
    override(
        ct,
        "/xl/workbook.xml",
        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml");
    override(
        ct,
        "/xl/styles.xml",
        "application/vnd.openxmlformats-officedocument.spreadsheetml.styles+xml");
    for (int i = 1; i <= 7; i++)
      override(
          ct,
          "/xl/worksheets/sheet" + i + ".xml",
          "application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml");
    for (int i = 1; i <= totalTables; i++)
      override(
          ct,
          "/xl/tables/table" + i + ".xml",
          "application/vnd.openxmlformats-officedocument.spreadsheetml.table+xml");
    if (chart) {
      override(
          ct,
          "/xl/drawings/drawing1.xml",
          "application/vnd.openxmlformats-officedocument.drawing+xml");
      override(
          ct,
          "/xl/charts/chart1.xml",
          "application/vnd.openxmlformats-officedocument.drawingml.chart+xml");
    }
    entry(zip, "[Content_Types].xml", ct.append("</Types>").toString());
    entry(
        zip,
        "_rels/.rels",
        relationships(
            "<Relationship Id=\"office\" Type=\""
                + REL
                + "/officeDocument\" Target=\"xl/workbook.xml\"/>"));
    StringBuilder book =
        new StringBuilder(
            "<?xml version=\"1.0\"?><workbook xmlns=\""
                + NS
                + "\" xmlns:r=\""
                + REL
                + "\"><bookViews><workbookView activeTab=\"0\"/></bookViews><sheets>");
    StringBuilder relations = new StringBuilder();
    int tableId = 0;
    for (int i = 0; i < 7; i++) {
      Sheet sh = sheets.get(i);
      book.append("<sheet name=\"")
          .append(xml(sh.name))
          .append("\" sheetId=\"")
          .append(i + 1)
          .append("\" r:id=\"sheet")
          .append(i + 1)
          .append("\"/>");
      relations
          .append("<Relationship Id=\"sheet")
          .append(i + 1)
          .append("\" Type=\"")
          .append(REL)
          .append("/worksheet\" Target=\"worksheets/sheet")
          .append(i + 1)
          .append(".xml\"/>");
      StringBuilder sheetRels = new StringBuilder();
      for (int j = 0; j < sh.tables.size(); j++) {
        Table table = sh.tables.get(j);
        tableId++;
        entry(zip, "xl/tables/table" + tableId + ".xml", table(sh, table, tableId));
        sheetRels
            .append("<Relationship Id=\"table")
            .append(j + 1)
            .append("\" Type=\"")
            .append(REL)
            .append("/table\" Target=\"../tables/table")
            .append(tableId)
            .append(".xml\"/>");
      }
      if (sh.chart)
        sheetRels
            .append("<Relationship Id=\"drawing\" Type=\"")
            .append(REL)
            .append("/drawing\" Target=\"../drawings/drawing1.xml\"/>");
      entry(zip, "xl/worksheets/sheet" + (i + 1) + ".xml", sheet(sh));
      if (!sheetRels.isEmpty())
        entry(
            zip,
            "xl/worksheets/_rels/sheet" + (i + 1) + ".xml.rels",
            relationships(sheetRels.toString()));
    }
    entry(zip, "xl/workbook.xml", book.append("</sheets></workbook>").toString());
    relations
        .append("<Relationship Id=\"styles\" Type=\"")
        .append(REL)
        .append("/styles\" Target=\"styles.xml\"/>");
    entry(zip, "xl/_rels/workbook.xml.rels", relationships(relations.toString()));
    entry(zip, "xl/styles.xml", styles());
    if (chart) {
      entry(zip, "xl/drawings/drawing1.xml", drawing());
      entry(
          zip,
          "xl/drawings/_rels/drawing1.xml.rels",
          relationships(
              "<Relationship Id=\"chart\" Type=\""
                  + REL
                  + "/chart\" Target=\"../charts/chart1.xml\"/>"));
      entry(zip, "xl/charts/chart1.xml", chart(s));
    }
  }

  private static String sheet(Sheet sh) {
    StringBuilder x =
        new StringBuilder(
            "<?xml version=\"1.0\" encoding=\"UTF-8\"?><worksheet xmlns=\""
                + NS
                + "\" xmlns:r=\""
                + REL
                + "\"><sheetPr><tabColor rgb=\"FF"
                + sh.color
                + "\"/><pageSetUpPr fitToPage=\"1\"/></sheetPr><dimension ref=\"A1:"
                + column(sh.widths.length - 1)
                + Math.max(sh.last(), sh.chart ? 48 : 1)
                + "\"/><sheetViews><sheetView showGridLines=\"0\" zoomScale=\"85\""
                + " workbookViewId=\"0\">");
    if (sh.freezeRows > 0)
      x.append("<pane xSplit=\"")
          .append(sh.freezeColumns)
          .append("\" ySplit=\"")
          .append(sh.freezeRows)
          .append("\" topLeftCell=\"")
          .append(column(sh.freezeColumns))
          .append(sh.freezeRows + 1)
          .append("\" activePane=\"bottomRight\" state=\"frozen\"/>");
    x.append("</sheetView></sheetViews><sheetFormatPr defaultRowHeight=\"22\"/><cols>");
    for (int c = 0; c < sh.widths.length; c++)
      x.append("<col min=\"")
          .append(c + 1)
          .append("\" max=\"")
          .append(c + 1)
          .append("\" width=\"")
          .append(sh.widths[c])
          .append("\" customWidth=\"1\"/>");
    x.append("</cols><sheetData>");
    for (var row : sh.rows.entrySet()) {
      x.append("<row r=\"")
          .append(row.getKey())
          .append("\" ht=\"")
          .append(sh.heights.getOrDefault(row.getKey(), 24.0))
          .append("\" customHeight=\"1\">");
      for (var c : row.getValue().entrySet()) {
        Cell cell = c.getValue();
        Object value = cell.value();
        String ref = column(c.getKey()) + row.getKey();
        x.append("<c r=\"").append(ref).append("\" s=\"").append(cell.style()).append("\"");
        if (value instanceof Number num && Double.isFinite(num.doubleValue()))
          x.append("><v>").append(num).append("</v></c>");
        else
          x.append(" t=\"inlineStr\"><is><t xml:space=\"preserve\">")
              .append(xml(value == null ? "N/A" : value.toString()))
              .append("</t></is></c>");
      }
      x.append("</row>");
    }
    x.append("</sheetData>");
    if (!sh.merges.isEmpty()) {
      x.append("<mergeCells count=\"").append(sh.merges.size()).append("\">");
      for (String merge : sh.merges) x.append("<mergeCell ref=\"").append(merge).append("\"/>");
      x.append("</mergeCells>");
    }
    for (Table t : sh.tables)
      if (t.columns() == 17 && t.last() > t.header() + 1)
        x.append("<conditionalFormatting sqref=\"Q")
            .append(t.header() + 1)
            .append(":Q")
            .append(t.last())
            .append("\"><cfRule type=\"cellIs\" dxfId=\"0\" priority=\"")
            .append(sh.tables.indexOf(t) + 1)
            .append(
                "\" operator=\"greaterThan\"><formula>0</formula></cfRule></conditionalFormatting>");
    x.append(
        "<printOptions horizontalCentered=\"0\"/><pageMargins left=\"0.25\" right=\"0.25\""
            + " top=\"0.4\" bottom=\"0.4\" header=\"0.2\" footer=\"0.2\"/><pageSetup"
            + " paperSize=\"9\" orientation=\"landscape\" fitToWidth=\"1\" fitToHeight=\"0\"/>");
    if (sh.chart) x.append("<drawing r:id=\"drawing\"/>");
    if (!sh.tables.isEmpty()) {
      x.append("<tableParts count=\"").append(sh.tables.size()).append("\">");
      for (int i = 0; i < sh.tables.size(); i++)
        x.append("<tablePart r:id=\"table").append(i + 1).append("\"/>");
      x.append("</tableParts>");
    }
    return x.append("</worksheet>").toString();
  }

  private static String table(Sheet sh, Table t, int id) {
    String ref = "A" + t.header() + ":" + column(t.columns() - 1) + t.last();
    StringBuilder x =
        new StringBuilder(
            "<?xml version=\"1.0\"?><table xmlns=\""
                + NS
                + "\" id=\""
                + id
                + "\" name=\""
                + t.name()
                + "\" displayName=\""
                + t.name()
                + "\" ref=\""
                + ref
                + "\" totalsRowCount=\""
                + (t.totals() ? 1 : 0)
                + "\" totalsRowShown=\""
                + (t.totals() ? 1 : 0)
                + "\"><autoFilter ref=\"A"
                + t.header()
                + ":"
                + column(t.columns() - 1)
                + (t.totals() ? t.last() - 1 : t.last())
                + "\"/><tableColumns count=\""
                + t.columns()
                + "\">");
    for (int c = 0; c < t.columns(); c++)
      x.append("<tableColumn id=\"")
          .append(c + 1)
          .append("\" name=\"")
          .append(xml(sh.rows.get(t.header()).get(c).value().toString()))
          .append("\"/>");
    return x.append(
            "</tableColumns><tableStyleInfo name=\"TableStyleMedium2\" showFirstColumn=\"0\""
                + " showLastColumn=\"0\" showRowStripes=\"0\" showColumnStripes=\"0\"/></table>")
        .toString();
  }

  private static String drawing() {
    return "<?xml version=\"1.0\"?><xdr:wsDr"
               + " xmlns:xdr=\"http://schemas.openxmlformats.org/drawingml/2006/spreadsheetDrawing\""
               + " xmlns:a=\"http://schemas.openxmlformats.org/drawingml/2006/main\"><xdr:oneCellAnchor><xdr:from><xdr:col>0</xdr:col><xdr:colOff>0</xdr:colOff><xdr:row>31</xdr:row><xdr:rowOff>0</xdr:rowOff></xdr:from><xdr:ext"
               + " cx=\"10477500\" cy=\"2857500\"/><xdr:graphicFrame"
               + " macro=\"\"><xdr:nvGraphicFramePr><xdr:cNvPr id=\"2\" name=\"Phase response-time"
               + " comparison\"/><xdr:cNvGraphicFramePr/></xdr:nvGraphicFramePr><xdr:xfrm><a:off"
               + " x=\"0\" y=\"0\"/><a:ext cx=\"0\" cy=\"0\"/></xdr:xfrm><a:graphic><a:graphicData"
               + " uri=\"http://schemas.openxmlformats.org/drawingml/2006/chart\"><c:chart"
               + " xmlns:c=\"http://schemas.openxmlformats.org/drawingml/2006/chart\" xmlns:r=\""
        + REL
        + "\" r:id=\"chart\"/></a:graphicData></a:graphic></xdr:graphicFrame><xdr:clientData/></xdr:oneCellAnchor></xdr:wsDr>";
  }

  private static String chart(ReportSnapshot s) {
    var phases = chartPhases(s);
    StringBuilder x =
        new StringBuilder(
            "<?xml version=\"1.0\"?><c:chartSpace"
                + " xmlns:c=\"http://schemas.openxmlformats.org/drawingml/2006/chart\""
                + " xmlns:a=\"http://schemas.openxmlformats.org/drawingml/2006/main\"><c:lang"
                + " val=\"en-US\"/><c:chart><c:autoTitleDeleted"
                + " val=\"1\"/><c:plotArea><c:layout/><c:barChart><c:barDir"
                + " val=\"col\"/><c:grouping val=\"clustered\"/>");
    String[] keys = {"averageMs", "p90Ms", "p95Ms"},
        labels = {"Average (ms)", "P90 (ms)", "P95 (ms)"},
        colors = {"475569", "4F83B0", "459875"};
    for (int i = 0; i < 3; i++) {
      x.append("<c:ser><c:idx val=\"")
          .append(i)
          .append("\"/><c:order val=\"")
          .append(i)
          .append("\"/><c:tx><c:v>")
          .append(labels[i])
          .append("</c:v></c:tx><c:spPr><a:solidFill><a:srgbClr val=\"")
          .append(colors[i])
          .append(
              "\"/></a:solidFill><a:ln><a:noFill/></a:ln></c:spPr><c:cat><c:strLit><c:ptCount"
                  + " val=\"")
          .append(phases.size())
          .append("\"/>");
      for (int j = 0; j < phases.size(); j++)
        x.append("<c:pt idx=\"")
            .append(j)
            .append("\"><c:v>")
            .append(xml(displayPhase(phases.get(j).name())))
            .append("</c:v></c:pt>");
      x.append(
              "</c:strLit></c:cat><c:val><c:numLit><c:formatCode>0.00</c:formatCode><c:ptCount"
                  + " val=\"")
          .append(phases.size())
          .append("\"/>");
      for (int j = 0; j < phases.size(); j++)
        x.append("<c:pt idx=\"")
            .append(j)
            .append("\"><c:v>")
            .append(phases.get(j).metrics().get(keys[i]))
            .append("</c:v></c:pt>");
      x.append("</c:numLit></c:val></c:ser>");
    }
    x.append(
        "<c:gapWidth val=\"80\"/><c:overlap val=\"0\"/><c:axId val=\"1\"/><c:axId"
            + " val=\"2\"/></c:barChart><c:catAx><c:axId val=\"1\"/><c:scaling><c:orientation"
            + " val=\"minMax\"/></c:scaling><c:axPos val=\"b\"/><c:tickLblPos"
            + " val=\"nextTo\"/><c:crossAx val=\"2\"/><c:crosses"
            + " val=\"autoZero\"/></c:catAx><c:valAx><c:axId val=\"2\"/><c:scaling><c:orientation"
            + " val=\"minMax\"/><c:min val=\"0\"/></c:scaling><c:axPos"
            + " val=\"l\"/><c:majorGridlines/><c:numFmt formatCode=\"#,##0\""
            + " sourceLinked=\"0\"/><c:tickLblPos val=\"nextTo\"/><c:crossAx val=\"1\"/><c:crosses"
            + " val=\"autoZero\"/><c:crossBetween"
            + " val=\"between\"/></c:valAx></c:plotArea><c:legend><c:legendPos"
            + " val=\"b\"/><c:layout/></c:legend><c:plotVisOnly val=\"1\"/><c:dispBlanksAs"
            + " val=\"gap\"/></c:chart><c:spPr><a:solidFill><a:srgbClr"
            + " val=\"FFFFFF\"/></a:solidFill><a:ln><a:noFill/></a:ln></c:spPr><c:txPr><a:bodyPr/><a:lstStyle/><a:p><a:pPr><a:defRPr"
            + " sz=\"1000\"><a:latin typeface=\"Arial\"/></a:defRPr></a:pPr><a:endParaRPr"
            + " lang=\"en-US\"/></a:p></c:txPr></c:chartSpace>");
    return x.toString();
  }

  private static String styles() {
    StringBuilder x =
        new StringBuilder(
            "<?xml version=\"1.0\"?><styleSheet xmlns=\""
                + NS
                + "\"><numFmts count=\"5\"><numFmt numFmtId=\"164\" formatCode=\"#,##0\"/><numFmt"
                + " numFmtId=\"165\" formatCode=\"#,##0.00\"/><numFmt numFmtId=\"166\""
                + " formatCode=\"0.00&quot;%&quot;\"/><numFmt numFmtId=\"167\""
                + " formatCode=\"yyyy-mm-dd hh:mm:ss\"/><numFmt numFmtId=\"168\""
                + " formatCode=\"[h]:mm:ss\"/></numFmts><fonts count=\"6\">");
    x.append(font(10, "253041", false, false))
        .append(font(14, "253041", true, false))
        .append(font(10, "FFFFFF", true, false))
        .append(font(10, "253041", true, false))
        .append(font(10, "637083", false, true))
        .append(font(10, "A33232", true, false));
    x.append(
        "</fonts><fills count=\"4\"><fill><patternFill"
            + " patternType=\"none\"/></fill><fill><patternFill"
            + " patternType=\"gray125\"/></fill><fill><patternFill patternType=\"solid\"><fgColor"
            + " rgb=\"FF334155\"/><bgColor indexed=\"64\"/></patternFill></fill><fill><patternFill"
            + " patternType=\"solid\"><fgColor rgb=\"FFF1F4F7\"/><bgColor"
            + " indexed=\"64\"/></patternFill></fill></fills><borders"
            + " count=\"3\"><border><left/><right/><top/><bottom/><diagonal/></border><border><left/><right/><top/><bottom"
            + " style=\"thin\"><color"
            + " rgb=\"FFCBD3DD\"/></bottom><diagonal/></border><border><left/><right/><top"
            + " style=\"thin\"><color"
            + " rgb=\"FF9AA9BA\"/></top><bottom/><diagonal/></border></borders><cellStyleXfs"
            + " count=\"1\"><xf numFmtId=\"0\" fontId=\"0\" fillId=\"0\""
            + " borderId=\"0\"/></cellStyleXfs><cellXfs count=\"17\">");
    int[][] specs = {
      {0, 0, 0, 0},
      {0, 1, 0, 1},
      {0, 2, 2, 0},
      {0, 3, 3, 0},
      {164, 0, 0, 0},
      {165, 0, 0, 0},
      {166, 0, 0, 0},
      {167, 0, 0, 0},
      {0, 4, 0, 0},
      {0, 2, 2, 0},
      {0, 3, 3, 2},
      {164, 3, 3, 2},
      {165, 3, 3, 2},
      {166, 3, 3, 2},
      {0, 3, 0, 0},
      {164, 5, 0, 0},
      {168, 0, 0, 0}
    };
    for (int i = 0; i < specs.length; i++) {
      int[] v = specs[i];
      x.append("<xf numFmtId=\"")
          .append(v[0])
          .append("\" fontId=\"")
          .append(v[1])
          .append("\" fillId=\"")
          .append(v[2])
          .append("\" borderId=\"")
          .append(v[3])
          .append(
              "\" xfId=\"0\" applyNumberFormat=\"1\" applyFont=\"1\" applyFill=\"1\""
                  + " applyBorder=\"1\" applyAlignment=\"1\"><alignment vertical=\"center\""
                  + " horizontal=\"")
          .append(i == HEADER ? "center" : v[0] > 0 ? "right" : "left")
          .append("\" wrapText=\"")
          .append(v[0] > 0 ? 0 : 1)
          .append("\"/></xf>");
    }
    return x.append(
            "</cellXfs><cellStyles count=\"1\"><cellStyle name=\"Normal\" xfId=\"0\""
                + " builtinId=\"0\"/></cellStyles><dxfs count=\"1\"><dxf><font><b/><color"
                + " rgb=\"FFA33232\"/></font></dxf></dxfs></styleSheet>")
        .toString();
  }

  private static String font(int size, String color, boolean bold, boolean italic) {
    return "<font>"
        + (bold ? "<b/>" : "")
        + (italic ? "<i/>" : "")
        + "<sz val=\""
        + size
        + "\"/><color rgb=\"FF"
        + color
        + "\"/><name val=\"Arial\"/></font>";
  }

  private static String relationships(String body) {
    return "<?xml version=\"1.0\"?><Relationships"
        + " xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">"
        + body
        + "</Relationships>";
  }

  private static void override(StringBuilder s, String part, String type) {
    s.append("<Override PartName=\"")
        .append(part)
        .append("\" ContentType=\"")
        .append(type)
        .append("\"/>");
  }

  private static void entry(ZipOutputStream zip, String name, String text) throws IOException {
    zip.putNextEntry(new ZipEntry(name));
    zip.write(text.getBytes(StandardCharsets.UTF_8));
    zip.closeEntry();
  }

  private static String column(int c) {
    String s = "";
    for (c++; c > 0; c = (c - 1) / 26) s = (char) ('A' + (c - 1) % 26) + s;
    return s;
  }

  public static String xml(String s) {
    StringBuilder out = new StringBuilder();
    s.codePoints()
        .filter(
            c ->
                c == 9
                    || c == 10
                    || c == 13
                    || c >= 32 && c <= 0xD7FF
                    || c >= 0xE000 && c <= 0xFFFD
                    || c >= 0x10000 && c <= 0x10FFFF)
        .limit(32000)
        .forEach(out::appendCodePoint);
    return out.toString()
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
        .replace("'", "&apos;");
  }
}
