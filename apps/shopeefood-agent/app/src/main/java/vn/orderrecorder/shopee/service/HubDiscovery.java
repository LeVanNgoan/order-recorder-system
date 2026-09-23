package vn.orderrecorder.shopee;

import android.content.Context;
import android.os.Build;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.HttpURLConnection;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.InterfaceAddress;
import java.net.NetworkInterface;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.CompletionService;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * Finds Order Recorder Hub after the PC LAN address changes.
 *
 * v2.0.7 recovery path:
 *  1) UDP broadcast ORDER_RECORDER_DISCOVER_V1 -> Hub UDP/17892.
 *  2) Build candidate URL from the UDP packet SOURCE address (not from an
 *     advertised IP, which is safer on PCs with multiple adapters).
 *  3) Authenticate the candidate with the existing Hub API key.
 *  4) Only after authentication succeeds, persist the new Hub URL.
 *  5) If UDP broadcast is blocked, scan the SUNMI's private /24 subnet for
 *     GET /api/discovery, then authenticate in exactly the same way.
 *
 * This class is called only from HubSync's background executor. It never runs
 * on the Accessibility/notification capture hot-path.
 */
public final class HubDiscovery {
    private static final int HUB_PORT = 17891;
    private static final int DISCOVERY_PORT = 17892;
    private static final byte[] MAGIC = "ORDER_RECORDER_DISCOVER_V1".getBytes(StandardCharsets.UTF_8);
    private static final String SERVICE = "order-recorder-hub";

    private HubDiscovery() {}

    public static Result discover(Context c, boolean allowSubnetFallback) {
        Context app = c.getApplicationContext();
        String key = HubPrefs.getKey(app);
        if (key.isEmpty()) return Result.fail("Chưa có API key để xác thực Hub");

        AppLog.add(app, "HUB_DISCOVERY start · udp=true · subnetFallback=" + allowSubnetFallback);
        Result udp = discoverUdp(app, key);
        if (udp.ok) return udp;

        if (!allowSubnetFallback) {
            AppLog.add(app, "HUB_DISCOVERY stop · UDP không tìm thấy Hub");
            return udp;
        }

        AppLog.add(app, "HUB_DISCOVERY UDP miss · bắt đầu fallback subnet");
        Result scan = discoverSubnet(app, key);
        if (scan.ok) return scan;

        String err = !scan.error.isEmpty() ? scan.error : udp.error;
        AppLog.add(app, "HUB_DISCOVERY failed · " + err);
        return Result.fail(err.isEmpty() ? "Không tìm thấy Hub trong mạng nội bộ" : err);
    }

    private static Result discoverUdp(Context c, String key) {
        DatagramSocket socket = null;
        try {
            socket = new DatagramSocket(null);
            socket.setReuseAddress(true);
            socket.setBroadcast(true);
            socket.bind(new InetSocketAddress(0));

            List<InetAddress> targets = broadcastTargets();
            for (InetAddress target : targets) {
                try {
                    DatagramPacket p = new DatagramPacket(MAGIC, MAGIC.length, target, DISCOVERY_PORT);
                    socket.send(p);
                } catch (Exception ignored) {}
            }

            long deadline = System.currentTimeMillis() + 1600L;
            byte[] buf = new byte[4096];
            while (System.currentTimeMillis() < deadline) {
                int remain = (int) Math.max(80L, deadline - System.currentTimeMillis());
                socket.setSoTimeout(Math.min(remain, 450));
                try {
                    DatagramPacket reply = new DatagramPacket(buf, buf.length);
                    socket.receive(reply);
                    String body = new String(reply.getData(), reply.getOffset(), reply.getLength(), StandardCharsets.UTF_8);
                    JSONObject j = new JSONObject(body);
                    if (!SERVICE.equals(j.optString("service", ""))) continue;
                    if (j.optInt("protocol", 0) != 1) continue;
                    int port = validPort(j.optInt("port", HUB_PORT));
                    String host = reply.getAddress().getHostAddress();
                    if (host == null || host.isEmpty()) continue;
                    String base = "http://" + host + ":" + port;
                    String hubId = j.optString("hub_id", "");
                    AppLog.add(c, "HUB_DISCOVERY UDP candidate · " + base + " · hubId=" + shortId(hubId));
                    if (verifyCandidate(c, base, key)) {
                        accept(c, base, hubId, "udp");
                        return Result.ok(base, hubId, "udp");
                    }
                    AppLog.add(c, "HUB_DISCOVERY UDP reject · auth failed · " + base);
                } catch (SocketTimeoutException ignored) {
                    // Keep waiting until the overall deadline.
                }
            }
        } catch (Exception e) {
            return Result.fail("UDP: " + shortError(e));
        } finally {
            if (socket != null) socket.close();
        }
        return Result.fail("UDP không nhận được Hub hợp lệ");
    }

