from app.repository.event_repository import EventRepository

class TelemetryService:
    ALLOWED_EVENTS = {"seen", "risk", "captured", "miss"}
    def __init__(self, repository=None):
        self.repository = repository or EventRepository()
    def accept(self, payload: dict):
        return self.repository.upsert(payload)
    def reconciliation(self, start_date: str, end_date: str):
        return self.repository.reconcile(start_date, end_date)
