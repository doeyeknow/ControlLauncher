package app.control.launcher;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Off-device verification for the v1.10 mesh NAT, exercising the
 * exact shipping pure-Java code (NatPackets, TcpFlow, NatEngine,
 * Socks5, GatewayAuth, SocksClient.Session):
 *
 * Legs:
 *   1. PACKETS: IP/TCP/UDP/DNS parse + build round-trips,
 *      malformed/truncated/fragment rejection, checksum validity.
 *   2. TCP FLOW: handshake, data both ways, FIN/RST close paths,
 *      retransmit on 3 duplicate ACKs, MSS cap, window respect,
 *      out-of-order + duplicate drop, pre-connect buffering,
 *      sequence wraparound, timeout sweep.
 *   3. SOCKS5: greeting, CONNECT, UDP ASSOCIATE, UDP datagram
 *      framing, malformed rejection.
 *   4. AUTH: challenge-response accept, stranger/wrong-key,
 *      replay against a fresh nonce, wrong gateway, truncated
 *      and tampered responses rejected.
 *   5. SESSION: the client handshake over piped streams against a
 *      fake gateway (no sockets: JVM IPv4 loopback is unusable in
 *      the build VM).
 *   6. ENGINE: full TCP + UDP flows through onPacket with a fake
 *      host, RST for stray segments, fragment/ICMP drops, DNS
 *      NXDOMAIN-vs-forward incl. blocklist hits on any resolver,
 *      DNS-ID reply demux, idle expiry, shutdown.
 *
 * What this harness cannot prove: real TUN behavior, real Wi-Fi
 * Direct groups, real SOCKS sockets, or two real phones — the
 * gateway path is Adam's two-phone test. These legs bound the
 * packet/state-machine risk; they do not eliminate the rest.
 *
 * Run (from ~/workspace/control-launcher):
 *   javac -encoding UTF-8 --release 17 -sourcepath src \
 *     -cp libs/bcprov-jdk18on.jar -d harness/classes \
 *     harness/NatHarness.java
 *   java -cp harness/classes:libs/bcprov-jdk18on.jar \
 *     app.control.launcher.NatHarness
 */
public class NatHarness {

    private static int checks = 0;
    private static int failures = 0;

    private static void check(boolean ok, String name) {
        checks++;
        if (!ok) {
            failures++;
            System.out.println("FAIL: " + name);
        } else {
            System.out.println("ok:   " + name);
        }
    }

    private static void eq(long want, long got, String name) {
        check(want == got, name + " (want=" + want + " got=" + got + ")");
    }

    private static void eq(String want, String got, String name) {
        check(want.equals(got), name + " (want=" + want + " got=" + got + ")");
    }

    // ---- packet builders (client side) ----

    private static final int CLIENT_IP = NatPackets.ip(10, 0, 0, 2);
    private static final int SERVER_IP = NatPackets.ip(93, 184, 216, 34);
    private static final int DNS_IP = NatPackets.ip(10, 0, 0, 1);

    private static byte[] ipTcp(int srcPort, int dstPort, long seq, long ack,
                                int flags, int window, int mss, byte[] payload) {
        byte[] seg = NatPackets.buildTcp(CLIENT_IP, SERVER_IP, srcPort, dstPort,
                seq, ack, flags, window, mss, payload, 0, payload.length);
        return NatPackets.buildIp(CLIENT_IP, SERVER_IP, NatPackets.PROTO_TCP, seg, 7);
    }

    private static byte[] ipUdp(int srcPort, int dstIp, int dstPort, byte[] payload) {
        byte[] seg = NatPackets.buildUdp(CLIENT_IP, dstIp, srcPort, dstPort,
                payload, 0, payload.length);
        return NatPackets.buildIp(CLIENT_IP, dstIp, NatPackets.PROTO_UDP, seg, 7);
    }

    private static byte[] dnsQuery(int id, String name) {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        b.write((id >> 8) & 0xFF);
        b.write(id & 0xFF);
        b.write(0x01); // RD
        b.write(0x00);
        b.write(0); b.write(1); // QDCOUNT
        b.write(0); b.write(0);
        b.write(0); b.write(0);
        b.write(0); b.write(0);
        for (String part : name.split("\\.")) {
            byte[] p = part.getBytes(StandardCharsets.US_ASCII);
            b.write(p.length);
            b.write(p, 0, p.length);
        }
        b.write(0);
        b.write(0); b.write(1); // QTYPE A
        b.write(0); b.write(1); // QCLASS IN
        return b.toByteArray();
    }

    /** Parses an emitted IP packet back into its TCP segment. */
    private static NatPackets.Tcp emittedTcp(byte[] packet) {
        NatPackets.Ip ip = NatPackets.parseIp(packet, packet.length);
        if (ip == null) return null;
        return NatPackets.parseTcp(packet, ip.payloadOff, ip.payloadLen);
    }

    private static byte[] emittedTcpPayload(byte[] packet) {
        NatPackets.Ip ip = NatPackets.parseIp(packet, packet.length);
        NatPackets.Tcp tcp = NatPackets.parseTcp(packet, ip.payloadOff, ip.payloadLen);
        byte[] out = new byte[tcp.payloadLen];
        System.arraycopy(packet, tcp.payloadOff, out, 0, tcp.payloadLen);
        return out;
    }

    public static void main(String[] args) throws Exception {
        packets();
        tcpFlow();
        socks5();
        auth();
        session();
        engine();
        System.out.println();
        System.out.println(checks + " checks, " + failures + " failures");
        if (failures > 0) System.exit(1);
    }

    // ---- 1. packets ----

