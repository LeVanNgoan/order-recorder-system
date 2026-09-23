package vn.orderrecorder.shopee;

import android.content.Context;
import android.content.SharedPreferences;
import org.json.JSONArray;
import java.util.ArrayList;
import java.util.List;

/**
 * Automation state for v2.0.3.
 * Deliberately follows the proven v0.2.5 state model: one order at a time, FIFO,
 * no user-idle arbitration, no exclusive overlay, no parallel retry worker.
 */
public final class AppPrefs {
    private static final String FILE = "recorder_prefs";
    private static final String ENABLED = "enabled";
    private static final String AUTO = "auto_enabled";
    private static final String PROCESSING_ORDER = "processing_order";
    private static final String PROCESSING_AT = "processing_at";
    private static final String ACTIVE_DETAIL_ORDER = "active_detail_order";
    private static final String CONTACT_ORDER = "contact_order";
    private static final String CAPTURE_ORDER = "capture_order";
    private static final String CAPTURE_AT = "capture_at";
    private static final String STAGE = "automation_stage";
    private static final String STAGE_AT = "automation_stage_at";
    private static final String QUEUE = "automation_queue";
    private static final String CONTACT_SHEET_AT = "contact_sheet_at";
    private static final String RECEIVER_Y = "receiver_y";
    private static final String PURCHASER_Y = "purchaser_y";
    private static boolean PROCESS_STATE_RECONCILED = false;

    public static final String STAGE_IDLE = "IDLE";
    public static final String STAGE_OPENING_ORDER = "OPENING_ORDER";
    public static final String STAGE_DETAIL = "DETAIL";
    public static final String STAGE_OPENING_CONTACT = "OPENING_CONTACT";
    public static final String STAGE_CONTACT_SHEET = "CONTACT_SHEET";
    public static final String STAGE_OPENING_DIALER = "OPENING_DIALER";
    public static final String STAGE_READING_PHONE = "READING_PHONE";

    private AppPrefs() {}
    private static SharedPreferences p(Context c) { return c.getSharedPreferences(FILE, Context.MODE_PRIVATE); }

    public static boolean isEnabled(Context c) { return p(c).getBoolean(ENABLED, true); }
    public static void setEnabled(Context c, boolean value) {
        p(c).edit().putBoolean(ENABLED, value).apply();
        if (!value) cancelProcessingKeepQueued(c);
    }

    public static boolean isAutoEnabled(Context c) { return p(c).getBoolean(AUTO, true); }
    public static void setAutoEnabled(Context c, boolean value) {
        p(c).edit().putBoolean(AUTO, value).apply();
        if (!value) cancelProcessingKeepQueued(c);
    }

    public static synchronized void enqueue(Context c, String rawId) {
        String id = TextParser.normalizeShortId(rawId);
        if (id.isEmpty()) return;
        List<String> q = queue(c);
        if (!q.contains(id) && !id.equals(getProcessingOrder(c))) q.add(id);
        saveQueue(c, q);
    }

    public static synchronized String peekQueue(Context c) {
        List<String> q = queue(c);
        return q.isEmpty() ? "" : q.get(0);
    }

    public static synchronized void removeFromQueue(Context c, String rawId) {
        String id = TextParser.normalizeShortId(rawId);
        List<String> q = queue(c);
        q.removeIf(x -> x.equals(id));
        saveQueue(c, q);
    }

    public static synchronized void moveToQueueEnd(Context c, String rawId) {
        String id = TextParser.normalizeShortId(rawId);
        if (id.isEmpty()) return;
        List<String> q = queue(c);
        q.removeIf(x -> x.equals(id));
        q.add(id);
        saveQueue(c, q);
    }

    public static synchronized int queueSize(Context c) { return queue(c).size(); }
    public static synchronized boolean isQueued(Context c, String rawId) { return queue(c).contains(TextParser.normalizeShortId(rawId)); }
    public static synchronized List<String> queueSnapshot(Context c) { return new ArrayList<>(queue(c)); }

    private static List<String> queue(Context c) {
        ArrayList<String> out = new ArrayList<>();
        try {
            JSONArray a = new JSONArray(p(c).getString(QUEUE, "[]"));
            for (int i = 0; i < a.length(); i++) {
                String s = TextParser.normalizeShortId(a.optString(i, ""));
                if (!s.isEmpty() && !out.contains(s)) out.add(s);
            }
        } catch (Exception ignored) {}
        return out;
    }

    private static void saveQueue(Context c, List<String> q) {
        JSONArray a = new JSONArray();
        for (String s : q) a.put(s);
        p(c).edit().putString(QUEUE, a.toString()).apply();
    }

