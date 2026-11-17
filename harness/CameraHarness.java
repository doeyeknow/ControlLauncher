package app.control.launcher;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;

import org.json.JSONObject;

/**
 * Off-device verification for the v1.31 Cameras page,
 * exercising the exact shipping rules (CamDigest, CamHttp,
 * MjpegParser, CamDiscovery, CamOnvif, CamFeed, CamProxy,
 * CamRecord, and the WalletCrypto seam CamStore seals
 * credentials with):
 *
 * Legs:
 *   1. DIGEST: the RFC 2617 test vector (qop=auth), the
 *      no-qop form, MD5-sess, and auth-int — all four
 *      cross-checked against hashlib; challenge parsing
 *      (quoted commas, escapes, multi-challenge headers,
 *      missing realm/nonce, stale, SHA-only refusal, qop
 *      choice); nc formatting; the Authorization header
 *      shape; and CamHttp's 401 handling (digest
 *      preferred over basic, basic fallback, null when
 *      neither is answerable).
 *   2. HTTP_PARSE: status variants, header
 *      case-insensitivity, content-length, chunked
 *      (extensions, trailers, empty), close-delimited,
 *      204/304, the caps, malformed input, and URL
 *      parsing (userinfo/https rejection, IPv6, bad
 *      ports). The parser runs over byte arrays: no
 *      socket is opened, because the sandbox garbles
 *      JVM IPv4 loopback traffic (the Share harness
 *      rule). The socket byte flow itself is verified
 *      by the on-device checklist.
 *   3. MJPEG: split-boundary feeds (every split point
 *      of a two-frame stream), preamble junk,
 *      content-length and no-length parts, oversized
 *      drop + resync, multiple frames per feed, the
 *      closing delimiter, non-JPEG parts skipped,
 *      boundary extraction forms, and the dashed
 *      boundary parameter.
 *   4. DISCOVERY: merge/dedupe by IP, name-rank
 *      precedence in both orders, ports/xaddrs union,
 *      the vendor table, sweep fingerprint rules, the
 *      mDNS marking rules, and /24 enumeration.
 *   5. ONVIF: the probe envelope (dn namespace, fresh
 *      UUID), ProbeMatches parsing (multi-match,
 *      XAddrs, scopes, malformed input, prefix
 *      tolerance), scope name/hardware decoding, the
 *      media SOAP builders, and mediaUri/fault.
 *   6. FEED: candidate order (vendor-first, deduped,
 *      no credentials in any pattern), URL building
 *      (userinfo/https refusal), JPEG validation,
 *      content-type checks, and refresh clamping and
 *      labels.
 *   7. PROXY: announcement round trip, the route
 *      table (traversal, encoded, and wrong-method
 *      rejections), the id charset, and the stale
 *      boundary.
 *   8. STORE_SHAPE: the stored record's JSON round
 *      trip, no plaintext credentials in the JSON,
 *      refresh/port clamping, id stability, host
 *      validation, and the credential join/split.
 *   9. CREDENTIAL_CRYPTO: the real WalletCrypto seam
 *      on the JVM with a generated AES-256 key —
 *      round trip, tamper failure, wrong-key failure,
 *      empty credentials, fresh IVs, and the too-short
 *      rejection. (The Android Keystore key itself
 *      cannot exist on the JVM; the seam is the same
 *      code path CamStore calls with its
 *      control-cameras key.)
 *  10. RESOLUTION_STATE: the feed-resolution order as
 *      encoded in the pure classes — ONVIF URIs gated
 *      before patterns, vendor patterns before
 *      generic, manual URLs accepted last. The probe
 *      loop that walks this order (CamFetcher) needs
 *      an Android Context for resolveFeed and is
 *      verified by inspection + the on-device list;
 *      the ordering it walks is pinned here.
 *
 * Run:
 *   javac -encoding UTF-8 --release 17 -sourcepath src \
 *     -cp harness/org-json.jar -d harness/classes \
 *     harness/CameraHarness.java
 *   java -cp harness/classes:harness/org-json.jar \
 *     app.control.launcher.CameraHarness
 * No Android classes, no network.
 */
public final class CameraHarness {

    private static int checks;
    private static int failures;

    private static void check(String name, boolean ok) {
        checks++;
        if (ok) {
            System.out.println("PASS  " + name);
        } else {
            failures++;
            System.out.println("FAIL  " + name);
        }
    }

    private static void checkEq(String name, Object want, Object got) {
        checks++;
        if (want == null ? got == null : want.equals(got)) {
            System.out.println("PASS  " + name);
        } else {
            failures++;
            System.out.println("FAIL  " + name + "  -- want <" + want + "> got <" + got + ">");
        }
    }

    public static void main(String[] args) throws Exception {
        digest();
        httpParse();
        mjpeg();
        discovery();
        onvif();
        feed();
        proxy();
        storeShape();
        credentialCrypto();
        resolutionState();
        System.out.println("CameraHarness: " + (checks - failures) + "/" + checks);
        if (failures > 0) System.exit(1);
    }

    // ---- 1. DIGEST ----

    private static final String RFC_REALM = "testrealm@host.com";
    private static final String RFC_NONCE = "dcd98b7102dd2f0e8b11d0f600bfb0c093";

    private static CamDigest.Challenge rfcChallenge(String qop, String algorithm) {
        CamDigest.Challenge c = new CamDigest.Challenge();
        c.realm = RFC_REALM;
        c.nonce = RFC_NONCE;
        c.qop = qop;
        c.algorithm = algorithm;
        return c;
    }

    private static void digest() {
        // The RFC 2617 section 3.5 vector, verbatim.
        checkEq("digest rfc2617 qop=auth",
                "6629fae49393a05397450978507c4ef1",
                CamDigest.response(rfcChallenge("auth", "MD5"),
                        "Mufasa", "Circle Of Life",
                        "GET", "/dir/index.html", 1, "0a4f113b"));
        checkEq("digest no-qop",
                "670fd8c2df070c60b045671b8b24ff02",
                CamDigest.response(rfcChallenge(null, "MD5"),
                        "Mufasa", "Circle Of Life",
                        "GET", "/dir/index.html", 1, "0a4f113b"));
        checkEq("digest md5-sess",
                "8e3825c57e897f5a0dec6c2d4e5059d0",
                CamDigest.response(rfcChallenge("auth", "MD5-sess"),
                        "Mufasa", "Circle Of Life",
                        "GET", "/dir/index.html", 1, "0a4f113b"));
        checkEq("digest auth-int empty body",
                "5e6610ecf9ba3017a4870ad48e3ad30b",
                CamDigest.response(rfcChallenge("auth-int", "MD5"),
                        "Mufasa", "Circle Of Life",
                        "GET", "/dir/index.html", 1, "0a4f113b"));
        // qop choice: auth wins over auth-int when both offered.
        checkEq("digest qop auth preferred",
                "6629fae49393a05397450978507c4ef1",
                CamDigest.response(rfcChallenge("auth,auth-int", "MD5"),
                        "Mufasa", "Circle Of Life",
                        "GET", "/dir/index.html", 1, "0a4f113b"));
        checkEq("digest qop auth-int only",
                "5e6610ecf9ba3017a4870ad48e3ad30b",
                CamDigest.response(rfcChallenge("auth-int", null),
                        "Mufasa", "Circle Of Life",
                        "GET", "/dir/index.html", 1, "0a4f113b"));
        // An unknown qop cannot be answered.
        check("digest unknown qop refused",
                CamDigest.response(rfcChallenge("weird", "MD5"),
                        "Mufasa", "Circle Of Life",
                        "GET", "/dir/index.html", 1, "0a4f113b") == null);
        // SHA-only challenges are refused honestly.
        check("digest sha-256 refused",
                !CamDigest.answerable(rfcChallenge("auth", "SHA-256")));
        check("digest sha-512 refused",
                !CamDigest.answerable(rfcChallenge("auth", "SHA-512-256")));
        check("digest md5 answerable", CamDigest.answerable(rfcChallenge("auth", "MD5")));
        check("digest no algorithm defaults md5",
                CamDigest.answerable(rfcChallenge("auth", null)));
        check("digest null unanswerable", !CamDigest.answerable(null));

        // Parsing.
        CamDigest.Challenge p = CamDigest.parse(
                "Digest realm=\"testrealm@host.com\", nonce=\"" + RFC_NONCE + "\", "
                        + "qop=\"auth\", algorithm=MD5, opaque=\"abc\"");
        check("digest parse realm", p != null && RFC_REALM.equals(p.realm));
        check("digest parse nonce", p != null && RFC_NONCE.equals(p.nonce));
        check("digest parse qop", p != null && "auth".equals(p.qop));
        check("digest parse algorithm", p != null && "MD5".equals(p.algorithm));
        check("digest parse opaque", p != null && "abc".equals(p.opaque));
        check("digest basic header not digest",
                CamDigest.parse("Basic realm=\"x\"") == null);
        check("digest missing realm null",
                CamDigest.parse("Digest nonce=\"abc\"") == null);
        check("digest missing nonce null",
                CamDigest.parse("Digest realm=\"abc\"") == null);
        check("digest null header null", CamDigest.parse(null) == null);
        check("digest empty header null", CamDigest.parse("") == null);
        // Quoted commas and escapes inside values.
        CamDigest.Challenge q = CamDigest.parse(
                "Digest realm=\"a,b\\\"c\", nonce=\"n1\", qop=auth");
        check("digest quoted comma realm",
                q != null && "a,b\"c".equals(q.realm));
        // Multiple challenges: Basic first, Digest second.
        CamDigest.Challenge m = CamDigest.parse(
                "Basic realm=\"x\", Digest realm=\"r2\", nonce=\"n2\", qop=\"auth\"");
        check("digest multi-challenge finds digest",
                m != null && "r2".equals(m.realm) && "n2".equals(m.nonce));
        // Case-insensitive parameter names.
        CamDigest.Challenge ci = CamDigest.parse(
                "Digest Realm=\"R\", Nonce=\"N\", QOP=\"auth\"");
        check("digest param names case-insensitive",
                ci != null && "R".equals(ci.realm) && "N".equals(ci.nonce));
        // stale=true parses and stays answerable (the retry
        // IS the fresh attempt).
        CamDigest.Challenge st = CamDigest.parse(
                "Digest realm=\"r\", nonce=\"n\", stale=true, qop=auth");
        check("digest stale parsed", st != null && st.stale);
        check("digest stale answerable", CamDigest.answerable(st));
        // Unknown parameters are ignored.
        CamDigest.Challenge unk = CamDigest.parse(
                "Digest realm=\"r\", nonce=\"n\", vendor-thing=\"zzz\", qop=auth");
        check("digest unknown params ignored",
                unk != null && CamDigest.answerable(unk));

        // nc formatting.
        checkEq("digest nc 1", "00000001", CamDigest.formatNc(1));
        checkEq("digest nc 0", "00000000", CamDigest.formatNc(0));
        checkEq("digest nc 255", "000000ff", CamDigest.formatNc(255));
        checkEq("digest nc large", "12345678", CamDigest.formatNc(0x12345678L));
        String cn = CamDigest.freshCnonce();
        check("digest cnonce 16 hex",
                cn != null && cn.matches("[0-9a-f]{16}"));

        // The Authorization header carries the RFC digest.
        String auth = CamDigest.authorization(rfcChallenge("auth", "MD5"),
                "Mufasa", "Circle Of Life",
                "GET", "/dir/index.html", 1, "0a4f113b");
        check("digest header starts digest", auth != null && auth.startsWith("Digest "));
        check("digest header response", auth != null
                && auth.contains("response=\"6629fae49393a05397450978507c4ef1\""));
        check("digest header nc", auth != null && auth.contains("nc=00000001"));
        check("digest header qop", auth != null && auth.contains("qop=auth"));
        check("digest header cnonce", auth != null && auth.contains("cnonce=\"0a4f113b\""));
        check("digest header algorithm", auth != null && auth.contains("algorithm=MD5"));
        CamDigest.Challenge withOpaque = rfcChallenge("auth", "MD5");
        withOpaque.opaque = "opaque-data";
        String authOp = CamDigest.authorization(withOpaque,
                "Mufasa", "Circle Of Life", "GET", "/dir/index.html", 1, "0a4f113b");
        check("digest header opaque", authOp != null
                && authOp.contains("opaque=\"opaque-data\""));
        check("digest header null when sha-only",
                CamDigest.authorization(rfcChallenge("auth", "SHA-256"),
                        "u", "p", "GET", "/x", 1, "c") == null);
        // No-qop header omits qop/nc/cnonce.
        String authNoQop = CamDigest.authorization(rfcChallenge(null, "MD5"),
                "Mufasa", "Circle Of Life", "GET", "/dir/index.html", 1, "0a4f113b");
        check("digest no-qop header omits qop", authNoQop != null
                && !authNoQop.contains("qop=") && !authNoQop.contains("nc="));

        // AuthState: nc counts per nonce, resets on change.
        CamHttp.AuthState as = new CamHttp.AuthState();
        checkEq("digest authstate nc 1", 1L, as.nextNc("n1"));
        checkEq("digest authstate nc 2", 2L, as.nextNc("n1"));
        checkEq("digest authstate resets", 1L, as.nextNc("n2"));

        // 401 handling: digest preferred, basic fallback, else null.
        CamHttp.Url u = CamHttp.parseUrl("http://192.168.1.20/snapshot.jpg");
        Map<String, List<String>> both = new HashMap<>();
        both.put("www-authenticate", Arrays.asList(
                "Basic realm=\"x\"",
                "Digest realm=\"r\", nonce=\"n\", qop=\"auth\", algorithm=MD5"));
        String h1 = CamHttp.authHeaderFor(new CamHttp.Response(401, both, new byte[0]),
                "admin", "pw", u, new CamHttp.AuthState());
        check("digest 401 prefers digest", h1 != null && h1.startsWith("Digest "));
        Map<String, List<String>> basicOnly = new HashMap<>();
        basicOnly.put("www-authenticate", Arrays.asList("Basic realm=\"x\""));
        String h2 = CamHttp.authHeaderFor(new CamHttp.Response(401, basicOnly, new byte[0]),
                "admin", "pw", u, new CamHttp.AuthState());
        checkEq("digest 401 basic fallback",
                "Basic " + java.util.Base64.getEncoder().encodeToString(
                        "admin:pw".getBytes(StandardCharsets.ISO_8859_1)), h2);
        Map<String, List<String>> shaOnly = new HashMap<>();
        shaOnly.put("www-authenticate", Arrays.asList(
                "Digest realm=\"r\", nonce=\"n\", qop=\"auth\", algorithm=SHA-256"));
        check("digest 401 sha-only null",
                CamHttp.authHeaderFor(new CamHttp.Response(401, shaOnly, new byte[0]),
                        "admin", "pw", u, new CamHttp.AuthState()) == null);
        Map<String, List<String>> none = new HashMap<>();
        check("digest 401 no challenge null",
                CamHttp.authHeaderFor(new CamHttp.Response(401, none, new byte[0]),
                        "admin", "pw", u, new CamHttp.AuthState()) == null);
    }