    private static void packets() {
        // IP round-trip.
        byte[] payload = "hello".getBytes(StandardCharsets.US_ASCII);
        byte[] pkt = NatPackets.buildIp(CLIENT_IP, SERVER_IP, NatPackets.PROTO_TCP, payload, 42);
        NatPackets.Ip ip = NatPackets.parseIp(pkt, pkt.length);
        check(ip != null, "ip: parse built packet");
        eq(CLIENT_IP, ip.src, "ip: src");
        eq(SERVER_IP, ip.dst, "ip: dst");
        eq(NatPackets.PROTO_TCP, ip.protocol, "ip: proto");
        eq(42, ip.id, "ip: id");
        eq(5, ip.payloadLen, "ip: payload len");
        check(!ip.isFragment(), "ip: not a fragment");

        // IP rejections.
        check(NatPackets.parseIp(new byte[10], 10) == null, "ip: short rejected");
        byte[] v6 = pkt.clone();
        v6[0] = 0x60;
        check(NatPackets.parseIp(v6, v6.length) == null, "ip: version 6 rejected");
        byte[] badIhl = pkt.clone();
        badIhl[0] = 0x44;
        check(NatPackets.parseIp(badIhl, badIhl.length) == null, "ip: IHL<5 rejected");
        check(NatPackets.parseIp(pkt, 10) == null, "ip: total beyond buffer rejected");
        byte[] badSum = pkt.clone();
        badSum[8] ^= 0xFF; // corrupt TTL -> checksum fails
        check(NatPackets.parseIp(badSum, badSum.length) == null, "ip: bad checksum rejected");
        byte[] frag = pkt.clone();
        frag[6] = 0x20; // MF set
        frag[7] = 0;
        NatPackets.put16(frag, 10, 0);
        NatPackets.put16(frag, 10, NatPackets.checksum(frag, 0, 20));
        NatPackets.Ip fragIp = NatPackets.parseIp(frag, frag.length);
        check(fragIp != null && fragIp.isFragment(), "ip: MF fragment reported");

        // TCP round-trip with MSS.
        byte[] seg = NatPackets.buildTcp(CLIENT_IP, SERVER_IP, 40000, 80,
                0x12345678L, 0x9ABCDEF0L, NatPackets.TCP_SYN | NatPackets.TCP_ACK,
                65535, 1460, payload, 0, payload.length);
        NatPackets.Tcp tcp = NatPackets.parseTcp(seg, 0, seg.length);
        check(tcp != null, "tcp: parse built segment");
        eq(40000, tcp.srcPort, "tcp: src port");
        eq(80, tcp.dstPort, "tcp: dst port");
        eq(0x12345678L, tcp.seq, "tcp: seq");
        eq(0x9ABCDEF0L, tcp.ack, "tcp: ack");
        eq(NatPackets.TCP_SYN | NatPackets.TCP_ACK, tcp.flags, "tcp: flags");
        eq(1460, tcp.mss, "tcp: MSS option");
        eq(5, tcp.payloadLen, "tcp: payload len");
        // Checksum validity: recomputing over the whole segment folds to 0.
        eq(0, NatPackets.tcpChecksum(CLIENT_IP, SERVER_IP, seg, seg.length),
                "tcp: checksum valid");

        // TCP rejections.
        check(NatPackets.parseTcp(new byte[10], 0, 10) == null, "tcp: short rejected");
        byte[] badOff = seg.clone();
        badOff[12] = 0x40; // data offset 16 < 20
        check(NatPackets.parseTcp(badOff, 0, badOff.length) == null, "tcp: bad data offset rejected");
        check(NatPackets.parseTcp(seg, 0, 10) == null, "tcp: truncated rejected");

        // UDP round-trip.
        byte[] udp = NatPackets.buildUdp(CLIENT_IP, SERVER_IP, 53535, 53,
                payload, 0, payload.length);
        NatPackets.Udp u = NatPackets.parseUdp(udp, 0, udp.length);
        check(u != null, "udp: parse built datagram");
        eq(53535, u.srcPort, "udp: src port");
        eq(53, u.dstPort, "udp: dst port");
        eq(5, u.payloadLen, "udp: payload len");
        eq(0, NatPackets.udpChecksum(CLIENT_IP, SERVER_IP, udp, udp.length),
                "udp: checksum valid");
        check(NatPackets.parseUdp(new byte[4], 0, 4) == null, "udp: short rejected");
        byte[] badLen = udp.clone();
        NatPackets.put16(badLen, 4, 4);
        check(NatPackets.parseUdp(badLen, 0, badLen.length) == null, "udp: len<8 rejected");
        check(NatPackets.parseUdp(udp, 0, 8) == null, "udp: truncated rejected");

        // DNS.
        byte[] q = dnsQuery(0xBEEF, "example.com");
        eq(0xBEEF, NatPackets.dnsId(q), "dns: id");
        check(NatPackets.isDnsQuery(q), "dns: is query");
        eq("example.com", NatPackets.parseQName(q), "dns: qname");
        byte[] nx = NatPackets.nxDomain(q);
        eq(0xBEEF, NatPackets.dnsId(nx), "dns: nxdomain keeps id");
        check((nx[2] & 0x80) != 0, "dns: nxdomain QR set");
        eq(3, nx[3] & 0x0F, "dns: nxdomain RCODE=3");
        eq("example.com", NatPackets.parseQName(nx), "dns: nxdomain keeps question");
        check(NatPackets.parseQName(new byte[]{1, 2, 3}) == null, "dns: garbage qname null");
        eq(-1, NatPackets.dnsId(new byte[1]), "dns: short id -1");
        check(!NatPackets.isDnsQuery(nx), "dns: response is not a query");
    }

    // ---- 2. TCP flow ----

    private static final class FlowSink implements TcpFlow.Sink {
        final List<byte[]> emitted = new ArrayList<>();
        final ByteArrayOutputStream toRemote = new ByteArrayOutputStream();
        int openRemote;
        int closeHalf;
        int closeFull;
        int removed;

        @Override
        public void emit(byte[] p) {
            emitted.add(p);
        }

        @Override
        public void toRemote(byte[] d) {
            toRemote.write(d, 0, d.length);
        }

        @Override
        public void closeRemote(boolean half) {
            if (half) closeHalf++;
            else closeFull++;
        }

        @Override
        public void openRemote() {
            openRemote++;
        }

        @Override
        public void remove() {
            removed++;
        }

        NatPackets.Tcp lastTcp() {
            return emittedTcp(emitted.get(emitted.size() - 1));
        }
    }

    private static TcpFlow newFlow(FlowSink sink, long isn, long now) {
        return new TcpFlow(CLIENT_IP, 40000, SERVER_IP, 80, isn, sink, now);
    }

