package app.control.launcher;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * The Chat pager page (v1.5 — chat used to be the standalone
 * ChatActivity + RoomActivity pair; both are thin forwarders now and
 * this page is the ONE chat surface). Same relay chat as v1.3: no
 * account and no sign-in — the header states the identity the profile
 * key gives you, and the list is built from what this phone can
 * actually open:
 *
 *  - DMs with contacts who carry a relay key + DM key (contact-card
 *    exchange, see ContactCard);
 *  - group chat channels whose keys live in the profile;
 *  - relay channels this key is a member of but whose key isn't on
 *    this phone — listed honestly as locked, never hidden.
 *
 * Messages are sealed with each conversation's channel key
 * (AES-256-GCM); the relay stores ciphertext only. The explanation
 * that used to sit under the header's identity line now lives in the
 * popup behind the "?" button at the header's top right — the words
 * are unchanged.
 *
 * The page is built once and kept: the conversation list and the
 * thread view (ChatThreadView) swap visibility inside it. Polling
 * follows visibility — the list polls every 5s and an open thread
 * every ~3s, ONLY while this page is the pager's shown page; nothing
 * polls the relay for chat while another page is up, and unread
 * badges/thread state survive page switches.
 */
public class ChatPage extends FrameLayout implements PagerLayout.PagerPage {

    static final class Convo {
        String channelId = "";
        String keyB64 = "";
        String title = "";
        String kind = "";        // "dm" | "chat" | "locked"
        String peerPub = "";     // dm: the other party's relay key
        String note = "";
        boolean hasNew;
    }

    private final Activity act;
    private LinearLayout listRoot;
    private ChatThreadView threadView;
    private TextView nameView;
    private TextView identityView;
    private TextView statusView;
    private ListView convoList;
    private final List<Convo> convos = new ArrayList<>();
    private ConvoAdapter adapter;
    private boolean pageVisible;
    private final Handler poll = new Handler(Looper.getMainLooper());
    private boolean polling;
    private final Runnable pollTick = new Runnable() {
        @Override public void run() {
            if (!polling) return;
            refreshServer();
            poll.postDelayed(this, 5000);
        }
    };

