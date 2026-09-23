package vn.orderrecorder.shopee;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.content.Intent;
import android.graphics.Path;
import android.graphics.Rect;
import android.os.Handler;
import android.os.Looper;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;
import android.widget.Toast;
import java.util.List;
import java.util.LinkedHashSet;
import java.util.Set;

public final class ShopeeAccessibilityService extends AccessibilityService {
    private static final String SHOPEE="com.shopeepay.merchant.vn";
    private static final String SYSTEM_UI="com.android.systemui";
    private static volatile ShopeeAccessibilityService instance;
    private final Handler handler=new Handler(Looper.getMainLooper());

    private long lastShopeeScan=0L,lastPhoneScan=0L,lastLayeredPhoneScan=0L,lastContactLog=0L,lastNoPhoneLog=0L;
    private long lastDetailClick=0L,lastReceiverClick=0L,lastMismatchLog=0L,lastMismatchRecover=0L,lastWindowScanLog=0L;
    private long lastShadeOpenAt=0L;
    private String lastExternalPkg="",lastShadeOrder="";

    // v2.0.10 observation-only state. Kept in memory on purpose so the proven
    // AppPrefs/state-machine persistence remains byte-for-byte unchanged.
    private final LinkedHashSet<String> windowCaptureBaseline=new LinkedHashSet<>();
    private String windowCaptureBaselineOrder="";
    private long receiverClickObservedAt=0L;

    @Override protected void onServiceConnected(){
        instance=this;
        if(AppPrefs.reconcileProcessStart(this))AppLog.add(this,"PROCESS RECOVERY: đã release in-flight state từ process cũ; queue được giữ");
        // Whichever Android service reconnects first after update must kill stale retry state.
        AppPrefs.ensureRetrySafetyMigration(this);
        boolean migrated=AppPrefs.ensureV208ReliabilityMigration(this);
        HubSync.start(this);
        AutoStartReceiver.scheduleWatchdog(this);
        AppLog.add(this,"SERVICE ACCESSIBILITY connected · v2.0.10 reliability + window-layer phone detection");
        if(migrated)AppLog.add(this,"MIGRATION v2.0.8: đã xóa in-flight state cũ; giữ queue + orders + Hub + technical logs");
    }

    @Override public void onDestroy(){AppLog.add(this,"SERVICE ACCESSIBILITY destroyed");if(instance==this)instance=null;super.onDestroy();}

    public static void requestNotificationTapFallback(String orderId){
        ShopeeAccessibilityService x=instance;
        if(x==null){ShopeeNotificationListener.requestNext();return;}
        x.handler.post(()->x.openAndTapNotification(orderId));
    }

    /** Inspect the actual foreground Accessibility tree once. Used only at attempt boundaries. */
    public static boolean isExpectedOrderVisibleNow(String orderId){
        ShopeeAccessibilityService x=instance;
        if(x==null||orderId==null||orderId.isEmpty())return false;
        try{
            AccessibilityNodeInfo root=x.getRootInActiveWindow();if(root==null)return false;
            String text=NodeUtil.text(root);
            if(text.contains("Chi tiết đơn hàng")){
                TextParser.Detail d=TextParser.detail(text);
                if(!d.shortId.isEmpty())return orderId.equals(d.shortId);
            }
            return text.contains("Liên hệ khách hàng")&&text.contains("Khách nhận đơn")
                    &&orderId.equals(AppPrefs.getContactOrder(x));
        }catch(Exception ignored){return false;}
    }

    public static void requestResumeVisibleOrder(String orderId){
        ShopeeAccessibilityService x=instance;if(x==null)return;
        x.handler.post(()->{
            if(orderId!=null&&orderId.equals(AppPrefs.getProcessingOrder(x)))x.scanShopee();
        });
    }

