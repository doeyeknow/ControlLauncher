package app.control.launcher;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * THE profile: one versioned JSON document that is the source of truth
 * for the user's launcher life — preferences, notification mutes, filter
 * settings, browser data, the relay identity, contacts, chat channels,
 * and the walkie-talkie identity. Stored at files/profile.json.
 *
 * What is NOT in here, by design:
 *  - runtime state (filter on/off, blocked counters, held notifications),
 *  - a background PHOTO's image bytes (device-local; the profile records
 *    only the choice + dim level).
 *
 * The relay identity key (an Ed25519 seed) IS in here, deliberately —
 * the opposite of the old Matrix-token rule. The key IS the account:
 * there is no sign-in, no password, nothing to re-login with, so the
 * key travels inside the encrypted profile export. Anyone holding an
 * exported profile and its passphrase holds the identity itself.
 * A legacy "matrix" section in an old profile is simply carried along
 * unread (migration tolerance); nothing reads it any more.
 */
public final class Profile {
    public static final int CURRENT_VERSION = 1;
    private static final String FILE_NAME = "profile.json";

    private static JSONObject root;
    private static File file;

    private Profile() {}

    // ---- lifecycle ----

    public static synchronized JSONObject load(Context c) {
        if (root != null) return root;
        file = new File(c.getFilesDir(), FILE_NAME);
        JSONObject parsed = null;
        if (file.exists()) {
            try (FileInputStream in = new FileInputStream(file)) {
                byte[] data = new byte[(int) file.length()];
                int read = 0;
                while (read < data.length) {
                    int n = in.read(data, read, data.length - read);
                    if (n < 0) break;
                    read += n;
                }
                parsed = new JSONObject(new String(data, 0, read, StandardCharsets.UTF_8));
            } catch (Exception e) {
                parsed = null;
            }
        }
        root = parsed != null ? parsed : defaultProfile();
        boolean freshIdentity = normalize(root);
        if (freshIdentity) {
            // The relay seed IS the account: persist it the moment it
            // is generated, or the next cold start would mint a
            // different identity and strand every membership.
            save(c);
        }
        return root;
    }

    public static boolean exists(Context c) {
        return new File(c.getFilesDir(), FILE_NAME).exists();
    }

    public static synchronized void save(Context c) {
        load(c);
        try {
            File tmp = new File(c.getFilesDir(), FILE_NAME + ".tmp");
            try (FileOutputStream out = new FileOutputStream(tmp)) {
                out.write(root.toString(2).getBytes(StandardCharsets.UTF_8));
            }
            if (!tmp.renameTo(file)) {
                // Fallback: direct write.
                try (FileOutputStream out = new FileOutputStream(file)) {
                    out.write(root.toString(2).getBytes(StandardCharsets.UTF_8));
                }
            }
        } catch (Exception ignored) {
        }
    }

    /** Replaces the whole profile (import). Returns an error string or null. */
    public static synchronized String install(Context c, JSONObject incoming) {
        int v = incoming.optInt("version", -1);
        if (v < 0) return "That file is not a Control profile (no version).";
        if (v > CURRENT_VERSION) {
            return "That profile was made by a newer version of Control (v" + v
                    + "). Update the app first.";
        }
        root = incoming;
        normalize(root);
        save(c);
        return null;
    }

    public static synchronized JSONObject snapshot(Context c) {
        load(c);
        try {
            return new JSONObject(root.toString());
        } catch (Exception e) {
            return root;
        }
    }

