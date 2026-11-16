package app.control.launcher;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/** Browser bookmarks — stored in the profile, so they travel. */
public class BookmarksActivity extends ThemedActivity {

    private final List<JSONObject> items = new ArrayList<>();
    private BookmarkAdapter adapter;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        LinearLayout root = Ui.column(this);
        int pad = Ui.dp(this, 20);
        root.setPadding(pad, Ui.dp(this, 20), pad, Ui.dp(this, 16));
        setContentView(root);

        root.addView(Ui.screenTitle(this, "Bookmarks"));
        root.addView(Ui.body(this, "Tap to open. Long-press to remove."));

        ListView list = new ListView(this);
        list.setDivider(null);
        adapter = new BookmarkAdapter();
        list.setAdapter(adapter);
        list.setOnItemClickListener((parent, view, pos, id) -> {
            String url = items.get(pos).optString("url");
            Intent i = new Intent(this, BrowserActivity.class);
            i.setAction(Intent.ACTION_VIEW);
            i.setData(Uri.parse(url));
            startActivity(i);
        });
        list.setOnItemLongClickListener((parent, view, pos, id) -> {
            Profile.removeBookmark(this, items.get(pos).optString("url"));
            reload();
            Toast.makeText(this, "Removed", Toast.LENGTH_SHORT).show();
            return true;
        });
        root.addView(list, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        reload();
    }

    private void reload() {
        items.clear();
        JSONArray arr = Profile.bookmarks(this);
        for (int i = 0; i < arr.length(); i++) items.add(arr.optJSONObject(i));
        adapter.notifyDataSetChanged();
    }

    private class BookmarkAdapter extends BaseAdapter {
        @Override public int getCount() { return Math.max(1, items.size()); }
        @Override public Object getItem(int pos) { return items.get(pos); }
        @Override public long getItemId(int pos) { return pos; }

        @Override
        public View getView(int pos, View convert, ViewGroup parent) {
            if (items.isEmpty()) {
                return Ui.body(BookmarksActivity.this,
                        "No bookmarks yet. In the browser: ⋮ → Bookmark this page.");
            }
            JSONObject b = items.get(pos);
            LinearLayout row = new LinearLayout(BookmarksActivity.this);
            row.setOrientation(LinearLayout.VERTICAL);
            row.setPadding(0, Ui.dp(BookmarksActivity.this, 10), 0, Ui.dp(BookmarksActivity.this, 10));
            row.addView(Ui.text(BookmarksActivity.this, b.optString("title", "Page"), 16, Ui.TEXT));
            TextView url = Ui.text(BookmarksActivity.this, b.optString("url", ""), 13, Ui.DIM);
            url.setMaxLines(1);
            row.addView(url);
            return row;
        }
    }
}
