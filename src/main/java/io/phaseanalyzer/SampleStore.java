package io.phaseanalyzer;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.function.Consumer;

/** Append-only CSV spool. Original data is immutable during reanalysis. */
public final class SampleStore implements AutoCloseable {
  public static final String HEADER =
      "timeStamp,elapsed,label,threadGroup,engine,success,bytes,sentBytes,Latency,Connect,grpThreads,responseCode,transaction,endTime\n";
  public final Path path;
  private final BufferedWriter writer;
  public volatile long count, first = Long.MAX_VALUE, last;

  public SampleStore(Path path) throws IOException {
    this.path = path;
    Files.createDirectories(path.toAbsolutePath().getParent());
    writer = Files.newBufferedWriter(path, StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
    writer.write(HEADER);
    writer.flush();
  }

  public synchronized void append(Sample s) throws IOException {
    writer.write(row(s));
    count++;
    first = Math.min(first, s.start());
    last = Math.max(last, s.end());
  }

  public synchronized void flush() throws IOException {
    writer.flush();
  }

  @Override
  public synchronized void close() throws IOException {
    writer.close();
  }

  public static String row(Sample s) {
    return Csv.row(
        s.start(),
        s.elapsed(),
        s.label(),
        s.group(),
        s.engine(),
        s.success(),
        s.received(),
        s.sent(),
        s.latency(),
        s.connect(),
        s.threads(),
        s.code(),
        s.transaction(),
        s.end());
  }

  public static List<String> scan(Path path, Consumer<Sample> consumer) throws IOException {
    List<String> warnings = new ArrayList<>();
    try (PushbackReader r =
        new PushbackReader(Files.newBufferedReader(path, StandardCharsets.UTF_8), 1)) {
      List<String> header = Csv.read(r);
      if (header == null) throw new IOException("Empty JTL");
      header.set(0, header.get(0).replace("\uFEFF", ""));
      Map<String, Integer> h = new HashMap<>();
      for (int i = 0; i < header.size(); i++) h.put(header.get(i), i);
      for (String required : List.of("timeStamp", "elapsed", "label", "success"))
        if (!h.containsKey(required))
          throw new IOException(
              "Missing required JTL column: "
                  + required
                  + ". CSV with epoch-millisecond start timestamps is required.");
      for (String optional :
          List.of(
              "threadGroup",
              "engine",
              "bytes",
              "sentBytes",
              "Latency",
              "Connect",
              "grpThreads",
              "transaction"))
        if (!h.containsKey(optional))
          warnings.add(optional + " unavailable; no inference from thread names or labels.");
      long line = 1;
      if (!h.containsKey("endTime"))
        warnings.add(
            "Completion reconstructed as start + elapsed: idle/paused time cannot be recovered;"
                + " strict boundary analysis may be incomplete for such samples.");
      for (List<String> row; (row = Csv.read(r)) != null; ) {
        line++;
        if (row.size() != header.size())
          throw new IOException("Incomplete/corrupt JTL record " + line);
        try {
          String success = get(row, h, "success", "");
          if (!success.equalsIgnoreCase("true") && !success.equalsIgnoreCase("false"))
            throw new IllegalArgumentException("success must be true/false");
          long start = num(row, h, "timeStamp", -1), elapsed = num(row, h, "elapsed", -1);
          consumer.accept(
              new Sample(
                  start,
                  elapsed,
                  get(row, h, "label", ""),
                  get(row, h, "threadGroup", "Unknown"),
                  get(row, h, "engine", "Unknown"),
                  Boolean.parseBoolean(success),
                  num(row, h, "bytes", -1),
                  num(row, h, "sentBytes", -1),
                  num(row, h, "Latency", -1),
                  num(row, h, "Connect", -1),
                  (int) num(row, h, "grpThreads", -1),
                  get(row, h, "responseCode", "Unknown"),
                  Boolean.parseBoolean(get(row, h, "transaction", "false")),
                  num(row, h, "endTime", Math.addExact(start, elapsed))));
        } catch (RuntimeException e) {
          throw new IOException("Invalid JTL record " + line + ": " + e.getMessage(), e);
        }
      }
    }
    return warnings;
  }

  private static String get(List<String> r, Map<String, Integer> h, String key, String def) {
    return h.containsKey(key) ? r.get(h.get(key)) : def;
  }

  private static long num(List<String> r, Map<String, Integer> h, String key, long def) {
    String s = get(r, h, key, "");
    return s.isEmpty() ? def : Long.parseLong(s);
  }
}
