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
public final class PhaseAnalyzerGui extends AbstractListenerGui implements Scrollable, org.apache.jmeter.samplers.Clearable {
  private static final long serialVersionUID = 2L;
  private static final AtomicBoolean sourceLogged=new AtomicBoolean();
  private static final String[] SCOPES = {
    "Steady load", "Ramp-up", "Ramp-down", "Complete execution"
  };
  private final JTabbedPane mainTabs = new JTabbedPane();
  private final JComboBox<String> dashboardScope = new JComboBox<>(SCOPES),
      tableScope = new JComboBox<>(SCOPES),
      metric = new JComboBox<>(TrendPanel.METRICS),
      units = new JComboBox<>(new String[] {"Seconds", "Minutes"});
  private final JComboBox<Choice> group = new JComboBox<>();
  private final JRadioButton automatic = new JRadioButton("Automatic (recommended)", true),
      manual = new JRadioButton("Manual");
  private final Map<String, JTextField> fields = new LinkedHashMap<>();
  private final Map<String, JCheckBox> present = new LinkedHashMap<>();
  private final JTextField filter=new JTextField(16);
  private final JCheckBox autoRefresh=new JCheckBox("Auto-refresh",true), showActual=new JCheckBox("Show observed active users");
  private final JButton refreshNow=new JButton("Refresh");
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
  private boolean refreshPending;
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
  private String selectedPhase="Steady load", selectionKey="";
  private Map<String,ReportSnapshot> groupSnapshots=Map.of();
  private final JLabel selectedPhaseMetrics=new JLabel(" ");
  private javax.swing.tree.TreeModel observedTree;
  private final AtomicBoolean discoveryQueued=new AtomicBoolean();
  private final javax.swing.event.TreeModelListener treeChanges=new javax.swing.event.TreeModelListener(){
    public void treeNodesChanged(javax.swing.event.TreeModelEvent e){scheduleDiscovery();}
    public void treeNodesInserted(javax.swing.event.TreeModelEvent e){scheduleDiscovery();}
    public void treeNodesRemoved(javax.swing.event.TreeModelEvent e){scheduleDiscovery();}
    public void treeStructureChanged(javax.swing.event.TreeModelEvent e){scheduleDiscovery();}
  };
  private final JPanel tableCards = new JPanel(new CardLayout());


  private record Choice(String key, String label, boolean enabled) {
    @Override
    public String toString() {
      return label;
    }
  }

