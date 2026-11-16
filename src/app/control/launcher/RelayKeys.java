package app.control.launcher;

import org.bouncycastle.crypto.AsymmetricCipherKeyPair;
import org.bouncycastle.crypto.generators.Ed25519KeyPairGenerator;
import org.bouncycastle.crypto.params.Ed25519KeyGenerationParameters;
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters;
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters;
import org.bouncycastle.crypto.signers.Ed25519Signer;

import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * Relay identity + encoding helpers (Control Relay protocol §2–§3,
 * §5). Pure Java + Bouncy Castle's lightweight API — no Android, no
 * org.json — so the exact code that ships in the app also runs in the
 * off-device JVM harness against the live relay.
 *
 * Encodings, per protocol:
 *  - public keys: base64url (RFC 4648 §5) WITHOUT padding of the raw
 *    32 bytes — always 43 chars;
 *  - signatures: same encoding of the 64-byte signature — 86 chars;
 *  - fingerprints: hex(first 8 bytes of SHA-256(bytes)) — 16 chars.
 */
public final class RelayKeys {

    private RelayKeys() {}

    // ---- keypairs ----

    /** A fresh identity: returns {seed(32), pub(32)}. The seed IS the
     *  private key material stored in the profile. */
    public static byte[][] generate() {
        Ed25519KeyPairGenerator gen = new Ed25519KeyPairGenerator();
        gen.init(new Ed25519KeyGenerationParameters(new SecureRandom()));
        AsymmetricCipherKeyPair kp = gen.generateKeyPair();
        byte[] seed = ((Ed25519PrivateKeyParameters) kp.getPrivate()).getEncoded();
        byte[] pub = ((Ed25519PublicKeyParameters) kp.getPublic()).getEncoded();
        return new byte[][]{seed, pub};
    }

    /** Public key for a stored 32-byte seed. */
    public static byte[] publicFromSeed(byte[] seed) {
        Ed25519PrivateKeyParameters priv = new Ed25519PrivateKeyParameters(seed, 0);
        return priv.generatePublicKey().getEncoded();
    }

    public static byte[] sign(byte[] seed, byte[] message) {
        Ed25519Signer signer = new Ed25519Signer();
        signer.init(true, new Ed25519PrivateKeyParameters(seed, 0));
        signer.update(message, 0, message.length);
        try {
            return signer.generateSignature();
        } catch (Exception e) {
            throw new IllegalStateException("Ed25519 sign failed", e);
        }
    }

    public static boolean verify(byte[] pub, byte[] message, byte[] signature) {
        try {
            Ed25519Signer signer = new Ed25519Signer();
            signer.init(false, new Ed25519PublicKeyParameters(pub, 0));
            signer.update(message, 0, message.length);
            return signer.verifySignature(signature);
        } catch (Exception e) {
            return false;
        }
    }

    // ---- encodings ----

    public static String b64Url(byte[] bytes) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    public static byte[] b64UrlDecode(String s) {
        return Base64.getUrlDecoder().decode(s);
    }

    public static String b64Std(byte[] bytes) {
        return Base64.getEncoder().encodeToString(bytes);
    }

    public static byte[] b64StdDecode(String s) {
        return Base64.getDecoder().decode(s);
    }

    public static byte[] sha256(byte[] data) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(data);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    public static String sha256Hex(byte[] data) {
        return hex(sha256(data));
    }

    public static String hex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) sb.append(String.format("%02x", b));
        return sb.toString();
    }

    /** Protocol fingerprint (§5): hex of the first 8 bytes of
     *  SHA-256 — 16 lowercase hex chars. Used for channel keys AND,
     *  as a display convention, for identity keys. */
    public static String fingerprint(byte[] keyBytes) {
        byte[] d = sha256(keyBytes);
        StringBuilder sb = new StringBuilder(16);
        for (int i = 0; i < 8; i++) sb.append(String.format("%02x", d[i]));
        return sb.toString();
    }
}
