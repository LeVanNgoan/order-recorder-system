/* global XlsxBuilder */
importScripts("src/service/xlsx-builder.js");

const APP_VERSION = "2.0.1";
const PLATFORM_GRAB = "GrabFood";
const GRAB_HOST_PATTERN = "https://merchant.grab.com/*";
const GRAB_DEFAULT_MERCHANT_ID = "5-C623VXMGVBATJN";
const GRAB_MONITOR_FALLBACK_URL = buildGrabPreparingUrl(GRAB_DEFAULT_MERCHANT_ID);

const KEYS = Object.freeze({
  RECORDING: "recordingEnabled",
  LAST_ORDER: "lastSavedOrder",
  LAST_DETECTED: "lastDetectedOrder",
  LAST_EXPORT: "lastExportStatus",
  DIRTY_DATES: "excelDirtyDates",
  DOWNLOADS: "orderRecorderDownloads",
  PENDING: "orderRecorderPendingOrders",
  AUTOMATION: "orderRecorderAutomationStatus",
  GRAB_MONITOR_TAB: "grabMonitorTabId",
  GRAB_MONITOR_URL: "grabMonitorPreparingUrl"
});

const LEGACY_KEYS = Object.freeze({
  PENDING: "grabPendingOrders",
  AUTOMATION: "grabAutomationStatus"
});

const ORDERS_PREFIX = "orders_";
const CLEANUP_ALARM = "order-recorder-cleanup";
const WATCHDOG_ALARM = "order-recorder-watchdog";
const RETENTION_DAYS = 7;

// Order Recorder Hub (Windows)
const HUB_URL = "http://127.0.0.1:17891";
const HUB_SYNC_ALARM = "order-recorder-hub-sync";
const HUB_HEARTBEAT_ALARM = "order-recorder-hub-heartbeat";
const HUB_QUEUE_KEY = "orderRecorderHubQueue";
const HUB_SENT_KEY = "orderRecorderHubSent";
const HUB_STATUS_KEY = "orderRecorderHubStatus";
const HUB_BACKFILL_VERSION_KEY = "orderRecorderHubBackfillVersion";
// Reconciliation telemetry is intentionally isolated from the order-sync queue.
// A Hub outage must never block or slow the Grab capture path.
const HUB_EVENT_QUEUE_KEY = "orderRecorderHubEventQueue";
const HUB_EVENT_SENT_KEY = "orderRecorderHubEventSent";
const HUB_MAX_SENT_KEYS = 3000;
const HUB_RETENTION_MS = RETENTION_DAYS * 24 * 60 * 60 * 1000;

let operationQueue = Promise.resolve();
let monitorRecoveryPromise = null;
let monitorRecreateTimer = null;
let pendingExportFilename = "";
const tabRecoveryLocks = new Map();

chrome.runtime.onInstalled.addListener((details) => {
  initializeExtension({ forceReloadMonitor: details?.reason === "update" }).catch(console.error);
});

chrome.runtime.onStartup.addListener(() => {
  initializeExtension().catch(console.error);
});

chrome.alarms.onAlarm.addListener((alarm) => {
  if (alarm.name === CLEANUP_ALARM) {
    runQueued(cleanupExpiredData).catch(console.error);
    return;
  }

  if (alarm.name === WATCHDOG_ALARM) {
    chrome.storage.local.get(KEYS.RECORDING).then((state) => {
      if (state[KEYS.RECORDING]) ensureGrabMonitorTab().catch(console.warn);
    }).catch(console.warn);
    return;
  }

  if (alarm.name === HUB_SYNC_ALARM) {
    cleanupHubLocalState()
      .then(async () => {
        await syncHubQueue();
        await syncHubEventQueue();
      })
      .catch(console.warn);
    return;
  }

  if (alarm.name === HUB_HEARTBEAT_ALARM) {
    sendHubHeartbeat().catch(() => undefined);
  }
});

chrome.storage.onChanged.addListener((changes, areaName) => {
  if (areaName === "local" && changes[KEYS.RECORDING]) {
    updateBadge().catch(console.error);
  }
});

// Ép Chrome dùng đúng tên file do extension tạo. Một số cấu hình máy hoặc
// extension khác có thể can thiệp vào bước xác định tên tải xuống.
chrome.downloads.onDeterminingFilename.addListener((item, suggest) => {
  const isOwnExport = item.byExtensionId === chrome.runtime.id
    || String(item.url || "").startsWith("data:application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");

  if (!isOwnExport || !pendingExportFilename) return;

  const filename = pendingExportFilename;
  pendingExportFilename = "";
  suggest({ filename, conflictAction: "overwrite" });
});

chrome.tabs.onUpdated.addListener((tabId, changeInfo, tab) => {
  handleMonitorTabUpdated(tabId, changeInfo, tab).catch(() => undefined);
});

chrome.tabs.onReplaced.addListener((addedTabId, removedTabId) => {
  chrome.storage.local.get([KEYS.RECORDING, KEYS.GRAB_MONITOR_TAB]).then(async (state) => {
    if (state[KEYS.GRAB_MONITOR_TAB] !== removedTabId) return;
    await chrome.storage.local.set({ [KEYS.GRAB_MONITOR_TAB]: addedTabId });
    if (state[KEYS.RECORDING]) await ensureGrabMonitorTab();
  }).catch(console.warn);
});

chrome.tabs.onRemoved.addListener((tabId) => {
  chrome.storage.local.get([KEYS.RECORDING, KEYS.GRAB_MONITOR_TAB]).then(async (state) => {
    if (state[KEYS.GRAB_MONITOR_TAB] !== tabId) return;
    await chrome.storage.local.remove(KEYS.GRAB_MONITOR_TAB);
    await saveAutomationStatus({
      platform: PLATFORM_GRAB,
      state: state[KEYS.RECORDING] ? "recovering" : "off",
      message: state[KEYS.RECORDING]
        ? "GrabFood · Tab giám sát đã đóng, đang mở lại"
        : "Đã tắt ghi nhận",
      updatedAt: isoNow()
    });
    if (state[KEYS.RECORDING]) scheduleMonitorRecreate();
  }).catch(console.warn);
});

