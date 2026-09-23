from app.service.system_service import SystemService

class SystemController:
    def __init__(self, service=None):
        self.service = service or SystemService()
    def heartbeat(self, payload):
        return {"ok": True, "device": self.service.heartbeat(payload)}
    def status(self):
        return self.service.device_status()
