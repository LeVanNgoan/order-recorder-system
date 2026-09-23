package vn.orderrecorder.shopee;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.ArrayAdapter;
import android.widget.TextView;
import android.widget.Toast;

import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;

public final class MainActivity extends Activity {
    private static final String VERSION = "2.0.10";
    private static final int ORANGE = Color.rgb(238, 77, 45);
    private static final int GREEN = Color.rgb(18, 155, 73);
    private static final int BLUE = Color.rgb(40, 105, 225);
    private static final int TEXT = Color.rgb(32, 38, 45);
    private static final int MUTED = Color.rgb(105, 113, 122);
    private static final int BG = Color.rgb(246, 247, 249);
    private static final int REQ_EXPORT = 4201;
    private static final int REQ_NOTIFY = 4202;
    private static final int REQ_EXPORT_LOG = 4203;

    private TextView headerStatus, recorderStatus, recorderDetail, totalValue, doneValue, reviewValue;
    private TextView hubState, automationState, recentRows, reviewRows, permissionState;
    private View reviewCard;
    private Button toggleButton, autoButton;
    private long exportDayMs = 0L;

    private final android.os.Handler handler = new android.os.Handler();
    private final Runnable refresh = new Runnable() {
        @Override public void run() {
            render();
            handler.postDelayed(this, 1200L);
        }
    };

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        boolean recoveredProcess=AppPrefs.reconcileProcessStart(this);
        CompletionNotifier.ensureChannel(this);
        ReviewNotifier.ensureChannel(this);
        AppLog.init(this);
        if(recoveredProcess)AppLog.add(this,"PROCESS RECOVERY: app start đã release orphan in-flight state; queue được giữ");
        AppLog.add(this, "APP START v" + VERSION + " · persistent black-box logging active");
        requestNotificationPermission();
        HubSync.start(this);
        AutoStartReceiver.scheduleWatchdog(this);
        setContentView(buildUi());
    }

    @Override protected void onResume() {
        super.onResume();
        HubSync.start(this);
        handler.removeCallbacks(refresh);
        handler.post(refresh);
    }

    @Override protected void onPause() {
        super.onPause();
        handler.removeCallbacks(refresh);
    }

    private View buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(BG);

        root.addView(buildHeader(), new LinearLayout.LayoutParams(-1, dp(92)));

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(14), dp(14), dp(14), dp(18));
        scroll.addView(content, new ScrollView.LayoutParams(-1, -2));
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));

        content.addView(buildRecorderCard());
        content.addView(space(10));
        content.addView(buildStats());
        content.addView(space(10));
        content.addView(buildReviewCard());
        content.addView(space(10));
        content.addView(buildAutomationCard());
        content.addView(space(10));
        content.addView(buildHubCard());
        content.addView(space(10));
        content.addView(buildRecentCard());
        content.addView(space(10));
        content.addView(buildActionsCard());
        content.addView(space(10));
        content.addView(buildPermissionsCard());

        TextView footer = text("Developer by Ngoan, Le Van", 12, true, MUTED);
        footer.setGravity(Gravity.CENTER);
        footer.setPadding(0, dp(18), 0, dp(8));
        content.addView(footer);
        return root;
    }

    private View buildHeader() {
        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(dp(18), dp(10), dp(18), dp(10));
        header.setBackgroundColor(ORANGE);

        LinearLayout left = new LinearLayout(this);
        left.setOrientation(LinearLayout.VERTICAL);
        TextView title = text("ShopeeFood Recorder", 24, true, Color.WHITE);
        headerStatus = text("● Đang khởi động...", 13, true, Color.WHITE);
        left.addView(title);
        left.addView(headerStatus);
        header.addView(left, new LinearLayout.LayoutParams(0, -2, 1));

        TextView ver = text("v" + VERSION, 13, true, Color.WHITE);
        ver.setGravity(Gravity.CENTER);
        ver.setPadding(dp(12), dp(8), dp(12), dp(8));
        ver.setBackground(round(Color.argb(42, 255, 255, 255), 18, 0));
        header.addView(ver);
        return header;
    }

    private View buildRecorderCard() {
        LinearLayout card = card();
        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);

        LinearLayout labels = new LinearLayout(this);
        labels.setOrientation(LinearLayout.VERTICAL);
        labels.addView(text("TRẠNG THÁI GHI NHẬN", 12, true, MUTED));
        recorderStatus = text("Đang hoạt động", 22, true, GREEN);
        recorderDetail = text("Tự động ghi nhận đơn mới", 13, false, MUTED);
        labels.addView(recorderStatus);
        labels.addView(recorderDetail);
        top.addView(labels, new LinearLayout.LayoutParams(0, -2, 1));

        toggleButton = button("Tạm dừng", ORANGE);
        toggleButton.setOnClickListener(v -> {
            boolean next = !AppPrefs.isEnabled(this);
            AppPrefs.setEnabled(this, next);
            if (next) ShopeeNotificationListener.requestNext();
            render();
        });
        top.addView(toggleButton, new LinearLayout.LayoutParams(dp(126), dp(48)));
        card.addView(top);
        return card;
    }

    private View buildStats() {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        totalValue = statCard(row, "Đơn hôm nay", ORANGE);
        doneValue = statCard(row, "Đã có SĐT", GREEN);
        reviewValue = statCard(row, "Cần kiểm tra", Color.rgb(227, 139, 23));
        return row;
    }

    private TextView statCard(LinearLayout parent, String label, int valueColor) {
        LinearLayout box = card();
        box.setGravity(Gravity.CENTER);
        box.setPadding(dp(8), dp(13), dp(8), dp(13));
        TextView value = text("0", 27, true, valueColor);
        value.setGravity(Gravity.CENTER);
        TextView name = text(label, 12, false, MUTED);
        name.setGravity(Gravity.CENTER);
        box.addView(value);
        box.addView(name);
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, dp(96), 1);
        if (parent.getChildCount() > 0) p.leftMargin = dp(8);
        parent.addView(box, p);
        return value;
    }


    private View buildReviewCard() {
        LinearLayout card = card();
        reviewCard = card;
        LinearLayout titleRow = new LinearLayout(this);
        titleRow.setOrientation(LinearLayout.HORIZONTAL);
        titleRow.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = text("⚠ ĐƠN BỊ MISS HÔM NAY", 13, true, Color.rgb(198, 56, 48));
        titleRow.addView(title, new LinearLayout.LayoutParams(0, -2, 1));
        Button open = smallButton("Xem tất cả", Color.rgb(198, 56, 48));
        open.setOnClickListener(v -> startActivity(new Intent(this, TodayOrdersActivity.class)));
        titleRow.addView(open);
        card.addView(titleRow);
        TextView note = text("Các đơn dưới đây đã hết giới hạn tự động nhưng chưa ghi nhận được SĐT. Hãy ghi thủ công để backup.", 12, false, MUTED);
        note.setPadding(0, dp(6), 0, dp(7));
        card.addView(note);
        reviewRows = text("", 13, true, TEXT);
        reviewRows.setTypeface(Typeface.MONOSPACE);
        reviewRows.setLineSpacing(dp(3), 1f);
        card.addView(reviewRows);
        card.setVisibility(View.GONE);
        return card;
    }

    private View buildAutomationCard() {
        LinearLayout card = card();
        card.addView(text("TỰ ĐỘNG HÓA", 12, true, MUTED));
        automationState = text("Đang kiểm tra...", 16, true, TEXT);
        automationState.setPadding(0, dp(5), 0, 0);
        card.addView(automationState);
        TextView note = text("Đơn mới được nhận ngay và vẫn giữ thời gian âm báo. Sau đó app tạm khóa chạm trong vài giây để mở đúng đơn → lấy SĐT thật nhanh → lưu xong sẽ mở khóa ngay.", 12, false, MUTED);
        note.setPadding(0, dp(6), 0, dp(9));
        card.addView(note);
        autoButton = button("Tắt tự động thao tác", Color.rgb(75, 83, 92));
        autoButton.setOnClickListener(v -> {
            boolean next = !AppPrefs.isAutoEnabled(this);
            AppPrefs.setAutoEnabled(this, next);
            if (next) ShopeeNotificationListener.requestNext();
            render();
        });
        card.addView(autoButton, new LinearLayout.LayoutParams(-1, dp(44)));
        return card;
    }

    private View buildHubCard() {
        LinearLayout card = card();
        LinearLayout titleRow = new LinearLayout(this);
        titleRow.setOrientation(LinearLayout.HORIZONTAL);
        titleRow.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = text("ORDER RECORDER HUB", 13, true, TEXT);
        titleRow.addView(title, new LinearLayout.LayoutParams(0, -2, 1));
        TextView tag = text("PC", 11, true, GREEN);
        tag.setPadding(dp(9), dp(5), dp(9), dp(5));
        tag.setBackground(round(Color.rgb(232, 247, 237), 16, 0));
        titleRow.addView(tag);
        card.addView(titleRow);

        hubState = text("Đang kiểm tra kết nối...", 14, true, MUTED);
        hubState.setPadding(0, dp(7), 0, dp(10));
        card.addView(hubState);

        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        Button config = button("Cấu hình Hub", Color.rgb(75, 83, 92));
        config.setOnClickListener(v -> showHubConfig());
        Button sync = button("Đồng bộ ngay", GREEN);
        sync.setOnClickListener(v -> {
            HubSync.kick(this);
            Toast.makeText(this, "Đang đồng bộ · nếu IP Hub đổi app sẽ tự tìm lại...", Toast.LENGTH_SHORT).show();
        });
        actions.addView(config, new LinearLayout.LayoutParams(0, dp(46), 1));
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(0, dp(46), 1);
        sp.leftMargin = dp(8);
        actions.addView(sync, sp);
        card.addView(actions);

        Button findHub = button("Tự tìm lại Hub", Color.rgb(53, 91, 156));
        LinearLayout.LayoutParams findParams = new LinearLayout.LayoutParams(-1, dp(42));
        findParams.topMargin = dp(8);
        findHub.setOnClickListener(v -> {
            Toast.makeText(this, "Đang tìm Hub trong mạng nội bộ...", Toast.LENGTH_SHORT).show();
            HubSync.discoverNow(this, (ok, err) -> runOnUiThread(() -> {
                Toast.makeText(this, ok ? "Đã tìm và kết nối lại Hub" : "Không tìm thấy Hub: " + err, Toast.LENGTH_LONG).show();
                render();
            }));
        });
        card.addView(findHub, findParams);
        return card;
    }

    private View buildRecentCard() {
        LinearLayout card = card();
        LinearLayout titleRow = new LinearLayout(this);
        titleRow.setOrientation(LinearLayout.HORIZONTAL);
        titleRow.setGravity(Gravity.CENTER_VERTICAL);
        titleRow.addView(text("ĐƠN GẦN NHẤT", 13, true, TEXT), new LinearLayout.LayoutParams(0, -2, 1));
        Button open = smallButton("Xem tất cả", BLUE);
        open.setOnClickListener(v -> startActivity(new Intent(this, TodayOrdersActivity.class)));
        titleRow.addView(open);
        card.addView(titleRow);
        recentRows = text("Chưa có đơn hôm nay.", 13, false, TEXT);
        recentRows.setTypeface(Typeface.MONOSPACE);
        recentRows.setLineSpacing(dp(3), 1f);
        recentRows.setPadding(0, dp(10), 0, 0);
        card.addView(recentRows);
        return card;
    }

    private View buildActionsCard() {
        LinearLayout card = card();
        card.addView(text("DỮ LIỆU", 12, true, MUTED));
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(0, dp(8), 0, 0);
        Button today = button("Đơn hôm nay", Color.rgb(53, 91, 156));
        today.setOnClickListener(v -> startActivity(new Intent(this, TodayOrdersActivity.class)));
        Button export = button("Xuất Excel", GREEN);
        export.setOnClickListener(v -> chooseExportDay());
        row.addView(today, new LinearLayout.LayoutParams(0, dp(48), 1));
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, dp(48), 1);
        p.leftMargin = dp(8);
        row.addView(export, p);
        card.addView(row);

        TextView migrationNote = text("Khi chuyển sang Hub mới hoặc đã xóa database Hub, có thể gửi lại toàn bộ dữ liệu local còn trong 7 ngày. Hub tự chống trùng.", 11, false, MUTED);
        migrationNote.setPadding(0, dp(10), 0, dp(7));
        card.addView(migrationNote);
        Button forceResync = button("Đồng bộ lại dữ liệu 7 ngày", Color.rgb(120, 74, 24));
        forceResync.setOnClickListener(v -> confirmForceResync());
        card.addView(forceResync, new LinearLayout.LayoutParams(-1, dp(44)));
        return card;
    }

    private View buildPermissionsCard() {
        LinearLayout card = card();
        card.addView(text("HỆ THỐNG", 12, true, MUTED));
        permissionState = text("Đang kiểm tra quyền...", 13, true, TEXT);
        permissionState.setPadding(0, dp(6), 0, dp(9));
        card.addView(permissionState);

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        Button permissions = button("Quyền hệ thống", Color.rgb(75, 83, 92));
        permissions.setOnClickListener(v -> showPermissionMenu());
        Button logs = button("Nhật ký kỹ thuật", Color.rgb(75, 83, 92));
        logs.setOnClickListener(v -> showLogs());
        row.addView(permissions, new LinearLayout.LayoutParams(0, dp(46), 1));
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, dp(46), 1);
        p.leftMargin = dp(8);
        row.addView(logs, p);
        card.addView(row);

        TextView logNote = text("Nhật ký kỹ thuật được lưu liên tục qua nhiều ngày và qua các lần cập nhật app. Chỉ mất khi gỡ app hoặc xóa dữ liệu ứng dụng.", 11, false, MUTED);
        logNote.setPadding(0, dp(9), 0, dp(7));
        card.addView(logNote);
        Button exportLogs = button("Xuất toàn bộ nhật ký kỹ thuật", BLUE);
        exportLogs.setOnClickListener(v -> startLogExport());
        card.addView(exportLogs, new LinearLayout.LayoutParams(-1, dp(46)));
        return card;
    }

    private void render() {
        boolean enabled = AppPrefs.isEnabled(this);
        boolean auto = AppPrefs.isAutoEnabled(this);
        boolean userActive = AppPrefs.isUserActive(this);
        if (autoButton != null) {
            autoButton.setText(auto ? "Tắt tự động thao tác" : "Bật tự động thao tác");
            autoButton.setBackground(round(auto ? Color.rgb(75,83,92) : GREEN, 12, 0));
        }
        toggleButton.setText(enabled ? "Tạm dừng" : "Bật ghi nhận");
        toggleButton.setBackground(round(enabled ? ORANGE : GREEN, 12, 0));

        if (!enabled) {
            headerStatus.setText("● Đang tạm dừng");
            recorderStatus.setText("Đang tạm dừng");
            recorderStatus.setTextColor(ORANGE);
            recorderDetail.setText("Thông báo mới sẽ không được ghi nhận");
        } else {
            headerStatus.setText("● Đang hoạt động");
            recorderStatus.setText("Đang hoạt động");
            recorderStatus.setTextColor(GREEN);
            recorderDetail.setText(auto ? "Tự động ghi nhận đơn mới" : "Đang ghi nhận · tự động thao tác đang tắt");
        }

        List<OrderRecord> today = OrderStore.getToday(this);
        int done = 0, review = 0;
        for (OrderRecord r : today) {
            if (r.isCompleted()) done++;
            else if ("needs_review".equals(r.status)) review++;
        }
        totalValue.setText(String.valueOf(today.size()));
        doneValue.setText(String.valueOf(done));
        reviewValue.setText(String.valueOf(review));
        renderReviews(today, review);

        String current = AppPrefs.getProcessingOrder(this);
        int queued = AppPrefs.queueSize(this);
        if (!enabled) {
            automationState.setText("Tạm dừng · " + queued + " đơn đang chờ");
            automationState.setTextColor(ORANGE);
        } else if (!current.isEmpty() && AppPrefs.isExclusiveAutomation(this)) {
            OrderRecord r = OrderStore.get(this, current);
            String code = r == null ? ("#" + current) : r.businessOrderCode();
            automationState.setText("Đang ghi nhanh " + (code.isEmpty() ? ("#" + current) : code) + " · màn hình tạm khóa");
            automationState.setTextColor(ORANGE);
        } else if (userActive) {
            automationState.setText("Có thao tác thủ công · chờ đơn tự động tiếp theo");
            automationState.setTextColor(Color.rgb(196, 116, 10));
        } else if (!current.isEmpty()) {
            OrderRecord r = OrderStore.get(this, current);
            String code = r == null ? ("#" + current) : r.businessOrderCode();
            automationState.setText("Đang ghi nhận " + (code.isEmpty() ? ("#" + current) : code) + " · còn " + queued + " đơn chờ");
            automationState.setTextColor(GREEN);
        } else if (queued > 0) {
            automationState.setText("Sẵn sàng xử lý · " + queued + " đơn đang chờ");
            automationState.setTextColor(BLUE);
        } else {
            automationState.setText("Sẵn sàng · không có đơn chờ");
            automationState.setTextColor(GREEN);
        }

        renderHub();
        renderRecent(today);
        renderPermissions();
    }


    private void renderReviews(List<OrderRecord> today, int reviewCount) {
        if (reviewCard == null || reviewRows == null) return;
        if (reviewCount <= 0) {
            reviewCard.setVisibility(View.GONE);
            reviewRows.setText("");
            return;
        }
        reviewCard.setVisibility(View.VISIBLE);
        SimpleDateFormat f = new SimpleDateFormat("HH:mm:ss", Locale.getDefault());
        f.setTimeZone(TimeZone.getTimeZone("Asia/Ho_Chi_Minh"));
        StringBuilder out = new StringBuilder();
        int shown = 0;
        for (OrderRecord r : today) {
            if (!"needs_review".equals(r.status) || r.isCompleted()) continue;
            shown++;
            String code = r.businessOrderCode();
            if (code.isEmpty()) code = "#" + TextParser.normalizeShortId(r.shortOrderId);
            if (out.length() > 0) out.append("\n");
            out.append("• ").append(code).append(" · ")
                    .append(r.receivedAt > 0 ? f.format(new Date(r.receivedAt)) : "—")
                    .append(" · CHƯA CÓ SĐT");
            if (shown >= 6) break;
        }
        if (reviewCount > shown) out.append("\n• +").append(reviewCount - shown).append(" đơn khác · bấm Xem tất cả");
        reviewRows.setText(out.toString());
    }

    private void renderHub() {
        int pending = HubSync.pendingCount(this);
        if (!HubPrefs.isConfigured(this)) {
            hubState.setText("Chưa cấu hình · " + pending + " đơn chờ đồng bộ");
            hubState.setTextColor(MUTED);
            return;
        }
        String err = HubPrefs.lastError(this);
        long okAt = HubPrefs.lastOk(this);
        boolean online = okAt > 0 && System.currentTimeMillis() - okAt < 95_000L && err.isEmpty();
        if (online) {
            long discoveredAt = HubPrefs.lastDiscovery(this);
            String auto = discoveredAt > 0 ? " · Auto Discovery" : "";
            hubState.setText("● Online" + auto + " · " + (pending == 0 ? "đã đồng bộ hết" : pending + " đơn chờ đồng bộ"));
            hubState.setTextColor(GREEN);
        } else if (!err.isEmpty()) {
            hubState.setText("● Offline · " + pending + " đơn chờ · sẽ tự gửi lại");
            hubState.setTextColor(Color.rgb(198, 56, 48));
        } else {
            hubState.setText("Đã cấu hình · đang kiểm tra kết nối");
            hubState.setTextColor(MUTED);
        }
    }

    private void renderRecent(List<OrderRecord> today) {
        if (today.isEmpty()) {
            recentRows.setText("Chưa có đơn hôm nay.");
            return;
        }
        SimpleDateFormat f = new SimpleDateFormat("HH:mm:ss dd-MM-yyyy", Locale.getDefault());
        f.setTimeZone(TimeZone.getTimeZone("Asia/Ho_Chi_Minh"));
        StringBuilder s = new StringBuilder();
        int shown = Math.min(5, today.size());
        for (int i = 0; i < shown; i++) {
            OrderRecord r = today.get(i);
            String code = r.businessOrderCode();
            if (code.isEmpty()) code = "Chưa xác định";
            String phone = r.isCompleted() ? TextParser.normalizePhone(r.receiverPhone) : "Chưa có SĐT";
            s.append(i + 1).append("  ").append(code).append("\n")
                    .append("    ").append(phone).append(" · ")
                    .append(r.receivedAt > 0 ? f.format(new Date(r.receivedAt)) : "—");
            if (i < shown - 1) s.append("\n\n");
        }
        recentRows.setText(s.toString());
    }

    private void renderPermissions() {
        boolean n = hasNotificationAccess();
        boolean a = hasAccessibilityAccess();
        permissionState.setText((n ? "✓" : "!") + " Thông báo   ·   " + (a ? "✓" : "!") + " Accessibility");
        permissionState.setTextColor(n && a ? GREEN : Color.rgb(196, 116, 10));
    }

    private void chooseExportDay() {
        List<OrderRecord> all = OrderStore.getAll(this);
        final ArrayList<Long> days = new ArrayList<>();
        final ArrayList<String> labels = new ArrayList<>();
        TimeZone vn = TimeZone.getTimeZone("Asia/Ho_Chi_Minh");
        SimpleDateFormat key = new SimpleDateFormat("yyyyMMdd", Locale.US); key.setTimeZone(vn);
        SimpleDateFormat label = new SimpleDateFormat("dd-MM-yyyy", Locale.getDefault()); label.setTimeZone(vn);
        String last = "";
        for (OrderRecord r : all) {
            if (r.receivedAt <= 0) continue;
            String k = key.format(new Date(r.receivedAt));
            if (k.equals(last)) continue;
            last = k;
            Calendar c = Calendar.getInstance(vn);
            c.setTimeInMillis(r.receivedAt);
            c.set(Calendar.HOUR_OF_DAY, 12); c.set(Calendar.MINUTE, 0); c.set(Calendar.SECOND, 0); c.set(Calendar.MILLISECOND, 0);
            long d = c.getTimeInMillis();
            days.add(d);
            labels.add(label.format(new Date(d)) + " · " + OrderStore.getForDay(this, d).size() + " đơn");
        }
        if (days.isEmpty()) {
            Toast.makeText(this, "Chưa có dữ liệu để xuất.", Toast.LENGTH_SHORT).show();
            return;
        }
        Spinner spinner = new Spinner(this);
        spinner.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, labels));
        spinner.setPadding(dp(12), dp(4), dp(12), dp(4));
        new AlertDialog.Builder(this)
                .setTitle("Chọn ngày xuất Excel")
                .setView(spinner)
                .setNegativeButton("Hủy", null)
                .setPositiveButton("Xuất Excel", (d, w) -> {
                    int pos = spinner.getSelectedItemPosition();
                    if (pos >= 0 && pos < days.size()) startExport(days.get(pos));
                }).show();
    }

    private void startExport(long dayMs) {
        exportDayMs = dayMs;
        Intent i = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
        SimpleDateFormat fileDate = new SimpleDateFormat("dd-MM-yyyy", Locale.getDefault()); fileDate.setTimeZone(TimeZone.getTimeZone("Asia/Ho_Chi_Minh"));
        String d = fileDate.format(new Date(dayMs));
        i.putExtra(Intent.EXTRA_TITLE, "DonHangShopeeFood_" + d + ".xlsx");
        startActivityForResult(i, REQ_EXPORT);
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode != RESULT_OK || data == null) return;
        Uri uri = data.getData();
        if (uri == null) return;

        if (requestCode == REQ_EXPORT_LOG) {
            try (OutputStream os = getContentResolver().openOutputStream(uri)) {
                AppLog.add(this, "LOG EXPORT: người dùng yêu cầu xuất toàn bộ nhật ký");
                AppLog.writeExport(this, os, VERSION);
                Toast.makeText(this, "Đã xuất toàn bộ nhật ký kỹ thuật.", Toast.LENGTH_LONG).show();
            } catch (Exception e) {
                Toast.makeText(this, "Không xuất được nhật ký: " + e.getClass().getSimpleName(), Toast.LENGTH_LONG).show();
            }
            return;
        }

        if (requestCode != REQ_EXPORT || exportDayMs <= 0) return;
        List<OrderRecord> selected = OrderStore.getForDay(this, exportDayMs);
        try (OutputStream os = getContentResolver().openOutputStream(uri)) {
            XlsxExporter.write(os, selected);
            Toast.makeText(this, "Đã xuất " + selected.size() + " đơn.", Toast.LENGTH_LONG).show();
        } catch (Exception e) {
            Toast.makeText(this, "Không xuất được Excel: " + e.getClass().getSimpleName(), Toast.LENGTH_LONG).show();
        } finally {
            exportDayMs = 0L;
        }
    }

    private void showHubConfig() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(20), dp(4), dp(20), 0);
        TextView help = text("Nhập địa chỉ Hub và API key lần đầu. Từ v2.0.7, nếu PC đổi IP sau khi restart, SUNMI sẽ tự tìm Hub trong LAN, xác thực bằng API key cũ rồi tự cập nhật địa chỉ.", 13, false, MUTED);
        box.addView(help);
        EditText url = new EditText(this);
        url.setHint("VD: http://192.168.1.20:17891");
        url.setSingleLine(true);
        url.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        url.setText(HubPrefs.getUrl(this));
        box.addView(url);
        EditText key = new EditText(this);
        key.setHint("API key");
        key.setSingleLine(true);
        key.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD);
        key.setText(HubPrefs.getKey(this));
        box.addView(key);
        new AlertDialog.Builder(this)
                .setTitle("Kết nối Order Recorder Hub")
                .setView(box)
                .setNegativeButton("Hủy", null)
                .setNeutralButton("Xóa cấu hình", (d, w) -> { HubPrefs.clear(this); render(); })
                .setPositiveButton("Lưu & kiểm tra", (d, w) -> {
                    HubPrefs.save(this, url.getText().toString(), key.getText().toString());
                    HubSync.test(this, (ok, err) -> runOnUiThread(() -> {
                        if (ok) {
                            Toast.makeText(this, "Đã kết nối Hub · Auto Discovery đang hoạt động", Toast.LENGTH_LONG).show();
                            HubSync.kick(this);
                        } else {
                            Toast.makeText(this, "Chưa kết nối được Hub: " + err, Toast.LENGTH_LONG).show();
                        }
                        render();
                    }));
                }).show();
    }

    private void confirmForceResync() {
        new AlertDialog.Builder(this)
                .setTitle("Đồng bộ lại dữ liệu 7 ngày")
                .setMessage("Gửi lại toàn bộ đơn ShopeeFood còn lưu trên thiết bị trong 7 ngày lên Hub.\n\nDữ liệu local không bị xóa và Hub sẽ tự chống trùng. Chỉ dùng khi chuyển sang Hub mới hoặc đã xóa database Hub.")
                .setNegativeButton("Hủy", null)
                .setPositiveButton("Đồng bộ lại", (d, w) -> {
                    Toast.makeText(this, "Đang quét và đồng bộ lại dữ liệu cũ...", Toast.LENGTH_LONG).show();
                    HubSync.forceResync7Days(this, (ok, sent, skipped, err) -> runOnUiThread(() -> {
                        if (ok) {
                            Toast.makeText(this, "Đã gửi lại " + sent + " đơn" + (skipped > 0 ? " · bỏ qua " + skipped + " record chưa hoàn chỉnh" : ""), Toast.LENGTH_LONG).show();
                        } else {
                            Toast.makeText(this, "Đồng bộ lại dừng sau " + sent + " đơn: " + err, Toast.LENGTH_LONG).show();
                        }
                        render();
                    }));
                }).show();
    }

    private void showPermissionMenu() {
        String[] items = {"Quyền đọc thông báo", "Quyền Accessibility"};
        new AlertDialog.Builder(this).setTitle("Quyền hệ thống").setItems(items, (d, which) -> {
            Intent i = new Intent(which == 0 ? Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS : Settings.ACTION_ACCESSIBILITY_SETTINGS);
            startActivity(i);
        }).setNegativeButton("Đóng", null).show();
    }

    private void startLogExport() {
        Intent i = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("text/plain");
        SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd_HHmm", Locale.US);
        f.setTimeZone(TimeZone.getTimeZone("Asia/Ho_Chi_Minh"));
        i.putExtra(Intent.EXTRA_TITLE, "SPF_Technical_Log_" + f.format(new Date()) + ".txt");
        startActivityForResult(i, REQ_EXPORT_LOG);
    }

    private void showLogs() {
        TextView v = text(AppLog.get(this), 11, false, TEXT);
        v.setTypeface(Typeface.MONOSPACE);
        v.setTextIsSelectable(true);
        v.setPadding(dp(16), dp(8), dp(16), dp(8));
        ScrollView s = new ScrollView(this);
        s.addView(v);
        new AlertDialog.Builder(this)
                .setTitle("Nhật ký kỹ thuật gần đây")
                .setMessage("Đã lưu " + AppLog.dayCount(this) + " ngày · " + Math.max(1L, AppLog.totalBytes(this) / 1024L) + " KB. File đầy đủ không tự xóa theo 7 ngày dữ liệu đơn.")
                .setView(s)
                .setNeutralButton("Xuất toàn bộ", (d, w) -> startLogExport())
                .setPositiveButton("Đóng", null)
                .show();
    }

    private boolean hasNotificationAccess() {
        try {
            String enabled = Settings.Secure.getString(getContentResolver(), "enabled_notification_listeners");
            return enabled != null && enabled.contains(getPackageName());
        } catch (Exception e) { return false; }
    }

    private boolean hasAccessibilityAccess() {
        try {
            String enabled = Settings.Secure.getString(getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
            return enabled != null && enabled.toLowerCase(Locale.ROOT).contains(getPackageName().toLowerCase(Locale.ROOT));
        } catch (Exception e) { return false; }
    }

    private void requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQ_NOTIFY);
        }
    }

    private LinearLayout card() {
        LinearLayout v = new LinearLayout(this);
        v.setOrientation(LinearLayout.VERTICAL);
        v.setPadding(dp(14), dp(14), dp(14), dp(14));
        v.setBackground(round(Color.WHITE, 16, Color.rgb(227, 230, 233)));
        return v;
    }

    private Button button(String s, int color) {
        Button b = new Button(this);
        b.setText(s);
        b.setAllCaps(false);
        b.setTextSize(13);
        b.setTextColor(Color.WHITE);
        b.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        b.setPadding(dp(8), 0, dp(8), 0);
        b.setBackground(round(color, 12, 0));
        return b;
    }

    private Button smallButton(String s, int color) {
        Button b = button(s, color);
        b.setTextSize(11);
        b.setMinHeight(0);
        b.setMinimumHeight(0);
        b.setPadding(dp(11), dp(5), dp(11), dp(5));
        return b;
    }

    private TextView text(String s, int size, boolean bold, int color) {
        TextView v = new TextView(this);
        v.setText(s);
        v.setTextSize(size);
        v.setTextColor(color);
        v.setTypeface(Typeface.DEFAULT, bold ? Typeface.BOLD : Typeface.NORMAL);
        return v;
    }

    private View space(int h) {
        View v = new View(this);
        v.setLayoutParams(new LinearLayout.LayoutParams(1, dp(h)));
        return v;
    }

    private GradientDrawable round(int fill, int radiusDp, int stroke) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(fill);
        d.setCornerRadius(dp(radiusDp));
        if (stroke != 0) d.setStroke(dp(1), stroke);
        return d;
    }

    private int dp(int v) { return Math.round(v * getResources().getDisplayMetrics().density); }
}
