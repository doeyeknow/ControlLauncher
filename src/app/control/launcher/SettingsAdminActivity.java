package app.control.launcher;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

/**
 * Device admin detail (Settings hub → Device admin): the honest
 * explainer, activate/deactivate, and Lock now. Moved out of the
 * old Settings screen — behavior unchanged.
 */
public class SettingsAdminActivity extends ThemedActivity {

    private static final int REQ_ADMIN = 7003;
    private TextView adminStatus;

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

        root.addView(Ui.screenTitle(this, "Device admin"));
        root.addView(Ui.body(this,
                "Device admin lets Control lock this phone for real (the LOCK "
                + "text command and the button below), and erase it only "
                + "through the two-step WIPE DEVICE text command. It is not "
                + "root, it cannot read your messages or files, and it does "
                + "not affect Deep view or the shell bridge. Turn it off here "
                + "any time."));
        adminStatus = Ui.text(this, "", 15, Ui.ACCENT);
        root.addView(adminStatus, Ui.wrap(0, 8, 0, 0, this));
        Button adminToggle = Ui.button(this, "Turn device admin on / off", false);
        adminToggle.setOnClickListener(v -> {
            if (AdminControl.isActive(this)) {
                AdminControl.deactivate(this);
                Toast.makeText(this, "Device admin off.", Toast.LENGTH_SHORT).show();
            } else {
                startActivityForResult(AdminControl.activationIntent(this), REQ_ADMIN);
            }
            refreshStatus();
        });
        root.addView(adminToggle, Ui.wrap(0, 8, 0, 0, this));
        Button lockNow = Ui.button(this, "Lock this phone now", true);
        lockNow.setOnClickListener(v -> {
            if (AdminControl.isActive(this)) {
                AdminControl.lockNow(this, "UI", "");
            } else {
                // No admin: fall back to Control's own UI lock, and say so.
                Prefs.get(this).edit().putBoolean(Prefs.KEY_APP_LOCKED, true).apply();
                Toast.makeText(this,
                        "Device admin is off — locking Control's screens only, "
                        + "not the whole phone.", Toast.LENGTH_LONG).show();
                startActivity(new Intent(this, LockActivity.class));
            }
        });
        root.addView(lockNow, Ui.wrap(0, 8, 0, 0, this));
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_ADMIN) refreshStatus();
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshStatus();
    }

    private void refreshStatus() {
        if (adminStatus != null) {
            adminStatus.setText(AdminControl.isActive(this)
                    ? "Device admin: on — LOCK locks the phone; WIPE DEVICE can erase it."
                    : "Device admin: off — LOCK locks Control's screens only.");
        }
    }
}
