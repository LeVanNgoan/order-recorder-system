# Grab Order Recorder v2.0.1 — Reconciliation Telemetry

## Mục tiêu
Bổ sung telemetry cho Order Recorder Hub v2.2.0 mà không thay đổi core capture GrabFood production v2.0.0.

## Thay đổi
- Gửi `SEEN` khi một đơn GrabFood mới thực sự được phát hiện.
- Gửi `CAPTURED` sau khi đơn đã được lưu local thành công.
- Event được queue local riêng; Hub offline không chặn capture.
- Order gửi Hub có `agentSessionId` để trang chi tiết ghép timeline đúng đơn.
- Không backfill `SEEN` cho đơn cũ để không làm sai capture rate lịch sử.
- Không thêm `MISS` suy đoán cho GrabFood: SEEN chưa CAPTURED sẽ được Hub hiển thị là đang thiếu/chờ xử lý.

## Không thay đổi
- `platforms/grab/grab-monitor.js`
- thuật toán mở drawer / đọc SĐT / retry
- monitor tab recovery / watchdog
- popup, viewer, Excel builder
- cấu trúc order local 7 ngày
