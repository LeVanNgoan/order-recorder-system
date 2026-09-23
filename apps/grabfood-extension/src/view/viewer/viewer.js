const body = document.getElementById("tableBody");
const empty = document.getElementById("empty");
const count = document.getElementById("count");
const platformSummary = document.getElementById("platformSummary");
const maskButton = document.getElementById("maskButton");
const searchInput = document.getElementById("searchInput");
const clearSearch = document.getElementById("clearSearch");
const notice = document.getElementById("notice");

let orders = [];
let revealPhone = false;

load().catch((error) => showNotice(error.message, true));

maskButton.addEventListener("click", () => {
  revealPhone = !revealPhone;
  maskButton.textContent = revealPhone ? "Ẩn bớt số điện thoại" : "Hiện số đầy đủ";
  render();
});

searchInput.addEventListener("input", render);
clearSearch.addEventListener("click", () => {
  searchInput.value = "";
  searchInput.focus();
  render();
});

chrome.storage.onChanged.addListener((changes, areaName) => {
  if (areaName !== "local") return;
  if (Object.keys(changes).some((key) => key.startsWith("orders_"))) {
    load().catch(() => undefined);
  }
});

document.addEventListener("keydown", (event) => {
  if ((event.ctrlKey || event.metaKey) && event.key.toLowerCase() === "f") {
    event.preventDefault();
    searchInput.focus();
    searchInput.select();
  }
});

async function load() {
  const response = await chrome.runtime.sendMessage({ type: "GET_ORDERS_TODAY" });
  if (!response?.ok) throw new Error(response?.error || "Không đọc được dữ liệu.");
  orders = response.data || [];
  render();
}

function render() {
  const query = normalizeSearch(searchInput.value);
  const filtered = orders.filter((order) => matchesSearch(order, query));
  clearSearch.classList.toggle("visible", Boolean(searchInput.value));
  count.textContent = `${filtered.length} đơn`;
  platformSummary.textContent = "Dữ liệu Grab lưu cục bộ tối đa 7 ngày.";
  body.textContent = "";
  empty.hidden = filtered.length > 0;
  maskButton.disabled = filtered.length === 0;

  const sorted = [...filtered].sort((a, b) => new Date(a.receivedAt || a.recordedAt) - new Date(b.receivedAt || b.recordedAt));
  sorted.forEach((order, index) => {
    const row = document.createElement("tr");
    const values = [
      String(index + 1),
      String(order.orderId || "").toUpperCase(),
      revealPhone ? normalizeDisplayPhone(order.phone) : maskPhone(normalizeDisplayPhone(order.phone)),
      formatDateTime(order.receivedAt || order.recordedAt)
    ];
    values.forEach((value) => {
      const cell = document.createElement("td");
      cell.textContent = value;
      row.appendChild(cell);
    });
    body.appendChild(row);
  });
}

function normalizeDisplayPhone(value) {
  let digits = String(value || "").replace(/\D/g, "");
  if (digits.startsWith("84") && digits.length >= 11) digits = `0${digits.slice(2)}`;
  if (!digits.startsWith("0") && digits.length === 9) digits = `0${digits}`;
  return digits;
}
function matchesSearch(order, query) {
  const orderText = normalizeSearch(`${order.orderId || ""} ${order.fullOrderId || ""}`);
  const phoneDigits = normalizePhone(order.phone);
  const queryDigits = normalizePhone(query);

  return orderText.includes(query)
    || (queryDigits.length >= 3 && phoneDigits.includes(queryDigits));
}

function normalizeSearch(value) {
  return String(value || "").toLocaleLowerCase("vi-VN").replace(/\s+/g, " ").trim();
}

function normalizePhone(value) {
  return String(value || "").replace(/\D/g, "");
}

function maskPhone(phone) {
  const value = String(phone || "");
  if (value.length < 6) return "******";
  return `${value.slice(0, 3)}****${value.slice(-3)}`;
}

function formatDateTime(value) {
  if (!value) return "";
  const parts = new Intl.DateTimeFormat("en-GB", {
    timeZone: "Asia/Ho_Chi_Minh", year: "numeric", month: "2-digit", day: "2-digit",
    hour: "2-digit", minute: "2-digit", second: "2-digit", hour12: false
  }).formatToParts(new Date(value));
  const m = Object.fromEntries(parts.map((p) => [p.type, p.value]));
  return `${m.hour}:${m.minute}:${m.second} ${m.day}-${m.month}-${m.year}`;
}
function showNotice(message, isError = false) {
  notice.textContent = message || "";
  notice.style.color = isError ? "#b42318" : "#166534";
}
