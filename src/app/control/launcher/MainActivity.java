package app.control.launcher;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.provider.Settings;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The launcher shell (v1.3 spatial layout, v1.5 adds Chat): a
 * FOUR-page pager — Browser LEFT of Home, Walkie RIGHT of Home,
 * Chat right of Walkie — starting on Home. Apps is no longer a
 * page: it's a full-screen sheet that rises from the bottom over
 * the current page (bottom-edge swipe-up, circle menu, or the Apps
 * tiny icon).
 *
 * Navigation (v1.14): pager swipe, plus a CIRCLE MENU on a
 * long-press of launcher chrome — ring of Home / Browser /
 * Walkie / Apps / Chat / Activity / Connections / Settings /
 * Wallet. The tiny page-icon strip is gone (Adam, v1.14): the
 * pages fill the screen and the menu + swipe carry navigation.
 * The Home page carries no buttons at all: clock, status lines
 * and the favorites grid, nothing else.
 *
 * Since v1.9 the circle menu has SUB-RINGS: holding a finger on a
 * ring icon for 1 second morphs the ring into that destination's
 * children (Apps → every app, Walkie → all 50 channels, Settings →
 * its sections, and so on). This activity supplies the sub-ring
 * contents and runs every leaf action through the same code path
 * the destination itself uses — launching, tuning, and opening
 * from the ring are identical to doing it from the sheet, the
 * tuner, or the list.
 *
 * Activities that used to be top-level screens (BrowserActivity,
 * WalkieActivity, DrawerActivity, and since v1.5 ChatActivity and
 * RoomActivity) are thin forwarders that land here on the right
 * page (or raise the sheet) — external web links included; they
 * land on the Browser page, now index 0. A forwarded conversation
 * (the old RoomActivity extras) lands on the Chat page with its
 * thread open.
 */
public class MainActivity extends ThemedActivity {

    public static final String EXTRA_PAGE = "control.page";
    public static final String EXTRA_URL = "control.url";
    public static final String EXTRA_APPS = "control.apps_sheet";
    /** A conversation to open in the Chat page's thread (v1.5 —
     *  carried across by the RoomActivity forwarder). */
    public static final String EXTRA_CHAT_CHANNEL_ID = "control.chat_channel";
    public static final String EXTRA_CHAT_CHANNEL_KEY = "control.chat_key";
    public static final String EXTRA_CHAT_NAME = "control.chat_name";
    public static final String EXTRA_CHAT_PEER = "control.chat_peer";
    /** Tune the walkie to this channel, then land on the Walkie
     *  page (v1.14 — the overlay ring's route, since it has no
     *  live page to tune). */
    public static final String EXTRA_TUNE_CHANNEL = "control.tune_channel";
    /** Request the home role on arrival (v1.14 — the overlay
     *  ring's route, since RoleManager needs an activity). */
    public static final String EXTRA_REQUEST_HOME_ROLE = "control.request_home_role";
    public static final int REQ_HOME_ROLE = 7100;
    public static final int REQ_WALKIE_LISTEN = 7200;
    public static final int REQ_CHAT_LISTEN = 7300;

    public static final int PAGE_BROWSER = 0;
    public static final int PAGE_HOME = 1;
    public static final int PAGE_WALKIE = 2;
    public static final int PAGE_CHAT = 3;

    private PagerLayout pager;
    private BrowserPage browserPage;
    private HomePage homePage;
    private WalkiePage walkiePage;
    private ChatPage chatPage;
    private AppsSheetView appsSheet;
    private CircleMenuView circleMenu;
    private BackgroundView backgroundView;
    private int currentPage = PAGE_HOME;
    private boolean pagesLive;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        LinearLayout root = Ui.column(this);
        root.setBackgroundColor(Ui.BG);
        setContentView(root);

        pager = new PagerLayout(this);
        browserPage = new BrowserPage(this);
        homePage = new HomePage(this);
        walkiePage = new WalkiePage(this);
        chatPage = new ChatPage(this);
        pager.addView(browserPage);
        pager.addView(homePage);
        pager.addView(walkiePage);
        pager.addView(chatPage);
        pager.setListener(index -> selectPage(index));

