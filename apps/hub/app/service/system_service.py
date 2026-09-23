from app.legacy import runtime as legacy

class SystemService:
    def device_status(self):
        return legacy.device_status()
    def heartbeat(self, payload):
        return legacy.upsert_heartbeat(payload)
    def backup_now(self):
        return legacy.create_database_backup(force=True)
