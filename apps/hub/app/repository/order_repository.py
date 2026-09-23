from app.legacy import runtime as legacy

class OrderRepository:
    """Persistence adapter. Keeps SQL hidden from controllers/services."""
    def upsert(self, payload: dict):
        return legacy.upsert_order(payload)
    def query(self, limit=100, platform="", q=""):
        return legacy.query_orders(limit=limit, platform=platform, q=q)
    def detail(self, order_id):
        return legacy.get_order_detail(order_id)
    def trash(self, limit=200, q=""):
        return legacy.get_trash(limit=limit, q=q)
