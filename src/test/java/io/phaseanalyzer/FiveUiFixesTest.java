package io.phaseanalyzer;

import static org.junit.jupiter.api.Assertions.*;
import static io.phaseanalyzer.PhaseViewBindingTest.*;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.nio.file.*;
import java.util.List;
import java.util.*;
import javax.swing.*;
import org.apache.jmeter.samplers.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

class FiveUiFixesTest {
  @TempDir Path temp;
  @BeforeAll static void init()throws Exception{PhaseViewBindingTest.init();}
  GroupDiscovery.Entry entry(String key,boolean enabled){return FunctionalUpdateTest.entry(key,key,enabled);}
  PhaseAnalyzerListener listener() {var c=new PhaseAnalyzerListener();c.setProperty("phase.output",temp.toString());return c;}
  static void sample(PhaseAnalyzerListener c,String name){var r=new SampleResult();r.setSampleLabel(name);r.sampleStart();r.sampleEnd();r.setSuccessful(true);r.setResponseCode("200");c.sampleOccurred(new SampleEvent(r,"TG"));}
  static void waitFor(java.util.function.BooleanSupplier check)throws Exception{long until=System.currentTimeMillis()+8000;while(!check.getAsBoolean()&&System.currentTimeMillis()<until){Thread.sleep(30);edt(()->{});}assertTrue(check.getAsBoolean());}

