# Update SUNMI lên v2.0.10

1. Cập nhật **Hub v2.2.5** trước.
2. Trên PC Hub mở `Cài đặt hệ thống` và bấm **Thử cảnh báo trên PC Hub**.
3. Build source v2.0.10 bằng GitHub Actions.
4. Cài APK release **đè** lên app đang có.
5. Tuyệt đối **không Uninstall** và **không Clear Data**.
6. Sau update kiểm tra:
   - Recorder v2.0.10;
   - Notification Access bật;
   - Accessibility bật;
   - Hub Online;
   - technical logs vẫn còn.

Update giữ applicationId/signing lineage như source trước; cài đè giữ orders, queue bền vững, Hub URL/API key và technical black-box logs. Nếu Android báo signature conflict, dừng lại và kiểm tra artifact/signing, không uninstall để chữa cháy.
