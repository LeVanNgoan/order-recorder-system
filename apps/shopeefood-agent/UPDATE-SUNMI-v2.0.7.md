# Cập nhật SUNMI lên ShopeeFood Recorder v2.0.7

1. Cập nhật PC lên Hub v2.0.2 Tailscale + Auto Discovery trước.
2. Build APK bằng GitHub Actions; artifact: `shopeefood-order-recorder-final-v2.0.7`.
3. **Không uninstall SPF Recorder. Không Clear Data/Storage.**
4. Cài APK đè lên app hiện tại để giữ đơn, API key, URL Hub và technical black-box logs.
5. Mở app, xác nhận `v2.0.7`.
6. Kiểm tra Notification Listener + Accessibility vẫn bật.
7. Nhìn Hub card: Online và pending = 0.

## Test Auto Discovery
- Ghi lại IP PC hiện tại.
- Đổi IP LAN của PC hoặc để router cấp IP mới, sau đó restart Hub.
- Không sửa cấu hình SUNMI.
- Chờ khoảng 5–30 giây (trường hợp cần fallback có thể lâu hơn).
- SPF phải tự chuyển sang URL mới và hiện Online.
- Có thể bấm `Tự tìm lại Hub` để test ngay.

Nếu Windows Firewall hiện hộp thoại cho Python/Hub hoặc UDP 17892, chọn Allow access trên mạng Private.
