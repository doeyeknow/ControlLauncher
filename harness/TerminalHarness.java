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
 * Off-device verification for the v1.6 terminal, against a mock
 * adbd on IPv6 loopback (::1 — the sandbox garbles JVM IPv4
 * sockets). The mock is the same shape as AdbConnectHarness's: a
 * Bouncy Castle TLS 1.3 server presenting a self-signed cert and
 * requesting a client certificate, like adbd's connect service.
 *
 * Legs:
 *   1. REDACTION (pure AdbShell): the token is masked everywhere
 *      it appears — the starter command, command output, repeated
 *      occurrences — while everything else stays byte-identical;
 *      empty/short tokens redact nothing; echo/shouldRecord rules.
 *   2. TYPED COMMAND: a multi-line, quoted command travels the ADB
 *      shell path byte-exact (the mock asserts the exact bytes it
 *      received) and multi-chunk output comes back verbatim.
 *   3. PRESET BYTES: a starter-shaped command (the
 *      shellStarterCommand shape, with a fake 48-hex token)
 *      reaches the mock byte-exact INCLUDING the real token — the
 *      wire carries the true command; only the display redacts it
 *      (the redacted form is asserted token-free at the same time).
 *      The app-side wiring — preset → AdbStarter.restartBridge →
 *      ShellBridgeClient.shellStarterCommand, unmodified — is
 *      structure, verified by reading the call chain, not by this
 *      harness: restartBridge needs NsdManager + the bridge socket.
 *   4. FAILURE MODES: refused port (generic wording, ConnectException
 *      verbatim, no pair-again wording) and certificate rejection
 *      (not connected, exception class verbatim, pair-again
 *      wording) — the exact properties TerminalActivity relies on
 *      to report honestly instead of faking success.
 *
 * What this harness cannot prove: mDNS discovery (needs
 * NsdManager), the bridge answering after the starter (needs the
 * bridge socket), or anything on a real phone. The ADB shell
 * service reports no exit status — the terminal claims none, and
 * there is no exit-code assertion to write.
 *
 * Run (from ~/workspace/control-launcher):
 *   javac --release 17 -cp $ANDROID_JAR:libs/zxing-core.jar:\
 *     libs/bcprov-jdk18on.jar:libs/bctls-jdk18on.jar:libs/bcutil-jdk18on.jar \
 *     -sourcepath src -d harness/classes harness/TerminalHarness.java
 *   java -cp harness/classes:libs/bcprov-jdk18on.jar:\
 *     libs/bctls-jdk18on.jar:libs/bcutil-jdk18on.jar:$ANDROID_JAR \
 *     app.control.launcher.TerminalHarness
 */
public final class TerminalHarness {

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

    private static final int A_CNXN = 0x4e584e43;
    private static final int A_OPEN = 0x4e45504f;
    private static final int A_OKAY = 0x59414b4f;
    private static final int A_CLSE = 0x45534c43;
    private static final int A_WRTE = 0x45545257;

    /** A fake bridge token in the real shape (48 hex chars). */
    private static final String TOKEN = "0123456789abcdef0123456789abcdef0123456789abcdef";

    /** The shellStarterCommand shape, with the fake token. */
    private static final String STARTER =
            "CP=$(pm path app.control.launcher | sed \"s/^package://\" "
            + "| tr \"\\n\" \":\"); CP=${CP%:}; "
            + "APP_UID=$(cmd package list packages -U app.control.launcher | sed \"s/.*uid://\"); "
            + "CLASSPATH=\"$CP\" nohup setsid app_process /system/bin "
            + "app.control.launcher.ShellBridgeMain \"$APP_UID\" \"" + TOKEN
            + "\" >/data/local/tmp/control-bridge.log 2>&1 </dev/null &";

