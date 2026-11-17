package app.control.launcher;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.URL;
import java.net.URLConnection;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import javax.net.ssl.HttpsURLConnection;

/**
 * The GitHub-hosted word dictionary (v1.29): background
 * fetch, verified local build, learned words, and taught
 * sets.
 *
 * <p>The list itself lives in a public GitHub repo and is
 * READ-ONLY from the phone: this class never writes to the
 * repo and holds no GitHub token. It fetches version.json,
 * and only when the version or checksum differs downloads
 * dictionary.txt, verifies the SHA-256 against version.json
 * BEFORE use, and builds the packed blob in app-private
 * storage with an atomic temp+rename. A half-written or
 * corrupt file is never used: the codec falls back to the
 * built-in 1,200-word list, and corruption triggers a
 * background rebuild.</p>
 *
 * <p>WRITE-BACK (v1.30): learned words flow back through the
 * RELAY, never to GitHub directly. The phone proposes its
 * learned words to POST /v1/dict/proposals (signed, same as
 * every relay call); a merge job off the phone reads the
 * queue, appends clean words to the repo file, and acks
 * them. Proposals are batched, throttled to one attempt per
 * hour, and silent on failure; a word is proposed once,
 * ever, from this phone. Merges are batched hourly — the
 * status line says "proposed", never "merged".</p>
 *
 * <p>Learned words (seen in sent or received text, not in
 * the list) and taught sets (which scopes know which
 * learned codes) live in separate files, independently
 * versioned from the base list, so a list rebuild never
 * loses them. Both stay on-device: they are never
 * uploaded and never part of a profile export.</p>
 *
 * <p>Every public method degrades safely when the store
 * was never initialized (the harness never calls init):
 * encoding falls back to the plain codec, receiving notes
 * nothing, and the status line reports the built-in list.</p>
 */
public final class DictStore {
    private static final String VERSION_URL =
            "https://raw.githubusercontent.com/doeyeknow/ControlDictionary/main/version.json";
    private static final String DICT_URL =
            "https://raw.githubusercontent.com/doeyeknow/ControlDictionary/main/dictionary.txt";

    private static final String FILE_BASE = "dict-base.blob";
    private static final String FILE_LEARNED = "dict-learned.txt";
    private static final String FILE_TAUGHT = "dict-taught.txt";
    private static final String FILE_SUBMITTED = "dict-submitted.txt";

    private static final long CHECK_INTERVAL_MS = 60L * 60L * 1000L;
    private static final int MANIFEST_MAX_BYTES = 64 * 1024;
    private static final int DICT_MAX_BYTES = 16 * 1024 * 1024;
    private static final int SMALL_FILE_MAX_BYTES = 2 * 1024 * 1024;
    private static final int MAX_REDIRECTS = 5;
    private static final int MANIFEST_CONNECT_MS = 15000;
    private static final int MANIFEST_READ_MS = 20000;
    private static final int DOWNLOAD_CONNECT_MS = 30000;
    private static final int DOWNLOAD_READ_MS = 60000;

    private static final Object LOCK = new Object();
    private static volatile Context appContext;
    private static volatile ExecutorService executor;
    private static volatile boolean loaded;
    private static volatile boolean fetching;
    private static volatile boolean building;
    private static volatile boolean submitting;
    private static volatile boolean hasFull;
    private static final TaughtStore taughtStore = new TaughtStore();
    /** Words this phone already proposed. Executor-confined:
     *  loaded in loadLocal, read/written only by the submit
     *  tick — both run on the single executor thread. */
    private static final Set<String> submitted = new HashSet<>();

    private DictStore() {}

    /** Idempotent. Queues the local-state load on the
     *  background thread; never blocks the caller. */
    public static void init(Context context) {
        if (context == null) return;
        synchronized (LOCK) {
            if (appContext != null) return;
            appContext = context.getApplicationContext();
        }
        executor().execute(DictStore::loadLocal);
    }

    private static ExecutorService executor() {
        ExecutorService e = executor;
        if (e == null) {
            synchronized (LOCK) {
                e = executor;
                if (e == null) {
                    e = Executors.newSingleThreadExecutor();
                    executor = e;
                }
            }
        }
        return e;
    }

    private static boolean initialized() {
        return appContext != null;
    }