    public static JSONObject defaultProfile() {
        JSONObject r = new JSONObject();
        try {
            r.put("version", CURRENT_VERSION);
            JSONObject launcher = new JSONObject();
            launcher.put("favorites", new JSONArray());
            launcher.put("showClock", true);
            r.put("launcher", launcher);
            JSONObject notif = new JSONObject();
            notif.put("mutedApps", new JSONArray());
            r.put("notifications", notif);
            JSONObject filter = new JSONObject();
            filter.put("upstreamDns", "1.1.1.1");
            filter.put("bypassApps", new JSONArray());
            r.put("filter", filter);
            JSONObject browser = new JSONObject();
            browser.put("jsEnabled", true);
            browser.put("homepage", DEFAULT_HOMEPAGE);
            browser.put("bookmarks", new JSONArray());
            r.put("browser", browser);
            JSONObject relay = new JSONObject();
            relay.put("seed", "");
            relay.put("pub", "");
            relay.put("cardDmKey", "");
            r.put("relay", relay);
            r.put("contacts", new JSONArray());
            r.put("chats", new JSONArray());
            JSONObject mesh = new JSONObject();
            mesh.put("displayName", "");
            mesh.put("key", randomKeyB64());
            r.put("mesh", mesh);
            r.put("channels", new JSONArray());
            JSONObject sms = new JSONObject();
            sms.put("enabled", false);
            sms.put("secretHash", "");
            r.put("smsControl", sms);
            JSONObject bg = new JSONObject();
            bg.put("type", "color");
            bg.put("value", "#101613");
            bg.put("scrim", 0);
            r.put("background", bg);
        } catch (Exception ignored) {
        }
        return r;
    }

    /** @return true when a fresh relay identity was generated. */
    private static boolean normalize(JSONObject r) {
        boolean freshIdentity = false;
        // Fill in anything missing (older files, partial imports). Unknown
        // fields are left untouched and therefore preserved.
        JSONObject d = defaultProfile();
        for (String section : new String[]{"launcher", "notifications", "filter",
                "browser", "relay", "mesh", "smsControl", "background"}) {
            if (!r.has(section) || !(r.opt(section) instanceof JSONObject)) {
                try {
                    r.put(section, d.getJSONObject(section));
                } catch (Exception ignored) {
                }
            }
        }
        if (!r.has("contacts")) {
            try {
                r.put("contacts", new JSONArray());
            } catch (Exception ignored) {
            }
        }
        if (!r.has("chats")) {
            try {
                r.put("chats", new JSONArray());
            } catch (Exception ignored) {
            }
        }
        if (!r.has("channels")) {
            try {
                r.put("channels", new JSONArray());
            } catch (Exception ignored) {
            }
        }
        if (!r.has("version")) {
            try {
                r.put("version", CURRENT_VERSION);
            } catch (Exception ignored) {
            }
        }
        JSONObject mesh = r.optJSONObject("mesh");
        if (mesh != null && mesh.optString("key", "").isEmpty()) {
            try {
                mesh.put("key", randomKeyB64());
            } catch (Exception ignored) {
            }
        }
        // Relay identity: generated on first need (fresh install, or an
        // old profile that predates the relay). An imported profile that
        // carries a seed keeps it — the seed IS the identity.
        JSONObject relay = r.optJSONObject("relay");
        if (relay != null) {
            try {
                byte[] seedBytes = null;
                String seed = relay.optString("seed", "");
                if (!seed.isEmpty()) {
                    try {
                        seedBytes = Base64.getDecoder().decode(seed);
                    } catch (Exception ignored) {
                        seedBytes = null;
                    }
                    if (seedBytes != null && seedBytes.length != 32) seedBytes = null;
                }
                if (seedBytes == null) {
                    byte[][] kp = RelayKeys.generate();
                    relay.put("seed", Base64.getEncoder().encodeToString(kp[0]));
                    relay.put("pub", RelayKeys.b64Url(kp[1]));
                    freshIdentity = true;
                } else if (relay.optString("pub", "").isEmpty()) {
                    relay.put("pub", RelayKeys.b64Url(RelayKeys.publicFromSeed(seedBytes)));
                }
                if (relay.optString("cardDmKey", "").isEmpty()) {
                    relay.put("cardDmKey", randomKeyB64());
                }
            } catch (Exception ignored) {
            }
        }
        return freshIdentity;
    }

