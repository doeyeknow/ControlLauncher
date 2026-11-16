package app.control.launcher;

import android.content.Context;
import android.content.Intent;
import android.provider.Settings;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The sub-ring contents + routing for the OVERLAY ring (v1.14).
 * This is the same ring MainActivity shows — same nine
 * destinations, same sub-ring contents, same leaf actions —
 * sourced from the same stores (Profile, InstalledApps, the
 * wallet), but routed through intents because the overlay has
 * no activity or pager to drive: choosing a leaf opens
 * MainActivity on the right page (or the destination activity
 * directly), exactly as the forwarders do.
 *
 * Two deliberate differences from MainActivity's source, both
 * forced by having no live pages:
 *   - Chat leaves come from the profile-local conversation list
 *     only (DMs from contacts + saved chat channels — the same
 *     data ChatPage builds its list from before its server
 *     refresh). Server-adopted or locked rows appear only in
 *     the Chat page itself, one tap away.
 *   - Tuning a walkie channel and requesting the home role go
 *     through MainActivity intent extras (EXTRA_TUNE_CHANNEL /
 *     EXTRA_REQUEST_HOME_ROLE), since both need the live
 *     activity.
 */
public final class OverlayRingSource implements RingItems.Source {

    private final Context app;

    public OverlayRingSource(Context context) {
        this.app = context.getApplicationContext();
    }

