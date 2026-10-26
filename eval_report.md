# Evaluation Report — Latency, Throughput & Session Isolation

Every number below is copied from a machine-written result file in [`eval/results/`](eval/results/). The
file is named next to each table. To regenerate them, run `./mvnw verify` for the in-process gates and
`scripts/run-load-test.sh` for HTTP. Targets come from [`QUALITY_CRITERIA.md`](QUALITY_CRITERIA.md).

## Hardware

| | |
| :--- | :--- |
| CPU | Intel Xeon @ 2.10 GHz, **2 vCPU** (1 thread per core), shared cloud VM |
| RAM | 8 GB (platform heap fixed at 1 GB, load generator 512 MB) |
| JVM | OpenJDK 21.0.12, generational ZGC (`-XX:+UseZGC -XX:+ZGenerational -Xms1g -Xmx1g`) |
| OS | Linux x86_64 (Ubuntu 24.04) |
| Load generator | `load-test` module, **on the same 2 vCPUs** as the server |

## Summary against the Day-1 ship gate

| Gate (QUALITY_CRITERIA §5) | Target | Measured | Result |
| :--- | :--- | :--- | :--- |
| Build | `./mvnw verify` green | 166 tests, 0 failures, 0 errors | ✅ |
| Golden scenarios | 30/30, each < 10 ms, mean ≤ 5 ms | 30/30, max **0.135 ms**, mean **0.038 ms** | ✅ |
| Engine p99 after ≥ 10k warm-up | < 10 ms | **0.010 ms** (p99.9 0.185 ms) | ✅ |
| Sub-10 ms pass rate | ≥ 99.9 % | **99.992 %** | ✅ |
| Engine capacity per core | ≥ 50,000 ops/s | **341,624 ops/s** (1 thread) | ✅ |
| Session isolation | 0 violations | **0 / 51,200 operations** | ✅ |
| HTTP throughput, 2 vCPU, load generator on the same box | ≥ 5,000 RPS | **15,865 RPS** (ZGC) / **18,310 RPS** (ParallelGC) | ✅ |
| HTTP 20,000 RPS | 20k sustained | **not reached on this box**: saturates at ~15.7k (see bottleneck analysis) | ⚠️ |

## 1. Golden scenarios — `scenario-results.json`

The 30 labelled scenarios in `stub-engine/src/test/resources/stub_match_scenarios.json` run in two ways.
`GoldenScenarioTest` runs them in-process, and `VirtualServiceHttpTest` runs them again over HTTP
through `POST /api/v1/engine/execute`. Each scenario uses its own session ids, so they can run in any
order.

| Category | Passed | What is covered |
| :--- | :--- | :--- |
| path_matching | 5/5 | literal vs `{var}`, nested variables, `*`, `**` tail, trailing slash |
| jsonpath | 7/7 | multi-field and nested paths, `[0]`, `[*]`, `$..` deep scan, regex, `gt`, `exists:false` |
| header_matching | 3/3 | media type with charset, bearer-token regex, case-insensitive names |
| query_matching | 1/1 | query constraint parsed from the path |
| state_mutation | 5/5 | create (client-supplied and generated ids), read, merge, delete then 404, list |
| session_isolation | 3/3 | B cannot read A; same id in two sessions; per-session id sequences |
| fallback | 6/6 | priority fallbacks, unknown route and wrong method give `NO_MATCHING_STUB` |
| **Total** | **30/30** | engine latency: mean **0.038 ms**, max **0.135 ms** |

## 2. Engine latency — `engine-latency.json`

`EngineLatencyBenchmarkTest` runs in its own JVM without the coverage agent. It first warms up with
20,000 requests: the 10,000-request `EngineWarmup` that the application also runs before it reports
ready, plus 10,000 requests of the benchmark's own mix. It then measures **400,000 requests on 4
threads** (2× the cores, so scheduling contention shows up in the numbers) across 64 sessions, each
preloaded with 100 accounts. The mix is 50 % state reads, 25 % creates, 10 % merges and deletes, and
15 % stateless matches and misses.

| p50 | p95 | p99 | p99.9 | max | mean | < 10 ms |
| :--- | :--- | :--- | :--- | :--- | :--- | :--- |
| 0.001 ms | 0.005 ms | **0.010 ms** | 0.185 ms | 17.1 ms | 0.007 ms | 99.992 % |

Throughput was **341,624 ops/s on one thread** and **442,413 ops/s contended** (both cores), with 0
unexpected 5xx responses.

The max comes from preemption and GC on an oversubscribed 2-core VM, not from matching. With G1 the
max reached 62–92 ms and the sub-10 ms rate fell to 99.94 %. GC logs showed every outlier lining up
with a G1 pause, which is why the platform runs on generational ZGC.

## 3. Session isolation — `isolation-results.json`

`ConcurrentSessionIsolationTest` runs **64 sessions on 16 threads**, and every session writes the
**same 200 entity ids** (`ACC-0` to `ACC-199`) with its own payloads. A single session's operations
are spread across many threads, and different sessions interleave in shuffled order. The test has
three phases: create everything; then concurrent reads, merges and deletes; then a full per-session
verification of holder, balance, merged status and entry count.

| Operations | Violations | Elapsed |
| :--- | :--- | :--- |
| 51,200 | **0** | 0.32 s |

A second test tears one session down while 4,000 reads hit the other seven. All the survivors
stayed intact, and the torn-down session returns 404. The same property is checked over HTTP by
`parallelPipelinesOverHttpDoNotPolluteEachOther`.

## 4. HTTP throughput — `http-*.json`

