package app.control.launcher;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.wifi.p2p.WifiP2pConfig;
import android.net.wifi.p2p.WifiP2pDevice;
import android.net.wifi.p2p.WifiP2pInfo;
import android.net.wifi.p2p.WifiP2pManager;
import android.os.IBinder;
import android.util.Log;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * The mesh internet coordinator (v1.10): one foreground service
 * that owns gateway discovery, the P2P group, the SOCKS relay
 * lifecycle, and NAT start/stop. All decisions run on a single
 * "mesh-coord" thread so the state machine is never re-entered.
 *
 * Reconcile logic (see MeshInternet for the VPN-slot rules):
 *  - Neither toggle on: stop everything, stopSelf.
 *  - Share + online: ensure a P2P group, run the relay, announce.
 *  - Share + offline: relay/announce stop (nothing to share).
 *  - Use + offline: connect to the freshest gateway (first fresh
 *    advertisement wins; failures are skipped for 30s); NEEDS_
 *    CONSENT when VPN permission is missing.
 *  - Use + online: NAT stops (with filter restore).
 *  - Announcements expire after 10s; P2P auto-reconnect to the
 *    last-known gateway is attempted at most every 15s.
 */
public class MeshInternetService extends Service {

    private static final String TAG = "MeshInternetService";
    private static final int NOTIF_ID = 3;

    private static final long GW_EXPIRY_MS = 10_000;
    private static final long GW_FAIL_SKIP_MS = 30_000;
    private static final long CONNECT_MIN_GAP_MS = 15_000;
    private static final long GROUP_MIN_GAP_MS = 15_000;

    private ScheduledExecutorService coord;
    private ConnectivityManager.NetworkCallback netCallback;
    private GatewayDiscovery discovery;
    private GatewayRelay relay;
    private InetAddress relayBindAddr;

    private WifiP2pManager p2p;
    private WifiP2pManager.Channel p2pChannel;
    private BroadcastReceiver p2pReceiver;
    private final List<WifiP2pDevice> peers = new ArrayList<>();
    private boolean groupCreatedByUs;
    private InetAddress groupOwnerAddr;
    private boolean isGroupOwner;
    private long lastGroupAttempt;
    private long lastConnectAttempt;
    private long lastDiscoverAttempt;

    private static final class Tracked {
        GatewayDiscovery.Gateway gw;
        long firstSeen;
        long lastSeen;
        long lastFail;
    }

    private final Map<String, Tracked> gateways = new HashMap<>();
    private String currentGwPub;

