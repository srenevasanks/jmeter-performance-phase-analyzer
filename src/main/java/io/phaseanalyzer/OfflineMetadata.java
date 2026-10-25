package io.phaseanalyzer;

import java.util.*;

public final class OfflineMetadata {
  private OfflineMetadata() {}

  public record Configuration(
      List<Window> windows,
      Schedule expected,
      long origin,
      Long executionStart,
      Long executionEnd,
      List<String> warnings) {}

  public static Configuration read(Map<String, Object> metadata, String selection) {
    var tree = Reports.mapper().valueToTree(metadata);
    List<RunSession.Group> groups = new ArrayList<>();
    for (var node : tree.path("groups")) {
      RunSession.Group g = Reports.mapper().convertValue(node, RunSession.Group.class);
      if (selection.equals("All") || selection.equals(g.name())) groups.add(g);
    }
    if (groups.isEmpty())
      throw new IllegalArgumentException("Companion metadata has no selected Thread Group");
    for (var g : groups)
      if (g.schedule() == null)
        throw new IllegalArgumentException(
            "Metadata schedule unsupported: " + g.name() + " — use manual windows");
    List<Window> ws = new ArrayList<>();
    List<String> warnings = new ArrayList<>();
    Schedule expected = null;
    long origin = 0;
    if (groups.size() == 1) {
      var g = groups.get(0);
      ws.addAll(g.schedule().windows(g.origin()));
      expected = g.schedule();
      origin = g.origin();
    } else {
      boolean first = true;
      for (var g : groups) {
        var holds =
            g.schedule().windows(g.origin()).stream()
                .filter(w -> w.phase().equals("Steady load"))
                .toList();
        if (first) {
          ws.addAll(holds);
          first = false;
        } else {
          List<Window> next = new ArrayList<>();
          for (var a : ws)
            for (var b : holds) {
              var w = Window.intersection(a, b);
              if (w.end() > w.start()) next.add(w);
            }
          ws = next;
        }
      }
      warnings.add(
          "Offline All groups uses common configured hold intersections; individual ramp phases"
              + " require group selection.");
    }
    Long
        start =
            tree.path("executionStart").isNumber() ? tree.path("executionStart").asLong() : null,
        end = tree.path("executionEnd").isNumber() ? tree.path("executionEnd").asLong() : null;
    if (end != null) {
      List<Window> clipped = new ArrayList<>();
      for (var w : ws) {
        long a = Math.min(w.start(), end), b = Math.min(w.end(), end);
        clipped.add(new Window(w.phase(), a, Math.max(a, b)));
      }
      ws = clipped;
    }
    return new Configuration(ws, expected, origin, start, end, warnings);
  }
}
