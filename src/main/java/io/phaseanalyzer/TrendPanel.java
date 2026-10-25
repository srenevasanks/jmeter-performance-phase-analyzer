package io.phaseanalyzer;

import java.awt.*;
import java.awt.event.*;
import java.awt.geom.*;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.List;
import javax.swing.*;

/** One compact, unit-aware trend graph. All Metrics normalizes each line to its own peak. */
public final class TrendPanel extends JPanel {
  private static final long serialVersionUID = 1L;
  public static final String[] METRICS = {
    "All Metrics",
    "Passed Requests",
    "Failed Requests",
    "Throughput",
    "Average Response Time",
    "P95 Response Time",
    "Error Rate"
  };
  private static final Color[] COLORS = {
    new Color(51, 125, 224),
    new Color(26, 155, 111),
    new Color(215, 79, 91),
    new Color(0, 150, 190),
    new Color(214, 151, 31),
    new Color(147, 95, 203),
    new Color(200, 83, 160),
    new Color(104, 123, 142)
  };
  private ReportSnapshot snapshot;
  private Schedule preview;
  private String metric = "Throughput";
  private boolean actual;
  private final Set<Integer> hidden = new HashSet<>();
  private final Map<Rectangle, Integer> legend = new LinkedHashMap<>();
  private int hover = -1;

  public TrendPanel() {
    setPreferredSize(new Dimension(800, 235));
    setMinimumSize(new Dimension(300, 180));
    setToolTipText("");
    addMouseListener(
        new MouseAdapter() {
          public void mouseClicked(MouseEvent e) {
            for (var item : legend.entrySet())
              if (item.getKey().contains(e.getPoint())) {
                if (!hidden.add(item.getValue())) hidden.remove(item.getValue());
                repaint();
                break;
              }
          }
        });
    addMouseMotionListener(
        new MouseMotionAdapter() {
          public void mouseMoved(MouseEvent e) {
            if (snapshot == null || snapshot.timeline().isEmpty()) return;
            long min = first(), max = last();
            double fraction =
                Math.max(0, Math.min(1, (e.getX() - 64.0) / Math.max(1, getWidth() - 132)));
            long t = min + (long) ((max - min) * fraction);
            int nearest = 0;
            for (int i = 1; i < snapshot.timeline().size(); i++)
              if (Math.abs(snapshot.timeline().get(i).time() - t)
                  < Math.abs(snapshot.timeline().get(nearest).time() - t)) nearest = i;
            hover = nearest;
            repaint();
          }
        });
  }

  public void setSnapshot(ReportSnapshot value) {
    snapshot = value;
    setPreferredSize(new Dimension(800,value==null?105:205));
    revalidate();
    preview = null;
    repaint();
  }

  public void setPreview(Schedule value) {
    snapshot = null;
    preview = value;
    repaint();
  }

  public void setMetric(String value) {
    metric = value;
    repaint();
  }

  public void setActual(boolean value) {
    actual = value;
    repaint();
  }

  private long first() {
    if (snapshot != null) return ((Number) snapshot.metadata().get("executionStart")).longValue();
    return 0;
  }

  private long last() {
    if (snapshot != null) {
      long end = ((Number) snapshot.metadata().get("executionEnd")).longValue();
      for (var p : snapshot.phases()) for (var w : p.configured()) end = Math.max(end, w.end());
      return Math.max(first() + 1, end);
    }
    return Math.max(1, preview == null ? 1 : preview.end());
  }

  private record Line(String name, String unit, int color, List<Long> times, List<Double> values) {}

  private List<Line> lines() {
    List<Line> lines = new ArrayList<>();
    if (snapshot != null) {
      if (!snapshot.expected().isEmpty())
        lines.add(
            new Line(
                "Configured workload",
                snapshot.expected().get(0).unit(),
                0,
                snapshot.expected().stream().map(ReportSnapshot.Expected::time).toList(),
                snapshot.expected().stream().map(ReportSnapshot.Expected::value).toList()));
      for (int m = 1; m <= 6; m++)
        if (metric.equals("All Metrics") || metric.equals(METRICS[m])) {
          final int index = m;
          lines.add(
              new Line(
                  METRICS[m],
                  unit(m),
                  m,
                  snapshot.timeline().stream().map(Timeline.Point::time).toList(),
                  snapshot.timeline().stream().map(p -> value(p, index)).toList()));
        }
      if (actual && snapshot.expected().stream().allMatch(p -> p.unit().equals("users")))
        lines.add(
            new Line(
                "Observed users",
                "users",
                7,
                snapshot.timeline().stream().map(Timeline.Point::time).toList(),
                snapshot.timeline().stream().map(Timeline.Point::active).toList()));
    } else if (preview != null && !preview.loadUnit().equals("unavailable")) {
      List<Long> times = new ArrayList<>();
      List<Double> values = new ArrayList<>();
      for (int i = 0; i <= 150; i++) {
        long t = last() * i / 150;
        times.add(t);
        double v = preview.expected(t);
        values.add(Double.isFinite(v) ? v : null);
      }
      lines.add(new Line("Configured workload", preview.loadUnit(), 0, times, values));
    }
    return lines;
  }

