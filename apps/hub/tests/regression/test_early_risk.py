import os, tempfile, importlib.util, pathlib
from datetime import datetime, timedelta, timezone

root=tempfile.mkdtemp(prefix='hub225-risk-')
os.environ['HOME']=root
os.environ['LOCALAPPDATA']=root
from app.legacy import runtime as h
h.PC_ALERTS_ENABLED=False
h.init_db()
now=h.local_now_iso(); day=h.date_part(now)
# Active risk must appear before terminal MISS.
h.upsert_order_event({'platform':'shopeefood','eventType':'seen','shortOrderNumber':'77','orderCode':'SPF-7777','agentSessionId':'spf:risk','eventAt':now})
h.upsert_order_event({'platform':'shopeefood','eventType':'risk','shortOrderNumber':'77','orderCode':'SPF-7777','agentSessionId':'spf:risk','eventAt':now,'reason':'phone_not_visible_after_receiver','attemptCount':1})
r=h.reconciliation_for_range(day,day); sp=r['platforms']['shopeefood']
assert sp['seen']==1 and sp['risk']==1 and sp['risk_active']==1 and sp['miss']==0,sp
assert len(r['issues'])==1 and r['issues'][0]['status']=='risk',r['issues']
# Capture later resolves the active warning but preserves risk history.
h.upsert_order_event({'platform':'shopeefood','eventType':'captured','shortOrderNumber':'77','orderCode':'SPF-7777','agentSessionId':'spf:risk','eventAt':now,'metadata':{'capture_mode':'auto'}})
r=h.reconciliation_for_range(day,day); sp=r['platforms']['shopeefood']
assert sp['risk']==1 and sp['risk_active']==0 and sp['risk_recovered']==1 and sp['data_complete']==1,sp
assert not r['issues'],r['issues']
# Terminal miss has priority over risk in issue status.
h.upsert_order_event({'platform':'shopeefood','eventType':'seen','shortOrderNumber':'78','orderCode':'SPF-7778','agentSessionId':'spf:miss','eventAt':now})
h.upsert_order_event({'platform':'shopeefood','eventType':'risk','shortOrderNumber':'78','orderCode':'SPF-7778','agentSessionId':'spf:miss','eventAt':now,'reason':'notification_navigation_delayed','attemptCount':1})
h.upsert_order_event({'platform':'shopeefood','eventType':'miss','shortOrderNumber':'78','orderCode':'SPF-7778','agentSessionId':'spf:miss','eventAt':now,'reason':'notification_card_not_found','attemptCount':3})
r=h.reconciliation_for_range(day,day)
issue=next(x for x in r['issues'] if x['order_code']=='SPF-7778')
assert issue['status']=='miss' and issue['reason']=='notification_card_not_found',issue
# Alert payload never includes customer phone data.
title,body,severity=h._pc_alert_payload({'platform':'shopeefood','event_type':'risk','order_code':'SPF-7778'})
assert 'SĐT' in title and 'SPF-7778' in body and severity=='risk'
# Stale risk should not generate a native alert after Hub was offline for a long time.
old=(datetime.now(timezone(timedelta(hours=7)))-timedelta(minutes=10)).isoformat(timespec='seconds')
h.PC_ALERTS_ENABLED=True
assert h.dispatch_pc_alert({'platform':'shopeefood','event_type':'risk','order_code':'SPF-OLD','event_at':old}) is False
html=h.DASHBOARD_HTML
for token in ['CẦN KIỂM TRA NGAY','RISK','Cảnh báo sớm trên PC','testPcAlert','statustag.risk']:
    assert token in html,token
print('EARLY_RISK_ALL_PASS')
