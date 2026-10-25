package io.phaseanalyzer;

import java.io.*;
import java.nio.file.*;
import java.util.*;

final class Metrics implements AutoCloseable {
  final ExactQuantiles values;
  long n, failed, min = Long.MAX_VALUE, max, received, sent, latency, connect;
  double mean, m2;
  long satisfied, tolerated;
  boolean hasBytes = true, hasSent = true, hasLatency = true, hasConnect = true;
  final Map<String, Long> codes = new TreeMap<>();

  Metrics(Path dir) throws IOException {
    values = new ExactQuantiles(dir);
  }

  void add(Sample s, long apdexT, long apdexF) throws IOException {
    n++;
    double d = s.elapsed() - mean;
    mean += d / n;
    m2 += d * (s.elapsed() - mean);
    min = Math.min(min, s.elapsed());
    max = Math.max(max, s.elapsed());
    values.add(s.elapsed());
    if (!s.success()) failed++;
    else if (s.elapsed() <= apdexT) satisfied++;
    else if (s.elapsed() <= apdexF) tolerated++;
    if (s.received() < 0) hasBytes = false;
    else received += s.received();
    if (s.sent() < 0) hasSent = false;
    else sent += s.sent();
    if (s.latency() < 0) hasLatency = false;
    else latency += s.latency();
    if (s.connect() < 0) hasConnect = false;
    else connect += s.connect();
    if (codes.size() >= 1000 && !codes.containsKey(s.code()))
      codes.merge("OTHER (code limit)", 1L, Long::sum);
    else codes.merge(s.code(), 1L, Long::sum);
  }

  Map<String, Object> finish(String label, double seconds) throws IOException {
    long[] p = values.finish();
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("label", label);
    m.put("samples", n);
    m.put("successful", n - failed);
    m.put("failed", failed);
    m.put("averageMs", n == 0 ? null : mean);
    m.put("medianMs", n == 0 ? null : p[0]);
    m.put("minMs", n == 0 ? null : min);
    m.put("maxMs", n == 0 ? null : max);
    m.put("stddevMs", n == 0 ? null : Math.sqrt(Math.max(0, m2 / n)));
    m.put("p90Ms", n == 0 ? null : p[1]);
    m.put("p95Ms", n == 0 ? null : p[2]);
    m.put("p99Ms", n == 0 ? null : p[3]);
    m.put("errorPercent", n == 0 ? null : failed * 100.0 / n);
    m.put("samplesPerSecond", seconds <= 0 ? null : n / seconds);
    m.put("receivedKBPerSecond", !hasBytes || seconds <= 0 ? null : received / 1024.0 / seconds);
    m.put("sentKBPerSecond", !hasSent || seconds <= 0 ? null : sent / 1024.0 / seconds);
    m.put("receivedBytes", hasBytes ? received : null);
    m.put("sentBytes", hasSent ? sent : null);
    m.put("averageResponseBytes", !hasBytes || n == 0 ? null : (double) received / n);
    m.put("averageLatencyMs", !hasLatency || n == 0 ? null : (double) latency / n);
    m.put("averageConnectMs", !hasConnect || n == 0 ? null : (double) connect / n);
    m.put("apdex", n == 0 ? null : (satisfied + tolerated * .5) / n);
    m.put("responseCodes", codes);
    return m;
  }

  public void close() throws IOException {
    values.close();
  }
}
