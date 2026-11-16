package app.control.launcher;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import java.security.SecureRandom;

/**
 * The wallet's cipher core (v1.7): AES-256-GCM, one random 12-byte
 * IV per encryption, 128-bit auth tag. Pure JCE over a supplied
 * {@link SecretKey} — no Android types — so the harness proves the
 * exact shipping encrypt/decrypt logic with a JVM-generated key
 * while the app supplies an Android Keystore key at runtime (see
 * {@link WalletStore}).
 *
 * Cipher sourcing (v1.12 — corrected, second attempt): v1.9.2
 * assumed a Keystore key can ONLY be used through a cipher from
 * the "AndroidKeyStore" provider, and asked for that provider
 * first, falling back only on NoSuchProviderException. That
 * premise was WRONG. The documented Android pattern is the
 * no-provider cipher, which delegates Keystore keys via
 * Conscrypt; the v1.7 InvalidKeyException theory behind v1.9.2
 * was never observed on a device (the generic toast hid every
 * cause). What Adam DID observe on his Samsung A16 (Android 16)
 * after v1.9.2's honest-error change made failures visible:
 * Cipher.getInstance("AES/GCM/NoPadding", "AndroidKeyStore")
 * throws NoSuchAlgorithmException — the provider exists, but
 * does not expose that Cipher lookup on his device — and the
 * catch missed it because it handled only NoSuchProviderException.
 *
 * So this class no longer bets on one theory. Every encrypt and
 * decrypt attempts the FULL operation (getInstance + init +
 * doFinal) with each cipher source in order — the default
 * provider first, the AndroidKeyStore provider second — moving
 * to the next on ANY exception, and throws only if ALL fail.
 * Each attempt is independent (its own fresh IV on encrypt), so
 * a failed attempt emits nothing reusable. If all attempts fail,
 * the thrown exception's message names EVERY attempt's failure
 * (source + exception class + short message), kept under the
 * ~160 characters WalletActivity's error display shows — so a
 * residual failure (e.g. a key problem, which would fail under
 * BOTH providers at init) is diagnosable from the next report
 * instead of another guess.
 *
 * Wire form: IV ‖ ciphertext+tag, as raw bytes. Callers base64 it
 * for storage. Decryption of tampered bytes or under the wrong key
 * fails with an exception (GCM authentication) — it never yields
 * wrong plaintext. The wire form is unchanged since v1.7, so
 * anything already sealed stays readable.
 */
public final class WalletCrypto {

    private static final String TRANSFORMATION = "AES/GCM/NoPadding";
    private static final int IV_BYTES = 12;
    private static final int TAG_BITS = 128;
    /** Cap on each attempt's failure detail, keeping the
     *  all-failed message inside the UI's ~160-char display. */
    private static final int FAILURE_DETAIL_CHARS = 28;
    private static final int MESSAGE_CAP_CHARS = 160;

    private WalletCrypto() {}

    /** One source of Ciphers for the transformation. The shipping
     *  list is tried in order; the harness substitutes its own
     *  suppliers through the package-private overloads to force
     *  failures. This is a real seam in the shipping path — the
     *  public methods delegate to the same overloads with the
     *  shipping list — not a test-only branch. */
    interface CipherSupplier {
        /** Short name used in the all-failed message. */
        String name();
        Cipher get() throws Exception;
    }

    private static final CipherSupplier DEFAULT_SUPPLIER = new CipherSupplier() {
        @Override public String name() { return "default"; }
        @Override public Cipher get() throws Exception {
            return Cipher.getInstance(TRANSFORMATION);
        }
    };

    private static final CipherSupplier KEYSTORE_SUPPLIER = new CipherSupplier() {
        @Override public String name() { return "keystore"; }
        @Override public Cipher get() throws Exception {
            return Cipher.getInstance(TRANSFORMATION, "AndroidKeyStore");
        }
    };

    /** The shipping suppliers, in try order: the documented
     *  no-provider pattern first, the provider-named lookup
     *  second (it works on some devices). */
    private static final CipherSupplier[] SUPPLIERS =
            new CipherSupplier[] { DEFAULT_SUPPLIER, KEYSTORE_SUPPLIER };

