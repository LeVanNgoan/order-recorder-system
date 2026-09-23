package vn.orderrecorder.shopee;

import android.Manifest;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class CompletionNotifier {
    private static final String CHANNEL_ID="order_recorded";
    private static final String CHANNEL_NAME="Đơn đã ghi nhận";
    private static final Pattern FOUR_DIGIT_DISPLAY_CODE=Pattern.compile("#\\s*(\\d{4})(?!\\d)");
    private CompletionNotifier() {}

    public static void ensureChannel(Context c){
        if(Build.VERSION.SDK_INT>=26){
            NotificationManager nm=(NotificationManager)c.getSystemService(Context.NOTIFICATION_SERVICE);
            if(nm==null)return;
            NotificationChannel ch=new NotificationChannel(CHANNEL_ID,CHANNEL_NAME,NotificationManager.IMPORTANCE_HIGH);
            ch.setDescription("Thông báo khi đã ghi nhận xong SĐT Khách nhận đơn");
            nm.createNotificationChannel(ch);
        }
    }

    public static String completionCode(OrderRecord r){
        if(r==null||r.displayOrderId==null)return"";
        Matcher m=FOUR_DIGIT_DISPLAY_CODE.matcher(r.displayOrderId);
        return m.find()?m.group(1):"";
    }

    public static void notifyRecorded(Context c,OrderRecord r){
        if(c==null||r==null)return;
        String code=completionCode(r);
        if(code.isEmpty())return;
        ensureChannel(c);
        if(Build.VERSION.SDK_INT>=33 && c.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED)return;
        Intent open=new Intent(c,MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent pi=PendingIntent.getActivity(c,1000+code.hashCode(),open,PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
        android.app.Notification.Builder b=Build.VERSION.SDK_INT>=26
                ?new android.app.Notification.Builder(c,CHANNEL_ID)
                :new android.app.Notification.Builder(c);
        b.setSmallIcon(R.drawable.ic_app)
                .setContentTitle("Đã ghi nhận đơn #"+code)
                .setContentText("Đã lưu SĐT Khách nhận đơn")
                .setAutoCancel(true)
                .setContentIntent(pi)
                .setPriority(android.app.Notification.PRIORITY_HIGH)
                .setCategory(android.app.Notification.CATEGORY_STATUS);
        NotificationManager nm=(NotificationManager)c.getSystemService(Context.NOTIFICATION_SERVICE);
        if(nm!=null)nm.notify(30000+(Math.abs(code.hashCode())%20000),b.build());
    }
}
