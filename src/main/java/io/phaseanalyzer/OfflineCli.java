package io.phaseanalyzer;

import java.nio.file.*;
import java.util.*;

/** Run with the plugin JAR plus JMeter's lib/* and lib/ext/* on the Java classpath. */
public final class OfflineCli {
  private OfflineCli() {}

  public static void main(String[] args) throws Exception {
    if (args.length < 5) {
      System.err.println(
          "Usage: OfflineCli input.jtl output-directory rampUpSeconds holdSeconds rampDownSeconds"
              + " [delaySeconds] [Requests|Transactions|Separate] [strict] [group]");
      System.exit(2);
    }
    Path file = Path.of(args[0]);
    long[] bounds = {Long.MAX_VALUE, 0};
    List<String> warnings =
        SampleStore.scan(
            file,
            s -> {
              bounds[0] = Math.min(bounds[0], s.start());
              bounds[1] = Math.max(bounds[1], s.end());
            });
    if (bounds[0] == Long.MAX_VALUE) throw new IllegalArgumentException("No samples");
    long up = duration(args[2]),
        hold = duration(args[3]),
        down = duration(args[4]),
        delay = args.length > 5 ? duration(args[5]) : 0,
        start = bounds[0];
    List<Window> ws =
        List.of(
            new Window("Ramp-up", start + delay, start + delay + up),
            new Window("Steady load", start + delay + up, start + delay + up + hold),
            new Window("Ramp-down", start + delay + up + hold, start + delay + up + hold + down),
            new Window("Complete execution", start, bounds[1]));
    Map<String, Object> meta = new LinkedHashMap<>();
    warnings.add(
        "Offline manual reference: earliest recorded sample start. Full configured windows are used"
            + " for phase rate denominators, even if recording ends early.");
    warnings.add(
        "CSV timeStamp must represent epoch-millisecond sample START; end-stamped CSV requires"
            + " conversion before import.");
    meta.put("warnings", warnings);
    meta.put("source", file.toAbsolutePath().toString());
    Path load = file.resolveSibling("load.csv");
    Reports.export(
        file,
        Path.of(args[1]),
        new Analyzer.Settings(
            ws,
            args.length > 8 ? args[8] : "All",
            args.length > 6 ? args[6] : "Requests",
            args.length > 7 && Boolean.parseBoolean(args[7]),
            500,
            2000),
        meta,
        Files.exists(load) ? load : null);
    System.out.println("Exported " + Path.of(args[1]).toAbsolutePath());
  }

  private static long duration(String v) {
    long n = Long.parseLong(v);
    if (n < 0) throw new IllegalArgumentException("Negative duration");
    return Math.multiplyExact(n, 1000);
  }
}
