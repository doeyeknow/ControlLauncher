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
import java.util.UUID;

import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;

/**
 * Wallet storage (v1.7): the device-local card vault.
 *
 * - One JSON file, {@code wallet-cards.json} in app-private
 *   storage. It is NOT part of the portable profile: profile
 *   export/import/wipe touch only {@code profile.json}, so cards
 *   never travel with a profile and a profile wipe leaves the
 *   wallet alone. The UI says so.
 * - The full card number is stored ONLY as AES-256-GCM ciphertext
 *   under a key held by Android Keystore (alias
 *   {@code control-wallet}); the key material never enters the
 *   app's memory in extractable form. Only the last 4, nickname,
 *   name, network, and expiry are stored in the clear.
 * - The key is NOT bound to user authentication: the reveal gate
 *   is enforced by the app (BiometricPrompt / device credential
 *   in WalletActivity) at the moment of display. Key-level auth
 *   would add CryptoObject plumbing whose failure modes cannot be
 *   proven off-device; the UI gate is the control Adam was
 *   promised, and the keystore still means the ciphertext is
 *   useless off this device. CVV is never stored — no field for
 *   it exists anywhere in the wallet.
 * - Writes are atomic (tmp file + rename, the OwnLog pattern).
 */
public final class WalletStore {

    private static final String FILE_NAME = "wallet-cards.json";
    private static final String KEY_ALIAS = "control-wallet";

    /** A stored card. {@code numberCipher} is base64 of the
     *  IV‖ciphertext blob; the plaintext number is never a field. */
    public static final class Card {
        public final String id;
        public final String nickname;
        public final String name;
        public final String network;
        public final int expMonth;
        public final int expYear;
        public final String last4;
        public final String numberCipher;

        Card(String id, String nickname, String name, String network,
             int expMonth, int expYear, String last4, String numberCipher) {
            this.id = id;
            this.nickname = nickname;
            this.name = name;
            this.network = network;
            this.expMonth = expMonth;
            this.expYear = expYear;
            this.last4 = last4;
            this.numberCipher = numberCipher;
        }
    }

    private WalletStore() {}

    // ---- key ----

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

    // ---- crypto through the shipping core ----

    private static String seal(String digits) throws Exception {
        byte[] blob = WalletCrypto.encrypt(key(),
                digits.getBytes(StandardCharsets.UTF_8));
        return Base64.encodeToString(blob, Base64.NO_WRAP);
    }

    /** Decrypts a card number. Callers must already have passed
     *  the credential gate; this method enforces nothing itself. */
    public static String unseal(Card card) throws Exception {
        byte[] blob = Base64.decode(card.numberCipher, Base64.NO_WRAP);
        return new String(WalletCrypto.decrypt(key(), blob), StandardCharsets.UTF_8);
    }

    // ---- file ----

    /** Thrown when the stored file exists but cannot be parsed —
     *  the UI surfaces it honestly instead of silently dropping
     *  the wallet. */
    public static final class CorruptException extends Exception {
        CorruptException(Throwable cause) { super(cause); }
    }

    private static JSONArray raw(Context c) throws Exception {
        File file = new File(c.getFilesDir(), FILE_NAME);
        if (!file.exists()) return new JSONArray();
        try (FileInputStream in = new FileInputStream(file)) {
            byte[] data = new byte[(int) Math.min(file.length(), 256_000)];
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

    private static Card fromJson(JSONObject o) {
        return new Card(
                o.optString("id"),
                o.optString("nickname"),
                o.optString("name"),
                o.optString("network", WalletCards.NETWORK_OTHER),
                o.optInt("expMonth"),
                o.optInt("expYear"),
                o.optString("last4"),
                o.optString("numberCipher"));
    }

    private static JSONObject toJson(Card card) throws Exception {
        JSONObject o = new JSONObject();
        o.put("id", card.id);
        o.put("nickname", card.nickname);
        o.put("name", card.name);
        o.put("network", card.network);
        o.put("expMonth", card.expMonth);
        o.put("expYear", card.expYear);
        o.put("last4", card.last4);
        o.put("numberCipher", card.numberCipher);
        return o;
    }

    // ---- CRUD ----

    public static synchronized List<Card> list(Context c) throws CorruptException {
        List<Card> out = new ArrayList<>();
        try {
            JSONArray arr = raw(c);
            for (int i = 0; i < arr.length(); i++) {
                out.add(fromJson(arr.getJSONObject(i)));
            }
        } catch (CorruptException e) {
            throw e;
        } catch (Exception e) {
            throw new CorruptException(e);
        }
        return out;
    }

    /** Adds a card. {@code digits} must already be validated
     *  (digits-only + Luhn); it is encrypted here and never
     *  retained. Returns the stored card. */
    public static synchronized Card add(Context c, String nickname, String name,
                                        String network, int expMonth, int expYear,
                                        String digits) throws Exception {
        JSONArray arr = raw(c);
        Card card = new Card(UUID.randomUUID().toString(), nickname, name, network,
                expMonth, expYear, WalletCards.last4(digits), seal(digits));
        arr.put(toJson(card));
        write(c, arr);
        return card;
    }

    /** Edits nickname/name/network/expiry only — the number is
     *  immutable once stored; changing it means delete + re-add. */
    public static synchronized void updateMeta(Context c, String id, String nickname,
                                               String name, String network,
                                               int expMonth, int expYear) throws Exception {
        JSONArray arr = raw(c);
        JSONArray next = new JSONArray();
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.getJSONObject(i);
            if (id.equals(o.optString("id"))) {
                o.put("nickname", nickname);
                o.put("name", name);
                o.put("network", network);
                o.put("expMonth", expMonth);
                o.put("expYear", expYear);
            }
            next.put(o);
        }
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
    }

    /** Deletes the whole wallet file (the recovery path when the
     *  stored data is unreadable). The keystore key is left in
     *  place — it holds no card data itself. */
    public static synchronized void clearAll(Context c) {
        try {
            new File(c.getFilesDir(), FILE_NAME).delete();
            new File(c.getFilesDir(), FILE_NAME + ".tmp").delete();
        } catch (Exception ignored) {
        }
    }
}