    private void openAndTapNotification(String orderId){
        if(orderId==null||orderId.isEmpty())return;
        if(!canDriveAuto(orderId))return;
        long now=System.currentTimeMillis();
        if(orderId.equals(lastShadeOrder)&&now-lastShadeOpenAt<650L)return;
        boolean opened=performGlobalAction(GLOBAL_ACTION_NOTIFICATIONS);
        AppLog.add(this,opened?"AUTO #"+orderId+": đã mở thanh thông báo":"AUTO #"+orderId+": không mở được thanh thông báo");
        if(opened){
            lastShadeOrder=orderId;lastShadeOpenAt=now;
            handler.postDelayed(()->tapNotificationFromShade(orderId,0),320L);
        }
    }

    private void tapNotificationFromShade(String orderId,int attempt){
        if(!canDriveAuto(orderId))return;
        AccessibilityNodeInfo root=getRootInActiveWindow();
        if(NodeUtil.clickNotificationForOrder(root,orderId)){
            AppLog.add(this,"AUTO #"+orderId+": đã click đúng card thông báo");
            handler.postDelayed(()->verifyNotificationTap(orderId),900L);
            return;
        }
        if(attempt<6){
            handler.postDelayed(()->tapNotificationFromShade(orderId,attempt+1),180L);
        }else{
            AppLog.add(this,"AUTO #"+orderId+": chưa thấy card notification · đóng shade và chờ UI settle lần cuối");
            performGlobalAction(GLOBAL_ACTION_BACK);
            handler.postDelayed(()->finalNavigationCheck(orderId,"notification_card_not_found"),AutomationPolicy.FINAL_NAVIGATION_SETTLE_MS);
        }
    }

    private void verifyNotificationTap(String orderId){
        if(!canDriveAuto(orderId))return;
        if(isExpectedOrderVisibleNow(orderId)||!AppPrefs.STAGE_OPENING_ORDER.equals(AppPrefs.getStage(this))){
            requestResumeVisibleOrder(orderId);return;
        }
        AppLog.add(this,"AUTO #"+orderId+": click notification xong nhưng UI chưa settle · chờ xác nhận lần cuối");
        handler.postDelayed(()->finalNavigationCheck(orderId,"notification_open_verify_failed"),AutomationPolicy.FINAL_NAVIGATION_SETTLE_MS);
    }

    private void finalNavigationCheck(String orderId,String failureReason){
        if(!canDriveAuto(orderId))return;
        if(isExpectedOrderVisibleNow(orderId)||orderId.equals(AppPrefs.getActiveDetailOrder(this))
                ||!AppPrefs.STAGE_OPENING_ORDER.equals(AppPrefs.getStage(this))){
            AppLog.add(this,"RECOVER #"+orderId+": UI xuất hiện trong settle grace · tiếp tục attempt hiện tại");
            requestResumeVisibleOrder(orderId);
            return;
        }
        AppLog.add(this,"AUTO #"+orderId+": hết settle grace · xác nhận "+failureReason);
        ShopeeNotificationListener.reportAttemptFailure(orderId,failureReason);
    }

    @Override public void onAccessibilityEvent(AccessibilityEvent event){
        if(!AppPrefs.isEnabled(this)||event==null)return;
        String pkg=event.getPackageName()==null?"":event.getPackageName().toString();

        if(SHOPEE.equals(pkg)){
            if(event.getEventType()==AccessibilityEvent.TYPE_VIEW_CLICKED)inspectShopeeClick(event.getSource());
            scheduleShopeeScan();
            // v2.0.10: the phone layer can exist behind the still-visible Contact sheet.
            // While capture is armed, also inspect every interactive Accessibility window.
            if(AppPrefs.isPhoneCaptureActive(this))scheduleLayeredPhoneScan();
            else clearWindowCaptureObservation();
            return;
        }
        if(getPackageName().equals(pkg)||SYSTEM_UI.equals(pkg))return;

        if(AppPrefs.isPhoneCaptureActive(this))schedulePhoneScan(pkg,eventSnapshot(event));
        else clearWindowCaptureObservation();
    }

