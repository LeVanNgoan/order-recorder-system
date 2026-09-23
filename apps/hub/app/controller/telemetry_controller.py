from app.service.telemetry_service import TelemetryService

class TelemetryController:
    def __init__(self, service=None):
        self.service = service or TelemetryService()
    def accept(self, payload: dict):
        if isinstance(payload, dict) and isinstance(payload.get("events"), list):
            rows = [self.service.accept(x) for x in payload["events"][:500]]
            return {"ok": True, "events": rows, "count": len(rows)}
        return {"ok": True, "event": self.service.accept(payload)}
