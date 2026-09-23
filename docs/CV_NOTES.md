# CV / Interview Notes

Use only statements you can explain technically in an interview.

## Short project description

**Order Recorder System** — Designed and built a local-first multi-agent order capture and reconciliation system integrating an Android Accessibility agent, a Chrome Manifest V3 extension, and a Python/SQLite Windows Hub with telemetry, recovery workflows, audit logs, and CI regression testing.

## Resume bullet options

### Software Engineer / Backend-oriented

- Designed a local-first order reconciliation platform using **Python, SQLite/WAL, REST-style HTTP APIs, Android Java, and Chrome Extension MV3**, separating business records from idempotent lifecycle telemetry (`SEEN/RISK/CAPTURED/MISS`).
- Built recovery and observability features including **bounded retries, audit trails, soft-delete/restore, daily online database backups, manual recovery, and native Windows early-risk alerts**.
- Refactored a production-derived codebase into an **MVC2/layered monorepo** with Controller/Service/Repository boundaries, shared JSON contracts, regression tests, and GitHub Actions build pipelines.

### Automation / Mobile-oriented

- Implemented Android merchant-workflow automation with **NotificationListenerService + AccessibilityService**, using processing leases, queue fairness, wrong-order guards, settle windows, bounded attempts, and local-first persistence.
- Investigated real UI/timing incidents through black-box telemetry and logs, prioritizing observable failure states over aggressive retry loops.

### Full-stack / Systems-oriented

- Integrated **Android, browser extension, Windows desktop backend, local networking/discovery, SQLite persistence, web dashboard, and CI/CD** into a single operational system.

## 30-second interview explanation

> I built an order-recorder system for merchant operations across two delivery-platform workflows. One agent runs as a Chrome extension, another runs on an Android/SUNMI device using Notification and Accessibility APIs, and both synchronize to a Python/SQLite Windows Hub. The interesting part was reliability: I separated detection from capture, added idempotent lifecycle telemetry, bounded automation retries, local-first persistence, early risk alerts, manual recovery and audit trails. Later I refactored the codebase into an MVC2 monorepo while protecting proven capture behavior with regression checks.

## Questions to prepare for

Be ready to explain:

- Why local-first instead of making Hub availability mandatory?
- Why `SEEN` should be the reconciliation denominator?
- What makes an event idempotent?
- Why bounded retries are safer for Accessibility automation?
- Why SQLite WAL/online backup was used?
- How an Android Notification Listener differs from AccessibilityService?
- How Chrome Manifest V3 service workers behave differently from persistent background pages?
- How you would validate that a captured phone number belongs to the intended semantic role, not merely that it matches a phone regex?
- Why the MVC2 migration kept a legacy runtime instead of rewriting everything?
