# Order Recorder System (Hệ thống Ghi nhận Đơn hàng)

> Một hệ thống tự động ghi nhận và đối soát đơn hàng đa nền tảng (GrabFood và ShopeeFood), hoạt động theo cơ chế ưu tiên lưu trữ cục bộ (local-first). Dự án được xây dựng bằng Python, Android Java, Chrome Extension Manifest V3, SQLite và tập trung vào tính ổn định thông qua hệ thống giám sát từ xa (telemetry).

[![Python](https://img.shields.io/badge/Python-Hub-3776AB?logo=python&logoColor=white)](apps/hub/)
[![Android](https://img.shields.io/badge/Android-Java-3DDC84?logo=android&logoColor=white)](apps/shopeefood-agent/)
[![Chrome Extension](https://img.shields.io/badge/Chrome-Manifest%20V3-4285F4?logo=googlechrome&logoColor=white)](apps/grabfood-extension/)
[![SQLite](https://img.shields.io/badge/SQLite-Local%20Storage-003B57?logo=sqlite&logoColor=white)](apps/hub/)
[![Architecture](https://img.shields.io/badge/Architecture-MVC2%20%2F%20Layered-blueviolet)](docs/ARCHITECTURE.md)

## Tổng quan

Order Recorder System là một dự án tự động hóa cấp độ thực chiến (production-oriented). Hệ thống được thiết kế để trích xuất siêu dữ liệu (metadata) của đơn hàng và thông tin liên hệ của khách hàng từ các nền tảng giao đồ ăn. Dữ liệu sau đó được lưu cục bộ, đồng bộ hóa (telemetry) về một máy chủ Windows (Hub) để đối soát, giúp phát hiện các vấn đề vận hành từ sớm nhằm tránh tình trạng mất mát dữ liệu ngầm.

Hệ thống là sự kết hợp của ba ứng dụng hoạt động độc lập:

- **Windows Hub** — Đóng vai trò là API trung tâm, lưu trữ bằng SQLite, đối soát dữ liệu, cung cấp dashboard, cảnh báo, sao lưu, import, kiểm toán (audit) và tự động nhận diện thiết bị.
- **ShopeeFood Agent** — Ứng dụng tự động hóa trên máy POS Android/SUNMI sử dụng Notification và Accessibility Service. Tích hợp cỗ máy trạng thái (state machine) có giới hạn, lưu trữ local-first, giới hạn số lần thử lại, ghi log kỹ thuật và cảnh báo rủi ro sớm.
- **GrabFood Extension** — Tiện ích mở rộng Chrome (MV3) dùng để giám sát trang quản lý của đối tác GrabFood, lưu đơn hàng cục bộ và đồng bộ dữ liệu đối soát ngầm.

Repository này hiện tại là phiên bản **tái cấu trúc theo mô hình MVC2 / Layered** từ một mã nguồn đã chạy ổn định thực tế. Việc cập nhật kiến trúc này nhằm giữ nguyên hành vi của ứng dụng hiện tại, đồng thời phân định rõ ràng các ranh giới Controller → Service → Repository → Model để dễ dàng bảo trì trong tương lai.

## Lý do tôi xây dựng hệ thống này

Bài toán kỹ thuật đặt ra ở đây không chỉ đơn giản là "đọc một số điện thoại".

Một hệ thống đáng tin cậy phải giải quyết được các vấn đề:

- Xử lý các thông báo và sự kiện trình duyệt bất đồng bộ;
- Trạng thái UI (giao diện) của bên thứ ba không ổn định;
- Độ trễ của Android Accessibility;
- Cơ chế bảo vệ để không bắt nhầm đơn hàng;
- Thử lại (retry) khi lỗi nhưng không rơi vào vòng lặp vô hạn;
- Xử lý nhiều đơn hàng đến cùng một lúc;
- Tiếp tục lưu dữ liệu cục bộ kể cả khi Hub (Máy chủ) mất kết nối;
- Gửi bù dữ liệu telemetry sau khi có mạng trở lại;
- Phân biệt rõ giữa lỗi "không nhận diện được" và lỗi "không lưu được";
- Cho phép người vận hành khôi phục và sửa lỗi thủ công;
- Xử lý các sự kiện trùng lặp (idempotent synchronization);
- Lưu log chẩn đoán lỗi nhưng vẫn phải đảm bảo quyền riêng tư của khách hàng;
- Duy trì tính ổn định của hệ thống đang chạy trong khi refactor mã nguồn cũ.

Những yếu tố này biến dự án trở thành một **bài toán về kỹ thuật hệ thống và độ tin cậy (reliability)**, chứ không chỉ là một ứng dụng CRUD thông thường.

---

## Kiến trúc Hệ thống

```mermaid
flowchart LR
    GF[GrabFood Merchant Web]
    SPF[ShopeeFood Merchant trên SUNMI]

    GE[GrabFood Chrome Extension]
    SA[ShopeeFood Android Agent]

    HUB[Windows Hub]
    DB[(SQLite)]
    DASH[Dashboard / Đối soát]
    ALERT[Cảnh báo Windows]

    GF --> GE
    SPF --> SA

    GE -->|SEEN / CAPTURED| HUB
    SA -->|SEEN / RISK / CAPTURED / MISS| HUB

    HUB --> DB
    HUB --> DASH
    HUB --> ALERT

    GE -. Hàng đợi cục bộ .-> GE
    SA -. Đơn hàng cục bộ + Telemetry chờ gửi .-> SA
