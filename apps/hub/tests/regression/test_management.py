import os, tempfile, importlib.util, pathlib, json
test_root=tempfile.mkdtemp(prefix='hub210_home_')
os.environ['HOME']=test_root
os.environ['LOCALAPPDATA']=test_root
os.environ['APPDATA']=test_root
from app.legacy import runtime as h
assert pathlib.Path(test_root).resolve() in h.DB_PATH.resolve().parents, (test_root, h.DB_PATH)
# DB/config are isolated from the user's real Hub data.
h.init_db()
cols={r[1] for r in h.db_connect().execute('PRAGMA table_info(orders)').fetchall()}
for c in ['manual_backup','deleted','deleted_at','deleted_by','delete_reason']:
    assert c in cols, c
# create backup
row,action=h.manual_backup_order({'platform':'shopeefood','orderCode':'#1234','phone':'+84912345678','receivedAt':h.local_now_iso()})
assert action=='created'
assert row['order_code']=='SPF-1234' and row['phone']=='0912345678'
oid=row['id']
# edit requires operator/reason and works
edited=h.edit_order({'id':oid,'platform':'shopeefood','orderCode':'SPF-1235','phone':'0987654321','receivedAt':h.local_now_iso(),'operatorName':'Tester','reason':'Sửa mã và SĐT test'},'100.80.1.2')
assert edited['order_code']=='SPF-1235' and edited['phone']=='0987654321'
logs=h.get_audit_logs(10)
assert any(x['action']=='EDIT' and x['operator_name']=='Tester' for x in logs)
# admin password
assert not h.admin_password_is_set()
r=h.set_admin_password({'newPassword':'admin1234'})
assert r['admin_password_set'] and h.verify_admin_password('admin1234')
try:
    h.set_admin_password({'currentPassword':'wrong','newPassword':'newadmin12'})
    raise AssertionError('wrong old password accepted')
except PermissionError:
    pass
# delete wrong pw
try:
    h.soft_delete_order({'id':oid,'operatorName':'Tester','reason':'Xóa test','adminPassword':'wrong'},'127.0.0.1')
    raise AssertionError('wrong delete pw accepted')
except PermissionError:
    pass
# delete correct
res=h.soft_delete_order({'id':oid,'operatorName':'Tester','reason':'Xóa test','adminPassword':'admin1234'},'127.0.0.1')
assert res['deleted']
assert h._get_active_order(oid) is None
assert all(o['id']!=oid for o in h.query_orders(days=1))
assert any(x['action']=='DELETE' and x['operator_name']=='Tester' for x in h.get_audit_logs(10))
# same business code can be recreated after soft delete
row2,action2=h.manual_backup_order({'platform':'shopeefood','orderCode':'SPF-1235','phone':'0900000000','receivedAt':h.local_now_iso()})
assert action2=='created' and row2['id']!=oid
# GrabFood normalization and manual fill existing incomplete
agent,_=h.upsert_order({'platform':'grab','orderCode':'GF-225','phone':'','receivedAt':h.local_now_iso(),'recordedAt':h.local_now_iso(),'sourceDevice':'test'})
filled,act=h.manual_backup_order({'platform':'grab','orderCode':'225','phone':'0901111222','receivedAt':h.local_now_iso()})
assert act=='filled' and filled['phone']=='0901111222'
# audit actions
acts={x['action'] for x in h.get_audit_logs(50)}
assert {'CREATE_BACKUP','EDIT','DELETE','FILL_BACKUP'} <= acts
# UI text
assert 'Thêm đơn hàng mới' in h.DASHBOARD_HTML
assert 'Chỉ dùng backup khi GrabFood/ShopeeFood chưa ghi nhận được đơn hàng.' in h.DASHBOARD_HTML
assert '10 gần nhất' in h.DASHBOARD_HTML and '100 gần nhất' in h.DASHBOARD_HTML and '>Tất cả<' in h.DASHBOARD_HTML
assert 'GrabFood' in h.DASHBOARD_HTML
assert 'Nhật ký thao tác' in h.DASHBOARD_HTML and 'Mật khẩu admin bảo vệ thao tác xóa, khôi phục và Import Excel.' in h.DASHBOARD_HTML
print('ALL_MANAGEMENT_TESTS_PASS')
print('DB', h.DB_PATH)
