package app.control.launcher;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.content.pm.SigningInfo;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URL;
import java.net.URLConnection;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.net.ssl.HttpsURLConnection;

/**
 * The in-app updater (v1.27): the Android side of the update flow.
 * The decisions live in the pure-Java seams ({@link UpdateManifest}
 * for the manifest and URLs, {@link UpdateVerify} for the download
 * checks); this class does the I/O and drives the one Settings row
 * that carries the whole flow. There are no popups, no banners,
 * and no background downloads: the manifest check is a quiet
 * background fetch, but the 20 MB APK downloads only on the
 * user's tap.
 *
 * <p>Flow: launch quietly fetches the manifest (throttled to once
 * an hour; failures silent) and the Settings row reports the last
 * check honestly. When the manifest names a strictly newer
 * versionCode, the row offers the download. The download is
 * verified — SHA-256, size, package, version code, and signing
 * certificate — before any install is offered; any failure
 * deletes the file. Install goes through the standard system
 * installer, which always needs the user's own tap: nothing in
 * this class can or does skip it, and no copy here promises
 * otherwise. If Android's "install unknown apps" grant for
 * Control is missing, the row says so and opens the exact system
 * setting.
 *
 * <p>The verified file stays in the app's private cache until it
 * is installed or replaced by a newer download. It is deleted on
 * launch once its version is the installed version (the update
 * landed), and on any verification failure. It is deliberately
 * NOT deleted the moment the install is offered: the installer
 * reads the file through the content provider after the intent
 * fires, and deleting it first would break the install.
 */
public final class UpdateManager {

    private static final String UPDATE_FILE = "control-update.apk";
    private static final String UPDATE_TMP = "control-update.apk.tmp";
    private static final int MANIFEST_CONNECT_MS = 15000;
    private static final int MANIFEST_READ_MS = 20000;
    private static final int DOWNLOAD_CONNECT_MS = 30000;
    private static final int DOWNLOAD_READ_MS = 60000;
    private static final int MAX_REDIRECTS = 5;
    /** Slack above the manifest size a download may run to
     *  before it is aborted (1 MB): a response far bigger than
     *  the promised APK is not the promised APK. */
    private static final long DOWNLOAD_SLACK_BYTES = 1024L * 1024L;
    private static final long PROGRESS_POST_MIN_MS = 250L;

    /** What the updater is doing right now (runtime only). */
    public enum Phase { IDLE, CHECKING, DOWNLOADING, VERIFYING }

    /** What a tap on the Settings row should do. */
    public enum TapAction { IGNORE, CHECK, DOWNLOAD, INSTALL, OPEN_GRANT_SETTING }

    /** A snapshot of everything the row line and the tap
     *  decision need. Pure data — {@link #rowLine} and
     *  {@link #decideTap} are pure functions of it, so the
     *  harness proves the exact row behavior. */
    public static final class RowState {
        public final String installedName;
        public final int installedCode;
        public final Phase phase;
        public final String activeName;
        public final long progressBytes;
        public final long totalBytes;
        public final UpdateManifest.Manifest known;
        public final long lastCheckAtMs;
        public final boolean lastCheckOk;
        public final String lastError;
        public final int downloadedCode;
        public final String downloadedName;
        public final String failure;

        public RowState(String installedName, int installedCode, Phase phase,
                        String activeName, long progressBytes, long totalBytes,
                        UpdateManifest.Manifest known, long lastCheckAtMs,
                        boolean lastCheckOk, String lastError,
                        int downloadedCode, String downloadedName, String failure) {
            this.installedName = installedName;
            this.installedCode = installedCode;
            this.phase = phase;
            this.activeName = activeName == null ? "" : activeName;
            this.progressBytes = progressBytes;
            this.totalBytes = totalBytes;
            this.known = known;
            this.lastCheckAtMs = lastCheckAtMs;
            this.lastCheckOk = lastCheckOk;
            this.lastError = lastError == null ? "" : lastError;
            this.downloadedCode = downloadedCode;
            this.downloadedName = downloadedName == null ? "" : downloadedName;
            this.failure = failure == null ? "" : failure;
        }
    }

