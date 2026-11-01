package io.phaseanalyzer;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.apache.jmeter.threads.*;

public final class RunSession {
  public record Group(
      String name, String className, long origin, Schedule schedule, String warning) {}

  public record Load(long time, String group, int active) {}

  public final long start = System.currentTimeMillis();
  private final String reportTimeZone = java.time.ZoneId.systemDefault().getId();
  public volatile String captureStatus = "Running";
  public volatile String testOutcome = "Unknown";
  public volatile String jmxFilename;
  // Runtime-only source identity: two copied JMX files may retain the same listener id.
  volatile Path sourcePlan;
  public final Path reportDirectory;
  public final long testStart;
  public volatile long end;
  public final SampleStore store;
  public final Map<String, Group> groups = new ConcurrentHashMap<>();
  public final List<String> warnings = new CopyOnWriteArrayList<>();
  public final AtomicLong dropped = new AtomicLong();
  final LiveThroughput liveThroughput = new LiveThroughput();
  private volatile PhaseAnalyzerListener frozenConfiguration;
  private final Map<String,Long> groupEnds=new ConcurrentHashMap<>();
  private final Map<String,String> groupOutcomes=new ConcurrentHashMap<>();
  private final Map<String,String> displayNames=new ConcurrentHashMap<>();
  private final Map<String,Map<String,Double>> completedRates=new ConcurrentHashMap<>();
  private void freezeRate(String key,long cutoff){
    Map<String,Double> rates=new HashMap<>();for(String mode:List.of("Requests","Transactions","Separate"))rates.put(mode,liveThroughput.rate(key,mode,start,cutoff));completedRates.putIfAbsent(key,Collections.unmodifiableMap(rates));
  }
  Double currentRate(String key,String mode,long cutoff){var rates=completedRates.get(key);return rates==null?liveThroughput.rate(key,mode,start,cutoff):rates.get(mode);}
  String displayName(String key){
    if(frozenConfiguration!=null)try{for(var entry:Reports.mapper().readTree(frozenConfiguration.getPropertyAsString("phase.catalog","[]")))if(entry.path("key").asText().equals(key))return entry.path("label").asText(entry.path("name").asText(key));}catch(Exception ignored){}
    String name=displayNames.getOrDefault(key,key);
    return displayNames.values().stream().filter(name::equals).count()>1?name+" ["+key.substring(Math.max(0,key.length()-8))+"]":name;
  }
  private final Set<String> naturalWorkerEnds=ConcurrentHashMap.newKeySet(),abnormalWorkerEnds=ConcurrentHashMap.newKeySet();
  public void freezeConfiguration(PhaseAnalyzerListener config){frozenConfiguration=(PhaseAnalyzerListener)config.clone();}
  RunSession emptyCapture() throws IOException {
    RunSession next=new RunSession(reportDirectory,testStart);
    next.jmxFilename=jmxFilename;next.sourcePlan=sourcePlan;
    next.frozenConfiguration=frozenConfiguration==null?null:(PhaseAnalyzerListener)frozenConfiguration.clone();
    next.groups.putAll(groups);next.observedStarts.putAll(observedStarts);next.nativeAnchored.addAll(nativeAnchored);
    next.groupEnds.putAll(groupEnds);next.groupOutcomes.putAll(groupOutcomes);next.displayNames.putAll(displayNames);
    next.live.putAll(live);
    next.naturalWorkerEnds.addAll(naturalWorkerEnds);next.abnormalWorkerEnds.addAll(abnormalWorkerEnds);
    return next;
  }
  synchronized void bindInitialSelection(String key){
    if(frozenConfiguration!=null&&frozenConfiguration.getPropertyAsString("phase.group").isBlank()){
      frozenConfiguration.setProperty("phase.group",key);PhaseConfiguration.migrateLegacy(frozenConfiguration);
    }
  }
  PhaseAnalyzerListener analysisConfiguration(PhaseAnalyzerListener current,String key){
    PhaseAnalyzerListener result=(PhaseAnalyzerListener)(frozenConfiguration==null?current:frozenConfiguration).clone();
    result.setProperty("phase.group",key);
    result.setProperty("phase.catalog",current.getPropertyAsString("phase.catalog"));
    if(frozenConfiguration!=null)PhaseConfiguration.apply(result,PhaseConfiguration.read(frozenConfiguration,key));
    return result;
  }
  boolean hasFrozenConfiguration(){return frozenConfiguration!=null;}
  boolean participated(String key){return observedStarts.containsKey(key);}
  boolean hasParticipatingGroups(){return !observedStarts.isEmpty();}
  long groupEnd(String key){return groupEnds.getOrDefault(key,end);}
  long groupStart(String key){return observedStarts.getOrDefault(key,start);}
  String groupStatus(String key){
    if(!participated(key))return "Execution Not Started";
    if(groupEnds.containsKey(key))return groupOutcomes.getOrDefault(key,"Finished (cause unavailable)");
    if(end>0)return testOutcome.equals("Unknown")?captureStatus:testOutcome;
    return "Running";
  }
  private final Set<String> nativeAnchored = ConcurrentHashMap.newKeySet();
  private final Map<String, Long> observedStarts = new ConcurrentHashMap<>();

