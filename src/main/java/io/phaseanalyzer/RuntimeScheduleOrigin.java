package io.phaseanalyzer;

import java.lang.reflect.Field;
import org.apache.jmeter.threads.*;

/** Installed-runtime schedule clocks, never response times or measured load. */
final class RuntimeScheduleOrigin {
  private RuntimeScheduleOrigin() {}
  static Long read(AbstractThreadGroup group, Schedule schedule, long testStart) {
    String type = group.getClass().getName();
    if (type.equals("org.apache.jmeter.threads.openmodel.OpenModelThreadGroup")) return testStart;
    JMeterContext context = JMeterContextService.getContext();
    JMeterThread thread = context.getThread();
    if (thread == null || context.getThreadGroup() != group) return null;
    if (group instanceof org.apache.jmeter.threads.ThreadGroup && thread.getEndTime() > 0)
      return thread.getEndTime() - schedule.end();
    if ((type.equals("kg.apc.jmeter.threads.UltimateThreadGroup") || type.equals("kg.apc.jmeter.threads.SteppingThreadGroup"))
        && thread.getStartTime() > 0 && thread.getThreadNum() < schedule.threads().size())
      return thread.getStartTime() - schedule.threads().get(thread.getThreadNum()).start();
    if (type.equals("com.blazemeter.jmeter.threads.concurrency.ConcurrencyThreadGroup")
        || type.equals("com.blazemeter.jmeter.threads.arrivals.ArrivalsThreadGroup")
        || type.equals("com.blazemeter.jmeter.threads.arrivals.FreeFormArrivalsThreadGroup")) {
      // CTG 3.1.1 exposes no public epoch accessor. These protected fields/units were
      // checked against the installed bytecode and real schedule facts. Fail visibly
      // if a future implementation changes them; do not guess a schedule clock.
      try {
        Object starter = field(group, "threadStarter");
        double nativeStart = ((Number) field(starter, "startTime")).doubleValue();
        if (!Double.isFinite(nativeStart) || nativeStart <= 0) return null;
        return Math.round(nativeStart * (type.contains(".arrivals.") ? 1000 : 1));
      } catch (ReflectiveOperationException | RuntimeException unavailable) { return null; }
    }
    return null;
  }
  static Object field(Object value, String name) throws ReflectiveOperationException {
    if (value == null) throw new NoSuchFieldException(name);
    for (Class<?> type = value.getClass(); type != null; type = type.getSuperclass()) {
      try { Field field = type.getDeclaredField(name); field.setAccessible(true); return field.get(value); }
      catch (NoSuchFieldException missing) { /* next superclass */ }
    }
    throw new NoSuchFieldException(name);
  }
}
