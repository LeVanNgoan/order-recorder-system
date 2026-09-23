package vn.orderrecorder.shopee;

import android.content.Context;
import android.util.AtomicFile;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;

public final class OrderStore {
    private static final String FILE = "orders.json";
    private static final long RETENTION = 7L * 24L * 60L * 60L * 1000L;
    private static final long SAME_ORDER_WINDOW = 18L * 60L * 60L * 1000L;
    private static final long NOTIFICATION_DUP_WINDOW = 12L * 60L * 1000L;
    private static final Object LOCK = new Object();
    private static final Object FILE_LOCK = new Object();
    private static final ExecutorService IO = Executors.newSingleThreadExecutor(r->{Thread t=new Thread(r,"order-store-io");t.setDaemon(true);return t;});
    private static final AtomicLong WRITE_GEN = new AtomicLong(0L);
    private static List<OrderRecord> CACHE = null;
    private OrderStore() {}

    public static OrderRecord recordNotification(Context c, String rawId, long at) {
        synchronized (LOCK) {
            String id = TextParser.normalizeShortId(rawId);
            if (id.isEmpty()) return null;
            long now=at>0?at:System.currentTimeMillis();
            List<OrderRecord> list = read(c);
            // Notification có thể tới đúng lúc đang bắt SĐT của đơn trước: không chạy merge O(n²)
            // và không ghi file đồng bộ trên main thread. Chỉ prune O(n), cache ngay, flush nền.
            pruneExpiredOnly(list);
            OrderRecord existing = findLatestRecent(list, id, now, NOTIFICATION_DUP_WINDOW);
            if (existing != null) {
                if (existing.eligibleAt <= 0L) { existing.eligibleAt = Math.max(now, existing.receivedAt) + AutomationPolicy.SOUND_GRACE_MS; writeAsync(c, list); }
                return existing;
            }
            OrderRecord r = new OrderRecord();
            r.shortOrderId = id; r.receivedAt = now; r.updatedAt = r.receivedAt;
            r.eligibleAt = r.receivedAt + AutomationPolicy.SOUND_GRACE_MS; r.status = "queued";
            r.sessionId = "spf:" + r.receivedAt + ":" + id;
            list.add(r); writeAsync(c, list); return r;
        }
    }

    public static OrderRecord updateDetails(Context c, String rawShortId, String display, String full) {
        synchronized (LOCK) {
            String shortId = TextParser.normalizeShortId(rawShortId);
            if (shortId.isEmpty()) return null;
            List<OrderRecord> list = read(c); long now = System.currentTimeMillis();
            OrderRecord r = findLatestRecent(list, shortId, now, SAME_ORDER_WINDOW);
            String incomingCode=TextParser.displayCode(display);
            if(r!=null){
                String oldCode=TextParser.displayCode(r.displayOrderId);
                boolean displayConflict=!incomingCode.isEmpty()&&!oldCode.isEmpty()&&!incomingCode.equals(oldCode);
                boolean fullConflict=full!=null&&!full.isEmpty()&&r.fullOrderId!=null&&!r.fullOrderId.isEmpty()&&!full.equals(r.fullOrderId);
                if(displayConflict||fullConflict)r=null; // short ID đã được tái sử dụng -> tạo session mới, không ghi đè đơn cũ.
            }
            if (r == null) {
                r = new OrderRecord(); r.shortOrderId = shortId; r.receivedAt = now; r.eligibleAt = now; r.sessionId = "spf:" + now + ":" + shortId; list.add(r);
            }
            boolean changed = false;
            if (display != null && !display.isEmpty() && !display.equals(r.displayOrderId)) { r.displayOrderId = display; r.hubSeenEventSynced = false; changed = true; }
            if (full != null && !full.isEmpty() && !full.equals(r.fullOrderId)) { r.fullOrderId = full; changed = true; }
            if (r.detailConfirmedAt == 0L) { r.detailConfirmedAt = now; if(r.lastStage==null||r.lastStage.isEmpty())r.lastStage="DETAIL"; changed = true; }
            boolean openedByUser = !AppPrefs.isProcessing(c) || !shortId.equals(AppPrefs.getProcessingOrder(c));
            if (openedByUser && !r.openedByUser) { r.openedByUser = true; changed = true; }
            if (changed && r.isCompleted()) r.hubSynced = false;
            // Once an order is terminal/needs_review, merely viewing the old detail must NOT
            // re-arm automatic clicking. Manual receiver capture can still complete it later.
            String desiredStatus = r.isCompleted() ? "completed" : ("needs_review".equals(r.status) ? "needs_review" : "getting_phone");
            if (!desiredStatus.equals(r.status)) { r.status = desiredStatus; changed = true; }
            if (changed) { r.updatedAt = now; cacheOnly(list); }
            return r;
        }
    }

