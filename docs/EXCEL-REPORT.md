# Excel report, version 1.1.1

The approved Excel redesign preserves the seven worksheet names and the existing immutable snapshot calculations. It changes workbook presentation and Excel-specific handling of unavailable values, without altering capture, phase detection, aggregation, HTML or Swing behavior.

## Workbook content

- Execution Summary: readable report information, captured timing and timezone, overall KPIs, phase comparison and an Excel-native Average/P90/P95 chart. The chart omits unavailable phases and is omitted entirely when fewer than two valid phases exist.
- Ramp-up, Steady Load, Ramp-down and Complete Execution: compact phase overview, capture explanation and native aggregate tables. Identifying columns and table headers are frozen. Filters exclude the distinguished TOTAL row; totals retain the full underlying phase/population when detail rows are filtered.
- Response Codes: counts and percentages reconcile with the snapshot's captured population. Sampler codes are not assumed to be HTTP statuses. Descriptions and per-code failure classifications are not retained by the current snapshot and remain unavailable rather than guessed.
- Execution Configuration: readable test settings, one row per retained Thread Group, native scheduling units, configured/effective windows and useful limitations. Disabled groups are shown when retained by the captured catalog. Absent zero-length native phases display N/A.

Dates are numeric Excel values rendered in the captured timezone. Counts have thousands separators; response times and rates have consistent precision. Percentage formats preserve already percentage-scaled source values. Internal Snapshot IDs, schema/debug fields, raw maps and Java object representations are absent from visible sections.

## Verification

The redesigned exporter passed a 54-test Java 17 Maven suite, including four focused Excel regressions for internal-field removal, exact metrics, timestamps, percentages, long labels/names, native table totals/filter ranges, zero samples, missing/partial phases, chart omission and all nine Thread Group types.

A newly generated workbook from a preserved actual Ultimate Thread Group local-mock run contained 30,342 captured HTTP samples: 30,219 successful and 123 failed. Independent JTL recalculation matched 360 exported metric values across four phases, five API labels and TOTAL, with zero discrepancies. The three chart series matched the same phase metrics. These are plugin-validation results, not real application-performance claims.

All seven sheets, the native chart, phase-window notes, percentage formatting and horizontal aggregate scrolling were opened and inspected in WPS Office 12.1.0.29162. The user reviewed and approved the design. Screenshots and raw validation outputs remain local and are not added to this repository. No new screenshots were created for publication.

A separate standalone JMeter 5.6.3 / Java 17 smoke test using the final Excel implementation captured 226 successful local samples, produced one seven-sheet XLSX, and reconciled its TOTAL to native JTL. All 66 non-Excel class/template entries remained byte-identical to the verified implementation. Version 1.1.1 changes Maven release metadata without changing that approved exporter design.

## Limits

An additional interactive Microsoft Excel desktop visual pass was not completed; review used WPS as directed after Excel displayed an activation notice. The package uses standard OOXML styles, native tables and charts, and its XML was validated. This does not claim a new Excel desktop review.

The preserved full run ended normally while its cutoff was about one second before the configured ramp-down end. The unchanged snapshot therefore reports Completed execution and Partially captured ramp-down. The workbook preserves and explains both statuses rather than modifying calculation boundaries.

Earlier native scheduling/lifecycle evidence limitations remain documented in [COMPATIBILITY.md](COMPATIBILITY.md). The Excel redesign did not rerun the long nine-group batch or change those findings.
