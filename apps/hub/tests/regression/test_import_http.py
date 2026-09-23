import os,tempfile,importlib.util,threading,urllib.request,urllib.error,json,base64,io
from datetime import datetime
from openpyxl import Workbook
home=tempfile.mkdtemp(prefix='hub224http_');os.environ['HOME']=home;os.environ['LOCALAPPDATA']=home
from app.legacy import runtime as h;h.init_db();h.set_admin_password({'newPassword':'Admin1234!'})
server=h.ThreadingHTTPServer(('127.0.0.1',0),h.HubHandler);port=server.server_address[1]
t=threading.Thread(target=server.serve_forever,daemon=True);t.start()
wb=Workbook();ws=wb.active;ws.append(['Mã đơn hàng','SĐT','Thời gian nhận đơn']);ws.append(['GF-777','0901234567',datetime.now()]);bio=io.BytesIO();wb.save(bio)
base={'filename':'http.xlsx','contentBase64':base64.b64encode(bio.getvalue()).decode(),'operatorName':'QA','reason':'HTTP test'}
def post(pw):
    data=json.dumps({**base,'adminPassword':pw}).encode();req=urllib.request.Request(f'http://127.0.0.1:{port}/api/import-xlsx',data=data,headers={'Content-Type':'application/json'},method='POST')
    try:
        with urllib.request.urlopen(req,timeout=5) as r:return r.status,json.loads(r.read())
    except urllib.error.HTTPError as e:return e.code,json.loads(e.read())
status,b=post('wrong');assert status==403,(status,b)
status,b=post('Admin1234!');assert status==200 and b['created']==1,(status,b)
print('HTTP_IMPORT_PASS',status,b['created'],b['safety_backup']['name'])
server.shutdown();server.server_close()