chrome.runtime.onMessage.addListener((message, sender, sendResponse) => {
  const action = message?.type;

  if (action === "GET_DASHBOARD") {
    getDashboard()
      .then((data) => sendResponse({ ok: true, data }))
      .catch((error) => sendResponse({ ok: false, error: error.message }));
    return true;
  }

  if (action === "SET_RECORDING") {
    runQueued(() => setRecording(Boolean(message.enabled)))
      .then((data) => sendResponse({ ok: true, data }))
      .catch((error) => sendResponse({ ok: false, error: error.message }));
    return true;
  }

  if (action === "GET_TAB_ROLE") {
    getTabRole(sender.tab?.id)
      .then((data) => sendResponse({ ok: true, data }))
      .catch((error) => sendResponse({ ok: false, error: error.message }));
    return true;
  }

  if (action === "ENSURE_MONITOR_TABS" || action === "INJECT_GRAB_TABS") {
    ensureGrabMonitorTab()
      .then((data) => sendResponse({ ok: true, data }))
      .catch((error) => sendResponse({ ok: false, error: error.message }));
    return true;
  }

  if (action === "AUTOMATION_STATUS") {
    acceptMonitorMessage(sender.tab?.id, message.status?.platform || PLATFORM_GRAB)
      .then((accepted) => accepted
        ? saveAutomationStatus(message.status)
        : { ignored: true })
      .then((data) => sendResponse({ ok: true, data }))
      .catch((error) => sendResponse({ ok: false, error: error.message }));
    return true;
  }

  if (action === "ORDER_DETECTED") {
    runQueued(async () => {
      const accepted = await acceptMonitorMessage(sender.tab?.id, message.order?.platform);
      if (!accepted) return { ignored: true };
      const detected = await rememberDetectedOrder(message.order);
      if (detected.newlyDetected) {
        await enqueueHubEvent(hubSeenEventFromDetection(message.order, detected));
        syncHubEventQueue().catch(console.warn);
      }
      return detected;
    })
      .then((data) => sendResponse({ ok: true, data }))
      .catch((error) => sendResponse({ ok: false, error: error.message }));
    return true;
  }

  if (action === "SAVE_ORDER") {
    runQueued(async () => {
      const accepted = await acceptMonitorMessage(sender.tab?.id, message.order?.platform);
      if (!accepted) return { saved: false, reason: "not_monitor_tab" };
      return saveOrder(message.order);
    })
      .then((data) => sendResponse({ ok: true, data }))
      .catch((error) => sendResponse({ ok: false, error: error.message }));
    return true;
  }

  if (action === "EXPORT_TODAY") {
    runQueued(() => exportOrdersForDate(localDateKey()))
      .then((data) => sendResponse({ ok: true, data }))
      .catch((error) => sendResponse({ ok: false, error: error.message }));
    return true;
  }

  if (action === "EXPORT_DATE") {
    runQueued(() => exportOrdersForDate(validateExportDateKey(message.dateKey)))
      .then((data) => sendResponse({ ok: true, data }))
      .catch((error) => sendResponse({ ok: false, error: error.message }));
    return true;
  }

  if (action === "GET_ORDERS_TODAY") {
    getOrdersForDate(localDateKey())
      .then((orders) => sendResponse({ ok: true, data: orders }))
      .catch((error) => sendResponse({ ok: false, error: error.message }));
    return true;
  }

  if (action === "HUB_SYNC_NOW") {
    syncHubQueue()
      .then(() => syncHubEventQueue())
      .then(() => getHubStatus())
      .then((data) => sendResponse({ ok: true, data }))
      .catch((error) => sendResponse({ ok: false, error: error.message }));
    return true;
  }

  if (action === "HUB_FORCE_RESYNC_7D") {
    forceResyncLocalGrabOrdersToHub()
      .then((data) => sendResponse({ ok: true, data }))
      .catch((error) => sendResponse({ ok: false, error: error.message }));
    return true;
  }

  if (action === "OPEN_HUB") {
    chrome.tabs.create({ url: `${HUB_URL}/` })
      .then(() => sendResponse({ ok: true }))
      .catch((error) => sendResponse({ ok: false, error: error.message }));
    return true;
  }

  return false;
});

async function initializeExtension(options = {}) {
  const current = await chrome.storage.local.get(KEYS.RECORDING);
  if (typeof current[KEYS.RECORDING] !== "boolean") {
    await chrome.storage.local.set({ [KEYS.RECORDING]: false });
  }

  await migrateLegacyStorage();
  await ensureMaintenanceAlarms();
  await ensureHubSyncAlarm();
  await ensureHubHeartbeatAlarm();
  await cleanupExpiredData();
  await cleanupHubLocalState();
  await backfillLocalGrabOrdersToHubOnce();
  syncHubQueue().catch(console.warn);
  syncHubEventQueue().catch(console.warn);
  sendHubHeartbeat().catch(() => undefined);
  await updateBadge();

  const state = await chrome.storage.local.get(KEYS.RECORDING);
  if (state[KEYS.RECORDING]) {
    if (options.forceReloadMonitor) {
      await reloadStoredMonitorAfterUpdate();
    }
    await ensureGrabMonitorTab();
    scheduleRecoveryBursts();
  }
}

async function reloadStoredMonitorAfterUpdate() {
  const state = await chrome.storage.local.get(KEYS.GRAB_MONITOR_TAB);
  const tab = await safeGetTab(state[KEYS.GRAB_MONITOR_TAB]);
  if (!tab || !Number.isInteger(tab.id) || !isGrabMerchantUrl(tab.url)) return;

  try {
    await reloadGrabTab(tab.id, "extension_updated");
  } catch (error) {
    console.warn("[Order Recorder] Không reload được tab monitor sau cập nhật:", error);
  }
}

async function migrateLegacyStorage() {
  const state = await chrome.storage.local.get([
    KEYS.PENDING,
    KEYS.AUTOMATION,
    LEGACY_KEYS.PENDING,
    LEGACY_KEYS.AUTOMATION
  ]);

  const updates = {};
  if (!state[KEYS.PENDING] && state[LEGACY_KEYS.PENDING]) {
    updates[KEYS.PENDING] = state[LEGACY_KEYS.PENDING];
  }
  if (!state[KEYS.AUTOMATION] && state[LEGACY_KEYS.AUTOMATION]) {
    updates[KEYS.AUTOMATION] = state[LEGACY_KEYS.AUTOMATION];
  }
  if (Object.keys(updates).length) await chrome.storage.local.set(updates);
}

function runQueued(task) {
  const next = operationQueue.then(task, task);
  operationQueue = next.catch(() => undefined);
  return next;
}

function isGrabMerchantUrl(url) {
  return typeof url === "string" && url.startsWith("https://merchant.grab.com/");
}

function isPublicGrabContentUrl(url) {
  if (!isGrabMerchantUrl(url)) return false;
  try {
    const path = new URL(url).pathname.toLowerCase();
    return /^\/(vn-vn|en-vn)(\/|$)/.test(path)
      || path.includes("/blog/")
      || path.includes("/guides/");
  } catch {
    return false;
  }
}


function extractGrabMerchantId(url) {
  if (!isGrabMerchantUrl(url)) return "";
  try {
    const path = new URL(url).pathname;
    const match = path.match(/^\/order\/([^/]+)(?:\/|$)/i);
    return normalizeText(match?.[1]);
  } catch {
    return "";
  }
}

function buildGrabPreparingUrl(merchantId) {
  const normalizedId = normalizeText(merchantId);
  return normalizedId
    ? `https://merchant.grab.com/order/${encodeURIComponent(normalizedId)}/preparing`
    : "https://merchant.grab.com/";
}

function normalizeGrabPreparingUrl(url) {
  const merchantId = extractGrabMerchantId(url);
  return merchantId ? buildGrabPreparingUrl(merchantId) : "";
}

async function rememberGrabPreparingUrl(url) {
  const preparingUrl = normalizeGrabPreparingUrl(url);
  if (!preparingUrl) return "";
  await chrome.storage.local.set({ [KEYS.GRAB_MONITOR_URL]: preparingUrl });
  return preparingUrl;
}

async function getTabRole(tabId) {
  const state = await chrome.storage.local.get(KEYS.GRAB_MONITOR_TAB);
  return {
    monitorMode: Number.isInteger(tabId) && state[KEYS.GRAB_MONITOR_TAB] === tabId,
    platform: state[KEYS.GRAB_MONITOR_TAB] === tabId ? PLATFORM_GRAB : ""
  };
}

async function acceptMonitorMessage(tabId, platform) {
  if (platform !== PLATFORM_GRAB || !Number.isInteger(tabId)) return false;
  const state = await chrome.storage.local.get(KEYS.GRAB_MONITOR_TAB);
  return state[KEYS.GRAB_MONITOR_TAB] === tabId;
}

async function handleMonitorTabUpdated(tabId, changeInfo, tab) {
  const state = await chrome.storage.local.get([KEYS.RECORDING, KEYS.GRAB_MONITOR_TAB]);
  if (!state[KEYS.RECORDING] || state[KEYS.GRAB_MONITOR_TAB] !== tabId) return;

  const url = changeInfo.url || tab.url || "";
  if (url && !isGrabMerchantUrl(url)) {
    await chrome.tabs.update(tabId, {
      url: await chooseGrabMonitorStartUrl(tabId),
      active: false,
      pinned: true
    });
    return;
  }

  if (isGrabMerchantUrl(url)) {
    await rememberGrabPreparingUrl(url);
  }

  const relevantChange = changeInfo.status === "complete"
    || changeInfo.url
    || changeInfo.discarded === false
    || changeInfo.frozen === false;
  if (relevantChange && isGrabMerchantUrl(url)) {
    await ensureGrabTabConnected(tabId, true);
  }
}