    /** The row's one-line summary for a snapshot. */
    public static String rowLine(RowState s, boolean grantMissing) {
        String installed = "Version " + s.installedName;
        switch (s.phase) {
            case DOWNLOADING: {
                String base = "Downloading " + s.activeName + "… ";
                if (s.totalBytes > 0) {
                    int pct = (int) Math.min(100,
                            (s.progressBytes * 100) / s.totalBytes);
                    return base + pct + "% (" + formatBytes(s.progressBytes)
                            + " of " + formatBytes(s.totalBytes) + ")";
                }
                return base + formatBytes(s.progressBytes);
            }
            case VERIFYING:
                return "Verifying " + s.activeName + "…";
            case CHECKING:
                return "Checking for updates…";
            default:
                break;
        }
        if (s.downloadedCode > s.installedCode) {
            if (grantMissing) {
                return s.downloadedName + " is verified — tap to allow "
                        + "installing it.";
            }
            return s.downloadedName + " is verified — tap to install.";
        }
        // A fresh download/verify failure is named while it is
        // fresh (a successful check clears it); the tap still
        // downloads, so the retry is one tap away.
        if (!s.failure.isEmpty()) {
            return installed + " — " + s.failure + " Tap to try again.";
        }
        if (UpdateManifest.isUpdate(s.known, s.installedCode)) {
            return "Update available: " + s.known.versionName + " · "
                    + formatBytes(s.known.size) + " — tap to download.";
        }
        if (s.lastCheckAtMs > 0 && !s.lastCheckOk && s.known == null) {
            String why = s.lastError.isEmpty()
                    ? "couldn't reach the update file" : s.lastError;
            return installed + " — the last check " + why + ". Tap to try again.";
        }
        if (s.known != null) {
            return installed + " — you're on the latest version.";
        }
        return installed + " — tap to check for updates.";
    }

    /** What a tap on the row does for a snapshot. */
    public static TapAction decideTap(RowState s, boolean grantMissing) {
        if (s.phase != Phase.IDLE) return TapAction.IGNORE;
        if (s.downloadedCode > s.installedCode) {
            return grantMissing ? TapAction.OPEN_GRANT_SETTING : TapAction.INSTALL;
        }
        if (UpdateManifest.isUpdate(s.known, s.installedCode)) {
            return TapAction.DOWNLOAD;
        }
        return TapAction.CHECK;
    }

