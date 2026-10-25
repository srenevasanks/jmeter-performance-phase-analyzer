package io.phaseanalyzer;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.layout.PatternLayout;

/** Read-only lifecycle evidence; never invokes any JMeter stop action. */
final class ExecutionEvidence extends AbstractAppender implements AutoCloseable {
  private final org.apache.logging.log4j.core.Logger root;
  private volatile boolean ended, stopped, interrupted, manual;
  private final java.util.concurrent.atomic.AtomicInteger started = new java.util.concurrent.atomic.AtomicInteger(), finished = new java.util.concurrent.atomic.AtomicInteger();
  private final java.awt.event.ActionListener guiStop = event -> {
    if (event.getActionCommand().equals(org.apache.jmeter.gui.action.ActionNames.ACTION_STOP)) { interrupted = true; manual = true; }
    if (event.getActionCommand().equals(org.apache.jmeter.gui.action.ActionNames.ACTION_SHUTDOWN)) { stopped = true; manual = true; }
  };
  private boolean guiRegistered;
  ExecutionEvidence() {
    super("phase-lifecycle-" + java.util.UUID.randomUUID(), null, PatternLayout.createDefaultLayout(), true,
        org.apache.logging.log4j.core.config.Property.EMPTY_ARRAY);
    root = ((LoggerContext) LogManager.getContext(false)).getRootLogger();
    start(); root.addAppender(this);
    if (org.apache.jmeter.gui.GuiPackage.getInstance() != null) {
      org.apache.jmeter.gui.action.ActionRouter.getInstance().addPreActionListener(org.apache.jmeter.gui.action.Start.class, guiStop);
      guiRegistered = true;
    }
  }
  @Override public void append(LogEvent event) {
    String logger = event.getLoggerName(), text = event.getMessage().getFormattedMessage();
    if (logger.equals("org.apache.jmeter.JMeter") && text.startsWith("Command: ")) {
      if (text.contains("Shutdown received")) { stopped = true; manual = true; }
      if (text.contains("StopTestNow received")) { interrupted = true; manual = true; }
    }
    if (logger.equals("org.apache.jmeter.threads.JMeterThread")) {
      if (text.startsWith("Thread started:")) started.incrementAndGet();
      if (text.startsWith("Thread finished:")) finished.incrementAndGet();
      if (text.startsWith("Stopping:") || text.startsWith("Stopping Test:")) stopped = true;
      if (text.startsWith("Stopping Test Now:") || text.startsWith("Stopping Test with interruption")) interrupted = true;
    }
    if (logger.equals("org.apache.jmeter.threads.JMeterThread") && text.startsWith("Interrupting:")) interrupted = true;
    if (logger.equals("org.apache.jmeter.engine.StandardJMeterEngine") && text.equals("Notifying test listeners of end of test")) ended = true;
  }
  String outcome() {
    if (interrupted) return manual ? "Interrupted (manual Stop Now)" : "Interrupted (source unknown)";
    if (stopped) return manual ? "Stopped (manual Shutdown)" : "Stopped (source unknown)";
    boolean coverage = LogManager.getLogger("org.apache.jmeter.threads.JMeterThread").isInfoEnabled();
    return ended && coverage && started.get() > 0 && started.get() == finished.get() ? "Completed" : "Unknown";
  }
  @Override public void close() { root.removeAppender(this); stop();
    if (guiRegistered) org.apache.jmeter.gui.action.ActionRouter.getInstance().removePreActionListener(org.apache.jmeter.gui.action.Start.class, guiStop); }
}

