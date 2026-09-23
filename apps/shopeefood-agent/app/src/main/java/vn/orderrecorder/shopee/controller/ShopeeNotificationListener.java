package vn.orderrecorder.shopee;

import android.app.ActivityOptions;
import android.app.Notification;
import android.app.PendingIntent;
import android.content.Context;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;
import java.util.concurrent.ConcurrentHashMap;

public final class ShopeeNotificationListener extends NotificationListenerService {
    private static final String SHOPEE="com.shopeepay.merchant.vn";
    private static volatile ShopeeNotificationListener instance;
    private final Handler handler=new Handler(Looper.getMainLooper());
    private final ConcurrentHashMap<String,PendingIntent> intents=new ConcurrentHashMap<>();

    @Override public void onListenerConnected(){
        super.onListenerConnected();instance=this;
        if(AppPrefs.reconcileProcessStart(this))AppLog.add(this,"PROCESS RECOVERY: đã release in-flight state từ process cũ; queue được giữ");
        if(AppPrefs.ensureStableCoreMigration(this))AppLog.add(this,"Đã làm sạch state automation thử nghiệm cũ · dữ liệu đơn được giữ nguyên");
        if(AppPrefs.ensureRetrySafetyMigration(this))AppLog.add(this,"v2.0.3: đã xóa queue/state retry cũ · dữ liệu đơn và Hub được giữ nguyên");
        if(AppPrefs.ensureV208ReliabilityMigration(this))AppLog.add(this,"MIGRATION v2.0.8: reset in-flight state; giữ queue để scheduler tiếp tục an toàn");
        HubSync.start(this);
        AutoStartReceiver.scheduleWatchdog(this);
        AppLog.add(this,"SERVICE NOTIFICATION connected · v2.0.10 window-layer + early-risk telemetry");
        handler.postDelayed(this::tryStartNext,180L);
    }

    @Override public void onListenerDisconnected(){
        AppLog.add(this,"SERVICE NOTIFICATION disconnected");
        if(instance==this)instance=null;
        super.onListenerDisconnected();
    }

    @Override public void onDestroy(){
        AppLog.add(this,"SERVICE NOTIFICATION destroyed");
        if(instance==this)instance=null;
        super.onDestroy();
    }

    @Override public void onNotificationPosted(StatusBarNotification sbn){
        if(!AppPrefs.isEnabled(this)||sbn==null||!SHOPEE.equals(sbn.getPackageName()))return;
        String combined=notificationText(sbn);String id=TextParser.notificationId(combined);
        if(id.isEmpty()){
            AppLog.add(this,"NOTIFY_UNPARSED · chars="+combined.length()+" · postAge="+Math.max(0L,System.currentTimeMillis()-sbn.getPostTime())+"ms");
            return;
        }
        PendingIntent pi=sbn.getNotification().contentIntent;if(pi!=null)intents.put(id,pi);
        OrderStore.recordNotification(this,id,sbn.getPostTime());AppPrefs.enqueue(this,id);
        // Telemetry sync is queued on HubSync's background executor; no network or disk I/O
        // is performed here on NotificationListener's hot path.
        HubSync.kick(this);
        AppLog.add(this,"NOTIFY #"+id+" · postAge="+Math.max(0L,System.currentTimeMillis()-sbn.getPostTime())+"ms · contentIntent="+(pi!=null)+" · queue="+AppPrefs.queueSize(this));
        if(AppPrefs.isAutoEnabled(this)){
            long grace=Math.max(0L,1000L-(System.currentTimeMillis()-sbn.getPostTime()));
            handler.postDelayed(this::tryStartNext,grace);
        }
    }

    public static void requestNext(){
        ShopeeNotificationListener x=instance;if(x!=null)x.handler.postDelayed(x::tryStartNext,90L);
    }

    /** Backup path for AlarmManager/Accessibility if the normal 30s timeout callback is ever lost. */
    public static void watchdog(Context context){
        Context app=context.getApplicationContext();
        ShopeeNotificationListener x=instance;
        if(x!=null)x.handler.post(x::tryStartNext);
        else repairStaleProcessing(app,"alarm_no_listener");
    }

