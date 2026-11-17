package app.control.launcher;

import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * The download verification decisions (v1.27): pure Java, so the
 * harness proves the exact rules the updater enforces before it
 * ever offers an install. The Android side supplies the measured
 * facts (the file's hash, its size, the package info Android read
 * out of the APK, both apps' signing-certificate hashes); this
 * class decides, and names the first failure in plain language.
 *
 * <p>All five checks must pass, in this order:
 * <ol>
 *   <li>The SHA-256 of the downloaded bytes equals the manifest's.</li>
 *   <li>The downloaded size equals the manifest's size.</li>
 *   <li>The APK's package is this app's package.</li>
 *   <li>The APK's version code equals the manifest's.</li>
 *   <li>The APK's signing certificates are exactly the running
 *       app's signing certificates — same set, no more, no fewer.
 *       An APK signed with any other key is never offered.</li>
 * </ol>
 * Any failure deletes the file and no install is offered.
 */
public final class UpdateVerify {

    private UpdateVerify() {}

    /** True when the string is exactly 64 lowercase-or-uppercase
     *  hex characters — a SHA-256 digest, nothing else. */
    public static boolean isValidSha256Hex(String hex) {
        if (hex == null || hex.length() != 64) return false;
        for (int i = 0; i < hex.length(); i++) {
            char c = hex.charAt(i);
            boolean digit = c >= '0' && c <= '9';
            boolean lo = c >= 'a' && c <= 'f';
            boolean hi = c >= 'A' && c <= 'F';
            if (!digit && !lo && !hi) return false;
        }
        return true;
    }

    /** Case-insensitive digest equality; both sides must be
     *  valid digests, so a truncated or garbage hash can never
     *  "match" by accident. */
    public static boolean shaMatches(String expectedHex, String actualHex) {
        if (!isValidSha256Hex(expectedHex)) return false;
        if (!isValidSha256Hex(actualHex)) return false;
        return expectedHex.equalsIgnoreCase(actualHex);
    }

    /**
     * True when the APK's signer set is exactly the running
     * app's signer set. Both sides are SHA-256 hex digests of
     * the certificates' bytes. Empty on either side never
     * matches: an unsigned APK, or an app that cannot read its
     * own signers, fails closed.
     */
    public static boolean certsMatch(List<String> ownCertShaHex,
                                     List<String> apkCertShaHex) {
        if (ownCertShaHex == null || apkCertShaHex == null) return false;
        if (ownCertShaHex.isEmpty() || apkCertShaHex.isEmpty()) return false;
        Set<String> own = lowerSet(ownCertShaHex);
        Set<String> apk = lowerSet(apkCertShaHex);
        if (own.isEmpty() || apk.isEmpty()) return false;
        return own.equals(apk);
    }

    private static Set<String> lowerSet(List<String> hexes) {
        Set<String> out = new HashSet<>();
        for (String h : hexes) {
            if (h == null) continue;
            String lower = h.trim().toLowerCase(Locale.US);
            if (isValidSha256Hex(lower)) out.add(lower);
        }
        return out;
    }

    /**
     * The full verification decision. Returns null when every
     * check passes; otherwise the plain-language reason the
     * download is rejected (the first failing check, in the
     * order listed above). The caller deletes the file on any
     * non-null return.
     */
    public static String verifyDownload(String expectedSha256, String actualSha256,
                                        long expectedSize, long actualSize,
                                        String ownPackage, String apkPackage,
                                        int expectedCode, int apkCode,
                                        List<String> ownCertShaHex,
                                        List<String> apkCertShaHex) {
        if (!shaMatches(expectedSha256, actualSha256)) {
            return "The download's checksum did not match the update "
                    + "file, so it was deleted. Nothing was installed.";
        }
        if (expectedSize > 0 && actualSize != expectedSize) {
            return "The download arrived incomplete, so it was deleted. "
                    + "Nothing was installed.";
        }
        if (ownPackage == null || apkPackage == null
                || !ownPackage.equals(apkPackage)) {
            return "The download is not a Control update, so it was "
                    + "deleted. Nothing was installed.";
        }
        if (apkCode != expectedCode) {
            return "The download is not the version the update file "
                    + "promised, so it was deleted. Nothing was installed.";
        }
        if (!certsMatch(ownCertShaHex, apkCertShaHex)) {
            return "The download was not signed with Control's key, "
                    + "so it was deleted. Nothing was installed.";
        }
        return null;
    }
}