    public static void main(String[] args) throws Exception {
        redactionLeg();

        InetAddress loop = InetAddress.getByName("::1");
        KeyPair clientKp = rsa();
        byte[] clientCert = selfSigned(clientKp);
        KeyPair serverKp = rsa();
        byte[] serverCert = selfSigned(serverKp);

        typedCommandLeg(loop, clientKp, clientCert, serverKp, serverCert);
        presetBytesLeg(loop, clientKp, clientCert, serverKp, serverCert);
        refusedLeg(loop, clientKp, clientCert);
        rejectLeg(loop, clientKp, clientCert, serverKp, serverCert);

        System.out.println("----");
        System.out.println(passed + " passed, " + failed + " failed");
        System.exit(failed == 0 ? 0 : 1);
    }

    // ---------- leg 1: redaction (pure AdbShell) ----------

    private static void redactionLeg() {
        String masked = AdbShell.redact(STARTER, TOKEN);
        check("redact: starter has no token value",
                !masked.contains(TOKEN), masked);
        check("redact: starter keeps everything else byte-identical",
                masked.equals(STARTER.replace(TOKEN, AdbShell.TOKEN_MASK)), masked);
        check("redact: mask placeholder present",
                masked.contains(AdbShell.TOKEN_MASK), masked);

        String output = "bridge token is " + TOKEN + " and again " + TOKEN + "\ndone\n";
        String redactedOut = AdbShell.redact(output, TOKEN);
        check("redact: output occurrences all masked",
                !redactedOut.contains(TOKEN)
                        && redactedOut.equals("bridge token is " + AdbShell.TOKEN_MASK
                                + " and again " + AdbShell.TOKEN_MASK + "\ndone\n"),
                redactedOut);

        check("redact: empty token is a no-op",
                AdbShell.redact("abc", "").equals("abc"), "");
        check("redact: short token is a no-op",
                AdbShell.redact("abcabc", "abc").equals("abcabc"), "");
        check("redact: null text yields empty",
                AdbShell.redact(null, TOKEN).isEmpty(), "");
        check("redact: text without token is untouched",
                AdbShell.redact("echo hi", TOKEN).equals("echo hi"), "");

        check("echoLine: prompt + masked command",
                AdbShell.echoLine(STARTER, TOKEN)
                        .equals("$ " + STARTER.replace(TOKEN, AdbShell.TOKEN_MASK)), "");
        check("echoLine: plain command verbatim after prompt",
                AdbShell.echoLine("id", TOKEN).equals("$ id"), "");

        check("shouldRecord: plain command recorded",
                AdbShell.shouldRecord("id", TOKEN), "");
        check("shouldRecord: token-bearing command never recorded",
                !AdbShell.shouldRecord(STARTER, TOKEN), "");
        check("shouldRecord: blank command never recorded",
                !AdbShell.shouldRecord("   ", TOKEN)
                        && !AdbShell.shouldRecord("", TOKEN), "");
        check("shouldRecord: null command never recorded",
                !AdbShell.shouldRecord(null, TOKEN), "");
    }

    // ---------- leg 2: typed command, verbatim both ways ----------

    private static void typedCommandLeg(InetAddress loop, KeyPair clientKp, byte[] clientCert,
                                        KeyPair serverKp, byte[] serverCert) throws Exception {
        final String command = "echo 'line one'; echo line-two 2>&1; echo \"quoted $HOME\"";
        final String chunk1 = "line one\nline-two\n";
        final String chunk2 = "quoted /data/user/0\n[stderr] warning: something\n";
        MockServer mock = new MockServer(serverKp, serverCert, false);
        AtomicReference<String> seenCommand = new AtomicReference<>();
        AtomicReference<Throwable> serverError = new AtomicReference<>();
        int port = serveOnce(loop, mock, (in, out) -> {
            Msg first = readMsg(in);
            if (first.command != A_CNXN) throw new IOException("expected CNXN");
            sendMsg(out, A_CNXN, 0x01000001, 1024 * 1024,
                    "device::\0".getBytes(StandardCharsets.UTF_8));
            Msg open = readMsg(in);
            if (open.command != A_OPEN) throw new IOException("expected OPEN");
            String dest = new String(open.payload, StandardCharsets.UTF_8);
            if (!dest.startsWith("shell:") || !dest.endsWith("\0")) {
                throw new IOException("bad OPEN dest");
            }
            seenCommand.set(dest.substring("shell:".length(), dest.length() - 1));
            sendMsg(out, A_OKAY, 7, open.arg0, null);
            sendMsg(out, A_WRTE, 7, open.arg0, chunk1.getBytes(StandardCharsets.UTF_8));
            if (readMsg(in).command != A_OKAY) throw new IOException("expected OKAY ack 1");
            sendMsg(out, A_WRTE, 7, open.arg0, chunk2.getBytes(StandardCharsets.UTF_8));
            if (readMsg(in).command != A_OKAY) throw new IOException("expected OKAY ack 2");
            sendMsg(out, A_CLSE, 7, open.arg0, null);
            if (readMsg(in).command != A_CLSE) throw new IOException("expected CLSE");
        }, serverError);

        AdbClient.Result r = AdbClient.runShell(clientKp, clientCert, loop, port,
                command, 15000);
        check("typed: connected + authorized", r.connected && r.authorized, r.error);
        check("typed: mock saw the exact command bytes",
                command.equals(seenCommand.get()),
                "seen=[" + seenCommand.get() + "] serverError=" + serverError.get());
        check("typed: multi-chunk output verbatim",
                (chunk1 + chunk2).equals(r.output), "output=[" + r.output + "]");
        check("typed: no error text", r.error.isEmpty(), r.error);
    }