    private static void tcpFlow() {
        FlowSink sink;
        TcpFlow f;
        NatPackets.Tcp t;

        // Handshake.
        sink = new FlowSink();
        f = newFlow(sink, 5000, 1000);
        f.onSyn(1000, 1460, 65535, 1000);
        check(sink.emitted.size() == 1, "flow: SYN -> one SYN-ACK");
        t = sink.lastTcp();
        eq(NatPackets.TCP_SYN | NatPackets.TCP_ACK, t.flags, "flow: SYN-ACK flags");
        eq(5000, t.seq, "flow: SYN-ACK seq = ISN");
        eq(1001, t.ack, "flow: SYN-ACK ack");
        eq(1460, t.mss, "flow: SYN-ACK MSS");
        eq(65535, t.window, "flow: SYN-ACK window");
        eq(1, sink.openRemote, "flow: openRemote once");
        check(f.state() == TcpFlow.State.SYN_RECEIVED, "flow: SYN_RECEIVED");
        f.onSegment(NatPackets.TCP_ACK, 1001, 5001, 65535, new byte[0], 0, 0, 1001);
        check(f.state() == TcpFlow.State.ESTABLISHED, "flow: ESTABLISHED after ACK");
        eq(5001, f.sendBase(), "flow: SYN acked");

        // Client data -> remote, ACKed.
        f.onRemoteConnected(1002);
        byte[] hello = "hello".getBytes(StandardCharsets.US_ASCII);
        f.onSegment(NatPackets.TCP_ACK | NatPackets.TCP_PSH, 1001, 5001, 65535,
                hello, 0, hello.length, 1003);
        eq("hello", new String(sink.toRemote.toByteArray(), StandardCharsets.US_ASCII),
                "flow: client data forwarded");
        eq(1006, sink.lastTcp().ack, "flow: ACK covers data");
        eq(1006, f.forwardedSeq(), "flow: forwardedSeq advanced");

        // Remote data -> client, ACKed.
        byte[] world = "world!".getBytes(StandardCharsets.US_ASCII);
        f.onRemoteData(world, 0, world.length, 1004);
        t = sink.lastTcp();
        eq(NatPackets.TCP_PSH | NatPackets.TCP_ACK, t.flags, "flow: data PSH|ACK");
        eq(5001, t.seq, "flow: data seq");
        eq("world!", new String(emittedTcpPayload(sink.emitted.get(sink.emitted.size() - 1)),
                StandardCharsets.US_ASCII), "flow: remote payload intact");
        f.onSegment(NatPackets.TCP_ACK, 1006, 5007, 65535, new byte[0], 0, 0, 1005);
        eq(5007, f.sendBase(), "flow: data acked");
        eq(0, f.unackedSegments(), "flow: unacked drained");

        // Out-of-order + duplicate client data dropped, re-ACKed.
        int remoteBefore = sink.toRemote.size();
        int emittedBefore = sink.emitted.size();
        f.onSegment(NatPackets.TCP_ACK, 2000, 5007, 65535, hello, 0, hello.length, 1006);
        eq(remoteBefore, sink.toRemote.size(), "flow: out-of-order dropped");
        eq(1006, sink.lastTcp().ack, "flow: out-of-order re-ACK");
        f.onSegment(NatPackets.TCP_ACK, 1001, 5007, 65535, hello, 0, hello.length, 1007);
        eq(remoteBefore, sink.toRemote.size(), "flow: duplicate dropped");
        check(sink.emitted.size() > emittedBefore, "flow: duplicate re-ACKed");

        // Fast retransmit on 3 duplicate ACKs.
        byte[] ten = "0123456789".getBytes(StandardCharsets.US_ASCII);
        f.onRemoteData(ten, 0, ten.length, 1008);
        byte[] sentSeg = sink.emitted.get(sink.emitted.size() - 1);
        int nBefore = sink.emitted.size();
        for (int i = 0; i < 2; i++) {
            f.onSegment(NatPackets.TCP_ACK, 1006, 5007, 65535, new byte[0], 0, 0, 1009 + i);
        }
        eq(nBefore, sink.emitted.size(), "flow: 2 dup ACKs, no retransmit");
        f.onSegment(NatPackets.TCP_ACK, 1006, 5007, 65535, new byte[0], 0, 0, 1011);
        eq(nBefore + 1, sink.emitted.size(), "flow: 3rd dup ACK retransmits");
        check(Arrays.equals(sentSeg, sink.emitted.get(sink.emitted.size() - 1)),
                "flow: retransmit is the same segment");
        f.onSegment(NatPackets.TCP_ACK, 1006, 5017, 65535, new byte[0], 0, 0, 1012);
        eq(0, f.unackedSegments(), "flow: retransmitted data acked");

        // MSS cap: 250 bytes at MSS 100 -> 100/100/50.
        sink = new FlowSink();
        f = newFlow(sink, 5000, 2000);
        f.onSyn(1000, 100, 65535, 2000);
        f.onSegment(NatPackets.TCP_ACK, 1001, 5001, 65535, new byte[0], 0, 0, 2001);
        f.onRemoteConnected(2002);
        byte[] big = new byte[250];
        Arrays.fill(big, (byte) 'x');
        int mBefore = sink.emitted.size();
        f.onRemoteData(big, 0, big.length, 2003);
        eq(3, sink.emitted.size() - mBefore, "flow: MSS splits into 3 segments");
        eq(100, emittedTcpPayload(sink.emitted.get(mBefore)).length, "flow: MSS chunk 1");
        eq(100, emittedTcpPayload(sink.emitted.get(mBefore + 1)).length, "flow: MSS chunk 2");
        eq(50, emittedTcpPayload(sink.emitted.get(mBefore + 2)).length, "flow: MSS chunk 3");

        // Window respect: window 10, then window update flushes the rest.
        sink = new FlowSink();
        f = newFlow(sink, 5000, 3000);
        f.onSyn(1000, 0, 10, 3000);
        f.onSegment(NatPackets.TCP_ACK, 1001, 5001, 10, new byte[0], 0, 0, 3001);
        f.onRemoteConnected(3002);
        byte[] hundred = new byte[100];
        int wBefore = sink.emitted.size();
        f.onRemoteData(hundred, 0, hundred.length, 3003);
        eq(1, sink.emitted.size() - wBefore, "flow: window caps first send");
        eq(10, emittedTcpPayload(sink.emitted.get(wBefore)).length, "flow: window chunk = 10");
        f.onSegment(NatPackets.TCP_ACK, 1001, 5011, 65535, new byte[0], 0, 0, 3004);
        eq(2, sink.emitted.size() - wBefore, "flow: window update flushes rest");
        eq(90, emittedTcpPayload(sink.emitted.get(wBefore + 1)).length,
                "flow: flushed remainder = 90");

        // Client-first close: FIN -> CLOSE_WAIT -> remote EOF -> LAST_ACK -> CLOSED.
        sink = new FlowSink();
        f = newFlow(sink, 5000, 4000);
        f.onSyn(1000, 1460, 65535, 4000);
        f.onSegment(NatPackets.TCP_ACK, 1001, 5001, 65535, new byte[0], 0, 0, 4001);
        f.onRemoteConnected(4002);
        f.onSegment(NatPackets.TCP_ACK | NatPackets.TCP_FIN, 1001, 5001, 65535,
                new byte[0], 0, 0, 4003);
        check(f.state() == TcpFlow.State.CLOSE_WAIT, "flow: CLOSE_WAIT after client FIN");
        eq(1, sink.closeHalf, "flow: half-close on client FIN");
        f.onRemoteClosed(4004);
        check(f.state() == TcpFlow.State.LAST_ACK, "flow: LAST_ACK after remote EOF");
        check((sink.lastTcp().flags & NatPackets.TCP_FIN) != 0, "flow: FIN sent");
        f.onSegment(NatPackets.TCP_ACK, 1002, 5002, 65535, new byte[0], 0, 0, 4005);
        check(f.state() == TcpFlow.State.CLOSED, "flow: CLOSED after FIN ack");
        eq(1, sink.removed, "flow: removed on close");

        // Remote-first close: EOF -> FIN_WAIT_1 -> ACK -> FIN_WAIT_2 -> client FIN -> CLOSED.
        sink = new FlowSink();
        f = newFlow(sink, 5000, 5000);
        f.onSyn(1000, 1460, 65535, 5000);
        f.onSegment(NatPackets.TCP_ACK, 1001, 5001, 65535, new byte[0], 0, 0, 5001);
        f.onRemoteConnected(5002);
        f.onRemoteClosed(5003);
        check(f.state() == TcpFlow.State.FIN_WAIT_1, "flow: FIN_WAIT_1 after remote EOF");
        f.onSegment(NatPackets.TCP_ACK, 1001, 5002, 65535, new byte[0], 0, 0, 5004);
        check(f.state() == TcpFlow.State.FIN_WAIT_2, "flow: FIN_WAIT_2 after FIN ack");
        f.onSegment(NatPackets.TCP_ACK | NatPackets.TCP_FIN, 1001, 5002, 65535,
                new byte[0], 0, 0, 5005);
        check(f.state() == TcpFlow.State.CLOSED, "flow: CLOSED after client FIN in FIN_WAIT_2");
        eq(1, sink.removed, "flow: removed after remote-first close");

        // RST from the client.
        sink = new FlowSink();
        f = newFlow(sink, 5000, 6000);
        f.onSyn(1000, 1460, 65535, 6000);
        f.onSegment(NatPackets.TCP_ACK, 1001, 5001, 65535, new byte[0], 0, 0, 6001);
        f.onRemoteConnected(6002);
        f.onSegment(NatPackets.TCP_RST, 1001, 0, 0, new byte[0], 0, 0, 6003);
        check(f.state() == TcpFlow.State.CLOSED, "flow: RST closes");
        eq(1, sink.closeFull, "flow: RST fully closes remote");
        eq(1, sink.removed, "flow: RST removes");

        // Remote failure before connect -> RST to the client.
        sink = new FlowSink();
        f = newFlow(sink, 5000, 7000);
        f.onSyn(1000, 1460, 65535, 7000);
        f.onRemoteFailed(7001);
        check((sink.lastTcp().flags & NatPackets.TCP_RST) != 0, "flow: remote fail sends RST");
        check(f.state() == TcpFlow.State.CLOSED, "flow: remote fail closes");

        // Pre-connect buffering: data held, ack lags, flush on connect.
        sink = new FlowSink();
        f = newFlow(sink, 5000, 8000);
        f.onSyn(1000, 1460, 65535, 8000);
        f.onSegment(NatPackets.TCP_ACK, 1001, 5001, 65535, new byte[0], 0, 0, 8001);
        byte[] abc = "abc".getBytes(StandardCharsets.US_ASCII);
        f.onSegment(NatPackets.TCP_ACK | NatPackets.TCP_PSH, 1001, 5001, 65535,
                abc, 0, abc.length, 8002);
        eq(0, sink.toRemote.size(), "flow: pre-connect data not forwarded");
        eq(1001, sink.lastTcp().ack, "flow: pre-connect ACK lags at forwardedSeq");
        eq(3, f.pendingLen(), "flow: pre-connect data buffered");
        f.onRemoteConnected(8003);
        eq("abc", new String(sink.toRemote.toByteArray(), StandardCharsets.US_ASCII),
                "flow: buffered data flushed on connect");
        eq(1004, sink.lastTcp().ack, "flow: ACK advances after flush");

        // Buffer cap: 70 KB before connect -> 64 KB held, excess dropped.
        sink = new FlowSink();
        f = newFlow(sink, 5000, 9000);
        f.onSyn(1000, 1460, 65535, 9000);
        f.onSegment(NatPackets.TCP_ACK, 1001, 5001, 65535, new byte[0], 0, 0, 9001);
        byte[] chunk = new byte[10240];
        long seq = 1001;
        for (int i = 0; i < 7; i++) {
            f.onSegment(NatPackets.TCP_ACK | NatPackets.TCP_PSH, seq, 5001, 65535,
                    chunk, 0, chunk.length, 9002 + i);
            seq += chunk.length;
        }
        eq(61440, f.pendingLen(), "flow: pending capped below 64K");
        f.onRemoteConnected(9010);
        eq(61440, sink.toRemote.size(), "flow: capped buffer flushed");
        // The dropped chunk retransmitted lands once connected.
        f.onSegment(NatPackets.TCP_ACK | NatPackets.TCP_PSH, 1001 + 61440, 5001, 65535,
                chunk, 0, chunk.length, 9011);
        eq(71680, sink.toRemote.size(), "flow: retransmitted chunk forwarded");

        // Sequence wraparound both directions.
        sink = new FlowSink();
        f = newFlow(sink, 0xFFFFFFF0L, 10000);
        f.onSyn(0xFFFFFFFEL, 1460, 65535, 10000);
        eq(0xFFFFFFFFL, sink.lastTcp().ack, "flow: wrap SYN-ACK ack");
        f.onSegment(NatPackets.TCP_ACK, 0xFFFFFFFFL, 0xFFFFFFF1L, 65535,
                new byte[0], 0, 0, 10001);
        check(f.state() == TcpFlow.State.ESTABLISHED, "flow: wrap handshake completes");
        f.onRemoteConnected(10002);
        byte[] hi = "hi".getBytes(StandardCharsets.US_ASCII);
        f.onSegment(NatPackets.TCP_ACK | NatPackets.TCP_PSH, 0xFFFFFFFFL, 0xFFFFFFF1L, 65535,
                hi, 0, hi.length, 10003);
        eq(1, f.forwardedSeq(), "flow: wrap client seq wraps to 1");
        byte[] twenty = new byte[20];
        f.onRemoteData(twenty, 0, twenty.length, 10004);
        eq(5, f.sendNext(), "flow: wrap our seq wraps to 5");
        f.onSegment(NatPackets.TCP_ACK, 1, 5, 65535, new byte[0], 0, 0, 10005);
        eq(5, f.sendBase(), "flow: wrap ACK advances sendBase");
        eq(0, f.unackedSegments(), "flow: wrap unacked drained");

        // Timeouts: SYN_RECEIVED sweep RSTs; LAST_ACK sweep closes silently.
        sink = new FlowSink();
        f = newFlow(sink, 5000, 11000);
        f.onSyn(1000, 1460, 65535, 11000);
        check(!f.onTick(11000 + 29_000), "flow: SYN_RECEIVED survives 29s");
        check(f.onTick(11000 + 31_000), "flow: SYN_RECEIVED swept at 31s");
        check((sink.lastTcp().flags & NatPackets.TCP_RST) != 0, "flow: sweep sends RST");
        sink = new FlowSink();
        f = newFlow(sink, 5000, 12000);
        f.onSyn(1000, 1460, 65535, 12000);
        f.onSegment(NatPackets.TCP_ACK, 1001, 5001, 65535, new byte[0], 0, 0, 12001);
        f.onRemoteConnected(12002);
        f.onSegment(NatPackets.TCP_ACK | NatPackets.TCP_FIN, 1001, 5001, 65535,
                new byte[0], 0, 0, 12003);
        f.onRemoteClosed(12004);
        int eBefore = sink.emitted.size();
        check(f.onTick(12004 + 61_000), "flow: LAST_ACK swept at 61s");
        eq(eBefore, sink.emitted.size(), "flow: LAST_ACK sweep is silent");
        eq(1, sink.removed, "flow: LAST_ACK sweep removes");
    }