    public static PhoneAttachResult attachPhoneStrict(Context c, String rawShortId, String rawPhone) {
        return attachPhoneStrict(c, rawShortId, rawPhone, "");
    }

    public static PhoneAttachResult attachPhoneStrict(Context c, String rawShortId, String rawPhone, String captureMode) {
        synchronized (LOCK) {
            String shortId = TextParser.normalizeShortId(rawShortId), phone = TextParser.normalizePhone(rawPhone);
            if (shortId.isEmpty() || phone.isEmpty()) return PhoneAttachResult.missing();
            List<OrderRecord> list = read(c);
            OrderRecord r = findLatestRecent(list, shortId, System.currentTimeMillis(), SAME_ORDER_WINDOW);
            if (r == null) return PhoneAttachResult.missing();
            if (r.isCompleted()) {
                if (r.receiverPhone.equals(phone)) return PhoneAttachResult.same(r);
                return PhoneAttachResult.conflict(r);
            }
            long now = System.currentTimeMillis();
            r.receiverPhone = phone; r.status = "completed"; r.result = "completed"; r.failureReason = "";
            String mode = captureMode == null ? "" : captureMode.trim().toLowerCase(Locale.US);
            if ("auto".equals(mode) || "manual".equals(mode)) r.captureMode = mode;
            else if (r.captureMode == null || r.captureMode.isEmpty()) r.captureMode = "unknown";
            r.updatedAt = now; r.phoneRecordedAt = now; r.hubSynced = false; r.hubSyncAt = 0L; r.hubCapturedEventSynced = false; r.lastStage = "COMPLETED";
            // SĐT đã được khóa vào record trong RAM ngay lập tức; flush file chạy nền để không chặn
            // Accessibility hot-path/đơn kế tiếp bởi I/O trên SUNMI.
            writeAsync(c, list); return PhoneAttachResult.saved(r);
        }
    }

    public static boolean isCompleted(Context c, String id) { OrderRecord r = get(c, id); return r != null && r.isCompleted(); }
    public static OrderRecord get(Context c, String rawId) {
        synchronized (LOCK) {
            String id = TextParser.normalizeShortId(rawId); if (id.isEmpty()) return null;
            List<OrderRecord> list = read(c);
            return findLatestRecent(list, id, System.currentTimeMillis(), SAME_ORDER_WINDOW);
        }
    }

    public static boolean canAutoAttempt(Context c, String rawId, long now) {
        synchronized (LOCK) {
            String id = TextParser.normalizeShortId(rawId); if (id.isEmpty()) return false;
            List<OrderRecord> list = read(c); OrderRecord r = findLatestRecent(list, id, now, SAME_ORDER_WINDOW);
            if (r == null || r.isCompleted() || "needs_review".equals(r.status)) return false;
            if (r.receivedAt <= 0L || now - r.receivedAt > AutomationPolicy.MAX_AUTO_AGE_MS) return false;
            if (r.attemptCount >= AutomationPolicy.MAX_ATTEMPTS) return false;
            if (r.eligibleAt > now || r.nextAttemptAt > now) return false;
            return true;
        }
    }

    public static boolean canContinueAuto(Context c, String rawId) {
        OrderRecord r = get(c, rawId);
        if (r == null || r.isCompleted() || "needs_review".equals(r.status)) return false;
        return r.receivedAt > 0 && System.currentTimeMillis() - r.receivedAt <= AutomationPolicy.MAX_AUTO_AGE_MS;
    }

