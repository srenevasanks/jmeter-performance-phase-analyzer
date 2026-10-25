package io.phaseanalyzer;

import java.io.*;
import java.nio.file.*;
import java.util.*;

/** Time-weighted active threads from independent engine polling, not request counts. */
public final class LoadAnalysis {
  private LoadAnalysis() {}

  public record Stats(
      Double averageActiveThreads, Integer peakActiveThreads, double coveredSeconds) {}

  public static Stats analyze(Path load, Window w, String group, long maxGap) throws IOException {
    if (load == null || !Files.isRegularFile(load)) return new Stats(null, null, 0);
    Map<String, long[]> previous = new HashMap<>();
    Map<String, Double> area = new HashMap<>(), covered = new HashMap<>();
    Map<String, Integer> peaks = new HashMap<>();
    try (PushbackReader r = new PushbackReader(Files.newBufferedReader(load), 1)) {
      Csv.read(r);
      for (List<String> row; (row = Csv.read(r)) != null; ) {
        if (row.size() != 3) throw new IOException("Invalid runtime load record");
        String g = row.get(1);
        if (!group.equals("All") && !g.equals(group)) continue;
        long t = Long.parseLong(row.get(0));
        int n = Integer.parseInt(row.get(2));
        long[] p = previous.put(g, new long[] {t, n});
        if (t >= w.start() && t < w.end()) peaks.merge(g, n, Math::max);
        if (p != null && t >= p[0] && t - p[0] <= maxGap) {
          long a = Math.max(w.start(), p[0]), b = Math.min(w.end(), t);
          if (b > a) {
            area.merge(g, (double) (b - a) * p[1], Double::sum);
            covered.merge(g, (b - a) / 1000.0, Double::sum);
            peaks.merge(g, (int) p[1], Math::max);
          }
        }
      }
    }
    if (covered.isEmpty()) return new Stats(null, null, 0);
    if (group.equals("All")
        && covered.size()
            > 1) { // Whole-engine combined peak cannot be a sum of independent per-group peaks.
      return new Stats(
          null, null, covered.values().stream().mapToDouble(Double::doubleValue).min().orElse(0));
    }
    String g = covered.keySet().iterator().next();
    double sec = covered.get(g);
    return new Stats(area.get(g) / (sec * 1000), peaks.get(g), sec);
  }

  public static void enrich(Analyzer.Result result, Path load, String group, Double target)
      throws IOException {
    for (Analyzer.View view : result.views()) {
      Stats stats = analyze(load, view.window(), group, 1000);
      for (Map<String, Object> row : view.rows()) {
        row.put("averageActiveThreads", stats.averageActiveThreads());
        row.put("peakActiveThreads", stats.peakActiveThreads());
        row.put("observedLoad", stats.averageActiveThreads());
        row.put("configuredTargetLoad", target);
        row.put("phaseDurationSeconds", view.window().seconds());
        row.put("loadCoverageSeconds", stats.coveredSeconds());
      }
    }
  }
}