    /** "2.96 MB", "512 B", "20.5 KB" — one decimal above bytes. */
    public static String formatBytes(long bytes) {
        if (bytes < 0) bytes = 0;
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) {
            return String.format(Locale.US, "%.1f KB", bytes / 1024.0);
        }
        return String.format(Locale.US, "%.1f MB", bytes / (1024.0 * 1024.0));
    }

    // ---- runtime state (static: the flow survives rotation) ----

    /** Receives row lines on the main thread. */
    public interface Listener {
        void onLine(String line);
    }

    private static volatile Listener listener;
    private static volatile Context appContext;
    private static volatile Handler mainHandler;
    private static volatile Phase phase = Phase.IDLE;
    private static volatile String activeName = "";
    private static volatile long progressBytes;
    private static volatile long totalBytes;
    private static final AtomicBoolean checkInFlight = new AtomicBoolean(false);
    private static final AtomicBoolean downloadInFlight = new AtomicBoolean(false);
    private static volatile long lastProgressPostMs;

    private UpdateManager() {}

    public static void setListener(Listener l) {
        listener = l;
    }

    private static Handler handler() {
        if (mainHandler == null) {
            mainHandler = new Handler(Looper.getMainLooper());
        }
        return mainHandler;
    }

    private static Context app(Context c) {
        if (appContext == null && c != null) {
            appContext = c.getApplicationContext();
        }
        return appContext;
    }

    // ---- entry points ----

    /**
     * Launch hook (MainActivity.onCreate): delete a stale update
     * file, then quietly check for updates unless one ran
     * recently. Never toasts, never interrupts.
     */
    public static void onAppLaunch(Context c) {
        Context a = app(c);
        if (a == null) return;
        cleanupStale(a);
        SharedPreferences p = Prefs.get(a);
        long last = p.getLong(Prefs.KEY_UPDATE_LAST_CHECK, 0);
        if (UpdateManifest.shouldQuietCheck(last, System.currentTimeMillis())) {
            fetchManifest(a, false);
        }
    }

    /**
     * Settings opened (or resumed): render the persisted state
     * now, then refresh the check in the background. The row
     * reports the last check honestly until the fresh one lands.
     */
    public static void refreshRow(Context c) {
        Context a = app(c);
        if (a == null) return;
        postLine(a);
        fetchManifest(a, false);
    }

    /** A tap on the Settings row: do what the state calls for. */
    public static void onRowTap(Context c) {
        Context a = app(c);
        if (a == null) return;
        RowState s = snapshot(a);
        boolean grantMissing = !canInstall(a);
        switch (decideTap(s, grantMissing)) {
            case IGNORE:
                break;
            case CHECK:
                fetchManifest(a, true);
                break;
            case DOWNLOAD:
                if (s.known != null) startDownload(a, s.known);
                break;
            case INSTALL:
                offerInstall(a);
                break;
            case OPEN_GRANT_SETTING:
                openGrantSetting(a);
                break;
        }
    }

    // ---- state ----

    private static RowState snapshot(Context a) {
        PackageInfo own = ownPackageInfo(a);
        String installedName = own != null && own.versionName != null
                ? own.versionName : "?";
        int installedCode = own != null ? versionCodeOf(own) : 0;
        SharedPreferences p = Prefs.get(a);
        UpdateManifest.Manifest known = null;
        int knownCode = p.getInt(Prefs.KEY_UPDATE_KNOWN_CODE, 0);
        String knownName = p.getString(Prefs.KEY_UPDATE_KNOWN_NAME, "");
        String knownSha = p.getString(Prefs.KEY_UPDATE_KNOWN_SHA, "");
        long knownSize = p.getLong(Prefs.KEY_UPDATE_KNOWN_SIZE, 0);
        if (knownCode > 0 && knownName != null && !knownName.isEmpty()
                && UpdateVerify.isValidSha256Hex(knownSha) && knownSize > 0) {
            String knownApk = p.getString(Prefs.KEY_UPDATE_APK_URL, "");
            if (knownApk == null || knownApk.isEmpty()) {
                knownApk = UpdateManifest.MANIFEST_URL;
            }
            known = new UpdateManifest.Manifest(knownCode, knownName,
                    knownApk, knownSha, knownSize);
        }
        int downloadedCode = p.getInt(Prefs.KEY_UPDATE_DOWNLOADED_CODE, 0);
        String downloadedName = p.getString(Prefs.KEY_UPDATE_DOWNLOADED_NAME, "");
        if (downloadedCode > 0 && !updateFile(a).isFile()) {
            downloadedCode = 0;
            downloadedName = "";
        }
        return new RowState(installedName, installedCode, phase, activeName,
                progressBytes, totalBytes, known,
                p.getLong(Prefs.KEY_UPDATE_LAST_CHECK, 0),
                p.getBoolean(Prefs.KEY_UPDATE_LAST_OK, false),
                p.getString(Prefs.KEY_UPDATE_LAST_ERROR, ""),
                downloadedCode, downloadedName,
                p.getString(Prefs.KEY_UPDATE_FAILURE, ""));
    }

    private static void postLine(Context a) {
        Listener l = listener;
        if (l == null) return;
        RowState s = snapshot(a);
        boolean grantMissing = !canInstall(a);
        String line = rowLine(s, grantMissing);
        handler().post(() -> {
            Listener cur = listener;
            if (cur != null) cur.onLine(line);
        });
    }

    private static void postProgress(Context a) {
        long now = System.currentTimeMillis();
        if (now - lastProgressPostMs < PROGRESS_POST_MIN_MS) return;
        lastProgressPostMs = now;
        postLine(a);
    }

    // ---- manifest fetch ----

    private static void fetchManifest(final Context a, final boolean forced) {
        if (!checkInFlight.compareAndSet(false, true)) {
            postLine(a);
            return;
        }
        phase = Phase.CHECKING;
        postLine(a);
        new Thread(() -> {
            String error = "";
            try {
                String url = UpdateManifest.cacheBustedUrl(
                        UpdateManifest.MANIFEST_URL, System.currentTimeMillis());
                byte[] raw = fetchBytes(url, MANIFEST_CONNECT_MS,
                        MANIFEST_READ_MS, UpdateManifest.MAX_MANIFEST_BYTES);
                String json = new String(raw,
                        java.nio.charset.StandardCharsets.UTF_8);
                UpdateManifest.ParseResult r = UpdateManifest.parse(json);
                if (!r.isOk()) {
                    error = "the update file was invalid";
                } else {
                    String apkUrl = UpdateManifest.resolveApkUrl(
                            UpdateManifest.MANIFEST_URL, r.manifest.apk);
                    if (apkUrl == null) {
                        error = "the update file named an unsafe download";
                    } else {
                        SharedPreferences.Editor e = Prefs.get(a).edit();
                        e.putLong(Prefs.KEY_UPDATE_LAST_CHECK,
                                System.currentTimeMillis());
                        e.putBoolean(Prefs.KEY_UPDATE_LAST_OK, true);
                        e.putString(Prefs.KEY_UPDATE_LAST_ERROR, "");
                        e.putInt(Prefs.KEY_UPDATE_KNOWN_CODE, r.manifest.versionCode);
                        e.putString(Prefs.KEY_UPDATE_KNOWN_NAME, r.manifest.versionName);
                        e.putString(Prefs.KEY_UPDATE_KNOWN_SHA, r.manifest.sha256);
                        e.putLong(Prefs.KEY_UPDATE_KNOWN_SIZE, r.manifest.size);
                        e.putString(Prefs.KEY_UPDATE_APK_URL, apkUrl);
                        e.putString(Prefs.KEY_UPDATE_FAILURE, "");
                        e.apply();
                        // A verified download for an older
                        // manifest is stale once a newer
                        // manifest names a newer version.
                        int downloaded = Prefs.get(a).getInt(
                                Prefs.KEY_UPDATE_DOWNLOADED_CODE, 0);
                        if (downloaded > 0
                                && downloaded < r.manifest.versionCode) {
                            deleteUpdateFiles(a);
                            Prefs.get(a).edit()
                                    .putInt(Prefs.KEY_UPDATE_DOWNLOADED_CODE, 0)
                                    .putString(Prefs.KEY_UPDATE_DOWNLOADED_NAME, "")
                                    .apply();
                        }
                    }
                }
            } catch (Exception e) {
                error = shortFetchError(e);
            }
            if (!error.isEmpty()) {
                SharedPreferences.Editor e = Prefs.get(a).edit();
                e.putLong(Prefs.KEY_UPDATE_LAST_CHECK, System.currentTimeMillis());
                e.putBoolean(Prefs.KEY_UPDATE_LAST_OK, false);
                e.putString(Prefs.KEY_UPDATE_LAST_ERROR, error);
                e.apply();
            }
            phase = Phase.IDLE;
            checkInFlight.set(false);
            postLine(a);
        }, "update-check").start();
    }

    private static String shortFetchError(Exception e) {
        String m = e == null ? "" : String.valueOf(e.getMessage());
        if (m.contains("HTTP ")) return "the update server answered " + m;
        if (m.contains("not a secure")) return "the update address was not secure";
        if (m.contains("too large")) return "the update file was too large";
        if (m.contains("redirect")) return "the update server redirected oddly";
        return "couldn't reach the update file";
    }

    // ---- download + verify + install ----

    private static void startDownload(final Context a,
                                      final UpdateManifest.Manifest known) {
        if (!downloadInFlight.compareAndSet(false, true)) return;
        // The download re-fetches the manifest first: the row's
        // stored copy may be stale, and the checksum the file is
        // verified against must be the fresh one.
        phase = Phase.CHECKING;
        activeName = known.versionName;
        postLine(a);
        new Thread(() -> {
            String failure = "";
            try {
                String url = UpdateManifest.cacheBustedUrl(
                        UpdateManifest.MANIFEST_URL, System.currentTimeMillis());
                byte[] raw = fetchBytes(url, MANIFEST_CONNECT_MS,
                        MANIFEST_READ_MS, UpdateManifest.MAX_MANIFEST_BYTES);
                UpdateManifest.ParseResult r = UpdateManifest.parse(new String(
                        raw, java.nio.charset.StandardCharsets.UTF_8));
                if (!r.isOk()) {
                    failure = "the update file was invalid";
                } else if (!UpdateManifest.isUpdate(r.manifest,
                        versionCodeOf(ownPackageInfo(a)))) {
                    failure = "no newer version is available";
                    SharedPreferences.Editor e = Prefs.get(a).edit();
                    e.putInt(Prefs.KEY_UPDATE_KNOWN_CODE, r.manifest.versionCode);
                    e.putString(Prefs.KEY_UPDATE_KNOWN_NAME, r.manifest.versionName);
                    e.putString(Prefs.KEY_UPDATE_KNOWN_SHA, r.manifest.sha256);
                    e.putLong(Prefs.KEY_UPDATE_KNOWN_SIZE, r.manifest.size);
                    e.apply();
                } else {
                    String apkUrl = UpdateManifest.resolveApkUrl(
                            UpdateManifest.MANIFEST_URL, r.manifest.apk);
                    if (apkUrl == null) {
                        failure = "the update file named an unsafe download";
                    } else {
                        failure = downloadAndVerify(a, r.manifest, apkUrl);
                    }
                }
            } catch (Exception e) {
                failure = shortFetchError(e);
            }
            if (!failure.isEmpty()) {
                Prefs.get(a).edit()
                        .putString(Prefs.KEY_UPDATE_FAILURE, failure).apply();
            }
            phase = Phase.IDLE;
            activeName = "";
            downloadInFlight.set(false);
            postLine(a);
        }, "update-download").start();
    }

    /**
     * Downloads, hashes, and fully verifies the APK. Returns ""
     * on success (the verified file is in place and the row
     * state persisted), or a short failure reason — the file
     * is deleted on every failure path.
     */
    private static String downloadAndVerify(Context a,
                                            UpdateManifest.Manifest m,
                                            String apkUrl) {
        File tmp = tmpFile(a);
        File dst = updateFile(a);
        if (tmp.exists()) tmp.delete();
        if (dst.exists()) dst.delete();
        phase = Phase.DOWNLOADING;
        activeName = m.versionName;
        progressBytes = 0;
        totalBytes = m.size;
        lastProgressPostMs = 0;
        postLine(a);
        HttpsURLConnection conn = null;
        try {
            conn = openHttps(apkUrl, DOWNLOAD_CONNECT_MS, DOWNLOAD_READ_MS);
            long contentLength = conn.getContentLengthLong();
            if (contentLength > 0
                    && contentLength > m.size + DOWNLOAD_SLACK_BYTES) {
                return fail(tmp, "the download was bigger than promised");
            }
            long cap = m.size + DOWNLOAD_SLACK_BYTES;
            try (InputStream in = new BufferedInputStream(conn.getInputStream());
                 OutputStream out = new FileOutputStream(tmp)) {
                byte[] buf = new byte[8192];
                long done = 0;
                int n;
                while ((n = in.read(buf)) >= 0) {
                    if (n > 0) {
                        out.write(buf, 0, n);
                        done += n;
                        if (done > cap) {
                            return fail(tmp,
                                    "the download was bigger than promised");
                        }
                        progressBytes = done;
                        postProgress(a);
                    }
                }
            }
        } catch (Exception e) {
            return fail(tmp, shortFetchError(e));
        } finally {
            if (conn != null) conn.disconnect();
        }
        phase = Phase.VERIFYING;
        postLine(a);
        String failure;
        try {
            String actualSha = sha256Hex(tmp);
            PackageInfo arc = archivePackageInfo(a, tmp);
            if (arc == null) {
                return fail(tmp, "the download was not a readable app package");
            }
            PackageInfo own = ownPackageInfo(a);
            failure = UpdateVerify.verifyDownload(
                    m.sha256, actualSha, m.size, tmp.length(),
                    a.getPackageName(), arc.packageName,
                    m.versionCode, versionCodeOf(arc),
                    certShaHex(own), certShaHex(arc));
            if (failure != null) {
                return fail(tmp, shortVerifyFailure(failure));
            }
            if (!tmp.renameTo(dst)) {
                // renameTo can fail across filesystems; the
                // cache dir is one filesystem, so this is
                // unexpected — treat it as a failure, delete.
                return fail(tmp, "the download could not be kept");
            }
            Prefs.get(a).edit()
                    .putInt(Prefs.KEY_UPDATE_DOWNLOADED_CODE, m.versionCode)
                    .putString(Prefs.KEY_UPDATE_DOWNLOADED_NAME, m.versionName)
                    .putString(Prefs.KEY_UPDATE_FAILURE, "")
                    .apply();
            return "";
        } catch (Exception e) {
            return fail(tmp, "the download could not be verified");
        }
    }

    private static String fail(File tmp, String reason) {
        if (tmp != null && tmp.exists()) tmp.delete();
        return reason;
    }

    /** The verify seam's sentences are long; the row needs the
     *  short form. The mapping keys on the distinguishing word
     *  of each sentence — all five are distinct. */
    private static String shortVerifyFailure(String full) {
        if (full.contains("checksum")) {
            return "the download failed the checksum and was deleted";
        }
        if (full.contains("incomplete")) {
            return "the download arrived incomplete and was deleted";
        }
        if (full.contains("not a Control update")) {
            return "the download was not a Control update and was deleted";
        }
        if (full.contains("not the version")) {
            return "the download was not the promised version and was deleted";
        }
        return "the download was not signed with Control's key and was deleted";
    }

    private static void offerInstall(Context a) {
        File apk = updateFile(a);
        if (!apk.isFile()) {
            Prefs.get(a).edit()
                    .putInt(Prefs.KEY_UPDATE_DOWNLOADED_CODE, 0)
                    .putString(Prefs.KEY_UPDATE_DOWNLOADED_NAME, "")
                    .putString(Prefs.KEY_UPDATE_FAILURE,
                            "the verified download is gone — tap to download again")
                    .apply();
            postLine(a);
            return;
        }
        try {
            Uri uri = ApkShareProvider.register(apk,
                    "Control " + Prefs.get(a).getString(
                            Prefs.KEY_UPDATE_DOWNLOADED_NAME, "") + ".apk");
            Intent intent = new Intent(Intent.ACTION_VIEW);
            intent.setDataAndType(uri, "application/vnd.android.package-archive");
            intent.setFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
                    | Intent.FLAG_ACTIVITY_NEW_TASK);
            a.startActivity(intent);
            // The file is NOT deleted here: the installer reads
            // it through the provider after this returns. The
            // launch cleanup removes it once the new version
            // is installed (see cleanupStale).
        } catch (Exception e) {
            Prefs.get(a).edit().putString(Prefs.KEY_UPDATE_FAILURE,
                    "the installer could not be opened").apply();
            postLine(a);
        }
    }

    private static boolean canInstall(Context a) {
        try {
            return a.getPackageManager().canRequestPackageInstalls();
        } catch (Exception e) {
            return false;
        }
    }

    private static void openGrantSetting(Context a) {
        try {
            Intent intent = new Intent(
                    Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:" + a.getPackageName()));
            intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            a.startActivity(intent);
        } catch (Exception e) {
            Prefs.get(a).edit().putString(Prefs.KEY_UPDATE_FAILURE,
                    "open Settings → Apps → Control → Install unknown apps")
                    .apply();
            postLine(a);
        }
    }

    // ---- cleanup ----

    /**
     * Deletes an update file whose version is already installed
     * (the update landed), or an orphan with no recorded
     * version. A verified file for a NEWER version is kept: the
     * user downloaded it and may still install it.
     */
    static void cleanupStale(Context a) {
        File dst = updateFile(a);
        File tmp = tmpFile(a);
        if (tmp.exists()) tmp.delete();
        if (!dst.isFile()) return;
        int downloaded = Prefs.get(a).getInt(Prefs.KEY_UPDATE_DOWNLOADED_CODE, 0);
        int installed = versionCodeOf(ownPackageInfo(a));
        if (downloaded <= 0 || downloaded <= installed) {
            dst.delete();
            Prefs.get(a).edit()
                    .putInt(Prefs.KEY_UPDATE_DOWNLOADED_CODE, 0)
                    .putString(Prefs.KEY_UPDATE_DOWNLOADED_NAME, "")
                    .apply();
        }
    }

    private static void deleteUpdateFiles(Context a) {
        File dst = updateFile(a);
        if (dst.exists()) dst.delete();
        File tmp = tmpFile(a);
        if (tmp.exists()) tmp.delete();
    }

    private static File updateFile(Context a) {
        return new File(a.getCacheDir(), UPDATE_FILE);
    }

    private static File tmpFile(Context a) {
        return new File(a.getCacheDir(), UPDATE_TMP);
    }

    // ---- package info helpers ----

    private static PackageInfo ownPackageInfo(Context a) {
        try {
            return a.getPackageManager().getPackageInfo(
                    a.getPackageName(), signingFlags());
        } catch (Exception e) {
            return null;
        }
    }

    private static PackageInfo archivePackageInfo(Context a, File apk) {
        try {
            return a.getPackageManager().getPackageArchiveInfo(
                    apk.getAbsolutePath(), signingFlags());
        } catch (Exception e) {
            return null;
        }
    }

    private static int signingFlags() {
        if (Build.VERSION.SDK_INT >= 28) {
            return PackageManager.GET_SIGNING_CERTIFICATES;
        }
        return PackageManager.GET_SIGNATURES;
    }

    private static int versionCodeOf(PackageInfo pi) {
        if (pi == null) return 0;
        if (Build.VERSION.SDK_INT >= 28) {
            long c = pi.getLongVersionCode();
            return c > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) c;
        }
        return pi.versionCode;
    }

    /** SHA-256 hex digests of the package's signing certificates.
     *  Catches Throwable: on old runtimes the signing-info field
     *  does not exist, and that failure must fail closed (an
     *  empty list never matches), never crash. */
    private static List<String> certShaHex(PackageInfo pi) {
        List<String> out = new ArrayList<>();
        if (pi == null) return out;
        try {
            if (Build.VERSION.SDK_INT >= 28) {
                SigningInfo si = pi.signingInfo;
                if (si != null) {
                    for (Signature s : si.getApkContentsSigners()) {
                        out.add(sha256Hex(s.toByteArray()));
                    }
                    return out;
                }
            }
            if (pi.signatures != null) {
                for (Signature s : pi.signatures) {
                    out.add(sha256Hex(s.toByteArray()));
                }
            }
        } catch (Throwable ignored) {
        }
        return out;
    }

    // ---- HTTPS helpers ----

    /**
     * Opens an HTTPS URL, following up to {@link #MAX_REDIRECTS}
     * redirects — each hop re-checked to be HTTPS. The caller
     * owns the returned connection and must disconnect it.
     */
    private static HttpsURLConnection openHttps(String url, int connectMs,
                                                int readMs) throws Exception {
        String current = url;
        for (int hop = 0; hop <= MAX_REDIRECTS; hop++) {
            if (!UpdateManifest.isHttpsUrl(current)) {
                throw new Exception("The update address is not a secure (HTTPS) address.");
            }
            URL u = new URL(current);
            URLConnection raw = u.openConnection();
            if (!(raw instanceof HttpsURLConnection)) {
                throw new Exception("The update address is not a secure (HTTPS) address.");
            }
            HttpsURLConnection conn = (HttpsURLConnection) raw;
            conn.setInstanceFollowRedirects(false);
            conn.setConnectTimeout(connectMs);
            conn.setReadTimeout(readMs);
            conn.setRequestProperty("Accept", "*/*");
            conn.connect();
            int code = conn.getResponseCode();
            if (code >= 200 && code < 300) return conn;
            if (code == 301 || code == 302 || code == 303
                    || code == 307 || code == 308) {
                String loc = conn.getHeaderField("Location");
                conn.disconnect();
                if (loc == null || loc.isEmpty()) {
                    throw new Exception("The update server redirected without an address.");
                }
                current = new URL(u, loc).toString();
                continue;
            }
            conn.disconnect();
            throw new Exception("The update server answered HTTP " + code + ".");
        }
        throw new Exception("The update server redirected too many times.");
    }

    private static byte[] fetchBytes(String url, int connectMs, int readMs,
                                     int maxBytes) throws Exception {
        HttpsURLConnection conn = null;
        try {
            conn = openHttps(url, connectMs, readMs);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            try (InputStream in = new BufferedInputStream(conn.getInputStream())) {
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) >= 0) {
                    out.write(buf, 0, n);
                    if (out.size() > maxBytes) {
                        throw new Exception("The update file was too large.");
                    }
                }
            }
            return out.toByteArray();
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    private static String sha256Hex(File f) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        try (InputStream in = new FileInputStream(f)) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) >= 0) md.update(buf, 0, n);
        }
        return hex(md.digest());
    }

    private static String sha256Hex(byte[] data) throws Exception {
        return hex(MessageDigest.getInstance("SHA-256").digest(data));
    }

    private static String hex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) sb.append(String.format("%02x", b & 0xff));
        return sb.toString();
    }
}
