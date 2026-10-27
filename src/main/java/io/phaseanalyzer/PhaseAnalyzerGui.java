package io.phaseanalyzer;

import java.awt.*;
import java.io.*;
import java.nio.file.*;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.swing.*;
import javax.swing.event.*;
import javax.swing.filechooser.FileNameExtensionFilter;
import javax.swing.table.*;
import org.apache.jmeter.gui.GuiPackage;
import org.apache.jmeter.testelement.TestElement;
import org.apache.jmeter.visualizers.gui.AbstractListenerGui;
import org.apache.jorphan.collections.HashTree;

/** Exactly the three approved primary tabs. */
public final class PhaseAnalyzerGui extends AbstractListenerGui implements Scrollable {
  private static final long serialVersionUID = 2L;
  private static final String[] SCOPES = {
    "Steady load", "Ramp-up", "Ramp-down", "Complete execution"
  };
  private final JTabbedPane mainTabs = new JTabbedPane();
  private final JComboBox<String> dashboardScope = new JComboBox<>(SCOPES),
      tableScope = new JComboBox<>(SCOPES),
      metric = new JComboBox<>(TrendPanel.METRICS),
      units = new JComboBox<>(new String[] {"Seconds", "Minutes"}),
      population = new JComboBox<>(new String[] {"Requests", "Transactions", "Separate"}),
      plateau = new JComboBox<>();
  private final JComboBox<Choice> group = new JComboBox<>();
  private final JRadioButton automatic = new JRadioButton("Automatic (recommended)", true),
      manual = new JRadioButton("Manual");
  private final Map<String, JTextField> fields = new LinkedHashMap<>();
  private final Map<String, JCheckBox> present = new LinkedHashMap<>();
  private final JTextField output = new JTextField("phase-analyzer-results", 22),
      absoluteStart = new JTextField(16),
      absoluteEnd = new JTextField(16),
      filter = new JTextField(16);
  private final JCheckBox strict = new JCheckBox("Strict: start and completion inside window"),
      autoRefresh = new JCheckBox("Auto-refresh", true),
      showActual = new JCheckBox("Observed users");
  private final JSpinner refresh = new JSpinner(new SpinnerNumberModel(2, 1, 60, 1));
  private final JLabel header = new JLabel("Not started"),
      status = new JLabel("Ready"),
      phaseInfo = new JLabel("Configuration-based phase detection"),
      liveStatus = new JLabel("Not started"),
      hint = new JLabel(" ");
  private final JLabel[] kpis = new JLabel[6];
  private final JPanel dashboardPhases = new JPanel(new GridLayout(1, 4, 8, 0)),
      livePhases = new JPanel(new GridLayout(1, 4, 8, 0));
  private final JPanel durationCards = new JPanel(new CardLayout());
  private final JLabel[] detectedDurations = new JLabel[3];
  private final JProgressBar progress = new JProgressBar(0, 100);
  private final TrendPanel preview = new TrendPanel(), graph = new TrendPanel();
  private final JTextArea details = new JTextArea(6, 80), diagnostics = new JTextArea(6, 80);
  private final JButton html = new JButton("Generate HTML Report"),
      excel = new JButton("Export Excel"),
      capture = new JButton("Stop Capture");
  private final JTable table = new JTable();
  private final AtomicBoolean busy = new AtomicBoolean(), discovering = new AtomicBoolean();
  private final javax.swing.Timer timer;
  private PhaseAnalyzerListener element;
  private ReportSnapshot snapshot;
  private List<GroupDiscovery.Entry> discovered = List.of();
  private long lastRefresh, lastDiscovery;
  private boolean filling;
  private Path lastDirectory;
  private JPanel body;
  private final JLabel groupStatus = new JLabel("Select a Thread Group"), phaseStatus = new JLabel("Ready"), elapsedStatus = new JLabel("—"), activeStatus = new JLabel("—"), rateStatus = new JLabel("—"), tableEmpty = new JLabel("Run a test to see captured API metrics.", SwingConstants.CENTER);
  private final AtomicBoolean exporting = new AtomicBoolean();
  private long lastUnits = 1;
  private String renderedRunId = "";
  private long selectionRevision;
  private final JPanel tableCards = new JPanel(new CardLayout());


  private record Choice(String key, String label, boolean enabled) {
    @Override
    public String toString() {
      return label;
    }
  }

  public PhaseAnalyzerGui() {
    if(GuiPackage.getInstance()!=null)PlanIdentitySync.install();
    setLayout(new BorderLayout(8, 8));
    setBorder(makeBorder());
    add(makeTitlePanel(), BorderLayout.NORTH);
    for (var e :
        new String[][] {
          {"delay", "0"},
          {"up", "0"},
          {"hold", "60"},
          {"down", "0"},
          {"apdexT", "500"},
          {"apdexF", "2000"}
        }) fields.put(e[0], new JTextField(e[1], 8));
    body = new JPanel(new BorderLayout(0, 4));
    mainTabs.addTab("Dashboard", dashboard());
    mainTabs.addTab("Phase Configuration", configuration());
    mainTabs.addTab("Live Metrics", live());
    body.add(mainTabs);
    add(body);
    ButtonGroup modes = new ButtonGroup();
    modes.add(automatic);
    modes.add(manual);
    automatic.addActionListener(e -> updateConfiguration());
    manual.addActionListener(e -> updateConfiguration());
    units.addActionListener(e -> {
      if (!filling) {
        long next = multiplier();
        if(next != lastUnits) {
          filling = true;
          for(String k: List.of("up","hold","down"))try {double seconds = Double.parseDouble(fields.get(k).getText()) * lastUnits; fields.get(k).setText(seconds / next == Math.rint(seconds / next) ? Long.toString((long)(seconds / next)) : Double.toString(seconds / next));} catch(NumberFormatException ignored) {}
          filling = false; lastUnits = next;
        }
        updatePreview();
      }
    });
    for (var text : fields.values())
      text.getDocument().addDocumentListener(change(this::updatePreview));
    for (var check : present.values()) check.addActionListener(e -> updateConfiguration());
    dashboardScope.addActionListener(e -> renderDashboard());
    tableScope.addActionListener(e -> renderTable());
    metric.setSelectedItem("All Metrics");
    graph.setMetric("All Metrics");
    metric.addActionListener(e -> graph.setMetric(Objects.toString(metric.getSelectedItem())));
    showActual.addActionListener(e -> graph.setActual(showActual.isSelected()));
    filter.getDocument().addDocumentListener(change(this::filterRows));
    group.addActionListener(
        e -> {
          if (!filling) {
            if(element!=null)element.setProperty("phase.group",selectedKey());
            selectionRevision++;
            snapshot=null;
            graph.setSnapshot(null);renderDashboard();renderTable();
            updateConfiguration();
            if(element!=null&&element.session()!=null)requestSnapshot();
          }
        });
    html.addActionListener(e -> export("html"));
    excel.addActionListener(e -> export("xlsx"));
    capture.addActionListener(e -> captureAction());
    details.setEditable(false);
    diagnostics.setEditable(false);
    diagnostics.setLineWrap(true);
    diagnostics.setWrapStyleWord(true);
    html.setEnabled(false);
    excel.setEnabled(false);
    capture.setEnabled(false);
    group.addItem(new Choice("All", "All Thread Groups", true));
    updateConfiguration();
    renderDashboard();
    renderTable();
    clock();
    graph.setSnapshot(null);
    timer =
        new javax.swing.Timer(
            1000,
            e -> {
              long now = System.currentTimeMillis();
              if (now - lastDiscovery > 2000) {
                discover();
                lastDiscovery = now;
              }
              clock();
              if (element != null
                  && element.session() != null
                  && (autoRefresh.isSelected() || snapshot == null || element.session().end > 0)
                  && now - lastRefresh >= ((Number) refresh.getValue()).longValue() * 1000) {
                requestSnapshot();
                lastRefresh = now;
              }
            });
  }

