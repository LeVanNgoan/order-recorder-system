# Cập nhật SUNMI lên ShopeeFood Recorder v2.0.8

1. Không uninstall bản đang dùng và không Clear Data.
2. Push source lên GitHub rồi chạy workflow `Build ShopeeFood Order Recorder APK`.
3. Artifact phải là `shopeefood-order-recorder-final-v2.0.8`.
4. Cài `app-release.apk` đè trực tiếp lên app hiện tại.
5. Mở app và xác nhận version `2.0.8`.
6. Kiểm tra Notification Access + Accessibility vẫn bật.
7. Kiểm tra Hub Online. Auto Hub Discovery v2.0.7 vẫn được giữ nguyên.
8. Không cần nhập lại API key hoặc xóa orders/logs.

## Sau update

Lần chạy đầu v2.0.8 sẽ xóa **chỉ in-flight state automation tạm** để chắc chắn không mang theo một `processing_order` kẹt từ bản cũ. Queue đang chờ được giữ để scheduler mới tự prune/retry an toàn. Không xóa `orders.json`, Hub config, API key hay technical logs.

## Rollout khuyến nghị

- Test đầu tiên trong một ca có thể quan sát 20–30 đơn.
- Sau đó để chạy ca thực tế bình thường và export technical log cuối ca/ngày.
- Không chỉnh timer tiếp nếu chưa có log chứng minh lỗi mới.
