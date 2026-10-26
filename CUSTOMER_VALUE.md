# Customer Value

## 1. Baseline problem

When a bank moves mainframe-backed systems onto microservices, every CI pipeline still hits the
same payment gateways and core-banking sandboxes. Those cost $0.05 per call, throttle traffic, fail
off-hours, and share test data, so parallel runs corrupt each other. Hand-written mocks take days,
break when contracts change, and are often built from captured traffic containing card numbers
and SSNs: a PCI-DSS problem in a test folder.

## 2. Measurable outcome: 30-day pilot, 3 squads

Take a one-week baseline, then compare it with weeks 2–4:

| Metric | Source | Target |
| :--- | :--- | :--- |
| Sandbox spend | Gateway invoices / call counts | −80 % |
| CI duration p50 / p90 | CI timings | −30 % |
| Flaky tests from dependencies | Reruns after 5xx or timeouts | −70 % |
| Shared-data failures | Fail together, pass alone | ≈ 0 |
| Time to mock an endpoint | Ticket → stub merged | days → < 1 hour |
| PCI/PII in test assets | Repo and stub scanners | 0 |

## 3. Risks and limitations

**What can go wrong:**

- Recorded stubs freeze yesterday's contract.
- Templating may vary a field that should stay fixed.
- The sanitizer can miss a value under a misleading name (an SSN in `ref`), or over-mask a
  harmless ID.

**Guardrails built:**

- **Fail closed.** Validated values (Luhn, SSA, JWT, key prefixes) are masked. Uncertain ones are
  masked *and* queued, and those stubs stay unpublished until a person approves.
- **No secrets in stubs.** Header credentials are dropped; output is checked for leaks.
- **Hand-written stubs are protected.** Recorded stubs cannot overwrite manual ones, and every stub
  shows its source and hit count.
- **Fail loudly.** Invalid stubs are rejected at registration; misses return diagnostics.

## 4. What I would not automate

- **Approving low-confidence sanitization.** It's a compliance decision that needs an accountable
  person.
- **Retiring or overwriting hand-written stubs.** They deliberately encode declines, timeouts and
  regulatory paths that traffic rarely shows.
- **Deciding a contract change is correct.** Tooling can flag a difference; whether the consumer or
  the provider is wrong is for the teams to settle.
- **Drift in shared environments.** Injecting failures should be an explicit choice per pipeline,
  never a default.