    /** Launch hook: init plus a throttled background check
     *  (about once an hour; always on first launch, when no
     *  local build exists yet). Failures are silent — the
     *  status line is the only witness. */
    public static void onAppLaunch(Context context) {
        init(context);
        if (!initialized()) return;
        executor().execute(() -> {
            // Runs after loadLocal on the same single
            // thread, so the local state is settled.
            try {
                SharedPreferences p = Prefs.get(appContext);
                long last = p.getLong(Prefs.KEY_DICT_LAST_CHECK, 0L);
                boolean due = System.currentTimeMillis() - last > CHECK_INTERVAL_MS;
                if (due || !hasFull) checkForUpdate();
            } catch (Exception ignored) {
            }
            // Best-effort write-back: throttled inside, and
            // silent when there is nothing to propose.
            maybeSubmitProposals();
        });
    }

    /** Forces a background check now, invoking
     *  {@code onDone} on the main thread when it settles
     *  (success or failure). Safe to call uninitialized. */
    public static void checkNow(Runnable onDone) {
        if (!initialized()) {
            postMain(onDone);
            return;
        }
        executor().execute(() -> {
            checkForUpdate();
            postMain(onDone);
        });
    }

    private static void postMain(Runnable r) {
        if (r == null) return;
        try {
            new Handler(Looper.getMainLooper()).post(r);
        } catch (Exception e) {
            try {
                r.run();
            } catch (Exception ignored) {
            }
        }
    }

    /** One line for the Settings row: the version, the word
     *  count, and the last-check status, honestly. "Proposed"
     *  counts words this phone sent to the relay queue —
     *  never "merged": merges happen off the phone, batched
     *  hourly, and this phone cannot see them happen. */
    public static String statusLine() {
        try {
            if (!initialized()) return "Using the built-in list";
            if (building) return "Building…";
            SharedPreferences p = Prefs.get(appContext);
            String err = p.getString(Prefs.KEY_DICT_LAST_ERROR, null);
            int proposed = p.getInt(Prefs.KEY_DICT_SUBMITTED_COUNT, 0);
            String prop = proposed > 0
                    ? " · " + group(proposed) + " proposed" : "";
            if (hasFull) {
                long version = p.getLong(Prefs.KEY_DICT_VERSION, 0L);
                long count = p.getLong(Prefs.KEY_DICT_COUNT, 0L);
                int learned = WordDict.activeTable().learnedCount();
                String s = "Ready — v" + version + " · "
                        + group(count) + " words";
                if (learned > 0) s += " · " + group(learned) + " learned";
                s += prop;
                if (err != null && !err.isEmpty()) s += " · last check failed";
                return s;
            }
            if (err != null && !err.isEmpty()) {
                return "Check failed — using the built-in list" + prop;
            }
            if (!loaded) return "Building…";
            return "Using the built-in list" + prop;
        } catch (Exception e) {
            return "Using the built-in list";
        }
    }

    private static String group(long n) {
        String s = Long.toString(n);
        StringBuilder out = new StringBuilder();
        int lead = s.length() % 3;
        if (lead == 0) lead = 3;
        out.append(s, 0, lead);
        for (int i = lead; i < s.length(); i += 3) {
            out.append(',').append(s, i, i + 3);
        }
        return out.toString();
    }

    // ---- encode / receive ----

    /**
     * Encodes a message for a scope (a peer or channel).
     * New words in the text are learned first; the result
     * always carries the wire form (v1.32 codes every
     * message), the wire generation, and the teach block
     * for words this scope has not been taught yet.
     * Learned and taught state persist in the background.
     * A null or invalid scope means no teaching: base
     * codes only, the teach block dropped. Never throws;
     * falls back to the plain codec when uninitialized or
     * on any failure.
     */
    public static WordDict.Encoded encodeForScope(String scope, String text) {
        if (text == null || text.isEmpty()) return null;
        if (!initialized()) return fallbackEncode(text);
        try {
            WordTable table = WordDict.activeTable();
            if (table.learnFromText(text) > 0) {
                persistLearnedAsync(table);
                queueSubmitTick();
            }
            if (scope == null || scope.isEmpty() || !TaughtStore.validScope(scope)) {
                return fallbackEncode(text);
            }
            WordDict.Encoded e = WordDict.encodeScoped(text, taughtStore.forScope(scope));
            if (e == null) return null;
            if (!e.newlyTaught.isEmpty()) {
                taughtStore.markTaught(scope, e.newlyTaught);
                persistTaughtAsync();
            }
            return e;
        } catch (Exception ex) {
            return fallbackEncode(text);
        }
    }

