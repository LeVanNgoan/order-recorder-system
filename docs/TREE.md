# Source tree

```text
OrderRecorder-System-MVC2/
├── apps/
│   ├── hub/
│   │   ├── hub.py                  # production entrypoint
│   │   ├── run.py
│   │   ├── app/
│   │   │   ├── model/
│   │   │   ├── repository/
│   │   │   ├── service/
│   │   │   ├── controller/
│   │   │   ├── view/
│   │   │   │   ├── templates/dashboard.html
│   │   │   │   └── static/js/dashboard.js
│   │   │   └── legacy/runtime.py  # v2.2.5 behavior-preserving core
│   │   └── tests/
│   │       ├── regression/
│   │       └── test_mvc2_adapters.py
│   │
│   ├── shopeefood-agent/
│   │   └── app/src/main/java/vn/orderrecorder/shopee/
│   │       ├── controller/
│   │       ├── view/
│   │       ├── model/
│   │       ├── repository/
│   │       ├── service/
│   │       ├── policy/
│   │       ├── support/
│   │       └── export/
│   │
│   └── grabfood-extension/
│       ├── service-worker.js       # bootstrap
│       ├── manifest.json
│       ├── assets/
│       └── src/
│           ├── controller/
│           ├── service/
│           ├── view/
│           └── legacy/
│
├── shared/contracts/
│   ├── order-v1.schema.json
│   ├── telemetry-v1.schema.json
│   ├── heartbeat-v1.schema.json
│   ├── discovery-v1.schema.json
│   └── draft/telemetry-receiver-v2.schema.json
│
├── scripts/
│   ├── qa_all.py
│   └── package_release.py
│
├── docs/
├── .github/workflows/
├── VERSION.json
└── MIGRATION-MANIFEST.json
```
