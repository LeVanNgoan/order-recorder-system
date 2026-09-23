import json
import socket
import sys

port = 17892
s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
s.setsockopt(socket.SOL_SOCKET, socket.SO_BROADCAST, 1)
s.settimeout(3)
s.bind(("", 0))
s.sendto(b"ORDER_RECORDER_DISCOVER_V1", ("255.255.255.255", port))
try:
    data, addr = s.recvfrom(4096)
    print("Hub found:", addr[0])
    print(json.dumps(json.loads(data.decode("utf-8")), ensure_ascii=False, indent=2))
except socket.timeout:
    print("Không tìm thấy Hub qua UDP. Kiểm tra cùng LAN và Windows Firewall UDP 17892.")
    sys.exit(1)