    // ---- 3. SOCKS5 framing ----

    private static void socks5() {
        byte[] g = Socks5.greeting();
        check(g.length == 3 && g[0] == 5 && g[1] == 1 && g[2] == 0, "socks: greeting bytes");
        eq(0, Socks5.parseGreetingReply(new byte[]{5, 0}, 2), "socks: greeting reply NO_AUTH");
        eq(-1, Socks5.parseGreetingReply(new byte[]{4, 0}, 2), "socks: greeting reply bad ver");
        eq(-1, Socks5.parseGreetingReply(new byte[]{5}, 1), "socks: greeting reply short");
        check(Socks5.greetingOffersNoAuth(new byte[]{5, 2, 1, 0}, 4), "socks: greeting offers no-auth");
        check(!Socks5.greetingOffersNoAuth(new byte[]{5, 1, 2}, 3), "socks: greeting without no-auth");
        check(!Socks5.greetingOffersNoAuth(new byte[]{4, 1, 0}, 3), "socks: greeting bad ver");

        byte[] req = Socks5.request(Socks5.CMD_CONNECT, SERVER_IP, 80);
        Socks5.Request r = Socks5.parseRequest(req, req.length);
        check(r != null, "socks: CONNECT parses");
        eq(Socks5.CMD_CONNECT, r.cmd, "socks: CONNECT cmd");
        eq(SERVER_IP, r.ipv4, "socks: CONNECT addr");
        eq(80, r.port, "socks: CONNECT port");
        byte[] domReq = Socks5.requestDomain(Socks5.CMD_CONNECT, "example.com", 443);
        Socks5.Request dr = Socks5.parseRequest(domReq, domReq.length);
        check(dr != null && "example.com".equals(dr.domain) && dr.port == 443,
                "socks: domain CONNECT parses");
        check(Socks5.parseRequest(new byte[]{5, 1, 0, 4, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0}, 20) == null,
                "socks: IPv6 request rejected");
        check(Socks5.parseRequest(new byte[]{5, 1, 0, 1, 1, 2}, 6) == null,
                "socks: truncated request rejected");
        check(Socks5.parseRequest(new byte[]{4, 1, 0, 1, 1, 2, 3, 4, 0, 80}, 10) == null,
                "socks: bad-version request rejected");

        byte[] rep = Socks5.reply(Socks5.REP_OK, NatPackets.ip(192, 168, 49, 1), 8992);
        Socks5.Reply rp = Socks5.parseReply(rep, rep.length);
        check(rp != null, "socks: reply parses");
        eq(0, rp.rep, "socks: reply OK");
        eq(NatPackets.ip(192, 168, 49, 1), rp.bndIpv4, "socks: reply bound addr");
        eq(8992, rp.bndPort, "socks: reply bound port");
        check(Socks5.parseReply(new byte[]{4, 0, 0, 1, 0, 0, 0, 0, 0, 0}, 10) == null,
                "socks: bad-version reply rejected");

        byte[] payload = "dns-bytes".getBytes(StandardCharsets.US_ASCII);
        byte[] dg = Socks5.wrapUdp(SERVER_IP, 53, payload, 0, payload.length);
        Socks5.Datagram d = Socks5.parseUdp(dg, dg.length);
        check(d != null, "socks: UDP datagram parses");
        eq(SERVER_IP, d.dstIpv4, "socks: UDP dst addr");
        eq(53, d.dstPort, "socks: UDP dst port");
        eq(payload.length, d.dataLen, "socks: UDP data len");
        check(Arrays.equals(payload, Arrays.copyOfRange(dg, d.dataOff, d.dataOff + d.dataLen)),
                "socks: UDP payload intact");
        byte[] fragDg = dg.clone();
        fragDg[2] = 1;
        check(Socks5.parseUdp(fragDg, fragDg.length) == null, "socks: fragmented UDP rejected");
        check(Socks5.parseUdp(new byte[5], 5) == null, "socks: short UDP rejected");
    }

