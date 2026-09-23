# v2.0.4 — Cảnh báo đơn bị miss

- Giữ nguyên stable automation core của v2.0.3.
- Khi một đơn hết giới hạn auto mà vẫn chưa có SĐT: chuyển `needs_review`, dừng auto vĩnh viễn với đơn đó và phát Android notification ưu tiên cao.
- MainActivity có mục **ĐƠN BỊ MISS HÔM NAY** liệt kê mã đơn + thời gian nhận.
- Danh sách Đơn hôm nay gắn nhãn `⚠ CẦN KIỂM TRA` cho record chưa có SĐT.
- Notification mở trực tiếp màn hình Đơn hôm nay để nhân viên đối chiếu/ghi thủ công.