        // Stage: the shared background layer at the back, the pager
        // over it (pages are transparent so the background shows
        // through), the Apps sheet over the pager, and the circle
        // menu overlaid on top (menu coordinates == pager coordinates).
        android.widget.FrameLayout stage = new android.widget.FrameLayout(this);
        backgroundView = new BackgroundView(this);
        stage.addView(backgroundView, new android.widget.FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        stage.addView(pager, new android.widget.FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        appsSheet = new AppsSheetView(this);
        stage.addView(appsSheet, new android.widget.FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        circleMenu = new CircleMenuView(this);
        stage.addView(circleMenu, new android.widget.FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        root.addView(stage, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        pager.setMenuTouchHandler(ev -> circleMenu.onMenuTouch(ev));
        pager.setChromeLongPressListener((x, y) -> circleMenu.showAt(x, y));
        pager.setBottomStripListener(() -> appsSheet.show());
        circleMenu.setListener(new CircleMenuView.Listener() {
            @Override public void onChoose(int item) {
                pager.setMenuOpen(false);
                chooseMenuItem(item);
            }

            @Override public void onDismiss() {
                pager.setMenuOpen(false);
            }
        });
        circleMenu.setSource(ringSource);

        // Start on Home (the middle page); the pager's pending-page
        // mechanism settles this on first layout.
        pager.goToPage(PAGE_HOME, false);
        handleIntent(getIntent());

        RelayClient.ensureRegistered(this);

        if (Prefs.get(this).getBoolean(Prefs.KEY_APP_LOCKED, false)) {
            startActivity(new Intent(this, LockActivity.class));
        }
        // First run: the four-step setup (name, permissions,
        // import, theme) before anything else. The flag alone
        // decides: existing installs already carry it, so
        // upgrading never re-shows setup.
        if (!Prefs.get(this).getBoolean(Prefs.KEY_FIRSTRUN_DONE, false)) {
            startActivity(new Intent(this, SetupActivity.class));
        }
    }

    private void chooseMenuItem(int item) {
        switch (item) {
            case CircleMenuView.ITEM_HOME:
                pager.goToPage(PAGE_HOME, true);
                break;
            case CircleMenuView.ITEM_BROWSER:
                pager.goToPage(PAGE_BROWSER, true);
                break;
            case CircleMenuView.ITEM_WALKIE:
                pager.goToPage(PAGE_WALKIE, true);
                break;
            case CircleMenuView.ITEM_APPS:
                appsSheet.show();
                break;
            case CircleMenuView.ITEM_CHAT:
                pager.goToPage(PAGE_CHAT, true);
                break;
            case CircleMenuView.ITEM_ACTIVITY:
                startActivity(new Intent(this, ControlRoomActivity.class));
                break;
            case CircleMenuView.ITEM_CONNECTIONS:
                startActivity(new Intent(this, ConnectionsActivity.class));
                break;
            case CircleMenuView.ITEM_SETTINGS:
                startActivity(new Intent(this, SettingsActivity.class));
                break;
            case CircleMenuView.ITEM_WALLET:
                startActivity(new Intent(this, WalletActivity.class));
                break;
            default:
                break;
        }
    }

    // ---- circle-menu sub-rings (v1.9) ----

    private final RingItems.Source ringSource = new RingItems.Source() {
        @Override public List<RingItems.Item> itemsFor(int parentItem) {
            switch (parentItem) {
                case CircleMenuView.ITEM_APPS: return appRingItems();
                case CircleMenuView.ITEM_SETTINGS: return settingsRingItems();
                case CircleMenuView.ITEM_WALKIE: return walkieRingItems();
                case CircleMenuView.ITEM_HOME: return homeRingItems();
                case CircleMenuView.ITEM_BROWSER: return browserRingItems();
                case CircleMenuView.ITEM_CHAT: return chatRingItems();
                case CircleMenuView.ITEM_ACTIVITY: return activityRingItems();
                case CircleMenuView.ITEM_CONNECTIONS: return connectionsRingItems();
                case CircleMenuView.ITEM_WALLET: return walletRingItems();
                default: return new ArrayList<>();
            }
        }

        @Override public String emptyHint(int parentItem) {
            switch (parentItem) {
                case CircleMenuView.ITEM_HOME:
                    return "No favorites yet — hold an app in the Apps sheet to star it";
                case CircleMenuView.ITEM_BROWSER:
                    return "No bookmarks yet";
                case CircleMenuView.ITEM_CHAT:
                    return "No conversations yet — exchange contact cards or start a chat";
                case CircleMenuView.ITEM_WALLET:
                    return "No cards saved yet — add one in the wallet";
                default:
                    return "";
            }
        }

        @Override public void onLeafAction(RingItems.Action action) {
            pager.setMenuOpen(false);
            switch (action.kind) {
                case RingItems.Action.LAUNCH_APP:
                    InstalledApps.launch(MainActivity.this, action.text);
                    break;
                case RingItems.Action.OPEN_INTENT:
                    startActivity(action.intent);
                    break;
                case RingItems.Action.TUNE_CHANNEL:
                    walkiePage.tuneToChannel(action.number);
                    pager.goToPage(PAGE_WALKIE, true);
                    break;
                case RingItems.Action.OPEN_URL:
                    browserPage.openUrl(action.text);
                    pager.goToPage(PAGE_BROWSER, true);
                    break;
                case RingItems.Action.OPEN_CONVO:
                    pager.goToPage(PAGE_CHAT, true);
                    chatPage.openConvoById(action.text);
                    break;
                case RingItems.Action.HOME_ROLE:
                    HomeRole.request(MainActivity.this, REQ_HOME_ROLE);
                    break;
                case RingItems.Action.NOTIF_SETTINGS:
                    try {
                        startActivity(new Intent(
                                Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS));
                    } catch (Exception e) {
                        Toast.makeText(MainActivity.this,
                                "Open Settings → Notifications → Device and app notifications",
                                Toast.LENGTH_LONG).show();
                    }
                    break;
                default:
                    break;
            }
        }
    };

    /** Apps: every launchable app from the shared cache, real
     *  icons resolved lazily per visible page. A background
     *  refresh re-renders the ring if the list changed. */
    private List<RingItems.Item> appRingItems() {
        List<RingItems.Item> out = new ArrayList<>();
        for (InstalledApps.Entry e : InstalledApps.all(this)) {
            final String pkg = e.pkg;
            out.add(RingItems.Item.appLazy(e.label,
                    () -> InstalledApps.iconFor(MainActivity.this, pkg),
                    RingItems.Action.launchApp(pkg)));
        }
        AppListStore.refreshAsync(this, () -> {
            if (circleMenu != null) circleMenu.refreshSubRing();
        });
        return out;
    }

    /** Settings: the hub's navigation rows, in hub order, with the
     *  hub's glyphs. Switch rows (clock, quiet-all, ad filter, SMS
     *  remote control, JavaScript) stay in Settings — the ring
     *  navigates, it never flips a setting — and "Turn off deep
     *  control" stays with its confirm dialog. The profile header
     *  card is folded into "Profile & backup" (same screen). */
    private List<RingItems.Item> settingsRingItems() {
        List<RingItems.Item> out = new ArrayList<>();
        out.add(settingsLeaf("Background", PageGlyphs.IMAGE, BackgroundActivity.class));
        out.add(RingItems.Item.glyph("Choose favorites", PageGlyphs.APPS,
                RingItems.Action.openIntent(AppPickerActivity.intent(
                        this, Prefs.KEY_FAVORITES, "Favorites"))));
        out.add(settingsLeaf("Connections", PageGlyphs.CONNECTIONS, ConnectionsActivity.class));
        out.add(RingItems.Item.glyph("Notification access", PageGlyphs.BELL,
                RingItems.Action.notifSettings()));
        out.add(RingItems.Item.glyph("Muted apps", PageGlyphs.BELL,
                RingItems.Action.openIntent(AppPickerActivity.intent(
                        this, Prefs.KEY_MUTED_APPS, "Muted apps"))));
        out.add(settingsLeaf("Held notifications", PageGlyphs.BELL, HeldActivity.class));
        out.add(settingsLeaf("Filter settings", PageGlyphs.SHIELD, SettingsFilterActivity.class));
        out.add(settingsLeaf("Walkie-talkie", PageGlyphs.WALKIE, WalkieActivity.class));
        out.add(settingsLeaf("Chat", PageGlyphs.CHAT, ChatActivity.class));
        out.add(settingsLeaf("SMS setup", PageGlyphs.CHAT, SettingsSmsActivity.class));
        out.add(settingsLeaf("Deep view", PageGlyphs.ACTIVITY, DeepViewActivity.class));
        out.add(settingsLeaf("Activity / Control Room", PageGlyphs.ACTIVITY, ControlRoomActivity.class));
        out.add(settingsLeaf("Governed Deep controls", PageGlyphs.SETTINGS, DeepControlActivity.class));
        out.add(settingsLeaf("Device admin", PageGlyphs.SHIELD, SettingsAdminActivity.class));
        out.add(RingItems.Item.glyph("Set home app", PageGlyphs.HOME,
                RingItems.Action.homeRole()));
        out.add(settingsLeaf("Profile & backup", PageGlyphs.LOCK, SettingsProfileActivity.class));
        out.add(settingsLeaf("Bookmarks", PageGlyphs.BROWSER, BookmarksActivity.class));
        out.add(settingsLeaf("About Control", PageGlyphs.INFO, SettingsAboutActivity.class));
        return out;
    }

    private RingItems.Item settingsLeaf(String label, int glyph, Class<?> activity) {
        return RingItems.Item.glyph(label, glyph,
                RingItems.Action.openIntent(new Intent(this, activity)));
    }

    /** Walkie: all 50 channels as numbered chips. Private slots
     *  show their number in accent; the label is the slot's name,
     *  or Open/Private when unnamed. */
    private List<RingItems.Item> walkieRingItems() {
        List<RingItems.Item> out = new ArrayList<>();
        for (int n = 1; n <= Profile.BANK_SIZE; n++) {
            JSONObject slot = Profile.slot(this, n);
            String label = "";
            boolean priv = false;
            if (slot != null) {
                label = slot.optString("label", "");
                priv = Profile.isPrivate(slot);
            }
            if (label.isEmpty()) label = priv ? "Private" : "Open";
            out.add(RingItems.Item.number(label, String.valueOf(n),
                    priv ? Ui.ACCENT : 0, RingItems.Action.tuneChannel(n)));
        }
        return out;
    }

    /** Home: the starred favorites, exactly as the Home page
     *  shows them (same store, same skip-if-uninstalled rule). */
    private List<RingItems.Item> homeRingItems() {
        Set<String> favs = Prefs.getSet(this, Prefs.KEY_FAVORITES);
        Map<String, String> labels = new HashMap<>();
        for (InstalledApps.Entry e : InstalledApps.all(this)) {
            labels.put(e.pkg, e.label);
        }
        List<RingItems.Item> out = new ArrayList<>();
        for (String pkg : favs) {
            String label = labels.get(pkg);
            if (label == null) {
                InstalledApps.Entry e = InstalledApps.one(this, pkg);
                if (e == null) continue;
                label = e.label;
            }
            final String p = pkg;
            out.add(RingItems.Item.appLazy(label,
                    () -> InstalledApps.iconFor(MainActivity.this, p),
                    RingItems.Action.launchApp(p)));
        }
        return out;
    }

    /** Browser: saved bookmarks; choosing one opens it on the
     *  Browser page. */
    private List<RingItems.Item> browserRingItems() {
        List<RingItems.Item> out = new ArrayList<>();
        JSONArray marks = Profile.bookmarks(this);
        for (int i = 0; i < marks.length(); i++) {
            JSONObject b = marks.optJSONObject(i);
            if (b == null) continue;
            String url = b.optString("url", "");
            if (url.isEmpty()) continue;
            String title = b.optString("title", "Page");
            if (title.isEmpty()) title = "Page";
            out.add(RingItems.Item.glyph(title, PageGlyphs.BROWSER,
                    RingItems.Action.openUrl(url)));
        }
        return out;
    }

    /** Chat: the conversation list, mirroring the Chat page —
     *  locked rows show the lock glyph and, when tapped, the same
     *  note a list tap shows. */
    private List<RingItems.Item> chatRingItems() {
        List<RingItems.Item> out = new ArrayList<>();
        for (ChatPage.Convo cv : chatPage.ringConvos()) {
            int glyph = "locked".equals(cv.kind) ? PageGlyphs.LOCK : PageGlyphs.CHAT;
            out.add(RingItems.Item.glyph(cv.title, glyph,
                    RingItems.Action.openConvo(cv.channelId)));
        }
        return out;
    }

    /** Activity: the Control Room's four tabs, plus the two Deep
     *  destinations the Control Room itself links to. */
    private List<RingItems.Item> activityRingItems() {
        List<RingItems.Item> out = new ArrayList<>();
        out.add(RingItems.Item.glyph("Notifications", PageGlyphs.BELL,
                RingItems.Action.openIntent(roomIntent("notifications"))));
        out.add(RingItems.Item.glyph("Apps", PageGlyphs.APPS,
                RingItems.Action.openIntent(roomIntent("apps"))));
        out.add(RingItems.Item.glyph("Network", PageGlyphs.CONNECTIONS,
                RingItems.Action.openIntent(roomIntent("network"))));
        out.add(RingItems.Item.glyph("This app", PageGlyphs.INFO,
                RingItems.Action.openIntent(roomIntent("own"))));
        out.add(RingItems.Item.glyph("Deep view", PageGlyphs.ACTIVITY,
                RingItems.Action.openIntent(new Intent(this, DeepViewActivity.class))));
        out.add(RingItems.Item.glyph("Governed controls", PageGlyphs.SETTINGS,
                RingItems.Action.openIntent(new Intent(this, DeepControlActivity.class))));
        return out;
    }

    private Intent roomIntent(String section) {
        Intent i = new Intent(this, ControlRoomActivity.class);
        i.putExtra(ControlRoomActivity.EXTRA_SECTION, section);
        return i;
    }

    /** Connections: the seven radio cards. Navigation only — each
     *  leaf opens ConnectionsActivity scrolled to its card, and
     *  flips nothing. */
    private List<RingItems.Item> connectionsRingItems() {
        String[] cards = {"Wi-Fi", "Bluetooth", "Cellular", "Airplane mode",
                "NFC", "Location", "VPN & filter"};
        List<RingItems.Item> out = new ArrayList<>();
        for (String card : cards) {
            Intent i = new Intent(this, ConnectionsActivity.class);
            i.putExtra(ConnectionsActivity.EXTRA_SECTION, card);
            out.add(RingItems.Item.glyph(card, PageGlyphs.CONNECTIONS,
                    RingItems.Action.openIntent(i)));
        }
        return out;
    }

    /** Wallet: the stored cards by nickname (or network + last 4).
     *  Tapping one opens the wallet — nothing is revealed from the
     *  ring, and the number is never unsealed here. */
    private List<RingItems.Item> walletRingItems() {
        List<RingItems.Item> out = new ArrayList<>();
        try {
            for (WalletStore.Card card : WalletStore.list(this)) {
                String label = card.nickname == null ? "" : card.nickname.trim();
                if (label.isEmpty()) {
                    String net = card.network == null ? "Card" : card.network;
                    String l4 = card.last4 == null ? "" : card.last4;
                    label = l4.isEmpty() ? net : net + " ••" + l4;
                }
                out.add(RingItems.Item.glyph(label, PageGlyphs.WALLET,
                        RingItems.Action.openIntent(
                                new Intent(this, WalletActivity.class))));
            }
        } catch (Exception e) {
            // A wallet that can't be read shows no cards here; the
            // wallet screen itself reports the problem.
        }
        return out;
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleIntent(intent);
    }

    private void handleIntent(Intent intent) {
        if (intent == null) return;
        if (intent.hasExtra(EXTRA_URL)) {
            String url = intent.getStringExtra(EXTRA_URL);
            if (url != null && !url.isEmpty()) browserPage.openUrl(url);
        }
        if (intent.hasExtra(EXTRA_PAGE)) {
            pager.goToPage(intent.getIntExtra(EXTRA_PAGE, PAGE_HOME), false);
        }
        if (intent.hasExtra(EXTRA_CHAT_CHANNEL_ID)) {
            // A conversation forwarded in (the old RoomActivity path):
            // land on the Chat page with that thread open.
            pager.goToPage(PAGE_CHAT, false);
            chatPage.openConversation(
                    intent.getStringExtra(EXTRA_CHAT_CHANNEL_ID),
                    intent.getStringExtra(EXTRA_CHAT_CHANNEL_KEY),
                    intent.getStringExtra(EXTRA_CHAT_NAME),
                    intent.getStringExtra(EXTRA_CHAT_PEER));
        }
        if (intent.getBooleanExtra(EXTRA_APPS, false)) {
            appsSheet.show();
        }
        if (intent.hasExtra(EXTRA_TUNE_CHANNEL)) {
            // The overlay ring's tune route: tune, then land on
            // the Walkie page (same two calls the in-launcher
            // ring's TUNE_CHANNEL leaf makes).
            walkiePage.tuneToChannel(intent.getIntExtra(EXTRA_TUNE_CHANNEL, 1));
            pager.goToPage(PAGE_WALKIE, false);
        }
        if (intent.getBooleanExtra(EXTRA_REQUEST_HOME_ROLE, false)) {
            HomeRole.request(this, REQ_HOME_ROLE);
        }
    }

    private void selectPage(int index) {
        if (index == currentPage && pagesLive) {
            return;
        }
        PagerLayout.PagerPage old = pageAt(currentPage);
        PagerLayout.PagerPage next = pageAt(index);
        currentPage = index;
        if (pagesLive) {
            if (old != null && old != next) old.onPageHidden();
            if (next != null) next.onPageShown();
        }
    }

    private PagerLayout.PagerPage pageAt(int index) {
        switch (index) {
            case PAGE_BROWSER: return browserPage;
            case PAGE_HOME: return homePage;
            case PAGE_WALKIE: return walkiePage;
            case PAGE_CHAT: return chatPage;
            default: return null;
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (Prefs.get(this).getBoolean(Prefs.KEY_APP_LOCKED, false)) {
            startActivity(new Intent(this, LockActivity.class));
        }
        pagesLive = true;
        PagerLayout.PagerPage p = pageAt(currentPage);
        if (p != null) p.onPageShown();
        homePage.refresh();
        if (backgroundView != null) backgroundView.refresh();
        MeshInternet.reconcile(this, false);
    }

    @Override
    protected void onPause() {
        PagerLayout.PagerPage p = pageAt(currentPage);
        if (p != null) p.onPageHidden();
        pagesLive = false;
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        homePage.onPageDestroy();
        browserPage.onPageDestroy();
        walkiePage.onPageDestroy();
        chatPage.onPageDestroy();
        super.onDestroy();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_HOME_ROLE) {
            homePage.refresh();
        } else if (requestCode == REQ_WALKIE_LISTEN && resultCode == RESULT_OK
                && data != null) {
            // The Walkie page's "Listen for invite" — the listen ran
            // in AudioKeyListenActivity under this host, so the result
            // lands here.
            pager.goToPage(PAGE_WALKIE, false);
            walkiePage.handleHeardText(data.getStringExtra(AudioKeyListenActivity.RESULT_TEXT));
        } else if (requestCode == REQ_CHAT_LISTEN && resultCode == RESULT_OK
                && data != null) {
            // The Chat page's "Listen" (contact cards + chat keys) —
            // the listen ran in AudioKeyListenActivity under this host.
            pager.goToPage(PAGE_CHAT, false);
            chatPage.handleHeardText(data.getStringExtra(AudioKeyListenActivity.RESULT_TEXT));
        }
    }

    @Override
    public void onBackPressed() {
        // The Apps sheet eats Back first, then an open circle menu:
        // Back goes up one ring level, then dismisses the menu.
        if (appsSheet != null && appsSheet.isOpen()) {
            appsSheet.hide();
            return;
        }
        if (circleMenu != null && circleMenu.isOpen()) {
            if (circleMenu.goBack()) return;
            circleMenu.close();
            pager.setMenuOpen(false);
            return;
        }
        // On the Chat page, Back inside a thread returns to the
        // conversation list first.
        if (currentPage == PAGE_CHAT && chatPage.onBackPressed()) {
            return;
        }
        // Back from Browser/Walkie/Chat returns to Home; on Home there
        // is nowhere to go (a home screen has no back).
        if (currentPage != PAGE_HOME) {
            pager.goToPage(PAGE_HOME, true);
        }
    }
}
