# Released JAR installation verification

Verified on 25 October 2026, Windows, Java 17, Apache JMeter 5.6.3.

## Artifact identity

- Maven version: `1.1.0`.
- Public release tag: `v1.1.0`.
- Downloaded asset: `jmeter-performance-phase-analyzer-1.1.0.jar`, 211,513 bytes.
- SHA-256: `be7a2497c3822e8e6a39a3a24e9193c0324b87a9754daeb68c1cc5bb486a1e01`.
- Embedded Maven artifact metadata identifies the same version. This is the previously verified native UI package; it was not rebuilt or replaced during this review.

## Clean environment

Downloaded the official `apache-jmeter-5.6.3.zip` from Apache's archive and verified its published SHA-512 before extracting into a new directory. Copied only the downloaded release JAR into `lib/ext`. No Custom Thread Groups plugin, Plugins Manager, external mock service, Maven libraries or extra Jackson JARs were installed. Stock JMeter supplies Jackson annotations/core/databind 2.16.1.

Distribution SHA-512:

```text
387fadca903ee0aa30e3f2115fdfedb3898b102e6b9fe7cc3942703094bd2e65b235df2b0c6d0d3248e74c9a7950a36e42625fd74425368342c12e40b0163076
```

## Actual execution results

Ran the repository's local demo through the standalone JMeter CLI, using a separate temporary plan for each output mode. These are measured local sampler results, not application-performance claims. Automatic export was enabled only in the two export test copies; the published demo still has it disabled.

| Test | Native JTL samples | Failed samples | Output | Result |
|---|---:|---:|---|---|
| Default capture | 218 | 0 | No report files | PASS |
| Explicit HTML auto-export | 222 | 0 | Exactly one nonempty self-contained HTML file | PASS |
| Explicit Excel auto-export | 222 | 0 | Exactly one nonempty XLSX with seven worksheets | PASS |

All three JMeter processes exited normally. The successful test logs contain no missing-class or finalization/export errors. The HTML contains the real JMX filename, sampler label and captured data; Complete Execution reports 222 samples, matching its native JTL. The workbook contains the measured sampler label and all seven required sheets.

Separately constructed `PhaseAnalyzerGui` on the Swing event thread using only the clean distribution's libraries and the release JAR. It loaded successfully and identified itself as Performance Phase Analyzer. This is a headless component/class-loading check, **not** a new interactive desktop visual review. Existing actual screenshots and desktop limitations remain documented in [VALIDATION.md](VALIDATION.md).

The first sandboxed attempt was blocked by access to Java's Windows preferences registry before JMeter could initialize. Normal Windows execution resolved that environment restriction; the plugin was not changed. Developer harness/log-decoding corrections were kept outside this repository. Raw test outputs and local helper files are not distributed as source or dependencies.

## CI and repository review

Both initial public Java 17 Maven workflow runs passed with 50 tests, zero failures/errors/skips. The workflow action versions were then updated to current official releases to remove Node 20/setup-java v4 deprecation warnings; the follow-up run is available from the repository's Actions page.

The README includes actual Dashboard, Phase Configuration and Live Metrics screenshots, plus a clearly qualified dark ready-state screenshot. Relevant repository topics were added. No change was made to the separate jmeter-html-report-plugin repository, the released plugin binary, or Plugins Manager.
