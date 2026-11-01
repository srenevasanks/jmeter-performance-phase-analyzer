# Official release v1.0.0

First official public release, explicitly reset to 1.0.0 by the maintainer. The development-release entries and tags are removed only after this release and its artifact are verified. Source commits and branches are preserved.

## Release notes

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

- Maven version / tag: `1.0.0` / `v1.0.0`.
- Filename: `jmeter-performance-phase-analyzer-1.0.0.jar`.
- SHA-256: see the release-attached `SHA256SUMS.txt`.
- Installation: one analyzer JAR in the complete JMeter 5.6.3 distribution's `lib/ext`, Java 17. Close JMeter before replacing an installed artifact; avoid duplicate analyzer copies.

The prior source-publication CI passed on Windows and failed on Linux/macOS. Those failures remain unresolved and are disclosed; this version-only release does not claim that CI or actual macOS GUI verification passed.
