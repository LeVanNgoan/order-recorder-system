# Architecture — MVC2 / Layered Model 2

## 1. Luồng chuẩn

```text
External event / HTTP request / browser event
                ↓
           Controller
                ↓
             Service
                ↓
           Repository
                ↓
              Model
                ↓
        Database / Storage

Controller → View / JSON / Notification
```

Controller không nên chứa SQL hoặc persistence. View không truy cập database. Repository không quyết định workflow automation. Service chứa business rule.

## 2. Hub

- **Controller**: HTTP/API orchestration.
- **Service**: order, reconciliation, telemetry, import, backup, alert.
- **Repository**: SQLite/config persistence.
- **Model**: DTO/domain records.
- **View**: Dashboard HTML/JS.

Trong `mvc2-r1`, production runtime cũ được đặt trong `app/legacy/runtime.py` để tránh rewrite nguy hiểm. Các MVC2 adapter mới là điểm mở rộng chuẩn. Khi migrate một use case khỏi legacy, phải có regression test trước rồi mới thay route sang controller mới.

## 3. ShopeeFood Agent

Android entrypoints được coi là Controller. Repository quản lý local persistence. Service quản lý Hub/network/notifier. Policy/support giữ automation constants và parser. Java package declaration chưa đổi để tránh regression; chỉ filesystem được chuẩn hóa ở r1.

## 4. GrabFood Extension

- background service worker = Controller entrypoint;
- monitor content script = Controller/platform adapter;
- popup/viewer = View;
- xlsx builder = Service;
- storage/business split sẽ được migrate incremental ở revision sau.

## 5. Shared contracts

Mọi thay đổi payload giữa agent và Hub phải cập nhật schema trong `shared/contracts` trước khi release.
