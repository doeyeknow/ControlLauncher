package app.control.launcher;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

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
 * Root cause, proven 2026-11-17 (v1.40): every version since v1.7
 * initialized encryption with a caller-provided IV (a fresh random
 * IV wrapped in GCMParameterSpec). Adam's full-error dialog from
 * v1.39 — after a FRESH key had just been created — reported
 * "default=InvalidAlgorithmParameterException: Caller-provided IV
 * not permitted". A Keystore key generated with randomized
 * encryption required (see WalletStore) refuses caller-provided
 * IVs on encrypt: the Keystore must generate the IV itself. That
 * is why saving a card never worked on his phone, on any version,
 * with any key — and the v1.39 bad-key theory is disproven (the
 * fresh key failed identically). Encryption now initializes with
 * NO parameters and reads the IV back via cipher.getIV(); the
 * attempt fails honestly if the cipher yields no 12-byte IV.
 * Decryption is unchanged: it MUST supply the stored IV, which
 * the Keystore permits — the no-caller-IV rule applies to
 * encryption with randomized-encryption-required keys only. The
 * other two suppliers are expected dead ends for a Keystore key
 * (AndroidOpenSSL needs key bytes Keystore keys never expose;
 * the AndroidKeyStore provider does not expose this Cipher
 * lookup on his device) and stay as fallbacks; the default
 * supplier is the only viable path.
 *
 * So this class no longer bets on one theory. Every encrypt and
 * decrypt attempts the FULL operation (getInstance + init +
 * doFinal) with each cipher source in order — the default
 * provider first, the AndroidOpenSSL provider second (v1.39: it
 * costs nothing, and distinguishes provider-resolution failures
 * from key failures in the diagnostics), the AndroidKeyStore
 * provider third — moving to the next on ANY exception, and
 * throws only if ALL fail. Each attempt is independent (the
 * cipher generates its own fresh IV on encrypt), so a failed
 * attempt emits nothing reusable. If all attempts fail, the thrown
 * {@link AllFailedException}'s message names EVERY attempt's
 * failure (source + exception class + short message), and the
 * UI shows the complete text in a scrollable dialog (v1.39) —
 * so a residual failure (e.g. a key problem, which fails under
 * every provider at init) is diagnosable from the next report
 * instead of another guess. The exception type itself is the
 * save path's signal for the key-regeneration recovery (see
 * WalletKeyRecovery): a key-load failure throws something else
 * and never triggers regeneration.
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
    /** Cap on each attempt's failure detail. 120 chars keeps
     *  the per-attempt cause readable in the full-failure
     *  dialog (v1.39) without letting one verbose provider
     *  message drown the others. */
    private static final int FAILURE_DETAIL_CHARS = 120;
    /** Safety bound on the composite message — far above any
     *  realistic three-attempt total, so the dialog shows the
     *  complete text in practice. The old 160-char toast cap is
     *  gone: the toast no longer carries this message. */
    private static final int MESSAGE_CAP_CHARS = 2000;

    /** Thrown when every cipher supplier failed. The type is
     *  the save path's regeneration signal: only this failure
     *  shape means "the key entry itself may be unusable" (see
     *  WalletKeyRecovery). Key-load failures throw other
     *  exceptions and never trigger regeneration. */
    public static final class AllFailedException extends Exception {
        AllFailedException(String message, Throwable cause) {
            super(message, cause);
        }
    }

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

    private static final CipherSupplier OPENSSL_SUPPLIER = new CipherSupplier() {
        @Override public String name() { return "androidopenssl"; }
        @Override public Cipher get() throws Exception {
            return Cipher.getInstance(TRANSFORMATION, "AndroidOpenSSL");
        }
    };

    /** The shipping suppliers, in try order: the documented
     *  no-provider pattern first, the explicit Conscrypt
     *  provider second (v1.39), the provider-named lookup
     *  third (it works on some devices). */
    private static final CipherSupplier[] SUPPLIERS =
            new CipherSupplier[] { DEFAULT_SUPPLIER, OPENSSL_SUPPLIER,
                    KEYSTORE_SUPPLIER };

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

    /** One encrypt attempt: getInstance + init + doFinal. The
     *  init carries NO parameters (v1.40): a Keystore key with
     *  randomized encryption required refuses a caller-provided
     *  IV, so the cipher generates the IV itself and it is read
     *  back via getIV(). The IV must be exactly 12 bytes or the
     *  attempt fails honestly and the next supplier is tried.
     *  Nothing leaves this method on failure. */
    private static byte[] encryptOnce(CipherSupplier supplier, SecretKey key,
                                      byte[] plaintext) throws Exception {
        Cipher cipher = supplier.get();
        cipher.init(Cipher.ENCRYPT_MODE, key);
        byte[] iv = cipher.getIV();
        if (iv == null || iv.length != IV_BYTES) {
            throw new IllegalStateException("cipher did not generate a "
                    + IV_BYTES + "-byte IV");
        }
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
     *  attempt's failure; the last failure rides as the cause
     *  for logcat and for the full-failure dialog. */
    private static Exception allFailed(String failures, Exception last) {
        String message = "No cipher worked: " + failures;
        if (message.length() > MESSAGE_CAP_CHARS) {
            message = message.substring(0, MESSAGE_CAP_CHARS);
        }
        return new AllFailedException(message, last);
    }
}
