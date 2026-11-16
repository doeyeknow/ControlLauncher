package app.control.launcher;

import org.bouncycastle.crypto.params.AsymmetricKeyParameter;
import org.bouncycastle.crypto.util.PrivateKeyFactory;
import org.bouncycastle.tls.AlertDescription;
import org.bouncycastle.tls.Certificate;
import org.bouncycastle.tls.CertificateEntry;
import org.bouncycastle.tls.CertificateRequest;
import org.bouncycastle.tls.DefaultTlsServer;
import org.bouncycastle.tls.ProtocolVersion;
import org.bouncycastle.tls.SignatureAndHashAlgorithm;
import org.bouncycastle.tls.TlsCredentials;
import org.bouncycastle.tls.TlsFatalAlert;
import org.bouncycastle.tls.TlsServerContext;
import org.bouncycastle.tls.TlsServerProtocol;
import org.bouncycastle.tls.crypto.TlsCertificate;
import org.bouncycastle.tls.crypto.TlsCryptoParameters;
import org.bouncycastle.tls.crypto.impl.bc.BcDefaultTlsCredentialedSigner;
import org.bouncycastle.tls.crypto.impl.bc.BcTlsCertificate;
import org.bouncycastle.tls.crypto.impl.bc.BcTlsCrypto;

