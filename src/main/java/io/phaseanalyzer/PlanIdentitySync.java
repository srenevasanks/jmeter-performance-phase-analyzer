package io.phaseanalyzer;

import org.apache.jmeter.gui.GuiPackage;
import org.apache.jmeter.gui.action.ActionRouter;
import org.apache.jmeter.gui.action.Start;
import org.apache.jmeter.gui.tree.JMeterTreeNode;
import org.apache.jorphan.collections.HashTree;

/** Restores identities after native editors save and before Start clones the plan. */
final class PlanIdentitySync {
  private static boolean installed;
  private static boolean clearingAll;
  static boolean clearingAll(){return clearingAll;}
  private static final java.util.Map<String,java.lang.ref.WeakReference<PhaseAnalyzerListener>> captureOwners=new java.util.HashMap<>();
  private static synchronized void captureIdentity(PhaseAnalyzerListener listener){
    captureOwners.entrySet().removeIf(e->e.getValue().get()==null);
    String id=listener.getPropertyAsString("phase.id");var owner=captureOwners.get(id);
    if(id.isBlank()||owner!=null&&owner.get()!=listener){id=java.util.UUID.randomUUID().toString();listener.setProperty("phase.id",id);}
    captureOwners.put(id,new java.lang.ref.WeakReference<>(listener));
  }
  static synchronized void install() {
    if(installed) return;
    ActionRouter.getInstance().addPreActionListener(org.apache.jmeter.gui.action.Clear.class,event->{
      if(!org.apache.jmeter.gui.action.ActionNames.CLEAR_ALL.equals(event.getActionCommand()))return;
      GuiPackage gui=GuiPackage.getInstance();if(gui==null)return;
      clearingAll=true;
      try{
        for(var node:gui.getTreeModel().getNodesOfType(PhaseAnalyzerListener.class))
          ((PhaseAnalyzerListener)node.getTestElement()).clearData();
      }catch(RuntimeException failure){clearingAll=false;throw failure;}
    });
    ActionRouter.getInstance().addPostActionListener(org.apache.jmeter.gui.action.Clear.class,event->clearingAll=false);
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
    ActionRouter.getInstance().addPreActionListener(org.apache.jmeter.gui.action.Save.class,event->{
      GuiPackage gui=GuiPackage.getInstance();
      if(gui!=null){gui.updateCurrentNode();synchronize(gui.getTreeModel().getTestPlan());}
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
        captureIdentity(listener);
        try {listener.setProperty("phase.catalog",Reports.mapper().writeValueAsString(entries));}
        catch(Exception e){throw new IllegalStateException("Cannot synchronize Thread Groups",e);}
        listener.setProperty("phase.group",GroupSelection.choose(entries,listener.getPropertyAsString("phase.group"),listener.session()!=null&&listener.session().hasParticipatingGroups()));
      }
      HashTree children=tree.getTree(node);
      if(children!=null)update(children,entries);
    }
  }
}
