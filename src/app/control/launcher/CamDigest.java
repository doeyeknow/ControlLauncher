package app.control.launcher;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * HTTP Digest authentication (RFC 2617, with the RFC 7616
 * algorithm names), for camera snapshot fetches. Most IP
 * cameras answer their snapshot URL with a Digest challenge;
 * Basic alone would leave most of them unreachable.
 *
 * Supported: MD5 and MD5-sess, qop=auth (preferred),
 * qop=auth-int (empty-body hash — snapshot fetches are GETs
 * with no body), and the no-qop RFC 2069 form. SHA-256 and
 * SHA-512-256 are NOT implemented: a challenge that offers
 * only a SHA algorithm is refused honestly (the camera's
 * auth settings, not this code, decide that) rather than
 * answered wrongly. userhash is not honored: the username
 * is always sent in the clear, as the RFC allows a client
 * to do when it does not implement userhash.
 *
 * Pure Java, no Android types: the harness proves the
 * challenge parsing and the response computation against
 * the RFC 2617 §3.5 test vector and independently computed
 * MD5-sess / no-qop / auth-int vectors.
 *
 * The nonce-count (nc) is the caller's to track: one
 * counter per (camera, nonce), incremented per request and
 * reset when the nonce changes. This class only formats it
 * (8 lowercase hex digits, zero-padded).
 */
public final class CamDigest {

    private CamDigest() {}

    /** A parsed Digest challenge (one WWW-Authenticate header's
     *  parameters). Null fields mean the challenge omitted them. */
    public static final class Challenge {
        public String realm;
        public String nonce;
        public String opaque;
        public String qop;        // raw qop list, e.g. "auth,auth-int"
        public String algorithm;  // raw, e.g. "MD5" or "MD5-sess"
        public boolean stale;
        public String charset;
        public boolean userhash;
    }

    /**
     * Parses a WWW-Authenticate header value. Returns null
     * when the value is not a Digest challenge, or when it
     * lacks a realm or a nonce (unanswerable). Quoted values
     * may contain commas and backslash escapes; bare tokens
     * are accepted for qop/algorithm/stale. Parameter names
     * are matched case-insensitively; unknown parameters are
     * ignored. Multiple challenges in one header (Basic +
     * Digest) are handled by scanning for the Digest scheme.
     */
    public static Challenge parse(String header) {
        if (header == null) return null;
        int d = findDigest(header);
        if (d < 0) return null;
        Map<String, String> params = parseParams(header, d + 6);
        Challenge c = new Challenge();
        c.realm = params.get("realm");
        c.nonce = params.get("nonce");
        c.opaque = params.get("opaque");
        c.qop = params.get("qop");
        c.algorithm = params.get("algorithm");
        c.charset = params.get("charset");
        c.stale = "true".equalsIgnoreCase(params.get("stale"));
        c.userhash = "true".equalsIgnoreCase(params.get("userhash"));
        if (c.realm == null || c.realm.isEmpty()) return null;
        if (c.nonce == null || c.nonce.isEmpty()) return null;
        return c;
    }

    /** Finds the "Digest" scheme token (case-insensitive,
     *  followed by space or end), or -1. */
    private static int findDigest(String header) {
        String lower = header.toLowerCase();
        int i = 0;
        while (true) {
            int at = lower.indexOf("digest", i);
            if (at < 0) return -1;
            int end = at + 6;
            if (end >= header.length()
                    || header.charAt(end) == ' ' || header.charAt(end) == '\t') {
                return at;
            }
            i = end;
        }
    }

    /** Parses name=value pairs after the scheme token. Stops
     *  at a bare token that starts another scheme (a token
     *  followed by whitespace and then a name= pair is
     *  treated as a new challenge — Digest params always
     *  carry '='). */
    private static Map<String, String> parseParams(String header, int from) {
        Map<String, String> out = new LinkedHashMap<>();
        int i = from;
        int n = header.length();
        while (i < n) {
            while (i < n && (header.charAt(i) == ' ' || header.charAt(i) == '\t'
                    || header.charAt(i) == ',')) i++;
            if (i >= n) break;
            int eq = header.indexOf('=', i);
            if (eq < 0) break;
            String name = header.substring(i, eq).trim().toLowerCase();
            if (name.isEmpty() || name.indexOf(' ') >= 0 || name.indexOf('\t') >= 0) break;
            i = eq + 1;
            while (i < n && (header.charAt(i) == ' ' || header.charAt(i) == '\t')) i++;
            String value;
            if (i < n && header.charAt(i) == '"') {
                StringBuilder sb = new StringBuilder();
                i++;
                boolean esc = false;
                while (i < n) {
                    char ch = header.charAt(i++);
                    if (esc) { sb.append(ch); esc = false; }
                    else if (ch == '\\') esc = true;
                    else if (ch == '"') break;
                    else sb.append(ch);
                }
                value = sb.toString();
            } else {
                int end = i;
                while (end < n && header.charAt(end) != ','
                        && header.charAt(end) != ' ' && header.charAt(end) != '\t') end++;
                value = header.substring(i, end);
                i = end;
            }
            out.put(name, value);
        }
        return out;
    }

