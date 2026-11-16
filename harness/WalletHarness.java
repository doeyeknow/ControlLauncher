package app.control.launcher;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;

/**
 * Off-device verification for the v1.7 wallet, exercising the exact
 * shipping pure-Java code (WalletCards + WalletCrypto):
 *
 * Legs:
 *   1. LUHN: valid numbers pass (Visa/Mastercard/Amex test numbers,
 *      with spaces/dashes tolerated); bad checksums, wrong lengths,
 *      non-digits, and empty input fail.
 *   2. NETWORK: detection from leading digits incl. the 2-series
 *      Mastercard range; unknown prefixes fall back to Other.
 *   3. DISPLAY: masked form, last4, grouped form (Amex 4-6-5 vs
 *      groups of 4), expiry validation incl. the current-month
 *      rule and the 30-year sanity cap.
 *   4. AUDIT: the audit-line builder's exact output, and that no
 *      full number can appear in it — the builder takes nickname
 *      + last4 only, so a PAN has no parameter to travel through.
 *   5. CIPHER: AES-256-GCM round-trip through the shipping core
 *      with a JVM-generated key (the Android Keystore key is the
 *      same SecretKey shape at runtime); random IVs; tampered
 *      ciphertext and wrong-key decryption fail instead of
 *      yielding wrong plaintext; short blobs rejected.
 *   6. CIPHER FALLBACK (v1.12): the shipping supplier list is
 *      default-first, keystore-second; a failing first supplier
 *      (lookup failure AND init failure) falls through to the
 *      next and the operation succeeds; a first success stops
 *      the attempts (the second supplier is never invoked); if
 *      every supplier fails, the thrown message names every
 *      attempt and fits the UI's ~160-char display. The v1.9.2
 *      premise (Keystore keys need the AndroidKeyStore provider)
 *      was wrong — Adam's A16 throws NoSuchAlgorithmException
 *      for that lookup — so the core now tries the full
 *      operation with each source in order instead of betting
 *      on one. The harness still cannot prove which source
 *      works with a real Keystore key on a real phone.
 *
 * What this harness cannot prove: the Android Keystore key itself
 * (needs a device), the BiometricPrompt/device-credential gate
 * (needs a device), the Pay handoff (needs Google Pay / Samsung
 * Wallet installed), or anything on a real phone. The keystore
 * and credential code paths fail closed — a keystore error means
 * no card is stored or shown, and a failed gate means no reveal.
 *
 * Run (from ~/workspace/control-launcher):
 *   javac --release 17 -cp $ANDROID_JAR:libs/zxing-core.jar:\
 *     libs/bcprov-jdk18on.jar:libs/bctls-jdk18on.jar:libs/bcutil-jdk18on.jar \
 *     -sourcepath src -d harness/classes harness/WalletHarness.java
 *   java -cp harness/classes app.control.launcher.WalletHarness
 */
public final class WalletHarness {

    private static int passed = 0;
    private static int failed = 0;

    private static void check(String name, boolean ok, String detail) {
        if (ok) {
            passed++;
            System.out.println("PASS  " + name);
        } else {
            failed++;
            System.out.println("FAIL  " + name + "  -- " + detail);
        }
    }

    /** A supplier that always fails with the given exception. */
    private static WalletCrypto.CipherSupplier failing(final String name,
                                                       final Exception toThrow) {
        return new WalletCrypto.CipherSupplier() {
            @Override public String name() { return name; }
            @Override public Cipher get() throws Exception { throw toThrow; }
        };
    }

