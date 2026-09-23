package vn.orderrecorder.shopee;

import android.Manifest;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;

public final class ReviewNotifier {
    private static final String CHANNEL_ID = "order_needs_review";
    private static final String CHANNEL_NAME = "Đơn bị miss - Cần kiểm tra";
    private ReviewNotifier() {}

    public static void ensureChannel(Context c) {
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm == null) return;
            NotificationChannel ch = new NotificationChannel(CHANNEL_ID, CHANNEL_NAME, NotificationManager.IMPORTANCE_HIGH);
            ch.setDescription("Cảnh báo đơn ShopeeFood chưa ghi nhận được SĐT và cần nhân viên kiểm tra thủ công");
            ch.enableVibration(true);
            nm.createNotificationChannel(ch);
        }
    }

    private static int notificationId(OrderRecord r) {
        String code = r == null ? "" : r.businessOrderCode();
        String shortId = r == null ? "" : TextParser.normalizeShortId(r.shortOrderId);
        String session = r == null || r.sessionId == null ? "" : r.sessionId;
        return 50000 + Math.abs((session + code + shortId).hashCode() % 10000);
    }

    public static void cancel(Context c, OrderRecord r) {
        if (c == null || r == null) return;
        NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm != null) nm.cancel(notificationId(r));
    }

    public static void notifyNeedsReview(Context c, OrderRecord r) {
        if (c == null || r == null || r.isCompleted()) return;
        ensureChannel(c);
        if (Build.VERSION.SDK_INT >= 33 && c.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return;

        String code = r.businessOrderCode();
        String label = code.isEmpty() ? ("đơn #" + TextParser.normalizeShortId(r.shortOrderId)) : code;
        Intent open = new Intent(c, TodayOrdersActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent pi = PendingIntent.getActivity(c,
                7000 + Math.abs((r.sessionId + label).hashCode() % 20000),
                open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        android.app.Notification.Builder b = Build.VERSION.SDK_INT >= 26
                ? new android.app.Notification.Builder(c, CHANNEL_ID)
                : new android.app.Notification.Builder(c);
        b.setSmallIcon(R.drawable.ic_app)
                .setContentTitle("⚠ Cần kiểm tra " + label)
                .setContentText("Chưa ghi nhận được SĐT. Vui lòng ghi thủ công để backup.")
                .setStyle(new android.app.Notification.BigTextStyle().bigText(
                        "Đơn " + label + " đã hết giới hạn tự động nhưng chưa ghi nhận được SĐT. " +
                        "App sẽ không tự mở lại đơn này. Vui lòng ghi SĐT thủ công để backup."))
                .setAutoCancel(true)
                .setContentIntent(pi)
                .setPriority(android.app.Notification.PRIORITY_HIGH)
                .setCategory(android.app.Notification.CATEGORY_ERROR);
        NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm != null) nm.notify(notificationId(r), b.build());
    }
}
