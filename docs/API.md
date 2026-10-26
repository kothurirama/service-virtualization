# API Reference

JSON field names are `snake_case` everywhere. Validation errors come back as
`{"error": "...", "message": "...", "details": ["...every problem..."]}` with HTTP 400, a missing
resource is 404, a conflict is 409, and a session or entry limit is 429.

## Data plane

| Method | Path | Purpose |
| :--- | :--- | :--- |
| ANY | `/virtual/**` | The virtual service itself. `/virtual/api/v1/accounts` is matched as `/api/v1/accounts`. Session from the `X-Session-Id` header or `?svp_session=`, otherwise `default`. Response headers: `X-SVP-Matched-Stub`, `X-SVP-Session-Id`, `X-SVP-Engine-Latency-Ms`. |
| POST | `/api/v1/engine/execute` | The assignment's engine contract: a request in, the execution result out. Always HTTP 200; the virtual status is in `status_code`. |

`POST /api/v1/engine/execute`

```json
// request
{ "session_id": "cicd-runner-9842", "protocol": "HTTP", "method": "POST", "path": "/api/v1/accounts?x=1",
  "headers": { "Content-Type": "application/json" }, "query": { "y": "2" },
  "body": { "account_holder": "Jane Doe", "initial_deposit": 500.00, "currency": "USD" } }
// response
{ "matched_stub_id": "stub_account_create_v1", "status_code": 201,
  "headers": { "Location": "/api/v1/accounts/ACC-1001", "Content-Type": "application/json" },
  "body": { "account_id": "ACC-1001", "account_holder": "Jane Doe", "balance": 500.0, "currency": "USD", "status": "ACTIVE" },
  "state_mutated": true, "execution_latency_ms": 0.136, "matched_rules": ["path_exact", "jsonpath_body_check"],
  "session_id": "cicd-runner-9842", "injected_delay_ms": 0, "fault_injected": false }
```

When no stub matches, the response has `matched_stub_id: null` and `status_code: 404`, and the body
is `{"error":"NO_MATCHING_STUB","near_misses":[{"stub_id","failed_rule","detail"}]}`. The near
misses list stubs whose route matched but whose constraint failed.

`matched_rules` lists the constraint kinds a stub checked: `path_exact` or `path_pattern`, then
`header_constraints`, `query_constraints` and `jsonpath_body_check`.

## Stub format

```json
{
  "stub_id": "stub_account_get_v1",          // required, [A-Za-z0-9_.:-]{1,128}
  "description": "…", "tags": ["accounts"],
  "enabled": true,                           // default true; disabled stubs are kept but never match
  "priority": 0,                             // higher wins between stubs on the same route
  "request_matcher": {
    "protocol": "HTTP",                      // default HTTP (only HTTP in this build)
    "method": "GET",                         // or "*" / "ANY"
    "path": "/api/v1/accounts/{id}",         // literal, {var}, * (one segment), ** (rest, last only)
    "headers": { "Authorization": { "matches": "^Bearer \\S+$" } },
    "query":   { "status": "PENDING" },
    "body_jsonpath": { "$.currency": "USD", "$.amount": { "gt": 10000 }, "$.items[*].sku": "SKU-1" }
  },
  "response_template": { "status_code": 200, "headers": { "X-Id": "{{request.path.id}}" }, "body": { } },
  "state_action": { "op": "GET", "collection": "accounts", "key": "{{request.path.id}}" },
  "not_found_response": { "status_code": 404, "body": { "error": "ACCOUNT_NOT_FOUND" } }
}
```

**Matcher values.** A plain value means equality. Body values compare by JSON type, with `500`
equal to `500.0`. Headers and query values compare as text, and `Content-Type`/`Accept` ignore
parameters such as `charset`. An operator object can combine several operators, which are ANDed:

* `equals`
* `matches` — a full-match regex
* `contains` — a substring, or an element of an array
* `exists: true|false`
* `gt`, `gte`, `lt`, `lte`

**JSONPath subset:**

* `$`, `.name`, `['name']`
* `[n]`, where a negative `n` counts from the end
* `[*]` and `.*`
* `..name` (deep scan)

Multi-valued paths match if *any* value satisfies the constraint.

**Precedence:**

1. literal segment > `{var}` > `*` > `**`
2. an exact method > `*`
3. higher `priority`
4. more constraints
5. earlier registration

**Template expressions (`{{ }}`).** A string that is exactly one expression keeps that expression's
JSON type. Any other string is interpolated as text.

| Expression | Value |
| :--- | :--- |
| `request.body[.path]`, `request.path`, `request.path.{var}` (alias `path.{var}`), `request.path.tail` (for `**`) | request data |
| `request.headers.{name}`, `request.query.{name}`, `request.method`, `request.session_id` | request data |
| `response.body[.path]`, `response.status_code` | the rendered response (allowed in `state_action` PUT and response headers) |
| `state.value[.path]`, `state.values`, `state.count`, `state.key` | session state read by the stub's state action |
| `generate.id:PREFIX` | per-session sequence, `PREFIX-1001`, `PREFIX-1002`, …; memoized within one request |
| `generate.uuid`, `now`, `now.epoch_millis`, `now.date` | generated values |
| `'text'`, `123`, `true`, `null`, `a ?: b ?: c` | literals, and the first present value |
| `"$merge": "{{request.body}}"` (object key) | copies an object's fields, then explicit keys override them |

**State actions.** All of them act on the request's own session only.

