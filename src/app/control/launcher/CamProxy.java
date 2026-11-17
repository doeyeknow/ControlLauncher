package app.control.launcher;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * The camera-sharing proxy's wire shapes (pure Java): the
 * UDP announcement a sharing phone broadcasts, and the
 * HTTP routing for snapshot requests. The proxy itself
 * (CamProxyServer) serves JPEG bytes only — never URLs,
 * never credentials — and only while the user left
 * "Share cameras with the mesh" on.
 *
 * Announcement: JSON over UDP broadcast, every 2 seconds
 * while sharing is on (the GatewayDiscovery pattern):
 *   {"magic":"CONTROL-CAMS/1","name":"<phone>",
 *    "tcp":8993,"cameras":[{"id":"<uuid>","name":"<label>"}]}
 * The camera list carries ids + display names only. A
 * peer fetches a camera with GET /cam/<id> and gets the
 * JPEG bytes the sharing phone just pulled from its own
 * camera with its own stored credentials.
 *
 * Routing is exact-match, ShareHttp-style: only GET (or
 * HEAD) /cam/<id> with a well-formed id is served.
 * Everything else — traversal, encoded variants, other
 * methods, other paths — is a 404. The id charset is
 * [A-Za-z0-9-] so no id can smuggle a path.
 */
public final class CamProxy {

    public static final String MAGIC = "CONTROL-CAMS/1";
    public static final int HTTP_PORT = 8993;
    public static final int UDP_PORT = 8994;
    /** Announcements go out every 2s; a proxy is stale
     *  after 15s without one (peers drop its cameras). */
    public static final long ANNOUNCE_INTERVAL_MS = 2000;
    public static final long STALE_AFTER_MS = 15000;

    /** One camera in an announcement. */
    public static final class Shared {
        public final String id;
        public final String name;

        public Shared(String id, String name) {
            this.id = id;
            this.name = name;
        }
    }

    /** A parsed announcement. */
    public static final class Announcement {
        public final String phoneName;
        public final int tcpPort;
        public final List<Shared> cameras;

        Announcement(String phoneName, int tcpPort, List<Shared> cameras) {
            this.phoneName = phoneName;
            this.tcpPort = tcpPort;
            this.cameras = cameras;
        }
    }

    private CamProxy() {}

    /** Builds an announcement payload. Camera ids + names
     *  only — never addresses, URLs, or credentials. */
    public static String buildAnnouncement(String phoneName, List<Shared> cameras) {
        try {
            JSONObject o = new JSONObject();
            o.put("magic", MAGIC);
            o.put("name", phoneName == null ? "" : phoneName);
            o.put("tcp", HTTP_PORT);
            JSONArray arr = new JSONArray();
            if (cameras != null) {
                for (Shared s : cameras) {
                    if (s == null || !validId(s.id)) continue;
                    JSONObject c = new JSONObject();
                    c.put("id", s.id);
                    c.put("name", s.name == null ? "" : s.name);
                    arr.put(c);
                }
            }
            o.put("cameras", arr);
            return o.toString();
        } catch (Exception e) {
            return "";
        }
    }

    /** Parses an announcement payload. Returns null for
     *  anything that is not our magic, has no usable port,
     *  or is malformed. Cameras with invalid ids are
     *  dropped, not fatal. */
    public static Announcement parseAnnouncement(String text) {
        if (text == null) return null;
        try {
            JSONObject o = new JSONObject(text);
            if (!MAGIC.equals(o.optString("magic"))) return null;
            int port = o.optInt("tcp", -1);
            if (port < 1 || port > 65535) return null;
            List<Shared> cameras = new ArrayList<>();
            JSONArray arr = o.optJSONArray("cameras");
            if (arr != null) {
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject c = arr.optJSONObject(i);
                    if (c == null) continue;
                    String id = c.optString("id", "");
                    if (!validId(id)) continue;
                    cameras.add(new Shared(id, c.optString("name", "")));
                }
            }
            return new Announcement(o.optString("name", ""), port, cameras);
        } catch (Exception e) {
            return null;
        }
    }

    /** A camera id is a UUID-shaped token: letters,
     *  digits, and dashes, 1–64 chars. Anything else is
     *  not an id and never reaches the router. */
    public static boolean validId(String id) {
        if (id == null || id.isEmpty() || id.length() > 64) return false;
        for (int i = 0; i < id.length(); i++) {
            char c = id.charAt(i);
            boolean ok = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')
                    || (c >= '0' && c <= '9') || c == '-';
            if (!ok) return false;
        }
        return true;
    }

    /** Routes a proxy HTTP request line. Returns the
     *  camera id for GET/HEAD /cam/<id>, else null. The
     *  path is matched verbatim, never percent-decoded,
     *  and a query string is stripped (it carries no
     *  meaning here). */
    public static String routeCamId(String requestLine) {
        if (requestLine == null) return null;
        String trimmed = requestLine.trim();
        if (trimmed.isEmpty()) return null;
        String[] parts = trimmed.split(" ");
        if (parts.length != 3) return null;
        if (!"GET".equals(parts[0]) && !"HEAD".equals(parts[0])) return null;
        if (!parts[2].startsWith("HTTP/")) return null;
        String target = parts[1];
        int q = target.indexOf('?');
        if (q >= 0) target = target.substring(0, q);
        int f = target.indexOf('#');
        if (f >= 0) target = target.substring(0, f);
        String prefix = "/cam/";
        if (!target.startsWith(prefix)) return null;
        String id = target.substring(prefix.length());
        if (id.isEmpty() || id.indexOf('/') >= 0) return null;
        return validId(id) ? id : null;
    }

    /** True when an announcement is stale (no fresh one
     *  within STALE_AFTER_MS). */
    public static boolean isStale(long lastSeenMs, long nowMs) {
        return nowMs - lastSeenMs > STALE_AFTER_MS;
    }
}
