# QA v2.0.1

- `platforms/grab/grab-monitor.js`: logic unchanged; only `SCRIPT_VERSION` 2.0.0 → 2.0.1 so it stays aligned with the service worker and does not trigger reload loops.
- `content/grab-content.js`: UNCHANGED
- `libs/xlsx-builder.js`: UNCHANGED
- `popup/*`: UNCHANGED
- `viewer/*`: UNCHANGED
- Capture selectors, timing, retry, drawer workflow, monitor recovery: UNCHANGED.
- Telemetry is local-first and uses a separate queue.
- No historical SEEN backfill.
