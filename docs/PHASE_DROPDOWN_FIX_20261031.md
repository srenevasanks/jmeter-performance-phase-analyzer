> Historical build evidence. See [current final validation](FINAL_VALIDATION_20261101.md) for latest counts and remaining limitations.

# Phase selection correction — 31 October 2026

Local unpublished 1.1.2 correction. No commit, push, tag or release. The previous nine-group validation and reports were retained; only short targeted runs were executed.

## Root cause and production correction

JMeter reuses listener GUI instances when loading another JMX. `clearGui()` reset the internal phase to Steady load but left Dashboard and Live Metrics dropdowns on their old labels. Rendering then used a different phase from the visible selection. Reused bindings and queued snapshot workers could also retain old view state.

- `PhaseAnalyzerGui.java`: atomically synchronize both phase controls and internal phase; reset group, phase, filters, charts and snapshot binding for a new listener; reject stale worker results; preserve explicit phase when refreshing the same listener.
- `PhaseAnalyzerListener.java`: resolve the actual normalized JMX path from JMeter context and associate it with the execution.
- `RunSession.java`: retain runtime-only source-plan ownership. A new JMX cannot expose another plan's session even if its basename and persisted listener identity match. Existing execution evidence is retained, not deleted.
- `PhaseViewBindingTest.java`: seven regressions covering clear/rebind, same-listener refresh, running/completed phase switching, JMX persistence, queued workers and same-name/different-path isolation.

New bindings default to Steady Load. Phase selection is view state, not a newly introduced saved JMX property. Valid saved Manual configuration continues to restore. Calculations, HTML design and Excel format were not changed.

## Verification

Clean Java 17 Maven verify: **83 tests, zero failures/errors/skips**. Corrected built and installed JAR SHA-256: `8e17bab21216d8e1d5f7d47c7cc863dcec7d2e11605745242ecb54ea0d1bbe9a`.

| Actual targeted execution | Native HTTP samples | Per-group samples | Independent comparisons | Discrepancies |
|---|---:|---|---:|---:|
| Sequential | 1,693 | TG1 93; TG2 1,200; TG3 400 | 5,792 | 0 |
| Parallel | 3,598 | TG1 800; TG2 1,200; TG3 1,598 | 7,690 | 0 |

The 13,482 checks reconcile native JTL with scoped plugin samples, phase statistics, response codes, HTML data and seven-sheet Excel exports. Intentional mock failures are preserved; these are plugin validation runs, not application performance acceptance. A developer validator initially applied the parallel lifecycle assertion to the renamed sequential folder; its condition was corrected without changing results.

Actual Windows JMeter checks:

- Sequential: completed/running/not-started separation, TG2 running Dashboard/Live, TG3 automatic start without reselection.
- TG3 completed phase switching: Steady 300 samples versus Complete 400; Dashboard and Live selection synchronize.
- Different JMX with the same filename and listener identity opens with Steady selected, empty metrics/table/chart and disabled exports.
- Parallel individual Dashboard running selections, TG1/TG2 running Live, completed All separate graph series and three actual group rows for Product Search (240/360/478).
- A native editor-state test disabled TG2 while the popup remained open with a stale enabled row. Clicking TG2 was rejected; TG1 remained selected. Reopening showed TG2 disabled. Native mutation timestamp and four actual captures are retained.
- Manual seven-second edit saves immediately without Apply/Reset/Advanced Settings, survives tab switching and does not rewrite the captured native schedule. All keeps Automatic active and Manual disabled.
- Original master reopened with all nine groups disabled, no test started, listener Ready and Steady selected. JMeter remains open.

## Evidence and remaining limits

[Corrected-build screenshot collection](../screenshots/historical/pre-final-20261031/README.md). All prior 342 gallery image hashes were verified unchanged. The prior collection records the earlier build; its two mismatch images remain pre-fix reproductions, not evidence of an unresolved phase defect. Build hash, not the unchanged development version label, distinguishes the correction.

macOS GUI testing remains pending. The parallel TG3 Live screenshot was captured at completion, not while running. A new All-running capture was not repeated; the earlier All-running evidence remains available for unchanged graph logic. All Configuration's preview still says Waiting for capture/no data after completion rather than explaining the absence of a shared schedule; per-group schedule explanation and combined data remain available. These limitations prevent claiming exhaustive GUI certification.

Actual targeted JTL/report files and developer comparison logs remain outside the distributable repository under the local `outputs/phase-dropdown-fix-20261031` and `work/phase-dropdown-fix-20261031` directories. Existing downloadable nine-group sample reports are unchanged.
