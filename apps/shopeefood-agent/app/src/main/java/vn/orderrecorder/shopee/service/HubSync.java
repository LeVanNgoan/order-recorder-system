package vn.orderrecorder.shopee;

import android.content.Context;
import android.os.Build;
import org.json.JSONObject;
import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

public final class HubSync {
    private static final String VERSION="2.0.10";
    private static final long DISCOVERY_COOLDOWN_MS=60_000L;
    private static final ScheduledExecutorService EXEC=Executors.newSingleThreadScheduledExecutor(r->{Thread t=new Thread(r,"order-hub-sync");t.setDaemon(true);return t;});
    private static final AtomicBoolean STARTED=new AtomicBoolean(false),RUNNING=new AtomicBoolean(false);
    private static final AtomicLong LAST_DISCOVERY_ATTEMPT=new AtomicLong(0L);
    private HubSync(){}

    public static void start(Context c){
        Context app=c.getApplicationContext();
        if(STARTED.compareAndSet(false,true)){
            final long telemetryStartAt=System.currentTimeMillis();
            EXEC.execute(()->{OrderStore.ensureTelemetryV209Migration(app,telemetryStartAt);tick(app);});
            EXEC.scheduleWithFixedDelay(()->tick(app),4,30,TimeUnit.SECONDS);
        }else kick(app);
    }
    public static void kick(Context c){Context app=c.getApplicationContext();EXEC.execute(()->tick(app));}
    public static int pendingCount(Context c){int n=0;for(OrderRecord r:OrderStore.getAll(c))if(r.isCompleted()&&!r.hubSynced&&!r.businessOrderCode().isEmpty())n++;return n;}

    /**
     * Normal sync path is unchanged. Auto Discovery only runs after the saved Hub
     * cannot be reached, and it runs on this background executor (never capture hot-path).
     */
    private static void tick(Context c){
        if(!HubPrefs.hasKey(c))return;
        if(!HubPrefs.hasUrl(c)){
            if(tryAutoDiscovery(c,false)){syncNow(c);sendHeartbeat(c);}
            return;
        }
        syncNow(c);
        boolean heartbeatOk=sendHeartbeat(c);
        if(!heartbeatOk&&tryAutoDiscovery(c,false)){
            AppLog.add(c,"Hub: IP mới đã được xác thực · thử đồng bộ lại ngay");
            syncNow(c);
            sendHeartbeat(c);
        }
    }

    /** Kiểm tra thật cả LAN + port + API key; nếu IP cũ chết thì tự tìm Hub mới. */
    public static void test(Context c,Callback cb){Context app=c.getApplicationContext();EXEC.execute(()->{
        String err="";boolean ok=false;
        try{
            String base=HubPrefs.getUrl(app),key=HubPrefs.getKey(app);
            if(key.isEmpty())throw new IOException("Chưa nhập API key");
            if(!base.isEmpty()){
                TestResult r=testBase(app,base,key,"connection_test");
                ok=r.ok;err=r.error;
            }else err="Chưa nhập địa chỉ Hub";
            if(!ok){
                HubDiscovery.Result d=discoverInternal(app,true);
                if(d.ok){ok=true;err="";}
                else if(err.isEmpty())err=d.error;
            }
            if(ok)HubPrefs.markOk(app);else HubPrefs.markError(app,err);
        }catch(Exception e){err=shortError(e);HubPrefs.markError(app,err);}
        if(cb!=null)cb.done(ok,err);
    });}

    /** Explicit user-triggered discovery, useful for diagnostics. */
    public static void discoverNow(Context c,Callback cb){Context app=c.getApplicationContext();EXEC.execute(()->{
        HubDiscovery.Result r=discoverInternal(app,true);
        if(r.ok){syncNow(app);sendHeartbeat(app);}
        if(cb!=null)cb.done(r.ok,r.ok?"":r.error);
    });}

    private static boolean tryAutoDiscovery(Context c,boolean force){
        long now=System.currentTimeMillis();
        long last=LAST_DISCOVERY_ATTEMPT.get();
        if(!force&&last>0&&now-last<DISCOVERY_COOLDOWN_MS)return false;
        LAST_DISCOVERY_ATTEMPT.set(now);
        return discoverInternal(c,true).ok;
    }

    private static HubDiscovery.Result discoverInternal(Context c,boolean fallback){
        if(!HubPrefs.hasKey(c))return HubDiscovery.Result.fail("Chưa có API key để tự tìm Hub");
        HubDiscovery.Result r=HubDiscovery.discover(c,fallback);
        if(!r.ok)HubPrefs.markError(c,"Auto Discovery: "+r.error);
        return r;
    }