  public void prime(List<GroupDiscovery.Entry> catalog, boolean parallelSafe) {
    for (var e : catalog)
      if (e.enabled()) {
        if (catalog.stream().filter(x -> x.enabled() && x.key().equals(e.key())).count() > 1) {
          warning(
              "Ambiguous Thread Group identities: open the listener and save the test plan"
                  + " before execution for stable cross-run identifiers.");
          continue;
        }
        groups.put(
            e.key(),
            new Group(
                e.key(),
                e.implementation(),
                testStart,
                null,
                "origin pending: Thread Group has not started"));
      }
  }

  private final ArrayBlockingQueue<Object> queue = new ArrayBlockingQueue<>(32768);

  public record CaptureCopy(
      Path samples,
      Path load,
      long cutoff,
      long count,
      long first,
      Map<String, Group> groups,
      String status) {}

  private record CopyRequest(CompletableFuture<CaptureCopy> result) {}

  private volatile boolean loadClosed;
  private final ExecutorService worker =
      Executors.newSingleThreadExecutor(
          r -> {
            Thread t = new Thread(r, "phase-spool");
            t.setDaemon(true);
            return t;
          });
  private final ScheduledExecutorService monitor =
      Executors.newSingleThreadScheduledExecutor(
          r -> {
            Thread t = new Thread(r, "phase-load");
            t.setDaemon(true);
            return t;
          });
  private final Map<String, AbstractThreadGroup> live = new ConcurrentHashMap<>();
  private final Map<String, AtomicInteger> actualThreads = new ConcurrentHashMap<>();
  public void recordThreadStart(AbstractThreadGroup group) {
    if (group != null) actualThreads.computeIfAbsent(GroupDiscovery.key(group), key -> new AtomicInteger()).incrementAndGet();
  }
  public void recordThreadFinish(AbstractThreadGroup group) {
    recordThreadFinish(group,JMeterContextService.getContext().getThread());
  }
  public void recordThreadFinish(AbstractThreadGroup group,JMeterThread thread) {
    if (group != null){
      String key=GroupDiscovery.key(group);
      if(thread!=null)try{
        Object controller=RuntimeScheduleOrigin.field(thread,"threadGroupLoopController");
        boolean natural=controller instanceof org.apache.jmeter.control.Controller c&&c.isDone();
        if(group.getSamplerController() instanceof org.apache.jmeter.control.LoopController loop
            &&loop.getLoops()>0&&((JMeterVariables)RuntimeScheduleOrigin.field(thread,"threadVars")).getIteration()>=loop.getLoops())natural=true;
        if(thread.getEndTime()>0&&System.currentTimeMillis()+250>=thread.getEndTime())natural=true;
        if(natural)naturalWorkerEnds.add(key);else abnormalWorkerEnds.add(key);
      }catch(ReflectiveOperationException|RuntimeException unavailable){warning("Native worker completion cause unavailable for "+key);}
      actualThreads.computeIfAbsent(key, ignored -> new AtomicInteger()).updateAndGet(n -> Math.max(0, n - 1));
    }
  }
  public void seedActiveThreads(AbstractThreadGroup group, int count) {
    actualThreads.put(GroupDiscovery.key(group), new AtomicInteger(Math.max(0, count)));
  }
  private final BufferedWriter loadWriter;
  private volatile boolean running = true, closed;
  public final Path directory;

  public RunSession(Path root) throws IOException {
    this(root, 0);
  }