import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.SecureRandom;
import java.security.Signature;
import java.util.Vector;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Off-device verification of the ADB TLS paths against a mock adbd
 * on IPv6 loopback (::1 — the sandbox garbles JVM IPv4 sockets).
 * The mock is a Bouncy Castle TLS 1.3 server presenting a
 * self-signed cert and requesting a client certificate, exactly
 * like adbd's connect/pairing services.
 *
 * Legs:
 *   1. CONNECT — the shipping AdbClient (v1.4, BC transport) runs a
 *      shell command end to end: mutual-TLS handshake, CNXN, OPEN
 *      shell:, WRTE echo, CLSE. The mock asserts it received the
 *      exact command.
 *   2. CONNECT, KEY REJECTED — the mock answers the client
 *      certificate with a fatal certificate_unknown alert. The
 *      result must be "not connected", carry the BC exception class
 *      + message verbatim, and carry the pair-again wording.
 *   3. CONNECT, NOTHING LISTENING — connection refused; the error
 *      must carry ConnectException verbatim under the generic
 *      "Could not connect over TLS" wording (NOT the pair-again
 *      wording).
 *   4. PAIRING (regression) — the shipping AdbPairing completes the
 *      full protocol against the mock pairing service (SPAKE2 bob
 *      side + PairingCipher are the app's own classes) and returns
 *      the mock's GUID; a wrong code fails at the identity stage.
 *
 * Run (from ~/workspace/control-launcher):
 *   javac --release 17 -cp $ANDROID_JAR:libs/zxing-core.jar:\
 *     libs/bcprov-jdk18on.jar:libs/bctls-jdk18on.jar:libs/bcutil-jdk18on.jar \
 *     -sourcepath src -d harness/classes harness/AdbConnectHarness.java
 *   java -cp harness/classes:libs/bcprov-jdk18on.jar:\
 *     libs/bctls-jdk18on.jar:libs/bcutil-jdk18on.jar:$ANDROID_JAR \
 *     app.control.launcher.AdbConnectHarness
 * ($ANDROID_JAR = the android-34 android.jar; needed only so the
 * Context-taking overloads resolve — the harness calls the
 * KeyPair overloads, like RelayHarness calls the pure core.)
 */
public final class AdbConnectHarness {

    private static int passed = 0;
    private static int failed = 0;

    private static void check(String name, boolean ok, String detail) {
        if (ok) {
            passed++;
            System.out.println("PASS  " + name);
        } else {
            failed++;
            System.out.println("FAIL  " + name + "  -- " + detail);
        }
    }

    // ADB wire ids (same constants as AdbClient).
    private static final int A_CNXN = 0x4e584e43;
    private static final int A_OPEN = 0x4e45504f;
    private static final int A_OKAY = 0x59414b4f;
    private static final int A_CLSE = 0x45534c43;
    private static final int A_WRTE = 0x45545257;

    private static final String PAIR_CODE = "483920";
    private static final String MOCK_GUID = "mock-adbd-guid-01";

    public static void main(String[] args) throws Exception {
        InetAddress loop = InetAddress.getByName("::1");
        KeyPair clientKp = rsa();
        byte[] clientCert = selfSigned(clientKp);
        KeyPair serverKp = rsa();
        byte[] serverCert = selfSigned(serverKp);

        connectLeg(loop, clientKp, clientCert, serverKp, serverCert);
        rejectLeg(loop, clientKp, clientCert, serverKp, serverCert);
        refusedLeg(loop, clientKp, clientCert);
        pairingLeg(loop, clientKp, clientCert, serverKp, serverCert, PAIR_CODE, true);
        pairingLeg(loop, clientKp, clientCert, serverKp, serverCert, "000000", false);

        System.out.println("----");
        System.out.println(passed + " passed, " + failed + " failed");
        System.exit(failed == 0 ? 0 : 1);
    }

    // ---------- leg 1: connect + shell round trip ----------

    private static void connectLeg(InetAddress loop, KeyPair clientKp, byte[] clientCert,
                                   KeyPair serverKp, byte[] serverCert) throws Exception {
        MockServer mock = new MockServer(serverKp, serverCert, false);
        AtomicReference<String> seenCommand = new AtomicReference<>();
        AtomicReference<Throwable> serverError = new AtomicReference<>();
        int port = serveOnce(loop, mock, (in, out) -> {
            Msg first = readMsg(in);
            if (first.command != A_CNXN) throw new IOException("expected CNXN, got " + first.command);
            sendMsg(out, A_CNXN, 0x01000001, 1024 * 1024,
                    "device::\0".getBytes(StandardCharsets.UTF_8));
            Msg open = readMsg(in);
            if (open.command != A_OPEN) throw new IOException("expected OPEN, got " + open.command);
            String dest = new String(open.payload, StandardCharsets.UTF_8);
            if (!dest.startsWith("shell:") || !dest.endsWith("\0")) {
                throw new IOException("bad OPEN dest: " + dest);
            }
            String cmd = dest.substring("shell:".length(), dest.length() - 1);
            seenCommand.set(cmd);
            sendMsg(out, A_OKAY, 7, open.arg0, null);
            sendMsg(out, A_WRTE, 7, open.arg0,
                    ("mock-shell: " + cmd + "\n").getBytes(StandardCharsets.UTF_8));
            Msg ack = readMsg(in);
            if (ack.command != A_OKAY) throw new IOException("expected OKAY ack, got " + ack.command);
            sendMsg(out, A_CLSE, 7, open.arg0, null);
            Msg close = readMsg(in);
            if (close.command != A_CLSE) throw new IOException("expected CLSE, got " + close.command);
        }, serverError);

        AdbClient.Result r = AdbClient.runShell(clientKp, clientCert, loop, port,
                "echo hello-connect", 15000);
        check("connect: TLS+CNXN completed", r.connected, r.error);
        check("connect: shell authorized", r.authorized, r.error);
        check("connect: echo round trip",
                "mock-shell: echo hello-connect\n".equals(r.output),
                "output=[" + r.output + "]");
        check("connect: mock saw the exact command",
                "echo hello-connect".equals(seenCommand.get()),
                "seen=[" + seenCommand.get() + "] serverError=" + serverError.get());
        check("connect: no error text", r.error.isEmpty(), r.error);
    }

    // ---------- leg 2: server rejects the client certificate ----------

    private static void rejectLeg(InetAddress loop, KeyPair clientKp, byte[] clientCert,
                                  KeyPair serverKp, byte[] serverCert) throws Exception {
        MockServer mock = new MockServer(serverKp, serverCert, true);
        AtomicReference<Throwable> serverError = new AtomicReference<>();
        int port = serveOnce(loop, mock, (in, out) -> {
            throw new IOException("wire should never be reached");
        }, serverError);

        AdbClient.Result r = AdbClient.runShell(clientKp, clientCert, loop, port,
                "echo nope", 15000);
        check("reject: not connected", !r.connected && !r.authorized, r.error);
        // The rejection reaches the client in one of two race-equal
        // shapes: the certificate_unknown alert read as a record
        // (TlsFatalAlertReceived), or the socket dying under our
        // first write (SocketException, broken pipe). Both must
        // carry the exception class verbatim and the pair-again
        // wording — never the generic "usual cause" sentence.
        check("reject: exception class verbatim",
                r.error.contains("TlsFatalAlertReceived")
                        || r.error.contains("SocketException"), r.error);
        check("reject: pair-again wording",
                r.error.contains("no longer trusts Control's key")
                        && r.error.contains("pair again")
                        && !r.error.contains("usual cause"), r.error);
    }

    // ---------- leg 3: connection refused ----------

    private static void refusedLeg(InetAddress loop, KeyPair clientKp, byte[] clientCert)
            throws Exception {
        int port;
        try (ServerSocket s = new ServerSocket(0, 1, loop)) {
            port = s.getLocalPort();
        }
        AdbClient.Result r = AdbClient.runShell(clientKp, clientCert, loop, port,
                "echo nope", 5000);
        check("refused: not connected", !r.connected && !r.authorized, r.error);
        check("refused: generic TLS wording + ConnectException verbatim",
                r.error.startsWith("Could not connect over TLS (")
                        && r.error.contains("ConnectException"), r.error);
        check("refused: no pair-again wording",
                !r.error.contains("pair again"), r.error);
    }

    // ---------- leg 4/5: pairing regression (success + wrong code) ----------

    private static void pairingLeg(InetAddress loop, KeyPair clientKp, byte[] clientCert,
                                   KeyPair serverKp, byte[] serverCert,
                                   String clientCode, boolean expectSuccess) throws Exception {
        MockServer mock = new MockServer(serverKp, serverCert, false);
        AtomicReference<Throwable> serverError = new AtomicReference<>();
        int port = serveOnce(loop, mock, (in, out) -> {
            byte[] exported = mock.exportedKeyMaterial();
            if (exported == null || exported.length != 64) {
                throw new IOException("server exporter missing");
            }
            byte[] password = Spake2.concat(
                    PAIR_CODE.getBytes(StandardCharsets.UTF_8), exported);
            Spake2 bob = new Spake2(false, password, new SecureRandom());
            byte[] clientMsg = readPacket(in, 0);
            writePacket(out, 0, bob.msg());
            byte[] keyMaterial = bob.processMsg(clientMsg);
            PairingCipher cipher = new PairingCipher(keyMaterial);
            byte[] theirInfo = cipher.open(readPacket(in, 1)); // throws on wrong code
            if (theirInfo.length != 8192 || theirInfo[0] != 0) {
                throw new IOException("bad client PeerInfo");
            }
            byte[] info = new byte[8192];
            info[0] = 1; // ADB_DEVICE_GUID
            byte[] guid = MOCK_GUID.getBytes(StandardCharsets.US_ASCII);
            System.arraycopy(guid, 0, info, 1, guid.length);
            writePacket(out, 1, cipher.seal(info));
        }, serverError);

        AdbPairing.Result r = AdbPairing.pair(clientKp, clientCert, loop, port,
                clientCode, null);
        if (expectSuccess) {
            check("pairing: success against mock adbd", r.success,
                    "stage=" + r.failedStage + " error=" + r.error
                            + " serverError=" + serverError.get());
            check("pairing: GUID round trip", MOCK_GUID.equals(r.deviceGuid),
                    "guid=[" + r.deviceGuid + "]");
        } else {
            check("pairing: wrong code refused", !r.success, "unexpectedly paired");
            check("pairing: wrong code fails at identity stage",
                    AdbPairing.STAGE_IDENTITY.equals(r.failedStage),
                    "stage=" + r.failedStage + " error=" + r.error);
        }
    }

    // ---------- mock adbd TLS server ----------

    private interface WireBody {
        void run(InputStream in, OutputStream out) throws Exception;
    }

    /** Starts the mock on an ephemeral ::1 port; the handshake runs
     *  inside TlsServerProtocol.accept, then body drives the wire.
     *  Returns the port once listening. */
    private static int serveOnce(InetAddress loop, MockServer mock, WireBody body,
                                 AtomicReference<Throwable> serverError) throws Exception {
        ServerSocket listener = new ServerSocket(0, 1, loop);
        int port = listener.getLocalPort();
        CountDownLatch listening = new CountDownLatch(1);
        Thread t = new Thread(() -> {
            try {
                listening.countDown();
                try (Socket s = listener.accept()) {
                    s.setSoTimeout(20000);
                    TlsServerProtocol proto = new TlsServerProtocol(
                            s.getInputStream(), s.getOutputStream());
                    proto.accept(mock);
                    body.run(proto.getInputStream(), proto.getOutputStream());
                    try {
                        proto.close();
                    } catch (Exception ignored) {
                    }
                }
            } catch (Throwable e) {
                serverError.set(e);
            } finally {
                try {
                    listener.close();
                } catch (Exception ignored) {
                }
            }
        }, "mock-adbd");
        t.setDaemon(true);
        t.start();
        if (!listening.await(5, TimeUnit.SECONDS)) {
            throw new IOException("mock server did not start");
        }
        return port;
    }

    private static final class MockServer extends DefaultTlsServer {
        private final KeyPair keyPair;
        private final byte[] certDer;
        private final boolean rejectClient;
        private volatile TlsServerContext serverContext;
        private volatile byte[] exportedKeyMaterial;

        MockServer(KeyPair keyPair, byte[] certDer, boolean rejectClient) {
            super(new BcTlsCrypto(new SecureRandom()));
            this.keyPair = keyPair;
            this.certDer = certDer;
            this.rejectClient = rejectClient;
        }

        @Override
        public void init(TlsServerContext context) {
            super.init(context);
            this.serverContext = context;
        }

        /** BC only hands out the exporter secret inside this
         *  callback — the same rule the client lives by. */
        @Override
        public void notifyHandshakeComplete() throws IOException {
            super.notifyHandshakeComplete();
            exportedKeyMaterial = serverContext.exportKeyingMaterial(
                    "adb-label\0", null, 64);
        }

        byte[] exportedKeyMaterial() {
            return exportedKeyMaterial;
        }

        /** TLS 1.3 only, like AOSP's tls_connection. */
        @Override
        protected ProtocolVersion[] getSupportedVersions() {
            return new ProtocolVersion[]{ProtocolVersion.TLSv13};
        }

        @Override
        public CertificateRequest getCertificateRequest() throws IOException {
            Vector<SignatureAndHashAlgorithm> sigAlgs = new Vector<>();
            sigAlgs.add(SignatureAndHashAlgorithm.rsa_pss_rsae_sha256);
            return new CertificateRequest(new byte[0], sigAlgs, null, null);
        }

        @Override
        public void notifyClientCertificate(Certificate clientCertificate)
                throws IOException {
            if (rejectClient) {
                throw new TlsFatalAlert(AlertDescription.certificate_unknown);
            }
        }

        @Override
        public TlsCredentials getCredentials() throws IOException {
            BcTlsCrypto bc = (BcTlsCrypto) getCrypto();
            AsymmetricKeyParameter priv = PrivateKeyFactory.createKey(
                    keyPair.getPrivate().getEncoded());
            TlsCertificate cert = new BcTlsCertificate(bc, certDer);
            Certificate certMsg = new Certificate(new byte[0],
                    new CertificateEntry[]{new CertificateEntry(cert, null)});
            return new BcDefaultTlsCredentialedSigner(
                    new TlsCryptoParameters(serverContext), bc, priv, certMsg,
                    SignatureAndHashAlgorithm.rsa_pss_rsae_sha256);
        }
    }

    // ---------- identity minting (harness-only) ----------

    private static KeyPair rsa() throws Exception {
        KeyPairGenerator g = KeyPairGenerator.getInstance("RSA");
        g.initialize(2048);
        return g.generateKeyPair();
    }

    /** Minimal hand-DER self-signed cert — the same construction
     *  AdbKeyStore ships (bcpkix is not vendored, and the real
     *  client cert is shaped exactly like this anyway). */
    private static byte[] selfSigned(KeyPair kp) throws Exception {
        byte[] algId = tlv(0x30, concat(
                tlv(0x06, new byte[]{0x2A, (byte) 0x86, 0x48, (byte) 0x86, (byte) 0xF7,
                        0x0D, 0x01, 0x01, 0x0B}),
                tlv(0x05, new byte[0])));
        byte[] atv = tlv(0x30, concat(
                tlv(0x06, new byte[]{0x55, 0x04, 0x03}),
                tlv(0x0C, "Mock ADB".getBytes(StandardCharsets.UTF_8))));
        byte[] name = tlv(0x30, tlv(0x31, atv));
        byte[] validity = tlv(0x30, concat(
                tlv(0x17, "240101000000Z".getBytes(StandardCharsets.US_ASCII)),
                tlv(0x17, "450101000000Z".getBytes(StandardCharsets.US_ASCII))));
        byte[] version = tlv(0xA0, tlv(0x02, new byte[]{2}));
        byte[] serial = tlv(0x02, new byte[]{1});
        byte[] spki = kp.getPublic().getEncoded();
        byte[] tbs = tlv(0x30, concat(version, serial, algId, name, validity, name, spki));
        Signature sig = Signature.getInstance("SHA256withRSA");
        sig.initSign(kp.getPrivate());
        sig.update(tbs);
        byte[] sigBitString = tlv(0x03, concat(new byte[]{0}, sig.sign()));
        return tlv(0x30, concat(tbs, algId, sigBitString));
    }

    private static byte[] tlv(int tag, byte[] content) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(tag);
        int len = content.length;
        if (len < 0x80) {
            out.write(len);
        } else if (len < 0x100) {
            out.write(0x81);
            out.write(len);
        } else {
            out.write(0x82);
            out.write((len >> 8) & 0xFF);
            out.write(len & 0xFF);
        }
        out.write(content, 0, content.length);
        return out.toByteArray();
    }

    private static byte[] concat(byte[]... parts) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (byte[] p : parts) out.write(p, 0, p.length);
        return out.toByteArray();
    }

    // ---------- ADB wire framing (server side) ----------

    private static final class Msg {
        int command, arg0, arg1;
        byte[] payload = new byte[0];
    }

    private static void sendMsg(OutputStream out, int command, int arg0, int arg1,
                                byte[] payload) throws IOException {
        if (payload == null) payload = new byte[0];
        int sum = 0;
        for (byte bb : payload) sum += bb & 0xFF;
        byte[] header = new byte[24];
        putLe(header, 0, command);
        putLe(header, 4, arg0);
        putLe(header, 8, arg1);
        putLe(header, 12, payload.length);
        putLe(header, 16, sum);
        putLe(header, 20, command ^ 0xFFFFFFFF);
        out.write(header);
        if (payload.length > 0) out.write(payload);
        out.flush();
    }

    private static Msg readMsg(InputStream in) throws IOException {
        byte[] header = readFully(in, 24);
        Msg m = new Msg();
        m.command = getLe(header, 0);
        m.arg0 = getLe(header, 4);
        m.arg1 = getLe(header, 8);
        int len = getLe(header, 12);
        if (len < 0 || len > 16 * 1024 * 1024) {
            throw new IOException("implausible payload length " + len);
        }
        m.payload = len == 0 ? new byte[0] : readFully(in, len);
        return m;
    }

    // ---------- pairing packet framing (server side) ----------

    private static void writePacket(OutputStream out, int type, byte[] payload)
            throws IOException {
        byte[] h = new byte[6];
        h[0] = 1;
        h[1] = (byte) type;
        int len = payload.length;
        h[2] = (byte) (len >>> 24);
        h[3] = (byte) (len >>> 16);
        h[4] = (byte) (len >>> 8);
        h[5] = (byte) len;
        out.write(h);
        out.write(payload);
        out.flush();
    }

    private static byte[] readPacket(InputStream in, int expectedType)
            throws IOException {
        byte[] h = readFully(in, 6);
        if ((h[0] & 0xff) != 1) throw new IOException("packet version " + (h[0] & 0xff));
        int type = h[1] & 0xff;
        int len = ((h[2] & 0xff) << 24) | ((h[3] & 0xff) << 16)
                | ((h[4] & 0xff) << 8) | (h[5] & 0xff);
        if (len <= 0 || len > 16384) throw new IOException("packet length " + len);
        if (type != expectedType) throw new IOException("packet type " + type);
        return readFully(in, len);
    }

    private static byte[] readFully(InputStream in, int n) throws IOException {
        byte[] buf = new byte[n];
        int got = 0;
        while (got < n) {
            int r = in.read(buf, got, n - got);
            if (r < 0) throw new EOFException("stream closed");
            got += r;
        }
        return buf;
    }

    private static void putLe(byte[] b, int off, int v) {
        b[off] = (byte) v;
        b[off + 1] = (byte) (v >> 8);
        b[off + 2] = (byte) (v >> 16);
        b[off + 3] = (byte) (v >> 24);
    }

    private static int getLe(byte[] b, int off) {
        return (b[off] & 0xFF) | ((b[off + 1] & 0xFF) << 8)
                | ((b[off + 2] & 0xFF) << 16) | ((b[off + 3] & 0xFF) << 24);
    }
}
