> Historical validation record: version labels and checksums below identify the development artifact actually tested. The current official release is **1.0.0**, a metadata-only renumbering; screenshots, sample metrics and original test evidence are preserved.

# Final local validation — 2026-11-01

**Overall: Partial.** All available numerical comparisons passed. Remaining GUI/evidence limits are explicitly listed below. No production Java changes, commit, push, tag or release were made during this final evidence pass.

## Artifact and tests

- Local Maven version: 1.1.2 (unpublished). Java 17, Apache JMeter 5.6.3.
- Build: `local-final-20261031-A64E91CE2999`.
- JAR SHA-256: `A64E91CE29993DFAC97F7AB4DB8C6D9F38CC72682CD69958DB0C934894375BD0`.
- Clean Maven verify and final Maven test: **95 tests**, zero failures/errors/skips.
- Independent JTL/HTML/Excel/time-series comparisons: **38,308 checks, zero discrepancies** across nine isolated executions plus focused sequential/parallel/clear/refresh supplements.
- HTML offline browser tests exercise nine sections, phase/search/sort/themes/legends/tooltips, no external requests, 1366×768 and 1920×1080. Excel retains seven worksheets; all nine opened in WPS.

## Nine isolated executions

| Group | Actual samples | Independent checks | Result | Evidence |
|---|---:|---:|---|---|
| 01-Standard | 1,765 | 1,207 | PASS (scoped metrics) / Partial overall | [Screenshots](../screenshots/validation/01-Standard/README.md) · [HTML](../examples/standard/report.html) · [Excel](../examples/standard/report.xlsx) |
| 02-Ultimate | 1,688 | 1,390 | PASS (scoped metrics) / Partial overall | [Screenshots](../screenshots/validation/02-Ultimate/README.md) · [HTML](../examples/ultimate/report.html) · [Excel](../examples/ultimate/report.xlsx) |
| 03-Stepping | 1,748 | 1,390 | PASS (scoped metrics) / Partial overall | [Screenshots](../screenshots/validation/03-Stepping/README.md) · [HTML](../examples/stepping/report.html) · [Excel](../examples/stepping/report.xlsx) |
| 04-Concurrency | 1,767 | 1,207 | PASS (scoped metrics) / Partial overall | [Screenshots](../screenshots/validation/04-Concurrency/README.md) · [HTML](../examples/concurrency/report.html) · [Excel](../examples/concurrency/report.xlsx) |
| 05-Arrivals | 1,620 | 1,207 | PASS (scoped metrics) / Partial overall | [Screenshots](../screenshots/validation/05-Arrivals/README.md) · [HTML](../examples/arrivals/report.html) · [Excel](../examples/arrivals/report.xlsx) |
| 06-FreeFormArrivals | 1,389 | 1,390 | PASS (scoped metrics) / Partial overall | [Screenshots](../screenshots/validation/06-FreeFormArrivals/README.md) · [HTML](../examples/free-form-arrivals/report.html) · [Excel](../examples/free-form-arrivals/report.xlsx) |
| 07-OpenModel | 1,400 | 1,383 | PASS (scoped metrics) / Partial overall | [Screenshots](../screenshots/validation/07-OpenModel/README.md) · [HTML](../examples/open-model/report.html) · [Excel](../examples/open-model/report.xlsx) |
| 08-SetUp | 1,777 | 1,207 | PASS (scoped metrics) / Partial overall | [Screenshots](../screenshots/validation/08-SetUp/README.md) · [HTML](../examples/setup/report.html) · [Excel](../examples/setup/report.xlsx) |
| 09-TearDown | 1,777 | 1,207 | PASS (scoped metrics) / Partial overall | [Screenshots](../screenshots/validation/09-TearDown/README.md) · [HTML](../examples/teardown/report.html) · [Excel](../examples/teardown/report.xlsx) |

Standard/setUp/tearDown: 20 s ramp-up + 70 s hold, abrupt native end, no invented gradual ramp-down. Ultimate/Stepping: 20/50/20 s (Stepping uses discrete steps). Concurrency: 20 s ramp-up + 70 s hold, 11 out-of-window tail samples retained in Complete Execution. Arrivals: 20 s rate ramp + 70 s hold, no gradual ramp-down. Free-Form/Open Model: native arrival-rate 20/50/20 s; rates are not users. Closed tests use 10 peak users; open profiles target 20 arrivals/sec. These short profiles are not the older 30-minute performance runs.

