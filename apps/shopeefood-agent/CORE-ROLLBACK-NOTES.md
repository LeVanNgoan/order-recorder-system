# Core rollback notes — v2.0.3

Phần automation của v2.0.3 được dựng từ `ShopeeFoodOrderRecorderAuto-v0.2.5`.

## Giữ nguyên từ v0.2.5
- FIFO một đơn tại một thời điểm.
- `contentIntent` trước, notification shade fallback sau.
- Xác nhận đúng màn hình `Chi tiết đơn hàng`.
- Mở contact từ dòng `Khách hàng`.
- Khóa contact sheet vào active order.
- Chỉ click/nhận SĐT từ `Khách nhận đơn`.
- Direct phone in ShopeeFood + Dialer fallback.
- Các nhịp retry/scan/recovery của v0.2.5.
- `NodeUtil.java` byte-identical với v0.2.5.
- Accessibility config quay về event set + `notificationTimeout=120` của v0.2.5.

## Chỉ thêm ngoài core
- 1.000 ms sound grace cho từng notification trước khi bắt đầu automation.
- TextParser mới: short ID canonical, `SPF-xxxx`, phone `0xxxxxxxxx`.
- OrderStore mới: retention 7 ngày, backward-compatible data, async disk write.
- Completion notification và HubSync chỉ chạy sau khi local phone attach thành công.
- UI/Excel/Hub/Force Resync của Final giữ nguyên.
- One-time migration xóa state/queue automation thử nghiệm v2.0.0/v2.0.1 nhưng không xóa `orders.json` hay Hub config.
