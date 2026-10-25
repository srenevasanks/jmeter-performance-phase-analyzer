package io.phaseanalyzer;

import java.util.*;
import org.apache.jmeter.testelement.property.*;
import org.apache.jmeter.threads.*;

/**
 * Third-party adapters use verified public methods, with no compile-time third-party dependency.
 */
public final class ScheduleAdapters {
  private ScheduleAdapters() {}

  public static Schedule read(AbstractThreadGroup g) throws Exception {
    for (var a :
        List.of(new Standard(), new Ultimate(), new Stepping(), new Dynamic(), new OpenModel()))
      if (a.supports(g)) return a.read(g);
    throw new IllegalArgumentException(
        "Automatic schedule unavailable for "
            + g.getClass().getName()
            + "; use manual windows. Arrival-rate schedules must not be inferred as concurrency.");
  }

  static int integer(Object g, String method) throws Exception {
    return ((Number) g.getClass().getMethod(method).invoke(g)).intValue();
  }

  static void validate(int users, long... values) {
    if (users <= 0 || users > 100000)
      throw new IllegalArgumentException("Schedule supports 1..100000 threads");
    for (long v : values) if (v < 0) throw new IllegalArgumentException("Negative schedule value");
  }

  public static final class Standard implements ThreadGroupScheduleAdapter {
    public boolean supports(AbstractThreadGroup g) {
      return g.getClass() == org.apache.jmeter.threads.ThreadGroup.class
          || g.getClass() == SetupThreadGroup.class
          || g.getClass() == PostThreadGroup.class;
    }

    public Schedule read(AbstractThreadGroup group) {
      var g = (org.apache.jmeter.threads.ThreadGroup) group;
      if (!g.getScheduler() || g.getDuration() <= 0)
        throw new IllegalArgumentException(
            "Standard Thread Group requires scheduler duration for automatic hold; finite loops"
                + " alone do not define hold.");
      int n = g.getNumThreads();
      long delay = g.getDelay() * 1000,
          up = g.getRampUp() * 1000L,
          end = delay + g.getDuration() * 1000;
      validate(n, delay, up, end);
      if (up >= g.getDuration() * 1000)
        throw new IllegalArgumentException(
            "Ramp-up consumes scheduler duration; no configured hold.");
      List<Schedule.Interval> ts = new ArrayList<>();
      for (int i = 0; i < n; i++) ts.add(new Schedule.Interval(delay + up * i / n, end));
      return new Schedule(
          "Standard scheduler (discrete starts; abrupt stop)",
          ts,
          List.of(
              "Standard Thread Group has no configured gradual ramp-down. Native scheduled end"
                  + " minus configured duration anchors the relative schedule after startup."),
          List.of(
              new Window("Ramp-up", delay, delay + up),
              new Window("Steady load", delay + up, end),
              new Window("Ramp-down", end, end)));
    }
  }

  public static final class Dynamic implements ThreadGroupScheduleAdapter {
    public boolean supports(AbstractThreadGroup g) {
      return Set.of(
              "com.blazemeter.jmeter.threads.concurrency.ConcurrencyThreadGroup",
              "com.blazemeter.jmeter.threads.arrivals.ArrivalsThreadGroup",
              "com.blazemeter.jmeter.threads.arrivals.FreeFormArrivalsThreadGroup")
          .contains(g.getClass().getName());
    }

    private double number(Object g, String m) throws Exception {
      return ((Number) g.getClass().getMethod(m).invoke(g)).doubleValue();
    }

