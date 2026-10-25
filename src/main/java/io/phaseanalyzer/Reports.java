package io.phaseanalyzer;

import com.fasterxml.jackson.databind.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

public final class Reports {
  private static final ObjectMapper MAPPER = new ObjectMapper();

  private Reports() {}

  public static ObjectMapper mapper() {
    return MAPPER;
  }

  public static void writeHtml(ReportSnapshot snapshot, Path destination) throws IOException {
    try (InputStream in = Reports.class.getResourceAsStream("/report.html")) {
      if (in == null) throw new IOException("HTML template missing");
      String data =
          MAPPER
              .writeValueAsString(snapshot)
              .replace("<", "\\u003c")
              .replace("\u2028", "\\u2028")
              .replace("\u2029", "\\u2029");
      atomic(
          destination,
          new String(in.readAllBytes(), StandardCharsets.UTF_8).replace("/*REPORT_DATA*/", data));
    }
  }

  public static String exportFilename(ReportSnapshot snapshot, String extension) {
    if (!Set.of("html", "csv", "xlsx").contains(extension))
      throw new IllegalArgumentException("Unsupported report format: " + extension);
    Object raw = snapshot.metadata().get("jmxFilename");
    String name = raw == null || raw.toString().isBlank() ? "JMX-unavailable" : raw.toString();
    name = name.replaceAll("(?i)\\.jmx$", "").replaceAll("[<>:/\\\\|?*\\p{Cntrl}]", "_").replace('"', '_')
        .replaceAll("[. ]+$", "");
    if (name.isBlank()) name = "JMX-unavailable";
    if (name.matches("(?i)(CON|PRN|AUX|NUL|COM[1-9]|LPT[1-9])(?:\\..*)?")) name = "_" + name;
    if (name.length() > 150) name = name.substring(0, 150);
    Object timestamp = snapshot.metadata().get("reportTimestamp");
    if (!(timestamp instanceof Number)) throw new IllegalArgumentException("Execution start unavailable");
    String stamp = java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss")
        .withZone(java.time.ZoneId.of(Objects.toString(snapshot.metadata().get("reportTimeZone"), java.time.ZoneId.systemDefault().getId()))).format(java.time.Instant.ofEpochMilli(((Number) timestamp).longValue()));
    return name + "_" + stamp + "." + extension;
  }