  @Override public Dimension getPreferredScrollableViewportSize(){return new Dimension(1000,600);}
  @Override public int getScrollableUnitIncrement(Rectangle r,int orientation,int direction){return 24;}
  @Override public int getScrollableBlockIncrement(Rectangle r,int orientation,int direction){return Math.max(24,r.height-40);}
  @Override public boolean getScrollableTracksViewportWidth(){return true;}
  @Override public boolean getScrollableTracksViewportHeight(){return true;}
  @Override public Dimension getMinimumSize(){return new Dimension(320,260);}

  @Override
  public void addNotify() {
    super.addNotify();
    timer.start();
  }

  @Override
  public void removeNotify() {
    timer.stop();
    super.removeNotify();
  }

  private JPanel executionStrip(boolean live) {
    JPanel strip=new JPanel(new BorderLayout(6,4));
    if(live){strip.add(liveStatus);return strip;}
    JPanel cells=new JPanel(new GridLayout(2,3,12,5));
    String[] names={"Capture status", "Thread Group", "Current phase", "Elapsed time", "Active users", "Current throughput"};
    JLabel[] values={header,groupStatus,phaseStatus,elapsedStatus,activeStatus,rateStatus};
    for(int i=0;i<names.length;i++) {JPanel pair=new JPanel(new BorderLayout(6,0));pair.add(new JLabel(names[i]+":"),BorderLayout.WEST);pair.add(values[i]);cells.add(pair);}
    strip.add(cells);strip.add(capture,BorderLayout.EAST);return strip;
  }
  private JPanel dashboard() {
    JPanel p=vertical();p.add(section("Execution",executionStrip(false)));
    JPanel metrics=new JPanel(new BorderLayout(4,6));JPanel scope=new JPanel(new FlowLayout(FlowLayout.RIGHT,4,0));scope.add(new JLabel("Phase:"));scope.add(dashboardScope);metrics.add(scope,BorderLayout.NORTH);
    JPanel grid=new JPanel(new GridLayout(2,3,14,6));String[] names={"Total Samples","Average Response Time","P90","P95","Error Rate","Throughput"};
    for(int i=0;i<6;i++){JPanel pair=new JPanel(new BorderLayout(8,0));pair.add(new JLabel(names[i]+":"),BorderLayout.WEST);kpis[i]=new JLabel("—");kpis[i].setFont(kpis[i].getFont().deriveFont(Font.BOLD));pair.add(kpis[i]);grid.add(pair);}metrics.add(grid);p.add(section("Key Metrics",metrics));
    p.add(section("Phase Overview",dashboardPhases));progress.setStringPainted(true);progress.setString("Ready to capture");JPanel bar=new JPanel(new BorderLayout(4,4));bar.add(progress);bar.add(status,BorderLayout.SOUTH);p.add(section("Execution Progress",bar));
    p.add(collapsible("Test Information",new JScrollPane(details)));JPanel actions=new JPanel(new FlowLayout(FlowLayout.RIGHT,6,0));actions.add(html);actions.add(excel);p.add(actions);phaseCards(dashboardPhases);return scroll(p);
  }
  private JPanel modeCard(JRadioButton radio,String explanation){JPanel p=new JPanel(new BorderLayout(4,2));p.add(radio,BorderLayout.NORTH);p.add(new JLabel("<html>"+explanation+"</html>"));return p;}

