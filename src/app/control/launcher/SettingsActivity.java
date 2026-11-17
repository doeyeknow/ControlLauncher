package app.control.launcher;

import android.app.Activity;
import android.app.AlertDialog;
import android.bluetooth.BluetoothAdapter;
import android.content.ComponentName;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.net.wifi.WifiManager;
import android.os.Bundle;
import android.provider.Settings;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * Settings hub (v1.2, Android-style): a pinned search field, the
 * profile header card, then grouped icon rows — each row shows a
 * one-line LIVE summary of its current state; true on/off settings
 * are switches right on their rows; everything else opens the
 * detail screen that keeps the full controls. Nothing from the old
 * single Settings screen was deleted — it all lives on the
 * sub-screens (SettingsFilterActivity, SettingsSmsActivity,
 * SettingsAdminActivity, SettingsProfileActivity, DeepViewActivity,
 * ConnectionsActivity, BackgroundActivity).
 */
public class SettingsActivity extends ThemedActivity {

    private static final int REQ_VPN = 7001;
    private static final int REQ_SMS = 7002;

    private static class HubRow {
        View row;
        View header;
        String search;
    }

    private final List<HubRow> hubRows = new ArrayList<>();
    private boolean syncingSwitches;

    // live summary targets
    private TextView headerLine;
    private TextView backgroundSummary;
    private TextView connectionsSummary;
    private TextView notifAccessSummary;
    private TextView mutedSummary;
    private TextView filterSummary;
    private TextView walkieSummary;
    private TextView chatSummary;
    private TextView smsSummary;
    private TextView deepSummary;
    private TextView watchdogSummary;
    private TextView overlaySummary;
    private TextView overlayPermissionSummary;
    private TextView bridgeRestartSummary;
    private TextView adminSummary;
    private TextView homeSummary;
    private TextView profileSummary;
    private TextView updateSummary;
    private TextView dictSummary;
    private TextView themeSummary;
    private Switch clockSwitch;
    private Switch quietSwitch;
    private Switch filterSwitch;
    private Switch smsSwitch;
    private Switch jsSwitch;
    private Switch overlaySwitch;
    private Switch themeSwitch;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        LinearLayout outer = Ui.column(this);
        outer.setBackgroundColor(Ui.BG);
        setContentView(outer);

