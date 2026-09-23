# ShopeeFood Recorder v2.0.8 — State Machine Reliability Fix

## Mục tiêu

Bản này được tạo từ log kỹ thuật thực tế 5 ngày. Không thêm worker song song, overlay, rapid polling hay cơ chế "thông minh" mới. Mục tiêu duy nhất là loại các trạng thái có thể làm một đơn chiếm automation quá lâu và giảm false failure khi ShopeeFood render chậm.

## Root cause được sửa

### 1. Accessibility tự nhận lại đơn sau khi attempt đã kết thúc
Ở v2.0.7, sau `ATTEMPT_RESULT`, `processing_order` được release nhưng nếu màn hình đơn cũ vẫn hiện thì Accessibility có thể `adoptVisibleOrder()` và tiếp tục click mà không có timeout mới. Điều này có thể giữ `processing_order` và chặn queue.

**v2.0.8:** chỉ Notification Scheduler được quyền tạo processing session. Mỗi session có `PROCESSING_AT` + lease. Accessibility chỉ thao tác khi đúng `processing_order` và lease còn hiệu lực.

### 2. Click `Khách nhận đơn` có thể tự gia hạn capture window liên tục
Ở v2.0.7, mỗi Accessibility event trên contact sheet có thể gọi `beginPhoneCapture()` lại, làm `CAPTURE_AT` bị reset và click receiver lặp lại.

**v2.0.8:** sau một click receiver thành công, trong toàn bộ `PHONE_CAPTURE_MS=15s` tuyệt đối không click lại và không reset `CAPTURE_AT`. TYPE_VIEW_CLICKED do auto click tạo ra cũng không bị hiểu nhầm là manual click.

### 3. Navigation bị kết luận fail trước khi ShopeeFood render xong
Log cho thấy nhiều `notification_card_not_found` / `notification_open_verify_failed` xảy ra trước khi đúng detail xuất hiện rất ngắn sau đó.

**v2.0.8:** contentIntent có settle grace 1.4s; trước khi xác nhận navigation fail có final settle grace 2.0s. Đây là delay chỉ ở failure path, không busy-poll và không làm chậm success path nếu Accessibility đã thấy đúng detail.

### 4. Wrong-screen recovery chạy cạnh tranh với contentIntent
Màn hình đơn cũ thường còn tồn tại trong lúc contentIntent của đơn mới đang load. Accessibility cũ có thể mở notification shade quá sớm.

**v2.0.8:** mismatch recovery không được chạy trước khi contentIntent settle window kết thúc.

### 5. Completed order vẫn bị scan/log lặp
**v2.0.8:** completed là terminal state thực sự trong contact-sheet flow. Không bind/click/log lặp lại đơn đã có SĐT.

## Process death recovery

- In-flight UI ownership (`processing_order`, stage, contact/capture binding) không được phép sống qua Android process death vì Handler timeout của process cũ đã mất.
- Component đầu tiên của process mới release in-flight state đúng một lần, **giữ queue**, rồi scheduler bắt đầu lại attempt có lease.

## Watchdog

- Normal attempt timeout: 30s.
- Processing lease backup: 32s.
- Alarm watchdog vẫn chạy định kỳ làm lớp phòng thủ nếu callback timeout bị mất do process/OEM scheduling.
- Watchdog có thể release stale processing, defer retry hoặc đưa sang `needs_review` theo đúng 3 attempts / 8 phút.

## Không thay đổi

- Notification capture source.
- Node parser / phone parser.
- Strict phone-to-order binding.
- OrderStore local save path.
- 7-day retention.
- Hub sync, heartbeat, Force Resync.
- Auto Hub Discovery v2.0.7.
- Persistent technical black-box logs.
- applicationId và signing keystore.

## Log mới cần quan sát

- `RECOVER #...: UI xuất hiện trong settle grace`
- `WATCHDOG #...: release stale processing`
- `AUTO #...: đúng đơn đã hiển thị · tiếp tục trong attempt mới`
- `ATTEMPT_RESULT ...`

Kỳ vọng sau v2.0.8: không còn chuỗi một order click `Khách nhận đơn` vô hạn và không còn `processing_order` cũ chặn nhiều notification mới qua hàng phút/giờ.
