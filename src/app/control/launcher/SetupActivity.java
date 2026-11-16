package app.control.launcher;

import android.app.AppOpsManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.VpnService;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;

/**
 * First-run setup (v1.15), shown once on first launch, in four
 * steps: name, permissions, profile import, theme. It replaces
 * the old two-button FirstRunActivity.
 *
 * Rules, all deliberate:
 *  - Every step is skippable, and skipping advances exactly like
 *    finishing. Declining a permission never blocks completion;
 *    Settings remains the place to grant later.
 *  - The flow is resumable: the current step is persisted, so
 *    leaving setup (Back, or the activity dying) resumes at the
 *    same step next launch. Setup never traps the user — Back
 *    from the first step leaves setup for the normal launcher.
 *  - The completed flag (Prefs.KEY_FIRSTRUN_DONE, the same key
 *    the old first-run screen set) is written only when the
 *    theme step finishes. Existing installs already carry it,
 *    so upgrading never re-shows setup.
 *  - Import runs the existing ImportActivity and its code
 *    paths; importing replaces the whole profile, so the name
 *    typed in step 1 is superseded by the imported profile's
 *    name. The import step says so before the user taps.
 *  - Re-runnable from Settings ("Run setup again").
 */
public class SetupActivity extends ThemedActivity {

    private static final int REQ_SETUP_PERMS = 7501;
    private static final int REQ_SETUP_VPN = 7502;
    private static final int REQ_SETUP_IMPORT = 7503;
    private static final String STATE_STEP = "setup_step";

