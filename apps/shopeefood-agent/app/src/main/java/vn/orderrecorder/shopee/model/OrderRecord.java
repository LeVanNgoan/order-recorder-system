package vn.orderrecorder.shopee;

import org.json.JSONException;
import org.json.JSONObject;

public final class OrderRecord {
    // Metadata kỹ thuật
    public String shortOrderId = "";
    public String displayOrderId = "";
    public String fullOrderId = "";
    public String receiverPhone = "";
    public boolean hubSynced = false;
    public long hubSyncAt = 0L;
    // Hub reconciliation telemetry flags. These only control background sync;
    // they never participate in the Accessibility capture state machine.
    public boolean hubSeenEventSynced = false;
    public boolean hubCapturedEventSynced = false;
    public boolean hubRiskEventSynced = false;
    public boolean hubMissEventSynced = false;
    public String captureMode = ""; // auto | manual | unknown
    // Persist terminal MISS telemetry so a later manual rescue cannot erase the fact that automation missed.
    public long riskEventAt = 0L;
    public String riskEventReason = "";
    public int riskEventAttempts = 0;
    public long missEventAt = 0L;
    public String missEventReason = "";
    public int missEventAttempts = 0;
    public String status = "waiting";
    public long receivedAt = 0L;
    public long updatedAt = 0L;

    // Telemetry nội bộ, không xuất ở dữ liệu nghiệp vụ chính thức
    public long eligibleAt = 0L;
    public long nextAttemptAt = 0L;
    public long processingStartedAt = 0L;
    public long detailConfirmedAt = 0L;
    public long contactOpenedAt = 0L;
    public long phoneRecordedAt = 0L;
    public int attemptCount = 0;
    public int pauseCount = 0;
    public long totalUserPauseMs = 0L;
    public String result = "";
    public String failureReason = "";
    public String lastStage = "";
    public String openMethod = "";
    public String sessionId = "";
    public boolean openedByUser = false;
    public boolean openedByAutomation = false;

    public String businessOrderCode() { return TextParser.businessOrderCode(displayOrderId); }
    public boolean isCompleted() { return receiverPhone != null && !receiverPhone.isEmpty(); }

    public JSONObject toJson() throws JSONException {
        JSONObject o = new JSONObject();
        o.put("shortOrderId", shortOrderId); o.put("displayOrderId", displayOrderId);
        o.put("fullOrderId", fullOrderId); o.put("receiverPhone", receiverPhone);
        o.put("hubSynced", hubSynced); o.put("hubSyncAt", hubSyncAt);
        o.put("hubSeenEventSynced", hubSeenEventSynced); o.put("hubCapturedEventSynced", hubCapturedEventSynced); o.put("hubRiskEventSynced", hubRiskEventSynced); o.put("hubMissEventSynced", hubMissEventSynced); o.put("captureMode", captureMode);
        o.put("riskEventAt", riskEventAt); o.put("riskEventReason", riskEventReason); o.put("riskEventAttempts", riskEventAttempts); o.put("missEventAt", missEventAt); o.put("missEventReason", missEventReason); o.put("missEventAttempts", missEventAttempts);
        o.put("status", status); o.put("receivedAt", receivedAt); o.put("updatedAt", updatedAt);
        o.put("eligibleAt", eligibleAt); o.put("nextAttemptAt", nextAttemptAt);
        o.put("processingStartedAt", processingStartedAt); o.put("detailConfirmedAt", detailConfirmedAt);
        o.put("contactOpenedAt", contactOpenedAt); o.put("phoneRecordedAt", phoneRecordedAt);
        o.put("attemptCount", attemptCount); o.put("pauseCount", pauseCount); o.put("totalUserPauseMs", totalUserPauseMs);
        o.put("result", result); o.put("failureReason", failureReason); o.put("lastStage", lastStage);
        o.put("openMethod", openMethod); o.put("sessionId", sessionId);
        o.put("openedByUser", openedByUser); o.put("openedByAutomation", openedByAutomation);
        return o;
    }

    public static OrderRecord fromJson(JSONObject o) {
        OrderRecord r = new OrderRecord();
        r.shortOrderId = TextParser.normalizeShortId(o.optString("shortOrderId", ""));
        r.displayOrderId = o.optString("displayOrderId", "");
        r.fullOrderId = o.optString("fullOrderId", "");
        r.receiverPhone = TextParser.normalizePhone(o.optString("receiverPhone", ""));
        r.hubSynced = o.optBoolean("hubSynced", false); r.hubSyncAt = o.optLong("hubSyncAt", 0L);
        r.hubSeenEventSynced = o.optBoolean("hubSeenEventSynced", false); r.hubCapturedEventSynced = o.optBoolean("hubCapturedEventSynced", false); r.hubRiskEventSynced = o.optBoolean("hubRiskEventSynced", false); r.hubMissEventSynced = o.optBoolean("hubMissEventSynced", false); r.captureMode = o.optString("captureMode", "");
        r.riskEventAt = o.optLong("riskEventAt", 0L); r.riskEventReason = o.optString("riskEventReason", ""); r.riskEventAttempts = o.optInt("riskEventAttempts", 0); r.missEventAt = o.optLong("missEventAt", 0L); r.missEventReason = o.optString("missEventReason", ""); r.missEventAttempts = o.optInt("missEventAttempts", 0);
        r.status = o.optString("status", "waiting"); r.receivedAt = o.optLong("receivedAt", 0L); r.updatedAt = o.optLong("updatedAt", r.receivedAt);
        r.eligibleAt = o.optLong("eligibleAt", r.receivedAt > 0 ? r.receivedAt + AutomationPolicy.SOUND_GRACE_MS : 0L);
        r.nextAttemptAt = o.optLong("nextAttemptAt", 0L); r.processingStartedAt = o.optLong("processingStartedAt", 0L);
        r.detailConfirmedAt = o.optLong("detailConfirmedAt", 0L); r.contactOpenedAt = o.optLong("contactOpenedAt", 0L);
        r.phoneRecordedAt = o.optLong("phoneRecordedAt", r.isCompleted() ? r.updatedAt : 0L);
        r.attemptCount = o.optInt("attemptCount", 0); r.pauseCount = o.optInt("pauseCount", 0); r.totalUserPauseMs = o.optLong("totalUserPauseMs", 0L);
        r.result = o.optString("result", r.isCompleted() ? "completed" : ""); r.failureReason = o.optString("failureReason", "");
        r.lastStage = o.optString("lastStage", ""); r.openMethod = o.optString("openMethod", ""); r.sessionId = o.optString("sessionId", "");
        r.openedByUser = o.optBoolean("openedByUser", false); r.openedByAutomation = o.optBoolean("openedByAutomation", false);
        return r;
    }
}
