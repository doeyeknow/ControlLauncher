package app.control.launcher;

import android.content.Context;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.SecureRandom;
import java.security.interfaces.RSAPublicKey;

/**
 * The real AOSP wireless-debugging pairing handshake (v1.2), from
 * packages/modules/adb (pairing_connection + pairing_auth +
 * tls_connection) and BoringSSL's spake25519:
 *
 *   1. TLS 1.3 with mutual certificates (Bouncy Castle provides the
 *      TLS stack Android's framework won't expose to apps; the peer
 *      certificate is accepted as-is, exactly like AOSP's pairing
 *      layer, which installs a verify callback returning 1).
 *   2. Export 64 bytes of TLS keying material with the label
 *      "adb-label\0" (9 bytes — BoringSSL passes sizeof of the C
 *      string, NUL included) and append them to the pairing code.
 *   3. SPAKE2 (BoringSSL spake25519, client = alice) over that
 *      password, packets framed [version=1][type][uint32 BE length].
 *   4. PeerInfo exchange encrypted with AES-128-GCM keyed from the
 *      SPAKE2 output: we send our RSA public key in ANDROID_PUBKEY
 *      form; the phone replies with its device GUID. A wrong code
 *      fails HERE (GCM tag), never silently.
 *
 * Stage outcomes are reported to the Listener by name; the pairing
 * code, the SPAKE2 secret, and all key material stay out of every
 * message — only stage names and error classes cross this boundary.
 */
public final class AdbPairing {

    public static final String STAGE_TLS = "Secure session established";
    public static final String STAGE_SPAKE = "SPAKE2 exchange";
    public static final String STAGE_IDENTITY = "Identity accepted";

    private static final int TYPE_SPAKE2_MSG = 0;
    private static final int TYPE_PEER_INFO = 1;
    private static final int MAX_PAYLOAD = 16384;
    private static final int PEER_INFO_SIZE = 8192;

    public interface Listener {
        void onStage(String stage, boolean ok, String detail);
    }

    public static final class Result {
        public boolean success;
        public String failedStage = "";
        public String error = "";
        public String deviceGuid = "";
    }

    private AdbPairing() {}

    /** Android entry point: pairs Control's stored ADB identity. */
    public static Result pair(Context ctx, InetAddress host, int port,
                              String code, Listener listener) {
        try {
            KeyPair kp = AdbKeyStore.keyPair(ctx);
            byte[] certDer = AdbKeyStore.certificate(ctx).getEncoded();
            return pair(kp, certDer, host, port, code, listener);
        } catch (Exception e) {
            Result r = new Result();
            r.failedStage = STAGE_TLS;
            r.error = "Control's ADB key could not be loaded: " + e.getClass().getSimpleName();
            if (listener != null) listener.onStage(STAGE_TLS, false, r.error);
            return r;
        }
    }

    /** Core handshake (no Android types — exercised off-device). */
    public static Result pair(KeyPair kp, byte[] certDer, InetAddress host, int port,
                              String code, Listener listener) {
        Result r = new Result();
        AdbTls.Connection conn = null;
        String stage = STAGE_TLS;
        try {
            // The shared BC TLS 1.3 stack (AdbTls) — the same client
            // the bridge-start connect path uses since v1.4.
            conn = AdbTls.connect(kp, certDer, host, port, 10000, 20000);
            // Captured inside notifyHandshakeComplete: BC only
            // exposes the TLS 1.3 exporter secret during that
            // callback (after connect() returns it is nulled).
            // "adb-label" + NUL: BoringSSL passes sizeof(kExportedKeyLabel).
            byte[] exported = conn.exportedKeyMaterial();
            if (exported == null || exported.length != 64) {
                throw new IOException("TLS exporter returned no key material");
            }
            emit(listener, STAGE_TLS, true, "TLS 1.3, mutual certificates");

            stage = STAGE_SPAKE;
            byte[] password = Spake2.concat(
                    code.getBytes(StandardCharsets.UTF_8), exported);
            Spake2 spake = new Spake2(true, password, new SecureRandom());
            InputStream in = conn.inputStream();
            OutputStream out = conn.outputStream();
            writePacket(out, TYPE_SPAKE2_MSG, spake.msg());
            byte[] theirMsg = readPacket(in, TYPE_SPAKE2_MSG);
            byte[] keyMaterial = spake.processMsg(theirMsg);
            emit(listener, STAGE_SPAKE, true, "shared secret derived from the code");

            stage = STAGE_IDENTITY;
            PairingCipher cipher = new PairingCipher(keyMaterial);
            byte[] info = new byte[PEER_INFO_SIZE];
            info[0] = 0; // ADB_RSA_PUB_KEY
            byte[] pubStr = AndroidPubkey.encode((RSAPublicKey) kp.getPublic())
                    .getBytes(StandardCharsets.US_ASCII);
            if (pubStr.length > PEER_INFO_SIZE - 1) {
                throw new IOException("public key string too long");
            }
            System.arraycopy(pubStr, 0, info, 1, pubStr.length);
            writePacket(out, TYPE_PEER_INFO, cipher.seal(info));
            byte[] theirInfo = cipher.open(readPacket(in, TYPE_PEER_INFO));
            if (theirInfo.length != PEER_INFO_SIZE) {
                throw new IOException("unexpected PeerInfo size " + theirInfo.length);
            }
            if (theirInfo[0] != 1) { // ADB_DEVICE_GUID
                throw new IOException("phone replied with PeerInfo type "
                        + (theirInfo[0] & 0xff) + ", not the device GUID");
            }
            r.deviceGuid = cString(theirInfo, 1);
            emit(listener, STAGE_IDENTITY, true,
                    "phone stored Control's key" + (r.deviceGuid.isEmpty()
                            ? "" : " (device " + r.deviceGuid + ")"));
            r.success = true;
            return r;
        } catch (Exception e) {
            r.failedStage = stage;
            r.error = plainError(stage, e);
            emit(listener, stage, false, r.error);
            return r;
        } finally {
            if (conn != null) conn.close();
        }
    }

