package app.control.launcher;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;

/**
 * The mesh NAT packet engine (v1.10). Pure Java, no Android — the
 * JVM harness exercises this exact code with a fake Host.
 *
 * Owns: IP dispatch, the TCP flow table, the UDP flow table, and
 * the DNS blocklist gate. The Host (NatVpnService on Android)
 * owns the sockets: one SOCKS CONNECT per TCP flow, one UDP
 * ASSOCIATE session for all UDP, and the TUN file descriptor.
 *
 * Dropped silently, by design: non-IPv4, IP fragments (no
 * reassembly in v1), ICMP, and anything unparseable. TCP RSTs are
 * emitted for segments that arrive with no flow, so the local
 * stack fails fast instead of retransmitting into the void.
 *
 * DNS: EVERY UDP packet to port 53 is checked against the
 * blocklist, not just packets addressed to the virtual DNS —
 * apps may query any resolver, and blocking must hold either
 * way. Blocked queries get a local NXDOMAIN; everything else is
 * forwarded to the gateway with its original destination. The
 * virtual DNS address (10.0.0.1) is rewritten to the gateway's
 * upstream resolver by the gateway relay, not here.
 */
public class NatEngine {

    /** The virtual DNS address the TUN hands to apps. */
    public static final int VIRTUAL_DNS_IP = (10 << 24) | 1;
    /** The TUN interface's own address. */
    public static final int TUN_ADDR = (10 << 24) | 2;
    public static final int MTU = 1500;

    public static final long UDP_IDLE_TIMEOUT_MS = 60_000;

    public interface IsnSource {
        long next();
    }

    public interface Host {
        /** Start a SOCKS CONNECT for a flow (async). The host must
         *  call flow.onRemoteConnected / onRemoteFailed, and feed
         *  bytes via flow.onRemoteData / onRemoteClosed. */
        void openTcp(TcpFlow flow, int dstIp, int dstPort);
        void writeRemote(TcpFlow flow, byte[] data);
        /** Must be idempotent: the engine may call it twice for
         *  one flow (abort path + removal). */
        void closeRemote(TcpFlow flow, boolean half);
        /** One UDP datagram toward the gateway (via UDP ASSOCIATE). */
        void sendUdp(int dstIp, int dstPort, byte[] payload);
        /** Blocklist predicate (Blocklist.isBlocked on Android). */
        boolean isBlocked(String host);
        /** An IP packet to write to the TUN. */
        void emit(byte[] ipPacket);
        /** A query was blocked (counting only — no hostname). */
        void onBlockedQuery();
    }

    private final Host host;
    private final IsnSource isnSource;
    private final Map<Long, TcpFlow> tcpFlows = new HashMap<>();
    private final Map<Integer, UdpFlow> udpFlows = new HashMap<>();
    private int ipId = 1;

    private static final class UdpFlow {
        final int srcPort;
        int dstIp;
        int dstPort;
        long lastSeen;
        final ArrayList<Integer> dnsIds = new ArrayList<>();
        UdpFlow(int srcPort, int dstIp, int dstPort, long nowMs) {
            this.srcPort = srcPort;
            this.dstIp = dstIp;
            this.dstPort = dstPort;
            this.lastSeen = nowMs;
        }
    }

    public NatEngine(Host host) {
        this(host, new IsnSource() {
            private final SecureRandom rnd = new SecureRandom();
            @Override
            public long next() {
                return rnd.nextInt() & 0xFFFFFFFFL;
            }
        });
    }

    public NatEngine(Host host, IsnSource isnSource) {
        this.host = host;
        this.isnSource = isnSource;
    }

    public synchronized int tcpFlowCount() {
        return tcpFlows.size();
    }

    public synchronized int udpFlowCount() {
        return udpFlows.size();
    }

    // ---- packets from the TUN ----