  private JPanel configuration() {
    JPanel p=vertical(),chooser=new JPanel(new BorderLayout(0,6));
    chooser.add(group);JLabel note=new JLabel("Thread Groups in the current Test Plan · disabled groups are marked");chooser.add(note,BorderLayout.SOUTH);
    p.add(section("1. Select Thread Group",chooser));
    JPanel modes=new JPanel(new GridLayout(1,2,10,0));modes.add(modeCard(automatic,"Read phase timings from the selected Thread Group configuration."));modes.add(modeCard(manual,"Enter analytical phase timings. This does not change the test workload."));p.add(section("2. Identify load phases",modes));
    JPanel inputs=new JPanel(new GridLayout(1,3,10,0)),detected=new JPanel(new GridLayout(1,3,10,0));int ix=0;
    for(String key:List.of("up","hold","down")){
      JPanel cell=new JPanel(new BorderLayout(4,4));JCheckBox check=new JCheckBox(switch(key){case "up"->"Ramp-up";case "hold"->"Steady Load";default->"Ramp-down";},true);present.put(key,check);cell.add(check,BorderLayout.NORTH);cell.add(fields.get(key));inputs.add(cell);
      JPanel read=new JPanel(new BorderLayout());detectedDurations[ix]=nativeLabel("Not detected",14,true);read.add(detectedDurations[ix]);detected.add(read);ix++;
    }
    durationCards.add(inputs,"Manual");durationCards.add(detected,"Automatic");JPanel edit=new JPanel(new BorderLayout(0,8));JPanel unitBar=new JPanel(new FlowLayout(FlowLayout.RIGHT,0,0));unitBar.add(units);edit.add(unitBar,BorderLayout.NORTH);edit.add(durationCards);p.add(section("3. Phase durations",edit));
    preview.setPreferredSize(new Dimension(800,155));p.add(section("Load Timeline Preview",preview));
    JPanel advanced=new JPanel(new GridLayout(0,2,12,8));
    addPair(advanced,"Manual schedule reference",new JLabel("Original execution start"));addPair(advanced,"Initial delay (seconds)",fields.get("delay"));addPair(advanced,"Aggregation population",population);addPair(advanced,"Configured steady plateau",plateau);addPair(advanced,"Absolute start / epoch ms",absoluteStart);addPair(advanced,"Absolute end / epoch ms",absoluteEnd);addPair(advanced,"APDEX satisfied / ms",fields.get("apdexT"));addPair(advanced,"APDEX tolerated / ms",fields.get("apdexF"));advanced.add(strict);advanced.add(new JLabel(""));addPair(advanced,"Default report directory",output);
    JButton imported=new JButton("Import saved JTL");imported.addActionListener(e->importJtl());advanced.add(imported);p.add(collapsible("Advanced Settings (optional)",advanced));
    JPanel messages=new JPanel(new BorderLayout(0,5));messages.add(phaseInfo);messages.add(hint,BorderLayout.SOUTH);p.add(section("Configuration status",messages));
    JPanel actions=new JPanel(new FlowLayout(FlowLayout.RIGHT,8,0));JButton reset=new JButton("Reset"),apply=new JButton("Apply Configuration");reset.addActionListener(e->resetConfiguration());apply.addActionListener(e->{try{if(element==null)element=(PhaseAnalyzerListener)createTestElement();else write(element);hint.setText("Configuration applied. Ready for the next capture.");requestSnapshot();}catch(Exception failure){error(failure);}});actions.add(reset);actions.add(apply);p.add(actions);return scroll(p);
  }

  private JPanel live() {
    JPanel p=vertical();p.add(section("",executionStrip(true)));
    JPanel chart=new JPanel(new BorderLayout(0,8)),controls=new JPanel(new BorderLayout());controls.add(nativeLabel("Live Load Timeline",14,true));
    JPanel selects=new JPanel(new FlowLayout(FlowLayout.RIGHT,8,0));showActual.setSelected(true);graph.setActual(true);selects.add(showActual);selects.add(metric);controls.add(selects,BorderLayout.EAST);chart.add(controls,BorderLayout.NORTH);graph.setPreferredSize(new Dimension(800,205));chart.add(graph);p.add(section("",chart));p.add(section("Phase Summary",livePhases));phaseCards(livePhases);
    JPanel aggregate=new JPanel(new BorderLayout(0,10)),bar=new JPanel(new BorderLayout());bar.add(nativeLabel("Live Aggregate Metrics",14,true));JPanel search=new JPanel(new FlowLayout(FlowLayout.RIGHT,6,0));search.add(new JLabel("API search"));search.add(filter);search.add(tableScope);bar.add(search,BorderLayout.SOUTH);aggregate.add(bar,BorderLayout.NORTH);
    table.setAutoResizeMode(JTable.AUTO_RESIZE_OFF);table.setAutoCreateRowSorter(true);table.setFillsViewportHeight(true);JScrollPane data=new JScrollPane(table);data.setPreferredSize(new Dimension(850,160));data.setMinimumSize(new Dimension(100,150));tableEmpty.setPreferredSize(new Dimension(800,42));tableCards.add(data,"Data");tableCards.add(tableEmpty,"Empty");aggregate.add(tableCards);p.add(section("",aggregate));
    JPanel footer=new JPanel(new FlowLayout(FlowLayout.LEFT,8,0));footer.add(autoRefresh);footer.add(refresh);JLabel note=new JLabel("seconds · aggregate percentiles are exact; chart P95 is an estimate");footer.add(note);p.add(footer);return scroll(p);
  }

  private static void addPair(JPanel p, String label, Component c) {
    p.add(new JLabel(label));
    p.add(c);
  }

  private static final class Stack extends JPanel implements Scrollable {
    Stack(){setLayout(new BoxLayout(this,BoxLayout.Y_AXIS));setBorder(BorderFactory.createEmptyBorder(4,2,4,2));}
    protected void addImpl(Component c,Object constraints,int index){if(c instanceof JComponent j)j.setAlignmentX(LEFT_ALIGNMENT);if(getComponentCount()>0)super.addImpl(Box.createVerticalStrut(5),null,-1);super.addImpl(c,constraints,index);}
    public Dimension getPreferredScrollableViewportSize(){return getPreferredSize();}
    public int getScrollableUnitIncrement(Rectangle r,int orientation,int direction){return 24;}
    public int getScrollableBlockIncrement(Rectangle r,int orientation,int direction){return Math.max(24,r.height-40);}
    public boolean getScrollableTracksViewportWidth(){return true;}
    public boolean getScrollableTracksViewportHeight(){return false;}
  }
  private static JPanel vertical(){return new Stack();}
  private static JPanel section(String title,Component c){
    JPanel p=new JPanel(new BorderLayout(0,10)){public Dimension getMaximumSize(){return new Dimension(Integer.MAX_VALUE,getPreferredSize().height);}};
    if(!title.isBlank())p.setBorder(BorderFactory.createTitledBorder(title));else p.setBorder(BorderFactory.createEmptyBorder(2,2,2,2));p.add(c);p.setAlignmentX(LEFT_ALIGNMENT);return p;
  }
  private static JPanel scroll(JPanel c){JPanel p=new JPanel(new BorderLayout());JScrollPane sc=new JScrollPane(c);sc.setHorizontalScrollBarPolicy(JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);sc.setBorder(null);sc.getVerticalScrollBar().setUnitIncrement(24);sc.getViewport().setScrollMode(JViewport.SIMPLE_SCROLL_MODE);p.add(sc);return p;}
  private static JPanel collapsible(String title,Component c){
    JPanel p=new JPanel(new BorderLayout(0,8)){public Dimension getMaximumSize(){return new Dimension(Integer.MAX_VALUE,getPreferredSize().height);}};JToggleButton toggle=new JToggleButton("▸  "+title);toggle.setHorizontalAlignment(SwingConstants.LEFT);p.add(toggle,BorderLayout.NORTH);c.setVisible(false);p.add(c);toggle.addActionListener(e->{c.setVisible(toggle.isSelected());toggle.setText((toggle.isSelected()?"▾  ":"▸  ")+title);p.revalidate();p.repaint();});p.setAlignmentX(LEFT_ALIGNMENT);return p;
  }