    // ---- 4. auth ----

    private static void auth() throws Exception {
        SecureRandom rnd = new SecureRandom();
        byte[][] client = RelayKeys.generate();
        byte[][] gateway = RelayKeys.generate();
        byte[][] stranger = RelayKeys.generate();
        byte[] clientSeed = client[0];
        byte[] clientPub = client[1];
        byte[] gwPub = gateway[1];

        byte[] nonce = GatewayAuth.newNonce(rnd);
        byte[] challenge = GatewayAuth.challenge(nonce);
        check(challenge.length == GatewayAuth.CHALLENGE_LEN, "auth: challenge length");
        check(Arrays.equals(nonce, GatewayAuth.parseChallenge(challenge, challenge.length)),
                "auth: challenge round-trips");
        check(GatewayAuth.parseChallenge(new byte[]{'X', 'X', 'X', 'X', 0, 0}, 36) == null,
                "auth: bad challenge magic rejected");
        check(GatewayAuth.parseChallenge(challenge, 10) == null, "auth: short challenge rejected");

        byte[] resp = GatewayAuth.buildResponse(clientSeed, clientPub, gwPub, nonce);
        eq(GatewayAuth.RESPONSE_LEN, resp.length, "auth: response length");
        byte[] verified = GatewayAuth.verifyResponse(nonce, gwPub, resp, resp.length);
        check(verified != null && Arrays.equals(clientPub, verified), "auth: valid peer accepted");

        // Stranger: a signature from a key the response does not claim.
        byte[] strangerResp = GatewayAuth.buildResponse(stranger[0], stranger[1], gwPub, nonce);
        byte[] tamperedClaim = strangerResp.clone();
        System.arraycopy(clientPub, 0, tamperedClaim, 0, 32); // claim client's key, stranger's sig
        check(GatewayAuth.verifyResponse(nonce, gwPub, tamperedClaim, tamperedClaim.length) == null,
                "auth: mismatched key+signature rejected");
        // Wrong key entirely: stranger's own response verifies as the
        // stranger (any valid Control identity passes) — but a response
        // signed for a DIFFERENT gateway must not.
        byte[][] otherGw = RelayKeys.generate();
        byte[] wrongGwResp = GatewayAuth.buildResponse(clientSeed, clientPub, otherGw[1], nonce);
        check(GatewayAuth.verifyResponse(nonce, gwPub, wrongGwResp, wrongGwResp.length) == null,
                "auth: response for another gateway rejected");

        // Replay: a response for nonce1 fails against a fresh nonce2.
        byte[] nonce2 = GatewayAuth.newNonce(rnd);
        check(!Arrays.equals(nonce, nonce2), "auth: nonces differ");
        check(GatewayAuth.verifyResponse(nonce2, gwPub, resp, resp.length) == null,
                "auth: replay against fresh nonce rejected");

        // Truncated / tampered / garbage.
        check(GatewayAuth.verifyResponse(nonce, gwPub, resp, 40) == null,
                "auth: truncated response rejected");
        byte[] tampered = resp.clone();
        tampered[50] ^= 0x01;
        check(GatewayAuth.verifyResponse(nonce, gwPub, tampered, tampered.length) == null,
                "auth: tampered signature rejected");
        byte[] garbage = new byte[GatewayAuth.RESPONSE_LEN];
        rnd.nextBytes(garbage);
        check(GatewayAuth.verifyResponse(nonce, gwPub, garbage, garbage.length) == null,
                "auth: garbage response rejected");
        check(GatewayAuth.verifyResponse(nonce, gwPub, null, 0) == null,
                "auth: null response rejected");
    }