  @Test void allAvailabilityUsesEnabledEditorCountEvenWithHistory(){
    var a=entry("A",true);var b=entry("B",true);var disabled=entry("A",false);
    assertEquals("",GroupSelection.choose(List.of(disabled),"All",true));
    assertFalse(GroupSelection.allAvailable(List.of(disabled)));
    assertEquals("B",GroupSelection.choose(List.of(disabled,b),"All",true));
    assertFalse(GroupSelection.allAvailable(List.of(a)));
    assertEquals("All",GroupSelection.choose(List.of(a,b),"All",true));
    assertEquals("A",GroupSelection.choose(List.of(a,b),""));
  }
  @Test void allFallsBackDynamicallyAndStaleChoiceIsRejected()throws Exception{edt(()->{
    var gui=new PhaseAnalyzerGui();gui.configure(listener());
    gui.setDiscovered(List.of(entry("A",true),entry("B",true)));combo(gui,"group").setSelectedIndex(0);
    assertEquals("All",get(gui,"selectionKey"));
    gui.setDiscovered(List.of(entry("A",false),entry("B",true)));
    assertEquals("B",get(gui,"selectionKey"));combo(gui,"group").setSelectedIndex(0);assertEquals("B",get(gui,"selectionKey"));
    gui.setDiscovered(List.of(entry("A",false),entry("B",false)));assertEquals("",get(gui,"selectionKey"));
  });}
  @Test void completedClearPreservesFilesAndConfiguration()throws Exception{
    var c=listener();c.setProperty("phase.group","TG");c.setProperty("phase.automatic",false);c.setProperty("phase.hold",27L);
    c.testStarted();sample(c,"before");c.testEnded();var old=c.session();assertTrue(old.store.count>0);
    Path exported=temp.resolve("keep.html"),jtl=temp.resolve("keep.jtl");Files.writeString(exported,"exported evidence");Files.writeString(jtl,"native evidence");
    c.clearData();assertNull(c.session());assertEquals("TG",c.getPropertyAsString("phase.group"));assertFalse(c.getPropertyAsBoolean("phase.automatic"));assertEquals(27,c.getPropertyAsLong("phase.hold"));
    assertEquals("exported evidence",Files.readString(exported));assertEquals("native evidence",Files.readString(jtl));
  }
  @Test void activeClearStartsFreshPopulationWithoutChangingRunSchedule()throws Exception{
    var c=listener();c.testStarted();sample(c,"before");var old=c.session();
    c.clearData();var fresh=c.session();assertNotSame(old,fresh);assertTrue(c.testRunning());assertEquals(old.testStart,fresh.testStart);
    sample(c,"after");c.testEnded();assertEquals(1,fresh.store.count);
    var copy=fresh.copy();String contents=Files.readString(copy.samples());assertTrue(contents.contains("after"));assertFalse(contents.contains("before"));fresh.cleanup();
  }
  @Test void clonedGuiListenerClearDelegatesToActiveCollector()throws Exception{
    var c=listener();c.testStarted();sample(c,"before");var clone=(PhaseAnalyzerListener)c.clone();clone.clearData();
    sample(c,"after");c.testEnded();assertEquals(1,c.session().store.count);c.session().cleanup();
  }
  @Test void nativeClearContractsAndViewResetPreserveSelections()throws Exception{edt(()->{
    var c=listener();var gui=new PhaseAnalyzerGui();gui.configure(c);gui.setDiscovered(List.of(entry("A",true),entry("B",true)));
    combo(gui,"dashboardScope").setSelectedIndex(3);((JTextField)get(gui,"filter")).setText("search");showData(gui,data("A",false));
    assertInstanceOf(Clearable.class,c);assertInstanceOf(Clearable.class,gui);gui.clearData();assertEmpty(gui);
    assertPhase(gui,3);assertEquals("A",get(gui,"selectionKey"));assertEquals("search",((JTextField)get(gui,"filter")).getText());
  });}
  @Test void observedToggleIsIndependentOfSamplesAndMetric()throws Exception{edt(()->{
    var graph=new TrendPanel();var snap=data("A",false);graph.setSnapshot(snap);graph.setGroupSnapshots(Map.of("A",snap));graph.setMetric("Throughput");
    graph.setActual(false);int before=graph.getPreferredSize().height;
    graph.setActual(true);assertEquals(before+115,graph.getPreferredSize().height);assertEquals("Throughput",get(graph,"metric"));assertSame(snap,get(graph,"snapshot"));
    graph.setSize(900,400);graph.paint(new BufferedImage(900,400,BufferedImage.TYPE_INT_RGB).getGraphics());
    graph.setActual(false);assertEquals(before,graph.getPreferredSize().height);
  });}
  @Test void observedRenderingUsesActualDataWithArrivalSchedules()throws Exception{edt(()->{
    var base=data("Arrival Group",false);
    var snap=new ReportSnapshot(base.schemaVersion(),base.snapshotId(),base.metadata(),base.settings(),base.analysis(),
        List.of(new Timeline.Point(0,1,1,20.,20.,0.,9.),new Timeline.Point(1000,1,1,20.,20.,0.,3.)),
        List.of(new ReportSnapshot.Expected(0,100.,"arrivals/sec")),base.phases(),0);
    var graph=new TrendPanel();graph.setSnapshot(snap);graph.setMetric("Throughput");graph.setGroupSnapshots(Map.of("arrival",snap));graph.setSize(900,450);
    graph.setActual(false);BufferedImage without=new BufferedImage(900,450,BufferedImage.TYPE_INT_RGB);graph.paint(without.getGraphics());
    graph.setActual(true);BufferedImage with=new BufferedImage(900,450,BufferedImage.TYPE_INT_RGB);graph.paint(with.getGraphics());
    assertFalse(Arrays.equals(with.getRGB(0,0,900,450,null,0,900),without.getRGB(0,0,900,450,null,0,900)));
    assertEquals(9.,snap.timeline().get(0).active());assertEquals("Throughput",get(graph,"metric"));
  });}
  @Test void aggregateHeaderHasCompactLayoutAndControlsAtTwoSizes()throws Exception{edt(()->{
    var gui=new PhaseAnalyzerGui();gui.configure(listener());
    for(int width:List.of(1000,650)){gui.setSize(width,650);layout(gui);var table=(JTable)get(gui,"table");assertEquals(JTable.AUTO_RESIZE_OFF,table.getAutoResizeMode());
      JLabel heading=find(gui);assertNotNull(heading);assertTrue(heading.getHeight()<45,"Heading must not stretch away from controls");}
  });}
  static void layout(Container c){c.doLayout();for(Component child:c.getComponents())if(child instanceof Container nested)layout(nested);}
  static JLabel find(Container c){for(Component child:c.getComponents()){if(child instanceof JLabel label&&label.getText().equals("Live Aggregate Metrics"))return label;if(child instanceof Container nested){var result=find(nested);if(result!=null)return result;}}return null;}
  @Test void manualRefreshAndAutoRefreshPreserveFiltersAndPhase()throws Exception{
    var c=listener();c.testStarted();sample(c,"Search");var gui=new PhaseAnalyzerGui();
    edt(()->{gui.configure(c);gui.setDiscovered(List.of(entry("TG",true),entry("Other",true)));((JCheckBox)get(gui,"autoRefresh")).setSelected(false);combo(gui,"dashboardScope").setSelectedIndex(3);((JTextField)get(gui,"filter")).setText("Search");});
    waitFor(()->{try{return get(gui,"snapshot")!=null;}catch(Exception e){return false;}});
    sample(c,"Search");Thread.sleep(100);long initial=((Number)((ReportSnapshot)get(gui,"snapshot")).phase("Complete execution").metrics().get("samples")).longValue();
    edt(()->{var timer=(javax.swing.Timer)get(gui,"timer");for(var a:timer.getActionListeners())a.actionPerformed(new java.awt.event.ActionEvent(timer,0,"tick"));});
    assertEquals(initial,((Number)((ReportSnapshot)get(gui,"snapshot")).phase("Complete execution").metrics().get("samples")).longValue());
    edt(()->((JButton)get(gui,"refreshNow")).doClick());
    waitFor(()->{try{return ((Number)((ReportSnapshot)get(gui,"snapshot")).phase("Complete execution").metrics().get("samples")).longValue()==2;}catch(Exception e){return false;}});
    edt(()->{assertPhase(gui,3);assertEquals("Search",((JTextField)get(gui,"filter")).getText());assertFalse(((JCheckBox)get(gui,"autoRefresh")).isSelected());});
    c.testEnded();edt(()->((JButton)get(gui,"refreshNow")).doClick());
    waitFor(()->!(boolean)((java.util.concurrent.atomic.AtomicBoolean)unchecked(gui,"busy")).get());
    edt(()->((JCheckBox)get(gui,"autoRefresh")).doClick());assertTrue(((JCheckBox)get(gui,"autoRefresh")).isSelected());
    waitFor(()->!((java.util.concurrent.atomic.AtomicBoolean)unchecked(gui,"busy")).get());c.session().cleanup();
  }
  @Test void manualRefreshRespectsAllSelectionAndNonDefaultPhase()throws Exception{
    var c=listener();c.testStarted();sample(c,"Search");var gui=new PhaseAnalyzerGui();
    edt(()->{gui.configure(c);gui.setDiscovered(List.of(entry("TG",true),entry("Other",true)));combo(gui,"group").setSelectedIndex(0);combo(gui,"tableScope").setSelectedIndex(1);((JCheckBox)get(gui,"autoRefresh")).setSelected(false);});
    waitFor(()->!((java.util.concurrent.atomic.AtomicBoolean)unchecked(gui,"busy")).get());
    sample(c,"Search");edt(()->((JButton)get(gui,"refreshNow")).doClick());
    waitFor(()->{try{return ((Number)((ReportSnapshot)get(gui,"snapshot")).phase("Complete execution").metrics().get("samples")).longValue()==2;}catch(Exception e){return false;}});
    edt(()->{assertEquals("All",get(gui,"selectionKey"));assertPhase(gui,1);assertFalse(((JCheckBox)get(gui,"autoRefresh")).isSelected());});
    c.testEnded();edt(()->((JButton)get(gui,"refreshNow")).doClick());waitFor(()->!((java.util.concurrent.atomic.AtomicBoolean)unchecked(gui,"busy")).get());c.session().cleanup();
  }
  static Object unchecked(Object o,String name){try{return get(o,name);}catch(Exception e){throw new RuntimeException(e);}}
}