    private static TestResult testBase(Context app,String base,String key,String status){
        HttpURLConnection x=null;
        try{
            x=(HttpURLConnection)new URL(base+"/api/heartbeat").openConnection();
            x.setConnectTimeout(2500);x.setReadTimeout(3000);x.setRequestMethod("POST");x.setDoOutput(true);x.setRequestProperty("Content-Type","application/json; charset=utf-8");x.setRequestProperty("X-Order-Recorder-Key",key);
            JSONObject o=heartbeatPayload(app,status);
            byte[] body=o.toString().getBytes(StandardCharsets.UTF_8);x.setFixedLengthStreamingMode(body.length);try(OutputStream os=x.getOutputStream()){os.write(body);}int code=x.getResponseCode();boolean ok=code>=200&&code<300;String err=ok?"":"HTTP "+code;try(InputStream is=ok?x.getInputStream():x.getErrorStream()){if(is!=null)while(is.read()!=-1){}}return new TestResult(ok,err);
        }catch(Exception e){return new TestResult(false,shortError(e));}
        finally{if(x!=null)x.disconnect();}
    }

    private static void syncNow(Context c){
        if(!HubPrefs.isConfigured(c)||!RUNNING.compareAndSet(false,true))return;
        try{
            List<OrderRecord> all=OrderStore.getAll(c);
            // Oldest -> newest keeps Hub timeline intuitive. Telemetry is best-effort and
            // deliberately separate from the business order POST: an older Hub without
            // /api/order-events must never block normal phone synchronization.
            for(int i=all.size()-1;i>=0;i--){
                OrderRecord r=all.get(i);if(r==null)continue;
                if(!r.hubSeenEventSynced){try{postEvent(c,r,"seen");OrderStore.markHubEventSynced(c,r.shortOrderId,r.receivedAt,"seen");}catch(Exception e){logTelemetryFailure(c,"seen",r,e);}}
                if(r.isCompleted()&&!r.hubSynced&&!r.businessOrderCode().isEmpty()){
                    try{post(c,r);OrderStore.markHubSynced(c,r.shortOrderId,r.receivedAt,System.currentTimeMillis());HubPrefs.markOk(c);AppLog.add(c,"Hub: đã đồng bộ "+r.businessOrderCode());}
                    catch(Exception e){String err=shortError(e);HubPrefs.markError(c,err);AppLog.add(c,"Hub: chưa gửi được · "+err);break;}
                }
                if(r.riskEventAt>0&&!r.hubRiskEventSynced){try{postEvent(c,r,"risk");OrderStore.markHubEventSynced(c,r.shortOrderId,r.receivedAt,"risk");}catch(Exception e){logTelemetryFailure(c,"risk",r,e);}}
                if(r.isCompleted()&&!r.hubCapturedEventSynced){try{postEvent(c,r,"captured");OrderStore.markHubEventSynced(c,r.shortOrderId,r.receivedAt,"captured");}catch(Exception e){logTelemetryFailure(c,"captured",r,e);}}
                if(r.missEventAt>0&&!r.hubMissEventSynced){try{postEvent(c,r,"miss");OrderStore.markHubEventSynced(c,r.shortOrderId,r.receivedAt,"miss");}catch(Exception e){logTelemetryFailure(c,"miss",r,e);}}
            }
        }finally{RUNNING.set(false);}
    }

    private static void logTelemetryFailure(Context c,String type,OrderRecord r,Exception e){
        String err=shortError(e);
        // Do not mark Hub offline solely because optional telemetry API failed. The normal
        // order/heartbeat path remains the source of connectivity truth.
        AppLog.add(c,"Hub telemetry "+type+" chưa gửi được #"+r.shortOrderId+" · "+err);
    }