function scheduleMonitorRecreate() {
  if (monitorRecreateTimer) clearTimeout(monitorRecreateTimer);
  monitorRecreateTimer = setTimeout(() => {
    monitorRecreateTimer = null;
    chrome.storage.local.get(KEYS.RECORDING).then((state) => {
      if (state[KEYS.RECORDING]) ensureGrabMonitorTab().catch(console.warn);
    }).catch(console.warn);
  }, 700);
}

async function ensureGrabMonitorTab() {
  if (monitorRecoveryPromise) return monitorRecoveryPromise;
  monitorRecoveryPromise = ensureGrabMonitorTabInternal().finally(() => {
    monitorRecoveryPromise = null;
  });
  return monitorRecoveryPromise;
}

async function ensureGrabMonitorTabInternal() {
  const state = await chrome.storage.local.get([KEYS.RECORDING, KEYS.GRAB_MONITOR_TAB]);
  if (!state[KEYS.RECORDING]) return { enabled: false, state: "off" };

  let monitorTab = await safeGetTab(state[KEYS.GRAB_MONITOR_TAB]);

  // ID tab không ổn định qua các phiên Chrome. Không được biến một tab không liên quan
  // thành tab monitor chỉ vì ID cũ vô tình được tái sử dụng.
  if (monitorTab && !isGrabMerchantUrl(monitorTab.url)) {
    monitorTab = null;
    await chrome.storage.local.remove(KEYS.GRAB_MONITOR_TAB);
  }

  if (!monitorTab) {
    monitorTab = await findExistingMonitorTab();
  }

  if (!monitorTab) {
    const startUrl = await chooseGrabMonitorStartUrl();
    monitorTab = await chrome.tabs.create({
      url: startUrl,
      active: false,
      pinned: true
    });
  }

  if (!Number.isInteger(monitorTab.id)) {
    throw new Error("Không tạo được tab giám sát GrabFood.");
  }

  await chrome.storage.local.set({ [KEYS.GRAB_MONITOR_TAB]: monitorTab.id });

  if (!isGrabMerchantUrl(monitorTab.url)) {
    monitorTab = await chrome.tabs.update(monitorTab.id, {
      url: await chooseGrabMonitorStartUrl(monitorTab.id),
      active: false,
      pinned: true
    });
  } else {
    try {
      monitorTab = await chrome.tabs.update(monitorTab.id, {
        active: false,
        pinned: true,
        autoDiscardable: false
      });
    } catch {
      monitorTab = await chrome.tabs.update(monitorTab.id, { active: false, pinned: true });
    }
  }

  if (monitorTab.discarded || monitorTab.frozen) {
    await reloadGrabTab(
      monitorTab.id,
      monitorTab.discarded ? "monitor_tab_discarded" : "monitor_tab_frozen"
    );
  } else if (monitorTab.status !== "complete") {
    await waitForTabComplete(monitorTab.id, 25000);
  }

  const connection = await ensureGrabTabConnected(monitorTab.id, true);
  return {
    enabled: true,
    platform: PLATFORM_GRAB,
    tabId: monitorTab.id,
    ...connection
  };
}

async function findExistingMonitorTab() {
  const tabs = await chrome.tabs.query({ url: GRAB_HOST_PATTERN });
  for (const tab of tabs) {
    if (!Number.isInteger(tab.id)) continue;
    const response = await pingRecorder(tab.id);
    if (response?.connected && response.monitorMode) return tab;
  }
  return null;
}

async function chooseGrabMonitorStartUrl(excludedTabId) {
  const tabs = await chrome.tabs.query({ url: GRAB_HOST_PATTERN });

  // Ưu tiên lấy merchantId từ bất kỳ tab đơn hàng Grab đang đăng nhập.
  for (const tab of tabs) {
    if (!Number.isInteger(tab.id) || tab.id === excludedTabId) continue;
    const preparingUrl = normalizeGrabPreparingUrl(tab.url);
    if (preparingUrl) {
      await chrome.storage.local.set({ [KEYS.GRAB_MONITOR_URL]: preparingUrl });
      return preparingUrl;
    }
  }

  // Dùng URL đã ghi nhớ từ lần hoạt động trước nếu phiên Chrome vừa khởi động.
  const stored = await chrome.storage.local.get(KEYS.GRAB_MONITOR_URL);
  const rememberedUrl = normalizeGrabPreparingUrl(stored[KEYS.GRAB_MONITOR_URL]);
  if (rememberedUrl) return rememberedUrl;

  // Fallback theo cửa hàng hiện tại do người dùng cung cấp. Khi phát hiện một
  // merchantId khác, extension sẽ tự thay thế bằng URL đúng của tài khoản đó.
  return GRAB_MONITOR_FALLBACK_URL;
}

async function ensureGrabTabConnected(tabId, monitorMode) {
  const existing = tabRecoveryLocks.get(tabId);
  if (existing) return existing;

  const task = ensureGrabTabConnectedInternal(tabId, monitorMode).finally(() => {
    if (tabRecoveryLocks.get(tabId) === task) tabRecoveryLocks.delete(tabId);
  });
  tabRecoveryLocks.set(tabId, task);
  return task;
}

async function ensureGrabTabConnectedInternal(tabId, monitorMode) {
  let tab = await chrome.tabs.get(tabId);
  if (!isGrabMerchantUrl(tab.url)) throw new Error("Tab giám sát không còn ở Grab Merchant.");

  await rememberGrabPreparingUrl(tab.url);

  try {
    await chrome.tabs.update(tabId, {
      pinned: Boolean(monitorMode),
      active: false,
      autoDiscardable: false
    });
  } catch (error) {
    console.warn("[Order Recorder] Không đặt được autoDiscardable=false:", error);
  }

  if (tab.discarded || tab.frozen) {
    await reloadGrabTab(tabId, tab.discarded ? "tab_discarded" : "tab_frozen");
    tab = await chrome.tabs.get(tabId);
  }

  let response = await pingRecorder(tabId);
  if (response?.connected && response.version !== APP_VERSION) {
    await reloadGrabTab(tabId, "script_version_mismatch");
    response = await pingRecorder(tabId);
  }

  if (!response?.connected || response.version !== APP_VERSION) {
    await chrome.scripting.executeScript({
      target: { tabId },
      files: ["src/controller/grab-monitor.js"],
      injectImmediately: true
    });
    await sleepMs(250);
    response = await pingRecorder(tabId);
  }

  if (!response?.connected || response.version !== APP_VERSION) {
    throw new Error("Tab GrabFood chưa kết nối được bộ giám sát.");
  }

  await chrome.tabs.sendMessage(tabId, {
    type: "SET_MONITOR_MODE",
    enabled: Boolean(monitorMode)
  }).catch(() => undefined);

  const preferredUrl = await chooseGrabMonitorStartUrl(tabId);
  await chrome.tabs.sendMessage(tabId, {
    type: "FORCE_MONITOR_VIEW",
    preparingUrl: preferredUrl
  }).catch(() => undefined);
  await sleepMs(180);
  const verified = await pingRecorder(tabId);
  if (verified?.url) await rememberGrabPreparingUrl(verified.url);

  return {
    connected: Boolean(verified?.connected),
    monitorMode: Boolean(verified?.monitorMode),
    version: verified?.version || "",
    viewState: verified?.viewState || "starting",
    viewMessage: verified?.viewMessage || "GrabFood · Đang khởi động tab giám sát",
    rows: Number(verified?.rows || 0),
    url: verified?.url || tab.url || ""
  };
}

async function pingRecorder(tabId) {
  if (!Number.isInteger(tabId)) return null;
  try {
    return await chrome.tabs.sendMessage(tabId, { type: "PING_RECORDER" });
  } catch {
    return null;
  }
}

