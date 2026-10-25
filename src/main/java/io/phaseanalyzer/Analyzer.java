package io.phaseanalyzer;

import java.io.*;
import java.nio.file.*;
import java.util.*;

/** The only aggregate calculation path used by UI, HTML, XLSX and legacy exports. */
public final class Analyzer {
  public record Settings(
      List<Window> windows, String group, String mode, boolean strict, long apdexT, long apdexF) {
    public Settings {
      windows = List.copyOf(windows);
      if (apdexT < 0 || apdexF < apdexT)
        throw new IllegalArgumentException("APDEX requires 0 <= T <= F");
      if (!List.of("Requests", "Transactions", "Separate").contains(mode))
        throw new IllegalArgumentException("Invalid aggregation mode");
    }
  }

  public record View(
      Window window,
      String kind,
      long eligible,
      long included,
      long excluded,
      List<Map<String, Object>> rows,
      List<Window> intervals,
      double durationSeconds) {
    public View(
        Window window,
        String kind,
        long eligible,
        long included,
        long excluded,
        List<Map<String, Object>> rows) {
      this(window, kind, eligible, included, excluded, rows, List.of(window), window.seconds());
    }
  }

  public record Result(
      String schemaVersion, String methodology, List<String> warnings, List<View> views) {}

  private record Query(String phase, List<Window> intervals) {}

  public static Result analyze(Path file, Settings settings) throws IOException {
    return calculate(
        file,
        settings,
        settings.windows().stream().map(w -> new Query(w.phase(), List.of(w))).toList());
  }

  public static Result ranges(Path file, Settings settings, Map<String, List<Window>> ranges)
      throws IOException {
    return calculate(
        file,
        settings,
        ranges.entrySet().stream()
            .filter(e -> !e.getValue().isEmpty())
            .map(e -> new Query(e.getKey(), normalize(e.getValue())))
            .toList());
  }

  public static List<Window> normalize(List<Window> input) {
    List<Window> sorted = input.stream().sorted(Comparator.comparingLong(Window::start)).toList();
    List<Window> out = new ArrayList<>();
    for (Window w : sorted) {
      if (out.isEmpty() || out.get(out.size() - 1).end() < w.start()) out.add(w);
      else {
        Window p = out.remove(out.size() - 1);
        out.add(new Window(p.phase(), p.start(), Math.max(p.end(), w.end())));
      }
    }
    return out;
  }

  private static Result calculate(Path file, Settings settings, List<Query> queries)
      throws IOException {
    Path scratch = Files.createTempDirectory("phase-metrics-");
    List<View> views = new ArrayList<>();
    Set<String> warnings = new LinkedHashSet<>();
    try {
      for (Query q : queries)
        for (String kind :
            settings.mode().equals("Separate")
                ? List.of("Requests", "Transactions")
                : List.of(settings.mode())) {
          Map<String, Metrics> labels = new TreeMap<>();
          Metrics total = new Metrics(scratch);
          long[] counts = {0, 0};
          double seconds = q.intervals().stream().mapToDouble(Window::seconds).sum();
          try {
            warnings.addAll(
                SampleStore.scan(
                    file,
                    s -> {
                      if (!settings.group().equals("All") && !settings.group().equals(s.group()))
                        return;
                      if (s.transaction() != kind.equals("Transactions")) return;
                      counts[0]++;
                      if (q.intervals().stream().noneMatch(w -> w.includes(s, settings.strict())))
                        return;
                      counts[1]++;
                      try {
                        Metrics metric = labels.get(s.label());
                        if (metric == null) {
                          if (labels.size() >= 256)
                            throw new IOException(
                                "More than 256 labels in one view; select a Thread Group or reduce"
                                    + " label cardinality. No raw records discarded.");
                          metric = new Metrics(scratch);
                          labels.put(s.label(), metric);
                        }
                        metric.add(s, settings.apdexT(), settings.apdexF());
                        total.add(s, settings.apdexT(), settings.apdexF());
                      } catch (IOException e) {
                        throw new UncheckedIOException(e);
                      }
                    }));
            List<Map<String, Object>> rows = new ArrayList<>();
            for (var entry : labels.entrySet())
              rows.add(entry.getValue().finish(entry.getKey(), seconds));
            rows.add(total.finish("TOTAL", seconds));
            long start = q.intervals().stream().mapToLong(Window::start).min().orElse(0),
                end = q.intervals().stream().mapToLong(Window::end).max().orElse(start);
            views.add(
                new View(
                    new Window(q.phase(), start, end),
                    kind,
                    counts[0],
                    counts[1],
                    counts[0] - counts[1],
                    rows,
                    q.intervals(),
                    seconds));
          } catch (UncheckedIOException e) {
            throw e.getCause();
          } finally {
            total.close();
            for (Metrics m : labels.values()) m.close();
          }
        }
    } finally {
      try (var paths = Files.list(scratch)) {
        for (Path p : paths.toList()) Files.deleteIfExists(p);
      }
      Files.deleteIfExists(scratch);
    }
    return new Result(
        "1.1",
        "Start timestamps and actual completion use epoch milliseconds. Windows are [start,end);"
            + " strict requires completion <= end. Multiple intervals are a disjoint union, counted"
            + " once; denominator is sum of effective interval durations, including idle time."
            + " Exact nearest-rank percentiles; Welford population standard deviation. Rates are"
            + " sampler or transaction records/second, not network requests or arrivals. KB=1024"
            + " bytes. Success uses JMeter isSuccessful including assertions. TOTAL uses underlying"
            + " observations. Missing metrics are null, not zero.",
        new ArrayList<>(warnings),
        views);
  }
}
