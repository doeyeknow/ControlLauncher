package app.control.launcher;

import android.app.Activity;
import android.app.AlertDialog;
import android.graphics.Bitmap;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.PopupMenu;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * One relay conversation (v1.3's RoomActivity, rehomed into the Chat
 * pager page in v1.5): a chat channel or a DM, per PROTOCOL.md. A
 * poll loop (every ~3s while the thread is open AND its page is
 * shown) pulls after the persisted per-channel cursor; sends post
 * immediately with a UUID clientMsgId and echo locally, the pull
 * dedupes by clientMsgId.
 *
 * Everything on the wire and on the server is a sealed blob
 * (AES-256-GCM under the conversation's channel key). Plaintext lives
 * only on member devices. The note under the title says exactly that
 * — and what it does not claim.
 *
 * The view is built once by ChatPage and kept: opening a conversation
 * resets the message state exactly like a fresh RoomActivity did;
 * swiping away from the page only pauses the poll, it does not tear
 * the thread down.
 */
public class ChatThreadView extends LinearLayout {

    /** The thread's own Back affordance (the page also gets the
     *  system Back via MainActivity). */
    public interface Listener {
        void onCloseThread();
    }

    static final class Msg {
        String senderPub = "";
        String senderName = "";
        String text = "";
        String cmid = "";
        boolean mine;
        boolean undecryptable;
        /** v1.28: non-null when the text arrived word-coded
         *  with a dictionary this build cannot decode. */
        String wordNote;
        /** v1.32: the wire form exactly as sent/received —
         *  null when unrecorded (a message this build never
         *  saw the plaintext of). Never a re-encode. */
        String wire;
        /** v1.32: the original text's byte count (-1 when
         *  unknown, i.e. an undecoded payload). */
        int plainBytes = -1;
        /** v1.32: whether the payload arrived coded. */
        boolean coded;
    }

    private final Activity act;
    private Listener listener;

    private String channelId = "";
    private String channelKeyB64;
    private byte[] channelKey;
    private String title = "";
    private final List<Msg> messages = new ArrayList<>();
    private final Set<String> seenCmids = new HashSet<>();
    private MsgAdapter adapter;
    private ListView list;
    private TextView status;
    private TextView titleView;
    private Button shareButton;
    private View composerAnchor;
    private boolean open;
    private boolean pageActive;
    private final Handler poll = new Handler(Looper.getMainLooper());
    private boolean polling;
    private boolean pulling;
    private final Runnable tick = new Runnable() {
        @Override public void run() {
            if (!polling) return;
            pullNow();
            poll.postDelayed(this, 3000);
        }
    };

    public ChatThreadView(Activity host) {
        super(host);
        this.act = host;
        setOrientation(LinearLayout.VERTICAL);
        int pad = Ui.dp(act, 16);
        setPadding(pad, Ui.dp(act, 16), pad, Ui.dp(act, 12));

        LinearLayout head = Ui.row(act);
        Button back = Ui.smallButton(act, "‹ Chats");
        back.setOnClickListener(v -> {
            if (listener != null) listener.onCloseThread();
        });
        head.addView(back);
        titleView = Ui.text(act, "Chat", 22, Ui.TEXT);
        head.addView(titleView, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        shareButton = Ui.smallButton(act, "Share key");
        shareButton.setOnClickListener(v -> shareKeySound());
        head.addView(shareButton);
        addView(head);
        TextView note = Ui.text(act,
                "Sealed with this conversation's channel key (AES-256-GCM). "
                + "The relay stores ciphertext only — it can see who talks "
                + "and when, never what's said. Anyone holding the channel "
                + "key can read: it is not Signal-grade end-to-end encryption.",
                12, Ui.DIM);
        note.setTypeface(android.graphics.Typeface.MONOSPACE);
        addView(note, Ui.wrap(0, 4, 0, 0, act));
        status = Ui.text(act, "", 13, Ui.ACCENT);
        addView(status);

        list = new ListView(act);
        list.setDivider(null);
        adapter = new MsgAdapter();
        list.setAdapter(adapter);
        // v1.32: long-press a message to view the encoded
        // form that actually went over the wire.
        list.setOnItemLongClickListener((p, v, pos, id) -> {
            showMessageMenu(v, messages.get(pos));
            return true;
        });
        addView(list, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        LinearLayout composer = new LinearLayout(act);
        composer.setOrientation(LinearLayout.HORIZONTAL);
        EditText input = Ui.input(act, "Message");
        composer.addView(input, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        Button send = Ui.button(act, "Send", true);
        send.setOnClickListener(v -> {
            String text = input.getText().toString().trim();
            if (text.isEmpty()) return;
            input.setText("");
            sendText(text);
        });
        composer.addView(send);
        composerAnchor = composer;
        addView(composer, Ui.wrap(0, 8, 0, 0, act));
    }

    public void setListener(Listener l) {
        listener = l;
    }

    public boolean isOpen() {
        return open;
    }

    /**
     * Open a conversation. Resets the message state exactly like a
     * fresh RoomActivity: the persisted cursor decides what the pull
     * brings in. The DM member-add and registration kick off here,
     * as they did in RoomActivity.onCreate.
     */
    public void open(String channelId, String channelKeyB64, String name,
                     String peerPub) {
        this.channelId = channelId == null ? "" : channelId;
        this.channelKeyB64 = channelKeyB64;
        this.title = name == null ? "Chat" : name;
        this.channelKey = RelayIds.decodeKey(channelKeyB64);
        titleView.setText(this.title);
        shareButton.setVisibility(
                channelKey != null && this.channelId.startsWith("chat:")
                        ? View.VISIBLE : View.GONE);
        status.setText("");
        messages.clear();
        seenCmids.clear();
        adapter.notifyDataSetChanged();
        open = true;

        // DM membership (PROTOCOL §5.1): opening the conversation adds
        // the other party — creating the channel when we're first.
        if (peerPub != null && !peerPub.isEmpty()) {
            RelayClient.addMemberQuiet(act, this.channelId, peerPub);
        }
        RelayClient.ensureRegistered(act);
        updatePolling();
        if (polling) pullNow();
    }

    /** Close the thread (back to the conversation list). */
    public void close() {
        open = false;
        updatePolling();
    }

    /** The hosting page is shown/hidden — polling follows it, so the
     *  relay is never polled for chat while another page is up. */
    public void setPageActive(boolean active) {
        pageActive = active;
        updatePolling();
        if (polling) pullNow();
    }

    public void destroy() {
        open = false;
        polling = false;
        poll.removeCallbacks(tick);
    }

    private void updatePolling() {
        boolean want = open && pageActive;
        if (want == polling) return;
        polling = want;
        poll.removeCallbacks(tick);
        if (polling) poll.postDelayed(tick, 3000);
    }

    /** The message list and the composer own their horizontal
     *  gestures (coords relative to the hosting ChatPage). */
    boolean ownsGestureWithin(View page, float x, float y) {
        return inside(page, list, x, y) || inside(page, composerAnchor, x, y);
    }

    private boolean inside(View page, View v, float x, float y) {
        if (v == null) return false;
        int[] mine = new int[2];
        int[] its = new int[2];
        page.getLocationOnScreen(mine);
        v.getLocationOnScreen(its);
        float lx = its[0] - mine[0];
        float ly = its[1] - mine[1];
        return x >= lx && x < lx + v.getWidth() && y >= ly && y < ly + v.getHeight();
    }

    // ---- pull ----

    private void pullNow() {
        if (pulling || channelId.isEmpty()) return;
        pulling = true;
        final long after = RelayClient.cursor(act, channelId);
        RelayClient.pull(act, channelId, after, 200, new RelayClient.Cb<RelayCore.PullResult>() {
            @Override public void ok(RelayCore.PullResult r) {
                pulling = false;
                boolean added = ingest(r);
                RelayClient.setCursor(act, channelId, r.nextAfter);
                if (added) {
                    adapter.notifyDataSetChanged();
                    list.setSelection(messages.size() - 1);
                }
                if (!r.messages.isEmpty()) status.setText("");
            }

            @Override public void err(String code, String message) {
                pulling = false;
                if ("not_a_member".equals(code)) {
                    status.setText("You're not a member of this channel yet — "
                            + "the person who shared the key must add your "
                            + "profile key: " + Profile.relayShortFingerprint(act));
                } else if ("channel_not_found".equals(code)) {
                    status.setText("This channel doesn't exist on the relay "
                            + "yet — it is created by the first member-add "
                            + "or message.");
                } else if ("unregistered_device".equals(code)) {
                    RelayClient.register(act, new RelayClient.Cb<Boolean>() {
                        @Override public void ok(Boolean created) { }
                        @Override public void err(String c2, String m2) { }
                    });
                    status.setText("Registering this profile key with the relay…");
                }
            }
        });
    }

    private boolean ingest(RelayCore.PullResult r) {
        boolean added = false;
        String myPub = RelayClient.myPub(act);
        for (RelayCore.Msg m : r.messages) {
            if (!m.clientMsgId.isEmpty() && !seenCmids.add(m.clientMsgId)) continue;
            Msg msg = new Msg();
            msg.senderPub = m.sender;
            msg.mine = myPub.equals(m.sender);
            msg.cmid = m.clientMsgId;
            msg.senderName = msg.mine ? "You" : ContactCard.senderName(act, m.sender);
            try {
                String plain = channelKey == null
                        // Open/unkeyed: the blob is the plaintext itself.
                        ? new String(RelayKeys.b64StdDecode(m.ciphertext),
                                StandardCharsets.UTF_8)
                        : new String(RelayCrypto.open(channelKey, m.ciphertext),
                                StandardCharsets.UTF_8);
                // v1.28: both branches unwrap, so a word-coded
                // message decodes on an unkeyed channel exactly
                // as on a keyed one. Side effect, and an
                // improvement: a plain envelope on an unkeyed
                // channel now shows its text, not its JSON.
                ChatEnvelope.Unwrapped u = ChatEnvelope.unwrap(plain);
                msg.text = u.text;
                msg.wordNote = u.wordNote;
                // v1.32: the wire form as received, never a
                // re-encode; an undecoded payload's original
                // size is unknown.
                msg.wire = u.wire;
                msg.coded = u.coded;
                msg.plainBytes = u.wordNote != null ? -1
                        : u.text.getBytes(StandardCharsets.UTF_8).length;
                // v1.29: clean decodes teach the table their
                // new words, and adopted teach-block codes
                // mark this conversation taught.
                DictStore.noteReceived(channelId,
                        new WordDict.Result(u.text, u.wordNote, u.adopted));
            } catch (Exception e) {
                msg.undecryptable = true;
            }
            if (msg.undecryptable) {
                msg.text = "Sealed message — can't open it with this "
                        + "conversation's key.";
            }
            messages.add(msg);
            added = true;
        }
        return added;
    }

    /** The long-press menu for one message (v1.32): the
     *  encoded view shows the recorded wire form, never a
     *  re-encode. An undecryptable message has no recorded
     *  form — the dialog says so honestly. */
    private void showMessageMenu(View anchor, Msg m) {
        PopupMenu menu = new PopupMenu(act, anchor);
        menu.getMenu().add("View encoded message");
        menu.setOnMenuItemClickListener(item -> {
            EncodedMessageDialog.show(act, m.wire, m.plainBytes, m.coded,
                    m.wordNote, m.text, null);
            return true;
        });
        menu.show();
    }

    // ---- send ----

    private void sendText(String text) {
        String cmid = UUID.randomUUID().toString();
        Msg local = new Msg();
        local.mine = true;
        local.senderName = "You";
        local.text = text;
        local.cmid = cmid;
        // v1.32: the envelope builder returns the exact wire
        // form, recorded here so the long-press view shows
        // what was actually sent, never a re-encode.
        ChatEnvelope.Built built = ChatEnvelope.build(text, channelId);
        local.wire = built.wire;
        local.coded = built.coded;
        local.plainBytes = text.getBytes(StandardCharsets.UTF_8).length;
        messages.add(local);
        seenCmids.add(cmid);
        adapter.notifyDataSetChanged();
        list.setSelection(messages.size() - 1);

        String envelope = built.envelope;
        byte[] plain = envelope.getBytes(StandardCharsets.UTF_8);
        final String blob = channelKey != null
                ? RelayCrypto.seal(channelKey, plain)
                : RelayKeys.b64Std(plain);
        RelayClient.post(act, channelId, cmid, blob, new RelayClient.Cb<RelayCore.PostResult>() {
            @Override public void ok(RelayCore.PostResult r) { }

            @Override public void err(String code, String message) {
                Toast.makeText(act,
                        "Not sent (" + code + "): " + message, Toast.LENGTH_LONG).show();
            }
        });
    }

    private void shareKeySound() {
        if (channelKeyB64 == null || channelKeyB64.isEmpty()) {
            Toast.makeText(act, "This conversation has no key to share.",
                    Toast.LENGTH_LONG).show();
            return;
        }
        act.startActivity(AudioKeyPlayActivity.chatKeyIntent(act, title, channelKeyB64));
    }

    // ---- rendering ----

    private class MsgAdapter extends BaseAdapter {
        @Override public int getCount() { return messages.size(); }
        @Override public Object getItem(int pos) { return messages.get(pos); }
        @Override public long getItemId(int pos) { return pos; }

        @Override
        public View getView(int pos, View convert, ViewGroup parent) {
            Msg m = messages.get(pos);
            LinearLayout row = new LinearLayout(act);
            row.setOrientation(LinearLayout.VERTICAL);
            row.setPadding(Ui.dp(act, 10), Ui.dp(act, 7),
                    Ui.dp(act, 10), Ui.dp(act, 7));
            row.setBackground(Ui.roundedStroke(act,
                    m.mine ? Ui.SURFACE2 : Ui.PANEL, 12, Ui.LINE));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.setMargins(0, Ui.dp(act, 3), 0, Ui.dp(act, 3));
            row.setLayoutParams(lp);
            TextView who = Ui.text(act, m.senderName, 12,
                    m.mine ? Ui.ACCENT : Ui.DIM);
            who.setTypeface(android.graphics.Typeface.MONOSPACE);
            row.addView(who);
            TextView body = Ui.text(act, m.text, 16,
                    m.undecryptable ? Ui.SIGNAL : Ui.TEXT);
            row.addView(body);
            // v1.28: an undecodable word-coded text names why
            // it shows as sent, under the text itself.
            if (m.wordNote != null && !m.wordNote.isEmpty()) {
                row.addView(Ui.text(act, m.wordNote, 11, Ui.SIGNAL));
            }
            return row;
        }
    }
}