    public static String getProcessingOrder(Context c) { return TextParser.normalizeShortId(p(c).getString(PROCESSING_ORDER, "")); }
    public static boolean isProcessing(Context c) { return !getProcessingOrder(c).isEmpty(); }

    public static void beginProcessing(Context c, String rawId) {
        String id = TextParser.normalizeShortId(rawId);
        clearScreenBinding(c);
        long now = System.currentTimeMillis();
        p(c).edit().putString(PROCESSING_ORDER, id).putLong(PROCESSING_AT, now)
                .putString(STAGE, STAGE_OPENING_ORDER).putLong(STAGE_AT, now).apply();
    }

    /**
     * Compatibility helper only. v2.0.8 never creates processing ownership from a visible screen.
     * It may bind the screen only when the scheduler already owns a valid leased attempt.
     */
    public static void adoptVisibleOrder(Context c, String rawId) {
        String id = TextParser.normalizeShortId(rawId);
        if (!isProcessingLeaseValid(c, id)) return;
        setActiveDetailOrder(c, id);
        setStage(c, STAGE_DETAIL);
    }

    public static void finishProcessing(Context c, String rawId) {
        String id = TextParser.normalizeShortId(rawId);
        removeFromQueue(c, id);
        p(c).edit().remove(PROCESSING_ORDER).remove(PROCESSING_AT).apply();
        setStage(c, STAGE_IDLE);
        clearScreenBinding(c);
    }

    public static void cancelProcessingKeepQueued(Context c) {
        p(c).edit().remove(PROCESSING_ORDER).remove(PROCESSING_AT).apply();
        setStage(c, STAGE_IDLE);
        clearScreenBinding(c);
    }

    public static void deferProcessing(Context c, String rawId) {
        moveToQueueEnd(c, rawId);
        cancelProcessingKeepQueued(c);
    }

    public static long processingAge(Context c) {
        long at = p(c).getLong(PROCESSING_AT, 0L);
        return at <= 0L ? Long.MAX_VALUE : Math.max(0L, System.currentTimeMillis() - at);
    }

    public static boolean isProcessingLeaseValid(Context c, String rawId) {
        String id = TextParser.normalizeShortId(rawId);
        return !id.isEmpty() && id.equals(getProcessingOrder(c))
                && processingAge(c) <= AutomationPolicy.ATTEMPT_LEASE_MS;
    }

    public static void setActiveDetailOrder(Context c, String rawId) { p(c).edit().putString(ACTIVE_DETAIL_ORDER, TextParser.normalizeShortId(rawId)).apply(); }
    public static String getActiveDetailOrder(Context c) { return TextParser.normalizeShortId(p(c).getString(ACTIVE_DETAIL_ORDER, "")); }

    public static void setStage(Context c, String stage) { p(c).edit().putString(STAGE, stage).putLong(STAGE_AT, System.currentTimeMillis()).apply(); }
    public static String getStage(Context c) { return p(c).getString(STAGE, STAGE_IDLE); }
    public static long stageAge(Context c) { long at = p(c).getLong(STAGE_AT, 0L); return at == 0L ? Long.MAX_VALUE : System.currentTimeMillis() - at; }

    public static boolean markContactSheet(Context c, String rawId, int receiverY, int purchaserY) {
        String id = TextParser.normalizeShortId(rawId);
        if (id.isEmpty()) return false;
        p(c).edit().putString(CONTACT_ORDER, id).putLong(CONTACT_SHEET_AT, System.currentTimeMillis())
                .putInt(RECEIVER_Y, receiverY).putInt(PURCHASER_Y, purchaserY).apply();
        return true;
    }

    public static String getContactOrder(Context c) { return TextParser.normalizeShortId(p(c).getString(CONTACT_ORDER, "")); }
    public static boolean contactSheetWasRecent(Context c) {
        long at = p(c).getLong(CONTACT_SHEET_AT, 0L);
        return !getContactOrder(c).isEmpty() && at > 0 && System.currentTimeMillis() - at < AutomationPolicy.PHONE_CAPTURE_MS;
    }
    public static int getReceiverY(Context c) { return p(c).getInt(RECEIVER_Y, Integer.MIN_VALUE); }
    public static int getPurchaserY(Context c) { return p(c).getInt(PURCHASER_Y, Integer.MIN_VALUE); }

    public static boolean beginPhoneCapture(Context c, String rawId) {
        String id = TextParser.normalizeShortId(rawId);
        if (id.isEmpty() || !getContactOrder(c).equals(id)) return false;
        p(c).edit().putString(CAPTURE_ORDER, id).putLong(CAPTURE_AT, System.currentTimeMillis()).apply();
        setStage(c, STAGE_READING_PHONE);
        return true;
    }

