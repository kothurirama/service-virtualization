# Blockers, Scope Decisions & Known Limitations

The brief warns that "a broken must-have costs more than a missing stretch item", so I made a
deliberate choice: build **all must-haves** to a tested, measured standard, and leave the
should-have and stretch tiers out rather than ship them half-finished.

## Not implemented

| Item | Tier | Why | Where the design lives |
| :--- | :--- | :--- | :--- |
| gRPC and Kafka matching | Should | Scoped out. The engine is ready for them: `protocol` is part of the stub model and the index is keyed protocol → method → trie, so gRPC `service/method` and Kafka `topic` slot in as new path sources. Registration currently rejects non-HTTP protocols with a clear message instead of silently accepting them. | `StubIndex`, `StubCompiler.SUPPORTED_PROTOCOLS` |
| Async trigger (HTTP call → delayed Kafka event) | Should | Depends on the above. | `SCALABILITY.md` §2 (timer wheel, outbox, per-session ordering, virtual clock) |
| Real gRPC server / real Kafka (Testcontainers) | Stretch | Out of scope. | — |
| Live outbound recording proxy | Stretch | The required minimum, log ingest via `POST /api/v1/recording/ingest`, is built. A forward proxy would feed the same pipeline. | `RecordingController` |
| Ephemeral environments beyond session create/teardown | Stretch | Sessions provide isolation, TTL expiry and one-call teardown. Full environment spin-up was not attempted. | `SessionStateStore` |
| XPath body matching | Must (as "JSONPath/XPath") | JSONPath was implemented, since every contract in the brief is JSON. XML bodies are accepted as text and can be matched with `contains` / `matches`, but there is no XPath evaluator. | `JsonPathExpr` |
| Persistence | — | Stubs, sessions and recordings live in memory and are lost on restart. Seed stubs reload at startup. | README "What I would do differently" |

## Measured shortfall

**20,000 RPS over HTTP was not reached on the available hardware.** The test box had 2 vCPU, with
the load generator running on the same machine. It reached 15.9k RPS with ZGC and 18.3k RPS with
ParallelGC. The engine itself does about 440k ops/s, and profiling puts roughly 89 % of the
per-request CPU in Tomcat, Spring MVC, Jackson and Micrometer. The full analysis is in
`eval_report.md` §4. I report the honest number rather than a projection.

## Known limitations

- **Sanitizer: SSN-shaped values under identifier-like keys.** An unformatted 9-digit value under a
  key such as `ref` or `reference` passes through unmasked. Masking every such value would also
  mask genuine identifiers (`invoice_no`, `routing_number`), and the two cases cannot be told apart
  from structure alone, since both pass the SSA rules. Under neutral keys the value is masked and
  held for review.
- **Sanitizer accuracy figures are self-assessed.** The detector and the golden dataset share an
  author. The hold-out probe (`HoldoutProbeTest`) is a partial check; the reviewers' hidden dataset
  is the real one.
- **Card expiry** is masked only when a PAN appears in the same object. On its own, `12/28` is
  indistinguishable from many other values.
- **Email pseudonyms** use an HMAC key that is random per process, so the same address maps to the
  same pseudonym until the platform restarts.
- **UI tests are not committed.** The dashboard was verified by driving every screen with Playwright
  during development (stub toggle and edit, session clear and teardown, drift sliders, ingest and
  review), but those scripts are not part of `./mvnw verify`.

## Environment notes

The build machine could not reach Maven Central, so the dependencies were resolved once on a
separate machine and the build was then run offline. On a normal network, `./mvnw verify` resolves
everything itself, including Node 22 for the UI build.