`load-test` is a dependency-free load generator. Each connection is a persistent HTTP/1.1 socket on a
virtual thread, sending the same request mix as above to `/virtual/**`, with `X-Session-Id` spread
over 64 sessions. Each run warms up with 50,000 requests and then measures for 60 seconds. With a
target rate it paces open-loop and measures from the *scheduled* send time, so overload shows up as
latency instead of being hidden by coordinated omission. Engine latency is read from the server's
`X-SVP-Engine-Latency-Ms` header, which means it is measured *during* the load.

| Run | Achieved RPS | Client p50 / p95 / p99 | Engine p50 / p99 | Unexpected status | Load gen CPU |
| :--- | :--- | :--- | :--- | :--- | :--- |
| Max throughput, ZGC | **15,865** | 3.9 / 8.0 / 11.4 ms | 0.003 / 0.047 ms | 0 | 29 % |
| Max throughput, ParallelGC | **18,310** | 3.4 / 6.4 / 10.0 ms | 0.002 / 0.041 ms | 0 | 33 % |
| Target 20,000 RPS, ZGC | 15,729 (saturated) | 6.2 s / 14.9 s / 17.3 s (queueing) | 0.003 / 0.048 ms | 0 | 29 % |
| Sustained 10,000 RPS, ZGC | 9,996 | 2.1 / 8.5 / 19.8 ms | 0.003 / 0.062 ms | 0 | 25 % |

### Bottleneck analysis: why 20k is not reached here

1. **The load generator takes about 30 % of the machine.** The server gets roughly 1.4 of the 2
   vCPUs.
2. **The engine is not the limit.** A JFR CPU profile taken during a max-throughput run attributes
   server CPU as follows: Tomcat 34.6 %, Spring MVC / filter chain 33.5 %, **engine 10.9 %**,
   Jackson 10.8 %, Micrometer observation 7.5 %, controller code 1.9 %. The engine alone sustains
   about 440k ops/s on these two cores, roughly 28× the HTTP rate.
3. **About 89 % of the per-request cost is the generic HTTP stack.** Roughly 1.4 server cores give
   15.9k RPS, or about 11k RPS per core. On the 8 vCPU / 16 GB reference box from the quality
   criteria, with the load generator on separate hardware, that ratio projects to well above 20k
   RPS. **This is an estimate, not a measurement**, and scaling across cores is rarely perfectly
   linear.
4. **Collector trade-off.** ParallelGC gives +15 % throughput (18.3k RPS) because ZGC does its work
   concurrently on the same two cores. ZGC stays the default because the engine's tail latency is
   the primary goal. Throughput-oriented deployments can switch collectors with a JVM flag.
5. **JIT warm-up.** With only a 10,000-request HTTP warm-up, the first ~2 seconds of a paced run had
   multi-second client latency while C2 was still compiling the Tomcat/Spring path, and engine
   latency stayed normal throughout. The harness therefore warms up with 50,000 requests by default.

Next steps for 20k on modest hardware (not done; see `BLOCKERS.md`): a Netty or raw-NIO ingress
that bypasses the Servlet and MVC chain for `/virtual/**`, observation disabled on that path, and
streaming JSON writes.

## 5. Drift simulation — `drift-results.json`

Each case uses 20,000 seeded samples (QUALITY_CRITERIA §3).

| Configured | Observed | Tolerance |
| :--- | :--- | :--- |
| error rate 5 % | 4.98 % | ±1 pp ✅ |
| error rate 10 % | 9.90 % (policy) / 10.05 % (through the engine) | ±1 pp ✅ |
| error rate 50 % | 50.52 % | ±1 pp ✅ |
| uniform 100 ± 20 ms | min 80, max 120, mean 99.84 | in bounds, mean ±5 % ✅ |
| gaussian μ = 50, σ = 10 ms | mean 49.96, σ 10.06, never negative | ✅ |

Injected faults never mutate state, and injected delay is reported separately from engine latency.
Over HTTP the delay is applied on the wire (`injectedLatencyIsAppliedOnTheWire`), and a 10 % error
rate holds end to end (`configuredErrorRateHoldsOverHttp`).

## 6. Sanitization — `sanitization-results.json`, `sanitizer-throughput.json`

Scoring is field by field over `sanitization_golden_dataset.json`: 40 recorded exchanges, 392 fields,
77 of them labelled sensitive. The look-alikes are deliberate: Luhn-failing 16-digit numbers, invalid
SSN areas, routing numbers, UUIDs, hashes, last-four values, `token_type`, base64 attachments.

| Category | Labelled | Recall | Precision |
| :--- | :--- | :--- | :--- |
| PCI | 21 | 100 % | 100 % |
| SSN / national id (`PII_CRITICAL`) | 7 | 100 % | 100 % |
| Secrets | 19 | 100 % | 100 % |
| Other PII | 30 | 100 % | 100 % |
| **Overall** | **77** | **100 %** | **100 %**, 0 leaks in the serialised output |

**Read this with care.** The golden dataset was written by the same author as the detector, so 100 %
is an upper bound and the reviewers' hidden dataset will score lower. To check for over-fitting,
`HoldoutProbeTest` adds 30 cases written *after* the detector was finished: PANs embedded in free
text, inside arrays and as JSON numbers, provider-prefixed keys, phone formats, plus look-alikes that
must survive, such as a Luhn-valid 16-digit *amount*. That probe originally found one miss, an
unformatted 9-digit SSN-shaped string under a neutral key. It is now masked and held for review, and
the probe passes 30/30. A further probe through `/recording/ingest` (PAN and SSN in the URL path and
query, card number in a header, cookies, nested contact arrays, OAuth tokens) leaked only one value:
`"ref": "536904399"`. That is a known limitation, explained in `BLOCKERS.md`.

Throughput on one core, with ~1.6 KB logs: **33,885 logs/s** sanitised, and **11,433 logs/s**
sanitised and turned into stubs. The target was ≥ 5,000.
