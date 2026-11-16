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
 * Layout (all little-endian):
 *   uint32  modulus_size_words = 64
 *   uint32  n0inv   — n0inv * (n mod 2^32) = -1 (mod 2^32)
 *   uint8[256] modulus, little-endian
 *   uint8[256] rr = R^2 mod n, R = 2^2048, little-endian
 *   uint32  exponent
 *
 * The encoded form returned here matches what the host adb client
 * sends (client/adb_wifi.cpp copies adb_auth_get_userkey()'s output
 * verbatim): base64(blob) + " " + a user@host comment.
 */
final class AndroidPubkey {

    static final int BLOB_SIZE = 524;

    private AndroidPubkey() {}

    static String encode(RSAPublicKey pub) {
        BigInteger n = pub.getModulus();
        BigInteger e = pub.getPublicExponent();
        byte[] blob = new byte[BLOB_SIZE];
        putU32(blob, 0, 64);
        BigInteger two32 = BigInteger.ONE.shiftLeft(32);
        long n0 = n.mod(two32).longValue();
        long inv = BigInteger.valueOf(n0).modInverse(two32).longValue();
        long n0inv = (two32.longValue() - inv) & 0xffffffffL;
        putU32(blob, 4, n0inv);
        putLePadded(blob, 8, n, 256);
        BigInteger rr = BigInteger.ONE.shiftLeft(4096).mod(n); // (2^2048)^2 mod n
        putLePadded(blob, 264, rr, 256);
        putU32(blob, 520, e.longValue() & 0xffffffffL);
        return Base64.getEncoder().encodeToString(blob) + " control@phone";
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
