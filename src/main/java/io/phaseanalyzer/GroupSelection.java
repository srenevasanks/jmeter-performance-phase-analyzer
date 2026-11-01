package io.phaseanalyzer;
import java.util.*;
final class GroupSelection {
  static String choose(List<GroupDiscovery.Entry> entries,String selected) {
    return choose(entries,selected,false);
  }
  static String choose(List<GroupDiscovery.Entry> entries,String selected,boolean historyAvailable) {
    if(entries.stream().noneMatch(GroupDiscovery.Entry::enabled))return "";
    if("All".equals(selected)&&allAvailable(entries))return "All";
    if(entries.stream().anyMatch(e->e.enabled()&&e.key().equals(selected)))return selected;
    return entries.stream().filter(GroupDiscovery.Entry::enabled).findFirst().orElseThrow().key();
  }
  static boolean allAvailable(List<GroupDiscovery.Entry> entries){return entries.stream().filter(GroupDiscovery.Entry::enabled).count()>=2;}
}
