package app.control.launcher;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.view.Gravity;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONObject;

/**
 * Plays a key as sound (v1.8). Keys travel by audio now: the other
 * phone listens from the matching screen, and the key arrives in a
 * few seconds of tones — no camera, no QR.
 *
 * One generic screen for every key that gets shared: channel join
 * codes, contact cards, exchange cards, chat keys. Callers build
 * the intent with the static factories, which carry the payload
 * bytes plus the copy and the audit label (identifiers only — key
 * material never reaches this screen's extras in readable form,
 * and never reaches the audit log).
 */
public class AudioKeyPlayActivity extends ThemedActivity {

    @Override
    protected boolean recreateOnThemeChange() {
        // Theme change: a rebuild would interrupt the playing key sound; the new theme
        // applies the next time this screen opens.
        return false;
    }

    public static final String EXTRA_TITLE = "title";
    public static final String EXTRA_MESSAGE = "message";
    public static final String EXTRA_PAYLOAD = "payload";
    public static final String EXTRA_AUDIT = "audit";

    private AudioKeyAudio.Player player;
    private short[] pcm;
    private String auditLabel = "";
    private Button playButton;
    private ProgressBar progress;
    private TextView status;

    // ---- factories: one per key kind, copy in one place ----

    /** Channel join sound, with the "this sound IS the key" warning
     *  and the transport honesty note the old invite screen carried. */
    public static Intent channelIntent(Context c, JSONObject slot) {
        boolean priv = Profile.isPrivate(slot);
        byte[] payload = AudioKeyPayload.channel(slot.optInt("number"),
                priv, slot.optString("label", ""), slot.optString("key", ""));
        Intent i = new Intent(c, AudioKeyPlayActivity.class);
        i.putExtra(EXTRA_TITLE, Profile.slotDisplayName(slot));
        i.putExtra(EXTRA_MESSAGE, priv
                ? "This sound IS the key — anyone whose phone hears it can "
                + "join this channel. Play it like a key, not like a link.\n\n"
                + "The other phone listens from the Walkie page (Listen for "
                + "invite) or the Channels screen, and lands tuned and ready "
                + "to talk.\n\n"
                + "What the key does: on Wi-Fi mesh, Bluetooth and LAN the "
                + "key itself admits and encrypts — no key, no join. On the "
                + "internet road this channel rides the Control relay: "
                + "members are added by signed relay adds, and bursts and "
                + "texts are sealed with this channel key — the relay stores "
                + "ciphertext only and never sees the key."
                : "This sound carries the channel number and label so "
                + "another Control phone lands on the same open channel. An "
                + "open channel has no key — anyone nearby on this number "
                + "can join, like a real radio.");
        i.putExtra(EXTRA_PAYLOAD, payload);
        i.putExtra(EXTRA_AUDIT, "Channel join sound for "
                + Profile.slotDisplayName(slot) + " (" + Profile.slotIdentity(slot) + ")");
        return i;
    }

    /** My contact card: name + relay pubkey + card DM key. */
    public static Intent myCardIntent(Context c) {
        byte[] payload = AudioKeyPayload.contact(Profile.displayName(c),
                Profile.relayPub(c), Profile.cardDmKey(c));
        Intent i = new Intent(c, AudioKeyPlayActivity.class);
        i.putExtra(EXTRA_TITLE, "Your contact card — " + Profile.displayName(c));
        i.putExtra(EXTRA_MESSAGE, "Whoever hears this gets your relay public "
                + "key and a DM key for messaging you — no account, no server "
                + "holding your contacts. The sound carries a key, so share "
                + "it like one.\n\nThe other phone listens from Contacts "
                + "(Listen for a card).");
        i.putExtra(EXTRA_PAYLOAD, payload);
        i.putExtra(EXTRA_AUDIT, "Contact card for " + Profile.displayName(c));
        return i;
    }

    /** Per-contact exchange card: my relay key + the DM key stored
     *  for this contact. */
    public static Intent exchangeCardIntent(Context c, String contactName, String dmKey) {
        byte[] payload = AudioKeyPayload.contact(Profile.displayName(c),
                Profile.relayPub(c), dmKey);
        Intent i = new Intent(c, AudioKeyPlayActivity.class);
        i.putExtra(EXTRA_TITLE, "Exchange card for " + contactName);
        i.putExtra(EXTRA_MESSAGE, "Have " + contactName + " listen with "
                + "Control (Contacts → Listen for a card) while this plays. "
                + "It gives them your relay key and the DM key for the two "
                + "of you — after that you can message each other from Chat, "
                + "no accounts anywhere.");
        i.putExtra(EXTRA_PAYLOAD, payload);
        i.putExtra(EXTRA_AUDIT, "Exchange card for " + contactName);
        return i;
    }

