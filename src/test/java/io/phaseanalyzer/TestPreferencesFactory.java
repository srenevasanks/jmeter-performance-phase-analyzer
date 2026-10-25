package io.phaseanalyzer;

import java.util.*;
import java.util.prefs.*;

/** Tests avoid Windows registry access and leave user JMeter preferences intact. */
public final class TestPreferencesFactory implements PreferencesFactory {
  private final Preferences root = new Memory(null, "");

  public Preferences systemRoot() {
    return root;
  }

  public Preferences userRoot() {
    return root;
  }

  static final class Memory extends AbstractPreferences {
    private final Map<String, String> values = new HashMap<>();
    private final Map<String, Memory> children = new HashMap<>();

    Memory(AbstractPreferences parent, String name) {
      super(parent, name);
    }

    protected void putSpi(String k, String v) {
      values.put(k, v);
    }

    protected String getSpi(String k) {
      return values.get(k);
    }

    protected void removeSpi(String k) {
      values.remove(k);
    }

    protected void removeNodeSpi() {
      values.clear();
      children.clear();
    }

    protected String[] keysSpi() {
      return values.keySet().toArray(String[]::new);
    }

    protected String[] childrenNamesSpi() {
      return children.keySet().toArray(String[]::new);
    }

    protected AbstractPreferences childSpi(String name) {
      return children.computeIfAbsent(name, k -> new Memory(this, k));
    }

    protected void syncSpi() {}

    protected void flushSpi() {}
  }
}