    /** Base codes only, no teaching — the wire form and
     *  the correct wire generation, or null for empty
     *  input. */
    private static WordDict.Encoded fallbackEncode(String text) {
        try {
            WordDict.Encoded e = WordDict.encodeScoped(text, new HashSet<Integer>());
            if (e == null) return null;
            return new WordDict.Encoded(e.wire, e.version, "",
                    new HashSet<Integer>());
        } catch (Exception ex) {
            return null;
        }
    }

    /**
     * Notes a received message: clean decodes teach the
     * table their new words, and adopted teach-block codes
     * mark the sender's scope taught (the sender knows them
     * by construction). Noted messages — undecoded, newer,
     * or corrupt — teach nothing. Never throws.
     */
    public static void noteReceived(String scope, WordDict.Result result) {
        if (result == null || !initialized()) return;
        try {
            if (result.note == null && result.text != null && !result.text.isEmpty()) {
                WordTable table = WordDict.activeTable();
                if (table.learnFromText(result.text) > 0) {
                    persistLearnedAsync(table);
                    queueSubmitTick();
                }
            }
            if (scope != null && !scope.isEmpty() && TaughtStore.validScope(scope)
                    && result.adopted != null && !result.adopted.isEmpty()) {
                taughtStore.markTaught(scope, result.adopted);
                persistTaughtAsync();
            }
        } catch (Exception ignored) {
        }
    }

    // ---- local state ----

    /** Loads the base blob, learned words, and taught sets.
     *  Runs once, on the background thread. A blob that does
     *  not parse, or does not match the recorded count, is
     *  corruption: the built-in list stays active and a
     *  rebuild is queued. */
    private static void loadLocal() {
        try {
            Context c = appContext;
            if (c == null) return;
            byte[] taughtBytes = readFile(c, FILE_TAUGHT, SMALL_FILE_MAX_BYTES);
            if (taughtBytes != null) {
                taughtStore.load(new String(taughtBytes, StandardCharsets.UTF_8));
            }
            byte[] submittedBytes = readFile(c, FILE_SUBMITTED, SMALL_FILE_MAX_BYTES);
            if (submittedBytes != null) {
                for (String line : new String(submittedBytes,
                        StandardCharsets.UTF_8).split("\n")) {
                    String w = line.trim();
                    // Only well-shaped words: a corrupt line
                    // never poisons the set, and a word that
                    // could never be proposed is not worth
                    // remembering as proposed.
                    if (DictSubmit.submittable(w)) submitted.add(w);
                }
            }
            byte[] blob = readFile(c, FILE_BASE, DictFile.MAX_BLOB_BYTES);
            WordTable table = null;
            if (blob != null) {
                DictFile.Parsed parsed = DictFile.parse(blob);
                if (parsed != null) {
                    long metaCount = Prefs.get(c).getLong(Prefs.KEY_DICT_COUNT, -1L);
                    if (metaCount == -1L || parsed.count() == metaCount) {
                        table = new WordTable(parsed);
                    }
                }
            }
            if (table != null) {
                byte[] learnedBytes = readFile(c, FILE_LEARNED, SMALL_FILE_MAX_BYTES);
                if (learnedBytes != null) {
                    table.loadLearned(new String(learnedBytes, StandardCharsets.UTF_8));
                }
                WordDict.setActiveTable(table);
                hasFull = true;
            } else if (blob != null) {
                executor().execute(DictStore::checkForUpdate);
            }
        } catch (Exception ignored) {
        } finally {
            loaded = true;
        }
    }

    private static void persistLearnedAsync(WordTable table) {
        String text;
        try {
            text = table.serializeLearned();
        } catch (Exception e) {
            return;
        }
        persistAsync(FILE_LEARNED, text.getBytes(StandardCharsets.UTF_8));
    }

    private static void persistTaughtAsync() {
        String text;
        try {
            text = taughtStore.serialize();
        } catch (Exception e) {
            return;
        }
        persistAsync(FILE_TAUGHT, text.getBytes(StandardCharsets.UTF_8));
    }

    /** Queues a write-back tick on the executor. Cheap when
     *  nothing is due: the throttle check inside is the only
     *  work. Safe to call uninitialized. */
    private static void queueSubmitTick() {
        if (!initialized()) return;
        try {
            executor().execute(DictStore::maybeSubmitProposals);
        } catch (Exception ignored) {
        }
    }