    // ---- 2. HTTP_PARSE ----

    private static CamHttp.Response parse(String text, int cap) throws Exception {
        InputStream in = new ByteArrayInputStream(text.getBytes(StandardCharsets.ISO_8859_1));
        return CamHttp.readResponse(in, cap);
    }

    private static int parseKind(String text, int cap) {
        try {
            parse(text, cap);
            return -1;
        } catch (CamHttp.FetchException e) {
            return e.kind;
        } catch (Exception e) {
            return -99;
        }
    }

    private static void httpParse() throws Exception {
        CamHttp.Response r = parse(
                "HTTP/1.1 200 OK\r\nContent-Type: image/jpeg\r\nContent-Length: 4\r\n\r\nABCD",
                1024);
        checkEq("http status 200", 200, r.status);
        checkEq("http body", "ABCD", new String(r.body, StandardCharsets.ISO_8859_1));
        checkEq("http header case-insensitive", "image/jpeg", r.header("content-type"));
        checkEq("http header mixed case", "image/jpeg", r.header("Content-TYPE"));
        check("http header missing null", r.header("X-Nope") == null);

        // 401 keeps its challenge for the auth retry.
        CamHttp.Response r401 = parse(
                "HTTP/1.1 401 Unauthorized\r\nWWW-Authenticate: Digest realm=\"r\", nonce=\"n\"\r\nContent-Length: 0\r\n\r\n",
                1024);
        checkEq("http 401 status", 401, r401.status);
        checkEq("http 401 challenge", "Digest realm=\"r\", nonce=\"n\"",
                r401.header("www-authenticate"));
        checkEq("http 401 empty body", 0, r401.body.length);

        // Chunked: simple, extensions, trailers, empty.
        CamHttp.Response chunked = parse(
                "HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\n"
                        + "5\r\nHello\r\n6;ext=1\r\n World\r\n0\r\nX-Trailer: t\r\n\r\n",
                1024);
        checkEq("http chunked body", "Hello World",
                new String(chunked.body, StandardCharsets.ISO_8859_1));
        CamHttp.Response chunkedEmpty = parse(
                "HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\n0\r\n\r\n",
                1024);
        checkEq("http chunked empty", 0, chunkedEmpty.body.length);
        checkEq("http chunked bad size",
                CamHttp.FetchException.BAD_RESPONSE,
                parseKind("HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\nZZ\r\nx\r\n0\r\n\r\n", 1024));

        // Close-delimited: everything to EOF.
        CamHttp.Response eof = parse(
                "HTTP/1.0 200 OK\r\nContent-Type: image/jpeg\r\n\r\nJPEGDATA", 1024);
        checkEq("http close-delimited", "JPEGDATA",
                new String(eof.body, StandardCharsets.ISO_8859_1));
        checkEq("http http10 status", 200, eof.status);

        // 204/304 have no body by definition.
        CamHttp.Response noContent = parse("HTTP/1.1 204 No Content\r\n\r\n", 1024);
        checkEq("http 204 empty", 0, noContent.body.length);
        CamHttp.Response notMod = parse("HTTP/1.1 304 Not Modified\r\n\r\n", 1024);
        checkEq("http 304 empty", 0, notMod.body.length);

        // Duplicate headers accumulate.
        CamHttp.Response dup = parse(
                "HTTP/1.1 200 OK\r\nSet-Cookie: a=1\r\nSet-Cookie: b=2\r\nContent-Length: 0\r\n\r\n",
                1024);
        checkEq("http duplicate headers", 2, dup.headers.get("set-cookie").size());
        checkEq("http first header wins", "a=1", dup.header("set-cookie"));

        // A header line without a colon is skipped, not fatal.
        CamHttp.Response noColon = parse(
                "HTTP/1.1 200 OK\r\nGarbageLine\r\nContent-Length: 1\r\n\r\nZ", 1024);
        checkEq("http colonless skipped", "Z",
                new String(noColon.body, StandardCharsets.ISO_8859_1));

        // LF-only line endings work (some cameras are sloppy).
        CamHttp.Response lf = parse(
                "HTTP/1.1 200 OK\nContent-Length: 2\n\nHI", 1024);
        checkEq("http lf endings", "HI", new String(lf.body, StandardCharsets.ISO_8859_1));

        // Caps and malformed input.
        checkEq("http content-length over cap",
                CamHttp.FetchException.TOO_LARGE,
                parseKind("HTTP/1.1 200 OK\r\nContent-Length: 99999\r\n\r\n", 100));
        checkEq("http eof over cap",
                CamHttp.FetchException.TOO_LARGE,
                parseKind("HTTP/1.1 200 OK\r\n\r\n0123456789ABCDEF", 10));
        checkEq("http bad content-length",
                CamHttp.FetchException.BAD_RESPONSE,
                parseKind("HTTP/1.1 200 OK\r\nContent-Length: lots\r\n\r\n", 1024));
        checkEq("http empty input",
                CamHttp.FetchException.BAD_RESPONSE, parseKind("", 1024));
        checkEq("http garbage status",
                CamHttp.FetchException.BAD_RESPONSE,
                parseKind("HELLO\r\n\r\n", 1024));
        checkEq("http short status",
                CamHttp.FetchException.BAD_RESPONSE,
                parseKind("HTTP/1.1 99\r\n\r\n", 1024));
        checkEq("http early close",
                CamHttp.FetchException.BAD_RESPONSE,
                parseKind("HTTP/1.1 200 OK\r\nContent-Length: 10\r\n\r\nAB", 1024));
        checkEq("http headers end early",
                CamHttp.FetchException.BAD_RESPONSE,
                parseKind("HTTP/1.1 200 OK\r\nContent-Length: 1\r\n", 1024));

        // Status parsing.
        checkEq("http parseStatus 200", 200, CamHttp.parseStatus("HTTP/1.1 200 OK"));
        checkEq("http parseStatus 404", 404, CamHttp.parseStatus("HTTP/1.0 404 Not Found"));
        checkEq("http parseStatus 100", 100, CamHttp.parseStatus("HTTP/1.1 100 Continue"));
        checkEq("http parseStatus 599", 599, CamHttp.parseStatus("HTTP/1.1 599 X"));
        checkEq("http parseStatus 600 rejected", -1, CamHttp.parseStatus("HTTP/1.1 600 X"));
        checkEq("http parseStatus no space", -1, CamHttp.parseStatus("HTTP/1.1"));
        checkEq("http parseStatus garbage", -1, CamHttp.parseStatus("garbage"));
        checkEq("http parseStatus null", -1, CamHttp.parseStatus(null));

        // URL parsing.
        CamHttp.Url u1 = CamHttp.parseUrl("http://192.168.1.20/snapshot.jpg");
        check("http url host", u1 != null && "192.168.1.20".equals(u1.host));
        check("http url default port", u1 != null && u1.port == 80);
        check("http url target", u1 != null && "/snapshot.jpg".equals(u1.target));
        CamHttp.Url u2 = CamHttp.parseUrl("http://cam.local:8080/cgi-bin/api.cgi?cmd=Snap&channel=0");
        check("http url port", u2 != null && u2.port == 8080);
        check("http url query kept", u2 != null
                && "/cgi-bin/api.cgi?cmd=Snap&channel=0".equals(u2.target));
        CamHttp.Url u3 = CamHttp.parseUrl("http://192.168.1.20");
        check("http url bare target", u3 != null && "/".equals(u3.target));
        CamHttp.Url u4 = CamHttp.parseUrl("http://[fe80::1]:8080/snap.jpg");
        check("http url ipv6", u4 != null && "fe80::1".equals(u4.host) && u4.port == 8080);
        check("http url https refused", CamHttp.parseUrl("https://192.168.1.20/x") == null);
        check("http url uppercase scheme refused",
                CamHttp.parseUrl("HTTP://192.168.1.20/x") == null);
        check("http url userinfo refused",
                CamHttp.parseUrl("http://admin:pw@192.168.1.20/x") == null);
        check("http url bare userinfo refused",
                CamHttp.parseUrl("http://admin@192.168.1.20/x") == null);
        check("http url no host", CamHttp.parseUrl("http:///x") == null);
        check("http url empty", CamHttp.parseUrl("http://") == null);
        check("http url null", CamHttp.parseUrl(null) == null);
        check("http url bad port", CamHttp.parseUrl("http://h:abc/x") == null);
        check("http url port zero", CamHttp.parseUrl("http://h:0/x") == null);
        check("http url port huge", CamHttp.parseUrl("http://h:70000/x") == null);
        check("http url space refused",
                CamHttp.parseUrl("http://192.168.1.20/snap shot.jpg") == null);
        check("http url unclosed bracket",
                CamHttp.parseUrl("http://[fe80::1/snap.jpg") == null);
        check("http url rtsp refused", CamHttp.parseUrl("rtsp://192.168.1.20/stream") == null);
    }

