import os, tempfile, importlib.util, pathlib, json
root=tempfile.mkdtemp(prefix='hub220-test-')
os.environ['HOME']=root
os.environ['LOCALAPPDATA']=root
from app.legacy import runtime as h
h.init_db()
# schema
with h.db_connect() as c:
    cols={r[1] for r in c.execute('pragma table_info(orders)').fetchall()}
    assert 'agent_session_id' in cols
    assert c.execute("select name from sqlite_master where type='table' and name='order_events'").fetchone()
# telemetry SPF: one capture, one miss recovered by manual backup, one unresolved miss
now=h.local_now_iso(); day=h.date_part(now)
for sid, short, code, typ in [
    ('spf:a','101','SPF-1001','captured'),
    ('spf:b','102','SPF-1002','miss'),
    ('spf:c','103','SPF-1003','miss'),
    ('spf:d','104','SPF-1004','miss')]:
    h.upsert_order_event({'platform':'shopeefood','eventType':'seen','shortOrderNumber':short,'agentSessionId':sid,'eventAt':now,'sourceDevice':'test'})
    h.upsert_order_event({'platform':'shopeefood','eventType':typ,'shortOrderNumber':short,'orderCode':code,'agentSessionId':sid,'eventAt':now,'reason':'test miss' if typ=='miss' else '', 'attemptCount':3})

# Manual rescue on SUNMI after a terminal miss: captured event is data-complete but not automatic capture.
h.upsert_order_event({'platform':'shopeefood','eventType':'captured','shortOrderNumber':'104','orderCode':'SPF-1004','agentSessionId':'spf:d','eventAt':now,'metadata':{'capture_mode':'manual'}})
# captured order row
row,_=h.upsert_order({'platform':'shopeefood','orderCode':'SPF-1001','displayOrderId':'#1001','shortOrderNumber':'101','phone':'0912345671','receivedAt':now,'recordedAt':now,'sourceDevice':'test','agentSessionId':'spf:a'})
# manual recovered miss B
manual,act=h.manual_backup_order({'platform':'shopeefood','orderCode':'SPF-1002','phone':'0912345672','receivedAt':now,'agentSessionId':'spf:b'})
assert act=='created'
r=h.reconciliation_for_range(day,day)
sp=r['platforms']['shopeefood']
assert sp['seen']==4,sp
assert sp['captured']==1,sp
assert sp['miss']==3,sp
assert sp['data_complete']==3,sp
assert sp['manual_backup_recovered']==1,sp
assert sp['manual_rescue']==1,sp
assert sp['recovered_after_miss']==2,sp
assert sp['unresolved']==1,sp
assert len(r['issues'])==1 and r['issues'][0]['order_code']=='SPF-1003'
# event upsert is idempotent
before=None
with h.db_connect() as c: before=c.execute('select count(*) from order_events').fetchone()[0]
h.upsert_order_event({'platform':'shopeefood','eventType':'seen','shortOrderNumber':'101','agentSessionId':'spf:a','eventAt':now,'sourceDevice':'test2'})
with h.db_connect() as c: after=c.execute('select count(*) from order_events').fetchone()[0]
assert before==after
# detail combines event timeline
d=h.get_order_detail(row['id'])
assert d['order']['agent_session_id']=='spf:a'
assert any(x['type']=='seen' for x in d['timeline']) and any(x['type']=='captured' for x in d['timeline'])
# delete + trash + restore
h.set_admin_password({'newPassword':'12345678'})
h.soft_delete_order({'id':row['id'],'operatorName':'Admin','reason':'test delete','adminPassword':'12345678'},'127.0.0.1')
trash=h.get_trash(100,'SPF-1001');assert len(trash)==1 and trash[0]['deleted']
rest=h.restore_order({'id':row['id'],'operatorName':'Admin','reason':'restore test','adminPassword':'12345678'},'127.0.0.1')
assert not rest['deleted']
assert not h.get_trash(100,'SPF-1001')
d=h.get_order_detail(row['id']);assert any(x['type']=='RESTORE' for x in d['timeline'])
# daily DB backup
b=h.create_database_backup(force=True);assert b['ok'] and pathlib.Path(b['file']).exists()
con=__import__('sqlite3').connect(b['file']);count=con.execute('select count(*) from orders').fetchone()[0];con.close();assert count>=2
# HTML required management features
html=h.DASHBOARD_HTML
for token in ['Dashboard đối soát','Thùng rác + Khôi phục đơn','Timeline đơn hàng','Sao lưu database tự động mỗi ngày','10 gần nhất','100 gần nhất','GrabFood','ShopeeFood']:
    assert token in html,token
print('V220_ALL_PASS', json.dumps({'seen':sp['seen'],'captured':sp['captured'],'miss':sp['miss'],'complete':sp['data_complete'],'backup':pathlib.Path(b['file']).name},ensure_ascii=False))
