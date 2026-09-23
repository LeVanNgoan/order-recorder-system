# ShopeeFood Recorder v2.0.10 — Window Layer Detection + Early Risk

## Mục tiêu

Bản này xử lý đúng failure mode quan sát trực tiếp trên SUNMI: sau khi bấm **Khách nhận đơn**, lớp có SĐT đôi khi đã xuất hiện nhưng nằm phía sau khung **Liên hệ**. Đồng thời app phát `RISK` sớm để Hub cảnh báo PC khi vẫn còn cơ hội backup thủ công.

## 1. Multi-window phone detection — chỉ đọc

Sau auto-click `Khách nhận đơn`:

1. app chụp tập phone-candidate đang tồn tại **trước click**;
2. arm capture cho đúng order như core v2.0.9;
3. thực hiện click theo logic cũ;
4. chạy vài probe thưa qua `AccessibilityService.getWindows()`;
5. loại toàn bộ phone-candidate đã tồn tại trong baseline;
6. chỉ khi còn **đúng một SĐT VN mới** mới attach vào đúng order capture.

Không có Back/click/gesture bổ sung trong window scan. Không có worker polling mới.

Baseline chỉ nằm **trong RAM của AccessibilityService**, không thêm SharedPreferences write vào hot path.

## 2. Early RISK

Một order chỉ được tạo RISK một lần.

- Sau auto-click `Khách nhận đơn`, nếu 6.5 giây vẫn chưa đọc được SĐT: `phone_not_visible_after_receiver`.
- Nếu attempt gặp trouble sau khi đã vào các stage Contact/Dialer/Reading phone: phát RISK trước khi fail/defer như bình thường.
- Navigation failure chỉ phát RISK khi đơn đã đủ cũ (>=30 giây), tránh cảnh báo quá sớm cho transient render bình thường.

RISK **không dừng automation, không đổi attempt, không đổi queue và không tăng click**. App tiếp tục xử lý theo state machine v2.0.8.

Nếu Hub offline, RISK được lưu local trong `orders.json` và gửi bù sau. Nếu app sau đó CAPTURED, Hub có thể hiển thị RISK đã được tự khôi phục.

## 3. Reliability guard

Giữ nguyên byte-for-byte so với source v2.0.9 production:

- `AppPrefs.java`
- `AutomationPolicy.java`
- `NodeUtil.java`
- `TextParser.java`

Do đó không thay:

- 1 order tại một thời điểm;
- max attempts;
- 30s processing lease;
- 15s phone capture window;
- queue fairness;
- contentIntent settle/fallback;
- click cadence;
- safety fuse/needs_review.

## 4. Black-box diagnostics

Không log SĐT thật. Có thêm log dạng:

- `WINDOW_SCAN #...: windows=... eligible=... newPhoneCandidates=... captureAge=...`
- `WINDOW_SCAN #...: tìm thấy đúng 1 SĐT mới trong accessibility windows · lưu an toàn`
- `RISK #...: ... automation vẫn tiếp tục`

## Version

- versionCode: 30
- versionName: 2.0.10
- GitHub artifact: `shopeefood-order-recorder-final-v2.0.10`
