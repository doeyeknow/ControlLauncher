package app.control.launcher;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * Off-device verification for the v1.27 in-app updater,
 * exercising the exact shipping rules (UpdateManifest,
 * UpdateVerify, and the pure row/tap/format decisions in
 * UpdateManager):
 *
 * Legs:
 *   1. PARSE: the fixed manifest shape — valid bodies, every
 *      missing or out-of-range field, malformed JSON, wrong
 *      types, extra fields ignored, checksum normalized.
 *   2. URLS: cache-busting, the HTTPS-only rule, relative
 *      resolution against the manifest origin, and every
 *      refusal (http, scheme-relative, traversal, empty).
 *   3. DECIDE: strictly-greater version comparison (no
 *      downgrades, no same-version reinstalls) and the quiet
 *      check throttle.
 *   4. VERIFY: checksum and certificate-set rules, and the
 *      full five-check download decision with first-failure
 *      ordering.
 *   5. ROW: the Settings row's one-line summary and tap
 *      decision for every state the flow can be in.
 *   6. FORMAT: byte/KB/MB formatting and its boundaries.
 *
 * The Android wiring the harness cannot see (the HTTPS fetch,
 * the file download, the PackageManager certificate reads,
 * the install intent, the unknown-apps setting) is verified
 * by the build and by the on-device checklist in BUILD_NOTES;
 * this harness pins every rule that wiring depends on. No
 * Android classes are touched at runtime, no network.
 * Run from the project root:
 *   javac -encoding UTF-8 --release 17 -sourcepath src \
 *     -cp "harness/org-json.jar:libs/bcprov-jdk18on.jar:libs/bctls-jdk18on.jar:libs/bcutil-jdk18on.jar:$ANDROID_HOME/platforms/android-34/android.jar" \
 *     -d harness/classes harness/UpdaterHarness.java
 *   java -cp harness/classes app.control.launcher.UpdaterHarness
 */
public final class UpdaterHarness {

    private static int checks;
    private static int failures;

    private static final String SHA_A =
            "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";
    private static final String SHA_A_UPPER =
            "0123456789ABCDEF0123456789ABCDEF0123456789ABCDEF0123456789ABCDEF";
    private static final String SHA_B =
            "fedcba9876543210fedcba9876543210fedcba9876543210fedcba9876543210";

    private static void check(String name, boolean ok) {
        checks++;
        if (ok) {
            System.out.println("PASS  " + name);
        } else {
            failures++;
            System.out.println("FAIL  " + name);
        }
    }

    private static void checkEq(String name, Object want, Object got) {
        checks++;
        if (want == null ? got == null : want.equals(got)) {
            System.out.println("PASS  " + name);
        } else {
            failures++;
            System.out.println("FAIL  " + name + "  -- want <" + want + "> got <" + got + ">");
        }
    }

    public static void main(String[] args) {
        parse();
        urls();
        decide();
        verify();
        row();
        format();
        System.out.println("UpdaterHarness: " + (checks - failures) + "/" + checks);
        if (failures > 0) System.exit(1);
    }

    private static String manifestJson(int code, String name, String apk,
                                       String sha, Object size) {
        String sizeText = size instanceof String
                ? "\"" + size + "\"" : String.valueOf(size);
        return "{\"versionCode\":" + code + ",\"versionName\":\"" + name
                + "\",\"apk\":\"" + apk + "\",\"sha256\":\"" + sha
                + "\",\"size\":" + sizeText + "}";
    }

