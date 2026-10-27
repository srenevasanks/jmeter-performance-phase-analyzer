# JMeter Performance Phase Analyzer

[![Java 17 Maven build](https://github.com/srenevasanks/jmeter-performance-phase-analyzer/actions/workflows/build.yml/badge.svg)](https://github.com/srenevasanks/jmeter-performance-phase-analyzer/actions/workflows/build.yml)
[![Apache License 2.0](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](LICENSE)

Understand how API performance changes during **ramp-up, steady load, ramp-down and complete execution** without changing your JMeter workload. Performance Phase Analyzer is a native Swing listener with live metrics, configurable analytical phases, one-file offline HTML reports and seven-sheet Excel workbooks.

**v1.1.2 · Java 17 · Apache JMeter 5.6.3 · Apache License 2.0**

[Download v1.1.2](https://github.com/srenevasanks/jmeter-performance-phase-analyzer/releases/tag/v1.1.2) · [Sample reports](examples/README.md) · [Screenshots](screenshots/README.md) · [Compatibility](docs/COMPATIBILITY.md)

## What the listener provides

- Discover actual Thread Group implementations and read supported native schedules automatically, or define manual analytical phase windows.
- Separate ramp-up, steady-load, ramp-down and complete-execution results, retaining eligible samples outside configured analytical phases.
- Track sample counts, success/failure, average, nearest-rank median/P90/P95/P99, min/max, population standard deviation, error %, throughput and received/sent KB/sec.
- Inspect configured load, observed users where available, response trends, error spikes and per-API aggregates.
- Capture Requests, Transactions or Separate populations. Filter phases and API labels without modifying collected data.
- Export HTML or Excel through Save dialogs. Buttons remain disabled without samples; automatic export is off unless explicitly enabled.
- **Stop Capture stops this listener only.** JMeter continues normally. Restarting capture creates a new independent session; JMeter's native Stop/Shutdown controls the actual test.

### Changes in v1.1.2

Thread Group identities are synchronized around native editor commits and Start/Save actions. Group changes update the analysis selection and discard stale asynchronous display results. Current throughput now uses completed one-second windows rather than an incomplete timeline bucket. The approved Swing layout, HTML/Excel designs and exported metric calculation engine are preserved.

The patch passes **62 automated tests**. Targeted native runs captured **659 samples**, with **2,432 independent JTL/report checks and no calculation discrepancies**. Final interactive re-verification of the corrected Start/Save sequence remains pending; all nine implementations retain **Partial overall validation status**. See [validation scope and limits](docs/VALIDATION.md).

## Dashboard

The Dashboard shows capture status, selected Thread Group, current phase, elapsed time, active users, six KPIs, phase summaries, execution progress and collapsible Test Information. Select a phase to inspect its results, then choose **Generate HTML Report** or **Export Excel**.

![Dashboard during actual local mock execution](screenshots/listener/dashboard-running.png)

The screenshots below and throughout this repository are genuine **v1.1.1 validation captures**, reused to illustrate the preserved UI/report designs. They are not new v1.1.2 GUI verification. In particular, the older Dashboard's Requests/sec label predates the v1.1.2 Current throughput display. Screenshot title bars were cropped for path privacy; content was not redrawn. [Provenance](screenshots/README.md).

<details><summary>Ready and completed states</summary>

![Ready before execution](screenshots/listener/dashboard-ready.png)
![Completed capture](screenshots/listener/dashboard-completed.png)

</details>

## Phase Configuration

Select a Thread Group and choose **Automatic** to inspect its detected native implementation and read-only configured durations. Unsupported or ambiguous schedules are identified explicitly. The analyzer does not infer phase boundaries from response times or observed concurrency.

![Actual Automatic phase configuration](screenshots/listener/configuration-automatic.png)

Use **Manual** for analytical durations in seconds or minutes, allowing absent phases. Manual configuration changes analysis windows, not the native workload. The duration-only preview does not invent a concurrent-user curve.

<details><summary>Manual analytical preview</summary>

![Unapplied Manual duration preview](screenshots/listener/configuration-manual-preview.png)

This capture is an unapplied 20/80/20-second preview. It does not establish a native ramp-down for the Standard Thread Group.

</details>

## Live Metrics and Aggregate Results

Choose All Metrics, Passed Requests, Failed Requests, Throughput, Average Response Time, P95 Response Time or Error Rate. The timeline provides phase boundaries, units and a compact legend. All Metrics explicitly normalizes series; individual views use their own units. The aggregate table supports phase selection, API search and horizontal scrolling.

![Live timeline and aggregates](screenshots/listener/live-metrics.png)

**Current throughput** counts accepted sample completions over up to five fully completed one-second intervals. The first partial capture second and current partial second are excluded. Startup shows Warming up; idle intervals count in the denominator. Failed samples count as samples. At capture end the display freezes at that cutoff. Units are **samples/sec**. Overall/phase exported throughput continues to use its documented effective-window denominator.

<details><summary>Observed error burst, recovery and aggregate view</summary>

![Actual injected errors and recovery](screenshots/listener/live-failure-recovery.png)
![Searchable aggregate results](screenshots/listener/aggregate-results.png)

</details>

## HTML reporting

Export one self-contained HTML file that works offline, without a server or external assets. It supports light/dark switching, phase selection, API search, numeric sorting, interactive chart legends and tooltips.

| Section | Contents |
|---|---|
| 1–3 | Execution Summary, Overall Key Metrics, Phase Results Summary |
| 4–6 | Active Users Timeline, Response Time Trend, Throughput & Error Rate |
| 7–9 | Aggregate Results by API, Response Codes Summary, Execution Configuration Details |

![Actual light HTML report](screenshots/html-report/light-complete.png)

<details><summary>Dark report and individual sections</summary>

![Actual dark HTML report](screenshots/html-report/dark-complete.png)

View [all nine section screenshots](screenshots/README.md) or download the [Ultimate HTML example](examples/ultimate/report.html). GitHub displays HTML source; download the file and open it locally for interaction.

</details>

## Excel reporting

Each workbook contains **Execution Summary, Ramp-up, Steady Load, Ramp-down, Complete Execution, Response Codes and Execution Configuration**. Structured summaries, readable dates/durations, formatted aggregate tables, filters and frozen identifiers preserve the same metrics used by HTML. The summary includes a native Average/P90/P95 comparison chart where data permits. Unavailable phases remain unavailable.

![Actual Excel Execution Summary viewed in WPS Office](screenshots/excel-report/01-execution-summary.png)

[All seven worksheets and chart screenshots](screenshots/README.md) · [Download Ultimate Excel example](examples/ultimate/report.xlsx) · [Excel calculation/format details](docs/EXCEL-REPORT.md)

Suggested report filenames are `<JMX_Name>_yyyy-MM-dd_HH-mm-ss.html` and `.xlsx`, using the same local execution-start timestamp. Names are sanitized and existing files are protected against accidental overwriting. An unresolved JMX filename is shown as unavailable. Only the selected export is created; internal capture data stays outside the report directory and ordinary JMeter JTL files are unaffected.

## Thread Group support and evidence

All nine implementations below have actual execution evidence, but **none is claimed as fully certified**. The published samples come from nine independent v1.1.1 local mock runs: **51,177 actual HTTP samples and 13,492 independent checks with zero report calculation differences**. No nine-group load-test batch was rerun for this publication.

| Thread Group | Provider | Native behavior in the supplied examples | Overall validation |
|---|---|---|---|
| Standard | JMeter 5.6.3 | Ramp-up + hold; abrupt end | Partial |
| Ultimate | Custom Thread Groups 3.1.1 | Scheduled ramp-up / hold / ramp-down | Partial |
| Stepping | Custom Thread Groups 3.1.1 | Discrete user increases and decreases | Partial |
| Concurrency | Custom Thread Groups 3.1.1 | Concurrency ramp-up + hold; abrupt end | Partial |
| Arrivals | Custom Thread Groups 3.1.1 | Arrival-rate ramp-up + hold; abrupt end | Partial |
| Free-Form Arrivals | Custom Thread Groups 3.1.1 | Three-phase arrival-rate profile | Partial |
| Open Model | JMeter 5.6.3 | Native random-arrival profile | Partial |
| setUp | JMeter 5.6.3 | Setup lifecycle; ramp-up + hold | Partial |
| tearDown | JMeter 5.6.3 | Teardown lifecycle; ramp-up + hold | Partial |

Arrival rates are **arrivals/sec, not concurrent users**. Missing native ramp-down is not synthesized. See [compatibility and targeted versus reused evidence](docs/COMPATIBILITY.md), [native configuration screenshots](screenshots/README.md) and [nine sample report pairs](examples/README.md).

## Installation

| Requirement | Version / dependency policy |
|---|---|
| Java | 17 |
| Apache JMeter | Complete official 5.6.3 binary distribution |
| Jackson | Stock JMeter 5.6.3 supplies 2.16.1; do not add another copy |
| Custom Thread Groups | Optional 3.1.1, required only for Ultimate, Stepping, Concurrency, Arrivals and Free-Form Arrivals workloads; install its normal dependencies, including common library 0.7 |
| Maven | 3.9.x + JDK 17, development/build only |

1. Download [`jmeter-performance-phase-analyzer-1.1.2.jar`](https://github.com/srenevasanks/jmeter-performance-phase-analyzer/releases/download/v1.1.2/jmeter-performance-phase-analyzer-1.1.2.jar).
2. Close JMeter and move older versions of this analyzer outside `<JMETER_HOME>/lib/ext`.
3. Copy the JAR to `<JMETER_HOME>/lib/ext/`, keeping exactly one analyzer version.
4. Restart JMeter 5.6.3 with Java 17.
5. Add **Test Plan → Add → Listener → Performance Phase Analyzer**.

JMeter supplies the provided runtime dependencies. Do not copy Maven dependency JARs into the installation. Optional Thread Groups are installed separately; they are not bundled. No Excel/WPS installation, external server or browser-based listener UI is required. Native Swing controls follow JMeter's Look and Feel. **This plugin has not been submitted to JMeter Plugins Manager.** Historical [clean-install evidence](docs/CLEAN-INSTALL-VERIFICATION.md) is explicitly versioned; current patch evidence is in [VALIDATION.md](docs/VALIDATION.md).

## Quick start

Open [examples/local-demo.jmx](examples/local-demo.jmx). It runs two users for four seconds with a local Groovy sampler that waits 25 ms and returns a success response. It makes no network requests and needs no private service.

1. Select the listener and inspect Automatic phase configuration.
2. Execute the plan and inspect Dashboard / Live Metrics.
3. Select Generate HTML Report or Export Excel and choose a destination.
4. Use only authorized targets when replacing the local demo with HTTP requests.

For your own plan, run workload groups independently when comparing schedules. Handle setUp/tearDown according to their native lifecycle. Samples are classified by start time in `[start,end)`; strict mode additionally requires completion within the end boundary. Complete Execution retains eligible samples outside analytical phases, disclosed as unclassified. Nearest-rank percentiles and throughput denominators are described in [methodology](docs/METHODOLOGY.md).

## Build and test

```sh
git clone https://github.com/srenevasanks/jmeter-performance-phase-analyzer.git
cd jmeter-performance-phase-analyzer
mvn --batch-mode --no-transfer-progress clean verify
```

Artifact: `target/jmeter-performance-phase-analyzer-1.1.2.jar`. The complete **62-test** suite includes regression and embedded native JMeter executions without a private service. GitHub Actions runs the same Maven command on Java 17. Generated outputs, raw JTL, logs, credentials and developer-only evidence remain excluded; only the explicitly curated example HTML/XLSX files are committed.

## Known limitations

- Overall compatibility remains **Partial**; other JMeter/Java versions, platforms and arbitrary complex/dynamic schedules are not exhaustively certified.
- Final interactive v1.1.2 Start/Save switching and Dashboard/Live Metrics completion checks remain pending after computer-use activation/capture failed. Actual native editor regression tests pass; the older screenshots are not proof of a new GUI run.
- In targeted Open Model evidence, two user-count readings differed by one worker at different sampling/callback instants. Exact continuous active-worker accuracy is not proven. Earlier Arrivals evidence also had a native arrival/completion lifecycle-fact difference; statistical reports retained actual HTTP samples.
- Standard/setUp worker schedule epochs differed by 2–3 ms; analysis anchors to the first observed supported native worker clock. Missing clock evidence is disclosed instead of guessed from measured concurrency.
- No native gradual ramp-down exists for Standard, Concurrency, Arrivals, setUp or tearDown in the supplied schedules. Manual windows do not change that workload behavior.
- Force-killing the JVM can prevent finalization/cleanup. Unknown termination causes remain unknown. Distributed execution and final desktop resizing/theme behavior are not fully verified.

## Contributing and license

Report issues with sanitized plugin/JMeter/Java versions, OS, minimal JMX and expected versus actual behavior. Remove secrets and private targets before sharing evidence. Contributions should include focused tests and preserve the documented sample populations.

[Apache License 2.0](LICENSE). [NOTICE](NOTICE) attributes the Apache JMeter test fixture. This independently maintained plugin is not an official Apache Software Foundation project.
