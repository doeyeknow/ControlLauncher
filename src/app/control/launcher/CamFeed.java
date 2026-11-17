package app.control.launcher;

import java.util.ArrayList;
import java.util.List;

/**
 * Feed resolution: where a camera's picture lives, and in
 * what order to look for it. Pure Java — the harness pins
 * the candidate order, the URL building, and the JPEG
 * validation the fetcher depends on.
 *
 * Resolution order (the brief's order, unchanged):
 *   1. ONVIF GetSnapshotUri, when the device answered
 *      WS-Discovery and the call succeeds with an http://
 *      URI (an https:// URI is noted and skipped — see
 *      CamHttp on self-signed certificates).
 *   2. Known snapshot-pattern probes, AFTER credentials
 *      are entered: the vendor's patterns first (when the
 *      vendor was guessed), then the generic patterns. The
 *      first probe that returns 200 with an image/*
 *      content type wins. A 401 during probing means the
 *      credentials are wrong — probing stops and says so,
 *      instead of burning through patterns that would all
 *      401 the same way.
 *   3. A URL the user types by hand.
 *
 * MJPEG follows the same shape: an ONVIF stream URI whose
 * scheme is http(s) and whose path looks like a motion-JPEG
 * endpoint, else known MJPEG patterns, else a hand-typed
 * URL. An rtsp:// URI is never treated as MJPEG.
 */
public final class CamFeed {

    /** A resolved feed: snapshot URL, optional MJPEG URL,
     *  and how each was found (for honest status copy). */
    public static final class Resolved {
        public String snapshotUrl = "";
        public String snapshotSource = "";  // "onvif" | "pattern" | "manual"
        public String mjpegUrl = "";
        public String mjpegSource = "";
    }

    private CamFeed() {}

    /** Generic snapshot paths, most common first. */
    public static final String[] GENERIC_SNAPSHOT_PATHS = {
            "/snapshot.jpg",
            "/snapshot.jpeg",
            "/cgi-bin/snapshot.cgi",
            "/jpg/image.jpg",
            "/image.jpg",
            "/snapshot",
            "/cgi-bin/snapshot.jpg",
            "/axis-cgi/jpg/image.cgi",
    };

    /** Vendor snapshot paths, tried before the generic
     *  list when the vendor was guessed. */
    public static String[] vendorSnapshotPaths(String vendor) {
        if (vendor == null) return new String[0];
        switch (vendor) {
            case "Hikvision":
                return new String[]{
                        "/ISAPI/Streaming/channels/101/picture",
                        "/ISAPI/Streaming/channels/1/picture",
                };
            case "Dahua":
            case "Amcrest":
                return new String[]{
                        "/cgi-bin/snapshot.cgi",
                        "/cgi-bin/snapshot.cgi?channel=0",
                };
            case "Reolink":
                return new String[]{
                        "/cgi-bin/api.cgi?cmd=Snap&channel=0&rs=1",
                };
            case "Axis":
                return new String[]{
                        "/axis-cgi/jpg/image.cgi",
                        "/axis-cgi/jpg/image.cgi?resolution=640x480",
                };
            case "Foscam":
                return new String[]{
                        "/cgi-bin/CGIProxy.fcgi?cmd=snapPicture2",
                        "/snapshot.cgi",
                };
            case "UniFi":
                return new String[]{
                        "/snap.jpeg",
                        "/snapshot",
                };
            default:
                return new String[0];
        }
    }

    /** Generic MJPEG stream paths. */
    public static final String[] GENERIC_MJPEG_PATHS = {
            "/mjpg/video.mjpg",
            "/video.mjpg",
            "/mjpg",
            "/axis-cgi/mjpg/video.cgi",
            "/cgi-bin/mjpg/video.cgi",
            "/videostream.cgi",
    };

    /** Vendor MJPEG paths, tried before the generic list. */
    public static String[] vendorMjpegPaths(String vendor) {
        if (vendor == null) return new String[0];
        switch (vendor) {
            case "Hikvision":
                return new String[]{
                        "/ISAPI/Streaming/channels/102/httppreview",
                };
            case "Axis":
                return new String[]{
                        "/axis-cgi/mjpg/video.cgi",
                };
            case "Foscam":
                return new String[]{
                        "/cgi-bin/CGIStream.cgi?cmd=GetMJStream",
                };
            default:
                return new String[0];
        }
    }

    /**
     * The full ordered probe list for snapshots: vendor
     * patterns, then generic patterns, deduplicated,
     * preserving order.
     */
    public static List<String> snapshotCandidates(String vendor) {
        List<String> out = new ArrayList<>();
        for (String p : vendorSnapshotPaths(vendor)) {
            if (!out.contains(p)) out.add(p);
        }
        for (String p : GENERIC_SNAPSHOT_PATHS) {
            if (!out.contains(p)) out.add(p);
        }
        return out;
    }

