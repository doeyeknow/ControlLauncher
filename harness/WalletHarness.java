package app.control.launcher;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.security.NoSuchProviderException;
import java.security.SecureRandom;
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
 *   6. CIPHER FALLBACK (v1.12, extended v1.39): the shipping
 *      supplier list is default-first, androidopenssl-second,
 *      keystore-third; a failing first supplier (lookup failure
 *      AND init failure) falls through to the next and the
 *      operation succeeds; a first success stops the attempts
 *      (later suppliers are never invoked); if every supplier
 *      fails, the thrown AllFailedException's message names
 *      every attempt in full (the old 160-char toast cap is
 *      gone — the UI shows the complete text in a scrollable
 *      dialog). The v1.9.2 premise (Keystore keys need the
 *      AndroidKeyStore provider) was wrong — Adam's A16 throws
 *      NoSuchAlgorithmException for that lookup — so the core
 *      now tries the full operation with each source in order
 *      instead of betting on one. The harness still cannot
 *      prove which source works with a real Keystore key on a
 *      real phone.
 *   7. SUPPLIER ORDER (v1.39): each of the three positions can
 *      win when the earlier ones fail, and the all-failed
 *      exception carries the AllFailedException type the save
 *      path keys regeneration on.
 *   8. KEY RECOVERY (v1.39): the regeneration decision — silent
 *      retry only for an all-cipher failure with a provably
 *      empty vault; ask when cards exist or the count is
 *      unknown; never twice, never for other failures, never
 *      on the decrypt path (the rule's caller). The confirm
 *      copy names the consequence plainly.
 *   9. KEYSTORE-GENERATED IV (v1.40): encryption initializes
 *      with NO parameters and reads the IV back via getIV()
 *      (a Keystore key with randomized encryption required
 *      refuses caller-provided IVs — Adam's v1.39 error proved
 *      it). The no-IV-init path round-trips; the provider's
 *      IVs differ between encryptions; the wire form still
 *      starts with the 12-byte IV (tampering it breaks
 *      decryption); a blob sealed the OLD way (caller-provided
 *      IV) still decrypts, so the wire form is unchanged; and
 *      the all-failed message still names all three attempts.
 *      The harness proves this path with a JVM software key;
 *      that a real Keystore key accepts no-parameter init is
 *      proven on-device only.
 *
 * What this harness cannot prove: the Android Keystore key itself
 * (needs a device), key regeneration (delete + generate are
 * Android-only — the decision rule is pinned here, the calls
 * are proven on-device), the BiometricPrompt/device-credential gate
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

    /** A supplier that returns a working GCM cipher under the
     *  given name — stands in for a provider that resolves on a
     *  device, so the order legs can pin which position wins. */
    private static WalletCrypto.CipherSupplier working(final String name) {
        return new WalletCrypto.CipherSupplier() {
            @Override public String name() { return name; }
            @Override public Cipher get() throws Exception {
                return Cipher.getInstance("AES/GCM/NoPadding");
            }
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

        // ---- 6. Cipher fallback (v1.12, extended v1.39) ----
        WalletCrypto.CipherSupplier[] shipping = WalletCrypto.suppliers();
        check("fallback: shipping list is default, androidopenssl, keystore",
                shipping.length == 3 && "default".equals(shipping[0].name())
                        && "androidopenssl".equals(shipping[1].name())
                        && "keystore".equals(shipping[2].name()), "");
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
        // A first success stops the attempts: the later suppliers
        // are never invoked (on the JVM the default supplier wins,
        // so the other lookups never run).
        final int[] calls = new int[3];
        WalletCrypto.CipherSupplier[] counting = new WalletCrypto.CipherSupplier[3];
        for (int i = 0; i < 3; i++) {
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
                calls[0] == 1 && calls[1] == 0 && calls[2] == 0,
                "calls=" + calls[0] + "," + calls[1] + "," + calls[2]);
        // All suppliers fail: the message names EVERY attempt in
        // full, for both encrypt and decrypt — and the exception
        // carries the type the save path keys regeneration on.
        String longDetail = "abcdefghijklmnopqrstuvwxyz0123456789"
                + "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789abcdefghij";
        WalletCrypto.CipherSupplier failDefault = failing("default",
                new NoSuchAlgorithmException(longDetail));
        WalletCrypto.CipherSupplier failOpenssl = failing("androidopenssl",
                new InvalidKeyException("openssl provider rejected the key"));
        WalletCrypto.CipherSupplier failKeystore = failing("keystore",
                new InvalidKeyException("keystore key rejected by this provider"));
        WalletCrypto.CipherSupplier[] allFail =
                new WalletCrypto.CipherSupplier[] { failDefault, failOpenssl, failKeystore };
        String encMsg = null;
        boolean encTyped = false;
        try {
            WalletCrypto.encrypt(key, plain, allFail);
        } catch (Exception e) {
            encMsg = e.getMessage();
            encTyped = e instanceof WalletCrypto.AllFailedException;
        }
        check("all-fail: encrypt names every attempt",
                encMsg != null && encMsg.startsWith("No cipher worked: ")
                        && encMsg.contains("default=NoSuchAlgorithmException")
                        && encMsg.contains("androidopenssl=InvalidKeyException")
                        && encMsg.contains("keystore=InvalidKeyException"),
                String.valueOf(encMsg));
        check("all-fail: encrypt detail survives in full",
                encMsg != null && encMsg.contains(longDetail),
                String.valueOf(encMsg));
        check("all-fail: encrypt message stays within the safety bound",
                encMsg != null && encMsg.length() <= 2000,
                "len=" + (encMsg == null ? "null" : encMsg.length()));
        check("all-fail: encrypt throws AllFailedException", encTyped, "");
        String decMsg = null;
        boolean decTyped = false;
        try {
            WalletCrypto.decrypt(key, blob, allFail);
        } catch (Exception e) {
            decMsg = e.getMessage();
            decTyped = e instanceof WalletCrypto.AllFailedException;
        }
        check("all-fail: decrypt names every attempt",
                decMsg != null && decMsg.contains("default=NoSuchAlgorithmException")
                        && decMsg.contains("androidopenssl=InvalidKeyException")
                        && decMsg.contains("keystore=InvalidKeyException"),
                String.valueOf(decMsg));
        check("all-fail: decrypt throws AllFailedException", decTyped, "");
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
        // doFinal and under the other two suppliers at lookup
        // (on the JVM the providers do not exist), so the
        // composite message names all three attempts — and still
        // throws instead of yielding wrong plaintext.
        String tamperMsg = null;
        try {
            WalletCrypto.decrypt(key, tampered);
        } catch (Exception e) {
            tamperMsg = e.getMessage();
        }
        check("all-fail: tampered blob names all three attempts",
                tamperMsg != null && tamperMsg.contains("default=AEADBadTagException")
                        && tamperMsg.contains("androidopenssl=NoSuchProviderException")
                        && tamperMsg.contains("keystore=NoSuchProviderException"),
                String.valueOf(tamperMsg));

        // ---- 7. Supplier order (v1.39) ----
        // Each position can win when the earlier ones fail: the
        // middle supplier wins here (named androidopenssl, backed
        // by a working cipher), and the third wins when both
        // earlier ones fail.
        WalletCrypto.CipherSupplier[] middleWins = new WalletCrypto.CipherSupplier[] {
                failing("default", new NoSuchAlgorithmException("no default")),
                working("androidopenssl"),
                failing("keystore", new NoSuchProviderException("no keystore")) };
        byte[] middleBlob = WalletCrypto.encrypt(key, plain, middleWins);
        check("order: middle supplier wins when the first fails",
                middleBlob != null && middleBlob.length > 12, "");
        WalletCrypto.CipherSupplier[] lastWins = new WalletCrypto.CipherSupplier[] {
                failing("default", new NoSuchAlgorithmException("no default")),
                failing("androidopenssl", new NoSuchProviderException("no openssl")),
                working("keystore") };
        byte[] lastBlob = WalletCrypto.encrypt(key, plain, lastWins);
        check("order: last supplier wins when the first two fail",
                lastBlob != null && lastBlob.length > 12, "");
        // A blob sealed through a later supplier still decrypts
        // through the shipping list (the wire form is unchanged).
        check("order: blob sealed by a later supplier decrypts",
                Arrays.equals(WalletCrypto.decrypt(key, middleBlob, shipping), plain), "");

        // ---- 8. Key recovery decision (v1.39) ----
        check("recovery: no failure means no regeneration",
                WalletKeyRecovery.decide(false, 0, false)
                        == WalletKeyRecovery.Action.GIVE_UP, "");
        check("recovery: all-fail + empty vault regenerates silently",
                WalletKeyRecovery.decide(true, 0, false)
                        == WalletKeyRecovery.Action.REGENERATE_SILENTLY, "");
        check("recovery: all-fail + one card asks first",
                WalletKeyRecovery.decide(true, 1, false)
                        == WalletKeyRecovery.Action.ASK_BEFORE_REGENERATE, "");
        check("recovery: all-fail + many cards asks first",
                WalletKeyRecovery.decide(true, 5, false)
                        == WalletKeyRecovery.Action.ASK_BEFORE_REGENERATE, "");
        check("recovery: all-fail + unknown count asks first (fail closed)",
                WalletKeyRecovery.decide(true, null, false)
                        == WalletKeyRecovery.Action.ASK_BEFORE_REGENERATE, "");
        check("recovery: already regenerated means give up",
                WalletKeyRecovery.decide(true, 0, true)
                        == WalletKeyRecovery.Action.GIVE_UP, "");
        check("recovery: already regenerated + cards means give up",
                WalletKeyRecovery.decide(true, 3, true)
                        == WalletKeyRecovery.Action.GIVE_UP, "");
        check("recovery: confirm copy names the consequence",
                WalletKeyRecovery.confirmBody().contains(
                        "previously saved cards will stop opening")
                        && WalletKeyRecovery.confirmBody().contains("re-added"),
                WalletKeyRecovery.confirmBody());
        check("recovery: confirm title names the key",
                WalletKeyRecovery.confirmTitle().contains("encryption key"),
                WalletKeyRecovery.confirmTitle());

        // ---- 9. Keystore-generated IV (v1.40) ----
        // (a) The no-IV-init encrypt path round-trips through
        // the shipping decrypt, default supplier only.
        WalletCrypto.CipherSupplier[] defaultOnly =
                new WalletCrypto.CipherSupplier[] { shipping[0] };
        check("keystore-iv: no-IV-init encrypt round-trips",
                Arrays.equals(plain, WalletCrypto.decrypt(key,
                        WalletCrypto.encrypt(key, plain, defaultOnly))), "");
        // (b) getIV() yields a fresh random 12-byte IV per
        // encryption: the two blobs' IV prefixes differ, and
        // both blobs decrypt.
        byte[] ivA = Arrays.copyOfRange(blob, 0, 12);
        byte[] ivB = Arrays.copyOfRange(blob2, 0, 12);
        check("keystore-iv: provider IVs differ between encryptions",
                !Arrays.equals(ivA, ivB), "");
        check("keystore-iv: IV prefix is exactly 12 bytes",
                ivA.length == 12 && ivB.length == 12, "");
        // (c) The wire form still starts with the IV: tampering
        // the IV prefix breaks decryption (the stored IV is the
        // one decrypt uses), and the blob length is unchanged.
        byte[] ivTampered = blob.clone();
        ivTampered[0] ^= 0x01;
        boolean ivTamperFailed = false;
        try {
            WalletCrypto.decrypt(key, ivTampered);
        } catch (Exception e) {
            ivTamperFailed = true;
        }
        check("keystore-iv: tampered IV prefix rejected", ivTamperFailed, "");
        // A blob sealed the OLD way (caller-provided IV, as
        // every version through v1.39 wrote it) still decrypts
        // through the unchanged decrypt path — the wire form
        // IV‖ciphertext+tag is identical.
        byte[] oldIv = new byte[12];
        new SecureRandom().nextBytes(oldIv);
        Cipher legacy = Cipher.getInstance("AES/GCM/NoPadding");
        legacy.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(128, oldIv));
        byte[] legacyCt = legacy.doFinal(plain);
        byte[] legacyBlob = new byte[12 + legacyCt.length];
        System.arraycopy(oldIv, 0, legacyBlob, 0, 12);
        System.arraycopy(legacyCt, 0, legacyBlob, 12, legacyCt.length);
        check("keystore-iv: blob sealed the old way still decrypts",
                Arrays.equals(plain, WalletCrypto.decrypt(key, legacyBlob)), "");
        // (d) Under the new encryptOnce, an all-failed encrypt
        // still names all three attempts.
        String ivFailMsg = null;
        try {
            WalletCrypto.encrypt(key, plain, allFail);
        } catch (Exception e) {
            ivFailMsg = e.getMessage();
        }
        check("keystore-iv: all-fail message still names all three attempts",
                ivFailMsg != null && ivFailMsg.contains("default=")
                        && ivFailMsg.contains("androidopenssl=")
                        && ivFailMsg.contains("keystore="),
                String.valueOf(ivFailMsg));

        System.out.println("----");
        System.out.println(passed + " passed, " + failed + " failed");
        if (failed > 0) System.exit(1);
    }
}
