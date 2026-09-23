from app.legacy import runtime as legacy

class EventRepository:
    def upsert(self, payload: dict):
        return legacy.upsert_order_event(payload)
    def reconcile(self, start_date: str, end_date: str):
        return legacy.reconciliation_for_range(start_date, end_date)
