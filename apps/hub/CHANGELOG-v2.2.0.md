# Changelog v2.2.0

## Reconciliation
- Added idempotent `order_events` table.
- Added API-key authenticated `POST /api/order-events` for `SEEN`, `CAPTURED`, `MISS`.
- Added reconciliation dashboard with per-platform metrics and unresolved-order queue.
- Capture denominator is explicit `SEEN`, never inferred from Hub order rows.
- Separates automatic capture rate from final data completion after manual backup.
- Platforms without telemetry show `Chưa có telemetry từ agent`.

## Order management
- Added Trash view for soft-deleted orders.
- Added password-protected Restore with operator + reason.
- Added order detail modal and merged timeline (agent events + audit history).
- Added `RESTORE` audit action.

## Database protection
- Added automatic daily SQLite database backup using SQLite Online Backup API.
- Backup on startup + hourly daily check.
- Backup retention is capped at the 7-day business-data retention; backups never extend phone-data retention.
- Added manual `Sao lưu ngay` and backup status in Settings.

## Compatibility / security
- Preserves v2.1.0 management features, Tailscale dashboard, Auto Discovery and 7-day order retention.
- Keeps existing API key/admin password/Hub ID/database during migration.
- Dashboard and management remain limited to localhost + Tailscale.
- Agent write endpoints remain API-key protected.
