# Validation provenance — v1.1.2

## Build and patch regression evidence

The Java 17 Maven suite contains **62 tests**, including 14 embedded native JMeter integration executions. All nine installed Thread Group classes have identity/selection regression coverage and independently read native scheduler-clock fixtures. The full clean verify command is used locally and in GitHub Actions. See the repository Actions run for the published commit rather than assuming a local pass proves CI.

Thread Group editors clear custom properties. Weak node ownership preserves identity across edit/rename/disable, prevents copied/replaced nodes from inheriting another node's identity, and exposes deleted selections as unavailable. Start synchronization refreshes configuration before cloning; a post-Save callback handles the second native commit in Save-before-run. The regression uses JMeter's real Open Model editor commit method. Selection revisions prevent stale asynchronous snapshots from overwriting a newer group choice. Swing tree access stays on the EDT.

Live throughput counts accepted sample completions across up to five fully completed seconds. Initial/current partial seconds are excluded; idle seconds remain in the denominator; startup is unavailable until a full interval exists; completion freezes the cutoff. Group/population reads do not consume data. Exported calculation code is unchanged.

## Targeted native API evidence

Three short independent JMeter 5.6.3 / Java 17 non-GUI local mock executions produced:

| Group | Actual samples | Ramp-up / steady / ramp-down samples | JTL/report checks | Differences |
|---|---:|---|---:|---:|
| Standard | 225 | 38 / 187 / 0 (absent) | 752 | 0 |
| Open Model | 210 | 20 / 170 / 20 | 928 | 0 |
| setUp | 224 | 37 / 187 / 0 (absent) | 752 | 0 |

Counts, nearest-rank percentiles, means, min/max, population standard deviation, error %, rates/bytes, phase totals, codes and timeline data were independently calculated from native JTL and reconciled with HTML/Excel. Seven sheets, frozen panes, report population parity and all five API labels were checked. All 72 live-rate observations matched independently calculated JTL completion windows. All three HTML reports passed offline browser controls, phases/search/sorting, chart legends/tooltips, both themes and both viewport sizes without JavaScript errors or external requests.

The final production JAR's 74 production class entries matched the artifact used for these native runs. Maven version is 1.1.2. No developer evidence recorder is bundled. A clean rebuild may have different ZIP timestamps; compare verified class/template entries rather than assuming bitwise reproducibility without a configured reproducible-build timestamp.

These non-GUI runs use finalized fixture identities and do **not** establish interactive correctness of the final Start/Save hook. An initial actual GUI attempt reproduced empty selected data despite captured native samples; that evidence is preserved locally. After correction, capture/activation failed with “foreground window did not report a process id,” including a recovery attempt. Final interactive switching, save/reopen and completed-state rendering remain unverified. No successful new v1.1.2 GUI screenshots are claimed.

Native Open Model user counts differed by one worker in two sample/callback observations. Standard/setUp per-worker epochs varied by 2–3 ms. These limits are retained, not converted into passing comparisons. Statistical reconciliation alone does not prove exact continuous worker-count accuracy.

## Published screenshots and sample reports

The [36 curated screenshots](../screenshots/README.md) and [nine report pairs](../examples/README.md) are genuine v1.1.1 evidence. The nine independent executions captured **51,177 actual samples**; **13,492 independent checks found zero calculation discrepancies**. All nine HTML files passed offline browser controls in both themes at 1366×768/1920×1080. WPS opened all seven sheets and the native chart of all nine workbooks. The reports are copied byte-for-byte from their original group folders. They have not been regenerated, mixed or relabeled as patch executions.

The Manual screenshot is an unapplied preview. JMeter title bars were cropped solely to remove filesystem paths, and PNGs losslessly optimized; data/content was not retouched. The original complete evidence remains local. Existing UI/HTML/Excel designs were preserved. Older live-rate labels in screenshots predate the completed-second patch.

No nine-group load-test batch was rerun for this publication. Raw JTL, complete screenshot collections, scripts, local paths and developer recorder binaries remain outside the public source. Runtime dependencies are documented in the main README. Historical [clean-install verification](CLEAN-INSTALL-VERIFICATION.md) is explicitly for v1.1.0; [Excel redesign verification](EXCEL-REPORT.md) describes the preserved v1.1.1 exporter.

Overall status for every group remains **Partial**. The remaining interactive/lifecycle checks must be reviewed before claiming Plugins Manager readiness. This release does not submit to Plugins Manager.
