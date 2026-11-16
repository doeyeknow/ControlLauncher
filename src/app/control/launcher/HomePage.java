package app.control.launcher;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.GridLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.Set;

/**
 * The Home page of the launcher pager: clock, status lines and the
 * user's chosen favorites — no buttons (v1.2). The set-as-home
 * prompt lives in Settings only; navigation is the tiny page icons,
 * the long-press circle menu, and the bottom-edge Apps sheet.
 */
public class HomePage extends LinearLayout implements PagerLayout.PagerPage {

    private final Activity act;
    private TextView clockView;
    private TextView dateView;
    private TextView statusView;
    private TextView heldView;
    private GridLayout favoritesGrid;
    private final android.os.Handler tick = new android.os.Handler();
    private final Runnable ticker = new Runnable() {
        @Override public void run() {
            updateClock();
            tick.postDelayed(this, 20_000);
        }
    };
    private boolean shown;

    public HomePage(Activity host) {
        super(host);
        this.act = host;
        setOrientation(LinearLayout.VERTICAL);
        setBackgroundColor(0x00000000); // transparent over the shared background layer
        int pad = Ui.dp(act, 24);
        setPadding(pad, Ui.dp(act, 36), pad, Ui.dp(act, 20));

        // The space mockup's home: a huge 800-weight clock, a mono
        // date line, mono status lines, then the favorites grid.
        clockView = Ui.text(act, "", 64, Ui.TEXT);
        Ui.display(clockView);
        dateView = Ui.text(act, "", 12, Ui.DIM);
        dateView.setTypeface(android.graphics.Typeface.MONOSPACE);
        dateView.setLetterSpacing(0.08f);
        addView(clockView);
        addView(dateView);

        statusView = Ui.text(act, "", 12, Ui.ACCENT);
        statusView.setTypeface(android.graphics.Typeface.MONOSPACE);
        statusView.setPadding(0, Ui.dp(act, 14), 0, 0);
        addView(statusView);

        heldView = Ui.text(act, "", 12, Ui.DIM);
        heldView.setTypeface(android.graphics.Typeface.MONOSPACE);
        heldView.setPadding(0, Ui.dp(act, 4), 0, 0);
        heldView.setOnClickListener(v ->
                act.startActivity(new Intent(act, HeldActivity.class)));
        addView(heldView);

        TextView favTitle = Ui.section(act, "Favorites");
        addView(favTitle);
        favoritesGrid = new GridLayout(act);
        favoritesGrid.setColumnCount(4);
        addView(favoritesGrid, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        View spacer = new View(act);
        addView(spacer, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
    }

    /** Re-read everything that can change while the app was away. */
    public void refresh() {
        boolean showClock = Prefs.showClock(act);
        clockView.setVisibility(showClock ? View.VISIBLE : View.GONE);
        dateView.setVisibility(showClock ? View.VISIBLE : View.GONE);
        updateClock();
        updateStatus();
        buildFavorites();
    }

    private void updateClock() {
        Date now = new Date();
        clockView.setText(new SimpleDateFormat("h:mm", Locale.getDefault()).format(now));
        dateView.setText(new SimpleDateFormat("EEEE, MMMM d", Locale.getDefault()).format(now));
    }

    private void updateStatus() {
        SharedPreferences p = Prefs.get(act);
        boolean on = p.getBoolean(Prefs.KEY_FILTER_ON, false);
        long blocked = p.getLong(Prefs.KEY_BLOCKED_COUNT, 0);
        statusView.setText(on
                ? "Ad filter on · " + blocked + " blocked"
                : "Ad filter off");
        int held = HeldStore.count(act);
        heldView.setText(held > 0 ? held + " notifications held — tap to view" : "");
        heldView.setVisibility(held > 0 ? View.VISIBLE : View.GONE);
    }

    private void buildFavorites() {
        favoritesGrid.removeAllViews();
        Set<String> favs = Prefs.getSet(act, Prefs.KEY_FAVORITES);
        int cellW = (act.getResources().getDisplayMetrics().widthPixels
                - Ui.dp(act, 48)) / 4;
        if (favs.isEmpty()) {
            TextView hint = Ui.body(act,
                    "No favorites yet. Swipe up from the bottom edge for "
                    + "Apps and hold an app to star it, or use Settings → "
                    + "Home screen → Choose favorites.");
            favoritesGrid.addView(hint);
            return;
        }
        for (String pkg : favs) {
            InstalledApps.Entry e = InstalledApps.one(act, pkg);
            if (e == null) continue;
            LinearLayout cell = new LinearLayout(act);
            cell.setOrientation(LinearLayout.VERTICAL);
            cell.setGravity(Gravity.CENTER);
            cell.setPadding(0, Ui.dp(act, 10), 0, Ui.dp(act, 10));
            ImageView iv = new ImageView(act);
            iv.setImageDrawable(InstalledApps.iconFor(act, pkg));
            int sz = Ui.dp(act, 46);
            cell.addView(iv, new LinearLayout.LayoutParams(sz, sz));
            TextView label = Ui.text(act, e.label, 11, Ui.DIM);
            label.setGravity(Gravity.CENTER);
            label.setMaxLines(1);
            cell.addView(label);
            cell.setOnClickListener(v -> InstalledApps.launch(act, pkg));
            GridLayout.LayoutParams gp = new GridLayout.LayoutParams();
            gp.width = cellW;
            favoritesGrid.addView(cell, gp);
        }
    }

    @Override
    public void onPageShown() {
        shown = true;
        refresh();
        tick.removeCallbacks(ticker);
        tick.postDelayed(ticker, 20_000);
    }

    @Override
    public void onPageHidden() {
        shown = false;
        tick.removeCallbacks(ticker);
    }

    @Override
    public void onPageDestroy() {
        tick.removeCallbacks(ticker);
    }

    @Override
    public boolean ownsHorizontalGesture(float x, float y) {
        return false; // nothing on Home pans horizontally — swipes page
    }
}