    private static void persistSubmittedAsync() {
        String text;
        try {
            java.util.List<String> words = new java.util.ArrayList<>(submitted);
            java.util.Collections.sort(words);
            StringBuilder sb = new StringBuilder();
            for (String w : words) sb.append(w).append('\n');
            text = sb.toString();
        } catch (Exception e) {
            return;
        }
        persistAsync(FILE_SUBMITTED, text.getBytes(StandardCharsets.UTF_8));
    }

    // ---- write-back (v1.30) ----

    /**
     * One proposal attempt, on the executor thread. Gates, in
     * order: local state loaded, device registered with the
     * relay, throttle window passed, unsubmitted words exist.
     * The attempt timestamp is recorded BEFORE the call, so
     * failures pace retries exactly like successes — at most
     * one attempt per hour, whatever happens. On a 201 the
     * whole batch is marked submitted (the server is
     * all-or-nothing) and persisted; every failure is silent
     * and retried at the next window. Never throws, never
     * blocks sending or any UI: callers queue this, and the
     * launch hook runs it after the dictionary check.
     */
    private static void maybeSubmitProposals() {
        Context c = appContext;
        if (c == null || !loaded) return;
        synchronized (LOCK) {
            if (submitting) return;
            submitting = true;
        }
        try {
            SharedPreferences p = Prefs.get(c);
            if (!p.getBoolean(Prefs.KEY_RELAY_REGISTERED, false)) return;
            DictSubmit.Plan plan = DictSubmit.plan(
                    WordDict.activeTable().learnedWordsByIndex(), submitted,
                    System.currentTimeMillis(),
                    p.getLong(Prefs.KEY_DICT_LAST_SUBMIT, 0L),
                    DictSubmit.MAX_BATCH);
            if (!plan.due) return;
            p.edit().putLong(Prefs.KEY_DICT_LAST_SUBMIT,
                    System.currentTimeMillis()).apply();
            int accepted = RelayClient.core(c).proposeWords(plan.batch);
            if (accepted <= 0) return;
            submitted.addAll(plan.batch);
            persistSubmittedAsync();
            p.edit().putInt(Prefs.KEY_DICT_SUBMITTED_COUNT, submitted.size()).apply();
        } catch (RelayCore.RelayException re) {
            if ("unregistered_device".equals(re.code)) {
                // The server no longer knows this key: let
                // the normal registration flow re-register
                // instead of failing every window.
                try {
                    Prefs.get(c).edit()
                            .putBoolean(Prefs.KEY_RELAY_REGISTERED, false).apply();
                } catch (Exception ignored) {
                }
            }
            // Every other refusal is silent; next window retries.
        } catch (Exception ignored) {
        } finally {
            synchronized (LOCK) {
                submitting = false;
            }
        }
    }

    private static void persistAsync(String name, byte[] bytes) {
        Context c = appContext;
        if (c == null) return;
        executor().execute(() -> {
            try {
                writeAtomically(c, name, bytes);
            } catch (Exception ignored) {
            }
        });
    }

