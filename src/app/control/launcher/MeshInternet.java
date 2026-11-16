package app.control.launcher;

import android.content.Context;
import android.content.Intent;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.VpnService;
import android.util.Log;

/**
 * Mesh internet coordination helpers (v1.10): toggle prefs, the
 * online check, the VPN-slot rules, and the intents/broadcasts
 * shared by the Connections UI, the coordinator service, and the
 * NAT service.
 *
 * THE VPN-SLOT RULES (one slot, two services — stated precisely):
 *  - Starting NAT while the filter is on PARKS the filter: the
 *    filter service is stopped, KEY_FILTER_PARKED is set, and NAT
 *    runs with blocklist enforcement included. Ad blocking is
 *    never silently dropped.
 *  - Stopping NAT RESTORES the filter if it was parked — but only
 *    when VPN consent is already granted (VpnService.prepare()
 *    returns null). If consent was revoked, the filter stays off
 *    and the audit log says so honestly.
 *  - Turning the filter on while NAT runs stops NAT first WITHOUT
 *    restoring (the filter start that follows takes the slot).
 *  - If the system revokes the NAT VPN, NAT_ON clears and the
 *    parked filter is restored by the same rule.
 *  - reconcile() is the backstop: any entry point that runs while
 *    NAT is not running restores a parked filter.
 */
public final class MeshInternet {

    private static final String TAG = "MeshInternet";

    // Broadcast: the coordinator's state changed; extras carry the
    // state string, gateway name, and client count.
    public static final String ACTION_MESH_STATE_CHANGED =
            "app.control.launcher.MESH_STATE_CHANGED";
    public static final String EXTRA_STATE = "mesh_state";
    public static final String EXTRA_GATEWAY_NAME = "mesh_gateway_name";
    public static final String EXTRA_CLIENTS = "mesh_clients";

    // Broadcast: P2P peer list for the "Find nearby phones" dialog.
    // Extra EXTRA_PEERS holds String[] of "name|address".
    public static final String ACTION_PEERS = "app.control.launcher.MESH_PEERS";
    public static final String EXTRA_PEERS = "mesh_peers";

    // Service actions (MeshInternetService).
    public static final String ACTION_RECONCILE = "app.control.launcher.MESH_RECONCILE";
    public static final String ACTION_DISCOVER_PEERS =
            "app.control.launcher.MESH_DISCOVER_PEERS";
    public static final String ACTION_CONNECT_PEER =
            "app.control.launcher.MESH_CONNECT_PEER";
    public static final String EXTRA_PEER_ADDR = "mesh_peer_addr";
    // The NAT service reports a gateway that failed, so the
    // coordinator backs off it for a while instead of flapping.
    public static final String ACTION_GATEWAY_FAILED =
            "app.control.launcher.MESH_GATEWAY_FAILED";
    public static final String EXTRA_GW_PUB = "mesh_gw_pub";

    // States (also the UI's vocabulary).
    public static final String STATE_OFF = "off";
    public static final String STATE_IDLE = "idle";
    public static final String STATE_SHARING = "sharing";
    public static final String STATE_SHARING_NO_INTERNET = "sharing_no_internet";
    public static final String STATE_USING = "using";
    public static final String STATE_SEARCHING = "searching";
    public static final String STATE_NEEDS_CONSENT = "needs_consent";
    public static final String STATE_NO_PERMISSION = "no_permission";

    /** Latest coordinator state (written by the service). */
    public static volatile String state = STATE_OFF;
    public static volatile String gatewayName = null;
    public static volatile int clientCount = 0;
    public static volatile boolean serviceAlive = false;

    private MeshInternet() {}

    // ---- toggles ----

    /** "Share mobile data with the mesh" — DEFAULT OFF. */
    public static boolean shareOn(Context c) {
        return Prefs.get(c).getBoolean(Prefs.KEY_MESH_SHARE, false);
    }