    /** True when this challenge can be answered: MD5-family
     *  algorithm (or none, which means MD5) and a usable qop
     *  (auth, auth-int, or none). */
    public static boolean answerable(Challenge c) {
        if (c == null) return false;
        String alg = c.algorithm == null ? "MD5" : c.algorithm.trim();
        if (!"MD5".equalsIgnoreCase(alg) && !"MD5-sess".equalsIgnoreCase(alg)) return false;
        return qopChoice(c) != null;
    }

    /** The qop to answer with: "auth" when offered, else
     *  "auth-int", else "" for the no-qop form, else null
     *  when the challenge demands a qop we cannot do. */
    static String qopChoice(Challenge c) {
        if (c.qop == null || c.qop.trim().isEmpty()) return "";
        String[] offered = c.qop.split(",");
        boolean authInt = false;
        for (String o : offered) {
            String t = o.trim();
            if ("auth".equalsIgnoreCase(t)) return "auth";
            if ("auth-int".equalsIgnoreCase(t)) authInt = true;
        }
        return authInt ? "auth-int" : null;
    }

    /** Formats a nonce count: 8 lowercase hex digits. */
    public static String formatNc(long nc) {
        return String.format("%08x", nc & 0xFFFFFFFFL);
    }

    /** A fresh client nonce: 16 random hex chars. */
    public static String freshCnonce() {
        byte[] b = new byte[8];
        new SecureRandom().nextBytes(b);
        StringBuilder sb = new StringBuilder(16);
        for (byte v : b) sb.append(String.format("%02x", v & 0xFF));
        return sb.toString();
    }

    /**
     * Computes the response digest for a request. {@code nc}
     * is the raw count (formatted here); {@code cnonce} is
     * the client nonce (ignored in the no-qop form).
     * Returns null when the challenge is not answerable.
     */
    public static String response(Challenge c, String username, String password,
                                  String method, String uri, long nc, String cnonce) {
        if (!answerable(c)) return null;
        String qop = qopChoice(c);
        String alg = c.algorithm == null ? "MD5" : c.algorithm.trim();
        boolean sess = "MD5-sess".equalsIgnoreCase(alg);
        String ha1 = md5Hex(username + ":" + c.realm + ":" + password);
        if (sess) {
            ha1 = md5Hex(ha1 + ":" + c.nonce + ":" + cnonce);
        }
        String ha2;
        if ("auth-int".equals(qop)) {
            ha2 = md5Hex(method + ":" + uri + ":" + md5Hex(""));
        } else {
            ha2 = md5Hex(method + ":" + uri);
        }
        if (qop.isEmpty()) {
            return md5Hex(ha1 + ":" + c.nonce + ":" + ha2);
        }
        return md5Hex(ha1 + ":" + c.nonce + ":" + formatNc(nc)
                + ":" + cnonce + ":" + qop + ":" + ha2);
    }

    /**
     * Builds the full Authorization header value for a
     * request. The caller supplies nc + cnonce (see
     * {@link #freshCnonce}); the response is computed here
     * so the header and the digest can never disagree.
     * Returns null when the challenge is not answerable.
     */
    public static String authorization(Challenge c, String username, String password,
                                       String method, String uri, long nc, String cnonce) {
        String resp = response(c, username, password, method, uri, nc, cnonce);
        if (resp == null) return null;
        String qop = qopChoice(c);
        StringBuilder h = new StringBuilder("Digest ");
        h.append("username=\"").append(escape(username)).append("\", ");
        h.append("realm=\"").append(escape(c.realm)).append("\", ");
        h.append("nonce=\"").append(escape(c.nonce)).append("\", ");
        h.append("uri=\"").append(escape(uri)).append("\", ");
        h.append("response=\"").append(resp).append("\"");
        if (c.algorithm != null && !c.algorithm.trim().isEmpty()) {
            h.append(", algorithm=").append(c.algorithm.trim());
        }
        if (c.opaque != null) {
            h.append(", opaque=\"").append(escape(c.opaque)).append("\"");
        }
        if (!qop.isEmpty()) {
            h.append(", qop=").append(qop);
            h.append(", nc=").append(formatNc(nc));
            h.append(", cnonce=\"").append(escape(cnonce)).append("\"");
        }
        if (c.charset != null && !c.charset.trim().isEmpty()) {
            h.append(", charset=").append(c.charset.trim());
        }
        return h.toString();
    }

    private static String escape(String v) {
        return v.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    static String md5Hex(String text) {
        return md5Hex(text.getBytes(StandardCharsets.ISO_8859_1));
    }

    static String md5Hex(byte[] data) {
        try {
            byte[] d = MessageDigest.getInstance("MD5").digest(data);
            StringBuilder sb = new StringBuilder(32);
            for (byte b : d) sb.append(String.format("%02x", b & 0xFF));
            return sb.toString();
        } catch (Exception e) {
            throw new IllegalStateException("MD5 unavailable", e);
        }
    }
}
