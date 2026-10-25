# Validation scope

The 1.1.0 native UI package passed 50 Java/Maven tests before publication preparation. The repository retains those tests; only a machine-specific test JMeter-home reference was replaced with a checked-in, attributed SaveService properties fixture. That publication preserved the verified production implementation. Version 1.1.1 adds the approved Excel-only redesign and focused tests; calculations, capture, detection, HTML and Swing remain unchanged. See [Excel verification](EXCEL-REPORT.md).

Actual Apache JMeter 5.6.3 / Java 17 GUI tests used a six-user five-minute local run and a two-user ninety-second run. Ready/running/completed states, automatic/manual configuration, aggregate search/scrolling, native HTML/Excel Save dialogs, JMX save/reopen and capture stop/restart were exercised. Stop Capture left JMeter running; a new capture started separately.

README light screenshots show actual running JMeter after the runtime-listener bridge fix. The dark ready screenshot documents the initial native styling pass, preceding final runtime/header/compact-label fixes. These are application captures, not generated mockups, and not all represent the final build byte-for-byte.

Actual small-window verification reached 1366×768. The maximized desktop window was 1920×1032 due to the Windows work area. Swing component rendering at 1920×1080 passed; exact 1920×1080 desktop and a fresh final-build dark desktop pass remain pending. Native export error handling is tested in code; a forced disk-error popup was not exercised in actual JMeter.

See COMPATIBILITY.md for independent full-run scope and unresolved native load evidence. CI executes local deterministic samplers and embedded JMeter engines; it does not run a 30-minute load against a public service. CI success does not establish cross-platform desktop rendering quality.
