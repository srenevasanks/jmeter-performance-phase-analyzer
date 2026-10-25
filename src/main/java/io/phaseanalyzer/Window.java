package io.phaseanalyzer;

public record Window(String phase, long start, long end) {
  public Window {
    if (start < 0 || end < start) throw new IllegalArgumentException("Invalid window " + phase);
  }

  public boolean includes(Sample s, boolean strict) {
    return s.start() >= start && s.start() < end && (!strict || s.end() <= end);
  }

  public double seconds() {
    return (end - start) / 1000.0;
  }

  public static Window intersection(Window a, Window b) {
    long start = Math.max(a.start, b.start), end = Math.min(a.end, b.end);
    return new Window("Steady load", start, Math.max(start, end));
  }
}
