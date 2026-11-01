package io.phaseanalyzer;

import java.util.*;

/** Per-listener, per-identity saved analysis settings; independent of selection. */
final class PhaseConfiguration {
  record Value(boolean automatic,long up,long hold,long down) {
    Value { if(up<0||hold<0||down<0)throw new IllegalArgumentException("Durations must be nonnegative");
      Math.multiplyExact(Math.addExact(Math.addExact(up,hold),down),1000);
      if(!automatic&&up+hold+down==0)throw new IllegalArgumentException("Enable at least one nonzero phase"); }
  }
  static Value defaults(){return new Value(true,0,60,0);}
  static void migrateLegacy(PhaseAnalyzerListener c){
    String key=c.getPropertyAsString("phase.group"),raw=c.getPropertyAsString("phase.configurations");
    if((raw.isBlank()||raw.equals("{}"))&&!key.isBlank()&&!key.equals("All"))save(c,key,read(c,key));
  }
  static Value read(PhaseAnalyzerListener c,String key) {
    if(key==null||key.isBlank()||key.equals("All"))return defaults();
    try {
      var json=Reports.mapper().readTree(c.getPropertyAsString("phase.configurations","{}"));
      if(json.has(key))return Reports.mapper().treeToValue(json.get(key),Value.class);
      if(json.isEmpty()&&key.equals(c.getPropertyAsString("phase.group")))
        return new Value(c.getPropertyAsBoolean("phase.automatic",true),c.getPropertyAsLong("phase.up"),c.getPropertyAsLong("phase.hold",60),c.getPropertyAsLong("phase.down"));
    } catch(Exception invalid) { /* Invalid/missing saved settings safely become Automatic. */ }
    return defaults();
  }
  static void save(PhaseAnalyzerListener c,String key,Value value) {
    if(key==null||key.isBlank()||key.equals("All"))return;
    try {
      var json=Reports.mapper().createObjectNode();
      try {var previous=Reports.mapper().readTree(c.getPropertyAsString("phase.configurations","{}"));if(previous.isObject())json.setAll((com.fasterxml.jackson.databind.node.ObjectNode)previous);}catch(Exception ignored){}
      json.set(key,Reports.mapper().valueToTree(value));
      c.setProperty("phase.configurations",json.toString());apply(c,value);
    }catch(Exception e){throw new IllegalArgumentException("Cannot save phase settings",e);}
  }
  static void apply(PhaseAnalyzerListener c,Value value) {
    c.setProperty("phase.automatic",value.automatic());c.setProperty("phase.up",value.up());
    c.setProperty("phase.hold",value.hold());c.setProperty("phase.down",value.down());
  }
}
