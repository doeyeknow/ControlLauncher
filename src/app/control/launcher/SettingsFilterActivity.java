package app.control.launcher;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.VpnService;
import android.os.Bundle;
import android.provider.Settings;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

/**
 * Ad filter detail (Settings hub → Ad filter): the local VPN filter
 * (toggle, upstream resolver, bypass list, blocklist) and the
 * Private DNS setup. Moved out of the old single Settings screen —
 * behavior unchanged.
 */
public class SettingsFilterActivity extends ThemedActivity {

    @Override
    protected boolean recreateOnThemeChange() {
        // Theme change: a rebuild would lose the half-typed resolver entry; the new theme
        // applies the next time this screen opens.
        return false;
    }

    private static final int REQ_VPN = 7001;
    static final String RECOMMENDED_PRIVATE_DNS = "dns.adguard-dns.com";

    private TextView filterStatus;
    private TextView curPdns;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(Ui.BG);
        LinearLayout root = Ui.column(this);
        int pad = Ui.dp(this, 20);
        root.setPadding(pad, Ui.dp(this, 20), pad, Ui.dp(this, 24));
        scroll.addView(root);
        setContentView(scroll);

        SharedPreferences p = Prefs.get(this);
        root.addView(Ui.screenTitle(this, "Ad filter"));
        root.addView(Ui.body(this,
                "A local filter on this phone answers ad and tracker lookups with "
                + "\"no such domain\" before they load — in the browser and in apps. "
                + "Nothing about your traffic leaves the phone except the lookups "
                + "themselves, which go to the resolver below."));
        filterStatus = Ui.text(this, "", 15, Ui.ACCENT);
        root.addView(filterStatus, Ui.wrap(0, 8, 0, 0, this));
        Button toggle = Ui.button(this, "Turn filter on / off", true);
        toggle.setOnClickListener(v -> toggleFilter());
        root.addView(toggle, Ui.wrap(0, 8, 0, 0, this));