    private static String plainError(String stage, Exception e) {
        String name = e.getClass().getSimpleName();
        String msg = e.getMessage();
        if (STAGE_IDENTITY.equals(stage)
                && (name.contains("AEADBadTag") || name.contains("BadTag")
                    || name.contains("InvalidCipherText") || name.contains("BadPadding")
                    // With a wrong code the phone fails to decrypt our
                    // PeerInfo and simply hangs up: the client sees an
                    // abrupt close at exactly this point.
                    || name.contains("NoCloseNotify")
                    || e instanceof java.io.EOFException)) {
            return "the phone could not decrypt our identity — almost always "
                    + "a mistyped or already-used pairing code. Open a fresh "
                    + "pairing code on the phone and try again.";
        }
        if (e instanceof java.net.SocketTimeoutException) {
            return "the phone stopped answering (" + name + ") — keep the "
                    + "pairing-code dialog open and try again.";
        }
        if (e instanceof java.net.ConnectException) {
            return "could not connect to the pairing service (" + name + ").";
        }
        String detail = (msg == null || msg.isEmpty()) ? name : name + ": " + msg;
        if (detail.length() > 180) detail = detail.substring(0, 180);
        return detail;
    }

    private static void emit(Listener l, String stage, boolean ok, String detail) {
        if (l != null) l.onStage(stage, ok, detail);
    }

    private static String cString(byte[] data, int off) {
        int end = off;
        while (end < data.length && data[end] != 0) end++;
        return new String(data, off, end - off, StandardCharsets.US_ASCII);
    }

    // ---- pairing packet framing (pairing_connection.cpp) ----

    private static void writePacket(OutputStream out, int type, byte[] payload)
            throws IOException {
        byte[] h = new byte[6];
        h[0] = 1; // kCurrentKeyHeaderVersion
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
        if ((h[0] & 0xff) != 1) {
            throw new IOException("pairing packet version " + (h[0] & 0xff));
        }
        int type = h[1] & 0xff;
        int len = ((h[2] & 0xff) << 24) | ((h[3] & 0xff) << 16)
                | ((h[4] & 0xff) << 8) | (h[5] & 0xff);
        if (len <= 0 || len > MAX_PAYLOAD) {
            throw new IOException("pairing packet length " + len);
        }
        if (type != expectedType) {
            throw new IOException("unexpected pairing packet type " + type);
        }
        return readFully(in, len);
    }

    private static byte[] readFully(InputStream in, int n) throws IOException {
        byte[] buf = new byte[n];
        int got = 0;
        while (got < n) {
            int r = in.read(buf, got, n - got);
            if (r < 0) throw new IOException("pairing stream closed early");
            got += r;
        }
        return buf;
    }

    // The Bouncy Castle TLS 1.3 client itself lives in AdbTls
    // since v1.4, shared verbatim with the connect path (AdbClient).
}
