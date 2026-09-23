(() => {
  "use strict";

  // Tránh chạy trùng khi service worker chủ động chèn lại script vào tab đã mở.
  if (globalThis.__GRAB_ORDER_RECORDER_V031__) return;
  globalThis.__GRAB_ORDER_RECORDER_V031__ = true;

  const SELECTORS = Object.freeze({
    orderRows: "tr.dui-table-row.dui-table-row-level-0",
    detailDrawer: ".dui-drawer.dui-drawer-open",
    drawerClose: "button.dui-drawer-close",
    displayOrderId: '[data-testid="displayOfDisplayID"]',
    fullOrderId: '[data-testid="displayOfOrderID"]',
    customerPhone: '[data-testid="eater-number"]'
  });

  const SETTINGS = Object.freeze({
    scanDebounceMs: 300,
    periodicScanMs: 1200,
    detailTimeoutMs: 12000,
    clickAttemptTimeoutMs: 3500,
    drawerCloseTimeoutMs: 3500,
    betweenOrdersMs: 650,
    retryDelayMs: 2200,
    maxRetries: 5
  });

  const seenRows = new Set();
  const queuedOrders = new Set();
  const retryCounts = new Map();
  const orderQueue = [];

  let enabled = false;
  let observer = null;
  let scanTimer = null;
  let periodicTimer = null;
  let processingQueue = false;
  let automationOrderId = "";
  let lastCapturedSignature = "";

  init().catch((error) => console.error("[Grab Order Recorder]", error));

  async function init() {
    chrome.runtime.onMessage.addListener((message, sender, sendResponse) => {
      if (message?.type === "PING_RECORDER") {
        sendResponse({
          ok: true,
          enabled,
          connected: true,
          url: location.href,
          rows: document.querySelectorAll(SELECTORS.orderRows).length
        });
        return false;
      }

      if (message?.type === "FORCE_SCAN") {
        scanPage();
        sendResponse({ ok: true });
        return false;
      }

      return false;
    });

    const state = await chrome.storage.local.get("recordingEnabled");
    enabled = Boolean(state.recordingEnabled);
    if (enabled) startWatching();

    chrome.storage.onChanged.addListener((changes, areaName) => {
      if (areaName !== "local" || !changes.recordingEnabled) return;
      enabled = Boolean(changes.recordingEnabled.newValue);
      if (enabled) startWatching();
      else stopWatching();
    });

    console.info("[Grab Order Recorder] Content script v0.3.1 đã kết nối.");
  }

  function startWatching() {
    if (!observer) {
      observer = new MutationObserver(scheduleScan);
      observer.observe(document.documentElement, {
        childList: true,
        subtree: true,
        characterData: true
      });
    }

    if (!periodicTimer) {
      periodicTimer = setInterval(scanPage, SETTINGS.periodicScanMs);
    }

    scanPage();
    reportAutomationStatus("idle", "", "Đã kết nối · Đang chờ đơn mới");
    console.info("[Grab Order Recorder] Đã bật tự động ghi nhận Grab Merchant.");
  }

  function stopWatching() {
    observer?.disconnect();
    observer = null;

    if (scanTimer) clearTimeout(scanTimer);
    scanTimer = null;

    if (periodicTimer) clearInterval(periodicTimer);
    periodicTimer = null;

    orderQueue.length = 0;
    queuedOrders.clear();
    retryCounts.clear();
    processingQueue = false;
    automationOrderId = "";
    lastCapturedSignature = "";

    reportAutomationStatus("off", "", "Đã tắt ghi nhận");
    console.info("[Grab Order Recorder] Đã tắt theo dõi Grab Merchant.");
  }

  function scheduleScan() {
    if (!enabled) return;
    if (scanTimer) clearTimeout(scanTimer);
    scanTimer = setTimeout(() => {
      scanTimer = null;
      scanPage();
    }, SETTINGS.scanDebounceMs);
  }

  function scanPage() {
    if (!enabled) return;
    detectVisibleRows();

    // Vẫn ghi nhận khi nhân viên tự mở chi tiết đơn.
    if (!automationOrderId) {
      captureManualOpenOrder().catch((error) => {
        console.warn("[Grab Order Recorder] Không đọc được đơn đang mở:", error);
      });
    }
  }

  function detectVisibleRows() {
    const rows = document.querySelectorAll(SELECTORS.orderRows);
    for (const row of rows) {
      const orderId = readOrderIdFromRow(row);
      if (!orderId || !/^GF-/i.test(orderId)) continue;
      if (seenRows.has(orderId) || queuedOrders.has(orderId) || automationOrderId === orderId) continue;

      seenRows.add(orderId);
      safeMessage({
        type: "ORDER_DETECTED",
        order: {
          platform: "GrabFood",
          orderId,
          detectedAt: new Date().toISOString()
        }
      }).then((response) => {
        if (!enabled) return;
        if (!response?.ok) {
          // Cho phép lần quét sau thử lại nếu service worker vừa khởi động lại.
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
      console.error("[Grab Order Recorder] Lỗi hàng đợi tự động:", error);
      processingQueue = false;
      automationOrderId = "";
      reportAutomationStatus("error", orderId, error.message || "Lỗi tự động ghi nhận");
    });
  }

  async function processOrderQueue() {
    if (processingQueue || !enabled) return;
    processingQueue = true;

    try {
      while (enabled && orderQueue.length) {
        const orderId = orderQueue.shift();
        queuedOrders.delete(orderId);
        automationOrderId = orderId;
        reportAutomationStatus("processing", orderId, `Đang tự mở ${orderId}`);

        const result = await autoCaptureOrder(orderId);
        if (!result.ok && enabled) {
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
            // Không khóa vĩnh viễn dòng đơn. Lần quét sau có thể thử lại.
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
      if (enabled) reportAutomationStatus("idle", "", "Đã kết nối · Đang chờ đơn mới");
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
    if (!row) {
      return { ok: false, retryable: true, message: `Không còn thấy dòng ${orderId}` };
    }

    const drawer = await openDrawerForOrder(row, orderId);
    if (!drawer) {
      return {
        ok: false,
        retryable: true,
        message: `Không mở được chi tiết ${orderId}`
      };
    }

    const saveResult = await waitAndCaptureDrawer(drawer, orderId, SETTINGS.detailTimeoutMs);
    await closeAutomationDrawer(drawer);

    if (!saveResult.ok) {
      return {
        ok: false,
        retryable: true,
        message: saveResult.message || `Thiếu thông tin của ${orderId}`
      };
    }

    return { ok: true };
  }

  async function openDrawerForOrder(row, orderId) {
    const cells = [...row.querySelectorAll("td.dui-table-cell")];
    const candidates = [cells[1], row].filter(Boolean);

    for (const target of candidates) {
      if (!enabled || !row.isConnected) return null;
      try {
        target.scrollIntoView({ block: "center", inline: "nearest", behavior: "auto" });
        await sleep(80);
        dispatchHumanLikeClick(target);
      } catch (error) {
        console.warn("[Grab Order Recorder] Lỗi click:", error);
      }

      const drawer = await waitForDrawer(orderId, SETTINGS.clickAttemptTimeoutMs);
      if (drawer) return drawer;
    }

    return null;
  }

  function dispatchHumanLikeClick(target) {
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
    if (!drawer) return;
    await captureDrawerOrder(drawer);
  }

  async function waitAndCaptureDrawer(drawer, orderId, timeoutMs) {
    const startedAt = Date.now();
    let lastMessage = "Đang chờ tên và số điện thoại";

    while (enabled && Date.now() - startedAt < timeoutMs) {
      if (!drawer.isConnected) {
        return { ok: false, message: "Khung chi tiết đã đóng trước khi đọc xong" };
      }

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

    if (response.data?.reason === "recording_off") {
      return { ok: false, message: "Extension đã tắt" };
    }

    return { ok: false, message: "Dữ liệu chưa được lưu" };
  }

  function parseDrawerOrder(drawer) {
    const orderId = readOrderIdFromDrawer(drawer);
    const phoneElement = drawer.querySelector(SELECTORS.customerPhone);
    const phone = normalizeVietnamPhone(phoneElement?.textContent);
    const customerName = findCustomerName(phoneElement, drawer);
    const fullOrderId = normalizeText(drawer.querySelector(SELECTORS.fullOrderId)?.textContent);

    return {
      platform: "GrabFood",
      orderId,
      fullOrderId,
      customerName,
      phone,
      recordedAt: new Date().toISOString()
    };
  }

  function findOrderRow(orderId) {
    return [...document.querySelectorAll(SELECTORS.orderRows)].find(
      (row) => readOrderIdFromRow(row) === orderId
    ) || null;
  }

  function readOrderIdFromRow(row) {
    const cells = row?.querySelectorAll("td.dui-table-cell");
    return normalizeText(cells?.[1]?.textContent);
  }

  function readOrderIdFromDrawer(drawer) {
    return normalizeText(drawer?.querySelector(SELECTORS.displayOrderId)?.textContent);
  }

  async function waitForDrawer(orderId, timeoutMs) {
    const startedAt = Date.now();
    while (enabled && Date.now() - startedAt < timeoutMs) {
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
    if (directPrevious) return directPrevious;

    const customerCard = [...drawer.querySelectorAll(".dui-card")].find((card) => {
      const title = normalizeText(card.querySelector(".dui-card-head-title")?.textContent);
      return title.toLowerCase() === "khách hàng";
    });

    if (!customerCard) return "";
    const candidates = [...customerCard.querySelectorAll(".dui-card-body div")]
      .map((element) => normalizeText(element.textContent))
      .filter((text) => text && !text.includes("📞") && text !== "Lưu ý từ khách hàng");

    return candidates[0] || "";
  }

  function reportAutomationStatus(state, orderId, message) {
    safeMessage({
      type: "AUTOMATION_STATUS",
      status: {
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
    try {
      return await chrome.runtime.sendMessage(message);
    } catch (error) {
      console.warn("[Grab Order Recorder] Không gửi được dữ liệu:", error);
      return null;
    }
  }
})();
