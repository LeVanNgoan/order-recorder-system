from app.legacy import runtime as legacy

class ConfigRepository:
    def public_status(self) -> dict:
        return {
            "version": legacy.APP_VERSION,
            "port": legacy.PORT,
            "retention_days": legacy.RETENTION_DAYS,
            "hub_id": legacy.HUB_ID,
        }
