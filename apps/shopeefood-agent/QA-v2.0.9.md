# QA v2.0.9

## Release invariants

- versionCode = 29.
- versionName = 2.0.9.
- Hub heartbeat/discovery reports 2.0.9.
- `AutomationPolicy.java`, `AppPrefs.java`, `NodeUtil.java` identical to v2.0.8.
- No parallel capture worker, overlay, rapid polling or timing-policy change.
- Accessibility change is metadata-only and reuses the existing phone-save write.
- Notification change only schedules Hub background sync after SEEN/terminal MISS.
- Existing orders/Hub config/logs preserved on in-place APK update.

## Telemetry correctness

- New notification → one idempotent SEEN.
- Auto capture → CAPTURED `capture_mode=auto`.
- Manual rescue → CAPTURED `capture_mode=manual`.
- Terminal failure → persisted MISS even if manual rescue happens while Hub is offline.
- Old pre-v2.0.9 records are not backfilled into the reconciliation denominator.
- Telemetry endpoint failure must not block normal `/api/orders` sync.

## Production validation

After GitHub Actions `assembleRelease` passes, install over v2.0.8 (do not uninstall). Verify 20–30 orders, including a small burst, then compare Hub SEEN/CAPTURED/MISS with technical black-box log.
