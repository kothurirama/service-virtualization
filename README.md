Implementation: Java 21 + Spring Boot 3.x | UI: Angular

# Enterprise Service Virtualization Platform

This platform stands in for expensive, rate-limited or fragile dependencies (payment gateways, core
banking APIs, mainframe services) during CI.

* Virtual services match requests in microseconds.
* Each pipeline gets its own private, stateful **session**.
* Recorded production traffic is sanitized of card numbers, SSNs and credentials before it becomes
  a stub.
* Latency, jitter and failures can be injected to rehearse a degraded downstream.

Everything is available through a REST control plane and an Angular dashboard served from the same
process.

| Document | What it covers |
| :--- | :--- |
| [`QUALITY_CRITERIA.md`](QUALITY_CRITERIA.md) | Targets and gates, written before any code (first commit) |
| [`eval_report.md`](eval_report.md) | Measured latency, throughput, isolation, drift and sanitization results |
| [`SCALABILITY.md`](SCALABILITY.md) | 20k RPS design, async protocol shift, stub governance, monitoring |
| [`CUSTOMER_VALUE.md`](CUSTOMER_VALUE.md) | Problem, pilot metrics, risks, what stays human |
| [`BLOCKERS.md`](BLOCKERS.md) | What was skipped and why |
| [`docs/API.md`](docs/API.md) | Request/response contracts for every endpoint |

## Architecture

```
  System under test / CI pipeline                         Engineer (browser)
  (sends X-Session-Id per run)                                    │
            │                                                     ▼
            │  ANY /virtual/**                      ┌───────────────────────────┐
            │  POST /api/v1/engine/execute          │  Angular dashboard (ui/)  │
            ▼                                       │  traffic · stubs ·        │
 ┌────────────────────────────────────────┐         │  sessions · drift ·       │
 │ Ingress (VirtualServiceController)     │         │  recording review         │
 │ HTTP → VirtualRequest, applies drift   │         └─────────────┬─────────────┘
 │ delay on a virtual thread              │                       │ same origin, /api/v1/**
 └───────────────────┬────────────────────┘                       ▼
                     │                       ┌─────────────────────────────────────┐
                     ▼                       │ Control plane (REST)                │
 ┌──────────────────────────────────────┐    │ /stubs /sessions /recording /drift  │
 │ StubMatchingEngine  (stub-engine)    │    │ /traffic /metrics/summary           │
 │                                      │    └──┬───────────┬──────────┬──────────┬┘
 │  StubIndex (immutable, volatile)◄────┼───────┘ upsert →   │          │          │
 │   protocol → method → PathTrie       │      recompile +   │          │          │
 │   literal > {var} > * > **           │      atomic swap   │          │          │
 │   + compiled header/query/JSONPath   │                    │          │          │
 │     predicates                       │                    │          │          │
 │         │ match                      │                    │          │          │
 │         ▼                            │                    │          │          │
 │  DriftPolicy ── fault? delay? ◄──────┼────────────────────┼──────────┘          │
 │         │                            │                    │                     │
 │         ▼                            │                    │                     │
 │  SessionStateStore ◄─────────────────┼── inspect / clear ─┘                     │
 │   sessionId → collection → key →     │   / teardown                            │
 │   frozen value (lock-free reads)     │                                         │
 │         │                            │                                         │
 │         ▼                            │                                         │
 │  Compiled response template ──► TrafficLog (lock-free ring) ──► viewer         │
 └──────────────────────────────────────┘                                         │
                     ▲  publish approved stubs                                    │
 ┌───────────────────┴──────────────────────────────────────────┐                 │
 │ recording-generator                                          │◄────────────────┘
 │ raw logs → KeyClassifier + ValueDetectors (Luhn/IIN, SSA,    │  POST /recording/ingest
 │ JWT, key prefixes, entropy) → mask / synthesize → confidence │
 │ ≥ 0.90 auto-publish · < 0.90 mask + review queue → stubs     │
 └──────────────────────────────────────────────────────────────┘
```

| Module | Role | Depends on |
| :--- | :--- | :--- |
| `stub-engine` | Matching engine, template compiler, session state, drift policy, traffic ring | Jackson only |
| `recording-generator` | PCI/PII/secret sanitizer and stub synthesis from recorded traffic | `stub-engine` |
| `ui` | Angular 22 dashboard, built by Maven and packaged as static resources in a jar | Node (downloaded by the build) |
| `platform-app` | Spring Boot 3.5: ingress, control plane, scheduling, bundles the UI | all of the above |
| `load-test` | Dependency-free HTTP load generator | JDK only |

## Setup and run

You need **JDK 21+**. Maven and Node are fetched automatically: the Maven wrapper downloads
Maven 3.9.11, and the build downloads Node 22 into `ui/.node`.

```bash
./mvnw spring-boot:run -pl platform-app -am          # Windows: mvnw.cmd spring-boot:run -pl platform-app -am
```

