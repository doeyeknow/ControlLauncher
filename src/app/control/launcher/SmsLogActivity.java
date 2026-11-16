package app.control.launcher;

import android.app.Activity;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;

import java.text.DateFormat;
import java.util.Date;
import java.util.List;

/** Shared audit log for SMS commands and governed bridge controls. */
public class SmsLogActivity extends ThemedActivity {
    private List<SmsControl.Entry> entries;
    private LogAdapter adapter;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        LinearLayout root = Ui.column(this);
        int pad = Ui.dp(this, 18);
        root.setPadding(pad, Ui.dp(this, 18), pad, Ui.dp(this, 16));
        setContentView(root);
        root.addView(Ui.screenTitle(this, "Command & control log"));
        root.addView(Ui.body(this,
                "SMS commands Control obeyed, rejected controller attempts, and "
                + "every governed bridge control attempt (origin, target, parameters, "
                + "result). SMS is authenticated, not encrypted."));
        ListView list = new ListView(this);
        list.setDivider(null);
        adapter = new LogAdapter();
        list.setAdapter(adapter);
        root.addView(list, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        Button clear = Ui.button(this, "Clear log", false);
        clear.setOnClickListener(v -> {
            SmsControl.clearLog(this);
            entries = SmsControl.entries(this);
            adapter.notifyDataSetChanged();
        });
        root.addView(clear, Ui.wrap(0, 10, 0, 0, this));
    }

    @Override
    protected void onResume() {
        super.onResume();
        entries = SmsControl.entries(this);
        adapter.notifyDataSetChanged();
    }

    private class LogAdapter extends BaseAdapter {
        @Override public int getCount() { return entries == null ? 0 : Math.max(1, entries.size()); }
        @Override public Object getItem(int pos) { return entries.get(pos); }
        @Override public long getItemId(int pos) { return pos; }

        @Override
        public View getView(int pos, View convert, ViewGroup parent) {
            if (entries == null || entries.isEmpty()) {
                return Ui.body(SmsLogActivity.this, "No commands or controls have been logged.");
            }
            SmsControl.Entry e = entries.get(pos);
            LinearLayout row = Ui.column(SmsLogActivity.this);
            row.setBackgroundColor(Ui.SURFACE);
            row.setPadding(Ui.dp(SmsLogActivity.this, 10), Ui.dp(SmsLogActivity.this, 8),
                    Ui.dp(SmsLogActivity.this, 10), Ui.dp(SmsLogActivity.this, 8));
            TextView title = Ui.text(SmsLogActivity.this,
                    e.origin + " · " + e.command + " · " + DateFormat.getDateTimeInstance(
                            DateFormat.SHORT, DateFormat.SHORT).format(new Date(e.time)),
                    14, Ui.TEXT);
            row.addView(title);
            String sub = (e.sender.isEmpty() ? "" : "From " + e.sender + " · ")
                    + (e.target.isEmpty() ? "" : "Target " + e.target + " · ")
                    + (e.parameters.isEmpty() ? "" : e.parameters + " · ") + e.result;
            row.addView(Ui.text(SmsLogActivity.this, sub, 13, Ui.DIM));
            return row;
        }
    }
}