    public synchronized void onPacket(byte[] packet, int len, long nowMs) {
        NatPackets.Ip ip = NatPackets.parseIp(packet, len);
        if (ip == null) return;
        if (ip.isFragment()) return; // no reassembly in v1
        if (ip.protocol == NatPackets.PROTO_TCP) {
            onTcp(ip, packet, nowMs);
        } else if (ip.protocol == NatPackets.PROTO_UDP) {
            onUdp(ip, packet, nowMs);
        }
        // ICMP and everything else: dropped.
    }

    private void onTcp(NatPackets.Ip ip, byte[] packet, long nowMs) {
        NatPackets.Tcp tcp = NatPackets.parseTcp(packet, ip.payloadOff, ip.payloadLen);
        if (tcp == null) return;
        long key = tcpKey(tcp.srcPort, ip.dst, tcp.dstPort);
        TcpFlow flow = tcpFlows.get(key);
        if (flow == null) {
            if ((tcp.flags & NatPackets.TCP_SYN) != 0
                    && (tcp.flags & NatPackets.TCP_ACK) == 0) {
                flow = newFlow(key, ip, tcp, nowMs);
                flow.onSyn(tcp.seq, tcp.mss, tcp.window, nowMs);
            } else if ((tcp.flags & NatPackets.TCP_RST) == 0) {
                // No flow and not a SYN: RST so the stack fails fast.
                long ack = (tcp.seq + tcp.payloadLen
                        + (((tcp.flags & NatPackets.TCP_FIN) != 0) ? 1 : 0)) & 0xFFFFFFFFL;
                byte[] seg = NatPackets.buildTcp(ip.dst, ip.src, tcp.dstPort, tcp.srcPort,
                        0, ack, NatPackets.TCP_RST | NatPackets.TCP_ACK,
                        0, 0, new byte[0], 0, 0);
                host.emit(NatPackets.buildIp(ip.dst, ip.src, NatPackets.PROTO_TCP, seg, ipId++));
            }
            return;
        }
        flow.onSegment(tcp.flags, tcp.seq, tcp.ack, tcp.window,
                packet, tcp.payloadOff, tcp.payloadLen, nowMs);
    }

    private TcpFlow newFlow(final long key, final NatPackets.Ip ip,
                            final NatPackets.Tcp tcp, long nowMs) {
        final int dstIp = ip.dst;
        final int dstPort = tcp.dstPort;
        final TcpFlow[] holder = new TcpFlow[1];
        TcpFlow flow = new TcpFlow(ip.src, tcp.srcPort, ip.dst, tcp.dstPort,
                isnSource.next(), new TcpFlow.Sink() {
                    @Override
                    public void emit(byte[] ipPacket) {
                        host.emit(ipPacket);
                    }

                    @Override
                    public void toRemote(byte[] data) {
                        host.writeRemote(holder[0], data);
                    }

                    @Override
                    public void closeRemote(boolean half) {
                        host.closeRemote(holder[0], half);
                    }

                    @Override
                    public void openRemote() {
                        host.openTcp(holder[0], dstIp, dstPort);
                    }

                    @Override
                    public void remove() {
                        tcpFlows.remove(key);
                        // Close the remote side too: a flow that died
                        // on timeout or RST must not leak its socket.
                        host.closeRemote(holder[0], false);
                    }
                }, nowMs);
        holder[0] = flow;
        tcpFlows.put(key, flow);
        return flow;
    }

    static long tcpKey(int srcPort, int dstIp, int dstPort) {
        return (((long) dstIp) << 32) | (((long) dstPort) << 16) | (srcPort & 0xFFFFL);
    }

    // ---- UDP ----