    public Schedule read(AbstractThreadGroup g) throws Exception {
      var it = g.propertyIterator();
      while (it.hasNext())
        if (it.next().getStringValue().contains("${"))
          throw new IllegalArgumentException(
              "Dynamic expressions cannot define a fixed analysis schedule; use Manual mode or"
                  + " literal scheduling values.");
      boolean arrivals = g.getClass().getName().contains("arrivals");
      double factor = number(g, "getUnitFactor");
      List<Schedule.Stage> stages = new ArrayList<>();
      List<Window> windows = new ArrayList<>();
      if (g.getClass().getName().contains("FreeForm")) {
        CollectionProperty rows = (CollectionProperty) g.getClass().getMethod("getData").invoke(g);
        long time = 0;
        for (JMeterProperty p : rows) {
          CollectionProperty row = (CollectionProperty) p;
          if (row.size() != 3) throw new IllegalArgumentException("Invalid Free-Form schedule row");
          double from = row.get(0).getDoubleValue() / factor,
              to = row.get(1).getDoubleValue() / factor;
          double seconds = row.get(2).getDoubleValue() * factor;
          if (seconds != (long) seconds)
            throw new IllegalArgumentException(
                "Fractional Free-Form row seconds are ambiguous in casutg 3.1.1 integer offset"
                    + " handling; use Manual");
          long end = Math.addExact(time, Math.round(seconds * 1000));
          addStage(stages, windows, time, end, from, to);
          time = end;
        }
      } else {
        long up = (long) number(g, "getRampUpSeconds") * 1000,
            hold = (long) number(g, "getHoldSeconds") * 1000,
            steps = (long) number(g, "getStepsAsLong");
        double target = number(g, arrivals ? "getTargetLevelFactored" : "getTargetLevelAsDouble");
        if (up < 0 || hold < 0 || steps < 0 || target <= 0 || !Double.isFinite(target))
          throw new IllegalArgumentException("Invalid target or duration");
        if (!arrivals
            && org.apache.jmeter.util.JMeterUtils.getPropDefault(
                    "dynamic_tg.shift_rampup_start", 0L)
                != 0)
          throw new IllegalArgumentException(
              "Shifted dynamic ramp-up is not supported; use Manual");
        if (steps > 10000) throw new IllegalArgumentException("Too many configured steps");
        if (up > 0) {
          if (steps > 0) {
            for (int i = 0; i < steps; i++) {
              double level = target * (i + 1) / steps;
              if (!arrivals) level = Math.round(level);
              stages.add(new Schedule.Stage(up * i / steps, up * (i + 1) / steps, level, level));
            }
          } else stages.add(new Schedule.Stage(0, up, 0, target));
          windows.add(new Window("Ramp-up", 0, up));
        }
        if (hold > 0) {
          stages.add(new Schedule.Stage(up, Math.addExact(up, hold), target, target));
          windows.add(new Window("Steady load", up, up + hold));
        }
      }
      if (stages.isEmpty()) throw new IllegalArgumentException("No configured schedule duration");
      return new Schedule(
          (arrivals ? "Arrivals" : "Concurrency") + " 3.1.1 configured profile",
          List.of(),
          List.of(
              "Configuration snapshot; mid-run dynamic property changes are not followed. Arrival"
                  + " rate is distinct from sample throughput and concurrency; no gradual ramp-down"
                  + " is invented."),
          windows,
          stages,
          arrivals ? "arrivals/sec" : "users");
    }
  }

  static void addStage(
      List<Schedule.Stage> stages,
      List<Window> windows,
      long start,
      long end,
      double from,
      double to) {
    if (end <= start)
      throw new IllegalArgumentException("Schedule stage must have positive duration");
    stages.add(new Schedule.Stage(start, end, from, to));
    if (from > 0 || to > 0)
      windows.add(
          new Window(to > from ? "Ramp-up" : to < from ? "Ramp-down" : "Steady load", start, end));
  }

  public static final class OpenModel implements ThreadGroupScheduleAdapter {
    public boolean supports(AbstractThreadGroup g) {
      return g instanceof org.apache.jmeter.threads.openmodel.OpenModelThreadGroup;
    }

    public Schedule read(AbstractThreadGroup g) throws Exception {
      String expression =
          ((org.apache.jmeter.threads.openmodel.OpenModelThreadGroup) g).getScheduleString();
      if (expression.contains("${"))
        throw new IllegalArgumentException("Unresolved Open Model expression; choose Manual");
      var parsed =
          org.apache.jmeter.threads.openmodel.ThreadScheduleUtils.ThreadSchedule(expression);
      var steps = parsed.getSteps();
      List<Schedule.Stage> stages = new ArrayList<>();
      List<Window> windows = new ArrayList<>();
      long t = 0;
      double rate = 0;
      for (int i = 0; i < steps.size(); i++) {
        var s = steps.get(i);
        if (s instanceof org.apache.jmeter.threads.openmodel.ThreadScheduleStep.RateStep r) {
          rate = r.getRate();
          continue;
        }
        if (s instanceof org.apache.jmeter.threads.openmodel.ThreadScheduleStep.ArrivalsStep a) {
          long end = Math.addExact(t, Math.round(a.getDuration() * 1000));
          if (a.getType().name().equals("PAUSE")) {
            stages.add(new Schedule.Stage(t, end, 0, 0));
          } else {
            double next =
                i + 1 < steps.size()
                        && steps.get(i + 1)
                            instanceof
                            org.apache.jmeter.threads.openmodel.ThreadScheduleStep.RateStep r
                    ? r.getRate()
                    : rate;
            addStage(stages, windows, t, end, rate, next);
          }
          t = end;
        }
      }
      if (stages.isEmpty()) throw new IllegalArgumentException("Open Model has no timed stages");
      return new Schedule(
          "Open Model 5.6.3 native parsed arrival profile",
          List.of(),
          List.of(
              "Random arrivals are a configured rate, not a configured concurrent-user count. Open"
                  + " Model uses JMeter test-start timestamp as its schedule reference."),
          windows,
          stages,
          "arrivals/sec");
    }
  }

  public static final class Ultimate implements ThreadGroupScheduleAdapter {
    public boolean supports(AbstractThreadGroup g) {
      return g.getClass().getName().equals("kg.apc.jmeter.threads.UltimateThreadGroup");
    }