    private static byte[] readFile(Context c, String name, int maxBytes) {
        File f = new File(c.getFilesDir(), name);
        if (!f.isFile() || f.length() > maxBytes) return null;
        try (FileInputStream in = new FileInputStream(f);
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) >= 0) {
                out.write(buf, 0, n);
                if (out.size() > maxBytes) return null;
            }
            return out.toByteArray();
        } catch (Exception e) {
            return null;
        }
    }

    /** Temp file + rename, so a crash mid-write never
     *  leaves a half-written file in place. */
    private static void writeAtomically(Context c, String name, byte[] bytes) throws Exception {
        File dir = c.getFilesDir();
        File tmp = new File(dir, name + ".tmp");
        File target = new File(dir, name);
        try (FileOutputStream out = new FileOutputStream(tmp)) {
            out.write(bytes);
            out.getFD().sync();
        }
        if (!tmp.renameTo(target)) {
            tmp.delete();
            throw new Exception("Could not save the dictionary file.");
        }
    }

    // ---- background fetch ----

    /** One fetch cycle, on the background thread.
     *  Single-flight: concurrent callers collapse into the
     *  run already in progress. Every failure records a
     *  short reason and keeps the old file. */
    private static void checkForUpdate() {
        Context c = appContext;
        if (c == null) return;
        synchronized (LOCK) {
            if (fetching) return;
            fetching = true;
        }
        building = true;
        try {
            String stamp = "?t=" + System.currentTimeMillis();
            byte[] manifestBytes = fetchBytes(VERSION_URL + stamp,
                    MANIFEST_CONNECT_MS, MANIFEST_READ_MS, MANIFEST_MAX_BYTES);
            JSONObject manifest = new JSONObject(
                    new String(manifestBytes, StandardCharsets.UTF_8));
            int version = manifest.getInt("version");
            long count = manifest.getLong("count");
            String sha = manifest.getString("sha256");
            int format = manifest.optInt("format", 1);
            if (version < 1 || count < 1 || count > DictFile.MAX_WORDS
                    || sha == null || !sha.matches("[0-9a-f]{64}") || format != 1) {
                throw new Exception("The dictionary list was not in the expected form.");
            }
            SharedPreferences p = Prefs.get(c);
            long localVersion = p.getLong(Prefs.KEY_DICT_VERSION, 0L);
            String localSha = p.getString(Prefs.KEY_DICT_SHA, null);
            if (hasFull && version == localVersion && sha.equals(localSha)) {
                markChecked(p, null);
                return;
            }
            byte[] dictBytes = fetchBytes(DICT_URL + stamp,
                    DOWNLOAD_CONNECT_MS, DOWNLOAD_READ_MS, DICT_MAX_BYTES);
            String actual = sha256Hex(dictBytes);
            if (!actual.equals(sha)) {
                throw new Exception("The dictionary download did not match its checksum.");
            }
            byte[] blob = DictFile.build(dictBytes);
            if (blob == null) {
                throw new Exception("The dictionary list could not be built.");
            }
            DictFile.Parsed parsed = DictFile.parse(blob);
            if (parsed == null || parsed.count() != count) {
                throw new Exception("The dictionary list had the wrong word count.");
            }
            writeAtomically(c, FILE_BASE, blob);
            p.edit()
                    .putLong(Prefs.KEY_DICT_VERSION, version)
                    .putLong(Prefs.KEY_DICT_COUNT, count)
                    .putString(Prefs.KEY_DICT_SHA, sha)
                    .apply();
            WordTable table = new WordTable(parsed);
            WordTable.LearnedState snap = WordDict.activeTable().snapshotLearned();
            table.restoreLearned(snap);
            WordDict.setActiveTable(table);
            hasFull = true;
            markChecked(p, null);
        } catch (Exception e) {
            try {
                markChecked(Prefs.get(c), shortReason(e));
            } catch (Exception ignored) {
            }
        } finally {
            building = false;
            synchronized (LOCK) {
                fetching = false;
            }
        }
    }

    private static void markChecked(SharedPreferences p, String error) {
        p.edit()
                .putLong(Prefs.KEY_DICT_LAST_CHECK, System.currentTimeMillis())
                .putString(Prefs.KEY_DICT_LAST_ERROR, error)
                .apply();
    }

    private static String shortReason(Exception e) {
        String m = e == null ? null : e.getMessage();
        if (m == null || m.isEmpty()) return "Check failed";
        if (m.length() > 80) m = m.substring(0, 80);
        return m;
    }

    /**
     * Opens an HTTPS URL, following up to MAX_REDIRECTS
     * redirects — each hop re-checked to be HTTPS (the
     * UpdateManager pattern). The caller owns the returned
     * connection and must disconnect it.
     */
    private static HttpsURLConnection openHttps(String url, int connectMs,
                                                int readMs) throws Exception {
        String current = url;
        for (int hop = 0; hop <= MAX_REDIRECTS; hop++) {
            if (!current.startsWith("https://")) {
                throw new Exception("The dictionary address is not a secure (HTTPS) address.");
            }
            URL u = new URL(current);
            URLConnection raw = u.openConnection();
            if (!(raw instanceof HttpsURLConnection)) {
                throw new Exception("The dictionary address is not a secure (HTTPS) address.");
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
                    throw new Exception("The dictionary server redirected without an address.");
                }
                current = new URL(u, loc).toString();
                continue;
            }
            conn.disconnect();
            throw new Exception("The dictionary server answered HTTP " + code + ".");
        }
        throw new Exception("The dictionary server redirected too many times.");
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
                        throw new Exception("The dictionary file was too large.");
                    }
                }
            }
            return out.toByteArray();
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    private static String sha256Hex(byte[] bytes) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        byte[] d = md.digest(bytes);
        char[] hex = "0123456789abcdef".toCharArray();
        StringBuilder sb = new StringBuilder(64);
        for (byte b : d) {
            sb.append(hex[(b >> 4) & 0xf]).append(hex[b & 0xf]);
        }
        return sb.toString();
    }
}
