package app.control.launcher;

import java.nio.charset.StandardCharsets;

/**
 * IPv4 / TCP / UDP / DNS packet parsing and building for the mesh
 * NAT (v1.10). Pure Java, no Android — the JVM harness exercises
 * this exact code.
 *
 * Scope, honestly: IPv4 only, no IP options on output, no fragment
 * reassembly (fragments are reported and dropped by the engine),
 * TCP options limited to MSS on SYN/SYN-ACK (no window scaling, no
 * SACK — the stack on the other end of the TUN is local and
 * generous, and v1 keeps the wire simple on purpose).
 */
public final class NatPackets {

    public static final int PROTO_ICMP = 1;
    public static final int PROTO_TCP = 6;
    public static final int PROTO_UDP = 17;

    public static final int TCP_FIN = 0x01;
    public static final int TCP_SYN = 0x02;
    public static final int TCP_RST = 0x04;
    public static final int TCP_PSH = 0x08;
    public static final int TCP_ACK = 0x10;
    public static final int TCP_URG = 0x20;

    private NatPackets() {}

    // ---- int / byte helpers (network order) ----

    public static int u8(byte[] d, int off) {
        return d[off] & 0xFF;
    }

    public static int u16(byte[] d, int off) {
        return ((d[off] & 0xFF) << 8) | (d[off + 1] & 0xFF);
    }

    public static long u32(byte[] d, int off) {
        return ((long) (d[off] & 0xFF) << 24)
                | ((long) (d[off + 1] & 0xFF) << 16)
                | ((long) (d[off + 2] & 0xFF) << 8)
                | (d[off + 3] & 0xFF);
    }

    public static void put16(byte[] d, int off, int v) {
        d[off] = (byte) (v >> 8);
        d[off + 1] = (byte) v;
    }

    public static void put32(byte[] d, int off, long v) {
        d[off] = (byte) (v >> 24);
        d[off + 1] = (byte) (v >> 16);
        d[off + 2] = (byte) (v >> 8);
        d[off + 3] = (byte) v;
    }

    public static int ip(int a, int b, int c, int d) {
        return (a << 24) | (b << 16) | (c << 8) | d;
    }

    public static String ipString(int addr) {
        return ((addr >> 24) & 0xFF) + "." + ((addr >> 16) & 0xFF)
                + "." + ((addr >> 8) & 0xFF) + "." + (addr & 0xFF);
    }

    /** One's-complement checksum over [off, off+len). */
    public static int checksum(byte[] d, int off, int len) {
        long sum = 0;
        int i = off;
        int end = off + len;
        while (i + 1 < end) {
            sum += ((d[i] & 0xFF) << 8) | (d[i + 1] & 0xFF);
            i += 2;
        }
        if (i < end) sum += (d[i] & 0xFF) << 8;
        while ((sum >> 16) != 0) sum = (sum & 0xFFFF) + (sum >> 16);
        return (int) (~sum & 0xFFFF);
    }

    // ---- IPv4 ----

    public static final class Ip {
        public int ihl;          // header length in bytes
        public int totalLen;
        public int id;
        public boolean moreFragments;
        public int fragOffset;   // in 8-byte units
        public int ttl;
        public int protocol;
        public int src;
        public int dst;
        public int payloadOff;
        public int payloadLen;

        public boolean isFragment() {
            return moreFragments || fragOffset != 0;
        }
    }

    /** Parses and validates an IPv4 header. Null when malformed:
     *  short, not IPv4, IHL < 5, total length beyond the buffer,
     *  or a bad header checksum. */
    public static Ip parseIp(byte[] d, int len) {
        if (d == null || len < 20) return null;
        if ((d[0] >> 4) != 4) return null;
        int ihl = (d[0] & 0x0F) * 4;
        if (ihl < 20 || len < ihl) return null;
        int total = u16(d, 2);
        if (total < ihl || total > len) return null;
        if (checksum(d, 0, ihl) != 0) return null;
        Ip ip = new Ip();
        ip.ihl = ihl;
        ip.totalLen = total;
        ip.id = u16(d, 4);
        int frag = u16(d, 6);
        ip.moreFragments = (frag & 0x2000) != 0;
        ip.fragOffset = frag & 0x1FFF;
        ip.ttl = u8(d, 8);
        ip.protocol = u8(d, 9);
        ip.src = (int) u32(d, 12);
        ip.dst = (int) u32(d, 16);
        ip.payloadOff = ihl;
        ip.payloadLen = total - ihl;
        return ip;
    }

