package app.control.launcher;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Intent;
import android.net.VpnService;
import android.os.ParcelFileDescriptor;
import android.util.Log;

import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.Socket;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Set;

/**
 * The offline phone's side of mesh internet (v1.10): a VpnService
 * routing default traffic (0.0.0.0/0) into a TUN interface, with a
 * userspace TCP/UDP stack (NatEngine) forwarding everything
 * through the gateway's SOCKS relay.
 *
 * VPN-slot honesty: this service and the DNS filter cannot run
 * together (one slot). The coordinator parks the filter before
 * starting us, and this service applies the blocklist itself —
 * blocked DNS queries get a local NXDOMAIN from the engine, so
 * ad blocking stays on while mesh internet runs. The blocked
 * counter is shared with the filter (same pref key).
 *
 * Every mesh socket is protected (VpnService.protect) so its
 * traffic bypasses this very TUN instead of looping into it.
 */
public class NatVpnService extends VpnService implements NatEngine.Host {

    public static final String ACTION_START = "app.control.launcher.START_MESH_NAT";
    public static final String ACTION_STOP = "app.control.launcher.STOP_MESH_NAT";
    public static final String EXTRA_GW_ADDR = "mesh_gw_addr";
    public static final String EXTRA_GW_PORT = "mesh_gw_port";
    public static final String EXTRA_GW_PUB = "mesh_gw_pub";
    public static final String EXTRA_GW_NAME = "mesh_gw_name";

    private static final String TAG = "NatVpnService";
    private static final int NOTIF_ID = 4;
    private static final int BACKPRESSURE_BYTES = 256 * 1024;

    private ParcelFileDescriptor tun;
    private FileOutputStream tunOut;
    private final Object writeLock = new Object();
    private volatile boolean running;
    private Thread readerThread;
    private Thread tickerThread;
    private NatEngine engine;
    private SocksClient socks;
    private SocksClient.UdpRelay udpRelay;
    private final Map<TcpFlow, SocksClient.TcpConn> conns = new IdentityHashMap<>();

