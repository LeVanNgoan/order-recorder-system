# v2.0.5 — Burst-safe performance update

Mục tiêu: giữ stable core một đơn tại một thời điểm, nhưng bỏ các khoảng chờ cố định không cần thiết giữa các bước/đơn.

- Sound grace vẫn 1000 ms.
- Accessibility scan: 90 ms -> 18 ms; event batching 120 ms -> 60 ms.
- Retry click trong cùng màn hình: 950 ms -> 260 ms.
- Sau khi một đơn thất bại và bị đưa về cuối queue, queue khác được xử lý sau ~120 ms thay vì đứng chờ 2800 ms.
- Sau khi một đơn đã lưu SĐT, nếu còn queue thì chuyển ngay đơn kế tiếp; không Back + chờ trước.
- Retry delay 2800 ms vẫn áp dụng riêng cho chính đơn lỗi, để không click spam.
- Giữ MAX_ATTEMPTS=3, MAX_AUTO_AGE=8 phút và cảnh báo Cần kiểm tra.
- Không overlay, không parallel worker, không xử lý hai đơn cùng lúc.
- Log PERF cho từng đơn để đo thời gian xử lý thật trên SUNMI.
