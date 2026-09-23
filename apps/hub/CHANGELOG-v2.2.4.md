# Hub v2.2.4 — Password-Protected Excel Import

## Import Excel (.xlsx)
- Thêm nút **Import Excel** trong khu vực Đơn hàng.
- Chỉ hỗ trợ `.xlsx`, tối đa 5 MB, tối đa 10.000 dòng mỗi lần import.
- Nhận trực tiếp format Excel Hub đang export: `Mã đơn hàng`, `SĐT`, `Thời gian nhận đơn`; `Nền tảng` là tùy chọn nếu mã đã có tiền tố `GF-` / `SPF-`.
- Tự nhận `GF-...` = GrabFood, `SPF-...` / `#...` = ShopeeFood.
- Chuẩn hóa SĐT Việt Nam và hỗ trợ thời gian ISO / `HH:mm:ss dd-MM-yyyy` / `dd/MM/yyyy HH:mm[:ss]`.

## Bảo vệ thao tác
- Import **bắt buộc**:
  - tên người thao tác;
  - lý do import;
  - mật khẩu admin hiện tại.
- Nếu chưa đặt mật khẩu admin hoặc nhập sai mật khẩu, Hub trả 403 và không thay đổi record nào.
- Trước khi thay đổi dữ liệu, Hub tự tạo một SQLite online backup an toàn.

## Quy tắc merge
- Đơn chưa có trong Hub: tạo mới.
- Đơn đã có nhưng thiếu SĐT: bổ sung SĐT.
- Đơn đã có đúng SĐT: bỏ qua.
- Đơn đã có SĐT khác: đưa vào Conflict, **không ghi đè tự động**.
- Đơn đang ở Thùng rác: đưa vào Conflict, không tự khôi phục.
- Dòng lỗi chỉ bị bỏ qua; các dòng hợp lệ khác vẫn tiếp tục import.

## Audit
- Ghi `IMPORT_CREATE` khi import tạo record mới.
- Ghi `IMPORT_FILL` khi import bổ sung SĐT.
- Audit lưu người thao tác, lý do, tên file, dòng Excel và snapshot before/after.

## Không thay đổi
- Database schema hiện tại.
- Reconciliation telemetry.
- Tailscale / Auto Discovery.
- GrabFood / ShopeeFood agent API.
- Retention dữ liệu 7 ngày.