  public RunSession(Path root, long testStart) throws IOException {
    this.testStart = testStart == 0 ? start : testStart;
    reportDirectory = root.toAbsolutePath().normalize();
    directory = Files.createTempDirectory("phase-analyzer-internal-");
    Runtime.getRuntime().addShutdownHook(new Thread(this::cleanup, "phase-cleanup"));
    store = new SampleStore(directory.resolve("samples.jtl"));
    loadWriter = Files.newBufferedWriter(directory.resolve("load.csv"));
    loadWriter.write("timeStamp,threadGroup,active\n");
    worker.submit(
        () -> {
          try {
            while (running || !queue.isEmpty()) {
              Object item = queue.poll(100, TimeUnit.MILLISECONDS);
              if (item instanceof Sample s) store.append(s);
              else if (item instanceof CopyRequest request) {
                try {
                  request.result().complete(copyNow(false));
                } catch (Exception e) {
                  request.result().completeExceptionally(e);
                }
              }
            }
            synchronized (store) {
              store.close();
              closed = true;
            }
          } catch (Exception e) {
            warning("Sample storage failed: " + e);
            running = false;
          }
        });
    monitor.scheduleAtFixedRate(this::recordLoad, 0, 250, TimeUnit.MILLISECONDS);
  }

  public void warning(String s) {
    if (!warnings.contains(s)) warnings.add(s);
  }

  public void groupStarted(AbstractThreadGroup g) {
    if (g == null) {
      warning("Thread Group context unavailable; automatic schedule cannot be anchored.");
      return;
    }
    String groupKey = GroupDiscovery.key(g);
    AbstractThreadGroup existing = live.get(groupKey);
    if (existing != null && existing != g) {
      g.setProperty("phase.analysisKey", "runtime:" + UUID.randomUUID());
      groupKey = GroupDiscovery.key(g);
      warning(
          "Duplicate Thread Group names were assigned distinct runtime identities; use Apply"
              + " Configuration to persist their identities in JMX.");
    }
    live.putIfAbsent(groupKey, g);
    displayNames.putIfAbsent(groupKey,g.getName());
    observedStarts.putIfAbsent(groupKey, System.currentTimeMillis());
    Group previous = groups.get(groupKey);
    if (previous != null && previous.warning().startsWith("origin pending:"))
      groups.remove(groupKey);
    groups.computeIfAbsent(
        groupKey,
        name -> {
          try {
            Schedule s = ScheduleAdapters.read(g);
            return new Group(
                name,
                g.getClass().getName(),
                s.profile().isEmpty() ? System.currentTimeMillis() - s.firstStart() : testStart,
                s,
                "");
          } catch (Exception e) {
            return new Group(
                name, g.getClass().getName(), System.currentTimeMillis(), null, e.getMessage());
          }
        });
    Group current = groups.get(groupKey);
    if (current != null && current.schedule() != null && !nativeAnchored.contains(groupKey)) {
      Long nativeOrigin = RuntimeScheduleOrigin.read(g, current.schedule(), testStart);
      if (nativeOrigin != null) {
        groups.put(groupKey, new Group(current.name(), current.className(), nativeOrigin, current.schedule(), current.warning()));
        nativeAnchored.add(groupKey);
      } else {
        warning(g.getName() + ": exact native schedule epoch unavailable; fallback reference contains startup jitter.");
      }
    }
    if (live.get(groupKey) != g)
      warning(
          "Duplicate Thread Group name: "
              + g.getName()
              + ". Give each group a unique name before analysis.");
  }

  public void accept(Sample s) {
    synchronized (queue) {
      if (!running) return;
      if (!queue.offer(s)) {
        dropped.incrementAndGet();
        warning(
            "Collection queue overflow or late event: reports are incomplete; increase storage"
                + " throughput or reduce load.");
      } else liveThroughput.record(s,System.currentTimeMillis());
    }
  }

  public void stopAccepting(String status) {
    synchronized (queue) {
      if (running) {
        running = false;
        end = System.currentTimeMillis();
        captureStatus = status;
      }
    }
  }

  public boolean isCapturing() {
    return running;
  }

  private volatile boolean finalized;
  public boolean isFinalized() { return finalized; }

  private void recordLoad() {
    try {
      observeGroupCompletion();
      synchronized (loadWriter) {
        long now = System.currentTimeMillis();
        for (var g : live.entrySet())
          loadWriter.write(Csv.row(now, g.getKey(), actualThreads.getOrDefault(g.getKey(), new AtomicInteger()).get()));
        loadWriter.flush();
      }
    } catch (Exception e) {
      warning("Load monitoring failed: " + e);
    }
  }

