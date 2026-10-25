# Calculation and phase methodology

For eligible emitted samples, response time is `SampleResult.getTime()` in milliseconds. Window assignment defaults to start timestamp within `[start,end)`. Strict mode additionally requires completion `<= end`. A completion exactly at the end is allowed if start is inside. Samples starting at the end belong to a subsequent window, not the previous one. Overlapping analytical views may include the same sample independently; counts within one view do not double count it. Initial delays and schedule gaps are retained in Complete Execution and may remain unclassified by phase.

Runtime completion is captured separately from `SampleResult.getEndTime()` in the normalized `endTime` column. It is not reconstructed from elapsed response time: JMeter elapsed may exclude idle/paused time. Ordinary JTL without this optional column uses start + elapsed and emits an explicit warning about strict-policy uncertainty for paused results.

| Metric | Definition |
|---|---|
| Mean | Sum of elapsed times / N, calculated incrementally |
| Median, P90, P95, P99 | Exact nearest rank: sorted value at 1-based `ceil(p*N)`; median uses lower middle for even N |
| Standard deviation | Population `sqrt(sum((x-mean)^2)/N)` using Welford |
| Errors | Failed samples / N * 100 |
| Rate | N / selected effective window seconds, including idle intervals |
| Received / sent KB/sec | Corresponding byte sum / 1024 / window seconds |
| Average response size | Received byte sum / N |
| APDEX | `(successful <= T + 0.5 * successful in (T,F]) / N`; every failed sample is frustrated |
| Latency / connect | Mean of reported JMeter timing fields; missing source fields make the whole metric N/A |
| Active load | Independent time-weighted group polling; unknown gaps are excluded and coverage disclosed |

Zero samples give N/A for response statistics, error percentage and APDEX. A positive-duration window with no samples has a zero sample rate. A zero-duration window has N/A rates. Missing byte/timing fields propagate N/A rather than zero. TOTAL uses all underlying eligible samples, never an average of label averages. For HTTP and other samplers the rate is sampler-record rate, not guaranteed HTTP network-request rate (redirects/retries may be inside one sampler). Transaction mode reports business transaction completion records. Arrival rate is not measured by completion records. This window denominator differs from JMeter Aggregate Report's elapsed sample interval; nearest-rank percentiles are explicitly specified rather than relying on configurable JMeter percentile settings.

Only JMeter-emitted records are analyzed. Transaction parent events are recognized by the verified `SampleEvent.isTransactionSampleEvent()` and `TransactionController.isFromTransactionController()` APIs. Generate Parent Sample children are unpacked recursively only when traversing transaction results. Non-parent controller events and child sampler events remain separate. Nested transactions are independent business records in Transactions; Requests excludes all transaction records. A scheduler may end an unfinished transaction without emitting its final parent; this does not justify inventing a parent sample.

JMeter 5.6.3's scheduler-stop cleanup can emit a parent before setting the usual transaction response-message marker. In that path, the analyzer also verifies object provenance: the synthetic parent's descendants contain the exact `JMeterContext.getPreviousResult()` sampler object, and the parent itself is a different object. Ordinary sampler notification sets previousResult to the sampler result itself, so HTTP redirects with subresults remain sampler records. This fallback was checked against `JMeterThread.executeSamplePackage` and `doEndTransactionSampler` and tested against real scheduler-stopped parent/nested runs. It does not infer identity from labels.

Configured phase boundaries and observed workload are separate. New UI Automatic mode uses configured group schedules only. Legacy observed-load candidate utilities remain internal/legacy analysis features; they do not change phase classification. Multiple configured steady plateaus require explicit selection. Manual mode provides time bands only. Absolute epoch boundaries explicitly override the steady analysis window and any resulting analytical overlap is disclosed.

Snapshot effective windows are clipped to the capture cutoff; configured windows are preserved. Multiple intervals are normalized to a disjoint union, counted once, with rate denominators equal to union duration. Complete Execution retains all eligible captured records, including schedule gaps and events outside selected phases; the unclassified count is disclosed. Future and absent phases remain Not started/Not configured/Unavailable rather than acquiring invented data.

Capture barriers include only persisted accepted records at their cutoff. Stop Capture drains previously accepted records, freezes its session and ignores later events without describing them as queue loss. New capture sessions do not replay prior events. An operation completing after restart can carry an earlier start timestamp; it remains a newly delivered event with its real timestamps. Partial-report phase status and effective duration are clipped consistently.

Known parallel groups are registered at test start so delayed groups cannot produce a false early common steady window. Sequential/setUp/tearDown origins use actual first-thread callbacks adjusted by configured initial start; jitter is possible. Open Model uses JMeter's native test-start reference. Combined aggregate mean/peak concurrency remain N/A where synchronized global coverage is unavailable. Arrival-rate configuration, observed users and completion throughput are different quantities and units.