    public static long eligibleAt(Context c, String id) { OrderRecord r = get(c, id); return r == null ? Long.MAX_VALUE : r.eligibleAt; }
    public static long age(Context c, String id) { OrderRecord r = get(c, id); return r == null || r.receivedAt <= 0 ? Long.MAX_VALUE : System.currentTimeMillis() - r.receivedAt; }

    public static void beginAttempt(Context c, String rawId, String method) {
        synchronized (LOCK) {
            String id = TextParser.normalizeShortId(rawId); List<OrderRecord> list = read(c);
            OrderRecord r = findLatestRecent(list, id, System.currentTimeMillis(), SAME_ORDER_WINDOW); if (r == null) return;
            long now = System.currentTimeMillis(); r.attemptCount++; r.processingStartedAt = now; r.updatedAt = now; r.status = "opening_order";
            r.result = "processing"; r.failureReason = ""; r.openMethod = method == null ? "" : method; r.openedByAutomation = true; r.lastStage = "OPENING_ORDER";
            writeAsync(c, list);
        }
    }

    public static boolean markRisk(Context c, String rawId, String reason) {
        synchronized (LOCK) {
            String id = TextParser.normalizeShortId(rawId); if (id.isEmpty()) return false;
            List<OrderRecord> list = read(c); pruneExpiredOnly(list);
            OrderRecord r = findLatestRecent(list, id, System.currentTimeMillis(), SAME_ORDER_WINDOW);
            if (r == null || r.isCompleted() || r.riskEventAt > 0L) return false;
            long now = System.currentTimeMillis();
            r.riskEventAt = now;
            r.riskEventReason = reason == null ? "" : reason;
            r.riskEventAttempts = r.attemptCount;
            r.hubRiskEventSynced = false;
            r.updatedAt = now;
            writeAsync(c, list);
            return true;
        }
    }

    public static boolean failAttempt(Context c, String rawId, String reason) {
        synchronized (LOCK) {
            String id = TextParser.normalizeShortId(rawId); List<OrderRecord> list = read(c); pruneExpiredOnly(list);
            OrderRecord r = findLatestRecent(list, id, System.currentTimeMillis(), SAME_ORDER_WINDOW); if (r == null) return false;
            long now = System.currentTimeMillis(); r.failureReason = reason == null ? "" : reason; r.updatedAt = now;
            boolean terminal = r.attemptCount >= AutomationPolicy.MAX_ATTEMPTS || (r.receivedAt > 0 && now - r.receivedAt > AutomationPolicy.MAX_AUTO_AGE_MS);
            if (terminal) {
                r.status = "needs_review"; r.result = "needs_review"; r.lastStage = "NEEDS_REVIEW";
                r.missEventAt = now; r.missEventReason = r.failureReason; r.missEventAttempts = r.attemptCount; r.hubMissEventSynced = false;
            }
            else { r.status = "queued_retry"; r.result = "queued"; r.nextAttemptAt = now + AutomationPolicy.RETRY_DELAY_MS; r.lastStage = "RETRY"; }
            writeAsync(c, list); return terminal;
        }
    }

    public static void markNeedsReview(Context c, String rawId, String reason) {
        synchronized (LOCK) {
            String id = TextParser.normalizeShortId(rawId); List<OrderRecord> list = read(c); pruneExpiredOnly(list);
            OrderRecord r = findLatestRecent(list, id, System.currentTimeMillis(), SAME_ORDER_WINDOW); if (r == null) return;
            long now = System.currentTimeMillis();
            r.status = "needs_review"; r.result = "needs_review"; r.failureReason = reason == null ? "" : reason; r.updatedAt = now; r.lastStage = "NEEDS_REVIEW";
            r.missEventAt = now; r.missEventReason = r.failureReason; r.missEventAttempts = r.attemptCount; r.hubMissEventSynced = false;
            writeAsync(c, list);
        }
    }

