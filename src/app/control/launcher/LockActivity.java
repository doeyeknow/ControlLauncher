package app.control.launcher;

import android.app.Activity;
import android.os.Bundle;
import android.text.InputType;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.Toast;

/** Lock screen used by the SMS LOCK command. The unlock phrase is the
 *  same command secret; only its hash exists in the profile. */
public class LockActivity extends ThemedActivity {

    @Override
    protected boolean recreateOnThemeChange() {
        // Theme change: a rebuild would lose the entered secret mid-unlock; the new theme
        // applies the next time this screen opens.
        return false;
    }
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (!Prefs.get(this).getBoolean(Prefs.KEY_APP_LOCKED, false)) {
            finish();
            return;
        }
        LinearLayout root = Ui.column(this);
        int pad = Ui.dp(this, 24);
        root.setPadding(pad, Ui.dp(this, 48), pad, Ui.dp(this, 24));
        setContentView(root);
        root.addView(Ui.screenTitle(this, "Control is locked"));
        root.addView(Ui.body(this,
                "This lock was set by an authenticated text command. Enter the "
                + "same command secret to unlock this phone's Control screens."));
        EditText secret = Ui.input(this, "Command secret");
        secret.setInputType(InputType.TYPE_CLASS_TEXT
                | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        root.addView(secret, Ui.wrap(0, 18, 0, 0, this));
        Button unlock = Ui.button(this, "Unlock", true);
        unlock.setOnClickListener(v -> {
            if (Profile.smsSecretMatches(this, secret.getText().toString().trim())) {
                Prefs.get(this).edit().putBoolean(Prefs.KEY_APP_LOCKED, false).apply();
                OwnLog.log(this, "Text control", "Control unlocked in the app");
                finish();
            } else {
                Toast.makeText(this, "That secret doesn't match.",
                        Toast.LENGTH_SHORT).show();
            }
        });
        root.addView(unlock, Ui.wrap(0, 12, 0, 0, this));
    }

    @Override
    public void onBackPressed() {
        // Locked means locked; Home remains available at the system level,
        // but Control does not expose its screens.
    }
}
