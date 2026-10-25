package io.phaseanalyzer;

import static org.junit.jupiter.api.Assertions.*;

import java.awt.*;
import java.awt.image.BufferedImage;
import java.nio.file.*;
import java.util.*;
import javax.imageio.ImageIO;
import javax.swing.*;
import org.apache.jmeter.util.JMeterUtils;
import org.junit.jupiter.api.*;

class GuiTest {
  @BeforeAll
  static void init() {
    if (JMeterUtils.getJMeterProperties() == null)
      JMeterUtils.loadJMeterProperties("src/test/resources/jmeter-test.properties");
    JMeterUtils.setLocale(Locale.ENGLISH);
  }

  @Test
  void actualSwingConfigurationAndRender() throws Exception {
    SwingUtilities.invokeAndWait(
        () -> {
          PhaseAnalyzerGui gui = new PhaseAnalyzerGui();
          try {
            assertEquals("Performance Phase Analyzer", gui.getStaticLabel());
            var lifecycleTimer = PhaseAnalyzerGui.class.getDeclaredField("timer");
            lifecycleTimer.setAccessible(true);
            assertFalse(((javax.swing.Timer) lifecycleTimer.get(gui)).isRunning());
            assertTrue(
                gui.getMenuCategories().contains(org.apache.jmeter.gui.util.MenuFactory.LISTENERS));
            var f = PhaseAnalyzerGui.class.getDeclaredField("fields");
            f.setAccessible(true);
            @SuppressWarnings("unchecked")
            Map<String, JTextField> fields = (Map<String, JTextField>) f.get(gui);
            fields.get("up").setText("2");
            fields.get("hold").setText("3");
            fields.get("down").setText("1");
            var unit = PhaseAnalyzerGui.class.getDeclaredField("units");
            unit.setAccessible(true);
            ((JComboBox<?>) unit.get(gui)).setSelectedIndex(1);
            PhaseAnalyzerListener element = (PhaseAnalyzerListener) gui.createTestElement();
            assertEquals(2, element.getPropertyAsLong("phase.up"), "Changing display units must preserve the duration");
            assertEquals(3, element.getPropertyAsLong("phase.hold"));
            fields.get("up").setText("-1");
            assertThrows(IllegalArgumentException.class, gui::createTestElement);
            fields.get("up").setText("2");
            gui.setSize(1250, 850);
            layout(gui);
            JTabbedPane tabs = findTabs(gui);
            assertEquals(3, tabs.getTabCount());
            assertEquals("Dashboard", tabs.getTitleAt(0));
            assertEquals("Phase Configuration", tabs.getTitleAt(1));
            assertEquals("Live Metrics", tabs.getTitleAt(2));
            Path out = Path.of("target", "gui-validation");
            Files.createDirectories(out);
            for (int i = 0; i < tabs.getTabCount(); i++) {
              tabs.setSelectedIndex(i);
              layout(gui);
              if (i == 2) {
                var phasesField = PhaseAnalyzerGui.class.getDeclaredField("livePhases");
                phasesField.setAccessible(true);
                JPanel cards = (JPanel) phasesField.get(gui);
                assertEquals(1, cards.getComponentCount(), "Phase summary uses one native table");
                assertTrue(cards.getHeight() >= 100, "Live phase cards must remain visible");
              }
              BufferedImage image = new BufferedImage(1250, 850, BufferedImage.TYPE_INT_RGB);
              Graphics2D g = image.createGraphics();
              gui.printAll(g);
              g.dispose();
              ImageIO.write(image, "png", out.resolve("gui-" + i + ".png").toFile());
            }
            for (int[] size : new int[][] {{1366, 768}, {1920, 1080}}) {
              gui.setSize(size[0], size[1]);
              for (int i = 0; i < 3; i++) {
                tabs.setSelectedIndex(i);
                layout(gui);
                BufferedImage image =
                    new BufferedImage(size[0], size[1], BufferedImage.TYPE_INT_RGB);
                Graphics2D g = image.createGraphics();
                gui.printAll(g);
                g.dispose();
                ImageIO.write(
                    image, "png", out.resolve("light-" + size[0] + "-" + i + ".png").toFile());
              }
            }
            SwingUtilities.updateComponentTreeUI(gui);
            for (int i = 0; i < 3; i++) {
              tabs.setSelectedIndex(i);
              layout(gui);
              BufferedImage image =
                  new BufferedImage(gui.getWidth(), gui.getHeight(), BufferedImage.TYPE_INT_RGB);
              Graphics2D g = image.createGraphics();
              gui.printAll(g);
              g.dispose();
              ImageIO.write(image, "png", out.resolve("dark-" + i + ".png").toFile());
            }
          } catch (Exception e) {
            throw new RuntimeException(e);
          } finally {
            try {
              var t = PhaseAnalyzerGui.class.getDeclaredField("timer");
              t.setAccessible(true);
              ((javax.swing.Timer) t.get(gui)).stop();
            } catch (Exception e) {
              throw new RuntimeException(e);
            }
          }
        });
  }

  private static void layout(Container c) {
    c.doLayout();
    for (Component child : c.getComponents())
      if (child instanceof Container container) layout(container);
  }

  private static JTabbedPane findTabs(Container c) {
    for (Component child : c.getComponents()) {
      if (child instanceof JTabbedPane t) return t;
      if (child instanceof Container container) {
        var found = findTabs(container);
        if (found != null) return found;
      }
    }
    return null;
  }
}
