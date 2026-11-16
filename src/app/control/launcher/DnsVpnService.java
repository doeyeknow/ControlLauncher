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
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Local VPN DNS filter.
 *
 * Design: the VPN advertises a virtual DNS server (10.0.0.1) and routes
 * only that one address into the tunnel, so the only traffic we ever see
 * is DNS queries. A query for a blocklisted domain is answered locally
 * with NXDOMAIN; anything else is forwarded verbatim to the upstream
 * resolver over a protected socket (so it bypasses the VPN) and the real
 * answer is written back. No browsing traffic passes through us at all.
 */
public class DnsVpnService extends VpnService {

    public static final String ACTION_START = "app.control.launcher.START_FILTER";
    public static final String ACTION_STOP = "app.control.launcher.STOP_FILTER";

    private static final String TAG = "DnsVpnService";
    private static final byte[] VIRTUAL_DNS = {10, 0, 0, 1};
    private static final String VIRTUAL_DNS_STR = "10.0.0.1";
    private static final String TUN_ADDR = "10.0.0.2";
    private static final int NOTIF_ID = 1;

    private ParcelFileDescriptor tun;
    private FileOutputStream tunOut;
    private Thread readerThread;
    private ExecutorService workers;
    private volatile boolean running;
    private final Object writeLock = new Object();
    private int ipId = 1;

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            shutdown();
            return START_NOT_STICKY;
        }
        if (running) return START_STICKY;
        startForeground(NOTIF_ID, buildNotification());
        if (!establish()) {
            shutdown();
            return START_NOT_STICKY;
        }
        running = true;
        Prefs.get(this).edit().putBoolean(Prefs.KEY_FILTER_ON, true).apply();
        workers = Executors.newCachedThreadPool();
        readerThread = new Thread(this::readLoop, "dns-reader");
        readerThread.start();
        Log.i(TAG, "filter running");
        OwnLog.log(this, "Ad filter", "Local DNS filter started");
        return START_STICKY;
    }

    private Notification buildNotification() {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (nm.getNotificationChannel("filter") == null) {
            nm.createNotificationChannel(new NotificationChannel(
                    "filter", "Ad filter", NotificationManager.IMPORTANCE_LOW));
        }
        PendingIntent open = PendingIntent.getActivity(this, 0,
                new Intent(this, MainActivity.class),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        return new Notification.Builder(this, "filter")
                .setContentTitle("Control ad filter is on")
                .setContentText("Ad and tracker lookups are being blocked on this device.")
                .setSmallIcon(R.drawable.ic_launcher_fg)
                .setContentIntent(open)
                .setOngoing(true)
                .build();
    }

    private boolean establish() {
        Builder b = new Builder();
        b.setSession("Control ad filter");
        b.addAddress(TUN_ADDR, 32);
        b.addDnsServer(VIRTUAL_DNS_STR);
        b.addRoute(VIRTUAL_DNS_STR, 32);
        b.setMtu(1500);
        // Per-app bypass: those apps' traffic (including their DNS) skips the VPN.
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
            // VPN consent revoked, or the interface cannot be created:
            // fail soft (filter stays OFF) instead of crashing the service.
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
            byte[] packet = new byte[n];
            System.arraycopy(buf, 0, packet, 0, n);
            handlePacket(packet);
        }
    }

    private void handlePacket(byte[] p) {
        // IPv4 + UDP + destination 10.0.0.1:53 only; anything else is dropped
        // (nothing else is routed into this tunnel by construction).
        if (p.length < 20 || (p[0] >> 4) != 4) return;
        int ihl = (p[0] & 0x0F) * 4;
        if (p.length < ihl + 8) return;
        if ((p[9] & 0xFF) != 17) return; // UDP
        if (p[16] != VIRTUAL_DNS[0] || p[17] != VIRTUAL_DNS[1]
                || p[18] != VIRTUAL_DNS[2] || p[19] != VIRTUAL_DNS[3]) return;
        int srcPort = u16(p, ihl);
        int dstPort = u16(p, ihl + 2);
        if (dstPort != 53) return;
        int udpLen = u16(p, ihl + 4);
        int dnsOff = ihl + 8;
        int dnsLen = Math.min(udpLen - 8, p.length - dnsOff);
        if (dnsLen < 12) return;
        byte[] dns = new byte[dnsLen];
        System.arraycopy(p, dnsOff, dns, 0, dnsLen);
        byte[] clientIp = {p[12], p[13], p[14], p[15]};

        String qname = parseQName(dns);
        if (qname != null && Blocklist.isBlocked(this, qname)) {
            byte[] reply = buildPacket(VIRTUAL_DNS, clientIp, 53, srcPort,
                    nxDomain(dns));
            writePacket(reply);
            long blocked = Prefs.get(this).getLong(Prefs.KEY_BLOCKED_COUNT, 0) + 1;
            Prefs.get(this).edit().putLong(Prefs.KEY_BLOCKED_COUNT, blocked).apply();
            return;
        }
        if (workers != null) {
            workers.execute(() -> forward(dns, clientIp, srcPort));
        }
    }

    private void forward(byte[] dns, byte[] clientIp, int clientPort) {
        DatagramSocket socket = null;
        try {
            socket = new DatagramSocket();
            if (!protect(socket)) {
                Log.w(TAG, "protect() refused");
                return;
            }
            socket.setSoTimeout(4000);
            InetAddress upstream = InetAddress.getByName(Prefs.upstreamDns(this));
            socket.send(new DatagramPacket(dns, dns.length, upstream, 53));
            byte[] rbuf = new byte[4096];
            DatagramPacket resp = new DatagramPacket(rbuf, rbuf.length);
            socket.receive(resp);
            byte[] payload = new byte[resp.getLength()];
            System.arraycopy(rbuf, 0, payload, 0, resp.getLength());
            writePacket(buildPacket(VIRTUAL_DNS, clientIp, 53, clientPort, payload));
        } catch (Exception e) {
            Log.w(TAG, "forward failed: " + e.getMessage());
        } finally {
            if (socket != null) socket.close();
        }
    }

    private void writePacket(byte[] packet) {
        if (packet == null) return;
        synchronized (writeLock) {
            try {
                if (tunOut != null) {
                    tunOut.write(packet);
                    tunOut.flush();
                }
            } catch (IOException e) {
                Log.w(TAG, "tun write failed", e);
            }
        }
    }

    private void shutdown() {
        if (running) OwnLog.log(this, "Ad filter", "Local DNS filter stopped");
        running = false;
        Prefs.get(this).edit().putBoolean(Prefs.KEY_FILTER_ON, false).apply();
        if (workers != null) workers.shutdownNow();
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
        shutdown();
        super.onRevoke();
    }

    @Override
    public void onDestroy() {
        running = false;
        super.onDestroy();
    }

    // ---- DNS helpers ----

    /** Extracts the query name from a DNS message, or null if unparseable. */
    static String parseQName(byte[] dns) {
        try {
            if (dns.length < 13) return null;
            StringBuilder sb = new StringBuilder();
            int i = 12;
            while (i < dns.length) {
                int len = dns[i] & 0xFF;
                if (len == 0) break;
                if (len > 63) return null; // compression in a query: not expected
                i++;
                for (int j = 0; j < len && i < dns.length; j++, i++) {
                    sb.append((char) (dns[i] & 0xFF));
                }
                sb.append('.');
            }
            if (sb.length() == 0) return null;
            return sb.substring(0, sb.length() - 1);
        } catch (Exception e) {
            return null;
        }
    }

    /** Turns a query into an NXDOMAIN response (keeps header + question). */
    static byte[] nxDomain(byte[] query) {
        // Find end of question: labels + null + qtype(2) + qclass(2).
        int i = 12;
        while (i < query.length && query[i] != 0) {
            i += (query[i] & 0xFF) + 1;
        }
        int qEnd = Math.min(query.length, i + 5);
        byte[] resp = new byte[qEnd];
        System.arraycopy(query, 0, resp, 0, qEnd);
        resp[2] = (byte) (0x80 | (query[2] & 0x01)); // QR=1, keep RD
        resp[3] = (byte) 0x83;                        // RA=1, RCODE=3 (NXDOMAIN)
        resp[6] = 0; resp[7] = 0;                     // ANCOUNT
        resp[8] = 0; resp[9] = 0;                     // NSCOUNT
        resp[10] = 0; resp[11] = 0;                   // ARCOUNT
        return resp;
    }

    // ---- IP/UDP packet building ----

    private byte[] buildPacket(byte[] srcIp, byte[] dstIp, int srcPort, int dstPort,
                               byte[] payload) {
        int udpLen = 8 + payload.length;
        int total = 20 + udpLen;
        byte[] p = new byte[total];
        p[0] = 0x45;                       // version + IHL(5)
        p[1] = 0;                          // DSCP/ECN
        put16(p, 2, total);
        put16(p, 4, (ipId = (ipId + 1) & 0xFFFF));
        put16(p, 6, 0);                    // flags/fragment
        p[8] = 64;                         // TTL
        p[9] = 17;                         // UDP
        System.arraycopy(srcIp, 0, p, 12, 4);
        System.arraycopy(dstIp, 0, p, 16, 4);
        put16(p, 10, checksum(p, 0, 20));
        int u = 20;
        put16(p, u, srcPort);
        put16(p, u + 2, dstPort);
        put16(p, u + 4, udpLen);
        put16(p, u + 6, 0);
        System.arraycopy(payload, 0, p, u + 8, payload.length);
        put16(p, u + 6, udpChecksum(p, srcIp, dstIp, payload));
        return p;
    }

    private static int udpChecksum(byte[] packet, byte[] srcIp, byte[] dstIp, byte[] payload) {
        int udpLen = 8 + payload.length;
        long sum = 0;
        sum += u16(srcIp, 0) + u16(srcIp, 2) + u16(dstIp, 0) + u16(dstIp, 2);
        sum += 17 + udpLen;
        // UDP header + payload as captured in the packet (checksum field is 0)
        for (int i = 20; i < 20 + udpLen; i += 2) {
            int hi = packet[i] & 0xFF;
            int lo = (i + 1 < 20 + udpLen) ? packet[i + 1] & 0xFF : 0;
            sum += (hi << 8) | lo;
        }
        while ((sum >> 16) != 0) sum = (sum & 0xFFFF) + (sum >> 16);
        int c = (int) (~sum & 0xFFFF);
        return c == 0 ? 0xFFFF : c;
    }

    private static int checksum(byte[] data, int off, int len) {
        long sum = 0;
        for (int i = off; i < off + len; i += 2) {
            int hi = data[i] & 0xFF;
            int lo = (i + 1 < off + len) ? data[i + 1] & 0xFF : 0;
            sum += (hi << 8) | lo;
        }
        while ((sum >> 16) != 0) sum = (sum & 0xFFFF) + (sum >> 16);
        return (int) (~sum & 0xFFFF);
    }

    static int u16(byte[] b, int off) {
        return ((b[off] & 0xFF) << 8) | (b[off + 1] & 0xFF);
    }

    private static void put16(byte[] b, int off, int v) {
        b[off] = (byte) (v >> 8);
        b[off + 1] = (byte) v;
    }
}
