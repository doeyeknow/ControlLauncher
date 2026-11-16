package app.control.launcher;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Access layer with a split personality, on purpose:
 *
 *  - USER DATA (favorites, mutes, bypass list, resolver, browser JS,
 *    clock toggle) lives in the Profile — the portable JSON document.
 *    The getters/setters here delegate to it, so every module reads and
 *    writes the profile without knowing it.
 *  - RUNTIME STATE (filter on/off, blocked counter, relay pull
 *    cursors, bridge token) stays in SharedPreferences: it is
 *    device-local and is never exported. (The relay IDENTITY is not
 *    runtime state — it lives in the Profile, on purpose.)
 */
public final class Prefs {
    private static final String FILE = "control_prefs";

    public static final String KEY_FAVORITES = "favorites";
    public static final String KEY_MUTED_APPS = "muted_apps";
    public static final String KEY_BYPASS_APPS = "bypass_apps";

    // Runtime-only keys (SharedPreferences).
    public static final String KEY_FILTER_ON = "filter_on";
    public static final String KEY_BLOCKED_COUNT = "blocked_count";
    public static final String KEY_RELAY_REGISTERED = "relay_registered";
    public static final String KEY_RELAY_REG_ATTEMPT = "relay_reg_attempt";
    public static final String KEY_FIRSTRUN_DONE = "firstrun_done";
    public static final String KEY_QUIET_ALL = "quiet_all";
    public static final String KEY_APP_LOCKED = "app_locked";
    public static final String KEY_WIPE_ARMED_UNTIL = "wipe_armed_until";
    public static final String KEY_WIPE_ARMED_FROM = "wipe_armed_from";
    public static final String KEY_WIPE_DEVICE_ARMED_UNTIL = "wipe_device_armed_until";
    public static final String KEY_WIPE_DEVICE_ARMED_FROM = "wipe_device_armed_from";
    public static final String KEY_ADB_PAIRED = "adb_paired";
    public static final String KEY_TERMINAL_HISTORY = "terminal_history";
    public static final String KEY_WALLET_PAY_APP = "wallet_pay_app";
    public static final String KEY_WALKIE_STATE = "walkie_state";
    public static final String KEY_BRIDGE_TOKEN = "bridge_token";
    public static final String KEY_BRIDGE_STATE = "bridge_state";
    // Bridge auto-restart watchdog (v1.16) — runtime state,
    // device-local. True once Deep view has run (a bridge start
    // succeeded, or Deep view found the bridge up); cleared by
    // "Turn off deep control". Default false: the watchdog
    // never starts the bridge for someone who never enabled
    // Deep view.
    public static final String KEY_DEEP_VIEW_ON = "deep_view_on";
    // Mesh internet (v1.10) — runtime state, device-local.
    public static final String KEY_MESH_SHARE = "mesh_share";
    public static final String KEY_MESH_USE = "mesh_use";
    public static final String KEY_MESH_NAT_ON = "mesh_nat_on";
    public static final String KEY_FILTER_PARKED = "filter_parked";
    public static final String KEY_MESH_LAST_GW_P2P = "mesh_last_gw_p2p";
    public static final String KEY_MESH_LAST_GW_NAME = "mesh_last_gw_name";
    // Walkie low-data mode (v1.11) — runtime state, device-local.
    // The mode needs the downloaded speech model, which is itself
    // device-local; the per-contact voice profiles live in the
    // profile (see Profile.contactVoice) and travel with it.
    public static final String KEY_LOWDATA_ON = "lowdata_on";
    public static final String KEY_LOWDATA_AUTOREAD = "lowdata_autoread";
    // Hold-anywhere overlay menu (v1.14) — runtime state,
    // device-local. The switch defaults ON; the overlay
    // permission + a running bridge are the real gates.
    public static final String KEY_OVERLAY_RING_ON = "overlay_ring_on";
    // First-run setup + theme (v1.15) — runtime state,
    // device-local. The theme is a device preference, not part
    // of the portable profile: importing a profile never flips
    // it. Dark is the default, so upgrades keep their look.
    public static final String KEY_THEME_LIGHT = "theme_light";
    // The setup step the user last reached; setup resumes there
    // if it is left unfinished. Cleared when setup completes.
    public static final String KEY_SETUP_STEP = "setup_step";

    private Prefs() {}

    public static SharedPreferences get(Context c) {
        return c.getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }

    // ---- profile-backed sets ----

    public static Set<String> getSet(Context c, String key) {
        switch (key) {
            case KEY_FAVORITES: return Profile.favorites(c);
            case KEY_MUTED_APPS: return Profile.mutedApps(c);
            case KEY_BYPASS_APPS: return Profile.bypassApps(c);
            default: break;
        }
        String csv = get(c).getString(key, "");
        LinkedHashSet<String> out = new LinkedHashSet<>();
        if (csv != null && !csv.isEmpty()) {
            for (String s : csv.split(",")) {
                if (!s.isEmpty()) out.add(s.trim());
            }
        }
        return out;
    }

    public static void putSet(Context c, String key, Set<String> values) {
        switch (key) {
            case KEY_FAVORITES: Profile.setFavorites(c, values); return;
            case KEY_MUTED_APPS: Profile.setMutedApps(c, values); return;
            case KEY_BYPASS_APPS: Profile.setBypassApps(c, values); return;
            default: break;
        }
        StringBuilder sb = new StringBuilder();
        for (String v : values) {
            if (sb.length() > 0) sb.append(',');
            sb.append(v);
        }
        get(c).edit().putString(key, sb.toString()).apply();
    }

    // ---- profile-backed scalars ----

    public static String upstreamDns(Context c) {
        return Profile.upstreamDns(c);
    }

    public static boolean showClock(Context c) {
        return Profile.showClock(c);
    }

    public static boolean jsOn(Context c) {
        return Profile.jsEnabled(c);
    }
}
