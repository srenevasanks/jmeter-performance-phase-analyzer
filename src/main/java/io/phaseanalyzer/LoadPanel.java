package io.phaseanalyzer;

import java.awt.*;
import java.util.*;
import java.util.List;
import javax.swing.*;

public final class LoadPanel extends JPanel {
  private static final long serialVersionUID = 1L;
  private volatile List<Timeline.Point> points = List.of();
  private volatile Schedule expected;
  private volatile long origin;
  private volatile String metric = "Active threads";

  public LoadPanel() {
    setPreferredSize(new Dimension(900, 280));
    setToolTipText(
        "Blue: measured engine load. Amber: configured schedule. Chart P95 is a sampled estimate;"
            + " aggregate tables are exact.");
  }

  public void update(List<Timeline.Point> p, Schedule s, long o, String m) {
    points = p;
    expected = s;
    origin = o;
    metric = m;
    repaint();
  }

  @Override
  protected void paintComponent(Graphics raw) {
    super.paintComponent(raw);
    Graphics2D g = (Graphics2D) raw.create();
    g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
    int width = getWidth() - 85, height = getHeight() - 75;
    if (width <= 0 || height <= 0) {
      g.dispose();
      return;
    }
    g.setColor(getForeground());
    g.drawLine(60, 25, 60, 25 + height);
    g.drawLine(60, 25 + height, 60 + width, 25 + height);
    List<Timeline.Point> p = points;
    Schedule s = expected;
    long first = p.isEmpty() ? origin : p.get(0).time(),
        last =
            p.isEmpty()
                ? origin + (s == null ? 1000 : Math.max(1000, s.end()))
                : p.get(p.size() - 1).time()
                    + (long) (p.get(p.size() - 1).durationSeconds() * 1000);
    double max = 1;
    for (var v : p) {
      Double y = value(v);
      if (y != null) max = Math.max(max, y);
    }
    if (metric.equals("Active threads") && s != null) max = Math.max(max, s.peak());
    g.drawString(metric + " · Blue observed / Amber expected (configured preview)", 60, 17);
    g.drawString(String.format(Locale.ROOT, "%.1f", max), 8, 32);
    g.drawString("0", 35, 25 + height);
    g.drawString("Seconds from timeline origin", 60, 25 + height + 35);
    g.drawString(
        String.format(Locale.ROOT, "%.1f", (last - first) / 1000.0),
        60 + width - 40,
        25 + height + 18);
    if (s != null && metric.equals("Active threads")) {
      for (Window w : s.windows(origin)) {
        g.setColor(
            w.phase().equals("Steady load")
                ? new Color(32, 150, 90, 30)
                : w.phase().equals("Ramp-up")
                    ? new Color(224, 162, 30, 30)
                    : new Color(210, 90, 40, 30));
        int a = 60 + (int) ((w.start() - first) * (double) width / Math.max(1, last - first)),
            b = 60 + (int) ((w.end() - first) * (double) width / Math.max(1, last - first));
        g.fillRect(a, 25, b - a, height);
        g.setColor(getForeground());
        if (b - a > 90) g.drawString(w.phase(), Math.max(65, a + 8), 68);
      }
      g.setColor(new Color(210, 147, 30));
      int previous = -1;
      for (int x = 0; x <= width; x++) {
        long t = first + (last - first) * x / width;
        int y = 25 + height - (int) (s.expected(t - origin) * height / max);
        if (previous >= 0) g.drawLine(59 + x, previous, 60 + x, y);
        previous = y;
      }
    }
    g.setColor(new Color(27, 128, 210));
    int px = -1, py = -1;
    for (var v : p) {
      Double val = value(v);
      if (val == null) {
        px = -1;
        continue;
      }
      int x = 60 + (int) ((v.time() - first) * (double) width / Math.max(1, last - first)),
          y = 25 + height - (int) (val * height / max);
      if (px >= 0) g.drawLine(px, py, x, y);
      px = x;
      py = y;
    }
    if (p.isEmpty()) g.drawString("Configured preview — no measured load available", 70, 50);
    g.dispose();
  }

  private Double value(Timeline.Point p) {
    return switch (metric) {
      case "Sample rate / sec" -> p.samples() / p.durationSeconds();
      case "Average / ms" -> p.averageMs();
      case "Estimated P95 / ms" -> p.sampledP95Ms();
      case "Errors / %" -> p.errorPercent();
      default -> p.active();
    };
  }
}
