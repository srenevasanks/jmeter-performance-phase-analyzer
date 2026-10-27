package io.phaseanalyzer;

import java.util.*;

/** Live-only completion rate. Exports continue to use the verified Analyzer denominator. */
final class LiveThroughput {
  private record Scope(String group, boolean transaction) {}
  private final Map<Scope,NavigableMap<Long,Long>> buckets=new HashMap<>();
  synchronized void record(Sample sample,long now) {
    long second=Math.floorDiv(sample.end(),1000);
    buckets.values().forEach(b->b.headMap(Math.floorDiv(now,1000)-6,false).clear());
    if(second<Math.floorDiv(now,1000)-6)return;
    buckets.computeIfAbsent(new Scope(sample.group(),sample.transaction()),s->new TreeMap<>()).merge(second,1L,Long::sum);
  }
  synchronized Double rate(String group,String population,long captureStart,long cutoff) {
    long end=Math.floorDiv(cutoff,1000), begin=Math.max(Math.floorDiv(captureStart+999,1000),end-5);
    if(end<=begin)return null;
    long count=0;
    for(var entry:buckets.entrySet()) {
      Scope s=entry.getKey();
      if(!group.equals("All")&&!s.group().equals(group))continue;
      if(!population.equals("Separate")&&s.transaction()!=population.equals("Transactions"))continue;
      count+=entry.getValue().subMap(begin,true,end,false).values().stream().mapToLong(Long::longValue).sum();
    }
    return (double)count/(end-begin);
  }
}
