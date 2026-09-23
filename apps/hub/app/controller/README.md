# Controller migration

New endpoints/features should be implemented here first. Existing v2.2.5 HTTP handler remains in `app/legacy/runtime.py` until each route has an equivalent regression-tested controller. Do not add new business logic to the legacy handler.
