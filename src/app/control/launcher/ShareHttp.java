package app.control.launcher;

/**
 * Request routing for the app-share HTTP server (v1.25). Pure
 * Java — no Android imports, no sockets — so the harness proves
 * the exact rules the server runs.
 *
 * The rule is deliberately narrow: the server answers exactly
 * two paths, the page (`/`) and the APK (`/app.apk`), for GET
 * and HEAD only. Anything else — any other path, any traversal
 * attempt, any encoded variant, any other method — is a 404.
 * Paths are matched EXACTLY as received, with no percent-
 * decoding: decoding would turn an encoded `..` into a real
 * one, and there is nothing legitimate to decode, because the
 * only two URLs in the world for this server are the ones on
 * the share screen. A query string is stripped before matching
 * (browsers sometimes add one; it carries no meaning here).
 */
public final class ShareHttp {

    public enum Route { PAGE, APK, NOT_FOUND }

    /** A parsed request line: method + target path (query stripped). */
    public static final class Request {
        public final String method;
        public final String path;

        Request(String method, String path) {
            this.method = method;
            this.path = path;
        }
    }

    private ShareHttp() {}

    /**
     * Parses one request line (`METHOD SP TARGET SP VERSION`).
     * Returns null for anything malformed: null/empty input, a
     * wrong number of parts, or a version that is not HTTP.
     * The target's query string and fragment are stripped; the
     * remaining path is returned verbatim, never decoded.
     */
    public static Request parseRequestLine(String line) {
        if (line == null) return null;
        String trimmed = line.trim();
        if (trimmed.isEmpty()) return null;
        String[] parts = trimmed.split(" ");
        if (parts.length != 3) return null;
        if (parts[0].isEmpty() || parts[1].isEmpty()) return null;
        if (!parts[2].startsWith("HTTP/")) return null;
        String target = parts[1];
        int q = target.indexOf('?');
        if (q >= 0) target = target.substring(0, q);
        int f = target.indexOf('#');
        if (f >= 0) target = target.substring(0, f);
        if (target.isEmpty()) return null;
        return new Request(parts[0], target);
    }

    /** Routes a parsed request. Null requests route to NOT_FOUND. */
    public static Route route(Request req) {
        if (req == null) return Route.NOT_FOUND;
        if (!"GET".equals(req.method) && !"HEAD".equals(req.method)) {
            return Route.NOT_FOUND;
        }
        if (SharePayload.PAGE_PATH.equals(req.path)) return Route.PAGE;
        if (SharePayload.APK_PATH.equals(req.path)) return Route.APK;
        return Route.NOT_FOUND;
    }

    /** Routes a raw request line in one step. */
    public static Route routeLine(String line) {
        return route(parseRequestLine(line));
    }
}
