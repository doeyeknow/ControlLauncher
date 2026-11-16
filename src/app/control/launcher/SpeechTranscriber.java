package app.control.launcher;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import org.vosk.LibVosk;
import org.vosk.LogLevel;
import org.vosk.Model;
import org.vosk.Recognizer;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * On-device speech transcription for walkie-talkie low-data mode,
 * built on Vosk 0.3.45 (Kaldi-based, Apache 2.0) with the small
 * US-English model. Everything here runs with NO connection: the
 * model is downloaded ONCE, verified, and stored device-local, and
 * every transcription after that is local computation.
 *
 * Why Vosk and not Android's SpeechRecognizer: the framework
 * recognizer is device-dependent and usually phones home to a
 * cloud service even with EXTRA_PREFER_OFFLINE — shipping it
 * labeled "offline" would be a lie. Vosk is a real offline engine
 * with a Java API (JNA) that vendors into this manual build the
 * way Bouncy Castle did: classes in libs/, native libs in
 * native-libs/ (arm64-v8a + armeabi-v7a).
 *
 * The model is deliberately NOT bundled in the APK (68 MB
 * unpacked): it downloads on first enabling low-data mode, with
 * the size shown honestly before the download starts and real
 * progress during it. The zip's SHA-256 is pinned below — a
 * download that doesn't match is deleted, never unpacked.
 *
 * Threading: one single-thread executor owns the Model (Vosk
 * models are shareable, recognizers are not thread-safe, so one
 * recognizer is created per transcription and closed when done).
 * All callbacks fire on the main thread.
 */
public final class SpeechTranscriber {
    /** The only model this build knows: small US English, 16 kHz. */
    public static final String MODEL_URL =
            "https://alphacephei.com/vosk/models/vosk-model-small-en-us-0.15.zip";
    public static final long MODEL_ZIP_BYTES = 41205931L;
    /** Shown before download; 41,205,931 bytes ≈ 39 MiB. */
    public static final String MODEL_DISPLAY_SIZE = "39 MB";
    public static final String MODEL_ZIP_SHA256 =
            "30f26242c4eb449f948e42cb302dd7a686cb29a3423a8367f99ff41780942498";

    private static final String MODEL_DIR = "vosk-model";
    private static final String ZIP_TMP = "vosk-model.zip.tmp";
    private static final String DIR_TMP = "vosk-model.tmp";
    private static final String[] MODEL_MARKERS = {
            "am/final.mdl", "graph/Gr.fst", "conf/model.conf"
    };

    public interface DownloadCb {
        void onProgress(long done, long total);
        void onDone();
        void onError(String reason);
    }

    public interface TranscribeCb {
        void onResult(VoskResult result);
        void onError(String reason);
    }