    /** "Use mesh internet when offline" — DEFAULT ON. */
    public static boolean useOn(Context c) {
        return Prefs.get(c).getBoolean(Prefs.KEY_MESH_USE, true);
    }

    public static boolean natOn(Context c) {
        return Prefs.get(c).getBoolean(Prefs.KEY_MESH_NAT_ON, false);
    }

    public static void setShare(Context c, boolean on) {
        Prefs.get(c).edit().putBoolean(Prefs.KEY_MESH_SHARE, on).apply();
    }

    public static void setUse(Context c, boolean on) {
        Prefs.get(c).edit().putBoolean(Prefs.KEY_MESH_USE, on).apply();
    }

    // ---- connectivity ----

    /** True when the active network is validated (real internet),
     *  on any transport. */
    public static boolean hasValidatedInternet(Context c) {
        try {
            ConnectivityManager cm =
                    (ConnectivityManager) c.getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm == null) return false;
            Network net = cm.getActiveNetwork();
            if (net == null) return false;
            NetworkCapabilities caps = cm.getNetworkCapabilities(net);
            return caps != null
                    && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED);
        } catch (Exception e) {
            return false;
        }
    }

    /** "cellular", "wifi", or "other" for the active network. */
    public static String transportType(Context c) {
        try {
            ConnectivityManager cm =
                    (ConnectivityManager) c.getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm == null) return "other";
            Network net = cm.getActiveNetwork();
            if (net == null) return "other";
            NetworkCapabilities caps = cm.getNetworkCapabilities(net);
            if (caps == null) return "other";
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) return "cellular";
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) return "wifi";
            return "other";
        } catch (Exception e) {
            return "other";
        }
    }

    /** True when VPN consent is already granted (no prompt needed). */
    public static boolean vpnConsentGranted(Context c) {
        try {
            return VpnService.prepare(c) == null;
        } catch (Exception e) {
            return false;
        }
    }

    // ---- service lifecycle ----

    /** Starts the coordinator if either toggle demands it, and asks
     *  it to reconcile. Safe to call from anywhere, any time. */
    public static void reconcile(Context c, boolean poke) {
        // Backstop: a parked filter with NAT not running is restored
        // here, covering the system-kill path where no service runs.
        if (!natOn(c) && Prefs.get(c).getBoolean(Prefs.KEY_FILTER_PARKED, false)) {
            restoreParkedFilter(c);
        }
        if (!shareOn(c) && !useOn(c)) {
            if (serviceAlive) {
                Intent stop = new Intent(c, MeshInternetService.class);
                stop.setAction(ACTION_RECONCILE);
                c.startService(stop); // the service stops itself
            }
            return;
        }
        Intent i = new Intent(c, MeshInternetService.class);
        i.setAction(ACTION_RECONCILE);
        try {
            c.startForegroundService(i);
        } catch (Exception e) {
            Log.w(TAG, "reconcile start failed: " + e.getMessage());
        }
    }

    public static void broadcastState(Context c) {
        Intent b = new Intent(ACTION_MESH_STATE_CHANGED);
        b.putExtra(EXTRA_STATE, state);
        b.putExtra(EXTRA_GATEWAY_NAME, gatewayName);
        b.putExtra(EXTRA_CLIENTS, clientCount);
        c.sendBroadcast(b);
    }

    // ---- VPN-slot rules ----

    /** Starts NAT through the given gateway, parking the filter
     *  first when it is on. */
    public static void startNat(Context c, String gwAddr, int gwPort,
                                String gwPubB64, String gwName) {
        boolean filterOn = Prefs.get(c).getBoolean(Prefs.KEY_FILTER_ON, false);
        if (filterOn) {
            Prefs.get(c).edit().putBoolean(Prefs.KEY_FILTER_PARKED, true).apply();
            Intent stop = new Intent(c, DnsVpnService.class);
            stop.setAction(DnsVpnService.ACTION_STOP);
            c.startService(stop);
        }
        Prefs.get(c).edit().putBoolean(Prefs.KEY_MESH_NAT_ON, true).apply();
        Prefs.get(c).edit().putString(Prefs.KEY_MESH_LAST_GW_NAME, gwName).apply();
        Intent start = new Intent(c, NatVpnService.class);
        start.setAction(NatVpnService.ACTION_START);
        start.putExtra(NatVpnService.EXTRA_GW_ADDR, gwAddr);
        start.putExtra(NatVpnService.EXTRA_GW_PORT, gwPort);
        start.putExtra(NatVpnService.EXTRA_GW_PUB, gwPubB64);
        start.putExtra(NatVpnService.EXTRA_GW_NAME, gwName);
        try {
            c.startForegroundService(start);
        } catch (Exception e) {
            Log.w(TAG, "startNat failed: " + e.getMessage());
            onNatFailed(c);
        }
    }

    /** Stops NAT. When restore is true, a parked filter is
     *  restarted (consent permitting); when false, the parked flag
     *  is simply cleared — the caller is starting the filter
     *  itself. */
    public static void stopNat(Context c, boolean restore) {
        if (!natOn(c)
                && !Prefs.get(c).getBoolean(Prefs.KEY_FILTER_PARKED, false)) {
            return;
        }
        Prefs.get(c).edit().putBoolean(Prefs.KEY_MESH_NAT_ON, false).apply();
        Intent stop = new Intent(c, NatVpnService.class);
        stop.setAction(NatVpnService.ACTION_STOP);
        c.startService(stop);
        if (Prefs.get(c).getBoolean(Prefs.KEY_FILTER_PARKED, false)) {
            Prefs.get(c).edit().putBoolean(Prefs.KEY_FILTER_PARKED, false).apply();
            if (restore) {
                startFilterIfConsented(c);
            }
        }
        broadcastState(c);
    }

    /** NAT failed to start or died: clear the flag, restore a
     *  parked filter, tell the UI. */
    public static void onNatFailed(Context c) {
        onNatFailed(c, null);
    }

    /** Same, plus telling the coordinator which gateway failed so
     *  it backs off that gateway for a while. */
    public static void onNatFailed(Context c, String gwPubB64) {
        Prefs.get(c).edit().putBoolean(Prefs.KEY_MESH_NAT_ON, false).apply();
        if (Prefs.get(c).getBoolean(Prefs.KEY_FILTER_PARKED, false)) {
            Prefs.get(c).edit().putBoolean(Prefs.KEY_FILTER_PARKED, false).apply();
            startFilterIfConsented(c);
        }
        if (gwPubB64 != null && serviceAlive) {
            Intent failed = new Intent(c, MeshInternetService.class);
            failed.setAction(ACTION_GATEWAY_FAILED);
            failed.putExtra(EXTRA_GW_PUB, gwPubB64);
            c.startService(failed);
        }
        broadcastState(c);
    }

    /** The system revoked the NAT VPN: same as a failure. */
    public static void onNatRevoked(Context c) {
        onNatFailed(c);
    }

    public static void restoreParkedFilter(Context c) {
        if (!Prefs.get(c).getBoolean(Prefs.KEY_FILTER_PARKED, false)) return;
        Prefs.get(c).edit().putBoolean(Prefs.KEY_FILTER_PARKED, false).apply();
        startFilterIfConsented(c);
    }

    private static void startFilterIfConsented(Context c) {
        if (!vpnConsentGranted(c)) {
            OwnLog.log(c, "Mesh internet",
                    "Ad filter was parked for mesh internet but VPN permission "
                            + "is gone — the filter stays off until you turn it on.");
            return;
        }
        Intent start = new Intent(c, DnsVpnService.class);
        start.setAction(DnsVpnService.ACTION_START);
        try {
            c.startForegroundService(start);
        } catch (Exception e) {
            Log.w(TAG, "filter restore failed: " + e.getMessage());
        }
    }
}
