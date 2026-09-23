# Thiết kế Window Layer + Early RISK v2.0.10

## Vì sao cần multi-window

`getRootInActiveWindow()` chỉ đại diện cho window đang active. Trên SUNMI đã quan sát trường hợp SĐT xuất hiện ở một accessibility window khác nhưng bảng `Liên hệ` vẫn nằm trên. v2.0.10 không cố đóng bảng Liên hệ; nó chỉ đọc thêm các interactive window hiện có.

## Chống ghép nhầm SĐT

Multi-window không được phép lấy một số ngẫu nhiên chỉ vì thấy nó ở background. Vì vậy:

- baseline được lấy ngay trước auto-click receiver;
- candidate phải qua `TextParser.normalizePhone()` như cũ;
- candidate đã có trước click bị loại;
- phải còn chính xác 1 candidate mới;
- capture order phải trùng processing order khi auto mode đang sở hữu lease;
- completed order không được ghi lại.

Nếu 0 hoặc >1 candidate, app không ghi gì và tiếp tục core cũ.

## Probe schedule

Read-only probes: 260ms, 900ms, 2200ms, 5000ms sau click. Accessibility event trong capture window cũng có thể schedule một scan nhẹ đã throttle.

Không có vòng lặp 50/100ms liên tục.

## Early RISK

RISK là warning, không phải terminal state. Một order chỉ persist RISK đầu tiên; retry telemetry cùng event key không tạo thêm notification PC.
