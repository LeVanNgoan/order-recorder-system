(() => {
  "use strict";

  const SCRIPT_VERSION = "2.0.1";
  const PLATFORM = "GrabFood";
  const GLOBAL_KEY = "__ORDER_RECORDER_GRAB_MONITOR__";

  const previousInstance = globalThis[GLOBAL_KEY];
  if (previousInstance?.version === SCRIPT_VERSION && !previousInstance?.destroyed) return;
  try {
    previousInstance?.destroy?.("replaced_by_new_script");
  } catch {
    // Phiên bản cũ có thể đã mất extension context, bỏ qua và tiếp tục khởi tạo.
  }

  const instanceState = {
    version: SCRIPT_VERSION,
    startedAt: Date.now(),
    destroyed: false,
    destroy: null
  };
  globalThis[GLOBAL_KEY] = instanceState;

  const SELECTORS = Object.freeze({
    preparingPanel: '[id$="-panel-preparing"]',
    preparingTab: '[id$="-tab-preparing"], [role="tab"][aria-controls$="-panel-preparing"]',
    preparingTable: '.preparing-table-list, [id$="-panel-preparing"] [data-testid="table-view"]',
    orderRows: '.preparing-table-list tr.dui-table-row.dui-table-row-level-0, [id$="-panel-preparing"] tr.dui-table-row.dui-table-row-level-0',
    detailDrawer: '.dui-drawer.dui-drawer-open',
    drawerClose: 'button.dui-drawer-close',
    displayOrderId: '[data-testid="displayOfDisplayID"]',
    fullOrderId: '[data-testid="displayOfOrderID"]',
    customerPhone: '[data-testid="eater-number"]'
  });

  const MONITOR_UI = Object.freeze({
    bannerId: "order-recorder-monitor-warning",
    styleId: "order-recorder-monitor-warning-style",
    title: "⚠ MONITOR — DO NOT INTERACT"
  });

  const SETTINGS = Object.freeze({
    scanDebounceMs: 250,
    periodicScanMs: 1100,
    viewGuardMs: 4000,
    detailTimeoutMs: 12000,
    clickAttemptTimeoutMs: 3500,
    drawerCloseTimeoutMs: 3500,
    betweenOrdersMs: 650,
    retryDelayMs: 2200,
    maxRetries: 5,
    routeRetryMs: 1200
  });

  const seenRows = new Set();
  const queuedOrders = new Set();
  const retryCounts = new Map();
  const orderQueue = [];

  let enabled = false;
  let monitorMode = false;
  let observer = null;
  let scanTimer = null;
  let periodicTimer = null;
  let viewGuardTimer = null;
  let processingQueue = false;
  let automationOrderId = "";
  let lastCapturedSignature = "";
  let viewState = "starting";
  let viewMessage = "GrabFood · Đang khởi động tab giám sát";
  let forceViewPromise = null;
  let lastRouteAttemptAt = 0;
  let preferredPreparingUrl = "";
  let runtimeMessageListener = null;
  let contextInvalidated = false;
  let recoveryReloadScheduled = false;
  let originalDocumentTitle = "";

  instanceState.destroy = destroyInstance;

  init().catch((error) => {
    if (isContextInvalidatedError(error)) {
      handleInvalidatedContext();
      return;
    }
    viewState = "error";
    viewMessage = error?.message || "GrabFood · Bộ giám sát gặp lỗi";
    console.error("[Order Recorder/Grab]", error);
  });

  async function init() {
    if (!hasRuntimeContext()) {
      handleInvalidatedContext();
      return;
    }

    try {
      sessionStorage.removeItem("__ORDER_RECORDER_CONTEXT_RECOVERY_AT__");
    } catch {
      // sessionStorage có thể bị chặn ở một số chế độ trình duyệt.
    }

    registerMessageHandlers();

    const [storageState, roleResponse] = await Promise.all([
      chrome.storage.local.get("recordingEnabled"),
      safeMessage({ type: "GET_TAB_ROLE" })
    ]);

    enabled = Boolean(storageState.recordingEnabled);
    monitorMode = Boolean(roleResponse?.ok && roleResponse.data?.monitorMode);

    chrome.storage.onChanged.addListener(handleStorageChanged);

    window.addEventListener("pageshow", handleWake, { passive: true });
    window.addEventListener("online", handleWake, { passive: true });
    window.addEventListener("popstate", handleWake, { passive: true });
    window.addEventListener("hashchange", handleWake, { passive: true });
    document.addEventListener("visibilitychange", handleWake, { passive: true });

    syncRuntimeState();
    console.info(`[Order Recorder/Grab] Monitor script v${SCRIPT_VERSION} đã kết nối. monitorMode=${monitorMode}`);
  }

  function registerMessageHandlers() {
    runtimeMessageListener = (message, sender, sendResponse) => {
      if (message?.type === "PING_RECORDER") {
        sendResponse({
          ok: true,
          connected: true,
          enabled,
          monitorMode,
          version: SCRIPT_VERSION,
          url: location.href,
          rows: countVisibleOrderRows(),
          viewState,
          viewMessage
        });
        return false;
      }

      if (message?.type === "SET_MONITOR_MODE") {
        monitorMode = Boolean(message.enabled);
        syncRuntimeState();
        sendResponse({ ok: true, monitorMode });
        return false;
      }

      if (message?.type === "FORCE_MONITOR_VIEW") {
        preferredPreparingUrl = normalizePreparingUrl(message.preparingUrl)
          || preferredPreparingUrl
          || resolvePreparingUrl();
        forceMonitorView()
          .then((result) => sendResponse({ ok: true, ...result }))
          .catch((error) => sendResponse({ ok: false, error: error.message }));
        return true;
      }

      if (message?.type === "FORCE_SCAN") {
        scanPage();
        sendResponse({ ok: true });
        return false;
      }

      return false;
    };
    chrome.runtime.onMessage.addListener(runtimeMessageListener);
  }

  function handleStorageChanged(changes, areaName) {
    if (contextInvalidated || areaName !== "local" || !changes.recordingEnabled) return;
    enabled = Boolean(changes.recordingEnabled.newValue);
    syncRuntimeState();
  }

  function syncRuntimeState() {
    if (contextInvalidated) return;
    if (enabled && monitorMode) {
      ensureMonitorUi();
      startWatching();
      forceMonitorView().catch((error) => {
        viewState = "error";
        viewMessage = error?.message || "GrabFood · Không mở được màn hình đơn hàng";
      });
    } else {
      removeMonitorUi();
      stopWatching();
      viewState = enabled ? "standby" : "off";
      viewMessage = enabled
        ? "GrabFood · Tab thường, không dùng để giám sát"
        : "Đã tắt ghi nhận";
    }
  }

  function ensureMonitorUi() {
    if (contextInvalidated || !enabled || !monitorMode) return;

    if (!originalDocumentTitle) {
      originalDocumentTitle = document.title || "Grab Merchant";
    }
    if (document.title !== MONITOR_UI.title) {
      document.title = MONITOR_UI.title;
    }

    if (!document.getElementById(MONITOR_UI.styleId)) {
      const style = document.createElement("style");
      style.id = MONITOR_UI.styleId;
      style.textContent = `
        #${MONITOR_UI.bannerId} {
          position: fixed;
          top: 50%;
          left: 50%;
          transform: translate(-50%, -50%) rotate(-4deg);
          width: min(960px, calc(100vw - 32px));
          box-sizing: border-box;
          z-index: 2147483647;
          pointer-events: none;
          user-select: none;
          color: rgba(185, 28, 28, 0.92);
          border: 7px double rgba(185, 28, 28, 0.86);
          border-radius: 20px;
          background: rgba(255, 255, 255, 0.055);
          box-shadow:
            inset 0 0 0 2px rgba(185, 28, 28, 0.17),
            0 8px 24px rgba(127, 29, 29, 0.10);
          font-family: "Segoe UI Variable Display", "Segoe UI", "Helvetica Neue", Arial, sans-serif;
          opacity: 0.94;
          filter: saturate(0.96) contrast(1.06);
        }
        #${MONITOR_UI.bannerId}::before,
        #${MONITOR_UI.bannerId}::after {
          content: "★";
          position: absolute;
          top: 50%;
          transform: translateY(-50%);
          color: rgba(185, 28, 28, 0.84);
          font-size: clamp(24px, 3.6vw, 48px);
          line-height: 1;
        }
        #${MONITOR_UI.bannerId}::before { left: clamp(16px, 3vw, 34px); }
        #${MONITOR_UI.bannerId}::after { right: clamp(16px, 3vw, 34px); }
        #${MONITOR_UI.bannerId} .order-recorder-monitor-warning-track {
          display: grid;
          grid-template-rows: auto auto auto;
          align-items: center;
          justify-items: center;
          row-gap: 12px;
          width: 100%;
          min-height: 214px;
          padding: 30px clamp(54px, 9vw, 112px);
          box-sizing: border-box;
          text-align: center;
          text-transform: uppercase;
          line-height: 1;
          text-shadow:
            1px 0 rgba(127, 29, 29, 0.16),
            -1px 0 rgba(127, 29, 29, 0.10),
            0 1px rgba(127, 29, 29, 0.10);
        }
        #${MONITOR_UI.bannerId} .order-recorder-monitor-warning-title,
        #${MONITOR_UI.bannerId} .order-recorder-monitor-warning-main,
        #${MONITOR_UI.bannerId} .order-recorder-monitor-warning-note {
          display: block;
          width: 100%;
          white-space: nowrap;
          overflow: visible;
          text-align: center;
        }
        #${MONITOR_UI.bannerId} .order-recorder-monitor-warning-title {
          font-size: clamp(23px, 3.4vw, 44px);
          font-weight: 850;
          letter-spacing: clamp(2px, 0.55vw, 7px);
          padding-bottom: 12px;
          border-bottom: 3px solid rgba(185, 28, 28, 0.72);
        }
        #${MONITOR_UI.bannerId} .order-recorder-monitor-warning-main {
          font-family: "Segoe UI Black", "Arial Black", "Segoe UI", Arial, sans-serif;
          font-size: clamp(34px, 5.4vw, 70px);
          font-weight: 900;
          letter-spacing: clamp(1px, 0.35vw, 4px);
        }
        #${MONITOR_UI.bannerId} .order-recorder-monitor-warning-note {
          font-size: clamp(11px, 1.75vw, 22px);
          font-weight: 750;
          letter-spacing: clamp(0.8px, 0.22vw, 2.4px);
          line-height: 1.15;
        }
        @media (max-width: 720px) {
          #${MONITOR_UI.bannerId} {
            width: calc(100vw - 18px);
            border-width: 5px;
            border-radius: 14px;
            transform: translate(-50%, -50%) rotate(-2deg);
          }
          #${MONITOR_UI.bannerId}::before,
          #${MONITOR_UI.bannerId}::after {
            display: none;
          }
          #${MONITOR_UI.bannerId} .order-recorder-monitor-warning-track {
            min-height: 158px;
            row-gap: 9px;
            padding: 20px 12px;
          }
          #${MONITOR_UI.bannerId} .order-recorder-monitor-warning-title {
            padding-bottom: 9px;
          }
        }
      `;
      (document.head || document.documentElement).appendChild(style);
    }

    if (!document.getElementById(MONITOR_UI.bannerId)) {
      const banner = document.createElement("div");
      banner.id = MONITOR_UI.bannerId;
      banner.setAttribute("role", "status");
      banner.setAttribute("aria-label", "Monitor tab. Do not interact. Automatic order recording in progress.");
      banner.innerHTML = `
        <div class="order-recorder-monitor-warning-track">
          <div class="order-recorder-monitor-warning-title">MONITOR TAB</div>
          <div class="order-recorder-monitor-warning-main">DO NOT INTERACT</div>
          <div class="order-recorder-monitor-warning-note">AUTOMATIC ORDER RECORDING IN PROGRESS</div>
        </div>
      `;
      (document.body || document.documentElement).appendChild(banner);
    }
  }

  function removeMonitorUi() {
    document.getElementById(MONITOR_UI.bannerId)?.remove();
    document.getElementById(MONITOR_UI.styleId)?.remove();

    if (originalDocumentTitle && document.title === MONITOR_UI.title) {
      document.title = originalDocumentTitle;
    }
    originalDocumentTitle = "";
  }

  function startWatching() {
    if (contextInvalidated) return;
    ensureMonitorUi();
    if (!observer) {
      observer = new MutationObserver(scheduleScan);
      observer.observe(document.documentElement || document, {
        childList: true,
        subtree: true,
        characterData: true
      });
    }

    if (!periodicTimer) periodicTimer = setInterval(scanPage, SETTINGS.periodicScanMs);
    if (!viewGuardTimer) {
      viewGuardTimer = setInterval(() => {
        if (enabled && monitorMode) forceMonitorView().catch(() => undefined);
      }, SETTINGS.viewGuardMs);
    }

    viewState = "starting";
    viewMessage = "GrabFood · Đang mở danh sách đơn chuẩn bị";
    scanPage();
  }

  function stopWatching() {
    observer?.disconnect();
    observer = null;

    if (scanTimer) clearTimeout(scanTimer);
    if (periodicTimer) clearInterval(periodicTimer);
    if (viewGuardTimer) clearInterval(viewGuardTimer);
    scanTimer = null;
    periodicTimer = null;
    viewGuardTimer = null;

    orderQueue.length = 0;
    queuedOrders.clear();
    retryCounts.clear();
    processingQueue = false;
    automationOrderId = "";
    lastCapturedSignature = "";
  }

  function handleWake() {
    if (contextInvalidated || !enabled || !monitorMode) return;
    forceMonitorView().catch(() => undefined);
    scheduleScan();
  }

  async function forceMonitorView() {
    if (contextInvalidated || !enabled || !monitorMode) return { state: "standby" };
    if (forceViewPromise) return forceViewPromise;

    forceViewPromise = forceMonitorViewInternal().finally(() => {
      forceViewPromise = null;
    });
    return forceViewPromise;
  }

  async function forceMonitorViewInternal() {
    ensureMonitorUi();
    if (location.hostname !== "merchant.grab.com") {
      viewState = "error";
      viewMessage = "GrabFood · Tab giám sát không ở đúng website";
      return { state: viewState };
    }

    const preparingTab = findPreparingTab();
    const preparingPanel = document.querySelector(SELECTORS.preparingPanel);

    if (preparingTab) {
      const selected = preparingTab.getAttribute("aria-selected") === "true"
        || preparingTab.classList.contains("dui-tabs-tab-active");
      if (!selected) {
        dispatchHumanLikeClick(preparingTab);
        await sleep(180);
      }
    }

    if (isPreparingViewReady()) {
      viewState = "ready";
      viewMessage = "GrabFood · Đang theo dõi đơn chuẩn bị";
      scheduleScan();
      return { state: viewState, rows: countVisibleOrderRows() };
    }

    // Trang có bộ tab đơn hàng nhưng React chưa render xong: chờ thay vì tải lại liên tục.
    if (preparingTab || preparingPanel || document.querySelector('[role="tablist"]')) {
      viewState = "starting";
      viewMessage = "GrabFood · Đang chờ danh sách đơn tải xong";
      return { state: viewState };
    }

    if (looksLikeLoginPage()) {
      viewState = "login_required";
      viewMessage = "GrabFood · Cần đăng nhập lại";
      reportAutomationStatus("login_required", "", viewMessage);
      return { state: viewState };
    }

    const monitorUrl = preferredPreparingUrl || resolvePreparingUrl();
    const now = Date.now();
    if (monitorUrl && now - lastRouteAttemptAt >= SETTINGS.routeRetryMs
        && !isSamePreparingRoute(location.href, monitorUrl)) {
      lastRouteAttemptAt = now;
      preferredPreparingUrl = monitorUrl;
      viewState = "navigating";
      viewMessage = "GrabFood · Đang chuyển đến đơn chuẩn bị";
      location.assign(monitorUrl);
      return { state: viewState };
    }

    viewState = "starting";
    viewMessage = "GrabFood · Đang khởi động màn hình đơn hàng";
    return { state: viewState };
  }


  function normalizePreparingUrl(url) {
    try {
      const parsed = new URL(url, location.origin);
      if (parsed.hostname !== "merchant.grab.com") return "";
      const match = parsed.pathname.match(/^\/order\/([^/]+)(?:\/|$)/i);
      if (!match?.[1]) return "";
      return `${parsed.origin}/order/${encodeURIComponent(match[1])}/preparing`;
    } catch {
      return "";
    }
  }

  function resolvePreparingUrl() {
    return normalizePreparingUrl(location.href) || preferredPreparingUrl;
  }

  function isSamePreparingRoute(currentUrl, targetUrl) {
    const current = normalizePreparingUrl(currentUrl);
    const target = normalizePreparingUrl(targetUrl);
    if (!current || !target) return false;
    try {
      return new URL(currentUrl).pathname.toLowerCase() === new URL(target).pathname.toLowerCase();
    } catch {
      return current === target;
    }
  }

  function findPreparingTab() {
    const direct = document.querySelector(SELECTORS.preparingTab);
    if (direct) return direct;

    return [...document.querySelectorAll('[role="tab"], .dui-tabs-tab')].find((element) => {
      const text = normalizeText(element.textContent).toLowerCase();
      return text.includes("đang chuẩn bị") || text === "preparing" || text.includes("preparing");
    }) || null;
  }

  function isPreparingViewReady() {
    const panel = document.querySelector(SELECTORS.preparingPanel);
    if (panel) {
      const hidden = panel.getAttribute("aria-hidden") === "true"
        || panel.classList.contains("dui-tabs-tabpane-hidden");
      if (!hidden && panel.querySelector('[data-testid="table-view"], .preparing-table-list')) return true;
    }
    return Boolean(document.querySelector(SELECTORS.preparingTable));
  }

  function looksLikeLoginPage() {
    const path = location.pathname.toLowerCase();
    if (path.includes("login") || path.includes("signin") || path.includes("auth")) return true;
    const text = normalizeText(document.body?.innerText).toLowerCase();
    return text.includes("đăng nhập") && !document.querySelector('[data-testid="table-view"]');
  }

  function scheduleScan() {
    if (contextInvalidated || !enabled || !monitorMode) return;
    if (scanTimer) clearTimeout(scanTimer);
    scanTimer = setTimeout(() => {
      scanTimer = null;
      scanPage();
    }, SETTINGS.scanDebounceMs);
  }

  function scanPage() {
    if (contextInvalidated || !enabled || !monitorMode) return;
    ensureMonitorUi();

    if (!isPreparingViewReady()) {
      forceMonitorView().catch(() => undefined);
      return;
    }

    viewState = "ready";
    viewMessage = "GrabFood · Đang theo dõi đơn chuẩn bị";
    detectVisibleRows();

    if (!automationOrderId) {
      captureManualOpenOrder().catch((error) => {
        console.warn("[Order Recorder/Grab] Không đọc được đơn đang mở:", error);
      });
    }
  }

  function getVisibleOrderRows() {
    return [...document.querySelectorAll(SELECTORS.orderRows)].filter((row) => {
      if (!row.isConnected || row.getAttribute("aria-hidden") === "true") return false;
      const panel = row.closest('[role="tabpanel"]');
      return !panel || panel.getAttribute("aria-hidden") !== "true";
    });
  }

  function countVisibleOrderRows() {
    return getVisibleOrderRows().length;
  }

  function detectVisibleRows() {
    if (contextInvalidated) return;
    for (const row of getVisibleOrderRows()) {
      const orderId = readOrderIdFromRow(row);
      if (!orderId || !/^GF-[A-Z0-9-]+$/i.test(orderId)) continue;
      if (seenRows.has(orderId) || queuedOrders.has(orderId) || automationOrderId === orderId) continue;

      seenRows.add(orderId);
      safeMessage({
        type: "ORDER_DETECTED",
        order: {
          platform: PLATFORM,
          orderId,
          detectedAt: new Date().toISOString()
        }
      }).then((response) => {
        if (!enabled || !monitorMode) return;
        if (!response?.ok || response.data?.ignored) {
          seenRows.delete(orderId);
          return;
        }
        if (response.data?.alreadySaved) return;
        enqueueOrder(orderId);
      });
    }
  }

  function enqueueOrder(orderId) {
    if (!orderId || queuedOrders.has(orderId) || automationOrderId === orderId) return;
    queuedOrders.add(orderId);
    orderQueue.push(orderId);
    reportAutomationStatus("queued", orderId, `Đang chờ xử lý ${orderQueue.length} đơn`);
    processOrderQueue().catch((error) => {
      processingQueue = false;
      automationOrderId = "";
      reportAutomationStatus("error", orderId, error.message || "Lỗi tự động ghi nhận");
      console.error("[Order Recorder/Grab] Lỗi hàng đợi:", error);
    });
  }

  async function processOrderQueue() {
    if (contextInvalidated || processingQueue || !enabled || !monitorMode) return;
    processingQueue = true;

    try {
      while (enabled && monitorMode && orderQueue.length) {
        const orderId = orderQueue.shift();
        queuedOrders.delete(orderId);
        automationOrderId = orderId;
        reportAutomationStatus("processing", orderId, `Đang mở ${orderId}`);

        const result = await autoCaptureOrder(orderId);
        if (!result.ok && enabled && monitorMode) {
          const retryCount = (retryCounts.get(orderId) || 0) + 1;
          retryCounts.set(orderId, retryCount);

          if (result.retryable && retryCount <= SETTINGS.maxRetries) {
            reportAutomationStatus(
              "retrying",
              orderId,
              `${result.message || "Chưa ghi nhận được"} · thử lại ${retryCount}/${SETTINGS.maxRetries}`
            );
            await sleep(SETTINGS.retryDelayMs);
            queuedOrders.add(orderId);
            orderQueue.push(orderId);
          } else {
            seenRows.delete(orderId);
            reportAutomationStatus("error", orderId, result.message || `Không thể ghi nhận ${orderId}`);
          }
        } else {
          retryCounts.delete(orderId);
        }

        automationOrderId = "";
        await sleep(SETTINGS.betweenOrdersMs);
      }
    } finally {
      processingQueue = false;
      automationOrderId = "";
      if (enabled && monitorMode) {
        reportAutomationStatus("idle", "", "GrabFood · Đang chờ đơn mới");
      }
    }
  }

  async function autoCaptureOrder(orderId) {
    const existingDrawer = document.querySelector(SELECTORS.detailDrawer);
    if (existingDrawer) {
      const openOrderId = readOrderIdFromDrawer(existingDrawer);
      if (openOrderId && openOrderId !== orderId) {
        return { ok: false, retryable: true, message: "Đang chờ đóng đơn đang xem" };
      }
      if (openOrderId === orderId) {
        const saveResult = await waitAndCaptureDrawer(existingDrawer, orderId, SETTINGS.detailTimeoutMs);
        return saveResult.ok
          ? { ok: true }
          : { ok: false, retryable: true, message: saveResult.message };
      }
    }

    const row = findOrderRow(orderId);
    if (!row) return { ok: false, retryable: true, message: `Không còn thấy dòng ${orderId}` };

    const drawer = await openDrawerForOrder(row, orderId);
    if (!drawer) {
      return { ok: false, retryable: true, message: `Không mở được chi tiết ${orderId}` };
    }

    const saveResult = await waitAndCaptureDrawer(drawer, orderId, SETTINGS.detailTimeoutMs);
    await closeAutomationDrawer(drawer);
    return saveResult.ok
      ? { ok: true }
      : { ok: false, retryable: true, message: saveResult.message || `Thiếu thông tin của ${orderId}` };
  }

  async function openDrawerForOrder(row, orderId) {
    const cells = [...row.querySelectorAll("td.dui-table-cell")];
    const idCell = cells.find((cell) => normalizeText(cell.textContent).includes(orderId)) || cells[1];
    const candidates = [idCell, row].filter(Boolean);

    for (const target of candidates) {
      if (!enabled || !monitorMode || !row.isConnected) return null;
      try {
        target.scrollIntoView({ block: "center", inline: "nearest", behavior: "auto" });
        await sleep(80);
        dispatchHumanLikeClick(target);
      } catch (error) {
        console.warn("[Order Recorder/Grab] Lỗi click:", error);
      }
      const drawer = await waitForDrawer(orderId, SETTINGS.clickAttemptTimeoutMs);
      if (drawer) return drawer;
    }
    return null;
  }

  function dispatchHumanLikeClick(target) {
    if (!target) return;
    const rect = target.getBoundingClientRect();
    const init = {
      bubbles: true,
      cancelable: true,
      composed: true,
      view: window,
      button: 0,
      buttons: 1,
      clientX: rect.left + Math.max(1, rect.width / 2),
      clientY: rect.top + Math.max(1, rect.height / 2)
    };

    if (typeof PointerEvent === "function") {
      target.dispatchEvent(new PointerEvent("pointerover", { ...init, pointerId: 1, pointerType: "mouse", isPrimary: true }));
      target.dispatchEvent(new PointerEvent("pointerdown", { ...init, pointerId: 1, pointerType: "mouse", isPrimary: true }));
      target.dispatchEvent(new PointerEvent("pointerup", { ...init, pointerId: 1, pointerType: "mouse", isPrimary: true, buttons: 0 }));
    }

    target.dispatchEvent(new MouseEvent("mousedown", init));
    target.dispatchEvent(new MouseEvent("mouseup", { ...init, buttons: 0 }));
    target.click();
  }

  async function captureManualOpenOrder() {
    const drawer = document.querySelector(SELECTORS.detailDrawer);
    if (drawer) await captureDrawerOrder(drawer);
  }

  async function waitAndCaptureDrawer(drawer, orderId, timeoutMs) {
    const startedAt = Date.now();
    let lastMessage = "Đang chờ tên và số điện thoại";

    while (enabled && monitorMode && Date.now() - startedAt < timeoutMs) {
      if (!drawer.isConnected) return { ok: false, message: "Khung chi tiết đã đóng trước khi đọc xong" };

      const currentOrderId = readOrderIdFromDrawer(drawer);
      if (currentOrderId && currentOrderId !== orderId) {
        return { ok: false, message: "Grab đã chuyển sang một đơn khác" };
      }

      const result = await captureDrawerOrder(drawer);
      if (result.ok) return result;
      lastMessage = result.message || lastMessage;
      await sleep(200);
    }
    return { ok: false, message: lastMessage };
  }

  async function captureDrawerOrder(drawer) {
    const parsed = parseDrawerOrder(drawer);
    if (!parsed.orderId) return { ok: false, message: "Không tìm thấy mã đơn" };
    if (!parsed.customerName) return { ok: false, message: "Chưa thấy tên khách hàng" };
    if (!parsed.phone) return { ok: false, message: "Chưa thấy số điện thoại" };

    const signature = `${parsed.orderId}|${parsed.phone}`;
    if (signature === lastCapturedSignature) return { ok: true, duplicate: true };

    const response = await safeMessage({ type: "SAVE_ORDER", order: parsed });
    if (!response?.ok) return { ok: false, message: response?.error || "Không lưu được dữ liệu" };

    if (response.data?.saved || response.data?.duplicate) {
      lastCapturedSignature = signature;
      reportAutomationStatus(
        "saved",
        parsed.orderId,
        response.data?.duplicate ? `${parsed.orderId} đã tồn tại` : `Đã lưu ${parsed.orderId}`
      );
      return { ok: true, duplicate: Boolean(response.data?.duplicate) };
    }

    if (response.data?.reason === "recording_off") return { ok: false, message: "Extension đã tắt" };
    if (response.data?.reason === "not_monitor_tab") return { ok: false, message: "Tab này không phải tab giám sát" };
    return { ok: false, message: "Dữ liệu chưa được lưu" };
  }

  function parseDrawerOrder(drawer) {
    const orderId = readOrderIdFromDrawer(drawer);
    const phoneElement = drawer.querySelector(SELECTORS.customerPhone);
    const phone = normalizeVietnamPhone(phoneElement?.textContent);
    const customerName = findCustomerName(phoneElement, drawer);
    const fullOrderId = normalizeText(drawer.querySelector(SELECTORS.fullOrderId)?.textContent);

    return {
      platform: PLATFORM,
      orderId,
      fullOrderId,
      customerName,
      phone,
      recordedAt: new Date().toISOString()
    };
  }

  function findOrderRow(orderId) {
    return getVisibleOrderRows().find((row) => readOrderIdFromRow(row) === orderId) || null;
  }

  function readOrderIdFromRow(row) {
    if (!row) return "";
    const cells = [...row.querySelectorAll("td.dui-table-cell")];
    const preferred = normalizeText(cells[1]?.textContent);
    const preferredMatch = preferred.match(/GF-[A-Z0-9-]+/i);
    if (preferredMatch) return preferredMatch[0].toUpperCase();

    const match = normalizeText(row.textContent).match(/GF-[A-Z0-9-]+/i);
    return match ? match[0].toUpperCase() : "";
  }

  function readOrderIdFromDrawer(drawer) {
    const direct = normalizeText(drawer?.querySelector(SELECTORS.displayOrderId)?.textContent);
    const match = direct.match(/GF-[A-Z0-9-]+/i);
    return match ? match[0].toUpperCase() : direct;
  }

  async function waitForDrawer(orderId, timeoutMs) {
    const startedAt = Date.now();
    while (enabled && monitorMode && Date.now() - startedAt < timeoutMs) {
      const drawer = document.querySelector(SELECTORS.detailDrawer);
      if (drawer) {
        const drawerOrderId = readOrderIdFromDrawer(drawer);
        if (!drawerOrderId || drawerOrderId === orderId) return drawer;
      }
      await sleep(120);
    }
    return null;
  }

  async function closeAutomationDrawer(drawer) {
    if (!drawer?.isConnected) return;
    const closeButton = drawer.querySelector(SELECTORS.drawerClose);
    if (!closeButton) return;

    closeButton.click();
    const startedAt = Date.now();
    while (drawer.isConnected && Date.now() - startedAt < SETTINGS.drawerCloseTimeoutMs) {
      await sleep(100);
    }
  }

  function findCustomerName(phoneElement, drawer) {
    if (!phoneElement) return "";

    const directPrevious = normalizeText(phoneElement.previousElementSibling?.textContent);
    if (directPrevious && !/\d{8,}/.test(directPrevious)) return directPrevious;

    const customerCard = [...drawer.querySelectorAll(".dui-card")].find((card) => {
      const title = normalizeText(card.querySelector(".dui-card-head-title")?.textContent).toLowerCase();
      return title === "khách hàng" || title === "customer";
    });

    if (!customerCard) return "";
    const candidates = [...customerCard.querySelectorAll(".dui-card-body div")]
      .map((element) => normalizeText(element.textContent))
      .filter((text) => text && !/\d{8,}/.test(text) && text.toLowerCase() !== "lưu ý từ khách hàng");

    return candidates[0] || "";
  }

  function reportAutomationStatus(state, orderId, message) {
    safeMessage({
      type: "AUTOMATION_STATUS",
      status: {
        platform: PLATFORM,
        state,
        orderId: orderId || "",
        message: message || "",
        queueLength: orderQueue.length,
        updatedAt: new Date().toISOString()
      }
    });
  }

  function normalizeText(value) {
    return String(value || "").replace(/\s+/g, " ").trim();
  }

  function normalizeVietnamPhone(value) {
    let digits = String(value || "").replace(/\D/g, "");
    if (digits.startsWith("84") && digits.length >= 11) digits = `0${digits.slice(2)}`;
    if (!digits.startsWith("0") && digits.length === 9) digits = `0${digits}`;
    return digits;
  }

  function sleep(ms) {
    return new Promise((resolve) => setTimeout(resolve, ms));
  }

  async function safeMessage(message) {
    if (contextInvalidated || !hasRuntimeContext()) {
      handleInvalidatedContext();
      return null;
    }

    try {
      return await chrome.runtime.sendMessage(message);
    } catch (error) {
      if (isContextInvalidatedError(error) || !hasRuntimeContext()) {
        handleInvalidatedContext();
        return null;
      }
      console.warn("[Order Recorder/Grab] Không gửi được dữ liệu:", error);
      return null;
    }
  }

  function hasRuntimeContext() {
    try {
      return Boolean(globalThis.chrome?.runtime?.id);
    } catch {
      return false;
    }
  }

  function isContextInvalidatedError(error) {
    const message = String(error?.message || error || "").toLowerCase();
    return message.includes("extension context invalidated")
      || message.includes("context invalidated");
  }

  function handleInvalidatedContext() {
    if (contextInvalidated) return;
    contextInvalidated = true;
    destroyInstance("extension_context_invalidated");

    // Sau khi extension được Reload/cập nhật, script cũ trong trang không thể
    // kết nối lại. Reload chính trang là cách duy nhất để Chrome nạp context mới.
    if (recoveryReloadScheduled) return;
    recoveryReloadScheduled = true;

    let shouldReload = true;
    try {
      const key = "__ORDER_RECORDER_CONTEXT_RECOVERY_AT__";
      const now = Date.now();
      const previous = Number(sessionStorage.getItem(key) || 0);
      if (now - previous < 8000) shouldReload = false;
      else sessionStorage.setItem(key, String(now));
    } catch {
      // Nếu sessionStorage bị chặn, vẫn thử reload đúng một lần trong instance này.
    }

    if (shouldReload) {
      window.setTimeout(() => {
        try {
          location.reload();
        } catch {
          // Không thể khôi phục tự động; service worker watchdog sẽ xử lý sau.
        }
      }, 700);
    }
  }

  function destroyInstance(reason = "stopped") {
    if (instanceState.destroyed) return;
    instanceState.destroyed = true;
    instanceState.destroyReason = reason;

    removeMonitorUi();
    enabled = false;
    monitorMode = false;
    stopWatching();

    window.removeEventListener("pageshow", handleWake);
    window.removeEventListener("online", handleWake);
    window.removeEventListener("popstate", handleWake);
    window.removeEventListener("hashchange", handleWake);
    document.removeEventListener("visibilitychange", handleWake);

    try {
      chrome.storage.onChanged.removeListener(handleStorageChanged);
    } catch {
      // Context đã mất nên API Chrome có thể không còn dùng được.
    }

    if (runtimeMessageListener) {
      try {
        chrome.runtime.onMessage.removeListener(runtimeMessageListener);
      } catch {
        // Context đã mất nên API Chrome có thể không còn dùng được.
      }
      runtimeMessageListener = null;
    }
  }
})();
