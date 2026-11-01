package io.phaseanalyzer;

import java.util.*;
import org.apache.jmeter.gui.tree.JMeterTreeNode;
import org.apache.jmeter.testelement.TestElement;
import org.apache.jmeter.threads.AbstractThreadGroup;
import org.apache.jorphan.collections.HashTree;

/** Reads only groups in the loaded plan, preserving identity across rename/disable changes. */
public final class GroupDiscovery {
  public record Entry(
      String key,
      String name,
      String label,
      String implementation,
      boolean enabled,
      Schedule schedule,
      String warning,
      Map<String, String> properties) {
    @Override
    public String toString() {
      return label + (enabled ? "" : " (disabled)");
    }
  }

  private GroupDiscovery() {}

  // Native editors call TestElement.clear(). Keep identity outside mutable properties,
  // using weak identity (not TestElement.equals/hashCode, which change with properties).
  private static final java.lang.ref.ReferenceQueue<Object> identityQueue = new java.lang.ref.ReferenceQueue<>();
  private static final Map<IdentityRef, String> identities = new HashMap<>();
  private static final Map<IdentityRef, Map<String,IdentityRef>> owners = new HashMap<>();
  private static final class IdentityRef extends java.lang.ref.WeakReference<Object> {
    private final int hash;
    IdentityRef(Object value) { super(value, identityQueue); hash=System.identityHashCode(value); }
    @Override public int hashCode() { return hash; }
    @Override public boolean equals(Object other) { return this==other || other instanceof IdentityRef r && get()!=null && get()==r.get(); }
  }
  private static synchronized String identity(Object node, AbstractThreadGroup group, List<Entry> entries,Object scope) {
    java.lang.ref.Reference<?> dead;
    while ((dead=identityQueue.poll())!=null) {identities.remove(dead);owners.remove(dead);}
    IdentityRef ref=new IdentityRef(node);
    String id=identities.getOrDefault(ref,group.getPropertyAsString("phase.identity"));
    String candidate=id;
    var plan=owners.computeIfAbsent(new IdentityRef(scope),s->new HashMap<>());
    IdentityRef owner=plan.get(id);
    if(id.isBlank() || owner!=null&&owner.get()!=null&&owner.get()!=node || entries.stream().anyMatch(e->e.key().equals("tg:"+candidate))) id=UUID.randomUUID().toString();
    identities.put(ref,id);
    plan.put(id,ref);
    group.setProperty("phase.identity",id);
    return id;
  }

  public static List<Entry> scan(HashTree tree) {
    return scan(tree, true);
  }

  public static List<Entry> scan(HashTree tree, boolean assignIdentity) {
    List<Entry> entries = new ArrayList<>();
    Object scope=tree;
    for(Object node:tree.list()) {
      Object value=node instanceof JMeterTreeNode n?n.getTestElement():node;
      if(value instanceof org.apache.jmeter.testelement.TestPlan){scope=node;break;}
    }
    if(assignIdentity)restoreLoadedIdentities(tree,scope);
    walk(tree, true, entries, assignIdentity,scope);
    Map<String, Long> names = new HashMap<>();
    for (var e : entries) names.merge(e.name(), 1L, Long::sum);
    return entries.stream()
        .map(
            e ->
                new Entry(
                    e.key(),
                    e.name(),
                    e.name()
                        + " — "
                        + shortName(e.implementation())
                        + (names.get(e.name()) > 1
                            ? " [" + e.key().substring(Math.max(0, e.key().length() - 8)) + "]"
                            : ""),
                    e.implementation(),
                    e.enabled(),
                    e.schedule(),
                    e.warning(),
                    e.properties()))
        .toList();
  }

