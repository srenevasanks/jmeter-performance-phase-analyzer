# Verified compatibility

Verified environment: Apache JMeter 5.6.3, Java 17, Windows, optional Custom Thread Groups 3.1.1. Other JMeter/Java/OS versions are not claimed as tested.

Nine independent full-profile local mock API runs produced 256,643 actual HTTP samples. Independent JTL comparisons found no statistical phase/aggregate metric discrepancies. Each HTML report passed offline browser controls/theme checks; each workbook had seven worksheets. These are plugin-validation results, not application-performance claims. Raw JTL/reports and machine-specific developer evidence are deliberately excluded from this repository.

| Thread Group | Implementation provider | Result | Phase/load limitation |
|---|---|---|---|
| Standard | JMeter 5.6.3 | Partial | Ramp-up and hold; no native gradual ramp-down. |
| Ultimate | Custom Thread Groups 3.1.1 | Passed | Three scheduled phases tested. |
| Stepping | Custom Thread Groups 3.1.1 | Passed | Native stepped user profile tested. |
| Concurrency | Custom Thread Groups 3.1.1 | Partial | Ramp-up and hold; no native gradual ramp-down. |
| Arrivals | Custom Thread Groups 3.1.1 | Partial | No native gradual ramp-down. All 27,300 HTTP samples retained, but native evidence had 27,300 arrival facts and 27,298 completion facts, with terminal active-count difference two. |
| Free-Form Arrivals | Custom Thread Groups 3.1.1 | Passed | Native arrival-rate schedule; arrivals are not concurrent users. |
| Open Model | JMeter 5.6.3 | Partial | Statistical metrics matched; 11 active-worker observations differed. Observer reconstruction was also inconsistent, so exact active-user accuracy at those instants remains unverified. |
| setUp | JMeter 5.6.3 | Partial | Actual lifecycle origin; no native gradual ramp-down. |
| tearDown | JMeter 5.6.3 | Partial | Actual lifecycle origin; no native gradual ramp-down. |

Passed applies to the executed validation scenarios, not every possible schedule. Partial distinguishes native schedule/evidence limitations from statistical calculation errors. Unsupported or ambiguous schedules require explicit manual phases rather than invented durations. Open workloads use arrival-rate units; they do not promise a concurrency target.

The subsequent native Swing UI revision preserved the calculation/report generator classes and passed the 50-test regression suite. It was exercised in actual JMeter with short local tests. Screenshot provenance and UI limits are documented in VALIDATION.md.