    /** Gửi lại toàn bộ đơn hoàn tất còn lưu local (tối đa 7 ngày) bất kể hubSynced. */
    public static void forceResync7Days(Context c, ForceCallback cb){
        Context app=c.getApplicationContext();
        EXEC.execute(()->{
            if(!HubPrefs.hasKey(app)){if(cb!=null)cb.done(false,0,0,"Chưa cấu hình API key Hub");return;}
            if(!HubPrefs.hasUrl(app)&&!tryAutoDiscovery(app,true)){if(cb!=null)cb.done(false,0,0,"Không tự tìm thấy Hub");return;}
            if(!RUNNING.compareAndSet(false,true)){if(cb!=null)cb.done(false,0,0,"Hub đang đồng bộ, thử lại sau vài giây");return;}
            int sent=0,skipped=0;String error="";boolean ok=true;
            try{
                List<OrderRecord> all=OrderStore.getAll(app);
                for(int i=all.size()-1;i>=0;i--){
                    OrderRecord r=all.get(i);
                    if(r==null||!r.isCompleted()){skipped++;continue;}
                    if(r.businessOrderCode().isEmpty()){skipped++;AppLog.add(app,"Force resync: bỏ qua đơn thiếu mã #xxxx · short #"+r.shortOrderId);continue;}
                    try{
                        post(app,r);
                        OrderStore.markHubSynced(app,r.shortOrderId,r.receivedAt,System.currentTimeMillis());
                        sent++;
                    }catch(Exception e){
                        // One recovery attempt if the PC address changed during force-resync.
                        RUNNING.set(false);
                        if(tryAutoDiscovery(app,true)&&RUNNING.compareAndSet(false,true)){
                            try{post(app,r);OrderStore.markHubSynced(app,r.shortOrderId,r.receivedAt,System.currentTimeMillis());sent++;continue;}catch(Exception ignored){}
                        }
                        ok=false;error=shortError(e);HubPrefs.markError(app,error);AppLog.add(app,"Force resync dừng: "+error);break;
                    }
                }
                if(ok){HubPrefs.markOk(app);AppLog.add(app,"Force resync 7 ngày hoàn tất · "+sent+" đơn");}
            }finally{
                RUNNING.set(false);
                sendHeartbeat(app);
                if(cb!=null)cb.done(ok,sent,skipped,error);
            }
        });
    }

    private static boolean sendHeartbeat(Context c){
        if(!HubPrefs.isConfigured(c))return false;
        HttpURLConnection x=null;
        try{
            String base=HubPrefs.getUrl(c),key=HubPrefs.getKey(c);
            x=(HttpURLConnection)new URL(base+"/api/heartbeat").openConnection();x.setConnectTimeout(2500);x.setReadTimeout(3000);x.setRequestMethod("POST");x.setDoOutput(true);x.setRequestProperty("Content-Type","application/json; charset=utf-8");x.setRequestProperty("X-Order-Recorder-Key",key);
            JSONObject o=heartbeatPayload(c,null);byte[] body=o.toString().getBytes(StandardCharsets.UTF_8);x.setFixedLengthStreamingMode(body.length);try(OutputStream os=x.getOutputStream()){os.write(body);}int code=x.getResponseCode();boolean ok=code>=200&&code<300;if(ok){HubPrefs.markOk(c);try(InputStream is=x.getInputStream()){while(is.read()!=-1){}}}else{HubPrefs.markError(c,"Heartbeat HTTP "+code);try(InputStream is=x.getErrorStream()){if(is!=null)while(is.read()!=-1){}}}return ok;
        }catch(Exception e){HubPrefs.markError(c,shortError(e));return false;}
        finally{if(x!=null)x.disconnect();}
    }

    private static JSONObject heartbeatPayload(Context c,String forcedStatus)throws Exception{
        JSONObject o=new JSONObject();o.put("source","shopeefood-sunmi");o.put("platform","shopeefood");o.put("deviceName",Build.MANUFACTURER+" "+Build.MODEL);o.put("status",forcedStatus!=null?forcedStatus:(AppPrefs.isEnabled(c)?(AppPrefs.isAutoEnabled(c)?"ready":"auto_off"):"recording_off"));o.put("pending",pendingCount(c));o.put("version",VERSION);return o;
    }

    private static void post(Context c,OrderRecord r)throws Exception{String base=HubPrefs.getUrl(c),key=HubPrefs.getKey(c);HttpURLConnection x=(HttpURLConnection)new URL(base+"/api/orders").openConnection();x.setConnectTimeout(3000);x.setReadTimeout(4000);x.setRequestMethod("POST");x.setDoOutput(true);x.setRequestProperty("Content-Type","application/json; charset=utf-8");x.setRequestProperty("X-Order-Recorder-Key",key);byte[] body=payload(r).toString().getBytes(StandardCharsets.UTF_8);x.setFixedLengthStreamingMode(body.length);try(OutputStream os=x.getOutputStream()){os.write(body);}int code=x.getResponseCode();if(code<200||code>=300){String msg=readSmall(x.getErrorStream());throw new IOException("HTTP "+code+(msg.isEmpty()?"":" · "+msg));}try(InputStream is=x.getInputStream()){while(is.read()!=-1){}}x.disconnect();}

