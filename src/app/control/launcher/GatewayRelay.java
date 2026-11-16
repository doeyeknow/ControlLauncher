package app.control.launcher;

import android.util.Log;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.security.SecureRandom;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The gateway side of mesh internet (v1.10): a SOCKS5 server that
 * forwards one mesh peer's traffic to the internet.
 *
 * HARD RULES:
 *  - Never an open proxy. The server socket binds to the mesh
 *    interface address only (never a wildcard), and every client
 *    must pass the GatewayAuth challenge-response before any
 *    SOCKS byte is honored. Unauthenticated clients are dropped
 *    silently.
 *  - No traffic content is ever logged. The only externally
 *    visible number is the active client count, for the UI.
 *  - Outbound sockets need NO protect(): the DNS filter VPN routes
 *    only 10.0.0.1/32, so normal outbound sockets are never
 *    captured by it, and the NAT VPN never runs on a gateway
 *    (a phone is either sharing or using, never both).
 *
 * Virtual DNS: a client request for 10.0.0.1:53 (the TUN's virtual
 * resolver) is rewritten to this phone's upstream resolver, and
 * UDP replies from that resolver have their SOCKS header rewritten
 * back to 10.0.0.1:53 so the client's demux matches.
 */
public class GatewayRelay {

    public interface Listener {
        void onClientCountChanged(int count);
    }

    private static final String TAG = "GatewayRelay";
    private static final int HANDSHAKE_TIMEOUT_MS = 5000;
    private static final int CONNECT_TIMEOUT_MS = 10000;

    private final byte[] gatewayPub;
    private final String upstreamDns;
    private final Listener listener;
    private final SecureRandom rnd = new SecureRandom();
    private final AtomicInteger clients = new AtomicInteger(0);

    private ServerSocket server;
    private Thread acceptThread;
    private volatile boolean running;

    public GatewayRelay(byte[] gatewayPub, String upstreamDns, Listener listener) {
        this.gatewayPub = gatewayPub;
        this.upstreamDns = upstreamDns;
        this.listener = listener;
    }

    public synchronized void start(InetAddress bindAddr, int port) throws IOException {
        if (running) return;
        server = new ServerSocket();
        server.setReuseAddress(true);
        server.bind(new InetSocketAddress(bindAddr, port));
        running = true;
        acceptThread = new Thread(this::acceptLoop, "gw-relay-accept");
        acceptThread.setDaemon(true);
        acceptThread.start();
        Log.i(TAG, "relay listening on mesh interface");
    }

    public synchronized void stop() {
        running = false;
        if (server != null) {
            try {
                server.close();
            } catch (IOException ignored) {
            }
            server = null;
        }
    }

    public int clientCount() {
        return clients.get();
    }

    public boolean isRunning() {
        return running;
    }

    private void acceptLoop() {
        while (running) {
            try {
                Socket s = server.accept();
                Thread t = new Thread(() -> handleClient(s), "gw-relay-client");
                t.setDaemon(true);
                t.start();
            } catch (IOException e) {
                if (running) Log.w(TAG, "accept failed: " + e.getMessage());
            }
        }
    }

    private void handleClient(Socket socket) {
        try {
            socket.setSoTimeout(HANDSHAKE_TIMEOUT_MS);
            InputStream in = socket.getInputStream();
            OutputStream out = socket.getOutputStream();
            // 1. Auth preamble: challenge, then verify the response.
            byte[] nonce = GatewayAuth.newNonce(rnd);
            out.write(GatewayAuth.challenge(nonce));
            out.flush();
            byte[] resp = SocksClient.Session.readExact(in, GatewayAuth.RESPONSE_LEN);
            if (GatewayAuth.verifyResponse(nonce, gatewayPub, resp, resp.length) == null) {
                return; // silent drop: no auth, no proxy
            }
            // 2. SOCKS greeting, NO_AUTH only.
            byte[] gHead = SocksClient.Session.readExact(in, 2);
            int nMethods = gHead[1] & 0xFF;
            if (gHead[0] != Socks5.VER || nMethods <= 0 || nMethods > 16) return;
            byte[] gRest = SocksClient.Session.readExact(in, nMethods);
            byte[] greeting = new byte[2 + nMethods];
            greeting[0] = gHead[0];
            greeting[1] = gHead[1];
            System.arraycopy(gRest, 0, greeting, 2, nMethods);
            if (!Socks5.greetingOffersNoAuth(greeting, greeting.length)) {
                out.write(Socks5.greetingReply(Socks5.METHOD_NONE_ACCEPTABLE));
                out.flush();
                return;
            }
            out.write(Socks5.greetingReply(Socks5.METHOD_NO_AUTH));
            out.flush();
            // 3. One request per connection.
            Socks5.Request req = readRequest(in);
            if (req == null) {
                out.write(Socks5.reply(Socks5.REP_ADDR_UNSUPPORTED, 0, 0));
                out.flush();
                return;
            }
            socket.setSoTimeout(0);
            if (req.cmd == Socks5.CMD_CONNECT) {
                clients.incrementAndGet();
                notifyCount();
                try {
                    handleConnect(socket, in, out, req);
                } finally {
                    clients.decrementAndGet();
                    notifyCount();
                }
            } else if (req.cmd == Socks5.CMD_UDP_ASSOCIATE) {
                clients.incrementAndGet();
                notifyCount();
                try {
                    handleUdpAssociate(socket, in, out);
                } finally {
                    clients.decrementAndGet();
                    notifyCount();
                }
            } else {
                out.write(Socks5.reply(Socks5.REP_CMD_UNSUPPORTED, 0, 0));
                out.flush();
            }
        } catch (SocketTimeoutException e) {
            // Handshake timed out: drop silently.
        } catch (EOFException e) {
            // Client went away mid-handshake.
        } catch (IOException e) {
            Log.w(TAG, "client handling failed: " + e.getMessage());
        } finally {
            try {
                socket.close();
            } catch (IOException ignored) {
            }
        }
    }

