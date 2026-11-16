package app.control.launcher;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.GridView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONObject;

/**
 * The channel bank: 50 numbered slots like a real radio. Tap a number
 * to tune to it; long-press to configure (label, private mode, members).
 * Unconfigured numbers are open channels — no setup.
 */
public class ChannelsActivity extends ThemedActivity {

    static final String PREF_LAST_CHANNEL = "last_channel_number";

    private SlotAdapter adapter;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        LinearLayout root = Ui.column(this);
        int pad = Ui.dp(this, 16);
        root.setPadding(pad, Ui.dp(this, 16), pad, Ui.dp(this, 12));
        setContentView(root);

        root.addView(Ui.screenTitle(this, "Channels"));
        root.addView(Ui.body(this,
                "Tap a number to tune. Long-press to label it or make it "
                + "private. Channels marked ● listening are subscribed: "
                + "they keep arriving over the internet relay even "
                + "when you're tuned elsewhere — subscribe on the "
                + "radio screen, or when configuring a channel."));

        Button listen = Ui.button(this, "Listen for a channel invite", false);
        listen.setOnClickListener(v ->
                startActivityForResult(AudioKeyListenActivity.channelIntent(this), 7401));
        root.addView(listen, Ui.wrap(0, 10, 0, 0, this));

        GridView grid = new GridView(this);
        grid.setNumColumns(5);
        grid.setVerticalSpacing(Ui.dp(this, 6));
        grid.setHorizontalSpacing(Ui.dp(this, 6));
        grid.setPadding(0, Ui.dp(this, 12), 0, 0);
        adapter = new SlotAdapter();
        grid.setAdapter(adapter);
        grid.setOnItemClickListener((parent, view, pos, id) -> {
            int number = pos + 1;
            Prefs.get(this).edit().putInt(PREF_LAST_CHANNEL, number).apply();
            Toast.makeText(this, "Tuned to " + Profile.slotDisplayName(
                    Profile.slot(this, number)), Toast.LENGTH_SHORT).show();
            finish();
        });
        grid.setOnItemLongClickListener((parent, view, pos, id) -> {
            Intent i = new Intent(this, ChannelEditActivity.class);
            i.putExtra("number", pos + 1);
            startActivity(i);
            return true;
        });
        root.addView(grid, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (adapter != null) adapter.notifyDataSetChanged();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != 7401 || resultCode != RESULT_OK || data == null) return;
        String text = data.getStringExtra(AudioKeyListenActivity.RESULT_TEXT);
        if (text == null) return;
        ChannelInvite.JoinResult r = ChannelInvite.join(this, text);
        if (r == null) {
            Toast.makeText(this, "That sound wasn't a channel invite.",
                    Toast.LENGTH_LONG).show();
            return;
        }
        Toast.makeText(this, r.message, Toast.LENGTH_LONG).show();
        if (r.joined) finish();
        if (adapter != null) adapter.notifyDataSetChanged();
    }

    private class SlotAdapter extends BaseAdapter {
        @Override public int getCount() { return Profile.BANK_SIZE; }
        @Override public Object getItem(int pos) { return pos + 1; }
        @Override public long getItemId(int pos) { return pos + 1; }

        @Override
        public View getView(int pos, View convert, ViewGroup parent) {
            int number = pos + 1;
            JSONObject slot = Profile.slot(ChannelsActivity.this, number);
            boolean priv = Profile.isPrivate(slot);
            String label = slot.optString("label", "");
            LinearLayout cell = new LinearLayout(ChannelsActivity.this);
            cell.setOrientation(LinearLayout.VERTICAL);
            cell.setGravity(Gravity.CENTER);
            cell.setBackgroundColor(priv ? Ui.SURFACE2 : Ui.SURFACE);
            cell.setPadding(0, Ui.dp(ChannelsActivity.this, 10), 0, Ui.dp(ChannelsActivity.this, 10));
            TextView num = Ui.text(ChannelsActivity.this, String.valueOf(number), 22,
                    priv ? Ui.ACCENT : Ui.TEXT);
            num.setGravity(Gravity.CENTER);
            cell.addView(num);
            TextView sub = Ui.text(ChannelsActivity.this,
                    label.isEmpty() ? (priv ? "private" : "open") : label, 10, Ui.DIM);
            sub.setGravity(Gravity.CENTER);
            sub.setMaxLines(1);
            cell.addView(sub);
            // v1.19: a subscribed channel is marked visibly, and
            // a channel with unplayed received messages shows
            // the count — background arrivals are findable
            // here without tuning each channel. Refreshed on
            // every resume (and after an invite join).
            boolean subbed = ChannelSubscriptions.isSubscribed(
                    ChannelsActivity.this, number);
            int unplayed = ChannelSubscriptions.unplayedCount(
                    ChannelsActivity.this, slot);
            StringBuilder status = new StringBuilder();
            if (subbed) status.append("● listening");
            if (unplayed > 0) {
                if (status.length() > 0) status.append(" · ");
                status.append(unplayed).append(" new");
            }
            TextView statusLine = Ui.text(ChannelsActivity.this,
                    status.toString(), 10,
                    unplayed > 0 ? Ui.SIGNAL : Ui.ACCENT);
            statusLine.setGravity(Gravity.CENTER);
            statusLine.setMaxLines(1);
            cell.addView(statusLine);
            return cell;
        }
    }
}