  private static String unit(int metric) {
    return switch (metric) {
      case 1, 2 -> "samples/bin";
      case 3 -> "samples/sec";
      case 4, 5 -> "ms";
      case 6 -> "%";
      default -> "";
    };
  }

  private static Double value(Timeline.Point p, int m) {
    return switch (m) {
      case 1 -> (double) p.passed();
      case 2 -> (double) p.failed();
      case 3 -> p.durationSeconds() <= 0 ? null : p.samples() / p.durationSeconds();
      case 4 -> p.averageMs();
      case 5 -> p.sampledP95Ms();
      case 6 -> p.errorPercent();
      default -> null;
    };
  }

  @Override
  public String getToolTipText(MouseEvent event) {
    if (snapshot == null || hover < 0 || hover >= snapshot.timeline().size())
      return "Configured timeline; click a legend label to hide/show a line";
    var p = snapshot.timeline().get(hover);
    return "<html>"
        + DateTimeFormatter.ISO_LOCAL_DATE_TIME.format(
            Instant.ofEpochMilli(p.time()).atZone(ZoneId.systemDefault()))
        + "<br>Bin: "
        + String.format(Locale.ROOT, "%.3f", p.durationSeconds())
        + " sec; passed "
        + p.passed()
        + ", failed "
        + p.failed()
        + "<br>Throughput "
        + fmt(value(p, 3))
        + " samples/sec; average "
        + fmt(p.averageMs())
        + " ms<br>P95 "
        + fmt(p.sampledP95Ms())
        + " ms (chart estimate); error "
        + fmt(p.errorPercent())
        + "%<br>Observed users "
        + fmt(p.active())
        + "</html>";
  }

  private static String fmt(Double d) {
    return d == null ? "N/A" : String.format(Locale.ROOT, "%.2f", d);
  }

