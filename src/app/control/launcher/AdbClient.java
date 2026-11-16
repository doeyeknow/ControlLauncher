package app.control.launcher;

import android.content.Context;

import org.bouncycastle.tls.AlertDescription;
import org.bouncycastle.tls.TlsFatalAlert;
import org.bouncycastle.tls.TlsFatalAlertReceived;

import java.io.EOFException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;

/**
 * A minimal ADB-over-TLS client — just enough of the wire protocol to
 * open the legacy {@code shell:} service and run one command:
 *
 *   TLS (client certificate = Control's ADB key, see AdbKeyStore)
 *   → CNXN handshake → OPEN shell:<cmd> → WRTE/OKAY until CLSE.
 *
 * Authorization happens at the TLS layer: the phone only completes a
 * handshake it can chain to an authorized key, and only for keys it
 * paired with. If Control's key was never paired (or pairing was
 * revoked), the handshake fails and this class says so — see
 * {@link Result#authorized} and the certificate-alert wording in
 * {@link #handshakeError}.
 *
 * The TLS transport is the shared Bouncy Castle stack in
 * {@link AdbTls} (v1.4) — the exact client {@link AdbPairing} proved
 * against a real adbd in v1.2. The framework SSLContext this class
 * used through v1.3 presented the same certificate bytes yet its
 * handshake died on Adam's phone before CNXN, while pairing's BC
 * handshake succeeded; there is now one stack, not two. The ADB
 * wire protocol below the transport is unchanged since v1.1.
 */
public final class AdbClient {

    // ADB protocol message ids (uint32, little-endian on the wire).
    private static final int A_CNXN = 0x4e584e43;
    private static final int A_OPEN = 0x4e45504f;
    private static final int A_OKAY = 0x59414b4f;
    private static final int A_CLSE = 0x45534c43;
    private static final int A_WRTE = 0x45545257;
    private static final int A_AUTH = 0x48545541;
    private static final int A_VERSION = 0x01000001;
    private static final int MAX_DATA = 1024 * 1024;

    public static final class Result {
        public boolean connected;     // TLS + CNXN completed
        public boolean authorized;    // shell service opened
        public String output = "";    // shell output, if any
        public String error = "";
    }

    private AdbClient() {}

    /** Android entry point: runs the command with Control's stored
     *  ADB identity. */
    public static Result runShell(Context ctx, InetAddress host, int port,
                                  String command, int timeoutMs) {
        try {
            KeyPair kp = AdbKeyStore.keyPair(ctx);
            byte[] certDer = AdbKeyStore.certificate(ctx).getEncoded();
            return runShell(kp, certDer, host, port, command, timeoutMs);
        } catch (Exception e) {
            Result r = new Result();
            r.error = "Control's ADB key could not be loaded: "
                    + e.getClass().getSimpleName();
            return r;
        }
    }

    /** Core client (no Android types — exercised off-device). */
    public static Result runShell(KeyPair kp, byte[] certDer, InetAddress host,
                                  int port, String command, int timeoutMs) {
        Result r = new Result();
        AdbTls.Connection conn = null;
        try {
            conn = AdbTls.connect(kp, certDer, host, port, timeoutMs, 8000);
            InputStream in = conn.inputStream();
            OutputStream out = conn.outputStream();

            // Handshake: host speaks first.
            send(out, A_CNXN, A_VERSION, MAX_DATA,
                    "host::\0".getBytes(StandardCharsets.UTF_8));
            Message first = read(in);
            if (first.command == A_AUTH) {
                r.error = "The phone asked for classic ADB auth — Control's key "
                        + "is not paired with this phone.";
                return r;
            }
            if (first.command != A_CNXN) {
                r.error = "Unexpected first message from the phone ("
                        + name(first.command) + ").";
                return r;
            }
            r.connected = true;

            int localId = 1;
            byte[] dest = ("shell:" + command + "\0").getBytes(StandardCharsets.UTF_8);
            send(out, A_OPEN, localId, 0, dest);

            int remoteId = -1;
            long deadline = System.currentTimeMillis() + timeoutMs;
            StringBuilder output = new StringBuilder();
            while (System.currentTimeMillis() < deadline) {
                Message m;
                try {
                    m = read(in);
                } catch (EOFException eof) {
                    break;
                }
                switch (m.command) {
                    case A_OKAY:
                        remoteId = m.arg0;
                        break;
                    case A_WRTE:
                        output.append(new String(m.payload, StandardCharsets.UTF_8));
                        send(out, A_OKAY, localId, m.arg0, null);
                        break;
                    case A_CLSE:
                        if (remoteId >= 0) send(out, A_CLSE, localId, remoteId, null);
                        r.authorized = remoteId >= 0;
                        r.output = output.toString();
                        return r;
                    case A_AUTH:
                        r.error = "The phone did not accept Control's key — "
                                + "it is not paired (or pairing was revoked).";
                        return r;
                    default:
                        // CNXN repeats and anything else: ignore.
                        break;
                }
            }
            r.authorized = remoteId >= 0;
            r.output = output.toString();
            if (!r.authorized && r.error.isEmpty()) {
                r.error = "The phone never opened the shell service.";
            }
            return r;
        } catch (Exception e) {
            String msg = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            // A TLS failure here almost always means the phone does not
            // trust Control's key — say that in plain words, carrying
            // the exception's class and message verbatim so the next
            // field report is self-diagnosing.
            if (!r.connected) {
                r.error = handshakeError(e);
            } else {
                r.error = msg;
            }
            return r;
        } finally {
            if (conn != null) {
                conn.close();
            }
        }
    }

