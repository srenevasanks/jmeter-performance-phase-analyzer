package io.phaseanalyzer;

import java.io.*;
import java.nio.file.*;
import java.util.*;

/**
 * One immutable calculation snapshot, shared unchanged by Dashboard, Live Metrics, HTML and XLSX.
 */
public record ReportSnapshot(
    String schemaVersion,
    String snapshotId,
    Map<String, Object> metadata,
    Analyzer.Settings settings,
    Analyzer.Result analysis,
    List<Timeline.Point> timeline,
    List<Expected> expected,
    List<Phase> phases,
    long unclassified) {
  public static final List<String> PHASES =
      List.of("Ramp-up", "Steady load", "Ramp-down", "Complete execution");

  public record Expected(long time, Double value, String unit) {}

  public record Phase(
      String name,
      String status,
      Double configuredSeconds,
      double capturedSeconds,
      Map<String, Object> metrics,
      List<Window> configured,
      List<Window> effective,
      String explanation) {}

  public Phase phase(String name) {
    return phases.stream().filter(p -> p.name().equals(name)).findFirst().orElse(null);
  }

  public Analyzer.View view(String phase) {
    return analysis.views().stream()
        .filter(v -> v.window().phase().equals(phase))
        .findFirst()
        .orElse(null);
  }

  public static ReportSnapshot build(PhaseAnalyzerListener config, RunSession run)
      throws IOException {
    RunSession.CaptureCopy copy = run.copy();
    try {
      return build(config, run, copy);
    } finally {
      Files.deleteIfExists(copy.samples());
      Files.deleteIfExists(copy.load());
    }
  }

  private static ReportSnapshot build(
      PhaseAnalyzerListener config, RunSession run, RunSession.CaptureCopy copy)
      throws IOException {
    Path samples = copy.samples();
    long cut = copy.cutoff(),
        begin = Math.min(run.start, copy.first() == Long.MAX_VALUE ? run.start : copy.first());
    String selected = config.getPropertyAsString("phase.group", "All"),
        kind = config.getPropertyAsString("phase.mode", "Requests");
    List<RunSession.Group> groups =
        copy.groups().values().stream()
            .filter(g -> selected.equals("All") || selected.equals(g.name()))
            .sorted(Comparator.comparing(RunSession.Group::name))
            .toList();
    Map<String, Object> metadata = new LinkedHashMap<>(config.metadata(run));
    List<String> warnings = new ArrayList<>(run.warnings);
    Map<String, List<Window>> configured = new LinkedHashMap<>();
    for (String phase : PHASES) configured.put(phase, new ArrayList<>());
    boolean automatic = config.getPropertyAsBoolean("phase.automatic", true);
    String unsupported = "";
    if (!automatic) {
      Schedule manual =
          Schedule.phases(
              ms(config, "delay"), ms(config, "up"), ms(config, "hold"), ms(config, "down"));
      for (Window w : manual.windows(run.testStart))
        if (w.end() > w.start()) configured.get(w.phase()).add(w);
      metadata.remove("manualSchedule");
    } else if (groups.isEmpty()) {
      unsupported = "Selected Thread Group has no runtime configuration yet.";
      warnings.add(unsupported);
    } else if (groups.size() == 1) {
      RunSession.Group g = groups.get(0);
      if (g.schedule() == null) {
        unsupported = g.warning();
        warnings.add(unsupported);
      } else {
        warnings.addAll(g.schedule().warnings());
        for (Window w : g.schedule().windows(g.origin()))
          if (w.end() > w.start()) configured.get(w.phase()).add(w);
      }
    } else {
      List<Window> common = new ArrayList<>();
      boolean first = true;
      for (var g : groups) {
        if (g.schedule() == null) {
          unsupported = "Combined analysis unavailable: " + g.name() + " — " + g.warning();
          common.clear();
          break;
        }
        List<Window> holds =
            g.schedule().windows(g.origin()).stream()
                .filter(w -> w.phase().equals("Steady load") && w.end() > w.start())
                .toList();
        if (first) {
          common.addAll(holds);
          first = false;
        } else {
          List<Window> next = new ArrayList<>();
          for (var a : common)
            for (var b : holds) {
              Window w = Window.intersection(a, b);
              if (w.end() > w.start()) next.add(w);
            }
          common = next;
        }
      }
      configured.get("Steady load").addAll(common);
      warnings.add(
          "All Thread Groups uses explicit common-window intersection for steady load. Individual"
              + " ramp phases are available by selecting a group; no global ramp periods are"
              + " invented.");
      if (common.isEmpty())
        unsupported =
            "No common configured steady-load interval; select a Thread Group or use an explicit"
                + " manual window.";
    }
    List<Window> holds = configured.get("Steady load");
    boolean ambiguous = holds.size() > 1;
    int plateau = config.getPropertyAsInt("phase.plateau", -1);
    if (ambiguous) {
      if (plateau >= 0 && plateau < holds.size()) {
        Window chosen = holds.get(plateau);
        configured.put("Steady load", new ArrayList<>(List.of(chosen)));
      } else {
        warnings.add(
            "Multiple configured steady-load plateaus. Select one in Phase Configuration; no"
                + " plateau is chosen from measured load.");
      }
    }
    String absoluteStart = config.getPropertyAsString("phase.absoluteStart"),
        absoluteEnd = config.getPropertyAsString("phase.absoluteEnd");
    if (!absoluteStart.isBlank() && !absoluteEnd.isBlank()) {
      configured.put(
          "Steady load",
          new ArrayList<>(
              List.of(
                  new Window(
                      "Steady load", Long.parseLong(absoluteStart), Long.parseLong(absoluteEnd)))));
      ambiguous = false;
    }
    long expectedEnd =
        groups.stream()
            .filter(g -> g.schedule() != null)
            .mapToLong(g -> g.origin() + g.schedule().end())
            .max()
            .orElse(0);
    if (automatic && groups.stream().anyMatch(g -> g.schedule() == null)) expectedEnd = 0;
    if (!automatic)
      expectedEnd =
          run.testStart
              + ms(config, "delay")
              + ms(config, "up")
              + ms(config, "hold")
              + ms(config, "down");
    if (expectedEnd > begin)
      configured
          .get("Complete execution")
          .add(new Window("Complete execution", automatic && groups.size() == 1 && groups.get(0).schedule() != null ? groups.get(0).origin() : run.testStart, expectedEnd));
    Map<String, List<Window>> effective = new LinkedHashMap<>();
    for (String phase : PHASES) {
      List<Window> ranges = new ArrayList<>();
      if (!(phase.equals("Steady load") && ambiguous && plateau < 0)) {
        for (Window w : configured.get(phase)) {
          long a = Math.max(begin, w.start()), b = Math.min(cut, w.end());
          if (b > a) ranges.add(new Window(phase, a, b));
        }
      }
      effective.put(phase, Analyzer.normalize(ranges));
    }
    effective.put(
        "Complete execution",
        List.of(new Window("Complete execution", begin, Math.max(begin, cut))));
    Analyzer.Settings settings =
        new Analyzer.Settings(
            effective.values().stream().flatMap(List::stream).toList(),
            selected,
            kind,
            config.getPropertyAsBoolean("phase.strict"),
            config.getPropertyAsLong("phase.apdexT", 500),
            config.getPropertyAsLong("phase.apdexF", 2000));
    Analyzer.Result result = Analyzer.ranges(samples, settings, effective);
    List<Timeline.Point> points =
        Timeline.read(
            samples,
            copy.load(),
            new Window("Complete execution", begin, Math.max(begin, cut)),
            selected,
            kind.equals("Transactions") ? kind : "Requests",
            1000);
    String state = copy.status();
    if (state.equals("Running")) state = "Partial Report";
    else if (state.equals("Completed")) state = run.testOutcome;
    metadata.put("executionStatus", state);
    metadata.put("executionStart", begin);
    metadata.put("executionEnd", cut);
    metadata.put("testPlan", run.jmxFilename);
    metadata.put(
        "detectionMode",
        automatic ? "Automatic — configuration" : "Manual — explicit time windows");
    metadata.put("selectedThreadGroup", selected);
    metadata.put("capturedSamples", copy.count());
    metadata.put("groups", copy.groups().values());
    metadata.put(
        "population",
        kind.equals("Separate")
            ? "Requests primary; Transactions retained as independent views"
            : kind);
    metadata.put(
        "phaseMethod",
        "Independent group schedules; All uses hold intersection. Non-selected plateaus, initial"
            + " delay, gaps and strict-crossing exclusions remain in Complete and are disclosed as"
            + " unclassified.");
    metadata.put("warnings", warnings.stream().distinct().toList());
    metadata.put(
        "environment",
        Map.of(
            "java",
            System.getProperty("java.version"),
            "os",
            System.getProperty("os.name"),
            "jmeter",
            "5.6.3"));
    String catalog = config.getPropertyAsString("phase.catalog");
    if (!catalog.isBlank())
      try {
        var catalogTree = Reports.mapper().readTree(catalog);
        metadata.put("threadGroups", catalogTree);
        for (var node : catalogTree)
          if (node.path("key").asText().equals(selected))
            metadata.put("selectedThreadGroupLabel", node.path("label").asText());
      } catch (Exception e) {
        warnings.add("Saved discovery metadata invalid: " + e.getMessage());
      }
    metadata.put("warnings", warnings.stream().distinct().toList());
    List<Expected> expected = new ArrayList<>();
    if (automatic && !groups.isEmpty() && groups.stream().allMatch(g -> g.schedule() != null)) {
      Set<String> units = new HashSet<>();
      for (var g : groups) units.add(g.schedule().loadUnit());
      if (units.size() == 1)
        for (int i = 0; i <= 600; i++) {
          long time = begin + (Math.max(cut, expectedEnd) - begin) * i / 600;
          double value = 0;
          for (var g : groups) value += g.schedule().expected(time - g.origin());
          expected.add(
              new Expected(time, Double.isFinite(value) ? value : null, units.iterator().next()));
        }
      else
        warnings.add(
            "Configured workload lines use incompatible units; no combined workload curve is"
                + " fabricated.");
    }
    List<Phase> phases = new ArrayList<>();
    long classified = 0, complete = 0;
    for (String name : PHASES) {
      List<Window> ranges = configured.get(name);
      Analyzer.View view =
          result.views().stream()
              .filter(v -> v.window().phase().equals(name))
              .findFirst()
              .orElse(null);
      Map<String, Object> metrics =
          view == null
              ? Map.of()
              : Collections.unmodifiableMap(view.rows().get(view.rows().size() - 1));
      long n = view == null ? 0 : view.included();
      if (name.equals("Complete execution")) complete = n;
      else classified += n;
      Double seconds =
          ranges.isEmpty()
              ? null
              : Analyzer.normalize(ranges).stream().mapToDouble(Window::seconds).sum();
      String status;
      if (name.equals("Complete execution"))
        status = state.equals("Partial Report") ? "Running" : state;
      else if (name.equals("Steady load") && ambiguous && plateau < 0) status = "Unavailable";
      else if (ranges.isEmpty()) status = unsupported.isBlank() ? "Not configured" : "Unavailable";
      else if (cut <= ranges.stream().mapToLong(Window::start).min().orElse(cut))
        status = copy.status().equals("Running") ? "Not started" : "Not captured";
      else if (!copy.status().equals("Running")
          && cut < ranges.stream().mapToLong(Window::end).max().orElse(cut))
        status = "Partially captured";
      else if (cut >= ranges.stream().mapToLong(Window::end).max().orElse(cut))
        status = "Completed";
      else status = "Running";
      phases.add(
          new Phase(
              name,
              status,
              seconds,
              view == null ? 0 : view.durationSeconds(),
              metrics,
              List.copyOf(ranges),
              List.copyOf(effective.get(name)),
              name.equals("Steady load") && ambiguous && plateau < 0
                  ? "Explicit plateau selection required"
                  : unsupported));
    }
    metadata.put("overlappingAnalyticalInclusions", Math.max(0, classified - complete));
    metadata.put("warnings", warnings.stream().distinct().toList());
    return new ReportSnapshot(
        "1.1",
        UUID.randomUUID().toString(),
        Collections.unmodifiableMap(metadata),
        settings,
        result,
        List.copyOf(points),
        List.copyOf(expected),
        List.copyOf(phases),
        Math.max(0, complete - classified));
  }

  private static long ms(PhaseAnalyzerListener c, String key) {
    long value = c.getPropertyAsLong("phase." + key);
    if (value < 0) throw new IllegalArgumentException("Negative " + key);
    return Math.multiplyExact(value, 1000);
  }
}