    private void scheduleShopeeScan(){
        long now=System.currentTimeMillis();if(now-lastShopeeScan<55L)return;
        lastShopeeScan=now;handler.postDelayed(this::scanShopee,18L);
    }

    private void scanShopee(){
        AccessibilityNodeInfo root=getRootInActiveWindow();if(root==null)return;
        String processingNow=AppPrefs.getProcessingOrder(this);
        if(!processingNow.isEmpty()&&!AppPrefs.isProcessingLeaseValid(this,processingNow)){
            ShopeeNotificationListener.requestNext(); // scheduler owns timeout/release; Accessibility must not self-adopt.
        }
        String text=NodeUtil.text(root);

        if(text.contains("Chi tiết đơn hàng")){
            TextParser.Detail d=TextParser.detail(text);
            if(!d.shortId.isEmpty()){
                String previous=AppPrefs.getActiveDetailOrder(this);
                if(!previous.isEmpty()&&!previous.equals(d.shortId))AppPrefs.clearContactBinding(this);
                AppPrefs.setActiveDetailOrder(this,d.shortId);
                OrderStore.updateDetails(this,d.shortId,d.display,d.full);

                if(AppPrefs.isAutoEnabled(this)&&!OrderStore.isCompleted(this,d.shortId)&&OrderStore.canContinueAuto(this,d.shortId)){
                    String processing=AppPrefs.getProcessingOrder(this);
                    // v2.0.8: Accessibility never creates a new automatic processing session.
                    // Only the notification scheduler may own an attempt + timeout lease.
                    if(d.shortId.equals(processing)&&canDriveAuto(processing)){
                        AppPrefs.setStage(this,AppPrefs.STAGE_DETAIL);
                        autoOpenContact(root,processing);
                    }else if(!processing.isEmpty()){
                        logMismatch(processing,d.shortId);
                        recoverExpectedOrder(processing);
                    }
                }
            }
        }

        if(text.contains("Liên hệ khách hàng")&&text.contains("Khách nhận đơn")){
            String boundOrder=AppPrefs.getActiveDetailOrder(this);
            if(boundOrder.isEmpty()){
                throttledLog("Thấy bảng Liên hệ nhưng chưa xác định được mã đơn · không ghi SĐT",2500L);
                return;
            }

            String processing=AppPrefs.getProcessingOrder(this);

            // Completed is a true terminal state. Do not keep re-binding/logging/clicking the old sheet.
            if(OrderStore.isCompleted(this,boundOrder)){
                if(boundOrder.equals(AppPrefs.getContactOrder(this)))AppPrefs.clearContactBinding(this);
                return;
            }

            // If another leased attempt owns automation, never manipulate the visible wrong order.
            if(AppPrefs.isAutoEnabled(this)&&!processing.isEmpty()&&!boundOrder.equals(processing)){
                logMismatch(processing,boundOrder);
                recoverExpectedOrder(processing);
                return;
            }

            Rect receiver=NodeUtil.findLabelBounds(root,"Khách nhận đơn");
            Rect purchaser=NodeUtil.findLabelBounds(root,"Khách hàng");
            int ry=receiver==null?Integer.MIN_VALUE:receiver.centerY();
            int py=purchaser==null?Integer.MIN_VALUE:purchaser.centerY();
            AppPrefs.markContactSheet(this,boundOrder,ry,py); // also enables explicit manual rescue.

            boolean autoOwnsSheet=canDriveAuto(boundOrder);
            if(autoOwnsSheet)AppPrefs.setStage(this,AppPrefs.STAGE_CONTACT_SHEET);

            long now=System.currentTimeMillis();
            if(autoOwnsSheet&&now-lastContactLog>1800L){
                lastContactLog=now;
                AppLog.add(this,"Đã khóa bảng Liên hệ vào #"+boundOrder+" · chỉ dùng Khách nhận đơn");
            }

            String receiverPhone=NodeUtil.singlePhoneOnRow(root,"Khách nhận đơn","Khách hàng");
            if(!receiverPhone.isEmpty()){
                saveReceiverPhone(boundOrder,receiverPhone,"trực tiếp dòng Khách nhận đơn",false);
                return;
            }

            if(autoOwnsSheet)autoClickReceiver(root,boundOrder);
        }
    }

