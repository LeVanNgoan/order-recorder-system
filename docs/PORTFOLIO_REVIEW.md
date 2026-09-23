# Portfolio Review Guide

If you are reviewing this project for a software-engineering role, the fastest path through the repository is:

1. `README.md` — problem, components, architecture, features.
2. `docs/CASE_STUDY.md` — engineering decisions and reliability lessons.
3. `apps/shopeefood-agent/app/src/main/java/.../controller/ShopeeAccessibilityService.java` — Android automation entrypoint.
4. `apps/shopeefood-agent/app/src/main/java/.../repository/OrderStore.java` — durable local order state.
5. `apps/shopeefood-agent/app/src/main/java/.../service/HubSync.java` — background telemetry/order synchronization.
6. `apps/hub/app/legacy/runtime.py` — current production-derived Hub runtime, including SQLite, HTTP APIs, reconciliation, alerts, audit and backups.
7. `apps/hub/app/{controller,service,repository}` — MVC2 migration boundaries.
8. `apps/grabfood-extension/src/controller/grab-monitor.js` — browser-side detection hot path.
9. `shared/contracts` — API/event contracts.
10. `.github/workflows` + `scripts/qa_all.py` — testing/build discipline.

The codebase intentionally contains a `legacy` runtime during an incremental refactor. This is documented technical debt rather than an attempt to present the system as fully migrated.
