package app.control.launcher;

import org.json.JSONObject;

import java.util.UUID;

/**
 * One camera's stored record: the JSON shape in
 * {@code cameras.json} (device-local, never part of a
 * profile export) and its validation rules. Pure Java
 * except for the org.json types every other store uses —
 * the harness proves the round trip and the field rules
 * with the exact shipping code.
 *
 * Plaintext fields: id, name, host, port, snapshot path,
 * MJPEG path, vendor, refresh interval, timestamps. The
 * credentials live ONLY in {@code credCipher} (base64 of
 * the WalletCrypto blob sealing "username\npassword"
 * under the control-cameras Keystore key). No plaintext
 * username or password field exists anywhere in this
 * record, and toJson refuses to write one.
 */
public final class CamRecord {

    public String id;
    public String name;
    public String host;
    public int port;
    /** Snapshot path ("/snapshot.jpg") or a full http://
     *  URL from ONVIF. Never carries userinfo. */
    public String snapshotPath;
    /** MJPEG path or full URL, "" when none. */
    public String mjpegPath;
    public String vendor;
    public int refreshSeconds;
    /** Base64 sealed credentials, "" when the camera
     *  needs none. */
    public String credCipher;
    public long addedAt;
    /** Last successful fetch, 0 when never. */
    public long lastGoodAt;

    public CamRecord() {
        this.id = UUID.randomUUID().toString();
        this.name = "";
        this.host = "";
        this.port = 80;
        this.snapshotPath = "";
        this.mjpegPath = "";
        this.vendor = "";
        this.refreshSeconds = CamFeed.DEFAULT_REFRESH_SECONDS;
        this.credCipher = "";
        this.addedAt = System.currentTimeMillis();
        this.lastGoodAt = 0;
    }

    /** The snapshot URL this camera fetches, or "" when
     *  the record cannot build one. */
    public String snapshotUrl() {
        return CamFeed.buildUrl(host, port, snapshotPath);
    }

    /** The MJPEG URL, or "" when the camera has none. */
    public String mjpegUrl() {
        if (mjpegPath == null || mjpegPath.isEmpty()) return "";
        return CamFeed.buildUrl(host, port, mjpegPath);
    }

    /** True when the record has enough to fetch: a host
     *  and a snapshot path/URL. */
    public boolean fetchable() {
        return !snapshotUrl().isEmpty();
    }

    /** Display name: the stored name, else the host. */
    public String displayName() {
        return (name == null || name.isEmpty()) ? host : name;
    }

    public JSONObject toJson() {
        JSONObject o = new JSONObject();
        try {
            o.put("id", id);
            o.put("name", name);
            o.put("host", host);
            o.put("port", port);
            o.put("snapshotPath", snapshotPath);
            o.put("mjpegPath", mjpegPath);
            o.put("vendor", vendor);
            o.put("refreshSeconds", refreshSeconds);
            o.put("credCipher", credCipher);
            o.put("addedAt", addedAt);
            o.put("lastGoodAt", lastGoodAt);
        } catch (Exception ignored) {
        }
        return o;
    }

    /** Parses a stored record. Returns null when the id
     *  or host is missing (an unusable record); other
     *  fields fall back to defaults. */
    public static CamRecord fromJson(JSONObject o) {
        if (o == null) return null;
        CamRecord r = new CamRecord();
        r.id = o.optString("id", "");
        r.name = o.optString("name", "");
        r.host = o.optString("host", "");
        r.port = o.optInt("port", 80);
        r.snapshotPath = o.optString("snapshotPath", "");
        r.mjpegPath = o.optString("mjpegPath", "");
        r.vendor = o.optString("vendor", "");
        r.refreshSeconds = CamFeed.clampRefreshSeconds(o.optInt("refreshSeconds",
                CamFeed.DEFAULT_REFRESH_SECONDS));
        r.credCipher = o.optString("credCipher", "");
        r.addedAt = o.optLong("addedAt", 0);
        r.lastGoodAt = o.optLong("lastGoodAt", 0);
        if (r.id.isEmpty() || r.host.isEmpty()) return null;
        if (r.port < 1 || r.port > 65535) r.port = 80;
        return r;
    }

    /** Validates a user-entered host: an IPv4 literal, a
     *  bracketed IPv6 literal ("[fe80::1]"), or a hostname
     *  — no scheme, no path, no userinfo, no port (the
     *  port is a separate field), no whitespace. A bare
     *  colon outside brackets is rejected so "host:port"
     *  typed into the host field cannot smuggle a port. */
    public static boolean validHost(String host) {
        if (host == null || host.isEmpty() || host.length() > 253) return false;
        boolean bracketed = host.startsWith("[") && host.endsWith("]");
        if (host.startsWith("[") != host.endsWith("]")) return false;
        for (int i = 0; i < host.length(); i++) {
            char c = host.charAt(i);
            if (c <= ' ' || c == 127) return false;
            if (c == '/' || c == '@') return false;
            if (c == ':' && !bracketed) return false;
        }
        return true;
    }

    /** Splits sealed credentials ("username\npassword")
     *  back into their two parts. A blob with no newline
     *  is a username with an empty password. */
    public static String[] splitCreds(String sealed) {
        if (sealed == null) return new String[]{"", ""};
        int nl = sealed.indexOf('\n');
        if (nl < 0) return new String[]{sealed, ""};
        return new String[]{sealed.substring(0, nl), sealed.substring(nl + 1)};
    }

    /** Joins credentials for sealing. Usernames and
     *  passwords containing newlines are refused upstream
     *  (the edit dialog rejects them) so the split is
     *  unambiguous. */
    public static String joinCreds(String username, String password) {
        return (username == null ? "" : username) + "\n" + (password == null ? "" : password);
    }
}