    @Override
    public List<RingItems.Item> itemsFor(int parentItem) {
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

    @Override
    public String emptyHint(int parentItem) {
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

    @Override
    public void onLeafAction(RingItems.Action action) {
        performAction(app, action);
    }

    // ---- contents (mirrors MainActivity's ringSource) ----

    private List<RingItems.Item> appRingItems() {
        List<RingItems.Item> out = new ArrayList<>();
        for (InstalledApps.Entry e : InstalledApps.all(app)) {
            final String pkg = e.pkg;
            out.add(RingItems.Item.appLazy(e.label,
                    () -> InstalledApps.iconFor(app, pkg),
                    RingItems.Action.launchApp(pkg)));
        }
        AppListStore.refreshAsync(app, null);
        return out;
    }

    private List<RingItems.Item> settingsRingItems() {
        List<RingItems.Item> out = new ArrayList<>();
        out.add(settingsLeaf("Background", PageGlyphs.IMAGE, BackgroundActivity.class));
        out.add(RingItems.Item.glyph("Choose favorites", PageGlyphs.APPS,
                RingItems.Action.openIntent(pickerIntent(
                        Prefs.KEY_FAVORITES, "Favorites"))));
        out.add(settingsLeaf("Connections", PageGlyphs.CONNECTIONS, ConnectionsActivity.class));
        out.add(RingItems.Item.glyph("Notification access", PageGlyphs.BELL,
                RingItems.Action.notifSettings()));
        out.add(RingItems.Item.glyph("Muted apps", PageGlyphs.BELL,
                RingItems.Action.openIntent(pickerIntent(
                        Prefs.KEY_MUTED_APPS, "Muted apps"))));
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
                RingItems.Action.openIntent(new Intent(app, activity)));
    }

    /** The AppPickerActivity.intent factory takes an Activity;
     *  the overlay has only a Context, so the same intent is
     *  built directly with the same extras. */
    private Intent pickerIntent(String prefKey, String title) {
        Intent i = new Intent(app, AppPickerActivity.class);
        i.putExtra(AppPickerActivity.EXTRA_KEY, prefKey);
        i.putExtra(AppPickerActivity.EXTRA_TITLE, title);
        return i;
    }

    private List<RingItems.Item> walkieRingItems() {
        List<RingItems.Item> out = new ArrayList<>();
        for (int n = 1; n <= Profile.BANK_SIZE; n++) {
            JSONObject slot = Profile.slot(app, n);
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

    private List<RingItems.Item> homeRingItems() {
        Set<String> favs = Prefs.getSet(app, Prefs.KEY_FAVORITES);
        Map<String, String> labels = new HashMap<>();
        for (InstalledApps.Entry e : InstalledApps.all(app)) {
            labels.put(e.pkg, e.label);
        }
        List<RingItems.Item> out = new ArrayList<>();
        for (String pkg : favs) {
            String label = labels.get(pkg);
            if (label == null) {
                InstalledApps.Entry e = InstalledApps.one(app, pkg);
                if (e == null) continue;
                label = e.label;
            }
            final String p = pkg;
            out.add(RingItems.Item.appLazy(label,
                    () -> InstalledApps.iconFor(app, p),
                    RingItems.Action.launchApp(p)));
        }
        return out;
    }

    private List<RingItems.Item> browserRingItems() {
        List<RingItems.Item> out = new ArrayList<>();
        JSONArray marks = Profile.bookmarks(app);
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

    /** Profile-local conversations: DMs from contacts carrying
     *  both keys, plus saved chat channels — the same local data
     *  ChatPage builds its list from. */
    private List<RingItems.Item> chatRingItems() {
        List<RingItems.Item> out = new ArrayList<>();
        for (JSONObject contact : Profile.contactList(app)) {
            String pub = contact.optString("relayKey", "");
            String dm = contact.optString("dmKey", "");
            if (pub.isEmpty() || dm.isEmpty()) continue;
            String id = RelayIds.dmChannelId(dm);
            if (id == null) continue;
            out.add(RingItems.Item.glyph(contact.optString("name", "Unnamed"),
                    PageGlyphs.CHAT, RingItems.Action.openConvo(id)));
        }
        JSONArray chats = Profile.chatChannels(app);
        for (int i = 0; i < chats.length(); i++) {
            JSONObject chat = chats.optJSONObject(i);
            if (chat == null) continue;
            String key = chat.optString("key", "");
            String id = RelayIds.chatChannelId(key);
            if (id == null) continue;
            out.add(RingItems.Item.glyph(chat.optString("name", "Chat"),
                    PageGlyphs.CHAT, RingItems.Action.openConvo(id)));
        }
        return out;
    }

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
                RingItems.Action.openIntent(new Intent(app, DeepViewActivity.class))));
        out.add(RingItems.Item.glyph("Governed controls", PageGlyphs.SETTINGS,
                RingItems.Action.openIntent(new Intent(app, DeepControlActivity.class))));
        return out;
    }

    private Intent roomIntent(String section) {
        Intent i = new Intent(app, ControlRoomActivity.class);
        i.putExtra(ControlRoomActivity.EXTRA_SECTION, section);
        return i;
    }

    /** The same seven Connections leaves the in-launcher ring
     *  shows; the Connections screen itself carries the rest. */
    private List<RingItems.Item> connectionsRingItems() {
        String[] cards = {"Wi-Fi", "Bluetooth", "Cellular", "Airplane mode",
                "NFC", "Location", "VPN & filter"};
        List<RingItems.Item> out = new ArrayList<>();
        for (String card : cards) {
            Intent i = new Intent(app, ConnectionsActivity.class);
            i.putExtra(ConnectionsActivity.EXTRA_SECTION, card);
            out.add(RingItems.Item.glyph(card, PageGlyphs.CONNECTIONS,
                    RingItems.Action.openIntent(i)));
        }
        return out;
    }

    private List<RingItems.Item> walletRingItems() {
        List<RingItems.Item> out = new ArrayList<>();
        try {
            for (WalletStore.Card card : WalletStore.list(app)) {
                String label = card.nickname == null ? "" : card.nickname.trim();
                if (label.isEmpty()) {
                    String net = card.network == null ? "Card" : card.network;
                    String l4 = card.last4 == null ? "" : card.last4;
                    label = l4.isEmpty() ? net : net + " ••" + l4;
                }
                out.add(RingItems.Item.glyph(label, PageGlyphs.WALLET,
                        RingItems.Action.openIntent(
                                new Intent(app, WalletActivity.class))));
            }
        } catch (Exception e) {
            // A wallet that can't be read shows no cards here; the
            // wallet screen itself reports the problem.
        }
        return out;
    }

    // ---- routing ----

    /** A main-ring choice from the overlay: open MainActivity on
     *  the right page (or raise the sheet), or the destination
     *  activity directly. */
    public static void openMainDestination(Context context, int item) {
        Context app = context.getApplicationContext();
        switch (item) {
            case CircleMenuView.ITEM_HOME:
                openPage(app, MainActivity.PAGE_HOME);
                break;
            case CircleMenuView.ITEM_BROWSER:
                openPage(app, MainActivity.PAGE_BROWSER);
                break;
            case CircleMenuView.ITEM_WALKIE:
                openPage(app, MainActivity.PAGE_WALKIE);
                break;
            case CircleMenuView.ITEM_CHAT:
                openPage(app, MainActivity.PAGE_CHAT);
                break;
            case CircleMenuView.ITEM_APPS: {
                Intent i = new Intent(app, MainActivity.class);
                i.putExtra(MainActivity.EXTRA_APPS, true);
                startNewTask(app, i);
                break;
            }
            case CircleMenuView.ITEM_ACTIVITY:
                startNewTask(app, new Intent(app, ControlRoomActivity.class));
                break;
            case CircleMenuView.ITEM_CONNECTIONS:
                startNewTask(app, new Intent(app, ConnectionsActivity.class));
                break;
            case CircleMenuView.ITEM_SETTINGS:
                startNewTask(app, new Intent(app, SettingsActivity.class));
                break;
            case CircleMenuView.ITEM_WALLET:
                startNewTask(app, new Intent(app, WalletActivity.class));
                break;
            default:
                break;
        }
    }

    private static void openPage(Context app, int page) {
        Intent i = new Intent(app, MainActivity.class);
        i.putExtra(MainActivity.EXTRA_PAGE, page);
        startNewTask(app, i);
    }

    /** A sub-ring leaf choice from the overlay. */
    public static void performAction(Context context, RingItems.Action action) {
        Context app = context.getApplicationContext();
        switch (action.kind) {
            case RingItems.Action.LAUNCH_APP:
                InstalledApps.launch(app, action.text);
                break;
            case RingItems.Action.OPEN_INTENT:
                startNewTask(app, action.intent);
                break;
            case RingItems.Action.TUNE_CHANNEL: {
                Intent i = new Intent(app, MainActivity.class);
                i.putExtra(MainActivity.EXTRA_TUNE_CHANNEL, action.number);
                startNewTask(app, i);
                break;
            }
            case RingItems.Action.OPEN_URL: {
                Intent i = new Intent(app, MainActivity.class);
                i.putExtra(MainActivity.EXTRA_URL, action.text);
                i.putExtra(MainActivity.EXTRA_PAGE, MainActivity.PAGE_BROWSER);
                startNewTask(app, i);
                break;
            }
            case RingItems.Action.OPEN_CONVO:
                openConversation(app, action.text);
                break;
            case RingItems.Action.HOME_ROLE: {
                Intent i = new Intent(app, MainActivity.class);
                i.putExtra(MainActivity.EXTRA_REQUEST_HOME_ROLE, true);
                startNewTask(app, i);
                break;
            }
            case RingItems.Action.NOTIF_SETTINGS:
                try {
                    Intent i = new Intent(
                            Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS);
                    startNewTask(app, i);
                } catch (Exception e) {
                    Toast.makeText(app,
                            "Open Settings → Notifications → Device and app notifications",
                            Toast.LENGTH_LONG).show();
                }
                break;
            default:
                break;
        }
    }

    /** Resolve a conversation from the profile and open its
     *  thread in MainActivity; fall back to the Chat page when
     *  the conversation is no longer in the profile. */
    private static void openConversation(Context app, String channelId) {
        for (JSONObject contact : Profile.contactList(app)) {
            String pub = contact.optString("relayKey", "");
            String dm = contact.optString("dmKey", "");
            if (pub.isEmpty() || dm.isEmpty()) continue;
            if (channelId.equals(RelayIds.dmChannelId(dm))) {
                Intent i = new Intent(app, MainActivity.class);
                i.putExtra(MainActivity.EXTRA_CHAT_CHANNEL_ID, channelId);
                i.putExtra(MainActivity.EXTRA_CHAT_CHANNEL_KEY, dm);
                i.putExtra(MainActivity.EXTRA_CHAT_NAME,
                        contact.optString("name", "Unnamed"));
                i.putExtra(MainActivity.EXTRA_CHAT_PEER, pub);
                startNewTask(app, i);
                return;
            }
        }
        JSONArray chats = Profile.chatChannels(app);
        for (int i = 0; i < chats.length(); i++) {
            JSONObject chat = chats.optJSONObject(i);
            if (chat == null) continue;
            String key = chat.optString("key", "");
            if (channelId.equals(RelayIds.chatChannelId(key))) {
                Intent intent = new Intent(app, MainActivity.class);
                intent.putExtra(MainActivity.EXTRA_CHAT_CHANNEL_ID, channelId);
                intent.putExtra(MainActivity.EXTRA_CHAT_CHANNEL_KEY, key);
                intent.putExtra(MainActivity.EXTRA_CHAT_NAME,
                        chat.optString("name", "Chat"));
                intent.putExtra(MainActivity.EXTRA_CHAT_PEER, "");
                startNewTask(app, intent);
                return;
            }
        }
        openPage(app, MainActivity.PAGE_CHAT);
    }

    private static void startNewTask(Context app, Intent intent) {
        if (intent == null) return;
        try {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            app.startActivity(intent);
        } catch (Exception ignored) {
            // A destination that cannot start (removed, blocked)
            // fails silently here; the ring is already dismissed.
        }
    }
}