    public static void cancelAttemptForUser(Context c, String rawId, long pauseMs) {
        synchronized (LOCK) {
            String id = TextParser.normalizeShortId(rawId); List<OrderRecord> list = read(c); pruneExpiredOnly(list);
            OrderRecord r = findLatestRecent(list, id, System.currentTimeMillis(), SAME_ORDER_WINDOW); if (r == null) return;
            if (r.attemptCount > 0) r.attemptCount--;
            r.status = "queued_user_pause"; r.result = "queued"; r.nextAttemptAt = Math.max(r.nextAttemptAt, System.currentTimeMillis() + Math.max(0, pauseMs)); r.updatedAt = System.currentTimeMillis(); r.lastStage = "USER_PAUSE"; writeAsync(c, list);
        }
    }

    public static void deferForUser(Context c, String rawId, long pauseMs) {
        synchronized (LOCK) {
            String id = TextParser.normalizeShortId(rawId); List<OrderRecord> list = read(c); pruneExpiredOnly(list);
            OrderRecord r = findLatestRecent(list, id, System.currentTimeMillis(), SAME_ORDER_WINDOW); if (r == null) return;
            r.status = "queued_user_pause"; r.result = "queued"; r.nextAttemptAt = Math.max(r.nextAttemptAt, System.currentTimeMillis() + Math.max(0, pauseMs)); r.updatedAt = System.currentTimeMillis(); r.lastStage = "USER_PAUSE"; writeAsync(c, list);
        }
    }

    public static void noteUserPause(Context c, String rawId, long durationMs) {
        synchronized (LOCK) {
            String id = TextParser.normalizeShortId(rawId); if (id.isEmpty()) return;
            List<OrderRecord> list = read(c); pruneExpiredOnly(list); OrderRecord r = findLatestRecent(list, id, System.currentTimeMillis(), SAME_ORDER_WINDOW); if (r == null) return;
            r.pauseCount++; r.totalUserPauseMs += Math.max(0L, durationMs); r.updatedAt = System.currentTimeMillis(); writeAsync(c, list);
        }
    }

    public static void markOpenedByUser(Context c, String rawId) {
        synchronized (LOCK) {
            String id=TextParser.normalizeShortId(rawId);if(id.isEmpty())return;List<OrderRecord> list=read(c);pruneExpiredOnly(list);
            OrderRecord r=findLatestRecent(list,id,System.currentTimeMillis(),SAME_ORDER_WINDOW);if(r!=null&&!r.openedByUser){r.openedByUser=true;r.updatedAt=System.currentTimeMillis();cacheOnly(list);}
        }
    }
    public static void markDetailConfirmed(Context c, String rawId) {
        synchronized (LOCK) {
            String id=TextParser.normalizeShortId(rawId);if(id.isEmpty())return;List<OrderRecord> list=read(c);pruneExpiredOnly(list);
            OrderRecord r=findLatestRecent(list,id,System.currentTimeMillis(),SAME_ORDER_WINDOW);if(r==null)return;
            boolean dirty=false;long now=System.currentTimeMillis();if(r.detailConfirmedAt==0){r.detailConfirmedAt=now;dirty=true;}if(!"DETAIL".equals(r.lastStage)){r.lastStage="DETAIL";dirty=true;}if(dirty){r.updatedAt=now;cacheOnly(list);}
        }
    }
    public static void markContactOpened(Context c, String rawId) {
        synchronized (LOCK) {
            String id=TextParser.normalizeShortId(rawId);if(id.isEmpty())return;List<OrderRecord> list=read(c);pruneExpiredOnly(list);
            OrderRecord r=findLatestRecent(list,id,System.currentTimeMillis(),SAME_ORDER_WINDOW);if(r==null)return;
            boolean dirty=false;long now=System.currentTimeMillis();if(r.contactOpenedAt==0){r.contactOpenedAt=now;r.lastStage="CONTACT_SHEET";dirty=true;}if(dirty){r.updatedAt=now;cacheOnly(list);}
        }
    }
    public static void setStatus(Context c, String rawId, String status) {
        synchronized (LOCK) {
            String id=TextParser.normalizeShortId(rawId);if(id.isEmpty())return;List<OrderRecord> list=read(c);pruneExpiredOnly(list);
            OrderRecord r=findLatestRecent(list,id,System.currentTimeMillis(),SAME_ORDER_WINDOW);if(r==null)return;
            String next=status==null?"":status;if(next.equals(r.status)&&next.equals(r.lastStage))return;
            r.status=next;r.lastStage=next;r.updatedAt=System.currentTimeMillis();cacheOnly(list);
        }
    }

