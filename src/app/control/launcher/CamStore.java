package app.control.launcher;

import android.content.Context;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.util.ArrayList;
import java.util.List;

import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;

/**
 * Camera storage (v1.31): the device-local camera list.
 *
 * - One JSON file, {@code cameras.json} in app-private
 *   storage. It is NOT part of the portable profile:
 *   profile export/import/wipe touch only
 *   {@code profile.json}, so camera addresses and
 *   credentials never travel with a profile. The UI says
 *   so on the Cameras page.
 * - Credentials are stored ONLY as AES-256-GCM ciphertext
 *   under an Android Keystore key (alias
 *   {@code control-cameras}, a separate alias from the
 *   wallet's — a wallet key problem must never take the
 *   cameras down with it, or vice versa). The seal/unseal
 *   path is the wallet's {@link WalletCrypto} core, the
 *   same cipher sourcing that survived the v1.12
 *   correction — no second crypto scheme. The key is NOT
 *   bound to user authentication (same reasoning as the
 *   wallet: the app gates display, the keystore keeps the
 *   ciphertext useless off this device).
 * - Credentials are never logged, never written in the
 *   clear anywhere, and never leave the device except in
 *   an Authorization header to the camera itself (or, for
 *   a shared camera, nowhere at all — the proxy serves
 *   JPEG bytes only).
 * - Last-good images live beside the file, one
 *   {@code cam-last/<id>.jpg} per camera, written
 *   atomically on each good fetch and deleted with the
 *   camera. They let an unreachable camera show its last
 *   picture honestly instead of a blank tile.
 * - Writes are atomic (tmp file + rename, the OwnLog /
 *   WalletStore pattern).
 */
public final class CamStore {

    private static final String FILE_NAME = "cameras.json";
    private static final String LAST_DIR = "cam-last";
    private static final String KEY_ALIAS = "control-cameras";
    private static final int MAX_FILE_BYTES = 256_000;

    private CamStore() {}

    // ---- key (WalletStore's spec, its own alias) ----