  private static DocumentListener change(Runnable r) {
    return new DocumentListener() {
      public void insertUpdate(DocumentEvent e) {
        r.run();
      }

      public void removeUpdate(DocumentEvent e) {
        r.run();
      }

      public void changedUpdate(DocumentEvent e) {
        r.run();
      }
    };
  }

  @Override
  public String getLabelResource() {
    return "performance_phase_analyzer";
  }

  @Override
  public String getStaticLabel() {
    return "Performance Phase Analyzer";
  }

  @Override
  public TestElement createTestElement() {
    PhaseAnalyzerListener p = new PhaseAnalyzerListener();
    configureTestElement(p);
    write(p);
    element = p;
    return p;
  }

  @Override
  public void modifyTestElement(TestElement p) {
    configureTestElement(p);
    try {
      write(p);
    } catch (Exception e) {
      error(e);
    }
  }

  private long number(String key) {
    long v = Long.parseLong(fields.get(key).getText().trim());
    if (v < 0) throw new IllegalArgumentException(key + " must be nonnegative");
    return v;
  }

  private long multiplier() {
    return units.getSelectedIndex() == 1 ? 60 : 1;
  }

  private long duration(String key) {
    if(!present.get(key).isSelected())return 0;
    try {double seconds = new java.math.BigDecimal(fields.get(key).getText().trim()).multiply(java.math.BigDecimal.valueOf(multiplier())).doubleValue(); if(!Double.isFinite(seconds)||seconds<0||seconds>Long.MAX_VALUE/1000||Math.abs(seconds-Math.rint(seconds))>0.000001)throw new IllegalArgumentException();return Math.round(seconds);}
    catch(Exception e){throw new IllegalArgumentException("Enter a nonnegative whole number of seconds for "+key+" (fractional minutes are supported)");}
  }

  private void write(TestElement p) {
    long delay = number("delay"),
        up = duration("up"),
        hold = duration("hold"),
        down = duration("down"),
        t = number("apdexT"),
        f = number("apdexF");
    if (f < t)
      throw new IllegalArgumentException(
          "APDEX tolerated threshold must be >= satisfied threshold");
    Schedule.phases(
        Math.multiplyExact(delay, 1000),
        Math.multiplyExact(up, 1000),
        Math.multiplyExact(hold, 1000),
        Math.multiplyExact(down, 1000));
    String a = absoluteStart.getText().trim(), b = absoluteEnd.getText().trim();
    if (!a.isBlank() || !b.isBlank())
      new Window("Steady load", Long.parseLong(a), Long.parseLong(b));
    Choice c = (Choice) group.getSelectedItem();
    // A disabled selection is a valid saved-plan state, not a GUI save error.
    p.setProperty("phase.automatic", automatic.isSelected());
    p.setProperty("phase.strict", strict.isSelected());
    p.setProperty("phase.delay", delay);
    p.setProperty("phase.up", up);
    p.setProperty("phase.hold", hold);
    p.setProperty("phase.down", down);
    p.setProperty("phase.apdexT", t);
    p.setProperty("phase.apdexF", f);
    p.setProperty("phase.group", c == null ? "All" : c.key());
    p.setProperty("phase.mode", Objects.toString(population.getSelectedItem(), "Requests"));
    p.setProperty("phase.plateau", plateau.getSelectedIndex() - 1);
    p.setProperty("phase.output", output.getText().trim());
    p.setProperty("phase.absoluteStart", a);
    p.setProperty("phase.absoluteEnd", b);
    try {
      p.setProperty("phase.catalog", Reports.mapper().writeValueAsString(discovered));
    } catch (Exception e) {
      throw new IllegalArgumentException(e);
    }
    GuiPackage gui = GuiPackage.getInstance();
    if (gui != null)
      for (Object node : gui.getTreeModel().getTestPlan().list()) {
        Object value =
            node instanceof org.apache.jmeter.gui.tree.JMeterTreeNode n ? n.getTestElement() : node;
        if (value instanceof TestElement te) {
          p.setProperty("phase.testPlan", te.getName());
          break;
        }
      }
  }

  @Override
  public void configure(TestElement p) {
    super.configure(p);
    element = (PhaseAnalyzerListener) p;
    filling = true;
    units.setSelectedIndex(0);
    lastUnits=1;
    automatic.setSelected(p.getPropertyAsBoolean("phase.automatic", true));
    manual.setSelected(!automatic.isSelected());
    for (var e : fields.entrySet())
      e.getValue()
          .setText(
              p.getPropertyAsString(
                  "phase." + e.getKey(),
                  switch (e.getKey()) {
                    case "hold" -> "60";
                    case "apdexT" -> "500";
                    case "apdexF" -> "2000";
                    default -> "0";
                  }));
    for (String k : present.keySet())
      present.get(k).setSelected(p.getPropertyAsLong("phase." + k) > 0);
    strict.setSelected(p.getPropertyAsBoolean("phase.strict"));
    population.setSelectedItem(p.getPropertyAsString("phase.mode", "Requests"));
    output.setText(p.getPropertyAsString("phase.output", "phase-analyzer-results"));
    absoluteStart.setText(p.getPropertyAsString("phase.absoluteStart"));
    absoluteEnd.setText(p.getPropertyAsString("phase.absoluteEnd"));
    filling = false;
    updateConfiguration();
    discover();
    clock();
    if (element.session() != null) requestSnapshot();
    else {snapshot=null;renderDashboard();renderTable();graph.setSnapshot(null);html.setEnabled(false);excel.setEnabled(false);}
  }

  @Override
  public void clearGui() {
    super.clearGui();
    element = null;
    snapshot = null;
    discovered = List.of();
    filling = true;
    group.removeAllItems();
    group.addItem(new Choice("All", "All Thread Groups", true));
    filling = false;
    resetConfiguration();
    renderDashboard();
    renderTable();
    graph.setSnapshot(null);
    html.setEnabled(false);
    excel.setEnabled(false);
    capture.setEnabled(false);
  }

