import os,tempfile,importlib.util,threading,urllib.request,urllib.error,json
home=tempfile.mkdtemp(prefix='hub225http_');os.environ['HOME']=home;os.environ['LOCALAPPDATA']=home
from app.legacy import runtime as h;h.init_db()
alerts=[]
h.dispatch_pc_alert=lambda event,force=False: alerts.append((dict(event),force)) or True
server=h.ThreadingHTTPServer(('127.0.0.1',0),h.HubHandler);port=server.server_address[1]
t=threading.Thread(target=server.serve_forever,daemon=True);t.start()
def post(path,payload,key=None):
    headers={'Content-Type':'application/json'}
    if key is not None: headers['X-Order-Recorder-Key']=key
    req=urllib.request.Request(f'http://127.0.0.1:{port}{path}',data=json.dumps(payload).encode(),headers=headers,method='POST')
    try:
        with urllib.request.urlopen(req,timeout=5) as r:return r.status,json.loads(r.read())
    except urllib.error.HTTPError as e:return e.code,json.loads(e.read())
status,b=post('/api/alert-test',{})
assert status==200 and alerts and alerts[-1][1] is True,(status,b,alerts)
alerts.clear()
e={'platform':'shopeefood','eventType':'risk','orderCode':'SPF-4321','shortOrderNumber':'12','agentSessionId':'spf:test:12','eventAt':h.local_now_iso(),'reason':'phone_not_visible_after_receiver'}
status,b=post('/api/order-events',e,h.API_KEY);assert status==200,(status,b)
status,b=post('/api/order-events',e,h.API_KEY);assert status==200,(status,b)
assert len(alerts)==1 and alerts[0][0]['event_type']=='risk',alerts
print('HTTP_EARLY_ALERT_PASS',len(alerts))
server.shutdown();server.server_close()