    private void tryStartNext(){
        repairStaleProcessing(this,"scheduler");
        if(!AppPrefs.isEnabled(this)||!AppPrefs.isAutoEnabled(this)||AppPrefs.isProcessing(this))return;
        String id=AppPrefs.peekQueue(this);
        long now=System.currentTimeMillis();
        int rotated=0;
        int scanLimit=Math.max(1,AppPrefs.queueSize(this));
        long earliestWake=Long.MAX_VALUE;
        while(!id.isEmpty()&&rotated<scanLimit){
            OrderRecord queued=OrderStore.get(this,id);
            if(queued==null||queued.isCompleted()){
                AppPrefs.removeFromQueue(this,id);intents.remove(id);id=AppPrefs.peekQueue(this);scanLimit=Math.max(scanLimit,AppPrefs.queueSize(this));continue;
            }
            boolean terminal="needs_review".equals(queued.status)
                    || queued.attemptCount>=AutomationPolicy.MAX_ATTEMPTS
                    || queued.receivedAt<=0L
                    || now-queued.receivedAt>AutomationPolicy.MAX_AUTO_AGE_MS;
            if(terminal){
                OrderStore.markNeedsReview(this,id,"auto_expired_or_attempt_limit");
                ReviewNotifier.notifyNeedsReview(this, OrderStore.get(this,id));
                HubSync.kick(this);
                AppLog.add(this,"AUTO #"+id+": dừng vĩnh viễn auto-retry · chuyển Cần kiểm tra + đã cảnh báo người dùng");
                AppPrefs.removeFromQueue(this,id);intents.remove(id);id=AppPrefs.peekQueue(this);scanLimit=Math.max(scanLimit,AppPrefs.queueSize(this));continue;
            }
            long waitUntil=Math.max(queued.eligibleAt,queued.nextAttemptAt);
            if(waitUntil>now){
                earliestWake=Math.min(earliestWake,waitUntil);
                // A retry/backoff order must never block a fresh order behind it during rush hour.
                AppPrefs.moveToQueueEnd(this,id);
                rotated++;
                id=AppPrefs.peekQueue(this);
                continue;
            }
            break;
        }
        if(id.isEmpty()||rotated>=scanLimit){
            if(earliestWake!=Long.MAX_VALUE){
                long delay=Math.max(60L,Math.min(earliestWake-System.currentTimeMillis(),1000L));
                handler.postDelayed(this::tryStartNext,delay);
            }
            return;
        }

        // Every order gets its own 1-second sound grace, even when it waited behind another order.
        OrderRecord pending=OrderStore.get(this,id);
        if(pending!=null&&pending.receivedAt>0L){
            long remaining=AutomationPolicy.SOUND_GRACE_MS-(System.currentTimeMillis()-pending.receivedAt);
            if(remaining>0L){handler.postDelayed(this::tryStartNext,remaining);return;}
        }

        // If a retry's order is already physically visible, reuse that screen instead of reopening it.
        // This replaces the old unbounded Accessibility "adopt visible order" behavior.
        boolean alreadyVisible=ShopeeAccessibilityService.isExpectedOrderVisibleNow(id);
        AppPrefs.beginProcessing(this,id);
        OrderStore.beginAttempt(this,id,"stable_core_v208");
        OrderRecord attemptRecord=OrderStore.get(this,id);
        AppLog.add(this,"ATTEMPT #"+id+" · n="+(attemptRecord==null?"?":attemptRecord.attemptCount)+"/"+AutomationPolicy.MAX_ATTEMPTS+" · age="+(attemptRecord==null||attemptRecord.receivedAt<=0?"?":(System.currentTimeMillis()-attemptRecord.receivedAt)+"ms")+" · queue="+AppPrefs.queueSize(this));
        scheduleAttemptTimeout(id);
        if(alreadyVisible){
            // beginProcessing() intentionally clears stale screen bindings; restore only the ID
            // that we just verified against the real foreground tree.
            AppPrefs.setActiveDetailOrder(this,id);
            AppLog.add(this,"AUTO #"+id+": đúng đơn đã hiển thị · tiếp tục trong attempt mới, không mở notification lại");
            ShopeeAccessibilityService.requestResumeVisibleOrder(id);
            return;
        }
        PendingIntent pi=intents.get(id);
        if(pi==null){StatusBarNotification n=findActiveNotification(id);if(n!=null)pi=n.getNotification().contentIntent;}

        if(pi!=null){
            try{
                sendContentIntent(pi);
                AppLog.add(this,"AUTO #"+id+": đã kích hoạt contentIntent");
                final String expected=id;
                handler.postDelayed(()->verifyOrOpenShade(expected),AutomationPolicy.CONTENT_INTENT_SETTLE_MS);
                return;
            }catch(Exception e){
                AppLog.add(this,"AUTO #"+id+": contentIntent không mở được ("+e.getClass().getSimpleName()+")");
            }
        }else{
            AppLog.add(this,"AUTO #"+id+": notification không có contentIntent dùng được");
        }
        ShopeeAccessibilityService.requestNotificationTapFallback(id);
    }

