# BUGFIX v2.0.4 — Stop Infinite Auto Retry

## Lỗi thực tế
Nếu một đơn không lấy được SĐT và sau đó ShopeeFood không còn hiển thị SĐT (ví dụ đơn đã giao), v2.0.2 có thể giữ đơn trong queue và tự click lại kéo dài.

## Nguyên nhân
Các giới hạn `MAX_ATTEMPTS` / `MAX_AUTO_AGE_MS` tồn tại trong model nhưng stable-core rollback chưa gọi `beginAttempt()` / `failAttempt()` trong live flow, nên chúng không thực sự chặn retry.

## Sửa
- Nối attempt counter vào live flow.
- 3 lần thử tối đa.
- 30 giây tối đa cho mỗi attempt.
- 8 phút là tuổi tối đa của auto task.
- Terminal → `needs_review` + remove queue + không auto-open lại.
- Update v2.0.2 → v2.0.4 xóa transient queue/state một lần, giữ nguyên orders.json và Hub config.
- Manual rescue vẫn hoạt động nhưng không tự click đối với order đã terminal/expired.