  private void resetConfiguration() {
    automatic.setSelected(true);
    manual.setSelected(false);
    units.setSelectedIndex(0);
    lastUnits=1;
    for (String k : List.of("up", "down", "delay")) fields.get(k).setText("0");
    fields.get("hold").setText("60");
    fields.get("apdexT").setText("500");
    fields.get("apdexF").setText("2000");
    for (var e : present.entrySet()) e.getValue().setSelected(e.getKey().equals("hold"));
    absoluteStart.setText("");
    absoluteEnd.setText("");
    strict.setSelected(false);
    population.setSelectedItem("Requests");
    updateConfiguration();
  }

  private void discover() {
    if (!discovering.compareAndSet(false, true)) return;
    GuiPackage gui = GuiPackage.getInstance();
    if (gui == null) {
      discovering.set(false);
      return;
    }
    // Swing tree nodes and identity properties must only be read/mutated on EDT.
    try {setDiscovered(PlanIdentitySync.synchronize(gui.getTreeModel().getTestPlan()));}
    catch(Exception e){error(e);}
    finally{discovering.set(false);}
  }

  void setDiscovered(List<GroupDiscovery.Entry> entries) {
    String previous =
        element == null ? selectedKey() : element.getPropertyAsString("phase.group", selectedKey());
    if (discovered.equals(entries)&&previous.equals(selectedKey())) return;
    discovered = entries;
    filling = true;
    group.removeAllItems();
    List<GroupDiscovery.Entry> enabled =
        entries.stream().filter(GroupDiscovery.Entry::enabled).toList();
    if (enabled.size() > 1)
      group.addItem(new Choice("All", "All Thread Groups — common steady window", true));
    for (var e : entries) group.addItem(new Choice(e.key(), e.toString(), e.enabled()));
    String desired=previous.equals("All")&&enabled.size()==1?enabled.get(0).key():previous;
    if(previous.equals("All")&&enabled.size()<=1)group.insertItemAt(new Choice("All","All Thread Groups",true),0);
    if(!desired.equals("All")&&entries.stream().noneMatch(e->e.key().equals(desired)))
      group.addItem(new Choice(desired,"Selected Thread Group unavailable (deleted or replaced)",false));
    for (int i = 0; i < group.getItemCount(); i++)
      if (group.getItemAt(i).key().equals(desired))
        group.setSelectedIndex(i);
    filling = false;
    if(element!=null) {
      element.setProperty("phase.group",selectedKey());
      try {element.setProperty("phase.catalog",Reports.mapper().writeValueAsString(entries));}
      catch(Exception e){error(e);}
    }
    snapshot=null;
    selectionRevision++;
    graph.setSnapshot(null);renderDashboard();renderTable();
    updateConfiguration();
  }

  private String selectedKey() {
    Choice c = (Choice) group.getSelectedItem();
    return c == null ? "All" : c.key();
  }

  private Schedule selectedSchedule() {
    String key = selectedKey();
    for (var e : discovered) if (e.key().equals(key)) return e.schedule();
    return null;
  }

  private void updateConfiguration() {
    if (filling) return;
    boolean auto = automatic.isSelected();
    ((CardLayout) durationCards.getLayout()).show(durationCards, auto ? "Automatic" : "Manual");
    units.setVisible(!auto);
    Schedule detected = selectedSchedule();
    for (int i = 0; i < 3; i++) {
      String phase = ReportSnapshot.PHASES.get(i);
      double seconds =
          detected == null
              ? 0
              : detected.windows(0).stream()
                  .filter(w -> w.phase().equals(phase))
                  .mapToDouble(Window::seconds)
                  .sum();
      detectedDurations[i].setText(
          "<html><b>"
              + label(phase)
              + "</b><br>"
              + (seconds > 0
                  ? display(seconds) + " seconds"
                  : detected == null ? "Unavailable" : "Not configured")
              + "</html>");
    }
    for (String key : List.of("up", "hold", "down")) {
      present.get(key).setEnabled(!auto);
      fields.get(key).setEnabled(!auto && present.get(key).isSelected());
    }
    units.setEnabled(!auto);
    Schedule s = selectedSchedule();
    List<Window> holds =
        s == null
            ? List.of()
            : s.windows(0).stream()
                .filter(w -> w.phase().equals("Steady load") && w.end() > w.start())
                .toList();
    int index = element == null ? -1 : element.getPropertyAsInt("phase.plateau", -1);
    plateau.removeAllItems();
    plateau.addItem("Select if multiple configured plateaus");
    for (var w : holds)
      plateau.addItem(
          String.format(Locale.ROOT, "%.1f–%.1f sec", w.start() / 1000.0, w.end() / 1000.0));
    if (index >= 0 && index < holds.size()) plateau.setSelectedIndex(index + 1);
    plateau.setEnabled(auto && holds.size() > 1);
    if (auto) {
      if (s != null) {
        phaseInfo.setText("<html>Detected: " + escaped(discovered.stream().filter(e->e.key().equals(selectedKey())).map(GroupDiscovery.Entry::implementation).findFirst().orElse(s.method())) + "<br>"+escaped(s.method())+" · "+escaped(s.loadUnit())+"</html>");
        diagnostics.setText(s.warnings() + "\n" + s.windows(0));
      } else {
        String warning =
            discovered.stream()
                .filter(e -> e.key().equals(selectedKey()))
                .map(GroupDiscovery.Entry::warning)
                .findFirst()
                .orElse("Select a loaded Thread Group. All uses common-window intersection.");
        phaseInfo.setText("<html>Automatic: " + escaped(warning)+"</html>");
        diagnostics.setText(warning);
      }
    } else
      phaseInfo.setText(
          "Manual windows reference original execution start; no concurrency profile is inferred.");
    updatePreview();
  }

  private void updatePreview() {
    if (filling) return;
    try {
      preview.setPreview(
          automatic.isSelected()
              ? selectedSchedule()
              : Schedule.phases(
                  number("delay") * 1000,
                  duration("up") * 1000,
                  duration("hold") * 1000,
                  duration("down") * 1000));
      if (hint.getText().startsWith("Invalid input:")) hint.setText(" ");
    } catch (Exception e) {
      hint.setText("Invalid input: " + e.getMessage());
    }
  }