  public PhaseAnalyzerGui() {
    if(sourceLogged.compareAndSet(false,true))try{
      org.slf4j.LoggerFactory.getLogger(PhaseAnalyzerGui.class).info("Performance Phase Analyzer GUI loaded from {}",PhaseAnalyzerGui.class.getProtectionDomain().getCodeSource().getLocation());
    }catch(SecurityException|NullPointerException unavailable){/* Optional diagnostic only. */}
    if(GuiPackage.getInstance()!=null)PlanIdentitySync.install();
    setLayout(new BorderLayout(8, 8));
    setBorder(makeBorder());
    add(makeTitlePanel(), BorderLayout.NORTH);
    for (var e :
        new String[][] {
          {"up", "0"},
          {"hold", "60"},
          {"down", "0"}
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
    automatic.addActionListener(e -> configurationEdited());
    manual.addActionListener(e -> configurationEdited());
    units.addActionListener(e -> {
      if (!filling) {
        long next = multiplier();
        if(next != lastUnits) {
          filling = true;
          for(String k: List.of("up","hold","down"))try {double seconds = Double.parseDouble(fields.get(k).getText()) * lastUnits; fields.get(k).setText(seconds / next == Math.rint(seconds / next) ? Long.toString((long)(seconds / next)) : Double.toString(seconds / next));} catch(NumberFormatException ignored) {}
          filling = false; lastUnits = next;
        }
        configurationEdited();
      }
    });
    for (var text : fields.values())
      text.getDocument().addDocumentListener(change(this::configurationEdited));
    for (var check : present.values()) check.addActionListener(e -> configurationEdited());
    dashboardScope.addActionListener(e -> selectPhase(dashboardScope));
    tableScope.addActionListener(e -> selectPhase(tableScope));
    metric.setSelectedItem("All Metrics");
    graph.setMetric("All Metrics");
    metric.addActionListener(e -> graph.setMetric(Objects.toString(metric.getSelectedItem())));
    showActual.addActionListener(e -> graph.setActual(showActual.isSelected()));
    showActual.setToolTipText("Shows or hides the actual active-user count on the timeline. Does not affect collected metrics.");
    refreshNow.addActionListener(e -> requestSnapshot());
    autoRefresh.addActionListener(e -> {if(autoRefresh.isSelected())requestSnapshot();});
    filter.getDocument().addDocumentListener(change(this::filterRows));
    group.setRenderer(new DefaultListCellRenderer(){
      public Component getListCellRendererComponent(JList<?> list,Object value,int index,boolean selected,boolean focus){
        Component c=super.getListCellRendererComponent(list,value,index,selected,focus);
        c.setEnabled(!(value instanceof Choice choice)||choice.enabled());return c;
      }
    });
    group.addPopupMenuListener(new PopupMenuListener(){
      public void popupMenuWillBecomeVisible(PopupMenuEvent e){discover();}
      public void popupMenuWillBecomeInvisible(PopupMenuEvent e){}
      public void popupMenuCanceled(PopupMenuEvent e){}
    });
    group.addActionListener(e->{if(!filling)selectionChanged();});
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
    group.addItem(new Choice("", "No enabled Thread Groups available.", false));
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
              if(autoRefresh.isSelected())clock();
              if (element != null
                  && viewSession() != null
                  && autoRefresh.isSelected()
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
    attachTree();timer.start();
  }

  @Override
  public void removeNotify() {
    timer.stop();detachTree();
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
    for(int i=0;i<6;i++){JPanel pair=new JPanel(new BorderLayout(8,0));pair.add(new JLabel(names[i]+":"),BorderLayout.WEST);kpis[i]=new JLabel("—");kpis[i].setFont(kpis[i].getFont().deriveFont(Font.BOLD));pair.add(kpis[i]);grid.add(pair);}metrics.add(grid);metrics.add(selectedPhaseMetrics,BorderLayout.SOUTH);p.add(section("Key Metrics",metrics));
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
    JPanel messages=new JPanel(new BorderLayout(0,5));messages.add(phaseInfo);messages.add(hint,BorderLayout.SOUTH);p.add(section("Configuration status",messages));
    return scroll(p);
  }

  private JPanel live() {
    JPanel p=vertical();p.add(section("",executionStrip(true)));
    JPanel chart=new JPanel(new BorderLayout(0,8)),controls=new JPanel(new BorderLayout());controls.add(nativeLabel("Live Load Timeline",14,true));
    JPanel selects=new JPanel(new FlowLayout(FlowLayout.RIGHT,8,0));showActual.setSelected(true);graph.setActual(true);selects.add(showActual);selects.add(metric);controls.add(selects,BorderLayout.EAST);chart.add(controls,BorderLayout.NORTH);graph.setPreferredSize(new Dimension(800,205));chart.add(graph);p.add(section("",chart));p.add(section("Phase Summary",livePhases));phaseCards(livePhases);
    JPanel aggregate=new JPanel(new BorderLayout(0,4)),bar=new JPanel(new BorderLayout(0,4));bar.add(nativeLabel("Live Aggregate Metrics",14,true),BorderLayout.NORTH);JPanel search=new JPanel(new FlowLayout(FlowLayout.RIGHT,6,0));search.add(new JLabel("API search"));search.add(filter);search.add(tableScope);bar.add(search,BorderLayout.CENTER);aggregate.add(bar,BorderLayout.NORTH);
    bar.setMaximumSize(new Dimension(Integer.MAX_VALUE,bar.getPreferredSize().height));
    table.setAutoResizeMode(JTable.AUTO_RESIZE_OFF);table.setAutoCreateRowSorter(true);table.setFillsViewportHeight(true);JScrollPane data=new JScrollPane(table);data.setPreferredSize(new Dimension(850,160));data.setMinimumSize(new Dimension(100,150));tableEmpty.setPreferredSize(new Dimension(800,42));tableCards.add(data,"Data");tableCards.add(tableEmpty,"Empty");aggregate.add(tableCards);p.add(section("",aggregate));
    JPanel footer=new JPanel(new FlowLayout(FlowLayout.LEFT,8,0));footer.add(autoRefresh);footer.add(refresh);footer.add(new JLabel("seconds"));footer.add(refreshNow);JLabel note=new JLabel("Aggregate percentiles are exact; chart P95 is an estimate");footer.add(note);p.add(footer);return scroll(p);
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
      hint.setText("Invalid or incomplete input; last valid configuration retained.");
    }
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
    p.setProperty("phase.group",selectedKey());
    var valid=element==null?PhaseConfiguration.defaults():PhaseConfiguration.read(element,selectedKey());
    PhaseConfiguration.Value draft=automatic.isSelected()?new PhaseConfiguration.Value(true,valid.up(),valid.hold(),valid.down()):new PhaseConfiguration.Value(false,duration("up"),duration("hold"),duration("down"));
    PhaseConfiguration.apply((PhaseAnalyzerListener)p,draft);
    PhaseConfiguration.save((PhaseAnalyzerListener)p,selectedKey(),draft);
    try {p.setProperty("phase.catalog",Reports.mapper().writeValueAsString(discovered));}
    catch(Exception e){throw new IllegalArgumentException(e);}
  }

  private void configurationEdited(){
    if(filling)return;
    if(selectedKey().equals("All")){automatic.setSelected(true);manual.setSelected(false);}
    try {
      var valid=element==null?PhaseConfiguration.defaults():PhaseConfiguration.read(element,selectedKey());
      var draft=automatic.isSelected()?new PhaseConfiguration.Value(true,valid.up(),valid.hold(),valid.down()):new PhaseConfiguration.Value(false,duration("up"),duration("hold"),duration("down"));
      if(element!=null){PhaseConfiguration.save(element,selectedKey(),draft);element.removeProperty("phase.absoluteStart");element.removeProperty("phase.absoluteEnd");if(selectedKey().equals("All"))PhaseConfiguration.apply(element,PhaseConfiguration.defaults());}
      hint.setText(element!=null&&element.testRunning()?"Saved for the next execution; current capture boundaries are unchanged.":"Configuration saved automatically.");
    }catch(Exception invalid){hint.setText("Invalid or incomplete input; last valid configuration retained. "+invalid.getMessage());}
    updateConfiguration();
  }

  private void loadConfiguration(){
    var value=element==null?PhaseConfiguration.defaults():PhaseConfiguration.read(element,selectedKey());
    filling=true;
    automatic.setSelected(value.automatic());manual.setSelected(!value.automatic());
    long[] durations={value.up(),value.hold(),value.down()};int index=0;
    for(String key:List.of("up","hold","down")){
      long seconds=durations[index++];fields.get(key).setText(Double.toString((double)seconds/multiplier()));present.get(key).setSelected(seconds>0);
    }
    filling=false;updateConfiguration();
  }

  private void selectPhase(JComboBox<String> source){
    if(filling)return;
    int index=source.getSelectedIndex();if(index<0)return;
    setPhaseSelection(index);
    renderDashboard();renderTable();
  }

  /** Keep the view state and both native controls atomic, including during JMX loading. */
  private void setPhaseSelection(int index){
    selectedPhase=SCOPES[index];
    boolean wasFilling=filling;
    filling=true;
    try{dashboardScope.setSelectedIndex(index);tableScope.setSelectedIndex(index);}
    finally{filling=wasFilling;}
  }

  private void resetViewBinding(){
    // JMeter reuses one GUI for different listener elements and reopened plans.
    // Invalidate pending workers even when two saved listeners share a capture id.
    selectionRevision++;
    selectionKey="";renderedRunId="";lastRefresh=0;
    snapshot=null;groupSnapshots=Map.of();discovered=List.of();
    boolean wasFilling=filling;filling=true;
    try{
      setPhaseSelection(0);
      group.removeAllItems();
      group.addItem(new Choice("", "No enabled Thread Groups available.", false));
      filter.setText("");
    }finally{filling=wasFilling;}
    graph.setSnapshot(null);graph.setGroupSnapshots(Map.of());
    details.setText("");diagnostics.setText("");
    html.setEnabled(false);excel.setEnabled(false);capture.setEnabled(false);
  }

  @Override
  public void configure(TestElement p) {
    super.configure(p);
    if(element!=p)resetViewBinding();
    element = (PhaseAnalyzerListener) p;
    PhaseConfiguration.migrateLegacy(element);
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
    filling = false;
    attachTree();
    discover();
    loadConfiguration();
    clock();
    if (viewSession() != null) requestSnapshot();
    else {snapshot=null;renderDashboard();renderTable();graph.setSnapshot(null);html.setEnabled(false);excel.setEnabled(false);}
  }

  @Override
  public void clearGui() {
    super.clearGui();
    element = null;
    resetViewBinding();
    restoreDefaults();
    renderDashboard();
    renderTable();
    graph.setSnapshot(null);
    html.setEnabled(false);
    excel.setEnabled(false);
    capture.setEnabled(false);
  }

  private void restoreDefaults() {
    automatic.setSelected(true);
    manual.setSelected(false);
    units.setSelectedIndex(0);
    lastUnits=1;
    for (String k : List.of("up", "down")) fields.get(k).setText("0");
    fields.get("hold").setText("60");
    for (var e : present.entrySet()) e.getValue().setSelected(e.getKey().equals("hold"));
    updateConfiguration();
  }

  private void attachTree(){
    GuiPackage gui=GuiPackage.getInstance();if(gui==null)return;
    if(observedTree!=gui.getTreeModel()){detachTree();observedTree=gui.getTreeModel();observedTree.addTreeModelListener(treeChanges);}
  }
  private void detachTree(){if(observedTree!=null){observedTree.removeTreeModelListener(treeChanges);observedTree=null;}}
  private void scheduleDiscovery(){if(discoveryQueued.compareAndSet(false,true))SwingUtilities.invokeLater(()->{discoveryQueued.set(false);discover();});}
  private List<GroupDiscovery.Entry> currentEntries(){
    GuiPackage gui=GuiPackage.getInstance();return gui==null?discovered:GroupDiscovery.scan(gui.getTreeModel().getTestPlan());
  }
  private void discover(){
    if(!SwingUtilities.isEventDispatchThread()){scheduleDiscovery();return;}
    if(!discovering.compareAndSet(false,true))return;
    try{GuiPackage gui=GuiPackage.getInstance();if(gui!=null)setDiscovered(GroupDiscovery.scan(gui.getTreeModel().getTestPlan()));}
    catch(Exception e){error(e);}finally{discovering.set(false);}
  }
  private void selectionChanged(){
    String requested=selectedKey();var fresh=currentEntries();
    boolean valid=requested.equals("All")?GroupSelection.allAvailable(fresh):fresh.stream().anyMatch(e->e.key().equals(requested)&&e.enabled());
    if(!valid){setDiscovered(fresh);return;}
    selectionKey=requested;if(element!=null)element.setProperty("phase.group",requested);
    if(!fresh.equals(discovered))setDiscovered(fresh);
    loadConfiguration();invalidateSelection();
  }
  private void invalidateSelection(){
    selectionRevision++;snapshot=null;groupSnapshots=Map.of();graph.setSnapshot(null);graph.setGroupSnapshots(Map.of());
    renderDashboard();renderTable();clock();if(viewSession()!=null)requestSnapshot();
  }
  void setDiscovered(List<GroupDiscovery.Entry> entries){
    String previous=element==null?selectionKey:element.getPropertyAsString("phase.group");
    String desired=GroupSelection.choose(entries,previous,historyAvailable());
    if(discovered.equals(entries)&&desired.equals(selectedKey()))return;
    discovered=List.copyOf(entries);filling=true;group.removeAllItems();
    boolean allowAll=GroupSelection.allAvailable(entries);
    group.addItem(new Choice("All",allowAll?"All Thread Groups":"All Thread Groups (requires 2 enabled groups)",allowAll));
    for(var entry:entries)group.addItem(new Choice(entry.key(),entry.toString(),entry.enabled()));
    if(entries.stream().noneMatch(GroupDiscovery.Entry::enabled))group.addItem(new Choice("","No enabled Thread Groups available.",false));
    for(int i=0;i<group.getItemCount();i++)if(group.getItemAt(i).key().equals(desired)){group.setSelectedIndex(i);break;}
    filling=false;selectionKey=desired;
    if(element!=null){element.setProperty("phase.group",desired);try{element.setProperty("phase.catalog",Reports.mapper().writeValueAsString(entries));}catch(Exception e){error(e);}}
    loadConfiguration();invalidateSelection();
  }
  private String selectedKey(){Choice c=(Choice)group.getSelectedItem();return c==null?"":c.key();}
  private RunSession viewSession(){
    RunSession run=element==null?null:element.session();
    Path current=PhaseAnalyzerListener.resolveJmxPath();
    return run!=null&&run.sourcePlan!=null&&current!=null&&!run.sourcePlan.equals(current)?null:run;
  }
  private boolean historyAvailable(){return viewSession()!=null&&viewSession().hasParticipatingGroups();}

  private Schedule selectedSchedule() {
    String key = selectedKey();
    for (var e : discovered) if (e.key().equals(key)) return e.schedule();
    return null;
  }

  private void updateConfiguration() {
    if (filling) return;
    boolean all=selectedKey().equals("All");
    if(all){automatic.setSelected(true);manual.setSelected(false);}
    manual.setEnabled(!all&&!selectedKey().isBlank());
    automatic.setEnabled(!selectedKey().isBlank());
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
                .orElse(selectedKey().equals("All")?"All uses each participating group’s own frozen configuration. Manual configuration is available for individual groups only.":"No enabled Thread Groups available.");
        phaseInfo.setText("<html>Automatic: " + escaped(warning)+"</html>");
        diagnostics.setText(warning);
      }
    } else
      phaseInfo.setText(
          "Manual analysis uses the selected group’s execution origin; no concurrency profile is inferred.");
    updatePreview();
  }

