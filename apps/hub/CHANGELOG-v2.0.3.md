# Order Recorder Hub v2.0.3 - Manual Backup

## Mục tiêu
Gom backup SĐT bị miss vào cùng Hub/database thay vì nhân viên phải ghi ở nơi khác.

## Hành vi
- Dashboard thêm khối **Backup SĐT bị miss**.
- Nhập: nền tảng, mã đơn, SĐT, thời gian nhận đơn.
- Mã được chuẩn hóa: `GF-xxx`, `SPF-xxxx`; SPF chấp nhận cả `#xxxx`/chỉ số.
- SĐT `+84`/`84` được chuẩn hóa về đầu `0`.
- Nếu đơn đã tồn tại và chưa có SĐT: chỉ bổ sung `phone` vào đúng record.
- Nếu đơn chưa có trong Hub: tạo record backup tối thiểu, vẫn dùng cùng dedup logic.
- Nếu đơn đã có đúng SĐT: không tạo trùng.
- Nếu đơn đã có SĐT khác: HTTP 409, không ghi đè tự động.
- Record từng được backup được đánh dấu `manual_backup=1` và có badge `BACKUP` trên Dashboard.
- Excel giữ nguyên 4 cột và tự bao gồm record backup.

## Bảo mật
- `POST /api/manual-backup` chỉ dùng được từ localhost hoặc Tailscale.
- LAN thường (ví dụ SUNMI) không được quyền đọc/chèn backup bằng endpoint này.
- API key không bị đưa ra remote dashboard.

## Tương thích
- Giữ nguyên `orders.db`, `hub_config.json`, API key, port, 7-day retention.
- Migration database chỉ thêm 2 cột: `manual_backup`, `manual_backup_at`.
- Tailscale remote dashboard và Auto Discovery v2.0.2 được giữ nguyên.
