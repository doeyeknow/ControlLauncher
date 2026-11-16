package app.control.launcher;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * The PeerInfo cipher from AOSP's pairing_auth/aes_128_gcm.cpp:
 *
 *   key   = HKDF-SHA256(ikm = 64-byte SPAKE2 key material,
 *                       salt = none, info = "adb pairing_auth aes-128-gcm key",
 *                       L = 16)
 *   nonce = 12 bytes: uint64 sequence number in native (little-endian)
 *           order in the first 8 bytes, rest zero; separate encrypt /
 *           decrypt counters, both starting at 0
 *   AES-128-GCM with the 16-byte tag appended (BoringSSL EVP_AEAD
 *   default), so ciphertext = plaintext + 16 bytes.
 */
final class PairingCipher {

    private static final byte[] HKDF_INFO =
            "adb pairing_auth aes-128-gcm key".getBytes(StandardCharsets.US_ASCII);

    private final SecretKeySpec key;
    private long encSeq;
    private long decSeq;

    PairingCipher(byte[] spake2KeyMaterial) throws Exception {
        key = new SecretKeySpec(hkdfSha256(spake2KeyMaterial), "AES");
    }

    /** RFC 5869 HKDF-SHA256 with an absent salt (BoringSSL passes
     *  salt=nullptr, len=0, which HKDF defines as HashLen zeros). */
    static byte[] hkdfSha256(byte[] ikm) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(new byte[32], "HmacSHA256"));
        byte[] prk = mac.doFinal(ikm);
        mac.init(new SecretKeySpec(prk, "HmacSHA256"));
        mac.update(HKDF_INFO);
        mac.update((byte) 0x01);
        byte[] okm = mac.doFinal();
        return Arrays.copyOf(okm, 16);
    }

    byte[] seal(byte[] plain) throws Exception {
        Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
        c.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(128, nonce(encSeq)));
        byte[] out = c.doFinal(plain);
        encSeq++;
        return out;
    }

    /** Throws (AEADBadTagException) when the key is wrong — in the
     *  pairing flow that IS the wrong-code signal. */
    byte[] open(byte[] sealed) throws Exception {
        Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
        c.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(128, nonce(decSeq)));
        byte[] out = c.doFinal(sealed);
        decSeq++;
        return out;
    }

    private static byte[] nonce(long seq) {
        byte[] n = new byte[12];
        for (int i = 0; i < 8; i++) {
            n[i] = (byte) (seq & 0xff);
            seq >>= 8;
        }
        return n;
    }
}
