# Scalability & Architecture

## 1. 20,000 RPS with sub-10 ms matching

- **No shared mutable state on the hot path.** Stubs compile into an immutable trie published
  through one volatile reference, so cost grows with path depth, not stub count.
- **State is partitioned by session:** per-session maps of frozen values, lock-free reads.
- **Drift waits off the request path.** An injected delay parks a virtual thread.
- **Measured:** engine p99 is 0.010 ms at 440k ops/s on 2 vCPU, and 0.05 ms during a 15.9k RPS HTTP
  run with the load generator on the same box. The engine is ~11 % of server CPU.
- **Reaching 20k:** 8 vCPU with a separate load generator. Beyond one node, a load balancer hashes
  `X-Session-Id`, so each pipeline stays on one node and nodes scale horizontally.

## 2. Async protocol shift (HTTP → Kafka after 500 ms)

Designed, not built. A stub declares `async_actions: [{topic, delay_ms, template}]`.

1. On a match, the rendered event goes into a timer wheel (O(1) scheduling) carrying
   `session_id`, `correlation_id` and a deterministic `event_id`.
2. The event is written to an outbox before the HTTP response returns. Delivery is at-least-once
   with idempotent keys, and pending events are replayed after a restart.
3. Events are keyed by session and correlation id, so they stay ordered per pipeline.
4. An injectable clock lets tests advance time instead of sleeping.

## 3. Stub sprawl and governance

- **Built:** every stub has a `source` (MANUAL, SEED, RECORDED), tags, `hit_count` and
  `last_hit_at`. Broken stubs are rejected; recorded stubs never overwrite hand-written ones.
- **Next:**
  - Stubs live in Git with an owner and an API version.
  - CI replays fresh recordings to flag contract drift.
  - Stubs with no hits for 30 days are disabled, then archived.

## 4. Production monitoring

| Metric | Alert |
| :--- | :--- |
| Engine p99 | > 5 ms for 5 min (warning), > 10 ms (page) |
| Unexpected 5xx (excluding injected) | > 0.1 % |
| `NO_MATCHING_STUB` per consumer | > 2 % |
| Sessions / entries | > 80 % of limits |
| Heap after GC; GC pause p99 | > 75 %; > 5 ms |
| `PENDING_REVIEW` recordings | older than 24 h |
| Sanitizer leak in canary scans | page immediately |
