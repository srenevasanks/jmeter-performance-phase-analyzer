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

  public static List<Entry> scan(HashTree tree) {
    return scan(tree, true);
  }

  public static List<Entry> scan(HashTree tree, boolean assignIdentity) {
    List<Entry> entries = new ArrayList<>();
    walk(tree, true, entries, assignIdentity);
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
      HashTree tree, boolean parentEnabled, List<Entry> result, boolean assignIdentity) {
    for (Object node : tree.list()) {
      Object value = node instanceof JMeterTreeNode n ? n.getTestElement() : node;
      boolean enabled = parentEnabled;
      if (value instanceof TestElement te) enabled &= te.isEnabled();
      if (value instanceof AbstractThreadGroup g) {
        String id = g.getPropertyAsString("phase.identity");
        if (assignIdentity
            && (id.isBlank() || result.stream().map(Entry::key).toList().contains("tg:" + id))) {
          id = UUID.randomUUID().toString();
          g.setProperty("phase.identity", id);
        }
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
      if (children != null) walk(children, enabled, result, assignIdentity);
    }
  }

  public static String key(AbstractThreadGroup g) {
    return g.getPropertyAsString("phase.analysisKey", g.getName());
  }

  private static String shortName(String n) {
    return n.substring(n.lastIndexOf('.') + 1);
  }
}
