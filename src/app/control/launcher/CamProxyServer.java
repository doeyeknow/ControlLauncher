package app.control.launcher;

import android.content.Context;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * The camera-sharing proxy (v1.31): when the user leaves
 * "Share cameras with the mesh" ON, this phone serves its
 * cameras' snapshots to other Control phones on the same
 * local network — the hotspot topology Adam asked for
 * (cameras on the phone's hotspot, mesh phones elsewhere
 * on the mesh, this phone bridging the two at the app
 * level).
 *
 * Shape (the ShareHttpServer + GatewayDiscovery pattern):
 *   - An HTTP server on port 8993 serving exactly
 *     GET /cam/<id> → the JPEG bytes just fetched from
 *     that camera with this phone's stored credentials.
 *     Nothing else is served: no URLs, no credentials,
 *     no camera list, no directory — every other request
 *     is a 404 (CamProxy.routeCamId, harness-pinned).
 *   - UDP announcements on port 8994 every 2s naming the
 *     phone and its shared cameras (ids + names only).
 * Both stop the moment the switch goes off.
 *
 * The server binds the wildcard address deliberately: the
 * sharing phone may sit on the hotspot interface, the
 * Wi-Fi interface, and a Wi-Fi Direct group at once, and
 * peers may arrive on any of them. The exposed surface
 * stays one exact path, and the switch is the gate — off
 * means nothing listens at all.
 *
 * Kernel IP forwarding would be the "real" bridge, and it
 * needs root, which this app does not have and does not
 * ask for. App-level proxying is the honest substitute:
 * it serves snapshots (not streams, not RTSP), and a peer
 * sees only what this phone can itself reach, when this
 * phone is running. BUILD_NOTES states the limit.
 *
 * Lifecycle: a process-wide singleton reconciled by the
 * Cameras page (start when the switch is on, stop when it
 * goes off). Sharing ends if Control's process dies —
 * the same rule as walkie hosting, and the announcements
 * going quiet tells peers the cameras are gone.
 */
public final class CamProxyServer {

    private static final int MAX_LINE = 2048;
    private static final int MAX_HEADERS = 8192;
    private static final int FETCH_TIMEOUT_MS = 10000;

    private static CamProxyServer instance;

    private final Context app;
    private ServerSocket server;
    private Thread acceptThread;
    private Thread announceThread;
    private DatagramSocket announceSocket;
    private final Set<Socket> clients = Collections.synchronizedSet(new HashSet<Socket>());
    private volatile boolean running;

    private CamProxyServer(Context context) {
        this.app = context.getApplicationContext();
    }

    /** Starts sharing when the switch is on, stops when it
     *  is off. Safe to call repeatedly; the state settles. */
    public static synchronized void reconcile(Context context) {
        boolean on = Prefs.get(context).getBoolean(Prefs.KEY_CAM_SHARE, false);
        if (on && instance == null) {
            instance = new CamProxyServer(context);
            instance.start();
        } else if (!on && instance != null) {
            instance.stop();
            instance = null;
        }
    }

    /** True while the proxy is listening. */
    public static synchronized boolean isSharing() {
        return instance != null && instance.running;
    }

    private synchronized void start() {
        if (running) return;
        try {
            server = new ServerSocket();
            server.setReuseAddress(true);
            server.bind(new InetSocketAddress(CamProxy.HTTP_PORT));
        } catch (IOException e) {
            return; // port taken or no network: sharing stays off, silently
        }
        running = true;
        acceptThread = new Thread(this::acceptLoop, "cam-proxy-accept");
        acceptThread.setDaemon(true);
        acceptThread.start();
        announceThread = new Thread(this::announceLoop, "cam-proxy-announce");
        announceThread.setDaemon(true);
        announceThread.start();
    }