  private void observeGroupCompletion(){
    for(var entry:live.entrySet()){
      String key=entry.getKey();var group=entry.getValue();
      if(groupEnds.containsKey(key)||!participated(key)||group.numberOfActiveThreads()>0)continue;
      if(actualThreads.getOrDefault(key,new AtomicInteger()).get()>0)continue;
      // Native starters can remain alive while arrival/stepping groups have no
      // active workers. A temporary gap is not a group completion.
      if(hasLiveStarter(group))continue;
      long now=System.currentTimeMillis();Group configured=groups.get(key);
      boolean natural=group.getSamplerController()!=null&&group.getSamplerController().isDone();
      natural|=naturalWorkerEnds.contains(key)&&!abnormalWorkerEnds.contains(key);
      if(configured!=null&&configured.schedule()!=null&&now+250>=configured.origin()+configured.schedule().end())natural=true;
      groupOutcomes.put(key,natural?"Completed":"Stopped / interrupted (native workers finished)");
      groupEnds.put(key,now);
      freezeRate(key,now);
    }
  }
  static boolean hasLiveStarter(AbstractThreadGroup group){
    for(Class<?> type=group.getClass();type!=null;type=type.getSuperclass())for(var field:type.getDeclaredFields()){
      if(java.lang.reflect.Modifier.isStatic(field.getModifiers())||!field.getName().toLowerCase(Locale.ROOT).contains("starter"))continue;
      try{field.setAccessible(true);Object value=field.get(group);
        if(value instanceof Thread thread&&thread.isAlive())return true;
        if(value instanceof java.util.concurrent.atomic.AtomicReference<?> ref)value=ref.get();
        if(value instanceof java.util.concurrent.Future<?> future&&!future.isDone())return true;
      }catch(ReflectiveOperationException|RuntimeException unavailable){return true;}
    }
    return false;
  }

  public synchronized void finish() throws Exception {
    if (finalized) return;
    if (running) stopAccepting("Completed");
    monitor.shutdown();
    monitor.awaitTermination(2, TimeUnit.SECONDS);
    recordLoad();
    for(String key:observedStarts.keySet())if(!groupEnds.containsKey(key)){
      groupEnds.put(key,end);groupOutcomes.put(key,testOutcome.equals("Unknown")?captureStatus:testOutcome);
      freezeRate(key,end);
    }
    worker.shutdown();
    if (!worker.awaitTermination(60, TimeUnit.SECONDS)) {
      warning("Sample spool did not drain within 60 seconds; execution incomplete");
      throw new IOException("Spool drain timeout");
    }
    synchronized (loadWriter) {
      loadWriter.close();
      loadClosed = true;
    }
    live.clear();
    finalized = true;
    if (dropped.get() > 0) warning("Dropped metadata records: " + dropped.get());
    Reports.mapper()
        .writerWithDefaultPrettyPrinter()
        .writeValue(directory.resolve("metadata.json").toFile(), metadata());
  }

  public Map<String, Object> metadata() {
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("schemaVersion", "1.0");
    m.put("executionStart", start);
    m.put("testStart", testStart);
    m.put("observedFirstThreadStarts", Map.copyOf(observedStarts));
    m.put("nativeScheduleAnchoredGroups", Set.copyOf(nativeAnchored));
    m.put("captureStatus", captureStatus);
    m.put("testOutcome", testOutcome);
    m.put("jmxFilename", jmxFilename);
    m.put("reportTimestamp", testStart);
    m.put("reportTimeZone", reportTimeZone);
    m.put("executionEnd", end == 0 ? null : end);
    m.put(
        "reference",
        "Phase durations are configuration-only. Known installed native scheduler clocks anchor relative windows after group startup; Open Model uses JMeter test start. Before startup/fallback, the provisional reference can contain startup jitter. No concurrency or response behavior determines phase boundaries.");
    m.put("groups", groups.values());
    m.put("warnings", warnings);
    m.put("dropped", dropped.get());
    m.put("loadMethod", "Started, unfinished local JMeter worker threads from ThreadListener callbacks; arrival-pool idle workers may be included. Configured arrival rates are not concurrent users.");
    m.put("distributed", "Local engine only; distributed global concurrency is unavailable");
    return m;
  }

  public synchronized Path snapshot() throws IOException {
    CaptureCopy copy = copy();
    Files.deleteIfExists(copy.load());
    return copy.samples();
  }

  public CaptureCopy copy() throws IOException {
    CompletableFuture<CaptureCopy> future = new CompletableFuture<>();
    boolean queued = false;
    synchronized (queue) {
      if (running) {
        if (!queue.offer(new CopyRequest(future)))
          throw new IOException(
              "Snapshot deferred: collection buffer is full. No raw samples were discarded by this"
                  + " export request.");
        queued = true;
      }
    }
    if (!queued) {
      try {
        finish();
      } catch (Exception e) {
        throw new IOException("Cannot finalize capture snapshot", e);
      }
      return copyNow(true);
    }
    try {
      return future.get(60, TimeUnit.SECONDS);
    } catch (Exception e) {
      throw new IOException("Snapshot collection barrier failed", e);
    }
  }

