package io.phaseanalyzer;

import java.io.*;
import java.nio.file.*;
import java.util.*;

/**
 * At most 600 chart bins; sampled chart percentile is explicitly distinct from exact table
 * percentiles.
 */
public final class Timeline {
  public record Point(
      long time,
      double durationSeconds,
      long samples,
      Double averageMs,
      Double sampledP95Ms,
      Double errorPercent,
      Double active,
      long passed,
      long failed,
      Double sampledP90Ms) {
    public Point(
        long time,
        double durationSeconds,
        long samples,
        Double averageMs,
        Double sampledP95Ms,
        Double errorPercent,
        Double active) {
      this(
          time,
          durationSeconds,
          samples,
          averageMs,
          sampledP95Ms,
          errorPercent,
          active,
          samples,
          samples == 0
              ? 0
              : Math.round(samples * (errorPercent == null ? 0 : errorPercent) / 100.0),
          null);
    }
  }

  public record LoadStats(
      Double average,
      Integer peak,
      double coveredSeconds,
      double withinToleranceSeconds,
      List<Window> candidates) {}

  static final class Bin {
    long n, errors;
    double sum;
    long[] reservoir = new long[256];
    int used;
    double loadIntegral, covered;
    final Map<String, Double> groupCoverage = new HashMap<>();
  }

  public static List<Point> read(
      Path samples, Path load, Window window, String group, String kind, long maxGap)
      throws IOException {
    return read(samples, load, window, group, kind, maxGap, 5);
  }

  public static List<Point> read(
      Path samples,
      Path load,
      Window window,
      String group,
      String kind,
      long maxGap,
      double missingTolerancePercent)
      throws IOException {
    if (maxGap <= 0 || missingTolerancePercent < 0 || missingTolerancePercent > 100)
      throw new IllegalArgumentException("Invalid missing-data tolerance");
    long duration = Math.max(1, window.end() - window.start()),
        step = Math.max(1000, (duration + 599) / 600);
    int size = (int) Math.min(600, (duration + step - 1) / step);
    Bin[] bins = new Bin[size];
    for (int i = 0; i < size; i++) bins[i] = new Bin();
    SampleStore.scan(
        samples,
        s -> {
          if (!window.includes(s, false)
              || (!group.equals("All") && !s.group().equals(group))
              || s.transaction() != kind.equals("Transactions")) return;
          int index = (int) ((s.start() - window.start()) / step);
          if (index >= size) return;
          Bin b = bins[index];
          b.n++;
          b.sum += s.elapsed();
          if (!s.success()) b.errors++;
          // deterministic bounded reservoir (Algorithm R); sampling applies ONLY to chart P95.
          if (b.used < 256) b.reservoir[b.used++] = s.elapsed();
          else {
            long hash = mix(b.n);
            long j = Long.remainderUnsigned(hash, b.n);
            if (j < 256) b.reservoir[(int) j] = s.elapsed();
          }
        });
    if (load != null && Files.isRegularFile(load)) {
      Map<String, long[]> previous = new HashMap<>();
      try (PushbackReader r = new PushbackReader(Files.newBufferedReader(load), 1)) {
        Csv.read(r);
        for (List<String> row; (row = Csv.read(r)) != null; ) {
          if (row.size() != 3) throw new IOException("Invalid load record");
          String name = row.get(1);
          if (!group.equals("All") && !group.equals(name)) continue;
          long t = Long.parseLong(row.get(0)), v = Long.parseLong(row.get(2));
          long[] p = previous.put(name, new long[] {t, v});
          if (p == null || t - p[0] > maxGap || t < p[0]) continue;
          long a = Math.max(window.start(), p[0]), end = Math.min(window.end(), t);
          while (a < end) {
            int idx = (int) ((a - window.start()) / step);
            if (idx >= size) break;
            long b = Math.min(end, window.start() + (idx + 1) * step);
            bins[idx].loadIntegral += (b - a) * p[1];
            bins[idx].covered += b - a;
            bins[idx].groupCoverage.merge(name, (double) (b - a), Double::sum);
            a = b;
          }
        }
      }
      if (group.equals(
          "All")) { // sum per-group time integrals over wall-clock bins, not average engines.
        for (int i = 0; i < size; i++)
          if (bins[i].covered > 0) {
            double binDuration = Math.min(step, window.end() - (window.start() + i * step));
            boolean adequate = true;
            for (String name : previous.keySet())
              if (bins[i].groupCoverage.getOrDefault(name, 0.0)
                  < binDuration * (1 - missingTolerancePercent / 100)) adequate = false;
            bins[i].covered = adequate ? binDuration : 0;
          }
      }
    }
    List<Point> result = new ArrayList<>();
    for (int i = 0; i < size; i++) {
      Bin b = bins[i];
      double binDuration = Math.min(step, window.end() - (window.start() + i * step));
      Arrays.sort(b.reservoir, 0, b.used);
      result.add(
          new Point(
              window.start() + i * step,
              binDuration / 1000.0,
              b.n,
              b.n == 0 ? null : b.sum / b.n,
              b.used == 0 ? null : (double) b.reservoir[(int) Math.ceil(b.used * .95) - 1],
              b.n == 0 ? null : b.errors * 100.0 / b.n,
              b.covered <= 0 || b.covered < binDuration * (1 - missingTolerancePercent / 100)
                  ? null
                  : b.loadIntegral / b.covered,
              b.n - b.errors,
              b.errors,
              b.used == 0 ? null : (double) b.reservoir[(int) Math.ceil(b.used * .90) - 1]));
    }
    return result;
  }

  private static long mix(long v) {
    v = (v ^ (v >>> 30)) * 0xbf58476d1ce4e5b9L;
    v = (v ^ (v >>> 27)) * 0x94d049bb133111ebL;
    return v ^ (v >>> 31);
  }

  public static LoadStats detect(
      List<Point> points, double target, double tolerancePercent, double minSeconds) {
    if (target <= 0 || tolerancePercent < 0 || minSeconds <= 0)
      throw new IllegalArgumentException(
          "Detection requires target > 0, tolerance >= 0, minimum duration > 0");
    List<Window> candidates = new ArrayList<>();
    double sum = 0, covered = 0, within = 0, stable = 0;
    long start = 0;
    int peak = 0;
    for (Point p : points) {
      boolean good =
          p.active() != null && Math.abs(p.active() - target) <= target * tolerancePercent / 100;
      if (p.active() != null) {
        sum += p.active() * p.durationSeconds();
        covered += p.durationSeconds();
        peak = Math.max(peak, (int) Math.ceil(p.active()));
      }
      if (good) {
        within += p.durationSeconds();
        if (stable == 0) start = p.time();
        stable += p.durationSeconds();
      } else if (stable > 0) {
        if (stable >= minSeconds)
          candidates.add(new Window("Observed stable concurrency candidate", start, p.time()));
        stable = 0;
      }
    }
    if (stable >= minSeconds && !points.isEmpty()) {
      Point p = points.get(points.size() - 1);
      candidates.add(
          new Window(
              "Observed stable concurrency candidate",
              start,
              p.time() + (long) (p.durationSeconds() * 1000)));
    }
    return new LoadStats(
        covered == 0 ? null : sum / covered,
        covered == 0 ? null : peak,
        covered,
        within,
        candidates);
  }
}
