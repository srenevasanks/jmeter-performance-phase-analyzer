package io.phaseanalyzer;

import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import org.apache.jmeter.control.TransactionController;
import org.apache.jmeter.engine.util.NoThreadClone;
import org.apache.jmeter.samplers.*;
import org.apache.jmeter.testelement.*;
import org.apache.jmeter.threads.*;

/** JMeter discovers the associated GUI by class scanning; no fictitious service registry. */
public final class PhaseAnalyzerListener extends AbstractTestElement
    implements SampleListener, TestStateListener, ThreadListener, NoThreadClone {
  private static final long serialVersionUID = 1L;
  static final Map<String, RunSession> SESSIONS = new ConcurrentHashMap<>();
  private static final Map<String, PhaseAnalyzerListener> RUNNING = new ConcurrentHashMap<>();
  private transient volatile boolean testRunning;
  private transient long originalTestStart;
  private transient Map<String, org.apache.jmeter.threads.AbstractThreadGroup> activeGroups;
  private transient List<GroupDiscovery.Entry> catalog = List.of();
  private transient boolean parallelSafe = true;
  private transient Map<String, java.util.concurrent.atomic.AtomicInteger> activeCounts = new ConcurrentHashMap<>();
  private transient ExecutionEvidence evidence;

  public PhaseAnalyzerListener() {
    setProperty("phase.id", UUID.randomUUID().toString());
    setProperty("phase.automatic", true);
    setProperty("phase.mode", "Requests");
    setProperty("phase.group", "All");
    setProperty("phase.hold", 60L);
    setProperty("phase.apdexT", 500L);
    setProperty("phase.apdexF", 2000L);
  }

  public RunSession session() {
    return SESSIONS.get(getPropertyAsString("phase.id"));
  }

  public void testStarted() {
    try {
      RunSession old = session();
      if (old != null) { old.finish(); old.cleanup(); }
      if (evidence != null) evidence.close();
      evidence = new ExecutionEvidence();
      catalog = readCatalog();
      parallelSafe =
          !getPropertyAsBoolean("phase.serialized")
              && catalog.stream().filter(GroupDiscovery.Entry::enabled)
                  .noneMatch(
                      g ->
                          g.implementation().endsWith("SetupThreadGroup")
                              || g.implementation().endsWith("PostThreadGroup"));
      long jmStart = JMeterContextService.getTestStartTime();
      if (jmStart <= 0 || Math.abs(System.currentTimeMillis() - jmStart) > 60000)
        jmStart = System.currentTimeMillis();
      RunSession run =
          new RunSession(
              Path.of(getPropertyAsString("phase.output", "phase-analyzer-results")), jmStart);
      run.jmxFilename = resolveJmxFilename();
      run.prime(catalog, parallelSafe);
      SESSIONS.put(getPropertyAsString("phase.id"), run);
      testRunning = true;
      RUNNING.put(getPropertyAsString("phase.id"), this);
      originalTestStart = run.testStart;
      activeGroups = new ConcurrentHashMap<>();
      activeCounts = new ConcurrentHashMap<>();
    } catch (Exception e) {
      throw new IllegalStateException("Cannot initialize phase analyzer", e);
    }
  }

  public void testStarted(String host) {
    testStarted();
    session()
        .warning(
            "Distributed callback from "
                + host
                + "; global observed load cannot be reconstructed by this listener.");
  }

  public void threadStarted() {
    RunSession r = session();
    var g = JMeterContextService.getContext().getThreadGroup();
    if (r != null && r.isCapturing()) { r.groupStarted(g); r.recordThreadStart(g); }
    if (g != null && activeCounts != null) activeCounts.computeIfAbsent(GroupDiscovery.key(g), key -> new java.util.concurrent.atomic.AtomicInteger()).incrementAndGet();
    if (g != null && activeGroups != null) activeGroups.put(GroupDiscovery.key(g), g);
  }

  public boolean testRunning() {
    PhaseAnalyzerListener live=RUNNING.get(getPropertyAsString("phase.id"));
    return live!=null && live.testRunning;
  }

  /** Read-only current worker count from the runtime listener, including the GUI's cloned element. */
  public Integer observedUsers(String key) {
    PhaseAnalyzerListener live=RUNNING.get(getPropertyAsString("phase.id"));
    if(live!=null && live!=this)return live.observedUsers(key);
    if(activeCounts==null||activeCounts.isEmpty())return null;
    if(key.equals("All"))return activeCounts.values().stream().mapToInt(java.util.concurrent.atomic.AtomicInteger::get).sum();
    var count=activeCounts.get(key);return count==null?null:count.get();
  }

  public CompletableFuture<RunSession> stopCapture() {
    RunSession r = session();
    if (r == null)
      return CompletableFuture.failedFuture(new IllegalStateException("Capture has not started"));
    r.stopAccepting("Stopped");
    return CompletableFuture.supplyAsync(
        () -> {
          try {
            r.finish();
            return r;
          } catch (Exception e) {
            throw new CompletionException(e);
          }
        });
  }

  public synchronized RunSession startCapture() throws Exception {
    PhaseAnalyzerListener live=RUNNING.get(getPropertyAsString("phase.id"));
    if(live!=null && live!=this)return live.startCapture();
    if (!testRunning)
      throw new IllegalStateException("JMeter must be running to start a new capture");
    RunSession old = session();
    if (old != null && old.isCapturing())
      throw new IllegalStateException("Capture is already running");
    if (old != null) old.finish();
    RunSession next =
        new RunSession(
            Path.of(getPropertyAsString("phase.output", "phase-analyzer-results")),
            originalTestStart);
    next.jmxFilename = old == null ? resolveJmxFilename() : old.jmxFilename;
    next.prime(catalog, parallelSafe);
    if (activeGroups != null) for (var g : activeGroups.values()) {
      next.groupStarted(g);
      next.seedActiveThreads(g, activeCounts.getOrDefault(GroupDiscovery.key(g), new java.util.concurrent.atomic.AtomicInteger()).get());
    }
    if (old != null) {
      for (var e : old.groups.entrySet())
        if (next.groups.containsKey(e.getKey())) next.groups.put(e.getKey(), e.getValue());
    }
    SESSIONS.put(getPropertyAsString("phase.id"), next);
    return next;
  }

  static String resolveJmxFilename() {
    String name = null;
    var gui = org.apache.jmeter.gui.GuiPackage.getInstance();
    if (gui != null) name = gui.getTestPlanFile();
    else name = org.apache.jmeter.services.FileServer.getFileServer().getScriptName();
    if (name == null || name.isBlank()) return null;
    try { return Path.of(name).getFileName().toString(); }
    catch (InvalidPathException e) { return null; }
  }

  private List<GroupDiscovery.Entry> readCatalog() {
    try {
      var server = org.apache.jmeter.services.FileServer.getFileServer();
      String name = server.getScriptName();
      if (name != null && !name.isBlank()) {
        Path path = Path.of(name);
        if (!path.isAbsolute()) path = Path.of(server.getBaseDir()).resolve(name);
        if (Files.isRegularFile(path)) {
          var tree = org.apache.jmeter.save.SaveService.loadTree(path.toFile());
          for (Object root : tree.list())
            if (root instanceof TestElement plan) {
              setProperty("phase.testPlan", plan.getName());
              setProperty(
                  "phase.serialized", plan.getPropertyAsBoolean("TestPlan.serialize_threadgroups"));
            }
          var entries = GroupDiscovery.scan(tree, false);
          setProperty("phase.catalog", Reports.mapper().writeValueAsString(entries));
          return entries;
        }
      }
      String raw = getPropertyAsString("phase.catalog");
      if (!raw.isBlank())
        return Reports.mapper()
            .readValue(
                raw,
                new com.fasterxml.jackson.core.type.TypeReference<List<GroupDiscovery.Entry>>() {});
    } catch (Exception e) {
      System.err.println("Phase Analyzer configuration discovery: " + e.getMessage());
    }
    return List.of();
  }

  public void threadFinished() {
    var g = JMeterContextService.getContext().getThreadGroup();
    RunSession run = session();
    if (run != null && run.isCapturing()) run.recordThreadFinish(g);
    if (g != null && activeCounts != null) activeCounts.computeIfAbsent(GroupDiscovery.key(g), key -> new java.util.concurrent.atomic.AtomicInteger()).updateAndGet(n -> Math.max(0, n - 1));
  }

  public void sampleOccurred(SampleEvent event) {
    RunSession run = session();
    if (run == null || !run.isCapturing()) return;
    var r = event.getResult();
    if (r.getSampleCount() != 1) {
      run.warning(
          "Aggregated SampleResult (sampleCount != 1) excluded: individual response times"
              + " unavailable");
      return;
    }
    boolean transaction =
        event.isTransactionSampleEvent()
            || TransactionController.isFromTransactionController(r)
            || isRuntimeParent(r);
    collect(run, event, r, transaction);
    if (transaction)
      for (SampleResult child : r.getSubResults()) collectChildren(run, event, child);
  }

  private void collectChildren(RunSession run, SampleEvent e, SampleResult r) {
    boolean tx = TransactionController.isFromTransactionController(r) || isRuntimeParent(r);
    collect(run, e, r, tx);
    if (tx) for (var c : r.getSubResults()) collectChildren(run, e, c);
  }

  private static boolean isRuntimeParent(SampleResult result) {
    // JMeterThread sets previousResult to a real sampler result before notification.
    // doEndTransactionSampler instead notifies its synthetic parent without replacing
    // previousResult.
    // On scheduler stop that parent may lack TransactionController's normal response-message
    // marker.
    SampleResult previous = JMeterContextService.getContext().getPreviousResult();
    return previous != null && previous != result && containsIdentity(result, previous);
  }

  private static boolean containsIdentity(SampleResult parent, SampleResult previous) {
    for (SampleResult child : parent.getSubResults())
      if (child == previous || containsIdentity(child, previous)) return true;
    return false;
  }

  private void collect(RunSession run, SampleEvent e, SampleResult r, boolean tx) {
    run.accept(
        new Sample(
            r.getStartTime(),
            r.getTime(),
            r.getSampleLabel(),
            JMeterContextService.getContext().getThreadGroup() == null
                ? e.getThreadGroup()
                : GroupDiscovery.key(JMeterContextService.getContext().getThreadGroup()),
            Objects.toString(e.getHostname(), "local"),
            r.isSuccessful(),
            r.getBytesAsLong(),
            r.getSentBytes(),
            r.getLatency(),
            r.getConnectTime(),
            r.getGroupThreads(),
            r.getResponseCode(),
            tx,
            r.getEndTime()));
  }

  public void sampleStarted(SampleEvent e) {}

  public void sampleStopped(SampleEvent e) {}

  public List<Window> windows(RunSession run) {
    String a = getPropertyAsString("phase.absoluteStart"),
        b = getPropertyAsString("phase.absoluteEnd");
    return run.windows(
        getPropertyAsString("phase.group", "All"),
        seconds("delay"),
        seconds("up"),
        seconds("hold"),
        seconds("down"),
        getPropertyAsBoolean("phase.automatic", true),
        a.isBlank() ? null : Long.parseLong(a),
        b.isBlank() ? null : Long.parseLong(b));
  }

  private long seconds(String p) {
    return Math.multiplyExact(getPropertyAsLong("phase." + p), 1000);
  }

  public Analyzer.Settings settings(List<Window> windows) {
    return new Analyzer.Settings(
        windows,
        getPropertyAsString("phase.group", "All"),
        getPropertyAsString("phase.mode", "Requests"),
        getPropertyAsBoolean("phase.strict"),
        getPropertyAsLong("phase.apdexT", 500),
        getPropertyAsLong("phase.apdexF", 2000));
  }

  public Map<String, Object> metadata(RunSession r) {
    var m = r.metadata();
    String selected = getPropertyAsString("phase.group", "All");
    RunSession.Group g = r.groups.get(selected);
    if (g == null && selected.equals("All") && r.groups.size() == 1)
      g = r.groups.values().iterator().next();
    Long target = null;
    if (!getPropertyAsBoolean("phase.automatic", true))
      target = getPropertyAsLong("phase.target", 1);
    else if (g != null && g.schedule() != null) target = (long) g.schedule().peak();
    m.put("configuredTargetLoad", target);
    if (!getPropertyAsBoolean("phase.automatic", true))
      m.put(
          "manualSchedule",
          Map.of(
              "origin",
              r.start,
              "schedule",
              Schedule.manual(
                  seconds("delay"),
                  seconds("up"),
                  seconds("hold"),
                  seconds("down"),
                  (int) getPropertyAsLong("phase.target", 1))));
    m.put("tolerancePercent", getPropertyAsLong("phase.tolerance", 5));
    m.put("minimumStableSeconds", getPropertyAsLong("phase.minimumStable", 10));
    return m;
  }

  public void testEnded() {
    testRunning = false;
    RUNNING.remove(getPropertyAsString("phase.id"),this);
    RunSession r = session();
    if (r == null) return;
    try {
      r.testOutcome = evidence == null ? "Unknown" : evidence.outcome();
      r.finish();
      if (getPropertyAsBoolean("phase.autoExport", false)) {
        var snapshot = ReportSnapshot.build(this, r);
        Reports.exportSelected(snapshot, r.reportDirectory,
            getPropertyAsString("phase.autoExportFormat", "html"));
      }
    } catch (Exception e) {
      r.warning("Finalization/export failed: " + e);
      org.apache.jmeter.util.JMeterUtils.reportErrorToUser("Phase analyzer: " + e.getMessage());
    } finally {
      if (evidence != null) evidence.close();
    }
  }

  public void testEnded(String host) {
    testEnded();
  }
}
