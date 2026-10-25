package io.phaseanalyzer;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.*;
import java.util.*;
import java.util.zip.ZipFile;
import javax.swing.*;
import org.apache.jmeter.util.JMeterUtils;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

class NativeGuiExportTest {
  @TempDir Path dir;
  @Test void nativeControlsAndExactChosenDestinationsPreserveExistingFiles() throws Exception {
    JMeterUtils.loadJMeterProperties("src/test/resources/jmeter-test.properties");
    SwingUtilities.invokeAndWait(()->{
      PhaseAnalyzerGui gui=new PhaseAnalyzerGui();
      List<JButton> buttons=new ArrayList<>();collect(gui,buttons);
      assertFalse(buttons.stream().anyMatch(b->b.getText().contains("CSV")||b.getText().contains("Light / Dark")));
      assertFalse(buttons.stream().filter(b->b.getText().equals("Generate HTML Report")||b.getText().equals("Export Excel")).anyMatch(JButton::isEnabled));
      assertEquals(UIManager.getColor("Panel.background"),gui.getBackground());
    });
    RunSession run=new RunSession(dir.resolve("configured"));
    try {
      run.accept(new Sample(run.start,25,"Product Search","G","local",true,100,50,2,1,1,"200",false));
      run.stopAccepting("Stopped");run.finish();
      PhaseAnalyzerListener config=new PhaseAnalyzerListener();config.setProperty("phase.automatic",false);config.setProperty("phase.hold",60L);
      ReportSnapshot data=ReportSnapshot.build(config,run);
      Path html=dir.resolve("chosen.html"),excel=dir.resolve("chosen.xlsx");
      PhaseAnalyzerGui.saveReport(data,html,"html");PhaseAnalyzerGui.saveReport(data,excel,"xlsx");
      assertTrue(Files.readString(html).contains("Product Search"));
      try(ZipFile z=new ZipFile(excel.toFile())){assertNotNull(z.getEntry("xl/workbook.xml"));}
      byte[] original=Files.readAllBytes(html);
      assertThrows(FileAlreadyExistsException.class,()->PhaseAnalyzerGui.saveReport(data,html,"html"));
      assertArrayEquals(original,Files.readAllBytes(html));
      assertThrows(NoSuchFileException.class,()->PhaseAnalyzerGui.saveReport(data,dir.resolve("missing/report.html"),"html"));
      assertFalse(Files.exists(dir.resolve("missing/report.html")));
    } finally {run.cleanup();}
  }
  private static void collect(java.awt.Container p,List<JButton> result){for(java.awt.Component c:p.getComponents()){if(c instanceof JButton b)result.add(b);if(c instanceof java.awt.Container child)collect(child,result);}}
}
