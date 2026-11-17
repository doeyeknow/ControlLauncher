package app.control.launcher;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ContentValues;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.provider.Settings;
import android.util.Base64;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.PopupMenu;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Governed bridge controls. Every button names one bridge operation;
 * every execution is confirmed first and audited through BridgeControl.
 *
 * v1.33 redesign: the screen is compact sections behind disclosures
 * instead of a wall of text — status, target, running processes,
 * and each control group opens with one tap, and nothing was
 * removed. The running-processes section lists live processes from
 * the bridge's PROCESSES op; long-pressing one offers Details,
 * Kill, and Disable/Enable through the same governed paths as the
 * buttons below (see ProcessRules for the shaping rules).
 *
 * v1.34: the processes section gains a filter control (All /
 * Can control / System) that separates the processes the user
 * can act on from system processes, using ProcessRules' group
 * classification. The filter only changes which rows are shown;
 * it never changes what a row can do.
 *
 * v1.35: the bridge expansion adds seven sections — Apps
 * (uninstall-for-user, removed apps, APK install, suspend,
 * trim caches), Display (window size/density/overscan with
 * originals + reset, the eight-key governed settings editor,
 * night mode), Input (fixed key menu, tap, swipe, type),
 * Capture (screenshot, screen recording), Power &amp;
 * scheduling (Doze, simulated battery, standby buckets, run
 * job), System config (device_config, Wi-Fi networks, users),
 * and Inspect (fixed-menu dumpsys, bugreport). Every write
 * confirms and audits, except input injections: confirming
 * every tap would make them unusable, and a confirm dialog
 * would show typed text on screen — so injections are the
 * deliberate exception, audit-logged, with the section copy
 * saying they act as the user's own touches. Reset actions
 * (display originals, battery reset, Doze unforce) need no
 * confirm: they restore the known-good state.
 *
 * v1.36: the Protected packages section holds the override
 * switch (default OFF, persisted, strong-confirmed to enable,
 * unlocked only on a CURRENT bridge). While it is on,
 * disable/enable is permitted on refuse-listed packages with a
 * per-action strong confirmation naming the consequence and the
 * recovery path; Control itself is never offered and stays
 * refused. The process menu hides Disable/Enable on protected
 * rows while the override is off and names the risk while it
 * is on. See ProtectedOverride for the decisions and wording.
 *
 * v1.37: the change journal (see ChangeJournal) and the
 * single-scroll layout — every group visible in one long page.
 *
 * v1.38 (REVERSAL of the v1.37 layout, on Adam's word): the one
 * long page had way too much scrolling, so Deep view is a hub
 * plus one page per group. The hub lists the groups as short
 * rows in the same order; tapping one opens only that group's
 * page; back returns to the hub. The Result card travels with
 * the page where a control ran. Bridge status and the Protected
 * packages state stay visible on the hub. Text rule, absolute:
 * no paragraphs of body text on any page — one short header
 * line per section plus the controls, and every longer
 * explanation lives ONLY behind that section's "?" modal
 * (scrollable, via the platform message area). The System
 * config flags UI gains a namespace picker (one namespace at a
 * time, alphabetical, last choice persisted) and toggle
 * switches on flags whose value is exactly "true"/"false";
 * other values keep the long-press menu. Navigation and
 * presentation only — controls, confirmations, refuse-lists,
 * audit, and journaling behave exactly as before.
 *
 * v1.39: the hub looks like the main Settings window — one
 * rounded card of icon rows (24dp PageGlyphs, title, chevron,
 * 56dp, dividers), same group order, tapping a row opens that
 * group's page. Above the rows, a status header: device meters
 * (memory, battery, storage, data, processing — all read
 * app-side, so they work with the bridge off; see
 * DeviceMeters/DeviceMeterReads), then the bridge status row,
 * the stale-restart row, and the Protected packages switch row.
 * The switch keeps its exact v1.36 behavior; it lives on the
 * hub now, and the Protected packages page keeps its status
 * line and "?" explanation. Group pages are otherwise
 * untouched, and the no-long-text rule stands. No protocol
 * change — no bridge restart needed after installing.
 */
public class DeepControlActivity extends ThemedActivity {
    /** Most process rows ever rendered at once; ps order is kept
     *  and the status line says when the list was capped. */
    private static final int MAX_PROC_ROWS = 300;

    /** Hub + pages (v1.38): the hub lists the groups as short
     *  rows; each group opens its own page. Groups are built
     *  once in onCreate and staged in {@link #pages}; showing
     *  a page re-parents that group's card into the page
     *  holder, so every control keeps its state. */
    private ScrollView hubScroll;
    private ScrollView pageScroll;
    private LinearLayout pageHolder;
    private TextView pageTitleView;
    /** -1 while the hub is showing, else the page index. */
    private int currentPage = -1;
    /** One staged group card per addGroup call, in build
     *  order — the hub rows and the pages share this list. */
    private final List<LinearLayout> pageCards = new ArrayList<>();
    private final List<String> pageTitles = new ArrayList<>();
    /** The Result card travels with the page where a control
     *  ran; it is the last group built. */
    private LinearLayout resultCard;
    /** The Protected packages switch row's summary on the hub:
     *  On/Off, or the short gate reason while the bridge is not
     *  current. */
    private TextView hubProtectedLine;
    /** The Bridge row's summary on the hub: the ping text,
     *  including the shell UID — it wraps to two lines so the
     *  UID is never cut off. */
    private TextView bridgeStatus;
    /** The stale-restart row container (divider + row), visible
     *  only while the bridge reports old code. */
    private LinearLayout staleRow;
    /** Device meters (v1.39), in DeviceMeterReads order:
     *  memory, battery, storage, data, processing. */
    private static final String[] METER_LABELS =
            {"Memory", "Battery", "Storage", "Data", "Processing"};
    private ProgressBar[] meterBars;
    private TextView[] meterNumbers;
    /** The change journal (v1.37): one app-private file, never
     *  part of the portable profile export. Loaded in onCreate. */
    private ChangeJournal journal;
    private EditText packageInput;
    private TextView targetStatus;
    private EditText dnsInput;
    private TextView output;
    private TextView procStatus;
    private LinearLayout procRows;
    private boolean procLoading;
    /** Set once the first auto-load has run; Refresh buttons
     *  reload after that. */
    private boolean initialLoadDone;
    /** Last fetched process list, in ps order; null until the
     *  first successful load. The filter renders from this —
     *  switching groups never re-fetches. */
    private List<ProcessRules.Row> procLoaded;
    /** Current filter selection; null means All (the default).
     *  Not persisted — the section always opens on All. */
    private ProcessRules.Group procFilter;
    private Button filterAll;
    private Button filterControllable;
    private Button filterSystem;
    private final Handler ui = new Handler(Looper.getMainLooper());

    // ---- v1.36: protected-package override ----
    /** The override section, switch, and status line. The
     *  section header names the switch state (ON/OFF) so the
     *  state is plain even while the section is collapsed. */
    /** The override group header, which names the switch
     *  state (ON/OFF) so it is plain at a glance. */
    private TextView protectedHeader;
    private Switch protectedSwitch;
    private TextView protectedStatus;
    /** Last bridge gate state from ping(), UNKNOWN until the
     *  first ping completes. The override switch unlocks only
     *  on CURRENT: a stale bridge refuses protected disables
     *  anyway, and the switch must not promise otherwise. */
    private BridgeGate.State lastBridgeState = BridgeGate.State.UNKNOWN;
    /** True while the switch is being set programmatically,
     *  so the listener does not re-run the toggle path. */
    private boolean syncingProtectedSwitch;

    // ---- v1.35: Apps section ----
    private LinearLayout removedRows;
    private TextView removedStatus;
    private static final int REQ_PICK_APK = 4101;
    private static final String APK_MIME = "application/vnd.android.package-archive";
    /** Staged install state: the MediaStore entry the bridge
     *  reads, the cache copy inspected before confirming, the
     *  shell-visible staged path, and the package the APK
     *  declares. All cleared after the attempt or on cancel. */
    private Uri stagedApkUri;
    private File stagedCacheFile;
    private String stagedApkPath;
    private String stagedApkPkg;

    // ---- v1.35: Display section ----
    private TextView wmStatus;
    private TextView wmOriginals;
    private Button wmResetButton;
    /** Current-value lines for the settings editor, parallel
     *  to BridgeOps.SETTINGS. */
    private TextView[] settingValues;
    /** Per-key Restore buttons, parallel to settingValues:
     *  visible only while a recorded default exists. */
    private Button[] settingRestoreButtons;
    private TextView nightStatus;
    /** Last good display state, for prefilling the set dialogs. */
    private BridgeOps.WmState lastWmState;

    // ---- v1.35: Input section ----
    private TextView inputBounds;
    private EditText tapXInput;
    private EditText tapYInput;
    private EditText swipeX1Input;
    private EditText swipeY1Input;
    private EditText swipeX2Input;
    private EditText swipeY2Input;
    private EditText swipeMsInput;
    private EditText typeTextInput;

    // ---- v1.35: Capture section ----
    private TextView shotStatus;
    private Button shotViewButton;
    private Button shotShareButton;
    /** The latest screenshot file (app-private); only the
     *  latest is kept. */
    private File lastScreenshot;
    private TextView recordStatus;
    private Button recordStopButton;
    private boolean recordPolling;
    private final Runnable recordPoller = this::pollRecordStatus;

    // ---- v1.35: Power & scheduling section ----
    private TextView dozeStatus;
    private TextView batteryStatus;
    private EditText batteryLevelInput;
    private TextView standbyStatus;
    private EditText jobIdInput;

    // ---- v1.35: System config section ----
    private EditText dcNamespaceInput;
    private EditText dcKeyInput;
    private EditText dcValueInput;
    private TextView dcValueView;
    /** Namespace picker (v1.38): one namespace at a time,
     *  alphabetical; the last choice persists in Prefs. */
    private Spinner flagNamespaceSpinner;
    private ArrayAdapter<String> flagNamespaceAdapter;
    /** True while the picker is being populated, so the
     *  selection callback does not reload mid-population. */
    private boolean populatingNamespaces;
    /** The namespace whose flags are currently listed. */
    private String currentFlagNamespace = "";
    /** Short honest note under the picker (caps only). */
    private TextView flagNote;
    private LinearLayout flagRows;
    private TextView flagStatus;
    private TextView flagRaw;
    private Button resetFlagsButton;
    private LinearLayout wifiRows;
    private TextView wifiStatus;
    private TextView wifiRaw;
    private LinearLayout userRows;
    private TextView userStatus;
    private EditText userNameInput;

    // ---- v1.37: Change history ----
    private LinearLayout historyRows;
    private TextView historyStatus;
    private Button restoreAllButton;

    // ---- v1.35: Inspect section ----
    private TextView dumpsysOutput;
    private String dumpsysFull = "";
    private TextView bugreportStatus;
    private Button bugreportStopButton;
    private boolean bugreportPolling;
    private final Runnable bugreportPoller = this::pollBugreportStatus;

    private interface BridgeCall {
        ShellBridgeClient.Response run();
    }

    /** One group in the single scrolling view (v1.37): a card
     *  with a short header line, an optional "?" button holding
     *  the longer explanation, and the controls. Groups are
     *  always visible — nothing hides behind a disclosure.
     *  {@code headerOut}, when given, receives the header view
     *  (the Protected packages header names the switch state).
     *  Returns the body column the controls attach to. */
    private LinearLayout addGroup(String title, String infoText) {
        return addGroup(title, infoText, null);
    }

    /** Builds one group card and STAGES it (v1.38): the card
     *  is not attached to any parent here — the hub lists the
     *  titles, and showing a page re-parents the card into the
     *  page holder. Build order is hub order, and the Result
     *  card (built last) travels with every page. */
    private LinearLayout addGroup(String title, String infoText,
                                  TextView[] headerOut) {
        LinearLayout card = Ui.card(this);
        int pad = Ui.dp(this, 12);
        card.setPadding(pad, Ui.dp(this, 10), pad, Ui.dp(this, 10));
        LinearLayout headerRow = Ui.row(this);
        TextView header = Ui.text(this, title, 17, Ui.TEXT);
        header.setTypeface(Typeface.create("sans-serif", Typeface.BOLD));
        headerRow.addView(header);
        if (headerOut != null && headerOut.length > 0) headerOut[0] = header;
        if (infoText != null) {
            TextView spacer = Ui.text(this, "", 15, Ui.TEXT);
            spacer.setLayoutParams(new LinearLayout.LayoutParams(0,
                    ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            headerRow.addView(spacer);
            Button info = Ui.smallButton(this, "?");
            info.setOnClickListener(v -> showInfo(title, infoText));
            headerRow.addView(info);
        }
        card.addView(headerRow);
        LinearLayout body = Ui.column(this);
        body.setBackgroundColor(Ui.SURFACE);
        card.addView(body);
        pageCards.add(card);
        pageTitles.add(title);
        return body;
    }

    /** The longer explanation for one group, behind its "?"
     *  button. Browsing copy stays terse; nothing essential is
     *  hidden — confirmations still name the action, target,
     *  consequence, and recovery exactly as before. */
    private void showInfo(String title, String infoText) {
        new AlertDialog.Builder(this)
                .setTitle(title)
                .setMessage(infoText)
                .setPositiveButton("Close", null)
                .show();
    }

    /** Scroll the current page so the Result card is visible.
     *  Only confirmed actions scroll; rapid-fire injections do
     *  not yank the view on every press. On the hub there is
     *  nothing to scroll — the Result card lives on pages. */
    private void scrollToResult() {
        if (currentPage < 0 || pageScroll == null || output == null) return;
        pageScroll.post(() -> {
            int[] loc = new int[2];
            output.getLocationOnScreen(loc);
            int[] scrollLoc = new int[2];
            pageScroll.getLocationOnScreen(scrollLoc);
            int y = pageScroll.getScrollY() + loc[1] - scrollLoc[1]
                    - Ui.dp(this, 12);
            pageScroll.smoothScrollTo(0, Math.max(0, y));
        });
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        journal = new ChangeJournal(new File(getFilesDir(), ChangeJournal.FILE_NAME));
        // Hub (v1.38): title, bridge status, and the group rows.
        // Group cards are staged by addGroup and shown one page
        // at a time; the hub itself is one screen of short rows.
        hubScroll = new ScrollView(this);
        hubScroll.setBackgroundColor(Ui.BG);
        LinearLayout hubRoot = Ui.column(this);
        int pad = Ui.dp(this, 18);
        hubRoot.setPadding(pad, Ui.dp(this, 18), pad, Ui.dp(this, 24));
        hubScroll.addView(hubRoot);

        LinearLayout titleRow = Ui.row(this);
        titleRow.addView(Ui.screenTitle(this, "Deep controls"));
        TextView titleSpacer = Ui.text(this, "", 15, Ui.TEXT);
        titleSpacer.setLayoutParams(new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        titleRow.addView(titleSpacer);
        Button aboutInfo = Ui.smallButton(this, "?");
        aboutInfo.setOnClickListener(v -> showInfo("About these controls",
                "These are governed writes, not a shell. Each one asks first, "
                + "names its target, is checked against a refuse-list, and is "
                + "written to the audit log. If the bridge is off, attempts are "
                + "still audited and nothing changes. Protected system packages "
                + "stay refused unless the Protected packages switch is on."));
        titleRow.addView(aboutInfo);
        hubRoot.addView(titleRow);

        // ---- v1.39: meters header + status card, Settings style ----
        hubRoot.addView(buildMetersCard(), Ui.wrap(0, 10, 0, 10, this));

        LinearLayout statusCard = hubCard();
        hubRoot.addView(statusCard, Ui.wrap(0, 0, 0, 10, this));
        bridgeStatus = hubStatusRow(statusCard, PageGlyphs.ACTIVITY, "Bridge",
                "Checking the bridge…");
        addHubDivider(statusCard);
        LinearLayout protectedHubRow = hubRowShell(PageGlyphs.SHIELD);
        LinearLayout protectedHubTexts = hubRowTexts(protectedHubRow,
                "Protected packages");
        hubProtectedLine = Ui.text(this, "", 12, Ui.DIM);
        hubProtectedLine.setSingleLine(true);
        hubProtectedLine.setEllipsize(android.text.TextUtils.TruncateAt.END);
        protectedHubTexts.addView(hubProtectedLine);
        protectedSwitch = new Switch(this);
        protectedSwitch.setChecked(overrideOn());
        protectedSwitch.setOnCheckedChangeListener((btn, on) -> {
            if (syncingProtectedSwitch) return;
            onProtectedToggle(on);
        });
        protectedHubRow.addView(protectedSwitch);
        statusCard.addView(protectedHubRow);

        // The stale-restart row lives inside the status card and
        // is visible only while the bridge reports old code; the
        // divider travels with it so no stray divider shows.
        staleRow = Ui.column(this);
        staleRow.setBackgroundColor(Ui.SURFACE);
        staleRow.setVisibility(View.GONE);
        addHubDivider(staleRow);
        LinearLayout restartRow = hubRowShell(PageGlyphs.SETTINGS);
        LinearLayout restartTexts = hubRowTexts(restartRow, "Restart bridge");
        TextView restartSummary = Ui.text(this,
                "Restart it once to get the current version.", 12, Ui.DIM);
        restartSummary.setSingleLine(true);
        restartSummary.setEllipsize(android.text.TextUtils.TruncateAt.END);
        restartTexts.addView(restartSummary);
        restartRow.addView(Ui.text(this, "›", 22, Ui.DIM));
        restartRow.setOnClickListener(v -> restartStaleBridge());
        staleRow.addView(restartRow);
        statusCard.addView(staleRow);

        // ---- v1.36: protected-package override ----
        TextView[] protectedHeaderOut = new TextView[1];
        LinearLayout protectedBody = addGroup("Protected packages",
                "System-critical packages — System UI, Settings, Phone, "
                + "Google Play services, and the rest of the refuse-list — "
                + "are refused by default. This switch allows disable and "
                + "enable on them, one strong confirmation per action. "
                + "Force-stop, clear-data, uninstall, and suspend stay "
                + "refused either way. Control itself can never be disabled.",
                protectedHeaderOut);
        protectedHeader = protectedHeaderOut[0];
        // v1.39: the switch itself lives on the hub (the status
        // card's switch row); this page keeps the status line,
        // the header state, and the "?" explanation.
        protectedBody.addView(Ui.body(this,
                "The switch lives on the Deep controls hub."));
        protectedStatus = Ui.body(this, "");
        protectedBody.addView(protectedStatus);
        refreshProtectedSwitch();

        LinearLayout target = addGroup("Target app",
                "The app the package-scoped controls act on. Pick a launchable "
                + "app, or type a package name and use it.");
        packageInput = Ui.input(this, "Package name, e.g. com.example.app");
        target.addView(packageInput);
        LinearLayout pickRow = Ui.row(this);
        Button pick = Ui.smallButton(this, "Pick a launchable app");
        pick.setOnClickListener(v -> pickApp());
        pickRow.addView(pick);
        Button inspect = Ui.smallButton(this, "Use typed package");
        inspect.setOnClickListener(v -> refreshTarget());
        pickRow.addView(inspect);
        target.addView(pickRow);
        targetStatus = Ui.body(this, "No target selected.");
        target.addView(targetStatus);

        LinearLayout procBody = addGroup("Running processes",
                "Live processes from the bridge. Tap one for details; "
                + "long-press for commands on it. The list loads on demand "
                + "and on Refresh — it never polls. Kill force-stops the "
                + "whole app: Android gives the shell user no per-process kill.");
        LinearLayout filterRow = Ui.row(this);
        filterAll = Ui.smallButton(this, "All");
        filterAll.setOnClickListener(v -> setProcFilter(null));
        filterRow.addView(filterAll);
        filterControllable = Ui.smallButton(this, "Can control");
        filterControllable.setOnClickListener(v -> setProcFilter(ProcessRules.Group.CONTROLLABLE));
        filterRow.addView(filterControllable);
        filterSystem = Ui.smallButton(this, "System");
        filterSystem.setOnClickListener(v -> setProcFilter(ProcessRules.Group.SYSTEM));
        filterRow.addView(filterSystem);
        procBody.addView(filterRow);
        updateFilterButtons();
        LinearLayout procRow = Ui.row(this);
        Button procRefresh = Ui.smallButton(this, "Refresh");
        procRefresh.setOnClickListener(v -> loadProcesses());
        procRow.addView(procRefresh);
        procStatus = Ui.body(this, "Not loaded yet.");
        procRow.addView(procStatus);
        procBody.addView(procRow);
        procRows = Ui.column(this);
        procRows.setBackgroundColor(Ui.SURFACE);
        procBody.addView(procRows);

        LinearLayout perms = addGroup("Permissions",
                "Grant or revoke one permission for the target app, through "
                + "the package manager. The current state is read first, so "
                + "the change can be restored from Change history.");
        LinearLayout permRow = Ui.row(this);
        Button grant = Ui.smallButton(this, "Grant…");
        grant.setOnClickListener(v -> choosePermission(true));
        permRow.addView(grant);
        Button revoke = Ui.smallButton(this, "Revoke…");
        revoke.setOnClickListener(v -> choosePermission(false));
        permRow.addView(revoke);
        perms.addView(permRow);

        LinearLayout appOps = addGroup("AppOps",
                "AppOps is Android's per-capability policy layer: camera, mic, "
                + "location, notifications, and more can be allowed, ignored, or denied.");
        Button appOp = Ui.button(this, "Set an AppOp…", false);
        appOp.setOnClickListener(v -> chooseAppOp());
        appOps.addView(appOp, Ui.wrap(0, 6, 0, 0, this));

        LinearLayout state = addGroup("App state",
                "Force-stop ends the app now. Disable / enable is reversible "
                + "and recorded in Change history. Clear data is destructive "
                + "and cannot be undone.");
        LinearLayout stateRow1 = Ui.row(this);
        Button forceStop = Ui.smallButton(this, "Force-stop");
        forceStop.setOnClickListener(v -> {
            String pkg = targetPackage();
            if (pkg == null) return;
            confirm(false, "Force-stop " + targetName(pkg) + "?",
                    "This stops the app now. It can be opened again normally.",
                    "Force-stop",
                    () -> BridgeControl.forceStop(this, BridgeControl.ORIGIN_UI, pkg));
        });
        stateRow1.addView(forceStop);
        Button toggleEnabled = Ui.smallButton(this, "Disable / enable…");
        toggleEnabled.setOnClickListener(v -> toggleEnabled());
        stateRow1.addView(toggleEnabled);
        state.addView(stateRow1);
        Button clear = Ui.button(this, "Clear app data…", false);
        clear.setOnClickListener(v -> {
            String pkg = targetPackage();
            if (pkg == null) return;
            confirmJournaled(true, "Clear all data for " + targetName(pkg) + "?",
                    "DESTRUCTIVE: this deletes the app's accounts, settings, files, "
                    + "and sign-ins stored on this phone. It cannot be undone.",
                    "Clear data",
                    cannotUndoDraft("CLEAR_APP_DATA", targetName(pkg),
                            "Deleted app data cannot be restored.", pkg, null, null, null),
                    () -> BridgeControl.clearAppData(this, BridgeControl.ORIGIN_UI, pkg));
        });
        state.addView(clear, Ui.wrap(0, 6, 0, 0, this));

        LinearLayout bg = addGroup("Background policy",
                "Restrict background data through Android's netpolicy, and "
                + "Doze exemptions through the device-idle whitelist. The "
                + "current policy cannot be read back, so these changes are "
                + "recorded without a restore — use the opposite action here "
                + "to undo one.");
        LinearLayout bgRow = Ui.row(this);
        Button restrict = Ui.smallButton(this, "Restrict background data");
        restrict.setOnClickListener(v -> {
            String pkg = targetPackage();
            if (pkg == null) return;
            confirmJournaled(false, "Restrict background data for " + targetName(pkg) + "?",
                    "Android netpolicy will put this app's UID on the restrict-background blacklist.",
                    "Restrict",
                    cannotUndoDraft("SET_BACKGROUND_DATA", targetName(pkg),
                            "The previous policy could not be read — to undo, use "
                            + "Allow background data here.", pkg, null, null, null),
                    () -> BridgeControl.setBackgroundData(this, BridgeControl.ORIGIN_UI, pkg, false));
        });
        bgRow.addView(restrict);
        Button allow = Ui.smallButton(this, "Allow background data");
        allow.setOnClickListener(v -> {
            String pkg = targetPackage();
            if (pkg == null) return;
            confirmJournaled(false, "Allow background data for " + targetName(pkg) + "?",
                    "Android netpolicy will remove this app's UID from the restrict-background blacklist.",
                    "Allow",
                    cannotUndoDraft("SET_BACKGROUND_DATA", targetName(pkg),
                            "The previous policy could not be read — to undo, use "
                            + "Restrict background data here.", pkg, null, null, null),
                    () -> BridgeControl.setBackgroundData(this, BridgeControl.ORIGIN_UI, pkg, true));
        });
        bgRow.addView(allow);
        bg.addView(bgRow);
        LinearLayout idleRow = Ui.row(this);
        Button idleAdd = Ui.smallButton(this, "Exempt from Doze");
        idleAdd.setOnClickListener(v -> {
            String pkg = targetPackage();
            if (pkg == null) return;
            confirmJournaled(false, "Exempt " + targetName(pkg) + " from Doze battery limits?",
                    "The app is added to Android's device-idle whitelist.",
                    "Exempt",
                    cannotUndoDraft("SET_DEVICE_IDLE_WHITELIST", targetName(pkg),
                            "The previous whitelist state could not be read — to undo, "
                            + "use Remove Doze exemption here.", pkg, null, null, null),
                    () -> BridgeControl.setDeviceIdleWhitelist(this, BridgeControl.ORIGIN_UI, pkg, true));
        });
        idleRow.addView(idleAdd);
        Button idleRemove = Ui.smallButton(this, "Remove Doze exemption");
        idleRemove.setOnClickListener(v -> {
            String pkg = targetPackage();
            if (pkg == null) return;
            confirmJournaled(false, "Remove the Doze exemption for " + targetName(pkg) + "?",
                    "Normal Android battery optimization will apply again.",
                    "Remove",
                    cannotUndoDraft("SET_DEVICE_IDLE_WHITELIST", targetName(pkg),
                            "The previous whitelist state could not be read — to undo, "
                            + "use Exempt from Doze here.", pkg, null, null, null),
                    () -> BridgeControl.setDeviceIdleWhitelist(this, BridgeControl.ORIGIN_UI, pkg, false));
        });
        idleRow.addView(idleRemove);
        bg.addView(idleRow);

        LinearLayout dns = addGroup("Private DNS",
                "Writes Android's global Private DNS mode and hostname directly. "
                + "The current mode is read first, so the change can be "
                + "restored from Change history.");
        dnsInput = Ui.input(this, "Filtering resolver hostname");
        dnsInput.setText(BridgeControl.FILTER_DNS_HOSTNAME);
        dns.addView(dnsInput);
        LinearLayout dnsRow = Ui.row(this);
        Button dnsSet = Ui.smallButton(this, "Set filtered DNS");
        dnsSet.setOnClickListener(v -> {
            String host = dnsInput.getText().toString().trim();
            setPrivateDnsFlow("hostname", host, "Set Private DNS to " + host + "?",
                    "The bridge writes Android's global Private DNS mode and hostname directly.",
                    "Set DNS");
        });
        dnsRow.addView(dnsSet);
        Button dnsAuto = Ui.smallButton(this, "Automatic");
        dnsAuto.setOnClickListener(v -> setPrivateDnsFlow("opportunistic", "",
                "Set Private DNS to Automatic?",
                "Android will use opportunistic Private DNS where the network offers it.",
                "Set automatic"));
        dnsRow.addView(dnsAuto);
        Button dnsOff = Ui.smallButton(this, "Off");
        dnsOff.setOnClickListener(v -> setPrivateDnsFlow("off", "",
                "Turn Private DNS off?",
                "Lookups return to the network's normal resolvers.",
                "Turn off"));
        dnsRow.addView(dnsOff);
        dns.addView(dnsRow);

        LinearLayout setting = addGroup("Launcher system setting",
                "Screen timeout is also in the Display group's settings editor, which "
                + "covers eight governed keys with fixed choices — font scale, animation "
                + "speeds, brightness, auto-rotate, and this one. There is no arbitrary "
                + "settings endpoint.");
        Button timeout = Ui.button(this, "Set screen timeout…", false);
        timeout.setOnClickListener(v -> chooseTimeout());
        setting.addView(timeout, Ui.wrap(0, 6, 0, 0, this));

        LinearLayout apps = addGroup("Apps",
                "Uninstalling for user 0 removes the app for the owner only — its files stay "
                + "on the phone, and Restore below brings it back. Suspending stops an app "
                + "from running until it is unsuspended; some builds refuse it, and a "
                + "refusal is reported with Android's own words. Trim caches frees up to "
                + "1 GB of cached files; no app data is deleted and it cannot be undone.");
        LinearLayout appRow1 = Ui.row(this);
        Button uninstall = Ui.smallButton(this, "Uninstall for user 0…");
        uninstall.setOnClickListener(v -> uninstallTarget());
        appRow1.addView(uninstall);
        Button suspend = Ui.smallButton(this, "Suspend…");
        suspend.setOnClickListener(v -> suspendTarget(true));
        appRow1.addView(suspend);
        Button unsuspend = Ui.smallButton(this, "Unsuspend…");
        unsuspend.setOnClickListener(v -> suspendTarget(false));
        appRow1.addView(unsuspend);
        apps.addView(appRow1);
        LinearLayout appRow2 = Ui.row(this);
        Button trim = Ui.smallButton(this, "Free 1 GB of caches…");
        trim.setOnClickListener(v -> confirmJournaled(false, "Free up to 1 GB of app caches?",
                "Android trims cached files across apps until 1 GB is free or nothing "
                + "more can be trimmed. No app data is deleted.",
                "Trim caches",
                cannotUndoDraft("TRIM_CACHES", "Device caches",
                        "Freed cache space cannot be restored.", null, null, null, null),
                () -> BridgeControl.trimCaches(this, BridgeControl.ORIGIN_UI)));
        appRow2.addView(trim);
        Button installApk = Ui.smallButton(this, "Install an APK file…");
        installApk.setOnClickListener(v -> pickApk());
        appRow2.addView(installApk);
        apps.addView(appRow2);
        LinearLayout removedRow = Ui.row(this);
        Button removedRefresh = Ui.smallButton(this, "Refresh removed apps");
        removedRefresh.setOnClickListener(v -> loadRemoved());
        removedRow.addView(removedRefresh);
        removedStatus = Ui.body(this, "Not loaded yet.");
        removedRow.addView(removedStatus);
        apps.addView(removedRow);
        removedRows = Ui.column(this);
        removedRows.setBackgroundColor(Ui.SURFACE);
        apps.addView(removedRows);

        LinearLayout display = addGroup("Display",
                "Window size, density, and overscan. The first time this screen loads, "
                + "the phone's current values are recorded as the originals — Reset "
                + "restores exactly those, in one tap. System settings — eight governed "
                + "keys only, each with fixed choices. There is no free-form settings "
                + "endpoint. Every change here is recorded in Change history.");
        LinearLayout wmRow = Ui.row(this);
        Button wmRefresh = Ui.smallButton(this, "Refresh");
        wmRefresh.setOnClickListener(v -> loadDisplay());
        wmRow.addView(wmRefresh);
        wmResetButton = Ui.smallButton(this, "Reset to originals");
        wmResetButton.setOnClickListener(v -> resetDisplay());
        wmResetButton.setEnabled(false);
        wmRow.addView(wmResetButton);
        display.addView(wmRow);
        wmStatus = Ui.body(this, "Not loaded yet.");
        display.addView(wmStatus);
        wmOriginals = Ui.body(this, "");
        display.addView(wmOriginals);
        LinearLayout wmSetRow = Ui.row(this);
        Button setSize = Ui.smallButton(this, "Set size…");
        setSize.setOnClickListener(v -> setWmSize());
        wmSetRow.addView(setSize);
        Button setDensity = Ui.smallButton(this, "Set density…");
        setDensity.setOnClickListener(v -> setWmDensity());
        wmSetRow.addView(setDensity);
        Button setOverscan = Ui.smallButton(this, "Set overscan…");
        setOverscan.setOnClickListener(v -> setWmOverscan());
        wmSetRow.addView(setOverscan);
        display.addView(wmSetRow);
        settingValues = new TextView[BridgeOps.SETTINGS.length];
        settingRestoreButtons = new Button[BridgeOps.SETTINGS.length];
        for (int i = 0; i < BridgeOps.SETTINGS.length; i++) {
            final BridgeOps.SettingDef def = BridgeOps.SETTINGS[i];
            LinearLayout sRow = Ui.row(this);
            TextView label = Ui.body(this, def.label);
            sRow.addView(label);
            settingValues[i] = Ui.body(this, "…");
            sRow.addView(settingValues[i]);
            Button change = Ui.smallButton(this, "Change…");
            change.setOnClickListener(v -> chooseSetting(def));
            sRow.addView(change);
            Button restore = Ui.smallButton(this, "Restore");
            restore.setOnClickListener(v -> restoreSetting(def));
            restore.setVisibility(View.GONE);
            sRow.addView(restore);
            settingRestoreButtons[i] = restore;
            display.addView(sRow);
        }
        LinearLayout nightRow = Ui.row(this);
        nightStatus = Ui.body(this, "Night mode: not loaded yet.");
        nightRow.addView(nightStatus);
        display.addView(nightRow);
        LinearLayout nightBtns = Ui.row(this);
        Button nightDay = Ui.smallButton(this, "Day");
        nightDay.setOnClickListener(v -> setNightMode("no"));
        nightBtns.addView(nightDay);
        Button nightNight = Ui.smallButton(this, "Night");
        nightNight.setOnClickListener(v -> setNightMode("yes"));
        nightBtns.addView(nightNight);
        Button nightAuto = Ui.smallButton(this, "Automatic");
        nightAuto.setOnClickListener(v -> setNightMode("auto"));
        nightBtns.addView(nightAuto);
        display.addView(nightBtns);

        LinearLayout input = addGroup("Input",
                "These act as your own touches and key presses, on whatever is on screen. "
                + "There is no confirm per press — that would make them unusable — but every "
                + "injection is written to the audit log. Typed text is never logged: only "
                + "its length.");
        LinearLayout keyRow = null;
        for (int i = 0; i < BridgeOps.KEY_CODES.length; i++) {
            if (i % 4 == 0) {
                keyRow = Ui.row(this);
                input.addView(keyRow);
            }
            final int code = Integer.parseInt(BridgeOps.KEY_CODES[i][1]);
            final String label = BridgeOps.KEY_CODES[i][0];
            Button key = Ui.smallButton(this, label);
            key.setOnClickListener(v -> sendKey(code, label));
            keyRow.addView(key);
        }
        inputBounds = Ui.body(this, "");
        input.addView(inputBounds);
        LinearLayout tapRow = Ui.row(this);
        tapXInput = Ui.input(this, "X");
        tapXInput.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
        tapRow.addView(tapXInput);
        tapYInput = Ui.input(this, "Y");
        tapYInput.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
        tapRow.addView(tapYInput);
        Button tapButton = Ui.smallButton(this, "Tap");
        tapButton.setOnClickListener(v -> sendTap());
        tapRow.addView(tapButton);
        input.addView(tapRow);
        LinearLayout swipeRow1 = Ui.row(this);
        swipeX1Input = Ui.input(this, "From X");
        swipeX1Input.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
        swipeRow1.addView(swipeX1Input);
        swipeY1Input = Ui.input(this, "From Y");
        swipeY1Input.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
        swipeRow1.addView(swipeY1Input);
        swipeX2Input = Ui.input(this, "To X");
        swipeX2Input.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
        swipeRow1.addView(swipeX2Input);
        swipeY2Input = Ui.input(this, "To Y");
        swipeY2Input.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
        swipeRow1.addView(swipeY2Input);
        input.addView(swipeRow1);
        LinearLayout swipeRow2 = Ui.row(this);
        swipeMsInput = Ui.input(this, "Duration ms (100–5000)");
        swipeMsInput.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
        swipeMsInput.setText("300");
        swipeRow2.addView(swipeMsInput);
        Button swipeButton = Ui.smallButton(this, "Swipe");
        swipeButton.setOnClickListener(v -> sendSwipe());
        swipeRow2.addView(swipeButton);
        input.addView(swipeRow2);
        typeTextInput = Ui.input(this, "Text to type into the focused field");
        input.addView(typeTextInput);
        Button typeButton = Ui.button(this, "Type it", false);
        typeButton.setOnClickListener(v -> sendText());
        input.addView(typeButton, Ui.wrap(0, 6, 0, 0, this));

        LinearLayout capture = addGroup("Capture",
                "Screenshots stay in Control's private storage — only the latest is kept — "
                + "and open or share from there. Recordings go to Movies, where the gallery "
                + "shows them; that is how you get them back.");
        Button shotButton = Ui.button(this, "Take a screenshot", false);
        shotButton.setOnClickListener(v -> captureScreenshot());
        capture.addView(shotButton, Ui.wrap(0, 6, 0, 0, this));
        shotStatus = Ui.body(this, "No screenshot taken yet.");
        capture.addView(shotStatus);
        LinearLayout shotRow = Ui.row(this);
        shotViewButton = Ui.smallButton(this, "View");
        shotViewButton.setOnClickListener(v -> viewScreenshot());
        shotViewButton.setEnabled(false);
        shotRow.addView(shotViewButton);
        shotShareButton = Ui.smallButton(this, "Share");
        shotShareButton.setOnClickListener(v -> shareScreenshot());
        shotShareButton.setEnabled(false);
        shotRow.addView(shotShareButton);
        capture.addView(shotRow);
        LinearLayout recRow = Ui.row(this);
        Button recStart = Ui.smallButton(this, "Record screen…");
        recStart.setOnClickListener(v -> startRecording());
        recRow.addView(recStart);
        recordStopButton = Ui.smallButton(this, "Stop");
        recordStopButton.setOnClickListener(v -> stopRecording());
        recordStopButton.setEnabled(false);
        recRow.addView(recordStopButton);
        Button recCheck = Ui.smallButton(this, "Check status");
        recCheck.setOnClickListener(v -> pollRecordStatus());
        recRow.addView(recCheck);
        capture.addView(recRow);
        recordStatus = Ui.body(this, "No recording running.");
        capture.addView(recordStatus);

        LinearLayout power = addGroup("Power & scheduling",
                "Doze and battery simulation are testing tools: they change what Android "
                + "reports and when it sleeps. Reset returns everything to normal. "
                + "Find job ids in Inspect → dumpsys → jobscheduler. The job runs "
                + "immediately, ignoring its schedule and constraints.");
        LinearLayout dozeRow = Ui.row(this);
        Button dozeRefresh = Ui.smallButton(this, "Refresh state");
        dozeRefresh.setOnClickListener(v -> loadPower());
        dozeRow.addView(dozeRefresh);
        dozeStatus = Ui.body(this, "Doze state: not loaded yet.");
        dozeRow.addView(dozeStatus);
        power.addView(dozeRow);
        LinearLayout dozeBtns = Ui.row(this);
        Button forceIdle = Ui.smallButton(this, "Force idle");
        forceIdle.setOnClickListener(v -> dozeAction("force-idle"));
        dozeBtns.addView(forceIdle);
        Button forceInactive = Ui.smallButton(this, "Force inactive");
        forceInactive.setOnClickListener(v -> dozeAction("force-inactive"));
        dozeBtns.addView(forceInactive);
        Button dozeStep = Ui.smallButton(this, "Step");
        dozeStep.setOnClickListener(v -> dozeAction("step"));
        dozeBtns.addView(dozeStep);
        Button dozeUnforce = Ui.smallButton(this, "Return to normal");
        dozeUnforce.setOnClickListener(v -> runCall("Doze: return to normal",
                () -> BridgeControl.doze(this, BridgeControl.ORIGIN_UI, "unforce"),
                this::loadPower));
        dozeBtns.addView(dozeUnforce);
        power.addView(dozeBtns);
        batteryStatus = Ui.body(this, "Battery: not loaded yet.");
        power.addView(batteryStatus);
        LinearLayout battRow = Ui.row(this);
        batteryLevelInput = Ui.input(this, "Level 0–100");
        batteryLevelInput.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
        battRow.addView(batteryLevelInput);
        Button battSet = Ui.smallButton(this, "Simulate level");
        battSet.setOnClickListener(v -> setBatteryLevel());
        battRow.addView(battSet);
        power.addView(battRow);
        LinearLayout battRow2 = Ui.row(this);
        Button battUnplug = Ui.smallButton(this, "Simulate unplug");
        battUnplug.setOnClickListener(v -> batteryUnplugFlow());
        battRow2.addView(battUnplug);
        Button battReset = Ui.smallButton(this, "Reset battery");
        battReset.setOnClickListener(v -> runCall("Battery: reset to real state",
                () -> BridgeControl.batteryReset(this, BridgeControl.ORIGIN_UI),
                this::loadPower));
        battRow2.addView(battReset);
        power.addView(battRow2);
        standbyStatus = Ui.body(this, "Standby bucket: not loaded yet.");
        power.addView(standbyStatus);
        Button standbyButton = Ui.button(this, "Set standby bucket for the target app…", false);
        standbyButton.setOnClickListener(v -> chooseStandbyBucket());
        power.addView(standbyButton, Ui.wrap(0, 6, 0, 0, this));
        jobIdInput = Ui.input(this, "Job id (a whole number)");
        jobIdInput.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
        power.addView(jobIdInput);
        Button runJob = Ui.button(this, "Run the target app's job now…", false);
        runJob.setOnClickListener(v -> runJob());
        power.addView(runJob, Ui.wrap(0, 6, 0, 0, this));

        LinearLayout sysconfig = addGroup("System config",
                "System behavior flags (device_config), saved Wi-Fi networks, and users. "
                + "The picker shows one flag namespace at a time; switches appear only "
                + "on flags whose value is exactly true or false, and flipping one "
                + "takes the same strong confirmation, journaling, and re-read "
                + "verification as changing a value. Other flags show their value "
                + "with the long-press menu (Change value / Reset to recorded "
                + "default). Tap a flag to fill the form below with it. Flag writes "
                + "are strong-confirmed and verified by re-reading. \"Defaults\" "
                + "means the values recorded before this app first changed "
                + "each flag; flags the app never changed are untouched, and flags "
                + "changed outside the app can't be known. Saved Wi-Fi networks: "
                + "forgetting one removes it from the phone; there is no add-network "
                + "here — joining a network stays in Settings. Users: user 0 (the "
                + "owner) and the currently-running user can never be removed.");
        flagNamespaceSpinner = new Spinner(this);
        flagNamespaceAdapter = new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_dropdown_item, new ArrayList<String>());
        flagNamespaceSpinner.setAdapter(flagNamespaceAdapter);
        flagNamespaceSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                if (populatingNamespaces) return;
                String selected = flagNamespaceAdapter.getItem(position);
                if (selected == null || selected.equals(currentFlagNamespace)) return;
                currentFlagNamespace = selected;
                Prefs.get(DeepControlActivity.this).edit()
                        .putString(Prefs.KEY_LAST_FLAG_NAMESPACE, selected).apply();
                dcNamespaceInput.setText(selected);
                loadFlags();
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {}
        });
        sysconfig.addView(flagNamespaceSpinner);
        flagNote = Ui.text(this, "", 13, Ui.DIM);
        sysconfig.addView(flagNote);
        dcNamespaceInput = Ui.input(this, "Namespace, e.g. activity_manager");
        sysconfig.addView(dcNamespaceInput);
        LinearLayout dcListRow = Ui.row(this);
        Button dcList = Ui.smallButton(this, "List flags");
        dcList.setOnClickListener(v -> loadFlagNamespaces());
        dcListRow.addView(dcList);
        resetFlagsButton = Ui.smallButton(this, "Reset all flags to defaults");
        resetFlagsButton.setOnClickListener(v -> resetAllFlags());
        dcListRow.addView(resetFlagsButton);
        sysconfig.addView(dcListRow);
        flagStatus = Ui.body(this, "No flags listed yet.");
        sysconfig.addView(flagStatus);
        flagRows = Ui.column(this);
        flagRows.setBackgroundColor(Ui.SURFACE);
        sysconfig.addView(flagRows);
        flagRaw = Ui.body(this, "");
        flagRaw.setTypeface(Typeface.MONOSPACE);
        flagRaw.setTextIsSelectable(true);
        sysconfig.addView(flagRaw);
        dcKeyInput = Ui.input(this, "Flag key");
        sysconfig.addView(dcKeyInput);
        LinearLayout dcGetRow = Ui.row(this);
        Button dcGet = Ui.smallButton(this, "Read flag");
        dcGet.setOnClickListener(v -> readDeviceConfig());
        dcGetRow.addView(dcGet);
        dcValueView = Ui.body(this, "");
        dcGetRow.addView(dcValueView);
        sysconfig.addView(dcGetRow);
        dcValueInput = Ui.input(this, "New value (blank clears the flag)");
        sysconfig.addView(dcValueInput);
        Button dcPut = Ui.button(this, "Set flag…", false);
        dcPut.setOnClickListener(v -> setDeviceConfig());
        sysconfig.addView(dcPut, Ui.wrap(0, 6, 0, 0, this));
        LinearLayout wifiRow = Ui.row(this);
        Button wifiRefresh = Ui.smallButton(this, "Refresh networks");
        wifiRefresh.setOnClickListener(v -> loadWifi());
        wifiRow.addView(wifiRefresh);
        wifiStatus = Ui.body(this, "Not loaded yet.");
        wifiRow.addView(wifiStatus);
        sysconfig.addView(wifiRow);
        wifiRows = Ui.column(this);
        wifiRows.setBackgroundColor(Ui.SURFACE);
        sysconfig.addView(wifiRows);
        wifiRaw = Ui.body(this, "");
        wifiRaw.setTypeface(Typeface.MONOSPACE);
        wifiRaw.setTextIsSelectable(true);
        sysconfig.addView(wifiRaw);
        LinearLayout userRow = Ui.row(this);
        Button userRefresh = Ui.smallButton(this, "Refresh users");
        userRefresh.setOnClickListener(v -> loadUsers());
        userRow.addView(userRefresh);
        userStatus = Ui.body(this, "Not loaded yet.");
        userRow.addView(userStatus);
        sysconfig.addView(userRow);
        userRows = Ui.column(this);
        userRows.setBackgroundColor(Ui.SURFACE);
        sysconfig.addView(userRows);
        userNameInput = Ui.input(this, "New user name");
        sysconfig.addView(userNameInput);
        Button userCreate = Ui.button(this, "Create user…", false);
        userCreate.setOnClickListener(v -> createUser());
        sysconfig.addView(userCreate, Ui.wrap(0, 6, 0, 0, this));

        LinearLayout inspectSection = addGroup("Inspect",
                "Fixed reads from Android's service dumps, plus a full bugreport. "
                + "Dumpsys is a fixed menu — never the whole dump, which is far too large "
                + "for the screen. Bugreport takes minutes and produces a large file. "
                + "The bridge writes it to Downloads, where the Files app shows it, and "
                + "reports the exact path.");
        Button dumpsysButton = Ui.button(this, "Run dumpsys…", false);
        dumpsysButton.setOnClickListener(v -> chooseDumpsys());
        inspectSection.addView(dumpsysButton, Ui.wrap(0, 6, 0, 0, this));
        dumpsysOutput = Ui.body(this, "No dump yet.");
        dumpsysOutput.setTypeface(Typeface.MONOSPACE);
        dumpsysOutput.setTextIsSelectable(true);
        inspectSection.addView(dumpsysOutput);
        LinearLayout dumpsysRow = Ui.row(this);
        Button dumpsysCopy = Ui.smallButton(this, "Copy");
        dumpsysCopy.setOnClickListener(v -> copyDumpsys());
        dumpsysRow.addView(dumpsysCopy);
        Button dumpsysShare = Ui.smallButton(this, "Share");
        dumpsysShare.setOnClickListener(v -> shareDumpsys());
        dumpsysRow.addView(dumpsysShare);
        inspectSection.addView(dumpsysRow);
        LinearLayout bugRow = Ui.row(this);
        Button bugStart = Ui.smallButton(this, "Start bugreport");
        bugStart.setOnClickListener(v -> startBugreport());
        bugRow.addView(bugStart);
        bugreportStopButton = Ui.smallButton(this, "Stop");
        bugreportStopButton.setOnClickListener(v -> stopBugreport());
        bugreportStopButton.setEnabled(false);
        bugRow.addView(bugreportStopButton);
        Button bugCheck = Ui.smallButton(this, "Check status");
        bugCheck.setOnClickListener(v -> pollBugreportStatus());
        bugRow.addView(bugCheck);
        inspectSection.addView(bugRow);
        bugreportStatus = Ui.body(this, "No bugreport running.");
        inspectSection.addView(bugreportStatus);

        // ---- v1.37: change history ----
        LinearLayout history = addGroup("Change history",
                "Every persistent change this screen makes is recorded here before it is "
                + "applied, with the value that was there before. Restore writes that "
                + "value back through the same governed control. Entries that cannot be "
                + "undone say so plainly. The history lives only on this phone — it is "
                + "never part of the portable profile export.");
        LinearLayout historyRow = Ui.row(this);
        restoreAllButton = Ui.smallButton(this, "Restore all changes");
        restoreAllButton.setOnClickListener(v -> restoreAllChanges());
        historyRow.addView(restoreAllButton);
        historyStatus = Ui.body(this, "");
        historyRow.addView(historyStatus);
        history.addView(historyRow);
        historyRows = Ui.column(this);
        historyRows.setBackgroundColor(Ui.SURFACE);
        history.addView(historyRows);
        refreshHistory();

        LinearLayout resultGroup = addGroup("Result", null);
        output = Ui.body(this, "No control has been run yet.");
        resultGroup.addView(output);

        // The Result card is the last group built; it travels
        // with every page (see showPage).
        resultCard = pageCards.get(pageCards.size() - 1);

        // Hub rows (v1.39): one rounded card of Settings-style
        // rows — icon, title, chevron — same group order. The
        // rows carry no summary line: the titles are the
        // controls' names, and live state belongs on the status
        // card above, not as repeated prose.
        LinearLayout groupsCard = hubCard();
        hubRoot.addView(groupsCard);
        for (int i = 0; i < pageTitles.size(); i++) {
            final int index = i;
            if (i > 0) addHubDivider(groupsCard);
            LinearLayout row = hubRowShell(DeepHub.glyphForGroup(i));
            TextView label = Ui.text(this, pageTitles.get(i), 15, Ui.TEXT);
            label.setLayoutParams(new LinearLayout.LayoutParams(0,
                    ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            label.setPadding(Ui.dp(this, 14), 0, Ui.dp(this, 8), 0);
            row.addView(label);
            row.addView(Ui.text(this, "›", 22, Ui.DIM));
            row.setOnClickListener(v -> showPage(index));
            groupsCard.addView(row);
        }

        // The page container: a back row plus a holder that
        // takes one group card (plus the Result card) at a time.
        pageScroll = new ScrollView(this);
        pageScroll.setBackgroundColor(Ui.BG);
        LinearLayout pageRoot = Ui.column(this);
        pageRoot.setPadding(pad, Ui.dp(this, 18), pad, Ui.dp(this, 24));
        pageScroll.addView(pageRoot);
        LinearLayout backRow = Ui.row(this);
        Button back = Ui.smallButton(this, "‹ Deep controls");
        back.setOnClickListener(v -> showHub());
        backRow.addView(back);
        pageTitleView = Ui.text(this, "", 17, Ui.TEXT);
        pageTitleView.setTypeface(Typeface.create("sans-serif", Typeface.BOLD));
        backRow.addView(pageTitleView);
        pageRoot.addView(backRow, Ui.wrap(0, 0, 0, 10, this));
        pageHolder = Ui.column(this);
        pageRoot.addView(pageHolder);

        refreshProtectedSwitch();
        setContentView(hubScroll);
    }

    /** Show the hub (v1.38). The group cards keep their state
     *  while detached; the hub rows are always in place. */
    private void showHub() {
        currentPage = -1;
        setContentView(hubScroll);
    }

    /** Show one group's page (v1.38): the group's card plus
     *  the Result card, so the result of a run is visible on
     *  the page where the control ran. The Result page shows
     *  its own card once. */
    private void showPage(int index) {
        currentPage = index;
        pageTitleView.setText(pageTitles.get(index));
        pageHolder.removeAllViews();
        LinearLayout card = pageCards.get(index);
        detach(card);
        pageHolder.addView(card, Ui.wrap(0, 0, 0, 10, this));
        if (card != resultCard) {
            detach(resultCard);
            pageHolder.addView(resultCard, Ui.wrap(0, 0, 0, 10, this));
        }
        pageScroll.scrollTo(0, 0);
        setContentView(pageScroll);
    }

    // ---- v1.39: Settings-style hub rows + meters ----

    /** A Settings group card: rounded surface, 1px line. */
    private LinearLayout hubCard() {
        LinearLayout card = Ui.column(this);
        card.setBackground(Ui.roundedStroke(this, Ui.SURFACE, 16, Ui.LINE));
        return card;
    }

    /** A 24dp glyph icon, drawn exactly the way SettingsActivity
     *  draws its row icons (same size, color, stroke). */
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

    /** One hub row shell: 56dp, glyph, Settings padding. The
     *  caller adds the texts and the trailing control. */
    private LinearLayout hubRowShell(int glyph) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setMinimumHeight(Ui.dp(this, 56));
        row.setPadding(Ui.dp(this, 14), Ui.dp(this, 6), Ui.dp(this, 10), Ui.dp(this, 6));
        row.addView(glyphIcon(glyph), new LinearLayout.LayoutParams(
                Ui.dp(this, 24), Ui.dp(this, 24)));
        return row;
    }

    /** The title column of a hub row; the caller adds a summary
     *  line beneath the title when the row carries live state. */
    private LinearLayout hubRowTexts(LinearLayout row, String title) {
        LinearLayout texts = Ui.column(this);
        texts.setBackgroundColor(Ui.SURFACE);
        texts.addView(Ui.text(this, title, 15, Ui.TEXT));
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        tlp.setMargins(Ui.dp(this, 14), 0, Ui.dp(this, 8), 0);
        row.addView(texts, tlp);
        return texts;
    }

    /** A status row: icon + title + a summary line carrying live
     *  state, no chevron, not clickable. The summary wraps to
     *  two lines so the bridge's shell UID is never cut off.
     *  Returns the summary view. */
    private TextView hubStatusRow(LinearLayout card, int glyph, String title,
                                  String initial) {
        LinearLayout row = hubRowShell(glyph);
        LinearLayout texts = hubRowTexts(row, title);
        TextView summary = Ui.text(this, initial, 12, Ui.DIM);
        summary.setMaxLines(2);
        summary.setEllipsize(android.text.TextUtils.TruncateAt.END);
        texts.addView(summary);
        card.addView(row);
        return summary;
    }

    /** The divider between hub rows, indented past the icon
     *  exactly like Settings. */
    private void addHubDivider(LinearLayout card) {
        View d = new View(this);
        d.setBackgroundColor(Ui.LINE);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 1);
        lp.setMargins(Ui.dp(this, 52), 0, Ui.dp(this, 12), 0);
        card.addView(d, lp);
    }

    /** The meters card: one bar + numbers per meter, refreshed
     *  on resume and on Refresh. All reads are app-side, so the
     *  meters work with the bridge off. */
    private LinearLayout buildMetersCard() {
        LinearLayout card = hubCard();
        int pad = Ui.dp(this, 14);
        card.setPadding(pad, Ui.dp(this, 10), pad, Ui.dp(this, 12));
        LinearLayout header = Ui.row(this);
        TextView title = Ui.text(this, "Status", 17, Ui.TEXT);
        title.setTypeface(Typeface.create("sans-serif", Typeface.BOLD));
        header.addView(title);
        TextView spacer = Ui.text(this, "", 15, Ui.TEXT);
        spacer.setLayoutParams(new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        header.addView(spacer);
        Button refresh = Ui.smallButton(this, "Refresh");
        refresh.setOnClickListener(v -> refreshMeters());
        header.addView(refresh);
        card.addView(header);
        meterBars = new ProgressBar[METER_LABELS.length];
        meterNumbers = new TextView[METER_LABELS.length];
        for (int i = 0; i < METER_LABELS.length; i++) {
            LinearLayout labelRow = Ui.row(this);
            labelRow.addView(Ui.text(this, METER_LABELS[i], 14, Ui.TEXT));
            TextView nums = Ui.text(this, "…", 13, Ui.DIM);
            Ui.mono(nums);
            nums.setGravity(Gravity.END);
            nums.setLayoutParams(new LinearLayout.LayoutParams(0,
                    ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            labelRow.addView(nums);
            card.addView(labelRow, Ui.wrap(0, 8, 0, 2, this));
            ProgressBar bar = new ProgressBar(this, null,
                    android.R.attr.progressBarStyleHorizontal);
            bar.setMax(1000);
            bar.setProgress(0);
            bar.setProgressTintList(ColorStateList.valueOf(Ui.ACCENT));
            bar.setProgressBackgroundTintList(ColorStateList.valueOf(Ui.LINE));
            card.addView(bar, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(this, 6)));
            meterBars[i] = bar;
            meterNumbers[i] = nums;
        }
        return card;
    }

    /** Read every meter off the UI thread (the CPU read sleeps
     *  between samples) and bind the results. */
    private void refreshMeters() {
        new Thread(() -> {
            DeviceMeters.Reading[] readings = new DeviceMeters.Reading[] {
                    DeviceMeterReads.readMemory(this),
                    DeviceMeterReads.readBattery(this),
                    DeviceMeterReads.readStorage(),
                    DeviceMeterReads.readData(),
                    DeviceMeterReads.readCpu(),
            };
            ui.post(() -> bindMeters(readings));
        }, "deep-meters").start();
    }

    private void bindMeters(DeviceMeters.Reading[] readings) {
        if (meterBars == null || meterNumbers == null) return;
        for (int i = 0; i < METER_LABELS.length && i < readings.length; i++) {
            DeviceMeters.Reading r = readings[i];
            if (r == null) r = DeviceMeters.Reading.unavailable();
            meterNumbers[i].setText(r.numbers);
            ProgressBar bar = meterBars[i];
            if (!r.available || Double.isNaN(r.fraction)) {
                bar.setProgress(0);
            } else {
                double f = Math.max(0.0, Math.min(1.0, r.fraction));
                bar.setProgress((int) Math.round(f * 1000));
            }
            bar.setProgressTintList(
                    ColorStateList.valueOf(r.warn ? Ui.SIGNAL : Ui.ACCENT));
        }
    }

    /** Detach a view from whatever parent holds it, if any —
     *  cards move between the page holder and nowhere (the hub
     *  holds no cards), so this never orphans a control. */
    private static void detach(View view) {
        ViewGroup parent = (ViewGroup) view.getParent();
        if (parent != null) parent.removeView(view);
    }

    @Override
    public void onBackPressed() {
        if (currentPage >= 0) {
            showHub();
            return;
        }
        super.onBackPressed();
    }

    @Override
    protected void onResume() {
        super.onResume();
        ping();
        refreshMeters();
        refreshTarget();
        refreshInputBounds();
        // v1.38: the hub + pages load every group's data once
        // on the first resume; Refresh buttons reload after
        // that. The flags picker loads its namespaces and the
        // first (or last-picked) namespace's flags here too.
        if (!initialLoadDone) {
            initialLoadDone = true;
            loadProcesses();
            loadRemoved();
            loadDisplay();
            loadPower();
            loadSystemConfig();
            loadFlagNamespaces();
            refreshSettingRestoreButtons();
        }
    }

    @Override
    protected void onPause() {
        // Status polling ends when the screen leaves: the
        // recording/bugreport keeps running bridge-side, and
        // Check status (or a fresh start) resumes the display.
        recordPolling = false;
        bugreportPolling = false;
        ui.removeCallbacks(recordPoller);
        ui.removeCallbacks(bugreportPoller);
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        recordPolling = false;
        bugreportPolling = false;
        ui.removeCallbacks(recordPoller);
        ui.removeCallbacks(bugreportPoller);
        discardStagedApk();
        super.onDestroy();
    }

    private void ping() {
        new Thread(() -> {
            ShellBridgeClient.Response r = ShellBridgeClient.ping(this);
            ui.post(() -> {
                BridgeGate.State state = BridgeGate.classify(r.ok, r.bridgeVersion);
                lastBridgeState = state;
                refreshProtectedSwitch();
                if (state == BridgeGate.State.CURRENT) {
                    bridgeStatus.setText("Bridge on — shell UID " + r.bridgeUid
                            + ", governed allowlist "
                            + BridgeGate.versionName(r.bridgeVersion) + ".");
                    staleRow.setVisibility(View.GONE);
                } else if (state == BridgeGate.State.STALE) {
                    bridgeStatus.setText("Bridge is running old code ("
                            + BridgeGate.versionName(r.bridgeVersion) + ").");
                    staleRow.setVisibility(View.VISIBLE);
                } else {
                    bridgeStatus.setText(
                            "Deep view is off — restart after each reboot.");
                    staleRow.setVisibility(View.GONE);
                }
            });
        }, "control-ping").start();
    }

    /** One-tap stale restart (v1.15.1 pattern): the running
     *  bridge survives APK upgrades, so after an update it
     *  answers PING while running old code. restartStaleBridge
     *  stops the old bridge first — the plain restart would
     *  no-op on it — and leaves a current bridge alone. */
    private void restartStaleBridge() {
        bridgeStatus.setText("Restarting the bridge…");
        staleRow.setVisibility(View.GONE);
        AdbStarter.restartStaleBridge(this, new AdbStarter.Callback() {
            @Override public void onStatus(String line) {
                ui.post(() -> bridgeStatus.setText(line));
            }

            @Override public void onDone(boolean up, String summary) {
                ui.post(() -> {
                    bridgeStatus.setText(summary);
                    ping();
                    refreshTarget();
                });
            }
        });
    }

    private void pickApp() {
        List<InstalledApps.Entry> apps = InstalledApps.all(this);
        String[] labels = new String[apps.size()];
        for (int i = 0; i < apps.size(); i++) labels[i] = apps.get(i).label + " · " + apps.get(i).pkg;
        new AlertDialog.Builder(this)
                .setTitle("Choose target app")
                .setItems(labels, (d, which) -> {
                    packageInput.setText(apps.get(which).pkg);
                    refreshTarget();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    // ---- v1.36: protected-package override ----

    private boolean overrideOn() {
        return Prefs.get(this).getBoolean(Prefs.KEY_PROTECTED_OVERRIDE, false);
    }

    /** Header, switch, and status for the override section,
     *  from the persisted pref and the last ping. The switch
     *  unlocks only on a CURRENT bridge: a stale bridge
     *  refuses protected disables anyway, and the switch must
     *  not promise otherwise. */
    private void refreshProtectedSwitch() {
        if (protectedHeader == null || protectedSwitch == null
                || protectedStatus == null) return;
        boolean on = overrideOn();
        protectedHeader.setText("Protected packages — " + (on ? "ON" : "OFF"));
        syncingProtectedSwitch = true;
        protectedSwitch.setChecked(on);
        syncingProtectedSwitch = false;
        boolean current = lastBridgeState == BridgeGate.State.CURRENT;
        protectedSwitch.setEnabled(current);
        String hubLine;
        if (lastBridgeState == BridgeGate.State.STALE) {
            protectedStatus.setText("Needs the current bridge (v"
                    + BridgeGate.HOLD_WATCH_VERSION + ").");
            hubLine = "Needs the current bridge";
        } else if (lastBridgeState == BridgeGate.State.NOT_RUNNING) {
            protectedStatus.setText("Needs the bridge running.");
            hubLine = "Needs the bridge running";
        } else if (lastBridgeState == BridgeGate.State.UNKNOWN) {
            protectedStatus.setText("Checking the bridge…");
            hubLine = "Checking the bridge…";
        } else {
            protectedStatus.setText(on ? ProtectedOverride.switchOnText()
                    : ProtectedOverride.switchOffText());
            hubLine = on ? "On" : "Off";
        }
        if (hubProtectedLine != null) hubProtectedLine.setText(hubLine);
    }

    private void onProtectedToggle(boolean on) {
        if (!on) {
            // Turning protection back on is the safe direction:
            // no confirmation, but the flip is still audited.
            Prefs.get(this).edit().putBoolean(Prefs.KEY_PROTECTED_OVERRIDE, false).apply();
            BridgeControl.logOverrideToggle(this, BridgeControl.ORIGIN_UI, false);
            refreshProtectedSwitch();
            return;
        }
        // Turning the override ON needs the strong confirmation.
        // The switch is reverted until the user holds to confirm;
        // a cancelled dialog leaves the switch (and the pref) off.
        syncingProtectedSwitch = true;
        protectedSwitch.setChecked(false);
        syncingProtectedSwitch = false;
        strongConfirm(ProtectedOverride.switchConfirmTitle(),
                ProtectedOverride.switchConfirmBody(),
                "Allow protected disables",
                () -> {
                    Prefs.get(this).edit()
                            .putBoolean(Prefs.KEY_PROTECTED_OVERRIDE, true).apply();
                    BridgeControl.logOverrideToggle(this, BridgeControl.ORIGIN_UI, true);
                    refreshProtectedSwitch();
                });
    }

    private String targetPackage() {
        String pkg = packageInput.getText().toString().trim();
        if (pkg.isEmpty()) {
            Toast.makeText(this, "Choose or type a target package first.",
                    Toast.LENGTH_LONG).show();
            return null;
        }
        return pkg;
    }

    private String targetName(String pkg) {
        return InstalledApps.label(this, pkg) + " (" + pkg + ")";
    }

    private void refreshTarget() {
        String pkg = packageInput.getText().toString().trim();
        if (pkg.isEmpty()) {
            targetStatus.setText("No target selected.");
            return;
        }
        try {
            ApplicationInfo info = getPackageManager().getApplicationInfo(pkg, 0);
            boolean enabled = packageEnabled(pkg);
            targetStatus.setText(targetName(pkg) + "\n"
                    + ((info.flags & ApplicationInfo.FLAG_SYSTEM) != 0 ? "System app" : "User app")
                    + " · currently " + (enabled ? "enabled" : "disabled")
                    + (BridgeControl.REFUSE_ALL.contains(pkg)
                    ? "\nProtected by the bridge refuse-list." : ""));
        } catch (Exception e) {
            targetStatus.setText("That package is not installed on this phone.");
        }
    }

    /** True unless Android reports the package disabled in any
     *  form (system-disabled, user-disabled, or disabled until
     *  used). Throws when the package is not installed. */
    private boolean packageEnabled(String pkg) throws Exception {
        int setting = getPackageManager().getApplicationEnabledSetting(pkg);
        return setting != PackageManager.COMPONENT_ENABLED_STATE_DISABLED
                && setting != PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER
                && setting != PackageManager.COMPONENT_ENABLED_STATE_DISABLED_UNTIL_USED;
    }

    private boolean isSystemApp(String pkg) {
        try {
            ApplicationInfo info = getPackageManager().getApplicationInfo(pkg, 0);
            return (info.flags & ApplicationInfo.FLAG_SYSTEM) != 0;
        } catch (Exception e) {
            return false;
        }
    }

    private void choosePermission(boolean grant) {
        String pkg = targetPackage();
        if (pkg == null) return;
        new AlertDialog.Builder(this)
                .setTitle((grant ? "Grant" : "Revoke") + " which permission?")
                .setItems(BridgeControl.PERMISSION_LABELS, (d, which) -> {
                    String permission = BridgeControl.PERMISSIONS[which];
                    String label = BridgeControl.PERMISSION_LABELS[which];
                    String previous;
                    try {
                        previous = getPackageManager().checkPermission(permission, pkg)
                                == PackageManager.PERMISSION_GRANTED
                                ? "granted" : "denied";
                    } catch (Exception e) {
                        previous = null;
                    }
                    Draft draft = draft(grant ? "GRANT_PERMISSION" : "REVOKE_PERMISSION",
                            targetName(pkg), previous, grant ? "granted" : "denied",
                            pkg, null, null, permission);
                    confirmJournaled(false, (grant ? "Grant " : "Revoke ") + label + (grant ? " to " : " from ")
                                    + targetName(pkg) + "?",
                            "The bridge will ask Android's package manager to "
                                    + (grant ? "grant" : "revoke") + " " + permission + ".",
                            grant ? "Grant" : "Revoke", draft,
                            () -> grant
                                    ? BridgeControl.grantPermission(this, BridgeControl.ORIGIN_UI, pkg, permission)
                                    : BridgeControl.revokePermission(this, BridgeControl.ORIGIN_UI, pkg, permission));
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void chooseAppOp() {
        String pkg = targetPackage();
        if (pkg == null) return;
        new AlertDialog.Builder(this)
                .setTitle("Set which AppOp for " + targetName(pkg) + "?")
                .setItems(BridgeControl.APP_OP_LABELS, (d, opWhich) -> {
                    String op = BridgeControl.APP_OPS[opWhich];
                    new AlertDialog.Builder(this)
                            .setTitle("Mode for " + BridgeControl.APP_OP_LABELS[opWhich])
                            .setItems(BridgeControl.APP_OP_MODES, (d2, modeWhich) -> {
                                String mode = BridgeControl.APP_OP_MODES[modeWhich];
                                new Thread(() -> {
                                    ShellBridgeClient.Response r =
                                            ShellBridgeClient.appOps(this, pkg);
                                    final String previous = r.ok
                                            ? BridgeOps.parseAppOpMode(r.text, op) : null;
                                    ui.post(() -> {
                                        Draft draft = draft("SET_APP_OP", targetName(pkg),
                                                previous, mode, pkg, null, null, op);
                                        confirmJournaled(false, "Set " + BridgeControl.APP_OP_LABELS[opWhich]
                                                        + " to " + mode + " for " + targetName(pkg) + "?",
                                                "The bridge writes this one AppOp policy only.",
                                                "Set AppOp", draft,
                                                () -> BridgeControl.setAppOp(this,
                                                        BridgeControl.ORIGIN_UI, pkg, op, mode));
                                    });
                                }, "bridge-appops").start();
                            })
                            .setNegativeButton("Cancel", null)
                            .show();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void toggleEnabled() {
        String pkg = targetPackage();
        if (pkg == null) return;
        toggleEnabledFor(pkg);
    }

    /** Disable/enable for one package, shared by the App state
     *  button and the process long-press menu. Disabling a
     *  system app (or a refuse-listed destructive target) needs
     *  the strong press-and-hold confirmation.
     *
     *  v1.36, protected packages: Control itself is never
     *  offered a confirmation — the attempt goes straight to
     *  BridgeControl, which refuses it with the lockout reason
     *  and audits the refusal. A protected package with the
     *  override off confirms with the coming refusal named
     *  (the killProcess pattern) and the bridge refuses. A
     *  protected package with the override on takes the strong
     *  confirmation naming the exact consequence and the
     *  recovery path.
     *
     *  v1.37: the flow takes the target state explicitly so a
     *  history restore reuses the exact governed path. A
     *  forward change journals; a restore marks the entry
     *  restored and appends nothing. */
    private void toggleEnabledFor(String pkg) {
        try {
            boolean currentlyEnabled = packageEnabled(pkg);
            enableDisableFlow(pkg, !currentlyEnabled, null);
        } catch (Exception e) {
            Toast.makeText(this, "That package is not installed.", Toast.LENGTH_LONG).show();
        }
    }

    private void enableDisableFlow(String pkg, boolean targetEnabled,
                                   ChangeJournal.Entry restoreEntry) {
        try {
            boolean currentlyEnabled = packageEnabled(pkg);
            String title = (targetEnabled ? "Enable " : "Disable ")
                    + targetName(pkg) + "?";
            if (ProtectedOverride.isOwnPackage(pkg)) {
                runCall(title, () -> BridgeControl.setAppEnabled(
                        this, BridgeControl.ORIGIN_UI, pkg, targetEnabled));
                return;
            }
            boolean protectedPkg = ProtectedOverride.isProtected(pkg);
            boolean override = protectedPkg && overrideOn();
            boolean strong = !targetEnabled
                    && (isSystemApp(pkg)
                    || BridgeControl.REFUSE_DESTRUCTIVE.contains(pkg));
            String body;
            if (protectedPkg && override) {
                strong = true;
                body = targetEnabled
                        ? ProtectedOverride.enableConfirmBody(pkg)
                        : ProtectedOverride.disableConfirmBody(pkg);
            } else if (protectedPkg) {
                body = "The bridge will refuse this — "
                        + ProcessRules.refuseReason(pkg)
                        + " Turn on the Protected packages switch to allow "
                        + "disabling protected packages.\n\n"
                        + (targetEnabled
                                ? "The app becomes available to open and run again."
                                : "The app is disabled: it cannot run until re-enabled. "
                                + "Its data is not deleted.");
            } else {
                body = targetEnabled
                        ? "The app becomes available to open and run again."
                        : "The app is disabled: it cannot run until re-enabled. "
                        + "Its data is not deleted.";
            }
            BridgeCall call = () -> BridgeControl.setAppEnabled(
                    this, BridgeControl.ORIGIN_UI, pkg, targetEnabled);
            if (restoreEntry != null) {
                confirmRestore(strong, title, body,
                        targetEnabled ? "Enable" : "Disable",
                        restoreEntry, call, null);
            } else {
                Draft draft = draft("SET_APP_ENABLED", targetName(pkg),
                        String.valueOf(currentlyEnabled), String.valueOf(targetEnabled),
                        pkg, null, null, null);
                confirmJournaled(strong, title, body,
                        targetEnabled ? "Enable" : "Disable", draft, call);
            }
        } catch (Exception e) {
            Toast.makeText(this, "That package is not installed.", Toast.LENGTH_LONG).show();
        }
    }

    private void chooseTimeout() {
        String[] labels = {"30 seconds", "1 minute", "2 minutes", "5 minutes", "10 minutes", "30 minutes"};
        String[] values = {"30000", "60000", "120000", "300000", "600000", "1800000"};
        new AlertDialog.Builder(this)
                .setTitle("Screen timeout")
                .setItems(labels, (d, which) -> {
                    String value = values[which];
                    new Thread(() -> {
                        ShellBridgeClient.Response r = ShellBridgeClient.settingGet(
                                this, "system", "screen_off_timeout");
                        final String readBack = r.ok ? r.text.trim() : null;
                        ui.post(() -> {
                            String previous = readBack == null || "null".equals(readBack)
                                    ? null : readBack;
                            String reason = readBack == null
                                    ? "The previous value could not be read."
                                    : "The setting was not set before this app changed it — "
                                    + "restoring it would need a delete the bridge does not offer.";
                            Draft draft = draft("SET_LAUNCHER_SETTING", "Screen timeout",
                                    previous, value, reason,
                                    null, "system", "screen_off_timeout", null);
                            confirmJournaled(false,
                                    "Set the screen timeout to " + labels[which] + "?",
                                    "This is the one named system setting exposed on this screen.",
                                    "Set timeout", draft,
                                    () -> BridgeControl.setLauncherSetting(this, BridgeControl.ORIGIN_UI,
                                            "screen_off_timeout", value));
                        });
                    }, "bridge-setting-read").start();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    // ---- v1.35: Apps ----

    private void uninstallTarget() {
        String pkg = targetPackage();
        if (pkg == null) return;
        boolean strong = isSystemApp(pkg)
                || BridgeControl.REFUSE_DESTRUCTIVE.contains(pkg);
        Draft draft = draft("UNINSTALL_USER", targetName(pkg),
                "installed", "removed", pkg, null, null, null);
        confirmJournaled(strong, "Uninstall " + targetName(pkg) + " for user 0?",
                "This removes the app for the owner user only. Its files stay on the phone, "
                + "and Restore (below) brings it back. The app's data for user 0 is removed.",
                "Uninstall", draft,
                () -> BridgeControl.uninstallUser(this, BridgeControl.ORIGIN_UI, pkg),
                this::loadRemoved);
    }

    private void suspendTarget(boolean suspend) {
        String pkg = targetPackage();
        if (pkg == null) return;
        // No read op reports whether an app is suspended, so
        // the before-value is unknown and the entry is
        // unrestorable — the copy points at the manual inverse.
        Draft draft = draft(suspend ? "SUSPEND_APP" : "UNSUSPEND_APP",
                targetName(pkg), null, suspend ? "suspended" : "unsuspended",
                "Whether the app was suspended before could not be read — "
                + "use Suspend / Unsuspend here to change it.",
                pkg, null, null, null);
        confirmJournaled(false, (suspend ? "Suspend " : "Unsuspend ") + targetName(pkg) + "?",
                suspend
                        ? "A suspended app cannot run until it is unsuspended. Some builds "
                        + "refuse suspending — a refusal is reported with Android's own words."
                        : "The app can run again.",
                suspend ? "Suspend" : "Unsuspend", draft,
                () -> BridgeControl.suspendApp(this, BridgeControl.ORIGIN_UI, pkg, suspend));
    }

    /** The removed-apps list: packages present on the device
     *  but uninstalled for user 0, each with a Restore action.
     *  Loads on demand, never polls. */
    private void loadRemoved() {
        removedStatus.setText("Loading…");
        removedRows.removeAllViews();
        new Thread(() -> {
            ShellBridgeClient.Response r = ShellBridgeClient.listRemoved(this);
            List<String> removed = new ArrayList<>();
            String error = null;
            if (!r.ok) {
                error = r.error;
            } else if (!r.text.trim().isEmpty()) {
                for (String line : r.text.split("\\n")) {
                    String pkg = line.trim();
                    if (!pkg.isEmpty()) removed.add(pkg);
                }
            }
            final List<String> done = removed;
            final String err = error;
            ui.post(() -> {
                if (err != null) {
                    removedStatus.setText("Removed apps are unavailable.\n" + err);
                    return;
                }
                if (done.isEmpty()) {
                    removedStatus.setText("No apps are removed for user 0.");
                    return;
                }
                removedStatus.setText(done.size() + " removed app"
                        + (done.size() == 1 ? "" : "s") + " — Restore brings one back.");
                for (String pkg : done) removedRows.addView(removedRowView(pkg));
            });
        }, "bridge-removed").start();
    }

    private View removedRowView(String pkg) {
        LinearLayout row = Ui.row(this);
        TextView label = Ui.text(this, InstalledApps.label(this, pkg) + "\n" + pkg, 14, Ui.TEXT);
        label.setTypeface(Typeface.MONOSPACE);
        row.addView(label);
        Button restore = Ui.smallButton(this, "Restore");
        restore.setOnClickListener(v -> confirm(false, "Restore " + pkg + " for user 0?",
                "The app becomes available to open and run again.",
                "Restore",
                () -> BridgeControl.installExisting(this, BridgeControl.ORIGIN_UI, pkg),
                this::loadRemoved));
        row.addView(restore);
        return row;
    }

    // ---- v1.35: APK install ----

    /** Pick an APK with the system file picker. Nothing is
     *  downloaded and no URL is involved: the user picks a
     *  file already on the phone. */
    private void pickApk() {
        Intent pick = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        pick.addCategory(Intent.CATEGORY_OPENABLE);
        pick.setType(APK_MIME);
        try {
            startActivityForResult(pick, REQ_PICK_APK);
        } catch (Exception e) {
            Toast.makeText(this, "No file picker is available on this phone.",
                    Toast.LENGTH_LONG).show();
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQ_PICK_APK || resultCode != Activity.RESULT_OK || data == null) return;
        Uri picked = data.getData();
        if (picked == null) return;
        prepareStagedApk(picked);
    }

    /**
     * Stage a picked APK for the bridge. The file is copied
     * twice, both by the app: once to its own cache, where
     * PackageManager reads the package it declares (a file
     * that is not an APK is refused here, before anything is
     * staged), and once to the app's own Downloads entry with
     * a random token name, which is the one path shape the
     * bridge installs from. App-private storage cannot be
     * the staging spot: the shell user cannot read it. The
     * staged file is deleted after the attempt, or on cancel.
     */
    private void prepareStagedApk(Uri picked) {
        scrollToResult();
        output.setText("Install an APK file\n\nReading the picked file…");
        new Thread(() -> {
            String error = null;
            File cache = null;
            Uri staged = null;
            String stagedPath = null;
            String pkg = null;
            String version = null;
            String displayName = null;
            try {
                cache = new File(getCacheDir(), "inspect-" + System.currentTimeMillis() + ".apk");
                try (InputStream in = getContentResolver().openInputStream(picked);
                     OutputStream out = new FileOutputStream(cache)) {
                    if (in == null) throw new Exception("The picked file could not be opened.");
                    byte[] buf = new byte[8192];
                    int n;
                    while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                }
                PackageInfo info = getPackageManager().getPackageArchiveInfo(
                        cache.getAbsolutePath(), 0);
                if (info == null || info.packageName == null) {
                    throw new Exception("That file is not an Android package (APK).");
                }
                pkg = info.packageName;
                version = info.versionName;
                byte[] rand = new byte[8];
                new SecureRandom().nextBytes(rand);
                StringBuilder token = new StringBuilder(16);
                for (byte b : rand) token.append(String.format("%02x", b & 0xff));
                String fileName = BridgeOps.stageFileName(token.toString());
                ContentValues values = new ContentValues();
                values.put(MediaStore.MediaColumns.DISPLAY_NAME, fileName);
                values.put(MediaStore.MediaColumns.MIME_TYPE, APK_MIME);
                values.put(MediaStore.MediaColumns.RELATIVE_PATH,
                        Environment.DIRECTORY_DOWNLOADS);
                staged = getContentResolver().insert(
                        MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
                if (staged == null) throw new Exception("The staged copy could not be created.");
                try (InputStream in = new java.io.FileInputStream(cache);
                     OutputStream out = getContentResolver().openOutputStream(staged)) {
                    if (out == null) throw new Exception("The staged copy could not be written.");
                    byte[] buf = new byte[8192];
                    int n;
                    while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                }
                stagedPath = "/sdcard/Download/" + fileName;
                displayName = fileName;
            } catch (Exception e) {
                error = e.getMessage() == null ? "The file could not be prepared." : e.getMessage();
                if (staged != null) {
                    try {
                        getContentResolver().delete(staged, null, null);
                    } catch (Exception ignored) {
                    }
                }
                if (cache != null) cache.delete();
            }
            final String err = error;
            final Uri stagedUri = staged;
            final File cacheFile = cache;
            final String path = stagedPath;
            final String packageName = pkg;
            final String versionName = version;
            final String shownName = displayName;
            ui.post(() -> {
                if (err != null) {
                    output.setText("Install an APK file\n\nRefused / failed: " + err);
                    return;
                }
                stagedApkUri = stagedUri;
                stagedCacheFile = cacheFile;
                stagedApkPath = path;
                stagedApkPkg = packageName;
                new AlertDialog.Builder(this)
                        .setTitle("Install this APK?")
                        .setMessage("File: " + shownName + "\nPackage: " + packageName
                                + (versionName == null ? "" : "\nVersion: " + versionName)
                                + "\n\nThe bridge will install it with `pm install -r`. "
                                + "An existing install of the same package is replaced. "
                                + "This attempt will be written to the audit log.")
                        .setNegativeButton("Cancel", (d, w) -> discardStagedApk())
                        .setOnCancelListener(d -> discardStagedApk())
                        .setPositiveButton("Install", (d, w) -> installStagedApk())
                        .show();
            });
        }, "bridge-stage-apk").start();
    }

    private void installStagedApk() {
        final String path = stagedApkPath;
        final String pkg = stagedApkPkg;
        Draft draft = cannotUndoDraft("INSTALL_APK", pkg,
                "An installed app can only be removed with Uninstall — "
                + "there is no undo that restores the previous state.",
                pkg, null, null, null);
        runJournaled("Install " + pkg, draft,
                () -> BridgeControl.installApk(this, BridgeControl.ORIGIN_UI, path, pkg),
                this::discardStagedApk);
    }

    /** Delete the staged copy and the cache copy. Runs after
     *  the install attempt, and when the user cancels. */
    private void discardStagedApk() {
        if (stagedApkUri != null) {
            try {
                getContentResolver().delete(stagedApkUri, null, null);
            } catch (Exception ignored) {
            }
            stagedApkUri = null;
        }
        if (stagedCacheFile != null) {
            stagedCacheFile.delete();
            stagedCacheFile = null;
        }
        stagedApkPath = null;
        stagedApkPkg = null;
    }

    // ---- v1.35: Display ----

    /** Load the display state, the eight governed settings,
     *  and the night mode — one background thread, sequential
     *  reads, each row updated as its read lands. The first
     *  successful display read records the originals in prefs
     *  (never overwritten after that). */
    private void loadDisplay() {
        wmStatus.setText("Loading…");
        nightStatus.setText("Night mode: loading…");
        for (TextView v : settingValues) v.setText("…");
        new Thread(() -> {
            ShellBridgeClient.Response wm = ShellBridgeClient.wmGet(this);
            final BridgeOps.WmState state = wm.ok ? BridgeOps.parseWmState(wm.text) : null;
            final String wmError = wm.ok ? null : wm.error;
            if (state != null) recordOriginals(state);
            ui.post(() -> {
                lastWmState = state;
                if (state == null) {
                    wmStatus.setText("Display state is unavailable.\n" + wmError);
                } else {
                    wmStatus.setText("Size: " + BridgeOps.wmDisplay(
                            new BridgeOps.WmValue(state.sizePhysical, state.sizeOverride, ""),
                            "could not be read")
                            + "\nDensity: " + BridgeOps.wmDisplay(
                            new BridgeOps.WmValue(state.densityPhysical, state.densityOverride, ""),
                            "could not be read")
                            + "\nOverscan: " + (state.overscan == null
                            ? "could not be read" : state.overscan));
                }
                updateOriginalsLine();
            });
            for (int i = 0; i < BridgeOps.SETTINGS.length; i++) {
                final int index = i;
                BridgeOps.SettingDef def = BridgeOps.SETTINGS[i];
                ShellBridgeClient.Response r = ShellBridgeClient.settingGet(
                        this, def.namespace, def.key);
                final String shown = r.ok
                        ? BridgeOps.settingDisplay(def, r.text)
                        : "unavailable";
                ui.post(() -> settingValues[index].setText(shown));
            }
            ShellBridgeClient.Response night = ShellBridgeClient.nightGet(this);
            final String nightShown = night.ok
                    ? BridgeOps.nightModeDisplay(night.text.trim()) : "unavailable";
            ui.post(() -> nightStatus.setText("Night mode: " + nightShown));
        }, "bridge-display").start();
    }

    /** Record the originals once, from the first readable
     *  state: the EFFECTIVE values (override when one is in
     *  force, else physical). A later read never overwrites
     *  them — they are the state before Control changed
     *  anything. */
    private void recordOriginals(BridgeOps.WmState state) {
        android.content.SharedPreferences prefs = Prefs.get(this);
        if (prefs.contains(Prefs.KEY_WM_ORIGINAL_SIZE)) return;
        String size = state.sizeOverride != null ? state.sizeOverride : state.sizePhysical;
        String density = state.densityOverride != null
                ? state.densityOverride : state.densityPhysical;
        android.content.SharedPreferences.Editor edit = prefs.edit();
        if (size != null) edit.putString(Prefs.KEY_WM_ORIGINAL_SIZE, size);
        if (density != null) edit.putString(Prefs.KEY_WM_ORIGINAL_DENSITY, density);
        if (state.overscan != null) edit.putString(Prefs.KEY_WM_ORIGINAL_OVERSCAN, state.overscan);
        edit.apply();
    }

    private void updateOriginalsLine() {
        android.content.SharedPreferences prefs = Prefs.get(this);
        String size = prefs.getString(Prefs.KEY_WM_ORIGINAL_SIZE, null);
        String density = prefs.getString(Prefs.KEY_WM_ORIGINAL_DENSITY, null);
        String overscan = prefs.getString(Prefs.KEY_WM_ORIGINAL_OVERSCAN, null);
        boolean haveAny = size != null || density != null || overscan != null;
        wmResetButton.setEnabled(haveAny);
        wmOriginals.setText(haveAny
                ? "Originals: size " + (size == null ? "?" : size)
                + " · density " + (density == null ? "?" : density)
                + " · overscan " + (overscan == null ? "?" : overscan)
                : "No originals recorded yet — they are recorded on the first successful read.");
    }

    /** Reset restores exactly the recorded originals, one set
     *  per recorded value, with no confirm: it restores the
     *  known-good state. Values that were never recorded are
     *  skipped and named. */
    private void resetDisplay() {
        android.content.SharedPreferences prefs = Prefs.get(this);
        final String size = prefs.getString(Prefs.KEY_WM_ORIGINAL_SIZE, null);
        final String density = prefs.getString(Prefs.KEY_WM_ORIGINAL_DENSITY, null);
        final String overscan = prefs.getString(Prefs.KEY_WM_ORIGINAL_OVERSCAN, null);
        scrollToResult();
        output.setText("Reset display to originals\n\nWorking…");
        new Thread(() -> {
            StringBuilder report = new StringBuilder();
            if (size != null) {
                String[] wh = size.split("x");
                ShellBridgeClient.Response r;
                try {
                    r = BridgeControl.wmSetSize(this, BridgeControl.ORIGIN_UI,
                            Integer.parseInt(wh[0]), Integer.parseInt(wh[1]));
                } catch (Exception e) {
                    r = null;
                }
                report.append("Size: ").append(r == null ? "recorded value unreadable"
                        : r.ok ? r.text : "failed: " + r.error).append("\n");
            } else {
                report.append("Size: no original recorded — skipped.\n");
            }
            if (density != null) {
                ShellBridgeClient.Response r;
                try {
                    r = BridgeControl.wmSetDensity(this, BridgeControl.ORIGIN_UI,
                            Integer.parseInt(density));
                } catch (Exception e) {
                    r = null;
                }
                report.append("Density: ").append(r == null ? "recorded value unreadable"
                        : r.ok ? r.text : "failed: " + r.error).append("\n");
            } else {
                report.append("Density: no original recorded — skipped.\n");
            }
            if (overscan != null) {
                String[] parts = overscan.split(",");
                ShellBridgeClient.Response r;
                try {
                    r = BridgeControl.wmSetOverscan(this, BridgeControl.ORIGIN_UI,
                            Integer.parseInt(parts[0]), Integer.parseInt(parts[1]),
                            Integer.parseInt(parts[2]), Integer.parseInt(parts[3]));
                } catch (Exception e) {
                    r = null;
                }
                report.append("Overscan: ").append(r == null ? "recorded value unreadable"
                        : r.ok ? r.text : "failed: " + r.error).append("\n");
            } else {
                report.append("Overscan: no original recorded — skipped.\n");
            }
            final String done = report.toString();
            ui.post(() -> {
                output.setText("Reset display to originals\n\n" + done);
                ping();
                loadDisplay();
            });
        }, "bridge-control").start();
    }

    private void setWmSize() {
        new Thread(() -> {
            ShellBridgeClient.Response r = ShellBridgeClient.wmGet(this);
            final BridgeOps.WmState fresh = r.ok ? BridgeOps.parseWmState(r.text) : null;
            ui.post(() -> setWmSizeWith(fresh));
        }, "bridge-display").start();
    }

    /** Size dialog prefilled from a FRESH read, so the journal
     *  entry carries the true before-value. */
    private void setWmSizeWith(BridgeOps.WmState fresh) {
        String current = fresh == null ? null
                : fresh.sizeOverride != null ? fresh.sizeOverride
                : fresh.sizePhysical;
        int curW = 1080;
        int curH = 2400;
        if (current != null && current.contains("x")) {
            try {
                String[] wh = current.split("x");
                curW = Integer.parseInt(wh[0]);
                curH = Integer.parseInt(wh[1]);
            } catch (Exception ignored) {
            }
        }
        LinearLayout box = Ui.column(this);
        int p = Ui.dp(this, 18);
        box.setPadding(p, p, p, p);
        EditText widthInput = Ui.input(this, "Width");
        widthInput.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
        widthInput.setText(String.valueOf(curW));
        box.addView(widthInput);
        EditText heightInput = Ui.input(this, "Height");
        heightInput.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
        heightInput.setText(String.valueOf(curH));
        box.addView(heightInput);
        new AlertDialog.Builder(this)
                .setTitle("Display size")
                .setView(box)
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Set size", (d, w) -> {
                    int width;
                    int height;
                    try {
                        width = Integer.parseInt(widthInput.getText().toString().trim());
                        height = Integer.parseInt(heightInput.getText().toString().trim());
                    } catch (Exception e) {
                        Toast.makeText(this, "Width and height must be whole numbers.",
                                Toast.LENGTH_LONG).show();
                        return;
                    }
                    if (!BridgeOps.validSize(width, height)) {
                        Toast.makeText(this, "Size is outside the allowed range.",
                                Toast.LENGTH_LONG).show();
                        return;
                    }
                    Draft draft = draft("WM_SET_SIZE", "Display size",
                            current, width + "x" + height, null, null, null, null);
                    confirmJournaled(false, "Set the display size to " + width + "x" + height + "?",
                            "The screen resolution changes at once. If the screen looks wrong, "
                            + "use Reset to originals.",
                            "Set size", draft,
                            () -> BridgeControl.wmSetSize(this, BridgeControl.ORIGIN_UI,
                                    width, height),
                            this::loadDisplay);
                })
                .show();
    }

    private void setWmDensity() {
        new Thread(() -> {
            ShellBridgeClient.Response r = ShellBridgeClient.wmGet(this);
            final BridgeOps.WmState fresh = r.ok ? BridgeOps.parseWmState(r.text) : null;
            ui.post(() -> setWmDensityWith(fresh));
        }, "bridge-display").start();
    }

    private void setWmDensityWith(BridgeOps.WmState fresh) {
        String current = fresh == null ? null
                : fresh.densityOverride != null ? fresh.densityOverride
                : fresh.densityPhysical;
        LinearLayout box = Ui.column(this);
        int p = Ui.dp(this, 18);
        box.setPadding(p, p, p, p);
        EditText densityInput = Ui.input(this, "Density, 120–640");
        densityInput.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
        if (current != null) densityInput.setText(current);
        box.addView(densityInput);
        new AlertDialog.Builder(this)
                .setTitle("Display density")
                .setView(box)
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Set density", (d, w) -> {
                    int density;
                    try {
                        density = Integer.parseInt(densityInput.getText().toString().trim());
                    } catch (Exception e) {
                        Toast.makeText(this, "Density must be a whole number.",
                                Toast.LENGTH_LONG).show();
                        return;
                    }
                    if (!BridgeOps.validDensity(density)) {
                        Toast.makeText(this, "Density must be 120–640.",
                                Toast.LENGTH_LONG).show();
                        return;
                    }
                    Draft draft = draft("WM_SET_DENSITY", "Display density",
                            current, String.valueOf(density), null, null, null, null);
                    confirmJournaled(false, "Set the display density to " + density + "?",
                            "Text and icons scale at once. If the screen looks wrong, "
                            + "use Reset to originals.",
                            "Set density", draft,
                            () -> BridgeControl.wmSetDensity(this, BridgeControl.ORIGIN_UI, density),
                            this::loadDisplay);
                })
                .show();
    }

    private void setWmOverscan() {
        new Thread(() -> {
            ShellBridgeClient.Response r = ShellBridgeClient.wmGet(this);
            final BridgeOps.WmState fresh = r.ok ? BridgeOps.parseWmState(r.text) : null;
            ui.post(() -> setWmOverscanWith(fresh));
        }, "bridge-display").start();
    }

    private void setWmOverscanWith(BridgeOps.WmState fresh) {
        int[] cur = {0, 0, 0, 0};
        if (fresh != null && fresh.overscan != null) {
            String[] parts = fresh.overscan.split(",");
            if (parts.length == 4) {
                try {
                    for (int i = 0; i < 4; i++) cur[i] = Integer.parseInt(parts[i]);
                } catch (Exception ignored) {
                }
            }
        }
        LinearLayout box = Ui.column(this);
        int p = Ui.dp(this, 18);
        box.setPadding(p, p, p, p);
        String[] hints = {"Left", "Top", "Right", "Bottom"};
        EditText[] inputs = new EditText[4];
        for (int i = 0; i < 4; i++) {
            inputs[i] = Ui.input(this, hints[i] + " (0–500)");
            inputs[i].setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
            inputs[i].setText(String.valueOf(cur[i]));
            box.addView(inputs[i]);
        }
        new AlertDialog.Builder(this)
                .setTitle("Overscan")
                .setView(box)
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Set overscan", (d, w) -> {
                    int[] values = new int[4];
                    try {
                        for (int i = 0; i < 4; i++) {
                            values[i] = Integer.parseInt(
                                    inputs[i].getText().toString().trim());
                        }
                    } catch (Exception e) {
                        Toast.makeText(this, "Overscan values must be whole numbers.",
                                Toast.LENGTH_LONG).show();
                        return;
                    }
                    if (!BridgeOps.validOverscan(values[0], values[1], values[2], values[3])) {
                        Toast.makeText(this, "Overscan values must be 0–500.",
                                Toast.LENGTH_LONG).show();
                        return;
                    }
                    final String tuple = values[0] + "," + values[1] + ","
                            + values[2] + "," + values[3];
                    Draft draft = draft("WM_SET_OVERSCAN", "Overscan",
                            fresh == null ? null : fresh.overscan, tuple,
                            null, null, null, null);
                    confirmJournaled(false, "Set the overscan to " + tuple + "?",
                            "The usable screen area shrinks by these margins. If the screen "
                            + "looks wrong, use Reset to originals.",
                            "Set overscan", draft,
                            () -> BridgeControl.wmSetOverscan(this, BridgeControl.ORIGIN_UI,
                                    values[0], values[1], values[2], values[3]),
                            this::loadDisplay);
                })
                .show();
    }

    /** One governed setting: a preset dialog over the fixed
     *  choices, then the confirm naming old → new. The read
     *  already taken for the confirm doubles as the journal's
     *  before-value; an unset-before setting is unrestorable. */
    private void chooseSetting(BridgeOps.SettingDef def) {
        String[] labels = new String[def.presets.length];
        for (int i = 0; i < def.presets.length; i++) labels[i] = def.presets[i][0];
        new AlertDialog.Builder(this)
                .setTitle(def.label)
                .setItems(labels, (d, which) -> {
                    String value = def.presets[which][1];
                    String label = def.presets[which][0];
                    new Thread(() -> {
                        ShellBridgeClient.Response r = ShellBridgeClient.settingGet(
                                this, def.namespace, def.key);
                        final String oldShown = r.ok
                                ? BridgeOps.settingDisplay(def, r.text) : "could not be read";
                        final String readBack = r.ok ? r.text.trim() : null;
                        ui.post(() -> {
                            String previous = readBack == null || "null".equals(readBack)
                                    ? null : readBack;
                            String reason = readBack == null
                                    ? "The previous value could not be read."
                                    : "The setting was not set before this app changed it — "
                                    + "restoring it would need a delete the bridge does not offer.";
                            Draft draft = draft("SETTING_PUT", def.label,
                                    previous, value, reason,
                                    null, def.namespace, def.key, null);
                            confirmJournaled(false,
                                    "Set " + def.label + " to " + label + "?",
                                    "Now: " + oldShown + " → " + label + ".",
                                    "Set", draft,
                                    () -> BridgeControl.settingPut(this, BridgeControl.ORIGIN_UI,
                                            def.namespace, def.key, value),
                                    this::loadDisplay);
                        });
                    }, "bridge-setting-read").start();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void setNightMode(String mode) {
        new Thread(() -> {
            ShellBridgeClient.Response r = ShellBridgeClient.nightGet(this);
            final String readBack = r.ok ? r.text.trim() : null;
            ui.post(() -> {
                String previous = BridgeOps.validNightMode(readBack) ? readBack : null;
                Draft draft = draft("NIGHT_SET", "Night mode",
                        previous, mode, null, null, null, null);
                confirmJournaled(false, "Set night mode to " + BridgeOps.nightModeDisplay(mode) + "?",
                        "Day, Night, or Automatic (follows the time of day).",
                        "Set", draft,
                        () -> BridgeControl.nightSet(this, BridgeControl.ORIGIN_UI, mode),
                        this::loadDisplay);
            });
        }, "bridge-display").start();
    }

    // ---- v1.35: Input ----

    /** The display bounds input coordinates are validated
     *  against: the current DisplayMetrics, which follow any
     *  wm size override in force. */
    private int[] displayBounds() {
        DisplayMetrics dm = getResources().getDisplayMetrics();
        return new int[]{dm.widthPixels, dm.heightPixels};
    }

    private void refreshInputBounds() {
        int[] bounds = displayBounds();
        inputBounds.setText("Display: " + bounds[0] + " × " + bounds[1]
                + " — coordinates must be inside it (0 to " + (bounds[0] - 1)
                + ", 0 to " + (bounds[1] - 1) + ").");
    }

    /** Injections run without a confirm (see the group copy)
     *  and report in the Result card, like every other
     *  control — the call itself is the audit record. They do
     *  not scroll the view: rapid-fire presses must not yank
     *  the screen on every tap. */
    private void inject(String title, BridgeCall call) {
        runCallWithResult(title, call, null, false, null);
    }

    private void sendKey(int code, String label) {
        inject("Key: " + label,
                () -> BridgeControl.inputKey(this, BridgeControl.ORIGIN_UI, code, label));
    }

    private void sendTap() {
        int[] bounds = displayBounds();
        int x;
        int y;
        try {
            x = Integer.parseInt(tapXInput.getText().toString().trim());
            y = Integer.parseInt(tapYInput.getText().toString().trim());
        } catch (Exception e) {
            Toast.makeText(this, "Tap coordinates must be whole numbers.",
                    Toast.LENGTH_LONG).show();
            return;
        }
        if (!BridgeOps.validCoord(x, bounds[0]) || !BridgeOps.validCoord(y, bounds[1])) {
            Toast.makeText(this, "Tap coordinates must be inside the display ("
                    + bounds[0] + " × " + bounds[1] + ").", Toast.LENGTH_LONG).show();
            return;
        }
        inject("Tap at " + x + "," + y,
                () -> BridgeControl.inputTap(this, BridgeControl.ORIGIN_UI, x, y));
    }

    private void sendSwipe() {
        int[] bounds = displayBounds();
        int x1;
        int y1;
        int x2;
        int y2;
        int ms;
        try {
            x1 = Integer.parseInt(swipeX1Input.getText().toString().trim());
            y1 = Integer.parseInt(swipeY1Input.getText().toString().trim());
            x2 = Integer.parseInt(swipeX2Input.getText().toString().trim());
            y2 = Integer.parseInt(swipeY2Input.getText().toString().trim());
            ms = Integer.parseInt(swipeMsInput.getText().toString().trim());
        } catch (Exception e) {
            Toast.makeText(this, "Swipe values must be whole numbers.",
                    Toast.LENGTH_LONG).show();
            return;
        }
        if (!BridgeOps.validCoord(x1, bounds[0]) || !BridgeOps.validCoord(y1, bounds[1])
                || !BridgeOps.validCoord(x2, bounds[0]) || !BridgeOps.validCoord(y2, bounds[1])) {
            Toast.makeText(this, "Swipe coordinates must be inside the display.",
                    Toast.LENGTH_LONG).show();
            return;
        }
        if (!BridgeOps.validSwipeDuration(ms)) {
            Toast.makeText(this, "Swipe duration must be 100–5000 ms.",
                    Toast.LENGTH_LONG).show();
            return;
        }
        inject("Swipe",
                () -> BridgeControl.inputSwipe(this, BridgeControl.ORIGIN_UI,
                        x1, y1, x2, y2, ms));
    }

    private void sendText() {
        String raw = typeTextInput.getText().toString();
        String escaped = BridgeOps.escapeInputText(raw);
        if (!BridgeOps.validEscapedText(escaped)) {
            Toast.makeText(this, "Nothing typable was entered (empty, or only characters "
                    + "the input tool cannot type).", Toast.LENGTH_LONG).show();
            return;
        }
        inject("Type text",
                () -> BridgeControl.inputText(this, BridgeControl.ORIGIN_UI, escaped));
    }

    // ---- v1.35: Capture ----

    private void captureScreenshot() {
        shotStatus.setText("Capturing…");
        new Thread(() -> {
            ShellBridgeClient.Response r =
                    BridgeControl.screenshot(this, BridgeControl.ORIGIN_UI);
            File saved = null;
            String error = null;
            if (!r.ok) {
                error = r.error;
            } else if (r.data.isEmpty()) {
                error = "The bridge answered without image data.";
            } else {
                try {
                    byte[] png = Base64.decode(r.data, Base64.DEFAULT);
                    File dir = new File(getFilesDir(), "screenshots");
                    dir.mkdirs();
                    File[] old = dir.listFiles();
                    if (old != null) {
                        for (File f : old) f.delete();
                    }
                    saved = new File(dir, "shot-" + System.currentTimeMillis() + ".png");
                    try (OutputStream out = new FileOutputStream(saved)) {
                        out.write(png);
                    }
                } catch (Exception e) {
                    error = "The screenshot could not be saved: " + e.getMessage();
                }
            }
            final File done = saved;
            final String err = error;
            final String summary = r.ok ? r.text : null;
            ui.post(() -> {
                if (done == null) {
                    shotStatus.setText("Screenshot failed.\n" + err);
                    shotViewButton.setEnabled(false);
                    shotShareButton.setEnabled(false);
                    return;
                }
                lastScreenshot = done;
                shotStatus.setText(summary + "\nSaved privately in Control — only the "
                        + "latest is kept.");
                shotViewButton.setEnabled(true);
                shotShareButton.setEnabled(true);
                ping();
            });
        }, "bridge-screenshot").start();
    }

    private void viewScreenshot() {
        if (lastScreenshot == null || !lastScreenshot.isFile()) {
            Toast.makeText(this, "No screenshot is saved.", Toast.LENGTH_SHORT).show();
            return;
        }
        Uri uri = ImageShareProvider.register(lastScreenshot, lastScreenshot.getName());
        Intent view = new Intent(Intent.ACTION_VIEW);
        view.setDataAndType(uri, ImageShareProvider.PNG_MIME);
        view.setClipData(ClipData.newRawUri("", uri));
        view.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        try {
            startActivity(Intent.createChooser(view, "View screenshot"));
        } catch (Exception e) {
            Toast.makeText(this, "No app on this phone can view an image.",
                    Toast.LENGTH_LONG).show();
        }
    }

    private void shareScreenshot() {
        if (lastScreenshot == null || !lastScreenshot.isFile()) {
            Toast.makeText(this, "No screenshot is saved.", Toast.LENGTH_SHORT).show();
            return;
        }
        Uri uri = ImageShareProvider.register(lastScreenshot, lastScreenshot.getName());
        Intent send = new Intent(Intent.ACTION_SEND);
        send.setType(ImageShareProvider.PNG_MIME);
        send.putExtra(Intent.EXTRA_STREAM, uri);
        send.setClipData(ClipData.newRawUri("", uri));
        send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        try {
            startActivity(Intent.createChooser(send, "Share screenshot"));
        } catch (Exception e) {
            Toast.makeText(this, "No app on this phone can share an image.",
                    Toast.LENGTH_LONG).show();
        }
    }

    private void startRecording() {
        String[] labels = {"10 seconds", "30 seconds", "60 seconds"};
        int[] seconds = {10, 30, 60};
        new AlertDialog.Builder(this)
                .setTitle("Record the screen")
                .setItems(labels, (d, which) -> {
                    final int secs = seconds[which];
                    confirm(false, "Record the screen for " + labels[which] + "?",
                            "The recording saves to Movies, where the gallery shows it. "
                            + "A minute of video can be tens of megabytes. Stop ends it early.",
                            "Start recording",
                            () -> BridgeControl.screenrecordStart(this,
                                    BridgeControl.ORIGIN_UI, secs),
                            this::beginRecordPolling);
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void stopRecording() {
        recordPolling = false;
        ui.removeCallbacks(recordPoller);
        runCall("Stop recording",
                () -> BridgeControl.screenrecordStop(this, BridgeControl.ORIGIN_UI),
                this::pollRecordStatus);
    }

    private void beginRecordPolling() {
        recordPolling = true;
        recordStopButton.setEnabled(true);
        ui.postDelayed(recordPoller, 2000);
        pollRecordStatus();
    }

    /** One status poll: while RUNNING, keep polling every 2
     *  seconds; on FINISHED, name the path and size and stop.
     *  The poll is one bridge request — cheap, and it ends
     *  when the screen pauses. */
    private void pollRecordStatus() {
        new Thread(() -> {
            ShellBridgeClient.Response r = ShellBridgeClient.screenrecordStatus(this);
            final BridgeOps.LongTaskStatus status = r.ok
                    ? BridgeOps.parseLongTaskStatus(r.text) : null;
            final String error = r.ok ? null : r.error;
            final String note = r.ok
                    ? BridgeOps.parseKeyValues(r.text).get("note") : null;
            ui.post(() -> {
                if (status == null) {
                    recordStatus.setText("Recording status is unavailable.\n" + error);
                    recordPolling = false;
                    recordStopButton.setEnabled(false);
                    return;
                }
                if (status.isRunning()) {
                    recordStatus.setText("Recording… " + BridgeOps.humanSize(status.size)
                            + " so far.\nSaving to: " + status.path);
                    recordStopButton.setEnabled(true);
                    if (recordPolling) ui.postDelayed(recordPoller, 2000);
                } else if (status.exists()) {
                    recordPolling = false;
                    recordStopButton.setEnabled(false);
                    recordStatus.setText("Recording finished: "
                            + BridgeOps.humanSize(status.size)
                            + ".\nFind it in the gallery (Movies), or at:\n" + status.path
                            + (note == null ? "" : "\n" + note));
                } else {
                    recordPolling = false;
                    recordStopButton.setEnabled(false);
                    recordStatus.setText("No recording running.");
                }
            });
        }, "bridge-record-status").start();
    }

    // ---- v1.35: Power & scheduling ----

    /** Load the Doze state, the battery read-back, and the
     *  target app's standby bucket — sequential reads on one
     *  background thread. */
    private void loadPower() {
        dozeStatus.setText("Doze state: loading…");
        batteryStatus.setText("Battery: loading…");
        standbyStatus.setText("Standby bucket: loading…");
        new Thread(() -> {
            ShellBridgeClient.Response doze = ShellBridgeClient.deviceIdleState(this);
            final String dozeShown = doze.ok ? doze.text.trim() : "unavailable";
            ShellBridgeClient.Response batt = ShellBridgeClient.batteryGet(this);
            final String battShown = batt.ok
                    ? BridgeOps.batteryDisplay(BridgeOps.parseBattery(batt.text))
                    : "unavailable";
            String pkg = packageInput.getText().toString().trim();
            String standbyShown;
            if (pkg.isEmpty()) {
                standbyShown = "choose a target app first";
            } else {
                ShellBridgeClient.Response sb = ShellBridgeClient.standbyGet(this, pkg);
                standbyShown = sb.ok ? sb.text.trim() : "unavailable";
            }
            final String standby = standbyShown;
            ui.post(() -> {
                dozeStatus.setText("Doze state: " + dozeShown);
                batteryStatus.setText("Battery: " + battShown);
                standbyStatus.setText("Standby bucket for "
                        + (pkg.isEmpty() ? "the target app" : pkg) + ": " + standby);
            });
        }, "bridge-power").start();
    }

    private void dozeAction(String action) {
        new Thread(() -> {
            ShellBridgeClient.Response r = ShellBridgeClient.deviceIdleState(this);
            final String before = r.ok ? r.text.trim() : null;
            ui.post(() -> {
                Draft draft = draft("DOZE", "Doze state", before, action,
                        null, null, null, null);
                // Restore is the unconditional unforce, so the
                // entry stays restorable even when the
                // before-state could not be read.
                draft.restorable = true;
                draft.unrestorableReason = null;
                confirmJournaled(false, "Doze: " + action + "?",
                        "This forces Android's Doze machinery into a test state. "
                        + "Return to normal undoes it. Testing only.",
                        "Run", draft,
                        () -> BridgeControl.doze(this, BridgeControl.ORIGIN_UI, action),
                        this::loadPower);
            });
        }, "bridge-power").start();
    }

    private void setBatteryLevel() {
        int level;
        try {
            level = Integer.parseInt(batteryLevelInput.getText().toString().trim());
        } catch (Exception e) {
            Toast.makeText(this, "Battery level must be a whole number.",
                    Toast.LENGTH_LONG).show();
            return;
        }
        if (!BridgeOps.validBatteryLevel(level)) {
            Toast.makeText(this, "Battery level must be 0–100.",
                    Toast.LENGTH_LONG).show();
            return;
        }
        final int target = level;
        new Thread(() -> {
            ShellBridgeClient.Response r = ShellBridgeClient.batteryGet(this);
            final String before = r.ok
                    ? BridgeOps.batteryDisplay(BridgeOps.parseBattery(r.text)) : null;
            ui.post(() -> {
                Draft draft = draft("BATTERY_SET_LEVEL", "Battery",
                        before, String.valueOf(target), null, null, null, null);
                // Restore is the unconditional battery reset,
                // so the entry stays restorable even when the
                // before-value could not be read.
                draft.restorable = true;
                draft.unrestorableReason = null;
                confirmJournaled(false, "Simulate a battery level of " + target + "%?",
                        "Android will report " + target + "% until Reset, whatever the real charge is. "
                        + "Testing only.",
                        "Simulate", draft,
                        () -> BridgeControl.batterySetLevel(this, BridgeControl.ORIGIN_UI, target),
                        this::loadPower);
            });
        }, "bridge-power").start();
    }

    private void chooseStandbyBucket() {
        String pkg = targetPackage();
        if (pkg == null) return;
        String[] buckets = BridgeOps.STANDBY_BUCKETS.keySet().toArray(new String[0]);
        new AlertDialog.Builder(this)
                .setTitle("Standby bucket for " + targetName(pkg))
                .setItems(buckets, (d, which) -> {
                    String bucket = buckets[which];
                    new Thread(() -> {
                        ShellBridgeClient.Response r =
                                ShellBridgeClient.standbyGet(this, pkg);
                        final String readBack = r.ok ? r.text.trim() : null;
                        ui.post(() -> {
                            String previous = readBack != null
                                    && BridgeOps.STANDBY_BUCKETS.containsKey(readBack)
                                    ? readBack : null;
                            Draft draft = draft("STANDBY_SET", targetName(pkg),
                                    previous, bucket, pkg, null, null, null);
                            confirmJournaled(false, "Set the standby bucket for " + targetName(pkg)
                                            + " to " + bucket + "?",
                                    "Android throttles the app's background work according to the "
                                    + "bucket: ACTIVE runs freest, RESTRICTED most throttled.",
                                    "Set bucket", draft,
                                    () -> BridgeControl.standbySet(this, BridgeControl.ORIGIN_UI,
                                            pkg, bucket),
                                    this::loadPower);
                        });
                    }, "bridge-power").start();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void runJob() {
        String pkg = targetPackage();
        if (pkg == null) return;
        String jobId = jobIdInput.getText().toString().trim();
        if (!BridgeOps.validJobId(jobId)) {
            Toast.makeText(this, "Job id must be a non-negative whole number.",
                    Toast.LENGTH_LONG).show();
            return;
        }
        confirm(false, "Run job " + jobId + " of " + targetName(pkg) + " now?",
                "The job runs immediately, ignoring its schedule and constraints.",
                "Run job",
                () -> BridgeControl.runJob(this, BridgeControl.ORIGIN_UI, pkg, jobId));
    }

    // ---- v1.35: System config ----

    private void loadSystemConfig() {
        loadWifi();
        loadUsers();
    }

    private void readDeviceConfig() {
        String namespace = dcNamespaceInput.getText().toString().trim();
        String key = dcKeyInput.getText().toString().trim();
        if (!BridgeOps.validNamespace(namespace) || !BridgeOps.validConfigKey(key)) {
            Toast.makeText(this, "Enter a valid namespace and flag key first.",
                    Toast.LENGTH_LONG).show();
            return;
        }
        dcValueView.setText("Reading…");
        new Thread(() -> {
            ShellBridgeClient.Response r =
                    ShellBridgeClient.deviceConfigGet(this, namespace, key);
            final String shown = r.ok
                    ? (r.text.isEmpty() ? "(empty)" : r.text)
                    : "Unavailable: " + r.error;
            ui.post(() -> dcValueView.setText("Value: " + shown));
        }, "bridge-deviceconfig").start();
    }

    /** Set a flag from the manual form: the current value is
     *  read first so the strong confirm names old → new, then
     *  the write is verified by re-reading bridge-side and
     *  journaled. The long-press menu shares this path through
     *  setDeviceConfigWith. */
    private void setDeviceConfig() {
        String namespace = dcNamespaceInput.getText().toString().trim();
        String key = dcKeyInput.getText().toString().trim();
        String value = dcValueInput.getText().toString();
        if (!BridgeOps.validNamespace(namespace) || !BridgeOps.validConfigKey(key)) {
            Toast.makeText(this, "Enter a valid namespace and flag key first.",
                    Toast.LENGTH_LONG).show();
            return;
        }
        if (!BridgeOps.validConfigValue(value)) {
            Toast.makeText(this, "Value must be a single line of at most 512 characters.",
                    Toast.LENGTH_LONG).show();
            return;
        }
        new Thread(() -> {
            ShellBridgeClient.Response r =
                    ShellBridgeClient.deviceConfigGet(this, namespace, key);
            final String readBack = r.ok ? r.text : null;
            ui.post(() -> setDeviceConfigWith(namespace, key, value, readBack));
        }, "bridge-deviceconfig").start();
    }

    private void loadWifi() {
        wifiStatus.setText("Loading…");
        wifiRows.removeAllViews();
        wifiRaw.setText("");
        new Thread(() -> {
            ShellBridgeClient.Response r = ShellBridgeClient.wifiList(this);
            List<BridgeOps.WifiNetwork> nets = new ArrayList<>();
            String error = null;
            String raw = null;
            if (!r.ok) {
                error = r.error;
            } else {
                nets = BridgeOps.parseWifiNetworks(r.text);
                if (nets.isEmpty() && !r.text.trim().isEmpty()) raw = r.text;
            }
            final List<BridgeOps.WifiNetwork> done = nets;
            final String err = error;
            final String rawOut = raw;
            ui.post(() -> {
                if (err != null) {
                    wifiStatus.setText("Networks are unavailable.\n" + err);
                    return;
                }
                if (rawOut != null) {
                    wifiStatus.setText("The network list could not be parsed — "
                            + "the raw output is shown below.");
                    wifiRaw.setText(rawOut);
                    return;
                }
                if (done.isEmpty()) {
                    wifiStatus.setText("No saved networks were listed.");
                    return;
                }
                wifiStatus.setText(done.size() + " saved network"
                        + (done.size() == 1 ? "" : "s") + ".");
                for (BridgeOps.WifiNetwork net : done) wifiRows.addView(wifiRowView(net));
            });
        }, "bridge-wifi").start();
    }

    private View wifiRowView(BridgeOps.WifiNetwork net) {
        LinearLayout row = Ui.row(this);
        TextView label = Ui.text(this, net.ssid + "\nNetwork id " + net.id, 14, Ui.TEXT);
        row.addView(label);
        Button forget = Ui.smallButton(this, "Forget");
        forget.setOnClickListener(v -> confirmJournaled(false, "Forget \"" + net.ssid + "\"?",
                "The saved network is removed from the phone. To join it again, "
                + "re-enter its password in Settings.",
                "Forget",
                cannotUndoDraft("WIFI_FORGET", net.ssid,
                        "A forgotten network can only be re-added by joining it again in Settings.",
                        null, null, null, null),
                () -> BridgeControl.wifiForget(this, BridgeControl.ORIGIN_UI,
                        String.valueOf(net.id), net.ssid),
                this::loadWifi));
        row.addView(forget);
        return row;
    }

    private void loadUsers() {
        userStatus.setText("Loading…");
        userRows.removeAllViews();
        new Thread(() -> {
            ShellBridgeClient.Response r = ShellBridgeClient.usersList(this);
            ShellBridgeClient.Response cur = ShellBridgeClient.currentUser(this);
            List<BridgeOps.UserInfo> users = new ArrayList<>();
            String error = null;
            int current = -1;
            if (!r.ok) {
                error = r.error;
            } else {
                users = BridgeOps.parseUsers(r.text);
                if (users.isEmpty() && !r.text.trim().isEmpty()) {
                    error = "The user list could not be parsed:\n" + r.text;
                }
            }
            if (cur.ok) {
                try {
                    current = Integer.parseInt(cur.text.trim());
                } catch (Exception ignored) {
                }
            }
            final List<BridgeOps.UserInfo> done = users;
            final String err = error;
            final int currentId = current;
            ui.post(() -> {
                if (err != null) {
                    userStatus.setText("Users are unavailable.\n" + err);
                    return;
                }
                userStatus.setText(done.size() + " user" + (done.size() == 1 ? "" : "s")
                        + (currentId >= 0 ? " · current user: " + currentId : "") + ".");
                for (BridgeOps.UserInfo user : done) {
                    userRows.addView(userRowView(user, currentId));
                }
            });
        }, "bridge-users").start();
    }

    private View userRowView(BridgeOps.UserInfo user, int currentId) {
        LinearLayout row = Ui.row(this);
        String line = user.name + " · user " + user.id + (user.running ? " · running" : "");
        TextView label = Ui.text(this, line, 14, Ui.TEXT);
        row.addView(label);
        String refusal = BridgeOps.refuseRemoveUser(user.id, currentId);
        if (refusal != null) {
            TextView note = Ui.text(this, "Cannot be removed", 13, Ui.DIM);
            row.addView(note);
        } else {
            Button remove = Ui.smallButton(this, "Remove");
            remove.setOnClickListener(v -> confirmJournaled(true,
                    "Remove user " + user.id + " (" + user.name + ")?",
                    "DESTRUCTIVE: the user's apps, settings, and files are deleted. "
                    + "It cannot be undone.",
                    "Remove user",
                    cannotUndoDraft("USER_REMOVE", user.name + " (user " + user.id + ")",
                            "A removed user's apps, settings, and files are deleted.",
                            null, null, null, null),
                    () -> BridgeControl.userRemove(this, BridgeControl.ORIGIN_UI,
                            user.id, user.name),
                    this::loadUsers));
            row.addView(remove);
        }
        return row;
    }

    private void createUser() {
        String name = userNameInput.getText().toString().trim();
        if (!BridgeOps.validUserName(name)) {
            Toast.makeText(this, "User name must be 1–40 characters, no leading dash.",
                    Toast.LENGTH_LONG).show();
            return;
        }
        confirmJournaled(false, "Create a user named \"" + name + "\"?",
                "A new user profile is created on the phone. Switching to it happens "
                + "from the lock screen or Settings.",
                "Create user",
                cannotUndoDraft("USER_CREATE", name,
                        "A created user can only be removed with Remove — "
                        + "there is no undo that restores the previous state.",
                        null, null, null, null),
                () -> BridgeControl.userCreate(this, BridgeControl.ORIGIN_UI, name),
                this::loadUsers);
    }

    // ---- v1.35: Inspect ----

    private void chooseDumpsys() {
        new AlertDialog.Builder(this)
                .setTitle("Dumpsys service")
                .setItems(BridgeOps.DUMPSYS_SERVICES, (d, which) -> {
                    String service = BridgeOps.DUMPSYS_SERVICES[which];
                    dumpsysOutput.setText("Running dumpsys " + service + "…");
                    new Thread(() -> {
                        ShellBridgeClient.Response r =
                                ShellBridgeClient.dumpsys(this, service);
                        final String full = r.ok ? r.text : null;
                        final String error = r.ok ? null : r.error;
                        ui.post(() -> {
                            if (full == null) {
                                dumpsysOutput.setText("dumpsys " + service
                                        + " is unavailable.\n" + error);
                                dumpsysFull = "";
                                return;
                            }
                            dumpsysFull = full;
                            String shown = full;
                            if (shown.length() > 60_000) {
                                shown = shown.substring(shown.length() - 60_000)
                                        + "\n\n[Only the last 60,000 characters are shown "
                                        + "here — Copy and Share carry the full dump.]";
                            }
                            dumpsysOutput.setText("dumpsys " + service + "\n\n" + shown);
                        });
                    }, "bridge-dumpsys").start();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void copyDumpsys() {
        if (dumpsysFull.isEmpty()) {
            Toast.makeText(this, "No dump to copy yet.", Toast.LENGTH_SHORT).show();
            return;
        }
        ClipboardManager cm = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
        cm.setPrimaryClip(ClipData.newPlainText("dumpsys", dumpsysFull));
        Toast.makeText(this, "Dump copied.", Toast.LENGTH_SHORT).show();
    }

    private void shareDumpsys() {
        if (dumpsysFull.isEmpty()) {
            Toast.makeText(this, "No dump to share yet.", Toast.LENGTH_SHORT).show();
            return;
        }
        Intent send = new Intent(Intent.ACTION_SEND);
        send.setType("text/plain");
        send.putExtra(Intent.EXTRA_TEXT, dumpsysFull);
        try {
            startActivity(Intent.createChooser(send, "Share dump"));
        } catch (Exception e) {
            Toast.makeText(this, "No app on this phone can share text.",
                    Toast.LENGTH_LONG).show();
        }
    }

    private void startBugreport() {
        confirm(false, "Start a bugreport?",
                "This takes minutes and produces a large file (tens of megabytes) in "
                + "Downloads. It contains detailed system state — share it only with "
                + "someone you trust to see it.",
                "Start bugreport",
                () -> BridgeControl.bugreportStart(this, BridgeControl.ORIGIN_UI),
                this::beginBugreportPolling);
    }

    private void stopBugreport() {
        bugreportPolling = false;
        ui.removeCallbacks(bugreportPoller);
        runCall("Stop bugreport",
                () -> BridgeControl.bugreportStop(this, BridgeControl.ORIGIN_UI),
                this::pollBugreportStatus);
    }

    private void beginBugreportPolling() {
        bugreportPolling = true;
        bugreportStopButton.setEnabled(true);
        ui.postDelayed(bugreportPoller, 5000);
        pollBugreportStatus();
    }

    /** One bugreport status poll: while RUNNING, keep polling
     *  every 5 seconds; on FINISHED, name the path and size
     *  and stop. */
    private void pollBugreportStatus() {
        new Thread(() -> {
            ShellBridgeClient.Response r = ShellBridgeClient.bugreportStatus(this);
            final BridgeOps.LongTaskStatus status = r.ok
                    ? BridgeOps.parseLongTaskStatus(r.text) : null;
            final String error = r.ok ? null : r.error;
            final String note = r.ok
                    ? BridgeOps.parseKeyValues(r.text).get("note") : null;
            ui.post(() -> {
                if (status == null) {
                    bugreportStatus.setText("Bugreport status is unavailable.\n" + error);
                    bugreportPolling = false;
                    bugreportStopButton.setEnabled(false);
                    return;
                }
                if (status.isRunning()) {
                    bugreportStatus.setText("Bugreport running… "
                            + BridgeOps.humanSize(status.size) + " so far.\nWriting to: "
                            + status.path);
                    bugreportStopButton.setEnabled(true);
                    if (bugreportPolling) ui.postDelayed(bugreportPoller, 5000);
                } else if (status.exists()) {
                    bugreportPolling = false;
                    bugreportStopButton.setEnabled(false);
                    bugreportStatus.setText("Bugreport finished: "
                            + BridgeOps.humanSize(status.size)
                            + (status.size == 0
                            ? " — the file is empty, so the report likely failed."
                            : "")
                            + ".\nFind it in Downloads (Files app), or at:\n" + status.path
                            + (note == null ? "" : "\n" + note));
                } else {
                    bugreportPolling = false;
                    bugreportStopButton.setEnabled(false);
                    bugreportStatus.setText("No bugreport running.");
                }
            });
        }, "bridge-bugreport-status").start();
    }

    // ---- running processes ----

    /** Load the process list once, on demand: when the section
     *  expands or Refresh is tapped. Never a background poll.
     *  A bridge failure shows the honest error — never a
     *  spinner that never ends. */
    private void loadProcesses() {
        if (procLoading) return;
        procLoading = true;
        procStatus.setText("Loading processes…");
        procRows.removeAllViews();
        new Thread(() -> {
            ShellBridgeClient.Response r = ShellBridgeClient.processes(this);
            List<ProcessRules.Row> rows = null;
            String error = null;
            if (!r.ok) {
                error = r.error;
            } else {
                try {
                    Set<String> installed = new HashSet<>();
                    for (ApplicationInfo info
                            : getPackageManager().getInstalledApplications(0)) {
                        installed.add(info.packageName);
                    }
                    rows = ProcessRules.parse(r.text, installed);
                } catch (Exception e) {
                    error = e.getMessage();
                }
            }
            final List<ProcessRules.Row> done = rows;
            final String err = error;
            ui.post(() -> {
                procLoading = false;
                if (done == null) {
                    procLoaded = null;
                    procStatus.setText("Processes are unavailable.\n"
                            + (err == null ? "" : err));
                    return;
                }
                procLoaded = done;
                renderProcesses();
            });
        }, "bridge-processes").start();
    }

    /** Switch the group filter and re-render from the fetched
     *  list — no re-fetch. Refresh re-applies the selection
     *  because renderProcesses always reads procFilter. */
    private void setProcFilter(ProcessRules.Group filter) {
        procFilter = filter;
        updateFilterButtons();
        if (procLoaded != null) renderProcesses();
    }

    /** The selected segment reads as the active choice (accent
     *  fill); the others keep the plain small-button look. */
    private void updateFilterButtons() {
        styleFilterButton(filterAll, procFilter == null);
        styleFilterButton(filterControllable, procFilter == ProcessRules.Group.CONTROLLABLE);
        styleFilterButton(filterSystem, procFilter == ProcessRules.Group.SYSTEM);
    }

    private void styleFilterButton(Button b, boolean selected) {
        if (selected) {
            b.setBackground(Ui.rounded(this, Ui.ACCENT, 9));
            b.setTextColor(Ui.ACCENT_INK);
            b.setTypeface(Typeface.create("sans-serif", Typeface.BOLD));
        } else {
            b.setBackground(Ui.roundedStroke(this, Ui.SURFACE, 9, Ui.LINE));
            b.setTextColor(Ui.TEXT);
            b.setTypeface(Typeface.MONOSPACE);
        }
    }

    /** Render the fetched list under the current filter, capped
     *  at MAX_PROC_ROWS in ps order. An empty group shows the
     *  honest empty state, never a stale list. The status line
     *  names the fetched total and both group sizes, and says
     *  when the cap cut the rendered rows. */
    private void renderProcesses() {
        procRows.removeAllViews();
        List<ProcessRules.Row> visible = procFilter == null
                ? procLoaded
                : ProcessRules.inGroup(procLoaded, procFilter);
        int shown = Math.min(visible.size(), MAX_PROC_ROWS);
        procStatus.setText(ProcessRules.listStatus(procLoaded, procFilter, shown));
        if (visible.isEmpty()) {
            procRows.addView(Ui.body(this, ProcessRules.emptyGroupText(procFilter)));
            return;
        }
        for (int i = 0; i < shown; i++) {
            procRows.addView(processRowView(visible.get(i)));
        }
    }

    private View processRowView(ProcessRules.Row row) {
        TextView v = Ui.text(this, "", 15, Ui.TEXT);
        String name = (row.warning ? "⚠ " : "") + row.name;
        v.setText(name + "\n" + ProcessRules.statusLine(row));
        int pad = Ui.dp(this, 8);
        v.setPadding(0, pad, 0, pad);
        v.setOnClickListener(x -> showProcessDetails(row));
        v.setOnLongClickListener(x -> {
            showProcessMenu(v, row);
            return true;
        });
        return v;
    }

    private void showProcessDetails(ProcessRules.Row row) {
        String message = ProcessRules.detailText(row);
        if (row.packageName != null) {
            message = "App: " + InstalledApps.label(this, row.packageName)
                    + "\n" + message;
        }
        new AlertDialog.Builder(this)
                .setTitle(row.name)
                .setMessage(message)
                .setPositiveButton("Close", null)
                .show();
    }

    /** Long-press menu for one process. Details is always
     *  offered. Kill and Disable/Enable need a known package
     *  mapping — without one the menu says so, in a disabled
     *  item, instead of offering commands that cannot run.
     *  v1.36: Disable/Enable is not offered on a protected row
     *  while the override is off (a disabled item says why),
     *  and never on Control's own row; under the override a
     *  protected row's item names the risk. */
    /** The process long-press menu, decided by
     *  ProcessRules.menuItems: Details always; Kill and
     *  Enable/Disable on every row with a known package,
     *  reading fresh enabled-state at menu-open time; an
     *  explicit disabled explainer when no package is known
     *  or the row is Control itself. Enable/disable runs the
     *  exact governed path from the App state section. */
    private void showProcessMenu(View anchor, ProcessRules.Row row) {
        Boolean enabledState = null;
        if (row != null && row.packageName != null) {
            try {
                enabledState = packageEnabled(row.packageName);
            } catch (Exception ignored) {
                // The governed path refuses honestly if the
                // package vanished since the list loaded.
            }
        }
        PopupMenu menu = new PopupMenu(this, anchor);
        List<Runnable> actions = new ArrayList<>();
        int id = 0;
        for (ProcessRules.MenuItem item : ProcessRules.menuItems(row, enabledState)) {
            menu.getMenu().add(0, id, id, item.label).setEnabled(item.enabled);
            Runnable action = null;
            if (item.enabled) {
                if ("details".equals(item.action)) {
                    action = () -> showProcessDetails(row);
                } else if ("kill".equals(item.action)) {
                    action = () -> killProcess(row);
                } else if ("toggle".equals(item.action)) {
                    action = () -> toggleEnabledFor(row.packageName);
                }
            }
            actions.add(action);
            id++;
        }
        menu.setOnMenuItemClickListener(item -> {
            int itemId = item.getItemId();
            if (itemId >= 0 && itemId < actions.size() && actions.get(itemId) != null) {
                actions.get(itemId).run();
                return true;
            }
            return false;
        });
        menu.show();
    }

    /**
     * Kill one process, through the existing governed kill path:
     * FORCE_STOP on the process's package. Android gives the
     * shell user no per-process kill (kill() needs the target's
     * UID or a capability shell does not have), so the app is
     * stopped as a whole — the confirmation dialog says exactly
     * that, names the process and PID, and carries the warning
     * for system or refuse-listed targets. A refused target is
     * refused by the bridge with its reason, and the attempt is
     * audited like every other control.
     */
    private void killProcess(ProcessRules.Row row) {
        String pkg = row.packageName;
        String warning = "";
        if (row.refuseReason != null) {
            warning = "The bridge will refuse this — " + row.refuseReason
                    + " The attempt is still written to the audit log.\n\n";
        } else if (row.systemUser) {
            warning = "This is a system process, not an app process.\n\n";
        }
        boolean strong = isSystemApp(pkg)
                || BridgeControl.REFUSE_DESTRUCTIVE.contains(pkg);
        confirm(strong, "Kill " + row.name + " (PID " + row.pid + ")?",
                warning + "This force-stops " + targetName(pkg)
                        + ": every process of the app ends, including PID "
                        + row.pid + ". Android gives the shell user no "
                        + "per-process kill, so the app is stopped as a "
                        + "whole. It can be opened again normally.",
                "Kill",
                () -> BridgeControl.forceStop(this, BridgeControl.ORIGIN_UI, pkg));
    }

    private void confirm(boolean strong, String title, String message,
                         String actionLabel, BridgeCall call) {
        confirm(strong, title, message, actionLabel, call, null);
    }

    /** Confirm, then run, then optionally refresh the section
     *  the action belongs to (the `after` runnable runs on the
     *  UI thread after the result posts). */
    private void confirm(boolean strong, String title, String message,
                         String actionLabel, BridgeCall call, Runnable after) {
        confirmAction(strong, title, message, actionLabel,
                () -> runCall(title, call, after));
    }

    /** The confirmation itself, factored out (v1.37) so the
     *  journaled path shares the exact dialogs: a normal
     *  confirm, or the press-and-hold strong confirm. The
     *  audit sentence is unchanged. */
    private void confirmAction(boolean strong, String title, String message,
                               String actionLabel, Runnable onConfirm) {
        if (!strong) {
            new AlertDialog.Builder(this)
                    .setTitle(title)
                    .setMessage(message + "\n\nThis attempt will be written to the audit log.")
                    .setNegativeButton("Cancel", null)
                    .setPositiveButton(actionLabel, (d, w) -> onConfirm.run())
                    .show();
            return;
        }
        strongConfirm(title, message, actionLabel, onConfirm);
    }

    /** Confirm, then run a journaled write: the entry is
     *  persisted before the write runs and finalized only on
     *  success (see runJournaled). Confirmations are identical
     *  to confirm() — journaling never changes governance. */
    private void confirmJournaled(boolean strong, String title, String message,
                                  String actionLabel, Draft draft, BridgeCall call) {
        confirmJournaled(strong, title, message, actionLabel, draft, call, null);
    }

    private void confirmJournaled(boolean strong, String title, String message,
                                  String actionLabel, Draft draft, BridgeCall call,
                                  Runnable after) {
        confirmAction(strong, title, message, actionLabel,
                () -> runJournaled(title, draft, call, after));
    }

    /** Press-and-hold-3-seconds confirmation that runs any
     *  runnable. Bridge calls go through confirm(); the
     *  protected-packages switch uses this directly, since
     *  enabling it is a pref flip, not a bridge call — and the
     *  flip is audited, so the audit sentence below stays true.
     *  The content rides a ScrollView (v1.38): a custom dialog
     *  view does not scroll on its own, and long protected
     *  confirm copy overflowed the dialog with no way to read
     *  it. The hold button scrolls with the text; a stationary
     *  press still holds, because the ScrollView only steals
     *  the touch once the finger moves. */
    private void strongConfirm(String title, String message,
                               String actionLabel, Runnable onConfirm) {
        LinearLayout box = Ui.column(this);
        int p = Ui.dp(this, 18);
        box.setPadding(p, p, p, p);
        TextView body = Ui.body(this, message
                + "\n\nStrong confirmation: press and hold the button below for 3 seconds. "
                + "This attempt will be written to the audit log.");
        box.addView(body);
        Button hold = Ui.button(this, "Press and hold to confirm", false);
        box.addView(hold, Ui.wrap(0, 14, 0, 0, this));
        ScrollView scroll = new ScrollView(this);
        scroll.addView(box);
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(title)
                .setView(scroll)
                .setNegativeButton("Cancel", null)
                .create();
        Runnable fire = () -> {
            dialog.dismiss();
            onConfirm.run();
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

    private void runCall(String title, BridgeCall call) {
        runCall(title, call, null);
    }

    private void runCall(String title, BridgeCall call, Runnable after) {
        runCallWithResult(title, call, after, true, null);
    }

    /** The result of one bridge call, for callers that need to
     *  act on success/failure (the journal finalizes here). */
    private interface ResultCallback {
        void onResult(boolean ok, String text, String error);
    }

    /** Run one call and report in the Result card. Confirmed
     *  actions scroll the single view to the Result card;
     *  rapid-fire injections (scroll=false) report without
     *  yanking the view. The processes list always refreshes:
     *  in the single view it is always visible, and an
     *  enable/disable must show at once. */
    private void runCallWithResult(String title, BridgeCall call, Runnable after,
                                   boolean scroll, ResultCallback done) {
        if (scroll) scrollToResult();
        output.setText(title + "\n\nWorking…");
        new Thread(() -> {
            ShellBridgeClient.Response r = call.run();
            ui.post(() -> {
                output.setText(title + "\n\n" + (r.ok ? r.text : "Refused / failed: " + r.error));
                ping();
                refreshTarget();
                loadProcesses();
                if (after != null) after.run();
                if (done != null) done.onResult(r.ok, r.text, r.error);
            });
        }, "bridge-control").start();
    }

    // ---- v1.37: change journal ----

    /** A journal entry not yet recorded: the op, target,
     *  before/after values, and restore routing. Built by the
     *  control that is about to write, with the before-value
     *  read fresh first. */
    private static final class Draft {
        String op;
        String target;
        /** Raw previous value, restore-ready; null = unknown. */
        String previous;
        /** Raw new value, restore-ready. */
        String current;
        boolean restorable = true;
        String unrestorableReason;
        String pkg;
        String namespace;
        String key;
        /** Op-specific restore payload: permission/AppOp. */
        String detail;
    }

    /** A draft for a write whose before-value was read: null
     *  previous means the read failed, and restore refuses
     *  honestly. */
    private Draft draft(String op, String target, String previous, String current,
                        String pkg, String namespace, String key, String detail) {
        return draft(op, target, previous, current,
                "The previous value could not be read.",
                pkg, namespace, key, detail);
    }

    private Draft draft(String op, String target, String previous, String current,
                        String unknownReason,
                        String pkg, String namespace, String key, String detail) {
        Draft d = new Draft();
        d.op = op;
        d.target = target;
        d.previous = previous;
        d.current = current;
        d.pkg = pkg;
        d.namespace = namespace;
        d.key = key;
        d.detail = detail;
        if (previous == null) {
            d.restorable = false;
            d.unrestorableReason = unknownReason;
        }
        return d;
    }

    /** A draft for an action with no meaningful restore: the
     *  entry is recorded, marked unrestorable, and never
     *  fakes a restore. */
    private Draft cannotUndoDraft(String op, String target, String reason,
                                  String pkg, String namespace, String key,
                                  String detail) {
        Draft d = new Draft();
        d.op = op;
        d.target = target;
        d.restorable = false;
        d.unrestorableReason = reason;
        d.pkg = pkg;
        d.namespace = namespace;
        d.key = key;
        d.detail = detail;
        if ("CLEAR_APP_DATA".equals(op)) d.current = "cleared";
        else if ("WIFI_FORGET".equals(op)) d.current = "forgotten";
        else if ("USER_REMOVE".equals(op)) d.current = "removed";
        else if ("TRIM_CACHES".equals(op)) d.current = "trimmed";
        else if ("USER_CREATE".equals(op)) d.current = "created";
        else if ("INSTALL_APK".equals(op)) d.current = "installed";
        return d;
    }

    /** Run a journaled write: the entry is persisted BEFORE
     *  the write runs, finalized only when the write reports
     *  success, and dropped on failure/refusal — nothing
     *  changed then, and the audit log holds the attempt. */
    private void runJournaled(String title, Draft draft, BridgeCall call, Runnable after) {
        final ChangeJournal.Entry entry = journal.record(draft.op, draft.target,
                draft.previous, draft.current, draft.restorable,
                draft.unrestorableReason, draft.pkg, draft.namespace,
                draft.key, draft.detail);
        runCallWithResult(title, call, after, true, (ok, text, error) -> {
            if (ok) journal.markApplied(entry.id);
            else journal.drop(entry.id);
            refreshHistory();
            refreshSettingRestoreButtons();
        });
    }

    /** Run one restore: the inverse call, with no new journal
     *  entry. Success marks the entry restored; failure leaves
     *  it unrestored so it can be tried again. */
    private void runRestore(String title, ChangeJournal.Entry entry, BridgeCall call,
                            Runnable after) {
        runCallWithResult(title, call, after, true, (ok, text, error) -> {
            if (ok) journal.markRestored(entry.id);
            refreshHistory();
            refreshSettingRestoreButtons();
        });
    }

    private void confirmRestore(boolean strong, String title, String message,
                                String actionLabel, ChangeJournal.Entry entry,
                                BridgeCall call, Runnable after) {
        confirmAction(strong, title, message, actionLabel,
                () -> runRestore(title, entry, call, after));
    }

    /** Restore one history entry through the same governed op
     *  that made the change, with the same confirmations.
     *  Unrestorable entries refuse with their reason. */
    private void restoreEntry(ChangeJournal.Entry entry) {
        if (entry == null || entry.restored) return;
        if (!entry.restorable) {
            Toast.makeText(this,
                    entry.unrestorableReason != null ? entry.unrestorableReason
                            : "This change cannot be undone.",
                    Toast.LENGTH_LONG).show();
            return;
        }
        if ("SET_APP_ENABLED".equals(entry.op) && entry.pkg != null) {
            enableDisableFlow(entry.pkg, "true".equals(entry.previous), entry);
            return;
        }
        BridgeCall call = restoreCallFor(entry);
        if (call == null) {
            Toast.makeText(this, "No restore is available for this change.",
                    Toast.LENGTH_LONG).show();
            return;
        }
        String was = ChangeJournal.formatValue(entry, entry.previous);
        String now = ChangeJournal.formatValue(entry, entry.current);
        confirmRestore("DEVICE_CONFIG_PUT".equals(entry.op),
                "Restore " + entry.target + "?",
                "This writes the recorded before-value back through the same control.\n\n"
                        + "Now: " + now + "\nRestore to: " + was + ".",
                "Restore", entry, call, afterFor(entry));
    }

    /** The group refresh that follows one restore. */
    private Runnable afterFor(ChangeJournal.Entry entry) {
        if ("DEVICE_CONFIG_PUT".equals(entry.op)) return this::loadFlags;
        if ("SETTING_PUT".equals(entry.op)
                || "SET_LAUNCHER_SETTING".equals(entry.op)
                || entry.op.startsWith("WM_")
                || "NIGHT_SET".equals(entry.op)) return this::loadDisplay;
        if (entry.op.startsWith("BATTERY_")
                || "DOZE".equals(entry.op)
                || "STANDBY_SET".equals(entry.op)) return this::loadPower;
        if ("UNINSTALL_USER".equals(entry.op)) return this::loadRemoved;
        return null;
    }

    /** The inverse bridge call for one entry, or null when the
     *  entry carries no restore. Every branch reuses the
     *  governed BridgeControl path — refuse-lists, override
     *  rules, verification, and audit markers all apply
     *  exactly as on the forward write. */
    private BridgeCall restoreCallFor(ChangeJournal.Entry entry) {
        switch (entry.op) {
            case "SET_APP_ENABLED":
                return () -> BridgeControl.setAppEnabled(this, BridgeControl.ORIGIN_UI,
                        entry.pkg, "true".equals(entry.previous));
            case "UNINSTALL_USER":
                return () -> BridgeControl.installExisting(this, BridgeControl.ORIGIN_UI,
                        entry.pkg);
            case "SETTING_PUT":
                return () -> BridgeControl.settingPut(this, BridgeControl.ORIGIN_UI,
                        entry.namespace, entry.key, entry.previous);
            case "SET_LAUNCHER_SETTING":
                return () -> BridgeControl.setLauncherSetting(this, BridgeControl.ORIGIN_UI,
                        entry.key, entry.previous);
            case "DEVICE_CONFIG_PUT":
                return () -> BridgeControl.deviceConfigPut(this, BridgeControl.ORIGIN_UI,
                        entry.namespace, entry.key, entry.previous);
            case "WM_SET_SIZE": {
                String[] wh = entry.previous.split("x");
                if (wh.length != 2) return null;
                try {
                    int w = Integer.parseInt(wh[0]);
                    int h = Integer.parseInt(wh[1]);
                    return () -> BridgeControl.wmSetSize(this, BridgeControl.ORIGIN_UI, w, h);
                } catch (Exception e) {
                    return null;
                }
            }
            case "WM_SET_DENSITY":
                try {
                    int d = Integer.parseInt(entry.previous);
                    return () -> BridgeControl.wmSetDensity(this, BridgeControl.ORIGIN_UI, d);
                } catch (Exception e) {
                    return null;
                }
            case "WM_SET_OVERSCAN": {
                String[] parts = entry.previous.split(",");
                if (parts.length != 4) return null;
                try {
                    int l = Integer.parseInt(parts[0]);
                    int t = Integer.parseInt(parts[1]);
                    int r = Integer.parseInt(parts[2]);
                    int b = Integer.parseInt(parts[3]);
                    return () -> BridgeControl.wmSetOverscan(this, BridgeControl.ORIGIN_UI,
                            l, t, r, b);
                } catch (Exception e) {
                    return null;
                }
            }
            case "NIGHT_SET":
                return () -> BridgeControl.nightSet(this, BridgeControl.ORIGIN_UI,
                        entry.previous);
            case "BATTERY_SET_LEVEL":
            case "BATTERY_UNPLUG":
                // Restore is unconditional: reset reports the
                // real battery state again, whatever it was.
                return () -> BridgeControl.batteryReset(this, BridgeControl.ORIGIN_UI);
            case "DOZE":
                return () -> BridgeControl.doze(this, BridgeControl.ORIGIN_UI, "unforce");
            case "STANDBY_SET":
                return () -> BridgeControl.standbySet(this, BridgeControl.ORIGIN_UI,
                        entry.pkg, entry.previous);
            case "GRANT_PERMISSION":
                return () -> BridgeControl.revokePermission(this, BridgeControl.ORIGIN_UI,
                        entry.pkg, entry.detail);
            case "REVOKE_PERMISSION":
                return () -> BridgeControl.grantPermission(this, BridgeControl.ORIGIN_UI,
                        entry.pkg, entry.detail);
            case "SET_APP_OP":
                return () -> BridgeControl.setAppOp(this, BridgeControl.ORIGIN_UI,
                        entry.pkg, entry.detail, entry.previous);
            case "SET_PRIVATE_DNS": {
                String raw = entry.previous == null ? "" : entry.previous;
                int bar = raw.indexOf('|');
                String mode = bar < 0 ? raw : raw.substring(0, bar);
                String host = bar < 0 ? "" : raw.substring(bar + 1);
                return () -> BridgeControl.setPrivateDns(this, BridgeControl.ORIGIN_UI,
                        mode, host);
            }
            default:
                return null;
        }
    }

    /** Replay a restore plan in order (newest first), one
     *  entry at a time on a worker thread, reporting every
     *  result honestly. A refused entry does not stop the
     *  replay — the rest still run, and the failures are
     *  listed. Restores never append journal entries. */
    private void replayEntries(List<ChangeJournal.Entry> plan, String title) {
        scrollToResult();
        output.setText(title + "\n\nWorking through " + plan.size()
                + " change" + (plan.size() == 1 ? "" : "s") + "…");
        new Thread(() -> {
            StringBuilder report = new StringBuilder();
            int okCount = 0;
            for (ChangeJournal.Entry entry : plan) {
                BridgeCall call = restoreCallFor(entry);
                if (call == null) {
                    report.append("✗ ").append(ChangeJournal.opLabel(entry.op))
                            .append(" — ").append(entry.target)
                            .append(": no restore available.\n");
                    continue;
                }
                ShellBridgeClient.Response r;
                try {
                    r = call.run();
                } catch (Exception e) {
                    r = null;
                }
                if (r != null && r.ok) {
                    journal.markRestored(entry.id);
                    okCount++;
                    report.append("✓ ").append(ChangeJournal.opLabel(entry.op))
                            .append(" — ").append(entry.target)
                            .append(": restored to ")
                            .append(ChangeJournal.formatValue(entry, entry.previous))
                            .append(".\n");
                } else {
                    report.append("✗ ").append(ChangeJournal.opLabel(entry.op))
                            .append(" — ").append(entry.target)
                            .append(": ")
                            .append(r == null ? "failed" : r.error)
                            .append("\n");
                }
            }
            final String done = report.toString();
            final int restored = okCount;
            ui.post(() -> {
                output.setText(title + "\n\nRestored " + restored + " of " + plan.size()
                        + ".\n\n" + done);
                ping();
                refreshTarget();
                loadProcesses();
                loadDisplay();
                loadPower();
                loadFlags();
                loadRemoved();
                refreshHistory();
                refreshSettingRestoreButtons();
            });
        }, "bridge-restore").start();
    }

    /** Restore every unrestored, restorable change, newest
     *  first, under one strong confirm. */
    private void restoreAllChanges() {
        List<ChangeJournal.Entry> plan = journal.restorePlan();
        int skipped = 0;
        for (ChangeJournal.Entry e : journal.newestFirst()) {
            if (!e.restored && !e.restorable) skipped++;
        }
        if (plan.isEmpty()) {
            Toast.makeText(this, "No restorable changes are recorded.",
                    Toast.LENGTH_LONG).show();
            return;
        }
        String message = "This writes the recorded before-value back for "
                + plan.size() + " change" + (plan.size() == 1 ? "" : "s")
                + ", newest first, through the same governed controls.";
        if (skipped > 0) {
            message += " " + skipped + " recorded change" + (skipped == 1 ? "" : "s")
                    + " cannot be undone and will be skipped.";
        }
        confirmAction(true, "Restore all changes?", message, "Restore all",
                () -> replayEntries(plan, "Restore all changes"));
    }

    /** Reset every journaled flag/setting to its recorded
     *  before-value, newest first, under one strong confirm.
     *  "Defaults" are the values recorded before the app first
     *  changed each flag — untouched flags stay untouched. */
    private void resetAllFlags() {
        List<ChangeJournal.Entry> plan = journal.flagRestorePlan();
        int skipped = 0;
        for (ChangeJournal.Entry e : journal.newestFirst()) {
            if (!e.restored && !e.restorable && ChangeJournal.isFlagOp(e.op)) skipped++;
        }
        if (plan.isEmpty()) {
            Toast.makeText(this, "No flag or setting change is recorded yet.",
                    Toast.LENGTH_LONG).show();
            return;
        }
        String message = "This restores " + plan.size() + " flag or setting change"
                + (plan.size() == 1 ? "" : "s")
                + " to the value recorded before this app first changed each one, "
                + "newest first. Flags the app never changed are untouched; flags "
                + "changed outside the app can't be known.";
        if (skipped > 0) {
            message += " " + skipped + " recorded flag change" + (skipped == 1 ? "" : "s")
                    + " cannot be undone and will be skipped.";
        }
        confirmAction(true, "Reset all flags to defaults?", message, "Reset all flags",
                () -> replayEntries(plan, "Reset all flags to defaults"));
    }

    // ---- v1.37: history + flags UI ----

    /** Rebuild the Change history group from the journal,
     *  newest first, capped at 100 rendered rows. */
    private void refreshHistory() {
        if (historyRows == null) return;
        List<ChangeJournal.Entry> entries = journal.newestFirst();
        historyRows.removeAllViews();
        int shown = Math.min(entries.size(), 100);
        for (int i = 0; i < shown; i++) {
            historyRows.addView(historyRowView(entries.get(i)));
        }
        if (entries.isEmpty()) {
            historyStatus.setText("No changes recorded yet.");
        } else {
            historyStatus.setText(entries.size() + " recorded change"
                    + (entries.size() == 1 ? "" : "s")
                    + (entries.size() > shown
                    ? " — showing the newest " + shown : "")
                    + ".");
        }
        boolean anyRestorable = false;
        for (ChangeJournal.Entry e : entries) {
            if (!e.restored && e.restorable) {
                anyRestorable = true;
                break;
            }
        }
        restoreAllButton.setEnabled(anyRestorable);
    }

    private View historyRowView(ChangeJournal.Entry entry) {
        LinearLayout row = Ui.row(this);
        TextView label = Ui.text(this, ChangeJournal.describe(entry), 14, Ui.TEXT);
        row.addView(label);
        if (!entry.restored && entry.restorable) {
            Button restore = Ui.smallButton(this, "Restore");
            restore.setOnClickListener(v -> restoreEntry(entry));
            row.addView(restore);
        }
        return row;
    }

    /** Show each Display-editor Restore button only while a
     *  recorded default exists for that key. */
    private void refreshSettingRestoreButtons() {
        if (settingRestoreButtons == null) return;
        for (int i = 0; i < BridgeOps.SETTINGS.length; i++) {
            BridgeOps.SettingDef def = BridgeOps.SETTINGS[i];
            String recorded = journal.recordedDefault(def.namespace, def.key);
            settingRestoreButtons[i].setVisibility(
                    recorded != null ? View.VISIBLE : View.GONE);
        }
    }

    /** Restore one governed setting to its recorded default:
     *  the earliest before-value the journal holds for it. A
     *  normal confirm, like other setting changes. The entries
     *  are marked restored only when the write succeeds — a
     *  refusal leaves them restorable. */
    private void restoreSetting(BridgeOps.SettingDef def) {
        String recorded = journal.recordedDefault(def.namespace, def.key);
        if (recorded == null) return;
        String label = BridgeOps.settingDisplay(def, recorded);
        confirmAction(false, "Restore " + def.label + " to " + label + "?",
                "This writes the value recorded before this app first changed "
                + def.label + " back through the same control.",
                "Restore",
                () -> runCallWithResult("Restore " + def.label + " to " + label + "?",
                        () -> BridgeControl.settingPut(this, BridgeControl.ORIGIN_UI,
                                def.namespace, def.key, recorded),
                        null, true, (ok, text, error) -> {
                            if (ok) journal.markRestoredFor(def.namespace, def.key);
                            refreshHistory();
                            refreshSettingRestoreButtons();
                            loadDisplay();
                        }));
    }

    /** Fetch the flag list and render it grouped by category.
     *  The unscoped list may return namespace names only — in
     *  that case each namespace is fetched separately (capped
     *  at 200). Unparseable output is shown raw, never guessed. */
    /** Populate the namespace picker (v1.38): one unscoped
     *  list call reveals the namespaces — from flag lines or
     *  bare names, whichever shape the build answers with.
     *  The picker defaults to the first namespace, or the
     *  last-picked one when it is still listed, and the
     *  choice persists. */
    private void loadFlagNamespaces() {
        flagStatus.setText("Loading…");
        flagRows.removeAllViews();
        flagRaw.setText("");
        new Thread(() -> {
            ShellBridgeClient.Response r =
                    ShellBridgeClient.deviceConfigList(this, "");
            List<String> names = new ArrayList<>();
            String error = null;
            if (!r.ok) {
                error = r.error;
            } else {
                names.addAll(ConfigFlags.sortedNamespaces(
                        ConfigFlags.parse(r.text, null)));
            }
            final List<String> done = names;
            final String err = error;
            ui.post(() -> {
                if (err != null) {
                    flagStatus.setText("Flags are unavailable.\n" + err);
                    return;
                }
                if (done.isEmpty()) {
                    flagStatus.setText("No flag namespaces were listed.");
                    return;
                }
                int shown = Math.min(done.size(), 200);
                populatingNamespaces = true;
                flagNamespaceAdapter.clear();
                for (int i = 0; i < shown; i++) {
                    flagNamespaceAdapter.add(done.get(i));
                }
                String last = Prefs.get(this)
                        .getString(Prefs.KEY_LAST_FLAG_NAMESPACE, null);
                int index = last == null ? 0 : flagNamespaceAdapter.getPosition(last);
                if (index < 0) index = 0;
                flagNamespaceSpinner.setSelection(index);
                populatingNamespaces = false;
                flagNote.setText(done.size() > shown
                        ? "First 200 of " + done.size() + " namespaces."
                        : "");
                currentFlagNamespace = flagNamespaceAdapter.getItem(index);
                Prefs.get(this).edit()
                        .putString(Prefs.KEY_LAST_FLAG_NAMESPACE, currentFlagNamespace)
                        .apply();
                dcNamespaceInput.setText(currentFlagNamespace);
                loadFlags();
            });
        }, "bridge-deviceconfig").start();
    }

    /** Load the flags of the picker's namespace only (v1.38).
     *  The namespace comes from the picker, never from the
     *  manual-form input: picking a namespace lists it. With
     *  no namespaces loaded yet, this loads the picker first. */
    private void loadFlags() {
        String namespace = currentFlagNamespace;
        if (namespace == null || namespace.isEmpty()) {
            if (flagNamespaceAdapter != null && flagNamespaceAdapter.getCount() > 0) {
                namespace = flagNamespaceAdapter.getItem(0);
            } else {
                loadFlagNamespaces();
                return;
            }
        }
        final String ns = namespace;
        currentFlagNamespace = ns;
        flagStatus.setText("Loading…");
        flagRows.removeAllViews();
        flagRaw.setText("");
        new Thread(() -> {
            ShellBridgeClient.Response r =
                    ShellBridgeClient.deviceConfigList(this, ns);
            List<ConfigFlags.Flag> flags = new ArrayList<>();
            String error = null;
            String raw = null;
            if (!r.ok) {
                error = r.error;
            } else {
                ConfigFlags.ParseResult parsed = ConfigFlags.parse(r.text, ns);
                if (parsed.sawFlags()) {
                    flags.addAll(parsed.flags);
                } else if (!r.text.trim().isEmpty()) {
                    raw = r.text;
                }
            }
            final List<ConfigFlags.Flag> done = flags;
            final String err = error;
            final String rawOut = raw;
            ui.post(() -> {
                if (err != null) {
                    flagStatus.setText("Flags are unavailable.\n" + err);
                    return;
                }
                if (rawOut != null && done.isEmpty()) {
                    flagStatus.setText("The flag list could not be parsed.");
                    flagRaw.setText(rawOut);
                    return;
                }
                renderFlags(done);
                flagStatus.setText(done.size() + " flag" + (done.size() == 1 ? "" : "s"));
                if (rawOut != null) flagRaw.setText(rawOut);
                refreshSettingRestoreButtons();
            });
        }, "bridge-deviceconfig").start();
    }

    /** Render one namespace's flags (v1.38): alphabetical by
     *  key, capped at 400 rows. The namespace header is the
     *  picker itself, so no category headers are rendered. */
    private void renderFlags(List<ConfigFlags.Flag> flags) {
        flagRows.removeAllViews();
        List<ConfigFlags.Group> groups = ConfigFlags.group(flags);
        int rendered = 0;
        for (ConfigFlags.Group group : groups) {
            for (ConfigFlags.Flag flag : group.flags) {
                if (rendered >= 400) break;
                flagRows.addView(flagRowView(flag));
                rendered++;
            }
            if (rendered >= 400) break;
        }
        int total = ConfigFlags.totalFlags(groups);
        if (total > rendered) {
            flagRows.addView(Ui.body(this,
                    "Showing " + rendered + " of " + total + " flags."));
        }
    }

    private View flagRowView(ConfigFlags.Flag flag) {
        LinearLayout row = Ui.row(this);
        TextView label = Ui.text(this, flag.key + "\n"
                + ConfigFlags.displayValue(flag.value), 14, Ui.TEXT);
        label.setTypeface(Typeface.MONOSPACE);
        label.setLayoutParams(new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        row.addView(label);
        if (ConfigFlags.isBooleanValue(flag.value)) {
            Switch toggle = new Switch(this);
            toggle.setChecked("true".equals(flag.value));
            // The switch reverts on tap: the flip commits only
            // through the strong confirm + verified write, and
            // the list reloads to show the real value.
            toggle.setOnClickListener(v -> {
                toggle.setChecked("true".equals(flag.value));
                toggleFlag(flag);
            });
            row.addView(toggle);
        }
        row.setOnClickListener(v -> {
            dcNamespaceInput.setText(flag.namespace);
            dcKeyInput.setText(flag.key);
            readDeviceConfig();
        });
        row.setOnLongClickListener(v -> {
            showFlagMenu(row, flag);
            return true;
        });
        return row;
    }

    /** Flip a true/false flag (v1.38): a FRESH read first —
     *  the listed value may be stale — then the same strong
     *  confirm + journaling + re-read verification as Change
     *  value. A flag that no longer holds true/false reloads
     *  the list instead of guessing. */
    private void toggleFlag(ConfigFlags.Flag flag) {
        new Thread(() -> {
            ShellBridgeClient.Response r = ShellBridgeClient.deviceConfigGet(
                    this, flag.namespace, flag.key);
            final String current = r.ok ? r.text : null;
            ui.post(() -> {
                String flipped = ConfigFlags.toggleValue(current);
                if (flipped == null) {
                    Toast.makeText(this, "That flag no longer has a true/false value.",
                            Toast.LENGTH_LONG).show();
                    loadFlags();
                    return;
                }
                setDeviceConfigWith(flag.namespace, flag.key, flipped, current);
            });
        }, "bridge-deviceconfig").start();
    }

    /** The flag long-press menu: change the value, or reset
     *  to the recorded default when the journal holds one. */
    private void showFlagMenu(View anchor, ConfigFlags.Flag flag) {
        PopupMenu menu = new PopupMenu(this, anchor);
        menu.getMenu().add("Change value…").setOnMenuItemClickListener(
                item -> {
                    changeFlagValue(flag);
                    return true;
                });
        String recorded = journal.recordedDefault(flag.namespace, flag.key);
        if (recorded != null) {
            menu.getMenu().add("Reset to recorded default").setOnMenuItemClickListener(
                    item -> {
                        resetFlagToDefault(flag, recorded);
                        return true;
                    });
        }
        menu.show();
    }

    /** Change one flag from its row: the edit dialog pre-fills
     *  from a FRESH read, then the existing strong confirm +
     *  journaling apply. */
    private void changeFlagValue(ConfigFlags.Flag flag) {
        new Thread(() -> {
            ShellBridgeClient.Response r = ShellBridgeClient.deviceConfigGet(
                    this, flag.namespace, flag.key);
            final String current = r.ok ? r.text : null;
            ui.post(() -> {
                LinearLayout box = Ui.column(this);
                int p = Ui.dp(this, 18);
                box.setPadding(p, p, p, p);
                EditText valueInput = Ui.input(this, "New value (blank clears the flag)");
                if (current != null && !"null".equals(current)) {
                    valueInput.setText(current);
                }
                box.addView(valueInput);
                new AlertDialog.Builder(this)
                        .setTitle(flag.namespace + " / " + flag.key)
                        .setView(box)
                        .setNegativeButton("Cancel", null)
                        .setPositiveButton("Set flag", (d, w) -> {
                            String value = valueInput.getText().toString();
                            if (!BridgeOps.validConfigValue(value)) {
                                Toast.makeText(this, "Value must be a single line of "
                                        + "at most 512 characters.", Toast.LENGTH_LONG).show();
                                return;
                            }
                            setDeviceConfigWith(flag.namespace, flag.key, value, current);
                        })
                        .show();
            });
        }, "bridge-deviceconfig").start();
    }

    /** Reset one flag to its recorded default, under the
     *  strong confirm flag writes always take. The entries
     *  are marked restored only when the write succeeds. */
    private void resetFlagToDefault(ConfigFlags.Flag flag, String recorded) {
        String shown = recorded.isEmpty() ? "(cleared)"
                : ConfigFlags.displayValue(recorded);
        confirmAction(true, "Reset " + flag.namespace + " / " + flag.key + "?",
                "This writes the value recorded before this app first changed "
                + "this flag back through the same control.\n\nRestore to: " + shown + ".",
                "Reset",
                () -> runCallWithResult("Reset " + flag.namespace + " / " + flag.key + "?",
                        () -> BridgeControl.deviceConfigPut(this, BridgeControl.ORIGIN_UI,
                                flag.namespace, flag.key, recorded),
                        null, true, (ok, text, error) -> {
                            if (ok) journal.markRestoredFor(flag.namespace, flag.key);
                            refreshHistory();
                            loadFlags();
                        }));
    }

    /** The shared flag-write path: the manual form and the
     *  long-press menu both confirm old → new and journal the
     *  write. {@code readBack} is the fresh read taken before
     *  the dialog (null when the read failed); "null" is
     *  Android's unset marker, and an unset-before flag is
     *  unrestorable — there is no delete to restore it with. */
    private void setDeviceConfigWith(String namespace, String key, String value,
                                     String readBack) {
        String oldShown = readBack == null ? "could not be read"
                : "null".equals(readBack) ? "not set"
                : readBack.isEmpty() ? "(empty)" : readBack;
        String previous = readBack == null || "null".equals(readBack) ? null : readBack;
        String reason = readBack == null
                ? "The previous value could not be read."
                : "The flag was not set before this app changed it — "
                + "restoring it would need a delete the bridge does not offer.";
        Draft draft = draft("DEVICE_CONFIG_PUT", namespace + " / " + key,
                previous, value, reason, null, namespace, key, null);
        confirmJournaled(true,
                "Set " + namespace + " / " + key + "?",
                "System behavior flag — changing it can alter how Android behaves.\n\n"
                + "Now: " + oldShown + "\nNew: " + (value.isEmpty() ? "(cleared)" : value),
                "Set flag", draft,
                () -> BridgeControl.deviceConfigPut(this, BridgeControl.ORIGIN_UI,
                        namespace, key, value),
                () -> {
                    readDeviceConfig();
                    loadFlags();
                });
    }

    /** Private DNS with journaling: the current mode and
     *  hostname are read first from Settings.Global, on the
     *  UI thread — a local read, no bridge round trip. A null
     *  mode reads as "opportunistic", the platform default. */
    private void setPrivateDnsFlow(String mode, String hostname, String title,
                                   String message, String actionLabel) {
        // The keys are @hide constants in AOSP, so the literal
        // names are used: "private_dns_mode" / "private_dns_specifier".
        String prevMode = Settings.Global.getString(getContentResolver(),
                "private_dns_mode");
        String prevHost = Settings.Global.getString(getContentResolver(),
                "private_dns_specifier");
        if (prevMode == null) prevMode = "opportunistic";
        if (prevHost == null) prevHost = "";
        Draft draft = draft("SET_PRIVATE_DNS", "Private DNS",
                prevMode + "|" + prevHost, mode + "|" + hostname,
                null, null, null, null);
        confirmJournaled(false, title, message, actionLabel, draft,
                () -> BridgeControl.setPrivateDns(this, BridgeControl.ORIGIN_UI,
                        mode, hostname));
    }

    /** Simulate unplug with journaling: the battery state is
     *  read fresh first; restore is the unconditional reset. */
    private void batteryUnplugFlow() {
        new Thread(() -> {
            ShellBridgeClient.Response r = ShellBridgeClient.batteryGet(this);
            final String before = r.ok
                    ? BridgeOps.batteryDisplay(BridgeOps.parseBattery(r.text)) : null;
            ui.post(() -> {
                Draft draft = draft("BATTERY_UNPLUG", "Battery", before, "unplugged",
                        null, null, null, null);
                // Restore is the unconditional battery reset,
                // so the entry stays restorable even when the
                // before-value could not be read.
                draft.restorable = true;
                draft.unrestorableReason = null;
                confirmJournaled(false, "Simulate unplugging the charger?",
                        "The battery will report discharging until Reset, even while plugged in. "
                        + "Testing only.",
                        "Simulate unplug", draft,
                        () -> BridgeControl.batteryUnplug(this, BridgeControl.ORIGIN_UI),
                        this::loadPower);
            });
        }, "bridge-power").start();
    }
}
