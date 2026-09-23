package vn.orderrecorder.shopee;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.SystemClock;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Persistent technical "black box" logger.
 *
 * Design goals:
 * - Never do disk I/O on the capture hot-path: add() only enqueues a tiny write task.
 * - Keep logs across days and app updates until app data is cleared/uninstalled.
 * - Store one UTF-8 file per Vietnam calendar day for safe incremental append.
 * - Export all days into one human-readable text file for root-cause analysis.
 * - Do not log actual customer phone numbers.
 */
public final class AppLog {
    private static final String LEGACY_PREF = "recorder_log";
    private static final String LEGACY_KEY = "lines";
    private static final String MIGRATION_PREF = "persistent_tech_log";
    private static final String MIGRATION_KEY = "legacy_migrated_v206";
    private static final String DIR_NAME = "technical_logs";
    private static final TimeZone VN = TimeZone.getTimeZone("Asia/Ho_Chi_Minh");
    private static final ExecutorService IO = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "spf-tech-log");
        t.setDaemon(true);
        return t;
    });
    private static volatile boolean initRequested = false;

    private AppLog() {}

    public static void init(Context c) {
        if (c == null || initRequested) return;
        synchronized (AppLog.class) {
            if (initRequested) return;
            initRequested = true;
            Context app = c.getApplicationContext();
            IO.execute(() -> migrateLegacy(app));
        }
    }

    public static void add(Context c, String msg) {
        if (c == null || msg == null) return;
        init(c);
        Context app = c.getApplicationContext();
        final long wall = System.currentTimeMillis();
        final long uptime = SystemClock.elapsedRealtime();
        final String thread = Thread.currentThread().getName();
        final String safe = sanitize(msg);
        IO.execute(() -> append(app, wall, uptime, thread, safe));
    }

    /** Recent lines for the on-device dialog only. Full history is available via export. */
    public static String get(Context c) {
        return getRecent(c, 120);
    }

    public static String getRecent(Context c, int maxLines) {
        flush(1500L);
        try {
            List<File> files = logFiles(c);
            if (files.isEmpty()) return "Chưa có nhật ký kỹ thuật";
            List<String> all = new ArrayList<>();
            for (int fi = files.size() - 1; fi >= 0 && all.size() < maxLines; fi--) {
                List<String> lines = readLines(files.get(fi));
                for (int i = lines.size() - 1; i >= 0 && all.size() < maxLines; i--) all.add(lines.get(i));
            }
            Collections.reverse(all);
            StringBuilder out = new StringBuilder();
            for (String line : all) {
                if (out.length() > 0) out.append('\n');
                out.append(line);
            }
            if (totalLineEstimate(files) > all.size()) {
                out.insert(0, "… đang hiển thị " + all.size() + " dòng gần nhất. Dùng 'Xuất toàn bộ nhật ký' để lấy tất cả các ngày.\n\n");
            }
            return out.toString();
        } catch (Exception e) {
            return "Không đọc được nhật ký: " + e.getClass().getSimpleName();
        }
    }

    public static int dayCount(Context c) {
        flush(1000L);
        return logFiles(c).size();
    }

    public static long totalBytes(Context c) {
        flush(1000L);
        long n = 0L;
        for (File f : logFiles(c)) n += f.length();
        return n;
    }

    public static void writeExport(Context c, OutputStream os, String appVersion) throws Exception {
        if (os == null) throw new IllegalArgumentException("output stream is null");
        flush(5000L);
        List<File> files = logFiles(c);
        BufferedWriter w = new BufferedWriter(new OutputStreamWriter(os, StandardCharsets.UTF_8));
        w.write("SHOPEEFOOD RECORDER - TECHNICAL BLACK BOX LOG\n");
        w.write("App version: " + value(appVersion) + "\n");
        w.write("Package: " + c.getPackageName() + "\n");
        w.write("Device: " + Build.MANUFACTURER + " " + Build.MODEL + "\n");
        w.write("Android: " + Build.VERSION.RELEASE + " (SDK " + Build.VERSION.SDK_INT + ")\n");
        w.write("Exported at: " + fullTime(System.currentTimeMillis()) + " Asia/Ho_Chi_Minh\n");
        w.write("Days recorded: " + files.size() + "\n");
        w.write("Privacy: technical log does NOT intentionally include full customer phone numbers.\n");
        w.write("Format: timestamp | uptime | thread | event\n");
        w.write("======================================================================\n");
        if (files.isEmpty()) {
            w.write("Chưa có nhật ký kỹ thuật.\n");
        } else {
            for (File f : files) {
                w.write("\n===== " + f.getName().replace(".log", "") + " =====\n");
                try (BufferedReader r = new BufferedReader(new InputStreamReader(new FileInputStream(f), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = r.readLine()) != null) {
                        w.write(line);
                        w.write('\n');
                    }
                }
            }
        }
        w.flush();
    }

    /** Explicit deletion only; normal operation never automatically deletes history. */
    public static void clear(Context c) {
        flush(2000L);
        File dir = dir(c);
        File[] fs = dir.listFiles();
        if (fs != null) for (File f : fs) if (f.isFile() && f.getName().endsWith(".log")) f.delete();
    }

    private static void append(Context c, long wall, long uptime, String thread, String msg) {
        try {
            File dir = dir(c);
            if (!dir.exists() && !dir.mkdirs()) return;
            File f = new File(dir, day(wall) + ".log");
            try (BufferedWriter w = new BufferedWriter(new OutputStreamWriter(new FileOutputStream(f, true), StandardCharsets.UTF_8))) {
                w.write(fullTime(wall));
                w.write(" | up=");
                w.write(String.valueOf(uptime));
                w.write("ms | ");
                w.write(thread == null ? "?" : thread);
                w.write(" | ");
                w.write(msg);
                w.write('\n');
            }
        } catch (Exception ignored) {
            // Logging must never crash or block order capture.
        }
    }

    private static void migrateLegacy(Context c) {
        try {
            SharedPreferences state = c.getSharedPreferences(MIGRATION_PREF, Context.MODE_PRIVATE);
            if (state.getBoolean(MIGRATION_KEY, false)) return;
            String old = c.getSharedPreferences(LEGACY_PREF, Context.MODE_PRIVATE).getString(LEGACY_KEY, "");
            if (old != null && !old.trim().isEmpty()) {
                long now = System.currentTimeMillis();
                append(c, now, SystemClock.elapsedRealtime(), "migration", "LEGACY v2.0.5: bắt đầu nhập tối đa 24 dòng nhật ký cũ");
                for (String line : old.split("\\n")) {
                    String s = sanitize(line);
                    if (!s.isEmpty()) append(c, now, SystemClock.elapsedRealtime(), "migration", "LEGACY | " + s);
                }
                append(c, now, SystemClock.elapsedRealtime(), "migration", "LEGACY v2.0.5: kết thúc nhập nhật ký cũ");
            }
            state.edit().putBoolean(MIGRATION_KEY, true).apply();
        } catch (Exception ignored) {}
    }

    private static boolean flush(long timeoutMs) {
        try {
            IO.submit(() -> {}).get(Math.max(100L, timeoutMs), TimeUnit.MILLISECONDS);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private static File dir(Context c) { return new File(c.getFilesDir(), DIR_NAME); }

    private static List<File> logFiles(Context c) {
        File[] fs = dir(c).listFiles((d, name) -> name != null && name.matches("\\d{4}-\\d{2}-\\d{2}\\.log"));
        if (fs == null || fs.length == 0) return new ArrayList<>();
        Arrays.sort(fs, (a, b) -> a.getName().compareTo(b.getName()));
        return new ArrayList<>(Arrays.asList(fs));
    }

    private static List<String> readLines(File f) throws Exception {
        List<String> out = new ArrayList<>();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(new FileInputStream(f), StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) out.add(line);
        }
        return out;
    }

    private static long totalLineEstimate(List<File> files) {
        long n = 0L;
        for (File f : files) n += Math.max(1L, f.length() / 90L);
        return n;
    }

    private static String day(long ms) {
        SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd", Locale.US);
        f.setTimeZone(VN);
        return f.format(new Date(ms));
    }

    private static String fullTime(long ms) {
        SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US);
        f.setTimeZone(VN);
        return f.format(new Date(ms));
    }

    private static String sanitize(String s) {
        if (s == null) return "";
        return s.replace('\r', ' ').replace('\n', ' ').trim();
    }

    private static String value(String s) { return s == null ? "" : s; }
}
