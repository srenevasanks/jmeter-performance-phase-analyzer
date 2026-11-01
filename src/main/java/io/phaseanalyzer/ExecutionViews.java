package io.phaseanalyzer;

import java.io.*;
import java.nio.file.*;
import java.util.*;

/** One collection barrier; exact per-group populations and pooled combined metrics. */
final class ExecutionViews {
  record Result(ReportSnapshot selected,Map<String,ReportSnapshot> members){}
  static Result build(PhaseAnalyzerListener current,RunSession run)throws IOException{
    var copy=run.copy();
    try {
      String selected=current.getPropertyAsString("phase.group");
      if(!selected.equals("All"))return new Result(ReportSnapshot.build(run.analysisConfiguration(current,selected),run,copy),Map.of());
      Set<String> keys=new LinkedHashSet<>();
      for(String key:copy.groups().keySet())if(run.participated(key))keys.add(key);
      SampleStore.scan(copy.samples(),s->keys.add(s.group()));
      Map<String,ReportSnapshot> members=new LinkedHashMap<>();
      for(String key:keys.stream().sorted().toList())members.put(key,ReportSnapshot.build(run.analysisConfiguration(current,key),run,copy));
      if(members.isEmpty())return new Result(ReportSnapshot.build(run.analysisConfiguration(current,"All"),run,copy),members);
      return new Result(combine(current,run,copy,members),Collections.unmodifiableMap(members));
    }finally{Files.deleteIfExists(copy.samples());Files.deleteIfExists(copy.load());}
  }
  private static ReportSnapshot combine(PhaseAnalyzerListener config,RunSession run,RunSession.CaptureCopy copy,Map<String,ReportSnapshot> members)throws IOException{
    var reference=members.values().iterator().next();
    List<Analyzer.View> views=new ArrayList<>();List<ReportSnapshot.Phase> phases=new ArrayList<>();
    Map<String,Object> metadata=new LinkedHashMap<>(reference.metadata());
    metadata.put("selectedThreadGroup","All");metadata.put("selectedThreadGroupLabel","All Thread Groups — "+members.size()+" participating groups");
    metadata.put("executionStatus",copy.status().equals("Running")?"Running":run.testOutcome);
    metadata.put("executionStart",run.start);metadata.put("executionEnd",copy.cutoff());
    metadata.put("groups",copy.groups().values().stream().filter(g->members.containsKey(g.name())).toList());
    metadata.put("phaseMethod","Each sample uses its own participating Thread Group's frozen phase windows. Combined phase throughput uses the union of effective wall-clock intervals; percentiles pool original observations.");
    metadata.put("participatingThreadGroups",List.copyOf(members.keySet()));
    List<String> limitations=new ArrayList<>();
    for(var member:members.entrySet())for(Object warning:(List<?>)member.getValue().metadata().getOrDefault("warnings",List.of()))limitations.add(member.getKey()+": "+warning);
    limitations.add("Combined phases use each group's own windows; their wall-clock intervals may overlap and do not define one shared workload schedule.");
    metadata.put("warnings",limitations);
    var settings=new Analyzer.Settings(List.of(),"All",reference.settings().mode(),false,reference.settings().apdexT(),reference.settings().apdexF());
    long classified=0,complete=0;
    for(String phase:ReportSnapshot.PHASES){
      List<Window> configured=new ArrayList<>(),effective=new ArrayList<>();
      for(var member:members.values()){configured.addAll(member.phase(phase).configured());effective.addAll(member.phase(phase).effective());}
      effective=Analyzer.normalize(effective);configured=Analyzer.normalize(configured);
      Path path=Files.createTempFile(run.directory,"combined-phase-",".jtl");Files.delete(path);
      Analyzer.Result result;
      try {
        try(SampleStore filtered=new SampleStore(path)){
          SampleStore.scan(copy.samples(),sample->{
            ReportSnapshot member=members.get(sample.group());
            if(member!=null&&member.phase(phase).effective().stream().anyMatch(w->w.includes(sample,member.settings().strict())))
              try{filtered.append(sample);}catch(IOException e){throw new UncheckedIOException(e);}
          });
        }
        result=Analyzer.ranges(path,settings,Map.of(phase,effective));
      }catch(UncheckedIOException e){throw e.getCause();}finally{Files.deleteIfExists(path);}
      views.addAll(result.views());
      Analyzer.View primary=result.views().isEmpty()?null:result.views().get(0);
      Map<String,Object> total=primary==null?Map.of():primary.rows().get(primary.rows().size()-1);
      long n=primary==null?0:primary.included();if(phase.equals("Complete execution"))complete=n;else classified+=n;
      String status=effective.isEmpty()?"Unavailable":members.values().stream().anyMatch(s->s.phase(phase).status().equals("Running"))?"Running":"Captured";
      if(phase.equals("Complete execution"))status=copy.status().equals("Running")?"Running":run.testOutcome.equals("Unknown")?copy.status():run.testOutcome;
      phases.add(new ReportSnapshot.Phase(phase,status,configured.isEmpty()?null:configured.stream().mapToDouble(Window::seconds).sum(),effective.stream().mapToDouble(Window::seconds).sum(),total,configured,effective,"Per-group classification; no common artificial phase schedule."));
    }
    var analysis=new Analyzer.Result(reference.analysis().schemaVersion(),reference.analysis().methodology()+" Combined views classify before pooling observations.",List.of(),views);
    var timeline=Timeline.read(copy.samples(),copy.load(),new Window("Complete execution",run.start,Math.max(run.start,copy.cutoff())),"All",settings.mode().equals("Transactions")?"Transactions":"Requests",1000);
    return new ReportSnapshot("1.1",UUID.randomUUID().toString(),Collections.unmodifiableMap(metadata),settings,analysis,timeline,List.of(),phases,Math.max(0,complete-classified));
  }
}
