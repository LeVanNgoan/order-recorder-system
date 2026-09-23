# v2.0.9 — Reconciliation Telemetry

## Contract với Hub v2.2.0

Endpoint: `POST /api/order-events` với `X-Order-Recorder-Key` hiện tại.

Mỗi order dùng `agentSessionId` ổn định (`spf:<receivedAt>:<shortId>`). Hub dùng event key theo session + event type nên retry là idempotent.

### SEEN
Gửi sau khi notification hợp lệ đã được ghi vào `OrderStore` và enqueue. Network chạy trên `order-hub-sync`, không chạy trực tiếp trên Notification main thread.

### CAPTURED
Gửi khi record có SĐT. Metadata:
- `capture_mode=auto` nếu SĐT được lưu khi scheduler đang sở hữu processing order.
- `capture_mode=manual` nếu nhân viên cứu đơn ngoài processing automation.
- `last_stage` để diagnostic.

### MISS
Gửi khi order đã terminal `needs_review`. Thời gian/lý do/số attempt được persist riêng trước khi sync; manual rescue sau đó không xóa lịch sử MISS telemetry.

## Failure isolation

- Telemetry error chỉ ghi technical log.
- Không `markError` Hub chỉ vì `/api/order-events` không tồn tại hoặc fail.
- `/api/orders` và heartbeat vẫn là connectivity/business sync chính.
- Hub cũ chưa có telemetry vẫn tiếp tục nhận đơn/SĐT bình thường.

## Migration

Record có sẵn trước v2.0.9 được đánh dấu telemetry legacy một lần. Không backfill SEEN/CAPTURED cho lịch sử vì không thể tái tạo các đơn đã miss hoàn toàn trước khi telemetry tồn tại.