  /** One chosen artifact, no sidecars. Private staging and CREATE_NEW-style final move. */
  public static Path exportSelected(ReportSnapshot snapshot, Path directory, String extension) throws IOException {
    String name = exportFilename(snapshot, extension);
    Files.createDirectories(directory);
    Path staged = Files.createTempFile("phase-export-", "." + extension);
    try {
      switch (extension) {
        case "html" -> writeHtml(snapshot, staged);
        case "csv" -> writeCsv(snapshot, staged);
        case "xlsx" -> ExcelReport.write(snapshot, staged);
        default -> throw new IllegalArgumentException("Unsupported report format");
      }
      int dot = name.lastIndexOf('.');
      for (int index = 0; ; index++) {
        Path destination = directory.resolve(index == 0 ? name : name.substring(0, dot) + "_" + index + name.substring(dot));
        try {
          try { Files.move(staged, destination); }
          catch (AccessDeniedException | java.nio.file.AtomicMoveNotSupportedException denied) {
            // Windows sandbox/volume boundaries may forbid a move; reserve the final path exclusively.
            try (var out = Files.newOutputStream(destination, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
              try { Files.copy(staged, out); }
              catch (IOException failure) { out.close(); Files.deleteIfExists(destination); throw failure; }
            }
          }
          return destination;
        }
        catch (FileAlreadyExistsException collision) { /* Never replace a user's existing file. */ }
      }
    } finally { Files.deleteIfExists(staged); }
  }

  public static void writeCsv(ReportSnapshot snapshot, Path destination) throws IOException {
    LinkedHashSet<String> keys = new LinkedHashSet<>();
    for (var view : snapshot.analysis().views()) for (var row : view.rows()) keys.addAll(row.keySet());
    try (var writer = Files.newBufferedWriter(destination, StandardCharsets.UTF_8)) {
      List<Object> header = new ArrayList<>(List.of("Phase", "Population")); header.addAll(keys);
      writer.write(Csv.row(header.toArray()));
      for (var view : snapshot.analysis().views()) for (var row : view.rows()) {
        List<Object> values = new ArrayList<>(List.of(view.window().phase(), view.kind()));
        for (String key : keys) values.add(Objects.toString(row.get(key), "N/A"));
        writer.write(Csv.row(values.toArray()));
      }
    }
  }

  private static void atomic(Path path, String data) throws IOException {
    Path parent = path.toAbsolutePath().getParent();
    Files.createDirectories(parent);
    Path temp = Files.createTempFile(parent, "phase-report-", ".tmp");
    try {
      Files.writeString(temp, data, StandardCharsets.UTF_8);
      try {
        Files.move(temp, path, StandardCopyOption.REPLACE_EXISTING);
      } catch (AccessDeniedException denied) {
        Files.copy(temp, path, StandardCopyOption.REPLACE_EXISTING);
      }
    } finally {
      Files.deleteIfExists(temp);
    }
  }

  public static void exportSnapshot(ReportSnapshot snapshot, Path directory) throws IOException {
    Files.createDirectories(directory);
    Files.deleteIfExists(directory.resolve("analysis-complete.txt"));
    writeHtml(snapshot, directory.resolve("report.html"));
    ExcelReport.write(snapshot, directory.resolve("report.xlsx"));
    atomic(
        directory.resolve("report.json"),
        MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(snapshot));
    atomic(
        directory.resolve("analysis-complete.txt"),
        "HTML, seven-sheet XLSX and JSON generated from snapshot " + snapshot.snapshotId() + "\n");
  }

  public static Analyzer.Result export(
      Path samples,
      Path destination,
      Analyzer.Settings settings,
      Map<String, Object> metadata,
      Path load)
      throws IOException {
    Files.createDirectories(destination);
    Analyzer.Result result = Analyzer.analyze(samples, settings);
    List<String> diagnostics = new ArrayList<>();
    Object existingWarnings = metadata.get("warnings");
    if (existingWarnings instanceof Collection<?> c)
      for (Object w : c) diagnostics.add(String.valueOf(w));
    for (var group : mapper().valueToTree(metadata).path("groups")) {
      for (var warning : group.path("schedule").path("warnings")) diagnostics.add(warning.asText());
      if (!group.path("warning").asText().isBlank())
        diagnostics.add(group.path("warning").asText());
    }
    metadata = new LinkedHashMap<>(metadata);
    metadata.put("warnings", diagnostics.stream().distinct().toList());
    List<Path> outputs = new ArrayList<>();
    for (String name : List.of("report.json", "report.html", "analysis-complete.txt"))
      outputs.add(destination.resolve(name));
    int outputIndex = 0;
    for (Analyzer.View view : result.views()) {
      String stem = stem(outputIndex++, view);
      outputs.add(destination.resolve(stem + ".csv"));
      outputs.add(destination.resolve(stem + ".jtl"));
    }
    for (Path output : outputs)
      if (sameFile(samples, output) || (load != null && sameFile(load, output)))
        throw new IOException("Export would overwrite original execution data: " + output);
    Files.deleteIfExists(destination.resolve("analysis-complete.txt"));
    Map<String, Object> report = new LinkedHashMap<>();
    report.put("schemaVersion", "1.0");
    report.put("metadata", metadata);
    report.put("settings", settings);
    report.put("analysis", result);
    Window complete =
        settings.windows().stream()
            .filter(w -> w.phase().equals("Complete execution"))
            .findFirst()
            .orElse(settings.windows().get(settings.windows().size() - 1));
    var timeline =
        Timeline.read(
            samples,
            load,
            complete,
            settings.group(),
            settings.mode().equals("Transactions") ? "Transactions" : "Requests",
            1000);
    report.put("timeline", timeline);
    Double target =
        metadata.get("configuredTargetLoad") instanceof Number n ? n.doubleValue() : null;
    LoadAnalysis.enrich(result, load, settings.group(), target);
    if (target != null && target > 0)
      report.put(
          "observedLoad",
          Timeline.detect(
              timeline,
              target,
              metadata.get("tolerancePercent") instanceof Number n ? n.doubleValue() : 5,
              metadata.get("minimumStableSeconds") instanceof Number n ? n.doubleValue() : 10));
    report.put(
        "chartMethodology",
        "At most 600 time bins. Chart P95 is a deterministic 256-value reservoir estimate per bin;"
            + " tables use exact percentiles. Concurrency comes from independent 250 ms engine"
            + " polling; gaps over 1 second are unknown. Distributed global load unavailable."
            + " Binned load statistics and candidates are estimates, not proof of application"
            + " stability.");
    MAPPER
        .writerWithDefaultPrettyPrinter()
        .writeValue(destination.resolve("report.json").toFile(), report);
    String json =
        MAPPER
            .writeValueAsString(report)
            .replace("<", "\\u003c")
            .replace("\u2028", "\\u2028")
            .replace("\u2029", "\\u2029");
    try (InputStream in = Reports.class.getResourceAsStream("/report.html")) {
      if (in == null) throw new IOException("Report template missing");
      String html =
          new String(in.readAllBytes(), StandardCharsets.UTF_8).replace("/*REPORT_DATA*/", json);
      Files.writeString(destination.resolve("report.html"), html, StandardCharsets.UTF_8);
    }
    int index = 0;
    for (Analyzer.View view : result.views()) {
      String stem = stem(index++, view);
      try (BufferedWriter w =
          Files.newBufferedWriter(destination.resolve(stem + ".csv"), StandardCharsets.UTF_8)) {
        List<String> keys = new ArrayList<>(view.rows().get(0).keySet());
        w.write(Csv.row(keys.toArray()));
        for (var row : view.rows())
          w.write(Csv.row(keys.stream().map(k -> Objects.toString(row.get(k), "N/A")).toArray()));
      }
      // Filtered JTL exports normalized captured fields; originals are never overwritten.
      try (BufferedWriter w =
          Files.newBufferedWriter(destination.resolve(stem + ".jtl"), StandardCharsets.UTF_8)) {
        w.write(SampleStore.HEADER);
        SampleStore.scan(
            samples,
            s -> {
              if (view.window().includes(s, settings.strict())
                  && (settings.group().equals("All") || s.group().equals(settings.group()))
                  && s.transaction() == view.kind().equals("Transactions"))
                try {
                  w.write(SampleStore.row(s));
                } catch (IOException e) {
                  throw new UncheckedIOException(e);
                }
            });
      }
    }
    Files.writeString(
        destination.resolve("analysis-complete.txt"),
        "All report formats generated successfully.\n",
        StandardCharsets.UTF_8);
    return result;
  }

  private static String stem(int index, Analyzer.View view) {
    return String.format(
        Locale.ROOT,
        "%02d-%s-%s",
        index,
        view.window().phase().replaceAll("[^a-zA-Z0-9]+", "-"),
        view.kind());
  }

  private static boolean sameFile(Path input, Path output) throws IOException {
    return input.toAbsolutePath().normalize().equals(output.toAbsolutePath().normalize())
        || (Files.exists(input) && Files.exists(output) && Files.isSameFile(input, output));
  }
}
