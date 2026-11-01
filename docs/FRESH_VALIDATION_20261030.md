> Historical validation record: version labels and checksums below identify the development artifact actually tested. The current official release is **1.0.0**, a metadata-only renumbering; screenshots, sample metrics and original test evidence are preserved.

> Historical build evidence. See [current final validation](FINAL_VALIDATION_20261101.md) for latest counts and remaining limitations.

# Current local validation — 30–31 October 2026

> **31 October correction:** The phase-dropdown defect described below is fixed in the latest installed local build. 83 automated tests and 13,482 additional comparisons pass. Targeted popup, lifecycle and dynamic-configuration checks are now captured. The following original evidence is retained as a dated record; [current results and remaining limitations](PHASE_DROPDOWN_FIX_20261031.md).

**Overall: Partial.** Calculations pass the scoped comparisons. A confirmed GUI phase-label reset defect and specific interactive coverage gaps remain. Nothing has been pushed or released.

Installed local build: 1.1.2; Java 17, Apache JMeter 5.6.3, Windows. JAR SHA-256 `EE2965E53326C36F59B722FCEF023779F5D9E5542A6A19A3CAB1BC3A3791CB12`.

## Verified evidence

- Previously completed clean Maven verify: **76 tests; zero failures/errors/skips**. No production code was changed or rebuilt in this evidence/documentation continuation.
- Nine independent individual executions and original sequential/parallel executions: **20,436 independent comparisons, zero calculation discrepancies**.
- Targeted GUI retry executions (sequential, parallel, Ultimate, setUp and tearDown only): **63 additional comparisons, zero differences** between screenshot readings and actual native JTL. These are separate populations, not replacements for the original 20,436 checks.
- Original nine HTML reports: all nine sections, phase/search/sort controls, offline charts and themes checked at 1366×768 and 1920×1080.
- All nine original Excel exports contain seven worksheets. Previously missing setUp/tearDown WPS sheets and Ultimate/Stepping/setUp/tearDown charts are now actually captured.
- Controlled mock errors are intentional. These are plugin-validation results, not real application performance acceptance results.

## Individual execution and screenshot matrix

| Thread Group | Samples / passed / failed | Up / steady / down samples | Outside | Current images | Result | Native limits |
|---|---|---|---:|---:|---|---|
| 01-Standard | 1764 / 1668 / 96 | 364 / 1400 / 0 | 0 | 32 | Metrics Passed; overall Partial | No native gradual ramp-down; abrupt scheduled end. |
| 02-Ultimate | 1691 / 1599 / 92 | 326 / 1001 / 364 | 0 | 34 | Metrics Passed; overall Partial | Single schedule exercised; arbitrary multi-row overlap is not certified. |
| 03-Stepping | 1745 / 1650 / 95 | 366 / 1000 / 379 | 0 | 32 | Metrics Passed; overall Partial | Discrete native user steps, not a smooth linear curve. |
| 04-Concurrency | 1758 / 1663 / 95 | 346 / 1400 / 0 | 12 | 32 | Metrics Passed; overall Partial | No gradual ramp-down; 12 tail samples retained in Complete Execution. |
| 05-Arrivals | 1620 / 1529 / 91 | 220 / 1400 / 0 | 0 | 31 | Metrics Passed; overall Partial | Arrival-rate units, not concurrency; no gradual ramp-down. |
| 06-FreeFormArrivals | 1389 / 1309 / 80 | 187 / 1000 / 202 | 0 | 32 | Metrics Passed; overall Partial | Decreasing arrival rate, not a configured concurrent-user curve. |
| 07-OpenModel | 1400 / 1317 / 83 | 200 / 1000 / 200 | 0 | 32 | Metrics Passed; overall Partial | Random arrival scheduling; exact continuous worker-count parity is not certified. |
| 08-SetUp | 1766 / 1670 / 96 | 365 / 1400 / 0 | 1 | 33 | Metrics Passed; overall Partial | Native setup lifecycle; no gradual ramp-down; one out-of-window sample retained. |
| 09-TearDown | 1766 / 1670 / 96 | 365 / 1400 / 0 | 1 | 33 | Metrics Passed; overall Partial | Native teardown lifecycle; no gradual ramp-down; one out-of-window sample retained. |

All previous individual missing screenshot types are now covered: Ultimate running Live Metrics; setUp completed Ramp-up/Manual; tearDown running Dashboard/Live; missing WPS worksheets/charts. All nine share the current GUI-reopen defect and unverified macOS GUI status; a complete screenshot list is not full certification.