  private static synchronized void restoreLoadedIdentities(HashTree tree,Object scope){
    if(owners.containsKey(new IdentityRef(scope)))return; // Never resurrect a deleted in-memory node.
    List<AbstractThreadGroup> groups=new ArrayList<>();List<String> catalogs=new ArrayList<>();
    collectSaved(tree,groups,catalogs);
    for(String catalog:catalogs)try{
      var saved=Reports.mapper().readTree(catalog);
      for(int i=0;i<Math.min(groups.size(),saved.size());i++){
        var group=groups.get(i);var entry=saved.get(i);String key=entry.path("key").asText();
        if(group.getPropertyAsString("phase.identity").isBlank()&&key.startsWith("tg:")&&entry.path("name").asText().equals(group.getName())&&entry.path("implementation").asText().equals(group.getClass().getName())){
          group.setProperty("phase.identity",key.substring(3));group.setProperty("phase.analysisKey",key);
        }
      }
      if(!saved.isEmpty())break;
    }catch(Exception invalid){/* Invalid legacy catalogs do not create identities. */}
  }
  private static void collectSaved(HashTree tree,List<AbstractThreadGroup> groups,List<String> catalogs){
    List<Object> nodes=new ArrayList<>(tree.list());
    nodes.sort((a,b)->a instanceof JMeterTreeNode x&&b instanceof JMeterTreeNode y&&x.getParent()!=null&&x.getParent()==y.getParent()?Integer.compare(x.getParent().getIndex(x),y.getParent().getIndex(y)):0);
    for(Object node:nodes){Object value=node instanceof JMeterTreeNode n?n.getTestElement():node;
      if(value instanceof AbstractThreadGroup group)groups.add(group);
      if(value instanceof PhaseAnalyzerListener listener&&!listener.getPropertyAsString("phase.catalog").isBlank())catalogs.add(listener.getPropertyAsString("phase.catalog"));
      HashTree children=tree.getTree(node);if(children!=null)collectSaved(children,groups,catalogs);
    }
  }

  private static void walk(
      HashTree tree, boolean parentEnabled, List<Entry> result, boolean assignIdentity,Object scope) {
    List<Object> nodes=new ArrayList<>(tree.list());
    nodes.sort((a,b)->a instanceof JMeterTreeNode x && b instanceof JMeterTreeNode y && x.getParent()!=null && x.getParent()==y.getParent()
      ?Integer.compare(x.getParent().getIndex(x),y.getParent().getIndex(y)):0);
    for (Object node : nodes) {
      Object value = node instanceof JMeterTreeNode n ? n.getTestElement() : node;
      boolean enabled = parentEnabled;
      if (value instanceof TestElement te) enabled &= te.isEnabled();
      if (value instanceof AbstractThreadGroup g) {
        String id = g.getPropertyAsString("phase.identity");
        if (assignIdentity) id=identity(node,g,result,scope);
        String key =
            assignIdentity
                ? "tg:" + id
                : g.getPropertyAsString(
                    "phase.analysisKey", id.isBlank() ? g.getName() : "tg:" + id);
        if (assignIdentity) g.setProperty("phase.analysisKey", key);
        Map<String, String> p = new TreeMap<>();
        var it = g.propertyIterator();
        while (it.hasNext()) {
          var property = it.next();
          if (!property.getName().startsWith("phase.")
              && !property.getName().equals("ThreadGroup.main_controller"))
            p.put(property.getName(), property.getStringValue());
        }
        Schedule schedule = null;
        String warning = "";
        try {
          schedule = ScheduleAdapters.read(g);
        } catch (Exception e) {
          warning = e.getMessage();
        }
        result.add(
            new Entry(
                key,
                g.getName(),
                g.getName(),
                g.getClass().getName(),
                enabled,
                schedule,
                warning,
                p));
      }
      HashTree children = tree.getTree(node);
      if (children != null) walk(children, enabled, result, assignIdentity,scope);
    }
  }

  public static String key(AbstractThreadGroup g) {
    return g.getPropertyAsString("phase.analysisKey", g.getName());
  }

  private static String shortName(String n) {
    return n.substring(n.lastIndexOf('.') + 1);
  }
}