async function reloadGrabTab(tabId, reason) {
  await saveAutomationStatus({
    platform: PLATFORM_GRAB,
    state: "recovering",
    message: "GrabFood · Đang tự khôi phục tab giám sát",
    updatedAt: isoNow()
  });
  await chrome.tabs.reload(tabId);
  await waitForTabComplete(tabId, 25000);
  await sleepMs(250);
  return { reloaded: true, reason };
}

async function waitForTabComplete(tabId, timeoutMs) {
  const startedAt = Date.now();
  while (Date.now() - startedAt < timeoutMs) {
    const tab = await chrome.tabs.get(tabId);
    if (tab.status === "complete" && !tab.discarded && !tab.frozen) return tab;
    await sleepMs(220);
  }
  throw new Error("Tab giám sát GrabFood tải quá lâu.");
}

async function safeGetTab(tabId) {
  if (!Number.isInteger(tabId)) return null;
  try {
    return await chrome.tabs.get(tabId);
  } catch {
    return null;
  }
}

function scheduleRecoveryBursts() {
  for (const delay of [1200, 4000, 10000]) {
    setTimeout(() => {
      chrome.storage.local.get(KEYS.RECORDING).then((state) => {
        if (state[KEYS.RECORDING]) ensureGrabMonitorTab().catch(console.warn);
      }).catch(console.warn);
    }, delay);
  }
}

function sleepMs(ms) {
  return new Promise((resolve) => setTimeout(resolve, ms));
}

async function ensureMaintenanceAlarms() {
  const cleanup = await chrome.alarms.get(CLEANUP_ALARM);
  if (!cleanup) {
    await chrome.alarms.create(CLEANUP_ALARM, {
      delayInMinutes: 1,
      periodInMinutes: 60
    });
  }

  const watchdog = await chrome.alarms.get(WATCHDOG_ALARM);
  if (!watchdog) {
    await chrome.alarms.create(WATCHDOG_ALARM, {
      delayInMinutes: 1,
      periodInMinutes: 1
    });
  }
}

async function setRecording(enabled) {
  await chrome.storage.local.set({ [KEYS.RECORDING]: enabled });

  if (enabled) {
    await ensureMaintenanceAlarms();
    await cleanupExpiredData();
    await saveAutomationStatus({
      platform: PLATFORM_GRAB,
      state: "starting",
      message: "GrabFood · Đang mở tab giám sát",
      updatedAt: isoNow()
    });
    await ensureGrabMonitorTab();
    scheduleRecoveryBursts();
  } else {
    await closeGrabMonitorTab();
    await saveAutomationStatus({
      platform: PLATFORM_GRAB,
      state: "off",
      message: "Đã tắt ghi nhận",
      updatedAt: isoNow()
    });
  }

  await updateBadge();
  return getDashboard();
}

async function closeGrabMonitorTab() {
  const state = await chrome.storage.local.get(KEYS.GRAB_MONITOR_TAB);
  const tabId = state[KEYS.GRAB_MONITOR_TAB];
  await chrome.storage.local.remove(KEYS.GRAB_MONITOR_TAB);
  if (!Number.isInteger(tabId)) return;
  try {
    await chrome.tabs.remove(tabId);
  } catch {
    // Tab đã được đóng trước đó.
  }
}

async function rememberDetectedOrder(order) {
  const platform = normalizePlatform(order?.platform);
  const orderId = normalizeText(order?.orderId);
  if (!platform || !orderId) throw new Error("Dữ liệu đơn phát hiện không hợp lệ.");

  const now = isoNow();
  const todayStorageKey = `${ORDERS_PREFIX}${localDateKey()}`;
  const result = await chrome.storage.local.get([KEYS.PENDING, KEYS.LAST_DETECTED, todayStorageKey]);
  const pending = result[KEYS.PENDING] || {};
  const todayOrders = Array.isArray(result[todayStorageKey]) ? result[todayStorageKey] : [];
  const uniqueKey = uniqueOrderKey(platform, orderId);
  const alreadySaved = todayOrders.some((item) => item.uniqueKey === uniqueKey);
  const alreadyPending = Boolean(pending[uniqueKey]);
  const newlyDetected = !alreadySaved && !alreadyPending;

  if (newlyDetected) {
    pending[uniqueKey] = {
      platform,
      orderId,
      detectedAt: isValidDate(order.detectedAt) ? new Date(order.detectedAt).toISOString() : now
    };
  }

  const lastDetected = {
    platform,
    orderId,
    detectedAt: pending[uniqueKey]?.detectedAt || now
  };

  await chrome.storage.local.set({
    [KEYS.PENDING]: pending,
    [KEYS.LAST_DETECTED]: lastDetected
  });

  return { ...lastDetected, alreadySaved, newlyDetected };
}

async function saveAutomationStatus(status) {
  const normalized = {
    platform: normalizePlatform(status?.platform) || PLATFORM_GRAB,
    state: normalizeText(status?.state) || "idle",
    orderId: normalizeText(status?.orderId),
    message: normalizeText(status?.message),
    queueLength: Number.isFinite(Number(status?.queueLength))
      ? Math.max(0, Number(status.queueLength))
      : 0,
    updatedAt: isValidDate(status?.updatedAt)
      ? new Date(status.updatedAt).toISOString()
      : isoNow()
  };
  await chrome.storage.local.set({ [KEYS.AUTOMATION]: normalized });
  return normalized;
}

async function saveOrder(rawOrder) {
  const state = await chrome.storage.local.get([KEYS.RECORDING, KEYS.PENDING]);
  if (!state[KEYS.RECORDING]) return { saved: false, reason: "recording_off" };

  const order = normalizeOrder(rawOrder);
  validateOrder(order);

  const dateKey = localDateKey(new Date(order.receivedAt || order.recordedAt));
  const storageKey = `${ORDERS_PREFIX}${dateKey}`;
  const stored = await chrome.storage.local.get(storageKey);
  const orders = Array.isArray(stored[storageKey]) ? stored[storageKey] : [];
  const uniqueKey = uniqueOrderKey(order.platform, order.orderId);

  if (orders.some((item) => item.uniqueKey === uniqueKey)) {
    return { saved: false, duplicate: true, orderId: order.orderId };
  }

  const pending = state[KEYS.PENDING] || {};
  const pendingEntry = pending[uniqueKey];
  if (!order.receivedAt && pendingEntry?.detectedAt) order.receivedAt = pendingEntry.detectedAt;
  if (!order.receivedAt) order.receivedAt = order.recordedAt;

  order.uniqueKey = uniqueKey;
  orders.push(order);
  orders.sort((a, b) => new Date(a.receivedAt || a.recordedAt) - new Date(b.receivedAt || b.recordedAt));

  delete pending[uniqueKey];
  await chrome.storage.local.set({
    [storageKey]: orders,
    [KEYS.PENDING]: pending,
    [KEYS.LAST_ORDER]: order
  });

  const dirtyState = await chrome.storage.local.get(KEYS.DIRTY_DATES);
  const dirtyDates = dirtyState[KEYS.DIRTY_DATES] || {};
  dirtyDates[dateKey] = { count: orders.length, updatedAt: isoNow() };
  await chrome.storage.local.set({ [KEYS.DIRTY_DATES]: dirtyDates });

  await updateBadge(orders.length, order.platform);
  await notifyOrderSaved(order);

  // Local-first: lưu extension trước, sau đó xếp hàng gửi Hub.
  // Hub offline không làm ảnh hưởng việc ghi nhận đơn Grab.
  if (order.platform === PLATFORM_GRAB) {
    await enqueueHubOrder(order);
    await enqueueHubEvent(hubCapturedEventFromOrder(order));
    syncHubQueue().catch(console.warn);
    syncHubEventQueue().catch(console.warn);
  }

  return {
    saved: true,
    duplicate: false,
    platform: order.platform,
    orderId: order.orderId,
    count: orders.length,
    exportPending: true
  };
}

