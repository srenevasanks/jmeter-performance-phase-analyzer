# Final screenshot audit — 1 November 2026

**Overall: Partial; not ready for an unqualified compatibility publication.** No production Java, version, tag, commit, release or remote repository was changed by this audit.

All 390 original current-gallery images were opened and visually inspected individually. The byte-identified review inventory and backup are retained locally under `work/screenshot-audit-20261101`. Decode/hash/similarity scanning found no corrupt, blank or exact duplicate images. 301 perceptual-similarity candidates were reviewed as actual distinct forms/report sections, not automatically deleted.

## Counts and organization

Original: 390 images. Final current: 378; defect evidence: 13; total 391 including one new targeted capture. All 391 were visually inspected. Correct original current images retained: 377 (including the two renamed images). One missing view recaptured. Two filenames were corrected to distinguish current ramp-down from the selected Steady aggregate. No image bytes were edited. No images were deleted; zero duplicates removed. Supplemental runs 19 and 20 were consolidated into Parallel and Sequential folders with explicit execution-number filename prefixes. Distinct executions are not interchangeable evidence.

The prior 30 historical pre-final images remain explicitly historical, separately from these current-build images. Existing older historical collections and published representative aliases were preserved. The earlier removal of 378 superseded files is not counted as a deletion in this audit.

[Current gallery](../screenshots/validation/README.md) · [Known-defect images](../screenshots/defect-evidence/README.md) · [Actual sample reports](../examples/README.md)

## Nine-group visual coverage

All nine have correct native configuration, Automatic, Manual, Dashboard ready/running/completed, completed Live Metrics/aggregate, running graph, phase summaries, HTML light/dark and nine section views, and eight WPS captures covering seven sheets plus charts. WPS captions and populations match each group's own workbook; no unrelated dialogs or wrong-group substitutions were found. Rightmost wide-table columns are not all visible in the existing WPS captures; numerical verification is separate from this visual limit.

| Group | Original images | Complete samples | Native phase limits / missing evidence |
|---|---:|---:|---|
| Standard | 33 | 1,765 | No native gradual ramp-down |
| Ultimate | 34 | 1,688 | All three configured phases |
| Stepping | 33 | 1,748 | Discrete stepped users, not a smooth curve |
| Concurrency | 32 | 1,767 | No native gradual ramp-down; 11 tail samples remain in Complete Execution |
| Arrivals | 31 | 1,620 | Arrival-rate profile, not users; running aggregate capture targeted separately below |
| Free-Form Arrivals | 36 | 1,389 | Arrival-rate phases, not users |
| Open Model | 34 | 1,400 | Arrival-rate phases, not users |
| setUp | 33 | 1,777 | Native setup lifecycle; no gradual ramp-down |
| tearDown | 33 | 1,777 | Native teardown lifecycle; no gradual ramp-down |

The original 95 automated tests and 38,308 independent numerical comparisons passed with zero discrepancies. They were not rerun or reinterpreted as proof of every GUI interaction. Mock errors are intentional; these are plugin validation results, not application performance acceptance results. Provenance is the recorded final installed build `local-final-20261031-A64E91CE2999`, not version text in the JMX display name. Pixels alone cannot prove binary provenance; the build/capture records and matching installed JAR hash provide that association.

## Functional scenario evidence

| Scenario | Visual conclusion / scope |
|---|---|
| Dynamic selection | First enabled, all-disabled, disabled choices and fallback shown. Native open-popup state proves TG2 disabled; post-click view supports rejection. Cached popup label remains defective. |
| Sequential | TG1 Completed, TG2 Running, TG3 Not Started and later automatically Running show isolated populations. Original 1,234 and supplemental 1,931 samples are separate executions. |
| Parallel | Individual and All views retain names, independent metrics and separate colored graph series. Original 2,073 and supplemental 4,793 populations are separate. |
| All | Actual group names in aggregate rows, distinct series and Manual disabled. Completed configuration preview is defective. |
| Phase switching | Steady 100 to Complete 200 changes KPIs and selected row. Progress caption remains defective. Complete screenshot is linked as defect evidence, not removed from the audit record. |
| Clear / Clear All | Ready after completed clear; active clear resets capture, new samples appear. Native 2,391 versus post-clear 48 (22/26) is documented, not missing samples. Pre/post export file-hash immutability proof absent. |
| Refresh | Frozen values with auto-refresh off, successive manual Refresh updates and auto-refresh resume are shown. Interaction logs support what static images cannot independently prove. |
| Observed users | Panel present/absent with checked/unchecked states; discrete measurements, not continuous authoritative traces. |
| JMX reopening | Ready/Steady and empty metrics after populated execution; original master reopened. |

