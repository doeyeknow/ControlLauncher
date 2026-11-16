package app.control.launcher;

import android.app.Activity;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * The Apps sheet (v1.3): the all-apps drawer as a full-screen sheet
 * that rises FROM THE BOTTOM over the current page — it left the
 * pager when the strip became Browser / Home / Walkie. Raised by a
 * bottom-edge swipe-up (PagerLayout's strip), the circle menu's Apps
 * icon, or the Apps tiny icon; lowered by a drag down on its header,
 * Back, or launching an app.
 *
 * The grid is built from plain cells with their own click handlers
 * (v1 used an AbsListView whose rows could swallow taps; see
 * BUILD_NOTES). Tap opens the app; long-press stars/unstars it as a
 * favorite — favorites live in the profile and show on Home.
 *
 * The list comes from AppListStore (v1.9): the cached list shows
 * instantly, a background refresh re-scans, and the grid rebuilds
 * only when the contents actually changed (an install, removal,
 * update, or relabel). Icons load lazily per row.
 */
public class AppsSheetView extends LinearLayout {

    private final Activity act;
    private final List<InstalledApps.Entry> all = new ArrayList<>();
    private LinearLayout gridBox;
    private EditText search;
    private boolean open;
    private Runnable launchedListener;
    private Runnable closeListener;

    /** Cache changed while the sheet is up (install/remove/update):
     *  rebuild from the fresh list, keeping the search text. */
    private final AppListStore.Listener storeListener = () -> {
        if (!open) return;
        reloadFromCache();
        buildGrid();
    };

    public AppsSheetView(Activity host) {
        super(host);
        this.act = host;
        setOrientation(LinearLayout.VERTICAL);
        setBackground(Ui.roundedStroke(act, Ui.BG, 0, Ui.LINE));
        int pad = Ui.dp(act, 16);
        setPadding(pad, Ui.dp(act, 8), pad, 0);
        setVisibility(INVISIBLE);

        // Header zone: drag handle + title. Dragging down here lowers
        // the sheet; the search field below keeps its own behavior.
        LinearLayout header = new LinearLayout(act);
        header.setOrientation(LinearLayout.VERTICAL);
        header.setGravity(Gravity.CENTER_HORIZONTAL);
        View handle = new View(act);
        handle.setBackground(Ui.rounded(act, Ui.DOT_LINE, 999));
        header.addView(handle, new LinearLayout.LayoutParams(
                Ui.dp(act, 44), Ui.dp(act, 5)));
        TextView title = Ui.text(act, "APPS", 12, Ui.ACCENT);
        title.setTypeface(android.graphics.Typeface.MONOSPACE);
        title.setLetterSpacing(0.16f);
        title.setPadding(0, Ui.dp(act, 8), 0, Ui.dp(act, 2));
        title.setGravity(Gravity.CENTER);
        header.addView(title);
        header.setOnTouchListener(new OnTouchListener() {
            private float downY;

            @Override public boolean onTouch(View v, MotionEvent e) {
                switch (e.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        downY = e.getY();
                        return true;
                    case MotionEvent.ACTION_MOVE:
                        if (e.getY() - downY > Ui.dp(act, 48)) {
                            hide();
                            return true;
                        }
                        return true;
                    default:
                        return true;
                }
            }
        });
        addView(header);

        search = new EditText(act);
        search.setHint("Search apps");
        search.setTextColor(Ui.TEXT);
        search.setHintTextColor(Ui.DIM);
        search.setTextSize(17);
        search.setSingleLine(true);
        search.setBackground(Ui.roundedStroke(act, Ui.SURFACE, 999, Ui.LINE));
        search.setPadding(Ui.dp(act, 14), Ui.dp(act, 10), Ui.dp(act, 14), Ui.dp(act, 10));
        addView(search, Ui.wrap(0, 8, 0, 0, act));

        TextView hint = Ui.text(act, "Tap to open · hold to ★ a favorite · drag the top down to close", 13, Ui.DIM);
        hint.setTypeface(android.graphics.Typeface.MONOSPACE);
        hint.setPadding(0, Ui.dp(act, 8), 0, 0);
        addView(hint);

        ScrollView scroll = new ScrollView(act);
        scroll.setBackgroundColor(0x00000000);
        gridBox = Ui.column(act);
        gridBox.setBackgroundColor(0x00000000);
        gridBox.setPadding(0, Ui.dp(act, 8), 0, Ui.dp(act, 16));
        scroll.addView(gridBox);
        addView(scroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        search.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) {
                buildGrid();
            }
            @Override public void afterTextChanged(Editable s) {}
        });
    }

    public void setLaunchedListener(Runnable r) {
        launchedListener = r;
    }

    public boolean isOpen() {
        return open;
    }

    public void show() {
        if (open) return;
        open = true;
        reloadFromCache();
        buildGrid(); // favorite stars may have changed elsewhere
        AppListStore.addListener(storeListener);
        // The cached list is already showing; refresh in the
        // background and re-render only if something changed.
        AppListStore.refreshAsync(act, null);
        setVisibility(VISIBLE);
        // Start below the stage and rise.
        post(() -> {
            setTranslationY(getHeight());
            animate().translationY(0).setDuration(240).start();
        });
    }

    public void hide() {
        if (!open) return;
        open = false;
        AppListStore.removeListener(storeListener);
        animate().translationY(getHeight()).setDuration(220)
                .withEndAction(() -> {
                    if (!open) setVisibility(INVISIBLE);
                })
                .start();
        if (closeListener != null) closeListener.run();
    }

    public void setCloseListener(Runnable r) {
        closeListener = r;
    }

    /** The cached list, synchronously — instant after first run. */
    private void reloadFromCache() {
        all.clear();
        all.addAll(InstalledApps.all(act));
    }

    private void buildGrid() {
        if (gridBox == null) return;
        gridBox.removeAllViews();
        String q = search.getText().toString().toLowerCase().trim();
        Set<String> favs = Prefs.getSet(act, Prefs.KEY_FAVORITES);
        List<InstalledApps.Entry> shown = new ArrayList<>();
        for (InstalledApps.Entry e : all) {
            if (q.isEmpty() || e.label.toLowerCase().contains(q)) shown.add(e);
        }
        int cols = 4;
        for (int i = 0; i < shown.size(); i += cols) {
            LinearLayout row = new LinearLayout(act);
            row.setOrientation(LinearLayout.HORIZONTAL);
            for (int j = 0; j < cols; j++) {
                if (i + j < shown.size()) {
                    row.addView(cell(shown.get(i + j), favs),
                            new LinearLayout.LayoutParams(0,
                                    ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
                } else {
                    row.addView(new View(act), new LinearLayout.LayoutParams(0, 1, 1f));
                }
            }
            gridBox.addView(row);
        }
        if (shown.isEmpty()) {
            gridBox.addView(Ui.body(act, "No apps match."));
        }
    }

    private View cell(InstalledApps.Entry e, Set<String> favs) {
        LinearLayout cell = new LinearLayout(act);
        cell.setOrientation(LinearLayout.VERTICAL);
        cell.setGravity(Gravity.CENTER);
        cell.setPadding(0, Ui.dp(act, 10), 0, Ui.dp(act, 10));
        ImageView iv = new ImageView(act);
        iv.setImageDrawable(InstalledApps.iconFor(act, e.pkg));
        int sz = Ui.dp(act, 46);
        cell.addView(iv, new LinearLayout.LayoutParams(sz, sz));
        TextView label = Ui.text(act,
                (favs.contains(e.pkg) ? "★ " : "") + e.label, 12,
                favs.contains(e.pkg) ? Ui.ACCENT : Ui.TEXT);
        label.setGravity(Gravity.CENTER);
        label.setMaxLines(1);
        cell.addView(label);
        cell.setOnClickListener(v -> {
            InstalledApps.launch(act, e.pkg);
            hide();
            if (launchedListener != null) launchedListener.run();
        });
        cell.setOnLongClickListener(v -> {
            Set<String> now = Prefs.getSet(act, Prefs.KEY_FAVORITES);
            boolean added;
            if (now.contains(e.pkg)) {
                now.remove(e.pkg);
                added = false;
            } else {
                now.add(e.pkg);
                added = true;
            }
            Prefs.putSet(act, Prefs.KEY_FAVORITES, now);
            Toast.makeText(act, added
                    ? e.label + " added to favorites"
                    : e.label + " removed from favorites", Toast.LENGTH_SHORT).show();
            buildGrid();
            return true;
        });
        return cell;
    }
}