async function notifyOrderSaved(order) {
  const orderId = normalizeText(order?.orderId);
  if (!orderId) return;

  const platform = normalizePlatform(order?.platform) || "Đơn hàng";
  const iconUrl = platform === PLATFORM_GRAB
    ? "assets/icons/platforms/grab128.png"
    : "assets/icons/icon128.png";

  try {
    await chrome.notifications.create(`order-recorder-${platform}-${Date.now()}`, {
      type: "basic",
      iconUrl,
      title: "Ghi nhận đơn hàng",
      message: `Đã ghi nhận đơn ${orderId}`,
      priority: 1
    });
  } catch (error) {
    console.warn("[Order Recorder] Không thể hiện thông báo:", error);
  }
}

function normalizeOrder(rawOrder) {
  const recordedAt = isValidDate(rawOrder?.recordedAt)
    ? new Date(rawOrder.recordedAt).toISOString()
    : isoNow();

  return {
    platform: normalizePlatform(rawOrder?.platform),
    orderId: normalizeText(rawOrder?.orderId),
    fullOrderId: normalizeText(rawOrder?.fullOrderId),
    receivedAt: isValidDate(rawOrder?.receivedAt)
      ? new Date(rawOrder.receivedAt).toISOString()
      : "",
    customerName: normalizeText(rawOrder?.customerName),
    phone: normalizeVietnamPhone(rawOrder?.phone),
    recordedAt
  };
}

function normalizePlatform(value) {
  const normalized = normalizeText(value).toLowerCase();
  if (normalized === "grabfood" || normalized === "grab") return PLATFORM_GRAB;
  if (normalized === "shopeefood" || normalized === "spf") return "ShopeeFood";
  return "";
}

function validateOrder(order) {
  if (!order.platform) throw new Error("Không xác định được nền tảng đơn hàng.");
  if (!order.orderId) throw new Error("Không tìm thấy mã đơn hàng.");
  if (!order.phone) throw new Error("Không tìm thấy số điện thoại.");
}

async function getDashboard() {
  const dateKey = localDateKey();
  const storageKey = `${ORDERS_PREFIX}${dateKey}`;
  const result = await chrome.storage.local.get(null);
  const orders = Array.isArray(result[storageKey]) ? result[storageKey] : [];
  const dirtyDates = result[KEYS.DIRTY_DATES] || {};
  const downloads = result[KEYS.DOWNLOADS] || {};
  const dirtyEntry = dirtyDates[dateKey] || null;
  const recordingEnabled = Boolean(result[KEYS.RECORDING]);
  const grabStatus = await getGrabMonitorStatus(recordingEnabled, result[KEYS.GRAB_MONITOR_TAB]);
  const hubStatus = await getHubStatus(false);

  return {
    appVersion: APP_VERSION,
    recordingEnabled,
    today: dateKey,
    count: orders.length,
    platformCounts: countOrdersByPlatform(orders),
    filename: excelFilename(dateKey),
    lastOrder: result[KEYS.LAST_ORDER] || null,
    lastDetected: result[KEYS.LAST_DETECTED] || null,
    lastExport: result[KEYS.LAST_EXPORT] || null,
    exportPending: Boolean(dirtyEntry),
    pendingExportCount: dirtyEntry?.count || 0,
    automationStatus: result[KEYS.AUTOMATION] || null,
    hub: hubStatus,
    history: buildExportHistory(result, dirtyDates, downloads),
    platforms: {
      grabfood: grabStatus,
      shopeefood: {
        integrated: false,
        connected: false,
        state: "not_integrated",
        message: "Chưa tích hợp"
      }
    }
  };
}

async function getGrabMonitorStatus(recordingEnabled, storedTabId) {
  if (!recordingEnabled) {
    return {
      integrated: true,
      connected: false,
      state: "off",
      message: "Đã tắt",
      tabId: null
    };
  }

  const tab = await safeGetTab(storedTabId);
  if (!tab) {
    return {
      integrated: true,
      connected: false,
      state: "starting",
      message: "Đang mở tab giám sát",
      tabId: null
    };
  }

  const response = await pingRecorder(tab.id);
  if (!response?.connected) {
    return {
      integrated: true,
      connected: false,
      state: "recovering",
      message: "Đang khôi phục kết nối",
      tabId: tab.id,
      url: tab.url || ""
    };
  }

  const connected = response.version === APP_VERSION && response.monitorMode;
  return {
    integrated: true,
    connected,
    state: response.viewState || (connected ? "ready" : "starting"),
    message: response.viewMessage || (connected ? "Đang theo dõi" : "Đang khởi động"),
    tabId: tab.id,
    rows: Number(response.rows || 0),
    url: response.url || tab.url || ""
  };
}

function countOrdersByPlatform(orders) {
  const counts = { GrabFood: 0, ShopeeFood: 0 };
  for (const order of orders) {
    const platform = normalizePlatform(order?.platform);
    if (platform) counts[platform] = (counts[platform] || 0) + 1;
  }
  return counts;
}

function buildExportHistory(allData, dirtyDates, downloads) {
  const entries = [];
  for (const [key, value] of Object.entries(allData)) {
    if (!key.startsWith(ORDERS_PREFIX) || !Array.isArray(value) || !value.length) continue;
    const dateKey = key.slice(ORDERS_PREFIX.length);
    if (!parseDateKey(dateKey)) continue;
    entries.push({
      dateKey,
      count: value.length,
      platformCounts: countOrdersByPlatform(value),
      filename: excelFilename(dateKey),
      exportPending: Boolean(dirtyDates[dateKey]),
      exportedAt: downloads[dateKey]?.exportedAt || ""
    });
  }
  return entries.sort((a, b) => b.dateKey.localeCompare(a.dateKey));
}

async function getOrdersForDate(dateKey) {
  const storageKey = `${ORDERS_PREFIX}${dateKey}`;
  const result = await chrome.storage.local.get(storageKey);
  return Array.isArray(result[storageKey]) ? result[storageKey] : [];
}

async function exportOrdersForDate(dateKey) {
  const orders = await getOrdersForDate(dateKey);
  if (!orders.length) throw new Error(`Ngày ${formatDateKeyForDisplay(dateKey)} không có đơn để xuất.`);

  const base64 = XlsxBuilder.buildOrdersWorkbookBase64(orders, dateKey);
  const filename = excelFilename(dateKey);
  const dataUrl = `data:application/vnd.openxmlformats-officedocument.spreadsheetml.sheet;base64,${base64}`;

  try {
    pendingExportFilename = filename;
    const downloadId = await chrome.downloads.download({
      url: dataUrl,
      filename,
      conflictAction: "overwrite",
      saveAs: false
    });

    const actualDownload = await waitForDownloadFilename(downloadId, filename);
    pendingExportFilename = "";
    const actualFilename = basename(actualDownload?.filename) || filename;

    const downloadsState = await chrome.storage.local.get([KEYS.DOWNLOADS, KEYS.DIRTY_DATES]);
    const downloads = downloadsState[KEYS.DOWNLOADS] || {};
    const dirtyDates = downloadsState[KEYS.DIRTY_DATES] || {};
    const exportedAt = isoNow();

    downloads[dateKey] = { downloadId, filename: actualFilename, exportedAt };
    delete dirtyDates[dateKey];

    const status = {
      ok: true,
      dateKey,
      filename: actualFilename,
      expectedFilename: filename,
      downloadId,
      exportedAt,
      exportedCount: orders.length,
      error: ""
    };

    await chrome.storage.local.set({
      [KEYS.DOWNLOADS]: downloads,
      [KEYS.DIRTY_DATES]: dirtyDates,
      [KEYS.LAST_EXPORT]: status
    });

    return { exported: true, ...status };
  } catch (error) {
    pendingExportFilename = "";
    const status = {
      ok: false,
      dateKey,
      filename,
      exportedAt: isoNow(),
      error: error.message || "Không thể tạo file Excel."
    };
    await chrome.storage.local.set({ [KEYS.LAST_EXPORT]: status });
    throw new Error("Không thể cập nhật Excel. Hãy đóng file Excel rồi bấm Xuất lại.");
  }
}