    private final ExecutorService exec = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "vosk");
        t.setDaemon(true);
        return t;
    });
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final AtomicBoolean cancelDownload = new AtomicBoolean(false);
    private Model model;
    private String engineFailure;

    // ---- model presence ----

    public static File modelDir(Context c) {
        return new File(c.getFilesDir(), MODEL_DIR);
    }

    /** True when a complete, verified model sits on disk. The
     *  filesystem is the source of truth — no pref caches this. */
    public static boolean isModelReady(Context c) {
        File dir = modelDir(c);
        if (!dir.isDirectory()) return false;
        for (String marker : MODEL_MARKERS) {
            if (!new File(dir, marker).isFile()) return false;
        }
        return true;
    }

    // ---- download ----

    public void downloadModel(final Context context, final DownloadCb cb) {
        final Context app = context.getApplicationContext();
        cancelDownload.set(false);
        exec.execute(() -> {
            try {
                downloadAndInstall(app, cb);
            } catch (Exception e) {
                postError(cb, cleanReason(e));
            }
        });
    }

    public void cancelDownload() {
        cancelDownload.set(true);
    }

    private void downloadAndInstall(Context app, DownloadCb cb) throws Exception {
        File zipTmp = new File(app.getFilesDir(), ZIP_TMP);
        File dirTmp = new File(app.getFilesDir(), DIR_TMP);
        deleteRecursively(dirTmp);
        if (zipTmp.exists()) zipTmp.delete();
        try {
            // 1. Download with real progress.
            HttpURLConnection conn = null;
            try {
                conn = (HttpURLConnection) new URL(MODEL_URL).openConnection();
                conn.setConnectTimeout(30000);
                conn.setReadTimeout(30000);
                conn.setInstanceFollowRedirects(true);
                conn.connect();
                int code = conn.getResponseCode();
                if (code < 200 || code >= 300) {
                    throw new Exception("The model server answered HTTP " + code + ".");
                }
                long total = conn.getContentLengthLong();
                if (total <= 0) total = MODEL_ZIP_BYTES;
                final long totalF = total;
                try (InputStream in = new BufferedInputStream(conn.getInputStream());
                     OutputStream out = new FileOutputStream(zipTmp)) {
                    byte[] buf = new byte[8192];
                    long done = 0;
                    int n;
                    while ((n = in.read(buf)) >= 0) {
                        if (cancelDownload.get()) {
                            throw new Cancelled();
                        }
                        if (n > 0) {
                            out.write(buf, 0, n);
                            done += n;
                            final long doneF = done;
                            ui.post(() -> cb.onProgress(doneF, totalF));
                        }
                    }
                }
            } finally {
                if (conn != null) conn.disconnect();
            }
            if (cancelDownload.get()) throw new Cancelled();
            // 2. Verify the pinned hash — a mismatch is deleted, never unpacked.
            String actual = sha256Hex(zipTmp);
            if (!MODEL_ZIP_SHA256.equalsIgnoreCase(actual)) {
                throw new Exception("The download didn't match the expected "
                        + "checksum, so it was deleted. Try again.");
            }
            // 3. Unzip, stripping the top-level folder, with a zip-slip guard.
            dirTmp.mkdirs();
            String canonicalBase = dirTmp.getCanonicalPath() + File.separator;
            try (ZipInputStream zin = new ZipInputStream(
                    new BufferedInputStream(new FileInputStream(zipTmp)))) {
                ZipEntry entry;
                byte[] buf = new byte[8192];
                while ((entry = zin.getNextEntry()) != null) {
                    String name = entry.getName();
                    int slash = name.indexOf('/');
                    if (slash < 0) continue; // the top-level folder entry itself
                    String rel = name.substring(slash + 1);
                    if (rel.isEmpty()) continue;
                    File out = new File(dirTmp, rel);
                    if (!out.getCanonicalPath().startsWith(canonicalBase)) {
                        throw new Exception("The model archive has an unsafe entry.");
                    }
                    if (entry.isDirectory()) {
                        out.mkdirs();
                    } else {
                        File parent = out.getParentFile();
                        if (parent != null) parent.mkdirs();
                        try (OutputStream outStream = new FileOutputStream(out)) {
                            int n;
                            while ((n = zin.read(buf)) >= 0) outStream.write(buf, 0, n);
                        }
                    }
                    zin.closeEntry();
                }
            }
            // 4. Verify the markers, then swap the directory into place.
            for (String marker : MODEL_MARKERS) {
                if (!new File(dirTmp, marker).isFile()) {
                    throw new Exception("The downloaded model is incomplete "
                            + "(missing " + marker + ").");
                }
            }
            File dir = modelDir(app);
            deleteRecursively(dir);
            if (!dirTmp.renameTo(dir)) {
                throw new Exception("Couldn't install the model on this phone.");
            }
            ui.post(cb::onDone);
        } catch (Cancelled c) {
            ui.post(() -> cb.onError("Download cancelled."));
        } finally {
            if (zipTmp.exists()) zipTmp.delete();
            deleteRecursively(dirTmp);
        }
    }

    private static final class Cancelled extends Exception {
    }

    // ---- transcription ----

    /**
     * Transcribes 16-bit mono 16 kHz PCM bytes. The model loads
     * lazily on first use (a second or two); later calls reuse it.
     * onError carries a plain reason — engine unavailable, model
     * missing, or a native failure — never a silent drop.
     */
    public void transcribe(final Context context, final byte[] pcm16,
                           final TranscribeCb cb) {
        final Context app = context.getApplicationContext();
        exec.execute(() -> {
            try {
                if (!isModelReady(app)) {
                    postTxError(cb, "The speech model isn't downloaded yet.");
                    return;
                }
                Model m = ensureModel(app);
                if (m == null) {
                    postTxError(cb, engineFailure != null ? engineFailure
                            : "The speech engine couldn't start on this phone.");
                    return;
                }
                Recognizer rec = new Recognizer(m, 16000.0f);
                try {
                    rec.setWords(true);
                    int off = 0;
                    while (off < pcm16.length) {
                        int n = Math.min(4000, pcm16.length - off);
                        byte[] chunk = new byte[n];
                        System.arraycopy(pcm16, off, chunk, 0, n);
                        rec.acceptWaveForm(chunk, n);
                        off += n;
                    }
                    String finalJson = rec.getFinalResult();
                    VoskResult result = VoskResult.summarize(finalJson);
                    ui.post(() -> cb.onResult(result));
                } finally {
                    try {
                        rec.close();
                    } catch (Exception ignored) {
                    }
                }
            } catch (Throwable t) {
                postTxError(cb, cleanReason(t));
            }
        });
    }

    /** Worker thread only. Returns null (with engineFailure set) when
     *  the native engine can't start — e.g. no libvosk.so for this
     *  phone's ABI, which surfaces as UnsatisfiedLinkError. */
    private synchronized Model ensureModel(Context app) {
        if (model != null) return model;
        if (engineFailure != null) return null;
        try {
            LibVosk.setLogLevel(LogLevel.WARNINGS);
            model = new Model(modelDir(app).getAbsolutePath());
            return model;
        } catch (Throwable t) {
            engineFailure = "The speech engine couldn't start on this phone ("
                    + t.getClass().getSimpleName()
                    + (t.getMessage() != null ? ": " + t.getMessage() : "") + ").";
            return null;
        }
    }

    public void shutdown() {
        cancelDownload.set(true);
        exec.execute(() -> {
            if (model != null) {
                try {
                    model.close();
                } catch (Exception ignored) {
                }
                model = null;
            }
        });
        exec.shutdown();
    }

    // ---- helpers ----

    private void postError(DownloadCb cb, String reason) {
        ui.post(() -> cb.onError(reason));
    }

    private void postTxError(TranscribeCb cb, String reason) {
        ui.post(() -> cb.onError(reason));
    }

    private static String cleanReason(Throwable t) {
        String m = t.getMessage();
        if (m == null || m.isEmpty()) m = t.getClass().getSimpleName();
        if (m.length() > 200) m = m.substring(0, 200);
        return m;
    }

    private static String sha256Hex(File f) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        try (InputStream in = new BufferedInputStream(new FileInputStream(f))) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) >= 0) md.update(buf, 0, n);
        }
        StringBuilder sb = new StringBuilder();
        for (byte b : md.digest()) sb.append(String.format("%02x", b));
        return sb.toString();
    }

    private static void deleteRecursively(File f) {
        if (f == null || !f.exists()) return;
        if (f.isDirectory()) {
            File[] kids = f.listFiles();
            if (kids != null) {
                for (File kid : kids) deleteRecursively(kid);
            }
        }
        f.delete();
    }
}
