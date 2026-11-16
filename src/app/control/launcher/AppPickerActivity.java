package app.control.launcher;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.CheckBox;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Generic multi-select app list. Extras: "pref_key" (which Prefs set to
 * edit) and "title". Changes save immediately.
 */
public class AppPickerActivity extends ThemedActivity {

    public static final String EXTRA_KEY = "pref_key";
    public static final String EXTRA_TITLE = "title";

    private String prefKey;
    private Set<String> selected;
    private PickerAdapter adapter;
    private final List<InstalledApps.Entry> apps = new ArrayList<>();

    void notifyChanged() {
        if (adapter != null) adapter.notifyDataSetChanged();
    }

    public static Intent intent(Activity from, String prefKey, String title) {
        Intent i = new Intent(from, AppPickerActivity.class);
        i.putExtra(EXTRA_KEY, prefKey);
        i.putExtra(EXTRA_TITLE, title);
        return i;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefKey = getIntent().getStringExtra(EXTRA_KEY);
        LinearLayout root = Ui.column(this);
        int pad = Ui.dp(this, 16);
        root.setPadding(pad, Ui.dp(this, 16), pad, 0);
        setContentView(root);

        root.addView(Ui.screenTitle(this, getIntent().getStringExtra(EXTRA_TITLE)));
        root.addView(Ui.body(this, "Changes save as you tap."));

        selected = Prefs.getSet(this, prefKey);
        apps.addAll(InstalledApps.all(this));

        ListView list = new ListView(this);
        list.setDivider(null);
        adapter = new PickerAdapter();
        list.setAdapter(adapter);
        list.setOnItemClickListener((parent, view, pos, id) -> {
            String pkg = apps.get(pos).pkg;
            if (!selected.remove(pkg)) selected.add(pkg);
            Prefs.putSet(this, prefKey, selected);
            adapter.notifyDataSetChanged();
        });
        root.addView(list, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
    }

    private class PickerAdapter extends BaseAdapter {
        @Override public int getCount() { return apps.size(); }
        @Override public Object getItem(int pos) { return apps.get(pos); }
        @Override public long getItemId(int pos) { return pos; }

        @Override
        public View getView(int pos, View convert, ViewGroup parent) {
            InstalledApps.Entry e = apps.get(pos);
            LinearLayout row = new LinearLayout(AppPickerActivity.this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(android.view.Gravity.CENTER_VERTICAL);
            row.setPadding(0, Ui.dp(AppPickerActivity.this, 8), 0, Ui.dp(AppPickerActivity.this, 8));

            CheckBox cb = new CheckBox(AppPickerActivity.this);
            cb.setChecked(selected.contains(e.pkg));
            cb.setClickable(false);
            // ROOT CAUSE of the v1 "can't select anything in the app
            // lists" bug: this CheckBox was clickable=false but still
            // FOCUSABLE (the default), and a focusable descendant inside
            // an AbsListView row suppresses the list's item clicks —
            // taps never reached the toggle code. Both flags must go.
            cb.setFocusable(false);
            cb.setFocusableInTouchMode(false);
            row.addView(cb);

            ImageView iv = new ImageView(AppPickerActivity.this);
            iv.setImageDrawable(InstalledApps.iconFor(AppPickerActivity.this, e.pkg));
            int sz = Ui.dp(AppPickerActivity.this, 36);
            LinearLayout.LayoutParams ip = new LinearLayout.LayoutParams(sz, sz);
            ip.setMargins(Ui.dp(AppPickerActivity.this, 8), 0, Ui.dp(AppPickerActivity.this, 12), 0);
            row.addView(iv, ip);

            TextView label = Ui.text(AppPickerActivity.this, e.label, 16, Ui.TEXT);
            row.addView(label);
            // Belt and suspenders: the row itself also handles the tap,
            // so selection never depends on AbsListView click routing.
            row.setOnClickListener(v -> {
                String pkg = e.pkg;
                if (!selected.remove(pkg)) selected.add(pkg);
                Prefs.putSet(AppPickerActivity.this, prefKey, selected);
                notifyChanged();
            });
            return row;
        }
    }
}
