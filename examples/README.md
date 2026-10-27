# Actual local mock-service sample reports

These nine HTML/Excel pairs are **unaltered copies of their corresponding v1.1.1 executions**, not regenerated v1.1.2 results. Each group ran independently in JMeter 5.6.3 / Java 17. The aggregate evidence is 51,177 actual HTTP samples and 13,492 independent JTL/report checks with zero calculation differences. These are plugin-validation examples, not production or real-application performance claims. Intentionally injected failures, latency spikes and recovery are genuine parts of the mock workload.

GitHub displays HTML source. Download a report.html and open it locally to use phase selection, search, charts and themes. Download report.xlsx to inspect the seven sheets in Excel, WPS or another compatible spreadsheet application. Raw JTL and developer scripts are retained locally and are not committed.

| Group | HTML | Excel | Actual samples | Configured up / steady / down seconds |
|---|---|---|---:|---|
| Standard | [HTML](standard/report.html) | [Excel](standard/report.xlsx) | 5,950 | 20 / 100 / absent |
| Ultimate | [HTML](ultimate/report.html) | [Excel](ultimate/report.xlsx) | 5,844 | 20 / 80 / 20 |
| Stepping | [HTML](stepping/report.html) | [Excel](stepping/report.xlsx) | 5,904 | 20 / 80 / 20 |
| Concurrency | [HTML](concurrency/report.html) | [Excel](concurrency/report.xlsx) | 6,046 | 20 / 100 / absent |
| Arrivals | [HTML](arrivals/report.html) | [Excel](arrivals/report.xlsx) | 5,550 | 20 / 100 / absent |
| Free-Form Arrivals | [HTML](free-form-arrivals/report.html) | [Excel](free-form-arrivals/report.xlsx) | 4,996 | 20 / 80 / 20 |
| Open Model | [HTML](open-model/report.html) | [Excel](open-model/report.xlsx) | 5,000 | 20 / 80 / 20 |
| setUp | [HTML](setup/report.html) | [Excel](setup/report.xlsx) | 5,945 | 20 / 100 / absent |
| tearDown | [HTML](teardown/report.html) | [Excel](teardown/report.xlsx) | 5,942 | 20 / 100 / absent |

Ultimate, Stepping, Free-Form Arrivals and Open Model have native decreasing-load portions in these configurations. Stepping is discrete. Standard, Concurrency, Arrivals, setUp and tearDown have an abrupt scheduled end; their ramp-down sheets explicitly remain unavailable. setUp and tearDown were each executed alone using their real native lifecycle classes.

Standard/Ultimate/Stepping/Concurrency use closed-user workloads. Arrivals, Free-Form Arrivals and Open Model schedule arrivals/sec; arrival rate is not a concurrent-user count. The closed-user example peak was 100; the open example plateau was 50 arrivals/sec. These representative schedules are approximately two minutes, not the older 30-minute validation batch.

Complete Execution retains eligible samples outside analytical windows. The Standard run has 3 such samples, Concurrency 109 and setUp 2; those records are not lost or placed into fabricated phases. The remaining runs have zero unclassified samples.

**Overall validation remains Partial for every implementation.** Statistical agreement does not certify every native lifecycle, dynamic schedule or GUI interaction. Patch 1.1.2 adds identity/live-rate fixes and scoped automated/native evidence; the supplied reports illustrate the unchanged approved exporter designs, not proof that final interactive patch verification is complete. See [compatibility](../docs/COMPATIBILITY.md), [validation](../docs/VALIDATION.md) and [genuine screenshot provenance](../screenshots/README.md).

For a safe runnable example without any service, use [local-demo.jmx](local-demo.jmx). The sample report folders contain reports only and do not depend on the private or temporary paths used during developer validation.
