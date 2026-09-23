from dataclasses import dataclass

@dataclass(frozen=True)
class OrderIdentity:
    platform: str
    order_code: str
    agent_session_id: str = ""

@dataclass
class OrderDTO:
    platform: str
    order_code: str
    phone: str = ""
    received_at: str = ""
    agent_session_id: str = ""