async function waitForDownloadFilename(downloadId, fallbackFilename) {
  for (let attempt = 0; attempt < 12; attempt += 1) {
    const items = await chrome.downloads.search({ id: downloadId });
    if (items?.[0]?.filename) return items[0];
    await sleep(80);
  }
  return { filename: fallbackFilename };
}

function basename(value) {
  return String(value || "").split(/[\\/]/).pop();
}

function sleep(ms) {
  return new Promise((resolve) => setTimeout(resolve, ms));
}

async function cleanupExpiredData() {
  const all = await chrome.storage.local.get(null);
  const downloads = all[KEYS.DOWNLOADS] || {};
  const dirtyDates = all[KEYS.DIRTY_DATES] || {};
  const cutoff = dateAtStartOfDay();
  cutoff.setDate(cutoff.getDate() - RETENTION_DAYS);
  const keysToRemove = [];

  for (const key of Object.keys(all)) {
    if (!key.startsWith(ORDERS_PREFIX)) continue;
    const dateKey = key.slice(ORDERS_PREFIX.length);
    const date = parseDateKey(dateKey);
    if (date && date <= cutoff) keysToRemove.push(key);
  }

  for (const [dateKey, entry] of Object.entries(downloads)) {
    const date = parseDateKey(dateKey);
    if (!date || date > cutoff) continue;

    if (entry?.downloadId) {
      try {
        await chrome.downloads.removeFile(entry.downloadId);
        await chrome.downloads.erase({ id: entry.downloadId });
      } catch (error) {
        console.warn(`Không thể xóa file ${dateKey}:`, error);
      }
    }
    delete downloads[dateKey];
    delete dirtyDates[dateKey];
  }

  for (const dateKey of Object.keys(dirtyDates)) {
    const date = parseDateKey(dateKey);
    if (!date || date <= cutoff) delete dirtyDates[dateKey];
  }

  if (keysToRemove.length) await chrome.storage.local.remove(keysToRemove);
  await chrome.storage.local.set({
    [KEYS.DOWNLOADS]: downloads,
    [KEYS.DIRTY_DATES]: dirtyDates
  });

  const pending = all[KEYS.PENDING] || {};
  const pendingCutoff = Date.now() - RETENTION_DAYS * 24 * 60 * 60 * 1000;
  let pendingChanged = false;
  for (const [key, value] of Object.entries(pending)) {
    if (!isValidDate(value?.detectedAt) || new Date(value.detectedAt).getTime() <= pendingCutoff) {
      delete pending[key];
      pendingChanged = true;
    }
  }
  if (pendingChanged) await chrome.storage.local.set({ [KEYS.PENDING]: pending });

  await updateBadge();
  return { removedStorageKeys: keysToRemove };
}

// ==============================
// Order Recorder Hub sync module
// ==============================

async function ensureHubSyncAlarm() {
  const alarm = await chrome.alarms.get(HUB_SYNC_ALARM);
  if (!alarm) {
    await chrome.alarms.create(HUB_SYNC_ALARM, {
      delayInMinutes: 0.5,
      periodInMinutes: 0.5
    });
  }
}

async function ensureHubHeartbeatAlarm() {
  const alarm = await chrome.alarms.get(HUB_HEARTBEAT_ALARM);
  if (!alarm) {
    await chrome.alarms.create(HUB_HEARTBEAT_ALARM, {
      delayInMinutes: 0.2,
      periodInMinutes: 1
    });
  }
}

async function sendHubHeartbeat() {
  const state = await chrome.storage.local.get([KEYS.RECORDING, HUB_QUEUE_KEY]);
  const queue = Array.isArray(state[HUB_QUEUE_KEY]) ? state[HUB_QUEUE_KEY] : [];
  const recording = Boolean(state[KEYS.RECORDING]);
  const controller = new AbortController();
  const timer = setTimeout(() => controller.abort(), 2500);
  try {
    const response = await fetch(`${HUB_URL}/api/heartbeat`, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({
        source: "grab-extension",
        platform: "grab",
        deviceName: "Grab Extension · Chrome",
        status: recording ? "ready" : "recording_off",
        pending: queue.length,
        version: APP_VERSION
      }),
      signal: controller.signal
    });
    return response.ok;
  } catch {
    return false;
  } finally {
    clearTimeout(timer);
  }
}

function normalizeHubPhone(value) {
  let digits = String(value || "").replace(/\D/g, "");
  if (digits.startsWith("84") && digits.length >= 11) digits = `0${digits.slice(2)}`;
  return digits;
}

function hubItemFromOrder(order) {
  const now = isoNow();
  const orderCode = normalizeText(order?.orderId);
  const fullOrderId = normalizeText(order?.fullOrderId);
  const receivedAt = isValidDate(order?.receivedAt) ? new Date(order.receivedAt).toISOString() : now;
  const recordedAt = isValidDate(order?.recordedAt) ? new Date(order.recordedAt).toISOString() : now;
  const phone = normalizeHubPhone(order?.phone);
  const key = fullOrderId
    ? `grab:full:${fullOrderId.toLowerCase()}`
    : `grab:display:${orderCode.toLowerCase()}:${receivedAt.slice(0, 10)}`;

  return {
    key,
    capturedAt: now,
    payload: {
      platform: "grab",
      orderCode,
      displayOrderId: orderCode,
      fullOrderId,
      phone,
      customerName: normalizeText(order?.customerName),
      receivedAt,
      recordedAt,
      sourceDevice: "Grab Extension",
      agentSessionId: grabAgentSessionId(orderCode, receivedAt)
    }
  };
}


function grabAgentSessionId(orderCode, eventAt) {
  const code = normalizeText(orderCode).toUpperCase().replace(/\s+/g, "");
  const at = isValidDate(eventAt) ? new Date(eventAt).toISOString() : isoNow();
  const day = at.slice(0, 10);
  return `grab:${day}:${code || "unknown"}`;
}

function hubSeenEventFromDetection(rawOrder, detected) {
  const orderCode = normalizeText(rawOrder?.orderId || detected?.orderId);
  const eventAt = isValidDate(detected?.detectedAt)
    ? new Date(detected.detectedAt).toISOString()
    : isoNow();
  const sessionId = grabAgentSessionId(orderCode, eventAt);
  return {
    key: `grab-event:${sessionId}:seen`,
    capturedAt: isoNow(),
    payload: {
      eventId: `grab:${sessionId}:seen`,
      platform: "grab",
      eventType: "seen",
      orderCode,
      agentSessionId: sessionId,
      eventAt,
      sourceDevice: "Grab Extension",
      metadata: { agentVersion: APP_VERSION }
    }
  };
}

function hubCapturedEventFromOrder(order) {
  const orderCode = normalizeText(order?.orderId);
  const eventAt = isValidDate(order?.recordedAt)
    ? new Date(order.recordedAt).toISOString()
    : isoNow();
  const receivedAt = isValidDate(order?.receivedAt)
    ? new Date(order.receivedAt).toISOString()
    : eventAt;
  const sessionId = grabAgentSessionId(orderCode, receivedAt);
  return {
    key: `grab-event:${sessionId}:captured`,
    capturedAt: isoNow(),
    payload: {
      eventId: `grab:${sessionId}:captured`,
      platform: "grab",
      eventType: "captured",
      orderCode,
      agentSessionId: sessionId,
      eventAt,
      sourceDevice: "Grab Extension",
      metadata: { agentVersion: APP_VERSION, captureMode: "automatic" }
    }
  };
}

