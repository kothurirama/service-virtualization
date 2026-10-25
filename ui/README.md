# ui — Angular dashboard

Angular 22 (standalone components, signals, zoneless). It talks only to the platform's public REST API
under `/api/v1`; nothing is private to the UI.

* **Production:** built by Maven (`frontend-maven-plugin`) and packaged into `ui-1.0.0.jar` under
  `META-INF/resources`, so Spring Boot serves it at `http://localhost:8080/` — one process, same origin.
* **Development:** run the platform on `:8080`, then `npm ci && npm start` here and open
  `http://localhost:4200` (requests to `/api`, `/virtual` and `/actuator` are proxied).

| Screen | What it does |
| :--- | :--- |
| Traffic | Live ledger of virtual requests: session, matched stub, status, engine latency, injected drift. Request composer for ad-hoc calls. |
| Stubs | Search and filter by source, enable/disable, edit JSON with server-side validation, create, delete. |
| Sessions | Create sessions with TTL and labels, inspect per-session state by collection, clear state, tear down. |
| Drift | Global and per-route latency, jitter, distribution and failure injection, with a plain-language summary. |
| Recording | Ingest captured traffic, see every masked field with confidence, approve or reject held configurations. |
