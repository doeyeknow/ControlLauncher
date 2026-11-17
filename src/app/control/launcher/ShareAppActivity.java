package app.control.launcher;

import android.content.ClipData;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Typeface;
import android.net.Uri;
import android.net.wifi.WifiConfiguration;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;

/**
 * Share an app with a nearby phone (v1.25), reached from
 * Settings. The picker lists every launchable app — Control
 * first — and each app shares two ways:
 *
 *  1. PRIMARY — the share sheet: the app's installed APK goes
 *     out through Android's share sheet (Bluetooth, Quick
 *     Share, anything else on the phone) via the narrow
 *     {@link ApkShareProvider}, which serves exactly that
 *     one registered file.
 *  2. FALLBACK — hotspot + QR: the phone starts a local-only
 *     hotspot (no internet) and a minimal HTTP server bound
 *     to the hotspot interface, serving one download page
 *     plus the APK. Two QR codes: one joins the hotspot, one
 *     opens the download page. The other phone needs nothing
 *     installed and no data.
 *
 * Deliberately NOT here: a captive portal. The auto-popup
 * portal page needs DNS control over the hotspot that
 * Android does not give regular apps (root-only), so the
 * screen never claims the page pops up on its own — the
 * second QR code is the honest replacement.
 *
 * The hotspot and server stop when the share ends: the Stop
 * button, picking a different app, backing out, or the
 * screen closing. Rotation does not end the share (the
 * manifest holds the configuration change), and a theme
 * change does not rebuild the screen mid-share.
 */
public class ShareAppActivity extends ThemedActivity {

    private static final int PHASE_PICKER = 0;
    private static final int PHASE_METHODS = 1;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private boolean destroyed = false;
    private boolean loading = false;

    private ScrollView scroll;
    private LinearLayout root;
    private LinearLayout listBox;
    private TextView statusLine;
    private LinearLayout methodsBox;

    private int phase = PHASE_PICKER;
    private SharePicker.Row selected;
    private File selectedApk;

    // Hotspot share state (fallback method).
    private final ShareHotspotState hotspotState = new ShareHotspotState();
    private WifiManager.LocalOnlyHotspotReservation reservation;
    private ShareHttpServer httpServer;
    private TextView hotspotStatus;
    private LinearLayout hotspotPanel;

    @Override
    protected boolean recreateOnThemeChange() {
        return false;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        scroll = new ScrollView(this);
        scroll.setBackgroundColor(Ui.BG);
        root = Ui.column(this);
        int pad = Ui.dp(this, 20);
        root.setPadding(pad, Ui.dp(this, 20), pad, Ui.dp(this, 24));
        scroll.addView(root);
        setContentView(scroll);

        root.addView(Ui.screenTitle(this, "Share an app"));
        root.addView(Ui.body(this,
                "Send an app on this phone to a nearby phone. The other "
                + "phone needs nothing installed — just the file, "
                + "straight from this phone."),
                Ui.wrap(0, 8, 0, 0, this));

        statusLine = Ui.text(this, "Reading the apps on this phone…", 13, Ui.ACCENT);
        statusLine.setTypeface(Typeface.MONOSPACE);
        root.addView(statusLine, Ui.wrap(0, 16, 0, 0, this));

        listBox = Ui.column(this);
        listBox.setBackgroundColor(0x00000000);
        root.addView(listBox, Ui.wrap(0, 8, 0, 0, this));

        methodsBox = Ui.column(this);
        methodsBox.setBackgroundColor(0x00000000);
        methodsBox.setVisibility(View.GONE);
        root.addView(methodsBox, Ui.wrap(0, 8, 0, 0, this));

        loadAsync();
    }

    @Override
    protected void onDestroy() {
        destroyed = true;
        stopSharing();
        super.onDestroy();
    }

    @Override
    public void onBackPressed() {
        if (phase == PHASE_METHODS) {
            stopSharing();
            showPicker();
            return;
        }
        super.onBackPressed();
    }

    // ---- picker ----

