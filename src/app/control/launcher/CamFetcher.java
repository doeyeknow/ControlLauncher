package app.control.launcher;

import android.content.Context;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Camera fetching: snapshots, MJPEG streams, and feed
 * resolution (ONVIF + pattern probes). All work runs off
 * the UI thread; results arrive through callbacks.
 *
 * Snapshot fetch: one CamHttp GET with the camera's stored
 * credentials (unsealed here, never logged, never kept
 * past the call). A 401 names the credential problem; any
 * other failure names itself. A good fetch stores the
 * last-good image and stamps the record.
 *
 * MJPEG: one reader thread per streaming camera while the
 * page shows it. The thread opens the stream URL, parses
 * the response headers itself (the body is endless, so
 * CamHttp.readResponse's capped read does not apply —
 * {@link #openStream} reads headers with the same line
 * rules, then feeds the raw stream to MjpegParser), and
 * posts frames to the listener. The thread ends on stop,
 * on socket error, or on the closing delimiter; a dead
 * stream reports itself ended, never spins reconnecting
 * (the page's refresh tick restarts it when due).
 *
 * Feed resolution runs at add time, in the brief's order:
 * ONVIF GetSnapshotUri first (POSTing the SOAP envelope
 * to the device's service address with the same auth
 * retry as a snapshot fetch), then pattern probes with
 * the entered credentials, stopping at the first probe
 * that returns 200 with an image content type — or at
 * the first 401, which means the credentials are wrong
 * and no further pattern would pass. MJPEG resolution
 * follows the same shape with the MJPEG candidates.
 */
public final class CamFetcher {

    /** A snapshot result: JPEG bytes, or a failure. */
    public interface SnapshotCallback {
        void onOk(byte[] jpeg);
        void onFail(String message, boolean authProblem);
    }

    /** MJPEG frames + stream end, on the caller's thread
     *  (the page re-posts to the main thread). */
    public interface StreamListener {
        void onFrame(byte[] jpeg);
        void onEnded(String message);
    }

    /** Feed-resolution result. */
    public interface ResolveCallback {
        void onResolved(CamFeed.Resolved resolved);
        void onFailed(String message, boolean authProblem);
    }

    private static final int SNAPSHOT_BODY_CAP = 8 * 1024 * 1024;
    private static final int CONNECT_MS = 3000;
    private static final int READ_MS = 8000;
    private static final int PROBE_READ_MS = 4000;

    /** Digest nonce state, one per camera id. */
    private static final Map<String, CamHttp.AuthState> AUTH_STATES = new ConcurrentHashMap<>();

    private CamFetcher() {}

    private static CamHttp.AuthState authState(String cameraId) {
        CamHttp.AuthState s = AUTH_STATES.get(cameraId);
        if (s == null) {
            s = new CamHttp.AuthState();
            AUTH_STATES.put(cameraId, s);
        }
        return s;
    }

    // ---- snapshots ----

    /** Fetches one snapshot on a background thread. */
    public static void fetchSnapshot(Context context, CamRecord rec,
                                     SnapshotCallback cb) {
        final Context app = context.getApplicationContext();
        new Thread(() -> {
            try {
                String[] creds = CamStore.unsealCreds(rec.credCipher);
                String url = rec.snapshotUrl();
                CamHttp.Response r = CamHttp.get(url, creds[0], creds[1],
                        authState(rec.id), CONNECT_MS, READ_MS, SNAPSHOT_BODY_CAP);
                if (r.status != 200) {
                    cb.onFail("The camera answered " + r.status, false);
                    return;
                }
                if (!CamFeed.jpegLooksValid(r.body)
                        && !CamFeed.isImageContentType(r.header("content-type"))) {
                    cb.onFail("The camera did not send a picture", false);
                    return;
                }
                CamStore.storeLastImage(app, rec.id, r.body);
                CamStore.markGood(app, rec.id, System.currentTimeMillis());
                cb.onOk(r.body);
            } catch (CamHttp.FetchException e) {
                cb.onFail(e.getMessage(), e.kind == CamHttp.FetchException.AUTH_FAILED);
            } catch (Exception e) {
                cb.onFail("Could not reach the camera", false);
            }
        }, "cam-snapshot").start();
    }

    // ---- MJPEG streams ----

    /** A running MJPEG stream. {@link #stop()} ends the
     *  reader thread and closes the socket. */
    public static final class Stream {
        private volatile boolean running = true;
        private Socket socket;
        private Thread thread;

        public void stop() {
            running = false;
            try {
                if (socket != null) socket.close();
            } catch (Exception ignored) {
            }
            if (thread != null) thread.interrupt();
        }

        public boolean isRunning() {
            return running;
        }
    }

    /** Starts an MJPEG stream on its own thread. */
    public static Stream startStream(Context context, CamRecord rec, StreamListener listener) {
        final Context app = context.getApplicationContext();
        Stream stream = new Stream();
        stream.thread = new Thread(() -> {
            try {
                runStream(app, rec, stream, listener);
            } catch (Exception e) {
                if (stream.running) {
                    listener.onEnded("The live view ended");
                }
            }
        }, "cam-mjpeg");
        stream.thread.setDaemon(true);
        stream.thread.start();
        return stream;
    }

    private static void runStream(Context app, CamRecord rec, Stream stream,
                                  StreamListener listener) throws Exception {
        String url = rec.mjpegUrl();
        CamHttp.Url u = CamHttp.parseUrl(url);
        if (u == null) {
            listener.onEnded("The live view address is not usable");
            return;
        }
        String[] creds = CamStore.unsealCreds(rec.credCipher);
        Socket socket = new Socket();
        stream.socket = socket;
        socket.connect(new InetSocketAddress(u.host, u.port), CONNECT_MS);
        socket.setSoTimeout(30000);
        String authHeader = null;
        // One unauthenticated attempt, then one authed
        // retry — the same rule as a snapshot fetch, but
        // the headers are read here because the body is a
        // stream, not a capped response.
        for (int attempt = 0; attempt < 2; attempt++) {
            OutputStream out = socket.getOutputStream();
            StringBuilder req = new StringBuilder();
            req.append("GET ").append(u.target).append(" HTTP/1.1\r\n");
            req.append("Host: ").append(u.host);
            if (u.port != 80) req.append(':').append(u.port);
            req.append("\r\n");
            if (authHeader != null) req.append("Authorization: ").append(authHeader).append("\r\n");
            req.append("User-Agent: Control/1\r\n");
            req.append("Accept: multipart/x-mixed-replace,image/*\r\n");
            req.append("Connection: close\r\n\r\n");
            out.write(req.toString().getBytes(StandardCharsets.ISO_8859_1));
            out.flush();
            StreamHead head = readHead(socket.getInputStream());
            if (head.status < 0) {
                listener.onEnded("The camera sent nothing readable");
                return;
            }
            if (head.status == 401 && attempt == 0 && authHeader == null) {
                if (creds[0] == null || creds[0].isEmpty()) {
                    listener.onEnded("The camera asked for a username and password");
                    return;
                }
                CamHttp.Response fake = new CamHttp.Response(401, head.headers, new byte[0]);
                authHeader = CamHttp.authHeaderFor(fake, creds[0], creds[1], u,
                        authState(rec.id));
                if (authHeader == null) {
                    listener.onEnded("The camera asked for a username and password");
                    return;
                }
                try {
                    socket.close();
                } catch (Exception ignored) {
                }
                socket = new Socket();
                stream.socket = socket;
                socket.connect(new InetSocketAddress(u.host, u.port), CONNECT_MS);
                socket.setSoTimeout(30000);
                continue;
            }
            if (head.status != 200) {
                listener.onEnded("The camera answered " + head.status + " for the live view");
                return;
            }
            String boundary = MjpegParser.boundaryFromContentType(head.contentType);
            if (boundary == null) {
                listener.onEnded("The camera did not send a live view stream");
                return;
            }
            pumpFrames(socket.getInputStream(), boundary, stream, listener);
            return;
        }
        listener.onEnded("The camera refused the username and password");
    }

    /** Response head for a stream: status, headers, and
     *  the content type. Read with the same line rules as
     *  CamHttp's parser (capped headers, no body read). */
    static final class StreamHead {
        int status;
        Map<String, java.util.List<String>> headers;
        String contentType;
    }

    static StreamHead readHead(InputStream in) throws Exception {
        StreamHead head = new StreamHead();
        String statusLine = readHeadLine(in);
        head.status = CamHttp.parseStatus(statusLine);
        Map<String, java.util.List<String>> headers = new java.util.LinkedHashMap<>();
        int total = 0;
        while (true) {
            String line = readHeadLine(in);
            if (line == null || line.isEmpty()) break;
            total += line.length();
            if (total > 32768) break;
            int colon = line.indexOf(':');
            if (colon <= 0) continue;
            String name = line.substring(0, colon).trim().toLowerCase();
            String value = line.substring(colon + 1).trim();
            headers.computeIfAbsent(name, k -> new java.util.ArrayList<>()).add(value);
        }
        head.headers = headers;
        java.util.List<String> ct = headers.get("content-type");
        head.contentType = (ct == null || ct.isEmpty()) ? "" : ct.get(0);
        return head;
    }

    private static String readHeadLine(InputStream in) throws Exception {
        java.io.ByteArrayOutputStream buf = new java.io.ByteArrayOutputStream();
        int b;
        int count = 0;
        boolean sawCr = false;
        while ((b = in.read()) != -1) {
            if (++count > 8192) throw new Exception("oversized header");
            if (b == '\n') break;
            if (sawCr) buf.write('\r');
            sawCr = (b == '\r');
            if (!sawCr) buf.write(b);
        }
        if (b == -1 && buf.size() == 0 && !sawCr) return null;
        return buf.toString(StandardCharsets.ISO_8859_1.name());
    }

    private static void pumpFrames(InputStream in, String boundary, Stream stream,
                                   StreamListener listener) throws Exception {
        MjpegParser parser = new MjpegParser(boundary);
        byte[] buf = new byte[16384];
        int n;
        while (stream.running && (n = in.read(buf)) != -1) {
            for (byte[] frame : parser.feed(buf, 0, n)) {
                if (!stream.running) break;
                listener.onFrame(frame);
            }
            if (parser.isEnded()) break;
        }
        if (stream.running) listener.onEnded("The live view ended");
    }

    // ---- feed resolution ----

    /**
     * Resolves a camera's feed on a background thread, in
     * the brief's order: ONVIF GetSnapshotUri (when the
     * device supplied XAddrs), then pattern probes with
     * the entered credentials, stopping at the first 401.
     * MJPEG resolution runs after snapshots with the same
     * shape. A resolution that finds nothing reports
     * failure with an honest message — the user can still
     * type a URL by hand.
     */
    public static void resolveFeed(String host, int port, String vendor,
                                   List<String> xaddrs,
                                   String username, String password,
                                   ResolveCallback cb) {
        new Thread(() -> {
            CamFeed.Resolved resolved = new CamFeed.Resolved();
            // 1. ONVIF.
            String onvifUri = tryOnvifSnapshot(xaddrs, username, password);
            if (onvifUri != null && CamFeed.usableOnvifUri(onvifUri)) {
                resolved.snapshotUrl = onvifUri;
                resolved.snapshotSource = "onvif";
            }
            // 2. Pattern probes (only when ONVIF found nothing).
            if (resolved.snapshotUrl.isEmpty()) {
                ProbeResult probe = probeCandidates(host, port, vendor, username, password,
                        CamFeed.snapshotCandidates(vendor), false);
                if (probe.authFailed) {
                    cb.onFailed("The camera refused the username and password", true);
                    return;
                }
                if (probe.url != null) {
                    resolved.snapshotUrl = probe.url;
                    resolved.snapshotSource = "pattern";
                }
            }
            if (resolved.snapshotUrl.isEmpty()) {
                cb.onFailed("No snapshot address answered — try typing the camera's "
                        + "snapshot URL by hand, or check the address", false);
                return;
            }
            // MJPEG: ONVIF stream URI, then patterns. A
            // missing MJPEG feed is fine — snapshots work.
            String streamUri = tryOnvifStream(xaddrs, username, password);
            if (streamUri != null && CamFeed.usableMjpegUri(streamUri)) {
                resolved.mjpegUrl = streamUri;
                resolved.mjpegSource = "onvif";
            } else {
                ProbeResult mjpeg = probeCandidates(host, port, vendor, username, password,
                        CamFeed.mjpegCandidates(vendor), true);
                if (mjpeg.url != null) {
                    resolved.mjpegUrl = mjpeg.url;
                    resolved.mjpegSource = "pattern";
                }
            }
            cb.onResolved(resolved);
        }, "cam-resolve").start();
    }

    private static final class ProbeResult {
        String url;
        boolean authFailed;
    }

    /** Probes candidate paths in order. The first 200 with
     *  an image (or MJPEG) content type wins. A 401 stops
     *  the probe run: the credentials are wrong, and every
     *  further pattern would 401 the same way. */
    private static ProbeResult probeCandidates(String host, int port, String vendor,
                                               String username, String password,
                                               List<String> candidates, boolean mjpeg) {
        ProbeResult out = new ProbeResult();
        CamHttp.AuthState auth = new CamHttp.AuthState();
        for (String path : candidates) {
            String url = CamFeed.buildUrl(host, port, path);
            if (url.isEmpty()) continue;
            try {
                CamHttp.Response r = CamHttp.get(url, username, password, auth,
                        CONNECT_MS, PROBE_READ_MS,
                        mjpeg ? 65536 : SNAPSHOT_BODY_CAP);
                if (r.status == 200) {
                    String ct = r.header("content-type");
                    if (mjpeg ? CamFeed.isMjpegContentType(ct)
                            : CamFeed.isImageContentType(ct)) {
                        out.url = url;
                        return out;
                    }
                    // A 200 that is not a picture/stream:
                    // not this path. Keep looking.
                }
            } catch (CamHttp.FetchException e) {
                if (e.kind == CamHttp.FetchException.AUTH_FAILED) {
                    out.authFailed = true;
                    return out;
                }
                // Unreachable/timeout/bad: try the next path.
            } catch (Exception ignored) {
            }
        }
        return out;
    }

    /** POSTs a GetSnapshotUri SOAP call to each XAddr in
     *  turn. Returns the MediaUri, or null. Auth failures
     *  end the attempts (same rule as pattern probes). */
    private static String tryOnvifSnapshot(List<String> xaddrs,
                                           String username, String password) {
        return tryOnvifCall(xaddrs, username, password,
                CamOnvif.getSnapshotUriEnvelope(""));
    }

    private static String tryOnvifStream(List<String> xaddrs,
                                         String username, String password) {
        return tryOnvifCall(xaddrs, username, password,
                CamOnvif.getStreamUriEnvelope(""));
    }

    private static String tryOnvifCall(List<String> xaddrs,
                                       String username, String password,
                                       String envelope) {
        if (xaddrs == null) return null;
        for (String xaddr : xaddrs) {
            CamHttp.Url u = CamHttp.parseUrl(xaddr);
            if (u == null) continue; // https or malformed: skip
            try {
                String reply = postSoap(u, envelope, username, password);
                if (reply == null) return null; // auth failed: stop
                if (CamOnvif.isFault(reply)) continue;
                String uri = CamOnvif.mediaUri(reply);
                if (!uri.isEmpty()) return uri;
            } catch (Exception ignored) {
            }
        }
        return null;
    }

    /** POSTs a SOAP envelope with the same one-retry auth
     *  rule as a GET. Returns the reply body, or null when
     *  auth failed outright. */
    private static String postSoap(CamHttp.Url u, String envelope,
                                   String username, String password) throws Exception {
        CamHttp.AuthState auth = new CamHttp.AuthState();
        String authHeader = null;
        for (int attempt = 0; attempt < 2; attempt++) {
            Socket socket = new Socket();
            try {
                socket.connect(new InetSocketAddress(u.host, u.port), CONNECT_MS);
                socket.setSoTimeout(READ_MS);
                byte[] body = envelope.getBytes(StandardCharsets.UTF_8);
                StringBuilder req = new StringBuilder();
                req.append("POST ").append(u.target).append(" HTTP/1.1\r\n");
                req.append("Host: ").append(u.host);
                if (u.port != 80) req.append(':').append(u.port);
                req.append("\r\n");
                req.append("Content-Type: application/soap+xml; charset=utf-8\r\n");
                req.append("Content-Length: ").append(body.length).append("\r\n");
                if (authHeader != null) {
                    req.append("Authorization: ").append(authHeader).append("\r\n");
                }
                req.append("Connection: close\r\n\r\n");
                OutputStream out = socket.getOutputStream();
                out.write(req.toString().getBytes(StandardCharsets.ISO_8859_1));
                out.write(body);
                out.flush();
                CamHttp.Response r = CamHttp.readResponse(socket.getInputStream(),
                        256 * 1024);
                if (r.status == 401 && attempt == 0 && authHeader == null) {
                    if (username == null || username.isEmpty()) return null;
                    authHeader = CamHttp.authHeaderFor(r, username, password, u, auth);
                    if (authHeader == null) return null;
                    continue;
                }
                if (r.status != 200) return "";
                return new String(r.body, StandardCharsets.UTF_8);
            } finally {
                try {
                    socket.close();
                } catch (Exception ignored) {
                }
            }
        }
        return null;
    }
}
