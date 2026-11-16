package app.control.launcher;

import android.util.Base64;

import org.json.JSONObject;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * Encrypted profile envelopes. There is no plaintext export: a profile
 * leaves the phone only as one of these JSON envelopes.
 *
 * Two flavours:
 *  - "passphrase": key = PBKDF2-HMAC-SHA256(passphrase, random salt,
 *    200k iterations). Import asks for the passphrase.
 *  - "key": key = 32 random bytes generated at export. The QR code (or
 *    NFC tag) carries {"k": key, "h": sha256(ciphertext)}; the envelope
 *    file travels by any channel, and import verifies the hash before
 *    decrypting — a swapped file simply fails.
 *
 * Plaintext is gzip-compressed before encryption (profiles shrink a lot,
 * which is what makes small profiles fit in a single QR).
 */
public final class ProfileCrypto {
    public static final String FORMAT = "control-profile";
    private static final int PBKDF_ITERATIONS = 200_000;

    private ProfileCrypto() {}

    public static class CryptoException extends Exception {
        CryptoException(String msg) { super(msg); }
    }

    public static JSONObject encryptWithPassphrase(String profileJson, char[] passphrase)
            throws Exception {
        byte[] salt = random(16);
        SecretKey key = pbkdf(passphrase, salt);
        return encrypt(profileJson, key, "passphrase", salt);
    }

    /** Returns {envelope, key} — the caller shows the key in a QR. */
    public static Object[] encryptWithRandomKey(String profileJson) throws Exception {
        byte[] keyBytes = random(32);
        SecretKey key = new SecretKeySpec(keyBytes, "AES");
        JSONObject env = encrypt(profileJson, key, "key", null);
        return new Object[]{env, keyBytes};
    }

    private static JSONObject encrypt(String profileJson, SecretKey key, String mode,
                                      byte[] salt) throws Exception {
        byte[] iv = random(12);
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(128, iv));
        byte[] ct = cipher.doFinal(gzip(profileJson.getBytes(StandardCharsets.UTF_8)));
        JSONObject env = new JSONObject();
        env.put("format", FORMAT);
        env.put("version", Profile.CURRENT_VERSION);
        env.put("mode", mode);
        if (salt != null) {
            env.put("kdf", "PBKDF2WithHmacSHA256");
            env.put("iterations", PBKDF_ITERATIONS);
            env.put("salt", Base64.encodeToString(salt, Base64.NO_WRAP));
        }
        env.put("iv", Base64.encodeToString(iv, Base64.NO_WRAP));
        env.put("ciphertext", Base64.encodeToString(ct, Base64.NO_WRAP));
        return env;
    }

    public static String decryptWithPassphrase(JSONObject env, char[] passphrase)
            throws Exception {
        byte[] salt = Base64.decode(env.getString("salt"), Base64.DEFAULT);
        int iters = env.optInt("iterations", PBKDF_ITERATIONS);
        SecretKey key = pbkdf(passphrase, salt, iters);
        return decrypt(env, key);
    }

    public static String decryptWithKey(JSONObject env, byte[] keyBytes) throws Exception {
        return decrypt(env, new SecretKeySpec(keyBytes, "AES"));
    }

    private static String decrypt(JSONObject env, SecretKey key) throws Exception {
        byte[] iv = Base64.decode(env.getString("iv"), Base64.DEFAULT);
        byte[] ct = Base64.decode(env.getString("ciphertext"), Base64.DEFAULT);
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(128, iv));
        byte[] plain = cipher.doFinal(ct); // throws on wrong key/tampering (GCM tag)
        return new String(gunzip(plain), StandardCharsets.UTF_8);
    }

    public static boolean isEnvelope(JSONObject o) {
        return o != null && FORMAT.equals(o.optString("format"));
    }

    public static String ciphertextHash(JSONObject env) throws Exception {
        byte[] ct = Base64.decode(env.getString("ciphertext"), Base64.DEFAULT);
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        byte[] digest = md.digest(ct);
        StringBuilder sb = new StringBuilder();
        for (byte b : digest) sb.append(String.format("%02x", b));
        return sb.toString();
    }

    /** The QR/NFC payload for key-mode transfers. */
    public static String keyPayload(byte[] keyBytes, String ctHash) throws Exception {
        JSONObject o = new JSONObject();
        o.put("app", "control");
        o.put("k", Base64.encodeToString(keyBytes, Base64.NO_WRAP));
        o.put("h", ctHash);
        return o.toString();
    }

    /**
     * For small profiles: a single QR carrying key + IV + ciphertext, so
     * the scan alone completes the transfer. Returns null when it doesn't
     * fit (then the file + key-QR pattern is used instead).
     */
    public static String qrFullPayload(JSONObject env, byte[] keyBytes) {
        try {
            JSONObject o = new JSONObject();
            o.put("f", "control-qr");
            o.put("k", Base64.encodeToString(keyBytes, Base64.NO_WRAP));
            o.put("iv", env.getString("iv"));
            o.put("ct", env.getString("ciphertext"));
            String s = o.toString();
            // QR byte-mode at medium ECC holds ~2,300 chars safely.
            return s.length() <= 2200 ? s : null;
        } catch (Exception e) {
            return null;
        }
    }

    public static boolean isQrFullPayload(JSONObject o) {
        return o != null && "control-qr".equals(o.optString("f"));
    }

    public static String decryptQrPayload(JSONObject p) throws Exception {
        byte[] keyBytes = Base64.decode(p.getString("k"), Base64.DEFAULT);
        byte[] iv = Base64.decode(p.getString("iv"), Base64.DEFAULT);
        byte[] ct = Base64.decode(p.getString("ct"), Base64.DEFAULT);
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(keyBytes, "AES"),
                new GCMParameterSpec(128, iv));
        return new String(gunzip(cipher.doFinal(ct)), StandardCharsets.UTF_8);
    }

    public static boolean isKeyPayload(JSONObject o) {
        return o != null && "control".equals(o.optString("app")) && o.has("k") && o.has("h");
    }

    private static SecretKey pbkdf(char[] passphrase, byte[] salt) throws Exception {
        return pbkdf(passphrase, salt, PBKDF_ITERATIONS);
    }

    private static SecretKey pbkdf(char[] passphrase, byte[] salt, int iterations)
            throws Exception {
        SecretKeyFactory f = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256");
        return new SecretKeySpec(
                f.generateSecret(new PBEKeySpec(passphrase, salt, iterations, 256))
                        .getEncoded(), "AES");
    }

    private static byte[] random(int n) {
        byte[] b = new byte[n];
        new SecureRandom().nextBytes(b);
        return b;
    }

    private static byte[] gzip(byte[] data) throws Exception {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (GZIPOutputStream g = new GZIPOutputStream(bos)) {
            g.write(data);
        }
        return bos.toByteArray();
    }

    private static byte[] gunzip(byte[] data) throws Exception {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (GZIPInputStream g = new GZIPInputStream(new ByteArrayInputStream(data))) {
            byte[] buf = new byte[4096];
            int n;
            while ((n = g.read(buf)) > 0) bos.write(buf, 0, n);
        }
        return bos.toByteArray();
    }
}