    private interface RecordEdit { void apply(OrderRecord r); }
    private static void updateTelemetry(Context c, String rawId, RecordEdit edit) {
        synchronized (LOCK) {
            String id=TextParser.normalizeShortId(rawId); if(id.isEmpty())return; List<OrderRecord> list=read(c);pruneExpiredOnly(list);
            OrderRecord r=findLatestRecent(list,id,System.currentTimeMillis(),SAME_ORDER_WINDOW);if(r!=null){edit.apply(r);writeAsync(c,list);}
        }
    }

    public static void markHubSynced(Context c, String rawId, long receivedAt, long syncAt) {
        synchronized (LOCK) {
            String id=TextParser.normalizeShortId(rawId); List<OrderRecord> list=read(c);pruneExpiredOnly(list); OrderRecord best=null;
            for(OrderRecord r:list){if(r!=null&&id.equals(r.shortOrderId)&&r.receivedAt==receivedAt){best=r;break;}}
            if(best==null)best=findLatestRecent(list,id,receivedAt,SAME_ORDER_WINDOW);
            if(best!=null){best.hubSynced=true;best.hubSyncAt=syncAt;writeAsync(c,list);}
        }
    }

    public static void markHubEventSynced(Context c, String rawId, long receivedAt, String eventType) {
        synchronized (LOCK) {
            String id=TextParser.normalizeShortId(rawId); List<OrderRecord> list=read(c);pruneExpiredOnly(list); OrderRecord best=null;
            for(OrderRecord r:list){if(r!=null&&id.equals(r.shortOrderId)&&r.receivedAt==receivedAt){best=r;break;}}
            if(best==null)best=findLatestRecent(list,id,receivedAt,SAME_ORDER_WINDOW);
            if(best==null)return;
            if("seen".equals(eventType))best.hubSeenEventSynced=true;
            else if("captured".equals(eventType))best.hubCapturedEventSynced=true;
            else if("risk".equals(eventType))best.hubRiskEventSynced=true;
            else if("miss".equals(eventType))best.hubMissEventSynced=true;
            writeAsync(c,list);
        }
    }

    public static void setCaptureMode(Context c, String rawId, long receivedAt, String mode) {
        synchronized (LOCK) {
            String id=TextParser.normalizeShortId(rawId); List<OrderRecord> list=read(c);pruneExpiredOnly(list); OrderRecord best=null;
            for(OrderRecord r:list){if(r!=null&&id.equals(r.shortOrderId)&&r.receivedAt==receivedAt){best=r;break;}}
            if(best==null)best=findLatestRecent(list,id,receivedAt,SAME_ORDER_WINDOW);
            if(best==null)return;
            String next=mode==null?"":mode;
            if(!next.equals(best.captureMode)){best.captureMode=next;best.hubCapturedEventSynced=false;writeAsync(c,list);}
        }
    }