    private void verifyOrOpenShade(String id){
        if(!AppPrefs.isProcessingLeaseValid(this,id))return;
        if(ShopeeAccessibilityService.isExpectedOrderVisibleNow(id)||id.equals(AppPrefs.getActiveDetailOrder(this))){
            ShopeeAccessibilityService.requestResumeVisibleOrder(id);return;
        }
        if(!AppPrefs.STAGE_OPENING_ORDER.equals(AppPrefs.getStage(this)))return;
        AppLog.add(this,"AUTO #"+id+": contentIntent chưa vào đúng Chi tiết sau settle grace → thử notification shade");
        ShopeeAccessibilityService.requestNotificationTapFallback(id);
    }

    public static void reportAttemptFailure(String orderId,String reason){
        ShopeeNotificationListener x=instance;
        if(x!=null)x.handler.post(()->x.failOrDefer(orderId,reason));
    }

    private void scheduleAttemptTimeout(String id){
        handler.postDelayed(()->{
            if(!id.equals(AppPrefs.getProcessingOrder(this))||OrderStore.isCompleted(this,id))return;
            AppLog.add(this,"AUTO #"+id+": quá thời gian 1 lần xử lý → release attempt, nhường queue");
            failOrDefer(id,"attempt_timeout");
        },AutomationPolicy.STALE_PROCESSING_MS);
    }

    private void failOrDefer(String id,String reason){
        if(id==null||id.isEmpty()||!id.equals(AppPrefs.getProcessingOrder(this)))return;
        maybeRaiseEarlyRisk(this,id,reason,AppPrefs.getStage(this));
        boolean terminal=OrderStore.failAttempt(this,id,reason);
        OrderRecord failed=OrderStore.get(this,id);
        AppLog.add(this,"ATTEMPT_RESULT #"+id+" · reason="+reason+" · terminal="+terminal+" · n="+(failed==null?"?":failed.attemptCount)+" · stage="+AppPrefs.getStage(this)+" · queue="+AppPrefs.queueSize(this));
        if(terminal){
            AppLog.add(this,"AUTO #"+id+": đã thử đủ giới hạn/đơn đã quá cũ → Cần kiểm tra, KHÔNG tự mở lại");
            ReviewNotifier.notifyNeedsReview(this, OrderStore.get(this,id));
            HubSync.kick(this);
            AppPrefs.finishProcessing(this,id);intents.remove(id);
            handler.postDelayed(this::tryStartNext,120L);
        }else{
            AppLog.add(this,"AUTO #"+id+": hoãn lần thử hiện tại · sẽ thử lại có giới hạn");
            AppPrefs.deferProcessing(this,id);
            handler.postDelayed(this::tryStartNext,120L);
        }
    }

