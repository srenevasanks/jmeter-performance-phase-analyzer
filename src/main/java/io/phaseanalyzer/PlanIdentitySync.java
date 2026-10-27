package io.phaseanalyzer;

import org.apache.jmeter.gui.GuiPackage;
import org.apache.jmeter.gui.action.ActionRouter;
import org.apache.jmeter.gui.action.Start;
import org.apache.jmeter.gui.tree.JMeterTreeNode;
import org.apache.jorphan.collections.HashTree;

/** Restores identities after native editors save and before Start clones the plan. */
final class PlanIdentitySync {
  private static boolean installed;
  static synchronized void install() {
    if(installed) return;
    ActionRouter.getInstance().addPreActionListener(Start.class,event->{
      GuiPackage gui=GuiPackage.getInstance();
      if(gui==null)return;
      gui.updateCurrentNode();
      synchronize(gui.getTreeModel().getTestPlan());
    });
    // Start may invoke Save internally. Save commits the native editor again,
    // clearing custom properties after our Start pre-listener has run.
    ActionRouter.getInstance().addPostActionListener(org.apache.jmeter.gui.action.Save.class,event->{
      GuiPackage gui=GuiPackage.getInstance();
      if(gui!=null)synchronize(gui.getTreeModel().getTestPlan());
    });
    installed=true;
  }
  static java.util.List<GroupDiscovery.Entry> synchronize(HashTree tree) {
    var entries=GroupDiscovery.scan(tree);
    update(tree,entries);
    return entries;
  }
  private static void update(HashTree tree,java.util.List<GroupDiscovery.Entry> entries) {
    for(Object node:tree.list()) {
      Object value=node instanceof JMeterTreeNode n?n.getTestElement():node;
      if(value instanceof PhaseAnalyzerListener listener) {
        try {listener.setProperty("phase.catalog",Reports.mapper().writeValueAsString(entries));}
        catch(Exception e){throw new IllegalStateException("Cannot synchronize Thread Groups",e);}
        String selected=listener.getPropertyAsString("phase.group","All");
        var enabled=entries.stream().filter(GroupDiscovery.Entry::enabled).toList();
        boolean disabled=entries.stream().anyMatch(g->g.key().equals(selected)&&!g.enabled());
        if(disabled&&enabled.size()==1) listener.setProperty("phase.group",enabled.get(0).key());
      }
      HashTree children=tree.getTree(node);
      if(children!=null)update(children,entries);
    }
  }
}