| `op` | Effect | Default response |
| :--- | :--- | :--- |
| `PUT` | stores `value` under `key` (default value: the rendered response body), rendered after the response | 201 |
| `GET` | reads `key`; missing gives `not_found_response` or a 404 | 200, body = the stored value |
| `MERGE` | RFC 7386 merge patch of `value` (default: the request body) into the existing value; missing gives a 404 | 200, body = the merged value |
| `DELETE` | removes `key`; missing gives a 404 | 204 |
| `LIST` | exposes `state.values` and `state.count` for `collection` | 200, body = the array |

**Ids.** `"{{request.body.account_id ?: generate.id:ACC}}"` honours a client-supplied id and
otherwise generates one.

## Control plane

| Method | Path | Body / params | Result |
| :--- | :--- | :--- | :--- |
| POST | `/api/v1/stubs` | one stub or an array (all-or-nothing) | 201 + stub view(s); 400 with every error |
| POST | `/api/v1/stubs/validate` | stub | `{valid, ...}` or an error list, without registering |
| GET | `/api/v1/stubs` | `q` (id, route, tag, description), `enabled`, `source=MANUAL\|SEED\|RECORDED` | stub views with `route`, `hit_count`, `last_hit_at` |
| GET / PUT / DELETE | `/api/v1/stubs/{id}` | PUT: the full stub | view / updated view / 204 |
| PATCH | `/api/v1/stubs/{id}/enabled` | `{"enabled": false}` | view |
| POST | `/api/v1/sessions` | `{"session_id"?, "ttl_seconds"?, "labels"?}` creates (201); `{"session_id", "action": "teardown"}` tears down (200) | session view |
| GET | `/api/v1/sessions`, `/api/v1/sessions/{id}` | | id, TTL, labels, entry/request/mutation counts, collection sizes |
| DELETE | `/api/v1/sessions/{id}` | | 204, the session and all its state removed |
| GET / DELETE | `/api/v1/sessions/{id}/state` | | `{session_id, entry_count, collections: {name: {key: value}}}` / 204 clears the state but keeps the session |
| POST | `/api/v1/recording/ingest` | `{"service_name", "auto_publish": true, "logs": [raw log…]}`, a bare array, or a single log | 201 + recording view (below) |
| POST | `/api/v1/recording/sanitize` | any JSON | `{sanitized, detections, fields_inspected}`, nothing stored |
| POST | `/api/v1/recording/sanitize-log` | one raw log | the sanitized log and its detections |
| GET | `/api/v1/recording/configs`, `/configs/{id}` | | recording views, newest first |
| POST | `/api/v1/recording/configs/{id}/approve` \| `/reject` | | publishes the stubs / discards them |
| GET | `/api/v1/drift` | | `{global, routes, known_routes}` |
| PUT | `/api/v1/drift/global` | drift profile | view |
| PUT | `/api/v1/drift/routes` | `{"route": "POST /api/v1/payments", ...profile}` | view |
| DELETE | `/api/v1/drift/routes?route=…` / POST `/api/v1/drift/reset` | | view |
| GET / DELETE | `/api/v1/traffic` | `limit`, `session_id`, `stub_id`, `after_seq` (for incremental polling) | recent exchanges, newest first |
| GET | `/api/v1/metrics/summary` | | counters, engine p50/p95/p99 over the traffic window, stub and session counts |
| GET | `/actuator/health/readiness` | | UP once seed stubs are loaded and the engine warm-up is done |

**Drift profile.** Missing fields default to neutral values:

```json
{ "enabled": true, "latency_ms": 200, "jitter_ms": 50, "distribution": "UNIFORM",
  "error_rate": 0.05, "error_status": 503 }
```

* `distribution` is one of `FIXED`, `UNIFORM` (base ± jitter) or `GAUSSIAN` (σ = jitter, floored
  at 0).
* `error_rate` is between 0 and 1, and `error_status` must be a 4xx or 5xx code.
* Delays are capped at 60 s.
* A route profile overrides the global one while it is enabled.

## Recording

**Raw traffic log.** `request` and `response` are optional, and the path may include a query
string. Bodies can be JSON or text.

```json
{ "method": "POST", "path": "/api/v1/payments?ref=1",
  "request":  { "headers": { "Content-Type": "application/json", "Authorization": "Bearer …" },
                "body": { "merchant_id": "MERCH_1092", "credit_card": "4111111111111111", "cvv": "123", "amount": 100.0 } },
  "response": { "status_code": 200, "headers": { }, "body": { "transaction_id": "TXN-881920", "status": "SUCCESS" } } }
```

**Recording view.** This is the assignment's stub config plus workflow fields:

```json
{ "stub_config_id": "AUTO_GEN_PAYMENT_SVC_2026-10-26", "generated_by": "TrafficRecordingProxy",
  "status": "PUBLISHED | PENDING_REVIEW | DRAFT | REJECTED", "published_stub_ids": ["…"],
  "sanitization_summary": { "total_fields_inspected": 13, "pci_fields_masked": 3, "pii_fields_masked": 1,
                            "secret_fields_masked": 2, "requires_human_review": false, "lowest_confidence": 0.95 },
  "virtual_stubs": [ /* stub format above, source = RECORDED */ ],
  "review_queue": [ { "field_name": "custom_auth_token", "reason": "Unrecognized high-entropy pattern — flagged for security review",
                      "suggested_action": "REDACT" } ],
  "field_detections": [ { "field": "request.body.credit_card", "type": "PAN", "category": "PCI",
                          "confidence": 0.99, "detector": "luhn_iin", "masked_as": "4242424242424242" } ] }
```

**Publishing rules:**

* Stubs auto-publish only when every detection has confidence ≥ 0.90 and the review queue is empty.
* Otherwise the recording is held as `PENDING_REVIEW` until someone approves it.
* With `auto_publish: false`, it is held as `DRAFT`.
* A recorded stub whose id matches a manual or seed stub is never published over it.
