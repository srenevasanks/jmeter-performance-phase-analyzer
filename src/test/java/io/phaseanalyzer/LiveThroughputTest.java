package io.phaseanalyzer;
import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;

class LiveThroughputTest {
  private Sample sample(long completion,String group,boolean transaction){return new Sample(completion,0,"API",group,"local",false,10,5,0,0,1,"503",transaction);}
  @Test void completedWindowExcludesPartialStartAndEndAndIncludesIdleSeconds() {
    var rate=new LiveThroughput();
    rate.record(sample(1250,"A",false),1500);
    assertNull(rate.rate("A","Requests",1250,1999));
    rate.record(sample(2100,"A",false),2100);
    rate.record(sample(2900,"A",false),2900);
    rate.record(sample(3100,"A",false),3100);
    assertEquals(2.,rate.rate("A","Requests",1250,3500));
    assertEquals(1.5,rate.rate("A","Requests",1250,4000));
    assertEquals(.6,rate.rate("A","Requests",1250,7000));
    assertEquals(0.,rate.rate("A","Requests",1250,10000));
  }
  @Test void groupsPopulationsFailuresAndFrozenEndStayIndependent() {
    var rate=new LiveThroughput();
    rate.record(sample(3000,"A",false),3000);rate.record(sample(3000,"B",true),3000);
    assertEquals(.2,rate.rate("A","Requests",0,5500));
    assertEquals(.2,rate.rate("B","Transactions",0,5500));
    assertEquals(0.,rate.rate("A","Transactions",0,5500));
    assertEquals(.4,rate.rate("All","Separate",0,5500));
    assertEquals(.2,rate.rate("A","Requests",0,5500),"Selecting another metric does not consume or mutate counters");
  }
}
