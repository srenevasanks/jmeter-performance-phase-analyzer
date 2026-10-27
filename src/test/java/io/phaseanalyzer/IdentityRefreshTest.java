package io.phaseanalyzer;

import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import javax.swing.*;
import org.apache.jmeter.gui.tree.JMeterTreeNode;
import org.apache.jmeter.threads.AbstractThreadGroup;
import org.apache.jmeter.util.JMeterUtils;
import org.apache.jorphan.collections.ListedHashTree;
import org.junit.jupiter.api.*;

class IdentityRefreshTest {
  static List<AbstractThreadGroup> groups() {
    return List.of(new org.apache.jmeter.threads.ThreadGroup(),new kg.apc.jmeter.threads.UltimateThreadGroup(),
      new kg.apc.jmeter.threads.SteppingThreadGroup(),new com.blazemeter.jmeter.threads.concurrency.ConcurrencyThreadGroup(),
      new com.blazemeter.jmeter.threads.arrivals.ArrivalsThreadGroup(),new com.blazemeter.jmeter.threads.arrivals.FreeFormArrivalsThreadGroup(),
      new org.apache.jmeter.threads.openmodel.OpenModelThreadGroup(),new org.apache.jmeter.threads.SetupThreadGroup(),new org.apache.jmeter.threads.PostThreadGroup());
  }
  @BeforeAll static void init(){JMeterUtils.loadJMeterProperties("src/test/resources/jmeter-test.properties");JMeterUtils.setLocale(Locale.ENGLISH);}
  @Test void allNineNativeEditorsCanClearPropertiesWithoutChangingNodeIdentity() {
    ListedHashTree tree=new ListedHashTree();var gs=groups();List<JMeterTreeNode> nodes=new ArrayList<>();
    for(int i=0;i<gs.size();i++){var g=gs.get(i);g.setName("G"+i);g.setEnabled(true);var n=new JMeterTreeNode(g,null);nodes.add(n);tree.add(n);}
    var original=GroupDiscovery.scan(tree);assertEquals(9,original.stream().map(GroupDiscovery.Entry::key).distinct().count());
    for(int i=0;i<gs.size();i++) {
      var g=gs.get(i);g.clear();g.setName("Renamed "+i);g.setEnabled(i%2==0);
      var refreshed=GroupDiscovery.scan(tree);
      assertEquals(original.get(i).key(),refreshed.get(i).key());
      assertEquals(original.get(i).key(),GroupDiscovery.key((AbstractThreadGroup)g.clone()));
      assertEquals(i%2==0,refreshed.get(i).enabled());assertEquals(g.getName(),refreshed.get(i).name());
    }
    var copy=(AbstractThreadGroup)gs.get(0).clone();tree.add(new JMeterTreeNode(copy,null));
    assertEquals(10,GroupDiscovery.scan(tree).stream().map(GroupDiscovery.Entry::key).distinct().count());
    tree.remove(nodes.get(0));assertTrue(GroupDiscovery.scan(tree).stream().noneMatch(e->e.key().equals(original.get(0).key())));
    tree.add(new JMeterTreeNode((AbstractThreadGroup)gs.get(0).clone(),null));
    assertTrue(GroupDiscovery.scan(tree).stream().noneMatch(e->e.key().equals(original.get(0).key())),"Replacement must not inherit deleted node's sample identity");
  }
  @Test void preStartRefreshUpdatesCatalogAndSoleEnabledExecutionSelection() throws Exception {
    ListedHashTree tree=new ListedHashTree();var listener=new PhaseAnalyzerListener();tree.add(listener);
    var a=new org.apache.jmeter.threads.ThreadGroup();a.setName("A");a.setEnabled(true);tree.add(a);
    var b=new org.apache.jmeter.threads.SetupThreadGroup();b.setName("B");b.setEnabled(false);tree.add(b);
    var first=PlanIdentitySync.synchronize(tree);listener.setProperty("phase.group",first.get(0).key());
    a.setEnabled(false);b.setEnabled(true);PlanIdentitySync.synchronize(tree);
    assertEquals(GroupDiscovery.key(b),listener.getPropertyAsString("phase.group"));
    assertTrue(listener.getPropertyAsString("phase.catalog").contains("SetupThreadGroup"));
    tree.remove(b);PlanIdentitySync.synchronize(tree);
    assertEquals(GroupDiscovery.key(b),listener.getPropertyAsString("phase.group"),"Deleted selection must not silently map to another group");
  }
  @Test void dashboardSelectionTracksAllNineAndPreservesUnavailableSelection() throws Exception {
    SwingUtilities.invokeAndWait(()->{
      try {
        var tree=new ListedHashTree();for(var g:groups()){g.setName(g.getClass().getName());g.setEnabled(true);tree.add(g);}
        var entries=GroupDiscovery.scan(tree);var gui=new PhaseAnalyzerGui();var listener=new PhaseAnalyzerListener();
        gui.configure(listener);gui.setDiscovered(entries);
        var field=PhaseAnalyzerGui.class.getDeclaredField("group");field.setAccessible(true);var combo=(JComboBox<?>)field.get(gui);
        for(int i=0;i<9;i++){combo.setSelectedIndex(i+1);assertEquals(entries.get(i).key(),listener.getPropertyAsString("phase.group"));}
        String selected=listener.getPropertyAsString("phase.group");gui.setDiscovered(entries.subList(0,8));
        assertEquals(selected,listener.getPropertyAsString("phase.group"));assertTrue(combo.getSelectedItem().toString().contains("unavailable"));
      } catch(Exception e){throw new RuntimeException(e);}
    });
  }

  @Test void nativeSaveCommitRequiresIdentityRefreshBeforeExecutionClone() throws Exception {
    SwingUtilities.invokeAndWait(()->{
      var editor=new org.apache.jmeter.threads.openmodel.gui.OpenModelThreadGroupGui();
      var group=(AbstractThreadGroup)editor.createTestElement();group.setName("Native Open Model");
      var node=new JMeterTreeNode(group,null);var tree=new ListedHashTree();tree.add(node);
      String key=GroupDiscovery.scan(tree).get(0).key();
      editor.configure(group);
      editor.modifyTestElement(group); // The real native Save-before-run commit.
      assertNotEquals(key,GroupDiscovery.key(group));
      PlanIdentitySync.synchronize(tree); // Same callback registered after Save.
      assertEquals(key,GroupDiscovery.key((AbstractThreadGroup)group.clone()));
    });
  }
}