    private void autoOpenContact(AccessibilityNodeInfo root,String id){
        if(!canDriveAuto(id))return;
        long now=System.currentTimeMillis();if(now-lastDetailClick<AutomationPolicy.CONTACT_RETRY_CLICK_MS)return;
        String stage=AppPrefs.getStage(this);
        if(AppPrefs.STAGE_CONTACT_SHEET.equals(stage)||AppPrefs.STAGE_READING_PHONE.equals(stage)||AppPrefs.STAGE_OPENING_DIALER.equals(stage)||AppPrefs.STAGE_OPENING_CONTACT.equals(stage)&&AppPrefs.stageAge(this)<650L)return;
        lastDetailClick=now;

        boolean nodeClick=NodeUtil.clickPhoneOnRow(root,"Khách hàng");
        boolean gesture=false;
        if(!nodeClick)gesture=tapRightSideOfRow(root,"Khách hàng");
        if(nodeClick||gesture){
            AppPrefs.setStage(this,AppPrefs.STAGE_OPENING_CONTACT);
            OrderStore.setStatus(this,id,"opening_contact");
            AppLog.add(this,"AUTO #"+id+": mở bảng Liên hệ ("+(nodeClick?"node":"gesture")+")");
        }else if(AppPrefs.stageAge(this)>650L){
            AppLog.add(this,"AUTO #"+id+": chưa bấm được nút Liên hệ; sẽ thử lại");
        }
    }

    private void autoClickReceiver(AccessibilityNodeInfo root,String id){
        if(!canDriveAuto(id)||!id.equals(AppPrefs.getContactOrder(this)))return;
        // Critical v2.0.8 fix: a successful receiver click owns a 15s capture window.
        // Never click it again while that window is active, otherwise CAPTURE_AT is refreshed forever.
        if(AppPrefs.isPhoneCaptureActive(this)&&id.equals(AppPrefs.getCaptureOrder(this)))return;
        long now=System.currentTimeMillis();if(now-lastReceiverClick<AutomationPolicy.RECEIVER_RETRY_CLICK_MS)return;
        lastReceiverClick=now;

        // Capture a baseline BEFORE the receiver click. Any persistent phone-like text already
        // visible in another window is excluded from the post-click candidate set.
        Set<String> baseline=collectWindowPhoneCandidates();
        // Khóa ID trước khi click. Dù Dialer/phone layer mở nhanh, số chỉ có thể vào đúng id này.
        if(!AppPrefs.beginPhoneCapture(this,id))return;
        armWindowCaptureObservation(id,baseline);
        AppPrefs.setStage(this,AppPrefs.STAGE_OPENING_DIALER);
        boolean nodeClick=NodeUtil.clickPhoneOnRow(root,"Khách nhận đơn");
        boolean gesture=false;
        if(!nodeClick)gesture=tapRightSideOfRow(root,"Khách nhận đơn");
        if(nodeClick||gesture){
            OrderStore.setStatus(this,id,"opening_dialer");
            AppLog.add(this,"AUTO #"+id+": bấm đúng Khách nhận đơn ("+(nodeClick?"node":"gesture")+") · chờ SĐT");
            scheduleCaptureObservation(id);
        }else{
            AppPrefs.clearPhoneCapture(this);
            clearWindowCaptureObservation();
            AppPrefs.setStage(this,AppPrefs.STAGE_CONTACT_SHEET);
            AppLog.add(this,"AUTO #"+id+": chưa bấm được icon Khách nhận đơn; sẽ thử lại");
        }
    }

