package app.control.launcher;

import android.app.Activity;
import android.app.AlertDialog;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The Cameras pager page (v1.31): network cameras on the
 * local network, as snapshots — plus live MJPEG where a
 * camera offers it. No account, no cloud: the page talks
 * to cameras on the Wi-Fi or hotspot the phone is on,
 * with credentials the user typed once and that never
 * leave the device except in an Authorization header to
 * the camera itself.
 *
 * Layout: a two-column grid of tiles (latest snapshot +
 * name + status + last-refresh time), tap for the
 * full-screen view. "Scan for cameras" runs the three
 * discovery layers (mDNS, ONVIF, subnet sweep) only while
 * the scan dialog is up; "Add manually" takes an address
 * by hand. Auto-refresh runs only while this page is the
 * pager's shown page, at each camera's own interval.
 *
 * Honesty rules, enforced in the tile states: a camera
 * that stops answering keeps its last picture, grayed,
 * with "Unreachable since <time>" and a retry — never a
 * spinner, never a blank tile hiding the failure. A 401
 * says "Check the username and password" and stops
 * auto-refresh for that camera until the user retries —
 * never a retry loop hammering a wrong password. A camera
 * with no picture yet says so. The empty state says
 * plainly that cloud-only cameras (Ring, Nest, Wyze
 * cloud) cannot be pulled directly, and that RTSP live
 * video, recording, motion alerts, and PTZ are not in
 * this version.
 *
 * Sharing: the "Share cameras with the mesh" switch
 * (default off) starts the app-level proxy
 * (CamProxyServer) and the announcement listener shows
 * other phones' shared cameras in this same grid, marked
 * "via <phone name>". Shared cameras are session-only
 * and read-only here.
 */
public class CameraPage extends FrameLayout implements PagerLayout.PagerPage {

    private final Activity act;
    private final Handler main = new Handler(Looper.getMainLooper());

    // Grid view.
    private LinearLayout gridRoot;
    private LinearLayout gridRows;
    private TextView statusView;
    private TextView emptyView;
    private Switch shareSwitch;

    // Detail view (swapped in-page, like ChatPage's thread).
    private LinearLayout detailRoot;
    private ImageView detailImage;
    private TextView detailName;
    private TextView detailAddr;
    private TextView detailStatus;
    private Button detailRefresh;
    private Button detailEdit;
    private Button detailRemove;
    private String detailKey;      // own camera id, or proxy key
    private boolean detailIsProxy;

    private boolean pageVisible;
    private final Map<String, Tile> tiles = new LinkedHashMap<>();
    private final Map<String, TileViews> tileViews = new HashMap<>();
    private final CamProxyClient proxyClient = new CamProxyClient();
    private CamScanner scanner;
    private AlertDialog scanDialog;
    private final List<CamDiscovery.Found> scanResults = new ArrayList<>();
    private android.widget.BaseAdapter scanAdapter;

    private static final int MAX_GRID_STREAMS = 4;
    private static final long FRAME_UI_MIN_MS = 200; // ~5 fps per tile

    /** One tile's live state. */
    private static final class Tile {
        String key;            // camera id (own) or proxy key
        boolean proxy;
        String proxyIp = "";
        String phoneName = "";
        String cameraId = "";
        String name = "";      // proxied cameras: the shared name
        int proxyPort;
        CamRecord record;      // own cameras only
        Bitmap bitmap;
        String status = "";
        long lastGoodAt;
        long unreachableSince;
        boolean authFailed;
        long nextFetchAt;
        CamFetcher.Stream stream;
        long lastFrameUiMs;
        boolean liveBadge;
    }