    private void notifyCount() {
        if (listener != null) listener.onClientCountChanged(clients.get());
    }

    private Socks5.Request readRequest(InputStream in) throws IOException {
        byte[] head = SocksClient.Session.readExact(in, 4);
        if (head[0] != Socks5.VER) return null;
        int atyp = head[3] & 0xFF;
        if (atyp == Socks5.ATYP_IPV4) {
            byte[] rest = SocksClient.Session.readExact(in, 6);
            byte[] full = new byte[10];
            System.arraycopy(head, 0, full, 0, 4);
            System.arraycopy(rest, 0, full, 4, 6);
            return Socks5.parseRequest(full, full.length);
        }
        if (atyp == Socks5.ATYP_DOMAIN) {
            byte[] ln = SocksClient.Session.readExact(in, 1);
            int n = ln[0] & 0xFF;
            byte[] rest = SocksClient.Session.readExact(in, n + 2);
            byte[] full = new byte[5 + rest.length];
            System.arraycopy(head, 0, full, 0, 4);
            full[4] = ln[0];
            System.arraycopy(rest, 0, full, 5, rest.length);
            return Socks5.parseRequest(full, full.length);
        }
        return null; // IPv6 and anything else: unsupported
    }

    // ---- CONNECT ----

    private void handleConnect(Socket client, InputStream cin, OutputStream cout,
                               Socks5.Request req) throws IOException {
        InetAddress target;
        int port = req.port;
        if (req.atyp == Socks5.ATYP_DOMAIN) {
            try {
                target = InetAddress.getByName(req.domain);
            } catch (Exception e) {
                cout.write(Socks5.reply(Socks5.REP_HOST_UNREACHABLE, 0, 0));
                cout.flush();
                return;
            }
        } else {
            target = ipv4Address(req.ipv4);
        }
        // Virtual DNS rewrite (DNS-over-TCP): the client's 10.0.0.1
        // means "this gateway's upstream resolver".
        if (req.atyp == Socks5.ATYP_IPV4 && req.ipv4 == NatEngine.VIRTUAL_DNS_IP && port == 53) {
            try {
                target = InetAddress.getByName(upstreamDns);
            } catch (Exception e) {
                cout.write(Socks5.reply(Socks5.REP_HOST_UNREACHABLE, 0, 0));
                cout.flush();
                return;
            }
        }
        Socket remote = new Socket();
        try {
            remote.connect(new InetSocketAddress(target, port), CONNECT_TIMEOUT_MS);
        } catch (IOException e) {
            int rep = e instanceof java.net.ConnectException
                    ? Socks5.REP_CONN_REFUSED : Socks5.REP_HOST_UNREACHABLE;
            cout.write(Socks5.reply(rep, 0, 0));
            cout.flush();
            try {
                remote.close();
            } catch (IOException ignored) {
            }
            return;
        }
        InetSocketAddress local = (InetSocketAddress) remote.getLocalSocketAddress();
        cout.write(Socks5.reply(Socks5.REP_OK, ipv4Int(local.getAddress()), local.getPort()));
        cout.flush();
        pumpBidirectional(client, remote);
    }

    /** Copies both directions with half-close: EOF on one side
     *  shuts down the other side's output, and the connection ends
     *  when both directions are done. */
    private void pumpBidirectional(Socket a, Socket b) {
        Thread t1 = pumpOneWay(a, b, "gw-pump-c2r");
        Thread t2 = pumpOneWay(b, a, "gw-pump-r2c");
        try {
            t1.join();
            t2.join();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            try {
                a.close();
            } catch (IOException ignored) {
            }
            try {
                b.close();
            } catch (IOException ignored) {
            }
        }
    }

    private Thread pumpOneWay(Socket from, Socket to, String name) {
        Thread t = new Thread(() -> {
            byte[] buf = new byte[16384];
            try {
                InputStream in = from.getInputStream();
                OutputStream out = to.getOutputStream();
                int n;
                while ((n = in.read(buf)) >= 0) {
                    if (n == 0) continue;
                    out.write(buf, 0, n);
                    out.flush();
                }
            } catch (IOException ignored) {
            } finally {
                try {
                    to.shutdownOutput();
                } catch (IOException ignored) {
                }
            }
        }, name);
        t.setDaemon(true);
        t.start();
        return t;
    }

