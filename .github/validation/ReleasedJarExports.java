import java.nio.file.*;
import io.phaseanalyzer.*;

/** Validation fixture only: calls public APIs from the downloaded release JAR. */
public final class ReleasedJarExports {
  public static void main(String[] args) throws Exception {
    for (String name : new String[]{"io.phaseanalyzer.PhaseAnalyzerListener", "io.phaseanalyzer.PhaseAnalyzerGui", "io.phaseanalyzer.Reports"}) {
      Class<?> type = Class.forName(name);
      String origin = type.getProtectionDomain().getCodeSource().getLocation().toString();
      if (!origin.endsWith("jmeter-performance-phase-analyzer-1.0.0.jar")) throw new IllegalStateException("Wrong artifact: " + origin);
      System.out.println(name + " loaded from " + origin);
    }
    Path directory = Path.of(args[0]);
    Path html;
    try (var files = Files.list(directory)) {
      var reports = files.filter(p -> p.toString().endsWith(".html")).toList();
      if (reports.size() != 1) throw new IllegalStateException("Expected one real automatic HTML export: " + reports);
      html = reports.get(0);
    }
    String content = Files.readString(html);
    int start = content.indexOf("const R=");
    if (start < 0) throw new IllegalStateException("Report snapshot missing");
    ReportSnapshot snapshot;
    try (var parser = Reports.mapper().getFactory().createParser(content.substring(start + 8))) {
      snapshot = Reports.mapper().readValue(parser, ReportSnapshot.class);
    }
    System.out.println("HTML: " + html);
    System.out.println("Excel: " + Reports.exportSelected(snapshot, directory, "xlsx"));
  }
}