    private void showPicker() {
        phase = PHASE_PICKER;
        selected = null;
        selectedApk = null;
        methodsBox.setVisibility(View.GONE);
        methodsBox.removeAllViews();
        hotspotPanel = null;
        hotspotStatus = null;
        listBox.setVisibility(View.VISIBLE);
        statusLine.setVisibility(View.VISIBLE);
        statusLine.setText("Choose an app to share.");
    }

    /** The PackageManager + file-size reads run off the main
     *  thread. The cached list scans synchronously on first
     *  run, so the picker shows every launchable app even
     *  before any background scan; a refresh re-renders if
     *  the list changed meanwhile. */
    private void loadAsync() {
        if (loading) return;
        loading = true;
        new Thread(() -> {
            List<SharePicker.Raw> raw = new ArrayList<>();
            String ownPkg = getPackageName();
            String ownLabel = ownPkg;
            long ownSize = 0;
            try {
                PackageManager pm = getPackageManager();
                // Control itself is NOT in the cached list — the
                // drawer scan deliberately hides our own package
                // (v1.26.1) — so the share feed adds it explicitly
                // here, through the same readable-file size rule
                // as every other row. If the own APK somehow
                // can't be read, the size stays 0 and shape()
                // drops the row like any other unreadable app.
                try {
                    ApplicationInfo self = pm.getApplicationInfo(ownPkg, 0);
                    CharSequence label = pm.getApplicationLabel(self);
                    if (label != null && label.length() > 0) {
                        ownLabel = label.toString();
                    }
                    if (self.sourceDir != null) {
                        File apk = new File(self.sourceDir);
                        if (apk.isFile() && apk.canRead()) ownSize = apk.length();
                    }
                } catch (PackageManager.NameNotFoundException ignored) {
                    // Our own package: cannot happen in practice;
                    // the row drops on size 0 if it ever did.
                }
                for (InstalledApps.Entry e : InstalledApps.all(this)) {
                    try {
                        ApplicationInfo ai = pm.getApplicationInfo(e.pkg, 0);
                        long size = 0;
                        if (ai.sourceDir != null) {
                            File apk = new File(ai.sourceDir);
                            if (apk.isFile() && apk.canRead()) size = apk.length();
                        }
                        raw.add(new SharePicker.Raw(e.pkg, e.label, true, size));
                    } catch (PackageManager.NameNotFoundException ignored) {
                        // Went away mid-scan; the refresh drops it.
                    }
                }
            } catch (Exception e) {
                // The list stays empty; the status line says so.
            }
            final List<SharePicker.Row> rows = SharePicker.shape(
                    SharePicker.prependSelf(raw, ownPkg, ownLabel, ownSize),
                    ownPkg);
            ui.post(() -> {
                loading = false;
                if (destroyed) return;
                renderPicker(rows);
                AppListStore.refreshAsync(this, () -> {
                    if (!destroyed && phase == PHASE_PICKER) loadAsync();
                });
            });
        }, "share-app-scan").start();
    }

