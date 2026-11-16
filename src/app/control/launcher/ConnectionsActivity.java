package app.control.launcher;

import android.app.Activity;
import android.app.AlertDialog;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.graphics.Typeface;
import android.location.LocationManager;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.net.VpnService;
import android.net.wifi.ScanResult;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.net.wifi.WifiNetworkSuggestion;
import android.nfc.NfcAdapter;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.telephony.ServiceState;
import android.telephony.SignalStrength;
import android.telephony.TelephonyManager;
import android.view.MotionEvent;
import android.view.View;
import android.widget.Button;
import android.widget.PopupMenu;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Connections (v1.2; redesigned v1.13): one screen for the phone's
 * radios — Wi-Fi, Bluetooth, Cellular, Airplane mode, NFC,
 * Location, Control's own VPN &amp; filter, Mesh internet, and the
 * Deep-view (bridge) status.
 *
 * v1.13 layout (Adam's order): the screen is a clean list — one
 * compact row per item, name + live status, nothing else. The
 * explanatory paragraphs are OFF the list entirely. TAP a row opens
 * a popup modal carrying that item's details + actions, exactly the
 * controls its old card had, with a "?" button inside the modal
 * revealing the item's info text (the chat "?" popup pattern).
 * LONG-PRESS a row opens a popup menu with the item's quick
 * actions. Modal buttons and menu items run the SAME factored
 * action methods — no duplicated logic. This is presentation +
 * structure only: every behavior (live refresh, bridge tier,
 * governed confirmations, VPN-slot interaction, mesh consent
 * flow) is unchanged.
 *
 * Two honest tiers, because Android 10+ reserves radio toggles to
 * the system:
 *
 *   BASE (no bridge): live status, scans, system panels and
 *   settings deep-links. Items that can't be switched in-app say
 *   why, behind the "?", and what Deep view changes.
 *
 *   BRIDGE (while the shell bridge is connected): the same items
 *   gain real switches, run as governed BridgeControl named ops
 *   (svc wifi/data/bluetooth/nfc, airplane mode) — confirmed,
 *   audit-logged, and reverted to base tier live when the bridge
 *   drops.
 *
 * Status refresh is event-driven (receivers + a NetworkCallback) —
 * no polling loops. Refresh methods always update the row status
 * lines; the modal's detail views are bound only while its modal
 * is open, and every refresh null-guards them.
 */
public class ConnectionsActivity extends ThemedActivity {

    private static final int REQ_PERMS = 7300;
    private static final int REQ_MESH_VPN = 7301;

    /** Scrolls to an item's row on open (v1.9: the circle menu's
     *  Connections sub-ring; v1.13: the row is scrolled to and
     *  highlighted, the modal is NOT auto-opened). Values are the
     *  item titles: "Wi-Fi", "Bluetooth", "Cellular",
     *  "Airplane mode", "NFC", "Location", "VPN & filter"
     *  ("Mesh internet" and "Deep view" also resolve). Navigation
     *  only — the ring flips nothing. */
    public static final String EXTRA_SECTION = "control.conn_section";

    private final Handler ui = new Handler(Looper.getMainLooper());
    /** Item title → its list row (v1.13: rows, not cards). */
    private final java.util.Map<String, View> sectionCards =
            new java.util.LinkedHashMap<>();

    private volatile boolean bridgeOn;
    private boolean syncingSwitches;

    // ---- row status lines: bound at onCreate, always live ----
    private TextView wifiRowStatus;
    private TextView btRowStatus;
    private TextView cellRowStatus;
    private TextView airplaneRowStatus;
    private TextView nfcRowStatus;
    private TextView locRowStatus;
    private TextView filterRowStatus;
    private TextView meshRowStatus;
    private TextView bridgeRowStatus;

    // ---- modal-bound views: non-null only while that item's
    // modal is open; every refresh null-guards them, and the
    // modal's dismiss listener clears them all together. ----
    private TextView bridgeDetail;

    // Wi-Fi
    private TextView wifiStatus;
    private LinearLayout wifiScanBox;
    private Switch wifiSwitch;
    private Button wifiGrantBtn;

    // Bluetooth
    private TextView btStatus;
    private LinearLayout btBox;
    private Switch btSwitch;

    // Cellular
    private TextView cellStatus;
    private Switch dataSwitch;
    private Button cellGrantBtn;

    // Airplane / NFC / Location / filter
    private TextView airplaneStatus;
    private Switch airplaneSwitch;
    private TextView nfcStatus;
    private Switch nfcSwitch;
    private TextView locStatus;
    private TextView filterStatus;

    // Mesh internet (v1.10)
    private TextView meshStatus;
    private Switch meshShareSwitch;
    private Switch meshUseSwitch;
    private Button meshConsentBtn;
    private boolean peerDialogWanted;

    // The open item modal, if any.
    private AlertDialog itemDialog;
    private String openItem;

    private ConnectivityManager.NetworkCallback netCallback;
    private boolean receiversOn;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        LinearLayout root = Ui.column(this);
        int pad = Ui.dp(this, 16);
        root.setPadding(pad, pad, pad, pad);
        android.widget.ScrollView scroll = new android.widget.ScrollView(this);
        scroll.addView(root);
        setContentView(scroll);

        root.addView(Ui.screenTitle(this, "Connections"));

        // The clean list (v1.13): one compact row per item, in the
        // old cards' order, the bridge status last. Tap opens the
        // item's modal; long-press opens its quick-action menu.
        wifiRowStatus = itemRow(root, "Wi-Fi");
        btRowStatus = itemRow(root, "Bluetooth");
        cellRowStatus = itemRow(root, "Cellular");
        airplaneRowStatus = itemRow(root, "Airplane mode");
        nfcRowStatus = itemRow(root, "NFC");
        locRowStatus = itemRow(root, "Location");
        filterRowStatus = itemRow(root, "VPN & filter");
        meshRowStatus = itemRow(root, "Mesh internet");
        bridgeRowStatus = itemRow(root, "Deep view");
        bridgeRowStatus.setText("Checking…");

        // A ring leaf lands on its row: scroll to it and highlight
        // it briefly. The modal is not auto-opened — navigation
        // only, as before.
        String section = getIntent().getStringExtra(EXTRA_SECTION);
        if (section != null) {
            View target = sectionCards.get(section);
            if (target != null) {
                scroll.post(() -> {
                    scroll.scrollTo(0, target.getTop());
                    highlightRow(target);
                });
            }
        }
    }

    /** One list row: name + live status, nothing else. Returns the
     *  status line so refresh methods can update it. Tap opens the
     *  item's modal; long-press opens its popup menu. */
    private TextView itemRow(LinearLayout root, String title) {
        LinearLayout row = Ui.column(this);
        row.setBackground(Ui.roundedStroke(this, Ui.SURFACE, 14, Ui.LINE));
        int p = Ui.dp(this, 14);
        row.setPadding(p, Ui.dp(this, 12), p, Ui.dp(this, 12));
        TextView name = Ui.text(this, title, 16, Ui.TEXT);
        name.setTypeface(Typeface.create("sans-serif", Typeface.BOLD));
        row.addView(name);
        TextView status = Ui.text(this, "", 13, Ui.DIM);
        status.setTypeface(Typeface.MONOSPACE);
        row.addView(status, Ui.wrap(0, 2, 0, 0, this));
        row.setClickable(true);
        row.setFocusable(true);
        // Visible pressed state (the touch listener returns false
        // so click / long-click still fire).
        row.setOnTouchListener((v, e) -> {
            if (e.getAction() == MotionEvent.ACTION_DOWN) {
                row.setBackground(Ui.roundedStroke(this, Ui.SURFACE2, 14, Ui.LINE));
            } else if (e.getAction() == MotionEvent.ACTION_UP
                    || e.getAction() == MotionEvent.ACTION_CANCEL) {
                row.setBackground(Ui.roundedStroke(this, Ui.SURFACE, 14, Ui.LINE));
            }
            return false;
        });
        row.setOnClickListener(v -> showItemModal(title));
        row.setOnLongClickListener(v -> {
            showItemMenu(row, title);
            return true;
        });
        root.addView(row, Ui.wrap(0, 8, 0, 0, this));
        sectionCards.put(title, row);
        return status;
    }

    /** Brief accent highlight for an EXTRA_SECTION landing. */
    private void highlightRow(View row) {
        row.setBackground(Ui.roundedStroke(this, Ui.SURFACE2, 14, Ui.ACCENT));
        ui.postDelayed(() ->
                row.setBackground(Ui.roundedStroke(this, Ui.SURFACE, 14, Ui.LINE)), 1200);
    }

    // ------------------------------------------------------------ item modal

    /** Opens an item's popup modal: details + actions, with a "?"
     *  button in the header for the info text (the chat "?" popup
     *  pattern). Content is built fresh on every open and bound to
     *  the modal fields, so refreshes update it live while open;
     *  dismiss clears the bindings. Outside tap and Back dismiss
     *  (AlertDialog defaults). */
    private void showItemModal(String title) {
        if (itemDialog != null) itemDialog.dismiss();
        LinearLayout content = Ui.column(this);
        content.setBackground(Ui.roundedStroke(this, Ui.SURFACE, 14, Ui.LINE));
        int p = Ui.dp(this, 16);
        content.setPadding(p, p, p, p);

        LinearLayout header = Ui.row(this);
        TextView t = Ui.text(this, title, 20, Ui.TEXT);
        t.setTypeface(Typeface.create("sans-serif", Typeface.BOLD));
        header.addView(t, new LinearLayout.LayoutParams(0,
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        Button help = Ui.smallButton(this, "?");
        help.setOnClickListener(v -> showItemInfo(title));
        header.addView(help);
        content.addView(header);

        switch (title) {
            case "Wi-Fi": buildWifiModal(content); break;
            case "Bluetooth": buildBluetoothModal(content); break;
            case "Cellular": buildCellularModal(content); break;
            case "Airplane mode": buildAirplaneModal(content); break;
            case "NFC": buildNfcModal(content); break;
            case "Location": buildLocationModal(content); break;
            case "VPN & filter": buildFilterModal(content); break;
            case "Mesh internet": buildMeshModal(content); break;
            case "Deep view": buildBridgeModal(content); break;
            default: return;
        }

        android.widget.ScrollView scroll = new android.widget.ScrollView(this);
        scroll.addView(content);
        openItem = title;
        refreshAll();
        itemDialog = new AlertDialog.Builder(this)
                .setView(scroll)
                .setOnDismissListener(d -> clearModalBindings())
                .create();
        itemDialog.show();
    }

    /** Clears every modal binding together — refresh methods then
     *  update rows only until the next modal opens. */
    private void clearModalBindings() {
        bridgeDetail = null;
        wifiStatus = null;
        wifiScanBox = null;
        wifiSwitch = null;
        wifiGrantBtn = null;
        btStatus = null;
        btBox = null;
        btSwitch = null;
        cellStatus = null;
        dataSwitch = null;
        cellGrantBtn = null;
        airplaneStatus = null;
        airplaneSwitch = null;
        nfcStatus = null;
        nfcSwitch = null;
        locStatus = null;
        filterStatus = null;
        meshStatus = null;
        meshShareSwitch = null;
        meshUseSwitch = null;
        meshConsentBtn = null;
        openItem = null;
        itemDialog = null;
    }

    /** The "?" popup inside a modal: the item's info/explanation
     *  text, verbatim from the old cards where it existed. */
    private void showItemInfo(String title) {
        new AlertDialog.Builder(this)
                .setTitle(title)
                .setMessage(itemInfo(title))
                .setPositiveButton("Close", null)
                .show();
    }

    private String itemInfo(String title) {
        switch (title) {
            case "Wi-Fi":
                return "Android keeps the Wi-Fi switch for itself — a normal "
                        + "app can't flip it. This screen shows live status, "
                        + "scans, and opens the system's own panel. With Deep "
                        + "view ON, the bridge switch is real. Tapping a "
                        + "scanned network suggests it to Android with the "
                        + "password you type — Android makes the final call "
                        + "on connecting. Scans need the location permission "
                        + "(Android's rule, not ours), and Android throttles "
                        + "scans to a few per minute.";
            case "Bluetooth":
                return "Android keeps the Bluetooth switch for itself. This "
                        + "screen shows live status and the paired list, asks "
                        + "the system to turn Bluetooth on, scans, and pairs "
                        + "on tap. With Deep view ON, the bridge switch is "
                        + "real. Listing devices needs the Bluetooth "
                        + "permission on Android 12+.";
            case "Cellular":
                return "Read-only: carrier, network type, signal, roaming, "
                        + "and data state. Network type, signal, and roaming "
                        + "need the phone-state permission. With Deep view "
                        + "ON, the bridge switch flips mobile data for real.";
            case "Airplane mode":
                return "Turning airplane mode ON drops EVERY radio — Wi-Fi, "
                        + "Bluetooth, cellular and NFC — including every "
                        + "walkie-talkie path. The bridge itself is local and "
                        + "keeps running, so you can turn it back off here. "
                        + "Turning it on needs the strong press-and-hold "
                        + "confirmation. Without Deep view, the system screen "
                        + "is the switch.";
            case "NFC":
                return "State plus the system settings screen — or \"no NFC "
                        + "radio\" on phones without one. With Deep view ON, "
                        + "the bridge switch is real.";
            case "Location":
                return "No bridge switch for location — Control doesn't "
                        + "offer one; the system screen is the switch.";
            case "VPN & filter":
                return "The filter's own controls live in Settings — this "
                        + "only reports; it doesn't duplicate them. Private "
                        + "DNS mode is read from the system setting. The ad "
                        + "filter and mesh internet share Android's single "
                        + "VPN slot: starting mesh internet parks the filter "
                        + "(ad blocking still applies inside it), and "
                        + "stopping it restores the filter.";
            case "Mesh internet":
                return "Limits, plainly: the first pairing with another "
                        + "phone needs a system accept tap on each phone "
                        + "(Android requires it) — after that, reconnecting "
                        + "is automatic. Calls and games may suffer over the "
                        + "mesh. IPv4 only. One hop: a phone using the mesh "
                        + "can't re-share it. Both phones need Control "
                        + "running.";
            case "Deep view":
                return "With Deep view ON, Control's bridge flips the radios "
                        + "for real, from the bridge switches inside each "
                        + "item. The bridge is the phone's own shell, "
                        + "reached over wireless debugging: it starts from "
                        + "Deep view, needs wireless debugging on, and stops "
                        + "when the phone reboots.";
            default:
                return "";
        }
    }

    // ------------------------------------------------------------ item menu

    /** Long-press popup menu: the item's quick actions. Every entry
     *  runs the SAME factored action the modal's buttons run. */
    private void showItemMenu(View anchor, String title) {
        PopupMenu menu = new PopupMenu(this, anchor);
        List<Runnable> actions = new ArrayList<>();
        switch (title) {
            case "Wi-Fi":
                addMenu(menu, actions, "Wi-Fi panel", this::openWifiPanel);
                addMenu(menu, actions, "Scan", this::scanWifi);
                if (!hasFineLocation()) {
                    addMenu(menu, actions, "Grant location permission",
                            () -> requestPerms(new String[]{
                                    android.Manifest.permission.ACCESS_FINE_LOCATION}));
                }
                if (bridgeOn) {
                    addMenu(menu, actions,
                            wifiEnabled() ? "Turn Wi-Fi off (bridge)" : "Turn Wi-Fi on (bridge)",
                            () -> flipWifi(!wifiEnabled(), null));
                }
                break;
            case "Bluetooth":
                addMenu(menu, actions, "Bluetooth settings", this::openBluetoothSettings);
                addMenu(menu, actions, "Turn on Bluetooth", this::requestBtEnable);
                addMenu(menu, actions, "Scan for devices", this::scanBluetooth);
                if (bridgeOn) {
                    addMenu(menu, actions,
                            btEnabled() ? "Turn Bluetooth off (bridge)" : "Turn Bluetooth on (bridge)",
                            () -> flipBluetooth(!btEnabled(), null));
                }
                break;
            case "Cellular":
                addMenu(menu, actions, "Internet panel", this::openInternetPanel);
                addMenu(menu, actions, "Mobile data settings", this::openMobileSettings);
                if (!hasPhoneState()) {
                    addMenu(menu, actions, "Grant phone-state permission",
                            () -> requestPerms(new String[]{
                                    android.Manifest.permission.READ_PHONE_STATE}));
                }
                if (bridgeOn) {
                    addMenu(menu, actions,
                            mobileDataOn() ? "Turn mobile data off (bridge)" : "Turn mobile data on (bridge)",
                            () -> flipMobileData(!mobileDataOn(), null));
                }
                break;
            case "Airplane mode":
                addMenu(menu, actions, "Airplane mode settings", this::openAirplaneSettings);
                if (bridgeOn) {
                    addMenu(menu, actions,
                            airplaneOn() ? "Turn airplane mode off (bridge)" : "Turn airplane mode on (bridge)",
                            () -> flipAirplane(!airplaneOn(), null));
                }
                break;
            case "NFC":
                addMenu(menu, actions, "NFC settings", this::openNfcSettings);
                if (bridgeOn) {
                    addMenu(menu, actions,
                            nfcOn() ? "Turn NFC off (bridge)" : "Turn NFC on (bridge)",
                            () -> flipNfc(!nfcOn(), null));
                }
                break;
            case "Location":
                addMenu(menu, actions, "Location settings", this::openLocationSettings);
                break;
            case "VPN & filter":
                addMenu(menu, actions, "Open filter settings", this::openFilterSettings);
                break;
            case "Mesh internet":
                addMenu(menu, actions, "Find nearby phones", this::findNearbyPhones);
                addMenu(menu, actions,
                        MeshInternet.shareOn(this) ? "Stop sharing mobile data" : "Share mobile data",
                        () -> setMeshShare(!MeshInternet.shareOn(this)));
                addMenu(menu, actions,
                        MeshInternet.useOn(this) ? "Stop using mesh internet" : "Use mesh internet when offline",
                        () -> setMeshUse(!MeshInternet.useOn(this)));
                break;
            case "Deep view":
                addMenu(menu, actions, "Open Deep view", this::openDeepView);
                addMenu(menu, actions, "Re-check bridge", this::pingBridge);
                break;
            default:
                return;
        }
        menu.setOnMenuItemClickListener(item -> {
            actions.get(item.getItemId()).run();
            return true;
        });
        menu.show();
    }

    private void addMenu(PopupMenu menu, List<Runnable> actions, String label, Runnable r) {
        menu.getMenu().add(0, actions.size(), actions.size(), label);
        actions.add(r);
    }

    /** A bridge-tier switch row: hidden until the bridge answers. */
    private Switch bridgeSwitch(LinearLayout cardBox, String label,
                                  android.widget.CompoundButton.OnCheckedChangeListener onFlip) {
        Switch sw = new Switch(this);
        sw.setText(label);
        sw.setTextColor(Ui.TEXT);
        sw.setTextSize(15);
        sw.setVisibility(View.GONE);
        sw.setOnCheckedChangeListener((bv, checked) -> {
            if (syncingSwitches) return;
            onFlip.onCheckedChanged(bv, checked);
        });
        cardBox.addView(sw, Ui.wrap(0, 8, 0, 0, this));
        return sw;
    }

    private void syncSwitch(Switch sw, boolean on) {
        if (sw == null) return;
        syncingSwitches = true;
        sw.setChecked(on);
        syncingSwitches = false;
        sw.setVisibility(bridgeOn ? View.VISIBLE : View.GONE);
    }

    // ------------------------------------------------------------ Wi-Fi

    /** Modal body for Wi-Fi: exactly the old card's controls. */
    private void buildWifiModal(LinearLayout c) {
        wifiStatus = Ui.body(this, "");
        c.addView(wifiStatus, Ui.wrap(0, 8, 0, 0, this));
        wifiGrantBtn = Ui.button(this, "Grant location permission (shows network names)", false);
        wifiGrantBtn.setOnClickListener(v -> requestPerms(
                new String[]{android.Manifest.permission.ACCESS_FINE_LOCATION}));
        c.addView(wifiGrantBtn, Ui.wrap(0, 8, 0, 0, this));
        LinearLayout row = Ui.row(this);
        Button scan = Ui.smallButton(this, "Scan");
        scan.setOnClickListener(v -> scanWifi());
        row.addView(scan);
        Button panel = Ui.smallButton(this, "Wi-Fi panel");
        panel.setOnClickListener(v -> openWifiPanel());
        row.addView(panel);
        c.addView(row, Ui.wrap(0, 8, 0, 0, this));
        wifiScanBox = Ui.column(this);
        c.addView(wifiScanBox);
        wifiSwitch = bridgeSwitch(c, "Bridge switch — Wi-Fi",
                (bv, on) -> flipWifi(on, wifiSwitch));
        refreshWifiScanResults();
    }

    /** Factored actions — the modal buttons and the popup menu
     *  share these; the menu passes a null switch for flips. */
    private void openWifiPanel() {
        openPanel(Settings.Panel.ACTION_WIFI, Settings.ACTION_WIFI_SETTINGS);
    }

    private void flipWifi(boolean on, Switch sw) {
        bridgeToggle("Wi-Fi", on, sw,
                () -> wifiEnabled(),
                (act, origin, en) -> BridgeControl.setWifi(act, origin, en),
                false, null);
    }

    private WifiManager wifi() {
        return (WifiManager) getApplicationContext().getSystemService(WIFI_SERVICE);
    }

    private boolean wifiEnabled() {
        try {
            return wifi().isWifiEnabled();
        } catch (Exception e) {
            return false;
        }
    }

    private boolean hasFineLocation() {
        return checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION)
                == PackageManager.PERMISSION_GRANTED;
    }

    private void refreshWifi() {
        WifiManager wm = wifi();
        boolean on = wifiEnabled();
        StringBuilder sb = new StringBuilder();
        String row;
        sb.append(on ? "On" : "Off");
        if (on) {
            try {
                WifiInfo info = wm.getConnectionInfo();
                if (info != null && info.getNetworkId() != -1) {
                    String ssid = info.getSSID();
                    String name;
                    if (!hasFineLocation()) {
                        sb.append(" · Connected — name hidden until location "
                                + "permission is granted");
                        name = "Connected (name hidden)";
                    } else if (ssid == null || ssid.equals("<unknown ssid>")) {
                        sb.append(" · Connected");
                        name = "Connected";
                    } else {
                        String clean = ssid.replace("\"", "");
                        sb.append(" · Connected to ").append(clean);
                        name = clean;
                    }
                    row = "On · " + name;
                    int ip = info.getIpAddress();
                    if (ip != 0) {
                        sb.append(" · ").append(ip & 0xff).append('.')
                                .append((ip >> 8) & 0xff).append('.')
                                .append((ip >> 16) & 0xff).append('.')
                                .append((ip >> 24) & 0xff);
                    }
                    if (info.getLinkSpeed() > 0) {
                        sb.append(" · ").append(info.getLinkSpeed()).append(" Mbps");
                    }
                } else {
                    sb.append(" · Not connected");
                    row = "On · Not connected";
                }
            } catch (Exception e) {
                sb.append(" · (status limited)");
                row = "On";
            }
        } else {
            row = "Off";
        }
        wifiRowStatus.setText(row);
        if (wifiStatus != null) wifiStatus.setText(sb.toString());
        if (wifiGrantBtn != null) {
            wifiGrantBtn.setVisibility(hasFineLocation() ? View.GONE : View.VISIBLE);
        }
        syncSwitch(wifiSwitch, on);
    }

    private void scanWifi() {
        if (!hasFineLocation()) {
            Toast.makeText(this, "Wi-Fi scans need the location permission "
                    + "(Android's rule, not ours).", Toast.LENGTH_LONG).show();
            requestPerms(new String[]{android.Manifest.permission.ACCESS_FINE_LOCATION});
            return;
        }
        boolean started;
        try {
            started = wifi().startScan();
        } catch (Exception e) {
            started = false;
        }
        Toast.makeText(this, started
                        ? "Scanning… (Android throttles scans to a few per minute)"
                        : "Scan didn't start — Android throttles scans, or "
                        + "location services are off.",
                Toast.LENGTH_LONG).show();
    }

    private void refreshWifiScanResults() {
        if (wifiScanBox == null) return;
        List<ScanResult> results;
        try {
            results = wifi().getScanResults();
        } catch (Exception e) {
            return;
        }
        if (results == null) return;
        wifiScanBox.removeAllViews();
        results.sort((a, b) -> b.level - a.level);
        int shown = 0;
        for (ScanResult r : results) {
            if (r.SSID == null || r.SSID.isEmpty()) continue;
            if (shown++ >= 12) break;
            Button b = Ui.smallButton(this, r.SSID + "  ·  " + r.level + " dBm"
                    + (isOpenNet(r) ? "  ·  open" : "  ·  secured"));
            b.setOnClickListener(v -> suggestNetwork(r));
            wifiScanBox.addView(b, Ui.wrap(0, 4, 0, 0, this));
        }
        if (shown > 0) {
            wifiScanBox.addView(Ui.body(this,
                    "Tapping a network suggests it to Android with the "
                    + "password you type — Android makes the final call on "
                    + "connecting."), Ui.wrap(0, 4, 0, 0, this));
        }
    }

    private static boolean isOpenNet(ScanResult r) {
        return r.capabilities == null
                || (!r.capabilities.contains("WPA") && !r.capabilities.contains("WEP"));
    }

    private void suggestNetwork(ScanResult r) {
        if (Build.VERSION.SDK_INT < 29) {
            Toast.makeText(this, "Suggesting networks needs Android 10+.",
                    Toast.LENGTH_LONG).show();
            return;
        }
        if (isOpenNet(r)) {
            doSuggest(r.SSID, null);
            return;
        }
        EditText pw = Ui.input(this, "Password for " + r.SSID);
        new AlertDialog.Builder(this)
                .setTitle("Connect to " + r.SSID)
                .setView(pw)
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Suggest", (d, w) ->
                        doSuggest(r.SSID, pw.getText().toString()))
                .show();
    }

    private void doSuggest(String ssid, String password) {
        try {
            WifiNetworkSuggestion.Builder b = new WifiNetworkSuggestion.Builder()
                    .setSsid(ssid);
            if (password != null && !password.isEmpty()) {
                b.setWpa2Passphrase(password);
            }
            List<WifiNetworkSuggestion> list = new ArrayList<>();
            list.add(b.build());
            int status = wifi().addNetworkSuggestions(list);
            Toast.makeText(this, status == WifiManager.STATUS_NETWORK_SUGGESTIONS_SUCCESS
                            ? "Suggested — Android will connect when it decides "
                            + "(watch the Wi-Fi panel)."
                            : "Android refused the suggestion (status " + status + ").",
                    Toast.LENGTH_LONG).show();
        } catch (Exception e) {
            Toast.makeText(this, "Couldn't suggest that network: "
                    + e.getClass().getSimpleName(), Toast.LENGTH_LONG).show();
        }
    }

    // ------------------------------------------------------------ Bluetooth

    /** Modal body for Bluetooth: exactly the old card's controls. */
    private void buildBluetoothModal(LinearLayout c) {
        btStatus = Ui.body(this, "");
        c.addView(btStatus, Ui.wrap(0, 8, 0, 0, this));
        btBox = Ui.column(this);
        c.addView(btBox);
        LinearLayout row = Ui.row(this);
        Button enable = Ui.smallButton(this, "Turn on Bluetooth");
        enable.setOnClickListener(v -> requestBtEnable());
        row.addView(enable);
        Button scan = Ui.smallButton(this, "Scan for devices");
        scan.setOnClickListener(v -> scanBluetooth());
        row.addView(scan);
        Button settings = Ui.smallButton(this, "Bluetooth settings");
        settings.setOnClickListener(v -> openBluetoothSettings());
        row.addView(settings);
        c.addView(row, Ui.wrap(0, 8, 0, 0, this));
        btSwitch = bridgeSwitch(c, "Bridge switch — Bluetooth",
                (bv, on) -> flipBluetooth(on, btSwitch));
        refreshFoundBox();
    }

    private void openBluetoothSettings() {
        openSystem(Settings.ACTION_BLUETOOTH_SETTINGS);
    }

    private void requestBtEnable() {
        try {
            startActivity(new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE));
        } catch (Exception e) {
            openSystem(Settings.ACTION_BLUETOOTH_SETTINGS);
        }
    }

    private void flipBluetooth(boolean on, Switch sw) {
        bridgeToggle("Bluetooth", on, sw,
                () -> btEnabled(),
                (act, origin, en) -> BridgeControl.setBluetooth(act, origin, en),
                false, null);
    }

    private BluetoothAdapter bt() {
        return BluetoothAdapter.getDefaultAdapter();
    }

    private boolean btEnabled() {
        try {
            BluetoothAdapter a = bt();
            return a != null && a.isEnabled();
        } catch (Exception e) {
            return false;
        }
    }

    private boolean hasBtPerms() {
        if (Build.VERSION.SDK_INT < 31) return true;
        return checkSelfPermission(android.Manifest.permission.BLUETOOTH_SCAN)
                        == PackageManager.PERMISSION_GRANTED
                && checkSelfPermission(android.Manifest.permission.BLUETOOTH_CONNECT)
                        == PackageManager.PERMISSION_GRANTED;
    }

    private void refreshBluetooth() {
        BluetoothAdapter a = bt();
        if (a == null) {
            btRowStatus.setText("No Bluetooth radio");
            if (btStatus != null) btStatus.setText("This phone has no Bluetooth radio.");
            syncSwitch(btSwitch, false);
            return;
        }
        boolean on = btEnabled();
        StringBuilder sb = new StringBuilder(on ? "On" : "Off");
        String row = on ? "On" : "Off";
        if (btBox != null) btBox.removeAllViews();
        if (on && hasBtPerms()) {
            try {
                Set<BluetoothDevice> bonded = a.getBondedDevices();
                sb.append(" · ").append(bonded.size()).append(" paired");
                row = "On · " + bonded.size() + " paired";
                if (btBox != null) {
                    for (BluetoothDevice d : bonded) {
                        btBox.addView(Ui.body(this, "Paired: " + safeBtName(d)),
                                Ui.wrap(0, 2, 0, 0, this));
                    }
                }
            } catch (Exception e) {
                sb.append(" · (paired list needs Bluetooth permission)");
                row = "On · permission needed";
            }
        } else if (on) {
            sb.append(" · Grant Bluetooth permission to list devices");
            row = "On · permission needed";
        }
        btRowStatus.setText(row);
        if (btStatus != null) btStatus.setText(sb.toString());
        syncSwitch(btSwitch, on);
    }

    private String safeBtName(BluetoothDevice d) {
        try {
            String n = d.getName();
            return (n == null || n.isEmpty()) ? d.getAddress() : n;
        } catch (Exception e) {
            return "(name needs permission)";
        }
    }

    private void scanBluetooth() {
        BluetoothAdapter a = bt();
        if (a == null) {
            Toast.makeText(this, "No Bluetooth radio.", Toast.LENGTH_LONG).show();
            return;
        }
        if (!btEnabled()) {
            Toast.makeText(this, "Bluetooth is off — turn it on first.",
                    Toast.LENGTH_LONG).show();
            return;
        }
        if (!hasBtPerms()) {
            requestPerms(new String[]{
                    android.Manifest.permission.BLUETOOTH_SCAN,
                    android.Manifest.permission.BLUETOOTH_CONNECT});
            return;
        }
        foundDevices.clear();
        refreshFoundBox();
        try {
            if (a.isDiscovering()) a.cancelDiscovery();
            boolean ok = a.startDiscovery();
            Toast.makeText(this, ok
                            ? "Scanning for ~12 seconds… tap a device to pair."
                            : "Discovery didn't start.",
                    Toast.LENGTH_LONG).show();
        } catch (Exception e) {
            Toast.makeText(this, "Discovery failed: "
                    + e.getClass().getSimpleName(), Toast.LENGTH_LONG).show();
        }
    }

    private final List<BluetoothDevice> foundDevices = new ArrayList<>();

    private void onBtFound(BluetoothDevice d) {
        for (BluetoothDevice x : foundDevices) {
            try {
                if (x.getAddress().equals(d.getAddress())) return;
            } catch (Exception ignored) {
            }
        }
        foundDevices.add(d);
        refreshFoundBox();
    }

    private void refreshFoundBox() {
        // Found devices render under the paired list in btBox; keep the
        // paired rows by rebuilding the whole modal body.
        refreshBluetooth();
        if (btBox == null) return;
        for (BluetoothDevice d : foundDevices) {
            Button b = Ui.smallButton(this, "Found: " + safeBtName(d) + " — tap to pair");
            b.setOnClickListener(v -> {
                try {
                    boolean ok = d.createBond();
                    Toast.makeText(this, ok
                                    ? "Pairing started — watch for the system prompt."
                                    : "Pairing didn't start.",
                            Toast.LENGTH_LONG).show();
                } catch (Exception e) {
                    Toast.makeText(this, "Pairing failed: "
                            + e.getClass().getSimpleName(), Toast.LENGTH_LONG).show();
                }
            });
            btBox.addView(b, Ui.wrap(0, 4, 0, 0, this));
        }
    }

    // ------------------------------------------------------------ Cellular

    /** Modal body for Cellular: exactly the old card's controls. */
    private void buildCellularModal(LinearLayout c) {
        cellStatus = Ui.body(this, "");
        c.addView(cellStatus, Ui.wrap(0, 8, 0, 0, this));
        cellGrantBtn = Ui.button(this, "Grant phone-state permission (network type + signal)", false);
        cellGrantBtn.setOnClickListener(v -> requestPerms(
                new String[]{android.Manifest.permission.READ_PHONE_STATE}));
        c.addView(cellGrantBtn, Ui.wrap(0, 8, 0, 0, this));
        LinearLayout row = Ui.row(this);
        Button internet = Ui.smallButton(this, "Internet panel");
        internet.setOnClickListener(v -> openInternetPanel());
        row.addView(internet);
        Button mobile = Ui.smallButton(this, "Mobile data settings");
        mobile.setOnClickListener(v -> openMobileSettings());
        row.addView(mobile);
        c.addView(row, Ui.wrap(0, 8, 0, 0, this));
        dataSwitch = bridgeSwitch(c, "Bridge switch — Mobile data",
                (bv, on) -> flipMobileData(on, dataSwitch));
    }

    private void openInternetPanel() {
        openPanel(Settings.Panel.ACTION_INTERNET_CONNECTIVITY,
                Settings.ACTION_NETWORK_OPERATOR_SETTINGS);
    }

    private void openMobileSettings() {
        openSystem(Settings.ACTION_NETWORK_OPERATOR_SETTINGS);
    }

    private void flipMobileData(boolean on, Switch sw) {
        bridgeToggle("Mobile data", on, sw,
                () -> mobileDataOn(),
                (act, origin, en) -> BridgeControl.setMobileData(act, origin, en),
                false, null);
    }

    private TelephonyManager tel() {
        return (TelephonyManager) getSystemService(TELEPHONY_SERVICE);
    }

    private boolean hasPhoneState() {
        return checkSelfPermission(android.Manifest.permission.READ_PHONE_STATE)
                == PackageManager.PERMISSION_GRANTED;
    }

    private boolean mobileDataOn() {
        try {
            return tel().getDataState() != TelephonyManager.DATA_DISCONNECTED;
        } catch (Exception e) {
            return false;
        }
    }

    private void refreshCellular() {
        TelephonyManager tm = tel();
        StringBuilder sb = new StringBuilder();
        String carrier = "—";
        try {
            String c = tm.getNetworkOperatorName();
            if (c != null && !c.isEmpty()) carrier = c;
        } catch (Exception ignored) {
        }
        sb.append("Carrier: ").append(carrier);
        String row;
        if (hasPhoneState()) {
            String net = "—";
            try {
                net = networkTypeName(tm.getDataNetworkType());
                sb.append(" · Network: ").append(net);
            } catch (Exception e) {
                sb.append(" · Network: —");
            }
            try {
                SignalStrength ss = tm.getSignalStrength();
                if (ss != null) sb.append(" · Signal: level ").append(ss.getLevel()).append("/4");
            } catch (Exception ignored) {
            }
            try {
                ServiceState ss = tm.getServiceState();
                if (ss != null && ss.getRoaming()) sb.append(" · Roaming");
            } catch (Exception ignored) {
            }
            boolean dataOn = false;
            try {
                dataOn = mobileDataOn();
                sb.append(" · Mobile data: ").append(dataOn ? "on" : "off");
            } catch (Exception ignored) {
            }
            row = carrier + " · " + net + " · data " + (dataOn ? "on" : "off");
        } else {
            sb.append(" · Grant phone-state permission for network type, "
                    + "signal and roaming");
            row = carrier + " · permission needed";
        }
        cellRowStatus.setText(row);
        if (cellStatus != null) cellStatus.setText(sb.toString());
        if (cellGrantBtn != null) {
            cellGrantBtn.setVisibility(hasPhoneState() ? View.GONE : View.VISIBLE);
        }
        syncSwitch(dataSwitch, mobileDataOn());
    }

    private static String networkTypeName(int t) {
        switch (t) {
            case TelephonyManager.NETWORK_TYPE_NR: return "5G (NR)";
            case TelephonyManager.NETWORK_TYPE_LTE: return "LTE";
            case TelephonyManager.NETWORK_TYPE_IWLAN: return "Wi-Fi calling (IWLAN)";
            case TelephonyManager.NETWORK_TYPE_HSPAP:
            case TelephonyManager.NETWORK_TYPE_HSPA:
            case TelephonyManager.NETWORK_TYPE_HSDPA:
            case TelephonyManager.NETWORK_TYPE_HSUPA: return "3G (HSPA)";
            case TelephonyManager.NETWORK_TYPE_EDGE:
            case TelephonyManager.NETWORK_TYPE_GPRS: return "2G";
            case TelephonyManager.NETWORK_TYPE_UNKNOWN: return "unknown";
            default: return "type " + t;
        }
    }

    // ------------------------------------------------- Airplane / NFC / Location

    /** Modal body for Airplane mode: exactly the old card's controls. */
    private void buildAirplaneModal(LinearLayout c) {
        airplaneStatus = Ui.body(this, "");
        c.addView(airplaneStatus, Ui.wrap(0, 8, 0, 0, this));
        Button b = Ui.smallButton(this, "Airplane mode settings");
        b.setOnClickListener(v -> openAirplaneSettings());
        c.addView(b, Ui.wrap(0, 8, 0, 0, this));
        airplaneSwitch = bridgeSwitch(c, "Bridge switch — Airplane mode",
                (bv, on) -> flipAirplane(on, airplaneSwitch));
    }

    private void openAirplaneSettings() {
        openSystem(Settings.ACTION_AIRPLANE_MODE_SETTINGS);
    }

    private void flipAirplane(boolean on, Switch sw) {
        bridgeToggle("Airplane mode", on, sw,
                () -> airplaneOn(),
                (act, origin, en) -> BridgeControl.setAirplaneMode(act, origin, en),
                true,
                "Turning airplane mode ON drops EVERY radio — Wi-Fi, "
                + "Bluetooth, cellular and NFC — including every "
                + "walkie-talkie path. The bridge itself is local and "
                + "keeps running, so you can turn it back off here.");
    }

    private boolean airplaneOn() {
        try {
            return Settings.Global.getInt(getContentResolver(),
                    Settings.Global.AIRPLANE_MODE_ON, 0) != 0;
        } catch (Exception e) {
            return false;
        }
    }

    private void refreshAirplane() {
        String s = airplaneOn() ? "ON — all radios are down" : "Off";
        airplaneRowStatus.setText(s);
        if (airplaneStatus != null) airplaneStatus.setText(s);
        syncSwitch(airplaneSwitch, airplaneOn());
    }

    /** Modal body for NFC: exactly the old card's controls. */
    private void buildNfcModal(LinearLayout c) {
        nfcStatus = Ui.body(this, "");
        c.addView(nfcStatus, Ui.wrap(0, 8, 0, 0, this));
        Button b = Ui.smallButton(this, "NFC settings");
        b.setOnClickListener(v -> openNfcSettings());
        c.addView(b, Ui.wrap(0, 8, 0, 0, this));
        nfcSwitch = bridgeSwitch(c, "Bridge switch — NFC",
                (bv, on) -> flipNfc(on, nfcSwitch));
    }

    private void openNfcSettings() {
        try {
            startActivity(new Intent(Settings.ACTION_NFC_SETTINGS));
        } catch (Exception e) {
            openSystem(Settings.ACTION_WIRELESS_SETTINGS);
        }
    }

    private void flipNfc(boolean on, Switch sw) {
        bridgeToggle("NFC", on, sw,
                () -> nfcOn(),
                (act, origin, en) -> BridgeControl.setNfc(act, origin, en),
                false, null);
    }

    private boolean nfcOn() {
        try {
            NfcAdapter a = NfcAdapter.getDefaultAdapter(this);
            return a != null && a.isEnabled();
        } catch (Exception e) {
            return false;
        }
    }

    private void refreshNfc() {
        NfcAdapter a = null;
        try {
            a = NfcAdapter.getDefaultAdapter(this);
        } catch (Exception ignored) {
        }
        String s = a == null
                ? "This phone has no NFC radio."
                : (a.isEnabled() ? "On" : "Off");
        nfcRowStatus.setText(a == null ? "No NFC radio" : (a.isEnabled() ? "On" : "Off"));
        if (nfcStatus != null) nfcStatus.setText(s);
        syncSwitch(nfcSwitch, nfcOn());
        if (nfcSwitch != null && a == null) nfcSwitch.setVisibility(View.GONE);
    }

    /** Modal body for Location: the old card's controls (its
     *  explanation line moved behind the modal's "?"). */
    private void buildLocationModal(LinearLayout c) {
        locStatus = Ui.body(this, "");
        c.addView(locStatus, Ui.wrap(0, 8, 0, 0, this));
        Button b = Ui.smallButton(this, "Location settings");
        b.setOnClickListener(v -> openLocationSettings());
        c.addView(b, Ui.wrap(0, 8, 0, 0, this));
    }

    private void openLocationSettings() {
        openSystem(Settings.ACTION_LOCATION_SOURCE_SETTINGS);
    }

    private void refreshLocation() {
        LocationManager lm = (LocationManager) getSystemService(LOCATION_SERVICE);
        boolean on;
        try {
            if (Build.VERSION.SDK_INT >= 28) {
                on = lm.isLocationEnabled();
            } else {
                on = lm.isProviderEnabled(LocationManager.GPS_PROVIDER)
                        || lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER);
            }
        } catch (Exception e) {
            on = false;
        }
        String s = on ? "On" : "Off";
        locRowStatus.setText(s);
        if (locStatus != null) locStatus.setText(s);
    }

    // ------------------------------------------------------------ VPN & filter

    /** Modal body for VPN &amp; filter: the old card's controls
     *  (its explanation sentence moved behind the modal's "?"). */
    private void buildFilterModal(LinearLayout c) {
        filterStatus = Ui.body(this, "");
        c.addView(filterStatus, Ui.wrap(0, 8, 0, 0, this));
        Button b = Ui.smallButton(this, "Open filter settings");
        b.setOnClickListener(v -> openFilterSettings());
        c.addView(b, Ui.wrap(0, 8, 0, 0, this));
    }

    private void openFilterSettings() {
        startActivity(new Intent(this, SettingsActivity.class));
    }

    private void refreshFilter() {
        boolean on = Prefs.get(this).getBoolean(Prefs.KEY_FILTER_ON, false);
        String dns;
        try {
            String mode = Settings.Global.getString(getContentResolver(), "private_dns_mode");
            String host = Settings.Global.getString(getContentResolver(), "private_dns_specifier");
            if ("off".equals(mode)) {
                dns = "off";
            } else if ("hostname".equals(mode)) {
                dns = "hostname (" + (host == null ? "?" : host) + ")";
            } else {
                dns = "automatic";
            }
        } catch (Exception e) {
            dns = "unknown";
        }
        String s = "Ad filter VPN: " + (on ? "on" : "off")
                + " · Private DNS: " + dns;
        filterRowStatus.setText(s);
        if (filterStatus != null) filterStatus.setText(s);
    }

    // ------------------------------------------------------------ mesh internet

    /** Modal body for Mesh internet: exactly the old card's
     *  controls (its "Limits, plainly" paragraph moved behind the
     *  modal's "?"). The VPN-slot interaction is unchanged:
     *  reconcile + refresh run on every toggle, as before. */
    private void buildMeshModal(LinearLayout c) {
        meshStatus = Ui.body(this, "");
        c.addView(meshStatus, Ui.wrap(0, 8, 0, 0, this));
        meshShareSwitch = meshSwitch(c,
                "Share mobile data with the mesh (uses your data + battery)",
                (bv, checked) -> setMeshShare(checked));
        meshUseSwitch = meshSwitch(c, "Use mesh internet when offline",
                (bv, checked) -> setMeshUse(checked));
        LinearLayout row = Ui.row(this);
        Button find = Ui.smallButton(this, "Find nearby phones");
        find.setOnClickListener(v -> findNearbyPhones());
        row.addView(find);
        meshConsentBtn = Ui.smallButton(this, "Grant VPN permission");
        meshConsentBtn.setOnClickListener(v -> {
            Intent consent = VpnService.prepare(this);
            if (consent != null) {
                startActivityForResult(consent, REQ_MESH_VPN);
            } else {
                MeshInternet.reconcile(this, true);
                refreshMesh();
            }
        });
        row.addView(meshConsentBtn);
        c.addView(row, Ui.wrap(0, 8, 0, 0, this));
    }

    /** Factored mesh actions — the modal's switches/buttons and
     *  the popup menu share these. */
    private void setMeshShare(boolean checked) {
        MeshInternet.setShare(this, checked);
        MeshInternet.reconcile(this, true);
        refreshMesh();
    }

    private void setMeshUse(boolean checked) {
        MeshInternet.setUse(this, checked);
        MeshInternet.reconcile(this, true);
        refreshMesh();
    }

    private void findNearbyPhones() {
        peerDialogWanted = true;
        Intent i = new Intent(this, MeshInternetService.class);
        i.setAction(MeshInternet.ACTION_DISCOVER_PEERS);
        startService(i);
    }

    /** A plain always-visible switch (not a bridge switch). */
    private Switch meshSwitch(LinearLayout cardBox, String label,
                              android.widget.CompoundButton.OnCheckedChangeListener onFlip) {
        Switch sw = new Switch(this);
        sw.setText(label);
        sw.setTextColor(Ui.TEXT);
        sw.setTextSize(15);
        sw.setOnCheckedChangeListener((bv, checked) -> {
            if (syncingSwitches) return;
            onFlip.onCheckedChanged(bv, checked);
        });
        cardBox.addView(sw, Ui.wrap(0, 8, 0, 0, this));
        return sw;
    }

    private void refreshMesh() {
        String state = MeshInternet.state;
        String gw = MeshInternet.gatewayName;
        StringBuilder sb = new StringBuilder();
        switch (state) {
            case MeshInternet.STATE_SHARING:
                sb.append("Providing internet to the mesh");
                if (MeshInternet.clientCount > 0) {
                    sb.append(" — sharing with ").append(MeshInternet.clientCount)
                            .append(MeshInternet.clientCount == 1 ? " phone." : " phones.");
                } else {
                    sb.append(" — no phone is using it right now.");
                }
                break;
            case MeshInternet.STATE_SHARING_NO_INTERNET:
                sb.append("Sharing is on, but this phone has no internet "
                        + "connection to share.");
                break;
            case MeshInternet.STATE_USING:
                sb.append("Using mesh internet via ")
                        .append(gw != null ? gw : "a nearby phone").append(".");
                break;
            case MeshInternet.STATE_SEARCHING:
                sb.append("Offline — looking for a nearby phone sharing "
                        + "its connection…");
                break;
            case MeshInternet.STATE_NEEDS_CONSENT:
                sb.append("A gateway (").append(gw != null ? gw : "nearby phone")
                        .append(") is nearby. VPN permission is needed to use it — "
                                + "tap the button below.");
                break;
            case MeshInternet.STATE_NO_PERMISSION:
                sb.append("Nearby-device permission is missing, so mesh phones "
                        + "can't be found. Open the Walkie page once to grant it.");
                break;
            case MeshInternet.STATE_IDLE:
                sb.append("Online. Mesh internet only acts when this phone "
                        + "is offline.");
                break;
            default:
                sb.append("Mesh internet is off — both toggles below are off.");
                break;
        }
        // The row shows the state sentence; the modal adds the
        // VPN-slot truth below it.
        meshRowStatus.setText(sb.toString());
        if (meshStatus == null) return;
        syncingSwitches = true;
        meshShareSwitch.setChecked(MeshInternet.shareOn(this));
        meshUseSwitch.setChecked(MeshInternet.useOn(this));
        syncingSwitches = false;
        // VPN-slot truth, always stated.
        boolean natOn = MeshInternet.natOn(this);
        boolean filterOn = Prefs.get(this).getBoolean(Prefs.KEY_FILTER_ON, false);
        boolean parked = Prefs.get(this).getBoolean(Prefs.KEY_FILTER_PARKED, false);
        sb.append("\nVPN slot: ");
        if (natOn) {
            sb.append("mesh internet, with ad blocking included.");
        } else if (filterOn) {
            sb.append("ad filter.");
        } else if (parked) {
            sb.append("free — the filter was parked and is being restored.");
        } else {
            sb.append("free.");
        }
        meshStatus.setText(sb.toString());
        meshConsentBtn.setVisibility(
                MeshInternet.STATE_NEEDS_CONSENT.equals(state) ? View.VISIBLE : View.GONE);
    }

    private void showPeerDialog(String[] peers) {
        if (peers == null || peers.length == 0) {
            Toast.makeText(this, "No nearby phones found yet — try again in "
                    + "a few seconds.", Toast.LENGTH_LONG).show();
            return;
        }
        String[] names = new String[peers.length];
        String[] addrs = new String[peers.length];
        for (int i = 0; i < peers.length; i++) {
            int bar = peers[i].indexOf('|');
            if (bar >= 0) {
                names[i] = peers[i].substring(0, bar);
                addrs[i] = peers[i].substring(bar + 1);
            } else {
                names[i] = peers[i];
                addrs[i] = peers[i];
            }
        }
        new AlertDialog.Builder(this)
                .setTitle("Connect to which phone?")
                .setItems(names, (d, which) -> {
                    Intent i = new Intent(this, MeshInternetService.class);
                    i.setAction(MeshInternet.ACTION_CONNECT_PEER);
                    i.putExtra(MeshInternet.EXTRA_PEER_ADDR, addrs[which]);
                    startService(i);
                    Toast.makeText(this, "Connecting to " + names[which]
                            + "… first pairing needs a tap on each phone.",
                            Toast.LENGTH_LONG).show();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    // ------------------------------------------------------------ bridge tier

    /** Modal body for Deep view: the bridge status (the old top
     *  status line, verbatim) plus navigation to where the bridge
     *  is managed. The row itself had no controls before; these
     *  two buttons are new but only navigate / re-check. */
    private void buildBridgeModal(LinearLayout c) {
        bridgeDetail = Ui.body(this, "");
        c.addView(bridgeDetail, Ui.wrap(0, 8, 0, 0, this));
        LinearLayout row = Ui.row(this);
        Button open = Ui.smallButton(this, "Open Deep view");
        open.setOnClickListener(v -> openDeepView());
        row.addView(open);
        Button recheck = Ui.smallButton(this, "Re-check");
        recheck.setOnClickListener(v -> pingBridge());
        row.addView(recheck);
        c.addView(row, Ui.wrap(0, 8, 0, 0, this));
    }

    private void openDeepView() {
        startActivity(new Intent(this, DeepViewActivity.class));
    }

    private interface RadioCall {
        ShellBridgeClient.Response run(Context c, String origin, boolean enabled);
    }

    private void pingBridge() {
        new Thread(() -> {
            ShellBridgeClient.Response r = ShellBridgeClient.ping(this);
            ui.post(() -> {
                bridgeOn = r.ok;
                bridgeRowStatus.setText(r.ok
                        ? "ON — bridge switches are real"
                        : "OFF — status and panels only");
                if (bridgeDetail != null) {
                    bridgeDetail.setText(r.ok
                            ? "Deep view is ON — the bridge switches inside "
                            + "each item are real (bridge UID " + r.bridgeUid + ")."
                            : "Deep view is OFF — live status and system panels "
                            + "only. Start Deep view and the bridge switches "
                            + "appear inside each item.");
                }
                refreshAll();
            });
        }, "conn-ping").start();
    }

    private void bridgeToggle(String name, boolean on, Switch sw,
                              java.util.function.BooleanSupplier actual,
                              RadioCall call, boolean strongOn, String warn) {
        if (!bridgeOn) {
            syncSwitch(sw, actual.getAsBoolean());
            return;
        }
        boolean strong = strongOn && on;
        String title = (on ? "Turn on " : "Turn off ") + name + "?";
        String message = (warn != null && on ? warn + "\n\n" : "")
                + "Control's bridge runs this one named switch — nothing "
                + "else. The attempt is written to the audit log.";
        confirmWrite(strong, title, message, on ? "Turn on" : "Turn off",
                () -> {
                    new Thread(() -> {
                        ShellBridgeClient.Response r =
                                call.run(this, BridgeControl.ORIGIN_UI, on);
                        ui.post(() -> {
                            Toast.makeText(this, r.ok
                                            ? name + (on ? " on." : " off.")
                                            : "Bridge refused / failed: " + r.error,
                                    Toast.LENGTH_LONG).show();
                            refreshAll();
                            pingBridge();
                        });
                    }, "conn-radio").start();
                },
                () -> syncSwitch(sw, actual.getAsBoolean()));
    }

    /** Confirmation pattern shared with the Deep controls screen:
     *  a plain dialog normally; press-and-hold 3 seconds when strong. */
    private void confirmWrite(boolean strong, String title, String message,
                              String actionLabel, Runnable write, Runnable cancel) {
        if (!strong) {
            new AlertDialog.Builder(this)
                    .setTitle(title)
                    .setMessage(message)
                    .setNegativeButton("Cancel", (d, w) -> cancel.run())
                    .setOnCancelListener(d -> cancel.run())
                    .setPositiveButton(actionLabel, (d, w) -> write.run())
                    .show();
            return;
        }
        LinearLayout box = Ui.column(this);
        int p = Ui.dp(this, 18);
        box.setPadding(p, p, p, p);
        box.addView(Ui.body(this, message
                + "\n\nStrong confirmation: press and hold the button below "
                + "for 3 seconds."));
        Button hold = Ui.button(this, "Press and hold to confirm", false);
        box.addView(hold, Ui.wrap(0, 14, 0, 0, this));
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(title)
                .setView(box)
                .setNegativeButton("Cancel", (d, w) -> cancel.run())
                .setOnCancelListener(d -> cancel.run())
                .create();
        Runnable fire = () -> {
            dialog.dismiss();
            write.run();
        };
        hold.setOnTouchListener((v, event) -> {
            if (event.getAction() == MotionEvent.ACTION_DOWN) {
                hold.setText("Keep holding…");
                ui.postDelayed(fire, 3000);
                return true;
            }
            if (event.getAction() == MotionEvent.ACTION_UP
                    || event.getAction() == MotionEvent.ACTION_CANCEL) {
                ui.removeCallbacks(fire);
                hold.setText("Press and hold to confirm");
                return true;
            }
            return false;
        });
        dialog.show();
    }

    // ------------------------------------------------------------ live status

    private final BroadcastReceiver receiver = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) {
            String a = i.getAction();
            if (a == null) return;
            switch (a) {
                case WifiManager.WIFI_STATE_CHANGED_ACTION:
                case WifiManager.NETWORK_STATE_CHANGED_ACTION:
                    refreshWifi();
                    break;
                case WifiManager.SCAN_RESULTS_AVAILABLE_ACTION:
                    refreshWifiScanResults();
                    refreshWifi();
                    break;
                case BluetoothAdapter.ACTION_STATE_CHANGED:
                    refreshBluetooth();
                    break;
                case BluetoothDevice.ACTION_FOUND: {
                    BluetoothDevice d = i.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
                    if (d != null) onBtFound(d);
                    break;
                }
                case BluetoothAdapter.ACTION_DISCOVERY_FINISHED:
                    refreshBluetooth();
                    break;
                case Intent.ACTION_AIRPLANE_MODE_CHANGED:
                    refreshAirplane();
                    refreshWifi();
                    refreshBluetooth();
                    refreshCellular();
                    break;
                case NfcAdapter.ACTION_ADAPTER_STATE_CHANGED:
                    refreshNfc();
                    break;
                case LocationManager.PROVIDERS_CHANGED_ACTION:
                    refreshLocation();
                    refreshWifi();
                    break;
                case MeshInternet.ACTION_MESH_STATE_CHANGED:
                    refreshMesh();
                    refreshFilter();
                    break;
                case MeshInternet.ACTION_PEERS:
                    if (peerDialogWanted) {
                        peerDialogWanted = false;
                        showPeerDialog(i.getStringArrayExtra(MeshInternet.EXTRA_PEERS));
                    }
                    break;
                default:
                    break;
            }
        }
    };

    private void refreshAll() {
        refreshWifi();
        refreshBluetooth();
        refreshCellular();
        refreshAirplane();
        refreshNfc();
        refreshLocation();
        refreshFilter();
        refreshMesh();
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshAll();
        pingBridge();
        if (!receiversOn) {
            IntentFilter f = new IntentFilter();
            f.addAction(WifiManager.WIFI_STATE_CHANGED_ACTION);
            f.addAction(WifiManager.NETWORK_STATE_CHANGED_ACTION);
            f.addAction(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION);
            f.addAction(BluetoothAdapter.ACTION_STATE_CHANGED);
            f.addAction(BluetoothDevice.ACTION_FOUND);
            f.addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED);
            f.addAction(Intent.ACTION_AIRPLANE_MODE_CHANGED);
            f.addAction(NfcAdapter.ACTION_ADAPTER_STATE_CHANGED);
            f.addAction(LocationManager.PROVIDERS_CHANGED_ACTION);
            f.addAction(MeshInternet.ACTION_MESH_STATE_CHANGED);
            f.addAction(MeshInternet.ACTION_PEERS);
            // v1.11 fix (Connections crashed on open in v1.10): on API
            // 33+, a context-registered receiver whose filter is not
            // system-only MUST be registered with an explicit exported
            // flag — the 2-arg call throws SecurityException in
            // onResume. Before v1.10 this filter held only system
            // broadcasts (exempt); the two mesh actions made it
            // non-exempt. RECEIVER_NOT_EXPORTED is correct: both mesh
            // broadcasts are sent by this app itself, and same-app
            // broadcasts still deliver to a not-exported receiver,
            // while other apps cannot spoof mesh state. The constant
            // is compile-time safe at minSdk 26; only the 3-arg
            // overload needs the version guard.
            if (android.os.Build.VERSION.SDK_INT >= 33) {
                registerReceiver(receiver, f, Context.RECEIVER_NOT_EXPORTED);
            } else {
                registerReceiver(receiver, f);
            }
            ConnectivityManager cm =
                    (ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
            netCallback = new ConnectivityManager.NetworkCallback() {
                @Override public void onCapabilitiesChanged(
                        Network network, NetworkCapabilities caps) {
                    ui.post(() -> {
                        refreshWifi();
                        refreshCellular();
                    });
                }

                @Override public void onLost(Network network) {
                    ui.post(() -> {
                        refreshWifi();
                        refreshCellular();
                    });
                }
            };
            try {
                cm.registerNetworkCallback(
                        new NetworkRequest.Builder().build(), netCallback);
            } catch (Exception ignored) {
            }
            receiversOn = true;
        }
    }

    @Override
    protected void onPause() {
        if (receiversOn) {
            try {
                unregisterReceiver(receiver);
            } catch (Exception ignored) {
            }
            ConnectivityManager cm =
                    (ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
            if (netCallback != null) {
                try {
                    cm.unregisterNetworkCallback(netCallback);
                } catch (Exception ignored) {
                }
                netCallback = null;
            }
            receiversOn = false;
        }
        super.onPause();
    }

    // ------------------------------------------------------------ helpers

    private void openPanel(String panelAction, String fallbackAction) {
        if (Build.VERSION.SDK_INT >= 29) {
            try {
                startActivity(new Intent(panelAction));
                return;
            } catch (Exception ignored) {
            }
        }
        openSystem(fallbackAction);
    }

    private void openSystem(String action) {
        try {
            startActivity(new Intent(action));
        } catch (Exception e) {
            Toast.makeText(this, "Couldn't open that system screen.",
                    Toast.LENGTH_LONG).show();
        }
    }

    private void requestPerms(String[] perms) {
        try {
            requestPermissions(perms, REQ_PERMS);
        } catch (Exception ignored) {
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions,
                                           int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_PERMS) refreshAll();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_MESH_VPN) {
            if (resultCode == RESULT_OK) {
                MeshInternet.reconcile(this, true);
            } else {
                Toast.makeText(this, "Mesh internet needs VPN permission to run.",
                        Toast.LENGTH_LONG).show();
            }
            refreshMesh();
        }
    }
}
