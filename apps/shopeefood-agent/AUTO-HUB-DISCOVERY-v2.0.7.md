# ShopeeFood Recorder v2.0.7 — Auto Hub Discovery

Bản này giữ nguyên core automation bắt SĐT của v2.0.6/v2.0.5 và chỉ nâng cấp lớp Hub/network.

## Mục tiêu
Khi PC Hub restart và router cấp IP LAN mới, SUNMI không còn phải nhập lại URL Hub thủ công.

## Luồng phục hồi
1. SPF vẫn thử URL Hub đang lưu như bình thường.
2. Nếu heartbeat thất bại, app chờ cooldown và chạy Auto Discovery trên background thread.
3. Gửi UDP broadcast `ORDER_RECORDER_DISCOVER_V1` tới port `17892`.
4. Hub v2.0.2 trả metadata không chứa API key.
5. SPF lấy IP từ **source address của UDP reply**, không tin mù quáng IP quảng bá.
6. SPF POST `/api/heartbeat` bằng API key đang lưu.
7. Chỉ khi HTTP 2xx mới lưu URL Hub mới và sync lại pending orders.
8. Nếu UDP bị chặn, app fallback scan private `/24` hiện tại và kiểm tra `GET /api/discovery`, sau đó vẫn phải xác thực API key.

## Chống quét liên tục
- Auto discovery chỉ chạy khi Hub đang offline.
- Cooldown tối thiểu 60 giây giữa các lần tự tìm.
- Subnet scan tối đa 2 private /24 và có deadline khoảng 3.2 giây.
- Toàn bộ discovery chạy trong `order-hub-sync`, không chạy trên Accessibility/Notification hot-path.

## Yêu cầu phía Hub
Dùng `Order Recorder Hub v2.0.2 Tailscale + Auto Discovery`:
- TCP Hub: `17891`
- UDP Discovery: `17892`
- Endpoint fallback: `/api/discovery`

Windows Firewall cần cho phép Hub/UDP 17892 trong mạng Private nếu firewall hỏi quyền.

## Bảo mật
Discovery không truyền API key và không truyền dữ liệu đơn/SĐT. Candidate Hub chỉ được chấp nhận sau khi xác thực bằng API key cũ.