    private static void parse() {
        UpdateManifest.ParseResult ok = UpdateManifest.parse(
                manifestJson(33, "1.28", "control-launcher.apk", SHA_A, 20694398L));
        check("parse valid", ok.isOk());
        checkEq("parse code", 33, ok.manifest.versionCode);
        checkEq("parse name", "1.28", ok.manifest.versionName);
        checkEq("parse apk", "control-launcher.apk", ok.manifest.apk);
        checkEq("parse sha", SHA_A, ok.manifest.sha256);
        checkEq("parse size", 20694398L, ok.manifest.size);

        UpdateManifest.ParseResult upper = UpdateManifest.parse(
                manifestJson(33, "1.28", "control-launcher.apk", SHA_A_UPPER, 100L));
        check("parse upper sha ok", upper.isOk());
        checkEq("parse sha lowercased", SHA_A, upper.manifest.sha256);

        check("parse extra fields ignored", UpdateManifest.parse(
                "{\"versionCode\":33,\"versionName\":\"1.28\",\"apk\":\"a.apk\","
                + "\"sha256\":\"" + SHA_A + "\",\"size\":100,"
                + "\"notes\":\"hello\",\"minSdk\":26}").isOk());
        check("parse surrounding whitespace", UpdateManifest.parse(
                "  \n " + manifestJson(33, "1.28", "a.apk", SHA_A, 100L) + " \n ").isOk());
        check("parse numeric strings", UpdateManifest.parse(
                manifestJson(0, "1.28", "a.apk", SHA_A, "100")
                        .replace("\"versionCode\":0", "\"versionCode\":\"33\"")).isOk());

        check("parse null", !UpdateManifest.parse(null).isOk());
        check("parse empty", !UpdateManifest.parse("").isOk());
        check("parse blank", !UpdateManifest.parse("   ").isOk());
        check("parse not json", !UpdateManifest.parse("hello").isOk());
        check("parse array", !UpdateManifest.parse("[1,2]").isOk());
        check("parse brace only", !UpdateManifest.parse("{").isOk());
        check("parse truncated", !UpdateManifest.parse(
                "{\"versionCode\":33,\"versionName\":\"1.28\"").isOk());
        check("parse empty object", !UpdateManifest.parse("{}").isOk());
        check("parse trailing garbage", !UpdateManifest.parse(
                manifestJson(33, "1.28", "a.apk", SHA_A, 100L) + "garbage").isOk());

        check("parse missing code", !UpdateManifest.parse(
                "{\"versionName\":\"1.28\",\"apk\":\"a.apk\",\"sha256\":\""
                + SHA_A + "\",\"size\":100}").isOk());
        check("parse missing name", !UpdateManifest.parse(
                "{\"versionCode\":33,\"apk\":\"a.apk\",\"sha256\":\""
                + SHA_A + "\",\"size\":100}").isOk());
        check("parse missing apk", !UpdateManifest.parse(
                "{\"versionCode\":33,\"versionName\":\"1.28\",\"sha256\":\""
                + SHA_A + "\",\"size\":100}").isOk());
        check("parse missing sha", !UpdateManifest.parse(
                "{\"versionCode\":33,\"versionName\":\"1.28\",\"apk\":\"a.apk\","
                + "\"size\":100}").isOk());
        check("parse missing size", !UpdateManifest.parse(
                "{\"versionCode\":33,\"versionName\":\"1.28\",\"apk\":\"a.apk\","
                + "\"sha256\":\"" + SHA_A + "\"}").isOk());

        check("parse code zero", !UpdateManifest.parse(
                manifestJson(0, "1.28", "a.apk", SHA_A, 100L)).isOk());
        check("parse code negative", !UpdateManifest.parse(
                manifestJson(-5, "1.28", "a.apk", SHA_A, 100L)).isOk());
        check("parse code boolean", !UpdateManifest.parse(
                manifestJson(33, "1.28", "a.apk", SHA_A, 100L)
                        .replace("\"versionCode\":33", "\"versionCode\":true")).isOk());
        check("parse name empty", !UpdateManifest.parse(
                manifestJson(33, "", "a.apk", SHA_A, 100L)).isOk());
        check("parse name blank", !UpdateManifest.parse(
                manifestJson(33, "   ", "a.apk", SHA_A, 100L)).isOk());
        check("parse apk empty", !UpdateManifest.parse(
                manifestJson(33, "1.28", "", SHA_A, 100L)).isOk());
        check("parse sha short", !UpdateManifest.parse(
                manifestJson(33, "1.28", "a.apk", SHA_A.substring(0, 63), 100L)).isOk());
        check("parse sha long", !UpdateManifest.parse(
                manifestJson(33, "1.28", "a.apk", SHA_A + "00", 100L)).isOk());
        check("parse sha non-hex", !UpdateManifest.parse(
                manifestJson(33, "1.28", "a.apk",
                        "g123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
                        100L)).isOk());
        check("parse size zero", !UpdateManifest.parse(
                manifestJson(33, "1.28", "a.apk", SHA_A, 0L)).isOk());
        check("parse size negative", !UpdateManifest.parse(
                manifestJson(33, "1.28", "a.apk", SHA_A, -1L)).isOk());
        check("parse size text", !UpdateManifest.parse(
                manifestJson(33, "1.28", "a.apk", SHA_A, "big")).isOk());

        check("parse error names the problem", UpdateManifest.parse("{}").error.length() > 0);
    }