    private void renderPicker(List<SharePicker.Row> rows) {
        listBox.removeAllViews();
        if (rows.isEmpty()) {
            statusLine.setText("No shareable apps were found on this phone.");
            return;
        }
        statusLine.setText("Choose an app to share.");
        boolean first = true;
        for (SharePicker.Row row : rows) {
            if (!first) listBox.addView(Ui.divider(this));
            first = false;
            final SharePicker.Row r = row;
            LinearLayout line = Ui.row(this);
            line.setPadding(0, Ui.dp(this, 10), 0, Ui.dp(this, 10));
            ImageView icon = new ImageView(this);
            icon.setImageDrawable(InstalledApps.iconFor(this, r.pkg));
            int sz = Ui.dp(this, 40);
            line.addView(icon, new LinearLayout.LayoutParams(sz, sz));
            LinearLayout names = Ui.column(this);
            names.setBackgroundColor(0x00000000);
            names.setPadding(Ui.dp(this, 12), 0, 0, 0);
            TextView label = Ui.text(this, r.label, 16, Ui.TEXT);
            names.addView(label);
            TextView sub = Ui.text(this,
                    r.pkg + "  ·  " + SharePayload.formatSize(r.apkSizeBytes),
                    12, Ui.DIM);
            sub.setTypeface(Typeface.MONOSPACE);
            names.addView(sub);
            line.addView(names, new LinearLayout.LayoutParams(0,
                    LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
            line.setOnClickListener(v -> showMethods(r));
            listBox.addView(line);
        }
    }

    // ---- methods ----

    private void showMethods(SharePicker.Row row) {
        File apk = null;
        try {
            ApplicationInfo ai = getPackageManager().getApplicationInfo(row.pkg, 0);
            if (ai.sourceDir != null) {
                File f = new File(ai.sourceDir);
                if (f.isFile() && f.canRead()) apk = f;
            }
        } catch (Exception ignored) {
        }
        if (apk == null) {
            Toast.makeText(this,
                    "That app's file can't be read, so it can't be shared.",
                    Toast.LENGTH_LONG).show();
            return;
        }
        selected = row;
        selectedApk = apk;
        phase = PHASE_METHODS;
        listBox.setVisibility(View.GONE);
        statusLine.setVisibility(View.GONE);
        methodsBox.removeAllViews();
        methodsBox.setVisibility(View.VISIBLE);

        // Header: icon + name + package + size.
        LinearLayout head = Ui.row(this);
        ImageView icon = new ImageView(this);
        icon.setImageDrawable(InstalledApps.iconFor(this, row.pkg));
        int sz = Ui.dp(this, 46);
        head.addView(icon, new LinearLayout.LayoutParams(sz, sz));
        LinearLayout names = Ui.column(this);
        names.setBackgroundColor(0x00000000);
        names.setPadding(Ui.dp(this, 12), 0, 0, 0);
        TextView nameView = Ui.text(this, row.label, 20, Ui.TEXT);
        nameView.setTypeface(Typeface.create("sans-serif", Typeface.BOLD));
        names.addView(nameView);
        TextView pkgView = Ui.text(this,
                row.pkg + "  ·  " + SharePayload.formatSize(row.apkSizeBytes),
                13, Ui.DIM);
        pkgView.setTypeface(Typeface.MONOSPACE);
        names.addView(pkgView);
        head.addView(names, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        head.setGravity(Gravity.CENTER_VERTICAL);
        methodsBox.addView(head);

        // PRIMARY: the share sheet.
        Button shareBtn = Ui.button(this, "Share…", true);
        shareBtn.setOnClickListener(v -> shareViaSheet());
        methodsBox.addView(shareBtn, Ui.wrap(0, 20, 0, 0, this));
        methodsBox.addView(Ui.body(this,
                "Sends the app through Android's share sheet — "
                + "Bluetooth, Quick Share, or anything else on this "
                + "phone. The other phone allows the install once "
                + "when Android asks. The file is the same signed "
                + "build already on this phone, so updates install "
                + "cleanly."),
                Ui.wrap(0, 8, 0, 0, this));

        // FALLBACK: hotspot + QR.
        Button hotspotBtn = Ui.button(this, "Hotspot + QR", false);
        hotspotBtn.setOnClickListener(v -> startHotspot());
        methodsBox.addView(hotspotBtn, Ui.wrap(0, 20, 0, 0, this));
        methodsBox.addView(Ui.body(this,
                "No app needed on the other phone, and no data "
                + "needed on either end. This phone starts a "
                + "hotspot with no internet and shows two codes: "
                + "one joins the hotspot, one opens the download "
                + "page. The hotspot stops when this screen closes."),
                Ui.wrap(0, 8, 0, 0, this));

        hotspotStatus = Ui.text(this, "", 13, Ui.ACCENT);
        hotspotStatus.setTypeface(Typeface.MONOSPACE);
        methodsBox.addView(hotspotStatus, Ui.wrap(0, 12, 0, 0, this));

        hotspotPanel = Ui.column(this);
        hotspotPanel.setBackgroundColor(0x00000000);
        methodsBox.addView(hotspotPanel, Ui.wrap(0, 4, 0, 0, this));

        Button backBtn = Ui.smallButton(this, "Choose a different app");
        backBtn.setOnClickListener(v -> {
            stopSharing();
            showPicker();
        });
        methodsBox.addView(backBtn, Ui.wrap(0, 16, 0, 0, this));
    }

    /** PRIMARY: the system share sheet, carrying a content URI
     *  the narrow provider serves. The grant rides both the
     *  intent flag and ClipData, which is the combination the
     *  chooser reliably forwards to the picked target. */
    private void shareViaSheet() {
        if (selected == null || selectedApk == null) return;
        String displayName = selected.pkg + ".apk";
        Uri uri = ApkShareProvider.register(selectedApk, displayName);
        Intent send = new Intent(Intent.ACTION_SEND);
        send.setType(SharePayload.APK_MIME);
        send.putExtra(Intent.EXTRA_STREAM, uri);
        send.putExtra(Intent.EXTRA_SUBJECT, selected.label);
        send.setClipData(ClipData.newRawUri("", uri));
        send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        Intent chooser = Intent.createChooser(send, "Share " + selected.label);
        chooser.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        try {
            startActivity(chooser);
        } catch (Exception e) {
            Toast.makeText(this,
                    "No app on this phone can share a file.",
                    Toast.LENGTH_LONG).show();
        }
    }

    // ---- fallback: hotspot + QR ----

    private boolean hasHotspotPermission() {
        if (Build.VERSION.SDK_INT >= 33) {
            return checkSelfPermission(
                    android.Manifest.permission.NEARBY_WIFI_DEVICES)
                    == PackageManager.PERMISSION_GRANTED;
        }
        return checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION)
                == PackageManager.PERMISSION_GRANTED;
    }

    private void startHotspot() {
        if (selected == null || selectedApk == null) return;
        if (!hotspotState.requestStart()) return;
        if (!hasHotspotPermission()) {
            hotspotState.onFailed();
            hotspotStatus.setText("Nearby-device permission is off, so the "
                    + "hotspot can't start. Turn it on in Settings and "
                    + "try again — or use Share… above instead.");
            return;
        }
        WifiManager wifi = (WifiManager) getApplicationContext()
                .getSystemService(Context.WIFI_SERVICE);
        if (wifi == null) {
            hotspotState.onFailed();
            hotspotStatus.setText("This phone has no Wi-Fi service to start "
                    + "a hotspot with. Use Share… above instead.");
            return;
        }
        hotspotStatus.setText("Starting the hotspot…");
        try {
            wifi.startLocalOnlyHotspot(new WifiManager.LocalOnlyHotspotCallback() {
                @Override
                public void onStarted(WifiManager.LocalOnlyHotspotReservation res) {
                    reservation = res;
                    onHotspotStarted(res.getWifiConfiguration());
                }

                @Override
                public void onStopped() {
                    stopServerOnly();
                    hotspotState.onStopped();
                    ui.post(() -> {
                        setHotspotStatus("The hotspot stopped.");
                        if (hotspotPanel != null) hotspotPanel.removeAllViews();
                    });
                }

                @Override
                public void onFailed(int reason) {
                    hotspotState.onFailed();
                    ui.post(() -> setHotspotStatus("The hotspot couldn't start ("
                            + hotspotFailureWords(reason) + "). "
                            + "Use Share… above instead."));
                }
            }, new Handler(Looper.getMainLooper()));
        } catch (SecurityException e) {
            hotspotState.onFailed();
            hotspotStatus.setText("Android refused the hotspot. Use Share… "
                    + "above instead.");
        } catch (Exception e) {
            hotspotState.onFailed();
            hotspotStatus.setText("The hotspot couldn't start. Use Share… "
                    + "above instead.");
        }
    }

    private String hotspotFailureWords(int reason) {
        switch (reason) {
            case WifiManager.LocalOnlyHotspotCallback.ERROR_TETHERING_DISALLOWED:
                return "tethering is not allowed on this phone";
            case WifiManager.LocalOnlyHotspotCallback.ERROR_NO_CHANNEL:
                return "no Wi-Fi channel is free right now";
            case WifiManager.LocalOnlyHotspotCallback.ERROR_GENERIC:
            default:
                return "the system gave no reason";
        }
    }

    private void onHotspotStarted(WifiConfiguration config) {
        final String ssid = stripQuotes(config != null ? config.SSID : null);
        final String password = stripQuotes(config != null ? config.preSharedKey : null);
        if (ssid == null || ssid.isEmpty() || password == null || password.isEmpty()) {
            hotspotState.onFailed();
            stopReservationOnly();
            ui.post(() -> setHotspotStatus("The hotspot started but gave no name "
                    + "or password to share. Use Share… above instead."));
            return;
        }
        // The hotspot interface takes a moment to come up; retry
        // the address lookup briefly before giving up honestly.
        new Thread(() -> {
            InetAddress addr = null;
            for (int i = 0; i < 8 && addr == null && !destroyed; i++) {
                addr = resolveHotspotAddress();
                if (addr == null) {
                    try {
                        Thread.sleep(500);
                    } catch (InterruptedException ignored) {
                        return;
                    }
                }
            }
            final InetAddress gateway = addr;
            if (gateway == null) {
                hotspotState.onFailed();
                stopReservationOnly();
                ui.post(() -> setHotspotStatus("The hotspot started but this phone "
                        + "couldn't find its own hotspot address. "
                        + "Use Share… above instead."));
                return;
            }
            try {
                ShareHttpServer server = new ShareHttpServer(gateway, selectedApk,
                        selected.label,
                        SharePayload.formatSize(selected.apkSizeBytes),
                        selected.pkg + ".apk");
                server.start();
                httpServer = server;
            } catch (Exception e) {
                hotspotState.onFailed();
                stopReservationOnly();
                ui.post(() -> setHotspotStatus("The download page couldn't start. "
                        + "Use Share… above instead."));
                return;
            }
            if (!hotspotState.onStarted()) {
                stopServerOnly();
                stopReservationOnly();
                return;
            }
            final String url = SharePayload.downloadUrl(
                    gateway.getHostAddress(), httpServer.port());
            ui.post(() -> {
                if (destroyed || phase != PHASE_METHODS || hotspotPanel == null) {
                    // The user left the screen while the hotspot
                    // came up: stop what was just started rather
                    // than leave a hotspot running unseen.
                    stopSharing();
                    return;
                }
                showHotspotPanel(ssid, password, url);
            });
        }, "share-hotspot-start").start();
    }

    /** The hotspot interface's address, via the harness-pinned
     *  selection rule over the enumerated interfaces. */
    private InetAddress resolveHotspotAddress() {
        try {
            List<ShareHotspotState.Iface> ifaces = new ArrayList<>();
            Enumeration<NetworkInterface> nis = NetworkInterface.getNetworkInterfaces();
            while (nis.hasMoreElements()) {
                NetworkInterface ni = nis.nextElement();
                List<String> addrs = new ArrayList<>();
                Enumeration<InetAddress> as = ni.getInetAddresses();
                while (as.hasMoreElements()) {
                    InetAddress a = as.nextElement();
                    if (a instanceof Inet4Address) addrs.add(a.getHostAddress());
                }
                ifaces.add(new ShareHotspotState.Iface(ni.getName(), ni.isUp(), addrs));
            }
            String picked = ShareHotspotState.pickAddress(ifaces);
            if (picked == null) return null;
            return InetAddress.getByName(picked);
        } catch (Exception ignored) {
            return null;
        }
    }

    private static String stripQuotes(String v) {
        return SharePayload.stripQuotes(v);
    }

    private void showHotspotPanel(String ssid, String password, String url) {
        hotspotStatus.setText("Hotspot is on. The other phone joins it, "
                + "then opens the download page — no internet involved.");
        hotspotPanel.removeAllViews();

        TextView creds = Ui.text(this,
                "Network: " + ssid + "\nPassword: " + password, 14, Ui.TEXT);
        creds.setTypeface(Typeface.MONOSPACE);
        hotspotPanel.addView(creds, Ui.wrap(0, 8, 0, 0, this));

        TextView step1 = Ui.text(this, "1 — Scan this code to join the hotspot",
                15, Ui.TEXT);
        hotspotPanel.addView(step1, Ui.wrap(0, 16, 0, 6, this));
        final ImageView wifiQr = new ImageView(this);
        int wifiPx = Ui.dp(this, 260);
        hotspotPanel.addView(wifiQr, new LinearLayout.LayoutParams(wifiPx, wifiPx));
        renderQrAsync(SharePayload.wifiConfig(ssid, password), wifiPx, wifiQr,
                "Couldn't draw the join code — join the network above by hand.");

        TextView step2 = Ui.text(this, "2 — Then scan this code to download",
                15, Ui.TEXT);
        hotspotPanel.addView(step2, Ui.wrap(0, 16, 0, 6, this));
        final ImageView urlQr = new ImageView(this);
        int urlPx = Ui.dp(this, 200);
        hotspotPanel.addView(urlQr, new LinearLayout.LayoutParams(urlPx, urlPx));
        if (url != null) {
            renderQrAsync(url, urlPx, urlQr,
                    "Couldn't draw the download code — type the address below.");
            TextView urlText = Ui.text(this, url, 13, Ui.DIM);
            urlText.setTypeface(Typeface.MONOSPACE);
            hotspotPanel.addView(urlText, Ui.wrap(0, 6, 0, 0, this));
        } else {
            hotspotPanel.addView(Ui.body(this,
                    "The download address couldn't be built. Stop sharing "
                    + "and try again, or use Share… above."),
                    Ui.wrap(0, 6, 0, 0, this));
        }

        Button stopBtn = Ui.button(this, "Stop sharing", false);
        stopBtn.setOnClickListener(v -> {
            stopSharing();
            hotspotStatus.setText("The hotspot stopped.");
            hotspotPanel.removeAllViews();
        });
        hotspotPanel.addView(stopBtn, Ui.wrap(0, 20, 0, 0, this));
    }

    /** QR rendering walks every pixel; it runs off the main
     *  thread, and a failure leaves the text fallback — never
     *  a blank box with no explanation. */
    private void renderQrAsync(final String payload, final int px,
                               final ImageView target, final String failureText) {
        new Thread(() -> {
            final Bitmap bmp = QrRender.render(payload, px);
            ui.post(() -> {
                if (destroyed) return;
                if (bmp != null) {
                    target.setImageBitmap(bmp);
                } else {
                    target.setVisibility(View.GONE);
                    Toast.makeText(this, failureText, Toast.LENGTH_LONG).show();
                }
            });
        }, "share-qr-render").start();
    }

    /** Sets the hotspot status line when the methods screen is
     *  still showing it. Async hotspot callbacks can fire after
     *  the user backed out to the picker, where the line no
     *  longer exists — those updates are dropped, never crashed
     *  on. */
    private void setHotspotStatus(String text) {
        if (destroyed) return;
        if (hotspotStatus != null) hotspotStatus.setText(text);
    }

    /** Stops everything the hotspot share started. Safe to call
     *  from any state, any number of times. */
    private void stopSharing() {
        if (hotspotState.requestStop() || hotspotState.state()
                != ShareHotspotState.State.IDLE) {
            stopServerOnly();
            stopReservationOnly();
            hotspotState.onStopped();
        } else {
            stopServerOnly();
            stopReservationOnly();
        }
    }

    private void stopServerOnly() {
        ShareHttpServer server = httpServer;
        httpServer = null;
        if (server != null) server.stop();
    }

    private void stopReservationOnly() {
        WifiManager.LocalOnlyHotspotReservation res = reservation;
        reservation = null;
        if (res != null) {
            try {
                res.close();
            } catch (Exception ignored) {
            }
        }
    }
}