    // ---- 5. client session over piped streams ----

    /** A fake gateway speaking the preamble + SOCKS on streams. */
    private static Thread fakeGateway(final InputStream in, final OutputStream out,
                                      final byte[] gwSeed, final byte[] gwPub,
                                      final int bndPort, final boolean badChallenge) {
        Thread t = new Thread(() -> {
            try {
                SecureRandom rnd = new SecureRandom();
                byte[] nonce = GatewayAuth.newNonce(rnd);
                if (badChallenge) {
                    out.write(new byte[]{'N', 'O', 'P', 'E'});
                    out.write(nonce);
                } else {
                    out.write(GatewayAuth.challenge(nonce));
                }
                out.flush();
                byte[] resp = SocksClient.Session.readExact(in, GatewayAuth.RESPONSE_LEN);
                byte[] clientPub = GatewayAuth.verifyResponse(nonce, gwPub, resp, resp.length);
                if (clientPub == null) return;
                byte[] greeting = SocksClient.Session.readExact(in, 3);
                if (!Socks5.greetingOffersNoAuth(greeting, 3)) return;
                out.write(Socks5.greetingReply(Socks5.METHOD_NO_AUTH));
                out.flush();
                byte[] head = SocksClient.Session.readExact(in, 4);
                Socks5.Request req;
                if (head[3] == Socks5.ATYP_IPV4) {
                    byte[] rest = SocksClient.Session.readExact(in, 6);
                    byte[] full = new byte[10];
                    System.arraycopy(head, 0, full, 0, 4);
                    System.arraycopy(rest, 0, full, 4, 6);
                    req = Socks5.parseRequest(full, full.length);
                } else {
                    return;
                }
                if (req == null) return;
                out.write(Socks5.reply(Socks5.REP_OK, NatPackets.ip(192, 168, 49, 1), bndPort));
                out.flush();
            } catch (Exception ignored) {
            }
        });
        t.setDaemon(true);
        return t;
    }