    private int step = SetupFlow.STEP_NAME;
    private LinearLayout root;
    private final List<PermRow> permRows = new ArrayList<>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (savedInstanceState != null) {
            step = SetupFlow.clampStep(savedInstanceState.getInt(STATE_STEP, step));
        } else {
            step = SetupFlow.clampStep(Prefs.get(this)
                    .getInt(Prefs.KEY_SETUP_STEP, SetupFlow.STEP_NAME));
        }
        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(Ui.BG);
        root = Ui.column(this);
        int pad = Ui.dp(this, 20);
        root.setPadding(pad, Ui.dp(this, 20), pad, Ui.dp(this, 24));
        scroll.addView(root);
        setContentView(scroll);
        showStep(step);
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putInt(STATE_STEP, step);
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshPermRows();
    }

    @Override
    public void onBackPressed() {
        if (step > SetupFlow.STEP_NAME) {
            showStep(SetupFlow.prevStep(step));
        } else {
            // Leaving setup early is allowed: the flag stays
            // unset, so setup shows again next launch, resuming
            // at the saved step.
            finish();
        }
    }

    // ---- steps ----

    private void showStep(int next) {
        step = SetupFlow.clampStep(next);
        Prefs.get(this).edit().putInt(Prefs.KEY_SETUP_STEP, step).apply();
        permRows.clear();
        root.removeAllViews();
        root.addView(Ui.section(this, "Setup · step " + (step + 1) + " of "
                + SetupFlow.STEP_COUNT));
        switch (step) {
            case SetupFlow.STEP_NAME: showNameStep(); break;
            case SetupFlow.STEP_PERMISSIONS: showPermissionsStep(); break;
            case SetupFlow.STEP_IMPORT: showImportStep(); break;
            case SetupFlow.STEP_THEME: showThemeStep(); break;
            default: showNameStep(); break;
        }
    }

    private void advance() {
        if (SetupFlow.completesSetup(step)) {
            finishSetup();
        } else {
            showStep(SetupFlow.nextStep(step));
        }
    }

    private void finishSetup() {
        Prefs.get(this).edit()
                .putBoolean(Prefs.KEY_FIRSTRUN_DONE, true)
                .remove(Prefs.KEY_SETUP_STEP)
                .apply();
        finish();
    }

    // ---- step 1: name ----

    private void showNameStep() {
        root.addView(Ui.screenTitle(this, "What should Control call you?"));
        root.addView(Ui.body(this,
                "This is the name other phones see in chat and on the "
                + "walkie-talkie — one name for both. It lives in your "
                + "profile, and you can change it later in Settings → "
                + "Profile & backup."));
        EditText nameField = Ui.input(this, "Your name");
        String stored = Profile.displayNameStored(this);
        if (!stored.isEmpty()) nameField.setText(stored);
        root.addView(nameField, Ui.wrap(0, 18, 0, 0, this));
        Button next = Ui.button(this, "Continue", true);
        next.setOnClickListener(v -> {
            String name = nameField.getText().toString().trim();
            if (!name.isEmpty()) {
                // The profile's one display name, written to the
                // same place every other screen reads it from.
                Profile.setDisplayName(this, name);
            }
            advance();
        });
        root.addView(next, Ui.wrap(0, 16, 0, 0, this));
        Button skip = Ui.button(this, "Skip for now", false);
        skip.setOnClickListener(v -> advance());
        root.addView(skip, Ui.wrap(0, 8, 0, 0, this));
    }

    // ---- step 2: permissions ----

    /** One grant: runtime permissions, or a special system
     *  grant that opens a system screen. */
    private static final class PermItem {
        final String title;
        final String why;
        final String[] runtimePerms;
        final int special; // 0 = runtime, else SPECIAL_*
        PermItem(String title, String why, String[] runtimePerms) {
            this.title = title;
            this.why = why;
            this.runtimePerms = runtimePerms;
            this.special = 0;
        }
        PermItem(String title, String why, int special) {
            this.title = title;
            this.why = why;
            this.runtimePerms = null;
            this.special = special;
        }
    }

    private static final int SPECIAL_LISTENER = 1;
    private static final int SPECIAL_USAGE = 2;
    private static final int SPECIAL_VPN = 3;
    private static final int SPECIAL_OVERLAY = 4;

    private static final class PermRow {
        PermItem item;
        TextView status;
        Button grant;
    }

    private List<PermItem> permItems() {
        List<PermItem> items = new ArrayList<>();
        items.add(new PermItem("Notifications",
                "Lets Control post its own alerts. Without it, you won't see "
                + "Control's alerts for chat, walkie, and mesh.",
                sdkPerms(new String[]{android.Manifest.permission.POST_NOTIFICATIONS}, 33)));
        items.add(new PermItem("Notification access",
                "Lets Control hold and mute other apps' notifications. "
                + "Without it, notification control and the Activity timeline stay empty.",
                SPECIAL_LISTENER));
        items.add(new PermItem("Microphone",
                "Walkie-talkie talk, listening for sound-shared keys, and "
                + "low-data transcription. Without it, walkie and sound sharing won't work.",
                new String[]{android.Manifest.permission.RECORD_AUDIO}));
        items.add(new PermItem("Camera",
                "Scanning QR codes for profile import. Without it, QR "
                + "scanning won't work — file import still does.",
                new String[]{android.Manifest.permission.CAMERA}));
        items.add(new PermItem("Location",
                "Android requires it for Wi-Fi Direct and mesh discovery. "
                + "Without it, the mesh won't find nearby phones.",
                new String[]{android.Manifest.permission.ACCESS_FINE_LOCATION,
                        android.Manifest.permission.ACCESS_COARSE_LOCATION}));
        items.add(new PermItem("Nearby devices",
                "Finding and talking to nearby phones over Wi-Fi and "
                + "Bluetooth. Without it, mesh and Bluetooth walkie won't work.",
                sdkPerms(new String[]{
                        android.Manifest.permission.NEARBY_WIFI_DEVICES,
                        android.Manifest.permission.BLUETOOTH_SCAN,
                        android.Manifest.permission.BLUETOOTH_CONNECT}, 31)));
        items.add(new PermItem("Phone state",
                "Shows your signal and network type on the Connections "
                + "screen. Without it, Connections shows less detail.",
                new String[]{android.Manifest.permission.READ_PHONE_STATE}));
        items.add(new PermItem("SMS — optional",
                "Only for the optional SMS remote control. Without it, SMS "
                + "remote control stays off; nothing else needs it.",
                new String[]{android.Manifest.permission.RECEIVE_SMS,
                        android.Manifest.permission.SEND_SMS}));
        items.add(new PermItem("Usage access",
                "Shows which apps you used, and for how long, in Activity. "
                + "Without it, the timeline has no app usage.",
                SPECIAL_USAGE));
        items.add(new PermItem("VPN permission",
                "The ad filter and mesh internet run through a local VPN on "
                + "this phone. Without it, both stay off.",
                SPECIAL_VPN));
        items.add(new PermItem("Draw over other apps",
                "The hold-anywhere ring opens over whatever app is showing. "
                + "Without it, the ring only works inside the launcher.",
                SPECIAL_OVERLAY));
        return items;
    }

    /** Drops permissions the platform doesn't know on this SDK
     *  (e.g. POST_NOTIFICATIONS below 33, nearby-devices below
     *  31): there is nothing to grant, so the row reads granted. */
    private String[] sdkPerms(String[] perms, int minSdk) {
        if (Build.VERSION.SDK_INT >= minSdk) return perms;
        return new String[0];
    }

    private void showPermissionsStep() {
        root.addView(Ui.screenTitle(this, "Permissions"));
        root.addView(Ui.body(this,
                "Control asks for each permission with the reason beside "
                + "it. Grant what you want, skip the rest — declining "
                + "never blocks setup, and Settings is always the place "
                + "to grant later."));
        for (PermItem item : permItems()) {
            root.addView(permRowView(item), Ui.wrap(0, 12, 0, 0, this));
        }
        Button next = Ui.button(this, "Continue", true);
        next.setOnClickListener(v -> advance());
        root.addView(next, Ui.wrap(0, 20, 0, 0, this));
        Button skip = Ui.button(this, "Skip all", false);
        skip.setOnClickListener(v -> advance());
        root.addView(skip, Ui.wrap(0, 8, 0, 0, this));
        refreshPermRows();
    }

    private View permRowView(PermItem item) {
        LinearLayout card = Ui.card(this);
        int pad = Ui.dp(this, 12);
        card.setPadding(pad, pad, pad, pad);
        card.addView(Ui.text(this, item.title, 16, Ui.TEXT));
        TextView why = Ui.body(this, item.why);
        card.addView(why, Ui.wrap(0, 4, 0, 0, this));
        PermRow row = new PermRow();
        row.item = item;
        row.status = Ui.text(this, "", 13, Ui.DIM);
        card.addView(row.status, Ui.wrap(0, 6, 0, 0, this));
        row.grant = Ui.smallButton(this, "Grant");
        row.grant.setOnClickListener(v -> grant(item));
        card.addView(row.grant);
        permRows.add(row);
        return card;
    }

    private void refreshPermRows() {
        for (PermRow row : permRows) {
            boolean granted = isGranted(row.item);
            row.status.setText(granted ? "Granted" : "Not granted");
            row.status.setTextColor(granted ? Ui.ACCENT : Ui.DIM);
            row.grant.setVisibility(granted ? View.GONE : View.VISIBLE);
        }
    }

    private boolean isGranted(PermItem item) {
        if (item.special == SPECIAL_LISTENER) return listenerEnabled();
        if (item.special == SPECIAL_USAGE) return usageAccessEnabled();
        if (item.special == SPECIAL_VPN) return MeshInternet.vpnConsentGranted(this);
        if (item.special == SPECIAL_OVERLAY) return OverlayRingService.overlayGranted(this);
        if (item.runtimePerms == null) return true;
        for (String p : item.runtimePerms) {
            if (checkSelfPermission(p) != PackageManager.PERMISSION_GRANTED) return false;
        }
        return true;
    }

    /** The existing grant flows, reused: the same runtime
     *  request mechanism and the same system deep links the rest
     *  of the app uses. No parallel grant paths. */
    private void grant(PermItem item) {
        try {
            switch (item.special) {
                case SPECIAL_LISTENER:
                    startActivity(new Intent(
                            Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS));
                    return;
                case SPECIAL_USAGE:
                    startActivity(new Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS));
                    return;
                case SPECIAL_OVERLAY:
                    startActivity(OverlayRingService.overlaySettingsIntent(this));
                    return;
                case SPECIAL_VPN: {
                    Intent consent = VpnService.prepare(this);
                    if (consent == null) {
                        refreshPermRows();
                    } else {
                        startActivityForResult(consent, REQ_SETUP_VPN);
                    }
                    return;
                }
                default:
                    break;
            }
            List<String> need = new ArrayList<>();
            if (item.runtimePerms != null) {
                for (String p : item.runtimePerms) {
                    if (checkSelfPermission(p) != PackageManager.PERMISSION_GRANTED) {
                        need.add(p);
                    }
                }
            }
            if (!need.isEmpty()) {
                requestPermissions(need.toArray(new String[0]), REQ_SETUP_PERMS);
            } else {
                refreshPermRows();
            }
        } catch (Exception e) {
            Toast.makeText(this, "Couldn't open that grant — "
                    + "you can grant it later in Settings.",
                    Toast.LENGTH_LONG).show();
        }
    }

    private boolean listenerEnabled() {
        try {
            String flat = Settings.Secure.getString(getContentResolver(),
                    "enabled_notification_listeners");
            if (flat == null) return false;
            ComponentName me = new ComponentName(this, NotificationHoldService.class);
            return flat.contains(me.flattenToString())
                    || flat.contains(getPackageName());
        } catch (Exception e) {
            return false;
        }
    }

    private boolean usageAccessEnabled() {
        try {
            AppOpsManager ops = (AppOpsManager) getSystemService(Context.APP_OPS_SERVICE);
            int mode = ops.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS,
                    android.os.Process.myUid(), getPackageName());
            return mode == AppOpsManager.MODE_ALLOWED;
        } catch (Exception e) {
            return false;
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions,
                                           int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_SETUP_PERMS) refreshPermRows();
    }

    // ---- step 3: import ----

    private void showImportStep() {
        root.addView(Ui.screenTitle(this, "Bring your profile?"));
        root.addView(Ui.body(this,
                "Moving from another phone? Import the profile you "
                + "exported there — your home screen, filters, contacts "
                + "and chat account come with it. Importing replaces "
                + "this phone's current profile, including the name from "
                + "the last step: the imported profile's name wins."));
        Button importBtn = Ui.button(this, "Import your profile", true);
        importBtn.setOnClickListener(v -> startActivityForResult(
                new Intent(this, ImportActivity.class), REQ_SETUP_IMPORT));
        root.addView(importBtn, Ui.wrap(0, 18, 0, 0, this));
        Button fresh = Ui.button(this, "Start fresh", false);
        fresh.setOnClickListener(v -> {
            // A fresh profile if none exists yet (the name step
            // may already have written one).
            Profile.save(this);
            advance();
        });
        root.addView(fresh, Ui.wrap(0, 8, 0, 0, this));
    }

    // ---- step 4: theme ----

    private void showThemeStep() {
        root.addView(Ui.screenTitle(this, "Light or dark?"));
        root.addView(Ui.body(this,
                "Pick how Control looks. You can switch any time in "
                + "Settings → Appearance."));
        boolean light = Ui.isLightTheme(this);
        root.addView(themeCard(UiTheme.DARK, "Dark",
                "The calm dark look Control has always had.", !light),
                Ui.wrap(0, 16, 0, 0, this));
        root.addView(themeCard(UiTheme.LIGHT, "Light",
                "Light grounds, same green.", light),
                Ui.wrap(0, 10, 0, 0, this));
        Button done = Ui.button(this, "Done — open Control", true);
        done.setOnClickListener(v -> finishSetup());
        root.addView(done, Ui.wrap(0, 20, 0, 0, this));
    }

    /** A theme preview rendered from that set's own tokens, so
     *  both previews are true to their theme whatever is active. */
    private View themeCard(UiTheme t, String name, String blurb, boolean selected) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(Ui.roundedStroke(this, t.bg, 14,
                selected ? t.accent : t.line));
        int pad = Ui.dp(this, 14);
        card.setPadding(pad, pad, pad, pad);
        LinearLayout inner = new LinearLayout(this);
        inner.setOrientation(LinearLayout.VERTICAL);
        inner.setBackground(Ui.roundedStroke(this, t.surface, 9, t.line));
        inner.setPadding(pad, Ui.dp(this, 10), pad, Ui.dp(this, 10));
        inner.addView(Ui.text(this, name, 17, t.text));
        inner.addView(Ui.text(this, blurb, 13, t.dim));
        Button sample = new Button(this);
        sample.setText("Button");
        sample.setAllCaps(false);
        sample.setTextSize(14);
        sample.setMinHeight(Ui.dp(this, 40));
        sample.setBackground(Ui.rounded(this, t.accent, 9));
        sample.setTextColor(t.accentInk);
        inner.addView(sample, Ui.wrap(0, 8, 0, 0, this));
        card.addView(inner);
        if (selected) {
            card.addView(Ui.text(this, "Selected", 13, t.accent),
                    Ui.wrap(0, 8, 0, 0, this));
        }
        card.setOnClickListener(v -> {
            boolean wantLight = (t == UiTheme.LIGHT);
            if (wantLight != Ui.isLightTheme(this)) {
                Prefs.get(this).edit()
                        .putBoolean(Prefs.KEY_THEME_LIGHT, wantLight).apply();
                Ui.refreshTheme(this);
                recreate();
            }
        });
        return card;
    }

    // ---- results ----

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_SETUP_VPN) {
            refreshPermRows();
        } else if (requestCode == REQ_SETUP_IMPORT && resultCode == RESULT_OK) {
            // The import set the completed flag (its standalone
            // behavior); setup isn't complete until the theme
            // step finishes, so clear it until then. The
            // imported profile is in place; continue to theme.
            Prefs.get(this).edit()
                    .putBoolean(Prefs.KEY_FIRSTRUN_DONE, false).apply();
            showStep(SetupFlow.STEP_THEME);
        }
    }
}