  private void requestSnapshot() {
    if (element == null || element.session() == null || !busy.compareAndSet(false, true)) return;
    PhaseAnalyzerListener cfg = (PhaseAnalyzerListener) element.clone();
    cfg.setProperty("phase.group",selectedKey());
    RunSession run = element.session();
    long requestedRevision=selectionRevision;
    new SwingWorker<ReportSnapshot, Void>() {
      protected ReportSnapshot doInBackground() throws Exception {
        return ReportSnapshot.build(cfg, run);
      }

      protected void done() {
        try {
          if (element != null && element.session() == run && requestedRevision==selectionRevision) {
            snapshot = get();
            renderDashboard();
            renderTable();
            graph.setSnapshot(snapshot);
            details.setText("Test plan: " + snapshot.metadata().getOrDefault("jmxFilename","Unavailable")
                + "\nThread Group: " + snapshot.metadata().getOrDefault("selectedThreadGroupLabel",snapshot.settings().group())
                + "\nCapture status: " + snapshot.metadata().getOrDefault("captureStatus","Unavailable")
                + "\nExecution status: " + snapshot.metadata().getOrDefault("executionStatus","Unavailable")
                + "\nCaptured samples: " + snapshot.phase("Complete execution").metrics().get("samples")
                + "\nOutput directory: " + output.getText());
            diagnostics.setText(
                snapshot.analysis().methodology()
                    + "\n"
                    + snapshot.analysis().warnings()
                    + "\n"
                    + snapshot.metadata().get("warnings"));
            var p = snapshot.phase("Complete execution");
            boolean has =
                p != null && p.metrics().get("samples") instanceof Number n && n.longValue() > 0;
            html.setEnabled(has && !exporting.get());
            excel.setEnabled(has && !exporting.get());
                  }
        } catch (Exception e) {
          error(e);
        } finally {
          busy.set(false);
          clock();
          if(element!=null&&element.session()==run&&requestedRevision!=selectionRevision)requestSnapshot();
        }
      }
    }.execute();
  }

  private void clock() {
    RunSession run=element==null?null:element.session();
    capture.setVisible(run!=null && element.testRunning());
    capture.setEnabled(run!=null && element.testRunning());
    if(run==null){header.setText("Ready");groupStatus.setText(shortGroup());phaseStatus.setText("Not started");elapsedStatus.setText("—");activeStatus.setText("— users");rateStatus.setText("— samples/sec");liveStatus.setText("Ready · Run JMeter to begin capture and view live metrics");progress.setValue(0);progress.setString("Ready to capture");status.setText("No execution data yet. Select a Thread Group, then start JMeter.");html.setEnabled(false);excel.setEnabled(false);return;}
    String runId=Long.toString(run.start);
    if(!renderedRunId.equals(runId)){renderedRunId=runId;snapshot=null;graph.setSnapshot(null);renderDashboard();renderTable();html.setEnabled(false);excel.setEnabled(false);}
    long end=run.end==0?System.currentTimeMillis():run.end;
    header.setText(run.captureStatus);
    groupStatus.setText(shortGroup());groupStatus.setToolTipText(Objects.toString(group.getSelectedItem()));phaseStatus.setText(run.end>0?run.captureStatus:currentPhase(end));elapsedStatus.setText(time((end-run.start)/1000));
    capture.setText(run.isCapturing()?"Stop Capture":"Start New Capture");
    String active="—";if(snapshot!=null&&!snapshot.timeline().isEmpty()){var point=snapshot.timeline().get(snapshot.timeline().size()-1);active=display(point.active());}
    Double liveRate=run.liveThroughput.rate(selectedKey(),element.getPropertyAsString("phase.mode","Requests"),run.start,end);
    String rate=liveRate==null?"Warming up":display(liveRate);
    rateStatus.setToolTipText("Completion rate over up to five completed one-second intervals; partial buckets excluded. Frozen at capture end. Overall throughput is shown in the KPI cards.");
    Integer workers=run.isCapturing()?element.observedUsers(selectedKey()):null;
    if(workers!=null)active=display(workers);
    if(!element.testRunning() && run.end>0)active="0";
    activeStatus.setText(active+" users");rateStatus.setText(liveRate==null?rate:rate+" samples/sec");
    liveStatus.setText(run.captureStatus+"  ·  "+time((end-run.start)/1000)+"  ·  "+(run.isCapturing()?currentPhase(end):run.captureStatus)+"  ·  "+active+" active users  ·  "+rate+(liveRate==null?"":" samples/sec (completed intervals)"));
  }
  private String shortGroup(){Choice c=(Choice)group.getSelectedItem();String name=discovered.stream().filter(e->c!=null&&e.key().equals(c.key())).map(GroupDiscovery.Entry::name).findFirst().orElse("Select a group");return name.length()>23?name.substring(0,21)+"…":name;}
  private static String time(long seconds){return String.format(Locale.ROOT,"%02d:%02d:%02d",seconds/3600,(seconds/60)%60,seconds%60);}
  private static String escaped(Object s){return Objects.toString(s,"").replace("&","&amp;").replace("<","&lt;").replace(">","&gt;");}

  private String currentPhase(long t) {
    if (snapshot == null) return "Phase unavailable";
    for (var p : snapshot.phases())
      if (!p.name().equals("Complete execution"))
        for (var w : p.configured()) if (t >= w.start() && t < w.end()) return label(p.name());
    return "Gap / unclassified";
  }

  private void renderDashboard() {
    var p =
        snapshot == null
            ? null
            : snapshot.phase(Objects.toString(dashboardScope.getSelectedItem(), "Steady load"));
    String[] keys = {"samples", "averageMs", "p90Ms", "p95Ms", "errorPercent", "samplesPerSecond"},
        suffix = {"", " ms", " ms", " ms", " %", " samples/s"};
    for (int i = 0; i < 6; i++) {
      Object v = p == null ? null : p.metrics().get(keys[i]);
      kpis[i].setText(v == null ? "—" : display(v) + suffix[i]);
      kpis[i].setToolTipText(p == null ? "No execution data" : p.status() + "; " + p.explanation());
    }
    phaseCards(dashboardPhases);
    phaseCards(livePhases);
    if (snapshot != null) {
      var c = snapshot.phase("Complete execution");
      Double duration = c.configuredSeconds();
      double elapsed =
          (((Number) snapshot.metadata().get("executionEnd")).longValue()
                  - ((Number) snapshot.metadata().get("testStart")).longValue())
              / 1000.0;
      progress.setIndeterminate(false);
      if (duration != null && duration > 0) {
        progress.setValue((int) Math.min(100, Math.max(0, elapsed * 100 / duration)));
        progress.setString(
            String.format(Locale.ROOT, "%.1f / %.1f sec (configured)", elapsed, duration));
      } else {
        progress.setValue(0);
        progress.setString("Configured total duration unavailable — no percentage inferred");
      }
      status.setText(
          currentPhase(((Number) snapshot.metadata().get("executionEnd")).longValue())
              + "  ·  Unclassified captured samples: "
              + snapshot.unclassified());
    }
  }