## Additional functional GUI evidence

| Check | Actual result / evidence | Status |
|---|---|---|
| Automatic default, first enabled group | TG1 disabled → TG2 selected automatically; master all-disabled message captured | Passed scoped GUI check |
| Disabled rejection and dynamic fallback | TG1 grey and click rejected; disabling selected TG2 falls back to TG3; all disabled produces ready/empty view | Passed scoped GUI check |
| Shared selection | TG3 persists from Configuration to Dashboard | Passed scoped GUI check |
| Sequential | TG1 Completed + historical graph while TG2 runs; TG3 Not Started + no foreign metrics, then automatic live updates | Partial: TG2-selected Running views missing |
| Parallel | Completed TG1 599, TG2 1000, TG3 1399 have individual graphs/API rows; running All retains separate group series | Partial: individual-selected running screenshots missing |
| All graph/table | Three named series, common actual timestamp axis, separate Product Search rows 180/300/420, TOTAL 2998 | Passed scoped GUI/JTL check |
| All configuration | Automatic active; attempted click on disabled Manual leaves Automatic active; no common phase schedule invented | Partial: completed preview text misleading |
| Removed actions | No Advanced Settings, Apply or Reset in current individual/All forms | Passed scoped visual check |
| Completed phase switching | TG3 Steady 433 → Complete 533 → Ramp-up 100; averages/P95/errors independently agree with JTL | Passed scoped regression |
| JMX reopen phase reset | Dropdown retains preceding Complete/Ramp-up label while internal selected phase is Steady | **Failed GUI reset check** |

## Confirmed defect and limits

`PhaseAnalyzerGui.clearGui()` resets `selectedPhase` to Steady load but does not reset `dashboardScope` and `tableScope`. `configure()`/render then use the reset internal phase with stale combo labels. Reproduced after opening another JMX in the same GUI; explicit phase selection synchronizes both controls and updates the values. This is a production UI state defect, not a JTL metric calculation discrepancy. Evidence: Ultimate `gui-retry-running-phase-label-mismatch.png` and All `running-throughput-three-groups-phase-label-review.png`. No source changes were made in this documentation/evidence task.

All Configuration uses a blank preview saying Waiting for capture/no data even after completion; combined Live/Dashboard have data. This wording is misleading for a view with no shared native schedule.

Exact remaining interactive evidence: TG2 selected while Running (sequential), individual selections while Running (parallel), stale-open-popup disable transition and selected-group deletion fallback. Automated tests cover relevant state logic, but those screenshots/interactive checks are not claimed as passed. macOS GUI verification and exhaustive Windows resize/theme permutations remain pending. The native abrupt-stop/arrival-rate limitations above remain.

## Provenance, quality and cleanup

All original 281 files match their recorded hashes and the nine capture DONE records point to the installed latest-plugin path; original report screenshots map to their own execution exports. PNG decode, dimensions, nonblank content and copy hashes are checked for every accepted current image. Representative group Dashboards, actual recovered GUI views, missing WPS sheets/charts and report evidence were visually inspected. This is not a claim of exhaustive pixel-by-pixel readability review of all off-screen table columns.

One original tearDown image named `dashboard-ready-first-enabled.png` was actually a Save confirmation. It is excluded from the curated collection and retained outside the repository as quarantined developer evidence. There are no fabricated replacement images.

Current gallery: **342 evidence images** (62 added during retry, 280 retained from the original 281). **274 superseded historical images removed/replaced** (139 old paths deleted, 135 paths replaced with current captures), **14 unique historical PNGs relocated into an explicitly historical collection**. All original bytes and old example reports are archived outside the repository before cleanup. **281 duplicate staging PNGs** were removed separately; these are not counted as outdated historical deletions. Curated aliases: 35.

## Manual handover

Latest plugin remains installed. The existing Master_ThreadGroups.jmx is open with all nine groups disabled and no test executing. Its Test Plan name still contains the historical v1.1.1 label; that label is not the installed plugin version. Current file SHA-256 is recorded in the local integrity JSON. An earlier handover record contained a different master hash; do not use that stale record as proof of byte identity. Current file last-write time predates these GUI retries; no Save was issued for the master here.

No push, release, tag, version change or Plugins Manager submission. Stop here for user approval.

[Current screenshots](../screenshots/validation/README.md) · [Actual example reports](../examples/README.md)
