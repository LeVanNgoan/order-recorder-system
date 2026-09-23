# GrabFood extension structure

- `service-worker.js`: compatibility bootstrap only.
- `src/controller/service-worker-controller.js`: background controller.
- `src/controller/grab-monitor.js`: merchant-page controller/platform adapter.
- `src/service/xlsx-builder.js`: export service.
- `src/view/`: popup and viewer.
- `src/legacy/grab-content.js`: preserved unused legacy source for diff/history.
- `assets/`: icons.

MVC2 r1 changes file paths only. Grab monitor/business behavior is unchanged.
