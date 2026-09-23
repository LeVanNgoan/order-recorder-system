# ShopeeFood Recorder — Final v2.0.10

## Mục tiêu của v2.0.10

v2.0.10 giữ nguyên state-machine/timing capture đã ổn định từ v2.0.8, giữ telemetry v2.0.9 và bổ sung hai khả năng quan sát an toàn:

- **Window Layer Detection**: sau auto-click `Khách nhận đơn`, đọc thêm mọi Accessibility window để bắt trường hợp SĐT đã hiện nhưng bị khung `Liên hệ` che phía trên.
- **Early RISK**: gửi cảnh báo sớm về Hub khi có khả năng miss, trong khi automation vẫn tiếp tục.

RISK là telemetry, không phải lệnh điều khiển. Multi-window scan là read-only, không tự Back/click thêm.

## Điểm bảo vệ reliability

Các file policy/parser quan trọng sau giữ nguyên byte-for-byte so với source production v2.0.9:

- `AutomationPolicy.java`
- `AppPrefs.java`
- `NodeUtil.java`
- `TextParser.java`

`ShopeeAccessibilityService` có thêm multi-window observation nhưng không đổi timer/click/queue policy. Baseline phone-candidate chỉ giữ trong RAM; không thêm SharedPreferences write cho feature này. `ShopeeNotificationListener` chỉ đánh dấu RISK telemetry trước các failure phù hợp rồi vẫn chạy flow fail/defer cũ.

Terminal MISS được lưu thành metadata riêng (`missEventAt/reason/attempts`) trước khi gửi Hub. Vì vậy nếu Hub đang offline và sau đó nhân viên cứu SĐT thủ công trên SUNMI, Hub vẫn có thể nhận được cả `MISS` cũ và `CAPTURED manual` khi kết nối lại.

## Telemetry và lịch sử

SEEN/CAPTURED/MISS tiếp tục lịch sử từ v2.0.9. RISK chỉ bắt đầu phát sinh từ v2.0.10; không backfill RISK giả cho record cũ. Business orders 7 ngày cũ vẫn sync/export bình thường.

## Luồng capture giữ nguyên từ v2.0.8

1. Notification ShopeeFood được lưu local và xếp queue.
2. 1 giây sound grace.
3. Scheduler tạo processing lease cho một đơn.
4. contentIntent + settle/fallback như v2.0.8.
5. Xác nhận đúng Chi tiết đơn.
6. Mở Liên hệ → Khách nhận đơn.
7. Capture window 15 giây.
8. Lưu SĐT local trước.
9. Hub sync/telemetry chạy nền sau local state.

Safety fuse vẫn: tối đa 3 attempts, 30 giây/attempt, tối đa 8 phút tuổi auto, hết giới hạn → `needs_review`.

## Auto Hub Discovery

Giữ nguyên từ v2.0.7:
- UDP 17892.
- fallback private /24 `/api/discovery`.
- chỉ nhận Hub mới sau khi API key hiện tại xác thực thành công.

## Build / Update

- applicationId: `vn.orderrecorder.shopee`
- versionCode: `30`
- versionName: `2.0.10`
- artifact GitHub Actions: `shopeefood-order-recorder-final-v2.0.10`
- production updates require the same private signing lineage; the public repository does not include that keystore.

For an existing private deployment, in-place updates must preserve the original signing lineage and application data. The public GitHub edition is intended for source review/debug builds unless you configure your own secure signing.

Xem thêm `CHANGELOG-v2.0.10.md`, `WINDOW-LAYER-EARLY-RISK-v2.0.10.md` và `QA-v2.0.10.md`.