    private synchronized void stop() {
        running = false;
        try {
            if (server != null) server.close();
        } catch (IOException ignored) {
        }
        try {
            if (announceSocket != null) announceSocket.close();
        } catch (Exception ignored) {
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
        if (acceptThread != null) acceptThread.interrupt();
        if (announceThread != null) announceThread.interrupt();
    }

    private void acceptLoop() {
        while (running) {
            try {
                Socket client = server.accept();
                clients.add(client);
                Thread t = new Thread(() -> serve(client), "cam-proxy-client");
                t.setDaemon(true);
                t.start();
            } catch (IOException e) {
                if (running) running = false;
            }
        }
    }

    private void announceLoop() {
        try {
            announceSocket = new DatagramSocket();
            announceSocket.setBroadcast(true);
            InetAddress broadcast = InetAddress.getByName("255.255.255.255");
            while (running) {
                String payload = buildPayload();
                if (!payload.isEmpty()) {
                    byte[] data = payload.getBytes(StandardCharsets.UTF_8);
                    try {
                        announceSocket.send(new DatagramPacket(data, data.length,
                                broadcast, CamProxy.UDP_PORT));
                    } catch (Exception ignored) {
                    }
                }
                Thread.sleep(CamProxy.ANNOUNCE_INTERVAL_MS);
            }
        } catch (Exception ignored) {
        }
    }

    private String buildPayload() {
        List<CamProxy.Shared> shared = new ArrayList<>();
        try {
            for (CamRecord r : CamStore.list(app)) {
                if (r.fetchable()) shared.add(new CamProxy.Shared(r.id, r.displayName()));
            }
        } catch (Exception ignored) {
        }
        return CamProxy.buildAnnouncement(Profile.displayName(app), shared);
    }

    private void serve(Socket client) {
        try {
            client.setSoTimeout(15000);
            InputStream in = new BufferedInputStream(client.getInputStream());
            OutputStream out = new BufferedOutputStream(client.getOutputStream());
            String requestLine = readLine(in, MAX_LINE);
            if (requestLine == null) return;
            skipHeaders(in);
            String camId = CamProxy.routeCamId(requestLine);
            boolean head = requestLine.startsWith("HEAD ");
            if (camId == null) {
                sendStatus(out, 404, "Not Found", head);
                out.flush();
                return;
            }
            byte[] jpeg = fetchForPeer(camId);
            if (jpeg == null) {
                sendStatus(out, 502, "Bad Gateway", head);
                out.flush();
                return;
            }
            StringBuilder h = new StringBuilder();
            h.append("HTTP/1.1 200 OK\r\n");
            h.append("Content-Type: image/jpeg\r\n");
            h.append("Content-Length: ").append(jpeg.length).append("\r\n");
            h.append("Connection: close\r\n\r\n");
            out.write(h.toString().getBytes(StandardCharsets.UTF_8));
            if (!head) out.write(jpeg);
            out.flush();
        } catch (IOException ignored) {
        } finally {
            clients.remove(client);
            try {
                client.close();
            } catch (IOException ignored) {
            }
        }
    }

    /** Fetches one snapshot for a peer, synchronously, on
     *  the client thread. Returns null when the camera
     *  cannot be reached, has no credentials that work, or
     *  does not exist. Credentials never leave this phone:
     *  the peer receives JPEG bytes and nothing else. */
    private byte[] fetchForPeer(String camId) {
        CamRecord rec = CamStore.byId(app, camId);
        if (rec == null || !rec.fetchable()) return null;
        final byte[][] result = new byte[1][];
        final CountDownLatch done = new CountDownLatch(1);
        CamFetcher.fetchSnapshot(app, rec, new CamFetcher.SnapshotCallback() {
            @Override public void onOk(byte[] jpeg) {
                result[0] = jpeg;
                done.countDown();
            }

            @Override public void onFail(String message, boolean authProblem) {
                done.countDown();
            }
        });
        try {
            done.await(FETCH_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        } catch (InterruptedException ignored) {
        }
        return result[0];
    }

    private void sendStatus(OutputStream out, int status, String reason, boolean head)
            throws IOException {
        byte[] body = (reason + ".").getBytes(StandardCharsets.UTF_8);
        StringBuilder h = new StringBuilder();
        h.append("HTTP/1.1 ").append(status).append(' ').append(reason).append("\r\n");
        h.append("Content-Type: text/plain; charset=utf-8\r\n");
        h.append("Content-Length: ").append(body.length).append("\r\n");
        h.append("Connection: close\r\n\r\n");
        out.write(h.toString().getBytes(StandardCharsets.UTF_8));
        if (!head) out.write(body);
    }

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
