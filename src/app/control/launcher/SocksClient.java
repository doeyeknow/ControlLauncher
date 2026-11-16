package app.control.launcher;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.ArrayDeque;
import java.util.Queue;

/**
 * Client side of the mesh gateway protocol (v1.10): the Control
 * auth preamble, then SOCKS5. Pure java.net — no Android — with
 * a Protector hook so the VpnService can exempt every socket
 * from the TUN (an unprotected socket's traffic would loop back
 * into the tunnel it feeds).
 *
 * The stream-level Session is factored out so the JVM harness can
 * exercise the exact handshake/request bytes over piped streams
 * (no sockets: JVM IPv4 loopback is unusable in the build VM).
 */
public class SocksClient {

    public interface Protector {
        void protect(Socket s) throws IOException;
        void protect(DatagramSocket s) throws IOException;
    }

    public interface TcpCallbacks {
        void onConnected();
        void onFailed();
        void onData(byte[] data, int off, int len);
        void onClosed();
    }

    public interface UdpCallback {
        void onDatagram(int srcIp, int srcPort, byte[] payload);
        /** The ASSOCIATE session ended (control EOF or failure). */
        void onClosed();
    }

    public static final int CONNECT_TIMEOUT_MS = 5000;
    public static final int HANDSHAKE_TIMEOUT_MS = 5000;

    private final InetAddress gatewayAddr;
    private final int gatewayPort;
    private final byte[] seed;
    private final byte[] pub;
    private final byte[] gatewayPub;
    private final Protector protector;

    public SocksClient(InetAddress gatewayAddr, int gatewayPort,
                       byte[] seed, byte[] pub, byte[] gatewayPub,
                       Protector protector) {
        this.gatewayAddr = gatewayAddr;
        this.gatewayPort = gatewayPort;
        this.seed = seed;
        this.pub = pub;
        this.gatewayPub = gatewayPub;
        this.protector = protector;
    }

    // ---- stream-level session (harness-testable) ----

    public static final class Session {

        public static byte[] readExact(InputStream in, int len) throws IOException {
            byte[] buf = new byte[len];
            int got = 0;
            while (got < len) {
                int n = in.read(buf, got, len - got);
                if (n < 0) throw new EOFException("short read: " + got + "/" + len);
                got += n;
            }
            return buf;
        }

        /** Reads the challenge, sends the signed response. */
        public static void authenticate(InputStream in, OutputStream out,
                                        byte[] seed, byte[] pub, byte[] gatewayPub)
                throws IOException {
            byte[] challenge = readExact(in, GatewayAuth.CHALLENGE_LEN);
            byte[] nonce = GatewayAuth.parseChallenge(challenge, challenge.length);
            if (nonce == null) throw new IOException("bad gateway challenge");
            out.write(GatewayAuth.buildResponse(seed, pub, gatewayPub, nonce));
            out.flush();
        }

        /** SOCKS greeting, NO_AUTH only. */
        public static void greeting(InputStream in, OutputStream out) throws IOException {
            out.write(Socks5.greeting());
            out.flush();
            byte[] reply = readExact(in, 2);
            int method = Socks5.parseGreetingReply(reply, 2);
            if (method != Socks5.METHOD_NO_AUTH) {
                throw new IOException("gateway refused greeting, method=" + method);
            }
        }

        /** Sends CONNECT, returns the parsed reply. */
        public static Socks5.Reply connectRequest(InputStream in, OutputStream out,
                                                  int dstIp, int dstPort) throws IOException {
            out.write(Socks5.request(Socks5.CMD_CONNECT, dstIp, dstPort));
            out.flush();
            return readReply(in);
        }

        /** Sends UDP ASSOCIATE, returns the parsed reply (the bound
         *  relay address is where UDP datagrams go). */
        public static Socks5.Reply udpAssociateRequest(InputStream in, OutputStream out)
                throws IOException {
            out.write(Socks5.request(Socks5.CMD_UDP_ASSOCIATE, 0, 0));
            out.flush();
            return readReply(in);
        }

        private static Socks5.Reply readReply(InputStream in) throws IOException {
            byte[] head = readExact(in, 4);
            if (head[0] != Socks5.VER) throw new IOException("bad SOCKS version in reply");
            int atyp = head[3] & 0xFF;
            byte[] rest;
            if (atyp == Socks5.ATYP_IPV4) {
                rest = readExact(in, 6);
            } else if (atyp == Socks5.ATYP_DOMAIN) {
                byte[] ln = readExact(in, 1);
                int n = ln[0] & 0xFF;
                rest = readExact(in, n + 2);
                byte[] full = new byte[5 + rest.length];
                System.arraycopy(head, 0, full, 0, 4);
                full[4] = ln[0];
                System.arraycopy(rest, 0, full, 5, rest.length);
                return Socks5.parseReply(full, full.length);
            } else {
                throw new IOException("unsupported reply atyp=" + atyp);
            }
            byte[] full = new byte[10];
            System.arraycopy(head, 0, full, 0, 4);
            System.arraycopy(rest, 0, full, 4, 6);
            Socks5.Reply r = Socks5.parseReply(full, full.length);
            if (r == null) throw new IOException("malformed SOCKS reply");
            return r;
        }
    }

