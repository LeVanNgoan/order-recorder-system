from pathlib import Path
import hashlib
root=Path(__file__).resolve().parent
j=root/'app/src/main/java/vn/orderrecorder/shopee'
expected={
 'AutomationPolicy.java':'12f3da17778f9a566a2f956c51eb05458bbf5034368de592683d6ae19f103a5b',
 'AppPrefs.java':'b29fa97f213f139a0f84be7079982367660081ea9336a287fec36267a5051ed9',
 'NodeUtil.java':'759b34bea3597da673f5a209ec64f6e61781d21bcd26b652135686a7169f5105',
}
for f,h in expected.items():
    got=hashlib.sha256((j/f).read_bytes()).hexdigest(); assert got==h,(f,got)
b=(root/'app/build.gradle').read_text(encoding='utf-8'); assert 'versionCode 29' in b and "versionName '2.0.9'" in b
assert 'shopeefood-order-recorder-final-v2.0.9' in (root/'.github/workflows/build-apk.yml').read_text(encoding='utf-8')
hs=(j/'HubSync.java').read_text(encoding='utf-8'); hd=(j/'HubDiscovery.java').read_text(encoding='utf-8'); acc=(j/'ShopeeAccessibilityService.java').read_text(encoding='utf-8'); nr=(j/'ShopeeNotificationListener.java').read_text(encoding='utf-8'); os=(j/'OrderStore.java').read_text(encoding='utf-8'); rec=(j/'OrderRecord.java').read_text(encoding='utf-8')
assert 'VERSION="2.0.9"' in hs and 'o.put("version", "2.0.9")' in hd
assert 'ensureTelemetryV209Migration' in hs and 'telemetry_v209_initialized' in os
assert 'r.missEventAt>0&&!r.hubMissEventSynced' in hs
for token in ['missEventAt','missEventReason','missEventAttempts']:
    assert token in rec
assert 'attachPhoneStrict(this,orderId,phone,wasProcessing?"auto":"manual")' in acc
assert 'setCaptureMode(this,doneId' not in acc
assert acc.count('void onDestroy()')==1 and nr.count('void onDestroy()')==1
print('V209_SOURCE_QA_PASS')