    private static void urls() {
        checkEq("cache-bust plain",
                "https://launcher.eyeknow.app/version.json?t=123",
                UpdateManifest.cacheBustedUrl(
                        "https://launcher.eyeknow.app/version.json", 123));
        checkEq("cache-bust existing query",
                "https://example.test/v.json?x=1&t=9",
                UpdateManifest.cacheBustedUrl("https://example.test/v.json?x=1", 9));
        checkEq("cache-bust null", "", UpdateManifest.cacheBustedUrl(null, 1));

        check("https ok", UpdateManifest.isHttpsUrl("https://launcher.eyeknow.app/v.json"));
        check("https case-insensitive",
                UpdateManifest.isHttpsUrl("HTTPS://launcher.eyeknow.app/v.json"));
        check("https trims", UpdateManifest.isHttpsUrl("  https://a.test/  "));
        check("http refused", !UpdateManifest.isHttpsUrl("http://a.test/v.json"));
        check("https bare refused", !UpdateManifest.isHttpsUrl("https://"));
        check("empty refused", !UpdateManifest.isHttpsUrl(""));
        check("null refused", !UpdateManifest.isHttpsUrl(null));
        check("relative refused", !UpdateManifest.isHttpsUrl("control-launcher.apk"));

        String base = UpdateManifest.MANIFEST_URL;
        checkEq("resolve relative", "https://launcher.eyeknow.app/control-launcher.apk",
                UpdateManifest.resolveApkUrl(base, "control-launcher.apk"));
        checkEq("resolve leading slash", "https://launcher.eyeknow.app/control-launcher.apk",
                UpdateManifest.resolveApkUrl(base, "/control-launcher.apk"));
        checkEq("resolve subpath", "https://launcher.eyeknow.app/r/a.apk",
                UpdateManifest.resolveApkUrl(base, "r/a.apk"));
        checkEq("resolve absolute https kept", "https://cdn.test/a.apk",
                UpdateManifest.resolveApkUrl(base, "https://cdn.test/a.apk"));
        check("resolve http refused",
                UpdateManifest.resolveApkUrl(base, "http://evil.test/a.apk") == null);
        check("resolve scheme-relative refused",
                UpdateManifest.resolveApkUrl(base, "//evil.test/a.apk") == null);
        check("resolve dotdot refused",
                UpdateManifest.resolveApkUrl(base, "../a.apk") == null);
        check("resolve mid traversal refused",
                UpdateManifest.resolveApkUrl(base, "r/../a.apk") == null);
        check("resolve trailing dotdot refused",
                UpdateManifest.resolveApkUrl(base, "r/..") == null);
        check("resolve empty refused",
                UpdateManifest.resolveApkUrl(base, "  ") == null);
        check("resolve null apk refused",
                UpdateManifest.resolveApkUrl(base, null) == null);
        check("resolve null base refused",
                UpdateManifest.resolveApkUrl(null, "a.apk") == null);
        check("resolve http base refused",
                UpdateManifest.resolveApkUrl("http://a.test/v.json", "a.apk") == null);
        checkEq("origin", "https://launcher.eyeknow.app",
                UpdateManifest.originOf("https://launcher.eyeknow.app/version.json"));
        check("origin http null",
                UpdateManifest.originOf("http://a.test/v.json") == null);
    }

    private static void decide() {
        UpdateManifest.Manifest m = new UpdateManifest.Manifest(
                33, "1.28", "a.apk", SHA_A, 100L);
        check("isUpdate greater", UpdateManifest.isUpdate(m, 32));
        check("isUpdate equal refused", !UpdateManifest.isUpdate(m, 33));
        check("isUpdate lower refused", !UpdateManifest.isUpdate(m, 34));
        check("isUpdate null refused", !UpdateManifest.isUpdate(null, 32));

        long hour = UpdateManifest.QUIET_CHECK_FRESH_MS;
        check("quiet never checked", UpdateManifest.shouldQuietCheck(0, 1000));
        check("quiet negative", UpdateManifest.shouldQuietCheck(-5, 1000));
        check("quiet recent refused", !UpdateManifest.shouldQuietCheck(1000, 1000 + hour - 1));
        check("quiet boundary refused",
                !UpdateManifest.shouldQuietCheck(1000, 1000 + hour));
        check("quiet stale", UpdateManifest.shouldQuietCheck(1000, 1000 + hour + 1));
        check("quiet clock back", UpdateManifest.shouldQuietCheck(5000, 1000));
    }