    /**
     * v2.0.9 starts reconciliation from the moment this version is installed. Existing v2.0.8
     * records are NOT backfilled as SEEN/CAPTURED because that would create a misleading
     * historical denominator (orders missed completely never existed in orders.json).
     */
    public static boolean ensureTelemetryV209Migration(Context c, long telemetryStartAt) {
        synchronized (LOCK) {
            android.content.SharedPreferences p=c.getSharedPreferences("order_recorder_migrations",Context.MODE_PRIVATE);
            if(p.getBoolean("telemetry_v209_initialized",false))return false;
            List<OrderRecord> list=read(c);
            for(OrderRecord r:list){
                if(r==null)continue;
                // Only records that existed before telemetry start are legacy. A notification that
                // arrives immediately after service start must still produce SEEN.
                if(r.receivedAt>0L && r.receivedAt>=telemetryStartAt)continue;
                r.hubSeenEventSynced=true; r.hubCapturedEventSynced=true; r.hubRiskEventSynced=true; r.hubMissEventSynced=true;
                if(r.captureMode==null||r.captureMode.isEmpty())r.captureMode="legacy";
                r.riskEventAt=0L; r.riskEventReason=""; r.riskEventAttempts=0;
                r.missEventAt=0L; r.missEventReason=""; r.missEventAttempts=0;
            }
            write(c,list);
            p.edit().putBoolean("telemetry_v209_initialized",true).apply();
            AppLog.add(c,"MIGRATION v2.0.9: telemetry bắt đầu từ bản này · không backfill record cũ để tránh sai tỷ lệ");
            return true;
        }
    }

    public static List<OrderRecord> getAll(Context c) { synchronized(LOCK){List<OrderRecord> l=read(c);pruneExpiredOnly(l);ArrayList<OrderRecord> out=new ArrayList<>(l);out.sort((a,b)->Long.compare(b.receivedAt,a.receivedAt));return out;} }
    public static List<OrderRecord> getToday(Context c) { return getForDay(c, System.currentTimeMillis()); }
    public static List<OrderRecord> getForDay(Context c, long dayMs) {
        synchronized(LOCK){
            List<OrderRecord> all=read(c);pruneExpiredOnly(all);Calendar d=Calendar.getInstance(TimeZone.getTimeZone("Asia/Ho_Chi_Minh"));d.setTimeInMillis(dayMs);d.set(Calendar.HOUR_OF_DAY,0);d.set(Calendar.MINUTE,0);d.set(Calendar.SECOND,0);d.set(Calendar.MILLISECOND,0);
            Calendar end=(Calendar)d.clone();end.add(Calendar.DAY_OF_MONTH,1);long a=d.getTimeInMillis(),b=end.getTimeInMillis();ArrayList<OrderRecord> out=new ArrayList<>();for(OrderRecord r:all)if(r.receivedAt>=a&&r.receivedAt<b)out.add(r);out.sort((x,y)->Long.compare(y.receivedAt,x.receivedAt));return out;
        }
    }
    public static void clear(Context c){synchronized(LOCK){CACHE=null;new AtomicFile(new File(c.getFilesDir(),FILE)).delete();}}

    private static OrderRecord findLatestRecent(List<OrderRecord> list,String id,long around,long window){OrderRecord best=null;for(OrderRecord r:list){if(r==null||!id.equals(TextParser.normalizeShortId(r.shortOrderId)))continue;long t=r.receivedAt>0?r.receivedAt:r.updatedAt;if(t>0&&Math.abs(around-t)>window)continue;if(best==null||r.receivedAt>best.receivedAt)best=r;}return best;}

    private static void pruneExpiredOnly(List<OrderRecord> list){
        long cut=System.currentTimeMillis()-RETENTION;
        list.removeIf(r->r==null||TextParser.normalizeShortId(r.shortOrderId).isEmpty()||(r.receivedAt>0&&r.receivedAt<cut));
    }

    private static void cleanupAndMerge(List<OrderRecord> list){
        pruneExpiredOnly(list);
        for(OrderRecord r:list){r.shortOrderId=TextParser.normalizeShortId(r.shortOrderId);r.receiverPhone=TextParser.normalizePhone(r.receiverPhone);if(r.eligibleAt<=0&&r.receivedAt>0)r.eligibleAt=r.receivedAt+AutomationPolicy.SOUND_GRACE_MS;}
        list.sort(Comparator.comparingLong(r->r.receivedAt));
        for(int i=0;i<list.size();i++){
            OrderRecord a=list.get(i);if(a==null)continue;
            for(int j=i+1;j<list.size();){OrderRecord b=list.get(j);if(sameLogicalOrder(a,b)){mergeInto(a,b);list.remove(j);}else j++;}
        }
    }