async function enqueueHubEvent(item) {
  if (!item?.key || !item?.payload) return { queued: false, ignored: true };
  const data = await chrome.storage.local.get([HUB_EVENT_QUEUE_KEY, HUB_EVENT_SENT_KEY]);
  const queue = Array.isArray(data[HUB_EVENT_QUEUE_KEY]) ? data[HUB_EVENT_QUEUE_KEY] : [];
  const sent = data[HUB_EVENT_SENT_KEY] && typeof data[HUB_EVENT_SENT_KEY] === "object"
    ? data[HUB_EVENT_SENT_KEY]
    : {};
  if (sent[item.key] || queue.some((entry) => entry?.key === item.key)) {
    return { queued: false, duplicate: true };
  }
  queue.push(item);
  await chrome.storage.local.set({ [HUB_EVENT_QUEUE_KEY]: queue });
  return { queued: true };
}

async function postHubEvent(payload) {
  const controller = new AbortController();
  const timer = setTimeout(() => controller.abort(), 3000);
  try {
    const response = await fetch(`${HUB_URL}/api/order-events`, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify(payload),
      signal: controller.signal
    });
    if (!response.ok) throw new Error(`Hub event HTTP ${response.status}`);
    return response.json();
  } finally {
    clearTimeout(timer);
  }
}

async function syncHubEventQueue() {
  const data = await chrome.storage.local.get([HUB_EVENT_QUEUE_KEY, HUB_EVENT_SENT_KEY]);
  const queue = Array.isArray(data[HUB_EVENT_QUEUE_KEY]) ? data[HUB_EVENT_QUEUE_KEY] : [];
  const sent = data[HUB_EVENT_SENT_KEY] && typeof data[HUB_EVENT_SENT_KEY] === "object"
    ? data[HUB_EVENT_SENT_KEY]
    : {};
  if (!queue.length) return { pending: 0, syncedNow: 0 };

  const online = await checkHubOnline();
  if (!online) return { online: false, pending: queue.length, syncedNow: 0 };

  const remaining = [];
  let syncedNow = 0;
  for (const item of queue) {
    if (!item?.key || sent[item.key]) continue;
    try {
      await postHubEvent(item.payload);
      sent[item.key] = Date.now();
      syncedNow += 1;
    } catch (error) {
      item.lastError = String(error?.message || error);
      item.lastTriedAt = Date.now();
      remaining.push(item);
    }
  }

  const compactSent = Object.fromEntries(
    Object.entries(sent).sort((a, b) => Number(b[1]) - Number(a[1])).slice(0, HUB_MAX_SENT_KEYS)
  );
  await chrome.storage.local.set({
    [HUB_EVENT_QUEUE_KEY]: remaining,
    [HUB_EVENT_SENT_KEY]: compactSent
  });
  return { online: true, pending: remaining.length, syncedNow };
}

async function enqueueHubOrder(order) {
  const item = hubItemFromOrder(order);
  if ((!/^GF-?\d+/i.test(item.payload.orderCode) && !item.payload.fullOrderId) || item.payload.phone.length < 9) {
    return { queued: false, ignored: true };
  }

  const data = await chrome.storage.local.get([HUB_QUEUE_KEY, HUB_SENT_KEY]);
  const queue = Array.isArray(data[HUB_QUEUE_KEY]) ? data[HUB_QUEUE_KEY] : [];
  const sent = data[HUB_SENT_KEY] && typeof data[HUB_SENT_KEY] === "object" ? data[HUB_SENT_KEY] : {};
  if (sent[item.key] || queue.some((entry) => entry.key === item.key)) return { queued: false, duplicate: true };
  queue.push(item);
  await chrome.storage.local.set({ [HUB_QUEUE_KEY]: queue });
  return { queued: true };
}

async function checkHubOnline() {
  const controller = new AbortController();
  const timer = setTimeout(() => controller.abort(), 1600);
  try {
    const response = await fetch(`${HUB_URL}/api/health`, {
      cache: "no-store",
      signal: controller.signal
    });
    return response.ok;
  } catch {
    return false;
  } finally {
    clearTimeout(timer);
  }
}

async function postHubOrder(payload) {
  const controller = new AbortController();
  const timer = setTimeout(() => controller.abort(), 3500);
  try {
    const response = await fetch(`${HUB_URL}/api/orders`, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify(payload),
      signal: controller.signal
    });
    if (!response.ok) throw new Error(`Hub HTTP ${response.status}`);
    return response.json();
  } finally {
    clearTimeout(timer);
  }
}

async function syncHubQueue() {
  const online = await checkHubOnline();
  const data = await chrome.storage.local.get([HUB_QUEUE_KEY, HUB_SENT_KEY]);
  const queue = Array.isArray(data[HUB_QUEUE_KEY]) ? data[HUB_QUEUE_KEY] : [];
  const sent = data[HUB_SENT_KEY] && typeof data[HUB_SENT_KEY] === "object" ? data[HUB_SENT_KEY] : {};

  if (!online) {
    await setHubStatus(false, queue.length, "Hub offline · đơn vẫn được giữ local");
    return { online: false, pending: queue.length };
  }

  const remaining = [];
  let syncedNow = 0;
  for (const item of queue) {
    if (sent[item.key]) continue;
    try {
      await postHubOrder(item.payload);
      sent[item.key] = Date.now();
      syncedNow += 1;
    } catch (error) {
      item.lastError = String(error?.message || error);
      item.lastTriedAt = Date.now();
      remaining.push(item);
    }
  }

  const compactSent = Object.fromEntries(
    Object.entries(sent).sort((a, b) => Number(b[1]) - Number(a[1])).slice(0, HUB_MAX_SENT_KEYS)
  );
  await chrome.storage.local.set({
    [HUB_QUEUE_KEY]: remaining,
    [HUB_SENT_KEY]: compactSent
  });
  await setHubStatus(
    true,
    remaining.length,
    syncedNow ? `Đã đồng bộ ${syncedNow} đơn lên Hub` : (remaining.length ? `Hub online · ${remaining.length} đơn chờ` : "Hub online · đã đồng bộ hết")
  );
  sendHubHeartbeat().catch(() => undefined);
  return { online: true, pending: remaining.length, syncedNow };
}

async function setHubStatus(online, pending, message) {
  const value = { online: Boolean(online), pending: Math.max(0, Number(pending) || 0), message, checkedAt: Date.now() };
  await chrome.storage.local.set({ [HUB_STATUS_KEY]: value });
  return value;
}

async function getHubStatus(checkNow = true) {
  const data = await chrome.storage.local.get([HUB_QUEUE_KEY, HUB_STATUS_KEY]);
  const queue = Array.isArray(data[HUB_QUEUE_KEY]) ? data[HUB_QUEUE_KEY] : [];
  const old = data[HUB_STATUS_KEY] || {};
  if (!checkNow) {
    const fresh = Number(old.checkedAt || 0) > Date.now() - 15000;
    if (fresh) return { ...old, pending: queue.length };
  }
  const online = await checkHubOnline();
  return setHubStatus(
    online,
    queue.length,
    online ? (queue.length ? `Hub online · ${queue.length} đơn chờ đồng bộ` : "Hub online · đã đồng bộ hết") : "Hub offline · lưu local an toàn"
  );
}

async function cleanupHubLocalState() {
  const cutoff = Date.now() - HUB_RETENTION_MS;
  const data = await chrome.storage.local.get([HUB_QUEUE_KEY, HUB_SENT_KEY, HUB_EVENT_QUEUE_KEY, HUB_EVENT_SENT_KEY]);
  const queue = (Array.isArray(data[HUB_QUEUE_KEY]) ? data[HUB_QUEUE_KEY] : []).filter((item) => {
    const t = Date.parse(item?.capturedAt || "");
    return !Number.isFinite(t) || t >= cutoff;
  });
  const sent = data[HUB_SENT_KEY] && typeof data[HUB_SENT_KEY] === "object" ? data[HUB_SENT_KEY] : {};
  const cleanSent = {};
  for (const [key, timestamp] of Object.entries(sent)) {
    if (Number(timestamp) >= cutoff) cleanSent[key] = timestamp;
  }
  const eventQueue = (Array.isArray(data[HUB_EVENT_QUEUE_KEY]) ? data[HUB_EVENT_QUEUE_KEY] : []).filter((item) => {
    const t = Date.parse(item?.capturedAt || "");
    return !Number.isFinite(t) || t >= cutoff;
  });
  const eventSent = data[HUB_EVENT_SENT_KEY] && typeof data[HUB_EVENT_SENT_KEY] === "object" ? data[HUB_EVENT_SENT_KEY] : {};
  const cleanEventSent = {};
  for (const [key, timestamp] of Object.entries(eventSent)) {
    if (Number(timestamp) >= cutoff) cleanEventSent[key] = timestamp;
  }
  await chrome.storage.local.set({
    [HUB_QUEUE_KEY]: queue,
    [HUB_SENT_KEY]: cleanSent,
    [HUB_EVENT_QUEUE_KEY]: eventQueue,
    [HUB_EVENT_SENT_KEY]: cleanEventSent
  });
}

