import os, tempfile, sys, base64, io, importlib.util
from datetime import datetime
from openpyxl import Workbook

tmp=tempfile.mkdtemp(prefix='hub224test_')
os.environ['LOCALAPPDATA']=tmp
os.environ['HOME']=tmp
from app.legacy import runtime as h
h.init_db()
h.set_admin_password({'newPassword':'Admin1234!'})
now=h.local_now_iso()
# existing blank order to fill
r,_=h.upsert_order({'platform':'shopeefood','orderCode':'SPF-1001','displayOrderId':'#1001','phone':'','receivedAt':now,'sourceDevice':'test'})
# existing completed order to conflict
r2,_=h.upsert_order({'platform':'grab','orderCode':'GF-3001','phone':'0911111111','receivedAt':now,'sourceDevice':'test'})

wb=Workbook();ws=wb.active
ws.append(['STT','Mã đơn hàng','SĐT','Thời gian nhận đơn'])
ws.append([1,'GF-2001','0901234567',datetime.now()])
ws.append([2,'SPF-1001','0987654321',datetime.now()])
ws.append([3,'GF-3001','0922222222',datetime.now()])
b=io.BytesIO();wb.save(b)
payload={'filename':'import-test.xlsx','contentBase64':base64.b64encode(b.getvalue()).decode(),'operatorName':'Tester','reason':'QA import','adminPassword':'Admin1234!'}
out=h.import_xlsx_orders(payload,'127.0.0.1')
print('RESULT',out)
assert out['created']==1, out
assert out['updated']==1, out
assert out['conflicts']==1, out
with h.db_connect() as c:
    count=c.execute('select count(*) from orders').fetchone()[0]
    phone=c.execute("select phone from orders where order_code='SPF-1001'").fetchone()[0]
    audits=[x[0] for x in c.execute("select action from order_audit_log order by id").fetchall()]
assert count==3, count
assert phone=='0987654321', phone
assert 'IMPORT_CREATE' in audits and 'IMPORT_FILL' in audits, audits
# wrong password must fail before changes
try:
    h.import_xlsx_orders({**payload,'adminPassword':'wrong'},'127.0.0.1')
    raise AssertionError('wrong password accepted')
except PermissionError:
    pass
print('PASS')
