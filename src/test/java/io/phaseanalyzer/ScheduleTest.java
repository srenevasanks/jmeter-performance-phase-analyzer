package io.phaseanalyzer;

import static org.junit.jupiter.api.Assertions.*;

import java.util.*;
import kg.apc.jmeter.threads.*;
import org.apache.jmeter.testelement.property.CollectionProperty;
import org.apache.jmeter.util.JMeterUtils;
import org.junit.jupiter.api.*;

class ScheduleTest {
  @BeforeAll
  static void init() {
    if (JMeterUtils.getJMeterProperties() == null)
      JMeterUtils.loadJMeterProperties("src/test/resources/jmeter-test.properties");
    JMeterUtils.getJMeterProperties().setProperty("threads_schedule", "");
  }

  @Test
  void standardUsesDurationIncludingRamp() throws Exception {
    var g = new org.apache.jmeter.threads.ThreadGroup();
    g.setNumThreads(10);
    g.setRampUp(5);
    g.setScheduler(true);
    g.setDuration(15);
    g.setDelay(2);
    Schedule s = ScheduleAdapters.read(g);
    assertEquals(new Window("Steady load", 7000, 17000), s.windows(0).get(1));
    assertEquals(10, s.load(7000));
    assertEquals(0, s.load(17000));
    g.setScheduler(false);
    assertThrows(IllegalArgumentException.class, () -> ScheduleAdapters.read(g));
  }

  @Test
  void ultimateOverlappingRowsAndDiscreteReverseOrder() throws Exception {
    var g = new UltimateThreadGroup();
    var rows = new CollectionProperty("ultimatethreadgroupdata", new ArrayList<>());
    rows.addItem(new CollectionProperty("row1", List.of("2", "0", "2", "4", "2")));
    rows.addItem(new CollectionProperty("row2", List.of("2", "1", "2", "4", "2")));
    g.setData(rows);
    Schedule s = ScheduleAdapters.read(g);
    assertEquals(4, s.threads().size());
    assertTrue(s.threads().contains(new Schedule.Interval(2000, 8000)));
    assertTrue(s.threads().contains(new Schedule.Interval(1000, 7000)));
    assertEquals(4, s.load(3000));
    assertEquals(3, s.load(7000));
    assertTrue(s.windows(0).contains(new Window("Steady load", 3000, 6000)));
  }

  @Test
  void steppingKnownSchedule() throws Exception {
    var g = new SteppingThreadGroup();
    g.setNumThreads("4");
    g.setInUserCount("2");
    g.setInUserCountBurst("2");
    g.setInUserPeriod("1");
    g.setRampUp("2");
    g.setFlightTime("3");
    g.setOutUserCount("2");
    g.setOutUserPeriod("1");
    g.setThreadGroupDelay("1");
    Schedule s = ScheduleAdapters.read(g);
    assertEquals(
        List.of(
            new Schedule.Interval(1000, 9000),
            new Schedule.Interval(2000, 9000),
            new Schedule.Interval(4000, 10000),
            new Schedule.Interval(5000, 10000)),
        s.threads());
    assertEquals(new Window("Steady load", 6000, 9000), s.windows(0).get(1));
  }

  @Test
  void multipleConfiguredPlateausAreExplicit() {
    var windows =
        ScheduleAdapters.combinedProfile(
            List.of(new long[] {2, 0, 1000, 1000, 1000}, new long[] {3, 5000, 1000, 1000, 1000}));
    assertEquals(2, windows.stream().filter(w -> w.phase().equals("Steady load")).count());
  }
}