    /** Builds an IPv4 packet (no options, DF clear, TTL 64). */
    public static byte[] buildIp(int src, int dst, int protocol,
                                 byte[] payload, int id) {
        int total = 20 + payload.length;
        byte[] p = new byte[total];
        p[0] = 0x45;
        p[1] = 0;
        put16(p, 2, total);
        put16(p, 4, id & 0xFFFF);
        put16(p, 6, 0);
        p[8] = 64;
        p[9] = (byte) protocol;
        put32(p, 12, src & 0xFFFFFFFFL);
        put32(p, 16, dst & 0xFFFFFFFFL);
        put16(p, 10, checksum(p, 0, 20));
        System.arraycopy(payload, 0, p, 20, payload.length);
        return p;
    }

    // ---- TCP ----

    public static final class Tcp {
        public int srcPort;
        public int dstPort;
        public long seq;
        public long ack;
        public int flags;
        public int window;
        public int dataOff;      // header length in bytes
        public int mss;          // 0 when no MSS option was present
        public int payloadOff;   // offset within the segment buffer
        public int payloadLen;
    }

    /** Parses a TCP segment starting at off, len bytes available.
     *  Null when short or when the data offset is impossible. The
     *  TCP checksum is NOT verified here — the engine trusts the
     *  local stack's checksums (they arrive over the TUN, not a
     *  wire) and verifying would cost a pass over every payload.
     *  Options are scanned for MSS only; anything else is skipped
     *  by the data offset. */
    public static Tcp parseTcp(byte[] d, int off, int len) {
        if (d == null || len < 20 || off + 20 > d.length) return null;
        int dataOff = (d[off + 12] >> 4) * 4;
        if (dataOff < 20 || dataOff > len) return null;
        Tcp t = new Tcp();
        t.srcPort = u16(d, off);
        t.dstPort = u16(d, off + 2);
        t.seq = u32(d, off + 4);
        t.ack = u32(d, off + 8);
        t.flags = d[off + 13] & 0x3F;
        t.window = u16(d, off + 14);
        t.dataOff = dataOff;
        t.payloadOff = off + dataOff;
        t.payloadLen = len - dataOff;
        // Scan options for MSS (kind 2, len 4). NOP/EOL aware.
        int i = off + 20;
        int end = off + dataOff;
        while (i < end) {
            int kind = u8(d, i);
            if (kind == 0) break; // EOL
            if (kind == 1) { i++; continue; } // NOP
            if (i + 1 >= end) break;
            int optLen = u8(d, i + 1);
            if (optLen < 2 || i + optLen > end) break;
            if (kind == 2 && optLen == 4) t.mss = u16(d, i + 2);
            i += optLen;
        }
        return t;
    }

    /** Builds a TCP segment (no payload checksum shortcut: the
     *  checksum covers the pseudo-header + header + payload).
     *  mss > 0 adds the MSS option (SYN / SYN-ACK only). */
    public static byte[] buildTcp(int srcIp, int dstIp, int srcPort, int dstPort,
                                  long seq, long ack, int flags, int window,
                                  int mss, byte[] payload, int payloadOff, int payloadLen) {
        int optLen = mss > 0 ? 4 : 0;
        int headerLen = 20 + optLen;
        byte[] seg = new byte[headerLen + payloadLen];
        put16(seg, 0, srcPort);
        put16(seg, 2, dstPort);
        put32(seg, 4, seq);
        put32(seg, 8, ack);
        seg[12] = (byte) ((headerLen / 4) << 4);
        seg[13] = (byte) (flags & 0x3F);
        put16(seg, 14, window & 0xFFFF);
        put16(seg, 16, 0); // checksum, filled below
        put16(seg, 18, 0); // urgent pointer
        if (mss > 0) {
            seg[20] = 2;
            seg[21] = 4;
            put16(seg, 22, mss);
        }
        if (payloadLen > 0) {
            System.arraycopy(payload, payloadOff, seg, headerLen, payloadLen);
        }
        put16(seg, 16, tcpChecksum(srcIp, dstIp, seg, seg.length));
        return seg;
    }