    private static String randomKeyB64() {
        byte[] k = new byte[32];
        new SecureRandom().nextBytes(k);
        return Base64.getEncoder().encodeToString(k);
    }

    // ---- typed accessors ----

    private static JSONObject section(Context c, String name) {
        JSONObject s = load(c).optJSONObject(name);
        if (s == null) {
            s = new JSONObject();
            try {
                root.put(name, s);
            } catch (Exception ignored) {
            }
        }
        return s;
    }

    private static Set<String> stringSet(JSONObject obj, String key) {
        LinkedHashSet<String> out = new LinkedHashSet<>();
        JSONArray arr = obj.optJSONArray(key);
        if (arr != null) {
            for (int i = 0; i < arr.length(); i++) {
                String v = arr.optString(i, "");
                if (!v.isEmpty()) out.add(v);
            }
        }
        return out;
    }

    private static void putSet(Context c, JSONObject obj, String key, Set<String> values) {
        try {
            JSONArray arr = new JSONArray();
            for (String v : values) arr.put(v);
            obj.put(key, arr);
        } catch (Exception ignored) {
        }
        save(c);
    }

    public static Set<String> favorites(Context c) {
        return stringSet(section(c, "launcher"), "favorites");
    }

    public static void setFavorites(Context c, Set<String> v) {
        putSet(c, section(c, "launcher"), "favorites", v);
    }

    public static boolean showClock(Context c) {
        return section(c, "launcher").optBoolean("showClock", true);
    }

    public static void setShowClock(Context c, boolean v) {
        try {
            section(c, "launcher").put("showClock", v);
        } catch (Exception ignored) {
        }
        save(c);
    }

    // ---- launcher background (v1.2) ----
    //
    // Stored in the profile so colors/gradients travel with it. A
    // PHOTO is device-local by design (unlike the identity key, which
    // must travel): the profile records only type "photo" + scrim,
    // never the image.

    public static JSONObject background(Context c) {
        return section(c, "background");
    }

    public static void saveBackground(Context c, String type, String value, int scrim) {
        try {
            JSONObject bg = section(c, "background");
            bg.put("type", type == null ? "color" : type);
            bg.put("value", value == null ? "" : value);
            bg.put("scrim", Math.max(0, Math.min(70, scrim)));
        } catch (Exception ignored) {
        }
        save(c);
    }

    public static Set<String> mutedApps(Context c) {
        return stringSet(section(c, "notifications"), "mutedApps");
    }

    public static void setMutedApps(Context c, Set<String> v) {
        putSet(c, section(c, "notifications"), "mutedApps", v);
    }

    public static String upstreamDns(Context c) {
        return section(c, "filter").optString("upstreamDns", "1.1.1.1");
    }

    public static void setUpstreamDns(Context c, String v) {
        try {
            section(c, "filter").put("upstreamDns", v);
        } catch (Exception ignored) {
        }
        save(c);
    }

    public static Set<String> bypassApps(Context c) {
        return stringSet(section(c, "filter"), "bypassApps");
    }

    public static void setBypassApps(Context c, Set<String> v) {
        putSet(c, section(c, "filter"), "bypassApps", v);
    }

    /** The browser's homepage since v1.5: the launcher's own site. */
    public static final String DEFAULT_HOMEPAGE = "https://launcher.eyeknow.app";
    /** The pre-v1.5 default, migrated on load (see browserHomepage). */
    private static final String LEGACY_DEFAULT_HOMEPAGE = "https://duckduckgo.com";

    /**
     * The stored browser homepage. Migration (v1.5): a profile still
     * carrying the old DuckDuckGo default moves to the new default
     * and the new value is written back; a homepage explicitly set
     * to anything else is the user's and stays; no stored value
     * (fresh profile) means the new default.
     */
    public static String browserHomepage(Context c) {
        String hp = section(c, "browser").optString("homepage", "");
        if (hp.isEmpty()) return DEFAULT_HOMEPAGE;
        if (LEGACY_DEFAULT_HOMEPAGE.equals(hp)) {
            setBrowserHomepage(c, DEFAULT_HOMEPAGE);
            return DEFAULT_HOMEPAGE;
        }
        return hp;
    }

