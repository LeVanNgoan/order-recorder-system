import os, tempfile, importlib

# Isolate data before legacy runtime import.
root=tempfile.mkdtemp(prefix="hubmvc2_")
os.environ["HOME"]=root
os.environ["LOCALAPPDATA"]=root

from app.legacy import runtime as legacy
from app.controller.order_controller import OrderController
from app.controller.telemetry_controller import TelemetryController

legacy.init_db()
order={"platform":"grab","orderCode":"GF-123","phone":"0912345678","receivedAt":legacy.local_now_iso(),"sourceDevice":"mvc2-test"}
body,status=OrderController().create_or_update(order)
assert status in (200,201) and body["ok"]
event={"platform":"grab","eventType":"seen","orderCode":"GF-123","agentSessionId":"mvc2:1","eventAt":legacy.local_now_iso()}
out=TelemetryController().accept(event)
assert out["ok"]
print("MVC2_ADAPTERS_PASS")