    private void onUdp(NatPackets.Ip ip, byte[] packet, long nowMs) {
        NatPackets.Udp udp = NatPackets.parseUdp(packet, ip.payloadOff, ip.payloadLen);
        if (udp == null) return;
        UdpFlow flow = udpFlows.get(udp.srcPort);
        if (flow == null) {
            flow = new UdpFlow(udp.srcPort, ip.dst, udp.dstPort, nowMs);
            udpFlows.put(udp.srcPort, flow);
        }
        flow.dstIp = ip.dst;
        flow.dstPort = udp.dstPort;
        flow.lastSeen = nowMs;

        byte[] payload = new byte[udp.payloadLen];
        System.arraycopy(packet, udp.payloadOff, payload, 0, udp.payloadLen);

        if (udp.dstPort == 53 && NatPackets.isDnsQuery(payload)) {
            int id = NatPackets.dnsId(payload);
            if (id >= 0) {
                flow.dnsIds.add(id);
                if (flow.dnsIds.size() > 8) flow.dnsIds.remove(0);
            }
            String qname = NatPackets.parseQName(payload);
            if (qname != null && host.isBlocked(qname)) {
                byte[] nx = NatPackets.nxDomain(payload);
                byte[] seg = NatPackets.buildUdp(ip.dst, ip.src, udp.dstPort, udp.srcPort,
                        nx, 0, nx.length);
                host.emit(NatPackets.buildIp(ip.dst, ip.src, NatPackets.PROTO_UDP, seg, ipId++));
                host.onBlockedQuery();
                return;
            }
        }
        host.sendUdp(ip.dst, udp.dstPort, payload);
    }

    /** A UDP reply arrived from the gateway. Demux: prefer a flow
     *  whose destination matches AND whose recent DNS IDs contain
     *  the reply's transaction ID; otherwise the most-recent flow
     *  to that destination. */
    public synchronized void onUdpReply(int srcIp, int srcPort,
                                        byte[] payload, long nowMs) {
        UdpFlow best = null;
        int replyId = (srcPort == 53) ? NatPackets.dnsId(payload) : -1;
        for (UdpFlow f : udpFlows.values()) {
            if (f.dstIp != srcIp || f.dstPort != srcPort) continue;
            if (best == null || f.lastSeen > best.lastSeen) best = f;
            if (replyId >= 0 && f.dnsIds.contains(replyId)) {
                best = f;
                break;
            }
        }
        if (best == null) return;
        best.lastSeen = nowMs;
        byte[] seg = NatPackets.buildUdp(srcIp, TUN_ADDR, srcPort, best.srcPort,
                payload, 0, payload.length);
        host.emit(NatPackets.buildIp(srcIp, TUN_ADDR, NatPackets.PROTO_UDP, seg, ipId++));
    }

    // ---- remote-side events, routed through the engine lock ----
    // Socket threads must never touch a flow directly: these
    // synchronized delegations serialize them with the TUN thread.

    public synchronized void tcpConnected(TcpFlow flow, long nowMs) {
        flow.onRemoteConnected(nowMs);
    }

    public synchronized void tcpFailed(TcpFlow flow, long nowMs) {
        flow.onRemoteFailed(nowMs);
    }

    public synchronized void tcpData(TcpFlow flow, byte[] data, int off, int len, long nowMs) {
        flow.onRemoteData(data, off, len, nowMs);
    }

    public synchronized void tcpClosed(TcpFlow flow, long nowMs) {
        flow.onRemoteClosed(nowMs);
    }

    // ---- lifecycle ----

    /** Periodic sweep: TCP timeouts + UDP idle expiry. Iterates
     *  over copies: a TCP timeout removes itself from the table
     *  via its Sink, which would break a live iterator. */
    public synchronized void onTick(long nowMs) {
        for (TcpFlow f : new ArrayList<>(tcpFlows.values())) {
            f.onTick(nowMs);
        }
        for (UdpFlow f : new ArrayList<>(udpFlows.values())) {
            if (nowMs - f.lastSeen > UDP_IDLE_TIMEOUT_MS) {
                udpFlows.remove(f.srcPort);
            }
        }
    }

    /** Shutdown: RST every TCP flow, clear the tables. */
    public synchronized void shutdown(long nowMs) {
        for (TcpFlow f : new ArrayList<>(tcpFlows.values())) {
            f.abort(nowMs);
        }
        tcpFlows.clear();
        udpFlows.clear();
    }
}