  private void phaseCards(JPanel panel) {
    panel.removeAll();panel.setLayout(new BorderLayout());
    DefaultTableModel model=new DefaultTableModel(new String[]{"Phase","Status","Duration (s)","Samples","Average (ms)","P95 (ms)","Error %"},0){public boolean isCellEditable(int row,int col){return false;}};
    for(String name:ReportSnapshot.PHASES){var phase=snapshot==null?null:snapshot.phase(name);model.addRow(new Object[]{label(name),phase==null?"Not started":phase.status(),phase==null?"—":display(phase.configuredSeconds()),phase==null?"—":display(phase.metrics().get("samples")),phase==null?"—":display(phase.metrics().get("averageMs")),phase==null?"—":display(phase.metrics().get("p95Ms")),phase==null?"—":display(phase.metrics().get("errorPercent"))});}
    JTable phases=new JTable(model);phases.setRowHeight(Math.max(22,phases.getFontMetrics(phases.getFont()).getHeight()+6));phases.setFillsViewportHeight(false);phases.getTableHeader().setReorderingAllowed(false);phases.getColumnModel().getColumn(0).setPreferredWidth(160);phases.getColumnModel().getColumn(1).setPreferredWidth(140);JScrollPane scroll=new JScrollPane(phases);scroll.setPreferredSize(new Dimension(800,phases.getRowHeight()*4+phases.getTableHeader().getPreferredSize().height+5));panel.add(scroll);panel.revalidate();panel.repaint();
  }

  private void renderTable() {
    String phase = Objects.toString(tableScope.getSelectedItem(), "Steady load");
    List<String> headers = new ArrayList<>(ExcelReport.HEADERS);
    headers.add(0, "Population");
    DefaultTableModel model =
        new DefaultTableModel(headers.toArray(), 0) {
          public boolean isCellEditable(int r, int c) {
            return false;
          }
        };
    if (snapshot != null)
      for (var v : snapshot.analysis().views())
        if (v.window().phase().equals(phase))
          for (var row : v.rows()) {
            List<Object> values = new ArrayList<>();
            values.add(v.kind());
            values.add(
                snapshot
                    .metadata()
                    .getOrDefault("selectedThreadGroupLabel", snapshot.settings().group()));
            for (String key : ExcelReport.KEYS)
              values.add(row.get(key) == null ? "N/A" : row.get(key));
            model.addRow(values.toArray());
          }
    table.setModel(model);
    tableCards.setPreferredSize(new Dimension(800,model.getRowCount()==0?44:160));
    ((CardLayout)tableCards.getLayout()).show(tableCards,model.getRowCount()==0?"Empty":"Data");
    tableEmpty.setText(snapshot==null?"Run a test to see captured API metrics.":"No samples captured in the selected phase.");
    table.setAutoCreateRowSorter(true);
    var sorter = (TableRowSorter<?>) table.getRowSorter();
    for (int c = 3; c < model.getColumnCount(); c++)
      sorter.setComparator(
          c,
          (a, b) ->
              a instanceof Number n
                  ? b instanceof Number m ? Double.compare(n.doubleValue(), m.doubleValue()) : -1
                  : b instanceof Number ? 1 : String.valueOf(a).compareTo(String.valueOf(b)));
    for (int c = 0; c < table.getColumnCount(); c++)
      table.getColumnModel().getColumn(c).setPreferredWidth(c == 2 ? 220 : c == 1 ? 175 : Math.max(125,table.getTableHeader().getFontMetrics(table.getTableHeader().getFont()).stringWidth(model.getColumnName(c))+26));
    DefaultTableCellRenderer numeric =
        new DefaultTableCellRenderer() {
          @Override
          protected void setValue(Object value) {
            super.setValue(value instanceof Number ? display(value) : value);
            setHorizontalAlignment(SwingConstants.RIGHT);
          }
        };
    for (int c = 3; c < table.getColumnCount(); c++)
      table.getColumnModel().getColumn(c).setCellRenderer(numeric);
    filterRows();
  }

  private void filterRows() {
    if (table.getRowSorter() instanceof TableRowSorter<?> s)
      s.setRowFilter(
          filter.getText().isBlank()
              ? null
              : RowFilter.regexFilter("(?i)" + java.util.regex.Pattern.quote(filter.getText()), 2));
  }

  private static String display(Object v) {
    return v == null
        ? "—"
        : v instanceof Number n
            ? String.format(
                Locale.ROOT,
                n.doubleValue() == Math.rint(n.doubleValue()) ? "%.0f" : "%.2f",
                n.doubleValue())
            : v.toString();
  }

  private static String label(String p) {
    return p.equals("Steady load")
        ? "Steady Load"
        : p.equals("Complete execution") ? "Complete Execution" : p;
  }

  private static JLabel nativeLabel(String text,int ignoredSize,boolean bold){JLabel l=new JLabel(text);if(bold)l.setFont(l.getFont().deriveFont(Font.BOLD));return l;}

  private void captureAction() {
    if (element == null || !element.testRunning()) return;
    capture.setEnabled(false);
    if (element.session().isCapturing()) {
      element
          .stopCapture()
          .whenComplete(
              (run, failure) ->
                  SwingUtilities.invokeLater(
                      () -> {
                        if (failure != null) error(failure);
                        else {
                          requestSnapshot();
                          hint.setText(
                              "Capture stopped. JMeter continues running. Reports remain"
                                  + " available.");
                        }
                        clock();
                      }));
    } else
      new SwingWorker<RunSession, Void>() {
        protected RunSession doInBackground() throws Exception {
          return element.startCapture();
        }

        protected void done() {
          try {
            get();
            snapshot = null;
            html.setEnabled(false);excel.setEnabled(false);
            renderDashboard();
            renderTable();
            graph.setSnapshot(null);
            requestSnapshot();
          } catch (Exception e) {
            error(e);
          }
          clock();
        }
      }.execute();
  }

