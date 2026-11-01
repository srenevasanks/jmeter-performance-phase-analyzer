package io.phaseanalyzer;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.*;
import java.util.*;
import javax.swing.*;
import org.apache.jmeter.util.JMeterUtils;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

class FunctionalUpdateTest {
  @TempDir Path temp;
  @BeforeAll static void init()throws Exception{JMeterUtils.loadJMeterProperties("src/test/resources/jmeter-test.properties");JMeterUtils.setJMeterHome(Path.of("src/test/resources/jmeter-home").toAbsolutePath().toString());org.apache.jmeter.save.SaveService.loadProperties();JMeterUtils.setLocale(Locale.ENGLISH);}
  static GroupDiscovery.Entry entry(String key,String name,boolean enabled){return new GroupDiscovery.Entry(key,name,name+" ["+key+"]","org.apache.jmeter.threads.ThreadGroup",enabled,Schedule.phases(0,1000,1000,1000),"",Map.of());}
  static Object field(Object object,String name)throws Exception{var f=object.getClass().getDeclaredField(name);f.setAccessible(true);return f.get(object);}
  @Test void selectionFollowsEnabledTreeOrderAndExplicitAll(){
    var a=entry("A","Same",false);var b=entry("B","Same",true);var c=entry("C","New",true);
    assertEquals("B",GroupSelection.choose(List.of(a,b,c),""));
    assertEquals("B",GroupSelection.choose(List.of(a,b,c),"deleted"));
    assertEquals("C",GroupSelection.choose(List.of(c,b,a),""));
    assertEquals("B",GroupSelection.choose(List.of(c,b,a),"B"));
    assertEquals("All",GroupSelection.choose(List.of(c,b,a),"All"));
    assertEquals("",GroupSelection.choose(List.of(a),"All"));
    assertEquals("C",GroupSelection.choose(List.of(a,c),"B"));
  }
  @Test void manualSettingsArePerGroupPerListenerAndInvalidSettingsFallBack(){
    var one=new PhaseAnalyzerListener();one.setProperty("phase.group","A");
    assertTrue(PhaseConfiguration.read(one,"A").automatic());
    PhaseConfiguration.save(one,"A",new PhaseConfiguration.Value(false,1,2,3));
    assertFalse(PhaseConfiguration.read(one,"A").automatic());
    assertTrue(PhaseConfiguration.read(one,"B").automatic());
    assertTrue(PhaseConfiguration.read(one,"All").automatic());
    assertTrue(PhaseConfiguration.read(new PhaseAnalyzerListener(),"A").automatic());
    one.setProperty("phase.configurations","{\"A\":{\"automatic\":false,\"up\":-1,\"hold\":0,\"down\":0}}");
    assertTrue(PhaseConfiguration.read(one,"A").automatic());
    assertThrows(IllegalArgumentException.class,()->new PhaseConfiguration.Value(false,0,0,0));
  }
  @Test void guiRejectsDisabledSelectionAndDynamicallyPersistsValidManualValues()throws Exception{
    SwingUtilities.invokeAndWait(()->{try{
      var listener=new PhaseAnalyzerListener();var gui=new PhaseAnalyzerGui();gui.configure(listener);
      gui.setDiscovered(List.of(entry("A","Disabled",false),entry("B","Same",true),entry("C","Same",true)));
      var combo=(JComboBox<?>)field(gui,"group");assertEquals("B",listener.getPropertyAsString("phase.group"));
      combo.setSelectedIndex(1);assertEquals("B",listener.getPropertyAsString("phase.group"),"Disabled cached choice must be rejected");
      ((JRadioButton)field(gui,"manual")).doClick();
      var fields=(Map<String,JTextField>)field(gui,"fields");fields.get("hold").setText("7");
      assertEquals(7,PhaseConfiguration.read(listener,"B").hold());
      fields.get("hold").setText("-");assertEquals(7,PhaseConfiguration.read(listener,"B").hold());
      fields.get("hold").setText("8");combo.setSelectedIndex(0);
      assertTrue(((JRadioButton)field(gui,"automatic")).isSelected());assertFalse(((JRadioButton)field(gui,"manual")).isEnabled());
      combo.setSelectedIndex(2);assertFalse(((JRadioButton)field(gui,"automatic")).isSelected());
      assertEquals(8,PhaseConfiguration.read(listener,"B").hold());
      List<String> buttons=new ArrayList<>();buttons(gui,buttons);
      assertFalse(buttons.contains("Apply Configuration"));assertFalse(buttons.contains("Reset"));
      gui.setDiscovered(List.of(entry("C","Renamed",true),entry("A","Disabled",false)));
      assertEquals("C",listener.getPropertyAsString("phase.group"));
      gui.setDiscovered(List.of(entry("A","Disabled",false)));assertEquals("",listener.getPropertyAsString("phase.group"));
    }catch(Exception e){throw new RuntimeException(e);}});
  }
  private static void buttons(java.awt.Container c,List<String> names){for(var child:c.getComponents()){if(child instanceof JButton b)names.add(b.getText());if(child instanceof java.awt.Container container)buttons(container,names);}}
  @Test void treeReorderingUsesNativeNodeOrderAndPreservesIdentity(){
    var root=new org.apache.jmeter.gui.tree.JMeterTreeNode(new org.apache.jmeter.testelement.TestPlan(),null);
    var a=new org.apache.jmeter.gui.tree.JMeterTreeNode(new org.apache.jmeter.threads.ThreadGroup(),null);a.getTestElement().setName("Same");
    var b=new org.apache.jmeter.gui.tree.JMeterTreeNode(new org.apache.jmeter.threads.SetupThreadGroup(),null);b.getTestElement().setName("Same");
    root.add(a);root.add(b);var tree=new org.apache.jorphan.collections.ListedHashTree();var children=tree.add(root);children.add(b);children.add(a);
    var first=GroupDiscovery.scan(tree);String aKey=first.get(0).key(),bKey=first.get(1).key();
    assertEquals("org.apache.jmeter.threads.ThreadGroup",first.get(0).implementation());
    root.insert(b,0);var second=GroupDiscovery.scan(tree);assertEquals(bKey,second.get(0).key());assertEquals(aKey,second.get(1).key());
    a.getTestElement().setName("Renamed");assertEquals(aKey,GroupDiscovery.scan(tree).get(1).key());
  }
  @Test void alreadyCachedEnabledChoiceIsRevalidatedAgainstTheActualJMeterTree()throws Exception{
    SwingUtilities.invokeAndWait(()->{Object previous=null;java.lang.reflect.Field singleton=null;try{
      singleton=org.apache.jmeter.gui.GuiPackage.class.getDeclaredField("guiPack");singleton.setAccessible(true);previous=singleton.get(null);singleton.set(null,null);
      var model=new org.apache.jmeter.gui.tree.JMeterTreeModel();var treeListener=new org.apache.jmeter.gui.tree.JMeterTreeListener(model);
      var tree=new JTree(model);treeListener.setJTree(tree);org.apache.jmeter.gui.GuiPackage.initInstance(treeListener,model);
      var plan=(org.apache.jmeter.gui.tree.JMeterTreeNode)((org.apache.jmeter.gui.tree.JMeterTreeNode)model.getRoot()).getChildAt(0);
      var a=new org.apache.jmeter.threads.ThreadGroup();a.setName("A");a.setEnabled(true);var b=new org.apache.jmeter.threads.ThreadGroup();b.setName("B");b.setEnabled(true);
      plan.add(new org.apache.jmeter.gui.tree.JMeterTreeNode(a,model));plan.add(new org.apache.jmeter.gui.tree.JMeterTreeNode(b,model));
      var listener=new PhaseAnalyzerListener();var gui=new PhaseAnalyzerGui();gui.configure(listener);gui.setDiscovered(GroupDiscovery.scan(model.getTestPlan()));
      ((JComboBox<?>)field(gui,"group")).setSelectedIndex(1);
      String original=listener.getPropertyAsString("phase.group");assertFalse(original.isBlank());b.setEnabled(false); // deliberately no event: popup's cached entry is stale
      ((JComboBox<?>)field(gui,"group")).setSelectedIndex(2);
      assertEquals(original,listener.getPropertyAsString("phase.group"));
    }catch(Exception e){throw new RuntimeException(e);}finally{try{if(singleton!=null)singleton.set(null,previous);}catch(Exception e){throw new RuntimeException(e);}}});
  }
  @Test void savedJmxRestoresManualSettingsAndIdentityAfterNativeEditorClearedProperties()throws Exception{
    JMeterUtils.setJMeterHome(Path.of("src/test/resources/jmeter-home").toAbsolutePath().toString());org.apache.jmeter.save.SaveService.loadProperties();
    var tree=new org.apache.jorphan.collections.ListedHashTree();var plan=tree.add(new org.apache.jmeter.testelement.TestPlan("Saved config"));
    var listener=new PhaseAnalyzerListener();plan.add(listener);
    var a=new org.apache.jmeter.threads.ThreadGroup();a.setName("Same");a.setEnabled(true);plan.add(a);
    var b=new org.apache.jmeter.threads.ThreadGroup();b.setName("Same");b.setEnabled(true);plan.add(b);
    var catalog=PlanIdentitySync.synchronize(tree);String key=catalog.get(1).key();listener.setProperty("phase.group",key);
    PhaseConfiguration.save(listener,key,new PhaseConfiguration.Value(false,2,8,2));
    b.removeProperty("phase.identity");b.removeProperty("phase.analysisKey");
    tree.traverse(new org.apache.jorphan.collections.HashTreeTraverser(){
      public void addNode(Object node,org.apache.jorphan.collections.HashTree child){if(node instanceof org.apache.jmeter.testelement.TestElement e){e.setProperty("TestElement.test_class",e.getClass().getName());e.setProperty("TestElement.gui_class",e instanceof PhaseAnalyzerListener?"io.phaseanalyzer.PhaseAnalyzerGui":e instanceof org.apache.jmeter.testelement.TestPlan?"org.apache.jmeter.control.gui.TestPlanGui":"org.apache.jmeter.threads.gui.ThreadGroupGui");}}
      public void subtractNode(){}public void processPath(){}
    });
    Path file=temp.resolve("Saved config.jmx");try(var stream=Files.newOutputStream(file)){org.apache.jmeter.save.SaveService.saveTree(tree,stream);}
    var loaded=org.apache.jmeter.save.SaveService.loadTree(file.toFile());var actual=GroupDiscovery.scan(loaded);
    assertEquals(key,actual.get(1).key());
    var found=new org.apache.jorphan.collections.SearchByClass<PhaseAnalyzerListener>(PhaseAnalyzerListener.class);loaded.traverse(found);
    var reopened=found.getSearchResults().iterator().next();assertEquals(key,reopened.getPropertyAsString("phase.group"));assertEquals(8,PhaseConfiguration.read(reopened,key).hold());assertFalse(PhaseConfiguration.read(reopened,key).automatic());
  }
  @Test void copiedListenersReceiveIndependentCaptureIds(){
    var tree=new org.apache.jorphan.collections.ListedHashTree();var a=new PhaseAnalyzerListener();tree.add(a);PlanIdentitySync.synchronize(tree);
    var b=(PhaseAnalyzerListener)a.clone();tree.add(b);PlanIdentitySync.synchronize(tree);assertNotEquals(a.getPropertyAsString("phase.id"),b.getPropertyAsString("phase.id"));
  }
  @Test void pooledPhasesUseOwnGroupWindowsAndExactUnderlyingPercentiles()throws Exception{
    RunSession run=new RunSession(temp);var config=new PhaseAnalyzerListener();config.setProperty("phase.group","All");
    var catalog=List.of(entry("A","Same",true),entry("B","Same",true),entry("C","NotStarted",true));
    config.setProperty("phase.catalog",Reports.mapper().writeValueAsString(catalog));run.prime(catalog,true);run.freezeConfiguration(config);
    try{
      for(String key:List.of("A","B")){
        var group=new org.apache.jmeter.threads.ThreadGroup();group.setName(key);group.setProperty("phase.analysisKey",key);run.groupStarted(group);
        run.groups.put(key,new RunSession.Group(key,group.getClass().getName(),run.start,Schedule.phases(0,1000,1000,1000),""));
        for(int i=0;i<10;i++)run.accept(new Sample(run.start+(i<3?100:i<7?1100:2100),key.equals("A")?10:100,"Search API",key,"local",i%3!=0,100,50,1,0,1,i%3!=0?"200":"503",false));
      }
      run.stopAccepting("Completed");run.finish();run.end=run.start+3000;run.testOutcome="Completed";
      var ends=(Map<String,Long>)field(run,"groupEnds");ends.put("A",run.end);ends.put("B",run.end);
      var result=ExecutionViews.build(config,run);assertEquals(Set.of("A","B"),result.members().keySet());
      assertEquals(20L,result.selected().phase("Complete execution").metrics().get("samples"));
      assertEquals(100.,((Number)result.selected().phase("Complete execution").metrics().get("p95Ms")).doubleValue());
      assertEquals(8L,result.selected().phase("Steady load").metrics().get("samples"));
      assertEquals(6L,result.selected().phase("Ramp-up").metrics().get("samples"));
      assertEquals(6L,result.selected().phase("Ramp-down").metrics().get("samples"));
      assertEquals(0,result.selected().unclassified());
      SwingUtilities.invokeAndWait(()->{try{
        var gui=new PhaseAnalyzerGui();gui.configure(new PhaseAnalyzerListener());gui.setDiscovered(catalog);
        ((JComboBox<?>)field(gui,"group")).setSelectedIndex(0);
        var data=PhaseAnalyzerGui.class.getDeclaredField("snapshot");data.setAccessible(true);data.set(gui,result.selected());
        var members=PhaseAnalyzerGui.class.getDeclaredField("groupSnapshots");members.setAccessible(true);members.set(gui,result.members());
        ((JComboBox<?>)field(gui,"dashboardScope")).setSelectedIndex(3);
        JTable table=(JTable)field(gui,"table");assertEquals(3,table.getRowCount());assertNotEquals(table.getValueAt(0,1),table.getValueAt(1,1));
        assertFalse(table.getValueAt(0,1).toString().startsWith("All"));assertEquals(20L,((Number)table.getValueAt(2,3)).longValue());
        TrendPanel graph=(TrendPanel)field(gui,"graph");graph.setSnapshot(result.selected());graph.setGroupSnapshots(result.members());
        for(String metric:TrendPanel.METRICS){graph.setMetric(metric);graph.setSize(1100,graph.getPreferredSize().height);var image=new java.awt.image.BufferedImage(graph.getWidth(),graph.getHeight(),java.awt.image.BufferedImage.TYPE_INT_RGB);var graphics=image.createGraphics();graph.paint(graphics);graphics.dispose();}
      }catch(Exception e){throw new RuntimeException(e);}});
      config.setProperty("phase.group","C");assertEquals("Execution Not Started",ExecutionViews.build(config,run).selected().metadata().get("executionStatus"));
      config.setProperty("phase.group","A");PhaseConfiguration.save(config,"A",new PhaseConfiguration.Value(false,9,9,9));
      assertEquals(1.,ExecutionViews.build(config,run).selected().phase("Steady load").configuredSeconds());
    }finally{run.cleanup();}
  }
  @Test void highVolumeSnapshotLeavesEventDispatchThreadResponsive()throws Exception{
    RunSession run=new RunSession(temp);var config=new PhaseAnalyzerListener();config.setProperty("phase.group","All");run.freezeConfiguration(config);
    try{
      var group=new org.apache.jmeter.threads.ThreadGroup();group.setName("Volume");group.setProperty("phase.analysisKey","volume");run.groupStarted(group);
      long sampleTime=System.currentTimeMillis();
      for(int i=0;i<20000;i++)run.accept(new Sample(sampleTime,10+i%100,"API "+i%5,"volume","local",true,100,50,1,0,1,"200",false));
      while(System.currentTimeMillis()<=sampleTime)Thread.sleep(1);
      run.stopAccepting("Completed");run.finish();
      var task=java.util.concurrent.CompletableFuture.supplyAsync(()->{try{return ExecutionViews.build(config,run);}catch(Exception e){throw new RuntimeException(e);}});
      var heartbeat=new java.util.concurrent.CountDownLatch(1);SwingUtilities.invokeLater(heartbeat::countDown);
      assertTrue(heartbeat.await(2,java.util.concurrent.TimeUnit.SECONDS),"EDT blocked during snapshot calculation");
      assertEquals(20000L,task.get(15,java.util.concurrent.TimeUnit.SECONDS).selected().phase("Complete execution").metrics().get("samples"));assertEquals(0,run.dropped.get());
    }finally{run.cleanup();}
  }
  @Test void dashboardPhaseSwitchUpdatesKpisAndApiScopeDuringAndAfterCapture()throws Exception{
    for(boolean running:List.of(true,false))SwingUtilities.invokeAndWait(()->{try{
      Map<String,Object> metadata=new HashMap<>();metadata.put("executionStart",0L);metadata.put("executionEnd",3000L);metadata.put("testStart",0L);metadata.put("executionStatus",running?"Running":"Completed");
      List<ReportSnapshot.Phase> phases=new ArrayList<>();for(String name:ReportSnapshot.PHASES){Map<String,Object> m=new HashMap<>();m.put("samples",name.equals("Complete execution")?100L:25L);m.put("averageMs",name.equals("Complete execution")?90.:10.);phases.add(new ReportSnapshot.Phase(name,"Captured",1.,1,m,List.of(),List.of(),""));}
      var snapshot=new ReportSnapshot("test","test",metadata,new Analyzer.Settings(List.of(),"A","Requests",false,500,2000),new Analyzer.Result("test","test",List.of(),List.of()),List.of(),List.of(),phases,0);
      var gui=new PhaseAnalyzerGui();var target=PhaseAnalyzerGui.class.getDeclaredField("snapshot");target.setAccessible(true);target.set(gui,snapshot);
      var dashboard=(JComboBox<?>)field(gui,"dashboardScope");var api=(JComboBox<?>)field(gui,"tableScope");var kpis=(JLabel[])field(gui,"kpis");
      dashboard.setSelectedIndex(3);assertEquals("100",kpis[0].getText());assertEquals(3,api.getSelectedIndex());
      dashboard.setSelectedIndex(1);assertEquals("25",kpis[0].getText());assertEquals(1,api.getSelectedIndex());
      dashboard.setSelectedIndex(3);assertEquals("100",kpis[0].getText());assertEquals("90 ms",kpis[1].getText());
    }catch(Exception e){throw new RuntimeException(e);}});
  }
}

