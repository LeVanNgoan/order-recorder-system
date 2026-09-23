package vn.orderrecorder.shopee;

/**
 * v2.0.10 observation-only policy.
 *
 * These thresholds never change the proven v2.0.8 capture timeout, queue, retry count,
 * contentIntent settle windows or click cadence. They only decide when to inspect extra
 * Accessibility windows and when to emit one early RISK telemetry event.
 */
public final class RiskPolicy {
    public static final long EARLY_RISK_AFTER_RECEIVER_MS = 6500L;
    public static final long NAVIGATION_RISK_MIN_AGE_MS = 30000L;
    public static final long WINDOW_EVENT_SCAN_THROTTLE_MS = 400L;

    // Sparse read-only probes after Khách nhận đơn. No clicks and no worker loop.
    public static final long[] WINDOW_PROBE_DELAYS_MS = new long[]{260L, 900L, 2200L, 5000L};

    private RiskPolicy() {}
}