        // ---- pinned search ----
        EditText search = new EditText(this);
        search.setHint("Search settings");
        search.setSingleLine(true);
        search.setTextColor(Ui.TEXT);
        search.setHintTextColor(Ui.DIM);
        search.setTextSize(16);
        search.setBackground(Ui.roundedStroke(this, Ui.SURFACE, 999, Ui.LINE));
        int sp = Ui.dp(this, 12);
        search.setPadding(sp, Ui.dp(this, 10), sp, Ui.dp(this, 10));
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        slp.setMargins(sp, sp, sp, Ui.dp(this, 4));
        outer.addView(search, slp);
        search.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}

            @Override public void onTextChanged(CharSequence s, int a, int b, int c) {
                applyFilter(s == null ? "" : s.toString());
            }

            @Override public void afterTextChanged(Editable e) {}
        });

        ScrollView scroll = new ScrollView(this);
        LinearLayout root = Ui.column(this);
        int pad = Ui.dp(this, 16);
        root.setPadding(pad, Ui.dp(this, 8), pad, Ui.dp(this, 24));
        scroll.addView(root);
        outer.addView(scroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        // ---- profile header card ----
        LinearLayout card = Ui.column(this);
        card.setBackground(Ui.roundedStroke(this, Ui.SURFACE, 16, Ui.LINE));
        card.setPadding(pad, pad, pad, pad);
        TextView headerName = Ui.text(this, Profile.displayName(this), 19, Ui.TEXT);
        card.addView(headerName);
        headerLine = Ui.text(this, "…", 13, Ui.DIM);
        headerLine.setTypeface(android.graphics.Typeface.MONOSPACE);
        card.addView(headerLine, Ui.wrap(0, 4, 0, 0, this));
        card.setOnClickListener(v -> startActivity(
                new Intent(this, SettingsProfileActivity.class)));
        root.addView(card, Ui.wrap(0, 4, 0, 0, this));
        new Thread(() -> {
            String fp = "";
            try {
                String full = AdbKeyStore.ownFingerprint(this);
                if (full != null && full.length() > 11) fp = full.substring(0, 11) + "…";
            } catch (Exception ignored) {
            }
            final String line2 = headerLine2(fp);
            runOnUiThread(() -> {
                headerName.setText(Profile.displayName(this));
                if (headerLine != null) headerLine.setText(line2);
            });
        }, "settings-header").start();

        // ---- Home screen ----
        View homeHeader = addGroup(root, "Home screen");
        LinearLayout home = groupCard(root);
        backgroundSummary = navRow(home, homeHeader, PageGlyphs.IMAGE, "Background",
                "background wallpaper photo color gradient dim",
                () -> startActivity(new Intent(this, BackgroundActivity.class)));
        navRow(home, homeHeader, PageGlyphs.APPS, "Choose favorites",
                "favorites home apps star",
                () -> startActivity(AppPickerActivity.intent(
                        this, Prefs.KEY_FAVORITES, "Favorites")));
        clockSwitch = switchRow(home, homeHeader, PageGlyphs.CLOCK,
                "Show clock and date", "clock date home",
                Prefs.showClock(this),
                on -> Profile.setShowClock(this, on));

        // ---- Appearance (v1.15) ----
        View appearanceHeader = addGroup(root, "Appearance");
        LinearLayout appearance = groupCard(root);
        themeSwitch = switchRow(appearance, appearanceHeader, PageGlyphs.IMAGE,
                "Light theme", "light dark theme appearance colors",
                Ui.isLightTheme(this),
                on -> {
                    Prefs.get(this).edit()
                            .putBoolean(Prefs.KEY_THEME_LIGHT, on).apply();
                    Ui.refreshTheme(this);
                    recreate();
                });
        themeSummary = lastSummary(appearance);
        navRow(appearance, appearanceHeader, PageGlyphs.SETTINGS, "Run setup again",
                "setup first run name permissions import theme walkthrough",
                () -> startActivity(new Intent(this, SetupActivity.class)));

        // ---- Connections ----
        View connHeader = addGroup(root, "Connections");
        LinearLayout conn = groupCard(root);
        connectionsSummary = navRow(conn, connHeader, PageGlyphs.CONNECTIONS,
                "Connections", "wifi bluetooth cellular airplane nfc location vpn network radios",
                () -> startActivity(new Intent(this, ConnectionsActivity.class)));

        // ---- Notifications ----
        View notifHeader = addGroup(root, "Notifications");
        LinearLayout notif = groupCard(root);
        notifAccessSummary = navRow(notif, notifHeader, PageGlyphs.BELL,
                "Notification access", "notification listener access shade held",
                () -> {
                    try {
                        startActivity(new Intent(
                                Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS));
                    } catch (Exception e) {
                        Toast.makeText(this,
                                "Open Settings → Notifications → Device and app notifications",
                                Toast.LENGTH_LONG).show();
                    }
                });
        mutedSummary = navRow(notif, notifHeader, PageGlyphs.BELL,
                "Muted apps", "muted apps notifications quiet per-app",
                () -> startActivity(AppPickerActivity.intent(
                        this, Prefs.KEY_MUTED_APPS, "Muted apps")));
        quietSwitch = switchRow(notif, notifHeader, PageGlyphs.BELL,
                "Quiet all", "quiet all hold mute everything notifications",
                Prefs.get(this).getBoolean(Prefs.KEY_QUIET_ALL, false),
                on -> Prefs.get(this).edit()
                        .putBoolean(Prefs.KEY_QUIET_ALL, on).apply());
        navRow(notif, notifHeader, PageGlyphs.BELL, "Held notifications",
                "held notifications list shade",
                () -> startActivity(new Intent(this, HeldActivity.class)));

        // ---- Ad filter ----
        View filterHeader = addGroup(root, "Ad filter");
        LinearLayout filter = groupCard(root);
        filterSwitch = switchRow(filter, filterHeader, PageGlyphs.SHIELD,
                "Ad filter", "ad filter ads tracker vpn dns block",
                Prefs.get(this).getBoolean(Prefs.KEY_FILTER_ON, false),
                on -> {
                    boolean isOn = Prefs.get(this)
                            .getBoolean(Prefs.KEY_FILTER_ON, false);
                    if (on != isOn) {
                        SettingsFilterActivity.toggleFilter(
                                this, this::refreshSummaries);
                    }
                });
        filterSummary = lastSummary(filter);
        navRow(filter, filterHeader, PageGlyphs.SHIELD, "Filter settings",
                "filter resolver upstream bypass private dns blocklist",
                () -> startActivity(new Intent(this, SettingsFilterActivity.class)));

        // ---- Walkie-talkie ----
        View walkieHeader = addGroup(root, "Walkie-talkie");
        LinearLayout walkie = groupCard(root);
        walkieSummary = navRow(walkie, walkieHeader, PageGlyphs.WALKIE,
                "Walkie-talkie", "walkie radio channel tuner talk",
                () -> startActivity(new Intent(this, WalkieActivity.class)));

        // ---- Chat & identity ----
        View chatHeader = addGroup(root, "Chat & identity");
        LinearLayout chat = groupCard(root);
        chatSummary = navRow(chat, chatHeader, PageGlyphs.CHAT,
                "Chat", "chat relay messages identity key dm",
                () -> startActivity(new Intent(this, ChatActivity.class)));

        // ---- SMS remote control ----
        View smsHeader = addGroup(root, "SMS remote control");
        LinearLayout sms = groupCard(root);
        smsSwitch = switchRow(sms, smsHeader, PageGlyphs.CHAT,
                "SMS remote control", "sms text remote control secret controller commands",
                Profile.smsControlEnabled(this),
                on -> {
                    Profile.setSmsControlEnabled(this, on);
                    if (on) {
                        requestSmsPermissionsIfNeeded();
                        if (Profile.smsSecretHash(this).isEmpty()) {
                            Toast.makeText(this,
                                    "Set a command secret and mark a Controller in SMS setup.",
                                    Toast.LENGTH_LONG).show();
                        }
                    }
                    refreshSummaries();
                });
        smsSummary = lastSummary(sms);
        navRow(sms, smsHeader, PageGlyphs.CHAT, "SMS setup",
                "sms secret controller commands log setup",
                () -> startActivity(new Intent(this, SettingsSmsActivity.class)));

        // ---- Deep view ----
        View deepHeader = addGroup(root, "Deep view");
        LinearLayout deep = groupCard(root);
        deepSummary = navRow(deep, deepHeader, PageGlyphs.ACTIVITY,
                "Deep view (shell bridge)", "deep view shell bridge pairing adb governed activity",
                () -> startActivity(new Intent(this, DeepViewActivity.class)));
        navRow(deep, deepHeader, PageGlyphs.ACTIVITY, "Activity / Control Room",
                "activity control room usage logs notifications",
                () -> startActivity(new Intent(this, ControlRoomActivity.class)));
        navRow(deep, deepHeader, PageGlyphs.SETTINGS, "Governed Deep controls",
                "deep controls permissions appops governed",
                () -> startActivity(new Intent(this, DeepControlActivity.class)));
        navRow(deep, deepHeader, PageGlyphs.LOCK, "Turn off deep control",
                "deep control off stop bridge kill",
                this::confirmStopBridge);
        // Bridge auto-restart watchdog (v1.16): a status row,
        // not a switch — the watch follows Deep view itself
        // (on once Deep view has run, off when deep control is
        // turned off). Tap opens Deep view.
        watchdogSummary = navRow(deep, deepHeader, PageGlyphs.ACTIVITY,
                "Bridge auto-restart", "bridge auto restart watchdog reboot wireless debugging",
                () -> startActivity(new Intent(this, DeepViewActivity.class)));
        TextView deepBody = Ui.body(this,
                "Once Deep view has run, Control watches the bridge "
                + "and restarts it by itself if it stops — including "
                + "after a reboot, while wireless debugging stays on. "
                + "It checks every few minutes from a foreground "
                + "service, so it shows a persistent notification "
                + "and uses a little battery. Turning off deep "
                + "control stops it.");
        deepBody.setPadding(pad, Ui.dp(this, 10), pad, Ui.dp(this, 12));
        deep.addView(deepBody);

        // ---- Hold-anywhere menu (v1.14) ----
        View overlayHeader = addGroup(root, "Hold-anywhere menu");
        LinearLayout overlay = groupCard(root);
        overlaySwitch = switchRow(overlay, overlayHeader, PageGlyphs.SETTINGS,
                "Hold-anywhere menu", "hold anywhere menu overlay ring deep view",
                OverlayRingService.switchOn(this),
                on -> {
                    Prefs.get(this).edit()
                            .putBoolean(Prefs.KEY_OVERLAY_RING_ON, on).apply();
                    OverlayRingService.reconcile(this, this::refreshSummariesIfAlive);
                    refreshSummaries();
                });
        overlaySummary = lastSummary(overlay);
        // v1.44: this summary carries TWO logical lines — the
        // state line plus the diagnostics line (statusLine2) —
        // so it must wrap, not single-line. switchRow sets
        // singleLine + ellipsize on every summary; undo the
        // single-line part here and cap at 2 display lines,
        // the v1.39 bridge-row treatment
        // (DeepControlActivity.hubStatusRow: setMaxLines(2) +
        // ellipsize END, never setSingleLine). On 1.43 the
        // single-line summary hid the diagnostics entirely:
        // Adam reported the row showed no text at all. The
        // ellipsize set by switchRow stays, so an over-long
        // diagnostic truncates with "…" instead of growing
        // the row. No other row is touched.
        overlaySummary.setSingleLine(false);
        overlaySummary.setMaxLines(2);
        overlayPermissionSummary = navRow(overlay, overlayHeader,
                PageGlyphs.SETTINGS, "Overlay permission",
                "overlay permission draw over other apps display",
                () -> {
                    try {
                        startActivity(OverlayRingService.overlaySettingsIntent(this));
                    } catch (Exception e) {
                        Toast.makeText(this,
                                "Open Settings → Apps → Control → Display over other apps",
                                Toast.LENGTH_LONG).show();
                    }
                });
        // Restart the bridge (v1.15.1): the bridge survives APK
        // upgrades running old code, and a stale bridge is the
        // one hold-anywhere failure the switch and permission
        // rows cannot explain. The tap re-checks first — a
        // current bridge is left alone — and otherwise stops
        // the old bridge and starts the current code.
        bridgeRestartSummary = navRow(overlay, overlayHeader,
                PageGlyphs.SETTINGS, "Restart the bridge",
                "restart bridge stale old version hold anywhere",
                this::restartBridgeForHoldAnywhere);
        TextView overlayBody = Ui.body(this,
                "Hold your finger still anywhere for a second and the "
                + "circle menu opens over whatever app is showing. It "
                + "needs Deep view running (the bridge reads the hold) "
                + "and the overlay permission. Only the hold point is "
                + "ever used — the touch stream is parsed on this phone, "
                + "never logged or stored. Inside the launcher, the "
                + "built-in long-press menu always works, with or "
                + "without this. A persistent notification shows while "
                + "the menu is watching. After a Control update, the "
                + "bridge keeps running its old code until it is "
                + "restarted — use “Restart the bridge” above when "
                + "the row says the bridge is running an old version.");
        overlayBody.setPadding(pad, Ui.dp(this, 10), pad, Ui.dp(this, 12));
        overlay.addView(overlayBody);

        // ---- Device admin ----
        View adminHeader = addGroup(root, "Device admin");
        LinearLayout admin = groupCard(root);
        adminSummary = navRow(admin, adminHeader, PageGlyphs.SHIELD,
                "Device admin", "device admin lock wipe",
                () -> startActivity(new Intent(this, SettingsAdminActivity.class)));

        // ---- Default home app ----
        View roleHeader = addGroup(root, "Default home app");
        LinearLayout role = groupCard(root);
        homeSummary = navRow(role, roleHeader, PageGlyphs.HOME,
                "Make Control your home app", "default home app launcher role",
                () -> HomeRole.request(this, MainActivity.REQ_HOME_ROLE));

        // ---- Profile & backup ----
        View profileHeader = addGroup(root, "Profile & backup");
        LinearLayout profile = groupCard(root);
        profileSummary = navRow(profile, profileHeader, PageGlyphs.LOCK,
                "Profile & backup", "profile backup export import contacts name encrypted",
                () -> startActivity(new Intent(this, SettingsProfileActivity.class)));

        // ---- Browser ----
        View browserHeader = addGroup(root, "Browser");
        LinearLayout browser = groupCard(root);
        jsSwitch = switchRow(browser, browserHeader, PageGlyphs.BROWSER,
                "JavaScript", "browser javascript webview",
                Prefs.jsOn(this),
                on -> Profile.setJsEnabled(this, on));
        navRow(browser, browserHeader, PageGlyphs.BROWSER, "Bookmarks",
                "browser bookmarks saved pages",
                () -> startActivity(new Intent(this, BookmarksActivity.class)));

        // ---- Share ----
        View shareHeader = addGroup(root, "Share");
        LinearLayout share = groupCard(root);
        navRow(share, shareHeader, PageGlyphs.APPS, "Share an app",
                "share app apk nearby hotspot qr bluetooth quick share",
                () -> startActivity(new Intent(this, ShareAppActivity.class)));

        // ---- Updates (v1.27) ----
        // The whole update flow lives on this one row: it shows
        // the installed version, whether a newer one is
        // available, download progress, verification, and the
        // install step. No popups, no banners anywhere else.
        View updateHeader = addGroup(root, "Updates");
        LinearLayout update = groupCard(root);
        updateSummary = navRow(update, updateHeader, PageGlyphs.INFO,
                "Check for updates",
                "update version check download install new version apk",
                () -> UpdateManager.onRowTap(this));

        // ---- Dictionary (v1.29) ----
        // The word list behind message compression, fetched
        // from GitHub in the background. The row shows the
        // version, the word count, and the last-check
        // status; tapping checks now. Learned words stay
        // on this phone — nothing here uploads anything.
        View dictHeader = addGroup(root, "Dictionary");
        LinearLayout dict = groupCard(root);
        dictSummary = navRow(dict, dictHeader, PageGlyphs.INFO,
                "Word dictionary",
                "dictionary words list check download compress",
                () -> DictStore.checkNow(this::refreshSummariesIfAlive));

        // ---- About ----
        View aboutHeader = addGroup(root, "About");
        LinearLayout about = groupCard(root);
        navRow(about, aboutHeader, PageGlyphs.INFO, "About Control",
                "about version build notes",
                () -> startActivity(new Intent(this, SettingsAboutActivity.class)));

        refreshSummaries();
    }

    // ---- row builders ----

    private TextView lastRowSummary;

    private TextView lastSummary(LinearLayout group) {
        return lastRowSummary;
    }

    private View addGroup(LinearLayout root, String title) {
        TextView h = Ui.sectionLabel(this, title);
        root.addView(h, Ui.wrap(0, 16, 0, 6, this));
        return h;
    }

    private LinearLayout groupCard(LinearLayout root) {
        LinearLayout card = Ui.column(this);
        card.setBackground(Ui.roundedStroke(this, Ui.SURFACE, 16, Ui.LINE));
        root.addView(card);
        return card;
    }

    private void addDividerIfNeeded(LinearLayout group) {
        if (group.getChildCount() > 0) {
            View d = new View(this);
            d.setBackgroundColor(Ui.LINE);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, 1);
            lp.setMargins(Ui.dp(this, 52), 0, Ui.dp(this, 12), 0);
            group.addView(d, lp);
        }
    }

    private ImageView glyphIcon(int glyph) {
        int sz = Ui.dp(this, 24);
        Bitmap bmp = Bitmap.createBitmap(sz, sz, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bmp);
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setColor(Ui.DIM);
        paint.setStrokeWidth(sz * 0.09f);
        PageGlyphs.draw(canvas, glyph, sz / 2f, sz / 2f, sz * 0.92f, paint);
        ImageView iv = new ImageView(this);
        iv.setImageBitmap(bmp);
        return iv;
    }

    /** A navigation row; returns its summary TextView. */
    private TextView navRow(LinearLayout group, View header, int glyph,
                            String title, String search, Runnable onClick) {
        addDividerIfNeeded(group);
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setMinimumHeight(Ui.dp(this, 56));
        row.setPadding(Ui.dp(this, 14), Ui.dp(this, 6), Ui.dp(this, 10), Ui.dp(this, 6));
        row.addView(glyphIcon(glyph), new LinearLayout.LayoutParams(
                Ui.dp(this, 24), Ui.dp(this, 24)));
        LinearLayout texts = Ui.column(this);
        TextView t = Ui.text(this, title, 15, Ui.TEXT);
        texts.addView(t);
        TextView s = Ui.text(this, "", 12, Ui.DIM);
        s.setSingleLine(true);
        s.setEllipsize(android.text.TextUtils.TruncateAt.END);
        texts.addView(s);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        tlp.setMargins(Ui.dp(this, 14), 0, Ui.dp(this, 8), 0);
        row.addView(texts, tlp);
        TextView chevron = Ui.text(this, "›", 22, Ui.DIM);
        row.addView(chevron);
        row.setOnClickListener(v -> onClick.run());
        group.addView(row);
        registerRow(row, header, title + " " + search);
        lastRowSummary = s;
        return s;
    }

    /** A row with a real switch on it; returns the Switch. */
    private Switch switchRow(LinearLayout group, View header, int glyph,
                             String title, String search, boolean initial,
                             java.util.function.Consumer<Boolean> onChange) {
        addDividerIfNeeded(group);
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setMinimumHeight(Ui.dp(this, 56));
        row.setPadding(Ui.dp(this, 14), Ui.dp(this, 6), Ui.dp(this, 10), Ui.dp(this, 6));
        row.addView(glyphIcon(glyph), new LinearLayout.LayoutParams(
                Ui.dp(this, 24), Ui.dp(this, 24)));
        LinearLayout texts = Ui.column(this);
        texts.addView(Ui.text(this, title, 15, Ui.TEXT));
        TextView s = Ui.text(this, "", 12, Ui.DIM);
        s.setSingleLine(true);
        s.setEllipsize(android.text.TextUtils.TruncateAt.END);
        texts.addView(s);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        tlp.setMargins(Ui.dp(this, 14), 0, Ui.dp(this, 8), 0);
        row.addView(texts, tlp);
        Switch sw = new Switch(this);
        sw.setChecked(initial);
        sw.setOnCheckedChangeListener((btn, on) -> {
            if (!syncingSwitches) onChange.accept(on);
        });
        row.addView(sw);
        group.addView(row);
        registerRow(row, header, title + " " + search);
        lastRowSummary = s;
        return sw;
    }

    private void registerRow(View row, View header, String search) {
        HubRow hr = new HubRow();
        hr.row = row;
        hr.header = header;
        hr.search = search.toLowerCase(java.util.Locale.US);
        hubRows.add(hr);
    }

    /** Search: show only matching rows; hide group headers (and
     *  their cards, via emptiness) with no matches. */
    private void applyFilter(String query) {
        String q = query.trim().toLowerCase(java.util.Locale.US);
        java.util.Map<View, Boolean> headerHasMatch = new java.util.HashMap<>();
        for (HubRow hr : hubRows) {
            boolean show = q.isEmpty() || hr.search.contains(q);
            hr.row.setVisibility(show ? View.VISIBLE : View.GONE);
            if (show) headerHasMatch.put(hr.header, Boolean.TRUE);
        }
        java.util.Set<View> rowSet = new java.util.HashSet<>();
        java.util.Set<View> cards = new java.util.HashSet<>();
        for (HubRow hr : hubRows) {
            rowSet.add(hr.row);
            cards.add((View) hr.row.getParent());
        }
        for (HubRow hr : hubRows) {
            hr.header.setVisibility(
                    headerHasMatch.containsKey(hr.header) ? View.VISIBLE : View.GONE);
            View card = (View) hr.row.getParent();
            // A card with no visible rows collapses via its rows;
            // hide the card itself when the header hides.
            if (!headerHasMatch.containsKey(hr.header)) {
                card.setVisibility(View.GONE);
            } else {
                card.setVisibility(View.VISIBLE);
            }
        }
        // Dividers belong to no row: hide them while a query is up.
        for (View card : cards) {
            if (card instanceof ViewGroup) {
                ViewGroup vg = (ViewGroup) card;
                for (int i = 0; i < vg.getChildCount(); i++) {
                    View child = vg.getChildAt(i);
                    if (!rowSet.contains(child)) {
                        child.setVisibility(q.isEmpty() ? View.VISIBLE : View.GONE);
                    }
                }
            }
        }
    }

    // ---- live summaries ----

    private String headerLine2(String fingerprintShort) {
        String key = fingerprintShort == null || fingerprintShort.isEmpty()
                ? "" : "ADB key " + fingerprintShort + " · ";
        return key + "Profile key " + Profile.relayShortFingerprint(this);
    }

    private void refreshSummaries() {
        syncingSwitches = true;
        try {
            if (backgroundSummary != null) {
                backgroundSummary.setText(Backgrounds.summary(this));
            }
            if (connectionsSummary != null) {
                connectionsSummary.setText(connectionsLine());
            }
            if (notifAccessSummary != null) {
                notifAccessSummary.setText("Listener "
                        + (listenerEnabled() ? "granted" : "not granted")
                        + " · " + HeldStore.count(this) + " held");
            }
            if (mutedSummary != null) {
                mutedSummary.setText(Profile.mutedApps(this).size() + " apps muted");
            }
            if (filterSwitch != null) {
                filterSwitch.setChecked(
                        Prefs.get(this).getBoolean(Prefs.KEY_FILTER_ON, false));
            }
            if (filterSummary != null) {
                boolean on = Prefs.get(this).getBoolean(Prefs.KEY_FILTER_ON, false);
                long blocked = Prefs.get(this).getLong(Prefs.KEY_BLOCKED_COUNT, 0);
                filterSummary.setText((on ? "On" : "Off") + " · "
                        + SettingsFilterActivity.privateDnsSummary(this)
                        + " · " + blocked + " blocked");
            }
            if (walkieSummary != null) {
                int n = Prefs.get(this).getInt(ChannelsActivity.PREF_LAST_CHANNEL, 1);
                JSONObject slot = Profile.slot(this, n);
                String name = slot == null ? "" : Profile.slotDisplayName(slot);
                walkieSummary.setText("Channel " + n
                        + (name.isEmpty() ? "" : " · " + name));
            }
            if (chatSummary != null) {
                chatSummary.setText("You are your profile key · "
                        + Profile.relayShortFingerprint(this)
                        + " — no account, no sign-in");
            }
            if (smsSwitch != null) {
                smsSwitch.setChecked(Profile.smsControlEnabled(this));
            }
            if (smsSummary != null) {
                smsSummary.setText(Profile.smsControlEnabled(this)
                        ? "On · secret " + (Profile.smsSecretHash(this).isEmpty()
                                ? "not set" : "set")
                        : "Off");
            }
            if (deepSummary != null) {
                String st = Prefs.get(this)
                        .getString(Prefs.KEY_BRIDGE_STATE, "");
                deepSummary.setText(st == null || st.isEmpty()
                        ? "Bridge state unknown — open to check" : st);
            }
            if (watchdogSummary != null) {
                watchdogSummary.setText(BridgeWatchdogService.stateLine(this));
            }
            if (overlaySwitch != null) {
                overlaySwitch.setChecked(OverlayRingService.switchOn(this));
            }
            if (overlaySummary != null) {
                String line2 = OverlayRingService.statusLine2(this);
                overlaySummary.setText(OverlayRingService.stateLine(this)
                        + (line2.isEmpty() ? "" : "\n" + line2));
            }
            if (overlayPermissionSummary != null) {
                overlayPermissionSummary.setText(OverlayRingService.overlayGranted(this)
                        ? "Granted — the ring can open over other apps"
                        : "Not granted — tap to allow Control to draw over other apps");
            }
            if (bridgeRestartSummary != null) {
                bridgeRestartSummary.setText(bridgeRestartLine());
            }
            if (adminSummary != null) {
                adminSummary.setText(AdminControl.isActive(this)
                        ? "Active — LOCK locks the phone"
                        : "Not active");
            }
            if (homeSummary != null) {
                homeSummary.setText(HomeRole.isHeld(this)
                        ? "Control is your home app"
                        : "Not your home app yet — tap to set");
            }
            if (profileSummary != null) {
                profileSummary.setText(Profile.displayName(this)
                        + " · encrypted export / import");
            }
            if (dictSummary != null) {
                dictSummary.setText(DictStore.statusLine());
            }
            if (clockSwitch != null) clockSwitch.setChecked(Prefs.showClock(this));
            if (themeSwitch != null) themeSwitch.setChecked(Ui.isLightTheme(this));
            if (themeSummary != null) {
                themeSummary.setText(Ui.isLightTheme(this)
                        ? "Light — light grounds, same green"
                        : "Dark — the calm dark look");
            }
            if (quietSwitch != null) {
                quietSwitch.setChecked(
                        Prefs.get(this).getBoolean(Prefs.KEY_QUIET_ALL, false));
            }
            if (jsSwitch != null) jsSwitch.setChecked(Prefs.jsOn(this));
        } finally {
            syncingSwitches = false;
        }
    }

    private String connectionsLine() {
        StringBuilder sb = new StringBuilder();
        try {
            WifiManager wm = (WifiManager) getApplicationContext()
                    .getSystemService(WIFI_SERVICE);
            if (wm != null) {
                if (!wm.isWifiEnabled()) {
                    sb.append("Wi-Fi off");
                } else {
                    android.net.wifi.WifiInfo info = wm.getConnectionInfo();
                    String ssid = info == null ? null : info.getSSID();
                    if (ssid == null || ssid.isEmpty() || "<unknown ssid>".equals(ssid)) {
                        sb.append("Wi-Fi on");
                    } else {
                        sb.append(ssid.replace("\"", ""));
                    }
                }
            }
        } catch (Exception ignored) {
        }
        try {
            BluetoothAdapter bt = BluetoothAdapter.getDefaultAdapter();
            if (bt != null) {
                if (sb.length() > 0) sb.append(" · ");
                sb.append(bt.isEnabled() ? "BT on" : "BT off");
            }
        } catch (Exception ignored) {
        }
        boolean filterOn = Prefs.get(this).getBoolean(Prefs.KEY_FILTER_ON, false);
        if (sb.length() > 0) sb.append(" · ");
        sb.append(filterOn ? "filter on" : "filter off");
        return sb.toString();
    }

    private boolean listenerEnabled() {
        String flat = Settings.Secure.getString(getContentResolver(),
                "enabled_notification_listeners");
        if (flat == null) return false;
        ComponentName me = new ComponentName(this, NotificationHoldService.class);
        return flat.contains(me.flattenToString())
                || flat.contains(getPackageName());
    }

    /** Summary for the "Restart the bridge" row: what the
     *  tap will do, from the last fresh ping. */
    private String bridgeRestartLine() {
        switch (OverlayRingService.lastBridgeState) {
            case STALE:
                return "Bridge is running an old version ("
                        + BridgeGate.versionName(OverlayRingService.lastBridgeVersion)
                        + ") — tap to restart it with the current code";
            case CURRENT:
                return "Bridge is current ("
                        + BridgeGate.versionName(OverlayRingService.lastBridgeVersion)
                        + ") — no restart needed";
            case NOT_RUNNING:
                return "Bridge is not running — tap to start it";
            default:
                return "Checking the bridge…";
        }
    }

    /** One-tap stale-bridge restart (v1.15.1). The stale-aware
     *  path re-checks first: a current bridge is left alone, a
     *  down bridge is started, and a stale bridge is stopped
     *  and then started with the current code. Afterwards
     *  reconcile re-runs, so a current bridge starts the
     *  hold-anywhere watch. */
    private void restartBridgeForHoldAnywhere() {
        if (bridgeRestartSummary != null) {
            bridgeRestartSummary.setText("Restarting the bridge…");
        }
        Toast.makeText(this, "Restarting the bridge…", Toast.LENGTH_SHORT).show();
        AdbStarter.restartStaleBridge(this, new AdbStarter.Callback() {
            @Override public void onStatus(String line) {
                // Stage chatter belongs to Deep view's log, not
                // to this row; the completion toast carries the
                // outcome.
            }

            @Override public void onDone(boolean up, String summary) {
                runOnUiThread(() -> {
                    Toast.makeText(SettingsActivity.this, summary,
                            Toast.LENGTH_LONG).show();
                    OverlayRingService.reconcile(SettingsActivity.this,
                            SettingsActivity.this::refreshSummariesIfAlive);
                    refreshSummaries();
                });
            }
        });
    }

    /** refreshSummaries guarded for reconcile callbacks, which
     *  can land after the activity is gone. */
    private void refreshSummariesIfAlive() {
        if (isFinishing() || isDestroyed()) return;
        refreshSummaries();
    }

    private void confirmStopBridge() {
        new AlertDialog.Builder(this)
                .setTitle("Turn off deep control?")
                .setMessage("This asks Control's shell bridge to stop itself. Deep view and governed controls stop until you start the bridge again (and it always stops at reboot). The hold-anywhere menu stops too. Base Activity view keeps working.")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Turn off", (d, w) -> new Thread(() -> {
                    ShellBridgeClient.Response r = BridgeControl.stopBridge(
                            this, BridgeControl.ORIGIN_UI);
                    // Deep view is off (v1.16): clear the flag
                    // so the watchdog stops too and never
                    // restarts the bridge behind this choice.
                    Prefs.get(this).edit()
                            .putBoolean(Prefs.KEY_DEEP_VIEW_ON, false).apply();
                    BridgeWatchdogService.reconcile(this);
                    runOnUiThread(() -> {
                        Toast.makeText(this,
                                r.ok ? "Deep control is off."
                                        : "The bridge was not running, so deep control is already off.",
                                Toast.LENGTH_LONG).show();
                        OverlayRingService.reconcile(this, this::refreshSummariesIfAlive);
                        refreshSummaries();
                    });
                }, "hub-bridge-stop").start())
                .show();
    }

    private void requestSmsPermissionsIfNeeded() {
        java.util.List<String> need = new java.util.ArrayList<>();
        if (checkSelfPermission(android.Manifest.permission.RECEIVE_SMS)
                != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            need.add(android.Manifest.permission.RECEIVE_SMS);
        }
        if (checkSelfPermission(android.Manifest.permission.SEND_SMS)
                != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            need.add(android.Manifest.permission.SEND_SMS);
        }
        if (checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION)
                != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            need.add(android.Manifest.permission.ACCESS_FINE_LOCATION);
        }
        if (!need.isEmpty()) {
            requestPermissions(need.toArray(new String[0]), REQ_SMS);
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_VPN) {
            SettingsFilterActivity.onVpnResult(this, resultCode, this::refreshSummaries);
        }
        if (requestCode == MainActivity.REQ_HOME_ROLE) refreshSummaries();
    }

    @Override
    protected void onResume() {
        super.onResume();
        // Returning from the overlay-permission screen (or Deep
        // view) may have changed the gates — re-enforce them
        // (fresh ping), then refresh the summaries now from the
        // cached state and again when the ping lands. The
        // foreground visit also wakes the bridge watchdog: if
        // its backoff is exhausted, this is a retry trigger.
        OverlayRingService.reconcile(this, this::refreshSummariesIfAlive);
        BridgeWatchdogService.kick(this);
        refreshSummaries();
        // The update row drives itself through the manager's
        // listener: returning from the install-permission
        // screen re-renders it with the grant's new state.
        UpdateManager.setListener(line -> {
            if (updateSummary != null) updateSummary.setText(line);
        });
        UpdateManager.refreshRow(this);
    }

    @Override
    protected void onPause() {
        UpdateManager.setListener(null);
        super.onPause();
    }
}
