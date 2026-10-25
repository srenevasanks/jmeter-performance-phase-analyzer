package io.phaseanalyzer;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.io.*;
import org.junit.jupiter.api.*;
import org.apache.jmeter.util.JMeterUtils;
import org.apache.jmeter.engine.StandardJMeterEngine;
import org.apache.jmeter.control.LoopController;
import org.apache.jmeter.protocol.java.sampler.JSR223Sampler;
import org.apache.jmeter.testelement.TestPlan;
import org.apache.jmeter.services.FileServer;
import org.apache.jmeter.save.SaveService;
import org.apache.jmeter.testelement.property.CollectionProperty;
import org.apache.jorphan.collections.ListedHashTree;

class OutputCleanupTest {
  static final Path ROOT = Path.of("target/output-cleanup-validation").toAbsolutePath();
  @BeforeAll static void init() throws Exception {
    JMeterUtils.loadJMeterProperties("src/test/resources/jmeter-test.properties");
    JMeterUtils.setJMeterHome(Path.of("src/test/resources/jmeter-home").toAbsolutePath().normalize().toString());
    org.apache.logging.log4j.core.config.Configurator.setLevel("org.apache.jmeter.JMeter", org.apache.logging.log4j.Level.INFO);
    org.apache.logging.log4j.core.config.Configurator.setLevel("org.apache.jmeter.engine.StandardJMeterEngine", org.apache.logging.log4j.Level.INFO);
    org.apache.logging.log4j.core.config.Configurator.setLevel("org.apache.jmeter.threads.JMeterThread", org.apache.logging.log4j.Level.INFO);
    JMeterUtils.initLocale(); SaveService.loadProperties(); Files.createDirectories(ROOT);
  }
  @Test void savedJmxRealExecutionAndSelectedExports() throws Exception {
    Path output = Files.createTempDirectory(ROOT,"reports-");
    var tree=new ListedHashTree(); var plan=tree.add(new TestPlan("THIS DISPLAY NAME MUST NOT BE THE FILENAME"));
    var listener=new PhaseAnalyzerListener();listener.setName("Phase Analyzer");listener.setProperty("phase.output",output.toString());plan.add(listener);
    var group=new kg.apc.jmeter.threads.UltimateThreadGroup();group.setName("Smoke Ultimate");group.setEnabled(true);
    var rows=new CollectionProperty("ultimatethreadgroupdata",new ArrayList<>());rows.addItem(new CollectionProperty("row",List.of("2","0","1","2","1")));group.setData(rows);
    var loop=new LoopController();loop.setLoops(-1);loop.setContinueForever(true);group.setSamplerController(loop);
    var sampler=new JSR223Sampler();sampler.setName("Measured local smoke");sampler.setProperty("scriptLanguage","groovy");sampler.setProperty("script","Thread.sleep(25); SampleResult.setResponseCode('200'); SampleResult.setSuccessful(true)");plan.add(group).add(sampler);
    tree.traverse(new org.apache.jorphan.collections.HashTreeTraverser() {
      public void addNode(Object n,org.apache.jorphan.collections.HashTree sub) {if(n instanceof org.apache.jmeter.testelement.TestElement e){e.setProperty("TestElement.test_class",e.getClass().getName());e.setProperty("TestElement.gui_class",e instanceof TestPlan?"org.apache.jmeter.control.gui.TestPlanGui":e instanceof PhaseAnalyzerListener?"io.phaseanalyzer.PhaseAnalyzerGui":e instanceof kg.apc.jmeter.threads.UltimateThreadGroup?"kg.apc.jmeter.threads.UltimateThreadGroupGui":"org.apache.jmeter.testbeans.gui.TestBeanGUI");}}
      public void subtractNode(){} public void processPath(){}
    });
    Path jmx=ROOT.resolve("Actual Cleanup Smoke.jmx");try(var stream=Files.newOutputStream(jmx)){SaveService.saveTree(tree,stream);}
    FileServer.getFileServer().setBaseForScript(jmx.toFile());
    var loaded=SaveService.loadTree(jmx.toFile());var engine=new StandardJMeterEngine();engine.configure(loaded);engine.runTest();engine.awaitTermination(Duration.ofSeconds(20));
    PhaseAnalyzerListener actual=find(loaded);RunSession run=actual.session();assertNotNull(run);assertTrue(run.store.count>30);assertEquals(0,run.dropped.get());
    long[] failed={0};SampleStore.scan(run.store.path,sample->{if(!sample.success())failed[0]++;});assertEquals(0,failed[0]);
    assertEquals("Actual Cleanup Smoke.jmx",run.jmxFilename);assertEquals("Completed",run.testOutcome);
    assertFalse(Files.exists(output.resolve("report.html")));try(var files=Files.list(output)){assertEquals(0,files.count());}
    assertFalse(run.directory.startsWith(output));
    var snapshot=ReportSnapshot.build(actual,run);var before=Reports.mapper().writeValueAsString(snapshot.analysis());
    Path html=Reports.exportSelected(snapshot,output,"html");assertTrue(html.getFileName().toString().matches("Actual Cleanup Smoke_\\d{4}-\\d{2}-\\d{2}_\\d{2}-\\d{2}-\\d{2}\\.html"));
    try(var files=Files.list(output)){assertEquals(1,files.count());}
    String text=Files.readString(html);assertFalse(text.contains("<footer>"));assertFalse(text.contains("$('footer')"));assertFalse(text.contains("id=\"footer\""));assertTrue(text.contains("Actual Cleanup Smoke.jmx"));
    for(String section:List.of("Execution Summary","Overall Key Metrics","Phase Results Summary","Active Users Timeline","Response Time Trend","Throughput &amp; Error Rate","Aggregate Results by API","Response Codes Summary","Execution Configuration Details"))assertTrue(text.contains(section),section);
    byte[] original=Files.readAllBytes(html);Path repeated=Reports.exportSelected(snapshot,output,"html");assertNotEquals(html,repeated);assertArrayEquals(original,Files.readAllBytes(html));
    Path csvOnly=Files.createTempDirectory(ROOT,"csv-only-");Path csv=Reports.exportSelected(snapshot,csvOnly,"csv");try(var files=Files.list(csvOnly)){assertEquals(1,files.count());}
    assertEquals(html.getFileName().toString().replace(".html",".csv"),csv.getFileName().toString());
    assertEquals(before,Reports.mapper().writeValueAsString(snapshot.analysis()));
    Analyzer.Result independent=Analyzer.ranges(run.store.path,snapshot.settings(),snapshot.phases().stream().collect(java.util.stream.Collectors.toMap(ReportSnapshot.Phase::name,ReportSnapshot.Phase::effective)));
    for(var view:snapshot.analysis().views()) {
      var matching=independent.views().stream().filter(v->v.window().phase().equals(view.window().phase())&&v.kind().equals(view.kind())).findFirst().orElseThrow();
      assertEquals(Reports.mapper().valueToTree(view),Reports.mapper().valueToTree(matching));
    }
    var evidence=new LinkedHashMap<String,Object>();evidence.put("jmx",jmx.toString());evidence.put("html",html.toString());evidence.put("csv",csv.toString());evidence.put("sampleCount",run.store.count);evidence.put("metadata",snapshot.metadata());evidence.put("phases",snapshot.phases());
    Reports.mapper().writerWithDefaultPrettyPrinter().writeValue(ROOT.resolve("evidence.json").toFile(),evidence);
    Files.copy(run.store.path,ROOT.resolve("actual-capture.jtl"),StandardCopyOption.REPLACE_EXISTING);
    run.cleanup();assertFalse(Files.exists(run.directory));assertTrue(Files.exists(html));
  }
  public static class InterruptibleSmoke extends org.apache.jmeter.samplers.AbstractSampler implements org.apache.jmeter.samplers.Interruptible {
    private volatile boolean interrupted;
    public boolean interrupt(){interrupted=true;return true;}
    public org.apache.jmeter.samplers.SampleResult sample(org.apache.jmeter.samplers.Entry entry){
      var result=new org.apache.jmeter.samplers.SampleResult();result.setSampleLabel("Lifecycle smoke");result.sampleStart();
      for(int i=0;i<100&&!interrupted;i++)try{Thread.sleep(10);}catch(InterruptedException e){Thread.currentThread().interrupt();break;}
      result.sampleEnd();result.setSuccessful(!interrupted);result.setResponseCode(interrupted?"499":"200");return result;
    }
  }
  @Test void nativeShutdownAndStopNowAreNotReportedAsCompletion() throws Exception {
    for(boolean interrupt:new boolean[]{false,true}) {
      var tree=new ListedHashTree();var plan=tree.add(new TestPlan("Lifecycle smoke"));
      var listener=new PhaseAnalyzerListener();listener.setProperty("phase.output",ROOT.resolve("no-auto-lifecycle").toString());plan.add(listener);
      var group=new org.apache.jmeter.threads.ThreadGroup();group.setName("Lifecycle group");group.setNumThreads(2);group.setRampUp(0);group.setScheduler(true);group.setDuration(15);
      var loop=new LoopController();loop.setLoops(-1);group.setSamplerController(loop);plan.add(group).add(new InterruptibleSmoke());
      var engine=new StandardJMeterEngine();engine.configure(tree);engine.runTest();
      long deadline=System.currentTimeMillis()+5000;while((listener.session()==null||listener.session().groups.isEmpty())&&System.currentTimeMillis()<deadline)Thread.sleep(20);
      Thread.sleep(200);engine.stopTest(interrupt);engine.awaitTermination(Duration.ofSeconds(10));
      String outcome=listener.session().testOutcome;assertTrue(outcome.startsWith(interrupt?"Interrupted":"Stopped"),outcome);
      assertFalse(Files.exists(ROOT.resolve("no-auto-lifecycle")));listener.session().cleanup();
    }
  }
  static PhaseAnalyzerListener find(org.apache.jorphan.collections.HashTree tree) {
    for(Object key:tree.list()){if(key instanceof PhaseAnalyzerListener p)return p;var found=find(tree.getTree(key));if(found!=null)return found;}return null;
  }
  @Test void unavailableAndSanitizedNamesAndEvidence() throws Exception {
    RunSession r=new RunSession(ROOT);var c=new PhaseAnalyzerListener();r.finish();var snapshot=ReportSnapshot.build(c,r);
    assertNull(snapshot.metadata().get("testPlan"));assertTrue(Reports.exportFilename(snapshot,"html").startsWith("JMX-unavailable_"));
    r.jmxFilename="CON.jmx";snapshot=ReportSnapshot.build(c,r);assertTrue(Reports.exportFilename(snapshot,"csv").startsWith("_CON_"));
    r.jmxFilename="bad:na?me.jmx";assertTrue(Reports.exportFilename(ReportSnapshot.build(c,r),"html").startsWith("bad_na_me_"));r.cleanup();
    try(var e=new ExecutionEvidence()) {assertEquals("Unknown",e.outcome());org.apache.logging.log4j.LogManager.getLogger("org.apache.jmeter.JMeter").info("Command: Shutdown received from /127.0.0.1");assertEquals("Stopped (manual Shutdown)",e.outcome());}
    try(var e=new ExecutionEvidence()) {org.apache.logging.log4j.LogManager.getLogger("org.apache.jmeter.JMeter").info("Command: StopTestNow received from /127.0.0.1");assertEquals("Interrupted (manual Stop Now)",e.outcome());}
  }
}
