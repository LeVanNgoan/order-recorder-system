from app.service.order_service import OrderService

class OrderController:
    def __init__(self, service=None):
        self.service = service or OrderService()
    def create_or_update(self, payload: dict):
        order, created = self.service.save_agent_order(payload)
        return {"ok": True, "created": created, "order": order}, (201 if created else 200)
    def detail(self, order_id):
        return self.service.get_detail(order_id)
