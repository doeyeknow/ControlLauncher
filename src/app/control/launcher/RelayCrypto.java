package app.control.launcher;

import java.security.SecureRandom;
import java.util.Base64;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * Channel encryption, Control Relay protocol §6 — the same scheme as
 * the launcher's profile export: AES-256-GCM with the 32-byte channel
 * key, a fresh random 12-byte nonce per message, and the posted blob
 * is nonce ‖ ciphertext ‖ 16-byte GCM tag, base64 (standard alphabet,
 * with padding). Pure Java (javax.crypto only) so the JVM harness
 * exercises the exact shipping code.
 */
public final class RelayCrypto {

    private RelayCrypto() {}

    /** Seal plaintext under a 32-byte channel key → base64 blob. */
    public static String seal(byte[] key, byte[] plaintext) {
        try {
            byte[] nonce = new byte[12];
            new SecureRandom().nextBytes(nonce);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"),
                    new GCMParameterSpec(128, nonce));
            byte[] ct = cipher.doFinal(plaintext);
            byte[] blob = new byte[nonce.length + ct.length];
            System.arraycopy(nonce, 0, blob, 0, nonce.length);
            System.arraycopy(ct, 0, blob, nonce.length, ct.length);
            return Base64.getEncoder().encodeToString(blob);
        } catch (Exception e) {
            throw new IllegalStateException("seal failed", e);
        }
    }

    /** Open a base64 blob under a 32-byte channel key. Throws
     *  (IllegalStateException) when the key is wrong or the blob was
     *  tampered with — GCM authentication failure is never silent. */
    public static byte[] open(byte[] key, String blobB64) {
        try {
            byte[] blob = Base64.getDecoder().decode(blobB64);
            if (blob.length < 12 + 16) throw new IllegalArgumentException("blob too short");
            byte[] nonce = new byte[12];
            System.arraycopy(blob, 0, nonce, 0, 12);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"),
                    new GCMParameterSpec(128, nonce));
            return cipher.doFinal(blob, 12, blob.length - 12);
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("open failed (wrong key or tampered blob)", e);
        }
    }
}
