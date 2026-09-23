# QA v2.0.10

## Static/source verification

Chạy:

`python verify_source_v2.0.10.py`

Verifier kiểm tra version đồng bộ, 4 file stable-core giữ nguyên SHA-256, interactive window flag, baseline safety, no extra UI action trong multi-window scan, RISK persistence/sync và chỉ một `onDestroy()`.

## Java parser check

Môi trường đóng gói source không có Android SDK nên không thể `assembleRelease` tại đây. `javac -proc:none` được dùng như parser sanity check: lỗi dự kiến là thiếu Android/org.json symbols; không có lỗi cú pháp Java (`';' expected`, `illegal start`, `reached end of file`, ...).

Bước build cuối phải chạy GitHub Actions `assembleRelease`.

## Production smoke test đề xuất

1. Cập nhật Hub v2.2.5 trước và bấm `Thử cảnh báo trên PC Hub`.
2. Cài APK v2.0.10 **đè** v2.0.9.
3. Không uninstall/Clear Data.
4. Test vài đơn bình thường.
5. Nếu gặp đúng case SĐT bị che sau Liên hệ, xem technical log có `WINDOW_SCAN ... lưu an toàn`.
6. Nếu phone chưa đọc được sau receiver, PC phải nhận RISK trước terminal MISS.
7. Xác nhận automation vẫn tiếp tục sau RISK và nếu tự cứu được thì Hub bỏ cảnh báo active.
