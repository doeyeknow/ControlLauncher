package app.control.launcher;

import java.security.SecureRandom;

/**
 * The gateway relay's authentication preamble (v1.10). Pure Java +
 * RelayKeys — the JVM harness exercises this exact code.
 *
 * The relay is NEVER an open proxy. Before any SOCKS byte flows,
 * the client must prove it holds a Control profile identity key:
 *
 *   1. Server (on accept) sends a challenge: magic "CGW1" + a fresh
 *      32-byte random nonce. The nonce is generated per connection
 *      and never reused.
 *   2. Client replies: its 32-byte public key + a 64-byte Ed25519
 *      signature over:
 *        "control-gateway/v1" || nonce || clientPub || gatewayPub
 *      The gateway's public key comes from its UDP announcement, so
 *      the signature is bound to THIS gateway — a captured auth
 *      cannot be replayed to a different gateway, and the fresh
 *      nonce means it cannot be replayed to this one either.
 *   3. Server verifies the signature against the presented public
 *      key. Failure (or a timeout, or a malformed reply) closes
 *      the connection silently.
 *
 * Trust model, stated plainly: authentication proves "holds a
 * Control profile identity key" — the same identity the relay
 * uses. It does NOT prove "is a known contact". Combined with the
 * relay binding to the mesh interface only, that is the v1 gate:
 * a stranger on the open internet cannot reach the relay at all,
 * and a device on the mesh still needs a Control identity to use
 * it. Restricting to contacts is future work, not v1 scope.
 */
public final class GatewayAuth {

    public static final byte[] MAGIC = {'C', 'G', 'W', '1'};
    public static final int NONCE_LEN = 32;
    public static final int CHALLENGE_LEN = 4 + NONCE_LEN;
    public static final int RESPONSE_LEN = 32 + 64;

    private static final byte[] LABEL =
            "control-gateway/v1".getBytes(java.nio.charset.StandardCharsets.US_ASCII);

    private GatewayAuth() {}

    /** A fresh per-connection nonce. */
    public static byte[] newNonce(SecureRandom rnd) {
        byte[] n = new byte[NONCE_LEN];
        rnd.nextBytes(n);
        return n;
    }

    /** Challenge bytes: magic + nonce. */
    public static byte[] challenge(byte[] nonce) {
        byte[] out = new byte[CHALLENGE_LEN];
        System.arraycopy(MAGIC, 0, out, 0, 4);
        System.arraycopy(nonce, 0, out, 4, NONCE_LEN);
        return out;
    }

    /** Extracts the nonce from challenge bytes, or null when the
     *  magic is wrong or the length is short. */
    public static byte[] parseChallenge(byte[] d, int len) {
        if (d == null || len < CHALLENGE_LEN) return null;
        for (int i = 0; i < 4; i++) {
            if (d[i] != MAGIC[i]) return null;
        }
        byte[] nonce = new byte[NONCE_LEN];
        System.arraycopy(d, 4, nonce, 0, NONCE_LEN);
        return nonce;
    }

    /** The signed message: label || nonce || clientPub || gatewayPub. */
    public static byte[] signedMessage(byte[] nonce, byte[] clientPub, byte[] gatewayPub) {
        byte[] m = new byte[LABEL.length + NONCE_LEN + 32 + 32];
        int p = 0;
        System.arraycopy(LABEL, 0, m, p, LABEL.length);
        p += LABEL.length;
        System.arraycopy(nonce, 0, m, p, NONCE_LEN);
        p += NONCE_LEN;
        System.arraycopy(clientPub, 0, m, p, 32);
        p += 32;
        System.arraycopy(gatewayPub, 0, m, p, 32);
        return m;
    }

    /** Client side: builds the 96-byte auth response. */
    public static byte[] buildResponse(byte[] seed, byte[] clientPub,
                                       byte[] gatewayPub, byte[] nonce) {
        byte[] sig = RelayKeys.sign(seed, signedMessage(nonce, clientPub, gatewayPub));
        byte[] out = new byte[RESPONSE_LEN];
        System.arraycopy(clientPub, 0, out, 0, 32);
        System.arraycopy(sig, 0, out, 32, 64);
        return out;
    }

    /** Server side: verifies a response against the nonce this
     *  connection issued and this gateway's public key. Returns
     *  the client's public key on success, null on any failure. */
    public static byte[] verifyResponse(byte[] nonce, byte[] gatewayPub,
                                        byte[] response, int len) {
        if (response == null || len < RESPONSE_LEN) return null;
        if (nonce == null || nonce.length != NONCE_LEN) return null;
        if (gatewayPub == null || gatewayPub.length != 32) return null;
        byte[] clientPub = new byte[32];
        byte[] sig = new byte[64];
        System.arraycopy(response, 0, clientPub, 0, 32);
        System.arraycopy(response, 32, sig, 0, 64);
        try {
            if (!RelayKeys.verify(clientPub,
                    signedMessage(nonce, clientPub, gatewayPub), sig)) {
                return null;
            }
        } catch (Exception e) {
            return null;
        }
        return clientPub;
    }
}