    // ---- 3. MJPEG ----

    private static byte[] jpeg(byte... payload) {
        byte[] out = new byte[payload.length + 4];
        out[0] = (byte) 0xFF;
        out[1] = (byte) 0xD8;
        System.arraycopy(payload, 0, out, 2, payload.length);
        out[out.length - 2] = (byte) 0xFF;
        out[out.length - 1] = (byte) 0xD9;
        return out;
    }

    private static byte[] concat(byte[]... parts) {
        int len = 0;
        for (byte[] p : parts) len += p.length;
        byte[] out = new byte[len];
        int at = 0;
        for (byte[] p : parts) {
            System.arraycopy(p, 0, out, at, p.length);
            at += p.length;
        }
        return out;
    }

    private static byte[] ascii(String s) {
        return s.getBytes(StandardCharsets.ISO_8859_1);
    }

    /** A two-frame stream, no content lengths (delimiter
     *  scanning), with a closing delimiter. */
    private static byte[] twoFrameStream(byte[] f1, byte[] f2) {
        return concat(
                ascii("--myb\r\nContent-Type: image/jpeg\r\n\r\n"), f1,
                ascii("\r\n--myb\r\nContent-Type: image/jpeg\r\n\r\n"), f2,
                ascii("\r\n--myb--\r\n"));
    }

    private static void mjpeg() {
        // Boundary extraction.
        checkEq("mjpeg boundary plain", "myb",
                MjpegParser.boundaryFromContentType(
                        "multipart/x-mixed-replace; boundary=myb"));
        checkEq("mjpeg boundary quoted", "myb",
                MjpegParser.boundaryFromContentType(
                        "multipart/x-mixed-replace; boundary=\"myb\""));
        checkEq("mjpeg boundary dashes verbatim", "--myb",
                MjpegParser.boundaryFromContentType(
                        "multipart/x-mixed-replace; boundary=--myb"));
        checkEq("mjpeg boundary extra params", "frame",
                MjpegParser.boundaryFromContentType(
                        "multipart/x-mixed-replace; boundary=frame; charset=utf-8"));
        checkEq("mjpeg boundary space", "myb",
                MjpegParser.boundaryFromContentType(
                        "multipart/x-mixed-replace; boundary= myb"));
        check("mjpeg boundary missing null",
                MjpegParser.boundaryFromContentType(
                        "multipart/x-mixed-replace") == null);
        check("mjpeg boundary null null",
                MjpegParser.boundaryFromContentType(null) == null);
        check("mjpeg boundary empty null",
                MjpegParser.boundaryFromContentType(
                        "multipart/x-mixed-replace; boundary=") == null);
        check("mjpeg boundary unterminated quote null",
                MjpegParser.boundaryFromContentType(
                        "multipart/x-mixed-replace; boundary=\"myb") == null);

        // Constructor rejects a missing boundary.
        boolean threw = false;
        try {
            new MjpegParser(null);
        } catch (IllegalArgumentException e) {
            threw = true;
        }
        check("mjpeg null boundary rejected", threw);
        threw = false;
        try {
            new MjpegParser("");
        } catch (IllegalArgumentException e) {
            threw = true;
        }
        check("mjpeg empty boundary rejected", threw);

        byte[] f1 = jpeg((byte) 1, (byte) 2, (byte) 3);
        byte[] f2 = jpeg((byte) 4, (byte) 5);
        byte[] stream = twoFrameStream(f1, f2);

        // Whole stream in one feed.
        MjpegParser p = new MjpegParser("myb");
        List<byte[]> frames = p.feed(stream, 0, stream.length);
        checkEq("mjpeg two frames one feed", 2, frames.size());
        check("mjpeg frame 1 exact", frames.size() == 2 && Arrays.equals(f1, frames.get(0)));
        check("mjpeg frame 2 exact", frames.size() == 2 && Arrays.equals(f2, frames.get(1)));
        check("mjpeg ended after close", p.isEnded());
        checkEq("mjpeg feed after end empty", 0,
                p.feed(stream, 0, stream.length).size());

        // Split at EVERY position: both frames must emerge.
        int splitFailures = 0;
        for (int split = 0; split <= stream.length; split++) {
            MjpegParser sp = new MjpegParser("myb");
            List<byte[]> got = new ArrayList<>();
            got.addAll(sp.feed(stream, 0, split));
            got.addAll(sp.feed(stream, split, stream.length - split));
            if (got.size() != 2 || !Arrays.equals(f1, got.get(0))
                    || !Arrays.equals(f2, got.get(1))) {
                splitFailures++;
            }
        }
        check("mjpeg every split point yields both frames (" + stream.length
                + " splits)", splitFailures == 0);

        // Byte-at-a-time.
        MjpegParser drip = new MjpegParser("myb");
        List<byte[]> dripped = new ArrayList<>();
        for (int i = 0; i < stream.length; i++) {
            dripped.addAll(drip.feed(stream, i, 1));
        }
        checkEq("mjpeg byte-at-a-time", 2, dripped.size());

        // Preamble junk before the first delimiter is dropped.
        byte[] junked = concat(ascii("HTTP/1.1 200 OK\r\nGarbage preamble\r\n"), stream);
        MjpegParser junk2 = new MjpegParser("myb");
        List<byte[]> junkFrames = junk2.feed(junked, 0, junked.length);
        checkEq("mjpeg preamble junk skipped", 2, junkFrames.size());

        // Content-length parts: exact frames, no delimiter scan.
        byte[] clStream = concat(
                ascii("--myb\r\nContent-Type: image/jpeg\r\nContent-Length: "
                        + f1.length + "\r\n\r\n"), f1,
                ascii("\r\n--myb\r\nContent-Type: image/jpeg\r\nContent-Length: "
                        + f2.length + "\r\n\r\n"), f2,
                ascii("\r\n--myb--\r\n"));
        MjpegParser cl = new MjpegParser("myb");
        List<byte[]> clFrames = cl.feed(clStream, 0, clStream.length);
        checkEq("mjpeg content-length frames", 2, clFrames.size());
        check("mjpeg content-length frame exact",
                clFrames.size() == 2 && Arrays.equals(f1, clFrames.get(0)));

        // A part with an insane content-length falls back to
        // delimiter scanning.
        byte[] insane = concat(
                ascii("--myb\r\nContent-Type: image/jpeg\r\nContent-Length: 99999999\r\n\r\n"),
                f1, ascii("\r\n--myb--\r\n"));
        MjpegParser ins = new MjpegParser("myb");
        List<byte[]> insFrames = ins.feed(insane, 0, insane.length);
        checkEq("mjpeg insane length falls back", 1, insFrames.size());
        check("mjpeg insane length frame exact",
                insFrames.size() == 1 && Arrays.equals(f1, insFrames.get(0)));

        // Non-JPEG parts are skipped, not emitted.
        byte[] mixed = concat(
                ascii("--myb\r\nContent-Type: text/plain\r\n\r\n"), ascii("HELLO"),
                ascii("\r\n--myb\r\nContent-Type: image/jpeg\r\n\r\n"), f1,
                ascii("\r\n--myb--\r\n"));
        MjpegParser mix = new MjpegParser("myb");
        List<byte[]> mixFrames = mix.feed(mixed, 0, mixed.length);
        checkEq("mjpeg non-jpeg skipped", 1, mixFrames.size());

        // Oversized part dropped, parser resyncs on the next
        // delimiter and the good frame still emerges.
        byte[] huge = new byte[MjpegParser.MAX_FRAME_BYTES + 100];
        Arrays.fill(huge, (byte) 'A');
        byte[] over = concat(
                ascii("--myb\r\nContent-Type: image/jpeg\r\n\r\n"), huge,
                ascii("\r\n--myb\r\nContent-Type: image/jpeg\r\n\r\n"), f1,
                ascii("\r\n--myb--\r\n"));
        MjpegParser ov = new MjpegParser("myb");
        List<byte[]> ovFrames = ov.feed(over, 0, over.length);
        checkEq("mjpeg oversized dropped, resync", 1, ovFrames.size());
        check("mjpeg resync frame exact",
                ovFrames.size() == 1 && Arrays.equals(f1, ovFrames.get(0)));

        // Header runaway: the part is dropped, the next
        // complete part still parses.
        StringBuilder longHeader = new StringBuilder("--myb\r\n");
        for (int i = 0; i < 9000; i++) longHeader.append('X');
        longHeader.append("\r\n\r\n");
        byte[] runaway = concat(ascii(longHeader.toString()),
                ascii("--myb\r\nContent-Type: image/jpeg\r\n\r\n"), f1,
                ascii("\r\n--myb--\r\n"));
        MjpegParser rw = new MjpegParser("myb");
        List<byte[]> rwFrames = rw.feed(runaway, 0, runaway.length);
        checkEq("mjpeg header runaway recovers", 1, rwFrames.size());

        // A dashed boundary parameter: the wire delimiter is
        // "--" + the parameter as sent.
        byte[] dashed = concat(
                ascii("----myb\r\nContent-Type: image/jpeg\r\n\r\n"), f1,
                ascii("\r\n----myb--\r\n"));
        MjpegParser dash = new MjpegParser("--myb");
        List<byte[]> dashFrames = dash.feed(dashed, 0, dashed.length);
        checkEq("mjpeg dashed boundary", 1, dashFrames.size());
        check("mjpeg dashed ended", dash.isEnded());

        // Empty feed: nothing, no crash.
        checkEq("mjpeg empty feed", 0,
                new MjpegParser("myb").feed(new byte[0], 0, 0).size());
    }

