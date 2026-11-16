package app.control.launcher;

import android.app.Activity;
import android.os.Bundle;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/** The held-notifications list: what the shade never showed you. */
public class HeldActivity extends ThemedActivity {

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

        root.addView(Ui.screenTitle(this, "Held notifications"));
        List<HeldStore.Held> held = HeldStore.list(this);
        root.addView(Ui.body(this, held.isEmpty()
                ? "Nothing held. Notifications from apps you mute in Settings "
                + "will be kept here instead of the shade."
                : held.size() + " held. These never reached your shade."));

        SimpleDateFormat fmt = new SimpleDateFormat("MMM d, h:mm a", Locale.getDefault());
        for (HeldStore.Held h : held) {
            InstalledApps.Entry e = InstalledApps.one(this, h.pkg);
            String app = e != null ? e.label : h.pkg;
            TextView head = Ui.text(this, app + "  ·  " + fmt.format(new Date(h.time)), 13, Ui.ACCENT);
            root.addView(head, Ui.wrap(0, 16, 0, 0, this));
            if (!h.title.isEmpty()) {
                root.addView(Ui.text(this, h.title, 16, Ui.TEXT));
            }
            if (!h.text.isEmpty()) {
                root.addView(Ui.body(this, h.text));
            }
        }

        if (!held.isEmpty()) {
            Button clear = Ui.button(this, "Clear held list", false);
            clear.setOnClickListener(v -> {
                HeldStore.clear(this);
                recreate();
            });
            root.addView(clear, Ui.wrap(0, 24, 0, 0, this));
        }
    }
}
