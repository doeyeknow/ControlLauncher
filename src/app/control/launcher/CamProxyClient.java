package app.control.launcher;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The peer side of camera sharing: while the Cameras page
 * is visible, this listens for sharing announcements on
 * UDP 8994 and keeps the live set of proxied cameras —
 * each one a camera on another phone, fetched over plain
 * HTTP from that phone's proxy. Proxied cameras are
 * session-only: they are never stored, they vanish when
 * the sharing phone goes quiet (15s without an
 * announcement), and the page marks every one "via
 * <phone name>" so a shared camera is never mistaken for
 * one of this phone's own.
 *
 * Fetching a proxied camera is one CamHttp GET with no
 * credentials (the proxy asks for none — it already
 * authenticated to the camera itself). The peer never
 * learns the camera's address or credentials: the URL it
 * fetches names the proxy phone and an opaque camera id.
 */
public final class CamProxyClient {

    /** One proxied camera, live in this session. */
    public static final class Proxied {
        public final String proxyIp;
        public final String phoneName;
        public final String cameraId;
        public final String cameraName;
        public final int proxyPort;
        public long lastSeenMs;

        Proxied(String proxyIp, String phoneName, String cameraId,
                String cameraName, int proxyPort, long lastSeenMs) {
            this.proxyIp = proxyIp;
            this.phoneName = phoneName;
            this.cameraId = cameraId;
            this.cameraName = cameraName;
            this.proxyPort = proxyPort;
            this.lastSeenMs = lastSeenMs;
        }

        /** The fetch URL: proxy phone + opaque id only. */
        public String url() {
            return "http://" + proxyIp + ":" + proxyPort + "/cam/" + cameraId;
        }

        /** Session key: proxy + camera id. */
        public String key() {
            return proxyIp + "|" + cameraId;
        }

        public String displayName() {
            return cameraName.isEmpty() ? "Shared camera" : cameraName;
        }
    }

    public interface Listener {
        /** The proxied set changed (main thread). */
        void onProxiedChanged(List<Proxied> proxied);
    }

    private final Handler main = new Handler(Looper.getMainLooper());
    private final Map<String, Proxied> live = new LinkedHashMap<>();
    private Listener listener;
    private volatile boolean listening;
    private DatagramSocket socket;
    private Thread listenThread;
    private Thread sweepThread;

    public void setListener(Listener listener) {
        this.listener = listener;
    }

    /** Starts listening for announcements. */
    public synchronized void start() {
        if (listening) return;
        listening = true;
        listenThread = new Thread(this::listenLoop, "cam-proxy-listen");
        listenThread.setDaemon(true);
        listenThread.start();
        sweepThread = new Thread(this::sweepLoop, "cam-proxy-sweep");
        sweepThread.setDaemon(true);
        sweepThread.start();
    }

    /** Stops listening and forgets every proxied camera. */
    public synchronized void stop() {
        listening = false;
        try {
            if (socket != null) socket.close();
        } catch (Exception ignored) {
        }
        if (listenThread != null) listenThread.interrupt();
        if (sweepThread != null) sweepThread.interrupt();
        synchronized (live) {
            live.clear();
        }
    }

    /** The currently live proxied cameras, freshest last. */
    public List<Proxied> current() {
        synchronized (live) {
            return new ArrayList<>(live.values());
        }
    }

    private void listenLoop() {
        try {
            socket = new DatagramSocket(CamProxy.UDP_PORT);
            socket.setBroadcast(true);
            socket.setSoTimeout(1000);
            byte[] buf = new byte[4096];
            while (listening) {
                DatagramPacket p = new DatagramPacket(buf, buf.length);
                try {
                    socket.receive(p);
                } catch (java.net.SocketTimeoutException e) {
                    continue;
                }
                String text = new String(p.getData(), 0, p.getLength(),
                        StandardCharsets.UTF_8);
                CamProxy.Announcement a = CamProxy.parseAnnouncement(text);
                if (a == null) continue;
                String proxyIp = p.getAddress().getHostAddress();
                long now = System.currentTimeMillis();
                boolean changed = false;
                synchronized (live) {
                    for (CamProxy.Shared s : a.cameras) {
                        String key = proxyIp + "|" + s.id;
                        Proxied existing = live.get(key);
                        if (existing == null) {
                            live.put(key, new Proxied(proxyIp, a.phoneName, s.id,
                                    s.name, a.tcpPort, now));
                            changed = true;
                        } else {
                            existing.lastSeenMs = now;
                        }
                    }
                }
                if (changed) notifyChanged();
            }
        } catch (Exception ignored) {
            // Socket closed on stop, or the port is taken:
            // proxied cameras simply stay undiscovered.
        }
    }

    private void sweepLoop() {
        while (listening) {
            try {
                Thread.sleep(5000);
            } catch (InterruptedException e) {
                return;
            }
            if (!listening) return;
            long now = System.currentTimeMillis();
            boolean changed = false;
            synchronized (live) {
                List<String> dead = new ArrayList<>();
                for (Map.Entry<String, Proxied> e : live.entrySet()) {
                    if (CamProxy.isStale(e.getValue().lastSeenMs, now)) {
                        dead.add(e.getKey());
                    }
                }
                for (String k : dead) {
                    live.remove(k);
                    changed = true;
                }
            }
            if (changed) notifyChanged();
        }
    }

    private void notifyChanged() {
        final List<Proxied> snapshot = current();
        main.post(() -> {
            if (listener != null) listener.onProxiedChanged(snapshot);
        });
    }

    /** Fetches one proxied snapshot on a background thread. */
    public static void fetchProxied(Proxied p, CamFetcher.SnapshotCallback cb) {
        new Thread(() -> {
            try {
                CamHttp.Response r = CamHttp.get(p.url(), null, null, null,
                        3000, 8000, 8 * 1024 * 1024);
                if (r.status != 200) {
                    cb.onFail("The sharing phone answered " + r.status, false);
                    return;
                }
                if (!CamFeed.jpegLooksValid(r.body)) {
                    cb.onFail("The sharing phone did not send a picture", false);
                    return;
                }
                cb.onOk(r.body);
            } catch (CamHttp.FetchException e) {
                cb.onFail(e.getMessage(), false);
            } catch (Exception e) {
                cb.onFail("Could not reach the sharing phone", false);
            }
        }, "cam-proxy-fetch").start();
    }
}