    // ---- UDP ASSOCIATE ----

    private void handleUdpAssociate(Socket control, InputStream cin, OutputStream cout)
            throws IOException {
        InetAddress bindAddr = ((InetSocketAddress) control.getLocalSocketAddress()).getAddress();
        InetAddress clientAddr = ((InetSocketAddress) control.getRemoteSocketAddress()).getAddress();
        DatagramSocket relay = new DatagramSocket(0, bindAddr);
        DatagramSocket upstream = new DatagramSocket();
        try {
            int relayPort = relay.getLocalPort();
            cout.write(Socks5.reply(Socks5.REP_OK, ipv4Int(bindAddr), relayPort));
            cout.flush();
            // Map: real reply source -> original destination, for the
            // virtual-DNS rewrite (replies from the upstream resolver
            // must be headed back to 10.0.0.1:53).
            Map<String, int[]> rewrite = new ConcurrentHashMap<>();
            // The client's UDP source port, learned from its first
            // datagram. Replies go back to that port — per-session
            // state, never shared between clients.
            final int[] clientUdpPortBox = new int[]{-1};
            Thread replyPump = new Thread(() -> {
                byte[] buf = new byte[65535];
                DatagramPacket p = new DatagramPacket(buf, buf.length);
                while (!relay.isClosed()) {
                    try {
                        upstream.receive(p);
                    } catch (IOException e) {
                        break;
                    }
                    int port = clientUdpPortBox[0];
                    if (port <= 0) continue; // no client datagram yet
                    int srcIp = ipv4Int(p.getAddress());
                    int srcPort = p.getPort();
                    int[] orig = rewrite.get(srcIp + ":" + srcPort);
                    int dstIp = orig != null ? orig[0] : srcIp;
                    int dstPort = orig != null ? orig[1] : srcPort;
                    byte[] dg = Socks5.wrapUdp(dstIp, dstPort,
                            p.getData(), p.getOffset(), p.getLength());
                    try {
                        relay.send(new DatagramPacket(dg, dg.length, clientAddr, port));
                    } catch (IOException ignored) {
                    }
                }
            }, "gw-udp-reply");
            replyPump.setDaemon(true);
            replyPump.start();
            // Control EOF ends the session.
            Thread watcher = new Thread(() -> {
                try {
                    while (cin.read() >= 0) {
                        // The client sends nothing here; EOF ends it.
                    }
                } catch (IOException ignored) {
                }
                relay.close();
                upstream.close();
            }, "gw-udp-watch");
            watcher.setDaemon(true);
            watcher.start();
            // Main loop: client datagrams -> upstream.
            byte[] buf = new byte[65535];
            DatagramPacket p = new DatagramPacket(buf, buf.length);
            while (!relay.isClosed()) {
                try {
                    relay.receive(p);
                } catch (IOException e) {
                    break;
                }
                if (!p.getAddress().equals(clientAddr)) continue;
                clientUdpPortBox[0] = p.getPort();
                Socks5.Datagram g = Socks5.parseUdp(p.getData(), p.getLength());
                if (g == null) continue;
                int dstIp = g.dstIpv4;
                int dstPort = g.dstPort;
                InetAddress dstAddr;
                if (g.dstDomain != null) {
                    try {
                        dstAddr = InetAddress.getByName(g.dstDomain);
                    } catch (Exception e) {
                        continue;
                    }
                    dstIp = ipv4Int(dstAddr);
                } else {
                    dstAddr = ipv4Address(dstIp);
                }
                if (dstIp == NatEngine.VIRTUAL_DNS_IP && dstPort == 53) {
                    try {
                        InetAddress up = InetAddress.getByName(upstreamDns);
                        rewrite.put(ipv4Int(up) + ":53",
                                new int[]{NatEngine.VIRTUAL_DNS_IP, 53});
                        dstAddr = up;
                    } catch (Exception e) {
                        continue;
                    }
                }
                byte[] payload = new byte[g.dataLen];
                System.arraycopy(p.getData(), g.dataOff, payload, 0, g.dataLen);
                try {
                    upstream.send(new DatagramPacket(payload, payload.length, dstAddr, dstPort));
                } catch (IOException ignored) {
                }
            }
            try {
                replyPump.join(1000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        } finally {
            relay.close();
            upstream.close();
        }
    }

    // ---- address helpers ----

    private static InetAddress ipv4Address(int ip) {
        try {
            return InetAddress.getByAddress(new byte[]{
                    (byte) (ip >> 24), (byte) (ip >> 16), (byte) (ip >> 8), (byte) ip});
        } catch (Exception e) {
            throw new IllegalArgumentException("bad ipv4", e);
        }
    }

    private static int ipv4Int(InetAddress addr) {
        byte[] b = addr.getAddress();
        if (b.length != 4) return 0;
        return ((b[0] & 0xFF) << 24) | ((b[1] & 0xFF) << 16)
                | ((b[2] & 0xFF) << 8) | (b[3] & 0xFF);
    }
}
