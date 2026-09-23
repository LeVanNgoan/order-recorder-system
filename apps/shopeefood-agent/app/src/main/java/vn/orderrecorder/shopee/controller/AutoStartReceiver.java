package vn.orderrecorder.shopee;

import android.app.AlarmManager;
import android.service.notification.NotificationListenerService;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.SystemClock;

public final class AutoStartReceiver extends BroadcastReceiver {
    private static final String ACTION_WATCHDOG="vn.orderrecorder.shopee.WATCHDOG";
    private static final long WATCHDOG_INTERVAL=5L*60L*1000L;

    @Override public void onReceive(Context context, Intent intent){
        Context app=context.getApplicationContext();
        String action=intent==null?"":String.valueOf(intent.getAction());
        if(AppPrefs.reconcileProcessStart(app))
            AppLog.add(app,"PROCESS RECOVERY: receiver đã release orphan in-flight state; queue được giữ");
        if(Intent.ACTION_BOOT_COMPLETED.equals(action)){
            // A process cannot survive reboot; clear stale in-flight state but keep queued notifications if any.
            AppPrefs.resetTransient(app,false);
        }else if(Intent.ACTION_MY_PACKAGE_REPLACED.equals(action)){
            // One-time v2.0.8 migration is safe here and in either service; first caller wins.
            if(AppPrefs.ensureV208ReliabilityMigration(app))
                AppLog.add(app,"MIGRATION v2.0.8: package update · cleared in-flight state, queue preserved");
        }
        HubSync.start(app);
        HubSync.kick(app);
        requestNotificationRebind(app);
        scheduleWatchdog(app);
        ShopeeNotificationListener.watchdog(app);
        ShopeeNotificationListener.requestNext();
        if(Intent.ACTION_BOOT_COMPLETED.equals(action)||Intent.ACTION_MY_PACKAGE_REPLACED.equals(action)){
            AppLog.add(app,"Auto Start: thiết bị vừa khởi động/cập nhật · đã khôi phục dịch vụ nền");
        }
    }

    public static void scheduleWatchdog(Context context){
        try{
            Context app=context.getApplicationContext();
            AlarmManager am=(AlarmManager)app.getSystemService(Context.ALARM_SERVICE);
            if(am==null)return;
            Intent i=new Intent(app,AutoStartReceiver.class).setAction(ACTION_WATCHDOG);
            int flags=PendingIntent.FLAG_UPDATE_CURRENT;
            if(Build.VERSION.SDK_INT>=23)flags|=PendingIntent.FLAG_IMMUTABLE;
            PendingIntent pi=PendingIntent.getBroadcast(app,9120,i,flags);
            long first=SystemClock.elapsedRealtime()+60_000L;
            am.setInexactRepeating(AlarmManager.ELAPSED_REALTIME_WAKEUP,first,WATCHDOG_INTERVAL,pi);
        }catch(Exception ignored){}
    }

    private static void requestNotificationRebind(Context context){
        if(Build.VERSION.SDK_INT<24)return;
        try{
            ComponentName component=new ComponentName(context,ShopeeNotificationListener.class);
            NotificationListenerService.requestRebind(component);
        }catch(Exception ignored){}
    }
}