    // ---- 4. DISCOVERY ----

    private static void discovery() {
        // Merge: dedupe by IP, union of ports/sources.
        Map<String, CamDiscovery.Found> map = CamDiscovery.newMap();
        CamDiscovery.merge(map, "192.168.1.20", CamDiscovery.SRC_MDNS,
                "Cam One", 2, "Reolink", Arrays.asList(80), null,
                Arrays.asList("_http._tcp"));
        CamDiscovery.merge(map, "192.168.1.20", CamDiscovery.SRC_ONVIF,
                "Front Door", 3, "Reolink", Arrays.asList(8000),
                Arrays.asList("http://192.168.1.20/onvif/device_service"), null);
        CamDiscovery.merge(map, "192.168.1.21", CamDiscovery.SRC_SWEEP,
                "", 1, "", Arrays.asList(80), null, null);
        checkEq("discovery dedupe by ip", 2, map.size());
        CamDiscovery.Found f = map.get("192.168.1.20");
        check("discovery ports union", f.ports.contains(80) && f.ports.contains(8000));
        check("discovery sources union",
                (f.sources & CamDiscovery.SRC_MDNS) != 0
                        && (f.sources & CamDiscovery.SRC_ONVIF) != 0);
        checkEq("discovery onvif name wins", "Front Door", f.name);
        checkEq("discovery xaddrs kept", 1, f.xaddrs.size());
        checkEq("discovery mdns services kept", 1, f.mdnsServices.size());
        check("discovery confirmed", f.confirmed());
        check("discovery sweep-only unconfirmed", !map.get("192.168.1.21").confirmed());
        checkEq("discovery displayName falls back to ip",
                "192.168.1.21", map.get("192.168.1.21").displayName());

        // Name rank: ONVIF beats mDNS regardless of order.
        Map<String, CamDiscovery.Found> m2 = CamDiscovery.newMap();
        CamDiscovery.merge(m2, "10.0.0.5", CamDiscovery.SRC_ONVIF,
                "ONVIF Name", 3, "", null, null, null);
        CamDiscovery.merge(m2, "10.0.0.5", CamDiscovery.SRC_MDNS,
                "mDNS Name", 2, "", null, null, null);
        checkEq("discovery onvif name survives mdns", "ONVIF Name",
                m2.get("10.0.0.5").name);
        // mDNS beats a hostname.
        Map<String, CamDiscovery.Found> m3 = CamDiscovery.newMap();
        CamDiscovery.merge(m3, "10.0.0.6", CamDiscovery.SRC_SWEEP,
                "host-6", 1, "", null, null, null);
        CamDiscovery.merge(m3, "10.0.0.6", CamDiscovery.SRC_MDNS,
                "mDNS Name", 2, "", null, null, null);
        checkEq("discovery mdns beats hostname", "mDNS Name", m3.get("10.0.0.6").name);
        Map<String, CamDiscovery.Found> m4 = CamDiscovery.newMap();
        CamDiscovery.merge(m4, "10.0.0.7", CamDiscovery.SRC_MDNS,
                "mDNS Name", 2, "", null, null, null);
        CamDiscovery.merge(m4, "10.0.0.7", CamDiscovery.SRC_SWEEP,
                "host-7", 1, "", null, null, null);
        checkEq("discovery hostname does not overwrite mdns", "mDNS Name",
                m4.get("10.0.0.7").name);
        // Equal rank: last wins. Empty names never overwrite.
        Map<String, CamDiscovery.Found> m5 = CamDiscovery.newMap();
        CamDiscovery.merge(m5, "10.0.0.8", CamDiscovery.SRC_MDNS,
                "First", 2, "", null, null, null);
        CamDiscovery.merge(m5, "10.0.0.8", CamDiscovery.SRC_MDNS,
                "Second", 2, "", null, null, null);
        checkEq("discovery equal rank last wins", "Second", m5.get("10.0.0.8").name);
        CamDiscovery.merge(m5, "10.0.0.8", CamDiscovery.SRC_MDNS,
                "", 2, "", null, null, null);
        checkEq("discovery empty name keeps", "Second", m5.get("10.0.0.8").name);
        // Vendor: first wins.
        Map<String, CamDiscovery.Found> m6 = CamDiscovery.newMap();
        CamDiscovery.merge(m6, "10.0.0.9", CamDiscovery.SRC_ONVIF,
                "", 3, "Hikvision", null, null, null);
        CamDiscovery.merge(m6, "10.0.0.9", CamDiscovery.SRC_MDNS,
                "", 2, "Dahua", null, null, null);
        checkEq("discovery vendor first wins", "Hikvision", m6.get("10.0.0.9").vendor);
        // Ordered: first-seen order.
        List<CamDiscovery.Found> ordered = CamDiscovery.ordered(map);
        checkEq("discovery ordered first seen", "192.168.1.20", ordered.get(0).ip);
        checkEq("discovery ordered second", "192.168.1.21", ordered.get(1).ip);

        // Vendor table.
        checkEq("discovery vendor hikvision", "Hikvision",
                CamDiscovery.guessVendor("HIKVISION DS-2CD"));
        checkEq("discovery vendor hik dash", "Hikvision",
                CamDiscovery.guessVendor("hik-1234"));
        checkEq("discovery vendor dahua", "Dahua", CamDiscovery.guessVendor("Dahua IPC"));
        checkEq("discovery vendor reolink", "Reolink",
                CamDiscovery.guessVendor("Reolink RLC-810A"));
        checkEq("discovery vendor amcrest", "Amcrest",
                CamDiscovery.guessVendor("Amcrest IP4M"));
        checkEq("discovery vendor axis", "Axis",
                CamDiscovery.guessVendor("AXIS M3045"));
        checkEq("discovery vendor unifi", "UniFi",
                CamDiscovery.guessVendor("Ubiquiti UVC-G4"));
        checkEq("discovery vendor foscam", "Foscam", CamDiscovery.guessVendor("Foscam C2"));
        checkEq("discovery vendor tplink", "TP-Link",
                CamDiscovery.guessVendor("TP-Link VIGI C420"));
        checkEq("discovery vendor hanwha", "Hanwha",
                CamDiscovery.guessVendor("Wisenet XNO"));
        checkEq("discovery vendor lorex", "Lorex", CamDiscovery.guessVendor("Lorex E891AB"));
        checkEq("discovery vendor swann", "Swann", CamDiscovery.guessVendor("Swann 4K"));
        checkEq("discovery vendor zosi", "Zosi", CamDiscovery.guessVendor("ZOSI C306"));
        checkEq("discovery vendor annke", "Annke", CamDiscovery.guessVendor("ANNKE C800"));
        checkEq("discovery vendor wyze", "Wyze", CamDiscovery.guessVendor("Wyze Cam v3"));
        checkEq("discovery vendor ring", "Ring", CamDiscovery.guessVendor("Ring Stick Up"));
        checkEq("discovery vendor nest", "Nest", CamDiscovery.guessVendor("Nest Cam"));
        checkEq("discovery vendor eufy", "Eufy", CamDiscovery.guessVendor("eufy SoloCam"));
        checkEq("discovery vendor blink", "Blink", CamDiscovery.guessVendor("Blink Mini"));
        checkEq("discovery vendor none", "", CamDiscovery.guessVendor("HP LaserJet"));
        checkEq("discovery vendor null hints", "", CamDiscovery.guessVendor((String[]) null));
        checkEq("discovery vendor null entries", "Dahua",
                CamDiscovery.guessVendor(null, "dahua", null));
        checkEq("discovery vendor multi-hint", "Reolink",
                CamDiscovery.guessVendor("unknown", "reolink"));

        // Sweep fingerprints.
        check("discovery sweep rtsp", CamDiscovery.sweepLooksLikeCamera(true, "", ""));
        check("discovery sweep hikvision server",
                CamDiscovery.sweepLooksLikeCamera(false, "Hikvision-Webs", ""));
        check("discovery sweep dahua body",
                CamDiscovery.sweepLooksLikeCamera(false, "Apache", "dahua login"));
        check("discovery sweep ipcam",
                CamDiscovery.sweepLooksLikeCamera(false, "GoAhead", "IPCam page"));
        check("discovery sweep network camera",
                CamDiscovery.sweepLooksLikeCamera(false, "", "Network Camera"));
        check("discovery sweep webcam",
                CamDiscovery.sweepLooksLikeCamera(false, "", "webcam feed"));
        check("discovery sweep goahead + camera hint",
                CamDiscovery.sweepLooksLikeCamera(false, "GoAhead-Webs", "camera login"));
        check("discovery sweep goahead + snapshot hint",
                CamDiscovery.sweepLooksLikeCamera(false, "GoAhead", "snapshot.jpg"));
        check("discovery sweep boa + live hint",
                CamDiscovery.sweepLooksLikeCamera(false, "Boa/0.94", "live view"));
        check("discovery sweep goahead alone rejected",
                !CamDiscovery.sweepLooksLikeCamera(false, "GoAhead-Webs", "router admin"));
        check("discovery sweep apache rejected",
                !CamDiscovery.sweepLooksLikeCamera(false, "Apache/2.4", "It works!"));
        check("discovery sweep nginx rejected",
                !CamDiscovery.sweepLooksLikeCamera(false, "nginx/1.18", "Welcome"));
        check("discovery sweep lighttpd rejected",
                !CamDiscovery.sweepLooksLikeCamera(false, "lighttpd/1.4", "index"));
        check("discovery sweep empty rejected",
                !CamDiscovery.sweepLooksLikeCamera(false, "", ""));
        check("discovery sweep nulls rejected",
                !CamDiscovery.sweepLooksLikeCamera(false, null, null));

        // mDNS rules.
        check("discovery mdns rtsp marks",
                CamDiscovery.mdnsMarksCamera("_rtsp._tcp"));
        check("discovery mdns onvif marks",
                CamDiscovery.mdnsMarksCamera("_onvif._tcp"));
        check("discovery mdns axis marks",
                CamDiscovery.mdnsMarksCamera("_axis-video._tcp"));
        check("discovery mdns camera marks",
                CamDiscovery.mdnsMarksCamera("_camera._tcp"));
        check("discovery mdns http alone does not mark",
                !CamDiscovery.mdnsMarksCamera("_http._tcp"));
        check("discovery mdns printer does not mark",
                !CamDiscovery.mdnsMarksCamera("_printer._tcp"));
        check("discovery mdns null does not mark",
                !CamDiscovery.mdnsMarksCamera(null));
        check("discovery mdns instance cam",
                CamDiscovery.mdnsInstanceLooksCamera("Front Door Cam"));
        check("discovery mdns instance vendor",
                CamDiscovery.mdnsInstanceLooksCamera("Reolink-ABC123"));
        check("discovery mdns instance ipcam",
                CamDiscovery.mdnsInstanceLooksCamera("IPCAM-01"));
        check("discovery mdns instance printer",
                !CamDiscovery.mdnsInstanceLooksCamera("HP LaserJet"));
        check("discovery mdns instance null",
                !CamDiscovery.mdnsInstanceLooksCamera(null));
        checkEq("discovery mdns services count", 5, CamDiscovery.MDNS_SERVICES.length);

        // /24 enumeration.
        List<String> hosts = CamDiscovery.hosts24("192.168.1.20");
        checkEq("discovery hosts24 count", 253, hosts.size());
        checkEq("discovery hosts24 first", "192.168.1.1", hosts.get(0));
        checkEq("discovery hosts24 last", "192.168.1.254", hosts.get(hosts.size() - 1));
        check("discovery hosts24 skips self", !hosts.contains("192.168.1.20"));
        check("discovery hosts24 skips network", !hosts.contains("192.168.1.0"));
        check("discovery hosts24 skips broadcast", !hosts.contains("192.168.1.255"));
        List<String> hosts10 = CamDiscovery.hosts24("10.0.0.1");
        checkEq("discovery hosts24 other subnet", "10.0.0.2", hosts10.get(0));
        checkEq("discovery hosts24 bad input empty", 0,
                CamDiscovery.hosts24("not-an-ip").size());
        checkEq("discovery hosts24 short empty", 0, CamDiscovery.hosts24("1.2.3").size());
        checkEq("discovery hosts24 bad octet empty", 0,
                CamDiscovery.hosts24("1.2.3.256").size());
        checkEq("discovery hosts24 ipv6 empty", 0,
                CamDiscovery.hosts24("fe80::1").size());
        checkEq("discovery hosts24 null empty", 0, CamDiscovery.hosts24(null).size());
    }

