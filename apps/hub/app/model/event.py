from dataclasses import dataclass, field
from typing import Any

@dataclass
class OrderEventDTO:
    platform: str
    event_type: str
    order_code: str = ""
    short_order_number: str = ""
    agent_session_id: str = ""
    event_at: str = ""
    reason: str = ""
    attempt_count: int = 0
    metadata: dict[str, Any] = field(default_factory=dict)