    private static List<InetAddress> broadcastTargets() {
        List<InetAddress> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        tryAdd(out, seen, "255.255.255.255");
        try {
            Enumeration<NetworkInterface> all = NetworkInterface.getNetworkInterfaces();
            if (all != null) {
                while (all.hasMoreElements()) {
                    NetworkInterface ni = all.nextElement();
                    try {
                        if (!ni.isUp() || ni.isLoopback()) continue;
                    } catch (Exception ignored) {}
                    for (InterfaceAddress ia : ni.getInterfaceAddresses()) {
                        InetAddress b = ia.getBroadcast();
                        if (b != null && b instanceof Inet4Address) {
                            String host = b.getHostAddress();
                            if (host != null && seen.add(host)) out.add(b);
                        }
                    }
                }
            }
        } catch (Exception ignored) {}
        return out;
    }

    private static void tryAdd(List<InetAddress> out, Set<String> seen, String host) {
        try {
            InetAddress a = InetAddress.getByName(host);
            String h = a.getHostAddress();
            if (h != null && seen.add(h)) out.add(a);
        } catch (Exception ignored) {}
    }

    private static Result discoverSubnet(Context c, String key) {
        List<String> prefixes = private24Prefixes();
        if (prefixes.isEmpty()) return Result.fail("Không xác định được subnet LAN của SUNMI");

        // Avoid scanning more than two adapters. On SUNMI normally there is one Wi-Fi /24.
        if (prefixes.size() > 2) prefixes = new ArrayList<>(prefixes.subList(0, 2));
        ExecutorService pool = Executors.newFixedThreadPool(28, r -> {
            Thread t = new Thread(r, "hub-discovery-scan");
            t.setDaemon(true);
            return t;
        });
        CompletionService<Candidate> cs = new ExecutorCompletionService<>(pool);
        List<Future<Candidate>> futures = new ArrayList<>();
        int submitted = 0;
        try {
            for (String prefix : prefixes) {
                for (int i = 1; i <= 254; i++) {
                    final String host = prefix + "." + i;
                    futures.add(cs.submit(() -> probeDiscoveryEndpoint(host)));
                    submitted++;
                }
            }

            long deadline = System.currentTimeMillis() + 3200L;
            for (int i = 0; i < submitted && System.currentTimeMillis() < deadline; i++) {
                long remain = deadline - System.currentTimeMillis();
                Future<Candidate> f = cs.poll(Math.max(1L, remain), TimeUnit.MILLISECONDS);
                if (f == null) break;
                Candidate candidate;
                try { candidate = f.get(); } catch (Exception e) { continue; }
                if (candidate == null) continue;
                AppLog.add(c, "HUB_DISCOVERY subnet candidate · " + candidate.base + " · hubId=" + shortId(candidate.hubId));
                if (verifyCandidate(c, candidate.base, key)) {
                    accept(c, candidate.base, candidate.hubId, "subnet");
                    return Result.ok(candidate.base, candidate.hubId, "subnet");
                }
            }
        } catch (Exception e) {
            return Result.fail("Subnet: " + shortError(e));
        } finally {
            for (Future<Candidate> f : futures) f.cancel(true);
            pool.shutdownNow();
        }
        return Result.fail("Không tìm thấy Hub bằng fallback subnet");
    }

    private static Candidate probeDiscoveryEndpoint(String host) {
        HttpURLConnection x = null;
        try {
            x = (HttpURLConnection) new URL("http://" + host + ":" + HUB_PORT + "/api/discovery").openConnection();
            x.setConnectTimeout(220);
            x.setReadTimeout(350);
            x.setRequestMethod("GET");
            x.setUseCaches(false);
            int code = x.getResponseCode();
            if (code < 200 || code >= 300) return null;
            String body = readSmall(x.getInputStream(), 2048);
            JSONObject j = new JSONObject(body);
            if (!SERVICE.equals(j.optString("service", "")) || j.optInt("protocol", 0) != 1) return null;
            int port = validPort(j.optInt("port", HUB_PORT));
            return new Candidate("http://" + host + ":" + port, j.optString("hub_id", ""));
        } catch (Exception ignored) {
            return null;
        } finally {
            if (x != null) x.disconnect();
        }
    }

