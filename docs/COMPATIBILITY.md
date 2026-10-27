# Compatibility and evidence scope

Verified baseline: Apache JMeter 5.6.3, Java 17, Windows; optional Custom Thread Groups 3.1.1. Other versions/platforms are not claimed as fully tested. **All nine implementations retain Partial overall validation status.** Passing scoped calculations or regression tests does not establish full certification.

The published [sample report pairs](../examples/README.md) come from nine independent v1.1.1 local mock executions: 51,177 HTTP samples, 13,492 JTL/report comparisons and zero calculation discrepancies. All nine HTML reports passed offline browser controls/themes at 1366×768 and 1920×1080. All 63 worksheets and nine native charts were inspected in WPS. The older 256,643-sample batch is separate historical evidence and is not the population of the published examples.

| Thread Group | Provider | Native gradual ramp-down in supplied plan | v1.1.2 evidence | Overall |
|---|---|---|---|---|
| Standard | JMeter 5.6.3 | Absent | New short API/report run: 225 samples; native clock/selection regression | Partial |
| Ultimate | Custom Thread Groups 3.1.1 | Scheduled | New native clock/selection regression; prior API evidence reused | Partial |
| Stepping | Custom Thread Groups 3.1.1 | Discrete steps | New native clock/selection regression; prior API evidence reused | Partial |
| Concurrency | Custom Thread Groups 3.1.1 | Absent | New native clock/selection regression; prior API evidence reused | Partial |
| Arrivals | Custom Thread Groups 3.1.1 | Absent | New native clock/selection regression; prior API evidence reused | Partial |
| Free-Form Arrivals | Custom Thread Groups 3.1.1 | Decreasing arrival rate | New native clock/selection regression; prior API evidence reused | Partial |
| Open Model | JMeter 5.6.3 | Decreasing arrival rate | New short API/report run: 210 samples; native clock/selection regression | Partial |
| setUp | JMeter 5.6.3 | Absent | New short API/report run: 224 samples; native clock/selection regression | Partial |
| tearDown | JMeter 5.6.3 | Absent | New native clock/selection regression; prior API evidence reused | Partial |

Native phases come from configuration, not observed load. Missing native ramp-down remains unavailable. Open groups use arrival-rate units; no concurrent-user target is fabricated. setUp/tearDown follow their native JMeter lifecycle.

Targeted patch checks independently reconciled 659 actual samples and 2,432 report/timeline checks with zero calculation differences. All 72 live completed-window rate readings matched JTL completion timestamps. First-observed native clock anchors matched; Standard/setUp worker clocks varied by 2–3 ms. Open Model had two native-versus-listener user observations differing by one worker at distinct sample/callback instants. Exact continuous active-worker agreement remains unverified. Historical Arrivals evidence also contained a native arrival/completion-fact discrepancy despite preserving all actual HTTP samples.

Final interactive checks of corrected GUI Start/Save switching, save/reopen and completed-state rendering are still pending after computer-use activation/capture failed. The real native editor commit regression passes; final successful interactive execution was not recorded. Arbitrary complex/multiple-row schedules, dynamic changes, distributed execution and every duplicate-name edge case are not exhaustively certified. Unsupported/ambiguous schedules are disclosed or require explicit manual analytical windows.

See [validation details](VALIDATION.md) and [native configuration screenshots](../screenshots/README.md). A Partial result is not a claim that calculations failed, nor permission to invent unavailable phases.
