package app.control.launcher;

import java.nio.charset.StandardCharsets;

/**
 * SOCKS5 wire framing (RFC 1928 + RFC 1929 §UDP), pure Java, no
 * Android — both the client (NatVpnService side) and the gateway
 * relay build and parse with this exact code, and the JVM harness
 * exercises it directly.
 *
 * Supported: greeting with NO_AUTH (authentication happens at the
 * Control preamble layer — see GatewayAuth — before any SOCKS
 * bytes), CONNECT, UDP ASSOCIATE, IPv4 + domain address forms.
 * BIND is refused. IPv6 address form is refused (the NAT is IPv4
 * only in v1).
 */
public final class Socks5 {

    public static final int VER = 0x05;
    public static final int METHOD_NO_AUTH = 0x00;
    public static final int METHOD_NONE_ACCEPTABLE = 0xFF;

    public static final int CMD_CONNECT = 0x01;
    public static final int CMD_BIND = 0x02;
    public static final int CMD_UDP_ASSOCIATE = 0x03;

    public static final int REP_OK = 0x00;
    public static final int REP_GENERAL_FAILURE = 0x01;
    public static final int REP_NOT_ALLOWED = 0x02;
    public static final int REP_NET_UNREACHABLE = 0x03;
    public static final int REP_HOST_UNREACHABLE = 0x04;
    public static final int REP_CONN_REFUSED = 0x05;
    public static final int REP_TTL_EXPIRED = 0x06;
    public static final int REP_CMD_UNSUPPORTED = 0x07;
    public static final int REP_ADDR_UNSUPPORTED = 0x08;

    public static final int ATYP_IPV4 = 0x01;
    public static final int ATYP_DOMAIN = 0x03;
    public static final int ATYP_IPV6 = 0x04;

    private Socks5() {}

    // ---- greeting ----

    /** Client greeting offering NO_AUTH only. */
    public static byte[] greeting() {
        return new byte[]{(byte) VER, 0x01, (byte) METHOD_NO_AUTH};
    }

    /** Parses a greeting reply [VER, METHOD]. Returns the method,
     *  or -1 when malformed. */
    public static int parseGreetingReply(byte[] d, int len) {
        if (d == null || len < 2 || d[0] != VER) return -1;
        return d[1] & 0xFF;
    }

    /** Server greeting reply selecting a method. */
    public static byte[] greetingReply(int method) {
        return new byte[]{(byte) VER, (byte) method};
    }

    /** Parses a client greeting [VER, NMETHODS, METHODS...].
     *  Returns true when it offers NO_AUTH. */
    public static boolean greetingOffersNoAuth(byte[] d, int len) {
        if (d == null || len < 3 || d[0] != VER) return false;
        int n = d[1] & 0xFF;
        if (len < 2 + n) return false;
        for (int i = 0; i < n; i++) {
            if ((d[2 + i] & 0xFF) == METHOD_NO_AUTH) return true;
        }
        return false;
    }

    // ---- requests / replies ----

    public static final class Request {
        public int cmd;
        public int atyp;
        public int ipv4;       // when atyp == IPV4
        public String domain;  // when atyp == DOMAIN
        public int port;
    }

    /** Builds a CONNECT / UDP ASSOCIATE request to an IPv4 target. */
    public static byte[] request(int cmd, int ipv4, int port) {
        byte[] r = new byte[10];
        r[0] = (byte) VER;
        r[1] = (byte) cmd;
        r[2] = 0;
        r[3] = (byte) ATYP_IPV4;
        NatPackets.put32(r, 4, ipv4 & 0xFFFFFFFFL);
        NatPackets.put16(r, 8, port);
        return r;
    }

    /** Builds a request to a domain target. */
    public static byte[] requestDomain(int cmd, String domain, int port) {
        byte[] name = domain.getBytes(StandardCharsets.US_ASCII);
        byte[] r = new byte[7 + name.length];
        r[0] = (byte) VER;
        r[1] = (byte) cmd;
        r[2] = 0;
        r[3] = (byte) ATYP_DOMAIN;
        r[4] = (byte) name.length;
        System.arraycopy(name, 0, r, 5, name.length);
        NatPackets.put16(r, 5 + name.length, port);
        return r;
    }