async function backfillLocalGrabOrdersToHubOnce() {
  const state = await chrome.storage.local.get(HUB_BACKFILL_VERSION_KEY);
  if (state[HUB_BACKFILL_VERSION_KEY] === APP_VERSION) return;

  const all = await chrome.storage.local.get(null);
  let added = 0;
  for (const [key, value] of Object.entries(all)) {
    if (!key.startsWith(ORDERS_PREFIX) || !Array.isArray(value)) continue;
    for (const order of value) {
      if (normalizePlatform(order?.platform) !== PLATFORM_GRAB) continue;
      const result = await enqueueHubOrder(order);
      if (result.queued) added += 1;
    }
  }
  await chrome.storage.local.set({ [HUB_BACKFILL_VERSION_KEY]: APP_VERSION });
  if (added) console.info(`[Order Recorder] Đã xếp ${added} đơn Grab local để đồng bộ Hub.`);
}

/**
 * Gửi lại toàn bộ dữ liệu Grab còn lưu local trong 7 ngày, kể cả các đơn từng
 * được đánh dấu đã sync. Hub chịu trách nhiệm upsert/chống trùng nên thao tác
 * này có thể chạy lại nhiều lần mà không tạo bản ghi trùng.
 */
async function forceResyncLocalGrabOrdersToHub() {
  const all = await chrome.storage.local.get(null);
  const uniqueItems = new Map();
  let scanned = 0;
  let skipped = 0;

  for (const [storageKey, value] of Object.entries(all)) {
    if (!storageKey.startsWith(ORDERS_PREFIX) || !Array.isArray(value)) continue;
    for (const order of value) {
      if (normalizePlatform(order?.platform) !== PLATFORM_GRAB) continue;
      scanned += 1;
      const item = hubItemFromOrder(order);
      const validCode = /^GF-?\d+/i.test(item.payload.orderCode) || Boolean(item.payload.fullOrderId);
      if (!validCode || item.payload.phone.length < 9) {
        skipped += 1;
        continue;
      }
      uniqueItems.set(item.key, item);
    }
  }

  if (!uniqueItems.size) {
    return { scanned, queued: 0, skipped, syncedNow: 0, pending: 0, message: "Không có dữ liệu Grab hợp lệ trong 7 ngày để đồng bộ lại." };
  }

  const state = await chrome.storage.local.get([HUB_QUEUE_KEY, HUB_SENT_KEY]);
  const sent = state[HUB_SENT_KEY] && typeof state[HUB_SENT_KEY] === "object" ? state[HUB_SENT_KEY] : {};
  const queueMap = new Map();
  for (const item of (Array.isArray(state[HUB_QUEUE_KEY]) ? state[HUB_QUEUE_KEY] : [])) {
    if (item?.key) queueMap.set(item.key, item);
  }

  // Bỏ dấu "đã gửi" chỉ cho những đơn đang force-resync và thay queue bằng
  // payload vừa chuẩn hóa từ local storage.
  for (const [key, item] of uniqueItems.entries()) {
    delete sent[key];
    queueMap.set(key, item);
  }

  await chrome.storage.local.set({
    [HUB_QUEUE_KEY]: Array.from(queueMap.values()),
    [HUB_SENT_KEY]: sent
  });
  await setHubStatus(true, queueMap.size, `Đã xếp ${uniqueItems.size} đơn cũ để đồng bộ lại`);

  const result = await syncHubQueue();
  const syncedNow = Number(result?.syncedNow || 0);
  const pending = Number(result?.pending || 0);
  const message = result?.online === false
    ? `Đã xếp ${uniqueItems.size} đơn cũ · Hub đang offline, sẽ tự gửi khi Hub hoạt động lại`
    : `Đồng bộ lại hoàn tất: ${syncedNow} đơn đã gửi, ${pending} đơn còn chờ`;

  return { scanned, queued: uniqueItems.size, skipped, syncedNow, pending, message };
}

async function updateBadge(forcedCount, platform) {
  const state = await chrome.storage.local.get(KEYS.RECORDING);
  const enabled = Boolean(state[KEYS.RECORDING]);

  if (!enabled) {
    await chrome.action.setBadgeText({ text: "OFF" });
    await chrome.action.setBadgeBackgroundColor({ color: "#64748b" });
    return;
  }

  let count = forcedCount;
  if (typeof count !== "number") count = (await getOrdersForDate(localDateKey())).length;

  await chrome.action.setBadgeText({ text: count > 0 ? String(count) : "ON" });
  await chrome.action.setBadgeBackgroundColor({
    color: platform === "ShopeeFood" ? "#ee4d2d" : "#00b14f"
  });
}

function uniqueOrderKey(platform, orderId) {
  return `${normalizePlatform(platform)}_${normalizeText(orderId).toUpperCase()}`;
}

function normalizeText(value) {
  return String(value || "").replace(/\s+/g, " ").trim();
}

function normalizeVietnamPhone(value) {
  const raw = String(value || "").trim();
  let digits = raw.replace(/\D/g, "");
  if (digits.startsWith("84") && digits.length >= 11) digits = `0${digits.slice(2)}`;
  if (!digits.startsWith("0") && digits.length === 9) digits = `0${digits}`;
  return digits;
}

function localDateKey(date = new Date()) {
  const parts = new Intl.DateTimeFormat("en-CA", {
    timeZone: "Asia/Ho_Chi_Minh",
    year: "numeric",
    month: "2-digit",
    day: "2-digit"
  }).formatToParts(date);
  const map = Object.fromEntries(parts.map((part) => [part.type, part.value]));
  return `${map.year}-${map.month}-${map.day}`;
}

function excelFilename(dateKey) {
  return `DonHangGrab_${dateKey}.xlsx`;
}

function validateExportDateKey(value) {
  const dateKey = normalizeText(value);
  const date = parseDateKey(dateKey);
  if (!date || dateKey > localDateKey()) throw new Error("Ngày xuất dữ liệu không hợp lệ.");

  const cutoff = dateAtStartOfDay();
  cutoff.setDate(cutoff.getDate() - RETENTION_DAYS);
  if (date <= cutoff) throw new Error("Dữ liệu ngày này đã vượt quá thời hạn lưu 7 ngày.");
  return dateKey;
}

function formatDateKeyForDisplay(dateKey) {
  const match = /^(\d{4})-(\d{2})-(\d{2})$/.exec(dateKey || "");
  if (!match) return dateKey || "";
  return `${match[3]}/${match[2]}/${match[1]}`;
}

function isoNow() {
  return new Date().toISOString();
}

function isValidDate(value) {
  return Boolean(value) && !Number.isNaN(new Date(value).getTime());
}

function dateAtStartOfDay() {
  return parseDateKey(localDateKey());
}

function parseDateKey(key) {
  const match = /^(\d{4})-(\d{2})-(\d{2})$/.exec(key || "");
  if (!match) return null;
  return new Date(Number(match[1]), Number(match[2]) - 1, Number(match[3]), 0, 0, 0, 0);
}