    private static void session() throws Exception {
        byte[][] client = RelayKeys.generate();
        byte[][] gateway = RelayKeys.generate();

        // CONNECT end to end.
        {
            PipedOutputStream cOut = new PipedOutputStream();
            PipedInputStream sIn = new PipedInputStream(cOut);
            PipedOutputStream sOut = new PipedOutputStream();
            PipedInputStream cIn = new PipedInputStream(sOut);
            Thread gw = fakeGateway(sIn, sOut, gateway[0], gateway[1], 0, false);
            gw.start();
            SocksClient.Session.authenticate(cIn, cOut, client[0], client[1], gateway[1]);
            SocksClient.Session.greeting(cIn, cOut);
            Socks5.Reply r = SocksClient.Session.connectRequest(cIn, cOut, SERVER_IP, 80);
            eq(0, r.rep, "session: CONNECT accepted");
            gw.join(2000);
            check(!gw.isAlive(), "session: gateway thread finished");
        }

        // UDP ASSOCIATE end to end, bound port carried.
        {
            PipedOutputStream cOut = new PipedOutputStream();
            PipedInputStream sIn = new PipedInputStream(cOut);
            PipedOutputStream sOut = new PipedOutputStream();
            PipedInputStream cIn = new PipedInputStream(sOut);
            Thread gw = fakeGateway(sIn, sOut, gateway[0], gateway[1], 45678, false);
            gw.start();
            SocksClient.Session.authenticate(cIn, cOut, client[0], client[1], gateway[1]);
            SocksClient.Session.greeting(cIn, cOut);
            Socks5.Reply r = SocksClient.Session.udpAssociateRequest(cIn, cOut);
            eq(0, r.rep, "session: UDP ASSOCIATE accepted");
            eq(45678, r.bndPort, "session: bound port carried");
            eq(NatPackets.ip(192, 168, 49, 1), r.bndIpv4, "session: bound addr carried");
            gw.join(2000);
        }

        // Bad challenge magic -> client aborts.
        {
            PipedOutputStream cOut = new PipedOutputStream();
            PipedInputStream sIn = new PipedInputStream(cOut);
            PipedOutputStream sOut = new PipedOutputStream();
            PipedInputStream cIn = new PipedInputStream(sOut);
            Thread gw = fakeGateway(sIn, sOut, gateway[0], gateway[1], 0, true);
            gw.start();
            boolean threw = false;
            try {
                SocksClient.Session.authenticate(cIn, cOut, client[0], client[1], gateway[1]);
            } catch (Exception e) {
                threw = true;
            }
            check(threw, "session: bad challenge aborts");
            gw.join(2000);
        }
    }

    // ---- 6. engine integration ----

    private static final class FakeHost implements NatEngine.Host {
        final List<byte[]> emitted = new ArrayList<>();
        final List<String> udpSent = new ArrayList<>();
        final List<TcpFlow> opened = new ArrayList<>();
        int closeHalf;
        int closeFull;
        int blockedQueries;
        final Set<String> blocked = new HashSet<>(Arrays.asList(
                "ads.example.com", "tracker.example.net"));

        @Override
        public void openTcp(TcpFlow flow, int dstIp, int dstPort) {
            opened.add(flow);
        }

        @Override
        public void writeRemote(TcpFlow flow, byte[] data) {
        }

        @Override
        public void closeRemote(TcpFlow flow, boolean half) {
            if (half) closeHalf++;
            else closeFull++;
        }

        @Override
        public void sendUdp(int dstIp, int dstPort, byte[] payload) {
            udpSent.add(NatPackets.ipString(dstIp) + ":" + dstPort + ":" + payload.length);
        }

        @Override
        public boolean isBlocked(String host) {
            return blocked.contains(host);
        }

        @Override
        public void emit(byte[] ipPacket) {
            emitted.add(ipPacket);
        }

        @Override
        public void onBlockedQuery() {
            blockedQueries++;
        }
    }

    private static void feed(NatEngine engine, byte[] pkt, long now) {
        engine.onPacket(pkt, pkt.length, now);
    }

