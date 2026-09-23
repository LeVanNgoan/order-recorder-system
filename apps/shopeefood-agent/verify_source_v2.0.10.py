from pathlib import Path
import hashlib, re, sys

ROOT=Path(__file__).resolve().parent
SRC=ROOT/'app/src/main/java/vn/orderrecorder/shopee'
EXPECTED_STABLE={
    'AppPrefs.java':'b29fa97f213f139a0f84be7079982367660081ea9336a287fec36267a5051ed9',
    'AutomationPolicy.java':'12f3da17778f9a566a2f956c51eb05458bbf5034368de592683d6ae19f103a5b',
    'NodeUtil.java':'759b34bea3597da673f5a209ec64f6e61781d21bcd26b652135686a7169f5105',
    'TextParser.java':'ca9ffdb6e70004bfc00d6933a857c1de7c8b3730ee26eebff6cb8930b28a010e',
}

def sha(p): return hashlib.sha256(p.read_bytes()).hexdigest()
def need(cond,msg):
    if not cond:
        print('FAIL:',msg)
        sys.exit(1)
    print('PASS:',msg)

build=(ROOT/'app/build.gradle').read_text(encoding='utf-8')
workflow=(ROOT.parent.parent/'.github/workflows/build-spf.yml').read_text(encoding='utf-8')
find=lambda name: next(SRC.rglob(name))
main=find('MainActivity.java').read_text(encoding='utf-8')
hub=find('HubSync.java').read_text(encoding='utf-8')
disc=find('HubDiscovery.java').read_text(encoding='utf-8')
access=find('ShopeeAccessibilityService.java').read_text(encoding='utf-8')
listener=find('ShopeeNotificationListener.java').read_text(encoding='utf-8')
store=find('OrderStore.java').read_text(encoding='utf-8')
record=find('OrderRecord.java').read_text(encoding='utf-8')
risk=find('RiskPolicy.java').read_text(encoding='utf-8')
xml=(ROOT/'app/src/main/res/xml/accessibility_service_config.xml').read_text(encoding='utf-8')

need("versionCode 30" in build and "versionName '2.0.10'" in build,'Android versionCode/name = 30 / 2.0.10')
need('shopeefood-order-recorder-final-v2.0.10' in workflow,'GitHub artifact version synchronized')
need('VERSION = "2.0.10"' in main and 'VERSION="2.0.10"' in hub and '"version", "2.0.10"' in disc,'UI/Hub heartbeat/discovery version synchronized')
for name,h in EXPECTED_STABLE.items():
    need(sha(find(name))==h,f'{name} unchanged byte-for-byte from v2.0.9 stable source')
need('flagRetrieveInteractiveWindows' in xml and 'canRetrieveWindowContent="true"' in xml,'Accessibility interactive-window retrieval enabled')
need('getWindows()' in access and 'windowCaptureBaseline' in access and 'candidates.removeAll(windowCaptureBaseline)' in access,'multi-window scan uses pre-click baseline')
need('receiverContextVisible' in access and 'capture.equals(AppPrefs.getContactOrder(this))' in access,'multi-window save requires current receiver/contact context')
need('RiskPolicy.WINDOW_PROBE_DELAYS_MS' in access and 'RiskPolicy.EARLY_RISK_AFTER_RECEIVER_MS' in access and 'RiskPolicy.WINDOW_EVENT_SCAN_THROTTLE_MS' in access,'window probes/RISK use observation-only policy')
need('setCaptureBaseline' not in access and 'getCaptureBaseline' not in access and 'phoneCaptureAge' not in access,'no new SharedPreferences capture-baseline hot-path')
scan=re.search(r'private void scanAllCaptureWindows\(\)\{(.*?)\n    \}',access,re.S)
need(scan is not None,'multi-window scan method found')
scan_body=scan.group(1)
need('performGlobalAction' not in scan_body and 'dispatchGesture' not in scan_body and 'performAction' not in scan_body,'multi-window scan is read-only (no Back/click/gesture)')
need('markRisk' in store and 'hubRiskEventSynced' in record and 'riskEventAt' in record,'RISK persisted idempotently in order record')
need('postEvent(c,r,"risk")' in hub and '"early_warning"' in hub,'RISK telemetry sync enabled')
need('RiskPolicy.NAVIGATION_RISK_MIN_AGE_MS' in listener and 'maybeRaiseEarlyRisk' in listener,'attempt trouble can emit early RISK without changing failure flow')
need(access.count('void onDestroy()')==1,'Accessibility has exactly one onDestroy()')
need('MAX_ATTEMPTS' not in risk and 'STALE_PROCESSING_MS' not in risk and 'PHONE_CAPTURE_MS' not in risk,'RiskPolicy does not redefine capture/queue/retry limits')
print('V2.0.10_SOURCE_VERIFICATION_PASS')
