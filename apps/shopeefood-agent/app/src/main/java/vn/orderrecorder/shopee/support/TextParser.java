package vn.orderrecorder.shopee;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class TextParser {
    private static final Pattern NOTIFICATION = Pattern.compile("(?:đơn\\s+hàng\\s+mới|don\\s+hang\\s+moi|đơn\\s+mới|don\\s+moi)\\s*[:\\-]?\\s*#?\\s*(\\d{1,8})", Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    private static final Pattern HASH_ID = Pattern.compile("#\\s*(\\d{1,8})");
    private static final Pattern DISPLAY = Pattern.compile("\\b(\\d{1,8})\\s*-\\s*#(\\d{1,10})\\b");
    private static final Pattern FULL = Pattern.compile("\\b\\d{4,8}-\\d{8,15}\\b");
    private static final Pattern PHONE = Pattern.compile("(?<!\\d)(?:\\+?84|0)[ .-]?[35789](?:[ .-]?\\d){8}(?!\\d)");
    private TextParser() {}

    public static String normalizeShortId(String raw) {
        String digits = raw == null ? "" : raw.replaceAll("\\D", "");
        if (digits.isEmpty()) return "";
        return digits.replaceFirst("^0+(?!$)", "");
    }

    public static String notificationId(String text) {
        if (text == null) return "";
        Matcher m = NOTIFICATION.matcher(text);
        if (m.find()) return normalizeShortId(m.group(1));
        // Fallback bảo thủ cho OEM tách câu: chỉ lấy #ID khi nội dung vẫn nói rõ đây là "đơn ... mới".
        String low = text.toLowerCase();
        boolean newOrder = (low.contains("đơn") && low.contains("mới")) || (low.contains("don") && low.contains("moi"));
        if (!newOrder) return "";
        m = HASH_ID.matcher(text);
        return m.find() ? normalizeShortId(m.group(1)) : "";
    }

    public static Detail detail(String text) {
        Detail d = new Detail();
        if (text == null) return d;
        Matcher m = DISPLAY.matcher(text);
        if (m.find()) {
            d.shortId = normalizeShortId(m.group(1));
            d.displayCode = m.group(2);
            d.display = m.group(0);
        }
        m = FULL.matcher(text);
        if (m.find()) d.full = m.group(0);
        return d;
    }

    public static String displayCode(String displayOrderId) {
        if (displayOrderId == null) return "";
        Matcher m = DISPLAY.matcher(displayOrderId);
        if (m.find()) return m.group(2);
        Matcher any = Pattern.compile("#\\s*(\\d{3,10})").matcher(displayOrderId);
        String last = "";
        while (any.find()) last = any.group(1);
        return last;
    }

    public static String businessOrderCode(String displayOrderId) {
        String code = displayCode(displayOrderId);
        return code.isEmpty() ? "" : "SPF-" + code;
    }

    public static Set<String> phoneCandidates(String text) {
        LinkedHashSet<String> out = new LinkedHashSet<>();
        if (text == null) return out;
        Matcher m = PHONE.matcher(text);
        while (m.find()) {
            String s = normalizePhone(m.group());
            if (!s.isEmpty()) out.add(s);
        }
        return out;
    }

    public static String singlePhone(String text) {
        Set<String> all = phoneCandidates(text);
        return all.size() == 1 ? all.iterator().next() : "";
    }

    /** Luôn trả SĐT Việt Nam dạng 0xxxxxxxxx. */
    public static String normalizePhone(String raw) {
        if (raw == null) return "";
        String digits = raw.replaceAll("\\D", "");
        if (digits.startsWith("84") && digits.length() == 11) digits = "0" + digits.substring(2);
        if (digits.length() == 9 && "35789".indexOf(digits.charAt(0)) >= 0) digits = "0" + digits;
        if (digits.length() == 10 && digits.charAt(0) == '0' && "35789".indexOf(digits.charAt(1)) >= 0) return digits;
        return "";
    }

    public static final class Detail {
        public String shortId = "", display = "", displayCode = "", full = "";
        public boolean valid() { return !shortId.isEmpty() || !display.isEmpty() || !full.isEmpty(); }
        public String businessCode() { return displayCode.isEmpty() ? "" : "SPF-" + displayCode; }
    }
}