    private static List<String> private24Prefixes() {
        Set<String> out = new HashSet<>();
        try {
            Enumeration<NetworkInterface> all = NetworkInterface.getNetworkInterfaces();
            if (all == null) return new ArrayList<>();
            while (all.hasMoreElements()) {
                NetworkInterface ni = all.nextElement();
                try {
                    if (!ni.isUp() || ni.isLoopback()) continue;
                } catch (Exception ignored) {}
                Enumeration<InetAddress> addrs = ni.getInetAddresses();
                while (addrs.hasMoreElements()) {
                    InetAddress a = addrs.nextElement();
                    if (!(a instanceof Inet4Address) || a.isLoopbackAddress()) continue;
                    byte[] b = a.getAddress();
                    int p0 = b[0] & 255, p1 = b[1] & 255, p2 = b[2] & 255;
                    if (!isPrivateLan(p0, p1)) continue;
                    out.add(String.format(Locale.US, "%d.%d.%d", p0, p1, p2));
                }
            }
        } catch (Exception ignored) {}
        List<String> list = new ArrayList<>(out);
        Collections.sort(list);
        return list;
    }

    private static boolean isPrivateLan(int a, int b) {
        if (a == 10) return true;
        if (a == 172 && b >= 16 && b <= 31) return true;
        return a == 192 && b == 168;
    }

    private static boolean verifyCandidate(Context c, String base, String key) {
        HttpURLConnection x = null;
        try {
            x = (HttpURLConnection) new URL(base + "/api/heartbeat").openConnection();
            x.setConnectTimeout(900);
            x.setReadTimeout(1200);
            x.setRequestMethod("POST");
            x.setDoOutput(true);
            x.setUseCaches(false);
            x.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            x.setRequestProperty("X-Order-Recorder-Key", key);
            JSONObject o = new JSONObject();
            o.put("source", "shopeefood-sunmi");
            o.put("platform", "shopeefood");
            o.put("deviceName", Build.MANUFACTURER + " " + Build.MODEL);
            o.put("status", AppPrefs.isEnabled(c) ? (AppPrefs.isAutoEnabled(c) ? "ready" : "auto_off") : "recording_off");
            o.put("pending", HubSync.pendingCount(c));
            o.put("version", "2.0.10");
            byte[] data = o.toString().getBytes(StandardCharsets.UTF_8);
            x.setFixedLengthStreamingMode(data.length);
            try (OutputStream os = x.getOutputStream()) { os.write(data); }
            int code = x.getResponseCode();
            try (InputStream is = code >= 200 && code < 300 ? x.getInputStream() : x.getErrorStream()) {
                if (is != null) while (is.read() != -1) {}
            }
            return code >= 200 && code < 300;
        } catch (Exception e) {
            return false;
        } finally {
            if (x != null) x.disconnect();
        }
    }

    private static void accept(Context c, String base, String hubId, String method) {
        String old = HubPrefs.getUrl(c);
        HubPrefs.saveDiscoveredUrl(c, base, hubId);
        HubPrefs.markDiscoveryMethod(c, method);
        HubPrefs.markOk(c);
        AppLog.add(c, "HUB_DISCOVERY success · method=" + method + " · old=" + safeUrl(old) + " · new=" + safeUrl(base) + " · hubId=" + shortId(hubId));
    }

    private static int validPort(int value) {
        return value > 0 && value <= 65535 ? value : HUB_PORT;
    }

    private static String readSmall(InputStream in, int max) throws Exception {
        if (in == null) return "";
        StringBuilder sb = new StringBuilder();
        try (BufferedReader br = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            char[] buf = new char[512];
            int n;
            while ((n = br.read(buf)) > 0 && sb.length() < max) {
                sb.append(buf, 0, Math.min(n, max - sb.length()));
            }
        }
        return sb.toString();
    }

    private static String shortId(String s) {
        if (s == null || s.isEmpty()) return "—";
        return s.length() <= 8 ? s : s.substring(0, 8);
    }

    private static String safeUrl(String s) {
        if (s == null || s.isEmpty()) return "—";
        return s.length() <= 80 ? s : s.substring(0, 80);
    }

    private static String shortError(Exception e) {
        String s = e.getMessage();
        if (s == null || s.trim().isEmpty()) s = e.getClass().getSimpleName();
        return s.length() > 100 ? s.substring(0, 100) : s;
    }

    private static final class Candidate {
        final String base;
        final String hubId;
        Candidate(String base, String hubId) { this.base = base; this.hubId = hubId == null ? "" : hubId; }
    }

    public static final class Result {
        public final boolean ok;
        public final String url;
        public final String hubId;
        public final String method;
        public final String error;
        private Result(boolean ok, String url, String hubId, String method, String error) {
            this.ok = ok;
            this.url = url == null ? "" : url;
            this.hubId = hubId == null ? "" : hubId;
            this.method = method == null ? "" : method;
            this.error = error == null ? "" : error;
        }
        static Result ok(String url, String hubId, String method) { return new Result(true, url, hubId, method, ""); }
        static Result fail(String error) { return new Result(false, "", "", "", error); }
    }
}
