const elements = {
  connectionText: document.getElementById("connectionText"),
  statusDot: document.getElementById("statusDot"),
  statusText: document.getElementById("statusText"),
  toggleButton: document.getElementById("toggleButton"),
  historyDate: document.getElementById("historyDate"),
  exportState: document.getElementById("exportState"),
  exportSelectedButton: document.getElementById("exportSelectedButton"),
  viewButton: document.getElementById("viewButton"),
  todayCount: document.getElementById("todayCount"),
  hubStatus: document.getElementById("hubStatus"),
  hubPending: document.getElementById("hubPending"),
  hubSyncButton: document.getElementById("hubSyncButton"),
  hubForceResyncButton: document.getElementById("hubForceResyncButton"),
  hubOpenButton: document.getElementById("hubOpenButton"),
  notice: document.getElementById("notice")
};

let dashboard = null;
let refreshTimer = null;
let pollingTimer = null;

init().catch((error) => showNotice(error.message, true));

async function init() {
  elements.toggleButton.addEventListener("click", toggleRecording);
  elements.exportSelectedButton.addEventListener("click", exportSelectedDate);
  elements.historyDate.addEventListener("change", renderExportState);
  elements.viewButton.addEventListener("click", () => {
    chrome.tabs.create({ url: chrome.runtime.getURL("src/view/viewer/viewer.html") });
  });
  elements.hubSyncButton.addEventListener("click", syncHubNow);
  elements.hubForceResyncButton.addEventListener("click", forceResyncHub7Days);
  elements.hubOpenButton.addEventListener("click", () => {
    chrome.runtime.sendMessage({ type: "OPEN_HUB" }).catch(() => undefined);
  });

  chrome.storage.onChanged.addListener(scheduleRefresh);
  await refresh();
  pollingTimer = setInterval(() => refresh().catch(() => undefined), 3000);
  window.addEventListener("unload", () => clearInterval(pollingTimer));
}

async function refresh() {
  const response = await chrome.runtime.sendMessage({ type: "GET_DASHBOARD" });
  if (!response?.ok) throw new Error(response?.error || "Không đọc được trạng thái extension.");
  dashboard = response.data;
  render();
}

function scheduleRefresh(changes, areaName) {
  if (areaName !== "local") return;
  if (refreshTimer) clearTimeout(refreshTimer);
  refreshTimer = setTimeout(() => refresh().catch(() => undefined), 150);
}

function render() {
  const enabled = Boolean(dashboard.recordingEnabled);
  const grab = dashboard.platforms?.grabfood || {};

  elements.toggleButton.textContent = enabled ? "Tắt ghi nhận" : "Bật ghi nhận";
  elements.toggleButton.className = `button button-primary ${enabled ? "stop" : ""}`;
  elements.todayCount.textContent = String(dashboard.count || 0);
  elements.viewButton.disabled = !dashboard.count;

  let label = "Đang tắt";
  let dotClass = "off";
  if (enabled && grab.connected && grab.state === "ready") {
    label = "Đang ghi nhận";
    dotClass = "on";
  } else if (enabled && ["login_required", "error"].includes(grab.state)) {
    label = grab.state === "login_required" ? "Cần đăng nhập Grab" : "Grab đang lỗi";
    dotClass = "error";
  } else if (enabled) {
    label = "Đang kết nối";
    dotClass = "warning";
  }

  elements.statusText.textContent = label;
  elements.statusDot.className = `status-dot ${dotClass}`;

  const hub = dashboard.hub || {};
  elements.hubStatus.textContent = hub.message || (hub.online ? "Hub online" : "Hub offline");
  elements.hubStatus.className = hub.online ? "hub-online" : "hub-offline";
  elements.hubPending.textContent = String(hub.pending || 0);
  elements.hubPending.title = `${hub.pending || 0} đơn chờ đồng bộ`;
  renderHistory();
}

async function toggleRecording() {
  setBusy(true);
  try {
    const response = await chrome.runtime.sendMessage({
      type: "SET_RECORDING",
      enabled: !dashboard.recordingEnabled
    });
    if (!response?.ok) throw new Error(response?.error || "Không đổi được trạng thái.");
    dashboard = response.data;
    render();
    showNotice(dashboard.recordingEnabled ? "Đã bật ghi nhận." : "Đã tắt ghi nhận.");
  } catch (error) {
    showNotice(error.message, true);
  } finally {
    setBusy(false);
  }
}