    private static final class TileViews {
        ImageView image;
        TextView name;
        TextView status;
        TextView time;
        TextView badge;
        View tile;
    }

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            if (!pageVisible) return;
            refreshDue();
            main.postDelayed(this, 1000);
        }
    };

    public CameraPage(Activity host) {
        super(host);
        this.act = host;
        setBackgroundColor(0x00000000);

        buildGridView();
        buildDetailView();
        detailRoot.setVisibility(View.GONE);

        proxyClient.setListener(proxied -> {
            if (!pageVisible) return;
            syncProxied(proxied);
        });
    }

    // ---- construction ----

    private void buildGridView() {
        gridRoot = Ui.column(act);
        gridRoot.setBackgroundColor(0x00000000);
        int pad = Ui.dp(act, 20);
        gridRoot.setPadding(pad, Ui.dp(act, 20), pad, Ui.dp(act, 16));

        LinearLayout header = Ui.row(act);
        header.addView(Ui.screenTitle(act, "Cameras"));
        gridRoot.addView(header);

        LinearLayout buttons = Ui.row(act);
        Button scan = Ui.button(act, "Scan for cameras", true);
        scan.setOnClickListener(v -> openScanDialog());
        buttons.addView(scan, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        Button add = Ui.button(act, "Add manually", false);
        add.setOnClickListener(v -> openEditDialog(null, null));
        LinearLayout.LayoutParams ap = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        ap.setMargins(Ui.dp(act, 8), 0, 0, 0);
        buttons.addView(add, ap);
        Button refreshAll = Ui.button(act, "Refresh all", false);
        refreshAll.setOnClickListener(v -> refreshAllNow());
        LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        rp.setMargins(Ui.dp(act, 8), 0, 0, 0);
        buttons.addView(refreshAll, rp);
        gridRoot.addView(buttons, Ui.wrap(0, 4, 0, 0, act));

        // Sharing switch: default off, and the row says what
        // it does in one line.
        LinearLayout shareRow = Ui.row(act);
        shareRow.setPadding(0, Ui.dp(act, 10), 0, 0);
        LinearLayout shareText = Ui.column(act);
        shareText.setBackgroundColor(0x00000000);
        shareText.addView(Ui.text(act, "Share cameras with the mesh", 15, Ui.TEXT));
        shareText.addView(Ui.text(act,
                "Other Control phones nearby can view these cameras. "
                        + "They get pictures only — never addresses or passwords.",
                13, Ui.DIM));
        shareRow.addView(shareText, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        shareSwitch = new Switch(act);
        shareSwitch.setChecked(Prefs.get(act).getBoolean(Prefs.KEY_CAM_SHARE, false));
        shareSwitch.setOnCheckedChangeListener((v, checked) -> {
            Prefs.get(act).edit().putBoolean(Prefs.KEY_CAM_SHARE, checked).apply();
            CamProxyServer.reconcile(act);
            updateStatusLine();
        });
        shareRow.addView(shareSwitch);
        gridRoot.addView(shareRow);

        statusView = Ui.text(act, "", 13, Ui.DIM);
        statusView.setTypeface(android.graphics.Typeface.MONOSPACE);
        gridRoot.addView(statusView, Ui.wrap(0, 8, 0, 0, act));

        emptyView = Ui.body(act, "No cameras yet. Scan finds cameras on "
                + "this Wi-Fi network or hotspot; add one by hand when you "
                + "know its address.\n\n"
                + "Cloud-only cameras (Ring, Nest, Wyze cloud, and the "
                + "like) cannot be pulled directly — they show their "
                + "picture only through the maker's app. This page shows "
                + "snapshots, and live view where a camera offers it. "
                + "It does not record, watch for motion, or move cameras.");
        gridRoot.addView(emptyView, Ui.wrap(0, 12, 0, 0, act));

        ScrollView scroll = new ScrollView(act);
        gridRows = Ui.column(act);
        gridRows.setBackgroundColor(0x00000000);
        scroll.addView(gridRows, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        gridRoot.addView(scroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        addView(gridRoot, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
    }

    private void buildDetailView() {
        detailRoot = Ui.column(act);
        detailRoot.setBackgroundColor(Ui.BG);
        int pad = Ui.dp(act, 20);
        detailRoot.setPadding(pad, Ui.dp(act, 20), pad, Ui.dp(act, 16));

        detailImage = new ImageView(act);
        detailImage.setBackgroundColor(Color.BLACK);
        detailImage.setScaleType(ImageView.ScaleType.FIT_CENTER);
        detailImage.setAdjustViewBounds(false);
        detailRoot.addView(detailImage, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        detailName = Ui.text(act, "", 20, Ui.TEXT);
        detailName.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        detailRoot.addView(detailName, Ui.wrap(0, 12, 0, 0, act));
        detailAddr = Ui.text(act, "", 13, Ui.DIM);
        detailAddr.setTypeface(android.graphics.Typeface.MONOSPACE);
        detailRoot.addView(detailAddr);
        detailStatus = Ui.text(act, "", 13, Ui.DIM);
        detailRoot.addView(detailStatus, Ui.wrap(0, 4, 0, 0, act));

        LinearLayout buttons = Ui.row(act);
        detailRefresh = Ui.button(act, "Refresh", true);
        detailRefresh.setOnClickListener(v -> refreshDetailNow());
        buttons.addView(detailRefresh, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        detailEdit = Ui.button(act, "Edit", false);
        detailEdit.setOnClickListener(v -> openEditDialog(detailKey, null));
        LinearLayout.LayoutParams ep = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        ep.setMargins(Ui.dp(act, 8), 0, 0, 0);
        buttons.addView(detailEdit, ep);
        detailRemove = Ui.button(act, "Remove", false);
        detailRemove.setOnClickListener(v -> confirmRemoveDetail());
        LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        rp.setMargins(Ui.dp(act, 8), 0, 0, 0);
        buttons.addView(detailRemove, rp);
        detailRoot.addView(buttons, Ui.wrap(0, 12, 0, 0, act));

        Button back = Ui.button(act, "Back to cameras", false);
        back.setOnClickListener(v -> closeDetail());
        detailRoot.addView(back, Ui.wrap(0, 8, 0, 0, act));

        addView(detailRoot, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
    }

    // ---- pager lifecycle ----

    @Override
    public void onPageShown() {
        pageVisible = true;
        shareSwitch.setChecked(Prefs.get(act).getBoolean(Prefs.KEY_CAM_SHARE, false));
        CamProxyServer.reconcile(act);
        rebuildTiles();
        proxyClient.start();
        syncProxied(proxyClient.current());
        main.removeCallbacks(tick);
        main.post(tick);
        startGridStreams();
    }

    @Override
    public void onPageHidden() {
        pageVisible = false;
        main.removeCallbacks(tick);
        stopAllStreams();
        proxyClient.stop();
        stopScan();
        if (detailRoot.getVisibility() == View.VISIBLE) {
            closeDetail();
        }
    }

    @Override
    public void onPageDestroy() {
        onPageHidden();
        for (Tile t : tiles.values()) {
            if (t.bitmap != null) t.bitmap.recycle();
        }
        tiles.clear();
    }

    @Override
    public boolean ownsHorizontalGesture(float x, float y) {
        return false; // the grid scrolls vertically; paging always wins horizontally
    }

    /** Back inside the detail view returns to the grid. */
    public boolean onBackPressed() {
        if (detailRoot.getVisibility() == View.VISIBLE) {
            closeDetail();
            return true;
        }
        return false;
    }

    // ---- tiles ----

    /** Rebuilds the own-camera tiles from storage, keeping
     *  live state (bitmaps, unreachable times) for cameras
     *  that are still there. */
    private void rebuildTiles() {
        Map<String, Tile> kept = new LinkedHashMap<>();
        try {
            for (CamRecord r : CamStore.list(act)) {
                Tile t = tiles.get(r.id);
                if (t == null) {
                    t = new Tile();
                    t.key = r.id;
                    t.proxy = false;
                    t.cameraId = r.id;
                    t.nextFetchAt = 0;
                    t.liveBadge = !r.mjpegUrl().isEmpty();
                    byte[] last = CamStore.lastImage(act, r.id);
                    if (last != null) {
                        t.bitmap = decode(last);
                        t.lastGoodAt = r.lastGoodAt;
                        t.status = t.bitmap != null ? "Last picture" : "";
                    }
                } else {
                    t.record = r;
                    t.liveBadge = !r.mjpegUrl().isEmpty();
                    if (t.stream != null && t.liveBadge) {
                        // MJPEG address may have changed; restart below.
                        t.stream.stop();
                        t.stream = null;
                    }
                }
                t.record = r;
                kept.put(r.id, t);
            }
        } catch (CamStore.CorruptException e) {
            Toast.makeText(act, "The saved cameras could not be read.",
                    Toast.LENGTH_LONG).show();
        }
        // Drop removed cameras' tiles (and their streams).
        for (Tile t : tiles.values()) {
            if (!t.proxy && !kept.containsKey(t.key)) {
                if (t.stream != null) t.stream.stop();
                if (t.bitmap != null) t.bitmap.recycle();
            }
        }
        // Keep proxied tiles alongside the rebuilt own tiles.
        for (Tile t : tiles.values()) {
            if (t.proxy) kept.put(t.key, t);
        }
        tiles.clear();
        tiles.putAll(kept);
        renderGrid();
        updateStatusLine();
    }

    private void syncProxied(List<CamProxyClient.Proxied> proxied) {
        boolean changed = false;
        Map<String, CamProxyClient.Proxied> byKey = new HashMap<>();
        for (CamProxyClient.Proxied p : proxied) byKey.put(p.key(), p);
        // Drop gone proxies.
        List<String> dead = new ArrayList<>();
        for (Tile t : tiles.values()) {
            if (t.proxy && !byKey.containsKey(t.key)) dead.add(t.key);
        }
        for (String k : dead) {
            Tile t = tiles.remove(k);
            if (t != null && t.bitmap != null) t.bitmap.recycle();
            changed = true;
        }
        // Add new proxies.
        for (CamProxyClient.Proxied p : proxied) {
            if (!tiles.containsKey(p.key())) {
                Tile t = new Tile();
                t.key = p.key();
                t.proxy = true;
                t.proxyIp = p.proxyIp;
                t.phoneName = p.phoneName;
                t.cameraId = p.cameraId;
                t.name = p.displayName();
                t.proxyPort = p.proxyPort;
                t.nextFetchAt = 0;
                t.status = "";
                tiles.put(t.key, t);
                changed = true;
            }
        }
        if (changed) {
            renderGrid();
            updateStatusLine();
        }
    }

    private void renderGrid() {
        gridRows.removeAllViews();
        tileViews.clear();
        List<Tile> list = new ArrayList<>(tiles.values());
        emptyView.setVisibility(list.isEmpty() ? View.VISIBLE : View.GONE);
        int gap = Ui.dp(act, 10);
        for (int i = 0; i < list.size(); i += 2) {
            LinearLayout row = new LinearLayout(act);
            row.setOrientation(LinearLayout.HORIZONTAL);
            for (int j = i; j < Math.min(i + 2, list.size()); j++) {
                Tile t = list.get(j);
                View tile = buildTile(t);
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0,
                        ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
                if (j > i) lp.setMargins(gap, 0, 0, 0);
                lp.setMargins(j > i ? gap : 0, 0, 0, gap);
                row.addView(tile, lp);
            }
            // Pad a lone tile so rows stay aligned.
            if (i + 1 >= list.size()) {
                View spacer = new View(act);
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0,
                        ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
                lp.setMargins(gap, 0, 0, 0);
                row.addView(spacer, lp);
            }
            gridRows.addView(row);
        }
    }

    private View buildTile(Tile t) {
        LinearLayout tile = Ui.card(act);
        tile.setPadding(Ui.dp(act, 8), Ui.dp(act, 8), Ui.dp(act, 8), Ui.dp(act, 8));
        TileViews v = new TileViews();
        v.tile = tile;

        FrameLayout imageWrap = new FrameLayout(act);
        v.image = new ImageView(act);
        v.image.setBackgroundColor(Color.BLACK);
        v.image.setScaleType(ImageView.ScaleType.CENTER_CROP);
        int imgH = Ui.dp(act, 110);
        imageWrap.addView(v.image, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, imgH));
        v.badge = Ui.text(act, "LIVE", 11, Ui.ACCENT_INK);
        v.badge.setBackground(Ui.rounded(act, Ui.ACCENT, 999));
        v.badge.setPadding(Ui.dp(act, 6), Ui.dp(act, 2), Ui.dp(act, 6), Ui.dp(act, 2));
        v.badge.setTypeface(android.graphics.Typeface.MONOSPACE);
        FrameLayout.LayoutParams bp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        bp.gravity = Gravity.TOP | Gravity.END;
        bp.setMargins(0, Ui.dp(act, 4), Ui.dp(act, 4), 0);
        imageWrap.addView(v.badge, bp);
        tile.addView(imageWrap);

        v.name = Ui.text(act, tileName(t), 15, Ui.TEXT);
        v.name.setMaxLines(1);
        v.name.setEllipsize(android.text.TextUtils.TruncateAt.END);
        tile.addView(v.name, Ui.wrap(0, 6, 0, 0, act));
        v.status = Ui.text(act, "", 12, Ui.DIM);
        v.status.setMaxLines(2);
        tile.addView(v.status);
        v.time = Ui.text(act, "", 12, Ui.DIM);
        v.time.setTypeface(android.graphics.Typeface.MONOSPACE);
        tile.addView(v.time);

        tile.setOnClickListener(vv -> openDetail(t.key));
        tileViews.put(t.key, v);
        bindTile(t);
        return tile;
    }

    private String tileName(Tile t) {
        if (t.proxy) return t.name.isEmpty() ? "Shared camera" : t.name;
        return t.record == null ? "" : t.record.displayName();
    }

    private void bindTile(Tile t) {
        TileViews v = tileViews.get(t.key);
        if (v == null) return;
        v.name.setText(tileName(t));
        if (t.bitmap != null) {
            v.image.setImageBitmap(t.bitmap);
            if (t.unreachableSince > 0) {
                v.image.setColorFilter(Color.GRAY);
                v.image.setAlpha(0.55f);
            } else {
                v.image.clearColorFilter();
                v.image.setAlpha(1f);
            }
        } else {
            v.image.setImageDrawable(null);
        }
        v.badge.setVisibility(t.liveBadge && t.stream != null ? View.VISIBLE : View.GONE);
        String status = t.status;
        if (t.authFailed) {
            status = "Check the username and password";
        } else if (t.unreachableSince > 0) {
            status = "Unreachable since " + timeOf(t.unreachableSince);
        } else if (t.proxy) {
            status = "Via " + t.phoneName;
        } else if (t.bitmap == null && t.status.isEmpty()) {
            status = "No picture yet";
        }
        v.status.setText(status);
        v.status.setTextColor(t.authFailed || t.unreachableSince > 0 ? Ui.SIGNAL : Ui.DIM);
        v.time.setText(t.lastGoodAt > 0 ? "Last picture " + timeOf(t.lastGoodAt) : "");
    }

    private void updateStatusLine() {
        int own = 0;
        int proxied = 0;
        for (Tile t : tiles.values()) {
            if (t.proxy) proxied++;
            else own++;
        }
        String sharing = Prefs.get(act).getBoolean(Prefs.KEY_CAM_SHARE, false)
                ? "sharing on" : "sharing off";
        statusView.setText(own + " camera" + (own == 1 ? "" : "s")
                + (proxied > 0 ? " · " + proxied + " shared" : "")
                + " · " + sharing);
    }

    private static String timeOf(long ms) {
        return new SimpleDateFormat("HH:mm", Locale.US).format(new Date(ms));
    }

    private static Bitmap decode(byte[] jpeg) {
        try {
            return BitmapFactory.decodeByteArray(jpeg, 0, jpeg.length);
        } catch (Exception e) {
            return null;
        }
    }

    // ---- refresh engine ----

    private void refreshDue() {
        long now = System.currentTimeMillis();
        for (Tile t : tiles.values()) {
            if (t.stream != null) continue; // live: frames arrive on their own
            if (t.authFailed) continue;     // never hammer a wrong password
            if (t.proxy) {
                if (now >= t.nextFetchAt) fetchProxiedTile(t);
                continue;
            }
            if (t.record == null || !t.record.fetchable()) continue;
            int interval = t.record.refreshSeconds;
            if (interval == 0) continue;    // manual refresh only
            if (now >= t.nextFetchAt) fetchTile(t);
        }
    }

    private void refreshAllNow() {
        for (Tile t : tiles.values()) {
            t.authFailed = false;
            t.nextFetchAt = 0;
            if (t.stream == null) {
                if (t.proxy) fetchProxiedTile(t);
                else if (t.record != null && t.record.fetchable()) fetchTile(t);
            }
        }
    }

    private void fetchTile(Tile t) {
        final CamRecord rec = t.record;
        if (rec == null) return;
        t.nextFetchAt = System.currentTimeMillis() + rec.refreshSeconds * 1000L;
        CamFetcher.fetchSnapshot(act, rec, new CamFetcher.SnapshotCallback() {
            @Override public void onOk(byte[] jpeg) {
                main.post(() -> {
                    Tile cur = tiles.get(t.key);
                    if (cur == null || cur.record != rec) return;
                    setTileBitmap(cur, decode(jpeg));
                    cur.lastGoodAt = System.currentTimeMillis();
                    cur.unreachableSince = 0;
                    cur.authFailed = false;
                    cur.status = "";
                    bindTile(cur);
                    if (detailKey != null && detailKey.equals(cur.key)) bindDetail(cur);
                });
            }

            @Override public void onFail(String message, boolean authProblem) {
                main.post(() -> {
                    Tile cur = tiles.get(t.key);
                    if (cur == null || cur.record != rec) return;
                    if (authProblem) {
                        cur.authFailed = true;
                        cur.status = message;
                    } else if (cur.unreachableSince == 0) {
                        cur.unreachableSince = System.currentTimeMillis();
                    }
                    bindTile(cur);
                    if (detailKey != null && detailKey.equals(cur.key)) bindDetail(cur);
                });
            }
        });
    }

    private void fetchProxiedTile(Tile t) {
        t.nextFetchAt = System.currentTimeMillis()
                + CamFeed.DEFAULT_REFRESH_SECONDS * 1000L;
        CamProxyClient.Proxied p = new CamProxyClient.Proxied(t.proxyIp, t.phoneName,
                t.cameraId, t.name, t.proxyPort, System.currentTimeMillis());
        CamProxyClient.fetchProxied(p, new CamFetcher.SnapshotCallback() {
            @Override public void onOk(byte[] jpeg) {
                main.post(() -> {
                    Tile cur = tiles.get(t.key);
                    if (cur == null) return;
                    setTileBitmap(cur, decode(jpeg));
                    cur.lastGoodAt = System.currentTimeMillis();
                    cur.unreachableSince = 0;
                    bindTile(cur);
                    if (detailKey != null && detailKey.equals(cur.key)) bindDetail(cur);
                });
            }

            @Override public void onFail(String message, boolean authProblem) {
                main.post(() -> {
                    Tile cur = tiles.get(t.key);
                    if (cur == null) return;
                    if (cur.unreachableSince == 0) {
                        cur.unreachableSince = System.currentTimeMillis();
                    }
                    bindTile(cur);
                    if (detailKey != null && detailKey.equals(cur.key)) bindDetail(cur);
                });
            }
        });
    }

    private void setTileBitmap(Tile t, Bitmap next) {
        if (next == null) return;
        Bitmap old = t.bitmap;
        t.bitmap = next;
        if (old != null && old != next) old.recycle();
    }

    // ---- MJPEG grid streams ----

    private void startGridStreams() {
        if (detailRoot.getVisibility() == View.VISIBLE) return;
        int running = 0;
        for (Tile t : tiles.values()) {
            if (running >= MAX_GRID_STREAMS) break;
            if (t.proxy || t.record == null || t.stream != null) continue;
            if (t.record.mjpegUrl().isEmpty()) continue;
            startTileStream(t);
            running++;
        }
    }

    private void startTileStream(Tile t) {
        final CamRecord rec = t.record;
        t.stream = CamFetcher.startStream(act, rec, new CamFetcher.StreamListener() {
            @Override public void onFrame(byte[] jpeg) {
                long now = System.currentTimeMillis();
                if (now - t.lastFrameUiMs < FRAME_UI_MIN_MS) return;
                t.lastFrameUiMs = now;
                final Bitmap bmp = decode(jpeg);
                if (bmp == null) return;
                main.post(() -> {
                    Tile cur = tiles.get(t.key);
                    if (cur == null || cur.record != rec || cur.stream == null) {
                        bmp.recycle();
                        return;
                    }
                    setTileBitmap(cur, bmp);
                    cur.lastGoodAt = System.currentTimeMillis();
                    cur.unreachableSince = 0;
                    cur.authFailed = false;
                    bindTile(cur);
                    if (detailKey != null && detailKey.equals(cur.key)) bindDetail(cur);
                });
            }

            @Override public void onEnded(String message) {
                main.post(() -> {
                    Tile cur = tiles.get(t.key);
                    if (cur == null || cur.record != rec) return;
                    cur.stream = null;
                    cur.nextFetchAt = 0; // fall back to snapshots
                    bindTile(cur);
                });
            }
        });
        bindTile(t);
    }

    private void stopAllStreams() {
        for (Tile t : tiles.values()) {
            if (t.stream != null) {
                t.stream.stop();
                t.stream = null;
            }
        }
    }

    // ---- detail view ----

    private void openDetail(String key) {
        Tile t = tiles.get(key);
        if (t == null) return;
        detailKey = key;
        detailIsProxy = t.proxy;
        stopAllStreams();
        gridRoot.setVisibility(View.GONE);
        detailRoot.setVisibility(View.VISIBLE);
        detailEdit.setVisibility(t.proxy ? View.GONE : View.VISIBLE);
        detailRemove.setVisibility(t.proxy ? View.GONE : View.VISIBLE);
        bindDetail(t);
        // A detail view with a live stream gets the stream;
        // otherwise it refreshes once on open.
        if (!t.proxy && t.record != null && !t.record.mjpegUrl().isEmpty()) {
            startTileStream(t);
        } else if (t.bitmap == null) {
            refreshDetailNow();
        }
    }

    private void bindDetail(Tile t) {
        detailName.setText(tileName(t));
        if (t.proxy) {
            detailAddr.setText("Shared by " + t.phoneName + " · via the mesh");
        } else if (t.record != null) {
            detailAddr.setText(t.record.host + ":" + t.record.port
                    + (t.record.vendor.isEmpty() ? "" : " · " + t.record.vendor));
        }
        if (t.bitmap != null) {
            detailImage.setImageBitmap(t.bitmap);
            if (t.unreachableSince > 0) {
                detailImage.setColorFilter(Color.GRAY);
                detailImage.setAlpha(0.55f);
            } else {
                detailImage.clearColorFilter();
                detailImage.setAlpha(1f);
            }
        } else {
            detailImage.setImageDrawable(null);
        }
        String status;
        if (t.authFailed) {
            status = "Check the username and password, then refresh.";
        } else if (t.unreachableSince > 0) {
            status = "Unreachable since " + timeOf(t.unreachableSince)
                    + " — showing the last picture. Refresh to retry.";
        } else if (t.stream != null) {
            status = "Live view" + (t.lastGoodAt > 0 ? " · last frame " + timeOf(t.lastGoodAt) : "");
        } else if (t.lastGoodAt > 0) {
            status = "Last picture " + timeOf(t.lastGoodAt);
        } else {
            status = "No picture yet — refresh to fetch one.";
        }
        if (t.proxy) status += " · shared by " + t.phoneName;
        detailStatus.setText(status);
    }

    private void refreshDetailNow() {
        Tile t = detailKey == null ? null : tiles.get(detailKey);
        if (t == null) return;
        t.authFailed = false;
        t.nextFetchAt = 0;
        if (t.stream != null) return; // live: frames keep coming
        if (t.proxy) fetchProxiedTile(t);
        else if (t.record != null && t.record.fetchable()) fetchTile(t);
    }

    private void closeDetail() {
        detailKey = null;
        stopAllStreams();
        detailRoot.setVisibility(View.GONE);
        gridRoot.setVisibility(View.VISIBLE);
        if (pageVisible) startGridStreams();
    }

    private void confirmRemoveDetail() {
        Tile t = detailKey == null ? null : tiles.get(detailKey);
        if (t == null || t.proxy || t.record == null) return;
        final CamRecord rec = t.record;
        new AlertDialog.Builder(act)
                .setTitle("Remove this camera?")
                .setMessage("“" + rec.displayName() + "” will be removed from "
                        + "this phone. Its saved password goes with it.")
                .setNegativeButton("Keep", null)
                .setPositiveButton("Remove", (d, w) -> {
                    try {
                        CamStore.delete(act, rec.id);
                    } catch (Exception e) {
                        Toast.makeText(act, "Could not remove the camera.",
                                Toast.LENGTH_SHORT).show();
                        return;
                    }
                    closeDetail();
                    rebuildTiles();
                })
                .show();
    }

    // ---- circle-menu support ----

    /** Own cameras for the circle menu's Cameras sub-ring. */
    public List<CamRecord> ringCameras() {
        List<CamRecord> out = new ArrayList<>();
        try {
            out.addAll(CamStore.list(act));
        } catch (Exception ignored) {
        }
        return out;
    }

    /** Opens a camera's detail view from the ring. */
    public boolean openCameraById(String cameraId) {
        if (cameraId == null) return false;
        if (!tiles.containsKey(cameraId)) rebuildTiles();
        if (!tiles.containsKey(cameraId)) return false;
        openDetail(cameraId);
        return true;
    }

    // ---- scan dialog ----

    private void openScanDialog() {
        stopScan();
        scanResults.clear();
        LinearLayout box = Ui.column(act);
        box.setBackgroundColor(0x00000000);
        box.setPadding(Ui.dp(act, 16), Ui.dp(act, 8), Ui.dp(act, 16), 0);
        final TextView scanStatus = Ui.text(act, "Scanning this network…",
                14, Ui.DIM);
        box.addView(scanStatus);
        android.widget.ListView list = new android.widget.ListView(act);
        list.setDivider(null);
        scanAdapter = new android.widget.BaseAdapter() {
            @Override public int getCount() { return scanResults.size(); }
            @Override public Object getItem(int pos) { return scanResults.get(pos); }
            @Override public long getItemId(int pos) { return pos; }

            @Override
            public View getView(int pos, View convert, ViewGroup parent) {
                CamDiscovery.Found f = scanResults.get(pos);
                LinearLayout row = new LinearLayout(act);
                row.setOrientation(LinearLayout.VERTICAL);
                row.setPadding(Ui.dp(act, 12), Ui.dp(act, 10),
                        Ui.dp(act, 12), Ui.dp(act, 10));
                row.setBackground(Ui.roundedStroke(act, Ui.SURFACE, 14, Ui.LINE));
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT);
                lp.setMargins(0, Ui.dp(act, 4), 0, Ui.dp(act, 4));
                row.setLayoutParams(lp);
                row.addView(Ui.text(act, f.displayName()
                        + (f.vendor.isEmpty() ? "" : " · " + f.vendor), 16, Ui.TEXT));
                String src = f.confirmed() ? "Camera" : "Possible camera";
                row.addView(Ui.text(act, src + " · " + f.ip, 13, Ui.DIM));
                return row;
            }
        };
        list.setAdapter(scanAdapter);
        list.setOnItemClickListener((parent, view, pos, id) -> {
            CamDiscovery.Found f = scanResults.get(pos);
            stopScan();
            if (scanDialog != null) scanDialog.dismiss();
            openEditDialog(null, f);
        });
        LinearLayout.LayoutParams listLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(act, 300));
        listLp.setMargins(0, Ui.dp(act, 8), 0, 0);
        box.addView(list, listLp);

        scanDialog = new AlertDialog.Builder(act)
                .setTitle("Scan for cameras")
                .setView(box)
                .setNegativeButton("Stop", (d, w) -> stopScan())
                .setOnDismissListener(d -> stopScan())
                .show();
        scanner = new CamScanner(act, new CamScanner.Listener() {
            @Override public void onFound(CamDiscovery.Found found) {
                boolean seen = false;
                for (CamDiscovery.Found f : scanResults) {
                    if (f.ip.equals(found.ip)) { seen = true; break; }
                }
                if (!seen) scanResults.add(found);
                scanAdapter.notifyDataSetChanged();
                scanStatus.setText(scanResults.size() + " device"
                        + (scanResults.size() == 1 ? "" : "s") + " found — tap one to add it");
            }

            @Override public void onDone(List<CamDiscovery.Found> all) {
                scanAdapter.notifyDataSetChanged();
                scanStatus.setText(all.isEmpty()
                        ? "No cameras found on this network. Try Add manually, "
                        + "or check the camera is on the same Wi-Fi or hotspot."
                        : all.size() + " device" + (all.size() == 1 ? "" : "s")
                        + " found — tap one to add it");
            }
        });
        scanner.start();
    }

    private void stopScan() {
        if (scanner != null) {
            scanner.stop();
            scanner = null;
        }
    }

    // ---- add / edit dialog ----

    /**
     * The add/edit form. {@code cameraId} edits an existing
     * camera; {@code found} pre-fills from a scan result;
     * both null means a blank manual add. The dialog never
     * shows a stored password in the clear beyond the
     * password field itself, and saving seals the
     * credentials before anything is written.
     */
    private void openEditDialog(String cameraId, CamDiscovery.Found found) {
        CamRecord existing = cameraId == null ? null : CamStore.byId(act, cameraId);
        LinearLayout box = Ui.column(act);
        box.setBackgroundColor(0x00000000);
        box.setPadding(Ui.dp(act, 16), Ui.dp(act, 8), Ui.dp(act, 16), 0);

        final android.widget.EditText nameField = Ui.input(act, "Name, e.g. Front door");
        final android.widget.EditText hostField = Ui.input(act, "Address, e.g. 192.168.1.20");
        final android.widget.EditText portField = Ui.input(act, "Port, e.g. 80");
        portField.setInputType(InputType.TYPE_CLASS_NUMBER);
        final android.widget.EditText snapField = Ui.input(act,
                "Snapshot address, e.g. /snapshot.jpg");
        final android.widget.EditText mjpegField = Ui.input(act,
                "Live view address (optional)");
        final android.widget.EditText userField = Ui.input(act, "Camera username");
        final android.widget.EditText passField = Ui.input(act, "Camera password");
        passField.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        final Spinner intervalSpinner = new Spinner(act);
        String[] labels = new String[CamFeed.REFRESH_INTERVALS.length];
        for (int i = 0; i < labels.length; i++) {
            labels[i] = CamFeed.refreshLabel(CamFeed.REFRESH_INTERVALS[i]);
        }
        intervalSpinner.setAdapter(new ArrayAdapter<>(act,
                android.R.layout.simple_spinner_dropdown_item, labels));
        final TextView resolveStatus = Ui.text(act, "", 13, Ui.DIM);

        String[] creds = new String[]{"", ""};
        int port = 80;
        int interval = CamFeed.DEFAULT_REFRESH_SECONDS;
        if (existing != null) {
            nameField.setText(existing.name);
            hostField.setText(existing.host);
            portField.setText(String.valueOf(existing.port));
            snapField.setText(existing.snapshotPath);
            mjpegField.setText(existing.mjpegPath);
            interval = existing.refreshSeconds;
            try {
                creds = CamStore.unsealCreds(existing.credCipher);
            } catch (Exception e) {
                Toast.makeText(act, "The saved password could not be read — "
                        + "enter it again.", Toast.LENGTH_LONG).show();
            }
        } else if (found != null) {
            nameField.setText(found.name);
            hostField.setText(found.ip);
            if (!found.ports.isEmpty()) {
                int first = found.ports.first();
                if (first != 554 && first != 443) port = first;
            }
            portField.setText(String.valueOf(port));
            snapField.setText("");
        } else {
            portField.setText("80");
        }
        userField.setText(creds[0]);
        passField.setText(creds[1]);
        for (int i = 0; i < CamFeed.REFRESH_INTERVALS.length; i++) {
            if (CamFeed.REFRESH_INTERVALS[i] == interval) {
                intervalSpinner.setSelection(i);
                break;
            }
        }
        final List<String> xaddrs = found == null ? null : found.xaddrs;
        final String vendor = existing != null ? existing.vendor
                : (found == null ? "" : found.vendor);

        box.addView(nameField);
        box.addView(hostField);
        box.addView(portField);
        box.addView(snapField);
        box.addView(mjpegField);
        box.addView(userField);
        box.addView(passField);
        box.addView(intervalSpinner);
        box.addView(resolveStatus);

        Button findButton = Ui.button(act, "Find the snapshot address", false);
        findButton.setOnClickListener(v -> {
            String host = hostField.getText().toString().trim();
            int p = parsePortField(portField.getText().toString().trim());
            if (!CamRecord.validHost(host) || p < 0) {
                resolveStatus.setText("Enter a valid address and port first.");
                return;
            }
            resolveStatus.setText("Looking for the snapshot address…");
            findButton.setEnabled(false);
            CamFetcher.resolveFeed(host, p, vendor, xaddrs,
                    userField.getText().toString(),
                    passField.getText().toString(),
                    new CamFetcher.ResolveCallback() {
                        @Override public void onResolved(CamFeed.Resolved r) {
                            main.post(() -> {
                                findButton.setEnabled(true);
                                snapField.setText(toStoredPath(r.snapshotUrl, host, p));
                                if (!r.mjpegUrl.isEmpty()) {
                                    mjpegField.setText(toStoredPath(r.mjpegUrl, host, p));
                                }
                                resolveStatus.setText("Found it"
                                        + (r.mjpegUrl.isEmpty() ? "" : " (live view too)")
                                        + " — save to keep it.");
                            });
                        }

                        @Override public void onFailed(String message, boolean authProblem) {
                            main.post(() -> {
                                findButton.setEnabled(true);
                                resolveStatus.setText(message);
                            });
                        }
                    });
        });
        box.addView(findButton, Ui.wrap(0, 8, 0, 0, act));

        box.addView(Ui.body(act, "The password is stored encrypted on "
                + "this phone only. It is never sent anywhere except "
                + "to this camera, and it never leaves in a profile backup."));

        new AlertDialog.Builder(act)
                .setTitle(existing != null ? "Edit camera" : "Add a camera")
                .setView(box)
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Save", (d, w) -> {
                    String name = nameField.getText().toString().trim();
                    String host = hostField.getText().toString().trim();
                    int p = parsePortField(portField.getText().toString().trim());
                    String snap = snapField.getText().toString().trim();
                    String mjpeg = mjpegField.getText().toString().trim();
                    String user = userField.getText().toString();
                    String pass = passField.getText().toString();
                    if (!CamRecord.validHost(host) || p < 0) {
                        Toast.makeText(act, "Enter a valid address and port.",
                                Toast.LENGTH_LONG).show();
                        return;
                    }
                    if (snap.isEmpty()) {
                        Toast.makeText(act, "Enter a snapshot address, or use "
                                + "“Find the snapshot address”.", Toast.LENGTH_LONG).show();
                        return;
                    }
                    if (user.contains("\n") || pass.contains("\n")) {
                        Toast.makeText(act, "The username and password cannot "
                                + "contain line breaks.", Toast.LENGTH_LONG).show();
                        return;
                    }
                    CamRecord rec = existing != null ? existing : new CamRecord();
                    rec.name = name;
                    rec.host = host;
                    rec.port = p;
                    rec.snapshotPath = snap;
                    rec.mjpegPath = mjpeg;
                    rec.vendor = vendor;
                    rec.refreshSeconds = CamFeed.REFRESH_INTERVALS[
                            intervalSpinner.getSelectedItemPosition()];
                    try {
                        rec.credCipher = CamStore.sealCreds(user, pass);
                        CamStore.save(act, rec);
                    } catch (Exception e) {
                        Toast.makeText(act, "Could not save the camera: "
                                + e.getMessage(), Toast.LENGTH_LONG).show();
                        return;
                    }
                    rebuildTiles();
                    Tile t = tiles.get(rec.id);
                    if (t != null) {
                        t.nextFetchAt = 0;
                        t.authFailed = false;
                        fetchTile(t);
                    }
                    startGridStreams();
                    Toast.makeText(act, "Camera saved.", Toast.LENGTH_SHORT).show();
                })
                .show();
    }

    /** A resolved absolute URL, stored as a path when it
     *  points at this same host (so a later host/port edit
     *  keeps working); kept whole otherwise. */
    private static String toStoredPath(String url, String host, int port) {
        if (url == null) return "";
        CamHttp.Url u = CamHttp.parseUrl(url);
        if (u == null) return url;
        if (u.host.equals(host) && u.port == port) return u.target;
        return url;
    }

    private static int parsePortField(String text) {
        try {
            int p = Integer.parseInt(text.trim());
            return (p >= 1 && p <= 65535) ? p : -1;
        } catch (Exception e) {
            return -1;
        }
    }
}