    private static void verify() {
        check("sha valid lower", UpdateVerify.isValidSha256Hex(SHA_A));
        check("sha valid upper", UpdateVerify.isValidSha256Hex(SHA_A_UPPER));
        check("sha 63 refused",
                !UpdateVerify.isValidSha256Hex(SHA_A.substring(0, 63)));
        check("sha 65 refused", !UpdateVerify.isValidSha256Hex(SHA_A + "0"));
        check("sha non-hex refused",
                !UpdateVerify.isValidSha256Hex(SHA_A.replace('a', 'g')));
        check("sha null refused", !UpdateVerify.isValidSha256Hex(null));
        check("sha empty refused", !UpdateVerify.isValidSha256Hex(""));

        check("shaMatches same", UpdateVerify.shaMatches(SHA_A, SHA_A));
        check("shaMatches case-insensitive",
                UpdateVerify.shaMatches(SHA_A, SHA_A_UPPER));
        check("shaMatches different refused", !UpdateVerify.shaMatches(SHA_A, SHA_B));
        check("shaMatches invalid expected refused",
                !UpdateVerify.shaMatches("xyz", SHA_A));
        check("shaMatches invalid actual refused",
                !UpdateVerify.shaMatches(SHA_A, "xyz"));

        List<String> oneA = Collections.singletonList(SHA_A);
        List<String> oneAUpper = Collections.singletonList(SHA_A_UPPER);
        List<String> oneB = Collections.singletonList(SHA_B);
        List<String> twoAB = Arrays.asList(SHA_A, SHA_B);
        List<String> twoBA = Arrays.asList(SHA_B, SHA_A);
        check("certs equal single", UpdateVerify.certsMatch(oneA, oneA));
        check("certs case-insensitive", UpdateVerify.certsMatch(oneA, oneAUpper));
        check("certs order-independent", UpdateVerify.certsMatch(twoAB, twoBA));
        check("certs subset refused", !UpdateVerify.certsMatch(twoAB, oneA));
        check("certs superset refused", !UpdateVerify.certsMatch(oneA, twoAB));
        check("certs disjoint refused", !UpdateVerify.certsMatch(oneA, oneB));
        check("certs empty own refused",
                !UpdateVerify.certsMatch(Collections.emptyList(), oneA));
        check("certs empty apk refused",
                !UpdateVerify.certsMatch(oneA, Collections.emptyList()));
        check("certs null own refused", !UpdateVerify.certsMatch(null, oneA));
        check("certs null apk refused", !UpdateVerify.certsMatch(oneA, null));
        check("certs invalid entries filtered",
                !UpdateVerify.certsMatch(oneA, Arrays.asList("xyz")));
        check("certs duplicates collapse",
                UpdateVerify.certsMatch(oneA, Arrays.asList(SHA_A, SHA_A)));

        check("verify all pass", UpdateVerify.verifyDownload(
                SHA_A, SHA_A, 100L, 100L,
                "app.control.launcher", "app.control.launcher",
                33, 33, oneA, oneA) == null);
        check("verify sha fails", UpdateVerify.verifyDownload(
                SHA_A, SHA_B, 100L, 100L,
                "app.control.launcher", "app.control.launcher",
                33, 33, oneA, oneA) != null);
        check("verify size fails", UpdateVerify.verifyDownload(
                SHA_A, SHA_A, 100L, 99L,
                "app.control.launcher", "app.control.launcher",
                33, 33, oneA, oneA) != null);
        check("verify package fails", UpdateVerify.verifyDownload(
                SHA_A, SHA_A, 100L, 100L,
                "app.control.launcher", "com.evil.other",
                33, 33, oneA, oneA) != null);
        check("verify null package fails", UpdateVerify.verifyDownload(
                SHA_A, SHA_A, 100L, 100L,
                "app.control.launcher", null,
                33, 33, oneA, oneA) != null);
        check("verify code fails", UpdateVerify.verifyDownload(
                SHA_A, SHA_A, 100L, 100L,
                "app.control.launcher", "app.control.launcher",
                33, 32, oneA, oneA) != null);
        check("verify cert fails", UpdateVerify.verifyDownload(
                SHA_A, SHA_A, 100L, 100L,
                "app.control.launcher", "app.control.launcher",
                33, 33, oneA, oneB) != null);
        check("verify sha wins over cert", UpdateVerify.verifyDownload(
                SHA_A, SHA_B, 100L, 100L,
                "app.control.launcher", "app.control.launcher",
                33, 33, oneA, oneB).contains("checksum"));
        check("verify cert reason names the key", UpdateVerify.verifyDownload(
                SHA_A, SHA_A, 100L, 100L,
                "app.control.launcher", "app.control.launcher",
                33, 33, oneA, oneB).contains("key"));
        check("verify zero expected size skips size check", UpdateVerify.verifyDownload(
                SHA_A, SHA_A, 0L, 999L,
                "app.control.launcher", "app.control.launcher",
                33, 33, oneA, oneA) == null);
    }