        TextView dnsLabel = Ui.body(this, "Upstream resolver (for allowed lookups):");
        root.addView(dnsLabel, Ui.wrap(0, 14, 0, 0, this));
        LinearLayout dnsRow = new LinearLayout(this);
        dnsRow.setOrientation(LinearLayout.HORIZONTAL);
        EditText dnsField = new EditText(this);
        dnsField.setText(Prefs.upstreamDns(this));
        dnsField.setTextColor(Ui.TEXT);
        dnsField.setTextSize(16);
        dnsField.setSingleLine(true);
        dnsField.setBackgroundColor(Ui.SURFACE);
        dnsField.setPadding(Ui.dp(this, 12), Ui.dp(this, 8), Ui.dp(this, 12), Ui.dp(this, 8));
        dnsRow.addView(dnsField, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        Button saveDns = Ui.button(this, "Save", false);
        saveDns.setOnClickListener(v -> {
            String v2 = dnsField.getText().toString().trim();
            if (v2.matches("\\d+\\.\\d+\\.\\d+\\.\\d+")) {
                Profile.setUpstreamDns(this, v2);
                Toast.makeText(this, "Resolver saved. Restart the filter to apply.",
                        Toast.LENGTH_LONG).show();
            } else {
                Toast.makeText(this, "Enter an IPv4 address, e.g. 1.1.1.1",
                        Toast.LENGTH_LONG).show();
            }
        });
        dnsRow.addView(saveDns);
        root.addView(dnsRow, Ui.wrap(0, 6, 0, 0, this));

        Button bypass = Ui.button(this, "Apps that bypass the filter", false);
        bypass.setOnClickListener(v -> startActivity(
                AppPickerActivity.intent(this, Prefs.KEY_BYPASS_APPS, "Filter bypass")));
        root.addView(bypass, Ui.wrap(0, 8, 0, 0, this));
        TextView bl = Ui.body(this, "Blocklist: " + Blocklist.size(this)
                + " ad/tracker domains (StevenBlack unified hosts).");
        root.addView(bl, Ui.wrap(0, 8, 0, 0, this));

        // ---- Private DNS ----
        root.addView(Ui.section(this, "Whole network (Private DNS)"));
        root.addView(Ui.body(this,
                "Android's Private DNS routes every app's lookups through one "
                + "encrypted resolver. Point it at a filtering resolver and ads "
                + "disappear even outside this app. Recommended:"));
        TextView rec = Ui.text(this, RECOMMENDED_PRIVATE_DNS, 17, Ui.TEXT);
        root.addView(rec, Ui.wrap(0, 6, 0, 0, this));
        LinearLayout pdnsRow = new LinearLayout(this);
        pdnsRow.setOrientation(LinearLayout.HORIZONTAL);
        Button copy = Ui.button(this, "Copy hostname", false);
        copy.setOnClickListener(v -> {
            ClipboardManager cm = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
            cm.setPrimaryClip(ClipData.newPlainText("Private DNS", RECOMMENDED_PRIVATE_DNS));
            Toast.makeText(this, "Copied", Toast.LENGTH_SHORT).show();
        });
        pdnsRow.addView(copy, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        Button openPdns = Ui.button(this, "Open settings", false);
        openPdns.setOnClickListener(v -> {
            try {
                startActivity(new Intent(Settings.ACTION_WIRELESS_SETTINGS));
                Toast.makeText(this,
                        "Connections → More connection settings → Private DNS → "
                        + "enter the hostname and save.",
                        Toast.LENGTH_LONG).show();
            } catch (Exception e) {
                Toast.makeText(this, "Open Settings → Network → Private DNS",
                        Toast.LENGTH_LONG).show();
            }
        });
        LinearLayout.LayoutParams op = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        op.setMargins(Ui.dp(this, 8), 0, 0, 0);
        pdnsRow.addView(openPdns, op);
        root.addView(pdnsRow, Ui.wrap(0, 8, 0, 0, this));
        Button bridgeDns = Ui.button(this, "Set it with the shell bridge (when Deep view is on)", false);
        bridgeDns.setOnClickListener(v -> {
            new AlertDialog.Builder(this)
                    .setTitle("Set Private DNS to " + RECOMMENDED_PRIVATE_DNS + "?")
                    .setMessage("Control's governed bridge will write Android's Private DNS settings directly. This attempt is audited with origin FILTER. If the bridge is off, use Open settings instead.")
                    .setNegativeButton("Cancel", null)
                    .setPositiveButton("Set DNS", (d, w) -> new Thread(() -> {
                        ShellBridgeClient.Response r = BridgeControl.setPrivateDns(
                                this, BridgeControl.ORIGIN_FILTER, "hostname",
                                RECOMMENDED_PRIVATE_DNS);
                        runOnUiThread(() -> {
                            Toast.makeText(this,
                                    r.ok ? "Private DNS set by the bridge."
                                            : "Bridge did not set it: " + r.error,
                                    Toast.LENGTH_LONG).show();
                            refreshStatuses();
                        });
                    }, "settings-bridge-dns").start())
                    .show();
        });
        root.addView(bridgeDns, Ui.wrap(0, 8, 0, 0, this));
        curPdns = Ui.body(this, "");
        root.addView(curPdns, Ui.wrap(0, 6, 0, 0, this));
    }

    /** Used by the hub's on-row switch too — same VPN consent flow. */
    public static void toggleFilter(Activity a, Runnable after) {
        boolean on = Prefs.get(a).getBoolean(Prefs.KEY_FILTER_ON, false);
        if (on) {
            Intent stop = new Intent(a, DnsVpnService.class);
            stop.setAction(DnsVpnService.ACTION_STOP);
            a.startService(stop);
            Prefs.get(a).edit().putBoolean(Prefs.KEY_FILTER_ON, false).apply();
            if (after != null) after.run();
        } else {
            // One VPN slot: mesh internet holds it while NAT runs.
            // Stop NAT first (no restore — the filter start below
            // takes the slot).
            MeshInternet.stopNat(a, false);
            Intent consent = VpnService.prepare(a);
            if (consent != null) {
                a.startActivityForResult(consent, REQ_VPN);
            } else {
                startFilter(a);
                if (after != null) after.run();
            }
        }
    }

    public static void startFilter(Activity a) {
        Intent start = new Intent(a, DnsVpnService.class);
        start.setAction(DnsVpnService.ACTION_START);
        a.startForegroundService(start);
    }

    public static void onVpnResult(Activity a, int resultCode, Runnable after) {
        if (resultCode == RESULT_OK) {
            startFilter(a);
            if (after != null) after.run();
        } else {
            Toast.makeText(a, "The filter needs your permission to run.",
                    Toast.LENGTH_LONG).show();
        }
    }

    private void toggleFilter() {
        toggleFilter(this, this::refreshStatuses);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_VPN) onVpnResult(this, resultCode, this::refreshStatuses);
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshStatuses();
    }

    private void refreshStatuses() {
        boolean on = Prefs.get(this).getBoolean(Prefs.KEY_FILTER_ON, false);
        long blocked = Prefs.get(this).getLong(Prefs.KEY_BLOCKED_COUNT, 0);
        if (filterStatus != null) {
            filterStatus.setText(on
                    ? "Status: on — " + blocked + " lookups blocked so far"
                    : "Status: off");
        }
        if (curPdns != null) {
            curPdns.setText("Current Private DNS: " + currentPrivateDns());
        }
    }

    /** One-line Private DNS state for the Settings hub summary. */
    public static String privateDnsSummary(android.content.Context c) {
        try {
            String mode = Settings.Global.getString(
                    c.getContentResolver(), "private_dns_mode");
            String spec = Settings.Global.getString(
                    c.getContentResolver(), "private_dns_specifier");
            if ("hostname".equals(mode) && spec != null) return spec;
            if ("off".equals(mode)) return "DNS off";
            return "DNS automatic";
        } catch (Exception e) {
            return "DNS unknown";
        }
    }

    private String currentPrivateDns() {
        try {
            String mode = Settings.Global.getString(getContentResolver(), "private_dns_mode");
            String spec = Settings.Global.getString(getContentResolver(), "private_dns_specifier");
            if ("hostname".equals(mode) && spec != null) return spec;
            if ("opportunistic".equals(mode)) return "automatic (not filtered)";
            if ("off".equals(mode)) return "off";
            return mode == null ? "unknown" : mode;
        } catch (Exception e) {
            return "unknown";
        }
    }
}