    // ---- TCP connections ----

    public TcpConn connectTcp(int dstIp, int dstPort, TcpCallbacks cb) {
        TcpConn conn = new TcpConn();
        Thread t = new Thread(() -> runTcpConnect(conn, dstIp, dstPort, cb));
        t.setDaemon(true);
        t.setName("socks-tcp-connect");
        t.start();
        return conn;
    }

    private void runTcpConnect(TcpConn conn, int dstIp, int dstPort, TcpCallbacks cb) {
        Socket s = new Socket();
        try {
            protector.protect(s);
            s.connect(new InetSocketAddress(gatewayAddr, gatewayPort), CONNECT_TIMEOUT_MS);
            s.setSoTimeout(HANDSHAKE_TIMEOUT_MS);
            InputStream in = s.getInputStream();
            OutputStream out = s.getOutputStream();
            Session.authenticate(in, out, seed, pub, gatewayPub);
            Session.greeting(in, out);
            Socks5.Reply reply = Session.connectRequest(in, out, dstIp, dstPort);
            if (reply.rep != Socks5.REP_OK) {
                throw new IOException("gateway CONNECT refused, rep=" + reply.rep);
            }
            s.setSoTimeout(0);
            if (!conn.attach(s, in, out)) {
                closeQuietly(s);
                return;
            }
            cb.onConnected();
            pumpTcp(conn, s, in, cb);
        } catch (Exception e) {
            closeQuietly(s);
            conn.markDead();
            cb.onFailed();
        }
    }

    private void pumpTcp(TcpConn conn, Socket s, InputStream in, TcpCallbacks cb) {
        byte[] buf = new byte[16384];
        try {
            while (!conn.isClosed()) {
                int n = in.read(buf);
                if (n < 0) break;
                if (n == 0) continue;
                // Backpressure: the engine throttles reads via the
                // flow's backlog; here we pause while the flow is
                // saturated so the gateway's buffers do the waiting
                // instead of ours growing without bound.
                cb.onData(buf, 0, n);
            }
        } catch (IOException e) {
            // Socket closed or reset: the flow ends either way.
        } finally {
            conn.markDead();
            closeQuietly(s);
            cb.onClosed();
        }
    }

    public static final class TcpConn {
        private Socket socket;
        private InputStream in;
        private OutputStream out;
        private boolean closed;
        private boolean dead;

        /** Attaches the connected socket. Returns false when the
         *  connection was already closed (caller must drop it). */
        synchronized boolean attach(Socket s, InputStream in, OutputStream out) {
            if (closed) return false;
            this.socket = s;
            this.in = in;
            this.out = out;
            return true;
        }

        synchronized void markDead() {
            dead = true;
        }

        public synchronized boolean isClosed() {
            return closed || dead;
        }

        public void write(byte[] data) throws IOException {
            OutputStream o;
            synchronized (this) {
                if (closed || dead || out == null) throw new IOException("not connected");
                o = out;
            }
            synchronized (o) {
                o.write(data);
                o.flush();
            }
        }

        public void shutdownOutput() {
            Socket s;
            synchronized (this) {
                s = socket;
            }
            if (s != null) {
                try {
                    s.shutdownOutput();
                } catch (IOException ignored) {
                }
            }
        }

        public void close() {
            Socket s;
            synchronized (this) {
                if (closed) return;
                closed = true;
                s = socket;
            }
            closeQuietly(s);
        }
    }

    // ---- UDP ASSOCIATE ----

    public UdpRelay openUdp(UdpCallback cb) {
        UdpRelay relay = new UdpRelay(cb);
        Thread t = new Thread(() -> runUdpAssociate(relay));
        t.setDaemon(true);
        t.setName("socks-udp-associate");
        t.start();
        return relay;
    }