    private static void engine() {
        FakeHost host = new FakeHost();
        NatEngine engine = new NatEngine(host, () -> 5000L);
        byte[] empty = new byte[0];

        // Full TCP flow through onPacket.
        feed(engine, ipTcp(40000, 80, 1000, 0, NatPackets.TCP_SYN, 65535, 1460, empty), 100);
        eq(1, host.opened.size(), "engine: SYN opens a flow");
        eq(1, host.emitted.size(), "engine: SYN-ACK emitted");
        check((emittedTcp(host.emitted.get(0)).flags & NatPackets.TCP_SYN) != 0,
                "engine: SYN-ACK flags");
        TcpFlow flow = host.opened.get(0);
        feed(engine, ipTcp(40000, 80, 1001, 5001, NatPackets.TCP_ACK, 65535, 0, empty), 101);
        check(flow.state() == TcpFlow.State.ESTABLISHED, "engine: flow established");
        flow.onRemoteConnected(102);
        byte[] hello = "hello".getBytes(StandardCharsets.US_ASCII);
        feed(engine, ipTcp(40000, 80, 1001, 5001, NatPackets.TCP_ACK | NatPackets.TCP_PSH,
                65535, 0, hello), 103);
        flow.onRemoteData("world!".getBytes(StandardCharsets.US_ASCII), 0, 6, 104);
        check(host.emitted.size() >= 3, "engine: data + ACKs emitted");
        feed(engine, ipTcp(40000, 80, 1006, 5007, NatPackets.TCP_ACK | NatPackets.TCP_FIN,
                65535, 0, empty), 105);
        flow.onRemoteClosed(106);
        feed(engine, ipTcp(40000, 80, 1007, 5008, NatPackets.TCP_ACK, 65535, 0, empty), 107);
        eq(0, engine.tcpFlowCount(), "engine: flow closed after FIN exchange");

        // Stray ACK with no flow -> RST.
        int eBefore = host.emitted.size();
        feed(engine, ipTcp(49999, 80, 5000, 0, NatPackets.TCP_ACK, 65535, 0, empty), 110);
        eq(eBefore + 1, host.emitted.size(), "engine: stray segment gets RST");
        NatPackets.Tcp rst = emittedTcp(host.emitted.get(host.emitted.size() - 1));
        check((rst.flags & NatPackets.TCP_RST) != 0, "engine: RST flag set");
        eq(5000, rst.ack, "engine: RST acks the stray seq");

        // Fragment + ICMP dropped silently.
        byte[] fragPkt = ipTcp(40001, 80, 1000, 0, NatPackets.TCP_SYN, 65535, 1460, empty);
        fragPkt[6] = 0x20;
        fragPkt[7] = 0;
        NatPackets.put16(fragPkt, 10, 0);
        NatPackets.put16(fragPkt, 10, NatPackets.checksum(fragPkt, 0, 20));
        eBefore = host.emitted.size();
        engine.onPacket(fragPkt, fragPkt.length, 120);
        eq(eBefore, host.emitted.size(), "engine: fragment dropped");
        eq(0, engine.tcpFlowCount(), "engine: fragment creates no flow");
        byte[] icmp = NatPackets.buildIp(CLIENT_IP, SERVER_IP, NatPackets.PROTO_ICMP, empty, 7);
        engine.onPacket(icmp, icmp.length, 121);
        eq(eBefore, host.emitted.size(), "engine: ICMP dropped");

        // UDP DNS forward + reply.
        byte[] q1 = dnsQuery(0x1234, "example.com");
        feed(engine, ipUdp(53535, DNS_IP, 53, q1), 200);
        eq(1, host.udpSent.size(), "engine: DNS query forwarded");
        check(host.udpSent.get(0).startsWith("10.0.0.1:53:"), "engine: forward keeps dst");
        int uBefore = host.emitted.size();
        byte[] resp = dnsQuery(0x1234, "example.com");
        resp[2] |= 0x80; // QR=1: a response
        engine.onUdpReply(DNS_IP, 53, resp, 201);
        eq(uBefore + 1, host.emitted.size(), "engine: DNS reply emitted");
        NatPackets.Ip rip = NatPackets.parseIp(host.emitted.get(host.emitted.size() - 1),
                host.emitted.get(host.emitted.size() - 1).length);
        NatPackets.Udp ru = NatPackets.parseUdp(host.emitted.get(host.emitted.size() - 1),
                rip.payloadOff, rip.payloadLen);
        eq(53535, ru.dstPort, "engine: reply delivered to query port");
        eq(DNS_IP, rip.src, "engine: reply src is the resolver");

        // Blocked DNS -> local NXDOMAIN, never forwarded.
        int sentBefore = host.udpSent.size();
        byte[] qb = dnsQuery(0x2222, "ads.example.com");
        feed(engine, ipUdp(53536, DNS_IP, 53, qb), 210);
        eq(sentBefore, host.udpSent.size(), "engine: blocked query not forwarded");
        eq(1, host.blockedQueries, "engine: blocked query counted");
        byte[] nxPkt = host.emitted.get(host.emitted.size() - 1);
        NatPackets.Ip nxIp = NatPackets.parseIp(nxPkt, nxPkt.length);
        NatPackets.Udp nxUdp = NatPackets.parseUdp(nxPkt, nxIp.payloadOff, nxIp.payloadLen);
        byte[] nxPayload = Arrays.copyOfRange(nxPkt, nxUdp.payloadOff,
                nxUdp.payloadOff + nxUdp.payloadLen);
        eq(0x2222, NatPackets.dnsId(nxPayload), "engine: NXDOMAIN keeps ID");
        eq(3, nxPayload[3] & 0x0F, "engine: NXDOMAIN RCODE=3");

        // Blocked DNS to a public resolver is still blocked.
        byte[] qb2 = dnsQuery(0x3333, "tracker.example.net");
        feed(engine, ipUdp(53537, NatPackets.ip(8, 8, 8, 8), 53, qb2), 220);
        eq(sentBefore, host.udpSent.size(), "engine: blocked query to 8.8.8.8 not forwarded");
        eq(2, host.blockedQueries, "engine: second blocked query counted");

        // DNS-ID demux: reply ID selects the older flow over the most-recent.
        feed(engine, ipUdp(40001, DNS_IP, 53, dnsQuery(0x1111, "a.example.com")), 300);
        feed(engine, ipUdp(40002, DNS_IP, 53, dnsQuery(0x2222, "b.example.com")), 310);
        byte[] replyOld = dnsQuery(0x1111, "a.example.com");
        replyOld[2] |= 0x80;
        engine.onUdpReply(DNS_IP, 53, replyOld, 320);
        byte[] demuxPkt = host.emitted.get(host.emitted.size() - 1);
        NatPackets.Ip demuxIp = NatPackets.parseIp(demuxPkt, demuxPkt.length);
        NatPackets.Udp demuxUdp = NatPackets.parseUdp(demuxPkt, demuxIp.payloadOff, demuxIp.payloadLen);
        eq(40001, demuxUdp.dstPort, "engine: DNS ID demux picks the matching flow");
        // Unknown ID falls back to the most-recent flow. (The
        // reply above refreshed 40001's recency, so refresh 40002
        // with a new query first to make it most-recent again.)
        feed(engine, ipUdp(40002, DNS_IP, 53, dnsQuery(0x2223, "b.example.com")), 325);
        byte[] replyUnknown = dnsQuery(0x9999, "c.example.com");
        replyUnknown[2] |= 0x80;
        engine.onUdpReply(DNS_IP, 53, replyUnknown, 330);
        byte[] fbPkt = host.emitted.get(host.emitted.size() - 1);
        NatPackets.Ip fbIp = NatPackets.parseIp(fbPkt, fbPkt.length);
        NatPackets.Udp fbUdp = NatPackets.parseUdp(fbPkt, fbIp.payloadOff, fbIp.payloadLen);
        eq(40002, fbUdp.dstPort, "engine: unknown DNS ID falls back to most-recent");

        // UDP idle expiry + shutdown.
        check(engine.udpFlowCount() > 0, "engine: UDP flows tracked");
        engine.onTick(330 + 61_000);
        eq(0, engine.udpFlowCount(), "engine: UDP flows expire");
        feed(engine, ipTcp(40010, 80, 1000, 0, NatPackets.TCP_SYN, 65535, 1460, empty), 400);
        eq(1, engine.tcpFlowCount(), "engine: flow open before shutdown");
        int sBefore = host.emitted.size();
        engine.shutdown(401);
        eq(0, engine.tcpFlowCount(), "engine: shutdown clears TCP");
        check(host.emitted.size() > sBefore, "engine: shutdown RSTs flows");
        check((emittedTcp(host.emitted.get(host.emitted.size() - 1)).flags
                & NatPackets.TCP_RST) != 0, "engine: shutdown RST flag");
    }
}