    /** The full ordered probe list for MJPEG streams. */
    public static List<String> mjpegCandidates(String vendor) {
        List<String> out = new ArrayList<>();
        for (String p : vendorMjpegPaths(vendor)) {
            if (!out.contains(p)) out.add(p);
        }
        for (String p : GENERIC_MJPEG_PATHS) {
            if (!out.contains(p)) out.add(p);
        }
        return out;
    }

    /**
     * Builds a snapshot URL from a host, port, and path.
     * The path is normalized to start with '/'; a path
     * that is itself a full URL is returned as-is when it
     * is an http:// URL (ONVIF returns absolute URIs).
     * Userinfo in a full URL is rejected (credentials
     * never travel in URLs). Returns "" when unbuildable.
     */
    public static String buildUrl(String host, int port, String path) {
        if (path != null && path.startsWith("http://")) {
            if (path.substring(7).contains("@")) return "";
            return path;
        }
        if (host == null || host.isEmpty() || path == null || path.isEmpty()) return "";
        if (path.startsWith("https://")) return "";
        String p = path.startsWith("/") ? path : "/" + path;
        StringBuilder sb = new StringBuilder("http://");
        sb.append(host);
        if (port != 80) sb.append(':').append(port);
        sb.append(p);
        return sb.toString();
    }

    /** True when a URI from ONVIF is usable as a snapshot
     *  URL: http://, no userinfo. https:// is refused
     *  (self-signed certificates — see CamHttp). */
    public static boolean usableOnvifUri(String uri) {
        if (uri == null || !uri.startsWith("http://")) return false;
        String rest = uri.substring(7);
        int slash = rest.indexOf('/');
        String authority = slash < 0 ? rest : rest.substring(0, slash);
        return authority.indexOf('@') < 0 && !authority.isEmpty();
    }

    /** True when a URI from ONVIF GetStreamUri is an MJPEG
     *  stream this app can read: http://, no userinfo, and
     *  not an rtsp:// URI (RTSP is out of scope). */
    public static boolean usableMjpegUri(String uri) {
        return usableOnvifUri(uri);
    }

    /**
     * Whether bytes look like a JPEG: SOI marker first,
     * EOI marker somewhere inside (the last two bytes need
     * not be EOI — some cameras pad). Empty, truncated,
     * and non-JPEG bodies fail. This is the guard before
     * a fetched body is shown or stored as a last-good
     * image.
     */
    public static boolean jpegLooksValid(byte[] body) {
        if (body == null || body.length < 4) return false;
        if (body[0] != (byte) 0xFF || body[1] != (byte) 0xD8) return false;
        for (int i = 2; i + 1 < body.length; i++) {
            if (body[i] == (byte) 0xFF && body[i + 1] == (byte) 0xD9) return true;
        }
        return false;
    }

    /** Whether a content type denotes a still image this
     *  app shows (JPEG, and PNG as a fallback — some
     *  cameras serve PNG snapshots). */
    public static boolean isImageContentType(String contentType) {
        if (contentType == null) return false;
        String t = contentType.toLowerCase().trim();
        return t.startsWith("image/jpeg") || t.startsWith("image/jpg")
                || t.startsWith("image/png");
    }

    /** Whether a content type denotes an MJPEG stream. */
    public static boolean isMjpegContentType(String contentType) {
        if (contentType == null) return false;
        return contentType.toLowerCase().trim().startsWith("multipart/x-mixed-replace");
    }

    /** Refresh intervals offered per camera, in seconds
     *  (0 = off — manual refresh only). */
    public static final int[] REFRESH_INTERVALS = {5, 10, 30, 60, 0};

    /** The default refresh interval: 10 seconds. */
    public static final int DEFAULT_REFRESH_SECONDS = 10;

    /** Clamps a stored refresh interval to an offered
     *  value (unknown values fall back to the default). */
    public static int clampRefreshSeconds(int seconds) {
        for (int v : REFRESH_INTERVALS) {
            if (v == seconds) return seconds;
        }
        return DEFAULT_REFRESH_SECONDS;
    }

    /** Display label for a refresh interval. */
    public static String refreshLabel(int seconds) {
        switch (seconds) {
            case 5: return "Every 5 seconds";
            case 10: return "Every 10 seconds";
            case 30: return "Every 30 seconds";
            case 60: return "Every minute";
            case 0: return "Manual refresh only";
            default: return "Every 10 seconds";
        }
    }
}
