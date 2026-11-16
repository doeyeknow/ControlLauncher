package app.control.launcher;

import android.app.Activity;
import android.os.Bundle;
import android.widget.LinearLayout;
import android.widget.ScrollView;

/**
 * About (Settings hub → About): version + the honest build summary
 * (the in-app mirror of BUILD_NOTES — what each part can and can't
 * do, stated plainly).
 */
public class SettingsAboutActivity extends ThemedActivity {

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

        String version = "unknown";
        int code = 0;
        try {
            android.content.pm.PackageInfo pi =
                    getPackageManager().getPackageInfo(getPackageName(), 0);
            version = pi.versionName;
            code = pi.versionCode;
        } catch (Exception ignored) {
        }
        root.addView(Ui.screenTitle(this, "About Control"));
        root.addView(Ui.body(this, "Control " + version + " (build " + code + ")"));
        root.addView(Ui.body(this,
                "One launcher that decides what this phone shows you: three "
                + "swipe pages (Browser, Home, Walkie) with tiny page icons, "
                + "an Apps sheet that rises from the bottom, a hold-anywhere "
                + "circle menu, notification control, an ad filter, a browser "
                + "with reader view, chat, and a 50-channel walkie-talkie."));

        root.addView(Ui.sectionLabel(this, "What's honest in this build"));
        root.addView(Ui.body(this,
                "• Chat runs on Control's own relay: no accounts, your "
                + "profile key is the identity. Messages are sealed with "
                + "each conversation's channel key and the relay stores "
                + "ciphertext only — but it does see who talks and when, "
                + "and anyone holding a channel key can read that channel. "
                + "It is not Signal-grade end-to-end encryption.\n"
                + "• Walkie private channels: the key gates and seals every "
                + "road — Wi-Fi mesh, Bluetooth, LAN, and the relay alike "
                + "(membership on the relay is a signed add by a member; "
                + "the key itself travels only as sound — join sounds and "
                + "contact cards played aloud). Open channels are public by design.\n"
                + "• SMS remote control is authenticated, not encrypted — "
                + "your carrier can read texts.\n"
                + "• Device admin is not root; Deep view is a fixed, audited "
                + "set of shell operations, never a general shell, and it "
                + "stops at every reboot until restarted.\n"
                + "• Your profile exports encrypted, always. Your identity "
                + "key travels inside that encrypted file — anyone holding "
                + "it is you. A background photo stays on this device by "
                + "design."));
    }
}
