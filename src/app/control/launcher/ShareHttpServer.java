package app.control.launcher;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/**
 * The minimal HTTP server behind hotspot sharing (v1.25). It
 * binds to the hotspot interface's address only (never a
 * wildcard), on an ephemeral port, and serves exactly two
 * things: the download page and the one selected APK. Routing
 * is {@link ShareHttp}'s exact-match rule — every other path
 * is a 404, so there is no directory listing, no file access
 * beyond the selected APK, and no path traversal to exploit.
 *
 * The server lives only while the share screen shows the
 * hotspot: {@link #stop()} closes the listening socket and
 * every open connection. A client that disconnects mid-
 * download fails silently on its own thread and affects
 * nothing else. Nothing about a request is logged.
 */
public final class ShareHttpServer {

    private static final int MAX_LINE = 2048;
    private static final int MAX_HEADERS = 8192;
    private static final int BUFFER = 65536;

    private final InetAddress bindAddr;
    private final File apkFile;
    private final byte[] pageBytes;
    private final String apkFileName;

    private ServerSocket server;
    private Thread acceptThread;
    private final Set<Socket> clients =
            Collections.synchronizedSet(new HashSet<Socket>());
    private volatile boolean running;

    public ShareHttpServer(InetAddress bindAddr, File apkFile,
                           String appLabel, String sizeText, String apkFileName) {
        this.bindAddr = bindAddr;
        this.apkFile = apkFile;
        this.pageBytes = SharePayload.pageHtml(appLabel, sizeText)
                .getBytes(StandardCharsets.UTF_8);
        this.apkFileName = apkFileName;
    }

    /** Starts listening. The port is ephemeral; read it back
     *  with {@link #port()} for the QR code. */
    public synchronized void start() throws IOException {
        if (running) return;
        server = new ServerSocket();
        server.setReuseAddress(true);
        server.bind(new InetSocketAddress(bindAddr, 0));
        running = true;
        acceptThread = new Thread(this::acceptLoop, "share-http-accept");
        acceptThread.setDaemon(true);
        acceptThread.start();
    }

    /** The bound port, or -1 before {@link #start()}. */
    public synchronized int port() {
        return server != null ? server.getLocalPort() : -1;
    }

    /** Stops listening and closes every open connection. */
    public synchronized void stop() {
        running = false;
        try {
            if (server != null) server.close();
        } catch (IOException ignored) {
        }
        synchronized (clients) {
            for (Socket s : clients) {
                try {
                    s.close();
                } catch (IOException ignored) {
                }
            }
            clients.clear();
        }
    }

    private void acceptLoop() {
        while (running) {
            try {
                Socket client = server.accept();
                clients.add(client);
                Thread t = new Thread(() -> serve(client), "share-http-client");
                t.setDaemon(true);
                t.start();
            } catch (IOException e) {
                if (running) {
                    // Accept failed while running (e.g. the
                    // interface went away): stop quietly rather
                    // than spin. The activity's stop path owns
                    // the user-facing wording.
                    running = false;
                }
            }
        }
    }

    private void serve(Socket client) {
        try {
            client.setSoTimeout(30000);
            InputStream in = new BufferedInputStream(client.getInputStream());
            OutputStream out = new BufferedOutputStream(client.getOutputStream());
            String requestLine = readLine(in, MAX_LINE);
            if (requestLine == null) return;
            skipHeaders(in);
            ShareHttp.Request req = ShareHttp.parseRequestLine(requestLine);
            ShareHttp.Route route = ShareHttp.route(req);
            boolean head = req != null && "HEAD".equals(req.method);
            switch (route) {
                case PAGE:
                    sendHeaders(out, 200, "OK", "text/html; charset=utf-8",
                            pageBytes.length, null);
                    if (!head) out.write(pageBytes);
                    break;
                case APK:
                    sendApk(out, head);
                    break;
                default:
                    byte[] body = "Not found.".getBytes(StandardCharsets.UTF_8);
                    sendHeaders(out, 404, "Not Found", "text/plain; charset=utf-8",
                            body.length, null);
                    if (!head) out.write(body);
                    break;
            }
            out.flush();
        } catch (IOException ignored) {
            // Client went away or misbehaved; nothing to do.
        } finally {
            clients.remove(client);
            try {
                client.close();
            } catch (IOException ignored) {
            }
        }
    }

    private void sendApk(OutputStream out, boolean head) throws IOException {
        long length = apkFile.length();
        if (length <= 0 || !apkFile.isFile()) {
            byte[] body = "Not found.".getBytes(StandardCharsets.UTF_8);
            sendHeaders(out, 404, "Not Found", "text/plain; charset=utf-8",
                    body.length, null);
            if (!head) out.write(body);
            return;
        }
        sendHeaders(out, 200, "OK", SharePayload.APK_MIME, length,
                "attachment; filename=\"" + apkFileName + "\"");
        if (head) return;
        // The read timeout applies to header reads; a slow
        // download must not be cut off mid-stream, so the
        // socket timeout is left as-is and the copy below
        // runs to completion or client disconnect.
        try (InputStream fileIn = new FileInputStream(apkFile)) {
            byte[] buf = new byte[BUFFER];
            int n;
            while ((n = fileIn.read(buf)) != -1) {
                out.write(buf, 0, n);
            }
        }
    }

    private void sendHeaders(OutputStream out, int status, String reason,
                             String contentType, long length,
                             String disposition) throws IOException {
        StringBuilder h = new StringBuilder();
        h.append("HTTP/1.1 ").append(status).append(' ').append(reason).append("\r\n");
        h.append("Content-Type: ").append(contentType).append("\r\n");
        h.append("Content-Length: ").append(length).append("\r\n");
        if (disposition != null) {
            h.append("Content-Disposition: ").append(disposition).append("\r\n");
        }
        h.append("Connection: close\r\n\r\n");
        out.write(h.toString().getBytes(StandardCharsets.UTF_8));
    }

    /** Reads one CRLF/LF-terminated line, capped. Null on EOF
     *  before any byte, or when the cap is hit (a runaway
     *  request line is treated as no request at all). */
    private String readLine(InputStream in, int cap) throws IOException {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        int b;
        int count = 0;
        boolean sawCr = false;
        while ((b = in.read()) != -1) {
            if (++count > cap) return null;
            if (b == '\n') break;
            if (sawCr) buf.write('\r');
            sawCr = (b == '\r');
            if (!sawCr) buf.write(b);
        }
        if (b == -1 && buf.size() == 0) return null;
        return buf.toString(StandardCharsets.UTF_8.name());
    }

    /** Consumes header lines until the blank line, capped in
     *  total so a client cannot hold the thread with an
     *  endless header block. */
    private void skipHeaders(InputStream in) throws IOException {
        int total = 0;
        while (true) {
            String line = readLine(in, MAX_LINE);
            if (line == null || line.isEmpty()) return;
            total += line.length();
            if (total > MAX_HEADERS) return;
        }
    }
}
