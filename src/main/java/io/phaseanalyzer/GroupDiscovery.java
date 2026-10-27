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

  private static void walk(
      HashTree tree, boolean parentEnabled, List<Entry> result, boolean assignIdentity,Object scope) {
    for (Object node : tree.list()) {
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