    // ---- 5. ONVIF ----

    private static void onvif() {
        String probe = CamOnvif.probeEnvelope();
        check("onvif probe dn namespace",
                probe.contains("xmlns:dn=\"http://www.onvif.org/ver10/network/wsdl\""));
        check("onvif probe type", probe.contains("dn:NetworkVideoTransmitter"));
        check("onvif probe action",
                probe.contains("http://schemas.xmlsoap.org/ws/2005/04/discovery/Probe"));
        check("onvif probe message id uuid", probe.contains("<a:MessageID>uuid:"));
        check("onvif probe to", probe.contains(
                "<a:To>urn:schemas-xmlsoap-org:ws:2005:04:discovery</a:To>"));
        check("onvif probe ids differ",
                !probe.equals(CamOnvif.probeEnvelope()));
        checkEq("onvif multicast address", "239.255.255.250", CamOnvif.MULTICAST_ADDRESS);
        checkEq("onvif multicast port", 3702, CamOnvif.MULTICAST_PORT);

        String reply = "<?xml version=\"1.0\"?>"
                + "<s:Envelope xmlns:s=\"http://www.w3.org/2003/05/soap-envelope\" "
                + "xmlns:d=\"http://schemas.xmlsoap.org/ws/2005/04/discovery\" "
                + "xmlns:a=\"http://schemas.xmlsoap.org/ws/2004/08/addressing\">"
                + "<s:Body><d:ProbeMatches><d:ProbeMatch>"
                + "<a:EndpointReference><a:Address>urn:uuid:abc-123</a:Address>"
                + "</a:EndpointReference>"
                + "<d:Types>dn:NetworkVideoTransmitter</d:Types>"
                + "<d:Scopes>onvif://www.onvif.org/name/Front%20Door "
                + "onvif://www.onvif.org/hardware/DS-2CD2143G2 "
                + "onvif://www.onvif.org/type/video_encoder</d:Scopes>"
                + "<d:XAddrs>http://192.168.1.20:8000/onvif/device_service "
                + "http://192.168.1.20/onvif/device_service</d:XAddrs>"
                + "<d:MetadataVersion>1</d:MetadataVersion>"
                + "</d:ProbeMatch></d:ProbeMatches></s:Body></s:Envelope>";
        List<CamOnvif.Device> devices = CamOnvif.parseProbeMatches(reply);
        checkEq("onvif one match", 1, devices.size());
        CamOnvif.Device d = devices.get(0);
        checkEq("onvif xaddrs count", 2, d.xaddrs.size());
        checkEq("onvif xaddr 1", "http://192.168.1.20:8000/onvif/device_service",
                d.xaddrs.get(0));
        checkEq("onvif scopes count", 3, d.scopes.size());
        checkEq("onvif endpoint ref", "urn:uuid:abc-123", d.endpointRef);
        checkEq("onvif name from scopes", "Front Door", CamOnvif.nameFromScopes(d.scopes));
        checkEq("onvif hardware from scopes", "DS-2CD2143G2",
                CamOnvif.hardwareFromScopes(d.scopes));

        // Two matches in one datagram.
        String two = reply.replace("</d:ProbeMatch>",
                "</d:ProbeMatch><d:ProbeMatch>"
                        + "<d:Scopes>onvif://www.onvif.org/name/Back%20Yard</d:Scopes>"
                        + "<d:XAddrs>http://192.168.1.21/onvif/device_service</d:XAddrs>"
                        + "</d:ProbeMatch>");
        List<CamOnvif.Device> twoDevs = CamOnvif.parseProbeMatches(two);
        checkEq("onvif two matches", 2, twoDevs.size());
        checkEq("onvif second name", "Back Yard",
                CamOnvif.nameFromScopes(twoDevs.get(1).scopes));

        // Prefix tolerance: different namespace prefixes.
        String altPrefix = reply.replace("d:ProbeMatch", "wsdd:ProbeMatch")
                .replace("d:Scopes", "wsdd:Scopes").replace("d:XAddrs", "wsdd:XAddrs")
                .replace("a:Address", "wsa:Address");
        checkEq("onvif alt prefixes", 1, CamOnvif.parseProbeMatches(altPrefix).size());

        // Malformed input: empty list, never an exception.
        checkEq("onvif no matches", 0, CamOnvif.parseProbeMatches(
                "<s:Envelope><s:Body>hello</s:Body></s:Envelope>").size());
        checkEq("onvif null", 0, CamOnvif.parseProbeMatches(null).size());
        checkEq("onvif empty", 0, CamOnvif.parseProbeMatches("").size());
        checkEq("onvif unclosed", 0, CamOnvif.parseProbeMatches(
                "<d:ProbeMatch><d:XAddrs>http://x/").size());
        checkEq("onvif garbage", 0, CamOnvif.parseProbeMatches("not xml at all").size());

        // Scope decoding.
        checkEq("onvif name missing", "", CamOnvif.nameFromScopes(
                Arrays.asList("onvif://www.onvif.org/hardware/X")));
        checkEq("onvif name null scopes", "", CamOnvif.nameFromScopes(null));
        checkEq("onvif hardware missing", "", CamOnvif.hardwareFromScopes(
                Arrays.asList("onvif://www.onvif.org/name/Y")));
        checkEq("onvif hardware percent decoded", "Cam 2000",
                CamOnvif.hardwareFromScopes(
                        Arrays.asList("onvif://www.onvif.org/hardware/Cam%202000")));
        checkEq("onvif name bad percent kept", "A%ZZB",
                CamOnvif.nameFromScopes(
                        Arrays.asList("onvif://www.onvif.org/name/A%ZZB")));

        // Media SOAP builders.
        String snap = CamOnvif.getSnapshotUriEnvelope("Profile_1");
        check("onvif snapshot envelope op", snap.contains("trt:GetSnapshotUri"));
        check("onvif snapshot envelope token",
                snap.contains("<trt:ProfileToken>Profile_1</trt:ProfileToken>"));
        String escaped = CamOnvif.getSnapshotUriEnvelope("a&b<c>\"d'");
        check("onvif snapshot token escaped",
                escaped.contains("a&amp;b&lt;c&gt;&quot;d&apos;"));
        check("onvif snapshot null token empty",
                CamOnvif.getSnapshotUriEnvelope(null).contains("<trt:ProfileToken></trt:ProfileToken>"));
        String stream = CamOnvif.getStreamUriEnvelope("Profile_1");
        check("onvif stream envelope op", stream.contains("trt:GetStreamUri"));
        check("onvif stream envelope unicast", stream.contains("RTP-Unicast"));
        check("onvif stream envelope http", stream.contains("<tt:Protocol>HTTP</tt:Protocol>"));

        // Media URI extraction + faults.
        String mediaReply = "<s:Envelope><s:Body><trt:GetSnapshotUriResponse>"
                + "<trt:MediaUri><tt:Uri>http://192.168.1.20/snap.jpg</tt:Uri>"
                + "</trt:MediaUri></trt:GetSnapshotUriResponse></s:Body></s:Envelope>";
        checkEq("onvif mediaUri", "http://192.168.1.20/snap.jpg",
                CamOnvif.mediaUri(mediaReply));
        check("onvif media reply not fault", !CamOnvif.isFault(mediaReply));
        String fault = "<s:Envelope><s:Body><s:Fault><faultcode>s:Sender</faultcode>"
                + "<faultstring>Not authorized</faultstring></s:Fault></s:Body></s:Envelope>";
        check("onvif fault detected", CamOnvif.isFault(fault));
        checkEq("onvif fault yields no uri", "", CamOnvif.mediaUri(fault));
        checkEq("onvif mediaUri null", "", CamOnvif.mediaUri(null));
        checkEq("onvif mediaUri no uri", "", CamOnvif.mediaUri("<s:Envelope/>"));
        check("onvif isFault null false", !CamOnvif.isFault(null));
    }

