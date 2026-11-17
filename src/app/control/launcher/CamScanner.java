package app.control.launcher;

import android.content.Context;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
import android.net.wifi.WifiManager;
import android.os.Handler;
import android.os.Looper;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The camera scan: three discovery layers, run together,
 * only while the user asked for a scan. Nothing here runs
 * in the background, and {@link #stop()} ends every layer
 * at once (the scan UI's Stop button, and the page going
 * away, both call it).
 *
 *   1. mDNS via NsdManager (the service types in
 *      CamDiscovery): quiet, fast, and the only layer
 *      that names devices without touching them.
 *   2. ONVIF WS-Discovery: a multicast Probe, replies
 *      parsed by CamOnvif. A MulticastLock is held while
 *      listening (CHANGE_WIFI_MULTICAST_STATE, a
 *      normal-level permission declared for exactly this).
 *   3. A bounded subnet sweep of the CURRENT /24 only:
 *      parallel TCP probes with short timeouts, capped
 *      at ~15s total, fingerprinting Server headers. Only
 *      hosts that look camera-like are reported.
 *
 * Results merge by IP (CamDiscovery) and stream to the
 * listener on the main thread as they arrive; onDone
 * fires once, when all three layers finish or the scan
 * is stopped. A layer that fails (no multicast route, no
 * NsdManager, no Wi-Fi address) ends quietly and the
 * other layers carry on — the scan never fails outright
 * because one road is closed.
 */
public final class CamScanner {

    public interface Listener {
        /** A device was found or enriched (main thread). */
        void onFound(CamDiscovery.Found found);
        /** All layers finished (main thread). */
        void onDone(List<CamDiscovery.Found> all);
    }

    private static final int SCAN_WINDOW_MS = 15000;
    private static final int ONVIF_LISTEN_MS = 4500;
    private static final int SWEEP_THREADS = 32;
    private static final int SWEEP_CONNECT_MS = 300;
    private static final int SWEEP_READ_MS = 700;
    private static final int[] SWEEP_PORTS = {80, 8080, 8000, 554, 443};

    private final Context app;
    private final Listener listener;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Map<String, CamDiscovery.Found> byIp = CamDiscovery.newMap();
    private final AtomicBoolean stopped = new AtomicBoolean(false);
    private final AtomicInteger layersLeft = new AtomicInteger(3);
    private final AtomicBoolean doneFired = new AtomicBoolean(false);

    private NsdManager nsd;
    private final List<NsdManager.DiscoveryListener> nsdListeners =
            Collections.synchronizedList(new ArrayList<NsdManager.DiscoveryListener>());
    private WifiManager.MulticastLock multicastLock;
    private DatagramSocket onvifSocket;
    private ExecutorService sweepPool;
    private Thread watchdog;

    public CamScanner(Context context, Listener listener) {
        this.app = context.getApplicationContext();
        this.listener = listener;
    }

    public void start() {
        startMdns();
        startOnvif();
        startSweep();
        watchdog = new Thread(() -> {
            try {
                Thread.sleep(SCAN_WINDOW_MS);
            } catch (InterruptedException ignored) {
                return;
            }
            stop();
        }, "cam-scan-watchdog");
        watchdog.setDaemon(true);
        watchdog.start();
    }

    public void stop() {
        if (!stopped.compareAndSet(false, true)) return;
        try {
            if (nsd != null) {
                for (NsdManager.DiscoveryListener l : nsdListeners) {
                    try {
                        nsd.stopServiceDiscovery(l);
                    } catch (Exception ignored) {
                    }
                }
            }
        } catch (Exception ignored) {
        }
        try {
            if (onvifSocket != null) onvifSocket.close();
        } catch (Exception ignored) {
        }
        try {
            if (multicastLock != null && multicastLock.isHeld()) multicastLock.release();
        } catch (Exception ignored) {
        }
        if (sweepPool != null) sweepPool.shutdownNow();
        if (watchdog != null) watchdog.interrupt();
        finish();
    }

    private void layerDone() {
        if (layersLeft.decrementAndGet() <= 0) {
            finish();
        }
    }

    private void finish() {
        if (!doneFired.compareAndSet(false, true)) return;
        final List<CamDiscovery.Found> all;
        synchronized (byIp) {
            all = CamDiscovery.ordered(byIp);
        }
        main.post(() -> listener.onDone(all));
    }

    private void report(CamDiscovery.Found f) {
        main.post(() -> {
            if (!stopped.get()) listener.onFound(f);
        });
    }

    private CamDiscovery.Found merge(String ip, int source, String name, int nameRank,
                                     String vendor, List<Integer> ports,
                                     List<String> xaddrs, List<String> mdnsServices) {
        CamDiscovery.Found f;
        synchronized (byIp) {
            f = CamDiscovery.merge(byIp, ip, source, name, nameRank, vendor,
                    ports, xaddrs, mdnsServices);
        }
        report(f);
        return f;
    }

    // ---- layer 1: mDNS ----

    private void startMdns() {
        try {
            nsd = (NsdManager) app.getSystemService(Context.NSD_SERVICE);
        } catch (Exception e) {
            nsd = null;
        }
        if (nsd == null) {
            layerDone();
            return;
        }
        final AtomicInteger pending = new AtomicInteger(CamDiscovery.MDNS_SERVICES.length);
        for (String serviceType : CamDiscovery.MDNS_SERVICES) {
            NsdManager.DiscoveryListener dl = new NsdManager.DiscoveryListener() {
                @Override public void onDiscoveryStarted(String t) { }
                @Override public void onDiscoveryStopped(String t) { }

                @Override
                public void onServiceFound(NsdServiceInfo info) {
                    if (stopped.get()) return;
                    resolveMdns(info, serviceType);
                }

                @Override public void onServiceLost(NsdServiceInfo info) { }

                @Override
                public void onStartDiscoveryFailed(String t, int errorCode) {
                    if (pending.decrementAndGet() <= 0) layerDone();
                }

                @Override
                public void onStopDiscoveryFailed(String t, int errorCode) { }
            };
            nsdListeners.add(dl);
            try {
                nsd.discoverServices(serviceType, NsdManager.PROTOCOL_DNS_SD, dl);
            } catch (Exception e) {
                if (pending.decrementAndGet() <= 0) layerDone();
            }
        }
        // mDNS has no natural end: the watchdog's stop()
        // ends it, and layerDone is reached through the
        // pending count only when every type fails to
        // start. To keep the countdown honest, a timer
        // ends this layer at the scan window even if the
        // watchdog is somehow beaten.
        new Thread(() -> {
            try {
                Thread.sleep(SCAN_WINDOW_MS);
            } catch (InterruptedException ignored) {
            }
            if (pending.get() > 0) {
                pending.set(0);
                layerDone();
            }
        }, "cam-scan-mdns-timer").start();
    }

    private void resolveMdns(NsdServiceInfo info, String serviceType) {
        try {
            nsd.resolveService(info, new NsdManager.ResolveListener() {
                @Override
                public void onResolveFailed(NsdServiceInfo i, int errorCode) { }

                @Override
                public void onServiceResolved(NsdServiceInfo resolved) {
                    if (stopped.get()) return;
                    InetAddress host = resolved.getHost();
                    if (host == null) return;
                    String ip = host.getHostAddress();
                    String instance = resolved.getServiceName();
                    boolean cameraType = CamDiscovery.mdnsMarksCamera(serviceType);
                    boolean cameraName = CamDiscovery.mdnsInstanceLooksCamera(instance);
                    if (!cameraType && !cameraName) return;
                    String name = displayNameFromInstance(instance, serviceType);
                    List<Integer> ports = new ArrayList<>();
                    if (resolved.getPort() > 0) ports.add(resolved.getPort());
                    List<String> services = new ArrayList<>();
                    services.add(serviceType);
                    merge(ip, CamDiscovery.SRC_MDNS, name, 2,
                            CamDiscovery.guessVendor(instance, serviceType),
                            ports, null, services);
                }
            });
        } catch (Exception ignored) {
        }
    }

    /** Strips the "._http._tcp." suffix (and the local
     *  domain) from an mDNS instance name for display. */
    static String displayNameFromInstance(String instance, String serviceType) {
        if (instance == null) return "";
        String suffix = "." + serviceType;
        int at = instance.indexOf(suffix);
        String base = at >= 0 ? instance.substring(0, at) : instance;
        if (base.endsWith(".local")) base = base.substring(0, base.length() - 6);
        return base.replace('_', ' ').trim();
    }

    // ---- layer 2: ONVIF WS-Discovery ----

    private void startOnvif() {
        new Thread(() -> {
            try {
                WifiManager wifi = (WifiManager) app.getSystemService(Context.WIFI_SERVICE);
                if (wifi != null) {
                    multicastLock = wifi.createMulticastLock("control-cam-scan");
                    multicastLock.setReferenceCounted(true);
                    try {
                        multicastLock.acquire();
                    } catch (Exception ignored) {
                    }
                }
                onvifSocket = new DatagramSocket();
                onvifSocket.setSoTimeout(500);
                byte[] probe = CamOnvif.probeEnvelope()
                        .getBytes(StandardCharsets.UTF_8);
                InetAddress group = InetAddress.getByName(CamOnvif.MULTICAST_ADDRESS);
                long deadline = System.currentTimeMillis() + ONVIF_LISTEN_MS;
                long secondProbeAt = System.currentTimeMillis() + 1000;
                boolean secondSent = false;
                onvifSocket.send(new DatagramPacket(probe, probe.length,
                        group, CamOnvif.MULTICAST_PORT));
                byte[] buf = new byte[8192];
                while (!stopped.get() && System.currentTimeMillis() < deadline) {
                    if (!secondSent && System.currentTimeMillis() >= secondProbeAt) {
                        secondSent = true;
                        try {
                            onvifSocket.send(new DatagramPacket(probe, probe.length,
                                    group, CamOnvif.MULTICAST_PORT));
                        } catch (Exception ignored) {
                        }
                    }
                    DatagramPacket p = new DatagramPacket(buf, buf.length);
                    try {
                        onvifSocket.receive(p);
                    } catch (java.net.SocketTimeoutException e) {
                        continue;
                    }
                    String xml = new String(p.getData(), 0, p.getLength(),
                            StandardCharsets.UTF_8);
                    String senderIp = p.getAddress().getHostAddress();
                    for (CamOnvif.Device d : CamOnvif.parseProbeMatches(xml)) {
                        String name = CamOnvif.nameFromScopes(d.scopes);
                        String vendor = CamDiscovery.guessVendor(
                                CamOnvif.hardwareFromScopes(d.scopes),
                                String.join(" ", d.scopes));
                        List<Integer> ports = new ArrayList<>();
                        for (String x : d.xaddrs) {
                            int port = portOf(x);
                            if (port > 0) ports.add(port);
                        }
                        merge(senderIp, CamDiscovery.SRC_ONVIF, name, 3, vendor,
                                ports, d.xaddrs, null);
                    }
                }
            } catch (Exception ignored) {
                // No multicast route, no socket, no replies:
                // the layer ends quietly.
            } finally {
                try {
                    if (onvifSocket != null) onvifSocket.close();
                } catch (Exception ignored) {
                }
                try {
                    if (multicastLock != null && multicastLock.isHeld()) {
                        multicastLock.release();
                    }
                } catch (Exception ignored) {
                }
                layerDone();
            }
        }, "cam-scan-onvif").start();
    }

    /** The port of an XAddr URI, or -1. */
    static int portOf(String uri) {
        if (uri == null) return -1;
        try {
            java.net.URI u = new java.net.URI(uri);
            int p = u.getPort();
            if (p > 0) return p;
            String scheme = u.getScheme();
            if ("http".equalsIgnoreCase(scheme)) return 80;
            if ("https".equalsIgnoreCase(scheme)) return 443;
            return -1;
        } catch (Exception e) {
            return -1;
        }
    }

    // ---- layer 3: subnet sweep ----

    private void startSweep() {
        new Thread(() -> {
            try {
                String selfIp = currentIpv4();
                List<String> hosts = CamDiscovery.hosts24(selfIp);
                if (hosts.isEmpty()) {
                    layerDone();
                    return;
                }
                sweepPool = Executors.newFixedThreadPool(SWEEP_THREADS);
                final AtomicInteger remaining = new AtomicInteger(hosts.size());
                for (String host : hosts) {
                    if (stopped.get()) break;
                    sweepPool.execute(() -> {
                        try {
                            probeHost(host);
                        } finally {
                            if (remaining.decrementAndGet() <= 0) {
                                layerDone();
                            }
                        }
                    });
                }
                sweepPool.shutdown();
                try {
                    sweepPool.awaitTermination(SCAN_WINDOW_MS, TimeUnit.MILLISECONDS);
                } catch (InterruptedException ignored) {
                }
            } catch (Exception ignored) {
                layerDone();
            }
        }, "cam-scan-sweep").start();
    }

    private void probeHost(String host) {
        if (stopped.get()) return;
        List<Integer> openPorts = new ArrayList<>();
        boolean rtspOpen = false;
        String serverHeader = "";
        String bodyHint = "";
        for (int port : SWEEP_PORTS) {
            if (stopped.get()) return;
            Socket s = new Socket();
            try {
                s.connect(new InetSocketAddress(host, port), SWEEP_CONNECT_MS);
                openPorts.add(port);
                if (port == 554) {
                    rtspOpen = true;
                    continue;
                }
                if (port == 443) continue; // answers, but says nothing
                s.setSoTimeout(SWEEP_READ_MS);
                OutputStream out = s.getOutputStream();
                out.write(("GET / HTTP/1.0\r\nHost: " + host + "\r\n\r\n")
                        .getBytes(StandardCharsets.ISO_8859_1));
                out.flush();
                InputStream in = s.getInputStream();
                byte[] buf = new byte[4096];
                int read = 0;
                try {
                    while (read < buf.length) {
                        int n = in.read(buf, read, buf.length - read);
                        if (n < 0) break;
                        read += n;
                        if (read > 1024) break;
                    }
                } catch (Exception ignored) {
                }
                if (read > 0) {
                    String head = new String(buf, 0, read, StandardCharsets.ISO_8859_1);
                    serverHeader = headerValue(head, "Server");
                    bodyHint = head.length() > 2048 ? head.substring(0, 2048) : head;
                }
            } catch (Exception ignored) {
                // closed/filtered: not a candidate on this port
            } finally {
                try {
                    s.close();
                } catch (Exception ignored) {
                }
            }
        }
        if (openPorts.isEmpty()) return;
        if (!CamDiscovery.sweepLooksLikeCamera(rtspOpen, serverHeader, bodyHint)) return;
        merge(host, CamDiscovery.SRC_SWEEP, "", 0,
                CamDiscovery.guessVendor(serverHeader, bodyHint),
                openPorts, null, null);
    }

    private static String headerValue(String head, String name) {
        String lower = head.toLowerCase();
        String key = name.toLowerCase() + ":";
        int at = lower.indexOf(key);
        if (at < 0) return "";
        int i = at + key.length();
        while (i < head.length() && (head.charAt(i) == ' ' || head.charAt(i) == '\t')) i++;
        int end = head.indexOf('\r', i);
        if (end < 0) end = head.indexOf('\n', i);
        if (end < 0) end = head.length();
        return head.substring(i, end).trim();
    }

    /** This phone's IPv4 address on the current network:
     *  the Wi-Fi connection's address first, else the first
     *  site-local address on an up interface. "" when none
     *  (the sweep then has no subnet to sweep). */
    private String currentIpv4() {
        try {
            WifiManager wifi = (WifiManager) app.getSystemService(Context.WIFI_SERVICE);
            if (wifi != null && wifi.getConnectionInfo() != null) {
                int ip = wifi.getConnectionInfo().getIpAddress();
                if (ip != 0) {
                    return (ip & 0xFF) + "." + ((ip >> 8) & 0xFF) + "."
                            + ((ip >> 16) & 0xFF) + "." + ((ip >> 24) & 0xFF);
                }
            }
        } catch (Exception ignored) {
        }
        try {
            Enumeration<NetworkInterface> ifs = NetworkInterface.getNetworkInterfaces();
            while (ifs.hasMoreElements()) {
                NetworkInterface ni = ifs.nextElement();
                if (!ni.isUp() || ni.isLoopback()) continue;
                Enumeration<InetAddress> addrs = ni.getInetAddresses();
                while (addrs.hasMoreElements()) {
                    InetAddress a = addrs.nextElement();
                    if (a instanceof java.net.Inet4Address && a.isSiteLocalAddress()) {
                        return a.getHostAddress();
                    }
                }
            }
        } catch (Exception ignored) {
        }
        return "";
    }
}
