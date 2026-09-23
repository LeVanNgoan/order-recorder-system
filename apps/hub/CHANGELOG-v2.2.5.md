# Hub v2.2.5 — Early Risk Alert

## Mục tiêu

Báo cho nhân viên **trước khi ShopeeFood đi tới MISS terminal**, để còn thời gian nhìn SUNMI và backup SĐT thủ công. RISK chỉ là telemetry/cảnh báo; Hub không điều khiển automation ShopeeFood.

## RISK trong đối soát

`POST /api/order-events` nhận thêm event idempotent `RISK` bên cạnh `SEEN / CAPTURED / MISS`.

- RISK đang mở: đơn chưa CAPTURED và chưa MISS.
- CAPTURED sau RISK: cảnh báo active tự biến mất, timeline vẫn giữ lịch sử RISK và thống kê `risk_recovered`.
- MISS sau RISK: trạng thái ưu tiên thành MISS.
- Retry cùng event key không tạo cảnh báo PC lặp lại.

Dashboard có banner **⚠ CẦN KIỂM TRA NGAY** và đưa RISK/MISS lên trước, kèm nút `Thêm SĐT`.

## Cảnh báo native Windows

Khi Hub nhận một RISK mới còn свеж/đủ mới, Hub gửi cảnh báo trực tiếp từ process Hub:

- tiêu đề: `⚠ ShopeeFood có nguy cơ MISS SĐT`;
- nội dung chỉ có mã đơn, không chứa SĐT khách;
- âm thanh cảnh báo;
- cố gắng dùng Windows Toast, có NotifyIcon balloon fallback;
- hoạt động không phụ thuộc Dashboard Chrome đang mở.

RISK cũ hơn 180 giây khi sync bù vẫn được lưu vào đối soát/timeline nhưng **không bật native popup muộn**. MISS cũ hơn 10 phút cũng không bật popup muộn.

Trong `Cài đặt hệ thống` có nút **Thử cảnh báo trên PC Hub** để kiểm tra ngay trên Windows thật.

> Native desktop notification cần Hub chạy trong phiên Windows người dùng đang đăng nhập. Focus Assist/Do Not Disturb của Windows có thể ảnh hưởng cách popup hiển thị.

## Không thay đổi

- database orders hiện tại;
- Import Excel v2.2.4;
- daily SQLite backup;
- audit/edit/delete/trash/restore;
- Tailscale;
- Auto Discovery;
- API `/api/orders` và API key;
- GrabFood capture logic.