    /**
     * The stage-2 error for a handshake that never reached CNXN.
     * The exception's class and message ride along verbatim in every
     * case. A certificate-flavored fatal alert (the phone rejecting
     * Control's client certificate — bad/unknown certificate,
     * handshake_failure after its CertificateRequest, …) gets the
     * direct reading: the phone no longer trusts the key, pair again.
     */
    private static String handshakeError(Exception e) {
        String detail = e.getClass().getName() + ": "
                + (e.getMessage() == null ? "(no message)" : e.getMessage());
        if (detail.length() > 320) detail = detail.substring(0, 320);
        if (isCertAlert(e)) {
            return "The phone refused Control's key during the TLS handshake ("
                    + detail + "). The phone no longer trusts Control's key — "
                    + "pair again from Deep view (Enter pairing code), and the "
                    + "bridge start will use the fresh pairing.";
        }
        return "Could not connect over TLS (" + detail + "). If wireless "
                + "debugging is on, the usual cause is that Control's key "
                + "is not paired with this phone.";
    }

    /** True when the failure is the phone rejecting our certificate:
     *  a fatal alert in the certificate family, whether BC raised it
     *  locally (TlsFatalAlert) or the phone sent it
     *  (TlsFatalAlertReceived); the abrupt-close presentation of the
     *  same rejection (TLS completed on our side, then the phone
     *  hung up during the CNXN exchange — BoringSSL commonly just
     *  drops a connection whose client key it won't authorize, the
     *  alert racing our first write); or anything whose message
     *  says "certificate". A refused connection (nothing listening)
     *  is NOT this — that's the service being off, not a rejection. */
    private static boolean isCertAlert(Exception e) {
        if (e instanceof java.net.ConnectException) {
            return false;
        }
        if (e instanceof EOFException || e instanceof java.net.SocketException
                || e instanceof org.bouncycastle.tls.TlsNoCloseNotifyException) {
            return true;
        }
        int desc = -1;
        if (e instanceof TlsFatalAlert) {
            desc = ((TlsFatalAlert) e).getAlertDescription();
        } else if (e instanceof TlsFatalAlertReceived) {
            desc = ((TlsFatalAlertReceived) e).getAlertDescription();
        }
        if (desc >= 0) {
            switch (desc) {
                case AlertDescription.bad_certificate:
                case AlertDescription.unsupported_certificate:
                case AlertDescription.certificate_revoked:
                case AlertDescription.certificate_expired:
                case AlertDescription.certificate_unknown:
                case AlertDescription.certificate_required:
                case AlertDescription.unknown_ca:
                case AlertDescription.access_denied:
                case AlertDescription.decrypt_error:
                case AlertDescription.handshake_failure:
                    return true;
                default:
                    return false;
            }
        }
        String m = e.getMessage();
        return m != null && m.toLowerCase(java.util.Locale.US).contains("certificate");
    }

    // ---- wire framing ----

    private static final class Message {
        int command, arg0, arg1;
        byte[] payload = new byte[0];
    }

    private static void send(OutputStream out, int command, int arg0, int arg1,
                             byte[] payload) throws Exception {
        if (payload == null) payload = new byte[0];
        int sum = 0;
        for (byte b : payload) sum += b & 0xFF;
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

    private static Message read(InputStream in) throws Exception {
        byte[] header = new byte[24];
        readFully(in, header, 0, 24);
        Message m = new Message();
        m.command = getLe(header, 0);
        m.arg0 = getLe(header, 4);
        m.arg1 = getLe(header, 8);
        int len = getLe(header, 12);
        if (len < 0 || len > 16 * 1024 * 1024) {
            throw new Exception("Implausible ADB payload length " + len);
        }
        m.payload = new byte[len];
        if (len > 0) readFully(in, m.payload, 0, len);
        return m;
    }

    private static void readFully(InputStream in, byte[] buf, int off, int len)
            throws Exception {
        int got = 0;
        while (got < len) {
            int n = in.read(buf, off + got, len - got);
            if (n < 0) throw new EOFException("ADB stream closed");
            got += n;
        }
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

    private static String name(int command) {
        switch (command) {
            case A_CNXN: return "CNXN";
            case A_OPEN: return "OPEN";
            case A_OKAY: return "OKAY";
            case A_CLSE: return "CLSE";
            case A_WRTE: return "WRTE";
            case A_AUTH: return "AUTH";
            default: return "0x" + Integer.toHexString(command);
        }
    }
}
