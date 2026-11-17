package app.control.launcher;

/**
 * The payloads the app-share screen (v1.25) hands to the other
 * phone: the Wi-Fi join QR, the download URL, the download page,
 * and the human-readable APK sizes. Pure Java — no Android
 * imports — so the harness proves the exact strings the
 * activity renders.
 *
 * One QR code holds one payload, so sharing over the hotspot
 * shows TWO codes: the Wi-Fi config (join the hotspot) and the
 * download URL (open the page). A single code cannot do both —
 * the Wi-Fi payload joins the network but opens nothing, and
 * the URL payload opens the browser but joins nothing. The
 * screen shows both, with the network name, password, and URL
 * as text beside them for anyone who would rather type.
 */
public final class SharePayload {

    /** MIME type of an APK, for the share sheet and the server. */
    public static final String APK_MIME = "application/vnd.android.package-archive";

    /** The download page's path on the local server. */
    public static final String PAGE_PATH = "/";

    /** The APK's path on the local server. */
    public static final String APK_PATH = "/app.apk";

    private SharePayload() {}

    /**
     * The Wi-Fi join payload (the de-facto `WIFI:` QR format
     * every Android scanner understands). Characters that would
     * end a field early — backslash, semicolon, comma, colon,
     * double-quote — are backslash-escaped, per the format.
     * A null or empty password means an open network (the
     * local-only hotspot always sets one, but the shape is
     * pinned anyway so a future open mode cannot emit a
     * password field with nothing in it).
     */
    public static String wifiConfig(String ssid, String password) {
        String s = ssid != null ? ssid : "";
        StringBuilder out = new StringBuilder("WIFI:T:");
        if (password == null || password.isEmpty()) {
            out.append("nopass;S:").append(escape(s)).append(";;");
        } else {
            out.append("WPA;S:").append(escape(s))
               .append(";P:").append(escape(password)).append(";H:false;;");
        }
        return out.toString();
    }

    static String escape(String v) {
        StringBuilder out = new StringBuilder(v.length() + 4);
        for (int i = 0; i < v.length(); i++) {
            char c = v.charAt(i);
            if (c == '\\' || c == ';' || c == ',' || c == ':' || c == '"') {
                out.append('\\');
            }
            out.append(c);
        }
        return out.toString();
    }

    /**
     * The download URL for the QR code and the on-screen text.
     * The host must be an IPv4 literal (the hotspot gateway
     * address) — a hostname would need DNS the hotspot does
     * not provide, and a name that resolves elsewhere would
     * send the other phone to the wrong place. Returns null
     * for anything that is not a valid IPv4 literal + port.
     */
    public static String downloadUrl(String host, int port) {
        if (!isIpv4(host) || port < 1 || port > 65535) return null;
        return "http://" + host + ":" + port + APK_PATH;
    }

    static boolean isIpv4(String host) {
        if (host == null || host.isEmpty()) return false;
        String[] parts = host.split("\\.", -1);
        if (parts.length != 4) return false;
        for (String p : parts) {
            if (p.isEmpty() || p.length() > 3) return false;
            int v = 0;
            for (int i = 0; i < p.length(); i++) {
                char c = p.charAt(i);
                if (c < '0' || c > '9') return false;
                v = v * 10 + (c - '0');
            }
            if (v > 255) return false;
        }
        return true;
    }

    /**
     * The one page the local server serves: the app's name and
     * size, one download link, and the honest line that the
     * hotspot carries no internet. The label is HTML-escaped —
     * an app label is untrusted text and this page renders in
     * another phone's browser.
     */
    public static String pageHtml(String label, String sizeText) {
        String name = escapeHtml(label == null || label.isEmpty() ? "app" : label);
        String size = escapeHtml(sizeText == null ? "" : sizeText);
        return "<!doctype html><html><head><meta charset=\"utf-8\">"
                + "<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">"
                + "<title>Install " + name + "</title></head>"
                + "<body style=\"font-family:sans-serif;background:#101613;color:#e8efe9;"
                + "margin:0;padding:32px 24px\">"
                + "<h1 style=\"font-size:22px\">" + name + "</h1>"
                + "<p style=\"color:#93a399\">" + size + " &middot; sent straight "
                + "from a nearby phone. This hotspot has no internet — "
                + "the file comes from the phone you are connected to.</p>"
                + "<p><a href=\"" + APK_PATH + "\" style=\"display:inline-block;"
                + "background:#4ade80;color:#0c120e;padding:14px 28px;"
                + "border-radius:10px;font-weight:bold;text-decoration:none\">"
                + "Download the app</a></p>"
                + "<p style=\"color:#93a399;font-size:14px\">After the download, "
                + "open the file and allow the install once when Android asks.</p>"
                + "</body></html>";
    }

    static String escapeHtml(String v) {
        StringBuilder out = new StringBuilder(v.length());
        for (int i = 0; i < v.length(); i++) {
            char c = v.charAt(i);
            switch (c) {
                case '&': out.append("&amp;"); break;
                case '<': out.append("&lt;"); break;
                case '>': out.append("&gt;"); break;
                case '"': out.append("&quot;"); break;
                case '\'': out.append("&#39;"); break;
                default: out.append(c);
            }
        }
        return out.toString();
    }

    /**
     * Strips one outer pair of double quotes. WifiConfiguration
     * quotes the SSID (and sometimes the password); the QR
     * payload and the on-screen text want the bare value.
     * A value with no outer quotes passes through untouched,
     * and a lone quote is never half-stripped.
     */
    public static String stripQuotes(String v) {
        if (v == null) return null;
        if (v.length() >= 2 && v.startsWith("\"") && v.endsWith("\"")) {
            return v.substring(1, v.length() - 1);
        }
        return v;
    }

    /** Human-readable size for the picker rows and the page. */
    public static String formatSize(long bytes) {
        if (bytes <= 0) return "0 B";
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024L * 1024L) return (bytes / 1024) + " KB";
        if (bytes < 1024L * 1024L * 1024L) {
            return String.format("%.1f MB", bytes / (1024.0 * 1024.0));
        }
        return String.format("%.1f GB", bytes / (1024.0 * 1024.0 * 1024.0));
    }
}
