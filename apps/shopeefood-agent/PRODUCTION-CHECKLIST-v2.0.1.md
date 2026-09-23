# Production checklist v2.0.1

- [ ] v2.0.1 / versionCode 21
- [ ] applicationId vn.orderrecorder.shopee
- [ ] signing keystore giữ nguyên
- [ ] Sound grace 1.0 s
- [ ] Accessibility notificationTimeout 20 ms
- [ ] Strict receiver binding
- [ ] Single-pass receiver/phone scan
- [ ] Notification persistence không chặn hot path
- [ ] Hub thread không giữ OrderStore lock khi ghi file
- [ ] Successful phone capture flush file nền, RAM cập nhật ngay
- [ ] AtomicFile chống hỏng orders.json khi ghi dở
- [ ] 5 attempts, queue rotation
- [ ] Hub test xác thực cả API key
- [ ] Force resync 7 days giữ nguyên
- [ ] Dữ liệu nghiệp vụ vẫn 4 cột
- [ ] Cài đè, không uninstall