    private static boolean sameLogicalOrder(OrderRecord a,OrderRecord b){
        if(a==null||b==null||!a.shortOrderId.equals(b.shortOrderId))return false;
        long delta=Math.abs(a.receivedAt-b.receivedAt);if(delta>SAME_ORDER_WINDOW)return false;
        // Hai session notification độc lập cách xa nhau không được merge chỉ vì short ID bị tái sử dụng.
        if(!a.sessionId.isEmpty()&&!b.sessionId.isEmpty()&&!a.sessionId.equals(b.sessionId)&&delta>NOTIFICATION_DUP_WINDOW)return false;
        String ac=TextParser.displayCode(a.displayOrderId),bc=TextParser.displayCode(b.displayOrderId);if(!ac.isEmpty()&&!bc.isEmpty()&&!ac.equals(bc))return false;
        if(!a.fullOrderId.isEmpty()&&!b.fullOrderId.isEmpty()&&!a.fullOrderId.equals(b.fullOrderId))return false;return sameLocalDay(a.receivedAt,b.receivedAt);
    }
    private static boolean sameLocalDay(long a,long b){SimpleDateFormat f=new SimpleDateFormat("yyyyMMdd",Locale.US);f.setTimeZone(TimeZone.getTimeZone("Asia/Ho_Chi_Minh"));return f.format(new Date(a)).equals(f.format(new Date(b)));}
    private static void mergeInto(OrderRecord a,OrderRecord b){
        a.receivedAt=Math.min(nonZero(a.receivedAt,b.receivedAt),nonZero(b.receivedAt,a.receivedAt));if(a.displayOrderId.isEmpty())a.displayOrderId=b.displayOrderId;if(a.fullOrderId.isEmpty())a.fullOrderId=b.fullOrderId;if(a.receiverPhone.isEmpty())a.receiverPhone=b.receiverPhone;
        a.hubSynced=a.hubSynced||b.hubSynced;a.hubSyncAt=Math.max(a.hubSyncAt,b.hubSyncAt);a.hubSeenEventSynced=a.hubSeenEventSynced||b.hubSeenEventSynced;a.hubCapturedEventSynced=a.hubCapturedEventSynced||b.hubCapturedEventSynced;a.hubRiskEventSynced=a.hubRiskEventSynced||b.hubRiskEventSynced;a.hubMissEventSynced=a.hubMissEventSynced||b.hubMissEventSynced;if(a.captureMode.isEmpty())a.captureMode=b.captureMode;if(b.riskEventAt>a.riskEventAt){a.riskEventAt=b.riskEventAt;a.riskEventReason=b.riskEventReason;a.riskEventAttempts=b.riskEventAttempts;}if(b.missEventAt>a.missEventAt){a.missEventAt=b.missEventAt;a.missEventReason=b.missEventReason;a.missEventAttempts=b.missEventAttempts;}a.updatedAt=Math.max(a.updatedAt,b.updatedAt);a.eligibleAt=Math.min(nonZero(a.eligibleAt,b.eligibleAt),nonZero(b.eligibleAt,a.eligibleAt));a.nextAttemptAt=Math.max(a.nextAttemptAt,b.nextAttemptAt);
        a.processingStartedAt=Math.max(a.processingStartedAt,b.processingStartedAt);a.detailConfirmedAt=Math.max(a.detailConfirmedAt,b.detailConfirmedAt);a.contactOpenedAt=Math.max(a.contactOpenedAt,b.contactOpenedAt);a.phoneRecordedAt=Math.max(a.phoneRecordedAt,b.phoneRecordedAt);a.attemptCount=Math.max(a.attemptCount,b.attemptCount);a.pauseCount+=b.pauseCount;a.totalUserPauseMs+=b.totalUserPauseMs;a.openedByUser|=b.openedByUser;a.openedByAutomation|=b.openedByAutomation;
        if(a.sessionId.isEmpty())a.sessionId=b.sessionId;if(a.openMethod.isEmpty())a.openMethod=b.openMethod;if(a.failureReason.isEmpty())a.failureReason=b.failureReason;if(a.lastStage.isEmpty())a.lastStage=b.lastStage;if(a.isCompleted()){a.status="completed";a.result="completed";}else if("needs_review".equals(b.status)){a.status=b.status;a.result=b.result;}
    }
    private static long nonZero(long v,long fallback){return v>0?v:fallback>0?fallback:System.currentTimeMillis();}