Then open **http://localhost:8080**. Seed stubs are loaded, and the engine warms up with 10,000
requests before the app reports ready.

To build and test everything, then run the jar:

```bash
./mvnw verify                       # 166 tests incl. latency benchmark, coverage under */target/site/jacoco
java -XX:+UseZGC -XX:+ZGenerational -Xms1g -Xmx1g -jar platform-app/target/platform-app.jar
```

Useful flags:

* `-DskipUi` builds without Node and reuses `ui/dist`.
* `-DskipBenchmarks` skips the latency benchmark JVMs.
* `-Dnode.downloadRoot=<mirror>` downloads Node from a corporate mirror.
* `PORT=9090` changes the port.

### Try it

```bash
# 1. pipeline A creates an account (worked example from the brief)
curl -s -X POST localhost:8080/virtual/api/v1/accounts -H 'X-Session-Id: A' -H 'Content-Type: application/json' \
     -d '{"account_holder":"Jane Doe","initial_deposit":500.00,"currency":"USD"}'
# → 201 {"account_id":"ACC-1001","account_holder":"Jane Doe","balance":500.0,"currency":"USD","status":"ACTIVE"}

curl -s localhost:8080/virtual/api/v1/accounts/ACC-1001 -H 'X-Session-Id: A'     # 200, same account
curl -s localhost:8080/virtual/api/v1/accounts/ACC-1001 -H 'X-Session-Id: B'     # 404, isolated

# 2. the engine contract (VirtualRequest in, execution result out)
curl -s -X POST localhost:8080/api/v1/engine/execute -H 'Content-Type: application/json' -d '{
  "session_id":"cicd-runner-9842","protocol":"HTTP","method":"POST","path":"/api/v1/accounts",
  "headers":{"Content-Type":"application/json"},"body":{"account_holder":"Jane Doe","initial_deposit":500.0,"currency":"USD"}}'

# 3. inspect state, then fail 10% of payments
curl -s localhost:8080/api/v1/sessions/A/state
curl -s -X PUT localhost:8080/api/v1/drift/routes -H 'Content-Type: application/json' \
     -d '{"route":"POST /api/v1/payments","latency_ms":200,"jitter_ms":50,"error_rate":0.1,"error_status":503}'
```

You can point a real application at the platform by setting its downstream base URL to
`http://<host>:8080/virtual` and sending `X-Session-Id` (or `?svp_session=`). The response headers
`X-SVP-Matched-Stub`, `X-SVP-Session-Id` and `X-SVP-Engine-Latency-Ms` make each match visible.

## Design decisions

1. **Matching data structure: a compiled segment trie per protocol and method.** Lookup cost depends
   on path depth, not on the number of stubs. Children are tried most-specific first (literal, then
   `{var}`, then `*`, then `**`), and the stubs within a node are pre-sorted by priority, constraint
   count and registration order. The first stub whose predicates pass is therefore the most specific
   match, and the order is deterministic. Regexes, the hand-written JSONPath subset (`.a`, `['a']`,
   `[n]`, `[*]`, `..a`) and `{{templates}}` are all compiled at registration. A malformed stub is
   rejected with every error listed at once and never fails on the request path. Misses return
   near-miss diagnostics ("header X-Tenant equals 'acme' (was 'other')") instead of a bare 404.
2. **State storage: `sessionId → collection → key → frozen value`.** Isolation comes from the
   structure itself: every state operation is keyed by session first, and no query can cross
   sessions. Stored values are deep-frozen, so a response object can never mutate anyone's state.
   Merge and id generation are atomic `compute` and `AtomicLong` operations. Generated ids (for
   example `ACC-1001`) are counted per session, so parallel pipelines are reproducible and never
   collide. Client-supplied ids are honoured with `{{request.body.account_id ?: generate.id:ACC}}`.
   Sessions have a TTL and size limits, and teardown is a single map removal.
3. **Concurrency model: lock-free reads, copy-on-write writes, virtual threads.** Every request reads
   the stub index through one volatile load. Control-plane writes, which are rare, are serialized:
   they compile the change, build a new immutable index and swap it in, all or nothing per batch.
   Tomcat runs on virtual threads, so an injected 2-second delay parks a virtual thread rather than
   holding a platform thread. Drift is *decided* inside the engine but *applied* outside it, so it
   never inflates `execution_latency_ms`, and an injected fault never mutates state. The JVM uses
   generational ZGC because with G1, GC pauses were the only source of tail latency (see
   `eval_report.md` §2).