    private String gwName = "a nearby phone";
    private String gwPubB64;
    private boolean failed;
    private boolean stopLogged;

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            shutdown();
            return START_NOT_STICKY;
        }
        if (running) return START_STICKY;
        String gwAddr = intent != null ? intent.getStringExtra(EXTRA_GW_ADDR) : null;
        int gwPort = intent != null ? intent.getIntExtra(EXTRA_GW_PORT, 0) : 0;
        gwPubB64 = intent != null ? intent.getStringExtra(EXTRA_GW_PUB) : null;
        String name = intent != null ? intent.getStringExtra(EXTRA_GW_NAME) : null;
        if (name != null && !name.isEmpty()) gwName = name;
        if (gwAddr == null || gwPort <= 0 || gwPubB64 == null) {
            fail("missing gateway details");
            return START_NOT_STICKY;
        }
        startForeground(NOTIF_ID, buildNotification());
        if (!establish()) {
            fail("could not create the VPN interface");
            return START_NOT_STICKY;
        }
        try {
            InetAddress gw = InetAddress.getByName(gwAddr);
            byte[] seed = Profile.relaySeedBytes(this);
            byte[] pub = RelayKeys.publicFromSeed(seed);
            byte[] gwPub = RelayKeys.b64UrlDecode(gwPubB64);
            socks = new SocksClient(gw, gwPort, seed, pub, gwPub,
                    new SocksClient.Protector() {
                        @Override
                        public void protect(Socket s) throws IOException {
                            if (!NatVpnService.this.protect(s)) {
                                throw new IOException("protect() refused socket");
                            }
                        }

                        @Override
                        public void protect(DatagramSocket s) throws IOException {
                            if (!NatVpnService.this.protect(s)) {
                                throw new IOException("protect() refused datagram socket");
                            }
                        }
                    });
        } catch (Exception e) {
            fail("bad gateway identity: " + e.getMessage());
            return START_NOT_STICKY;
        }
        engine = new NatEngine(this);
        running = true;
        udpRelay = socks.openUdp(new SocksClient.UdpCallback() {
            @Override
            public void onDatagram(int srcIp, int srcPort, byte[] payload) {
                NatEngine e = engine;
                if (e != null) e.onUdpReply(srcIp, srcPort, payload, System.currentTimeMillis());
            }

            @Override
            public void onClosed() {
                // The gateway went away: stop NAT so the coordinator
                // can restore the filter or find another gateway.
                if (running) fail("gateway connection lost");
            }
        });
        readerThread = new Thread(this::readLoop, "nat-reader");
        readerThread.start();
        tickerThread = new Thread(this::tickLoop, "nat-ticker");
        tickerThread.start();
        Prefs.get(this).edit().putBoolean(Prefs.KEY_MESH_NAT_ON, true).apply();
        OwnLog.log(this, "Mesh internet", "Using mesh internet via " + gwName
                + " (ad blocking included)");
        Log.i(TAG, "NAT running via gateway");
        return START_STICKY;
    }

    private Notification buildNotification() {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (nm.getNotificationChannel("mesh-nat") == null) {
            nm.createNotificationChannel(new NotificationChannel(
                    "mesh-nat", "Mesh internet (VPN)", NotificationManager.IMPORTANCE_LOW));
        }
        PendingIntent open = PendingIntent.getActivity(this, 0,
                new Intent(this, MainActivity.class),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        return new Notification.Builder(this, "mesh-nat")
                .setContentTitle("Using mesh internet via " + gwName)
                .setContentText("Internet is coming through a nearby phone. Ad blocking is on.")
                .setSmallIcon(R.drawable.ic_launcher_fg)
                .setContentIntent(open)
                .setOngoing(true)
                .build();
    }

    private boolean establish() {
        Builder b = new Builder();
        b.setSession("Control mesh internet");
        b.addAddress("10.0.0.2", 32);
        b.addDnsServer("10.0.0.1");
        b.addRoute("0.0.0.0", 0);
        b.setMtu(NatEngine.MTU);
        // Same per-app bypass list as the filter: bypassed apps keep
        // their own path instead of the mesh.
        Set<String> bypass = Prefs.getSet(this, Prefs.KEY_BYPASS_APPS);
        for (String pkg : bypass) {
            try {
                b.addDisallowedApplication(pkg);
            } catch (Exception e) {
                Log.w(TAG, "bypass failed for " + pkg, e);
            }
        }
        try {
            b.addDisallowedApplication(getPackageName());
        } catch (Exception ignored) {
        }
        try {
            tun = b.establish();
        } catch (SecurityException | IllegalStateException e) {
            Log.e(TAG, "establish() threw", e);
            return false;
        }
        if (tun == null) {
            Log.e(TAG, "establish() returned null");
            return false;
        }
        tunOut = new FileOutputStream(tun.getFileDescriptor());
        return true;
    }

    private void readLoop() {
        FileInputStream in = new FileInputStream(tun.getFileDescriptor());
        byte[] buf = new byte[32767];
        while (running) {
            int n;
            try {
                n = in.read(buf);
            } catch (IOException e) {
                if (running) Log.w(TAG, "tun read failed", e);
                break;
            }
            if (n <= 0) continue;
            NatEngine e = engine;
            if (e == null) continue;
            // Copy: the engine may hold the bytes past this read.
            byte[] packet = new byte[n];
            System.arraycopy(buf, 0, packet, 0, n);
            try {
                e.onPacket(packet, n, System.currentTimeMillis());
            } catch (Exception ex) {
                Log.w(TAG, "engine packet failed: " + ex.getMessage());
            }
        }
    }

    private void tickLoop() {
        while (running) {
            try {
                Thread.sleep(5000);
            } catch (InterruptedException e) {
                break;
            }
            NatEngine e = engine;
            if (e != null && running) {
                try {
                    e.onTick(System.currentTimeMillis());
                } catch (Exception ex) {
                    Log.w(TAG, "engine tick failed: " + ex.getMessage());
                }
            }
        }
    }

    // ---- NatEngine.Host ----

    @Override
    public void openTcp(TcpFlow flow, int dstIp, int dstPort) {
        SocksClient client = socks;
        if (client == null) {
            engine.tcpFailed(flow, System.currentTimeMillis());
            return;
        }
        SocksClient.TcpConn conn = client.connectTcp(dstIp, dstPort,
                new SocksClient.TcpCallbacks() {
                    @Override
                    public void onConnected() {
                        NatEngine e = engine;
                        if (e != null) e.tcpConnected(flow, System.currentTimeMillis());
                    }

                    @Override
                    public void onFailed() {
                        NatEngine e = engine;
                        if (e != null) e.tcpFailed(flow, System.currentTimeMillis());
                    }

                    @Override
                    public void onData(byte[] data, int off, int len) {
                        // Backpressure (v1-simple): pause while this
                        // flow holds more than 256 KB unacked, so the
                        // gateway's buffers wait instead of ours
                        // growing without bound.
                        while (running && flow.backlogBytes() > BACKPRESSURE_BYTES) {
                            try {
                                Thread.sleep(20);
                            } catch (InterruptedException e) {
                                Thread.currentThread().interrupt();
                                return;
                            }
                        }
                        NatEngine e = engine;
                        if (e != null) {
                            e.tcpData(flow, data, off, len, System.currentTimeMillis());
                        }
                    }

                    @Override
                    public void onClosed() {
                        NatEngine e = engine;
                        if (e != null) e.tcpClosed(flow, System.currentTimeMillis());
                    }
                });
        synchronized (conns) {
            conns.put(flow, conn);
        }
    }

    @Override
    public void writeRemote(TcpFlow flow, byte[] data) {
        SocksClient.TcpConn conn;
        synchronized (conns) {
            conn = conns.get(flow);
        }
        if (conn == null) return;
        try {
            conn.write(data);
        } catch (IOException ignored) {
            // The pump thread reports the close; nothing to do here.
        }
    }

    @Override
    public void closeRemote(TcpFlow flow, boolean half) {
        SocksClient.TcpConn conn;
        synchronized (conns) {
            conn = half ? conns.get(flow) : conns.remove(flow);
        }
        if (conn == null) return;
        if (half) {
            conn.shutdownOutput();
        } else {
            conn.close();
        }
    }

    @Override
    public void sendUdp(int dstIp, int dstPort, byte[] payload) {
        SocksClient.UdpRelay r = udpRelay;
        if (r != null) r.send(dstIp, dstPort, payload);
    }

    @Override
    public boolean isBlocked(String host) {
        return Blocklist.isBlocked(this, host);
    }

    @Override
    public void emit(byte[] ipPacket) {
        synchronized (writeLock) {
            try {
                if (tunOut != null) {
                    tunOut.write(ipPacket);
                    tunOut.flush();
                }
            } catch (IOException e) {
                Log.w(TAG, "tun write failed", e);
            }
        }
    }

    @Override
    public void onBlockedQuery() {
        long blocked = Prefs.get(this).getLong(Prefs.KEY_BLOCKED_COUNT, 0) + 1;
        Prefs.get(this).edit().putLong(Prefs.KEY_BLOCKED_COUNT, blocked).apply();
    }

    // ---- lifecycle ----

    /** Failure path: report, restore a parked filter, stop. */
    private void fail(String reason) {
        if (failed) return;
        failed = true;
        stopLogged = true;
        Log.w(TAG, "NAT failed: " + reason);
        OwnLog.log(this, "Mesh internet", "Mesh internet stopped (" + reason + ")");
        MeshInternet.onNatFailed(this, gwPubB64);
        shutdown();
    }

    private void shutdown() {
        boolean wasRunning = running;
        running = false;
        if (wasRunning && !stopLogged) {
            OwnLog.log(this, "Mesh internet", "Mesh internet stopped");
        }
        if (engine != null) {
            try {
                engine.shutdown(System.currentTimeMillis());
            } catch (Exception ignored) {
            }
            engine = null;
        }
        if (udpRelay != null) {
            udpRelay.close();
            udpRelay = null;
        }
        synchronized (conns) {
            for (SocksClient.TcpConn c : conns.values()) c.close();
            conns.clear();
        }
        try {
            if (tun != null) tun.close();
        } catch (IOException ignored) {
        }
        tun = null;
        tunOut = null;
        stopForeground(true);
        stopSelf();
    }

    @Override
    public void onRevoke() {
        MeshInternet.onNatRevoked(this);
        shutdown();
        super.onRevoke();
    }

    @Override
    public void onDestroy() {
        running = false;
        super.onDestroy();
    }
}
