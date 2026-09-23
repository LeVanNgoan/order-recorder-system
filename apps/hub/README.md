# Order Recorder Hub v2.2.5 — Reconciliation Management + Early Risk Alert

Bản này tiếp tục từ Hub v2.1.0 và biến Hub thành trung tâm quản lý + đối soát, nhưng vẫn giữ kiến trúc local-first: GrabFood/ShopeeFood tự ghi local trước, Hub chỉ nhận bản sao và telemetry nền.

## 1. Dashboard đối soát

Hub có endpoint `POST /api/order-events` (API key như `/api/orders`) để agent gửi bốn event idempotent:

- `SEEN`: agent đã phát hiện đơn.
- `CAPTURED`: agent đã lấy được SĐT; metadata `capture_mode=auto|manual|unknown`.
- `RISK`: cảnh báo sớm khi automation có dấu hiệu có thể miss nhưng vẫn còn tiếp tục xử lý.
- `MISS`: automation đã chuyển đơn sang trạng thái cần kiểm tra.

Dashboard tính theo `SEEN`, không dùng số record Hub làm mẫu số giả. Vì vậy một đơn đã được agent nhìn thấy nhưng chưa lấy được SĐT vẫn nằm trong đối soát.

Các chỉ số:
- Đơn phát hiện.
- Tự động có SĐT.
- RISK đang/có trong lịch sử.
- MISS automation.
- Đã backup thủ công sau MISS.
- Còn thiếu.
- Capture rate = tự động capture / SEEN.
- Completion rate = dữ liệu cuối cùng đã có SĐT / SEEN.

Nếu một nền tảng chưa gửi telemetry, Hub hiển thị **Chưa có telemetry từ agent** thay vì suy đoán tỷ lệ từ database.

## 2. Thùng rác + Khôi phục đơn

Xóa vẫn là soft delete như v2.1.0. Bản v2.2.2 thêm giao diện Thùng rác và thao tác **Khôi phục**.

Khôi phục bắt buộc:
- tên người thao tác;
- lý do khôi phục;
- mật khẩu admin.

Hub kiểm tra dedup trước khi restore. Nếu đã có record đang hoạt động trùng dữ liệu, Hub từ chối khôi phục để tránh tạo hai đơn giống nhau. Mọi thao tác restore ghi audit `RESTORE`.

Lưu ý: retention nghiệp vụ vẫn là 7 ngày; record trong Thùng rác cũng không được dùng để vượt chính sách retention này.

## 3. Trang chi tiết từng đơn

Bấm vào mã đơn trong danh sách hoặc Thùng rác để mở chi tiết:
- nền tảng;
- mã đơn;
- SĐT;
- thời gian nhận;
- nguồn ghi nhận;
- agent session;
- full order ID;
- trạng thái hiện tại;
- timeline hợp nhất.

Timeline gồm:
- record tạo tại Hub;
- `SEEN / RISK / CAPTURED / MISS` từ agent;
- tạo/bổ sung backup;
- chỉnh sửa;
- xóa;
- khôi phục;
- người thao tác + lý do từ audit log.

## 4. Backup database tự động mỗi ngày

Mặc định bật.

- Hub tạo tối đa một automatic backup mỗi ngày khi Hub đang chạy.
- Thử backup khi Hub khởi động và kiểm tra lại trong maintenance loop mỗi giờ.
- Dùng SQLite Online Backup API (`Connection.backup`) để tạo bản nhất quán ngay cả khi database dùng WAL; không copy thô `orders.db`.
- Giữ tối đa 7 ngày backup, đồng bộ với retention dữ liệu nghiệp vụ để backup không kéo dài thời gian lưu SĐT.
- Có nút **Sao lưu ngay** trong Cài đặt.

Thư mục mặc định Windows:

`%LOCALAPPDATA%\OrderRecorderHub\backups`

Tên file:

`orders_YYYY-MM-DD_HHMMSS.db`

Database nghiệp vụ vẫn ở `%LOCALAPPDATA%\OrderRecorderHub\orders.db`.

## 5. Quản lý đơn từ v2.1.0 vẫn giữ nguyên

- Thêm đơn thủ công / backup SĐT.
- `Grab` hiển thị thành `GrabFood`; internal key vẫn là `grab` để tương thích agent cũ.
- Danh sách 10 / 20 / 30 / 50 / 100 / Tất cả.
- Sửa đơn bắt buộc tên người thao tác + lý do.
- Xóa đơn bắt buộc tên người thao tác + lý do + mật khẩu admin.
- Audit log before/after.
- Cài đặt thu gọn.
- Tailscale remote dashboard.
- Auto Hub Discovery cho SUNMI.

