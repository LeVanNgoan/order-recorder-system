# Hub MVC2

## Entry
`hub.py` chỉ khởi động runtime. View đã tách khỏi Python core.

## Model
DTO/domain objects trong `app/model`.

## Repository
Persistence boundary trong `app/repository`. Revision r1 dùng adapter tới proven v2.2.5 runtime; khi migrate SQL ra repository phải giữ regression tests pass.

## Service
Business use-case boundary: orders, telemetry/reconciliation, system.

## Controller
API/controller boundary cho feature mới. Không thêm business rule mới vào HTTP handler legacy.

## View
`app/view/templates/dashboard.html` là source of truth. Runtime load file này thay cho HTML literal 48 KB trước đây.

## Legacy bridge
`app/legacy/runtime.py` giữ hành vi v2.2.5 để migration cấu trúc không biến thành một rewrite production. `MIGRATION-MANIFEST.json` xác nhận AST của toàn bộ 80 function/class giữ nguyên; chỉ `DASHBOARD_HTML` được externalize.
