package app.control.launcher;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Contact cards (v1.3; audio transport since v1.8): the exchange
 * PROTOCOL.md §5.1 calls "the existing contact exchange". It never
 * existed as a card before — contacts were typed in by hand — so
 * v1.3 defines it:
 *
 *   v2 (this build): {"app":"control-contact","v":2,
 *       "name":"…", "pub":"<relay pubkey, base64url>",
 *       "dm":"<carried DM key, base64 std>"}
 *   v1 (read-only legacy shape): {"app":"control-contact","v":1,
 *       "name":"…", "matrixId":"@…"} — imports with NO relay key; the
 *       contact shows "no relay key yet" until a v2 card is exchanged.
 *
 * The "dm" key is the card OWNER's per-profile card DM key (Profile
 * .cardDmKey): the listener files it as the pair's DM key.
 * Convergence rule (the protocol doesn't specify one): a DM key
 * already on file for a contact is never overwritten by a later
 * card — first card wins — and a phone also recognizes DM channels
 * keyed by its OWN card key (see ChatActivity), so either exchange
 * order converges.
 */
public final class ContactCard {

    public static final String MARKER = "control-contact";
    public static final int VERSION = 2;

    private ContactCard() {}

    /** Parse any version of a contact card; null when the text isn't one. */
    public static JSONObject parse(String text) {
        if (text == null) return null;
        try {
            JSONObject o = new JSONObject(text);
            return MARKER.equals(o.optString("app")) ? o : null;
        } catch (Exception e) {
            return null;
        }
    }

    public static boolean isCard(String text) {
        return parse(text) != null;
    }

    /**
     * File a heard card as a contact: matched by relay pubkey when
     * the card carries one, else by name. Sets relayKey when present;
     * sets dmKey only when the contact has none yet (first card wins).
     * Returns a user-facing result line.
     */
    public static String apply(Context c, JSONObject card) {
        String name = card.optString("name", "").trim();
        String pub = card.optString("pub", "").trim();
        String dm = card.optString("dm", "").trim();
        String matrixId = card.optString("matrixId", "").trim();
        if (name.isEmpty() && pub.isEmpty()) {
            return "That contact card has no name or key on it.";
        }
        try {
            JSONArray arr = Profile.contacts(c);
            int found = -1;
            for (int i = 0; i < arr.length(); i++) {
                JSONObject contact = arr.getJSONObject(i);
                if (!pub.isEmpty() && pub.equals(contact.optString("relayKey", ""))) {
                    found = i;
                    break;
                }
                if (pub.isEmpty() && !name.isEmpty()
                        && name.equalsIgnoreCase(contact.optString("name", ""))) {
                    found = i;
                    break;
                }
            }
            JSONObject contact;
            if (found >= 0) {
                contact = arr.getJSONObject(found);
            } else {
                contact = new JSONObject();
                contact.put("name", name.isEmpty() ? "Unnamed" : name);
                contact.put("phone", "");
                contact.put("email", "");
                contact.put("meshKey", "");
                contact.put("notes", "");
                contact.put("controller", false);
                arr.put(contact);
            }
            if (!name.isEmpty()) contact.put("name", name);
            if (!pub.isEmpty()) contact.put("relayKey", pub);
            if (!matrixId.isEmpty() && contact.optString("matrixId", "").isEmpty()) {
                contact.put("matrixId", matrixId);
            }
            boolean dmSet = false;
            if (!dm.isEmpty() && contact.optString("dmKey", "").isEmpty()) {
                contact.put("dmKey", dm);
                dmSet = true;
            }
            Profile.saveContacts(c, arr);
            String who = contact.optString("name", "Contact");
            if (pub.isEmpty()) {
                return who + " saved — that card predates relay keys, so "
                        + "there's no relay key yet. Re-exchange a card from "
                        + "their current app to message them here.";
            }
            return who + " saved with their relay key"
                    + (dmSet ? " and a DM key — you can message them from Chat."
                             : ".");
        } catch (Exception e) {
            return "That contact card couldn't be filed.";
        }
    }

    /** Find a contact by relay pubkey ("" when none). */
    public static JSONObject byRelayKey(Context c, String pub) {
        if (pub == null || pub.isEmpty()) return null;
        for (JSONObject contact : Profile.contactList(c)) {
            if (pub.equals(contact.optString("relayKey", ""))) return contact;
        }
        return null;
    }

    /** Display name for a message sender: the contact's name when the
     *  pubkey is known, else a short key fingerprint. */
    public static String senderName(Context c, String pub) {
        JSONObject contact = byRelayKey(c, pub);
        if (contact != null) {
            String n = contact.optString("name", "");
            if (!n.isEmpty()) return n;
        }
        try {
            return "Key " + RelayKeys.fingerprint(RelayKeys.b64UrlDecode(pub));
        } catch (Exception e) {
            return "Someone";
        }
    }
}