## A. Confirmed plugin display defects

1. Disable TG2 natively while the combo popup stays open: its cached label still appears enabled. Selection is rejected, so this is immediate visual-state refresh failure, not unsafe sample reassignment. Native-state record: `TG2 enabled=false; EDT=true`; post-selection evidence is separate.
2. Select All after capture completes: Automatic preview says “Waiting for capture / No data has been collected” despite populated metrics. Manual correctly remains disabled.
3. Complete a group, select Complete Execution: KPIs correctly change to 200 from Steady 100, but progress caption can retain “Steady Load”. Other completed views say “Gap / unclassified” with zero unclassified samples. This is misleading captioning, not the previously fixed selected-metric calculation bug.
4. Combined HTML load chart marked “Observed load only” still includes a configured-users legend and manual-duration explanation although detection is Automatic. The combined sequential observed trace also lacks earlier groups' history. This latter observation requires snapshot/aggregation investigation before attributing it to a calculation defect. Individual report traces retain their own history.

These findings were reported before any production change. No code fixes were attempted in this evidence-only audit. See the unchanged [13 defect images](../screenshots/defect-evidence/README.md). Other captures that expose the same caption issue remain useful for distinct group populations and must not be read as clean visual passes.

## B. Evidence gaps

- Selected-group deletion fallback lacks a fresh interactive capture; automated and historical coverage are not fresh GUI proof.
- Existing WPS images do not expose every rightmost wide aggregate column. All seven-sheet data comparisons remain a separate passing result.
- Clear export-file immutability lacks a before/after hash capture.
- Static screenshots do not alone prove selection rejection, timing or repeated-run isolation; retained interaction records and tests supply narrower supporting evidence.
- Minor right-edge axis label clipping in combined Swing graphs remains a readability limitation at the captured size.

## C. Cross-platform verification pending

Actual macOS GUI verification was not available and is not claimed. Fresh comprehensive dark native-JMeter and alternative display-scaling checks remain pending. HTML light/dark browser evidence at 1366×768 and 1920×1080 is separate from native Swing scaling evidence. No pixel-perfect match to approved mockups is claimed.

## Documentation, artifacts and publication gate

README, per-folder galleries, compatibility and current validation documentation now link to this audit. Installation and usage instructions and nine sample HTML/XLSX/JTL sets are retained. Local link counts, workbook/package checks and final artifact hashes are recorded in the audit verification output. No existing report or JTL was overwritten by the targeted recapture.

Publication awaits review of the confirmed display defects and remaining evidence limits. Do not describe all GUI behavior or macOS compatibility as fully validated. No GitHub operation was performed.

## Targeted Arrivals recapture

Only the isolated 90-second Arrivals profile was executed, using the same authorized local mock API. The running Steady aggregate image was saved and reopened: 1,258 Steady samples and all five API labels are visible. The final new population is 1,620 (220 ramp-up / 1,400 Steady); all independent native-JTL/HTML/Excel/time-series comparisons pass with zero discrepancies. This execution is distinct from the original 1,620-sample example and does not overwrite it. Developer-only recording exported its evidence outside the repository report directories. Original tests were not rerun.

## Final verification results

- 662 local Markdown/image link occurrences checked; zero broken targets. 32 old link occurrences replaced or removed while galleries were corrected; these were path updates, not proof that the original gallery had broken links.
- Nine sample HTML files: embedded snapshots parse and all nine sections present. Nine XLSX files: ZIP integrity valid, seven worksheets each. Prior offline-browser evidence is retained; this audit did not claim a new browser run.
- Targeted recapture: 1207 independent checks, zero discrepancies; prior 38,308 checks remain a distinct baseline.
- Installed and built JAR SHA-256 match the verified A64E91CE2999 build. Original master hash unchanged.
- Git HEAD unchanged at `dd8e9885ab2d5b6ab371ec77f07785d5bb3336a0`; worktree remains dirty from prior work plus this audit. No commit/push/tag/release.
- Screenshot changes: 2 semantic renames; 13 images moved to defect evidence; 12 non-defect supplemental images consolidated into Sequential/Parallel; 1 new actual running Arrivals capture; 0 image deletions; 0 duplicate deletions.
- Documentation changes: concise README audit link, per-folder galleries, current validation and compatibility notices, final audit and defect index. Installation and sample-report paths retained.
- Selected-group deletion, WPS rightmost visual columns, Clear hash evidence and fresh display-scale/native-dark checks remain missing. macOS actual GUI remains pending.