  @Override
  protected void paintComponent(Graphics raw) {
    super.paintComponent(raw);
    Graphics2D g = (Graphics2D) raw.create();
    g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
    g.setFont(getFont().deriveFont(11f));
    int left = 64,
        top = 38,
        width = Math.max(1, getWidth() - 132),
        height = Math.max(30, getHeight() - 115);
    long min = first(), max = last();
    List<Line> lines = lines();
    if(snapshot==null && preview==null) {
      g.setColor(getForeground());
      g.setFont(getFont().deriveFont(Font.BOLD,14f));String message="Waiting for capture";
      g.drawString(message,(getWidth()-g.getFontMetrics().stringWidth(message))/2,getHeight()/2-8);
      g.setFont(getFont().deriveFont(12f));message="Live measurements appear when JMeter starts. No data has been collected.";
      g.drawString(message,Math.max(12,(getWidth()-g.getFontMetrics().stringWidth(message))/2),getHeight()/2+16);g.dispose();return;
    }
    boolean normalized = snapshot!=null && metric.equals("All Metrics");
    double loadMax = 1, metricMax = 1;
    for (Line line : lines)
      if (!hidden.contains(line.color()))
        for (Double v : line.values())
          if (v != null) {
            if (line.color() == 0 || line.color() == 7) loadMax = Math.max(loadMax, v);
            else metricMax = Math.max(metricMax, v);
          }
    g.setColor(getForeground());
    g.drawString(
        snapshot==null ? (lines.isEmpty()?"Analytical phase windows · workload unavailable":"Configured workload ("+preview.loadUnit()+")") : normalized
            ? "Normalized trends: 0–100% of each line's peak; hover for original values"
            : (lines.isEmpty()
                ? "Phase windows only — configured workload unavailable"
                : "Left: workload ("+(snapshot!=null&&!snapshot.expected().isEmpty()?snapshot.expected().get(0).unit():preview!=null?preview.loadUnit():"unavailable")+")  |  Right: "
                    + metric
                    + " ("
                    + unit(Arrays.asList(METRICS).indexOf(metric))
                    + ")"),
        left,
        16);
    List<Window> windows = new ArrayList<>();
    if (snapshot != null)
      for (var p : snapshot.phases())
        if (!p.name().equals("Complete execution")) windows.addAll(p.configured());
        else {
        }
    if (preview != null) windows.addAll(preview.windows(0));
    List<Window> merged=new ArrayList<>();
    for(Window w:windows){if(!merged.isEmpty()){Window prior=merged.get(merged.size()-1);if(prior.phase().equals(w.phase())&&prior.end()==w.start()){merged.set(merged.size()-1,new Window(prior.phase(),prior.start(),w.end()));continue;}}merged.add(w);}
    for (Window w : merged) {
      int x = left + (int) ((w.start() - min) * (double) width / Math.max(1, max - min));
      if (x < left || x > left + width) continue;
      int endX=left+(int)((w.end()-min)*(double)width/Math.max(1,max-min));
      Color phaseColor=COLORS[w.phase().equals("Ramp-up")?4:w.phase().equals("Steady load")?1:2];
      g.setColor(new Color(phaseColor.getRed(),phaseColor.getGreen(),phaseColor.getBlue(),18));
      g.fillRect(x,top,Math.max(0,Math.min(left+width,endX)-x),height);
      g.setColor(new Color(getForeground().getRed(),getForeground().getGreen(),getForeground().getBlue(),55));
      g.setStroke(new BasicStroke(1, 0, 0, 1, new float[] {3, 4}, 0));
      g.drawLine(x, top, x, top + height);
      g.setColor(getForeground());
      if(width>500) {
        String phaseName=w.phase().equals("Steady load")?"Steady Load":w.phase();
        int span=Math.min(left+width,endX)-x;
        if(g.getFontMetrics().stringWidth(phaseName)+8>span)phaseName=w.phase().equals("Ramp-up")?"Up":w.phase().equals("Ramp-down")?"Down":"Steady";
        if(g.getFontMetrics().stringWidth(phaseName)+8<=span)g.drawString(phaseName,x+4,top+14);
      }
    }
    g.setStroke(new BasicStroke(1));
    for(int tick=0;tick<=4;tick++){
      int yy=top+height-height*tick/4;
      g.setColor(new Color(getForeground().getRed(),getForeground().getGreen(),getForeground().getBlue(),25));g.drawLine(left,yy,left+width,yy);
      g.setColor(getForeground());String tickLabel=normalized?Integer.toString(tick*25)+"%":String.format(Locale.ROOT,loadMax<4?"%.1f":"%.0f",loadMax*tick/4);if(!lines.isEmpty())g.drawString(tickLabel,left-8-g.getFontMetrics().stringWidth(tickLabel),yy+4);
      if(snapshot!=null && !normalized)g.drawString(String.format(Locale.ROOT,"%.1f",metricMax*tick/4),left+width+7,yy+4);
    }
    g.setColor(getForeground());
    g.drawLine(left, top, left, top + height);
    g.drawLine(left, top + height, left + width, top + height);
    g.drawLine(left + width, top, left + width, top + height);
    Shape oldClip = g.getClip();
    g.clipRect(left, top, width, height + 1);
    for (Line line : lines) {
      if (hidden.contains(line.color())) continue;
      double scale =
          normalized
              ? Math.max(
                  1,
                  line.values().stream()
                      .filter(Objects::nonNull)
                      .mapToDouble(Double::doubleValue)
                      .max()
                      .orElse(1))
              : (line.color() == 0 || line.color() == 7 ? loadMax : metricMax);
      g.setColor(COLORS[line.color()]);
      g.setStroke(new BasicStroke(1.3f));
      Path2D path = new Path2D.Double();
      boolean open = false;
      for (int i = 0; i < line.times().size(); i++) {
        Double value = line.values().get(i);
        if (value == null || !Double.isFinite(value)) {
          open = false;
          continue;
        }
        double x = left + (line.times().get(i) - min) * (double) width / Math.max(1, max - min),
            y = top + height - value / scale * height;
        if (!open) {
          path.moveTo(x, y);
          open = true;
        } else path.lineTo(x, y);
      }
      g.draw(path);
    }
    g.setClip(oldClip);
    g.setColor(getForeground());
    for (int i = 0; i <= 4; i++) {
      long t = min + (max - min) * i / 4;
      String label =
          snapshot == null
              ? String.format(Locale.ROOT, "%.0f s", t / 1000.0)
              : DateTimeFormatter.ofPattern("HH:mm:ss")
                  .format(Instant.ofEpochMilli(t).atZone(ZoneId.systemDefault()));
      g.drawString(label, left + width * i / 4 - 20, top + height + 20);
    }
    g.drawString("Time ("+(snapshot==null?"seconds":"local HH:mm:ss")+")",left+width/2-60,top+height+37);
    if (snapshot == null && lines.isEmpty())
      g.drawString("Phase windows only · manual timings do not define users or arrival rate", left+8,getHeight()-10);
    legend.clear();
    int x = left, y = getHeight() - 13;
    for (Line line : lines) {
      String name = line.name() + (line.color() == 5 ? " (estimate)" : "");
      int w = g.getFontMetrics().stringWidth(name) + 22;
      if (x + w > getWidth() - 20) {
        y -= 18;
        x = left;
      }
      g.setColor(hidden.contains(line.color()) ? Color.GRAY : COLORS[line.color()]);
      g.drawLine(x, y - 4, x + 13, y - 4);
      g.drawString(name, x + 18, y);
      legend.put(new Rectangle(x, y - 14, w, 18), line.color());
      x += w + 9;
    }
    g.dispose();
  }
}