    public ChatPage(Activity host) {
        super(host);
        this.act = host;
        setBackgroundColor(0x00000000); // transparent: the shared background shows through

        listRoot = Ui.column(act);
        int pad = Ui.dp(act, 20);
        listRoot.setPadding(pad, Ui.dp(act, 20), pad, Ui.dp(act, 16));

        // Header: title + display name + identity line on the left,
        // the "?" button at the top right. The identity line is the
        // ONLY explanatory line left in the header — everything that
        // used to sit below it moved, verbatim, into the "?" popup.
        LinearLayout header = Ui.row(act);
        LinearLayout headerText = Ui.column(act);
        headerText.addView(Ui.screenTitle(act, "Chat"));
        nameView = Ui.text(act, Profile.displayName(act), 17, Ui.TEXT);
        headerText.addView(nameView);
        identityView = Ui.text(act,
                "You are your profile key · " + Profile.relayShortFingerprint(act),
                13, Ui.ACCENT);
        identityView.setTypeface(android.graphics.Typeface.MONOSPACE);
        headerText.addView(identityView);
        header.addView(headerText, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        Button help = Ui.smallButton(act, "?");
        help.setOnClickListener(v -> showHelpPopup());
        LinearLayout.LayoutParams hp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        hp.gravity = android.view.Gravity.TOP;
        header.addView(help, hp);
        listRoot.addView(header);

        LinearLayout top = new LinearLayout(act);
        top.setOrientation(LinearLayout.HORIZONTAL);
        Button newChat = Ui.button(act, "New chat", true);
        newChat.setOnClickListener(v -> newChatDialog());
        top.addView(newChat, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        Button contacts = Ui.button(act, "Contacts", false);
        contacts.setOnClickListener(v ->
                act.startActivity(new Intent(act, ContactsActivity.class)));
        LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        cp.setMargins(Ui.dp(act, 8), 0, 0, 0);
        top.addView(contacts, cp);
        Button listen = Ui.button(act, "Listen", false);
        listen.setOnClickListener(v -> act.startActivityForResult(
                AudioKeyListenActivity.anyIntent(act), MainActivity.REQ_CHAT_LISTEN));
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        sp.setMargins(Ui.dp(act, 8), 0, 0, 0);
        top.addView(listen, sp);
        listRoot.addView(top, Ui.wrap(0, 12, 0, 0, act));

        statusView = Ui.text(act, "", 13, Ui.DIM);
        statusView.setTypeface(android.graphics.Typeface.MONOSPACE);
        listRoot.addView(statusView, Ui.wrap(0, 8, 0, 0, act));

        convoList = new ListView(act);
        convoList.setDivider(null);
        adapter = new ConvoAdapter();
        convoList.setAdapter(adapter);
        convoList.setOnItemClickListener((parent, view, pos, id) -> openConvo(convos.get(pos)));
        listRoot.addView(convoList, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        addView(listRoot, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        threadView = new ChatThreadView(act);
        threadView.setListener(this::closeThread);
        threadView.setVisibility(View.GONE);
        addView(threadView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        RelayClient.ensureRegistered(act);
    }

    /** The "?" popup: the explanatory text that sat below the
     *  identity line in the standalone chat screen, verbatim. */
    private void showHelpPopup() {
        new AlertDialog.Builder(act)
                .setMessage("No account, no sign-in, no password — this key is the "
                        + "identity. Messages are sealed with each conversation's "
                        + "channel key; the relay stores ciphertext only and sees "
                        + "who talks and when, never what's said.")
                .setPositiveButton("Close", null)
                .show();
    }

    // ---- pager lifecycle ----

    @Override
    public void onPageShown() {
        pageVisible = true;
        nameView.setText(Profile.displayName(act));
        identityView.setText("You are your profile key · "
                + Profile.relayShortFingerprint(act));
        rebuildLocal();
        if (threadView.isOpen()) {
            threadView.setPageActive(true);
        } else {
            refreshServer();
            startListPoll();
        }
    }

    @Override
    public void onPageHidden() {
        pageVisible = false;
        stopListPoll();
        threadView.setPageActive(false);
    }

    @Override
    public void onPageDestroy() {
        pageVisible = false;
        stopListPoll();
        threadView.destroy();
    }

    /** Inside a thread, Back returns to the conversation list. */
    public boolean onBackPressed() {
        if (threadView.isOpen()) {
            closeThread();
            return true;
        }
        return false;
    }

    public boolean isThreadOpen() {
        return threadView.isOpen();
    }

    /** The message list scrolls vertically and owns its horizontal
     *  gestures; with a thread open, its list + composer own theirs
     *  (the composer yields the pager while typing, like the
     *  walkie's). Everything else on the page pages normally. */
    @Override
    public boolean ownsHorizontalGesture(float x, float y) {
        if (threadView.isOpen()) {
            return threadView.ownsGestureWithin(this, x, y);
        }
        return inside(convoList, x, y);
    }

    private boolean inside(View v, float x, float y) {
        if (v == null) return false;
        int[] mine = new int[2];
        int[] its = new int[2];
        getLocationOnScreen(mine);
        v.getLocationOnScreen(its);
        float lx = its[0] - mine[0];
        float ly = its[1] - mine[1];
        return x >= lx && x < lx + v.getWidth() && y >= ly && y < ly + v.getHeight();
    }

    private void startListPoll() {
        if (polling || !pageVisible || threadView.isOpen()) return;
        polling = true;
        poll.removeCallbacks(pollTick);
        poll.postDelayed(pollTick, 5000);
    }

    private void stopListPoll() {
        polling = false;
        poll.removeCallbacks(pollTick);
    }

    // ---- conversation sources ----

    /**
     * The conversation list for the circle menu's Chat sub-ring
     * (v1.9): the same list this page shows. Built from local data
     * when the page has never been shown; left alone otherwise so
     * server-merged rows (including locked ones) survive. The
     * returned list is a copy; rows are the live Convo objects.
     */
    public List<Convo> ringConvos() {
        if (convos.isEmpty()) rebuildLocal();
        return new ArrayList<>(convos);
    }

    /** Opens a conversation by channel id through the same path a
     *  list tap takes (locked rows show their note, nothing else).
     *  Returns false when the id is not in the list. */
    public boolean openConvoById(String channelId) {
        if (channelId == null) return false;
        for (Convo cv : convos) {
            if (channelId.equals(cv.channelId)) {
                openConvo(cv);
                return true;
            }
        }
        return false;
    }

    private void rebuildLocal() {
        convos.clear();
        // DMs from contacts that carry both a relay key and a DM key.
        for (JSONObject contact : Profile.contactList(act)) {
            String pub = contact.optString("relayKey", "");
            String dm = contact.optString("dmKey", "");
            if (pub.isEmpty() || dm.isEmpty()) continue;
            String id = RelayIds.dmChannelId(dm);
            if (id == null) continue;
            Convo cv = new Convo();
            cv.channelId = id;
            cv.keyB64 = dm;
            cv.title = contact.optString("name", "Unnamed");
            cv.kind = "dm";
            cv.peerPub = pub;
            cv.note = "Direct message";
            convos.add(cv);
        }
        // DM channels keyed by MY card key are adopted in refreshServer.
        // Group chats whose keys live in the profile.
        JSONArray chats = Profile.chatChannels(act);
        for (int i = 0; i < chats.length(); i++) {
            JSONObject chat = chats.optJSONObject(i);
            if (chat == null) continue;
            String key = chat.optString("key", "");
            String id = RelayIds.chatChannelId(key);
            if (id == null) continue;
            Convo cv = new Convo();
            cv.channelId = id;
            cv.keyB64 = key;
            cv.title = chat.optString("name", "Chat");
            cv.kind = "chat";
            cv.note = "Chat channel";
            convos.add(cv);
        }
        adapter.notifyDataSetChanged();
        if (convos.isEmpty()) {
            statusView.setText("No conversations yet — exchange contact "
                    + "cards (Contacts) or start a chat.");
        }
    }

    private void refreshServer() {
        RelayClient.listChannels(act, new RelayClient.Cb<List<RelayCore.ChannelInfo>>() {
            @Override public void ok(List<RelayCore.ChannelInfo> channels) {
                mergeServer(channels);
            }

            @Override public void err(String code, String message) {
                if ("unregistered_device".equals(code)) {
                    RelayClient.register(act,
                            new RelayClient.Cb<Boolean>() {
                                @Override public void ok(Boolean created) { }
                                @Override public void err(String c2, String m2) { }
                            });
                }
            }
        });
    }

    private void mergeServer(List<RelayCore.ChannelInfo> channels) {
        Set<String> known = new HashSet<>();
        for (Convo cv : convos) known.add(cv.channelId);
        String myCardId = RelayIds.dmChannelId(Profile.cardDmKey(act));
        boolean changed = false;
        for (RelayCore.ChannelInfo ci : channels) {
            Convo match = null;
            for (Convo cv : convos) {
                if (cv.channelId.equals(ci.id)) { match = cv; break; }
            }
            if (match != null) {
                boolean hasNew = ci.lastSeq > RelayClient.cursor(act, ci.id);
                if (hasNew != match.hasNew) { match.hasNew = hasNew; changed = true; }
                continue;
            }
            if (ci.id.startsWith("open:") || ci.id.startsWith("priv:")) {
                continue; // walkie channels live on the Walkie page
            }
            Convo cv = new Convo();
            cv.channelId = ci.id;
            if (ci.id.equals(myCardId)) {
                // A DM addressed to my contact card: the key is mine.
                cv.keyB64 = Profile.cardDmKey(act);
                cv.kind = "dm";
                cv.title = "Direct message";
                cv.note = "Someone messaged your contact card — open it "
                        + "to see who";
            } else {
                cv.kind = "locked";
                cv.title = ci.id.startsWith("dm:") ? "Direct message" : "Chat channel";
                cv.note = "You're a member, but this conversation's key "
                        + "isn't on this phone — ask for the key (chat) or "
                        + "a fresh contact card (DM)";
            }
            cv.hasNew = ci.lastSeq > RelayClient.cursor(act, ci.id);
            convos.add(cv);
            changed = true;
        }
        if (changed) adapter.notifyDataSetChanged();
    }

    private void openConvo(Convo cv) {
        if ("locked".equals(cv.kind)) {
            Toast.makeText(act, cv.note, Toast.LENGTH_LONG).show();
            return;
        }
        openConversation(cv.channelId, cv.keyB64, cv.title, cv.peerPub);
    }

    /**
     * Open a conversation in the in-page thread (row taps, the
     * new-chat flow, and intent routing from MainActivity — contact
     * DM buttons and the retired RoomActivity all land here).
     */
    public void openConversation(String channelId, String keyB64, String name,
                                 String peerPub) {
        stopListPoll();
        listRoot.setVisibility(View.GONE);
        threadView.setVisibility(View.VISIBLE);
        threadView.open(channelId, keyB64, name, peerPub);
        threadView.setPageActive(pageVisible);
    }

    private void closeThread() {
        threadView.close();
        threadView.setVisibility(View.GONE);
        listRoot.setVisibility(View.VISIBLE);
        rebuildLocal();
        refreshServer();
        startListPoll();
    }

    // ---- new chat ----

    private void newChatDialog() {
        LinearLayout box = Ui.column(act);
        box.setPadding(Ui.dp(act, 16), Ui.dp(act, 8), Ui.dp(act, 16), 0);
        final EditText nameField = Ui.input(act, "Chat name, e.g. Crew");
        box.addView(nameField);
        final List<CheckBox> boxes = new ArrayList<>();
        final List<JSONObject> members = new ArrayList<>();
        for (JSONObject contact : Profile.contactList(act)) {
            String pub = contact.optString("relayKey", "");
            CheckBox cb = new CheckBox(act);
            cb.setText(contact.optString("name", "Unnamed")
                    + (pub.isEmpty() ? " — no relay key yet" : ""));
            cb.setTextColor(Ui.TEXT);
            cb.setTextSize(15);
            cb.setEnabled(!pub.isEmpty());
            boxes.add(cb);
            members.add(contact);
            box.addView(cb);
        }
        if (members.isEmpty()) {
            box.addView(Ui.body(act, "No contacts yet — a chat starts "
                    + "empty and its key can be shared from inside."));
        }
        new AlertDialog.Builder(act)
                .setTitle("New chat channel")
                .setView(box)
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Create", (d, w) -> {
                    String name = nameField.getText().toString().trim();
                    if (name.isEmpty()) name = "Chat";
                    String keyB64 = Profile.newChannelKey();
                    Profile.addChatChannel(act, name, keyB64);
                    String id = RelayIds.chatChannelId(keyB64);
                    int added = 0;
                    for (int i = 0; i < boxes.size(); i++) {
                        if (!boxes.get(i).isChecked()) continue;
                        String pub = members.get(i).optString("relayKey", "");
                        if (pub.isEmpty()) continue;
                        RelayClient.addMemberQuiet(act, id, pub);
                        added++;
                    }
                    Toast.makeText(act, added > 0
                            ? "Chat created — members added over the relay. "
                              + "Share the chat key from inside the chat so "
                              + "they can read it."
                            : "Chat created. Share its key from inside the chat.",
                            Toast.LENGTH_LONG).show();
                    rebuildLocal();
                    refreshServer();
                    Convo cv = new Convo();
                    cv.channelId = id;
                    cv.keyB64 = keyB64;
                    cv.title = name;
                    cv.kind = "chat";
                    openConvo(cv);
                })
                .show();
    }

    // ---- listening: contact cards + chat keys ----

    /** Heard keys land here from MainActivity.onActivityResult —
     *  the listen itself runs in AudioKeyListenActivity under the
     *  host. Resolution is unchanged from the old QR scan path. */
    public void handleHeardText(String text) {
        if (text == null) return;
        JSONObject card = ContactCard.parse(text);
        if (card != null) {
            Toast.makeText(act, ContactCard.apply(act, card), Toast.LENGTH_LONG).show();
            rebuildLocal();
            return;
        }
        JSONObject chatKey = parseChatKey(text);
        if (chatKey != null) {
            Profile.addChatChannel(act, chatKey.optString("name", "Chat"),
                    chatKey.optString("key", ""));
            Toast.makeText(act, "Chat key saved — the chat is in the list. "
                    + "If pulls say you're not a member, the person who "
                    + "shared it must add your profile key.", Toast.LENGTH_LONG).show();
            rebuildLocal();
            return;
        }
        Toast.makeText(act, "That sound wasn't a contact card or a chat key.",
                Toast.LENGTH_LONG).show();
    }

    /** Chat-key payload: {"app":"control-chat","v":1,"name","key"}. */
    public static JSONObject parseChatKey(String text) {
        if (text == null) return null;
        try {
            JSONObject o = new JSONObject(text);
            if (!"control-chat".equals(o.optString("app"))) return null;
            return o.optString("key", "").isEmpty() ? null : o;
        } catch (Exception e) {
            return null;
        }
    }

    /** Short display id for a sender pubkey (legacy callers). */
    static String shortId(String pub) {
        if (pub == null || pub.isEmpty()) return "";
        try {
            return RelayKeys.fingerprint(RelayKeys.b64UrlDecode(pub));
        } catch (Exception e) {
            return pub.length() > 12 ? pub.substring(0, 12) : pub;
        }
    }

    // ---- list rendering ----

    private class ConvoAdapter extends BaseAdapter {
        @Override public int getCount() { return convos.size(); }
        @Override public Object getItem(int pos) { return convos.get(pos); }
        @Override public long getItemId(int pos) { return pos; }

        @Override
        public View getView(int pos, View convert, ViewGroup parent) {
            Convo cv = convos.get(pos);
            LinearLayout row = new LinearLayout(act);
            row.setOrientation(LinearLayout.VERTICAL);
            row.setPadding(Ui.dp(act, 12), Ui.dp(act, 10),
                    Ui.dp(act, 12), Ui.dp(act, 10));
            row.setBackground(Ui.roundedStroke(act,
                    "locked".equals(cv.kind) ? Ui.PANEL : Ui.SURFACE, 14, Ui.LINE));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.setMargins(0, Ui.dp(act, 4), 0, Ui.dp(act, 4));
            row.setLayoutParams(lp);
            TextView name = Ui.text(act,
                    cv.title + (cv.hasNew ? "  ●" : ""), 17,
                    cv.hasNew ? Ui.ACCENT : Ui.TEXT);
            row.addView(name);
            TextView note = Ui.text(act, cv.note, 13, Ui.DIM);
            note.setTypeface(android.graphics.Typeface.MONOSPACE);
            row.addView(note);
            return row;
        }
    }
}
