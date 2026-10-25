package io.phaseanalyzer;

import java.util.*;

/** Expected thread intervals are discrete, matching scheduling rather than a smooth guess. */
public record Schedule(
    String method,
    List<Interval> threads,
    List<String> warnings,
    List<Window> configured,
    List<Stage> profile,
    String loadUnit) {
  public record Stage(long start, long end, double from, double to) {
    public Stage {
      if (start < 0
          || end < start
          || !Double.isFinite(from)
          || !Double.isFinite(to)
          || from < 0
          || to < 0) throw new IllegalArgumentException("Invalid configured load stage");
    }
  }

  public Schedule {
    profile = profile == null ? List.of() : profile;
    loadUnit = loadUnit == null ? "users" : loadUnit;
  }

  public Schedule(
      String method, List<Interval> threads, List<String> warnings, List<Window> configured) {
    this(method, threads, warnings, configured, List.of(), "users");
  }

  public Schedule(String method, List<Interval> threads, List<String> warnings) {
    this(method, threads, warnings, List.of());
  }

  public record Interval(long start, long end) {
    public Interval {
      if (start < 0 || end < start)
        throw new IllegalArgumentException("Invalid expected thread interval");
    }
  }

  public int load(long relative) {
    int n = 0;
    for (var t : threads) if (relative >= t.start && relative < t.end) n++;
    return n;
  }

  public double expected(long relative) {
    if (!profile.isEmpty()) {
      for (Stage s : profile)
        if (relative >= s.start() && relative < s.end())
          return s.from()
              + (s.to() - s.from()) * (relative - s.start()) / Math.max(1.0, s.end() - s.start());
      return 0;
    }
    if (method.equals("Manual phase windows")) return Double.NaN;
    if (!method.startsWith("Manual")) return load(relative);
    int target = threads.size();
    for (Window w : configured)
      if (relative >= w.start() && relative < w.end()) {
        double fraction = (relative - w.start()) / (double) Math.max(1, w.end() - w.start());
        return w.phase().equals("Ramp-up")
            ? target * fraction
            : w.phase().equals("Ramp-down") ? target * (1 - fraction) : target;
      }
    return 0;
  }

  public List<Window> windows(long origin) {
    if (!configured.isEmpty())
      return configured.stream()
          .map(w -> new Window(w.phase(), origin + w.start(), origin + w.end()))
          .toList();
    TreeMap<Long, Integer> changes = new TreeMap<>();
    for (var t : threads) {
      changes.merge(t.start, 1, Integer::sum);
      changes.merge(t.end, -1, Integer::sum);
    }
    if (changes.isEmpty()) return List.of();
    List<Long> times = new ArrayList<>(changes.keySet());
    List<Window> result = new ArrayList<>();
    int n = 0;
    for (int i = 0; i < times.size() - 1; i++) {
      long t = times.get(i);
      int delta = changes.get(t);
      n += delta;
      if (n == 0 || times.get(i + 1) == t) continue;
      // A plateau qualifies only if no starts or stops occur in its interior. All plateaus are
      // offered explicitly.
      String phase = delta > 0 ? "Ramp-up" : "Ramp-down";
      if (i + 1 < times.size() && changes.get(times.get(i + 1)) < 0 && n == peak())
        phase = "Steady load";
      result.add(new Window(phase, origin + t, origin + times.get(i + 1)));
    }
    return result;
  }

  public int peak() {
    if (!profile.isEmpty())
      return (int)
          Math.ceil(profile.stream().mapToDouble(s -> Math.max(s.from(), s.to())).max().orElse(0));
    TreeMap<Long, Integer> events = new TreeMap<>();
    for (var t : threads) {
      events.merge(t.start, 1, Integer::sum);
      events.merge(t.end, -1, Integer::sum);
    }
    int n = 0, p = 0;
    for (int d : events.values()) {
      n += d;
      p = Math.max(p, n);
    }
    return p;
  }

  public long end() {
    if (!profile.isEmpty()) return profile.get(profile.size() - 1).end();
    if (threads.isEmpty() && !configured.isEmpty())
      return configured.stream().mapToLong(Window::end).max().orElse(0);
    return threads.stream().mapToLong(Interval::end).max().orElse(0);
  }

  public long firstStart() {
    if (!profile.isEmpty()) return profile.get(0).start();
    return threads.stream().mapToLong(Interval::start).min().orElse(0);
  }

  public static Schedule manual(long delay, long up, long hold, long down, int users) {
    if (delay < 0 || up < 0 || hold < 0 || down < 0 || users < 0)
      throw new IllegalArgumentException("Durations and target must be nonnegative");
    if (users > 100000)
      throw new IllegalArgumentException("Target exceeds schedule capacity (100000 threads)");
    List<Interval> ts = new ArrayList<>();
    long descent = Math.addExact(delay, Math.addExact(up, hold));
    for (int i = 0; i < users; i++)
      ts.add(
          new Interval(
              delay + up * i / Math.max(1, users), descent + down * (i + 1) / Math.max(1, users)));
    return new Schedule(
        "Manual illustrative concurrency",
        ts,
        List.of(),
        List.of(
            new Window("Ramp-up", delay, delay + up),
            new Window("Steady load", delay + up, descent),
            new Window("Ramp-down", descent, Math.addExact(descent, down))));
  }

  public static Schedule phases(long delay, long up, long hold, long down) {
    if (delay < 0 || up < 0 || hold < 0 || down < 0)
      throw new IllegalArgumentException("Durations must be nonnegative");
    long a = Math.addExact(delay, up), b = Math.addExact(a, hold), c = Math.addExact(b, down);
    return new Schedule(
        "Manual phase windows",
        List.of(),
        List.of(
            "Manual durations specify analysis windows only; no configured concurrency curve is"
                + " invented."),
        List.of(
            new Window("Ramp-up", delay, a),
            new Window("Steady load", a, b),
            new Window("Ramp-down", b, c)),
        List.of(),
        "unavailable");
  }
}