    /** The shipping supplier list, in try order. Package-private
     *  so the harness can build failing-first lists around the
     *  exact shipping suppliers. */
    static CipherSupplier[] suppliers() {
        return SUPPLIERS.clone();
    }

    /** "SimpleName: short message" for one failed attempt, with
     *  the message flattened to one line and capped. */
    private static String failureShort(Exception e) {
        String name = e.getClass().getSimpleName();
        String msg = e.getMessage();
        if (msg == null) return name;
        msg = msg.replace('\n', ' ').replace('\r', ' ').trim();
        if (msg.isEmpty()) return name;
        if (msg.length() > FAILURE_DETAIL_CHARS) {
            msg = msg.substring(0, FAILURE_DETAIL_CHARS);
        }
        return name + ": " + msg;
    }

    public static byte[] encrypt(SecretKey key, byte[] plaintext) throws Exception {
        return encrypt(key, plaintext, SUPPLIERS);
    }

    /** The encrypt path with an explicit supplier list. The
     *  public method delegates here with the shipping list. */
    static byte[] encrypt(SecretKey key, byte[] plaintext,
                          CipherSupplier[] suppliers) throws Exception {
        StringBuilder failures = new StringBuilder();
        Exception last = null;
        for (CipherSupplier supplier : suppliers) {
            try {
                return encryptOnce(supplier, key, plaintext);
            } catch (Exception e) {
                last = e;
                if (failures.length() > 0) failures.append("; ");
                failures.append(supplier.name()).append('=').append(failureShort(e));
            }
        }
        throw allFailed(failures.toString(), last);
    }

    /** One encrypt attempt: fresh IV, then getInstance + init +
     *  doFinal. Nothing leaves this method on failure. */
    private static byte[] encryptOnce(CipherSupplier supplier, SecretKey key,
                                      byte[] plaintext) throws Exception {
        byte[] iv = new byte[IV_BYTES];
        new SecureRandom().nextBytes(iv);
        Cipher cipher = supplier.get();
        cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
        byte[] ct = cipher.doFinal(plaintext);
        byte[] out = new byte[IV_BYTES + ct.length];
        System.arraycopy(iv, 0, out, 0, IV_BYTES);
        System.arraycopy(ct, 0, out, IV_BYTES, ct.length);
        return out;
    }

    public static byte[] decrypt(SecretKey key, byte[] blob) throws Exception {
        return decrypt(key, blob, SUPPLIERS);
    }

    /** The decrypt path with an explicit supplier list. The
     *  public method delegates here with the shipping list. A
     *  too-short blob is rejected before any attempt, as before. */
    static byte[] decrypt(SecretKey key, byte[] blob,
                          CipherSupplier[] suppliers) throws Exception {
        if (blob == null || blob.length <= IV_BYTES) {
            throw new IllegalArgumentException("ciphertext too short");
        }
        byte[] iv = new byte[IV_BYTES];
        System.arraycopy(blob, 0, iv, 0, IV_BYTES);
        StringBuilder failures = new StringBuilder();
        Exception last = null;
        for (CipherSupplier supplier : suppliers) {
            try {
                Cipher cipher = supplier.get();
                cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
                return cipher.doFinal(blob, IV_BYTES, blob.length - IV_BYTES);
            } catch (Exception e) {
                last = e;
                if (failures.length() > 0) failures.append("; ");
                failures.append(supplier.name()).append('=').append(failureShort(e));
            }
        }
        throw allFailed(failures.toString(), last);
    }

    /** The all-attempts-failed exception: the message names every
     *  attempt's failure and stays inside the UI's display cap;
     *  the last failure rides as the cause for logcat. */
    private static Exception allFailed(String failures, Exception last) {
        String message = "No cipher worked: " + failures;
        if (message.length() > MESSAGE_CAP_CHARS) {
            message = message.substring(0, MESSAGE_CAP_CHARS);
        }
        return new Exception(message, last);
    }
}