## Targeted production behavior verified

- Automatic default; first enabled group; disabled popup choice rejected, including native state changing while popup is open; all-disabled empty state.
- Shared group selection, sequential Completed/Running/Not Started, automatic updates on next group start, parallel individual views and combined named series/API rows.
- All requires multiple enabled choices, uses Automatic and disables Manual. Dynamic valid Manual settings and removal of Advanced Settings/Apply/Reset are visible.
- Steady → Complete changes actual samples and KPIs; JMX reopen resets to Steady/Ready without previous execution data.
- Auto-refresh off freezes displayed data while collection advances. Two manual refreshes advance the display. Auto-refresh on resumes updates during the running supplemental test.
- Observed Users toggle, Clear and Clear All were exercised in actual running tests; completed Clear All returns to Ready without changing configuration.

## Additional execution populations

- Original sequential: 1,234 samples (134/700/400).
- Original parallel: 2,073 samples (391/691/991).
- Clear scenario native evidence: 2,391; captured post-clear: 48 (22/26).
- Parallel/Refresh supplement: 4,793 (1,593/1,600/1,600).
- Sequential GUI supplement: 1,931 (134/1,597/200).

These are distinct executions, not interchangeable screenshot totals. Every completed export was compared with its own native JTL.

## Remaining limitations and missing evidence

- macOS actual GUI verification remains pending; Windows evidence cannot certify it.
- All selected: completed configuration timeline can still say “Waiting for capture”; combined graphs/tables contain data and Manual remains disabled.
- Dashboard Complete Execution correctly changes KPIs/selection, but the progress caption can retain “Steady Load”.
- Arrivals running aggregate gap was filled by an isolated audit recapture; original sample reports remain unchanged.
- Selected-group deletion fallback was not freshly repeated interactively; automated coverage and historical evidence are separate.
- Wide WPS aggregates require horizontal scrolling; not every rightmost column was visually inspected. All seven sheet data were independently checked.
- HTML browser controls/layout screenshots exist for both themes and two resolutions; all 390 original images have now been individually reviewed; see the final screenshot audit. Do not claim pixel matching to mockups.
- Active Clear/Clear All preserve native JTL evidence (2,391 samples); reports reconcile the 48 post-clear samples. A before/after file-hash proof of export immutability was not captured.
- Observed-user sampling is discrete, not a continuous authoritative thread trace. Arrival rates are not concurrent users. Unsupported native phases remain N/A.
- Mock failures are intentional (roughly 5–6% in these scenarios); these are plugin-validation results, not a passing application performance acceptance test.

## Local handover and release policy

Latest JAR is installed; original Master_ThreadGroups.jmx is left open without execution. Its saved enabled states are preserved. The test-plan display name still contains an older version; it is not the loaded JAR version.

The audit did not change releases. Subsequently the maintainer explicitly selected 1.0.0 as the first official release and authorized removal of development-release entries/tags after verifying the new artifact. Source history remains preserved. Users of a higher-numbered development build must install 1.0.0 explicitly; version-ordered update tools may not treat it as an upgrade.

## Installation / manual use / troubleshooting

Use Java 17 and the complete JMeter 5.6.3 distribution. Close JMeter before replacing the analyzer in lib/ext; retain exactly one analyzer JAR. Custom Thread Groups 3.1.1 and its normal common-library dependency are optional for the five third-party groups. Standard/Open Model/setUp/tearDown are native. WPS/Excel is optional for viewing exported XLSX, not a plugin runtime dependency.

Enable only the intended workloads; inspect Automatic configuration, run manually, then select the group and phase. HTML/Excel actions require captured samples and use Save dialogs. Stop Capture affects only listener collection; JMeter native Stop/Shutdown controls the test. If the listener is missing, check duplicate JARs, Java version and jmeter.log. A selected phase N/A indicates no valid analytical window, not a zero result. Local validation's mock API must be running before executing its HTTP plans.

Latest screenshot audit: [390 original images plus one targeted recapture; defects and evidence limits](SCREENSHOT_AUDIT_20261101.md). Overall remains **Partial**; cached popup labels, completed preview/caption and combined HTML load wording are not clean passes.
