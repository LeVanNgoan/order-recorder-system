# QA v2.0.8

## Scope lock

Các file capture/data quan trọng sau được giữ byte-for-byte từ v2.0.7:

- `NodeUtil.java`
- `OrderStore.java`
- `TextParser.java`
- `OrderRecord.java`
- `AppLog.java`
- `CompletionNotifier.java`
- `ReviewNotifier.java`
- `TodayOrdersActivity.java`
- `XlsxExporter.java`
- `HubPrefs.java`
- `AndroidManifest.xml`
- Accessibility service config
- signing keystore

HubSync/HubDiscovery chỉ đổi version string sang 2.0.8. MainActivity ngoài version chỉ gọi process-start reconciliation trước khi dựng UI.

## Reliability invariants checked in source

1. Không còn call `adoptVisibleOrder()` từ Accessibility flow.
2. Scheduler là nơi duy nhất gọi `beginProcessing()` cho auto flow.
3. `beginProcessing()` tạo `PROCESSING_AT`.
4. `finishProcessing()`, cancel và migration đều xóa `PROCESSING_AT`.
5. Accessibility auto action yêu cầu `isProcessingLeaseValid()`.
6. Receiver không bị click lại khi phone capture window đang active.
7. Auto TYPE_VIEW_CLICKED không reset `CAPTURE_AT`.
8. Completed contact sheet return trước bind/stage/click/log loop.
9. `tryStartNext()` chạy stale-processing repair trước khi từ chối vì `isProcessing()`.
10. Normal timeout 30s và watchdog lease 32s.
11. Retry delay order vẫn được move cuối queue để fresh order không bị backoff order chặn.
12. contentIntent settle + final settle chỉ chạy trên navigation/failure path; không tạo polling worker.
13. Auto Hub Discovery v2.0.7 được giữ.
14. Process-start reconciliation release orphan in-flight state sau Android process death nhưng giữ queue.
15. `versionCode=28`, `versionName=2.0.8`, Hub heartbeat/discovery version=2.0.8.

## Static validation performed

- Java delimiter/string/comment balance: PASS trên toàn bộ source `.java`.
- Parser-level `javac` syntax scan: không phát hiện parser syntax error ở các file sửa. Môi trường đóng gói không có Android SDK nên APK release vẫn phải được xác nhận bằng GitHub Actions `gradle :app:assembleRelease`.
- ZIP integrity cần chạy `unzip -t` sau đóng gói.

## Production acceptance criteria

Trong technical log sau update:

- Không còn `AUTO tiếp quản đơn đang mở` / `AUTO tiếp quản bảng Liên hệ`.
- Một đơn không được spam `bấm đúng Khách nhận đơn` liên tục trong cùng 15s capture window.
- Không còn một `processing_order` cũ chặn queue hàng phút/giờ.
- Có thể thấy `RECOVER #...` khi ShopeeFood render muộn nhưng vẫn được cứu trong settle grace.
- Nếu timeout callback bất thường, `WATCHDOG #...` phải release state thay vì để kẹt.
- Completed order không sinh hàng trăm dòng `đã có SĐT · không ghi đè` nữa.
