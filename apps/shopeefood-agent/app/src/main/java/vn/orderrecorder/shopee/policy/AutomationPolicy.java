package vn.orderrecorder.shopee;

/**
 * v2.0.8 reliability policy.
 *
 * The capture engine remains strictly one-order-at-a-time. This release intentionally avoids
 * adding workers/overlays/parallel navigation. It only makes the existing attempt lifecycle
 * bounded and gives ShopeeFood UI enough time to settle before declaring navigation failure.
 */
public final class AutomationPolicy {
    public static final long SOUND_GRACE_MS = 1000L;
    public static final long USER_IDLE_MS = 0L;
    public static final long WORKER_TICK_MS = 0L;

    // Retry/backoff is still short enough for rush hour, but a failed order must yield to fresh work.
    public static final long RETRY_DELAY_MS = 2800L;

    // Absolute safety limits retained from the production fuse.
    public static final long MAX_AUTO_AGE_MS = 8L * 60L * 1000L;
    public static final long STALE_PROCESSING_MS = 30_000L;
    public static final long PHONE_CAPTURE_MS = 15_000L;
    public static final int MAX_ATTEMPTS = 3;

    // v2.0.8: a processing session must have a lease. The 2s margin is only a watchdog backup;
    // the normal per-attempt timeout still fires at STALE_PROCESSING_MS.
    public static final long ATTEMPT_LEASE_MS = STALE_PROCESSING_MS + 2_000L;

    // v2.0.8: logs showed that ShopeeFood often rendered the expected screen about 0.5-1.0s
    // after the old code had already reported failure. These are settle windows, not busy polling.
    public static final long CONTENT_INTENT_SETTLE_MS = 1400L;
    public static final long FINAL_NAVIGATION_SETTLE_MS = 2000L;

    // Retry clicks are deliberately moved back toward the proven pre-burst cadence.
    public static final long CONTACT_RETRY_CLICK_MS = 850L;
    public static final long RECEIVER_RETRY_CLICK_MS = 900L;

    public static final long EXCLUSIVE_LOCK_MAX_MS = 0L;
    public static final long RAPID_SCAN_MS = 0L;
    public static final long RAPID_SCAN_TICK_MS = 0L;

    private AutomationPolicy() {}
}