    public static String getCaptureOrder(Context c) { return TextParser.normalizeShortId(p(c).getString(CAPTURE_ORDER, "")); }
    // Restore the proven v0.2.5 capture window. This is intentionally generous: success exits immediately.
    public static boolean isPhoneCaptureActive(Context c) {
        long at = p(c).getLong(CAPTURE_AT, 0L);
        return !getCaptureOrder(c).isEmpty() && at > 0 && System.currentTimeMillis() - at < AutomationPolicy.PHONE_CAPTURE_MS;
    }

    public static void clearPhoneCapture(Context c) { p(c).edit().remove(CAPTURE_ORDER).remove(CAPTURE_AT).apply(); }
    public static void clearContactBinding(Context c) {
        p(c).edit().remove(CONTACT_ORDER).remove(CONTACT_SHEET_AT).remove(RECEIVER_Y).remove(PURCHASER_Y).apply();
        clearPhoneCapture(c);
    }
    public static void clearScreenBinding(Context c) { p(c).edit().remove(ACTIVE_DETAIL_ORDER).apply(); clearContactBinding(c); }

    /**
     * First component in each Android process clears persisted in-flight UI ownership exactly once.
     * Handler callbacks from a dead process cannot survive, so keeping its processing_order would
     * create an orphan attempt. Queue is preserved and the scheduler will restart it safely.
     */
    public static synchronized boolean reconcileProcessStart(Context c) {
        if (PROCESS_STATE_RECONCILED) return false;
        PROCESS_STATE_RECONCILED = true;
        boolean hadInFlight = isProcessing(c) || !getStage(c).equals(STAGE_IDLE)
                || !getCaptureOrder(c).isEmpty() || !getContactOrder(c).isEmpty();
        resetTransient(c, false);
        return hadInFlight;
    }

    /** Clear volatile state after reboot/update without touching orders.json or Hub settings. */
    public static synchronized void resetTransient(Context c, boolean clearQueue) {
        SharedPreferences.Editor e = p(c).edit().remove(PROCESSING_ORDER).remove(PROCESSING_AT).remove(STAGE).remove(STAGE_AT)
                .remove(ACTIVE_DETAIL_ORDER).remove(CONTACT_ORDER).remove(CONTACT_SHEET_AT)
                .remove(RECEIVER_Y).remove(PURCHASER_Y).remove(CAPTURE_ORDER).remove(CAPTURE_AT)
                // remove keys used by v2.0.0/2.0.1 experimental automation
                .remove("capture_baseline").remove("user_active_until").remove("automation_action_until")
                .remove("exclusive_order").remove("exclusive_until");
        if (clearQueue) e.remove(QUEUE);
        e.apply();
    }


    /** Run once after installing v2.0.3 so no experimental v2.x queue/state can leak into stable core. */
    public static synchronized boolean ensureStableCoreMigration(Context c) {
        final String key="stable_core_v203_migrated";
        if (p(c).getBoolean(key,false)) return false;
        resetTransient(c,true);
        p(c).edit().putBoolean(key,true).apply();
        return true;
    }

    /**
     * v2.0.3 safety migration: clear only volatile automation state/queue once after update.
     * This immediately kills any stale v2.0.2 order that may have been retrying overnight.
     * orders.json and Hub configuration are intentionally untouched.
     */
    public static synchronized boolean ensureRetrySafetyMigration(Context c) {
        final String key="stable_core_v203_retry_safety_migrated";
        if (p(c).getBoolean(key,false)) return false;
        resetTransient(c,true);
        p(c).edit().putBoolean(key,true).apply();
        return true;
    }


    /**
     * v2.0.8 reliability migration: clear only volatile in-flight screen/processing state once after update.
     * Queue is intentionally preserved; the new scheduler safely prunes completed/expired entries.
     * orders.json, Hub URL/API key and technical logs are intentionally preserved.
     */
    public static synchronized boolean ensureV208ReliabilityMigration(Context c) {
        final String key="state_machine_v208_migrated";
        if (p(c).getBoolean(key,false)) return false;
        resetTransient(c,false);
        p(c).edit().putBoolean(key,true).apply();
        return true;
    }

    // Compatibility for the v2 dashboard only. Stable core intentionally does not use these concepts.
    public static boolean isExclusiveAutomation(Context c) { return false; }
    public static boolean isUserActive(Context c) { return false; }
    public static long userIdleRemaining(Context c) { return 0L; }
    public static void clearExclusiveAutomation(Context c) {}
}
