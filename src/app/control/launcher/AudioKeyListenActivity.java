package app.control.launcher;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.nfc.NfcAdapter;
import android.nfc.Tag;
import android.nfc.tech.IsoDep;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

/**
 * Listens for a key played as sound (v1.8). The counterpart to
 * AudioKeyPlayActivity: the other phone plays, this phone hears,
 * and the decoded key is delivered to the caller as the same JSON
 * text the old QR scan used to deliver — so every caller's
 * resolution code runs unchanged.
 *
 * Expects one kind of key (EXTRA_EXPECT): a different kind arriving
 * is named honestly and listening continues. Listening times out
 * after 30 seconds and always releases the microphone — on
 * success, on timeout, on pause, on destroy.
 *
 * v1.26: the screen also arms NFC reader mode while it is open.
 * Tapping the phones back to back (screen on, backs together)
 * confirms the listen is armed — the tap carries only an arm
 * signal (see NfcArm); the sound still carries the key. The tap
 * is a shortcut, never the only way in: opening this screen arms
 * listening by itself, with or without a tap, and a tap with no
 * armed screen does nothing (see ListenArm). The type tones at
 * the head of each packet are announced as they arrive, so the
 * screen names what is coming before the payload finishes.
 */
public class AudioKeyListenActivity extends ThemedActivity {

    @Override
    protected boolean recreateOnThemeChange() {
        // Theme change: a rebuild would interrupt listening; the new theme
        // applies the next time this screen opens.
        return false;
    }

    /** Result extra: the decoded key as legacy JSON text. */
    public static final String RESULT_TEXT = "audio_text";
    public static final String EXTRA_EXPECT = "expect";
    public static final String EXTRA_TITLE = "title";
    public static final String EXTRA_MESSAGE = "message";

    public static final String EXPECT_CHANNEL = "channel";
    public static final String EXPECT_CONTACT = "contact";
    public static final String EXPECT_MESH = "mesh";
    public static final String EXPECT_ANY = "any";

    private static final int REQ_MIC = 7801;
    /** One listen lasts 30 seconds, then asks to try again. */
    private static final long LISTEN_TIMEOUT_MS = 30_000;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final AudioKeyAudio.Listener listener = new AudioKeyAudio.Listener();
    /** The arm rules: manual arming + tap confirmation. */
    private final ListenArm arm = new ListenArm();

    private String expect = EXPECT_ANY;
    private boolean listening;
    private boolean done;
    private ProgressBar levelBar;
    private TextView status;
    private Button againButton;
    private NfcAdapter nfcAdapter;
    private boolean readerArmed;

    /** Runs on a binder thread: SELECT our AID, verify the arm
     *  signal, and only then touch the UI thread. Any failure
     *  (tag left early, wrong answer) is ignored silently — the
     *  sound path still works without the tap. */
    private final NfcAdapter.ReaderCallback readerCallback = new NfcAdapter.ReaderCallback() {
        @Override
        public void onTagDiscovered(Tag tag) {
            IsoDep iso = IsoDep.get(tag);
            if (iso == null) return;
            try {
                iso.setTimeout(1000);
                iso.connect();
                byte[] resp = iso.transceive(NfcArm.buildSelectApdu());
                iso.close();
                if (!NfcArm.isArmResponse(resp)) return;
                runOnUiThread(() -> {
                    if (arm.tap()) {
                        status.setText("Tap heard — listening for the sound…");
                    }
                });
            } catch (Exception ignored) {
                try {
                    iso.close();
                } catch (Exception ignored2) {
                }
            }
        }
    };

    private final Runnable timeout = () -> {
        if (done) return;
        arm.timeout();
        stopListening();
        status.setText("Nothing heard in 30 seconds. Make sure the other "
                + "phone is playing, the phones are close, and the room is "
                + "reasonably quiet — then listen again.");
        againButton.setVisibility(Button.VISIBLE);
    };

    // ---- factories: one per listening context, copy in one place ----

    public static Intent channelIntent(Context c) {
        Intent i = new Intent(c, AudioKeyListenActivity.class);
        i.putExtra(EXTRA_EXPECT, EXPECT_CHANNEL);
        i.putExtra(EXTRA_TITLE, "Listen for a channel invite");
        i.putExtra(EXTRA_MESSAGE, "Ask the other phone to play the channel's "
                + "join sound (Walkie page → Show join code, or the play "
                + "button at the bottom of the radio). Hold the phones near "
                + "each other in a reasonably quiet room.");
        return i;
    }