    private static SecretKey key() throws Exception {
        KeyStore ks = KeyStore.getInstance("AndroidKeyStore");
        ks.load(null);
        if (ks.containsAlias(KEY_ALIAS)) {
            return (SecretKey) ks.getKey(KEY_ALIAS, null);
        }
        KeyGenerator gen = KeyGenerator.getInstance(
                KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
        gen.init(new KeyGenParameterSpec.Builder(KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .setRandomizedEncryptionRequired(true)
                .build());
        return gen.generateKey();
    }

    // ---- credentials through the wallet's crypto core ----

    /** Seals "username\npassword" for storage. Empty
     *  credentials seal to "" (no blob for a camera that
     *  needs no login). */
    public static String sealCreds(String username, String password) throws Exception {
        if ((username == null || username.isEmpty())
                && (password == null || password.isEmpty())) {
            return "";
        }
        byte[] blob = WalletCrypto.encrypt(key(),
                CamRecord.joinCreds(username, password).getBytes(StandardCharsets.UTF_8));
        return Base64.encodeToString(blob, Base64.NO_WRAP);
    }

    /** Unseals a stored credential blob into
     *  [username, password]. Empty blob = no credentials. */
    public static String[] unsealCreds(String credCipher) throws Exception {
        if (credCipher == null || credCipher.isEmpty()) return new String[]{"", ""};
        byte[] blob = Base64.decode(credCipher, Base64.NO_WRAP);
        String plain = new String(WalletCrypto.decrypt(key(), blob), StandardCharsets.UTF_8);
        return CamRecord.splitCreds(plain);
    }

    // ---- file ----

    /** Thrown when the stored file exists but cannot be
     *  parsed — the UI surfaces it honestly instead of
     *  silently dropping the camera list. */
    public static final class CorruptException extends Exception {
        CorruptException(Throwable cause) { super(cause); }
    }

    private static JSONArray raw(Context c) throws Exception {
        File file = new File(c.getFilesDir(), FILE_NAME);
        if (!file.exists()) return new JSONArray();
        try (FileInputStream in = new FileInputStream(file)) {
            byte[] data = new byte[(int) Math.min(file.length(), MAX_FILE_BYTES)];
            int read = 0;
            while (read < data.length) {
                int n = in.read(data, read, data.length - read);
                if (n < 0) break;
                read += n;
            }
            return new JSONArray(new String(data, 0, read, StandardCharsets.UTF_8));
        }
    }

    private static void write(Context c, JSONArray arr) throws Exception {
        File tmp = new File(c.getFilesDir(), FILE_NAME + ".tmp");
        try (FileOutputStream out = new FileOutputStream(tmp)) {
            out.write(arr.toString().getBytes(StandardCharsets.UTF_8));
        }
        File file = new File(c.getFilesDir(), FILE_NAME);
        if (!tmp.renameTo(file)) {
            try (FileOutputStream out = new FileOutputStream(file)) {
                out.write(arr.toString().getBytes(StandardCharsets.UTF_8));
            }
            tmp.delete();
        }
    }

    // ---- CRUD ----

    public static synchronized List<CamRecord> list(Context c) throws CorruptException {
        List<CamRecord> out = new ArrayList<>();
        try {
            JSONArray arr = raw(c);
            for (int i = 0; i < arr.length(); i++) {
                CamRecord r = CamRecord.fromJson(arr.getJSONObject(i));
                if (r != null) out.add(r);
            }
        } catch (CorruptException e) {
            throw e;
        } catch (Exception e) {
            throw new CorruptException(e);
        }
        return out;
    }

    public static synchronized CamRecord byId(Context c, String id) {
        try {
            for (CamRecord r : list(c)) {
                if (r.id.equals(id)) return r;
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    /** Adds or replaces a record by id (the edit dialog
     *  saves through here too — one write path). */
    public static synchronized void save(Context c, CamRecord rec) throws Exception {
        JSONArray arr = raw(c);
        JSONArray next = new JSONArray();
        boolean replaced = false;
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.getJSONObject(i);
            if (rec.id.equals(o.optString("id"))) {
                next.put(rec.toJson());
                replaced = true;
            } else {
                next.put(o);
            }
        }
        if (!replaced) next.put(rec.toJson());
        write(c, next);
    }

    public static synchronized void delete(Context c, String id) throws Exception {
        JSONArray arr = raw(c);
        JSONArray next = new JSONArray();
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.getJSONObject(i);
            if (!id.equals(o.optString("id"))) next.put(o);
        }
        write(c, next);
        deleteLastImage(c, id);
    }

    /** Records a successful fetch's timestamp without
     *  touching anything else. Failures here are silent:
     *  the timestamp is display-only. */
    public static synchronized void markGood(Context c, String id, long whenMs) {
        try {
            JSONArray arr = raw(c);
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                if (id.equals(o.optString("id"))) {
                    o.put("lastGoodAt", whenMs);
                    break;
                }
            }
            write(c, arr);
        } catch (Exception ignored) {
        }
    }

    // ---- last-good images ----

    private static File lastImageFile(Context c, String id) {
        File dir = new File(c.getFilesDir(), LAST_DIR);
        return new File(dir, id + ".jpg");
    }

    /** Stores a last-good image atomically. Only JPEG-
     *  looking bytes are stored (the caller validates). */
    public static void storeLastImage(Context c, String id, byte[] jpeg) {
        try {
            File dir = new File(c.getFilesDir(), LAST_DIR);
            dir.mkdirs();
            File tmp = new File(dir, id + ".tmp");
            try (FileOutputStream out = new FileOutputStream(tmp)) {
                out.write(jpeg);
            }
            File dst = lastImageFile(c, id);
            if (!tmp.renameTo(dst)) {
                try (FileOutputStream out = new FileOutputStream(dst)) {
                    out.write(jpeg);
                }
                tmp.delete();
            }
        } catch (Exception ignored) {
        }
    }

    /** Reads the last-good image, or null when there is
     *  none. Capped — a corrupt oversized file is ignored. */
    public static byte[] lastImage(Context c, String id) {
        try {
            File f = lastImageFile(c, id);
            if (!f.exists() || f.length() > 8L * 1024 * 1024) return null;
            byte[] data = new byte[(int) f.length()];
            try (FileInputStream in = new FileInputStream(f)) {
                int read = 0;
                while (read < data.length) {
                    int n = in.read(data, read, data.length - read);
                    if (n < 0) break;
                    read += n;
                }
            }
            return data;
        } catch (Exception e) {
            return null;
        }
    }

    public static void deleteLastImage(Context c, String id) {
        try {
            lastImageFile(c, id).delete();
            new File(new File(c.getFilesDir(), LAST_DIR), id + ".tmp").delete();
        } catch (Exception ignored) {
        }
    }
}