    /** Mesh join sound (v1.26): the instructions another Control
     *  phone needs to connect to this phone's mesh — which mesh,
     *  which road, and how to reach the host. The sound carries
     *  join instructions, not a channel key: a keyed channel
     *  still needs its invite first. */
    public static Intent meshIntent(Context c, int road, String hostName, String address,
                                    String channelId, String channelName, boolean keyed) {
        byte[] payload = AudioKeyPayload.mesh(road, hostName, address,
                channelId, channelName, keyed);
        boolean lan = road == AudioKeyPayload.ROAD_LAN;
        Intent i = new Intent(c, AudioKeyPlayActivity.class);
        i.putExtra(EXTRA_TITLE, "Mesh sound — " + channelName);
        i.putExtra(EXTRA_MESSAGE, "This sound tells another Control phone "
                + "how to join this mesh (" + (lan ? "same Wi-Fi network"
                : "Wi-Fi Direct group") + ", channel " + channelName + "). "
                + "The other phone listens from the Walkie page (Join by "
                + "sound) and joins the mesh named in the sound."
                + (lan ? "" : "\n\nWi-Fi Direct still needs one system tap: "
                + "when the other phone asks to connect, accept it on "
                + "this phone. That tap is Android's, not Control's.")
                + (keyed ? "\n\nThis channel is keyed — the other phone "
                + "needs the channel invite first, or the mesh will turn "
                + "it away at the door." : ""));
        i.putExtra(EXTRA_PAYLOAD, payload);
        i.putExtra(EXTRA_AUDIT, "Mesh join sound for " + channelName
                + " via " + (lan ? "LAN" : "Wi-Fi Direct"));
        return i;
    }

    /** Chat key for one conversation. */
    public static Intent chatKeyIntent(Context c, String name, String keyB64) {
        byte[] payload = AudioKeyPayload.chat(name, keyB64);
        Intent i = new Intent(c, AudioKeyPlayActivity.class);
        i.putExtra(EXTRA_TITLE, "Chat key — " + name);
        i.putExtra(EXTRA_MESSAGE, "This sound IS the chat key: whoever hears "
                + "it can read everything sealed with it. They also need a "
                + "member-add from an existing member — their profile key "
                + "shows on their Chat screen.\n\nThe other phone listens "
                + "from Chat (Listen).");
        i.putExtra(EXTRA_PAYLOAD, payload);
        i.putExtra(EXTRA_AUDIT, "Chat key for " + name);
        return i;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        byte[] payload = getIntent().getByteArrayExtra(EXTRA_PAYLOAD);
        auditLabel = getIntent().getStringExtra(EXTRA_AUDIT);
        if (auditLabel == null) auditLabel = "";
        if (payload == null) {
            Toast.makeText(this, "Couldn't build the sound — the key is missing.",
                    Toast.LENGTH_LONG).show();
            finish();
            return;
        }
        pcm = AudioModem.encode(payload);
        if (pcm == null) {
            Toast.makeText(this, "Couldn't build the sound.",
                    Toast.LENGTH_LONG).show();
            finish();
            return;
        }

        LinearLayout root = Ui.column(this);
        int pad = Ui.dp(this, 20);
        root.setPadding(pad, Ui.dp(this, 24), pad, pad);
        root.setGravity(Gravity.CENTER_HORIZONTAL);
        setContentView(root);

        TextView title = Ui.text(this,
                String.valueOf(getIntent().getStringExtra(EXTRA_TITLE)), 22, Ui.TEXT);
        title.setGravity(Gravity.CENTER);
        root.addView(title);
        TextView body = Ui.body(this,
                String.valueOf(getIntent().getStringExtra(EXTRA_MESSAGE)));
        body.setGravity(Gravity.CENTER);
        root.addView(body, Ui.wrap(0, 10, 0, 0, this));

        int seconds = Math.max(1, Math.round(AudioModem.airTimeMs(payload) / 1000f));
        TextView how = Ui.body(this,
                "Both phones need Control open, near each other, in a "
                + "reasonably quiet room. Turn the volume up — the sound "
                + "lasts about " + seconds + " seconds. If the other phone "
                + "misses it, play it again.");
        how.setGravity(Gravity.CENTER);
        root.addView(how, Ui.wrap(0, 14, 0, 0, this));

        progress = new ProgressBar(this, null,
                android.R.attr.progressBarStyleHorizontal);
        progress.setMax(1000);
        progress.setProgress(0);
        root.addView(progress, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        status = Ui.text(this, "Ready to play.", 14, Ui.DIM);
        status.setGravity(Gravity.CENTER);
        root.addView(status, Ui.wrap(0, 8, 0, 0, this));

        playButton = Ui.button(this, "Play the sound", true);
        playButton.setOnClickListener(v -> play());
        root.addView(playButton, Ui.wrap(0, 12, 0, 0, this));

        Button done = Ui.button(this, "Done", false);
        done.setOnClickListener(v -> finish());
        root.addView(done, Ui.wrap(0, 8, 0, 0, this));

        player = new AudioKeyAudio.Player();
    }

    private void play() {
        playButton.setEnabled(false);
        progress.setProgress(0);
        status.setText("Playing… hold the phones near each other.");
        player.play(pcm, new AudioKeyAudio.Player.Cb() {
            @Override public void onProgress(float frac) {
                progress.setProgress(Math.round(frac * 1000));
            }

            @Override public void onDone() {
                progress.setProgress(1000);
                status.setText("Played. If the other phone missed it, play it again.");
                playButton.setText("Play again");
                playButton.setEnabled(true);
                OwnLog.log(AudioKeyPlayActivity.this, "Key share",
                        "Played " + auditLabel + ", origin UI");
            }

            @Override public void onError(String message) {
                status.setText(message);
                playButton.setEnabled(true);
            }
        });
    }

    @Override
    protected void onPause() {
        if (player != null) player.stop();
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        if (player != null) player.stop();
        super.onDestroy();
    }
}