    private static List<OrderRecord> read(Context c){
        if(CACHE!=null)return CACHE;
        List<OrderRecord> out=new ArrayList<>();File f=new File(c.getFilesDir(),FILE);
        AtomicFile atomic=new AtomicFile(f);
        try(InputStream in=atomic.openRead();BufferedReader br=new BufferedReader(new InputStreamReader(in,StandardCharsets.UTF_8))){StringBuilder s=new StringBuilder();String line;while((line=br.readLine())!=null)s.append(line);JSONArray a=new JSONArray(s.toString());for(int i=0;i<a.length();i++){JSONObject o=a.optJSONObject(i);if(o!=null)out.add(OrderRecord.fromJson(o));}}catch(FileNotFoundException ignored){}catch(Exception ignored){}
        // Chuẩn hóa/merge legacy đúng MỘT LẦN khi nạp file. Các heartbeat/query sau đó chỉ O(n),
        // tránh Hub thread giữ LOCK làm Accessibility khựng ở cao điểm.
        cleanupAndMerge(out);
        CACHE=out;return CACHE;
    }
    /** Hot-path update: keep current state in RAM. Completion/failure/export will persist it. */
    private static void cacheOnly(List<OrderRecord> list){ CACHE=new ArrayList<>(list); }

    private static String serialize(List<OrderRecord> list){
        try{JSONArray a=new JSONArray();for(OrderRecord r:list)a.put(r.toJson());return a.toString();}catch(Exception ignored){return"";}
    }

    /** Flush nền dùng cho notification arrival để không chặn Accessibility hot-path của đơn đang xử lý. */
    private static void writeAsync(Context c,List<OrderRecord> list){
        CACHE=new ArrayList<>(list);
        final String json=serialize(list);if(json.isEmpty())return;
        final long gen=WRITE_GEN.incrementAndGet();
        final File file=new File(c.getFilesDir(),FILE);
        IO.execute(()->{
            synchronized(FILE_LOCK){
                // Nếu một completion/failure mới hơn đã ghi file, snapshot notification cũ tuyệt đối không được ghi đè lại.
                if(WRITE_GEN.get()!=gen)return;
                writeJsonFile(file,json);
            }
        });
    }

    private static void write(Context c,List<OrderRecord> list){
        CACHE=new ArrayList<>(list);
        WRITE_GEN.incrementAndGet(); // vô hiệu mọi async snapshot cũ đang chờ.
        String json=serialize(list);if(json.isEmpty())return;
        synchronized(FILE_LOCK){
            writeJsonFile(new File(c.getFilesDir(),FILE),json);
        }
    }

    private static void writeJsonFile(File file,String json){
        AtomicFile atomic=new AtomicFile(file);FileOutputStream os=null;
        try{os=atomic.startWrite();os.write(json.getBytes(StandardCharsets.UTF_8));atomic.finishWrite(os);}
        catch(Exception ignored){if(os!=null)try{atomic.failWrite(os);}catch(Exception ignored2){}}
    }

    public static final class PhoneAttachResult { public static final int SAVED=1,SAME=2,CONFLICT=3,MISSING=4;public final int code;public final OrderRecord record;private PhoneAttachResult(int c,OrderRecord r){code=c;record=r;}public static PhoneAttachResult saved(OrderRecord r){return new PhoneAttachResult(SAVED,r);}public static PhoneAttachResult same(OrderRecord r){return new PhoneAttachResult(SAME,r);}public static PhoneAttachResult conflict(OrderRecord r){return new PhoneAttachResult(CONFLICT,r);}public static PhoneAttachResult missing(){return new PhoneAttachResult(MISSING,null);} }
}