    public static void main(String[] args) throws Exception {
        // ---- 1. Luhn ----
        check("luhn: valid Visa test number",
                WalletCards.luhnValid("4242424242424242"), "");
        check("luhn: valid Mastercard test number",
                WalletCards.luhnValid("5555555555554444"), "");
        check("luhn: valid Amex test number",
                WalletCards.luhnValid("378282246310005"), "");
        check("luhn: spaces and dashes tolerated",
                WalletCards.luhnValid(WalletCards.digitsOnly("4242 4242-4242 4242")), "");
        check("luhn: bad checksum rejected",
                !WalletCards.luhnValid("4242424242424241"), "");
        check("luhn: too short rejected",
                !WalletCards.luhnValid("424242424242"), "");
        check("luhn: too long rejected",
                !WalletCards.luhnValid("42424242424242424242"), "");
        check("luhn: non-digits rejected",
                !WalletCards.luhnValid(WalletCards.digitsOnly("4242x424242424242")), "");
        check("luhn: empty rejected", !WalletCards.luhnValid(""), "");
        check("luhn: null rejected", !WalletCards.luhnValid(null), "");

        // ---- 2. Network ----
        check("network: Visa", "Visa".equals(WalletCards.detectNetwork("4242424242424242")), "");
        check("network: Mastercard 5-series",
                "Mastercard".equals(WalletCards.detectNetwork("5555555555554444")), "");
        check("network: Mastercard 2-series",
                "Mastercard".equals(WalletCards.detectNetwork("2223000044445555")), "");
        check("network: 2721 is not Mastercard",
                !"Mastercard".equals(WalletCards.detectNetwork("2721999999999999")),
                WalletCards.detectNetwork("2721999999999999"));
        check("network: Amex 34", "Amex".equals(WalletCards.detectNetwork("340000000000009")), "");
        check("network: Amex 37", "Amex".equals(WalletCards.detectNetwork("378282246310005")), "");
        check("network: 35 is not Amex",
                !"Amex".equals(WalletCards.detectNetwork("3500000000000000")), "");
        check("network: Discover", "Discover".equals(WalletCards.detectNetwork("6011000000000004")), "");
        check("network: unknown falls back to Other",
                "Other".equals(WalletCards.detectNetwork("9000000000000001")), "");
        check("network: empty falls back to Other",
                "Other".equals(WalletCards.detectNetwork("")), "");

        // ---- 3. Display + expiry ----
        check("masked form", "•••• 4242".equals(WalletCards.masked("4242424242424242")),
                WalletCards.masked("4242424242424242"));
        check("masked empty", "••••".equals(WalletCards.masked("")), "");
        check("last4", "4242".equals(WalletCards.last4("4242424242424242")), "");
        check("last4 short", "".equals(WalletCards.last4("123")), "");
        check("grouped Visa", "4242 4242 4242 4242".equals(
                WalletCards.grouped("4242424242424242")), WalletCards.grouped("4242424242424242"));
        check("grouped Amex 4-6-5", "3782 822463 10005".equals(
                WalletCards.grouped("378282246310005")), WalletCards.grouped("378282246310005"));
        check("expiry: current month valid",
                WalletCards.expiryValid(6, 2027, 2027, 6), "");
        check("expiry: past month same year invalid",
                !WalletCards.expiryValid(5, 2027, 2027, 6), "");
        check("expiry: past year invalid",
                !WalletCards.expiryValid(12, 2026, 2027, 6), "");
        check("expiry: future valid",
                WalletCards.expiryValid(1, 2028, 2027, 6), "");
        check("expiry: month 0 invalid", !WalletCards.expiryValid(0, 2028, 2027, 6), "");
        check("expiry: month 13 invalid", !WalletCards.expiryValid(13, 2028, 2027, 6), "");
        check("expiry: year beyond +30 invalid",
                !WalletCards.expiryValid(1, 2058, 2027, 6), "");
        check("expiry display", "06/27".equals(WalletCards.expiryDisplay(6, 2027)),
                WalletCards.expiryDisplay(6, 2027));

        // ---- 4. Audit ----
        String line = WalletCards.auditLine("added", "Everyday", "4242");
        check("audit: exact line",
                "Wallet card added: Everyday •••• 4242, origin UI".equals(line), line);
        String payLine = WalletCards.auditLine("pay pressed", "", "");
        check("audit: pay line has no card facts",
                "Wallet card pay pressed, origin UI".equals(payLine), payLine);
        String pan = "4242424242424242";
        check("audit: no full number can appear",
                !line.contains(pan) && !line.contains("42424242"), line);
        check("audit: only last4 of the number appears",
                line.contains("4242") && !line.replace("•••• 4242", "").contains("4242"), line);

        // ---- 5. Cipher ----
        KeyGenerator gen = KeyGenerator.getInstance("AES");
        gen.init(256);
        SecretKey key = gen.generateKey();
        SecretKey otherKey = gen.generateKey();
        byte[] plain = "4242424242424242".getBytes(StandardCharsets.UTF_8);
        byte[] blob = WalletCrypto.encrypt(key, plain);
        check("cipher: blob is IV + ct + tag",
                blob.length == 12 + plain.length + 16, "len=" + blob.length);
        check("cipher: round-trip",
                Arrays.equals(plain, WalletCrypto.decrypt(key, blob)), "");
        byte[] blob2 = WalletCrypto.encrypt(key, plain);
        check("cipher: random IV, encryptions differ",
                !Arrays.equals(blob, blob2), "");
        check("cipher: second blob also decrypts",
                Arrays.equals(plain, WalletCrypto.decrypt(key, blob2)), "");
        byte[] tampered = blob.clone();
        tampered[tampered.length - 1] ^= 0x01;
        boolean tamperFailed = false;
        try {
            WalletCrypto.decrypt(key, tampered);
        } catch (Exception e) {
            tamperFailed = true;
        }
        check("cipher: tampered ciphertext rejected", tamperFailed, "");
        boolean wrongKeyFailed = false;
        try {
            WalletCrypto.decrypt(otherKey, blob);
        } catch (Exception e) {
            wrongKeyFailed = true;
        }
        check("cipher: wrong key rejected", wrongKeyFailed, "");
        boolean shortFailed = false;
        try {
            WalletCrypto.decrypt(key, new byte[8]);
        } catch (Exception e) {
            shortFailed = true;
        }
        check("cipher: short blob rejected", shortFailed, "");
        check("cipher: empty plaintext round-trips",
                WalletCrypto.decrypt(key, WalletCrypto.encrypt(key, new byte[0])).length == 0, "");

        // ---- 6. Cipher fallback (v1.12) ----
        WalletCrypto.CipherSupplier[] shipping = WalletCrypto.suppliers();
        check("fallback: shipping list is default first, keystore second",
                shipping.length == 2 && "default".equals(shipping[0].name())
                        && "keystore".equals(shipping[1].name()), "");
        // A supplier that fails exactly the way Adam's A16 fails
        // the AndroidKeyStore lookup: provider present, Cipher
        // lookup not exposed.
        WalletCrypto.CipherSupplier a16 = failing("keystore",
                new NoSuchAlgorithmException("No such algorithm: AES/GCM/NoPadding"));
        WalletCrypto.CipherSupplier[] failingFirst =
                new WalletCrypto.CipherSupplier[shipping.length + 1];
        failingFirst[0] = a16;
        System.arraycopy(shipping, 0, failingFirst, 1, shipping.length);
        byte[] fbBlob = WalletCrypto.encrypt(key, plain, failingFirst);
        check("fallback: lookup failure falls through, encrypt succeeds",
                fbBlob.length == 12 + plain.length + 16, "len=" + fbBlob.length);
        check("fallback: decrypt with failing-first list succeeds",
                Arrays.equals(plain, WalletCrypto.decrypt(key, fbBlob, failingFirst)), "");
        // A supplier whose cipher exists but cannot init for GCM:
        // fallback must cover init failures, not just lookups.
        WalletCrypto.CipherSupplier wrongTransform = new WalletCrypto.CipherSupplier() {
            @Override public String name() { return "ecb"; }
            @Override public Cipher get() throws Exception {
                return Cipher.getInstance("AES/ECB/PKCS5Padding");
            }
        };
        WalletCrypto.CipherSupplier[] initFails =
                new WalletCrypto.CipherSupplier[] { wrongTransform, shipping[0] };
        check("fallback: init failure falls through, encrypt succeeds",
                Arrays.equals(plain, WalletCrypto.decrypt(key,
                        WalletCrypto.encrypt(key, plain, initFails))), "");
        // A first success stops the attempts: the second supplier
        // is never invoked (on the JVM the default supplier wins,
        // so the keystore lookup never runs).
        final int[] calls = new int[2];
        WalletCrypto.CipherSupplier[] counting = new WalletCrypto.CipherSupplier[2];
        for (int i = 0; i < 2; i++) {
            final int idx = i;
            final WalletCrypto.CipherSupplier inner = shipping[i];
            counting[i] = new WalletCrypto.CipherSupplier() {
                @Override public String name() { return inner.name(); }
                @Override public Cipher get() throws Exception {
                    calls[idx]++;
                    return inner.get();
                }
            };
        }
        WalletCrypto.encrypt(key, plain, counting);
        check("fallback: first success stops the attempts",
                calls[0] == 1 && calls[1] == 0, "calls=" + calls[0] + "," + calls[1]);
        // All suppliers fail: the message names EVERY attempt and
        // fits the UI display cap, for both encrypt and decrypt.
        WalletCrypto.CipherSupplier failDefault = failing("default",
                new NoSuchAlgorithmException("No such algorithm: AES/GCM/NoPadding"));
        WalletCrypto.CipherSupplier failKeystore = failing("keystore",
                new InvalidKeyException("keystore key rejected by this provider"));
        WalletCrypto.CipherSupplier[] allFail =
                new WalletCrypto.CipherSupplier[] { failDefault, failKeystore };
        String encMsg = null;
        try {
            WalletCrypto.encrypt(key, plain, allFail);
        } catch (Exception e) {
            encMsg = e.getMessage();
        }
        check("all-fail: encrypt names every attempt",
                encMsg != null && encMsg.startsWith("No cipher worked: ")
                        && encMsg.contains("default=NoSuchAlgorithmException")
                        && encMsg.contains("keystore=InvalidKeyException"),
                String.valueOf(encMsg));
        check("all-fail: encrypt message fits the UI cap",
                encMsg != null && encMsg.length() <= 160,
                "len=" + (encMsg == null ? "null" : encMsg.length()));
        String decMsg = null;
        try {
            WalletCrypto.decrypt(key, blob, allFail);
        } catch (Exception e) {
            decMsg = e.getMessage();
        }
        check("all-fail: decrypt names every attempt",
                decMsg != null && decMsg.contains("default=NoSuchAlgorithmException")
                        && decMsg.contains("keystore=InvalidKeyException"),
                String.valueOf(decMsg));
        // A failure with no message still yields a readable entry.
        WalletCrypto.CipherSupplier noMessage = failing("default", new InvalidKeyException());
        String nullMsg = null;
        try {
            WalletCrypto.encrypt(key, plain,
                    new WalletCrypto.CipherSupplier[] { noMessage });
        } catch (Exception e) {
            nullMsg = e.getMessage();
        }
        check("all-fail: null exception message handled",
                "No cipher worked: default=InvalidKeyException".equals(nullMsg),
                String.valueOf(nullMsg));
        // A tampered blob fails under the default supplier at
        // doFinal and under the keystore supplier at lookup (on
        // the JVM the provider does not exist), so the composite
        // message names both attempts — and still throws instead
        // of yielding wrong plaintext.
        String tamperMsg = null;
        try {
            WalletCrypto.decrypt(key, tampered);
        } catch (Exception e) {
            tamperMsg = e.getMessage();
        }
        check("all-fail: tampered blob names both attempts",
                tamperMsg != null && tamperMsg.contains("default=AEADBadTagException")
                        && tamperMsg.contains("keystore=NoSuchProviderException"),
                String.valueOf(tamperMsg));

        System.out.println("----");
        System.out.println(passed + " passed, " + failed + " failed");
        if (failed > 0) System.exit(1);
    }
}