  private CaptureCopy copyNow(boolean complete) throws IOException {
    long cut = System.currentTimeMillis();
    Path samples = Files.createTempFile(directory, "snapshot-", ".jtl"),
        load = Files.createTempFile(directory, "snapshot-load-", ".csv");
    long count, first;
    synchronized (store) {
      if (!closed) store.flush();
      Files.copy(store.path, samples, StandardCopyOption.REPLACE_EXISTING);
      count = store.count;
      first = store.first;
    }
    synchronized (loadWriter) {
      if (!loadClosed) loadWriter.flush();
      Files.copy(directory.resolve("load.csv"), load, StandardCopyOption.REPLACE_EXISTING);
    }
    return new CaptureCopy(
        samples,
        load,
        complete && end != 0 ? end : cut,
        count,
        first,
        Map.copyOf(groups),
        complete ? captureStatus : "Running");
  }

  public List<Window> windows(
      String group,
      long delay,
      long up,
      long hold,
      long down,
      boolean automatic,
      Long selectedStart,
      Long selectedEnd) {
    long recordedEnd = end == 0 ? System.currentTimeMillis() : end;
    List<Window> ws = new ArrayList<>();
    if (selectedStart != null && selectedEnd != null)
      ws.add(new Window("Steady load", selectedStart, selectedEnd));
    else if (automatic) {
      Collection<Group> chosen =
          group.equals("All")
              ? groups.values()
              : groups.containsKey(group) ? List.of(groups.get(group)) : List.of();
      if (chosen.isEmpty())
        warning(
            "No runtime schedule found for selected Thread Group; wait for startup or choose manual"
                + " analysis.");
      if (group.equals("All") && chosen.size() == 1) {
        Group only = chosen.iterator().next();
        if (only.schedule() != null) {
          ws.addAll(only.schedule().windows(only.origin()));
          chosen = List.of();
        }
      }
      List<Window> common = new ArrayList<>();
      boolean first = true;
      for (Group g : chosen) {
        if (g.schedule() == null) {
          warning(g.name() + ": " + g.warning());
          common.clear();
          first = false;
          break;
        }
        List<Window> windows = g.schedule().windows(g.origin());
        List<Window> holds =
            windows.stream()
                .filter(w -> w.phase().equals("Steady load") && w.end() > w.start())
                .toList();
        if (!group.equals("All")) {
          ws.addAll(windows);
          break;
        }
        if (first) {
          common.addAll(holds);
          first = false;
        } else {
          List<Window> next = new ArrayList<>();
          for (Window a : common)
            for (Window b : holds) {
              Window w = Window.intersection(a, b);
              if (w.end() > w.start()) next.add(w);
            }
          common = next;
        }
      }
      if (group.equals("All") && !chosen.isEmpty()) {
        ws.addAll(common);
        warning(
            "All groups: steady-load views use common-window intersections, not a union of"
                + " individual holds; ramp phases require individual group selection.");
        if (common.isEmpty()) warning("No qualifying common steady-load window is available.");
      }
      long candidates = ws.stream().filter(w -> w.phase().equals("Steady load")).count();
      if (candidates > 1)
        warning(
            "Multiple configured hold candidates: each is shown separately. Select explicit"
                + " timestamps to choose an analysis window.");
    } else {
      ws.add(new Window("Ramp-up", start + delay, start + delay + up));
      ws.add(new Window("Steady load", start + delay + up, start + delay + up + hold));
      ws.add(new Window("Ramp-down", start + delay + up + hold, start + delay + up + hold + down));
    }
    // Clip effective denominator to the observed execution while preserving configured metadata.
    List<Window> clipped = new ArrayList<>();
    for (Window w : ws) {
      if (w.end() > recordedEnd)
        warning(
            "Configured "
                + w.phase()
                + " extends beyond recorded execution; effective window is clipped.");
      long a = Math.min(w.start(), recordedEnd), b = Math.min(w.end(), recordedEnd);
      clipped.add(new Window(w.phase(), a, Math.max(a, b)));
    }
    clipped.add(
        new Window(
            "Complete execution",
            Math.min(start, store.first == Long.MAX_VALUE ? start : store.first),
            Math.max(start, recordedEnd)));
    return clipped;
  }
  /** Delete only this session's private, generated spool; never the report directory. */
  public synchronized void cleanup() {
    try {
      if (!finalized) finish();
      try (var paths = Files.walk(directory)) {
        for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
      }
    } catch (Exception ignored) { /* OS/process failure may leave a private temp directory. */ }
  }

}
