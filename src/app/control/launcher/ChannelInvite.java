package app.control.launcher;

import android.content.Context;

import org.json.JSONObject;

/**
 * Channel invites (v1.2; audio transport since v1.8): recognize and
 * join. Keys travel by sound now (AudioKeyPayload builds the compact
 * binary form; the receiver rebuilds this JSON text and join() runs
 * exactly as it did for a scanned QR).
 *
 * Payload (JSON):
 *   {"app":"control-channel", "v":1, "number":N, "label":"...",
 *    "mode":"private"|"open",
 *    "key":"<base64 channel key, private only>"}
 *
 * The "app" marker is what tells a listener "this is a channel
 * invite", as opposed to a profile-import QR (marker
 * "control-profile"). The slot identity is always recomputed from
 * number + key on join rather than trusted.
 *
 * Join placement (Profile bank semantics): if a configured slot
 * already speaks this identity, just tune to it. Else place at the
 * invited number when it is unconfigured. Else — the number is held
 * by a different channel — place at the next free slot WITH the
 * invite's identity preserved as the slot's wireId (same key, same
 * identity: transports filter traffic by identity, so this is what
 * makes the moved channel actually meet the inviter's group), and
 * say so in the result message.
 */
public final class ChannelInvite {

    public static final String MARKER = "control-channel";

    private ChannelInvite() {}

    /** True when the heard text is a channel invite (any version). */
    public static boolean isInvite(String text) {
        return parse(text) != null;
    }

    private static JSONObject parse(String text) {
        if (text == null) return null;
        try {
            JSONObject o = new JSONObject(text);
            return MARKER.equals(o.optString("app")) ? o : null;
        } catch (Exception e) {
            return null;
        }
    }

    public static final class JoinResult {
        public boolean joined;
        public boolean alreadyHad;
        public boolean moved;
        public int placedNumber = -1;
        public String message = "";
    }

    /**
     * Join the channel an invite describes. Returns null when the
     * text is not a channel invite at all (so callers can fall
     * through to their other handling); otherwise a result whose
     * message is written for the user, success or not.
     */
    public static JoinResult join(Context c, String text) {
        JSONObject o = parse(text);
        if (o == null) return null;
        JoinResult r = new JoinResult();
        int wanted = o.optInt("number", 0);
        if (wanted < 1 || wanted > Profile.BANK_SIZE) {
            r.message = "That invite names channel " + wanted
                    + ", outside this radio's 1–" + Profile.BANK_SIZE + " bank.";
            return r;
        }
        boolean priv = "private".equals(o.optString("mode", "open"));
        String key = o.optString("key", "");
        if (priv && key.isEmpty()) {
            r.message = "That private-channel invite is missing its key — "
                    + "ask them to play the join sound again.";
            return r;
        }
        try {
            JSONObject incoming = Profile.defaultSlot(wanted);
            incoming.put("label", o.optString("label", ""));
            incoming.put("mode", priv ? "private" : "open");
            incoming.put("key", priv ? key : "");
            String identity = Profile.slotIdentity(incoming);

            // Already speaking this exact channel somewhere in the bank?
            for (JSONObject s : Profile.configuredSlots(c)) {
                if (identity.equals(Profile.slotIdentity(s))) {
                    r.joined = true;
                    r.alreadyHad = true;
                    r.placedNumber = s.optInt("number");
                    tune(c, r.placedNumber);
                    r.message = "You already have this channel — tuned to "
                            + Profile.slotDisplayName(s) + ".";
                    return r;
                }
            }

            int placed = wanted;
            boolean occupied = isConfigured(c, wanted);
            if (occupied) {
                placed = nextFree(c, wanted);
                if (placed < 0) {
                    r.message = "All " + Profile.BANK_SIZE + " slots are "
                            + "configured — reset one in Channels before "
                            + "joining a new channel.";
                    return r;
                }
                incoming.put("number", placed);
                if (priv) {
                    // Keep the invite's identity: same key, same
                    // channel on the wire, different local number.
                    incoming.put("wireId", identity);
                }
                r.moved = true;
            }
            Profile.saveSlot(c, incoming);
            tune(c, placed);
            r.joined = true;
            r.placedNumber = placed;
            r.message = r.moved
                    ? "Channel joined at slot " + placed + " — slot " + wanted
                        + " already holds a different channel on this phone. "
                        + "Same key, same group; you're tuned and ready to talk."
                    : "Channel " + placed + " added from invite — tuned and "
                        + "ready to talk.";
            return r;
        } catch (Exception e) {
            r.message = "That invite couldn't be read.";
            return r;
        }
    }

    private static boolean isConfigured(Context c, int number) {
        for (JSONObject s : Profile.configuredSlots(c)) {
            if (s.optInt("number") == number) return true;
        }
        return false;
    }

    private static int nextFree(Context c, int wanted) {
        for (int i = 1; i <= Profile.BANK_SIZE; i++) {
            int n = ((wanted - 1 + i) % Profile.BANK_SIZE) + 1;
            if (!isConfigured(c, n)) return n;
        }
        return -1;
    }

    private static void tune(Context c, int number) {
        Prefs.get(c).edit().putInt(ChannelsActivity.PREF_LAST_CHANNEL, number).apply();
    }
}
