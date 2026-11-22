package app.control.launcher;

import java.math.BigInteger;
import java.security.interfaces.RSAPublicKey;
import java.util.Base64;

/**
 * Builds the ANDROID_PUBKEY blob the ADB daemon expects inside a
 * pairing PeerInfo (system/libcrypto_utils android_pubkey.c, checked
 * by daemon/auth.cpp: the first whitespace-separated token of a
 * stored key must base64-decode to exactly 524 bytes and pass
 * android_pubkey_decode).
 *
 * Layout (all little-endian) — must match AOSP android_pubkey_encode:
 *   uint32  modulus_size_words = 64
 *   uint32  n0inv   — n0inv = -1 / n[0] mod 2^32
 *   uint8[256] modulus, little-endian
 *   uint8[256] rr = (2^2048)^2 mod n = 2^4096 mod n, little-endian
 *   uint32  exponent
 *
 * On connect, adbd extracts the RSA key from the TLS client cert,
 * re-encodes it with the same algorithm, and looks it up against the
 * string stored at pairing. The modulus/exponent must therefore be
 * the exact key inside {@link AdbKeyStore}'s certificate.
 *
 * Wire form: base64(blob) + " " + comment (host adb uses user@host;
 * on-device self-pair uses control@localhost).
 */
final class AndroidPubkey {

    static final int BLOB_SIZE = 524;
    static final int MODULUS_SIZE = 256;
    static final int MODULUS_WORDS = 64; // 256 / 4

    private AndroidPubkey() {}

    static String encode(RSAPublicKey pub) {
        return encode(pub, "control@localhost");
    }

    static String encode(RSAPublicKey pub, String comment) {
        BigInteger n = pub.getModulus();
        BigInteger e = pub.getPublicExponent();
        if (n.bitLength() > MODULUS_SIZE * 8) {
            throw new IllegalArgumentException(
                    "RSA modulus too large for ANDROID_PUBKEY (" + n.bitLength() + " bits)");
        }
        byte[] blob = new byte[BLOB_SIZE];
        // modulus_size_words
        putU32(blob, 0, MODULUS_WORDS);
        // n0inv = -1 / n[0] mod 2^32  (AOSP: BN_mod_inverse then BN_sub from 2^32)
        BigInteger two32 = BigInteger.ONE.shiftLeft(32);
        BigInteger n0 = n.mod(two32);
        BigInteger inv = n0.modInverse(two32);
        long n0inv = two32.subtract(inv).mod(two32).longValue();
        putU32(blob, 4, n0inv);
        // modulus LE-padded to 256 bytes
        putLePadded(blob, 8, n, MODULUS_SIZE);
        // rr = (2^2048)^2 mod n  ==  2^4096 mod n
        BigInteger rr = BigInteger.ONE.shiftLeft(MODULUS_SIZE * 8).mod(n);
        rr = rr.multiply(rr).mod(n); // match AOSP BN_mod_sqr on 2^2048
        putLePadded(blob, 8 + MODULUS_SIZE, rr, MODULUS_SIZE);
        // exponent
        putU32(blob, 8 + MODULUS_SIZE + MODULUS_SIZE, e.longValue() & 0xffffffffL);
        String c = (comment == null || comment.isEmpty()) ? "control@localhost" : comment;
        return Base64.getEncoder().encodeToString(blob) + " " + c;
    }

    private static void putU32(byte[] out, int off, long v) {
        for (int i = 0; i < 4; i++) {
            out[off + i] = (byte) (v & 0xff);
            v >>= 8;
        }
    }

    private static void putLePadded(byte[] out, int off, BigInteger v, int len) {
        byte[] le = Ed25519.bigToLe(v, len);
        System.arraycopy(le, 0, out, off, len);
    }
}