    public static void setBrowserHomepage(Context c, String v) {
        try {
            section(c, "browser").put("homepage", v);
        } catch (Exception ignored) {
        }
        save(c);
    }

    public static boolean jsEnabled(Context c) {
        return section(c, "browser").optBoolean("jsEnabled", true);
    }

    public static void setJsEnabled(Context c, boolean v) {
        try {
            section(c, "browser").put("jsEnabled", v);
        } catch (Exception ignored) {
        }
        save(c);
    }

    public static JSONArray bookmarks(Context c) {
        JSONArray arr = section(c, "browser").optJSONArray("bookmarks");
        return arr != null ? arr : new JSONArray();
    }

    public static void addBookmark(Context c, String title, String url) {
        try {
            JSONArray arr = bookmarks(c);
            for (int i = 0; i < arr.length(); i++) {
                if (url.equals(arr.getJSONObject(i).optString("url"))) return;
            }
            JSONObject b = new JSONObject();
            b.put("title", title == null || title.isEmpty() ? url : title);
            b.put("url", url);
            arr.put(b);
            section(c, "browser").put("bookmarks", arr);
            save(c);
        } catch (Exception ignored) {
        }
    }

    public static void removeBookmark(Context c, String url) {
        try {
            JSONArray arr = bookmarks(c);
            JSONArray next = new JSONArray();
            for (int i = 0; i < arr.length(); i++) {
                JSONObject b = arr.getJSONObject(i);
                if (!url.equals(b.optString("url"))) next.put(b);
            }
            section(c, "browser").put("bookmarks", next);
            save(c);
        } catch (Exception ignored) {
        }
    }

    // ---- relay identity (v1.3) ----
    //
    // The Ed25519 keypair that IS the user's chat/walkie account on the
    // Control Relay. Stored in the profile (seed + public key + the
    // per-profile contact-card DM key) and therefore inside every
    // encrypted export — see the class doc for why.

    /** The 32-byte Ed25519 seed. normalize() guarantees it exists. */
    public static byte[] relaySeedBytes(Context c) {
        String seed = section(c, "relay").optString("seed", "");
        try {
            byte[] bytes = Base64.getDecoder().decode(seed);
            if (bytes.length == 32) return bytes;
        } catch (Exception ignored) {
        }
        // Unreachable after normalize(); never hand back a wrong key
        // silently in a way that persists — this fallback is transient.
        return RelayKeys.generate()[0];
    }

    /** Public key, protocol encoding (base64url, no padding, 43 chars). */
    public static String relayPub(Context c) {
        String pub = section(c, "relay").optString("pub", "");
        if (!pub.isEmpty()) return pub;
        return RelayKeys.b64Url(RelayKeys.publicFromSeed(relaySeedBytes(c)));
    }

    /** Display fingerprint of the identity key: 16 hex chars
     *  (SHA-256 of the raw public key, first 8 bytes — §5 style). */
    public static String relayShortFingerprint(Context c) {
        try {
            return RelayKeys.fingerprint(RelayKeys.b64UrlDecode(relayPub(c)));
        } catch (Exception e) {
            return "";
        }
    }

    /** The DM key this profile's contact card carries (§5.1): one key
     *  per profile, shown to whoever scans the card. */
    public static String cardDmKey(Context c) {
        return section(c, "relay").optString("cardDmKey", "");
    }

    // ---- chat channels (v1.3) ----
    //
    // Group chats created or imported on this profile: [{name, key}]
    // with key base64-standard. The channel id is derived from the key
    // (RelayIds.chatChannelId); membership lives on the relay.

    public static JSONArray chatChannels(Context c) {
        JSONArray arr = load(c).optJSONArray("chats");
        return arr != null ? arr : new JSONArray();
    }