    public Schedule read(AbstractThreadGroup g) throws Exception {
      Object data = g.getClass().getMethod("getData").invoke(g);
      if (!(data instanceof CollectionProperty rows))
        throw new IllegalArgumentException("Ultimate schedule missing");
      List<Schedule.Interval> ts = new ArrayList<>();
      List<long[]> profiles = new ArrayList<>();
      for (JMeterProperty p : rows) {
        if (!(p instanceof CollectionProperty row) || row.size() != 5)
          throw new IllegalArgumentException("Invalid Ultimate schedule row");
        int n = row.get(0).getIntValue();
        long d = row.get(1).getLongValue() * 1000,
            u = row.get(2).getLongValue() * 1000,
            h = row.get(3).getLongValue() * 1000,
            r = row.get(4).getLongValue() * 1000;
        validate(n, d, u, h, r);
        profiles.add(new long[] {n, d, u, h, r});
        if (ts.size() + n > 100000)
          throw new IllegalArgumentException("Too many scheduled threads");
        // casutg schedules remainingThreadCount from n down to 1, not from zero.
        for (int remaining = n; remaining > 0; remaining--)
          ts.add(new Schedule.Interval(d + u * remaining / n, d + u + h + r * remaining / n));
      }
      return new Schedule(
          "Ultimate 3.1.1 discrete combined rows",
          ts,
          List.of(
              "Configured phase windows follow the summed intended row profiles; expected thread"
                  + " counts use exact discrete scheduling. Every constant-load interval is a"
                  + " separate candidate, including cancelling overlapping ramps; application"
                  + " stability is not inferred."),
          combinedProfile(profiles));
    }
  }

  public static final class Stepping implements ThreadGroupScheduleAdapter {
    public boolean supports(AbstractThreadGroup g) {
      return g.getClass().getName().equals("kg.apc.jmeter.threads.SteppingThreadGroup");
    }

    public Schedule read(AbstractThreadGroup g) throws Exception {
      int n = g.getNumThreads(),
          in = integer(g, "getInUserCountAsInt"),
          out = integer(g, "getOutUserCountAsInt"),
          burst = Math.min(integer(g, "getInUserCountBurstAsInt"), n);
      if (in == 0) in = n;
      if (out == 0) out = n;
      if (burst <= 0) burst = in;
      long d = integer(g, "getThreadGroupDelayAsInt") * 1000L,
          period = integer(g, "getInUserPeriodAsInt") * 1000L,
          ramp = integer(g, "getRampUpAsInt") * 1000L,
          hold = integer(g, "getFlightTimeAsInt") * 1000L,
          stop = integer(g, "getOutUserPeriodAsInt") * 1000L;
      validate(n, in, out, burst, d, period, ramp, hold, stop);
      if (in <= 0 || out <= 0) throw new IllegalArgumentException("Invalid stepping counts");
      int iterations = n < burst ? 1 : (int) Math.ceil((double) (n - burst) / in),
          last = (n - burst) % in;
      if (last == 0) last = in;
      long descent = d + iterations * (period + ramp) + (ramp / in) * last + hold;
      List<Schedule.Interval> ts = new ArrayList<>();
      for (int i = 0; i < n; i++) {
        int bucket = i < burst ? 0 : 1 + (i - burst) / in,
            position = i < burst ? i : (i - burst) % in,
            size = i < burst ? burst : in;
        ts.add(
            new Schedule.Interval(
                d + bucket * (period + ramp) + position * (ramp / size),
                descent + stop * (i / out)));
      }
      return new Schedule(
          "Stepping 3.1.1 discrete buckets",
          ts,
          List.of(),
          List.of(
              new Window("Ramp-up", d, descent - hold),
              new Window("Steady load", descent - hold, descent),
              new Window(
                  "Ramp-down",
                  descent,
                  ts.stream().mapToLong(Schedule.Interval::end).max().orElse(descent))));
    }
  }

  static List<Window> combinedProfile(List<long[]> rows) {
    TreeSet<Long> breaks = new TreeSet<>();
    for (long[] r : rows) {
      breaks.add(r[1]);
      breaks.add(r[1] + r[2]);
      breaks.add(r[1] + r[2] + r[3]);
      breaks.add(r[1] + r[2] + r[3] + r[4]);
    }
    List<Long> times = new ArrayList<>(breaks);
    List<Window> windows = new ArrayList<>();
    for (int i = 0; i < times.size() - 1; i++) {
      long a = times.get(i), b = times.get(i + 1);
      double midpoint = a + (b - a) / 2.0, slope = 0, load = 0;
      for (long[] r : rows) {
        long d = r[1], up = r[2], hold = r[3], down = r[4];
        if (midpoint < d || midpoint >= d + up + hold + down) continue;
        if (up > 0 && midpoint < d + up) {
          slope += (double) r[0] / up;
          load += r[0] * (midpoint - d) / up;
        } else if (midpoint < d + up + hold) {
          load += r[0];
        } else if (down > 0) {
          slope -= (double) r[0] / down;
          load += r[0] * (1 - (midpoint - d - up - hold) / down);
        }
      }
      if (load <= 0) continue;
      String phase = Math.abs(slope) < 1e-10 ? "Steady load" : slope > 0 ? "Ramp-up" : "Ramp-down";
      // Do not merge constant plateaus separated by a discontinuous change in load.
      windows.add(new Window(phase, a, b));
    }
    return windows;
  }
}
