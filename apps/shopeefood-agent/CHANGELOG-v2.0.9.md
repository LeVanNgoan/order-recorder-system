# Changelog v2.0.9

- Added best-effort Hub reconciliation telemetry: `SEEN`, `CAPTURED`, `MISS`.
- Added stable `agentSessionId` to normal Hub order payloads.
- Added capture-mode metadata (`auto` / `manual`) without adding a second hot-path file write.
- Persisted terminal MISS timestamp/reason/attempt count so later manual rescue cannot erase MISS history while Hub is offline.
- Added one-time telemetry migration: existing v2.0.8 records are not backfilled into the SEEN denominator.
- Telemetry network work remains on the existing single-thread `order-hub-sync` executor.
- Telemetry failures do not block normal `/api/orders` business sync.
- Updated heartbeat/Auto Discovery version to 2.0.9.
- `AutomationPolicy.java`, `AppPrefs.java`, `NodeUtil.java` unchanged from v2.0.8.