    public static void addChatChannel(Context c, String name, String keyB64) {
        try {
            JSONArray arr = chatChannels(c);
            for (int i = 0; i < arr.length(); i++) {
                if (keyB64.equals(arr.getJSONObject(i).optString("key"))) return;
            }
            JSONObject chat = new JSONObject();
            chat.put("name", name == null || name.isEmpty() ? "Chat" : name);
            chat.put("key", keyB64);
            arr.put(chat);
            load(c).put("chats", arr);
            save(c);
        } catch (Exception ignored) {
        }
    }

    /** The stored key for a chat:<fp> channel id, or "" if this
     *  profile doesn't hold it. */
    public static String chatKeyFor(Context c, String channelId) {
        JSONArray arr = chatChannels(c);
        for (int i = 0; i < arr.length(); i++) {
            JSONObject chat = arr.optJSONObject(i);
            if (chat == null) continue;
            String key = chat.optString("key", "");
            if (channelId.equals(RelayIds.chatChannelId(key))) return key;
        }
        return "";
    }

    /** The stored name exactly as typed, "" when none was ever
     *  set. Unlike {@link #displayName}, this never substitutes
     *  the "Someone" fallback — setup pre-fills from it so a
     *  re-run shows the real stored value. */
    public static String displayNameStored(Context c) {
        return section(c, "mesh").optString("displayName", "");
    }

    public static String displayName(Context c) {
        String n = section(c, "mesh").optString("displayName", "");
        if (!n.isEmpty()) return n;
        // Legacy: pre-v1.3 profiles kept the name in the (now unread)
        // matrix section.
        JSONObject mx = load(c).optJSONObject("matrix");
        if (mx != null) {
            n = mx.optString("displayName", "");
            if (!n.isEmpty()) return n;
        }
        return "Someone";
    }

    /** One name for chat and walkie-talkie. */
    public static void setDisplayName(Context c, String name) {
        try {
            section(c, "mesh").put("displayName", name);
        } catch (Exception ignored) {
        }
        save(c);
    }

    public static String meshKey(Context c) {
        return section(c, "mesh").optString("key", "");
    }

    public static JSONArray contacts(Context c) {
        JSONArray arr = load(c).optJSONArray("contacts");
        return arr != null ? arr : new JSONArray();
    }

    public static void saveContacts(Context c, JSONArray contacts) {
        try {
            load(c).put("contacts", contacts);
        } catch (Exception ignored) {
        }
        save(c);
    }

    // ---- SMS remote control ----

    public static boolean smsControlEnabled(Context c) {
        return section(c, "smsControl").optBoolean("enabled", false);
    }

    public static void setSmsControlEnabled(Context c, boolean on) {
        try {
            section(c, "smsControl").put("enabled", on);
        } catch (Exception ignored) {
        }
        save(c);
    }

    /** The command secret is only ever stored as a SHA-256 hash. */
    public static String smsSecretHash(Context c) {
        return section(c, "smsControl").optString("secretHash", "");
    }

    public static void setSmsSecret(Context c, String secret) {
        try {
            section(c, "smsControl").put("secretHash",
                    secret == null || secret.isEmpty() ? "" : sha256Hex(secret));
        } catch (Exception ignored) {
        }
        save(c);
    }

    public static boolean smsSecretMatches(Context c, String candidate) {
        String stored = smsSecretHash(c);
        return !stored.isEmpty() && candidate != null
                && stored.equals(sha256Hex(candidate));
    }

    public static String sha256Hex(String s) {
        try {
            byte[] d = java.security.MessageDigest.getInstance("SHA-256")
                    .digest(s.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : d) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            return "";
        }
    }

    /** Digits-only phone comparison key: last 10 digits when available. */
    public static String phoneKey(String phone) {
        if (phone == null) return "";
        StringBuilder digits = new StringBuilder();
        for (int i = 0; i < phone.length(); i++) {
            char ch = phone.charAt(i);
            if (ch >= '0' && ch <= '9') digits.append(ch);
        }
        String d = digits.toString();
        return d.length() > 10 ? d.substring(d.length() - 10) : d;
    }

