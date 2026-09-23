# Update SUNMI — v2.0.9

1. Update Hub lên v2.2.0 trước.
2. Build APK v2.0.9 bằng GitHub Actions.
3. Cài APK **đè** lên app hiện tại.
4. Không Uninstall, không Clear Data.
5. Mở Recorder, kiểm tra Notification Access + Accessibility vẫn bật.
6. Kiểm tra Hub hiển thị ShopeeFood v2.0.9.
7. Dashboard đối soát bắt đầu thu SEEN/CAPTURED/MISS cho các đơn mới từ thời điểm update.

Rollback: giữ ZIP/source v2.0.8 last-known-good. Nếu Android từ chối downgrade APK theo versionCode, build rollback với versionCode cao hơn nhưng source core v2.0.8; không uninstall để tránh mất dữ liệu/log.
