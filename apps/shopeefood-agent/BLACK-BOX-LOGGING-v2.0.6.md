# v2.0.6 — Persistent Technical Black Box

Mục tiêu của v2.0.6 là thu thập bằng chứng kỹ thuật nhiều ngày để phân tích nguyên nhân gốc rễ của đơn ShopeeFood bị miss SĐT. Core automation burst-safe của v2.0.5 được giữ nguyên.

## Nhật ký được giữ như thế nào
- Lưu từng ngày trong private app storage: `technical_logs/YYYY-MM-DD.log`.
- Không áp dụng retention 7 ngày của dữ liệu đơn; log được giữ qua nhiều ngày và qua update APK.
- Log chỉ mất nếu **Uninstall app**, **Clear data/Clear storage**, hoặc người dùng chủ động xóa bằng code/tool tương lai.
- v2.0.6 cố gắng nhập tối đa 24 dòng log cũ còn lại từ cơ chế SharedPreferences trước đây.

## Không làm chậm capture
`AppLog.add()` chỉ enqueue sự kiện sang một single-thread background writer. Disk I/O không chạy trực tiếp trên Accessibility/notification hot-path.

Không ghi toàn bộ raw Accessibility events. Thay vào đó ghi các state transition có giá trị chẩn đoán: notification, queue, attempt, contentIntent, detail/contact/receiver, mismatch, retry, terminal miss, phone capture, PERF, Hub sync, service lifecycle.

## Xuất log
Trong app: **HỆ THỐNG → Xuất toàn bộ nhật ký kỹ thuật**.
App tạo file dạng `SPF_Technical_Log_YYYY-MM-DD_HHmm.txt`, ghép tất cả ngày theo thứ tự thời gian.

## Dữ liệu riêng tư
Technical log không chủ ý ghi full SĐT khách hàng. File có mã đơn, thời gian, trạng thái kỹ thuật và lý do lỗi để phân tích root cause.