    private boolean tapRightSideOfRow(AccessibilityNodeInfo root,String label){
        Rect lr=NodeUtil.findLabelBounds(root,label);if(lr==null||lr.isEmpty())return false;
        Rect wr=new Rect();root.getBoundsInScreen(wr);if(wr.isEmpty())return false;
        float density=getResources().getDisplayMetrics().density;
        float x=wr.right-Math.max(48f*density,wr.width()*0.055f);
        float y=lr.centerY();
        Path path=new Path();path.moveTo(x,y);
        GestureDescription.StrokeDescription stroke=new GestureDescription.StrokeDescription(path,0,70);
        return dispatchGesture(new GestureDescription.Builder().addStroke(stroke).build(),null,null);
    }

    private void inspectShopeeClick(AccessibilityNodeInfo source){
        if(!AppPrefs.contactSheetWasRecent(this))return;
        String orderId=AppPrefs.getContactOrder(this);if(orderId.isEmpty())return;
        String around=NodeUtil.text(source);
        boolean receiver=around.contains("Khách nhận đơn")||NodeUtil.clickMatchesCachedReceiverRow(source,AppPrefs.getReceiverY(this),AppPrefs.getPurchaserY(this));
        if(!receiver)return; // click Khách hàng không bao giờ arm capture.
        if(OrderStore.isCompleted(this,orderId))return;
        // Auto click also emits TYPE_VIEW_CLICKED. Do not reset CAPTURE_AT or mislabel it as manual.
        if(AppPrefs.isPhoneCaptureActive(this)&&orderId.equals(AppPrefs.getCaptureOrder(this)))return;
        if(AppPrefs.beginPhoneCapture(this,orderId))AppLog.add(this,"MANUAL: xác nhận click Khách nhận đơn · khóa SĐT vào #"+orderId);
    }

    private void armWindowCaptureObservation(String id,Set<String> baseline){
        windowCaptureBaseline.clear();
        if(baseline!=null)windowCaptureBaseline.addAll(baseline);
        windowCaptureBaselineOrder=id==null?"":id;
        receiverClickObservedAt=System.currentTimeMillis();
    }

    private void clearWindowCaptureObservation(){
        windowCaptureBaseline.clear();
        windowCaptureBaselineOrder="";
        receiverClickObservedAt=0L;
    }

    private long windowCaptureAge(String id){
        if(id==null||!id.equals(windowCaptureBaselineOrder)||receiverClickObservedAt<=0L)return 0L;
        return Math.max(0L,System.currentTimeMillis()-receiverClickObservedAt);
    }

    private void scheduleLayeredPhoneScan(){
        long now=System.currentTimeMillis();if(now-lastLayeredPhoneScan<RiskPolicy.WINDOW_EVENT_SCAN_THROTTLE_MS)return;
        lastLayeredPhoneScan=now;
        handler.postDelayed(this::scanAllCaptureWindows,24L);
    }

    private void scheduleCaptureObservation(String id){
        // Sparse read-only probes; this is intentionally NOT a polling worker and never clicks UI.
        for(long delay:RiskPolicy.WINDOW_PROBE_DELAYS_MS){
            handler.postDelayed(()->{
                if(id.equals(AppPrefs.getCaptureOrder(this))&&AppPrefs.isPhoneCaptureActive(this))scanAllCaptureWindows();
            },delay);
        }
        handler.postDelayed(()->{
            if(!id.equals(AppPrefs.getCaptureOrder(this))||!AppPrefs.isPhoneCaptureActive(this)||OrderStore.isCompleted(this,id))return;
            // One final read before warning. If it succeeds, no RISK event is emitted.
            scanAllCaptureWindows();
            if(OrderStore.isCompleted(this,id))return;
            if(OrderStore.markRisk(this,id,"phone_not_visible_after_receiver")){
                AppLog.add(this,"RISK #"+id+": đã bấm Khách nhận đơn nhưng "+RiskPolicy.EARLY_RISK_AFTER_RECEIVER_MS+"ms chưa đọc được SĐT · automation vẫn tiếp tục");
                HubSync.kick(this);
            }
        },RiskPolicy.EARLY_RISK_AFTER_RECEIVER_MS);
    }

