# Quality Criteria — Enterprise Service Virtualization Platform

Written **before** any implementation code. Every criterion below has a metric, a target, how it is
measured, and the automated test that enforces it. If a gate fails, the build is not shippable —
`eval_report.md` must report the real number, not a rounded-up one.

---

## 1. Engine performance & concurrency

### 1.1 What "latency" means here

* **Engine latency** = `System.nanoTime()` from the moment the engine receives an already-parsed
  `VirtualRequest` until it returns the `VirtualResponse` (stub lookup + predicate evaluation +
  session-state read/write + response template rendering). It excludes HTTP parsing/serialisation
  and excludes any *injected* drift delay, which is reported separately (`injected_delay_ms`).
* **End-to-end latency** = client-observed round trip in the HTTP load test. Reported, not gated,
  because it depends on the load generator sharing the same CPUs.
* Percentiles are computed from the **full sorted sample** (no histogram approximation), after a
  warm-up of **≥ 10,000 requests** so JIT compilation is not counted.

### 1.2 Latency targets

| Metric (engine latency, mixed workload) | Ship gate | Aspiration | Enforced by |
| :--- | :--- | :--- | :--- |
| p50 | < 1 ms | < 0.05 ms | `EngineLatencyBenchmarkTest` |
| p95 | < 5 ms | < 0.2 ms | `EngineLatencyBenchmarkTest` |
| **p99** | **< 10 ms (hard)** | < 1 ms | `EngineLatencyBenchmarkTest` |
| Sub-10 ms pass rate | ≥ 99.9 % of requests | 100 % | `EngineLatencyBenchmarkTest` |
| Each of the 30 golden scenarios | < 10 ms, mean ≤ 5 ms | mean < 0.5 ms | `GoldenScenarioTest` |
| Engine capacity (in-process) | ≥ 50,000 ops/s per core | ≥ 200,000 ops/s per core | `EngineLatencyBenchmarkTest` |

The mixed workload is 50 % state reads, 25 % creates (state writes), 10 % updates/deletes,
15 % non-state matches and misses, spread over 64 concurrent sessions and more threads than cores
(so scheduling contention is included).

### 1.3 Throughput targets (HTTP, running application)

* **Target:** 20,000 RPS sustained for 60 s with engine p99 < 10 ms and 0 % unexpected errors
  (non-injected 5xx or wrong status) on reference hardware of 8 vCPU / 16 GB.
* **Minimum shippable on a 2-vCPU dev box with the load generator co-located:** ≥ 5,000 RPS
  sustained, 0 % unexpected errors, engine p99 < 10 ms measured *during* the load.
* Hardware (CPU model, cores, RAM, JVM flags) is always printed next to the number. If 20k is not
  reached, the report states the bottleneck with evidence (CPU saturation, client vs server share).

### 1.4 Session isolation — how cross-contamination is prevented

* **Isolation by construction, not by convention.** State lives in
  `sessionId → collection → key → value`. Every state operation takes the session id as its first
  key; there is no API that reads or writes state without one. A stub can never address another
  session's data, even with a crafted template.
* **Immutable stored values.** Values are deep-copied into unmodifiable structures on write, so a
  response object handed to one caller can never be mutated into another caller's state.
* **Lock-free reads, atomic writes.** Per-session `ConcurrentHashMap`s; read-modify-write
  operations (merge, id generation) use atomic `compute`/`AtomicLong`, so parallel steps inside the
  *same* pipeline are also safe.
* **Deterministic ids per session.** Generated ids (`{{generate.id:ACC}}`) come from a counter
  owned by the session, so two pipelines both get `ACC-1001` without colliding or influencing each
  other, and re-running a pipeline is reproducible.
* **Lifecycle.** Explicit create/teardown via API; teardown removes the whole session map in one
  operation. Idle sessions expire after a TTL (default 30 min) so abandoned CI runs cannot leak.
* **Bounded.** Max sessions and max entries per session are enforced and fail fast with a clear
  error rather than degrading the shared JVM.

**Isolation gate: 100 %.** `ConcurrentSessionIsolationTest` runs ≥ 50 sessions on ≥ 16 threads,
all writing the *same* entity ids with session-specific payloads, then verifies every read.
A single cross-session read, lost write, or 200 on another session's key fails the build.

### 1.5 Matching correctness

