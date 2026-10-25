package io.phaseanalyzer;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

class LoadAndOfflineTest {
  @TempDir Path dir;

  @Test
  void timeWeightedLoadAndUnknownGap() throws Exception {
    Path load = dir.resolve("load.csv");
    Files.writeString(load, "timeStamp,threadGroup,active\n0,G,2\n500,G,4\n1000,G,0\n5000,G,10\n");
    var stats = LoadAnalysis.analyze(load, new Window("hold", 0, 5000), "G", 1000);
    assertEquals(3, stats.averageActiveThreads());
    assertEquals(4, stats.peakActiveThreads());
    assertEquals(1, stats.coveredSeconds());
  }

  @Test
  void combinedPeaksAreNotSumOfUnalignedPeaks() throws Exception {
    Path load = dir.resolve("load.csv");
    Files.writeString(
        load,
        "timeStamp,threadGroup,active\n0,A,10\n0,B,0\n500,A,0\n500,B,10\n1000,A,0\n1000,B,0\n");
    var s = LoadAnalysis.analyze(load, new Window("all", 0, 1000), "All", 1000);
    assertNull(s.peakActiveThreads());
    assertNull(s.averageActiveThreads());
  }

  @Test
  void insufficientBinCoverageDoesNotProduceStableCandidate() throws Exception {
    Path load = dir.resolve("load.csv"), samples = dir.resolve("samples.jtl");
    Files.writeString(load, "timeStamp,threadGroup,active\n0,G,10\n250,G,10\n");
    try (var store = new SampleStore(samples)) {}
    var timeline =
        Timeline.read(samples, load, new Window("all", 0, 1000), "G", "Requests", 1000, 5);
    assertNull(timeline.get(0).active());
    assertTrue(Timeline.detect(timeline, 10, 5, 1).candidates().isEmpty());
  }

  @Test
  void companionMetadataRoundTripAndMultipleCandidates() throws Exception {
    Schedule schedule = Schedule.manual(1000, 2000, 3000, 1000, 10);
    RunSession.Group group = new RunSession.Group("G", "verified", 10000, schedule, "");
    Map<String, Object> meta = new LinkedHashMap<>();
    meta.put("groups", List.of(group));
    meta.put("executionStart", 9000);
    meta.put("executionEnd", 20000);
    var tree =
        Reports.mapper()
            .readValue(
                Reports.mapper().writeValueAsString(meta),
                new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {});
    var config = OfflineMetadata.read(tree, "G");
    assertEquals(new Window("Steady load", 13000, 16000), config.windows().get(1));
    assertEquals(10, config.expected().peak());
    assertEquals(10000, config.origin());
    assertThrows(IllegalArgumentException.class, () -> OfflineMetadata.read(tree, "Missing"));
  }
}