    private Set<String> collectWindowPhoneCandidates(){
        LinkedHashSet<String> out=new LinkedHashSet<>();
        try{
            List<AccessibilityWindowInfo> windows=getWindows();
            if(windows!=null)for(AccessibilityWindowInfo w:windows){
                if(w==null)continue;
                AccessibilityNodeInfo root=w.getRoot();if(root==null)continue;
                String pkg=root.getPackageName()==null?"":root.getPackageName().toString();
                if(getPackageName().equals(pkg)||SYSTEM_UI.equals(pkg))continue;
                out.addAll(TextParser.phoneCandidates(NodeUtil.text(root)));
            }
        }catch(Exception ignored){}
        return out;
    }

    private void scanAllCaptureWindows(){
        if(!AppPrefs.isPhoneCaptureActive(this)){clearWindowCaptureObservation();return;}
        String capture=AppPrefs.getCaptureOrder(this);
        if(capture.isEmpty()||OrderStore.isCompleted(this,capture)){clearWindowCaptureObservation();return;}
        // Only the auto receiver click arms layered-window matching with a pre-click baseline.
        // Manual rescue keeps the proven v2.0.9 capture path and is never broadened implicitly.
        if(!capture.equals(windowCaptureBaselineOrder))return;
        if(!capture.equals(AppPrefs.getContactOrder(this)))return;
        String processing=AppPrefs.getProcessingOrder(this);
        if(AppPrefs.isAutoEnabled(this)&&!processing.isEmpty()&&!capture.equals(processing))return;

        LinkedHashSet<String> candidates=new LinkedHashSet<>();
        int windowsCount=0,eligibleWindows=0;
        boolean receiverContextVisible=false;
        try{
            List<AccessibilityWindowInfo> windows=getWindows();
            if(windows!=null)for(AccessibilityWindowInfo w:windows){
                if(w==null)continue;windowsCount++;
                AccessibilityNodeInfo root=w.getRoot();if(root==null)continue;
                String pkg=root.getPackageName()==null?"":root.getPackageName().toString();
                if(getPackageName().equals(pkg)||SYSTEM_UI.equals(pkg))continue;
                eligibleWindows++;
                String windowText=NodeUtil.text(root);
                if(SHOPEE.equals(pkg)&&windowText.contains("Liên hệ khách hàng")&&windowText.contains("Khách nhận đơn"))receiverContextVisible=true;
                candidates.addAll(TextParser.phoneCandidates(windowText));
            }
        }catch(Exception ignored){}

        // The capture baseline was collected before clicking Khách nhận đơn. Only newly exposed
        // phone candidates are eligible, preventing stale/background numbers from another layer.
        candidates.removeAll(windowCaptureBaseline);
        if(receiverContextVisible&&candidates.size()==1){
            String phone=candidates.iterator().next();
            AppLog.add(this,"WINDOW_SCAN #"+capture+": tìm thấy đúng 1 SĐT mới trong accessibility windows · lưu an toàn");
            saveReceiverPhone(capture,phone,"Accessibility window layer sau Khách nhận đơn",false);
            return;
        }

        long now=System.currentTimeMillis();
        if(now-lastWindowScanLog>1800L){
            lastWindowScanLog=now;
            AppLog.add(this,"WINDOW_SCAN #"+capture+": windows="+windowsCount+" · eligible="+eligibleWindows+" · receiverContext="+receiverContextVisible+" · newPhoneCandidates="+candidates.size()+" · captureAge="+windowCaptureAge(capture)+"ms");
        }
    }

    private void schedulePhoneScan(String pkg,String eventText){
        long now=System.currentTimeMillis();if(now-lastPhoneScan<55L)return;
        lastPhoneScan=now;handler.postDelayed(()->scanPhone(pkg,eventText),18L);
    }

