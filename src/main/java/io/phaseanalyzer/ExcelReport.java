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

  private record Sheet(List<List<Object>> rows, boolean aggregate, String color) {}

  private ExcelReport() {}

  public static void write(ReportSnapshot snapshot, Path destination) throws IOException {
    List<Sheet> sheets = new ArrayList<>();
    List<List<Object>> summary = new ArrayList<>();
    row(summary, "Performance Phase Analyzer — " + snapshot.metadata().get("executionStatus"));
    row(summary, "Snapshot ID", snapshot.snapshotId());
    for (var e : snapshot.metadata().entrySet())
      if (!List.of("groups", "threadGroups", "warnings").contains(e.getKey()))
        row(summary, e.getKey(), e.getValue());
    var complete = snapshot.phase("Complete execution");
    if (complete != null)
      for (var e : complete.metrics().entrySet()) row(summary, e.getKey(), e.getValue());
    row(
        summary,
        "Phase",
        "Status",
        "Configured sec",
        "Captured sec",
        "Samples",
        "Average ms",
        "P95 ms",
        "Error %",
        "Samples/sec");
    for (var p : snapshot.phases())
      row(
          summary,
          p.name(),
          p.status(),
          p.configuredSeconds(),
          p.capturedSeconds(),
          p.metrics().get("samples"),
          p.metrics().get("averageMs"),
          p.metrics().get("p95Ms"),
          p.metrics().get("errorPercent"),
          p.metrics().get("samplesPerSecond"));
    row(summary, "Unclassified captured samples", snapshot.unclassified());
    row(summary, "Methodology", snapshot.analysis().methodology());
    sheets.add(new Sheet(summary, false, "64748B"));
    int phaseIndex = 0;
    for (String phase : ReportSnapshot.PHASES) {
      List<List<Object>> rows = new ArrayList<>();
      var p = snapshot.phase(phase);
      row(rows, phase + " — " + (p == null ? "Unavailable" : p.status()));
      row(
          rows,
          "Units: milliseconds, samples/second, KB=1024 bytes. Snapshot " + snapshot.snapshotId());
      rows.add(new ArrayList<>(HEADERS));
      boolean found = false;
      for (var v : snapshot.analysis().views())
        if (v.window().phase().equals(phase)) {
          found = true;
          for (var metric : v.rows()) {
            List<Object> r = new ArrayList<>();
            r.add(snapshot.settings().group());
            for (String key : KEYS)
              r.add(key.equals("label") ? v.kind() + " / " + metric.get(key) : metric.get(key));
            rows.add(r);
          }
        }
      if (!found)
        row(
            rows,
            p == null ? "Unavailable" : p.status(),
            p == null
                ? "No data"
                : p.explanation().isBlank()
                    ? "No captured results for this phase"
                    : p.explanation());
      sheets.add(
          new Sheet(rows, true, List.of("E7A13B", "24A777", "E16C75", "4B8FD8").get(phaseIndex++)));
    }
    List<List<Object>> codes = new ArrayList<>();
    row(codes, "Recorded response codes (sampler codes are not assumed HTTP)");
    row(codes, "Snapshot ID", snapshot.snapshotId());
    row(codes, "Response code", "Description", "Count", "Percentage");
    Object rawCodes = complete == null ? null : complete.metrics().get("responseCodes");
    long n =
        complete == null || !(complete.metrics().get("samples") instanceof Number num)
            ? 0
            : num.longValue();
    if (rawCodes instanceof Map<?, ?> map)
      for (var e : map.entrySet())
        row(
            codes,
            String.valueOf(e.getKey()),
            "Not supplied by source",
            e.getValue(),
            n == 0 ? null : ((Number) e.getValue()).doubleValue() * 100 / n);
    sheets.add(new Sheet(codes, false, "64748B"));
    List<List<Object>> config = new ArrayList<>();
    row(config, "Execution configuration");
    row(config, "Snapshot ID", snapshot.snapshotId());
    row(config, "Property", "Value");
    for (var e : snapshot.metadata().entrySet()) row(config, e.getKey(), e.getValue());
    for (var p : snapshot.phases()) {
      row(config, p.name() + " configured windows", p.configured());
      row(config, p.name() + " effective windows", p.effective());
    }
    for (String warning : snapshot.analysis().warnings()) row(config, "Data warning", warning);
    sheets.add(new Sheet(config, false, "64748B"));
    Path parent = destination.toAbsolutePath().getParent();
    Files.createDirectories(parent);
    Path temp = Files.createTempFile(parent, "phase-xlsx-", ".tmp");
    try {
      try (ZipOutputStream zip =
          new ZipOutputStream(Files.newOutputStream(temp), StandardCharsets.UTF_8)) {
        entry(zip, "[Content_Types].xml", contentTypes());
        entry(
            zip,
            "_rels/.rels",
            "<?xml version=\"1.0\" encoding=\"UTF-8\"?><Relationships"
                + " xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\"><Relationship"
                + " Id=\"rId1\""
                + " Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument\""
                + " Target=\"xl/workbook.xml\"/></Relationships>");
        StringBuilder workbook =
            new StringBuilder(
                "<?xml version=\"1.0\" encoding=\"UTF-8\"?><workbook"
                    + " xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\""
                    + " xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\"><sheets>");
        StringBuilder relations =
            new StringBuilder(
                "<?xml version=\"1.0\"?><Relationships"
                    + " xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">");
        for (int i = 0; i < 7; i++) {
          workbook
              .append("<sheet name=\"")
              .append(xml(SHEETS.get(i)))
              .append("\" sheetId=\"")
              .append(i + 1)
              .append("\" r:id=\"rId")
              .append(i + 1)
              .append("\"/>");
          relations
              .append("<Relationship Id=\"rId")
              .append(i + 1)
              .append(
                  "\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet\""
                      + " Target=\"worksheets/sheet")
              .append(i + 1)
              .append(".xml\"/>");
          entry(zip, "xl/worksheets/sheet" + (i + 1) + ".xml", sheet(sheets.get(i), i));
        }
        workbook.append("</sheets></workbook>");
        relations.append(
            "<Relationship Id=\"styles\""
                + " Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles\""
                + " Target=\"styles.xml\"/></Relationships>");
        entry(zip, "xl/workbook.xml", workbook.toString());
        entry(zip, "xl/_rels/workbook.xml.rels", relations.toString());
        entry(zip, "xl/styles.xml", styles());
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

  private static void row(List<List<Object>> rows, Object... values) {
    rows.add(new ArrayList<>(Arrays.asList(values)));
  }

  private static void entry(ZipOutputStream zip, String name, String text) throws IOException {
    zip.putNextEntry(new ZipEntry(name));
    zip.write(text.getBytes(StandardCharsets.UTF_8));
    zip.closeEntry();
  }

  private static String contentTypes() {
    StringBuilder s =
        new StringBuilder(
            "<?xml version=\"1.0\"?><Types"
                + " xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\"><Default"
                + " Extension=\"rels\""
                + " ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/><Default"
                + " Extension=\"xml\" ContentType=\"application/xml\"/><Override"
                + " PartName=\"/xl/workbook.xml\""
                + " ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml\"/><Override"
                + " PartName=\"/xl/styles.xml\""
                + " ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.styles+xml\"/>");
    for (int i = 1; i <= 7; i++)
      s.append("<Override PartName=\"/xl/worksheets/sheet")
          .append(i)
          .append(
              ".xml\""
                  + " ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml\"/>");
    return s.append("</Types>").toString();
  }

  private static String sheet(Sheet sheet, int index) {
    StringBuilder x =
        new StringBuilder(
            "<?xml version=\"1.0\" encoding=\"UTF-8\"?><worksheet"
                + " xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\"><sheetPr><tabColor"
                + " rgb=\"FF"
                + sheet.color()
                + "\"/></sheetPr><sheetViews><sheetView workbookViewId=\"0\"><pane xSplit=\"2\""
                + " ySplit=\"3\" topLeftCell=\"C4\" activePane=\"bottomRight\""
                + " state=\"frozen\"/></sheetView></sheetViews><cols><col min=\"1\" max=\"1\""
                + " width=\"26\" customWidth=\"1\"/><col min=\"2\" max=\"2\" width=\"42\""
                + " customWidth=\"1\"/><col min=\"3\" max=\"17\" width=\"19\""
                + " customWidth=\"1\"/></cols><sheetData>");
    int r = 0;
    for (var values : sheet.rows()) {
      r++;
      x.append("<row r=\"")
          .append(r)
          .append("\" ht=\"")
          .append(r == 3 ? 32 : 22)
          .append("\" customHeight=\"1\">");
      for (int c = 0; c < values.size(); c++) {
        Object value = values.get(c);
        String ref = column(c) + (r);
        boolean header = r == 1 || r == 3,
            total = values.size() > 1 && String.valueOf(values.get(1)).endsWith(" / TOTAL");
        int style = header ? 1 : total ? 3 : value instanceof Number ? 2 : 0;
        if (value instanceof Number num && Double.isFinite(num.doubleValue()))
          x.append("<c r=\"")
              .append(ref)
              .append("\" s=\"")
              .append(style)
              .append("\"><v>")
              .append(num)
              .append("</v></c>");
        else
          x.append("<c r=\"")
              .append(ref)
              .append("\" s=\"")
              .append(style)
              .append("\" t=\"inlineStr\"><is><t xml:space=\"preserve\">")
              .append(xml(value == null ? "N/A" : value.toString()))
              .append("</t></is></c>");
      }
      x.append("</row>");
    }
    x.append("</sheetData>");
    if (sheet.aggregate() && r > 3)
      x.append("<autoFilter ref=\"A3:Q")
          .append(r)
          .append("\"/><conditionalFormatting sqref=\"Q4:Q")
          .append(r)
          .append(
              "\"><cfRule type=\"cellIs\" dxfId=\"0\" priority=\"1\""
                  + " operator=\"greaterThan\"><formula>0</formula></cfRule></conditionalFormatting>");
    if (index == 5 && r > 3) x.append("<autoFilter ref=\"A3:D").append(r).append("\"/>");
    return x.append("</worksheet>").toString();
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
                    || (c >= 32 && c <= 0xD7FF)
                    || (c >= 0xE000 && c <= 0xFFFD)
                    || (c >= 0x10000 && c <= 0x10FFFF))
        .limit(32000)
        .forEach(out::appendCodePoint);
    return out.toString()
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
        .replace("'", "&apos;");
  }

  private static String styles() {
    return "<?xml version=\"1.0\"?><styleSheet"
               + " xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\"><fonts"
               + " count=\"3\"><font><sz val=\"11\"/><name val=\"Calibri\"/></font><font><b/><color"
               + " rgb=\"FFFFFFFF\"/><sz val=\"11\"/><name val=\"Calibri\"/></font><font><b/><sz"
               + " val=\"11\"/><name val=\"Calibri\"/></font></fonts><fills"
               + " count=\"3\"><fill><patternFill patternType=\"none\"/></fill><fill><patternFill"
               + " patternType=\"gray125\"/></fill><fill><patternFill"
               + " patternType=\"solid\"><fgColor rgb=\"FF253B53\"/><bgColor"
               + " indexed=\"64\"/></patternFill></fill></fills><borders"
               + " count=\"1\"><border><left/><right/><top/><bottom/><diagonal/></border></borders><cellStyleXfs"
               + " count=\"1\"><xf numFmtId=\"0\" fontId=\"0\" fillId=\"0\""
               + " borderId=\"0\"/></cellStyleXfs><cellXfs count=\"4\"><xf numFmtId=\"0\""
               + " fontId=\"0\" fillId=\"0\" borderId=\"0\" xfId=\"0\"/><xf numFmtId=\"0\""
               + " fontId=\"1\" fillId=\"2\" borderId=\"0\" xfId=\"0\" applyFill=\"1\"><alignment"
               + " vertical=\"center\" wrapText=\"1\"/></xf><xf numFmtId=\"2\" fontId=\"0\""
               + " fillId=\"0\" borderId=\"0\" xfId=\"0\" applyNumberFormat=\"1\"/><xf"
               + " numFmtId=\"2\" fontId=\"2\" fillId=\"0\" borderId=\"0\" xfId=\"0\""
               + " applyFont=\"1\"/></cellXfs><cellStyles count=\"1\"><cellStyle name=\"Normal\""
               + " xfId=\"0\" builtinId=\"0\"/></cellStyles><dxfs count=\"1\"><dxf><font><color"
               + " rgb=\"FFB42332\"/><b/></font></dxf></dxfs></styleSheet>";
  }
}
