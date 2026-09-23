# Changelog v2.1.0

- Fixed manual-add form overlap between datetime input and save button.
- Renamed UI `Grab` -> `GrabFood` while preserving internal platform key `grab`.
- Manual section renamed to `Thêm đơn hàng mới` with shorter guidance copy.
- Added recent-order display limits: 10, 20, 30, 50, 100, all.
- Changed table to newest-first and added Platform + Actions columns.
- Added order edit workflow with mandatory operator + reason.
- Added persistent audit trail with before/after snapshots.
- Added password-protected soft delete.
- Added locally managed admin password using PBKDF2-HMAC-SHA256 + salt.
- Added collapsible Audit Log and Settings panels.
- Preserved Tailscale remote access, Auto Discovery, API key and existing database.
- Fixed text search edge case where a query without digits could accidentally make phone matching too broad.
