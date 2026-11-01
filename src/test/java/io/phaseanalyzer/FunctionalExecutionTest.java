package io.phaseanalyzer;

import static org.junit.jupiter.api.Assertions.*;
import java.io.*;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import org.apache.jmeter.control.LoopController;
import org.apache.jmeter.engine.StandardJMeterEngine;
import org.apache.jmeter.engine.util.NoThreadClone;
import org.apache.jmeter.reporters.ResultCollector;
import org.apache.jmeter.samplers.*;
import org.apache.jmeter.testelement.*;
import org.apache.jmeter.threads.*;
import org.apache.jmeter.util.JMeterUtils;
import org.apache.jorphan.collections.ListedHashTree;
import org.junit.jupiter.api.*;

/** Actual native executions and independent native CSV/JTL reconciliation. */
class FunctionalExecutionTest {
  @BeforeAll static void init()throws Exception{JMeterUtils.loadJMeterProperties("src/test/resources/jmeter-test.properties");JMeterUtils.setJMeterHome(Path.of("src/test/resources/jmeter-home").toAbsolutePath().toString());org.apache.jmeter.save.SaveService.loadProperties();JMeterUtils.setLocale(Locale.ENGLISH);}
  public static class ProbeSampler extends AbstractSampler {
    public SampleResult sample(Entry entry){
      try{Thread.sleep(20);}catch(InterruptedException e){Thread.currentThread().interrupt();}
      if(getPropertyAsBoolean("zero"))return null;
      SampleResult r=new SampleResult();r.setSampleLabel("Search API");r.sampleStart();
      try{Thread.sleep(5);}catch(InterruptedException e){Thread.currentThread().interrupt();}
      r.sampleEnd();boolean good=JMeterContextService.getContext().getVariables().getIteration()%5!=0;
      r.setSuccessful(good);r.setResponseCode(good?"200":"503");r.setResponseData("Local plugin integration validation","UTF-8");
      r.setResponseMessage(GroupDiscovery.key(JMeterContextService.getContext().getThreadGroup()));return r;
    }
  }
  static class Probe extends AbstractTestElement implements SampleListener,NoThreadClone {
    final PhaseAnalyzerListener listener;final List<String> keys;final boolean sequential;
    volatile boolean sawMiddleState;
    final List<Map<String,Object>> observations=new CopyOnWriteArrayList<>();
    Probe(PhaseAnalyzerListener listener,List<String> keys,boolean sequential){this.listener=listener;this.keys=keys;this.sequential=sequential;}
    public void sampleStarted(SampleEvent e){}public void sampleStopped(SampleEvent e){}
    public void sampleOccurred(SampleEvent e){
      RunSession r=listener.session();if(r==null)return;
      if(sequential&&GroupDiscovery.key(JMeterContextService.getContext().getThreadGroup()).equals(keys.get(1))){
        String a=r.groupStatus(keys.get(0)),b=r.groupStatus(keys.get(1)),c=r.groupStatus(keys.get(2));
        if(a.equals("Completed")&&b.equals("Running")&&c.equals("Execution Not Started"))sawMiddleState=true;
      }
      observations.add(Map.of("time",System.currentTimeMillis(),"key",GroupDiscovery.key(JMeterContextService.getContext().getThreadGroup()),"states",keys.stream().map(r::groupStatus).toList()));
    }
  }
  @Test void sequentialLifecycleAndJtlReports()throws Exception{execute(true);}
  @Test void parallelGroupIsolationAndJtlReports()throws Exception{execute(false);}
  static class ClearProbe extends AbstractTestElement implements SampleListener,NoThreadClone {
    final PhaseAnalyzerListener listener;int delivered;volatile long boundary;
    ClearProbe(PhaseAnalyzerListener listener){this.listener=listener;}
    public void sampleStarted(SampleEvent e){}public void sampleStopped(SampleEvent e){}
    public synchronized void sampleOccurred(SampleEvent e){if(++delivered==7){listener.clearData();boundary=listener.session().start;}}
  }
  @Test void activeClearReconcilesNativeJtlAndBothReports()throws Exception{
    Path out=Files.createTempDirectory(Path.of("target"),"native-clear-");
    ListedHashTree tree=new ListedHashTree();var children=tree.add(new TestPlan("Native clear boundary"));
    var listener=new PhaseAnalyzerListener();listener.setProperty("phase.output",out.toString());children.add(listener);
    var group=new org.apache.jmeter.threads.ThreadGroup();group.setName("Clear Target");group.setNumThreads(1);group.setRampUp(0);
    LoopController loop=new LoopController();loop.setLoops(24);group.setSamplerController(loop);children.add(group).add(new ProbeSampler());
    listener.setProperty("phase.catalog",Reports.mapper().writeValueAsString(GroupDiscovery.scan(tree)));
    var probe=new ClearProbe(listener);children.add(probe);
    var writer=new ResultCollector();writer.setFilename(out.resolve("native.jtl").toString());
    var save=new SampleSaveConfiguration(false);save.setAsXml(false);save.setFieldNames(true);save.setTimestamp(true);save.setTime(true);save.setLabel(true);save.setSuccess(true);writer.setSaveConfig(save);children.add(writer);
    var engine=new StandardJMeterEngine();engine.configure(runtimeTree(tree));engine.runTest();
    assertTimeoutPreemptively(Duration.ofSeconds(12),()->{while(listener.session()==null||!listener.session().isFinalized()||engine.isActive())Thread.sleep(20);});
    assertEquals(24,probe.delivered);assertTrue(probe.boundary>0);assertEquals(17,listener.session().store.count);
    var snapshot=ExecutionViews.build(listener,listener.session()).selected();assertEquals(17L,((Number)snapshot.phase("Complete execution").metrics().get("samples")).longValue());
    assertEquals(25,Files.readAllLines(out.resolve("native.jtl")).size());
    assertTrue(Files.size(Reports.exportSelected(snapshot,out,"html"))>0);assertTrue(Files.size(Reports.exportSelected(snapshot,out,"xlsx"))>0);
    listener.clearData();assertNull(listener.session());assertEquals(25,Files.readAllLines(out.resolve("native.jtl")).size());
  }
  void execute(boolean sequential)throws Exception{
    Path out=Files.createTempDirectory(Path.of("target"),"functional-"+(sequential?"sequential-":"parallel-"));
    TestPlan plan=new TestPlan("Local functional validation");plan.setProperty("TestPlan.serialize_threadgroups",sequential);
    ListedHashTree tree=new ListedHashTree();var children=tree.add(plan);
    PhaseAnalyzerListener listener=new PhaseAnalyzerListener();listener.setProperty("phase.group","All");listener.setProperty("phase.output",out.toString());children.add(listener);
    List<org.apache.jmeter.threads.ThreadGroup> groups=new ArrayList<>();int[] loops={12,60,20,2,3};
    for(int i=0;i<5;i++){
      var g=new org.apache.jmeter.threads.ThreadGroup();g.setName(i<2?"Same Name":"TG"+(i+1));g.setEnabled(i!=4);g.setNumThreads(1);g.setRampUp(1);g.setScheduler(true);g.setDuration(4);
      LoopController loop=new LoopController();loop.setLoops(loops[i]);g.setSamplerController(loop);
      ProbeSampler sampler=new ProbeSampler();sampler.setName("Search API");sampler.setProperty("zero",i==3);children.add(g).add(sampler);groups.add(g);
    }
    var catalog=GroupDiscovery.scan(tree);listener.setProperty("phase.catalog",Reports.mapper().writeValueAsString(catalog));
    List<String> keys=groups.stream().map(GroupDiscovery::key).toList();
    Probe probe=new Probe(listener,keys,sequential);children.add(probe);
    ResultCollector nativeWriter=new ResultCollector();nativeWriter.setFilename(out.resolve("results.jtl").toString());
    SampleSaveConfiguration save=new SampleSaveConfiguration(false);save.setAsXml(false);save.setFieldNames(true);save.setTimestamp(true);save.setTime(true);save.setLabel(true);save.setCode(true);save.setMessage(true);save.setThreadName(true);save.setSuccess(true);save.setBytes(true);save.setSentBytes(true);save.setLatency(true);save.setConnectTime(true);save.setThreadCounts(true);nativeWriter.setSaveConfig(save);children.add(nativeWriter);
    var server=org.apache.jmeter.services.FileServer.getFileServer();String old=server.getScriptName();server.setScriptName(null);
    try{
      StandardJMeterEngine engine=new StandardJMeterEngine();engine.configure(runtimeTree(tree));engine.runTest();
      assertTimeoutPreemptively(Duration.ofSeconds(15),()->{while(listener.session()==null||!listener.session().isFinalized()||engine.isActive())Thread.sleep(20);});
      RunSession run=listener.session();assertEquals(92,run.store.count);assertEquals(0,run.dropped.get());
      Reports.mapper().writerWithDefaultPrettyPrinter().writeValue(out.resolve("lifecycle.json").toFile(),probe.observations);
      assertTrue(run.participated(keys.get(3)),"Zero-sample native group must retain lifecycle");assertFalse(run.participated(keys.get(4)),"Disabled group must never participate");
      if(sequential)assertTrue(probe.sawMiddleState,"Observe completed TG1, running TG2 and not-started TG3 together");
      var all=ExecutionViews.build(listener,run);assertEquals(4,all.members().size());
      List<Map<String,String>> nativeRows=read(out.resolve("results.jtl"));assertEquals(92,nativeRows.size());
      Map<String,Object> summary=new LinkedHashMap<>();summary.put("mode",sequential?"sequential":"parallel");summary.put("samples",nativeRows.size());summary.put("sequentialMiddleStateObserved",probe.sawMiddleState);
      for(int i=0;i<4;i++){
        String key=keys.get(i);var rows=nativeRows.stream().filter(r->r.get("responseMessage").equals(key)).toList();
        ReportSnapshot snapshot=all.members().get(key);assertNotNull(snapshot);
        checkMetrics(rows,snapshot.phase("Complete execution").metrics());
        long phaseSum=0;
        for(String phase:ReportSnapshot.PHASES)if(!phase.equals("Complete execution")){
          var windows=snapshot.phase(phase).effective();var selected=rows.stream().filter(r->windows.stream().anyMatch(w->Long.parseLong(r.get("timeStamp"))>=w.start()&&Long.parseLong(r.get("timeStamp"))<w.end())).toList();
          var metric=snapshot.phase(phase).metrics();assertEquals(selected.size(),((Number)metric.getOrDefault("samples",0)).intValue());phaseSum+=selected.size();
        }
        assertEquals(rows.size(),phaseSum+snapshot.unclassified());
        Reports.exportSelected(snapshot,out.resolve("group-"+(i+1)),"html");Reports.exportSelected(snapshot,out.resolve("group-"+(i+1)),"xlsx");
        Reports.mapper().writerWithDefaultPrettyPrinter().writeValue(out.resolve("group-"+(i+1)+".json").toFile(),snapshot);
      }
      checkMetrics(nativeRows,all.selected().phase("Complete execution").metrics());
      for(String phase:ReportSnapshot.PHASES){long expected=all.members().values().stream().mapToLong(s->((Number)s.phase(phase).metrics().getOrDefault("samples",0)).longValue()).sum();assertEquals(expected,((Number)all.selected().phase(phase).metrics().getOrDefault("samples",0)).longValue());}
      Reports.exportSelected(all.selected(),out.resolve("combined"),"html");Reports.exportSelected(all.selected(),out.resolve("combined"),"xlsx");
      Reports.mapper().writerWithDefaultPrettyPrinter().writeValue(out.resolve("combined.json").toFile(),all.selected());
      Reports.mapper().writerWithDefaultPrettyPrinter().writeValue(out.resolve("lifecycle.json").toFile(),probe.observations);
      summary.put("reportAndJtlReconciliation","Passed");Reports.mapper().writerWithDefaultPrettyPrinter().writeValue(out.resolve("summary.json").toFile(),summary);
      // Same listener, same native groups: a new test must replace, not append
      // to, the prior capture. New manual settings take effect only now.
      PhaseConfiguration.save(listener,keys.get(0),new PhaseConfiguration.Value(false,0,4,0));
      nativeWriter.setFilename(out.resolve("second-run.jtl").toString());
      StandardJMeterEngine second=new StandardJMeterEngine();second.configure(runtimeTree(tree));second.runTest();
      assertTimeoutPreemptively(Duration.ofSeconds(15),()->{while(listener.session()==run||!listener.session().isFinalized()||second.isActive())Thread.sleep(20);});
      RunSession next=listener.session();assertNotSame(run,next);assertEquals(92,next.store.count);
      var secondView=ExecutionViews.build(listener,next);var secondRows=read(out.resolve("second-run.jtl"));checkMetrics(secondRows,secondView.selected().phase("Complete execution").metrics());
      assertEquals(12L,secondView.members().get(keys.get(0)).phase("Steady load").metrics().get("samples"));
      assertEquals("Manual — explicit time windows",secondView.members().get(keys.get(0)).metadata().get("detectionMode"));
      Reports.mapper().writerWithDefaultPrettyPrinter().writeValue(out.resolve("second-run.json").toFile(),secondView.selected());
    }finally{server.setScriptName(old);}
  }
  static org.apache.jorphan.collections.HashTree runtimeTree(org.apache.jorphan.collections.HashTree source){
    org.apache.jmeter.JMeter.convertSubTree(source,false);
    var cloner=new org.apache.jmeter.engine.TreeCloner(true);source.traverse(cloner);return cloner.getClonedTree();
  }
  static List<Map<String,String>> read(Path path)throws Exception{
    List<Map<String,String>> rows=new ArrayList<>();try(PushbackReader reader=new PushbackReader(Files.newBufferedReader(path),2)){var header=Csv.read(reader);for(List<String> values;(values=Csv.read(reader))!=null;){Map<String,String> row=new HashMap<>();for(int i=0;i<header.size();i++)row.put(header.get(i),values.get(i));rows.add(row);}}return rows;
  }
  static void checkMetrics(List<Map<String,String>> rows,Map<String,Object> actual){
    assertEquals(rows.size(),((Number)actual.getOrDefault("samples",0)).intValue());if(rows.isEmpty())return;
    long[] times=rows.stream().mapToLong(r->Long.parseLong(r.get("elapsed"))).sorted().toArray();long failures=rows.stream().filter(r->!r.get("success").equals("true")).count();
    assertEquals(failures,((Number)actual.get("failed")).longValue());assertEquals(rows.size()-failures,((Number)actual.get("successful")).longValue());
    assertEquals(Arrays.stream(times).average().orElseThrow(),((Number)actual.get("averageMs")).doubleValue(),1e-8);
    double[] percentiles={.5,.9,.95,.99};String[] names={"medianMs","p90Ms","p95Ms","p99Ms"};
    for(int i=0;i<4;i++)assertEquals(times[(int)Math.ceil(times.length*percentiles[i])-1],((Number)actual.get(names[i])).longValue());
    assertEquals(times[0],((Number)actual.get("minMs")).longValue());assertEquals(times[times.length-1],((Number)actual.get("maxMs")).longValue());
    assertEquals(failures*100./rows.size(),((Number)actual.get("errorPercent")).doubleValue(),1e-8);
  }
}


