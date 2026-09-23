# Contributing

This project is primarily maintained as a portfolio and learning system, but the engineering workflow follows production-style change discipline.

## Change rules

1. Run `python scripts/qa_all.py` before opening a PR.
2. Do not combine a structural refactor with a capture/state-machine behavior change.
3. For changes to agent/Hub payloads, update `shared/contracts` first.
4. Add or update regression tests for every bug fix.
5. Never add real customer/order data, runtime databases, technical logs, API keys, or signing files.
6. Keep reliability-sensitive Android timing changes isolated and justified by reproducible evidence.

## Commit style

Recommended Conventional Commit prefixes:

```text
feat:     user-visible capability
fix:      bug fix
refactor: behavior-preserving structure change
test:     test-only changes
docs:     documentation
chore:    build/repository maintenance
ci:       GitHub Actions changes
```

Examples:

```text
fix(spf): prevent receiver capture from accepting ambiguous phone candidates
feat(hub): add device-health warning telemetry
refactor(hub): move reconciliation rules into service layer
```

## Pull requests

A PR should explain:

- the observed problem;
- the expected behavior;
- the changed layer/component;
- regression risk;
- how the change was tested;
- whether wire contracts or data migrations changed.
