package io.phaseanalyzer;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.*;
import java.util.*;
import java.util.zip.ZipFile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Runs unchanged on the actual host filesystem; does not spoof os.name. */
class CrossPlatformTest {
  @TempDir Path temporary;

  @Test void unicodeSpaceAndNestedExportPathsPreserveUserFiles() throws Exception {
    Path output=temporary.resolve("Phase results café 東京").resolve("nested reports");
    RunSession run=new RunSession(output);
    try {
      run.jmxFilename="Checkout café 東京.jmx";
      run.accept(new Sample(run.start,25,"Product Search café","G","local",true,100,50,2,1,1,"200",false));
      run.stopAccepting("Stopped");run.finish();
      PhaseAnalyzerListener config=new PhaseAnalyzerListener();
      config.setProperty("phase.automatic",false);config.setProperty("phase.hold",60L);
      ReportSnapshot snapshot=ReportSnapshot.build(config,run);
      Path html=Reports.exportSelected(snapshot,output,"html");
      byte[] original=Files.readAllBytes(html);
      Path duplicate=Reports.exportSelected(snapshot,output,"html");
      Path excel=Reports.exportSelected(snapshot,output,"xlsx");
      assertNotEquals(html,duplicate);
      assertArrayEquals(original,Files.readAllBytes(html));
      assertEquals(output.toAbsolutePath().normalize(),html.getParent().toAbsolutePath().normalize());
      assertTrue(html.getFileName().toString().startsWith("Checkout café 東京_"));
      assertTrue(Files.readString(html).contains("Product Search café"));
      try(ZipFile archive=new ZipFile(excel.toFile())) {
        assertNotNull(archive.getEntry("xl/workbook.xml"));
        assertEquals(7,archive.stream().filter(e->e.getName().matches("xl/worksheets/sheet\\d+\\.xml")).count());
      }
      try(var files=Files.list(output)){assertEquals(3,files.count());}
    } finally {run.cleanup();}
  }

  @Test void portableNamesAndTimeZonesDoNotDependOnHostDefaults() {
    long start=java.time.Instant.parse("2026-10-28T00:00:00Z").toEpochMilli();
    for(String zone:List.of("America/Los_Angeles","Asia/Calcutta")) {
      Map<String,Object> metadata=new HashMap<>();metadata.put("jmxFilename","CON: Checkout/東京\\Flow?.jmx");
      metadata.put("reportTimestamp",start);metadata.put("reportTimeZone",zone);
      ReportSnapshot snapshot=new ReportSnapshot("test","test",metadata,null,null,List.of(),List.of(),List.of(),0);
      String html=Reports.exportFilename(snapshot,"html"),xlsx=Reports.exportFilename(snapshot,"xlsx");
      assertFalse(html.chars().anyMatch(ch -> Set.of(60,62,58,34,47,92,124,63,42).contains(ch)));
      assertEquals(html.substring(0,html.length()-5),xlsx.substring(0,xlsx.length()-5));
      assertTrue(html.contains(zone.equals("Asia/Calcutta")?"2026-10-28_05-30-00":"2026-10-27_17-00-00"));
    }
  }
}
