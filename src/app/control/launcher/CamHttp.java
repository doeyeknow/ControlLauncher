package app.control.launcher;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A minimal HTTP/1.1 GET client over a raw socket, for camera
 * snapshot fetches on the local network.
 *
 * Raw sockets are deliberate, not nostalgic: the app's
 * manifest sets usesCleartextTraffic=false, so the platform
 * HTTP stack refuses the plain-HTTP snapshot URLs every
 * camera serves on a LAN. A socket client also keeps the
 * digest-auth retry, the chunked-body handling, and the
 * MJPEG stream reading under one roof, with no per-request
 * framework policy to fight.
 *
 * Scope, honestly narrow: GET only, http:// only (an https://
 * URL is refused — cameras serve self-signed certificates
 * this client will not blindly trust, and the feed
 * resolution prefers the camera's HTTP address), no
 * redirects (a 3xx is a failure naming the status), no
 * cookies, no proxy. Bodies are capped: a snapshot that
 * exceeds the cap is a failure, never a memory blowup.
 *
 * Auth: one unauthenticated attempt, then ONE retry with an
 * Authorization header when the camera answers 401 — Basic
 * when the challenge offers it, Digest otherwise. A second
 * 401 is returned as AUTH_FAILED, never retried in a loop.
 * The digest nonce-count is tracked by the caller through
 * {@link AuthState} (one per camera).
 *
 * The response parser ({@link #readResponse}) works over any
 * InputStream, so the harness proves the exact shipping
 * parse rules — status line, headers, content-length,
 * chunked, and close-delimited bodies — from byte arrays,
 * with no socket involved (the sandbox garbles JVM IPv4
 * loopback, so a loopback socket test would fail for
 * sandbox reasons, not code reasons — the same rule the
 * Share harness follows).
 */
public final class CamHttp {

    /** A fetched response: status, headers, and body. */
    public static final class Response {
        public final int status;
        public final Map<String, List<String>> headers;
        public final byte[] body;

        Response(int status, Map<String, List<String>> headers, byte[] body) {
            this.status = status;
            this.headers = headers;
            this.body = body;
        }

        /** First header value, case-insensitive name, or null. */
        public String header(String name) {
            List<String> v = headers.get(name.toLowerCase());
            return (v == null || v.isEmpty()) ? null : v.get(0);
        }
    }

    /** Why a fetch failed. AUTH_FAILED means the camera
     *  refused the credentials (or offered an auth scheme
     *  this client cannot answer); TIMEOUT/IO mean the
     *  camera could not be reached; TOO_LARGE means the
     *  body exceeded the cap; BAD_RESPONSE means the bytes
     *  were not HTTP at all. */
    public static final class FetchException extends Exception {
        public static final int AUTH_FAILED = 1;
        public static final int TIMEOUT = 2;
        public static final int IO = 3;
        public static final int TOO_LARGE = 4;
        public static final int BAD_RESPONSE = 5;
        public static final int UNSUPPORTED_URL = 6;
        public final int kind;

        FetchException(int kind, String message) {
            super(message);
            this.kind = kind;
        }

        FetchException(int kind, String message, Throwable cause) {
            super(message, cause);
            this.kind = kind;
        }
    }

    /** Per-camera digest state: the last nonce seen and the
     *  count used against it. The count resets when the
     *  nonce changes, per the RFC. */
    public static final class AuthState {
        String lastNonce;
        long nc;

        /** The next nonce-count for this nonce (1-based). */
        synchronized long nextNc(String nonce) {
            if (nonce == null || !nonce.equals(lastNonce)) {
                lastNonce = nonce;
                nc = 0;
            }
            return ++nc;
        }
    }

    /** Parsed http:// URL: host, port, and request target.
     *  Userinfo is REJECTED (credentials travel only in
     *  the Authorization header, never in a URL that could
     *  be logged or displayed). */
    public static final class Url {
        public final String host;
        public final int port;
        public final String target;

        Url(String host, int port, String target) {
            this.host = host;
            this.port = port;
            this.target = target;
        }
    }

    private static final int MAX_HEADER_BYTES = 32768;
    private static final int MAX_LINE_BYTES = 8192;

    private CamHttp() {}

    /** Parses an http:// URL. Returns null for anything else
     *  (https, userinfo, missing host, bad port, control
     *  characters). The target keeps its query string. */
    public static Url parseUrl(String url) {
        if (url == null) return null;
        if (!url.startsWith("http://")) return null;
        String rest = url.substring(7);
        if (rest.isEmpty()) return null;
        for (int i = 0; i < rest.length(); i++) {
            char ch = rest.charAt(i);
            if (ch <= ' ' || ch == 127) return null;
        }
        int slash = rest.indexOf('/');
        String authority = slash < 0 ? rest : rest.substring(0, slash);
        String target = slash < 0 ? "/" : rest.substring(slash);
        if (authority.indexOf('@') >= 0) return null;
        if (authority.isEmpty()) return null;
        String host;
        int port = 80;
        if (authority.startsWith("[")) {
            int close = authority.indexOf(']');
            if (close < 0) return null;
            host = authority.substring(1, close);
            if (close + 1 < authority.length()) {
                if (authority.charAt(close + 1) != ':') return null;
                port = parsePort(authority.substring(close + 2));
                if (port < 0) return null;
            }
        } else {
            int colon = authority.lastIndexOf(':');
            if (colon >= 0) {
                host = authority.substring(0, colon);
                port = parsePort(authority.substring(colon + 1));
                if (port < 0) return null;
            } else {
                host = authority;
            }
        }
        if (host.isEmpty()) return null;
        return new Url(host, port, target);
    }

    private static int parsePort(String s) {
        if (s.isEmpty()) return -1;
        try {
            int p = Integer.parseInt(s);
            return (p >= 1 && p <= 65535) ? p : -1;
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    // ---- fetching ----

    /**
     * GETs a URL, with one auth retry. {@code username} /
     * {@code password} may be null/empty (no credentials —
     * the retry is skipped and a 401 returns AUTH_FAILED).
     * {@code bodyCap} bounds the response body. Timeouts are
     * in milliseconds. The socket is always closed; the
     * caller owns nothing.
     */
    public static Response get(String url, String username, String password,
                               AuthState auth, int connectTimeoutMs,
                               int readTimeoutMs, int bodyCap) throws FetchException {
        Url u = parseUrl(url);
        if (u == null) {
            throw new FetchException(FetchException.UNSUPPORTED_URL,
                    "Only plain http:// addresses on the local network are fetched");
        }
        Response first = getOnce(u, null, connectTimeoutMs, readTimeoutMs, bodyCap);
        if (first.status != 401) return first;
        if (username == null || username.isEmpty()) {
            throw new FetchException(FetchException.AUTH_FAILED,
                    "The camera asked for a username and password");
        }
        String authHeader = authHeaderFor(first, username, password, u, auth);
        if (authHeader == null) {
            throw new FetchException(FetchException.AUTH_FAILED,
                    "The camera asked for an authentication method this app cannot answer");
        }
        Response second = getOnce(u, authHeader, connectTimeoutMs, readTimeoutMs, bodyCap);
        if (second.status == 401) {
            throw new FetchException(FetchException.AUTH_FAILED,
                    "The camera refused the username and password");
        }
        return second;
    }

    /** Builds the Authorization header for a 401 response:
     *  Digest when offered and answerable, else Basic when
     *  offered, else null. Handles a stale=true digest
     *  challenge by answering it the same way (the retry
     *  IS the fresh attempt). */
    static String authHeaderFor(Response r401, String username, String password,
                                Url u, AuthState auth) {
        List<String> challenges = r401.headers.get("www-authenticate");
        if (challenges == null) return null;
        String basicSeen = null;
        for (String ch : challenges) {
            CamDigest.Challenge digest = CamDigest.parse(ch);
            if (digest != null && CamDigest.answerable(digest)) {
                String cnonce = CamDigest.freshCnonce();
                long nc = auth == null ? 1 : auth.nextNc(digest.nonce);
                return CamDigest.authorization(digest, username, password,
                        "GET", u.target, nc, cnonce);
            }
            if (basicSeen == null && ch != null
                    && ch.trim().toLowerCase().startsWith("basic")) {
                basicSeen = ch;
            }
        }
        if (basicSeen != null) {
            String creds = username + ":" + (password == null ? "" : password);
            return "Basic " + java.util.Base64.getEncoder().encodeToString(
                    creds.getBytes(StandardCharsets.ISO_8859_1));
        }
        return null;
    }

    private static Response getOnce(Url u, String authHeader,
                                    int connectTimeoutMs, int readTimeoutMs,
                                    int bodyCap) throws FetchException {
        Socket socket = new Socket();
        try {
            socket.connect(new InetSocketAddress(u.host, u.port), connectTimeoutMs);
            socket.setSoTimeout(readTimeoutMs);
            OutputStream out = socket.getOutputStream();
            StringBuilder req = new StringBuilder();
            req.append("GET ").append(u.target).append(" HTTP/1.1\r\n");
            req.append("Host: ").append(u.host);
            if (u.port != 80) req.append(':').append(u.port);
            req.append("\r\n");
            if (authHeader != null) {
                req.append("Authorization: ").append(authHeader).append("\r\n");
            }
            req.append("User-Agent: Control/1\r\n");
            req.append("Accept: image/*,*/*\r\n");
            req.append("Connection: close\r\n\r\n");
            out.write(req.toString().getBytes(StandardCharsets.ISO_8859_1));
            out.flush();
            return readResponse(socket.getInputStream(), bodyCap);
        } catch (java.net.SocketTimeoutException e) {
            throw new FetchException(FetchException.TIMEOUT,
                    "Timed out reaching the camera", e);
        } catch (FetchException e) {
            throw e;
        } catch (IOException e) {
            throw new FetchException(FetchException.IO,
                    "Could not reach the camera", e);
        } finally {
            try {
                socket.close();
            } catch (IOException ignored) {
            }
        }
    }

    // ---- response parsing (pure stream logic) ----

    /**
     * Reads one HTTP/1.x response from a stream: status line,
     * headers, then the body by content-length, chunked
     * transfer-encoding, or read-to-EOF. Header bytes and
     * body bytes are each capped; exceeding either cap is a
     * FetchException, never an unbounded read. A 204/304
     * response has no body by definition.
     */
    public static Response readResponse(InputStream in, int bodyCap)
            throws FetchException, IOException {
        String statusLine = readLine(in, MAX_LINE_BYTES, MAX_HEADER_BYTES);
        if (statusLine == null) {
            throw new FetchException(FetchException.BAD_RESPONSE,
                    "The camera sent nothing readable");
        }
        int status = parseStatus(statusLine);
        if (status < 0) {
            throw new FetchException(FetchException.BAD_RESPONSE,
                    "The camera sent a bad status line");
        }
        Map<String, List<String>> headers = new LinkedHashMap<>();
        int headerBytes = statusLine.length();
        while (true) {
            String line = readLine(in, MAX_LINE_BYTES, MAX_HEADER_BYTES - headerBytes);
            if (line == null) {
                throw new FetchException(FetchException.BAD_RESPONSE,
                        "The camera's headers ended early");
            }
            headerBytes += line.length();
            if (line.isEmpty()) break;
            int colon = line.indexOf(':');
            if (colon <= 0) continue;
            String name = line.substring(0, colon).trim().toLowerCase();
            String value = line.substring(colon + 1).trim();
            headers.computeIfAbsent(name, k -> new ArrayList<>()).add(value);
        }
        byte[] body;
        if (status == 204 || status == 304) {
            body = new byte[0];
        } else if (isChunked(headers)) {
            body = readChunked(in, bodyCap);
        } else if (firstHeader(headers, "content-length") != null) {
            long len = parseContentLength(firstHeader(headers, "content-length"));
            if (len < 0) {
                throw new FetchException(FetchException.BAD_RESPONSE,
                        "The camera sent a bad content length");
            }
            if (len > bodyCap) {
                throw new FetchException(FetchException.TOO_LARGE,
                        "The camera's picture is larger than this app reads");
            }
            body = readExact(in, (int) len);
        } else {
            body = readToEof(in, bodyCap);
        }
        return new Response(status, headers, body);
    }

    private static String firstHeader(Map<String, List<String>> headers, String name) {
        List<String> v = headers.get(name);
        return (v == null || v.isEmpty()) ? null : v.get(0);
    }

    private static boolean isChunked(Map<String, List<String>> headers) {
        List<String> v = headers.get("transfer-encoding");
        if (v == null) return false;
        for (String s : v) {
            if (s.toLowerCase().contains("chunked")) return true;
        }
        return false;
    }

    private static long parseContentLength(String v) {
        try {
            return Long.parseLong(v.trim());
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    /** Parses "HTTP/1.1 200 OK" (any HTTP/1.x version, any
     *  reason phrase). Returns -1 for anything else. */
    static int parseStatus(String line) {
        if (line == null) return -1;
        if (!line.startsWith("HTTP/")) return -1;
        int sp = line.indexOf(' ');
        if (sp < 0) return -1;
        int end = sp + 1;
        while (end < line.length() && line.charAt(end) == ' ') end++;
        int numEnd = end;
        while (numEnd < line.length() && Character.isDigit(line.charAt(numEnd))) numEnd++;
        if (numEnd - end != 3) return -1;
        try {
            int status = Integer.parseInt(line.substring(end, numEnd));
            return (status >= 100 && status <= 599) ? status : -1;
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    /** Reads one CRLF/LF-terminated line, without the
     *  terminator. Null on EOF before any byte. The line cap
     *  counts bytes read; exceeding it throws TOO_LARGE so a
     *  runaway header line cannot spin the reader. */
    private static String readLine(InputStream in, int lineCap, int budget)
            throws IOException, FetchException {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        int b;
        int count = 0;
        boolean sawCr = false;
        while ((b = in.read()) != -1) {
            if (++count > lineCap || count > budget) {
                throw new FetchException(FetchException.TOO_LARGE,
                        "The camera sent oversized headers");
            }
            if (b == '\n') break;
            if (sawCr) buf.write('\r');
            sawCr = (b == '\r');
            if (!sawCr) buf.write(b);
        }
        if (b == -1 && buf.size() == 0 && !sawCr) return null;
        return buf.toString(StandardCharsets.ISO_8859_1.name());
    }

    private static byte[] readExact(InputStream in, int len)
            throws IOException, FetchException {
        byte[] out = new byte[len];
        int read = 0;
        while (read < len) {
            int n = in.read(out, read, len - read);
            if (n < 0) {
                throw new FetchException(FetchException.BAD_RESPONSE,
                        "The camera closed the connection early");
            }
            read += n;
        }
        return out;
    }

    private static byte[] readToEof(InputStream in, int cap)
            throws IOException, FetchException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) != -1) {
            if (out.size() + n > cap) {
                throw new FetchException(FetchException.TOO_LARGE,
                        "The camera's picture is larger than this app reads");
            }
            out.write(buf, 0, n);
        }
        return out.toByteArray();
    }

    private static byte[] readChunked(InputStream in, int cap)
            throws IOException, FetchException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        while (true) {
            String sizeLine = readLine(in, MAX_LINE_BYTES, MAX_HEADER_BYTES);
            if (sizeLine == null) {
                throw new FetchException(FetchException.BAD_RESPONSE,
                        "The camera's chunked picture ended early");
            }
            int semi = sizeLine.indexOf(';');
            String sizeText = (semi >= 0 ? sizeLine.substring(0, semi) : sizeLine).trim();
            long size;
            try {
                size = Long.parseLong(sizeText, 16);
            } catch (NumberFormatException e) {
                throw new FetchException(FetchException.BAD_RESPONSE,
                        "The camera sent a bad chunk size");
            }
            if (size == 0) {
                // Trailer headers until the blank line, then done.
                while (true) {
                    String t = readLine(in, MAX_LINE_BYTES, MAX_HEADER_BYTES);
                    if (t == null || t.isEmpty()) break;
                }
                return out.toByteArray();
            }
            if (size > cap - out.size()) {
                throw new FetchException(FetchException.TOO_LARGE,
                        "The camera's picture is larger than this app reads");
            }
            byte[] chunk = readExact(in, (int) size);
            out.write(chunk);
            String crlf = readLine(in, MAX_LINE_BYTES, MAX_HEADER_BYTES);
            if (crlf == null) {
                throw new FetchException(FetchException.BAD_RESPONSE,
                        "The camera's chunked picture ended early");
            }
        }
    }
}