async function exportSelectedDate() {
  const dateKey = elements.historyDate.value;
  if (!dateKey) return showNotice("Chưa có dữ liệu để xuất.", true);

  setBusy(true);
  try {
    const response = await chrome.runtime.sendMessage({ type: "EXPORT_DATE", dateKey });
    if (!response?.ok) throw new Error(response?.error || "Không xuất được Excel.");
    showNotice(`Đã tải ${response.data.filename}`);
    await refresh();
    elements.historyDate.value = dateKey;
    renderExportState();
  } catch (error) {
    showNotice(error.message, true);
  } finally {
    setBusy(false);
  }
}

async function syncHubNow() {
  setBusy(true);
  try {
    const response = await chrome.runtime.sendMessage({ type: "HUB_SYNC_NOW" });
    if (!response?.ok) throw new Error(response?.error || "Không đồng bộ được Hub.");
    showNotice(response.data?.message || "Đã kiểm tra Hub.");
    await refresh();
  } catch (error) {
    showNotice(error.message, true);
  } finally {
    setBusy(false);
  }
}

async function forceResyncHub7Days() {
  const accepted = window.confirm(
    "Đồng bộ lại toàn bộ dữ liệu Grab còn lưu trong 7 ngày lên Hub?\n\n" +
    "Hub sẽ tự chống trùng. Dữ liệu local trong extension không bị xóa."
  );
  if (!accepted) return;

  setBusy(true);
  try {
    const response = await chrome.runtime.sendMessage({ type: "HUB_FORCE_RESYNC_7D" });
    if (!response?.ok) throw new Error(response?.error || "Không đồng bộ lại được dữ liệu cũ.");
    showNotice(response.data?.message || "Đã đồng bộ lại dữ liệu cũ.");
    await refresh();
  } catch (error) {
    showNotice(error.message, true);
  } finally {
    setBusy(false);
  }
}

function renderHistory() {
  const previousValue = elements.historyDate.value;
  const history = Array.isArray(dashboard.history) ? dashboard.history : [];
  elements.historyDate.textContent = "";

  if (!history.length) {
    const option = document.createElement("option");
    option.value = "";
    option.textContent = "Chưa có dữ liệu";
    elements.historyDate.appendChild(option);
    elements.historyDate.disabled = true;
    elements.exportSelectedButton.disabled = true;
    elements.exportState.textContent = "";
    return;
  }

  history.forEach((entry) => {
    const option = document.createElement("option");
    option.value = entry.dateKey;
    option.textContent = `${formatDateLabel(entry.dateKey)} · ${entry.count} đơn`;
    elements.historyDate.appendChild(option);
  });

  const stillExists = history.some((entry) => entry.dateKey === previousValue);
  elements.historyDate.value = stillExists ? previousValue : history[0].dateKey;
  elements.historyDate.disabled = false;
  elements.exportSelectedButton.disabled = false;
  renderExportState();
}

function renderExportState() {
  const history = Array.isArray(dashboard?.history) ? dashboard.history : [];
  const selected = history.find((entry) => entry.dateKey === elements.historyDate.value);
  if (!selected) {
    elements.exportState.textContent = "";
    return;
  }
  elements.exportState.textContent = selected.exportPending
    ? "Có dữ liệu mới"
    : selected.exportedAt
      ? "Đã xuất"
      : "Chưa xuất";
}

function setBusy(busy) {
  for (const button of document.querySelectorAll("button")) button.disabled = busy;
  if (!busy && dashboard) render();
}

function showNotice(message, isError = false) {
  elements.notice.textContent = message || "";
  elements.notice.className = `notice ${isError ? "error" : ""}`;
}

function formatDateLabel(dateKey) {
  const match = /^(\d{4})-(\d{2})-(\d{2})$/.exec(dateKey || "");
  return match ? `${match[3]}/${match[2]}/${match[1]}` : dateKey || "";
}
