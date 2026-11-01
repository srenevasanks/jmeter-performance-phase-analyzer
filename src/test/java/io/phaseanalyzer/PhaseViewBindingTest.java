package io.phaseanalyzer;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.*;
import org.apache.jmeter.save.SaveService;
import org.apache.jmeter.util.JMeterUtils;
import org.apache.jorphan.collections.ListedHashTree;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

/** Reuses the same native listener GUI, as JMeter does across Load/Clear actions. */
class PhaseViewBindingTest {
  @TempDir Path temp;
  @BeforeAll static void init() throws Exception {
    JMeterUtils.loadJMeterProperties("src/test/resources/jmeter-test.properties");
    JMeterUtils.setJMeterHome(Path.of("src/test/resources/jmeter-home").toAbsolutePath().toString());
    SaveService.loadProperties();JMeterUtils.setLocale(Locale.ENGLISH);
  }
  interface Checked {void run() throws Exception;}
  static void edt(Checked task)throws Exception {SwingUtilities.invokeAndWait(()->{try{task.run();}catch(Exception e){throw new RuntimeException(e);}});}
  static Object get(Object object,String name)throws Exception {var f=object.getClass().getDeclaredField(name);f.setAccessible(true);return f.get(object);}
  static void set(Object object,String name,Object value)throws Exception {var f=object.getClass().getDeclaredField(name);f.setAccessible(true);f.set(object,value);}
  static JComboBox<?> combo(PhaseAnalyzerGui gui,String name)throws Exception{return (JComboBox<?>)get(gui,name);}
  static void invoke(PhaseAnalyzerGui gui,String name)throws Exception{var m=PhaseAnalyzerGui.class.getDeclaredMethod(name);m.setAccessible(true);m.invoke(gui);}
  static void assertPhase(PhaseAnalyzerGui gui,int index)throws Exception {
    assertEquals(index,combo(gui,"dashboardScope").getSelectedIndex());
    assertEquals(index,combo(gui,"tableScope").getSelectedIndex());
    assertEquals(combo(gui,"dashboardScope").getSelectedItem(),get(gui,"selectedPhase"));
  }
  static ReportSnapshot data(String group,boolean running) {
    var phases=new ArrayList<ReportSnapshot.Phase>();var views=new ArrayList<Analyzer.View>();
    for(String name:ReportSnapshot.PHASES){
      long n=name.equals("Complete execution")?100: name.equals("Steady load")?60:20;
      var metrics=new HashMap<String,Object>();metrics.put("samples",n);metrics.put("averageMs",(double)n);
      metrics.put("label","Search API");metrics.put("threadGroup",group);
      phases.add(new ReportSnapshot.Phase(name,running?"Running":"Completed",1.,1,metrics,List.of(),List.of(),""));
      views.add(new Analyzer.View(new Window(name,0,1000),"Requests",n,n,0,List.of(metrics)));
    }
    return new ReportSnapshot("test","test",Map.of("executionStart",0L,"executionEnd",3000L,"testStart",0L,"executionStatus",running?"Running":"Completed","selectedThreadGroupLabel",group),new Analyzer.Settings(List.of(),group,"Requests",false,500,2000),new Analyzer.Result("test","test",List.of(),views),List.of(),List.of(),phases,0);
  }
  static void showData(PhaseAnalyzerGui gui,ReportSnapshot snapshot)throws Exception{set(gui,"snapshot",snapshot);invoke(gui,"renderDashboard");invoke(gui,"renderTable");}
  static void assertEmpty(PhaseAnalyzerGui gui)throws Exception {
    assertNull(get(gui,"snapshot"));assertTrue(((Map<?,?>)get(gui,"groupSnapshots")).isEmpty());
    assertEquals("—",((JLabel[])get(gui,"kpis"))[0].getText());assertEquals(0,((JTable)get(gui,"table")).getRowCount());
    assertNull(get(get(gui,"graph"),"snapshot"));assertTrue(((Map<?,?>)get(get(gui,"graph"),"groupSnapshots")).isEmpty());
    assertFalse(((JButton)get(gui,"html")).isEnabled());assertFalse(((JButton)get(gui,"excel")).isEnabled());
  }
  @Test void clearResetsBothSelectorsAndAllExecutionViews()throws Exception{edt(()->{
    var gui=new PhaseAnalyzerGui();gui.configure(new PhaseAnalyzerListener());showData(gui,data("Old",false));
    combo(gui,"dashboardScope").setSelectedIndex(3);assertEquals("100",((JLabel[])get(gui,"kpis"))[0].getText());
    ((TrendPanel)get(gui,"graph")).setGroupSnapshots(Map.of("Old",data("Old",false)));
    ((JTextField)get(gui,"filter")).setText("Old API");gui.clearGui();
    assertPhase(gui,0);assertEmpty(gui);assertEquals("",((JTextField)get(gui,"filter")).getText());
    assertEquals("",((JTextArea)get(gui,"details")).getText());
  });}
  @Test void newListenerBindingResetsWithoutDependingOnClearGui()throws Exception{edt(()->{
    var gui=new PhaseAnalyzerGui();gui.configure(new PhaseAnalyzerListener());showData(gui,data("A",true));
    combo(gui,"tableScope").setSelectedIndex(1);long revision=(long)get(gui,"selectionRevision");
    gui.configure(new PhaseAnalyzerListener());assertPhase(gui,0);assertEmpty(gui);
    assertTrue((long)get(gui,"selectionRevision")>revision);
    showData(gui,data("B",false));combo(gui,"dashboardScope").setSelectedIndex(3);
    assertPhase(gui,3);assertEquals("100",((JLabel[])get(gui,"kpis"))[0].getText());
    assertEquals("B",((JTable)get(gui,"table")).getValueAt(0,1));
  });}
  @Test void sameListenerReconfigurationPreservesAnExplicitValidPhase()throws Exception{edt(()->{
    var listener=new PhaseAnalyzerListener();var gui=new PhaseAnalyzerGui();gui.configure(listener);
    combo(gui,"dashboardScope").setSelectedIndex(3);gui.configure(listener);assertPhase(gui,3);
  });}
  @Test void dashboardAndLiveSelectionsUpdateActualKpisAndAggregateScope()throws Exception{
    for(boolean running:List.of(true,false))edt(()->{
      var gui=new PhaseAnalyzerGui();gui.configure(new PhaseAnalyzerListener());showData(gui,data("B",running));
      assertPhase(gui,0);assertEquals("60",((JLabel[])get(gui,"kpis"))[0].getText());
      combo(gui,"dashboardScope").setSelectedIndex(3);assertPhase(gui,3);
      assertEquals("100",((JLabel[])get(gui,"kpis"))[0].getText());assertEquals(100L,((JTable)get(gui,"table")).getValueAt(0,3));
      combo(gui,"tableScope").setSelectedIndex(1);assertPhase(gui,1);
      assertEquals("20",((JLabel[])get(gui,"kpis"))[0].getText());assertEquals(20L,((JTable)get(gui,"table")).getValueAt(0,3));
    });
  }
  @Test void actualJmxLoadsRestoreManualConfigurationButNeverAnotherPlansViewPhase()throws Exception{
    PhaseAnalyzerListener a=new PhaseAnalyzerListener(),b=new PhaseAnalyzerListener();a.setProperty("phase.group","A");b.setProperty("phase.group","B");
    PhaseConfiguration.save(b,"B",new PhaseConfiguration.Value(false,2,8,0));
    var loaded=new ArrayList<PhaseAnalyzerListener>();
    for(var entry:Map.of("One.jmx",a,"Two.jmx",b).entrySet()){
      entry.getValue().setProperty("TestElement.gui_class",PhaseAnalyzerGui.class.getName());entry.getValue().setProperty("TestElement.test_class",PhaseAnalyzerListener.class.getName());
      var tree=new ListedHashTree();tree.add(entry.getValue());Path file=temp.resolve(entry.getKey());
      try(var out=Files.newOutputStream(file)){SaveService.saveTree(tree,out);}
      loaded.add((PhaseAnalyzerListener)SaveService.loadTree(file.toFile()).getArray()[0]);
    }
    edt(()->{
      var gui=new PhaseAnalyzerGui();
      for(var listener:loaded){gui.clearGui();gui.configure(listener);assertPhase(gui,0);assertEmpty(gui);
        var key=listener.getPropertyAsString("phase.group");
        gui.setDiscovered(List.of(FunctionalUpdateTest.entry(key,key,true)));
        assertEquals(key,listener.getPropertyAsString("phase.group"));
        assertEquals(PhaseConfiguration.read(listener,key).automatic(),((JRadioButton)get(gui,"automatic")).isSelected());
        combo(gui,"dashboardScope").setSelectedIndex(3);showData(gui,data(key,false));
      }
    });
  }
  @Test void pendingOldSnapshotCannotPopulateTheNewListener()throws Exception{
    var old=new PhaseAnalyzerListener();old.setProperty("phase.group","All");RunSession run=new RunSession(temp);
    run.accept(new Sample(run.start,10,"Old API","Old","local",true,10,5,1,0,1,"200",false));run.stopAccepting("Completed");run.finish();
    PhaseAnalyzerListener.SESSIONS.put(old.getPropertyAsString("phase.id"),run);
    AtomicReference<PhaseAnalyzerGui> ref=new AtomicReference<>();
    try{
      edt(()->{var gui=new PhaseAnalyzerGui();ref.set(gui);gui.configure(old);gui.clearGui();gui.configure(new PhaseAnalyzerListener());});
      long deadline=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(10);
      while(((java.util.concurrent.atomic.AtomicBoolean)get(ref.get(),"busy")).get()&&System.nanoTime()<deadline)Thread.sleep(20);
      edt(()->{assertFalse(((java.util.concurrent.atomic.AtomicBoolean)get(ref.get(),"busy")).get());assertPhase(ref.get(),0);assertEmpty(ref.get());});
    }finally{PhaseAnalyzerListener.SESSIONS.remove(old.getPropertyAsString("phase.id"));run.cleanup();}
  }
  @Test void copiedListenerIdsCannotExposeAnotherJmxFilesCapture()throws Exception{
    var server=org.apache.jmeter.services.FileServer.getFileServer();String previous=server.getScriptName();
    var old=new PhaseAnalyzerListener();RunSession run=new RunSession(temp);run.sourcePlan=temp.resolve("one/Validation.jmx").toAbsolutePath();
    var copied=(PhaseAnalyzerListener)old.clone();PhaseAnalyzerListener.SESSIONS.put(old.getPropertyAsString("phase.id"),run);
    try{
      server.setScriptName(temp.resolve("two/Validation.jmx").toString());
      edt(()->{var gui=new PhaseAnalyzerGui();gui.configure(copied);var method=PhaseAnalyzerGui.class.getDeclaredMethod("viewSession");method.setAccessible(true);assertNull(method.invoke(gui));assertEmpty(gui);assertPhase(gui,0);});
      assertSame(run,old.session(),"Changing the editor must not delete a running or historical capture");
    }finally{server.setScriptName(previous);PhaseAnalyzerListener.SESSIONS.remove(old.getPropertyAsString("phase.id"));run.finish();run.cleanup();}
  }
}