    public static Intent contactIntent(Context c) {
        Intent i = new Intent(c, AudioKeyListenActivity.class);
        i.putExtra(EXTRA_EXPECT, EXPECT_CONTACT);
        i.putExtra(EXTRA_TITLE, "Listen for a contact card");
        i.putExtra(EXTRA_MESSAGE, "Ask the other phone to play their contact "
                + "card (Contacts → My contact card). Hold the phones near "
                + "each other in a reasonably quiet room.");
        return i;
    }

    /** Mesh join listens for a mesh invite, which names the mesh
     *  to join and how to reach it. */
    public static Intent meshIntent(Context c) {
        Intent i = new Intent(c, AudioKeyListenActivity.class);
        i.putExtra(EXTRA_EXPECT, EXPECT_MESH);
        i.putExtra(EXTRA_TITLE, "Join a mesh by sound");
        i.putExtra(EXTRA_MESSAGE, "Ask the phone hosting the mesh to play "
                + "its mesh sound (Walkie page → Share mesh by sound). "
                + "This phone joins the mesh named in the sound. Hold the "
                + "phones near each other in a reasonably quiet room — "
                + "or tap them back to back first, then play the sound.");
        return i;
    }

    /** Chat listens for either a contact card or a chat key, as the
     *  old Scan button did. */
    public static Intent anyIntent(Context c) {
        Intent i = new Intent(c, AudioKeyListenActivity.class);
        i.putExtra(EXTRA_EXPECT, EXPECT_ANY);
        i.putExtra(EXTRA_TITLE, "Listen for a key");
        i.putExtra(EXTRA_MESSAGE, "Ask the other phone to play a contact "
                + "card or a chat key. Hold the phones near each other in "
                + "a reasonably quiet room.");
        return i;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        String e = getIntent().getStringExtra(EXTRA_EXPECT);
        if (e != null) expect = e;

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

        levelBar = new ProgressBar(this, null,
                android.R.attr.progressBarStyleHorizontal);
        levelBar.setMax(1000);
        levelBar.setProgress(0);
        root.addView(levelBar, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        status = Ui.text(this, "Listening…", 14, Ui.DIM);
        status.setGravity(Gravity.CENTER);
        root.addView(status, Ui.wrap(0, 8, 0, 0, this));

        againButton = Ui.button(this, "Listen again", true);
        againButton.setOnClickListener(v -> {
            againButton.setVisibility(Button.GONE);
            startListening();
        });
        againButton.setVisibility(Button.GONE);
        root.addView(againButton, Ui.wrap(0, 12, 0, 0, this));

        Button cancel = Ui.button(this, "Cancel", false);
        cancel.setOnClickListener(v -> finish());
        root.addView(cancel, Ui.wrap(0, 8, 0, 0, this));

        nfcAdapter = NfcAdapter.getDefaultAdapter(this);

        if (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(
                    new String[]{android.Manifest.permission.RECORD_AUDIO}, REQ_MIC);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions,
                                           int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_MIC) {
            if (grantResults.length > 0
                    && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                startListening();
            } else {
                status.setText("Microphone permission is needed to hear a "
                        + "key. Grant it in Settings → Apps → Control → "
                        + "Permissions, then try again.");
                Toast.makeText(this, "Microphone permission is needed to listen.",
                        Toast.LENGTH_LONG).show();
            }
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (!done && !listening
                && checkSelfPermission(android.Manifest.permission.RECORD_AUDIO)
                == PackageManager.PERMISSION_GRANTED) {
            startListening();
        }
        armReaderMode();
    }

    @Override
    protected void onPause() {
        disarmReaderMode();
        arm.disarm();
        stopListening();
        super.onPause();
    }

    /** Arms NFC reader mode while this screen is in front: a tap
     *  from the other phone's HCE service confirms the listen.
     *  No NFC hardware, or NFC off, means no reader mode — the
     *  screen still listens by sound alone, and says nothing
     *  about a tap it cannot hear. */
    private void armReaderMode() {
        if (nfcAdapter == null || done) return;
        try {
            nfcAdapter.enableReaderMode(this, readerCallback,
                    NfcAdapter.FLAG_READER_NFC_A
                            | NfcAdapter.FLAG_READER_SKIP_NDEF_CHECK,
                    null);
            readerArmed = true;
            if (listening) refreshIdleStatus();
        } catch (Exception ignored) {
            readerArmed = false;
        }
    }

    private void disarmReaderMode() {
        if (nfcAdapter == null || !readerArmed) return;
        readerArmed = false;
        try {
            nfcAdapter.disableReaderMode(this);
        } catch (Exception ignored) {
        }
    }

    /** The resting status line: listening, plus the tap hint only
     *  when reader mode is actually armed. */
    private void refreshIdleStatus() {
        status.setText(readerArmed
                ? "Listening… You can also tap the phones back to back."
                : "Listening…");
    }

    @Override
    protected void onDestroy() {
        stopListening();
        super.onDestroy();
    }

    private void startListening() {
        if (listening || done) return;
        refreshIdleStatus();
        levelBar.setProgress(0);
        boolean started = listener.start(new AudioKeyAudio.Listener.Cb() {
            @Override public void onLevel(double level) {
                levelBar.setProgress((int) Math.round(level * 1000));
            }

            @Override public void onFrame(byte[] payload) {
                onHeard(payload);
            }

            @Override public void onTypeHeard(int kind) {
                if (AudioKeyPayload.isKnownKind(kind)) {
                    status.setText("Hearing a "
                            + AudioKeyPayload.kindName(kind) + "…");
                } else {
                    status.setText("Hearing a packet type this version "
                            + "doesn't know — it will be ignored. "
                            + "Still listening…");
                }
            }

            @Override public void onCrcError() {
                status.setText("That sound didn't come through clean — ask "
                        + "them to play it again. Still listening…");
            }

            @Override public void onError(String message) {
                status.setText(message);
            }
        });
        if (!started) return;
        arm.arm();
        listening = true;
        ui.postDelayed(timeout, LISTEN_TIMEOUT_MS);
    }

    private void stopListening() {
        ui.removeCallbacks(timeout);
        if (!listening) return;
        listening = false;
        listener.stop();
    }

    private void onHeard(byte[] payload) {
        AudioKeyPayload.Decoded d = AudioKeyPayload.decode(payload);
        if (d == null) {
            status.setText("Heard something that wasn't a Control key — "
                    + "still listening…");
            return;
        }
        boolean wanted = EXPECT_ANY.equals(expect)
                || (EXPECT_CHANNEL.equals(expect) && d.kind == AudioKeyPayload.KIND_CHANNEL)
                || (EXPECT_CONTACT.equals(expect) && d.kind == AudioKeyPayload.KIND_CONTACT)
                || (EXPECT_MESH.equals(expect) && d.kind == AudioKeyPayload.KIND_MESH);
        if (!wanted) {
            status.setText("That was a " + AudioKeyPayload.kindName(d.kind)
                    + " — still listening for " + expectedName() + ".");
            return;
        }
        done = true;
        arm.disarm();
        stopListening();
        OwnLog.log(this, "Key share", "Heard " + describe(d) + ", origin UI");
        Intent out = new Intent();
        out.putExtra(RESULT_TEXT, AudioKeyPayload.toJsonText(d));
        setResult(RESULT_OK, out);
        finish();
    }

    private String expectedName() {
        if (EXPECT_CHANNEL.equals(expect)) return "a channel invite";
        if (EXPECT_CONTACT.equals(expect)) return "a contact card";
        if (EXPECT_MESH.equals(expect)) return "a mesh invite";
        return "a key";
    }

    /** Identifiers only — never key material, never addresses. */
    private static String describe(AudioKeyPayload.Decoded d) {
        switch (d.kind) {
            case AudioKeyPayload.KIND_CHANNEL:
                return "channel invite for Channel " + d.number;
            case AudioKeyPayload.KIND_CONTACT:
                return "contact card for " + d.name;
            case AudioKeyPayload.KIND_CHAT:
                return "chat key for " + d.name;
            case AudioKeyPayload.KIND_MESH:
                return "mesh invite for " + d.channelName + " via "
                        + (d.road == AudioKeyPayload.ROAD_LAN ? "LAN" : "Wi-Fi Direct");
            default:
                return "key";
        }
    }
}