    /** Is this sender a contact flagged as a controller? */
    public static boolean isController(Context c, String senderPhone) {
        String key = phoneKey(senderPhone);
        if (key.isEmpty()) return false;
        for (JSONObject contact : contactList(c)) {
            if (contact.optBoolean("controller", false)
                    && key.equals(phoneKey(contact.optString("phone", "")))) {
                return true;
            }
        }
        return false;
    }

    public static void setContactController(Context c, int index, boolean on) {
        try {
            JSONArray arr = load(c).optJSONArray("contacts");
            if (arr == null || index < 0 || index >= arr.length()) return;
            arr.getJSONObject(index).put("controller", on);
            save(c);
        } catch (Exception ignored) {
        }
    }

    // ---- walkie voice profiles (v1.11) ----
    //
    // A contact's low-data voice profile lives on the contact
    // record as a nested "voice" object {"voice","pitch","rate"}
    // inside profile.json, so it travels with the encrypted
    // profile export like the rest of the contact. A voice NAME
    // that isn't installed on the reading phone falls back to the
    // system default voice (VoiceProfile.resolve), so an imported
    // profile never breaks speech elsewhere. The profile is a
    // synthetic stand-in chosen on this phone — never the
    // person's real voice, never a clone.

    /** The contact's voice profile, or the default when none is set
     *  or the stored value is unreadable. Never null. */
    public static VoiceProfile contactVoice(JSONObject contact) {
        if (contact == null) return VoiceProfile.defaults();
        JSONObject v = contact.optJSONObject("voice");
        if (v == null) return VoiceProfile.defaults();
        return VoiceProfile.parse(v.toString());
    }

    public static void setContactVoice(Context c, int index, VoiceProfile p) {
        try {
            JSONArray arr = load(c).optJSONArray("contacts");
            if (arr == null || index < 0 || index >= arr.length()) return;
            arr.getJSONObject(index).put("voice",
                    new JSONObject(p == null
                            ? VoiceProfile.defaults().toJson() : p.toJson()));
            save(c);
        } catch (Exception ignored) {
        }
    }

    /** Voice profile for a relay sender, by pubkey; default when
     *  the sender isn't a contact or has no profile set. */
    public static VoiceProfile voiceForSenderKey(Context c, String relayPub) {
        JSONObject contact = ContactCard.byRelayKey(c, relayPub);
        return contactVoice(contact);
    }

    /** Voice profile for a mesh/Bluetooth sender, by display name
     *  (best-effort: those roads carry names, not keys); default
     *  when no contact matches. */
    public static VoiceProfile voiceForSenderName(Context c, String name) {
        if (name != null && !name.isEmpty()) {
            for (JSONObject contact : contactList(c)) {
                if (name.equalsIgnoreCase(contact.optString("name", ""))) {
                    return contactVoice(contact);
                }
            }
        }
        return VoiceProfile.defaults();
    }

    /** Deletes the profile file and resets the in-memory copy. The SMS
     *  WIPE command calls this (plus clearing runtime prefs). */
    public static synchronized void wipe(Context c) {
        try {
            new java.io.File(c.getFilesDir(), FILE_NAME).delete();
        } catch (Exception ignored) {
        }
        root = null;
    }

    // ---- walkie-talkie channel bank ----
    //
    // The radio has BANK_SIZE numbered slots, like an FRS/GMRS radio.
    // Every number always exists: unconfigured numbers are OPEN channels
    // (any nearby device on the same number can join; the session key is
    // a public per-number string, exactly like a real radio channel).
    // Configuring a slot — label, PRIVATE mode with its own key and
    // member contacts — is optional depth stored here. Two people's
    // "channel 3" can be different groups because their slot keys differ,
    // just like privacy codes on real radios.

    public static final int BANK_SIZE = 50;