    // ---- 6. FEED ----

    private static void feed() {
        // Candidate order: vendor first, then generic, deduped.
        List<String> hik = CamFeed.snapshotCandidates("Hikvision");
        checkEq("feed hikvision first", "/ISAPI/Streaming/channels/101/picture",
                hik.get(0));
        check("feed hikvision before generic",
                hik.indexOf("/ISAPI/Streaming/channels/101/picture")
                        < hik.indexOf("/snapshot.jpg"));
        checkEq("feed hikvision no dupes", new HashSet<>(hik).size(), hik.size());
        List<String> dahua = CamFeed.snapshotCandidates("Dahua");
        checkEq("feed dahua first", "/cgi-bin/snapshot.cgi", dahua.get(0));
        int dahuaCount = 0;
        for (String p : dahua) if (p.equals("/cgi-bin/snapshot.cgi")) dahuaCount++;
        checkEq("feed dahua generic overlap deduped", 1, dahuaCount);
        List<String> reolink = CamFeed.snapshotCandidates("Reolink");
        checkEq("feed reolink first", "/cgi-bin/api.cgi?cmd=Snap&channel=0&rs=1",
                reolink.get(0));
        checkEq("feed unknown vendor is generics",
                Arrays.asList(CamFeed.GENERIC_SNAPSHOT_PATHS),
                CamFeed.snapshotCandidates("Unknown"));
        checkEq("feed null vendor is generics",
                Arrays.asList(CamFeed.GENERIC_SNAPSHOT_PATHS),
                CamFeed.snapshotCandidates(null));

        // No candidate pattern anywhere carries credentials.
        String[] vendors = {"Hikvision", "Dahua", "Amcrest", "Reolink",
                "Axis", "Foscam", "UniFi", null, "Unknown"};
        boolean clean = true;
        for (String v : vendors) {
            for (String p : CamFeed.snapshotCandidates(v)) {
                if (p.contains("@") || p.contains("user=") || p.contains("usr=")
                        || p.toLowerCase().contains("password")) clean = false;
            }
            for (String p : CamFeed.mjpegCandidates(v)) {
                if (p.contains("@") || p.contains("user=") || p.contains("usr=")
                        || p.toLowerCase().contains("password")) clean = false;
            }
        }
        check("feed no credentials in any pattern", clean);

        List<String> axisMjpeg = CamFeed.mjpegCandidates("Axis");
        checkEq("feed axis mjpeg first", "/axis-cgi/mjpg/video.cgi", axisMjpeg.get(0));
        int axisCount = 0;
        for (String p : axisMjpeg) if (p.equals("/axis-cgi/mjpg/video.cgi")) axisCount++;
        checkEq("feed axis mjpeg deduped", 1, axisCount);
        checkEq("feed hikvision mjpeg first", "/ISAPI/Streaming/channels/102/httppreview",
                CamFeed.mjpegCandidates("Hikvision").get(0));
        checkEq("feed unknown mjpeg is generics",
                Arrays.asList(CamFeed.GENERIC_MJPEG_PATHS),
                CamFeed.mjpegCandidates("Unknown"));

        // URL building.
        checkEq("feed buildUrl basic", "http://192.168.1.20/snapshot.jpg",
                CamFeed.buildUrl("192.168.1.20", 80, "/snapshot.jpg"));
        checkEq("feed buildUrl port kept", "http://192.168.1.20:8080/snap.jpg",
                CamFeed.buildUrl("192.168.1.20", 8080, "/snap.jpg"));
        checkEq("feed buildUrl slash added", "http://h/snap.jpg",
                CamFeed.buildUrl("h", 80, "snap.jpg"));
        checkEq("feed buildUrl query kept", "http://h:8080/a.cgi?cmd=Snap",
                CamFeed.buildUrl("h", 8080, "/a.cgi?cmd=Snap"));
        checkEq("feed buildUrl full url passthrough",
                "http://10.0.0.5:8000/onvif/snap.jpg",
                CamFeed.buildUrl("ignored", 80, "http://10.0.0.5:8000/onvif/snap.jpg"));
        checkEq("feed buildUrl full url userinfo refused", "",
                CamFeed.buildUrl("h", 80, "http://admin:pw@10.0.0.5/snap.jpg"));
        checkEq("feed buildUrl https refused", "",
                CamFeed.buildUrl("h", 80, "https://h/snap.jpg"));
        checkEq("feed buildUrl empty host", "", CamFeed.buildUrl("", 80, "/x"));
        checkEq("feed buildUrl null host", "", CamFeed.buildUrl(null, 80, "/x"));
        checkEq("feed buildUrl empty path", "", CamFeed.buildUrl("h", 80, ""));
        checkEq("feed buildUrl null path", "", CamFeed.buildUrl("h", 80, null));

        // ONVIF URI gating.
        check("feed onvif uri http ok",
                CamFeed.usableOnvifUri("http://192.168.1.20/snap.jpg"));
        check("feed onvif uri https refused",
                !CamFeed.usableOnvifUri("https://192.168.1.20/snap.jpg"));
        check("feed onvif uri userinfo refused",
                !CamFeed.usableOnvifUri("http://admin@192.168.1.20/snap.jpg"));
        check("feed onvif uri empty authority refused",
                !CamFeed.usableOnvifUri("http:///snap.jpg"));
        check("feed onvif uri null refused", !CamFeed.usableOnvifUri(null));
        check("feed onvif uri rtsp refused",
                !CamFeed.usableOnvifUri("rtsp://192.168.1.20/stream"));
        check("feed mjpeg uri http ok",
                CamFeed.usableMjpegUri("http://192.168.1.20/mjpg"));
        check("feed mjpeg uri rtsp refused",
                !CamFeed.usableMjpegUri("rtsp://192.168.1.20/stream"));

        // JPEG validation.
        check("feed jpeg valid",
                CamFeed.jpegLooksValid(new byte[]{(byte) 0xFF, (byte) 0xD8,
                        0x01, (byte) 0xFF, (byte) 0xD9}));
        check("feed jpeg trailing pad ok",
                CamFeed.jpegLooksValid(new byte[]{(byte) 0xFF, (byte) 0xD8,
                        0x01, (byte) 0xFF, (byte) 0xD9, 0x00, 0x00}));
        check("feed jpeg no eoi",
                !CamFeed.jpegLooksValid(new byte[]{(byte) 0xFF, (byte) 0xD8, 0x01, 0x02}));
        check("feed jpeg no soi",
                !CamFeed.jpegLooksValid(new byte[]{0x01, 0x02, (byte) 0xFF, (byte) 0xD9}));
        check("feed jpeg too short",
                !CamFeed.jpegLooksValid(new byte[]{(byte) 0xFF, (byte) 0xD8}));
        check("feed jpeg null", !CamFeed.jpegLooksValid(null));
        check("feed jpeg empty", !CamFeed.jpegLooksValid(new byte[0]));

        // Content types.
        check("feed image jpeg", CamFeed.isImageContentType("image/jpeg"));
        check("feed image jpeg charset",
                CamFeed.isImageContentType("image/jpeg; charset=binary"));
        check("feed image jpg", CamFeed.isImageContentType("image/jpg"));
        check("feed image png", CamFeed.isImageContentType("image/png"));
        check("feed image case", CamFeed.isImageContentType("IMAGE/JPEG"));
        check("feed image html refused", !CamFeed.isImageContentType("text/html"));
        check("feed image gif refused", !CamFeed.isImageContentType("image/gif"));
        check("feed image null refused", !CamFeed.isImageContentType(null));
        check("feed mjpeg type",
                CamFeed.isMjpegContentType("multipart/x-mixed-replace; boundary=myb"));
        check("feed mjpeg type case",
                CamFeed.isMjpegContentType("Multipart/X-Mixed-Replace"));
        check("feed mjpeg type jpeg refused",
                !CamFeed.isMjpegContentType("image/jpeg"));
        check("feed mjpeg type null refused", !CamFeed.isMjpegContentType(null));

        // Refresh intervals.
        checkEq("feed clamp 5", 5, CamFeed.clampRefreshSeconds(5));
        checkEq("feed clamp 10", 10, CamFeed.clampRefreshSeconds(10));
        checkEq("feed clamp 30", 30, CamFeed.clampRefreshSeconds(30));
        checkEq("feed clamp 60", 60, CamFeed.clampRefreshSeconds(60));
        checkEq("feed clamp 0", 0, CamFeed.clampRefreshSeconds(0));
        checkEq("feed clamp 7 default", 10, CamFeed.clampRefreshSeconds(7));
        checkEq("feed clamp negative default", 10, CamFeed.clampRefreshSeconds(-1));
        checkEq("feed label 5", "Every 5 seconds", CamFeed.refreshLabel(5));
        checkEq("feed label 10", "Every 10 seconds", CamFeed.refreshLabel(10));
        checkEq("feed label 30", "Every 30 seconds", CamFeed.refreshLabel(30));
        checkEq("feed label 60", "Every minute", CamFeed.refreshLabel(60));
        checkEq("feed label 0", "Manual refresh only", CamFeed.refreshLabel(0));
        checkEq("feed label unknown default", "Every 10 seconds", CamFeed.refreshLabel(7));
        checkEq("feed default interval", 10, CamFeed.DEFAULT_REFRESH_SECONDS);
    }

    // ---- 7. PROXY ----