## 6. Database migration

Không xóa `orders.db`.

v2.2.x tự thêm cột `agent_session_id` vào `orders` nếu chưa có và tạo bảng `order_events`. Các bảng/cột của v2.1.0 được giữ nguyên.

Giữ nguyên:
- đơn cũ;
- API key;
- Hub ID;
- admin password;
- Auto Discovery;
- Tailscale;
- audit log;
- config.

## 7. Quyền truy cập

- Dashboard/read/management: chỉ localhost hoặc Tailscale.
- `/api/orders`, `/api/heartbeat`, `/api/order-events`: API key authenticated, dùng cho agent LAN.
- API key vẫn bị ẩn khi xem Dashboard qua Tailscale.
- Đặt/đổi mật khẩu admin: chỉ trực tiếp trên PC Hub.

## 8. Agent compatibility

### ShopeeFood
Dùng ShopeeFood Recorder v2.0.10 để có `SEEN/RISK/CAPTURED/MISS` và window-layer detection. v2.0.10 giữ nguyên `AutomationPolicy/AppPrefs/NodeUtil/TextParser` của core ổn định và chỉ bổ sung quan sát đa-window + cảnh báo sớm.

### GrabFood
Dùng GrabFood extension v2.0.1 để có `SEEN/CAPTURED`. GrabFood không phát RISK/MISS suy đoán; nếu SEEN chưa CAPTURED thì Hub vẫn nhìn thấy đơn đang thiếu trong đối soát.

## 9. Cập nhật Hub

1. Tắt Hub cũ.
2. Backup source Hub cũ nếu muốn rollback.
3. Thay `hub.py` bằng v2.2.5 hoặc build lại EXE từ source mới.
4. **Không xóa** `%LOCALAPPDATA%\OrderRecorderHub`.
5. Mở Hub lại và kiểm tra Dashboard.
6. GrabFood v2.0.1 tiếp tục tương thích. Để có Early RISK + window-layer detection, cập nhật ShopeeFood lên v2.0.10 sau khi Hub v2.2.5 đã chạy.

Nếu dùng EXE đóng gói, phải build lại EXE; thay file `hub.py` bên ngoài không thay code đã đóng gói trong EXE.

## v2.2.3 UI hotfix
Bản v2.2.3 sửa trực tiếp template `DASHBOARD_HTML` trong `hub.py`. Hai bảng của Dashboard đối soát được tách thành hai hàng full-width và không còn horizontal scrollbar.


## v2.2.4 — Import Excel có mật khẩu admin

Hub có nút **Import Excel** trong phần Đơn hàng. Import yêu cầu file `.xlsx`, tên người thao tác, lý do và mật khẩu admin. Server xác minh mật khẩu trước khi xử lý file và tự tạo một bản backup database trước mọi thay đổi.

Format tối thiểu:
- `Mã đơn hàng`
- `Thời gian nhận đơn`

Khuyến nghị có thêm `SĐT`. Cột `Nền tảng` là tùy chọn nếu mã đã có `GF-` hoặc `SPF-`. Hub không tự ghi đè một SĐT khác đã có và không tự khôi phục record đang trong Thùng rác. Kết quả import hiển thị số dòng thêm mới, bổ sung SĐT, bỏ qua, conflict và lỗi.

Import vẫn tuân theo retention 7 ngày hiện tại.


## v2.2.5 — Early Risk Alert

Hub nhận thêm `RISK` telemetry. RISK được ưu tiên trong banner **CẦN KIỂM TRA NGAY** để nhân viên backup khi còn kịp. Khi CAPTURED tới sau RISK, cảnh báo active tự được giải quyết nhưng timeline vẫn giữ lịch sử. Nếu sau đó thành MISS, trạng thái chuyển đỏ.

Hub còn gửi cảnh báo trực tiếp lên Windows từ process Hub, không cần Dashboard đang mở. Cảnh báo chỉ chứa nền tảng + mã đơn, không chứa SĐT khách. Event retry không spam nhờ event key idempotent; event quá cũ khi sync bù không bật popup muộn. Trong Cài đặt có nút **Thử cảnh báo trên PC Hub**.

Native popup nên được kiểm tra trên chính PC cửa hàng vì Windows Focus Assist/Do Not Disturb và cách Hub được auto-start có thể ảnh hưởng phần hiển thị desktop.
