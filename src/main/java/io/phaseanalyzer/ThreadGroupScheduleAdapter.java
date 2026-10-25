package io.phaseanalyzer;

import org.apache.jmeter.threads.AbstractThreadGroup;

public interface ThreadGroupScheduleAdapter {
  boolean supports(AbstractThreadGroup group);

  Schedule read(AbstractThreadGroup group) throws Exception;
}