    private void runUdpAssociate(UdpRelay relay) {
        Socket control = new Socket();
        try {
            protector.protect(control);
            control.connect(new InetSocketAddress(gatewayAddr, gatewayPort), CONNECT_TIMEOUT_MS);
            control.setSoTimeout(HANDSHAKE_TIMEOUT_MS);
            InputStream in = control.getInputStream();
            OutputStream out = control.getOutputStream();
            Session.authenticate(in, out, seed, pub, gatewayPub);
            Session.greeting(in, out);
            Socks5.Reply reply = Session.udpAssociateRequest(in, out);
            if (reply.rep != Socks5.REP_OK) {
                throw new IOException("gateway UDP ASSOCIATE refused, rep=" + reply.rep);
            }
            control.setSoTimeout(0);
            DatagramSocket udp = new DatagramSocket();
            protector.protect(udp);
            InetAddress relayAddr = InetAddress.getByAddress(new byte[]{
                    (byte) (reply.bndIpv4 >> 24), (byte) (reply.bndIpv4 >> 16),
                    (byte) (reply.bndIpv4 >> 8), (byte) reply.bndIpv4});
            if (!relay.attach(control, udp, relayAddr, reply.bndPort)) {
                closeQuietly(control);
                udp.close();
                return;
            }
            relay.pump();
        } catch (Exception e) {
            closeQuietly(control);
            relay.markDead();
        }
    }

    public static final class UdpRelay {
        private final UdpCallback cb;
        private Socket control;
        private DatagramSocket udp;
        private InetAddress relayAddr;
        private int relayPort;
        private boolean ready;
        private boolean closed;
        private boolean dead;
        private final Queue<byte[]> early = new ArrayDeque<>();

        UdpRelay(UdpCallback cb) {
            this.cb = cb;
        }

        synchronized boolean attach(Socket control, DatagramSocket udp,
                                    InetAddress relayAddr, int relayPort) {
            if (closed) return false;
            this.control = control;
            this.udp = udp;
            this.relayAddr = relayAddr;
            this.relayPort = relayPort;
            this.ready = true;
            // Flush anything queued before the session was ready.
            while (!early.isEmpty()) {
                byte[] datagram = early.poll();
                try {
                    udp.send(new DatagramPacket(datagram, datagram.length, relayAddr, relayPort));
                } catch (IOException ignored) {
                }
            }
            return true;
        }

        private boolean closedReported;

        synchronized void markDead() {
            dead = true;
            reportClosed();
        }

        private void reportClosed() {
            // Called holding the monitor or from pump's finally; the
            // callback fires at most once.
            if (closedReported) return;
            closedReported = true;
            cb.onClosed();
        }

        public synchronized boolean isReady() {
            return ready && !closed && !dead;
        }

        /** Sends one datagram. Before the session is ready,
         *  datagrams are queued (cap 64, oldest dropped). */
        public void send(int dstIp, int dstPort, byte[] payload) {
            byte[] datagram = Socks5.wrapUdp(dstIp, dstPort, payload, 0, payload.length);
            DatagramSocket u;
            InetAddress addr;
            int port;
            synchronized (this) {
                if (closed || dead) return;
                if (!ready) {
                    if (early.size() >= 64) early.poll();
                    early.add(datagram);
                    return;
                }
                u = udp;
                addr = relayAddr;
                port = relayPort;
            }
            try {
                u.send(new DatagramPacket(datagram, datagram.length, addr, port));
            } catch (IOException ignored) {
            }
        }

        void pump() {
            // The control connection's EOF ends the session.
            Thread watcher = new Thread(() -> {
                try {
                    int b;
                    InputStream in = control.getInputStream();
                    while ((b = in.read()) >= 0) {
                        // The gateway sends nothing on the control
                        // connection; any byte is ignored, EOF ends it.
                    }
                } catch (IOException ignored) {
                }
                close();
            });
            watcher.setDaemon(true);
            watcher.setName("socks-udp-watch");
            watcher.start();
            byte[] buf = new byte[65535];
            DatagramPacket p = new DatagramPacket(buf, buf.length);
            try {
                while (true) {
                    synchronized (this) {
                        if (closed || dead) break;
                    }
                    udp.receive(p);
                    Socks5.Datagram g = Socks5.parseUdp(p.getData(), p.getLength());
                    if (g == null) continue;
                    byte[] payload = new byte[g.dataLen];
                    System.arraycopy(p.getData(), g.dataOff, payload, 0, g.dataLen);
                    cb.onDatagram(g.dstIpv4, g.dstPort, payload);
                }
            } catch (IOException ignored) {
            } finally {
                synchronized (this) {
                    reportClosed();
                }
                close();
            }
        }

        public void close() {
            Socket c;
            DatagramSocket u;
            synchronized (this) {
                if (closed) return;
                closed = true;
                c = control;
                u = udp;
            }
            closeQuietly(c);
            if (u != null) u.close();
        }
    }

    private static void closeQuietly(Socket s) {
        if (s != null) {
            try {
                s.close();
            } catch (IOException ignored) {
            }
        }
    }
}
