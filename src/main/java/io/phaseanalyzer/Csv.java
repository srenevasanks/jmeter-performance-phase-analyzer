package io.phaseanalyzer;

import java.io.*;
import java.util.*;

/** RFC 4180 reader, including quoted commas, doubled quotes, and multiline labels. */
public final class Csv {
  private Csv() {}

  public static List<String> read(PushbackReader r) throws IOException {
    List<String> fields = new ArrayList<>();
    StringBuilder b = new StringBuilder();
    boolean quoted = false, seen = false, closed = false;
    for (int c; (c = r.read()) != -1; ) {
      seen = true;
      if (quoted) {
        if (c == '"') {
          int next = r.read();
          if (next == '"') b.append('"');
          else {
            quoted = false;
            closed = true;
            if (next != -1) r.unread(next);
          }
        } else b.append((char) c);
      } else if (c == ',' || c == '\n' || c == '\r') {
        fields.add(b.toString());
        b.setLength(0);
        closed = false;
        if (c != ',') {
          if (c == '\r') {
            int n = r.read();
            if (n != '\n' && n != -1) r.unread(n);
          }
          return fields;
        }
      } else if (c == '"' && b.length() == 0 && !closed) quoted = true;
      else {
        if (closed) throw new IOException("Unexpected text after closing quote");
        b.append((char) c);
      }
    }
    if (quoted) throw new IOException("Truncated quoted CSV record");
    if (!seen) return null;
    fields.add(b.toString());
    return fields;
  }

  public static String row(Object... values) {
    StringJoiner j = new StringJoiner(",");
    for (Object v : values) {
      String s = Objects.toString(v, "");
      j.add("\"" + s.replace("\"", "\"\"") + "\"");
    }
    return j + "\n";
  }
}