    @Override
    public void onCreate() {
        super.onCreate();
        MeshInternet.serviceAlive = true;
        coord = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "mesh-coord");
            t.setDaemon(true);
            return t;
        });
        startForeground(NOTIF_ID, buildNotification("Mesh internet is watching."));
        ensureP2p();
        registerP2pReceiver();
        discovery = new GatewayDiscovery();
        discovery.startListening(gw -> coord.execute(() -> onGateway(gw)));
        registerNetCallback();
        coord.scheduleWithFixedDelay(() -> {
            try {
                reconcileLocked();
            } catch (Exception e) {
                Log.w(TAG, "periodic reconcile failed: " + e.getMessage());
            }
        }, 5, 5, TimeUnit.SECONDS);
        coord.execute(this::reconcileLocked);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) return START_STICKY;
        String action = intent.getAction();
        if (MeshInternet.ACTION_DISCOVER_PEERS.equals(action)) {
            coord.execute(this::discoverPeers);
        } else if (MeshInternet.ACTION_CONNECT_PEER.equals(action)) {
            String addr = intent.getStringExtra(MeshInternet.EXTRA_PEER_ADDR);
            if (addr != null) coord.execute(() -> connectToPeerAddress(addr));
        } else if (MeshInternet.ACTION_GATEWAY_FAILED.equals(action)) {
            String pub = intent.getStringExtra(MeshInternet.EXTRA_GW_PUB);
            if (pub != null) {
                coord.execute(() -> {
                    Tracked t = gateways.get(pub);
                    if (t != null) t.lastFail = System.currentTimeMillis();
                    if (pub.equals(currentGwPub)) currentGwPub = null;
                    reconcileLocked();
                });
            }
        } else {
            coord.execute(this::reconcileLocked);
        }
        return START_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onDestroy() {
        MeshInternet.serviceAlive = false;
        if (coord != null) coord.shutdownNow();
        if (netCallback != null) {
            try {
                ConnectivityManager cm =
                        (ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
                cm.unregisterNetworkCallback(netCallback);
            } catch (Exception ignored) {
            }
        }
        if (discovery != null) discovery.stop();
        stopRelay();
        if (p2pReceiver != null) {
            try {
                unregisterReceiver(p2pReceiver);
            } catch (Exception ignored) {
            }
        }
        // Only tear down a group WE created — never a walkie session's.
        if (groupCreatedByUs && p2p != null && p2pChannel != null) {
            try {
                p2p.removeGroup(p2pChannel, null);
            } catch (SecurityException ignored) {
            }
        }
        MeshInternet.state = MeshInternet.STATE_OFF;
        MeshInternet.gatewayName = null;
        MeshInternet.clientCount = 0;
        super.onDestroy();
    }

    // ---- notification ----

    private Notification buildNotification(String text) {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (nm.getNotificationChannel("mesh-internet") == null) {
            nm.createNotificationChannel(new NotificationChannel(
                    "mesh-internet", "Mesh internet", NotificationManager.IMPORTANCE_LOW));
        }
        PendingIntent open = PendingIntent.getActivity(this, 0,
                new Intent(this, MainActivity.class),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        return new Notification.Builder(this, "mesh-internet")
                .setContentTitle("Mesh internet")
                .setContentText(text)
                .setSmallIcon(R.drawable.ic_launcher_fg)
                .setContentIntent(open)
                .setOngoing(true)
                .build();
    }

    private void updateNotification(String text) {
        try {
            NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            nm.notify(NOTIF_ID, buildNotification(text));
        } catch (Exception ignored) {
        }
    }

    // ---- network callback ----

    private void registerNetCallback() {
        try {
            ConnectivityManager cm =
                    (ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
            netCallback = new ConnectivityManager.NetworkCallback() {
                @Override
                public void onAvailable(Network network) {
                    coord.execute(() -> reconcileLocked());
                }

                @Override
                public void onLost(Network network) {
                    coord.execute(() -> reconcileLocked());
                }

                @Override
                public void onCapabilitiesChanged(Network network, NetworkCapabilities caps) {
                    coord.execute(() -> reconcileLocked());
                }
            };
            cm.registerDefaultNetworkCallback(netCallback);
        } catch (Exception e) {
            Log.w(TAG, "net callback failed: " + e.getMessage());
        }
    }

    // ---- the state machine (coord thread only) ----

    private void onGateway(GatewayDiscovery.Gateway gw) {
        // Ignore our own announcements (broadcast loopback).
        String ownPub = Profile.relayPub(this);
        if (ownPub != null && ownPub.equals(gw.pubB64)) return;
        long now = System.currentTimeMillis();
        Tracked t = gateways.get(gw.pubB64);
        if (t == null) {
            t = new Tracked();
            t.firstSeen = now;
            gateways.put(gw.pubB64, t);
        }
        t.gw = gw;
        t.lastSeen = now;
        reconcileLocked();
    }

    private void reconcileLocked() {
        boolean share = MeshInternet.shareOn(this);
        boolean use = MeshInternet.useOn(this);
        if (!share && !use) {
            stopRelay();
            if (MeshInternet.natOn(this)) MeshInternet.stopNat(this, true);
            MeshInternet.state = MeshInternet.STATE_OFF;
            MeshInternet.gatewayName = null;
            MeshInternet.clientCount = 0;
            MeshInternet.broadcastState(this);
            stopSelf();
            return;
        }
        boolean online = MeshInternet.hasValidatedInternet(this);
        long now = System.currentTimeMillis();

        // Expire stale announcements.
        for (Map.Entry<String, Tracked> e : new ArrayList<>(gateways.entrySet())) {
            if (now - e.getValue().lastSeen > GW_EXPIRY_MS) {
                gateways.remove(e.getKey());
            }
        }

        // Sharing side.
        if (share && online) {
            ensureP2pGroup();
            ensureRelay();
            ensureAnnouncing();
        } else {
            stopRelay();
        }

        // Using side.
        boolean natRunning = MeshInternet.natOn(this);
        if (use && !online) {
            Tracked best = pickGateway(now);
            if (best != null && !best.gw.pubB64.equals(currentGwPub)) {
                // A (new) gateway is available: switch to it.
                if (natRunning) stopNatServiceOnly();
                if (!MeshInternet.vpnConsentGranted(this)) {
                    publishState(MeshInternet.STATE_NEEDS_CONSENT, best.gw.name);
                    return;
                }
                currentGwPub = best.gw.pubB64;
                MeshInternet.startNat(this,
                        best.gw.address.getHostAddress(), best.gw.tcpPort,
                        best.gw.pubB64, best.gw.name);
                publishState(MeshInternet.STATE_USING, best.gw.name);
                return;
            }
            if (best == null) {
                // No gateway in range: try the P2P road back to the
                // last-known gateway, then report searching.
                if (natRunning && currentGwPub != null
                        && !gateways.containsKey(currentGwPub)) {
                    // Current gateway went quiet but NAT is up: leave
                    // it — NAT self-terminates if the gateway is gone.
                    publishState(MeshInternet.STATE_USING, MeshInternet.gatewayName);
                    return;
                }
                maybeReconnectP2p();
                if (!hasP2pPermission() && !natRunning) {
                    publishState(MeshInternet.STATE_NO_PERMISSION, null);
                } else if (!natRunning) {
                    publishState(MeshInternet.STATE_SEARCHING, null);
                } else {
                    publishState(MeshInternet.STATE_USING, MeshInternet.gatewayName);
                }
                return;
            }
            // best == current gateway: NAT already runs to it.
            publishState(MeshInternet.STATE_USING, best.gw.name);
            return;
        }
        // Online, or use is off: NAT must not run.
        if (natRunning) {
            MeshInternet.stopNat(this, true);
            currentGwPub = null;
        }
        if (relay != null) {
            publishState(MeshInternet.STATE_SHARING, null);
        } else if (share) {
            publishState(MeshInternet.STATE_SHARING_NO_INTERNET, null);
        } else {
            publishState(MeshInternet.STATE_IDLE, null);
        }
    }

    /** First fresh advertisement wins; failures skipped for 30s. */
    private Tracked pickGateway(long now) {
        Tracked best = null;
        for (Tracked t : gateways.values()) {
            if (now - t.lastSeen > GW_EXPIRY_MS) continue;
            if (now - t.lastFail < GW_FAIL_SKIP_MS) continue;
            if (best == null || t.firstSeen < best.firstSeen) best = t;
        }
        return best;
    }

    private void publishState(String state, String gwName) {
        boolean changed = !state.equals(MeshInternet.state)
                || (gwName == null ? MeshInternet.gatewayName != null
                : !gwName.equals(MeshInternet.gatewayName));
        MeshInternet.state = state;
        MeshInternet.gatewayName = gwName;
        if (relay == null) MeshInternet.clientCount = 0;
        if (changed) {
            MeshInternet.broadcastState(this);
            updateNotification(stateText(state, gwName));
        }
    }

    private String stateText(String state, String gwName) {
        switch (state) {
            case MeshInternet.STATE_SHARING:
                return MeshInternet.clientCount > 0
                        ? "Sharing with " + MeshInternet.clientCount + " phone(s)."
                        : "Sharing your connection with the mesh.";
            case MeshInternet.STATE_USING:
                return "Using mesh internet via " + (gwName != null ? gwName : "a nearby phone")
                        + " — ad blocking included.";
            case MeshInternet.STATE_SEARCHING:
                return "Looking for a nearby phone sharing its connection…";
            case MeshInternet.STATE_NEEDS_CONSENT:
                return "A gateway is nearby — VPN permission is needed to use it.";
            case MeshInternet.STATE_NO_PERMISSION:
                return "Nearby-device permission is needed to find mesh phones.";
            case MeshInternet.STATE_SHARING_NO_INTERNET:
                return "Sharing is on, but this phone has no internet to share.";
            default:
                return "Mesh internet is watching.";
        }
    }

    /** Stops the NAT service WITHOUT touching the parked-filter
     *  flags — used when switching gateways (startNat re-parks
     *  only if the filter is actually on). */
    private void stopNatServiceOnly() {
        Intent stop = new Intent(this, NatVpnService.class);
        stop.setAction(NatVpnService.ACTION_STOP);
        startService(stop);
    }

    // ---- relay + announce ----

    private void ensureRelay() {
        InetAddress bind = relayBindAddress();
        if (bind == null) return; // no mesh/LAN interface yet
        if (relay != null && bind.equals(relayBindAddr)) return;
        stopRelay();
        try {
            byte[] pub = RelayKeys.b64UrlDecode(Profile.relayPub(this));
            relay = new GatewayRelay(pub, Prefs.upstreamDns(this),
                    count -> {
                        MeshInternet.clientCount = count;
                        MeshInternet.broadcastState(this);
                        updateNotification(stateText(MeshInternet.state,
                                MeshInternet.gatewayName));
                    });
            relay.start(bind, GatewayDiscovery.RELAY_PORT);
            relayBindAddr = bind;
            OwnLog.log(this, "Mesh internet", "Gateway relay started (mesh interface only)");
        } catch (Exception e) {
            Log.w(TAG, "relay start failed: " + e.getMessage());
            relay = null;
            relayBindAddr = null;
        }
    }

    private void stopRelay() {
        if (relay != null) {
            relay.stop();
            relay = null;
            relayBindAddr = null;
            MeshInternet.clientCount = 0;
            if (discovery != null) discovery.stopAnnouncing();
            OwnLog.log(this, "Mesh internet", "Gateway relay stopped");
        } else if (discovery != null) {
            discovery.stopAnnouncing();
        }
    }

    private void ensureAnnouncing() {
        if (relay == null || discovery == null) return;
        discovery.startAnnouncing(Profile.displayName(this),
                Profile.relayPub(this), MeshInternet.transportType(this));
    }

    /** Bind address order: the P2P group-owner address (when we are
     *  the GO), the first IPv4 on a p2p* interface, the Wi-Fi
     *  address. Never a wildcard. */
    private InetAddress relayBindAddress() {
        if (isGroupOwner && groupOwnerAddr != null) return groupOwnerAddr;
        InetAddress p2p = firstInterfaceAddress("p2p");
        if (p2p != null) return p2p;
        InetAddress wlan = firstInterfaceAddress("wlan");
        if (wlan != null) return wlan;
        if (groupOwnerAddr != null) return groupOwnerAddr;
        return null;
    }

    private InetAddress firstInterfaceAddress(String prefix) {
        try {
            Enumeration<NetworkInterface> ifs = NetworkInterface.getNetworkInterfaces();
            while (ifs.hasMoreElements()) {
                NetworkInterface ni = ifs.nextElement();
                if (!ni.getName().startsWith(prefix) || !ni.isUp()) continue;
                Enumeration<InetAddress> addrs = ni.getInetAddresses();
                while (addrs.hasMoreElements()) {
                    InetAddress a = addrs.nextElement();
                    if (a instanceof Inet4Address && !a.isLoopbackAddress()) return a;
                }
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    // ---- Wi-Fi Direct ----

    private boolean hasP2pPermission() {
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            return checkSelfPermission(
                    android.Manifest.permission.NEARBY_WIFI_DEVICES)
                    == PackageManager.PERMISSION_GRANTED;
        }
        return checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION)
                == PackageManager.PERMISSION_GRANTED;
    }

    private void ensureP2p() {
        if (p2p != null) return;
        try {
            p2p = (WifiP2pManager) getSystemService(Context.WIFI_P2P_SERVICE);
            if (p2p != null) {
                p2pChannel = p2p.initialize(this, getMainLooper(), null);
            }
        } catch (Exception e) {
            Log.w(TAG, "p2p init failed: " + e.getMessage());
        }
    }

    private void registerP2pReceiver() {
        if (p2p == null || p2pReceiver != null) return;
        IntentFilter filter = new IntentFilter();
        filter.addAction(WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION);
        filter.addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION);
        p2pReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                String action = intent.getAction();
                if (WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION.equals(action)) {
                    try {
                        p2p.requestPeers(p2pChannel, peerList -> {
                            synchronized (peers) {
                                peers.clear();
                                peers.addAll(peerList.getDeviceList());
                            }
                            broadcastPeers();
                            coord.execute(() -> maybeReconnectP2p());
                        });
                    } catch (SecurityException ignored) {
                    }
                } else if (WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION.equals(action)) {
                    try {
                        p2p.requestConnectionInfo(p2pChannel, MeshInternetService.this::onP2pInfo);
                    } catch (SecurityException ignored) {
                    }
                }
            }
        };
        try {
            registerReceiver(p2pReceiver, filter);
        } catch (Exception e) {
            Log.w(TAG, "p2p receiver failed: " + e.getMessage());
        }
    }

    private void onP2pInfo(WifiP2pInfo info) {
        groupOwnerAddr = info.groupOwnerAddress;
        isGroupOwner = info.isGroupOwner;
        if (info.groupFormed) {
            // A group exists now: the relay may need to rebind, and a
            // gateway on this group may start announcing.
            coord.execute(this::reconcileLocked);
        }
    }

    /** Sharing needs a group to exist so joiners have somewhere to
     *  connect. We prefer to be the group owner (createGroup), but
     *  an existing group is fine too. */
    private void ensureP2pGroup() {
        if (p2p == null || p2pChannel == null || !hasP2pPermission()) return;
        long now = System.currentTimeMillis();
        if (now - lastGroupAttempt < GROUP_MIN_GAP_MS) return;
        lastGroupAttempt = now;
        try {
            p2p.requestConnectionInfo(p2pChannel, info -> {
                if (info.groupFormed) {
                    onP2pInfo(info);
                    return;
                }
                try {
                    p2p.createGroup(p2pChannel, new WifiP2pManager.ActionListener() {
                        @Override
                        public void onSuccess() {
                            groupCreatedByUs = true;
                            try {
                                p2p.requestConnectionInfo(p2pChannel,
                                        MeshInternetService.this::onP2pInfo);
                            } catch (SecurityException ignored) {
                            }
                        }

                        @Override
                        public void onFailure(int reason) {
                            // A group may already exist — re-check.
                            try {
                                p2p.requestConnectionInfo(p2pChannel,
                                        MeshInternetService.this::onP2pInfo);
                            } catch (SecurityException ignored) {
                            }
                        }
                    });
                } catch (SecurityException ignored) {
                }
            });
        } catch (SecurityException ignored) {
        }
    }

    /** Client side: if we are offline with no gateway and the
     *  last-known gateway peer is visible, connect to it (PBC).
     *  First-time pairing still needs the system accept tap. */
    private void maybeReconnectP2p() {
        if (!MeshInternet.useOn(this) || MeshInternet.hasValidatedInternet(this)) return;
        if (MeshInternet.natOn(this)) return;
        if (!hasP2pPermission() || p2p == null || p2pChannel == null) return;
        String last = Prefs.get(this).getString(Prefs.KEY_MESH_LAST_GW_P2P, "");
        if (last == null || last.isEmpty()) return;
        long now = System.currentTimeMillis();
        if (now - lastConnectAttempt < CONNECT_MIN_GAP_MS) return;
        List<WifiP2pDevice> snapshot;
        synchronized (peers) {
            snapshot = new ArrayList<>(peers);
        }
        for (WifiP2pDevice d : snapshot) {
            if (last.equals(d.deviceAddress)) {
                lastConnectAttempt = now;
                connectToPeerAddress(last);
                return;
            }
        }
    }

    private void discoverPeers() {
        if (p2p == null || p2pChannel == null) {
            broadcastPeers();
            return;
        }
        if (!hasP2pPermission()) {
            broadcastPeers();
            return;
        }
        long now = System.currentTimeMillis();
        if (now - lastDiscoverAttempt < CONNECT_MIN_GAP_MS) {
            broadcastPeers();
            return;
        }
        lastDiscoverAttempt = now;
        try {
            p2p.discoverPeers(p2pChannel, new WifiP2pManager.ActionListener() {
                @Override
                public void onSuccess() {
                    broadcastPeers();
                }

                @Override
                public void onFailure(int reason) {
                    broadcastPeers();
                }
            });
        } catch (SecurityException ignored) {
            broadcastPeers();
        }
    }

    private void broadcastPeers() {
        List<WifiP2pDevice> snapshot;
        synchronized (peers) {
            snapshot = new ArrayList<>(peers);
        }
        String[] out = new String[snapshot.size()];
        for (int i = 0; i < snapshot.size(); i++) {
            out[i] = snapshot.get(i).deviceName + "|" + snapshot.get(i).deviceAddress;
        }
        Intent b = new Intent(MeshInternet.ACTION_PEERS);
        b.putExtra(MeshInternet.EXTRA_PEERS, out);
        sendBroadcast(b);
    }

    private void connectToPeerAddress(String deviceAddress) {
        if (p2p == null || p2pChannel == null || !hasP2pPermission()) return;
        Prefs.get(this).edit().putString(Prefs.KEY_MESH_LAST_GW_P2P, deviceAddress).apply();
        WifiP2pConfig config = new WifiP2pConfig();
        config.deviceAddress = deviceAddress;
        config.wps.setup = android.net.wifi.WpsInfo.PBC;
        try {
            p2p.connect(p2pChannel, config, new WifiP2pManager.ActionListener() {
                @Override
                public void onSuccess() {
                    // Connection-changed broadcast drives the rest.
                }

                @Override
                public void onFailure(int reason) {
                    Log.w(TAG, "p2p connect failed: reason " + reason);
                }
            });
        } catch (SecurityException ignored) {
        }
    }
}