    private static void maybeRaiseEarlyRisk(Context context,String id,String reason,String stage){
        if(id==null||id.isEmpty()||OrderStore.isCompleted(context,id))return;
        String st=stage==null?"":stage;
        boolean reachedContact=AppPrefs.STAGE_OPENING_CONTACT.equals(st)
                ||AppPrefs.STAGE_CONTACT_SHEET.equals(st)
                ||AppPrefs.STAGE_OPENING_DIALER.equals(st)
                ||AppPrefs.STAGE_READING_PHONE.equals(st);
        boolean oldNavigationFailure=(reason!=null&&reason.startsWith("notification_"))
                &&OrderStore.age(context,id)>=RiskPolicy.NAVIGATION_RISK_MIN_AGE_MS;
        if(!reachedContact&&!oldNavigationFailure)return;
        String riskReason=reachedContact
                ?("attempt_trouble_"+st.toLowerCase(java.util.Locale.US))
                :"notification_navigation_delayed";
        if(OrderStore.markRisk(context,id,riskReason)){
            AppLog.add(context,"RISK #"+id+": cảnh báo sớm · "+riskReason+" · automation vẫn tiếp tục");
            HubSync.kick(context);
        }
    }

    private static boolean repairStaleProcessing(Context context,String source){
        String id=AppPrefs.getProcessingOrder(context);if(id.isEmpty())return false;
        OrderRecord r=OrderStore.get(context,id);
        if(r==null){
            AppLog.add(context,"WATCHDOG #"+id+": record không còn tồn tại · release processing");
            AppPrefs.cancelProcessingKeepQueued(context);return true;
        }
        if(r.isCompleted()){
            AppLog.add(context,"WATCHDOG #"+id+": record đã completed · dọn processing còn sót");
            AppPrefs.finishProcessing(context,id);return true;
        }
        boolean leaseExpired=!AppPrefs.isProcessingLeaseValid(context,id);
        boolean autoExpired=!OrderStore.canContinueAuto(context,id);
        if(!leaseExpired&&!autoExpired)return false;

        String reason=autoExpired?"watchdog_auto_expired":"watchdog_processing_timeout";
        maybeRaiseEarlyRisk(context,id,reason,AppPrefs.getStage(context));
        boolean terminal=OrderStore.failAttempt(context,id,reason);
        OrderRecord failed=OrderStore.get(context,id);
        AppLog.add(context,"WATCHDOG #"+id+": release stale processing · source="+source
                +" · age="+AppPrefs.processingAge(context)+"ms · terminal="+terminal
                +" · n="+(failed==null?"?":failed.attemptCount));
        if(terminal){
            ReviewNotifier.notifyNeedsReview(context,failed);
            HubSync.kick(context);
            AppPrefs.finishProcessing(context,id);
        }else{
            AppPrefs.deferProcessing(context,id);
        }
        return true;
    }

    private void sendContentIntent(PendingIntent pi)throws PendingIntent.CanceledException{
        if(Build.VERSION.SDK_INT>=34){
            ActivityOptions options=ActivityOptions.makeBasic();
            options.setPendingIntentBackgroundActivityStartMode(ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED);
            pi.send(this,0,null,null,null,null,options.toBundle());
        }else pi.send();
    }

    private StatusBarNotification findActiveNotification(String id){
        try{
            StatusBarNotification[] all=getActiveNotifications();if(all==null)return null;
            for(StatusBarNotification s:all){
                if(s!=null&&SHOPEE.equals(s.getPackageName())&&id.equals(TextParser.notificationId(notificationText(s))))return s;
            }
        }catch(Exception ignored){}
        return null;
    }

    private static String notificationText(StatusBarNotification sbn){
        Bundle e=sbn.getNotification().extras;
        return value(e.getCharSequence(Notification.EXTRA_TITLE))+"\n"+value(e.getCharSequence(Notification.EXTRA_TEXT))+"\n"+value(e.getCharSequence(Notification.EXTRA_BIG_TEXT));
    }
    private static String value(CharSequence s){return s==null?"":s.toString();}
}