4. **Sanitization logic: name hints combined with value validators, scored by confidence, failing
   closed.** Key names are tokenized (`cardNumber`, `card_no` and `x-api-key` all normalize), and
   values are checked by structure: Luhn plus card-network IIN, SSA area/group/serial rules, JWT
   shape, PEM blocks, provider prefixes (`AKIA`, `sk_live_`), email, E.164, and entropy. Free text
   is scanned for embedded values. Replacements keep the format so stubs still replay: a
   brand-matched Luhn-valid test PAN, `000-00-0000`, and HMAC email pseudonyms keyed per process, so they
   are consistent across stubs but not reversible. Numbers stay numbers. A detection at confidence ≥ 0.90 is masked silently. Anything below that is masked
   *and* queued for review, and the stub set stays `PENDING_REVIEW` until a person approves it.
   Recorded stubs can never overwrite hand-written ones.
5. **UI integration: one origin, one process, API-only.** The Angular app is built by
   `frontend-maven-plugin`, packaged as `META-INF/resources` inside the `ui` jar, and served by
   Spring Boot, which avoids CORS and a second deployable. It calls only the public `/api/v1`
   endpoints, so anything the UI does can be scripted from CI. Pages are lazy-loaded with hash
   routing, so deep links work without server rewrites, and a live metrics bar polls
   `/metrics/summary`.

## What I would do differently with more time

* **gRPC and Kafka.** I'd add them as protocols on the same trie; the `protocol` field and per-protocol
  index are already in place. I'd also add the async HTTP-to-Kafka trigger described in
  `SCALABILITY.md`. I scoped these out to keep the must-haves solid (see `BLOCKERS.md`).
* **A purpose-built ingress for `/virtual/**`.** Under load the engine uses only ~11 % of server CPU;
  Tomcat, Spring MVC and Micrometer use most of the rest. A Netty or raw-NIO front door would likely
  more than double RPS per core.
* **Persistence and multiple nodes.** Stubs and recordings are in memory. I'd store definitions in
  Git or a database with versioning, and shard sessions across nodes by consistent hashing on
  `session_id`.
* **A sanitization dataset written by someone else.** My golden set and the detector share an author,
  so its 100 % score is an upper bound. I'd collect real, labelled, anonymized samples and track
  precision and recall per release.
* **UI tests.** The dashboard was verified by driving it with Playwright during development, not by
  committed tests. I'd add component tests and a Playwright smoke suite to CI.
* **Contract drift detection.** I'd diff recorded traffic against live stubs and flag fields that
  appeared or disappeared, so stale stubs surface automatically.

## Test coverage summary

`./mvnw verify` runs **166 tests**: stub-engine 88 plus a benchmark, recording-generator 58 plus a
throughput benchmark, platform-app 18. JaCoCo line coverage is **87 %** (engine), **92 %**
(recording) and **89 %** (platform app).

| Area | What is tested | Where |
| :--- | :--- | :--- |
| Matching | the 30 labelled scenarios (in-process *and* over HTTP); precedence rules; JSONPath subset; every matcher operator; template compiler; registration validation; all-or-nothing batches; toggling | `GoldenScenarioTest`, `PathPrecedenceTest`, `JsonPathExprTest`, `ValueMatcherTest`, `TemplateCompilerTest`, `StubRegistryTest` |
| State and isolation | PUT/GET/MERGE/DELETE/LIST lifecycle; immutability; TTL expiry; limits (429); **64 sessions × 16 threads with conflicting ids, 0 violations**; teardown under load; parallel pipelines over HTTP | `StatefulBehaviourTest`, `SessionStateStoreTest`, `ConcurrentSessionIsolationTest`, `VirtualServiceHttpTest` |
| Latency | the illustrative JUnit tests from the brief; benchmark after 20k warm-up (p99 < 10 ms, ≥ 99.9 % under 10 ms, ≥ 50k ops/s per core) | `StubMatchingEngineTest`, `EngineLatencyBenchmarkTest` |
| Sanitization | field-level precision and recall over the 40-record golden set with look-alikes; zero substring leaks; type preservation; 30-case hold-out probe; the brief's PCI example (`pci_fields_masked == 2`); review-queue routing; ≥ 5k logs/s | `SanitizationPrecisionRecallTest`, `HoldoutProbeTest`, `SanitizerStubGeneratorTest`, `SensitiveDataSanitizerTest`, `SanitizerThroughputTest` |
| Drift | 5/10/50 % error rates within ±1 pp over 20k samples; uniform and gaussian distributions; route overrides; no state mutation on faults; delay applied on the wire | `DriftPolicyTest`, `VirtualServiceHttpTest` |
| Control plane | every endpoint's happy path and invalid input; approve/reject flow; recorded-vs-manual protection | `ControlPlaneApiTest` |
| HTTP throughput | 60 s runs against the running jar (not part of `verify`, because it needs a dedicated box) | `load-test`, `scripts/run-load-test.sh` |

**Not covered by automated tests:** the Angular UI (manual and Playwright-driven checks only);
multi-node behaviour, which isn't built; and real gRPC or Kafka traffic (out of scope, see
`BLOCKERS.md`).
