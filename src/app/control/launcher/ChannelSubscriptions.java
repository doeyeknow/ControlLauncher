package app.control.launcher;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/**
 * The channel-subscription store (v1.19): which walkie slots
 * this phone keeps listening to over the internet relay, even
 * when tuned elsewhere.
 *
 * STORAGE DECISION: subscriptions live in the portable profile
 * (a top-level "subscriptions" array of slot numbers in
 * profile.json), alongside the channel bank itself. They are
 * the user's channel choices — like favorites — so they travel
 * with the encrypted profile export and import, and a phone
 * restored from a profile resumes listening to the same
 * channels. The relay pull CURSORS stay device-local prefs
 * (RelayClient, as before): a restored profile re-pulls from
 * each channel's retained history only as far as the fresh
 * cursors allow, and the subscription service's prime step
 * fast-forwards a never-polled channel to live on first poll.
 *
 * Subscriptions follow the slot NUMBER, not the channel
 * identity: reconfiguring a slot (new key, new label) keeps
 * the subscription, and the relay channel id is re-derived
 * from the current slot config on every poll cycle.
 *
 * Also owns the unplayed counts the channel grid and the
 * walkie page show: received entries that have not been
 * played yet (HistoryRule), per slot.
 */
public final class ChannelSubscriptions {
    private static final String KEY = "subscriptions";

    private ChannelSubscriptions() {}

    /** The subscribed slot numbers, normalized and sorted. */
    public static TreeSet<Integer> subscribed(Context c) {
        JSONArray arr = Profile.load(c).optJSONArray(KEY);
        List<Integer> raw = new ArrayList<>();
        if (arr != null) {
            for (int i = 0; i < arr.length(); i++) raw.add(arr.optInt(i, -1));
        }
        return SubscriptionPolicy.normalizeSet(raw);
    }

    public static boolean isSubscribed(Context c, int number) {
        return subscribed(c).contains(number);
    }

    /** Subscribes or unsubscribes one slot, persisting
     *  immediately. Out-of-range numbers are ignored. */
    public static void setSubscribed(Context c, int number, boolean on) {
        Set<Integer> next = SubscriptionPolicy.toggle(subscribed(c), number, on);
        JSONArray arr = new JSONArray();
        for (int n : next) arr.put(n);
        try {
            Profile.load(c).put(KEY, arr);
        } catch (Exception ignored) {
        }
        Profile.save(c);
    }

    /** Received, unplayed entries in a slot's stored thread —
     *  the "new" count for one channel. Own entries never
     *  count: the user never "receives" them. */
    public static int unplayedCount(Context c, JSONObject slot) {
        if (c == null || slot == null) return 0;
        int n = 0;
        for (JSONObject e : WalkieStore.thread(c, Profile.slotIdentity(slot))) {
            if (!HistoryRule.isMine(e) && !HistoryRule.isPlayed(e)) n++;
        }
        return n;
    }

    /** Total unplayed received entries on every channel
     *  EXCEPT one slot number — the "messages waiting on
     *  other channels" total the walkie page's channel button
     *  shows. */
    public static int unplayedTotalExcept(Context c, int exceptNumber) {
        if (c == null) return 0;
        int total = 0;
        for (int n = 1; n <= Profile.BANK_SIZE; n++) {
            if (n == exceptNumber) continue;
            total += unplayedCount(c, Profile.slot(c, n));
        }
        return total;
    }
}