  private void export(String extension) {
    ReportSnapshot data = snapshot;
    if (data == null
        || data.phase("Complete execution") == null
        || !(data.phase("Complete execution").metrics().get("samples") instanceof Number n)
        || n.longValue() == 0) {
      error(new IllegalStateException("No captured samples to export"));
      return;
    }
    JFileChooser chooser=new JFileChooser(lastDirectory==null?new File(output.getText().isBlank()?".":output.getText()):lastDirectory.toFile());
    chooser.setDialogTitle(extension.equals("html")?"Save HTML report":"Save Excel report");chooser.setFileFilter(new FileNameExtensionFilter(extension.equals("html")?"HTML report (*.html)":"Excel workbook (*.xlsx)",extension));chooser.setAcceptAllFileFilterUsed(false);
    chooser.setSelectedFile(new File(Reports.exportFilename(data,extension)));
    if(chooser.showSaveDialog(this)!=JFileChooser.APPROVE_OPTION)return;
    Path selected=chooser.getSelectedFile().toPath().toAbsolutePath();
    if(!selected.getFileName().toString().toLowerCase(Locale.ROOT).endsWith("."+extension))selected=selected.resolveSibling(selected.getFileName()+"."+extension);
    if(Files.exists(selected)){JOptionPane.showMessageDialog(this,"This file already exists. Choose a new filename to preserve the earlier report.","Choose another filename",JOptionPane.WARNING_MESSAGE);return;}
    final Path destination=selected;
    if(!exporting.compareAndSet(false,true))return;
    html.setEnabled(false);excel.setEnabled(false);
    new SwingWorker<Boolean, Void>() {
      protected Boolean doInBackground() throws Exception {
        saveReport(data,destination,extension);
        return true;
      }

      protected void done() {
        try {
          if (!get()) return;
          lastDirectory=destination.getParent();
          Object[] options = {"Open File", "Open Folder", "Close"};
          int choice =
              JOptionPane.showOptionDialog(
                  PhaseAnalyzerGui.this,
                  "Report created:\n" + destination,
                  "Report ready",
                  JOptionPane.DEFAULT_OPTION,
                  JOptionPane.INFORMATION_MESSAGE,
                  null,
                  options,
                  options[2]);
          if (choice == 0 || choice == 1)
            new SwingWorker<Void, Void>() {
              protected Void doInBackground() throws Exception {
                if (!Desktop.isDesktopSupported())
                  throw new IOException(
                      "Desktop opening unavailable; open " + destination + " manually");
                Desktop.getDesktop()
                    .open(
                        (choice == 0 ? destination : destination.toAbsolutePath().getParent())
                            .toFile());
                return null;
              }

              protected void done() {
                try {
                  get();
                } catch (Exception e) {
                  error(e);
                }
              }
            }.execute();
        } catch (Exception e) {
          error(e);
        } finally { exporting.set(false);requestSnapshot(); }
      }
    }.execute();
  }

  static void saveReport(ReportSnapshot data,Path destination,String extension) throws IOException {
    Path staging=Files.createTempDirectory("phase-ui-export-");
    try {
      Path generated=Reports.exportSelected(data,staging,extension);
      try(var out=Files.newOutputStream(destination,StandardOpenOption.CREATE_NEW,StandardOpenOption.WRITE)) {
        try { Files.copy(generated,out); }
        catch(IOException failure) {out.close();Files.deleteIfExists(destination);throw failure;}
      }
    } finally {try(var files=Files.list(staging)){for(Path f:files.toList())Files.deleteIfExists(f);}Files.deleteIfExists(staging);}
  }

  private void importJtl() {
    JFileChooser chooser = new JFileChooser(lastDirectory == null ? null : lastDirectory.toFile());
    if (chooser.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) return;
    Path source = chooser.getSelectedFile().toPath();
    if (element != null && element.testRunning()) {
      error(new IllegalStateException("Wait for JMeter to finish before importing offline data"));
      return;
    }
    if (element == null) element = (PhaseAnalyzerListener) createTestElement();
    PhaseAnalyzerListener cfg = element;
    new SwingWorker<RunSession, Void>() {
      protected RunSession doInBackground() throws Exception {
        long[] bounds = {Long.MAX_VALUE, 0};
        List<String> warnings =
            SampleStore.scan(
                source,
                s -> {
                  bounds[0] = Math.min(bounds[0], s.start());
                  bounds[1] = Math.max(bounds[1], s.end());
                });
        if (bounds[0] == Long.MAX_VALUE) throw new IOException("No sample records in JTL");
        RunSession run =
            new RunSession(
                Path.of(cfg.getPropertyAsString("phase.output", "phase-analyzer-results")),
                bounds[0]);
        try {
          SampleStore.scan(
              source,
              s -> {
                try {
                  run.store.append(s);
                } catch (IOException e) {
                  throw new UncheckedIOException(e);
                }
              });
          run.stopAccepting("Offline recording");
          run.finish();
          run.end = bounds[1];
          Path metadata = source.resolveSibling("metadata.json");
          if (Files.exists(metadata)) {
            var tree = Reports.mapper().readTree(metadata.toFile());
            for (var node : tree.path("groups")) {
              RunSession.Group g = Reports.mapper().convertValue(node, RunSession.Group.class);
              run.groups.put(g.name(), g);
            }
          }
          Path load = source.resolveSibling("load.csv");
          if (Files.exists(load))
            Files.copy(
                load, run.directory.resolve("load.csv"), StandardCopyOption.REPLACE_EXISTING);
          for (String w : warnings) run.warning(w);
          run.warning(
              "Offline source: "
                  + source
                  + ". Without companion metadata, manual reference is earliest sample start, not"
                  + " the true test start.");
          return run;
        } catch (Exception e) {
          run.stopAccepting("Failed");
          run.finish();
          throw e;
        }
      }

      protected void done() {
        try {
          RunSession run = get();
          PhaseAnalyzerListener.SESSIONS.put(cfg.getPropertyAsString("phase.id"), run);
          automatic.setSelected(!run.groups.isEmpty());
          manual.setSelected(!automatic.isSelected());
          if (run.groups.isEmpty()) {
            absoluteStart.setText(Long.toString(run.store.first));
            absoluteEnd.setText(Long.toString(run.store.last));
          }
          write(cfg);
          requestSnapshot();
        } catch (Exception e) {
          error(e);
        }
      }
    }.execute();
  }

  private void error(Throwable e) {
    while (e.getCause() != null) e = e.getCause();
    String text = "Action failed: " + e.getMessage();
    hint.setText("<html>"+escaped(text)+"</html>");
    diagnostics.setText(text);
    if(isShowing())JOptionPane.showMessageDialog(this,text,"Performance Phase Analyzer",JOptionPane.ERROR_MESSAGE);
  }
}
