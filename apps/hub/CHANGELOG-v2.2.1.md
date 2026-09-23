# Order Recorder Hub v2.2.1 — UI Fix

## Sửa lỗi giao diện Dashboard đối soát

- Sửa khung **Đơn cần xử lý** bị tràn ra ngoài panel Dashboard đối soát.
- Grid đối soát dùng `minmax(0, ...)` và các cột con có `min-width: 0`, ngăn table ép chiều rộng parent.
- Bảng **Đơn cần xử lý** cuộn ngang bên trong card khi cần, không kéo rộng toàn trang.
- Giới hạn chiều rộng ô lý do và cho phép xuống dòng.
- Từ màn hình <= 1180px, hai bảng **Theo nền tảng** và **Đơn cần xử lý** tự xếp thành một cột để dễ đọc.
- Giữ nguyên API, database schema, telemetry, Tailscale, Auto Discovery, backup DB, audit, thùng rác và logic quản lý của v2.2.0.

## Version
- Hub: `2.2.1`