    /** Parses a request. Null when malformed, truncated, or an
     *  unsupported address form (IPv6). */
    public static Request parseRequest(byte[] d, int len) {
        if (d == null || len < 7 || d[0] != VER) return null;
        Request r = new Request();
        r.cmd = d[1] & 0xFF;
        r.atyp = d[3] & 0xFF;
        if (r.atyp == ATYP_IPV4) {
            if (len < 10) return null;
            r.ipv4 = (int) NatPackets.u32(d, 4);
            r.port = NatPackets.u16(d, 8);
        } else if (r.atyp == ATYP_DOMAIN) {
            int n = d[4] & 0xFF;
            if (len < 7 + n) return null;
            r.domain = new String(d, 5, n, StandardCharsets.US_ASCII);
            r.port = NatPackets.u16(d, 5 + n);
        } else {
            return null; // IPv6 and anything else: unsupported in v1
        }
        return r;
    }

    /** Builds a reply carrying an IPv4 bound address. */
    public static byte[] reply(int rep, int bndIpv4, int bndPort) {
        byte[] r = new byte[10];
        r[0] = (byte) VER;
        r[1] = (byte) rep;
        r[2] = 0;
        r[3] = (byte) ATYP_IPV4;
        NatPackets.put32(r, 4, bndIpv4 & 0xFFFFFFFFL);
        NatPackets.put16(r, 8, bndPort);
        return r;
    }

    public static final class Reply {
        public int rep;
        public int bndIpv4;
        public int bndPort;
    }

    /** Parses a reply. Handles IPv4 and domain bound forms (domain
     *  is accepted but the address is left 0 — callers that need
     *  the UDP relay address require the IPv4 form). Null when
     *  malformed. */
    public static Reply parseReply(byte[] d, int len) {
        if (d == null || len < 7 || d[0] != VER) return null;
        Reply r = new Reply();
        r.rep = d[1] & 0xFF;
        int atyp = d[3] & 0xFF;
        if (atyp == ATYP_IPV4) {
            if (len < 10) return null;
            r.bndIpv4 = (int) NatPackets.u32(d, 4);
            r.bndPort = NatPackets.u16(d, 8);
        } else if (atyp == ATYP_DOMAIN) {
            int n = d[4] & 0xFF;
            if (len < 7 + n) return null;
            r.bndPort = NatPackets.u16(d, 5 + n);
        } else {
            return null;
        }
        return r;
    }

    // ---- UDP ASSOCIATE datagrams (RFC 1928 §7) ----
    // [RSV:2][FRAG:1][ATYP:1][DST.ADDR][DST.PORT][DATA]

    public static final class Datagram {
        public int frag;
        public int dstIpv4;
        public String dstDomain;
        public int dstPort;
        public int dataOff;
        public int dataLen;
    }

    /** Wraps a payload in a SOCKS UDP datagram to an IPv4 target. */
    public static byte[] wrapUdp(int dstIpv4, int dstPort,
                                 byte[] payload, int off, int len) {
        byte[] out = new byte[10 + len];
        out[0] = 0;
        out[1] = 0;
        out[2] = 0; // FRAG: no fragmentation in v1
        out[3] = (byte) ATYP_IPV4;
        NatPackets.put32(out, 4, dstIpv4 & 0xFFFFFFFFL);
        NatPackets.put16(out, 8, dstPort);
        if (len > 0) System.arraycopy(payload, off, out, 10, len);
        return out;
    }

    /** Unwraps a SOCKS UDP datagram. Null when malformed,
     *  truncated, fragmented (FRAG != 0 — v1 never fragments), or
     *  an unsupported address form. */
    public static Datagram parseUdp(byte[] d, int len) {
        if (d == null || len < 10) return null;
        if (d[2] != 0) return null; // fragmented: unsupported
        Datagram g = new Datagram();
        g.frag = 0;
        int atyp = d[3] & 0xFF;
        if (atyp == ATYP_IPV4) {
            g.dstIpv4 = (int) NatPackets.u32(d, 4);
            g.dstPort = NatPackets.u16(d, 8);
            g.dataOff = 10;
            g.dataLen = len - 10;
        } else if (atyp == ATYP_DOMAIN) {
            int n = d[4] & 0xFF;
            if (len < 7 + n) return null;
            g.dstDomain = new String(d, 5, n, StandardCharsets.US_ASCII);
            g.dstPort = NatPackets.u16(d, 5 + n);
            g.dataOff = 7 + n;
            g.dataLen = len - (7 + n);
        } else {
            return null;
        }
        return g;
    }
}
