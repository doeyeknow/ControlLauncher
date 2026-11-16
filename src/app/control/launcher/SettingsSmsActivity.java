package app.control.launcher;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONObject;

import java.util.List;

/**
 * SMS remote control detail (Settings hub → SMS remote control):
 * the enable switch lives on the hub row too; this screen keeps the
 * command-secret setup, the controller contacts, the command list
 * and the audit log. Moved out of the old Settings screen —
 * behavior unchanged.
 */
public class SettingsSmsActivity extends ThemedActivity {

    @Override
    protected boolean recreateOnThemeChange() {
        // Theme change: a rebuild would lose the half-typed command secret; the new theme
        // applies the next time this screen opens.
        return false;
    }

    private static final int REQ_SMS = 7002;

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

        root.addView(Ui.screenTitle(this, "SMS remote control"));
        root.addView(Ui.body(this,
                "Text commands are authenticated, not encrypted — your carrier "
                + "can read SMS; keep commands to controls, not secrets. A text "
                + "is obeyed only from a contact marked Controller below and only "
                + "when its first word is the command secret. Default is off."));

        Switch smsOn = new Switch(this);
        smsOn.setText("Enable SMS remote control");
        smsOn.setTextColor(Ui.TEXT);
        smsOn.setTextSize(16);
        smsOn.setChecked(Profile.smsControlEnabled(this));
        smsOn.setPadding(0, Ui.dp(this, 10), 0, Ui.dp(this, 10));
        smsOn.setOnCheckedChangeListener((btn, on) -> {
            Profile.setSmsControlEnabled(this, on);
            if (on) {
                requestSmsPermissionsIfNeeded();
                if (Profile.smsSecretHash(this).isEmpty()) {
                    Toast.makeText(this,
                            "Set a command secret and mark at least one contact as Controller.",
                            Toast.LENGTH_LONG).show();
                }
            }
        });
        root.addView(smsOn);

        TextView secretState = Ui.body(this, Profile.smsSecretHash(this).isEmpty()
                ? "Command secret: not set" : "Command secret: set (only its hash is stored)");
        root.addView(secretState, Ui.wrap(0, 8, 0, 0, this));
        LinearLayout secretRow = new LinearLayout(this);
        secretRow.setOrientation(LinearLayout.HORIZONTAL);
        EditText secretField = new EditText(this);
        secretField.setHint("New secret — one word, at least 6 characters");
        secretField.setTextColor(Ui.TEXT);
        secretField.setHintTextColor(Ui.DIM);
        secretField.setTextSize(16);
        secretField.setSingleLine(true);
        secretField.setBackgroundColor(Ui.SURFACE);
        secretField.setPadding(Ui.dp(this, 12), Ui.dp(this, 8), Ui.dp(this, 12), Ui.dp(this, 8));
        secretRow.addView(secretField, new LinearLayout.LayoutParams(0,
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        Button setSecret = Ui.button(this, "Set / rotate", false);
        setSecret.setOnClickListener(v -> {
            String value = secretField.getText().toString().trim();
            if (value.length() < 6 || value.contains(" ") || value.contains("\t")) {
                Toast.makeText(this, "Use one word of at least 6 characters.",
                        Toast.LENGTH_LONG).show();
                return;
            }
            Profile.setSmsSecret(this, value);
            secretField.setText("");
            secretState.setText("Command secret: set (only its hash is stored)");
            Toast.makeText(this, "Command secret updated.", Toast.LENGTH_SHORT).show();
        });
        secretRow.addView(setSecret);
        root.addView(secretRow, Ui.wrap(0, 6, 0, 0, this));

        root.addView(Ui.body(this, "Controllers (contacts with a phone number):"));
        List<JSONObject> contactsForSms = Profile.contactList(this);
        boolean anyControllerChoice = false;
        for (int i = 0; i < contactsForSms.size(); i++) {
            JSONObject contact = contactsForSms.get(i);
            String phone = contact.optString("phone", "");
            if (phone.isEmpty()) continue;
            anyControllerChoice = true;
            CheckBox cb = new CheckBox(this);
            cb.setText(contact.optString("name", "Unnamed") + " · " + phone);
            cb.setTextColor(Ui.TEXT);
            cb.setTextSize(15);
            cb.setChecked(contact.optBoolean("controller", false));
            final int index = i;
            cb.setOnCheckedChangeListener((btn, checked) ->
                    Profile.setContactController(this, index, checked));
            root.addView(cb);
        }
        if (!anyControllerChoice) {
            root.addView(Ui.body(this,
                    "No contacts with phone numbers yet. Add one in Settings → "
                    + "Profile & backup → Contacts, then return here."));
        }
        root.addView(Ui.body(this,
                "Commands: <secret> STATUS · QUIET · LOUD · FILTER ON · FILTER OFF · "
                + "TUNE <1–50> · LOCATE · LOCK · WIPE PROFILE (then <secret> CONFIRM WIPE) · "
                + "WIPE DEVICE (then <secret> CONFIRM WIPE DEVICE — erases the whole phone, "
                + "and only works while device admin is on). LOCK locks the whole phone "
                + "when device admin is on; without it, LOCK locks Control's screens only. "
                + "LOCATE uses Android's last known location and only if location permission is granted."));
        Button smsLog = Ui.button(this, "View command & control log", false);
        smsLog.setOnClickListener(v -> startActivity(new Intent(this, SmsLogActivity.class)));
        root.addView(smsLog, Ui.wrap(0, 8, 0, 0, this));
    }

    private void requestSmsPermissionsIfNeeded() {
        java.util.List<String> need = new java.util.ArrayList<>();
        if (checkSelfPermission(Manifest.permission.RECEIVE_SMS)
                != PackageManager.PERMISSION_GRANTED) need.add(Manifest.permission.RECEIVE_SMS);
        if (checkSelfPermission(Manifest.permission.SEND_SMS)
                != PackageManager.PERMISSION_GRANTED) need.add(Manifest.permission.SEND_SMS);
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)
                != PackageManager.PERMISSION_GRANTED) need.add(Manifest.permission.ACCESS_FINE_LOCATION);
        if (!need.isEmpty()) {
            requestPermissions(need.toArray(new String[0]), REQ_SMS);
        }
    }
}