    public static JSONArray channels(Context c) {
        JSONArray arr = load(c).optJSONArray("channels");
        return arr != null ? arr : new JSONArray();
    }

    /** Configured slots only (unconfigured numbers are implicit). */
    public static List<JSONObject> configuredSlots(Context c) {
        List<JSONObject> out = new ArrayList<>();
        JSONArray arr = channels(c);
        for (int i = 0; i < arr.length(); i++) {
            JSONObject slot = arr.optJSONObject(i);
            if (slot != null) out.add(slot);
        }
        return out;
    }

    /** The effective slot for a number: its stored config, or the
     *  factory default (open, unlabeled, no key, no room). */
    public static JSONObject slot(Context c, int number) {
        for (JSONObject s : configuredSlots(c)) {
            if (s.optInt("number") == number) return s;
        }
        return defaultSlot(number);
    }

    public static JSONObject defaultSlot(int number) {
        JSONObject s = new JSONObject();
        try {
            s.put("number", number);
            s.put("label", "");
            s.put("mode", "open");
            s.put("key", "");
            s.put("members", new JSONArray());
        } catch (Exception ignored) {
        }
        return s;
    }

    public static void saveSlot(Context c, JSONObject slot) {
        try {
            JSONArray arr = channels(c);
            JSONArray next = new JSONArray();
            int n = slot.optInt("number");
            boolean placed = false;
            for (int i = 0; i < arr.length(); i++) {
                JSONObject s = arr.getJSONObject(i);
                if (s.optInt("number") == n) {
                    next.put(slot);
                    placed = true;
                } else {
                    next.put(s);
                }
            }
            if (!placed) next.put(slot);
            load(c).put("channels", next);
            save(c);
        } catch (Exception ignored) {
        }
    }

    /** Removes a slot's configuration — the number returns to open default. */
    public static void resetSlot(Context c, int number) {
        try {
            JSONArray arr = channels(c);
            JSONArray next = new JSONArray();
            for (int i = 0; i < arr.length(); i++) {
                JSONObject s = arr.getJSONObject(i);
                if (s.optInt("number") != number) next.put(s);
            }
            load(c).put("channels", next);
            save(c);
        } catch (Exception ignored) {
        }
    }

    public static boolean isPrivate(JSONObject slot) {
        return "private".equals(slot.optString("mode"));
    }

    /** Session identity shared by every transport: same number + same
     *  key = same channel, on both sides of the online/offline line.
     *  A slot that arrived by invite but had to move to a different
     *  local number keeps the invite's identity in its "wireId" field,
     *  so it still speaks as the same channel on every transport. */
    public static String slotIdentity(JSONObject slot) {
        String wire = slot.optString("wireId", "");
        if (!wire.isEmpty()) return wire;
        int n = slot.optInt("number");
        if (!isPrivate(slot)) return "open:" + n;
        String key = slot.optString("key", "");
        String fp = "";
        try {
            byte[] digest = java.security.MessageDigest.getInstance("SHA-256")
                    .digest(key.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 4; i++) sb.append(String.format("%02x", digest[i]));
            fp = sb.toString();
        } catch (Exception ignored) {
        }
        return "priv:" + n + ":" + fp;
    }

    /** The passphrase a local session runs on for this slot. */
    public static String slotPassphrase(JSONObject slot) {
        if (!isPrivate(slot)) return "control-open-" + slot.optInt("number");
        return slot.optString("key", "");
    }

    public static String slotDisplayName(JSONObject slot) {
        int n = slot.optInt("number");
        String label = slot.optString("label", "");
        return label.isEmpty() ? "Channel " + n : n + " — " + label;
    }

    public static String newChannelKey() {
        return randomKeyB64();
    }

    public static List<JSONObject> contactList(Context c) {
        List<JSONObject> out = new ArrayList<>();
        JSONArray arr = contacts(c);
        for (int i = 0; i < arr.length(); i++) {
            JSONObject contact = arr.optJSONObject(i);
            if (contact != null) out.add(contact);
        }
        return out;
    }
}
