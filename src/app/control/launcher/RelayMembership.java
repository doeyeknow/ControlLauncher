package app.control.launcher;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Relay membership for private walkie channels (factored out of
 * WalkiePage in v1.19 so the subscription service runs the
 * exact same flow): membership is a signed relay member-add
 * per member contact carrying a relay key (PROTOCOL §7.3) —
 * silent and idempotent. The first add also CREATES the
 * channel, with the signer as creator and first member.
 *
 * Legacy member values (Matrix ids, "name:…") are simply not
 * relay keys and are skipped. Errors are expected in normal
 * life (offline, not yet a member) and are deliberately
 * silent — the callers' status lines say what the user needs
 * to know, and a refused pull surfaces as a blocked channel
 * state, not a toast.
 */
public final class RelayMembership {
    private RelayMembership() {}

    /** True when the string is a canonical relay pubkey:
     *  exactly 43 base64url characters (§2 encoding). */
    public static boolean isRelayPub(String s) {
        if (s == null || s.length() != 43) return false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            boolean ok = (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z')
                    || (c >= '0' && c <= '9') || c == '-' || c == '_';
            if (!ok) return false;
        }
        return true;
    }

    /** Adds every relay-keyed member of a private slot to its
     *  relay channel. No-op for open slots and for a null
     *  channel id (a private slot with no valid key). */
    public static void sync(Context c, JSONObject slot, String relayChannelId) {
        if (c == null || slot == null) return;
        if (!Profile.isPrivate(slot) || relayChannelId == null) return;
        RelayClient.ensureRegistered(c);
        JSONArray members = slot.optJSONArray("members");
        if (members == null) return;
        for (int i = 0; i < members.length(); i++) {
            String m = members.optString(i, "");
            if (isRelayPub(m)) RelayClient.addMemberQuiet(c, relayChannelId, m);
        }
    }
}