    private void scanPhone(String pkg,String eventText){
        if(!AppPrefs.isPhoneCaptureActive(this))return;
        String capture=AppPrefs.getCaptureOrder(this);if(capture.isEmpty())return;
        String processing=AppPrefs.getProcessingOrder(this);
        if(AppPrefs.isAutoEnabled(this)&&!processing.isEmpty()&&!capture.equals(processing)){
            AppLog.add(this,"CHẶN ghép sai: processing #"+processing+" nhưng Dialer thuộc #"+capture);
            AppPrefs.clearContactBinding(this);return;
        }

        AccessibilityNodeInfo root=getRootInActiveWindow();String phone=NodeUtil.singleLikelyPhone(root,eventText);
        if(phone.isEmpty()){
            long now=System.currentTimeMillis();
            if(!pkg.equals(lastExternalPkg)||now-lastNoPhoneLog>1800L){
                lastExternalPkg=pkg;lastNoPhoneLog=now;AppLog.add(this,"Dialer "+pkg+" · đang chờ đúng 1 SĐT VN");
            }
            return;
        }
        saveReceiverPhone(capture,phone,"Dialer sau Khách nhận đơn",true);
    }

    private void saveReceiverPhone(String orderId,String phone,String source,boolean cameFromDialer){
        if(orderId!=null&&orderId.equals(windowCaptureBaselineOrder))clearWindowCaptureObservation();
        String processing=AppPrefs.getProcessingOrder(this);
        boolean wasProcessing=!processing.isEmpty()&&orderId.equals(processing);
        OrderStore.PhoneAttachResult result=OrderStore.attachPhoneStrict(this,orderId,phone,wasProcessing?"auto":"manual");
        if(result.code==OrderStore.PhoneAttachResult.MISSING){
            AppLog.add(this,"Đọc được SĐT nhưng không có record #"+orderId+" · KHÔNG ghi");AppPrefs.clearContactBinding(this);return;
        }
        if(result.code==OrderStore.PhoneAttachResult.CONFLICT){
            AppLog.add(this,"CHẶN ghi đè #"+orderId+": đã có SĐT khác");AppPrefs.clearContactBinding(this);
            if(orderId.equals(processing))AppPrefs.finishProcessing(this,orderId);return;
        }

        boolean newlySaved=result.code==OrderStore.PhoneAttachResult.SAVED;String doneId=result.record.shortOrderId;
        AppLog.add(this,(newlySaved?"Đã lưu":"SĐT trùng dữ liệu cũ của")+" #"+doneId+" · nguồn: "+source);
        if(newlySaved && result.record.processingStartedAt>0L && result.record.phoneRecordedAt>=result.record.processingStartedAt){
            long workMs=result.record.phoneRecordedAt-result.record.processingStartedAt;
            long totalMs=result.record.receivedAt>0L?result.record.phoneRecordedAt-result.record.receivedAt:workMs;
            AppLog.add(this,"PERF #"+doneId+": xử lý "+workMs+"ms · từ thông báo "+totalMs+"ms");
        }
        if(newlySaved){
            Toast.makeText(this,"Đã ghi SĐT Khách nhận đơn #"+doneId,Toast.LENGTH_LONG).show();
            ReviewNotifier.cancel(this,result.record);
            CompletionNotifier.notifyRecorded(this,result.record);
            // Local save is already complete. Hub sync is asynchronous and must never block capture.
            HubSync.kick(this);
        }

        if(wasProcessing)AppPrefs.finishProcessing(this,doneId);else AppPrefs.clearContactBinding(this);

        // Burst-safe turnover: if another order is already queued, start it immediately.
        // Do NOT spend ~0.8s backing out first; the next order's contentIntent will replace the current screen.
        // This preserves the simple one-order-at-a-time engine while increasing throughput during rushes.
        boolean hasNext=AppPrefs.queueSize(this)>0;
        if(hasNext){
            AppLog.add(this,"BURST: #"+doneId+" đã lưu · chuyển ngay sang đơn kế tiếp");
            ShopeeNotificationListener.requestNext();
        }else if(cameFromDialer){
            robustReturnToShopee(doneId,false);
        }else{
            handler.postDelayed(()->{
                performGlobalAction(GLOBAL_ACTION_BACK);
                AppLog.add(this,"Hoàn tất #"+doneId+" · đóng bảng Liên hệ");
            },180L);
        }
    }

