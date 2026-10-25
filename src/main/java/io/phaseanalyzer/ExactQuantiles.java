package io.phaseanalyzer;

import java.io.*;
import java.nio.file.*;
import java.util.*;

/** Exact nearest-rank percentiles, external merge sort with bounded buffers and fan-in. */
final class ExactQuantiles implements AutoCloseable {
  final Path dir, raw;
  final DataOutputStream out;
  long count;

  ExactQuantiles(Path dir) throws IOException {
    this.dir = dir;
    raw = Files.createTempFile(dir, "values-", ".bin");
    out = new DataOutputStream(new BufferedOutputStream(Files.newOutputStream(raw)));
  }

  void add(long v) throws IOException {
    out.writeLong(v);
    count++;
  }

  long[] finish() throws IOException {
    out.close();
    if (count == 0) {
      Files.delete(raw);
      return new long[4];
    }
    List<Path> runs = new ArrayList<>();
    try (DataInputStream in =
        new DataInputStream(new BufferedInputStream(Files.newInputStream(raw)))) {
      long left = count;
      while (left > 0) {
        int n = (int) Math.min(left, 65536);
        long[] a = new long[n];
        for (int i = 0; i < n; i++) a[i] = in.readLong();
        Arrays.sort(a);
        Path p = Files.createTempFile(dir, "sort-", ".bin");
        try (DataOutputStream o =
            new DataOutputStream(new BufferedOutputStream(Files.newOutputStream(p)))) {
          for (long v : a) o.writeLong(v);
        }
        runs.add(p);
        left -= n;
      }
    }
    Files.delete(raw);
    while (runs.size() > 1) {
      List<Path> next = new ArrayList<>();
      for (int i = 0; i < runs.size(); i += 32)
        next.add(merge(runs.subList(i, Math.min(i + 32, runs.size()))));
      runs = next;
    }
    long[]
        ranks =
            {
              Math.max(1, (long) Math.ceil(count * .5)),
              (long) Math.ceil(count * .9),
              (long) Math.ceil(count * .95),
              (long) Math.ceil(count * .99)
            },
        result = new long[4];
    try (DataInputStream in =
        new DataInputStream(new BufferedInputStream(Files.newInputStream(runs.get(0))))) {
      int q = 0;
      for (long i = 1; i <= count; i++) {
        long v = in.readLong();
        while (q < 4 && ranks[q] == i) result[q++] = v;
      }
    }
    Files.delete(runs.get(0));
    return result;
  }

  private Path merge(List<Path> paths) throws IOException {
    record Head(long value, int index) {}
    List<DataInputStream> ins = new ArrayList<>();
    PriorityQueue<Head> heap = new PriorityQueue<>(Comparator.comparingLong(Head::value));
    Path p = Files.createTempFile(dir, "merge-", ".bin");
    try (DataOutputStream o =
        new DataOutputStream(new BufferedOutputStream(Files.newOutputStream(p)))) {
      for (Path source : paths) {
        DataInputStream in =
            new DataInputStream(new BufferedInputStream(Files.newInputStream(source)));
        ins.add(in);
        try {
          heap.add(new Head(in.readLong(), ins.size() - 1));
        } catch (EOFException ignored) {
        }
      }
      while (!heap.isEmpty()) {
        Head h = heap.remove();
        o.writeLong(h.value());
        try {
          heap.add(new Head(ins.get(h.index()).readLong(), h.index()));
        } catch (EOFException ignored) {
        }
      }
    } finally {
      for (var in : ins) in.close();
      for (Path source : paths) Files.delete(source);
    }
    return p;
  }

  public void close() throws IOException {
    out.close();
    Files.deleteIfExists(raw);
  }
}
