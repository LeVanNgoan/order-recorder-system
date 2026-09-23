from app.repository.order_repository import OrderRepository

class OrderService:
    def __init__(self, repository=None):
        self.repository = repository or OrderRepository()
    def save_agent_order(self, payload: dict):
        return self.repository.upsert(payload)
    def list_orders(self, **filters):
        return self.repository.query(**filters)
    def get_detail(self, order_id):
        return self.repository.detail(order_id)