    private void robustReturnToShopee(String doneId,boolean requestNext){
        backUntilShopee(doneId,0,requestNext);
    }

    private void backUntilShopee(String doneId,int attempt,boolean requestNext){
        handler.postDelayed(()->{
            if(isShopeeForeground()){
                AppLog.add(this,"Hoàn tất #"+doneId+" · đã về Shopee Partner");
                if(requestNext)ShopeeNotificationListener.requestNext();return;
            }
            if(attempt<3){
                performGlobalAction(GLOBAL_ACTION_BACK);
                backUntilShopee(doneId,attempt+1,requestNext);
            }else{
                bringShopeeToFront();
                AppLog.add(this,"Hoàn tất #"+doneId+" · yêu cầu đưa Shopee Partner lên trước");
                if(requestNext)handler.postDelayed(ShopeeNotificationListener::requestNext,100L);
            }
        },attempt==0?180L:260L);
    }

    private boolean isShopeeForeground(){
        AccessibilityNodeInfo root=getRootInActiveWindow();
        return root!=null&&root.getPackageName()!=null&&SHOPEE.equals(root.getPackageName().toString());
    }

    private void bringShopeeToFront(){
        try{
            Intent i=getPackageManager().getLaunchIntentForPackage(SHOPEE);
            if(i!=null){i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_REORDER_TO_FRONT|Intent.FLAG_ACTIVITY_SINGLE_TOP);startActivity(i);}
        }catch(Exception e){AppLog.add(this,"Không thể đưa Shopee Partner lên foreground");}
    }

    private boolean canDriveAuto(String id){
        return id!=null&&!id.isEmpty()&&AppPrefs.isAutoEnabled(this)
                &&AppPrefs.isProcessingLeaseValid(this,id)
                &&!OrderStore.isCompleted(this,id)&&OrderStore.canContinueAuto(this,id);
    }

    private void recoverExpectedOrder(String expected){
        if(!canDriveAuto(expected))return;
        // Old completed/detail UI commonly remains visible briefly while contentIntent is loading.
        // Let the normal settle window expire before Accessibility starts a competing shade recovery.
        if(AppPrefs.STAGE_OPENING_ORDER.equals(AppPrefs.getStage(this))
                &&AppPrefs.processingAge(this)<AutomationPolicy.CONTENT_INTENT_SETTLE_MS)return;
        long now=System.currentTimeMillis();if(now-lastMismatchRecover<1200L)return;
        lastMismatchRecover=now;
        AppLog.add(this,"AUTO khôi phục: mở lại notification của #"+expected+" thay vì kẹt ở đơn khác");
        requestNotificationTapFallback(expected);
    }

    private void logMismatch(String expected,String visible){
        long now=System.currentTimeMillis();if(now-lastMismatchLog<1600L)return;
        lastMismatchLog=now;AppLog.add(this,"CHẶN sai đơn: cần #"+expected+" nhưng màn hình là #"+visible);
    }

    private void throttledLog(String msg,long interval){
        long now=System.currentTimeMillis();if(now-lastMismatchLog<interval)return;lastMismatchLog=now;AppLog.add(this,msg);
    }

    private static String eventSnapshot(AccessibilityEvent e){
        StringBuilder b=new StringBuilder();List<CharSequence> xs=e.getText();
        if(xs!=null)for(CharSequence x:xs)if(x!=null)b.append(x).append('\n');
        CharSequence d=e.getContentDescription();if(d!=null)b.append(d).append('\n');
        CharSequence c=e.getClassName();if(c!=null)b.append(c).append('\n');return b.toString();
    }

    @Override public void onInterrupt(){AppLog.add(this,"SERVICE ACCESSIBILITY interrupted");}
}