    // ---------- leg 3: preset bytes on the wire vs masked display ----------

    private static void presetBytesLeg(InetAddress loop, KeyPair clientKp, byte[] clientCert,
                                       KeyPair serverKp, byte[] serverCert) throws Exception {
        MockServer mock = new MockServer(serverKp, serverCert, false);
        AtomicReference<String> seenCommand = new AtomicReference<>();
        AtomicReference<Throwable> serverError = new AtomicReference<>();
        int port = serveOnce(loop, mock, (in, out) -> {
            Msg first = readMsg(in);
            if (first.command != A_CNXN) throw new IOException("expected CNXN");
            sendMsg(out, A_CNXN, 0x01000001, 1024 * 1024,
                    "device::\0".getBytes(StandardCharsets.UTF_8));
            Msg open = readMsg(in);
            if (open.command != A_OPEN) throw new IOException("expected OPEN");
            String dest = new String(open.payload, StandardCharsets.UTF_8);
            seenCommand.set(dest.substring("shell:".length(), dest.length() - 1));
            sendMsg(out, A_OKAY, 7, open.arg0, null);
            // The real starter backgrounds itself and prints nothing.
            sendMsg(out, A_CLSE, 7, open.arg0, null);
            if (readMsg(in).command != A_CLSE) throw new IOException("expected CLSE");
        }, serverError);

        AdbClient.Result r = AdbClient.runShell(clientKp, clientCert, loop, port,
                STARTER, 15000);
        check("preset: connected + authorized", r.connected && r.authorized, r.error);
        check("preset: wire carries the exact starter bytes, token included",
                STARTER.equals(seenCommand.get()),
                "seen=[" + seenCommand.get() + "] serverError=" + serverError.get());
        check("preset: wire bytes contain the real token",
                seenCommand.get() != null && seenCommand.get().contains(TOKEN), "");
        check("preset: display form is token-free",
                !AdbShell.echoLine(STARTER, TOKEN).contains(TOKEN), "");
        check("preset: empty output is a clean run, not an error",
                r.authorized && r.error.isEmpty() && r.output.isEmpty(),
                "output=[" + r.output + "] error=" + r.error);
    }

    // ---------- leg 4a: nothing listening ----------

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

    // ---------- leg 4b: server rejects the client certificate ----------

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
        check("reject: exception class verbatim",
                r.error.contains("TlsFatalAlertReceived")
                        || r.error.contains("SocketException"), r.error);
        check("reject: pair-again wording, no generic sentence",
                r.error.contains("no longer trusts Control's key")
                        && r.error.contains("pair again")
                        && !r.error.contains("usual cause"), r.error);
    }

    // ---------- mock adbd TLS server (same shape as AdbConnectHarness) ----------

    private interface WireBody {
        void run(InputStream in, OutputStream out) throws Exception;
    }

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
