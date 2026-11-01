# Proposed v1.1.2 — approval pending

This document prepares a release; it does not publish one. Existing releases v1.1.0 and v1.1.1 remain unchanged. Version 1.1.2 is the next unused patch version and matches the accepted Maven implementation. Publishing v1.0.0 now would reverse version ordering.

## Proposed release notes

- Dynamic Thread Group discovery and shared selection across Dashboard, Phase Configuration and Live Metrics, preserving native tree order and group identity.
- Automatic configuration by default; valid per-group Manual settings persist dynamically. All uses Automatic and disables Manual.
- Isolated sequential and parallel lifecycle/metrics, actual group names in combined aggregate rows and separate combined graph series.
- Correct phase-selector binding when opening another JMX; Steady Load defaults and Complete Execution updates remain synchronized without stale execution data.
- Clear / Clear All capture reset, manual Refresh with auto-refresh disabled, and independent observed-user visibility.
- Accepted native Swing layouts, offline nine-section HTML and seven-sheet Excel reports retained without further visual redesign.
- Audited current gallery: 378 images plus 13 explicitly labeled defect-evidence images; current reports for all nine tested Thread Group implementations.

## Verification

- Java 17 / Maven clean verify: 95 tests, zero failures/errors/skips.
- Prior actual Windows validation: 38,308 independent JTL/report comparisons, zero discrepancies. One targeted Arrivals capture: 1,207 additional comparisons, zero discrepancies. These are distinct validation populations, not new performance acceptance runs.
- Cleanly rebuilt 82 production class entries are byte-identical to the installed, GUI-verified JMeter 5.6.3 artifact. JAR archive checksum differs from the earlier build because the archive was rebuilt; no production source changes were made during publication preparation.
- Optional Custom Thread Groups 3.1.1/common dependencies remain necessary only for third-party groups. Native groups do not require them. See installation and compatibility documentation.

## Known limitations to include in the release

Exact-build macOS desktop verification remains pending; automated macOS CI is not GUI verification. Known display limitations remain documented: cached enabled labels in an already-open group popup (selection still rejected), completed All preview/caption wording, combined HTML load-chart wording, and incomplete early observed-load history in combined sequential report captures pending investigation. Some WPS rightmost-column, fresh deletion-fallback and display-scaling evidence remains unavailable. Scoped numerical verification does not certify every GUI interaction.

## Candidate artifact

- Maven version / proposed tag: `1.1.2` / `v1.1.2`.
- Filename: `jmeter-performance-phase-analyzer-1.1.2.jar`.
- SHA-256: `7f5e5c7126eeb1027ca039fc3c285b6c3725e3202a45349561c2e988215db958`.
- Installation: one analyzer JAR in the complete JMeter 5.6.3 distribution's `lib/ext`, Java 17. Close JMeter before replacing an installed artifact; avoid duplicate analyzer copies.

GitHub Release creation and tag creation require separate user approval after source publication and CI verification. No release asset is attached by this source-only publication.