    private static void proxy() {
        checkEq("proxy magic", "CONTROL-CAMS/1", CamProxy.MAGIC);
        checkEq("proxy http port", 8993, CamProxy.HTTP_PORT);
        checkEq("proxy udp port", 8994, CamProxy.UDP_PORT);
        checkEq("proxy announce interval", 2000L, CamProxy.ANNOUNCE_INTERVAL_MS);
        checkEq("proxy stale after", 15000L, CamProxy.STALE_AFTER_MS);

        // Round trip.
        List<CamProxy.Shared> shared = Arrays.asList(
                new CamProxy.Shared("id-one", "Front Door"),
                new CamProxy.Shared("id-two", "Back Yard"));
        String built = CamProxy.buildAnnouncement("Adam's Phone", shared);
        CamProxy.Announcement ann = CamProxy.parseAnnouncement(built);
        check("proxy round trip parsed", ann != null);
        checkEq("proxy round trip name", "Adam's Phone", ann == null ? null : ann.phoneName);
        checkEq("proxy round trip port", 8993, ann == null ? -1 : ann.tcpPort);
        checkEq("proxy round trip count", 2, ann == null ? -1 : ann.cameras.size());
        checkEq("proxy round trip id", "id-one",
                ann == null ? null : ann.cameras.get(0).id);
        checkEq("proxy round trip cam name", "Back Yard",
                ann == null ? null : ann.cameras.get(1).name);

        // The built JSON carries only the four keys — no
        // addresses, no URLs, no credentials.
        Set<String> keys = new HashSet<>();
        try {
            JSONObject o = new JSONObject(built);
            java.util.Iterator<String> it = o.keys();
            while (it.hasNext()) keys.add(it.next());
        } catch (Exception e) {
            // falls through to the check below
        }
        checkEq("proxy announcement keys", new HashSet<>(Arrays.asList(
                "magic", "name", "tcp", "cameras")), keys);
        check("proxy announcement no url", !built.contains("http://"));
        check("proxy announcement no at-sign", !built.contains("@"));

        // Build drops invalid ids and nulls; null name → "".
        List<CamProxy.Shared> dirty = Arrays.asList(
                new CamProxy.Shared("../evil", "Bad"),
                null,
                new CamProxy.Shared("good-id", null));
        CamProxy.Announcement ann2 = CamProxy.parseAnnouncement(
                CamProxy.buildAnnouncement("P", dirty));
        checkEq("proxy build drops invalid", 1, ann2 == null ? -1 : ann2.cameras.size());
        checkEq("proxy null name empty", "", ann2 == null ? null : ann2.cameras.get(0).name);
        CamProxy.Announcement ann3 = CamProxy.parseAnnouncement(
                CamProxy.buildAnnouncement("P", null));
        checkEq("proxy null cameras empty", 0, ann3 == null ? -1 : ann3.cameras.size());
        CamProxy.Announcement ann4 = CamProxy.parseAnnouncement(
                CamProxy.buildAnnouncement(null, null));
        checkEq("proxy null phone name empty", "", ann4 == null ? null : ann4.phoneName);

        // Parse rejections.
        check("proxy parse wrong magic null",
                CamProxy.parseAnnouncement("{\"magic\":\"OTHER\",\"tcp\":8993,\"cameras\":[]}") == null);
        check("proxy parse missing magic null",
                CamProxy.parseAnnouncement("{\"tcp\":8993,\"cameras\":[]}") == null);
        check("proxy parse port zero null",
                CamProxy.parseAnnouncement("{\"magic\":\"CONTROL-CAMS/1\",\"tcp\":0}") == null);
        check("proxy parse port huge null",
                CamProxy.parseAnnouncement("{\"magic\":\"CONTROL-CAMS/1\",\"tcp\":70000}") == null);
        check("proxy parse missing port null",
                CamProxy.parseAnnouncement("{\"magic\":\"CONTROL-CAMS/1\"}") == null);
        check("proxy parse malformed null",
                CamProxy.parseAnnouncement("{not json") == null);
        check("proxy parse null null", CamProxy.parseAnnouncement(null) == null);
        check("proxy parse empty null", CamProxy.parseAnnouncement("") == null);
        // Missing cameras array: empty list, not null.
        CamProxy.Announcement noCams = CamProxy.parseAnnouncement(
                "{\"magic\":\"CONTROL-CAMS/1\",\"tcp\":8993}");
        check("proxy parse no cameras array", noCams != null && noCams.cameras.isEmpty());
        // Invalid ids in a payload are dropped, valid kept.
        CamProxy.Announcement mixed = CamProxy.parseAnnouncement(
                "{\"magic\":\"CONTROL-CAMS/1\",\"tcp\":8993,\"cameras\":["
                        + "{\"id\":\"good-1\",\"name\":\"A\"},"
                        + "{\"id\":\"../bad\",\"name\":\"B\"},"
                        + "{\"id\":\"good-2\",\"name\":\"C\"}]}");
        checkEq("proxy parse drops invalid ids", 2, mixed == null ? -1 : mixed.cameras.size());

        // Id charset.
        check("proxy id uuid", CamProxy.validId("123e4567-e89b-12d3-a456-426614174000"));
        check("proxy id simple", CamProxy.validId("abc-123"));
        check("proxy id 64 ok", CamProxy.validId(
                "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"));
        check("proxy id empty rejected", !CamProxy.validId(""));
        check("proxy id 65 rejected", !CamProxy.validId(
                "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"));
        check("proxy id slash rejected", !CamProxy.validId("a/b"));
        check("proxy id dot rejected", !CamProxy.validId(".."));
        check("proxy id underscore rejected", !CamProxy.validId("a_b"));
        check("proxy id percent rejected", !CamProxy.validId("a%20b"));
        check("proxy id space rejected", !CamProxy.validId("a b"));
        check("proxy id null rejected", !CamProxy.validId(null));

        // Route table.
        checkEq("proxy route get", "abc-1",
                CamProxy.routeCamId("GET /cam/abc-1 HTTP/1.1"));
        checkEq("proxy route head", "abc-1",
                CamProxy.routeCamId("HEAD /cam/abc-1 HTTP/1.1"));
        checkEq("proxy route query stripped", "abc-1",
                CamProxy.routeCamId("GET /cam/abc-1?x=1 HTTP/1.1"));
        checkEq("proxy route fragment stripped", "abc-1",
                CamProxy.routeCamId("GET /cam/abc-1#frag HTTP/1.1"));
        check("proxy route post rejected",
                CamProxy.routeCamId("POST /cam/abc-1 HTTP/1.1") == null);
        check("proxy route bare cam rejected",
                CamProxy.routeCamId("GET /cam/ HTTP/1.1") == null);
        check("proxy route nested rejected",
                CamProxy.routeCamId("GET /cam/a/b HTTP/1.1") == null);
        check("proxy route other path rejected",
                CamProxy.routeCamId("GET /other/abc-1 HTTP/1.1") == null);
        check("proxy route encoded rejected",
                CamProxy.routeCamId("GET /cam/%2e%2e HTTP/1.1") == null);
        check("proxy route traversal rejected",
                CamProxy.routeCamId("GET /cam/../secret HTTP/1.1") == null);
        check("proxy route no version rejected",
                CamProxy.routeCamId("GET /cam/abc-1") == null);
        check("proxy route lowercase method rejected",
                CamProxy.routeCamId("get /cam/abc-1 HTTP/1.1") == null);
        check("proxy route double space rejected",
                CamProxy.routeCamId("GET  /cam/abc-1  HTTP/1.1") == null);
        check("proxy route trailing slash rejected",
                CamProxy.routeCamId("GET /cam/abc-1/ HTTP/1.1") == null);
        check("proxy route null rejected", CamProxy.routeCamId(null) == null);
        check("proxy route empty rejected", CamProxy.routeCamId("") == null);
        check("proxy route http10 ok", "abc-1".equals(
                CamProxy.routeCamId("GET /cam/abc-1 HTTP/1.0")));

        // Stale boundary: strictly greater than the window.
        check("proxy stale fresh", !CamProxy.isStale(1000, 1000));
        check("proxy stale at boundary", !CamProxy.isStale(0, 15000));
        check("proxy stale past boundary", CamProxy.isStale(0, 15001));
        check("proxy stale future", !CamProxy.isStale(20000, 1000));
    }

    // ---- 8. STORE_SHAPE ----

