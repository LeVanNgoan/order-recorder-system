# Migration policy

## MVC2 revision r1

Mục tiêu: đổi cấu trúc source mà không đổi nghiệp vụ production.

### Không thay trong r1

- SPF queue/retry/state-machine/timing.
- SPF phone parsing/capture behavior.
- GrabFood order detection/capture logic.
- Hub database schema và API behavior.
- retention 7 ngày.

### Thay đổi an toàn

- gom 3 project vào một monorepo;
- phân nhóm file theo Model / View / Controller / Service / Repository;
- Hub dashboard HTML trở thành file View độc lập;
- workflow build chuyển sang monorepo;
- sửa tên artifact SPF workflow từ `v2.0.8` thành `v2.0.10` (metadata build, không đổi runtime);
- thêm shared JSON schemas và QA automation.

### Quy tắc cho feature tiếp theo

Feature `Receiver-Only Phone Binding` phải được thực hiện **sau** structural refactor, trong một commit/release riêng để số liệu regression có thể quy nguyên nhân rõ ràng.