    public static int tcpChecksum(int srcIp, int dstIp, byte[] seg, int len) {
        long sum = 0;
        sum += (srcIp >> 16) & 0xFFFF;
        sum += srcIp & 0xFFFF;
        sum += (dstIp >> 16) & 0xFFFF;
        sum += dstIp & 0xFFFF;
        sum += PROTO_TCP;
        sum += len;
        int i = 0;
        while (i + 1 < len) {
            sum += ((seg[i] & 0xFF) << 8) | (seg[i + 1] & 0xFF);
            i += 2;
        }
        if (i < len) sum += (seg[i] & 0xFF) << 8;
        while ((sum >> 16) != 0) sum = (sum & 0xFFFF) + (sum >> 16);
        return (int) (~sum & 0xFFFF);
    }

    // ---- UDP ----

    public static final class Udp {
        public int srcPort;
        public int dstPort;
        public int len;
        public int payloadOff;
        public int payloadLen;
    }

    public static Udp parseUdp(byte[] d, int off, int len) {
        if (d == null || len < 8 || off + 8 > d.length) return null;
        int total = u16(d, off + 4);
        if (total < 8 || total > len) return null;
        Udp u = new Udp();
        u.srcPort = u16(d, off);
        u.dstPort = u16(d, off + 2);
        u.len = total;
        u.payloadOff = off + 8;
        u.payloadLen = total - 8;
        return u;
    }

    /** Builds a UDP datagram with a correct checksum (0 becomes
     *  0xFFFF, per RFC 768 — a transmitted zero means "no
     *  checksum", which we never mean). */
    public static byte[] buildUdp(int srcIp, int dstIp, int srcPort, int dstPort,
                                  byte[] payload, int payloadOff, int payloadLen) {
        int total = 8 + payloadLen;
        byte[] seg = new byte[total];
        put16(seg, 0, srcPort);
        put16(seg, 2, dstPort);
        put16(seg, 4, total);
        put16(seg, 6, 0);
        if (payloadLen > 0) {
            System.arraycopy(payload, payloadOff, seg, 8, payloadLen);
        }
        int c = udpChecksum(srcIp, dstIp, seg, total);
        put16(seg, 6, c == 0 ? 0xFFFF : c);
        return seg;
    }

    public static int udpChecksum(int srcIp, int dstIp, byte[] seg, int len) {
        long sum = 0;
        sum += (srcIp >> 16) & 0xFFFF;
        sum += srcIp & 0xFFFF;
        sum += (dstIp >> 16) & 0xFFFF;
        sum += dstIp & 0xFFFF;
        sum += PROTO_UDP;
        sum += len;
        int i = 0;
        while (i + 1 < len) {
            sum += ((seg[i] & 0xFF) << 8) | (seg[i + 1] & 0xFF);
            i += 2;
        }
        if (i < len) sum += (seg[i] & 0xFF) << 8;
        while ((sum >> 16) != 0) sum = (sum & 0xFFFF) + (sum >> 16);
        return (int) (~sum & 0xFFFF);
    }

    // ---- DNS (same semantics as DnsVpnService's helpers, factored
    // pure so the NAT engine and the harness share them) ----

    /** The DNS transaction ID, or -1 when the message is too short. */
    public static int dnsId(byte[] dns) {
        if (dns == null || dns.length < 2) return -1;
        return u16(dns, 0);
    }

    /** True when the message looks like a standard query (QR=0). */
    public static boolean isDnsQuery(byte[] dns) {
        if (dns == null || dns.length < 12) return false;
        return (dns[2] & 0x80) == 0;
    }

    /** Extracts the query name from a DNS message, or null if
     *  unparseable. */
    public static String parseQName(byte[] dns) {
        try {
            if (dns == null || dns.length < 13) return null;
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

    /** Turns a query into an NXDOMAIN response (keeps header +
     *  question). */
    public static byte[] nxDomain(byte[] query) {
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
}