    private static JSONObject payload(OrderRecord r)throws Exception{JSONObject o=new JSONObject();o.put("platform","shopeefood");o.put("orderCode",r.businessOrderCode());o.put("shortOrderNumber",r.shortOrderId);o.put("displayOrderId",r.displayOrderId);o.put("fullOrderId",r.fullOrderId);o.put("phone",TextParser.normalizePhone(r.receiverPhone));o.put("receivedAt",iso(r.receivedAt));o.put("recordedAt",iso(r.phoneRecordedAt>0?r.phoneRecordedAt:r.updatedAt));o.put("sourceDevice","ShopeeFood "+Build.MANUFACTURER+" "+Build.MODEL);o.put("agentSessionId",r.sessionId);o.put("syncId",!r.fullOrderId.isEmpty()?"spf:full:"+r.fullOrderId:"spf:"+r.receivedAt+":"+r.businessOrderCode());return o;}

    private static void postEvent(Context c,OrderRecord r,String eventType)throws Exception{
        String base=HubPrefs.getUrl(c),key=HubPrefs.getKey(c);HttpURLConnection x=null;
        try{
            x=(HttpURLConnection)new URL(base+"/api/order-events").openConnection();
            x.setConnectTimeout(2500);x.setReadTimeout(3500);x.setRequestMethod("POST");x.setDoOutput(true);x.setRequestProperty("Content-Type","application/json; charset=utf-8");x.setRequestProperty("X-Order-Recorder-Key",key);
            JSONObject o=new JSONObject();o.put("platform","shopeefood");o.put("eventType",eventType);o.put("orderCode",r.businessOrderCode());o.put("shortOrderNumber",r.shortOrderId);o.put("displayOrderId",r.displayOrderId);o.put("agentSessionId",r.sessionId);o.put("sourceDevice","ShopeeFood "+Build.MANUFACTURER+" "+Build.MODEL);
            int attempts="risk".equals(eventType)&&r.riskEventAttempts>0?r.riskEventAttempts:"miss".equals(eventType)&&r.missEventAttempts>0?r.missEventAttempts:r.attemptCount;o.put("attemptCount",attempts);
            long at="seen".equals(eventType)?r.receivedAt:"captured".equals(eventType)?(r.phoneRecordedAt>0?r.phoneRecordedAt:r.updatedAt):"risk".equals(eventType)?(r.riskEventAt>0?r.riskEventAt:r.updatedAt):(r.missEventAt>0?r.missEventAt:r.updatedAt);o.put("eventAt",iso(at));
            if("risk".equals(eventType))o.put("reason",r.riskEventReason==null||r.riskEventReason.isEmpty()?"risk_detected":r.riskEventReason);
            if("miss".equals(eventType))o.put("reason",r.missEventReason==null||r.missEventReason.isEmpty()?r.failureReason:r.missEventReason);
            JSONObject meta=new JSONObject();if("captured".equals(eventType))meta.put("capture_mode",r.captureMode==null||r.captureMode.isEmpty()?"unknown":r.captureMode);meta.put("last_stage",r.lastStage);if("risk".equals(eventType))meta.put("early_warning",true);o.put("metadata",meta);
            byte[] body=o.toString().getBytes(StandardCharsets.UTF_8);x.setFixedLengthStreamingMode(body.length);try(OutputStream os=x.getOutputStream()){os.write(body);}int code=x.getResponseCode();if(code<200||code>=300){String msg=readSmall(x.getErrorStream());throw new IOException("HTTP "+code+(msg.isEmpty()?"":" · "+msg));}try(InputStream is=x.getInputStream()){while(is.read()!=-1){}}
        }finally{if(x!=null)x.disconnect();}
    }
    private static String iso(long ms){if(ms<=0)ms=System.currentTimeMillis();SimpleDateFormat f=new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX",Locale.US);f.setTimeZone(TimeZone.getTimeZone("Asia/Ho_Chi_Minh"));return f.format(new Date(ms));}
    private static String readSmall(InputStream in){if(in==null)return"";try(BufferedReader b=new BufferedReader(new InputStreamReader(in,StandardCharsets.UTF_8))){String s=b.readLine();return s==null?"":s.substring(0,Math.min(120,s.length()));}catch(Exception e){return"";}}
    private static String shortError(Exception e){String s=e.getMessage();if(s==null||s.trim().isEmpty())s=e.getClass().getSimpleName();return s.length()>100?s.substring(0,100):s;}
    private static final class TestResult{final boolean ok;final String error;TestResult(boolean ok,String error){this.ok=ok;this.error=error==null?"":error;}}
    public interface Callback{void done(boolean ok,String error);}
    public interface ForceCallback{void done(boolean ok,int sent,int skipped,String error);}
}