    private static UpdateManager.RowState state(UpdateManager.Phase phase,
                                                String activeName,
                                                long progress, long total,
                                                UpdateManifest.Manifest known,
                                                long lastCheck, boolean lastOk,
                                                String lastError,
                                                int downloadedCode, String downloadedName,
                                                String failure) {
        return new UpdateManager.RowState("1.27", 32, phase, activeName,
                progress, total, known, lastCheck, lastOk, lastError,
                downloadedCode, downloadedName, failure);
    }

    private static UpdateManifest.Manifest known(int code, String name, long size) {
        return new UpdateManifest.Manifest(code, name, "a.apk", SHA_A, size);
    }

    private static void row() {
        UpdateManager.RowState never = state(UpdateManager.Phase.IDLE, "", 0, 0,
                null, 0, false, "", 0, "", "");
        checkEq("row never checked", "Version 1.27 — tap to check for updates.",
                UpdateManager.rowLine(never, false));
        checkEq("tap never checked", UpdateManager.TapAction.CHECK,
                UpdateManager.decideTap(never, false));

        UpdateManager.RowState current = state(UpdateManager.Phase.IDLE, "", 0, 0,
                known(32, "1.27", 100L), 1000, true, "", 0, "", "");
        checkEq("row current", "Version 1.27 — you're on the latest version.",
                UpdateManager.rowLine(current, false));
        checkEq("tap current", UpdateManager.TapAction.CHECK,
                UpdateManager.decideTap(current, false));

        UpdateManager.RowState older = state(UpdateManager.Phase.IDLE, "", 0, 0,
                known(31, "1.26.1", 100L), 1000, true, "", 0, "", "");
        checkEq("row older manifest is current",
                "Version 1.27 — you're on the latest version.",
                UpdateManager.rowLine(older, false));
        checkEq("tap older manifest", UpdateManager.TapAction.CHECK,
                UpdateManager.decideTap(older, false));

        UpdateManager.RowState avail = state(UpdateManager.Phase.IDLE, "", 0, 0,
                known(33, "1.28", 20694398L), 1000, true, "", 0, "", "");
        checkEq("row available",
                "Update available: 1.28 · 19.7 MB — tap to download.",
                UpdateManager.rowLine(avail, false));
        checkEq("tap available", UpdateManager.TapAction.DOWNLOAD,
                UpdateManager.decideTap(avail, false));

        UpdateManager.RowState checking = state(UpdateManager.Phase.CHECKING, "", 0, 0,
                null, 0, false, "", 0, "", "");
        checkEq("row checking", "Checking for updates…",
                UpdateManager.rowLine(checking, false));
        checkEq("tap checking ignored", UpdateManager.TapAction.IGNORE,
                UpdateManager.decideTap(checking, false));

        UpdateManager.RowState downloading = state(UpdateManager.Phase.DOWNLOADING,
                "1.28", 10347199L, 20694398L, known(33, "1.28", 20694398L),
                1000, true, "", 0, "", "");
        checkEq("row downloading",
                "Downloading 1.28… 50% (9.9 MB of 19.7 MB)",
                UpdateManager.rowLine(downloading, false));
        checkEq("tap downloading ignored", UpdateManager.TapAction.IGNORE,
                UpdateManager.decideTap(downloading, false));

        UpdateManager.RowState downloadingUnknown = state(
                UpdateManager.Phase.DOWNLOADING, "1.28", 1048576L, 0,
                known(33, "1.28", 20694398L), 1000, true, "", 0, "", "");
        checkEq("row downloading unknown total", "Downloading 1.28… 1.0 MB",
                UpdateManager.rowLine(downloadingUnknown, false));

        UpdateManager.RowState verifying = state(UpdateManager.Phase.VERIFYING,
                "1.28", 0, 0, known(33, "1.28", 20694398L),
                1000, true, "", 0, "", "");
        checkEq("row verifying", "Verifying 1.28…",
                UpdateManager.rowLine(verifying, false));
        checkEq("tap verifying ignored", UpdateManager.TapAction.IGNORE,
                UpdateManager.decideTap(verifying, false));

        UpdateManager.RowState ready = state(UpdateManager.Phase.IDLE, "", 0, 0,
                known(33, "1.28", 20694398L), 1000, true, "",
                33, "1.28", "");
        checkEq("row ready", "1.28 is verified — tap to install.",
                UpdateManager.rowLine(ready, false));
        checkEq("tap ready", UpdateManager.TapAction.INSTALL,
                UpdateManager.decideTap(ready, false));
        checkEq("row ready grant missing",
                "1.28 is verified — tap to allow installing it.",
                UpdateManager.rowLine(ready, true));
        checkEq("tap ready grant missing", UpdateManager.TapAction.OPEN_GRANT_SETTING,
                UpdateManager.decideTap(ready, true));

        UpdateManager.RowState staleReady = state(UpdateManager.Phase.IDLE, "", 0, 0,
                known(32, "1.27", 100L), 1000, true, "",
                32, "1.27", "");
        checkEq("row downloaded same version is current",
                "Version 1.27 — you're on the latest version.",
                UpdateManager.rowLine(staleReady, false));

        UpdateManager.RowState failed = state(UpdateManager.Phase.IDLE, "", 0, 0,
                null, 1000, false, "couldn't reach the update file", 0, "", "");
        checkEq("row check failed",
                "Version 1.27 — the last check couldn't reach the update file. Tap to try again.",
                UpdateManager.rowLine(failed, false));
        checkEq("tap check failed", UpdateManager.TapAction.CHECK,
                UpdateManager.decideTap(failed, false));

        UpdateManager.RowState failedWithKnown = state(UpdateManager.Phase.IDLE,
                "", 0, 0, known(33, "1.28", 20694398L), 1000, false,
                "couldn't reach the update file", 0, "", "");
        checkEq("row failed check keeps known update",
                "Update available: 1.28 · 19.7 MB — tap to download.",
                UpdateManager.rowLine(failedWithKnown, false));

        UpdateManager.RowState downloadFailed = state(UpdateManager.Phase.IDLE,
                "", 0, 0, known(33, "1.28", 20694398L), 1000, true, "",
                0, "", "the download failed the checksum and was deleted");
        checkEq("row download failure beats available",
                "Version 1.27 — the download failed the checksum and was deleted Tap to try again.",
                UpdateManager.rowLine(downloadFailed, false));
        checkEq("tap download failure", UpdateManager.TapAction.DOWNLOAD,
                UpdateManager.decideTap(downloadFailed, false));
    }

    private static void format() {
        checkEq("format 0", "0 B", UpdateManager.formatBytes(0));
        checkEq("format negative", "0 B", UpdateManager.formatBytes(-5));
        checkEq("format 1", "1 B", UpdateManager.formatBytes(1));
        checkEq("format 1023", "1023 B", UpdateManager.formatBytes(1023));
        checkEq("format 1024", "1.0 KB", UpdateManager.formatBytes(1024));
        checkEq("format 1536", "1.5 KB", UpdateManager.formatBytes(1536));
        checkEq("format 1048575", "1024.0 KB", UpdateManager.formatBytes(1048575));
        checkEq("format 1MB", "1.0 MB", UpdateManager.formatBytes(1048576));
        checkEq("format 20MB", "19.7 MB", UpdateManager.formatBytes(20694398));
        checkEq("format 2.96MB", "2.8 MB", UpdateManager.formatBytes(2958255));
    }
}
