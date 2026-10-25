package io.phaseanalyzer;

/** Immutable metadata only: response bodies and assertion objects are never retained. */
public record Sample(
    long start,
    long elapsed,
    String label,
    String group,
    String engine,
    boolean success,
    long received,
    long sent,
    long latency,
    long connect,
    int threads,
    String code,
    boolean transaction,
    long completion) {
  public Sample {
    if (start < 0 || elapsed < 0 || start > Long.MAX_VALUE - elapsed || completion < start)
      throw new IllegalArgumentException("Invalid sample timestamp/duration");
  }

  public long end() {
    return completion;
  }

  public Sample(
      long start,
      long elapsed,
      String label,
      String group,
      String engine,
      boolean success,
      long received,
      long sent,
      long latency,
      long connect,
      int threads,
      String code,
      boolean transaction) {
    this(
        start,
        elapsed,
        label,
        group,
        engine,
        success,
        received,
        sent,
        latency,
        connect,
        threads,
        code,
        transaction,
        Math.addExact(start, elapsed));
  }
}