* 30 labelled scenarios (`stub_match_scenarios.json`): **30/30** must match the expected stub id,
  status code and asserted body fields.
* Deterministic precedence: literal path segment > path variable > single-segment wildcard >
  tail wildcard; then explicit priority; then number of constraints; then registration order.
* A request that matches nothing returns `404 NO_MATCHING_STUB` with near-miss diagnostics — never a
  500.
* Invalid stubs (bad JSONPath, bad regex, bad template expression) are rejected **at registration**
  with a 400 and a reason; nothing is compiled lazily on the request path.

---

## 2. Sanitization accuracy targets

Evaluation is **field-level** over a labelled golden dataset that includes look-alikes
(non-Luhn 16-digit order numbers, 9-digit invoice numbers, UUIDs, hashes, last-four values,
amounts, `token_type`, `auth_code`, etc.).

* **TP** = sensitive field masked. **FN** = sensitive value left verbatim anywhere in the generated
  stub config. **FP** = non-sensitive field altered.

| Category | Recall | Precision |
| :--- | :--- | :--- |
| PCI — PAN, CVV, PIN, track data | **100 % (hard gate)** | ≥ 95 % |
| SSN / national id | **100 % (hard gate)** | ≥ 95 % |
| Secrets — passwords, bearer / JWT / API keys, auth headers | ≥ 99 % | ≥ 90 % |
| Other PII — email, phone, DOB, person name, street address | ≥ 98 % | ≥ 95 % |
| **Overall** | **≥ 99 %** | **≥ 95 %** |

Rationale: the costs are asymmetric. A missed card number or SSN is a compliance breach; an
over-masked field only makes one stub less realistic. The detector is therefore tuned to fail
closed.

Additional hard gates:

* **Zero leaks:** the entire serialised output (stubs, summary, review queue) must not contain any
  original sensitive value as a substring.
* **Non-sensitive fields survive unchanged** in value *and* type (e.g. `amount: 100.0` stays a
  number).
* Credentials in headers (`Authorization`, `Cookie`, `Set-Cookie`, API-key headers) never reach a
  generated stub.
* **Throughput:** ≥ 5,000 typical (~2 KB) logs per second per core.

### 2.1 Confidence threshold — auto-publish vs manual review

Each detection carries a confidence in [0, 1]:

| Confidence | Typical evidence | Action |
| :--- | :--- | :--- |
| **≥ 0.90** | Validator-confirmed (Luhn + card-network prefix, SSN area/group/serial rules, JWT structure, known key prefixes such as `sk_live_`/`AKIA`) or key name and value shape agree | Mask automatically |
| **0.50 – 0.90** | Single weak signal: high-entropy string under a neutral key; sensitive-sounding key whose value does not validate | **Mask anyway** (fail closed) **and** add to the review queue |
| < 0.50 | No credible signal | Leave unchanged |

**A generated stub set auto-publishes only if** every detection is ≥ 0.90, the review queue is
empty, and every stub compiles. Otherwise it is stored as `PENDING_REVIEW` and stays out of the
live registry until a human approves it (API or UI). Recorded stubs never overwrite a manually
authored stub.

---

## 3. Drift (resilience) simulation accuracy

* Configured error rate *p* over ≥ 20,000 requests: observed rate within **±1 percentage point**
  (≈ 4σ for p = 10 %).
* Latency with base *L* and uniform jitter *J*: every sample in [L − J, L + J] (floored at 0),
  observed mean within ±5 % of *L*.
* Injected faults happen **before** stub execution, so a faulted request never mutates state.
* Injected delay is applied off the engine thread (virtual thread sleep) and is excluded from
  engine latency.

---

## 4. API and UI

* Every capability (stubs, sessions, state, recording, review, drift, traffic) is reachable through
  the REST API; the UI calls only those public endpoints.
* Every control-plane endpoint has an integration test for the happy path and for invalid input.

---

## 5. Day-1 ship gate (definition of done)

The engine is shippable only when **all** of these hold:

1. `./mvnw verify` is green.
2. 30/30 golden scenarios pass; each < 10 ms; mean ≤ 5 ms.
3. Engine benchmark after ≥ 10k warm-up: p99 < 10 ms, sub-10 ms pass rate ≥ 99.9 %,
   ≥ 50,000 ops/s per core.
4. 0 session-isolation violations under concurrency.
5. Numbers in `eval_report.md` come from the test output, not from hand-typed estimates.