  private void updatePreview() {
    if (filling) return;
    try {
      preview.setPreview(
          automatic.isSelected()
              ? selectedSchedule()
              : Schedule.phases(
                  (element==null?0:element.getPropertyAsLong("phase.delay"))*1000,
                  duration("up") * 1000,
                  duration("hold") * 1000,
                  duration("down") * 1000));
      if (hint.getText().startsWith("Invalid input:")) hint.setText(" ");
    } catch (Exception e) {
      hint.setText("Invalid input: " + e.getMessage());
    }
  }

  private void requestSnapshot() {
    if (element == null || viewSession() == null) return;
    if(!busy.compareAndSet(false,true)){refreshPending=true;return;}
    if(!renderedRunId.equals(Long.toString(viewSession().start)))clock();
    refreshPending=false;
    PhaseAnalyzerListener requestedElement=element;
    PhaseAnalyzerListener cfg = (PhaseAnalyzerListener) element.clone();
    cfg.setProperty("phase.group",selectedKey());
    RunSession run = viewSession();
    long requestedRevision=selectionRevision;
    new SwingWorker<ExecutionViews.Result, Void>() {
      protected ExecutionViews.Result doInBackground() throws Exception {
        return ExecutionViews.build(cfg, run);
      }

      protected void done() {
        try {
          if (element == requestedElement && viewSession() == run && requestedRevision==selectionRevision) {
            var result=get();snapshot=result.selected();groupSnapshots=result.members();
            renderDashboard();
            renderTable();
            graph.setSnapshot("Execution Not Started".equals(snapshot.metadata().get("executionStatus"))?null:snapshot);graph.setGroupSnapshots(groupSnapshots);
            details.setText("Test plan: " + snapshot.metadata().getOrDefault("jmxFilename","Unavailable")
                + "\nThread Group: " + snapshot.metadata().getOrDefault("selectedThreadGroupLabel",snapshot.settings().group())
                + "\nCapture status: " + snapshot.metadata().getOrDefault("captureStatus","Unavailable")
                + "\nExecution status: " + snapshot.metadata().getOrDefault("executionStatus","Unavailable")
                + "\nCaptured samples: " + snapshot.phase("Complete execution").metrics().get("samples")
                + "\nOutput directory: " + reportDirectory());
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
          if(element==requestedElement&&viewSession()==run&&requestedRevision==selectionRevision)error(e);
        } finally {
          busy.set(false);
          clock();
          if(viewSession()!=null&&(refreshPending||requestedRevision!=selectionRevision))requestSnapshot();
        }
      }
    }.execute();
  }

  /** Native Clear resets results without changing saved configuration or view filters. */
  @Override public void clearData(){
    if(!SwingUtilities.isEventDispatchThread()){SwingUtilities.invokeLater(this::clearData);return;}
    if(element!=null&&!PlanIdentitySync.clearingAll())element.clearData();
    selectionRevision++;snapshot=null;groupSnapshots=Map.of();renderedRunId="";lastRefresh=0;
    graph.setSnapshot(null);graph.setGroupSnapshots(Map.of());details.setText("");diagnostics.setText("");
    renderDashboard();renderTable();clock();html.setEnabled(false);excel.setEnabled(false);
  }

  private void clock() {
    RunSession run=viewSession();
    capture.setVisible(run!=null && element.testRunning());
    capture.setEnabled(run!=null && element.testRunning());
    if(run==null){header.setText("Ready");groupStatus.setText(shortGroup());phaseStatus.setText("Not started");elapsedStatus.setText("—");activeStatus.setText("— users");rateStatus.setText("— samples/sec");liveStatus.setText("Ready · Run JMeter to begin capture and view live metrics");progress.setValue(0);progress.setString("Ready to capture");status.setText("No execution data yet. Select a Thread Group, then start JMeter.");html.setEnabled(false);excel.setEnabled(false);return;}
    String runId=Long.toString(run.start);
    if(!renderedRunId.equals(runId)){renderedRunId=runId;snapshot=null;graph.setSnapshot(null);renderDashboard();renderTable();html.setEnabled(false);excel.setEnabled(false);}
    long end=run.end==0?System.currentTimeMillis():run.end;
    if(!selectedKey().equals("All")&&run.groupEnd(selectedKey())>0)end=Math.min(end,run.groupEnd(selectedKey()));
    long since=selectedKey().equals("All")?run.start:snapshot!=null?((Number)snapshot.metadata().get("executionStart")).longValue():run.groupStart(selectedKey());
    String elapsed=selectedKey().equals("All")||run.participated(selectedKey())?time(Math.max(0,(end-since)/1000)):"—";
    String groupExecution=selectedKey().equals("All")?run.captureStatus:run.groupStatus(selectedKey());
    header.setText(groupExecution);
    groupStatus.setText(shortGroup());groupStatus.setToolTipText(Objects.toString(group.getSelectedItem()));phaseStatus.setText(groupExecution.equals("Running")?currentPhase(end):groupExecution);elapsedStatus.setText(elapsed);
    capture.setText(run.isCapturing()?"Stop Capture":"Start New Capture");
    String active="—";if(snapshot!=null&&!snapshot.timeline().isEmpty()){var point=snapshot.timeline().get(snapshot.timeline().size()-1);active=display(point.active());}
    Double liveRate=run.currentRate(selectedKey(),element.getPropertyAsString("phase.mode","Requests"),end);
    String rate=liveRate==null?(groupExecution.equals("Running")?"Warming up":"N/A"):display(liveRate);
    rateStatus.setToolTipText("Completion rate over up to five completed one-second intervals; partial buckets excluded. Frozen at capture end. Overall throughput is shown in the KPI cards.");
    Integer workers=run.isCapturing()?element.observedUsers(selectedKey()):null;
    if(workers!=null)active=display(workers);
    if(!element.testRunning() && run.end>0||!groupExecution.equals("Running"))active="0";
    activeStatus.setText(active+" users");rateStatus.setText(liveRate==null?rate:rate+" samples/sec");
    liveStatus.setText(groupExecution+"  ·  "+elapsed+"  ·  "+(groupExecution.equals("Running")?currentPhase(end):groupExecution)+"  ·  "+active+" active users  ·  "+rate+(liveRate==null?"":" samples/sec (completed intervals)"));
  }
  private String shortGroup(){Choice c=(Choice)group.getSelectedItem();String name=discovered.stream().filter(e->c!=null&&e.key().equals(c.key())).map(GroupDiscovery.Entry::name).findFirst().orElse(c!=null&&c.key().equals("All")?"All Thread Groups":"No enabled Thread Groups available.");return name.length()>23?name.substring(0,21)+"…":name;}
  private static String time(long seconds){return String.format(Locale.ROOT,"%02d:%02d:%02d",seconds/3600,(seconds/60)%60,seconds%60);}
  private static String escaped(Object s){return Objects.toString(s,"").replace("&","&amp;").replace("<","&lt;").replace(">","&gt;");}

  private String currentPhase(long t) {
    if(selectedKey().equals("All"))return "Per-group phases — independent schedules";
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
            : snapshot.phase(selectedPhase);
    if(snapshot!=null&&"Execution Not Started".equals(snapshot.metadata().get("executionStatus")))p=null;
    String[] keys = {"samples", "averageMs", "p90Ms", "p95Ms", "errorPercent", "samplesPerSecond"},
        suffix = {"", " ms", " ms", " ms", " %", " samples/s"};
    for (int i = 0; i < 6; i++) {
      Object v = p == null ? null : p.metrics().get(keys[i]);
      kpis[i].setText(v == null ? "—" : display(v) + suffix[i]);
      kpis[i].setToolTipText(p == null ? "No execution data" : p.status() + "; " + p.explanation());
    }
    if(p==null)selectedPhaseMetrics.setText("No execution data for the selected Thread Group.");
    else {var m=p.metrics();Object n=m.get("samples"),passed=m.get("successful");
      selectedPhaseMetrics.setText(label(selectedPhase)+" · "+p.status()+" · Passed "+display(passed)+" · Failed "+display(m.get("failed"))+" · Pass % "+(n instanceof Number total&&total.longValue()>0&&passed instanceof Number ok?display(ok.doubleValue()*100/total.doubleValue()):"—")+" · Median "+display(m.get("medianMs"))+" ms · P99 "+display(m.get("p99Ms"))+" ms · Min / Max "+display(m.get("minMs"))+" / "+display(m.get("maxMs"))+" ms");}
    phaseCards(dashboardPhases);
    phaseCards(livePhases);
    if (snapshot != null) {
      var c = snapshot.phase("Complete execution");
      Double duration = c.configuredSeconds();
      double elapsed =
          (((Number) snapshot.metadata().get("executionEnd")).longValue()
                  - ((Number) snapshot.metadata().get("executionStart")).longValue())
              / 1000.0;
      progress.setIndeterminate(false);
      if(selectedKey().equals("All")){
        progress.setValue("Completed".equals(snapshot.metadata().get("executionStatus"))?100:0);
        progress.setString("All participating groups — independent schedules");
      } else
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
    JTable phases=new JTable(model);phases.setRowHeight(Math.max(22,phases.getFontMetrics(phases.getFont()).getHeight()+6));phases.setFillsViewportHeight(false);phases.getTableHeader().setReorderingAllowed(false);phases.getColumnModel().getColumn(0).setPreferredWidth(160);phases.getColumnModel().getColumn(1).setPreferredWidth(140);JScrollPane scroll=new JScrollPane(phases);scroll.setPreferredSize(new Dimension(800,phases.getRowHeight()*4+phases.getTableHeader().getPreferredSize().height+5));phases.setRowSelectionAllowed(true);phases.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);phases.setRowSelectionInterval(ReportSnapshot.PHASES.indexOf(selectedPhase),ReportSnapshot.PHASES.indexOf(selectedPhase));panel.add(scroll);panel.revalidate();panel.repaint();
  }

  private void renderTable() {
    String phase = selectedPhase;
    List<String> headers = new ArrayList<>(ExcelReport.HEADERS);
    headers.add(0, "Population");
    DefaultTableModel model =
        new DefaultTableModel(headers.toArray(), 0) {
          public boolean isCellEditable(int r, int c) {
            return false;
          }
        };
    if(snapshot!=null&&!"Execution Not Started".equals(snapshot.metadata().get("executionStatus"))){
      if(selectedKey().equals("All")&&!groupSnapshots.isEmpty()){
        for(var member:groupSnapshots.entrySet())appendRows(model,member.getValue(),phase,false);
        appendRows(model,snapshot,phase,true);
      }else appendRows(model,snapshot,phase,null);
    }
    table.setModel(model);
    tableCards.setPreferredSize(new Dimension(800,model.getRowCount()==0?44:160));
    ((CardLayout)tableCards.getLayout()).show(tableCards,model.getRowCount()==0?"Empty":"Data");
    tableEmpty.setText(snapshot==null?"Run a test to see captured API metrics.":"Execution Not Started".equals(snapshot.metadata().get("executionStatus"))?"Execution has not started for this Thread Group.":"No samples captured in the selected phase.");
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

  private void appendRows(DefaultTableModel model,ReportSnapshot data,String phase,Boolean totalsOnly){
    for(var view:data.analysis().views())if(view.window().phase().equals(phase))for(var row:view.rows()){
      boolean total="TOTAL".equals(row.get("label"));if(totalsOnly!=null&&total!=totalsOnly)continue;
      List<Object> values=new ArrayList<>();values.add(view.kind());values.add(data.metadata().getOrDefault("selectedThreadGroupLabel",data.settings().group()));
      for(String key:ExcelReport.KEYS)values.add(row.get(key)==null?"N/A":row.get(key));model.addRow(values.toArray());
    }
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
    if (element == null || viewSession()==null || !element.testRunning()) return;
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

  private String reportDirectory(){return element==null?"phase-analyzer-results":element.getPropertyAsString("phase.output","phase-analyzer-results");}

  private void export(String extension) {
    ReportSnapshot data = snapshot;
    if (data == null
        || data.phase("Complete execution") == null
        || !(data.phase("Complete execution").metrics().get("samples") instanceof Number n)
        || n.longValue() == 0) {
      error(new IllegalStateException("No captured samples to export"));
      return;
    }
    JFileChooser chooser=new JFileChooser(lastDirectory==null?new File(reportDirectory().isBlank()?".":reportDirectory()):lastDirectory.toFile());
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

  private void error(Throwable e) {
    while (e.getCause() != null) e = e.getCause();
    String text = "Action failed: " + e.getMessage();
    hint.setText("<html>"+escaped(text)+"</html>");
    diagnostics.setText(text);
    if(isShowing())JOptionPane.showMessageDialog(this,text,"Performance Phase Analyzer",JOptionPane.ERROR_MESSAGE);
  }
}