    private static void storeShape() {
        CamRecord rec = new CamRecord();
        rec.id = "fixed-id-123";
        rec.name = "Front Door";
        rec.host = "192.168.1.20";
        rec.port = 8080;
        rec.snapshotPath = "/snapshot.jpg";
        rec.mjpegPath = "/mjpg";
        rec.vendor = "Reolink";
        rec.refreshSeconds = 30;
        rec.credCipher = "QUJDREVGR0g=";
        rec.addedAt = 1700000000000L;
        rec.lastGoodAt = 1700000001000L;

        JSONObject json = rec.toJson();
        CamRecord back = CamRecord.fromJson(json);
        check("store round trip nonnull", back != null);
        checkEq("store round trip id", "fixed-id-123", back == null ? null : back.id);
        checkEq("store round trip name", "Front Door", back == null ? null : back.name);
        checkEq("store round trip host", "192.168.1.20", back == null ? null : back.host);
        checkEq("store round trip port", 8080, back == null ? -1 : back.port);
        checkEq("store round trip snapshot", "/snapshot.jpg",
                back == null ? null : back.snapshotPath);
        checkEq("store round trip mjpeg", "/mjpg", back == null ? null : back.mjpegPath);
        checkEq("store round trip vendor", "Reolink", back == null ? null : back.vendor);
        checkEq("store round trip refresh", 30, back == null ? -1 : back.refreshSeconds);
        checkEq("store round trip cipher", "QUJDREVGR0g=",
                back == null ? null : back.credCipher);
        checkEq("store round trip addedAt", 1700000000000L,
                back == null ? -1L : back.addedAt);
        checkEq("store round trip lastGoodAt", 1700000001000L,
                back == null ? -1L : back.lastGoodAt);

        // The JSON carries no plaintext credential field.
        String flat = json.toString();
        check("store json no username key", !json.has("username"));
        check("store json no password key", !json.has("password"));
        check("store json has cipher key", json.has("credCipher"));
        check("store json no plaintext creds",
                !flat.contains("admin") && !flat.contains("s3cret"));

        // fromJson rejections and clamps.
        check("store fromJson null", CamRecord.fromJson(null) == null);
        check("store fromJson no id", CamRecord.fromJson(
                new JSONObject("{\"host\":\"h\"}")) == null);
        check("store fromJson no host", CamRecord.fromJson(
                new JSONObject("{\"id\":\"x\"}")) == null);
        check("store fromJson empty id", CamRecord.fromJson(
                new JSONObject("{\"id\":\"\",\"host\":\"h\"}")) == null);
        CamRecord badPort = CamRecord.fromJson(new JSONObject(
                "{\"id\":\"x\",\"host\":\"h\",\"port\":0}"));
        checkEq("store port zero clamped", 80, badPort == null ? -1 : badPort.port);
        CamRecord hugePort = CamRecord.fromJson(new JSONObject(
                "{\"id\":\"x\",\"host\":\"h\",\"port\":70000}"));
        checkEq("store port huge clamped", 80, hugePort == null ? -1 : hugePort.port);
        CamRecord noPort = CamRecord.fromJson(new JSONObject(
                "{\"id\":\"x\",\"host\":\"h\"}"));
        checkEq("store port default", 80, noPort == null ? -1 : noPort.port);
        CamRecord badRefresh = CamRecord.fromJson(new JSONObject(
                "{\"id\":\"x\",\"host\":\"h\",\"refreshSeconds\":7}"));
        checkEq("store refresh clamped", 10, badRefresh == null ? -1 : badRefresh.refreshSeconds);

        // Id stability: fresh records get distinct UUIDs.
        CamRecord a = new CamRecord();
        CamRecord b = new CamRecord();
        check("store fresh ids distinct", !a.id.equals(b.id) && !a.id.isEmpty());

        // Display name, fetchability, URLs.
        checkEq("store displayName name", "Front Door", rec.displayName());
        CamRecord unnamed = new CamRecord();
        unnamed.host = "192.168.1.21";
        checkEq("store displayName host", "192.168.1.21", unnamed.displayName());
        check("store fetchable", rec.fetchable());
        CamRecord noPath = new CamRecord();
        noPath.host = "192.168.1.21";
        check("store not fetchable without path", !noPath.fetchable());
        CamRecord httpsPath = new CamRecord();
        httpsPath.host = "192.168.1.21";
        httpsPath.snapshotPath = "https://192.168.1.21/snap.jpg";
        check("store https path not fetchable", !httpsPath.fetchable());
        checkEq("store snapshotUrl", "http://192.168.1.20:8080/snapshot.jpg",
                rec.snapshotUrl());
        checkEq("store mjpegUrl", "http://192.168.1.20:8080/mjpg", rec.mjpegUrl());
        CamRecord noMjpeg = new CamRecord();
        noMjpeg.host = "h";
        checkEq("store mjpegUrl empty", "", noMjpeg.mjpegUrl());
        CamRecord fullUrl = new CamRecord();
        fullUrl.host = "192.168.1.21";
        fullUrl.snapshotPath = "http://10.0.0.9:8000/onvif/snap";
        checkEq("store snapshotUrl full passthrough",
                "http://10.0.0.9:8000/onvif/snap", fullUrl.snapshotUrl());

        // Host validation.
        check("store host ipv4", CamRecord.validHost("192.168.1.20"));
        check("store host name", CamRecord.validHost("camera.local"));
        check("store host bracketed v6", CamRecord.validHost("[fe80::1]"));
        check("store host bracketed loopback", CamRecord.validHost("[::1]"));
        check("store host scheme rejected", !CamRecord.validHost("http://x"));
        check("store host space rejected", !CamRecord.validHost("a b"));
        check("store host path rejected", !CamRecord.validHost("a/b"));
        check("store host userinfo rejected", !CamRecord.validHost("user@host"));
        check("store host empty rejected", !CamRecord.validHost(""));
        check("store host null rejected", !CamRecord.validHost(null));
        check("store host port smuggle rejected", !CamRecord.validHost("host:80"));
        check("store host bare v6 rejected", !CamRecord.validHost("fe80::1"));
        check("store host unclosed bracket rejected", !CamRecord.validHost("[abc"));
        check("store host stray bracket rejected", !CamRecord.validHost("abc]"));
        StringBuilder longHost = new StringBuilder();
        for (int i = 0; i < 254; i++) longHost.append('a');
        check("store host too long rejected", !CamRecord.validHost(longHost.toString()));

        // Credential join/split.
        String joined = CamRecord.joinCreds("admin", "s3cret");
        String[] split = CamRecord.splitCreds(joined);
        checkEq("store creds round trip user", "admin", split[0]);
        checkEq("store creds round trip pass", "s3cret", split[1]);
        String[] noNl = CamRecord.splitCreds("onlyuser");
        checkEq("store creds no newline user", "onlyuser", noNl[0]);
        checkEq("store creds no newline pass", "", noNl[1]);
        String[] nullSplit = CamRecord.splitCreds(null);
        checkEq("store creds null user", "", nullSplit[0]);
        checkEq("store creds null pass", "", nullSplit[1]);
        String[] multi = CamRecord.splitCreds("a\nb\nc");
        checkEq("store creds first newline splits user", "a", multi[0]);
        checkEq("store creds rest is password", "b\nc", multi[1]);
        checkEq("store creds join nulls", "\n", CamRecord.joinCreds(null, null));
        checkEq("store creds join empty", "\n", CamRecord.joinCreds("", ""));
    }

    // ---- 9. CREDENTIAL_CRYPTO ----

    private static void credentialCrypto() throws Exception {
        KeyGenerator kg = KeyGenerator.getInstance("AES");
        kg.init(256);
        SecretKey key = kg.generateKey();
        SecretKey other = kg.generateKey();

        String plain = CamRecord.joinCreds("admin", "s3cret-pass");
        byte[] blob = WalletCrypto.encrypt(key, plain.getBytes(StandardCharsets.UTF_8));
        check("crypto blob longer than plain", blob.length > plain.length());
        byte[] back = WalletCrypto.decrypt(key, blob);
        checkEq("crypto round trip", plain,
                new String(back, StandardCharsets.UTF_8));
        String[] pair = CamRecord.splitCreds(new String(back, StandardCharsets.UTF_8));
        checkEq("crypto round trip user", "admin", pair[0]);
        checkEq("crypto round trip pass", "s3cret-pass", pair[1]);

        // Fresh IVs: two seals of the same plaintext differ.
        byte[] blob2 = WalletCrypto.encrypt(key, plain.getBytes(StandardCharsets.UTF_8));
        check("crypto fresh iv differs", !Arrays.equals(blob, blob2));
        checkEq("crypto second seal reads", plain,
                new String(WalletCrypto.decrypt(key, blob2), StandardCharsets.UTF_8));

        // Tamper with the ciphertext: authentication fails.
        byte[] tampered = Arrays.copyOf(blob, blob.length);
        tampered[tampered.length - 1] ^= 0x01;
        boolean tamperFailed = false;
        try {
            WalletCrypto.decrypt(key, tampered);
        } catch (Exception e) {
            tamperFailed = true;
        }
        check("crypto tampered ciphertext rejected", tamperFailed);

        // Tamper with the IV: authentication fails too.
        byte[] tamperedIv = Arrays.copyOf(blob, blob.length);
        tamperedIv[0] ^= 0x01;
        boolean ivFailed = false;
        try {
            WalletCrypto.decrypt(key, tamperedIv);
        } catch (Exception e) {
            ivFailed = true;
        }
        check("crypto tampered iv rejected", ivFailed);

        // The wrong key reads nothing.
        boolean wrongKeyFailed = false;
        try {
            WalletCrypto.decrypt(other, blob);
        } catch (Exception e) {
            wrongKeyFailed = true;
        }
        check("crypto wrong key rejected", wrongKeyFailed);

        // Empty credentials round trip (a camera with no
        // password seals the empty pair).
        byte[] emptyBlob = WalletCrypto.encrypt(key,
                CamRecord.joinCreds("", "").getBytes(StandardCharsets.UTF_8));
        checkEq("crypto empty creds round trip", "\n",
                new String(WalletCrypto.decrypt(key, emptyBlob), StandardCharsets.UTF_8));

        // Too-short blobs are rejected before any attempt.
        boolean shortFailed = false;
        try {
            WalletCrypto.decrypt(key, new byte[12]);
        } catch (IllegalArgumentException e) {
            shortFailed = true;
        }
        check("crypto too-short rejected", shortFailed);
        boolean nullFailed = false;
        try {
            WalletCrypto.decrypt(key, null);
        } catch (IllegalArgumentException e) {
            nullFailed = true;
        }
        check("crypto null blob rejected", nullFailed);

        // Random bytes of plausible length fail authentication.
        boolean garbageFailed = false;
        try {
            byte[] garbage = new byte[64];
            new java.security.SecureRandom().nextBytes(garbage);
            WalletCrypto.decrypt(key, garbage);
        } catch (Exception e) {
            garbageFailed = true;
        }
        check("crypto garbage rejected", garbageFailed);
    }

    // ---- 10. RESOLUTION_STATE ----

    private static void resolutionState() {
        // The order the probe loop walks: ONVIF URIs are
        // gated first (usableOnvifUri), then vendor
        // patterns, then generic patterns, then the
        // hand-typed URL (buildUrl passthrough). The loop
        // itself (CamFetcher.resolveFeed) needs an Android
        // Context and is verified by inspection + the
        // on-device list; the order it walks is pinned
        // here, in the pure classes.
        List<String> reolink = CamFeed.snapshotCandidates("Reolink");
        check("resolution vendor before generic",
                reolink.indexOf("/cgi-bin/api.cgi?cmd=Snap&channel=0&rs=1")
                        < reolink.indexOf("/snapshot.jpg"));
        List<String> hik = CamFeed.snapshotCandidates("Hikvision");
        check("resolution both vendor paths before generics",
                hik.indexOf("/ISAPI/Streaming/channels/1/picture")
                        < hik.indexOf("/snapshot.jpg"));
        checkEq("resolution dedupe holds",
                new HashSet<>(reolink).size(), reolink.size());
        // The ONVIF gate: an http URI is usable, an https
        // one falls through to the pattern probes.
        check("resolution onvif http gate open",
                CamFeed.usableOnvifUri("http://192.168.1.20/onvif/snap"));
        check("resolution onvif https gate closed",
                !CamFeed.usableOnvifUri("https://192.168.1.20/onvif/snap"));
        // The manual last resort: a full URL passes through.
        checkEq("resolution manual url passthrough",
                "http://192.168.1.20/custom/snap.cgi",
                CamFeed.buildUrl("192.168.1.20", 80,
                        "http://192.168.1.20/custom/snap.cgi"));
        // MJPEG follows the same vendor-first order.
        List<String> foscam = CamFeed.mjpegCandidates("Foscam");
        check("resolution mjpeg vendor first",
                foscam.get(0).equals("/cgi-bin/CGIStream.cgi?cmd=GetMJStream"));
        // A camera with no vendor still gets the generic list.
        check("resolution generic fallback nonempty",
                !CamFeed.snapshotCandidates("").isEmpty());
    }
}
