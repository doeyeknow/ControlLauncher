package app.control.launcher;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioRecord;
import android.media.AudioTrack;
import android.media.MediaRecorder;
import android.media.ToneGenerator;
import android.net.wifi.p2p.WifiP2pConfig;
import android.net.wifi.p2p.WifiP2pDevice;
import android.net.wifi.p2p.WifiP2pDeviceList;
import android.net.wifi.p2p.WifiP2pManager;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.net.InetAddress;
import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * The walkie-talkie: one push-to-talk engine over every road that
 * reaches the channel — Internet (the Control Relay), nearby mesh
 * (Wi-Fi Direct), plain LAN Wi-Fi, and Bluetooth — with numbered
 * channel slots tuned by a radio dial, and text riding the same
 * roads, store-and-forward.
 */
public class WalkiePage extends LinearLayout implements PagerLayout.PagerPage {

    private final Activity act;
    private ScrollView scrollRoot;
    private View tunerAnchor;
    private View pttAnchor;
    private View threadAnchor;
    private View composerAnchor;


    private static final int SAMPLE_RATE = 16000;

    private int channelNumber;
    private JSONObject slot;
    private String identity;

    private TextView bigNumber;
    private TextView readoutFreq;
    private TextView readoutLabel;
    private TextView modeLine;
    private TunerView tuner;
    private TextView statusText;
    private TextView talkerText;
    private TextView membersText;
    private Button pttButton;
    private Button scanButton;
    private Button channelActionBtn;
    private Button channelsBtn;
    private LinearLayout playBlock;
    private Button playJoinButton;
    private ProgressBar playProgress;
    private TextView playCaption;
    private String playForIdentity = "";
    private final AudioKeyAudio.Player joinPlayer = new AudioKeyAudio.Player();
    private boolean joinPlaying;
    private ThreadAdapter threadAdapter;
    private final List<JSONObject> threadEntries = new ArrayList<>();
    // v1.18: played received entries leave the thread and live in
    // the per-channel History list. Both lists hold the SAME live
    // entry objects the store holds (never copies), and the lists
    // themselves are never reassigned — the History dialog's
    // adapter keeps working across reloads.
    private HistoryAdapter historyAdapter;
    private final List<JSONObject> historyEntries = new ArrayList<>();
    private Button historyButton;
    private TextView threadEmptyHint;
    private EditText composer;
    private final Handler ui = new Handler(Looper.getMainLooper());

    // recording / playback
    private volatile boolean recording;
    private AudioRecord recorder;
    private Thread recordThread;
    private final ByteArrayOutputStream spurtPcm = new ByteArrayOutputStream();
    private final Map<Long, byte[]> voiceCache = new HashMap<>();
    private ToneGenerator tones;

    // low-data mode (v1.11): on-device transcription + TTS voice profiles
    private SpeechTranscriber transcriber;
    private LowDataSpeaker speaker;
    private boolean lowDataRecording;
    private volatile boolean transcribing;
    private Switch lowDataSwitch;
    private Switch autoReadSwitch;
    private TextView lowDataStatus;
    private LinearLayout modelBlock;
    private TextView modelStatus;
    private ProgressBar modelProgress;
    private Button modelButton;
    private boolean downloadingModel;

    // transports
    private MeshNet mesh;
    private boolean meshHost;
    private boolean meshLive;
    private BtWalkie bt;
    private boolean btLive;
    private LanDiscovery lan;
    private boolean lanHosting;
    private WifiP2pManager p2p;
    private WifiP2pManager.Channel p2pChannel;
    private BroadcastReceiver p2pReceiver;
    private final List<WifiP2pDevice> peers = new ArrayList<>();

    // relay loop (internet road)
    private volatile boolean relayRunning;
    private int relayGen;
    private String relayChannelId = "";
    private byte[] relayKeyBytes;
    private final Set<String> seenCmids = new HashSet<>();

    // channel subscriptions (v1.19): background relay listening
    private Switch subscribeSwitch;
    private TextView subscribeState;
    private BroadcastReceiver subscriptionReceiver;

    // scan
    private boolean scanning;
    private boolean suppressRetune;
    private int scanChannel;
    private final Runnable scanStep = new Runnable() {
        @Override public void run() {
            if (!scanning) return;
            scanChannel = scanChannel % Profile.BANK_SIZE + 1;
            suppressRetune = true;
            tuner.tuneTo(scanChannel, true);
            updateReadout(scanChannel);
            if (channelHasRecentActivity(scanChannel)) {
                statusText.setText("Scan paused — activity on channel " + scanChannel + ".");
                suppressRetune = false;
                if (scanChannel != channelNumber) bindChannel(scanChannel);
                ui.postDelayed(this, 5000);
            } else {
                ui.postDelayed(this, 1100);
            }
        }
    };

    // ------------------------------------------------------------ lifecycle

    public WalkiePage(Activity host) {
        super(host);
        this.act = host;
        setOrientation(LinearLayout.VERTICAL);
        setBackgroundColor(0x00000000); // transparent over the shared background layer
        tones = new ToneGenerator(AudioManager.STREAM_MUSIC, 60);
        transcriber = new SpeechTranscriber();
        speaker = new LowDataSpeaker(host);
        channelNumber = Prefs.get(act).getInt(ChannelsActivity.PREF_LAST_CHANNEL, 1);
        buildUi();
        retune(channelNumber);
        tuner.tuneTo(channelNumber, false);
        startRelayLoop();
    }

    @Override
    public void onPageShown() {
        int wanted = Prefs.get(act).getInt(ChannelsActivity.PREF_LAST_CHANNEL, channelNumber);
        if (wanted != channelNumber) {
            tuner.tuneTo(wanted, false);
            retune(wanted);
        } else {
            // Same number, but its configuration may have changed
            // (made private in Configure, new key, …). If the wire
            // identity changed, rebind so transports run the new
            // key; otherwise just refresh the chrome.
            JSONObject s = Profile.slot(act, channelNumber);
            if (!Profile.slotIdentity(s).equals(identity)) {
                bindChannel(channelNumber);
            } else {
                updateReadout(channelNumber);
                refreshChannelAction();
                refreshPlayBlock();
            }
        }
        registerP2pReceiver();
        registerSubscriptionReceiver();
        refreshLowDataBlock();
        refreshSubscriptionSection();
        refreshChannelsButton();
        // The subscription service is the poller for
        // subscribed channels; coming forward reconciles it
        // back to life if Samsung killed it.
        SubscriptionService.reconcile(act);
    }

    @Override
    public void onPageHidden() {
        stopJoinPlay();
        if (p2pReceiver != null) {
            try {
                act.unregisterReceiver(p2pReceiver);
            } catch (Exception ignored) {
            }
            p2pReceiver = null;
        }
        if (subscriptionReceiver != null) {
            try {
                act.unregisterReceiver(subscriptionReceiver);
            } catch (Exception ignored) {
            }
            subscriptionReceiver = null;
        }
    }

    /** Listens for the subscription service's deliveries while
     *  this page is visible: a message on the TUNED channel
     *  reloads the thread (the service owns polling for
     *  subscribed channels, including the tuned one); a
     *  message anywhere refreshes the other-channels count;
     *  a state change refreshes the subscription line. Same
     *  API-33 guard as the p2p receiver: the 2-arg call
     *  throws for non-exempt filters on API 33+, and
     *  RECEIVER_NOT_EXPORTED is correct — these broadcasts
     *  are app-scoped. */
    private void registerSubscriptionReceiver() {
        if (subscriptionReceiver != null) return;
        subscriptionReceiver = new BroadcastReceiver() {
            @Override public void onReceive(Context context, Intent intent) {
                if (intent == null) return;
                String action = intent.getAction();
                if (SubscriptionService.ACTION_MESSAGE.equals(action)) {
                    String id = intent.getStringExtra(
                            SubscriptionService.EXTRA_IDENTITY);
                    if (id != null && id.equals(identity)) reloadThread();
                    refreshChannelsButton();
                } else if (SubscriptionService.ACTION_STATE.equals(action)) {
                    refreshSubscriptionSection();
                }
            }
        };
        IntentFilter filter = new IntentFilter();
        filter.addAction(SubscriptionService.ACTION_MESSAGE);
        filter.addAction(SubscriptionService.ACTION_STATE);
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            act.registerReceiver(subscriptionReceiver, filter,
                    Context.RECEIVER_NOT_EXPORTED);
        } else {
            act.registerReceiver(subscriptionReceiver, filter);
        }
    }

    @Override
    public void onPageDestroy() {
        stopJoinPlay();
        relayRunning = false;
        relayGen++;
        stopLocalSessions();
        if (lan != null) lan.stop();
        if (tones != null) tones.release();
        if (transcriber != null) transcriber.shutdown();
        if (speaker != null) speaker.shutdown();
    }

    /** The tuner sweep, the hold-to-talk button, the thread and the
     *  composer own their touches; everything else on this page lets
     *  the pager take a horizontal swipe. */
    @Override
    public boolean ownsHorizontalGesture(float x, float y) {
        return inside(tunerAnchor, x, y) || inside(pttAnchor, x, y)
                || inside(threadAnchor, x, y) || inside(composerAnchor, x, y);
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

    // ------------------------------------------------------------ UI

    private void buildUi() {
        LinearLayout root = Ui.column(act);
        int pad = Ui.dp(act, 16);
        root.setPadding(pad, Ui.dp(act, 12), pad, pad);
        ScrollView scroll = new ScrollView(act);
        scroll.setBackgroundColor(0x00000000);
        scroll.addView(root);
        scrollRoot = scroll;
        addView(scroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        // The private channel's join sound plays from the BOTTOM of
        // the radio screen (v1.2 placement, audio since v1.8):
        // pinned outside the scrolling column so it never covers
        // the tuner or the talk button. It is pager chrome — swipes
        // starting on it change pages — and tapping it plays the
        // join sound for the tuned channel. Open channel → no block.
        playBlock = Ui.column(act);
        playBlock.setGravity(Gravity.CENTER_HORIZONTAL);
        playBlock.setBackgroundColor(Ui.SURFACE);
        playBlock.setPadding(pad, Ui.dp(act, 10), pad, Ui.dp(act, 10));
        playJoinButton = Ui.button(act, "Play join sound", true);
        playJoinButton.setOnClickListener(v -> onPlayJoinTapped());
        playBlock.addView(playJoinButton);
        playProgress = new ProgressBar(act, null,
                android.R.attr.progressBarStyleHorizontal);
        playProgress.setMax(1000);
        playProgress.setProgress(0);
        playProgress.setVisibility(GONE);
        playBlock.addView(playProgress, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        playCaption = Ui.text(act, "", 11, Ui.DIM);
        playCaption.setGravity(Gravity.CENTER);
        playBlock.addView(playCaption, Ui.wrap(0, 6, 0, 0, act));
        playBlock.setOnClickListener(v -> onPlayJoinTapped());
        playBlock.setVisibility(GONE);
        addView(playBlock, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        LinearLayout header = Ui.row(act);
        TextView title = Ui.text(act, "WALKIE-TALKIE", 13, Ui.DIM);
        header.addView(title, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        channelsBtn = Ui.smallButton(act, "All channels");
        channelsBtn.setOnClickListener(v -> act.startActivity(new Intent(act, ChannelsActivity.class)));
        header.addView(channelsBtn);
        Button editBtn = Ui.smallButton(act, "Configure");
        editBtn.setOnClickListener(v -> {
            Intent i = new Intent(act, ChannelEditActivity.class);
            i.putExtra("number", channelNumber);
            act.startActivity(i);
        });
        header.addView(editBtn);
        root.addView(header);

        // radio readout (tap = direct tune) — the space mockup's
        // tuner panel: inset panel tone, mono accent frequency.
        LinearLayout readout = Ui.column(act);
        readout.setBackground(Ui.roundedStroke(act, Ui.PANEL, 14, Ui.LINE));
        readout.setPadding(pad, Ui.dp(act, 10), pad, Ui.dp(act, 12));
        LinearLayout readoutRow = Ui.row(act);
        bigNumber = Ui.text(act, "1", 64, Ui.TEXT);
        Ui.display(bigNumber);
        readoutRow.addView(bigNumber);
        LinearLayout readoutSide = Ui.column(act);
        readoutSide.setBackgroundColor(0x00000000);
        readoutSide.setPadding(Ui.dp(act, 14), Ui.dp(act, 10), 0, 0);
        readoutFreq = Ui.text(act, "", 18, Ui.ACCENT);
        readoutFreq.setTypeface(android.graphics.Typeface.MONOSPACE);
        readoutSide.addView(readoutFreq);
        readoutLabel = Ui.text(act, "", 16, Ui.TEXT);
        readoutSide.addView(readoutLabel);
        readoutRow.addView(readoutSide);
        readout.addView(readoutRow);
        modeLine = Ui.text(act, "", 13, Ui.DIM);
        readout.addView(modeLine);
        readout.setOnClickListener(v -> directTuneDialog());
        root.addView(readout, Ui.wrap(0, 10, 0, 0, act));

        tuner = new TunerView(act);
        tuner.setOnTuneListener(new TunerView.OnTuneListener() {
            @Override public void onPreview(int channel) {
                updateReadout(channel);
            }

            @Override public void onSettled(int channel) {
                if (suppressRetune) {
                    // A scan step is previewing — the engine stays put.
                    updateReadout(channel);
                    return;
                }
                stopScan();
                if (channel != channelNumber) bindChannel(channel);
            }
        });
        root.addView(tuner, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(act, 84)));
        TextView tunerHint = Ui.text(act,
                "Drag the band to tune. The frequency is the channel's radio "
                + "identity — your voice itself travels over the data roads below.", 11, Ui.DIM);
        tunerHint.setGravity(Gravity.CENTER);
        root.addView(tunerHint);

        LinearLayout stepRow = Ui.row(act);
        Button down = Ui.smallButton(act, "CH −");
        down.setOnClickListener(v -> step(-1));
        Button up = Ui.smallButton(act, "CH +");
        up.setOnClickListener(v -> step(1));
        scanButton = Ui.smallButton(act, "Scan");
        scanButton.setOnClickListener(v -> {
            if (scanning) stopScan(); else startScan();
        });
        stepRow.addView(down);
        stepRow.addView(up);
        stepRow.addView(scanButton);
        root.addView(stepRow);

        // First-class private-channel flow, right on the radio: make
        // this channel private (or play its join sound), and listen
        // for an invite without leaving the page.
        LinearLayout actionRow = Ui.row(act);
        channelActionBtn = Ui.smallButton(act, "Make this channel private");
        channelActionBtn.setOnClickListener(v -> onChannelAction());
        actionRow.addView(channelActionBtn);
        Button listenInviteBtn = Ui.smallButton(act, "Listen for invite");
        listenInviteBtn.setOnClickListener(v -> {
            if (act instanceof MainActivity) {
                act.startActivityForResult(
                        AudioKeyListenActivity.channelIntent(act),
                        MainActivity.REQ_WALKIE_LISTEN);
            } else {
                act.startActivity(new Intent(act, ChannelsActivity.class));
            }
        });
        actionRow.addView(listenInviteBtn);
        root.addView(actionRow, Ui.wrap(0, 8, 0, 0, act));

        statusText = Ui.body(act, "");
        root.addView(statusText, Ui.wrap(0, 10, 0, 0, act));
        talkerText = Ui.text(act, "", 15, Ui.ACCENT);
        root.addView(talkerText);
        membersText = Ui.body(act, "");
        root.addView(membersText);

        // Push-to-talk — the mockup's round button: dark green disc,
        // lighter ring, mono accent label; held → accent fill.
        LinearLayout pttWrap = new LinearLayout(act);
        pttWrap.setGravity(Gravity.CENTER);
        pttButton = new Button(act);
        pttAnchor = pttButton;
        pttButton.setText("HOLD TO\nTALK");
        pttButton.setTextSize(15);
        pttButton.setTypeface(android.graphics.Typeface.MONOSPACE, android.graphics.Typeface.BOLD);
        pttButton.setLetterSpacing(0.12f);
        pttButton.setTextColor(Ui.ACCENT);
        pttButton.setBackground(pttDrawable(false));
        int pttSide = Ui.dp(act, 148);
        pttWrap.addView(pttButton, new LinearLayout.LayoutParams(pttSide, pttSide));
        pttButton.setOnTouchListener((v, e) -> {
            if (e.getAction() == MotionEvent.ACTION_DOWN) {
                if (lowDataOn()) startLowDataSpurt(); else startSpurt();
            }
            if (e.getAction() == MotionEvent.ACTION_UP
                    || e.getAction() == MotionEvent.ACTION_CANCEL) stopSpurt();
            return true;
        });
        root.addView(pttWrap, Ui.wrap(0, 10, 0, 0, act));

        root.addView(lowDataSection());
        root.addView(readAloudSection());

        // v1.18: History lives one tap above the thread. The
        // button names the count so the thread's shrinking is
        // never a mystery; the hint under it says what the thread
        // holds now (unplayed messages only) without ever claiming
        // a message was heard when it wasn't.
        historyButton = Ui.smallButton(act, "History");
        historyButton.setOnClickListener(v -> showHistoryDialog());
        root.addView(historyButton, Ui.wrap(0, 8, 0, 0, act));
        threadEmptyHint = Ui.text(act,
                "Nothing unplayed here. Messages you have heard are in History.",
                13, Ui.DIM);
        threadEmptyHint.setVisibility(GONE);
        root.addView(threadEmptyHint, Ui.wrap(0, 2, 0, 0, act));

        ListView thread = new ListView(act);
        threadAnchor = thread;
        thread.setDivider(null);
        threadAdapter = new ThreadAdapter();
        historyAdapter = new HistoryAdapter();
        thread.setAdapter(threadAdapter);
        thread.setOnItemClickListener((p, v, pos, id) -> {
            JSONObject entry = threadEntries.get(pos);
            if ("voice".equals(entry.optString("kind"))) {
                playEntry(entry);
            } else if (!entry.optBoolean("mine")) {
                // Any received text replays aloud in the sender's
                // voice profile; own messages never speak.
                replayEntry(entry);
            }
        });
        thread.setOnItemLongClickListener((p, v, pos, id) -> {
            showEntryInfo(threadEntries.get(pos));
            return true;
        });
        root.addView(thread, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(act, 220)));

        LinearLayout composeRow = Ui.row(act);
        composer = Ui.input(act, "Text this channel…");
        composerAnchor = composer;
        composeRow.addView(composer, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        Button send = Ui.button(act, "Send", true);
        send.setOnClickListener(v -> {
            String text = composer.getText().toString().trim();
            if (text.isEmpty()) return;
            composer.setText("");
            sendChannelText(text);
        });
        composeRow.addView(send);
        root.addView(composeRow, Ui.wrap(0, 8, 0, 0, act));

        root.addView(localSection());
        root.addView(internetSection());
        root.addView(subscriptionSection());
        root.addView(bluetoothSection());
    }

    private View localSection() {
        LinearLayout box = Ui.column(act);
        box.setBackgroundColor(0x00000000);
        box.addView(Ui.sectionLabel(act, "Nearby mesh & LAN (no internet)"));
        box.addView(Ui.body(act,
                "Start a session to talk to nearby phones on this channel. "
                + "Mesh uses Wi-Fi Direct; LAN uses the Wi-Fi you're both on."));
        LinearLayout row1 = Ui.row(act);
        Button host = Ui.smallButton(act, "Host mesh group");
        host.setOnClickListener(v -> hostMesh());
        Button join = Ui.smallButton(act, "Join mesh…");
        join.setOnClickListener(v -> joinMesh());
        row1.addView(host);
        row1.addView(join);
        box.addView(row1);
        LinearLayout row2 = Ui.row(act);
        Button lanHost = Ui.smallButton(act, "Start LAN session");
        lanHost.setOnClickListener(v -> hostLan());
        Button stop = Ui.smallButton(act, "Stop local session");
        stop.setOnClickListener(v -> {
            stopLocalSessions();
            statusText.setText("Local session stopped.");
        });
        row2.addView(lanHost);
        row2.addView(stop);
        box.addView(row2);
        return box;
    }

    private View internetSection() {
        LinearLayout box = Ui.column(act);
        box.setBackgroundColor(0x00000000);
        box.addView(Ui.sectionLabel(act, "Internet (Wi-Fi or mobile data)"));
        box.addView(Ui.body(act,
                "Bursts and texts ride this channel on the Control relay — "
                + "no account; your profile key is the identity. Private "
                + "channels are sealed with the channel key before they "
                + "leave the phone, and the relay stores ciphertext only. "
                + "Open channels are public by design, like a real radio."));
        return box;
    }

    /** Channel subscriptions (v1.19): keep listening to the
     *  TUNED channel over the internet relay even when tuned
     *  elsewhere. The switch is per-slot; the subscription
     *  follows the slot number and travels with the profile
     *  export. The copy states the cost honestly: background
     *  polling uses a little data and battery, and more
     *  subscriptions means each channel is checked less
     *  often. Mesh and Bluetooth are untouched — subscriptions
     *  are relay-only, by Adam's decision. */
    private View subscriptionSection() {
        LinearLayout box = Ui.column(act);
        box.setBackgroundColor(0x00000000);
        box.addView(Ui.sectionLabel(act, "Listen in the background"));
        box.addView(Ui.body(act,
                "Keep listening to this channel over the internet "
                + "relay, even when you're tuned elsewhere. Messages "
                + "arrive in this channel's thread and are read aloud, "
                + "just as if you were tuned here. Each subscribed "
                + "channel is checked every few seconds — more "
                + "subscriptions means each one is checked less often "
                + "— and background listening uses a little mobile "
                + "data and battery. Quiet silences the read-aloud, "
                + "as always. The mesh and Bluetooth roads are not "
                + "affected: they only ever hear the tuned channel."));
        subscribeSwitch = new Switch(act);
        subscribeSwitch.setTextColor(Ui.TEXT);
        subscribeSwitch.setTextSize(15);
        subscribeSwitch.setPadding(0, Ui.dp(act, 8), 0, Ui.dp(act, 2));
        subscribeSwitch.setOnCheckedChangeListener((b, on) -> onSubscribeToggled(on));
        box.addView(subscribeSwitch);
        subscribeState = Ui.text(act, "", 13, Ui.DIM);
        box.addView(subscribeState);
        refreshSubscriptionSection();
        return box;
    }

    private void refreshSubscriptionSection() {
        if (subscribeSwitch == null || subscribeState == null) return;
        subscribeSwitch.setOnCheckedChangeListener(null);
        subscribeSwitch.setText("Listen to channel " + channelNumber
                + " in the background");
        subscribeSwitch.setChecked(
                ChannelSubscriptions.isSubscribed(act, channelNumber));
        subscribeSwitch.setOnCheckedChangeListener(
                (b, on) -> onSubscribeToggled(on));
        subscribeState.setText(subscriptionStateLine());
    }

    /** One honest line about this channel's background
     *  listening: off, listening, waiting offline, or the
     *  membership blocker — never a claim the service is
     *  hearing a channel it cannot. */
    private String subscriptionStateLine() {
        if (!ChannelSubscriptions.isSubscribed(act, channelNumber)) {
            return "Not listening in the background — this channel "
                    + "is only heard while tuned here.";
        }
        SubscriptionPolicy.ChannelState s =
                SubscriptionService.channelState(relayChannelId);
        switch (s) {
            case BLOCKED_NOT_MEMBER:
                return "The relay says this phone isn't a member of "
                        + "this private channel, so background listening "
                        + "can't hear it. A member needs to add this "
                        + "phone — or re-join with a fresh invite.";
            case BLOCKED_NOT_FOUND:
                return "This private channel doesn't exist on the "
                        + "relay yet — background listening retries, "
                        + "and starts hearing it once a member posts "
                        + "or adds this phone.";
            case RATE_LIMITED:
                return "The relay asked this phone to slow down — "
                        + "background listening is paused briefly.";
            case OFFLINE:
                return "Waiting — the relay can't be reached. "
                        + "Listening resumes on its own when the "
                        + "connection comes back.";
            case OK:
                return SubscriptionService.serviceAlive
                        ? "Listening in the background."
                        : "Listening resumes when the background "
                        + "service restarts — the tuned channel is "
                        + "still heard while this page is open.";
            case UNKNOWN:
            default:
                return "Listening in the background.";
        }
    }

    private void onSubscribeToggled(boolean on) {
        ChannelSubscriptions.setSubscribed(act, channelNumber, on);
        if (on && Profile.isPrivate(slot)) {
            // Establishing membership is the same member-add
            // sync the tuned-channel path runs: it creates
            // the channel (with this phone as creator and
            // first member) on the first add.
            RelayMembership.sync(act, slot, relayChannelId);
        }
        SubscriptionService.reconcile(act);
        if (on) SubscriptionService.kick(act);
        refreshSubscriptionSection();
    }

    /** The "All channels" button carries the count of unplayed
     *  messages waiting on OTHER channels, so a background
     *  arrival is findable from this page without opening the
     *  grid. Zero means the plain label. */
    private void refreshChannelsButton() {
        if (channelsBtn == null) return;
        int n = ChannelSubscriptions.unplayedTotalExcept(act, channelNumber);
        channelsBtn.setText(n > 0 ? "All channels (" + n + ")" : "All channels");
    }

    private View bluetoothSection() {
        LinearLayout box = Ui.column(act);
        box.setBackgroundColor(0x00000000);
        box.addView(Ui.sectionLabel(act, "Bluetooth (last resort)"));
        box.addView(Ui.body(act,
                "Pairs with one nearby phone over Bluetooth Classic. On "
                + "Bluetooth, talk arrives in bursts — not fluid live talk."));
        LinearLayout row = Ui.row(act);
        Button listen = Ui.smallButton(act, "Listen for a phone");
        listen.setOnClickListener(v -> btListen());
        Button connect = Ui.smallButton(act, "Connect to a paired phone…");
        connect.setOnClickListener(v -> btConnectDialog());
        row.addView(listen);
        row.addView(connect);
        box.addView(row);
        return box;
    }

    // ------------------------------------------------------------ low-data mode (v1.11)

    private boolean lowDataOn() {
        return Prefs.get(act).getBoolean(Prefs.KEY_LOWDATA_ON, false);
    }

    private View lowDataSection() {
        LinearLayout box = Ui.column(act);
        box.setBackgroundColor(0x00000000);
        box.addView(Ui.sectionLabel(act, "Low-data mode"));
        box.addView(Ui.body(act,
                "Sends text instead of audio. You talk, this phone "
                + "transcribes your words on-device, and you check them "
                + "before anything is sent. The other phone reads the "
                + "text aloud in a synthetic voice profile you choose per "
                + "contact — recognizable, but not their real voice. "
                + "Transcription works fully offline once the speech "
                + "model is downloaded."));
        lowDataSwitch = new Switch(act);
        lowDataSwitch.setText("Low-data mode");
        lowDataSwitch.setTextColor(Ui.TEXT);
        lowDataSwitch.setTextSize(15);
        lowDataSwitch.setPadding(0, Ui.dp(act, 8), 0, Ui.dp(act, 2));
        lowDataSwitch.setChecked(lowDataOn());
        lowDataSwitch.setOnCheckedChangeListener((b, on) -> onLowDataToggled(on));
        box.addView(lowDataSwitch);
        lowDataStatus = Ui.text(act, "", 13, Ui.DIM);
        box.addView(lowDataStatus);

        // Speech-model block: shown when the mode is wanted but the
        // model isn't on the phone yet, and while downloading.
        modelBlock = Ui.column(act);
        modelBlock.setBackgroundColor(0x00000000);
        modelStatus = Ui.text(act, "", 13, Ui.SIGNAL);
        modelBlock.addView(modelStatus, Ui.wrap(0, 8, 0, 0, act));
        modelProgress = new ProgressBar(act, null,
                android.R.attr.progressBarStyleHorizontal);
        modelProgress.setMax(1000);
        modelProgress.setProgress(0);
        modelProgress.setVisibility(GONE);
        modelBlock.addView(modelProgress, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        modelButton = Ui.button(act, "Download the speech model", true);
        modelButton.setOnClickListener(v -> onModelButton());
        modelBlock.addView(modelButton, Ui.wrap(0, 6, 0, 0, act));
        box.addView(modelBlock);

        refreshLowDataBlock();
        return box;
    }

    /** Read-aloud (v1.17): one switch for every received walkie
     *  text, on all three roads. It lived inside the low-data
     *  section in v1.11, visible only while low-data mode was on
     *  and covering only low-data messages; Adam's v1.17 directive
     *  ("play the audio anytime it receives a message") makes it
     *  cover all received texts, always visible, default ON. The
     *  stored pref key is unchanged (KEY_LOWDATA_AUTOREAD) so a
     *  user who already turned it off keeps that choice — the key
     *  name predates the wider role. Voice bursts are unaffected:
     *  they play as audio, as always. */
    private View readAloudSection() {
        LinearLayout box = Ui.column(act);
        box.setBackgroundColor(0x00000000);
        box.addView(Ui.sectionLabel(act, "Read aloud"));
        box.addView(Ui.body(act,
                "Every text this phone receives in a walkie channel is "
                + "read aloud in the sender's voice profile — a synthetic "
                + "voice you choose per contact, recognizable, but not "
                + "their real voice."));
        autoReadSwitch = new Switch(act);
        autoReadSwitch.setText("Read walkie messages aloud");
        autoReadSwitch.setTextColor(Ui.TEXT);
        autoReadSwitch.setTextSize(15);
        autoReadSwitch.setPadding(0, Ui.dp(act, 8), 0, Ui.dp(act, 2));
        autoReadSwitch.setChecked(
                Prefs.get(act).getBoolean(Prefs.KEY_LOWDATA_AUTOREAD, true));
        autoReadSwitch.setOnCheckedChangeListener((b, on) -> Prefs.get(act).edit()
                .putBoolean(Prefs.KEY_LOWDATA_AUTOREAD, on).apply());
        box.addView(autoReadSwitch);
        box.addView(Ui.text(act, "Never speaks while Quiet is on.", 13, Ui.DIM));
        return box;
    }

    private void refreshLowDataBlock() {
        if (lowDataSwitch == null) return;
        boolean on = lowDataOn();
        boolean ready = SpeechTranscriber.isModelReady(act);
        lowDataSwitch.setChecked(on);
        if (!on) {
            lowDataStatus.setText("Off — hold to talk sends voice bursts as usual.");
            modelBlock.setVisibility(GONE);
            return;
        }
        if (ready) {
            lowDataStatus.setText("On — hold to talk, then check the words "
                    + "before sending. Only the text goes out.");
            modelBlock.setVisibility(GONE);
        } else {
            lowDataStatus.setText("On, but the speech model isn't downloaded "
                    + "yet — hold to talk is paused until it is.");
            modelBlock.setVisibility(VISIBLE);
            if (!downloadingModel) {
                modelStatus.setText("The speech model is "
                        + SpeechTranscriber.MODEL_DISPLAY_SIZE
                        + ", downloaded once, and works offline after that. "
                        + "It stays on this phone.");
                modelProgress.setVisibility(GONE);
                modelButton.setText("Download the speech model ("
                        + SpeechTranscriber.MODEL_DISPLAY_SIZE + ")");
                modelButton.setEnabled(true);
            }
        }
    }

    private void onLowDataToggled(boolean on) {
        Prefs.get(act).edit().putBoolean(Prefs.KEY_LOWDATA_ON, on).apply();
        refreshLowDataBlock();
    }

    private void onModelButton() {
        if (downloadingModel) {
            transcriber.cancelDownload();
            return;
        }
        downloadingModel = true;
        modelButton.setText("Cancel download");
        modelButton.setEnabled(true);
        modelProgress.setVisibility(VISIBLE);
        modelProgress.setProgress(0);
        modelStatus.setText("Downloading the speech model…");
        transcriber.downloadModel(act, new SpeechTranscriber.DownloadCb() {
            @Override public void onProgress(long done, long total) {
                int pct = total > 0 ? (int) (done * 1000 / total) : 0;
                modelProgress.setProgress(Math.min(1000, pct));
                modelStatus.setText("Downloading the speech model… "
                        + (done / 1048576) + " MB of " + (total / 1048576) + " MB");
            }

            @Override public void onDone() {
                downloadingModel = false;
                modelProgress.setProgress(1000);
                modelStatus.setText("Speech model ready — low-data mode works offline now.");
                Toast.makeText(act, "Speech model downloaded. Low-data mode is on.",
                        Toast.LENGTH_LONG).show();
                refreshLowDataBlock();
            }

            @Override public void onError(String reason) {
                downloadingModel = false;
                modelProgress.setVisibility(GONE);
                modelStatus.setText("Download failed: " + reason);
                modelButton.setText("Try the download again");
                modelButton.setEnabled(true);
            }
        });
    }

    // ------------------------------------------------------------ channel

    private void updateReadout(int number) {
        JSONObject s = Profile.slot(act, number);
        bigNumber.setText(String.valueOf(number));
        readoutFreq.setText(TunerView.frequencyText(number));
        String label = s.optString("label", "");
        readoutLabel.setText(label.isEmpty() ? "—" : label);
        modeLine.setText(Profile.isPrivate(s)
                ? "Private — members with the channel key only"
                : "Open — anyone nearby on channel " + number);
    }

    /**
     * Tunes to a channel from outside the page (v1.9: the circle
     * menu's Walkie sub-ring). The same two calls the tuner's own
     * settled path and the heard-invite path make — tuner display
     * first, then the rebind that saves the preference and reloads
     * the channel's thread and sessions.
     */
    public void tuneToChannel(int number) {
        if (number < 1 || number > Profile.BANK_SIZE) return;
        tuner.tuneTo(number, false);
        retune(number);
    }

    private void retune(int number) {
        stopScan();
        bindChannel(number);
    }

    private void bindChannel(int number) {
        channelNumber = number;
        Prefs.get(act).edit().putInt(ChannelsActivity.PREF_LAST_CHANNEL, number).apply();
        slot = Profile.slot(act, number);
        identity = Profile.slotIdentity(slot);
        // The relay channel id is derived per PROTOCOL §5 — a
        // different fingerprint from the local slot identity (see
        // RelayIds). Moved slots speak with their ORIGINAL number.
        relayChannelId = RelayIds.walkieChannelId(
                RelayIds.wireNumber(number, slot.optString("wireId", "")),
                Profile.isPrivate(slot), slot.optString("key", ""));
        relayKeyBytes = Profile.isPrivate(slot)
                ? RelayIds.decodeKey(slot.optString("key", "")) : null;
        stopLocalSessions();
        updateReadout(number);
        refreshChannelAction();
        refreshPlayBlock();
        reloadThread();
        seenCmids.clear();
        // Seed from the WHOLE store, not just the thread: played
        // entries live in History now, and their cmids must still
        // suppress re-ingest on a retune (v1.18).
        for (JSONObject e : WalkieStore.thread(act, identity)) {
            String cmid = e.optString("cmid", "");
            if (!cmid.isEmpty()) seenCmids.add(cmid);
        }
        statusText.setText(channelStatusLine());
        talkerText.setText("");
        membersText.setText("");
        syncRelayMembers();
        refreshSubscriptionSection();
        refreshChannelsButton();
        tryFlushOutbox();
    }

    /** Private channels: membership is a signed relay member-add
     *  per member contact carrying a relay key (PROTOCOL §7.3)
     *  — see RelayMembership, which the subscription service
     *  shares. Silent and idempotent; it also creates the
     *  channel the first time. */
    private void syncRelayMembers() {
        RelayMembership.sync(act, slot, relayChannelId);
    }

    private String channelStatusLine() {
        if (Profile.isPrivate(slot)) {
            return "Private channel. Voice and text are sealed with the "
                    + "channel key on every road — the local sessions and "
                    + "the relay alike; the relay only ever stores sealed "
                    + "blobs.";
        }
        return "Open channel — anyone nearby on channel " + channelNumber
                + " can listen. Not encrypted.";
    }

    // ---- private channels: first-class flow on the radio ----

    private void refreshChannelAction() {
        if (channelActionBtn == null) return;
        JSONObject s = Profile.slot(act, channelNumber);
        channelActionBtn.setText(Profile.isPrivate(s)
                ? "Share join sound" : "Make this channel private");
    }

    private void onChannelAction() {
        JSONObject s = Profile.slot(act, channelNumber);
        if (Profile.isPrivate(s)) {
            openFullInvite();
            return;
        }
        EditText input = Ui.input(act, "Label (optional), e.g. Worksite");
        input.setText(s.optString("label", ""));
        new AlertDialog.Builder(act)
                .setTitle("Make channel " + channelNumber + " private")
                .setMessage("A channel key is generated on this phone. Only "
                        + "devices with the key can join — and the join sound "
                        + "plays from the bottom of the radio, ready to "
                        + "share.")
                .setView(input)
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Make private", (d, w) -> {
                    try {
                        JSONObject ns = Profile.slot(act, channelNumber);
                        ns.put("label", input.getText().toString().trim());
                        ns.put("mode", "private");
                        if (ns.optString("key", "").isEmpty()) {
                            ns.put("key", Profile.newChannelKey());
                        }
                        Profile.saveSlot(act, ns);
                    } catch (Exception ignored) {
                    }
                    bindChannel(channelNumber);
                    openFullInvite();
                })
                .show();
    }

    private void openFullInvite() {
        act.startActivity(AudioKeyPlayActivity.channelIntent(
                act, Profile.slot(act, channelNumber)));
    }

    /** Refresh the bottom join-sound block for the tuned channel:
     *  private → the play affordance; open → no block. */
    private void refreshPlayBlock() {
        if (playBlock == null) return;
        JSONObject s = Profile.slot(act, channelNumber);
        if (!Profile.isPrivate(s)) {
            stopJoinPlay();
            playBlock.setVisibility(GONE);
            playForIdentity = "";
            return;
        }
        String cacheKey = Profile.slotIdentity(s) + "|" + s.optString("label", "");
        if (!cacheKey.equals(playForIdentity)) {
            stopJoinPlay();
            playCaption.setText("Channel " + channelNumber
                    + " join sound — tap to play it for the other phone.\n"
                    + "This sound IS the key: anyone who hears it can join this channel.");
            playJoinButton.setText("Play join sound");
            playProgress.setProgress(0);
            playProgress.setVisibility(GONE);
            playForIdentity = cacheKey;
        }
        playBlock.setVisibility(VISIBLE);
    }

    /** Tap on the pinned block: play the tuned channel's join sound
     *  right here (tap again while playing to stop). */
    private void onPlayJoinTapped() {
        if (joinPlaying) {
            stopJoinPlay();
            playCaption.setText("Stopped. Tap to play the join sound again.");
            return;
        }
        JSONObject s = Profile.slot(act, channelNumber);
        byte[] payload = AudioKeyPayload.channel(channelNumber, true,
                s.optString("label", ""), s.optString("key", ""));
        short[] pcm = payload == null ? null : AudioModem.encode(payload);
        if (pcm == null) {
            Toast.makeText(act, "Couldn't build the join sound.",
                    Toast.LENGTH_LONG).show();
            return;
        }
        joinPlaying = true;
        playJoinButton.setText("Stop");
        playProgress.setVisibility(VISIBLE);
        playProgress.setProgress(0);
        playCaption.setText("Playing… hold the phones near each other.");
        joinPlayer.play(pcm, new AudioKeyAudio.Player.Cb() {
            @Override public void onProgress(float frac) {
                playProgress.setProgress(Math.round(frac * 1000));
            }

            @Override public void onDone() {
                joinPlaying = false;
                playJoinButton.setText("Play again");
                playProgress.setProgress(1000);
                playCaption.setText("Played. If the other phone missed it, play it again.");
                OwnLog.log(act, "Key share", "Played Channel join sound for "
                        + Profile.slotDisplayName(s) + " ("
                        + Profile.slotIdentity(s) + "), origin UI");
            }

            @Override public void onError(String message) {
                joinPlaying = false;
                playJoinButton.setText("Play join sound");
                playCaption.setText(message);
            }
        });
    }

    private void stopJoinPlay() {
        joinPlaying = false;
        joinPlayer.stop();
    }

    /** A heard key, delivered by MainActivity (the listen ran under
     *  the host activity). Joins channel invites; anything else gets
     *  a plain refusal. */
    public void handleHeardText(String text) {
        if (text == null) return;
        ChannelInvite.JoinResult r = ChannelInvite.join(act, text);
        if (r == null) {
            Toast.makeText(act, "That sound wasn't a channel invite.",
                    Toast.LENGTH_LONG).show();
            return;
        }
        Toast.makeText(act, r.message, Toast.LENGTH_LONG).show();
        if (r.joined) {
            tuner.tuneTo(r.placedNumber, false);
            retune(r.placedNumber);
        }
    }

    private void step(int delta) {
        int next = channelNumber + delta;
        if (next < 1) next = Profile.BANK_SIZE;
        if (next > Profile.BANK_SIZE) next = 1;
        tuner.tuneTo(next, true);
    }

    private void directTuneDialog() {
        EditText input = new EditText(act);
        input.setInputType(InputType.TYPE_CLASS_NUMBER);
        input.setHint("Channel 1–" + Profile.BANK_SIZE);
        input.setTextColor(Ui.TEXT);
        new AlertDialog.Builder(act)
                .setTitle("Tune to channel")
                .setView(input)
                .setPositiveButton("Tune", (d, w) -> {
                    try {
                        int n = Integer.parseInt(input.getText().toString().trim());
                        if (n >= 1 && n <= Profile.BANK_SIZE) tuner.tuneTo(n, true);
                    } catch (Exception ignored) {
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void startScan() {
        scanning = true;
        scanChannel = channelNumber;
        scanButton.setText("Stop scan");
        statusText.setText("Scanning…");
        ui.postDelayed(scanStep, 600);
    }

    private void stopScan() {
        if (!scanning) return;
        scanning = false;
        suppressRetune = false;
        ui.removeCallbacks(scanStep);
        if (scanButton != null) scanButton.setText("Scan");
    }

    private boolean channelHasRecentActivity(int number) {
        JSONObject s = Profile.slot(act, number);
        List<JSONObject> t = WalkieStore.thread(act, Profile.slotIdentity(s));
        if (t.isEmpty()) return false;
        long last = t.get(t.size() - 1).optLong("ts");
        return System.currentTimeMillis() - last < 5 * 60 * 1000L;
    }

    // ------------------------------------------------------------ thread

    private void reloadThread() {
        HistoryRule.split(WalkieStore.thread(act, identity),
                threadEntries, historyEntries);
        refreshLists();
    }

    private void appendEntry(JSONObject entry) {
        WalkieStore.append(act, identity, entry);
        // A new entry is always unplayed, so it always lands in
        // the thread (own entries stay there permanently).
        threadEntries.add(entry);
        refreshLists();
    }

    /** One refresh for both lists, the History count, and the
     *  thread's empty hint — every mutation path goes through
     *  here so the two lists can never disagree with the store. */
    private void refreshLists() {
        threadAdapter.notifyDataSetChanged();
        if (historyAdapter != null) historyAdapter.notifyDataSetChanged();
        refreshHistoryButton();
        refreshThreadHint();
    }

    private void refreshHistoryButton() {
        if (historyButton == null) return;
        int n = historyEntries.size();
        historyButton.setText(n > 0 ? "History (" + n + ")" : "History");
    }

    private void refreshThreadHint() {
        if (threadEmptyHint == null) return;
        threadEmptyHint.setVisibility(
                threadEntries.isEmpty() ? VISIBLE : GONE);
    }

    /**
     * Moves an entry from the thread to History after a COMPLETED
     * playback. Called only from playback-completion callbacks
     * (TTS onDone, burst play-through) — never from a gated,
     * failed, or interrupted playback. The move is a state change
     * on the live entry object (HistoryRule.markPlayed) plus a
     * persist, so the entry can neither duplicate nor vanish; the
     * in-memory lists follow only when this channel is the tuned
     * one (an entry from another channel is persisted, and the
     * split picks it up when that channel is tuned). Safe to call
     * from any thread: the store write happens here, the list
     * move on the UI thread.
     */
    private void markEntryPlayed(final JSONObject entry) {
        if (!HistoryRule.markPlayed(entry)) return;
        WalkieStore.saveNow(act);
        ui.post(() -> {
            if (!threadEntries.remove(entry)) return;
            insertHistorySorted(entry);
            refreshLists();
        });
    }

    /** History stays in time order even when playbacks complete
     *  out of order (a manual replay can overtake an auto-read). */
    private void insertHistorySorted(JSONObject entry) {
        long ts = entry.optLong("ts");
        int at = historyEntries.size();
        for (int i = 0; i < historyEntries.size(); i++) {
            if (historyEntries.get(i).optLong("ts") > ts) {
                at = i;
                break;
            }
        }
        historyEntries.add(at, entry);
    }

    /** Marks a thread entry as a low-data message (persisted with
     *  the entry, so reloads keep the marker and the replay). */
    private static void markLowData(JSONObject entry) {
        try {
            entry.put("lowData", true);
        } catch (Exception ignored) {
        }
    }

    /** Reads a received walkie message aloud in the sender's
     *  voice profile when the gate allows (read-aloud on, not
     *  QUIET). Since v1.17 this runs for EVERY received text on
     *  every road, not only low-data ones. The text is in the
     *  thread regardless — speech is an enhancement, and a gated
     *  or engine-less speak is silent by design. senderPub is the
     *  relay pubkey when known (relay road); mesh/Bluetooth
     *  senders resolve by display name. Callers pass only other
     *  people's messages: the mesh/Bluetooth listeners never carry
     *  own traffic, and the relay path checks !mine. Since v1.18
     *  the entry moves to History when the utterance FINISHES —
     *  the callback runs only on completion, never on a gate, an
     *  error, or an interruption. */
    private void maybeSpeak(String fromName, String senderPub, String text,
                            JSONObject entry) {
        VoiceProfile profile = senderPub != null && !senderPub.isEmpty()
                ? Profile.voiceForSenderKey(act, senderPub)
                : Profile.voiceForSenderName(act, fromName);
        speaker.speak(act, text, profile, false,
                () -> markEntryPlayed(entry));
    }

    /** Manual replay: tapping a received text entry reads it aloud
     *  in the sender's profile. Bypasses the read-aloud switch,
     *  never QUIET — and says so when QUIET is the reason nothing
     *  happened. A replay that runs to completion also counts as
     *  heard (v1.18): a thread entry moves to History; a History
     *  entry stays put (markEntryPlayed is a no-op on it). */
    private void replayEntry(JSONObject entry) {
        String senderPub = entry.optString("sender", "");
        VoiceProfile profile = !senderPub.isEmpty()
                ? Profile.voiceForSenderKey(act, senderPub)
                : Profile.voiceForSenderName(act, entry.optString("from", ""));
        LowDataSpeaker.Outcome o = speaker.speak(act, entry.optString("text", ""),
                profile, true, () -> markEntryPlayed(entry));
        if (o == LowDataSpeaker.Outcome.QUIET) {
            Toast.makeText(act, "Quiet is on, so nothing is read aloud.",
                    Toast.LENGTH_LONG).show();
        } else if (o == LowDataSpeaker.Outcome.NO_ENGINE) {
            Toast.makeText(act, "This phone has no speech engine.",
                    Toast.LENGTH_LONG).show();
        }
    }

    private class ThreadAdapter extends BaseAdapter {
        @Override public int getCount() { return threadEntries.size(); }
        @Override public Object getItem(int pos) { return threadEntries.get(pos); }
        @Override public long getItemId(int pos) { return pos; }

        @Override
        public View getView(int pos, View convert, ViewGroup parent) {
            return entryRow(threadEntries.get(pos), false);
        }
    }

    /** History rows render exactly like thread rows (same sender,
     *  time, low-data marker, and content) — only the tap hint
     *  differs, since the message has already been heard once. */
    private class HistoryAdapter extends BaseAdapter {
        @Override public int getCount() { return historyEntries.size(); }
        @Override public Object getItem(int pos) { return historyEntries.get(pos); }
        @Override public long getItemId(int pos) { return pos; }

        @Override
        public View getView(int pos, View convert, ViewGroup parent) {
            return entryRow(historyEntries.get(pos), true);
        }
    }

    /** One row renderer for both lists. inHistory only changes the
     *  tap hint ("hear it again" vs "hear it read aloud"). */
    private View entryRow(JSONObject e, boolean inHistory) {
        boolean mine = e.optBoolean("mine");
        LinearLayout rowLayout = Ui.column(act);
        rowLayout.setPadding(Ui.dp(act, 10), Ui.dp(act, 6),
                Ui.dp(act, 10), Ui.dp(act, 6));
        rowLayout.setBackground(Ui.roundedStroke(act,
                mine ? Ui.SURFACE2 : Ui.PANEL, 12, Ui.LINE));
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rlp.setMargins(0, Ui.dp(act, 3), 0, Ui.dp(act, 3));
        rowLayout.setLayoutParams(rlp);
        String head = e.optString("from") + "  ·  "
                + DateFormat.getTimeInstance(DateFormat.SHORT)
                .format(new Date(e.optLong("ts")));
        if ("queued".equals(e.optString("state"))) head += "  ·  queued";
        if (e.optBoolean("lowData", false)) head += "  ·  low-data";
        rowLayout.addView(Ui.text(act, head, 11, Ui.DIM));
        if ("voice".equals(e.optString("kind"))) {
            int secs = Math.max(1, e.optInt("samples") / SAMPLE_RATE);
            rowLayout.addView(Ui.text(act,
                    "▶ Voice burst · " + secs + "s (tap to play"
                            + (inHistory ? " again" : "") + ")", 15, Ui.TEXT));
        } else {
            rowLayout.addView(Ui.text(act, e.optString("text"), 15, Ui.TEXT));
            if (!mine) {
                rowLayout.addView(Ui.text(act,
                        inHistory
                                ? "Tap to hear it again in the sender's voice profile."
                                : "Tap to hear it read aloud in the sender's voice profile.",
                        11, Ui.DIM));
            }
        }
        return rowLayout;
    }

    /** The per-channel History list (v1.18): played received
     *  messages for the TUNED channel, replayable in place.
     *  Replaying from History moves nothing — the entries are
     *  already played and stay in History. The list is rebuilt
     *  from the store on every open so it can never show stale
     *  state after a retune or a background move. */
    private void showHistoryDialog() {
        HistoryRule.split(WalkieStore.thread(act, identity),
                threadEntries, historyEntries);
        refreshLists();
        LinearLayout box = Ui.column(act);
        box.setPadding(Ui.dp(act, 12), Ui.dp(act, 8),
                Ui.dp(act, 12), Ui.dp(act, 8));
        if (historyEntries.isEmpty()) {
            box.addView(Ui.body(act,
                    "No heard messages yet on channel " + channelNumber + ". "
                    + "Messages move here after you have heard them."));
        } else {
            ListView list = new ListView(act);
            list.setDivider(null);
            list.setAdapter(historyAdapter);
            list.setOnItemClickListener((p, v, pos, id) -> {
                JSONObject entry = historyEntries.get(pos);
                if ("voice".equals(entry.optString("kind"))) {
                    playEntry(entry);
                } else {
                    replayEntry(entry);
                }
            });
            list.setOnItemLongClickListener((p, v, pos, id) -> {
                showEntryInfo(historyEntries.get(pos));
                return true;
            });
            box.addView(list, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(act, 400)));
        }
        new AlertDialog.Builder(act)
                .setTitle("Channel " + channelNumber + " history")
                .setView(box)
                .setPositiveButton("Close", null)
                .show();
    }

    /** The long-press detail toast, shared by the thread and
     *  History (sender, time, road, state). */
    private void showEntryInfo(JSONObject entry) {
        String road = entry.optString("road", "");
        String when = DateFormat.getTimeInstance(DateFormat.SHORT)
                .format(new Date(entry.optLong("ts")));
        Toast.makeText(act,
                entry.optString("from") + " · " + when
                        + (road.isEmpty() ? "" : " · via " + road)
                        + " · " + entry.optString("state", ""),
                Toast.LENGTH_LONG).show();
    }

    // ------------------------------------------------------------ transports

    private String sessionRoad = "Nearby mesh";
    private AudioTrack playTrack;
    private Adpcm btDecoder;
    private volatile String lanFoundName;
    private volatile InetAddress lanFoundAddr;
    private volatile long lanFoundTs;

    private String myName() {
        return Profile.displayName(act);
    }

    /** The internet road exists whenever the tuned slot maps to a
     *  relay channel (always derivable; posts are store-and-forward
     *  and failures leave texts queued / bursts local). */
    private boolean relayOnline() {
        return relayChannelId != null && !relayChannelId.isEmpty();
    }

    private void saveWalkieState(String state) {
        Prefs.get(act).edit().putString(Prefs.KEY_WALKIE_STATE, state).apply();
        OwnLog.log(act, "Walkie-talkie", state + " on channel " + channelNumber);
    }

    // ---- mesh / LAN session (MeshNet carries both) ----

    private final MeshNet.Listener meshListener = new MeshNet.Listener() {
        @Override public void onState(String state) {
            ui.post(() -> statusText.setText(state));
        }

        @Override public void onMembers(List<String> names) {
            meshLive = true;
            saveWalkieState("live via " + sessionRoad + " with " + names.size()
                    + " nearby member(s)");
            ui.post(() -> {
                membersText.setText(names.isEmpty()
                        ? "Session open — nobody else here yet."
                        : "On this channel nearby: " + String.join(", ", names));
                tryFlushOutbox();
            });
        }

        @Override public void onTalker(String name) {
            ui.post(() -> {
                if (name != null) {
                    talkerText.setText(name + " is talking…");
                    startPlayTrack();
                } else {
                    talkerText.setText("");
                    stopPlayTrack();
                }
            });
        }

        @Override public void onText(String from, String text, boolean lowData) {
            ui.post(() -> {
                JSONObject entry = WalkieStore.entry(
                        "text", from, text, "", 0, sessionRoad, false, "sent");
                if (lowData) markLowData(entry);
                appendEntry(entry);
                maybeSpeak(from, null, text, entry);
            });
        }

        @Override public void onVoice(byte[] pcm) {
            if (playTrack != null) playTrack.write(pcm, 0, pcm.length);
        }

        @Override public void onSpurtEnd() {
            ui.post(() -> {
                beep();
                talkerText.setText("");
                stopPlayTrack();
            });
        }

        @Override public void onChannel(String channelId, String channelName) {
            // The host confirms which channel this session serves; the
            // activity already keys everything off the tuned slot.
        }
    };

    private MeshNet newMeshSession(String road) {
        sessionRoad = road;
        return new MeshNet(myName(), Profile.slotPassphrase(slot), identity,
                Profile.slotDisplayName(slot), meshListener);
    }

    private void hostMesh() {
        if (!ensureP2p()) return;
        if (!ensureNearbyPermissions()) return;
        p2p.createGroup(p2pChannel, new WifiP2pManager.ActionListener() {
            @Override public void onSuccess() {
                p2p.requestConnectionInfo(p2pChannel, info -> {
                    if (info.groupFormed && info.isGroupOwner) {
                        startMeshHost("Nearby mesh");
                    } else {
                        statusText.setText("Mesh group formed — waiting to host…");
                    }
                });
            }

            @Override public void onFailure(int reason) {
                // A group may already exist — try hosting on it.
                p2p.requestConnectionInfo(p2pChannel, info -> {
                    if (info.groupFormed && info.isGroupOwner) {
                        startMeshHost("Nearby mesh");
                    } else {
                        statusText.setText("Couldn't start a mesh group (reason "
                                + reason + "). Check Wi-Fi is on.");
                    }
                });
            }
        });
    }

    private void startMeshHost(String road) {
        stopLocalSessions();
        mesh = newMeshSession(road);
        meshHost = true;
        mesh.startHost();
        meshLive = true;
        saveWalkieState("hosting live via " + road);
        statusText.setText(road + " session open on channel " + channelNumber
                + " — others can join.");
        tryFlushOutbox();
    }

    private void joinMesh() {
        if (!ensureP2p()) return;
        if (!ensureNearbyPermissions()) return;
        p2p.discoverPeers(p2pChannel, new WifiP2pManager.ActionListener() {
            @Override public void onSuccess() {
                showPeerDialog();
            }

            @Override public void onFailure(int reason) {
                statusText.setText("Peer discovery failed (reason " + reason + ").");
            }
        });
    }

    private void showPeerDialog() {
        if (peers.isEmpty()) {
            Toast.makeText(act, "Searching… no nearby devices yet — try Join again "
                    + "in a few seconds.", Toast.LENGTH_LONG).show();
            return;
        }
        String[] names = new String[peers.size()];
        for (int i = 0; i < peers.size(); i++) {
            names[i] = peers.get(i).deviceName + " (" + peers.get(i).status + ")";
        }
        new AlertDialog.Builder(act)
                .setTitle("Join which device's group?")
                .setItems(names, (d, which) -> connectToPeer(peers.get(which)))
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void connectToPeer(WifiP2pDevice device) {
        WifiP2pConfig config = new WifiP2pConfig();
        config.deviceAddress = device.deviceAddress;
        config.wps.setup = android.net.wifi.WpsInfo.PBC;
        p2p.connect(p2pChannel, config, new WifiP2pManager.ActionListener() {
            @Override public void onSuccess() {
                statusText.setText("Connecting to " + device.deviceName + "…");
            }

            @Override public void onFailure(int reason) {
                statusText.setText("Connect failed (reason " + reason + ").");
            }
        });
    }

    private void hostLan() {
        if (lanFoundAddr != null
                && System.currentTimeMillis() - lanFoundTs < 15000) {
            // A LAN host is already announcing — join it instead.
            stopLocalSessions();
            mesh = newMeshSession("LAN");
            meshHost = false;
            mesh.connectToHost(lanFoundAddr);
            statusText.setText("Joining the LAN session hosted by " + lanFoundName + "…");
            return;
        }
        stopLocalSessions();
        mesh = newMeshSession("LAN");
        meshHost = true;
        mesh.startHost();
        meshLive = true;
        saveWalkieState("hosting live via LAN");
        if (lan == null) lan = new LanDiscovery();
        lan.startAnnouncing(myName());
        statusText.setText("LAN session open on channel " + channelNumber
                + " — phones on this Wi-Fi can join.");
        tryFlushOutbox();
    }

    private void stopLocalSessions() {
        if (mesh != null) {
            mesh.shutdown();
            mesh = null;
        }
        meshLive = false;
        meshHost = false;
        if (bt != null) {
            bt.shutdown();
            bt = null;
        }
        btLive = false;
        if (lan != null) lan.stop();
        saveWalkieState("no live local session reported");
        stopPlayTrack();
    }

    // ---- Wi-Fi Direct plumbing ----

    private boolean ensureP2p() {
        if (p2p != null) return true;
        p2p = (WifiP2pManager) act.getSystemService(Context.WIFI_P2P_SERVICE);
        if (p2p == null) {
            statusText.setText("This phone has no Wi-Fi Direct.");
            return false;
        }
        p2pChannel = p2p.initialize(act, act.getMainLooper(), null);
        return true;
    }

    private boolean ensureNearbyPermissions() {
        List<String> need = new ArrayList<>();
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            if (act.checkSelfPermission(Manifest.permission.NEARBY_WIFI_DEVICES)
                    != PackageManager.PERMISSION_GRANTED) {
                need.add(Manifest.permission.NEARBY_WIFI_DEVICES);
            }
        } else if (act.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)
                != PackageManager.PERMISSION_GRANTED) {
            need.add(Manifest.permission.ACCESS_FINE_LOCATION);
        }
        if (act.checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
            need.add(Manifest.permission.RECORD_AUDIO);
        }
        if (!need.isEmpty()) {
            act.requestPermissions(need.toArray(new String[0]), 7701);
            Toast.makeText(act, "Grant the permissions, then tap again.",
                    Toast.LENGTH_LONG).show();
            return false;
        }
        return true;
    }

    private void registerP2pReceiver() {
        if (!ensureP2p() || p2pReceiver != null) return;
        IntentFilter filter = new IntentFilter();
        filter.addAction(WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION);
        filter.addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION);
        p2pReceiver = new BroadcastReceiver() {
            @Override public void onReceive(Context context, Intent intent) {
                String action = intent.getAction();
                if (WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION.equals(action)) {
                    p2p.requestPeers(p2pChannel, peerList -> {
                        peers.clear();
                        peers.addAll(peerList.getDeviceList());
                    });
                } else if (WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION.equals(action)) {
                    p2p.requestConnectionInfo(p2pChannel, info -> {
                        if (info.groupFormed && !info.isGroupOwner && mesh == null) {
                            stopLocalSessions();
                            mesh = newMeshSession("Nearby mesh");
                            meshHost = false;
                            mesh.connectToHost(info.groupOwnerAddress);
                            statusText.setText("Joined the mesh group on channel "
                                    + channelNumber + ".");
                        }
                    });
                }
            }
        };
        act.registerReceiver(p2pReceiver, filter);
        // LAN discovery listening runs for the activity's lifetime.
        if (lan == null) {
            lan = new LanDiscovery();
            lan.startListening((hostName, address) -> {
                lanFoundName = hostName;
                lanFoundAddr = address;
                lanFoundTs = System.currentTimeMillis();
            });
        }
    }

    // ---- Bluetooth ----

    private final BtWalkie.Listener btListener = new BtWalkie.Listener() {
        @Override public void onState(String state) {
            ui.post(() -> statusText.setText("Bluetooth: " + state));
        }

        @Override public void onPeer(String name) {
            btLive = true;
            saveWalkieState("live via Bluetooth with " + name);
            ui.post(() -> {
                membersText.setText("Bluetooth: paired with " + name + ".");
                tryFlushOutbox();
            });
        }

        @Override public void onSpurtStart(String name) {
            btDecoder = new Adpcm();
            ui.post(() -> {
                talkerText.setText(name + " is talking (Bluetooth burst)…");
                startPlayTrack();
            });
        }

        @Override public void onFrame(byte[] adpcmFrame) {
            if (btDecoder == null || playTrack == null) return;
            short[] pcm = btDecoder.decode(adpcmFrame, adpcmFrame.length * 2);
            byte[] bytes = new byte[pcm.length * 2];
            for (int i = 0; i < pcm.length; i++) {
                bytes[2 * i] = (byte) (pcm[i] & 0xFF);
                bytes[2 * i + 1] = (byte) ((pcm[i] >> 8) & 0xFF);
            }
            playTrack.write(bytes, 0, bytes.length);
        }

        @Override public void onSpurtEnd() {
            ui.post(() -> {
                beep();
                talkerText.setText("");
                stopPlayTrack();
            });
        }

        @Override public void onText(String from, String text, boolean lowData) {
            ui.post(() -> {
                JSONObject entry = WalkieStore.entry(
                        "text", from, text, "", 0, "Bluetooth", false, "sent");
                if (lowData) markLowData(entry);
                appendEntry(entry);
                maybeSpeak(from, null, text, entry);
            });
        }
    };

    private android.bluetooth.BluetoothAdapter btAdapter() {
        android.bluetooth.BluetoothManager mgr =
                (android.bluetooth.BluetoothManager) act.getSystemService(Context.BLUETOOTH_SERVICE);
        return mgr != null ? mgr.getAdapter() : null;
    }

    private boolean ensureBtPermissions() {
        if (android.os.Build.VERSION.SDK_INT >= 31) {
            List<String> need = new ArrayList<>();
            if (act.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT)
                    != PackageManager.PERMISSION_GRANTED) {
                need.add(Manifest.permission.BLUETOOTH_CONNECT);
            }
            if (act.checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN)
                    != PackageManager.PERMISSION_GRANTED) {
                need.add(Manifest.permission.BLUETOOTH_SCAN);
            }
            if (!need.isEmpty()) {
                act.requestPermissions(need.toArray(new String[0]), 7702);
                Toast.makeText(act, "Grant Bluetooth permission, then tap again.",
                        Toast.LENGTH_LONG).show();
                return false;
            }
        }
        return true;
    }

    private void btListen() {
        android.bluetooth.BluetoothAdapter adapter = btAdapter();
        if (adapter == null || !adapter.isEnabled()) {
            Toast.makeText(act, "Bluetooth is off.", Toast.LENGTH_LONG).show();
            return;
        }
        if (!ensureBtPermissions()) return;
        stopLocalSessions();
        bt = new BtWalkie(myName(), identity, btListener);
        bt.listen(adapter);
        statusText.setText("Bluetooth: listening for a phone on channel "
                + channelNumber + "…");
    }

    private void btConnectDialog() {
        android.bluetooth.BluetoothAdapter adapter = btAdapter();
        if (adapter == null || !adapter.isEnabled()) {
            Toast.makeText(act, "Bluetooth is off.", Toast.LENGTH_LONG).show();
            return;
        }
        if (!ensureBtPermissions()) return;
        Set<android.bluetooth.BluetoothDevice> bonded;
        try {
            bonded = adapter.getBondedDevices();
        } catch (SecurityException e) {
            Toast.makeText(act, "Bluetooth permission needed.", Toast.LENGTH_LONG).show();
            return;
        }
        if (bonded == null || bonded.isEmpty()) {
            Toast.makeText(act, "No paired phones — pair in system Settings first.",
                    Toast.LENGTH_LONG).show();
            return;
        }
        List<android.bluetooth.BluetoothDevice> devices = new ArrayList<>(bonded);
        String[] names = new String[devices.size()];
        for (int i = 0; i < devices.size(); i++) {
            String n = devices.get(i).getName();
            names[i] = n != null ? n : devices.get(i).getAddress();
        }
        new AlertDialog.Builder(act)
                .setTitle("Connect to which paired phone?")
                .setItems(names, (d, which) -> {
                    stopLocalSessions();
                    bt = new BtWalkie(myName(), identity, btListener);
                    bt.connect(adapter, devices.get(which));
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    // ------------------------------------------------------------ PTT engine

    /** The PTT disc: rest = dark green with the mockup's ring;
     *  held = accent fill with dark ink. */
    private android.graphics.drawable.GradientDrawable pttDrawable(boolean held) {
        android.graphics.drawable.GradientDrawable d =
                new android.graphics.drawable.GradientDrawable();
        d.setShape(android.graphics.drawable.GradientDrawable.OVAL);
        if (held) {
            d.setColor(Ui.ACCENT);
            d.setStroke(Ui.dp(act, 3), Ui.ACCENT);
        } else {
            d.setColor(Ui.PTT_BG);
            d.setStroke(Ui.dp(act, 3), Ui.PTT_LINE);
        }
        return d;
    }

    private void startPlayTrack() {
        stopPlayTrack();
        int min = AudioTrack.getMinBufferSize(SAMPLE_RATE,
                AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT);
        playTrack = new AudioTrack(AudioManager.STREAM_MUSIC, SAMPLE_RATE,
                AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT,
                Math.max(min, SAMPLE_RATE), AudioTrack.MODE_STREAM);
        playTrack.play();
    }

    private void stopPlayTrack() {
        if (playTrack != null) {
            try {
                playTrack.stop();
                playTrack.release();
            } catch (Exception ignored) {
            }
            playTrack = null;
        }
    }

    private void beep() {
        if (tones != null) tones.startTone(ToneGenerator.TONE_PROP_BEEP, 110);
    }

    private void startSpurt() {
        startSpurtInternal(false);
    }

    /** Low-data press-talk: records locally ONLY — nothing streams
     *  to the mesh or Bluetooth while talking. On release the audio
     *  is transcribed on-device and the user checks the words
     *  before anything goes out. */
    private void startLowDataSpurt() {
        if (recording) return;
        if (transcribing) {
            Toast.makeText(act, "Still transcribing the last one — a moment.",
                    Toast.LENGTH_SHORT).show();
            return;
        }
        if (!SpeechTranscriber.isModelReady(act)) {
            Toast.makeText(act, "Download the speech model first — it's "
                    + "in Low-data mode below.", Toast.LENGTH_LONG).show();
            refreshLowDataBlock();
            return;
        }
        startSpurtInternal(true);
    }

    private void startSpurtInternal(boolean lowData) {
        if (recording) return;
        stopScan();
        if (act.checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
            act.requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, 7703);
            Toast.makeText(act, "Microphone permission needed for push-to-talk.",
                    Toast.LENGTH_LONG).show();
            return;
        }
        recording = true;
        lowDataRecording = lowData;
        spurtPcm.reset();
        pttButton.setText(lowData ? "LISTENING" : "TALKING");
        pttButton.setTextColor(Ui.ACCENT_INK);
        pttButton.setBackground(pttDrawable(true));
        if (!lowData) {
            if (meshLive && mesh != null) mesh.beginSpurt();
            if (btLive && bt != null) bt.sendSpurtStart();
        } else {
            statusText.setText("Listening — release to transcribe on this phone.");
        }
        int min = AudioRecord.getMinBufferSize(SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
        recorder = new AudioRecord(MediaRecorder.AudioSource.MIC, SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
                Math.max(min, SAMPLE_RATE));
        recorder.startRecording();
        final Adpcm btEncoder = new Adpcm();
        recordThread = new Thread(() -> {
            short[] chunk = new short[640];
            ByteArrayOutputStream btPending = new ByteArrayOutputStream();
            while (recording) {
                int n = recorder.read(chunk, 0, chunk.length);
                if (n <= 0) continue;
                byte[] bytes = new byte[n * 2];
                for (int i = 0; i < n; i++) {
                    bytes[2 * i] = (byte) (chunk[i] & 0xFF);
                    bytes[2 * i + 1] = (byte) ((chunk[i] >> 8) & 0xFF);
                }
                synchronized (spurtPcm) {
                    spurtPcm.write(bytes, 0, bytes.length);
                }
                // Low-data mode records locally only — nothing
                // streams until the user sends the transcription.
                if (lowDataRecording) continue;
                if (meshLive && mesh != null) mesh.sendVoice(bytes);
                if (btLive && bt != null) {
                    btPending.write(bytes, 0, bytes.length);
                    if (btPending.size() >= 3200) {
                        byte[] all = btPending.toByteArray();
                        btPending.reset();
                        short[] samples = new short[1600];
                        for (int i = 0; i < 1600; i++) {
                            samples[i] = (short) ((all[2 * i] & 0xFF)
                                    | (all[2 * i + 1] << 8));
                        }
                        bt.sendFrame(btEncoder.encode(samples));
                    }
                }
            }
        }, "ptt-record");
        recordThread.start();
    }

    private void stopSpurt() {
        if (!recording) return;
        boolean wasLowData = lowDataRecording;
        recording = false;
        lowDataRecording = false;
        pttButton.setText("HOLD TO\nTALK");
        pttButton.setTextColor(Ui.ACCENT);
        pttButton.setBackground(pttDrawable(false));
        if (recorder != null) {
            try {
                recorder.stop();
                recorder.release();
            } catch (Exception ignored) {
            }
            recorder = null;
        }
        if (recordThread != null) {
            try {
                recordThread.join(400);
            } catch (Exception ignored) {
            }
            recordThread = null;
        }
        if (!wasLowData) {
            if (meshLive && mesh != null) mesh.endSpurt();
            if (btLive && bt != null) bt.sendSpurtEnd();
        }

        byte[] pcmBytes;
        synchronized (spurtPcm) {
            pcmBytes = spurtPcm.toByteArray();
        }
        int samples = pcmBytes.length / 2;
        if (samples < SAMPLE_RATE / 5) {
            Toast.makeText(act, "Too short — hold a moment longer.",
                    Toast.LENGTH_SHORT).show();
            return;
        }
        if (wasLowData) {
            transcribeSpurt(pcmBytes);
            return;
        }
        short[] pcm = new short[samples];
        for (int i = 0; i < samples; i++) {
            pcm[i] = (short) ((pcmBytes[2 * i] & 0xFF) | (pcmBytes[2 * i + 1] << 8));
        }
        byte[] adpcm = Adpcm.encodeBurst(pcm);
        List<String> roads = new ArrayList<>();
        if (meshLive) roads.add(sessionRoad);
        if (btLive) roads.add("Bluetooth");
        if (relayOnline()) roads.add("Internet");
        JSONObject entry = WalkieStore.entry("voice", myName(), "", "", samples,
                String.join(" · ", roads), true,
                roads.isEmpty() ? "on this phone only" : "sent");
        long entryTs = entry.optLong("ts");
        voiceCache.put(entryTs, adpcm);
        appendEntry(entry);
        beep();
        if (relayOnline()) postBurstToRelay(entry, adpcm, roads.size() > 1);
    }

    /** Low-data release path: transcribe on-device, then show the
     *  words for an explicit Send/Cancel — transcription errors
     *  must never auto-broadcast. Every terminal state restores
     *  the channel status line and says what happened. */
    private void transcribeSpurt(byte[] pcmBytes) {
        transcribing = true;
        statusText.setText("Transcribing on this phone…");
        transcriber.transcribe(act, pcmBytes, new SpeechTranscriber.TranscribeCb() {
            @Override public void onResult(VoskResult r) {
                transcribing = false;
                switch (r.status) {
                    case OK:
                        showLowDataSendDialog(r);
                        break;
                    case EMPTY:
                        statusText.setText(channelStatusLine());
                        Toast.makeText(act, "No speech heard — try again, "
                                + "closer to the mic.", Toast.LENGTH_LONG).show();
                        break;
                    case FAILED:
                        statusText.setText(channelStatusLine());
                        Toast.makeText(act, "Transcription failed — the "
                                + "result couldn't be read. Try again.",
                                Toast.LENGTH_LONG).show();
                        break;
                }
            }

            @Override public void onError(String reason) {
                transcribing = false;
                statusText.setText(channelStatusLine());
                Toast.makeText(act, "Transcription failed: " + reason,
                        Toast.LENGTH_LONG).show();
            }
        });
    }

    private void showLowDataSendDialog(VoskResult r) {
        statusText.setText(channelStatusLine());
        EditText input = Ui.input(act, "What you said");
        input.setSingleLine(false);
        input.setMinLines(2);
        input.setText(r.text);
        input.setSelection(r.text.length());
        String note = "";
        if (r.hasWords && r.meanConf < 0.5f) {
            note = "The transcription is uncertain — check the words before sending.\n\n";
        }
        new AlertDialog.Builder(act)
                .setTitle("Send as a low-data message?")
                .setMessage(note + "Only the text is sent. The other phone "
                        + "reads it aloud in a synthetic voice profile — "
                        + "not your real voice.")
                .setView(input)
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Send", (d, w) -> {
                    String text = input.getText().toString().trim();
                    if (!text.isEmpty()) sendChannelText(text, true);
                })
                .show();
    }

    /** Post a burst to the relay channel: the plaintext is the raw
     *  IMA-ADPCM bytes exactly as the local transports carry them
     *  (PROTOCOL §6) — sealed with the channel key on private
     *  channels, plain base64 on open ones (public by design). */
    private void postBurstToRelay(final JSONObject entry, byte[] adpcm,
                                  final boolean otherRoadsCarried) {
        final String channel = relayChannelId;
        final String cmid = java.util.UUID.randomUUID().toString();
        final String blob;
        try {
            blob = relayKeyBytes != null
                    ? RelayCrypto.seal(relayKeyBytes, adpcm)
                    : RelayKeys.b64Std(adpcm);
        } catch (Exception e) {
            return;
        }
        RelayClient.post(act, channel, cmid, blob, new RelayClient.Cb<RelayCore.PostResult>() {
            @Override public void ok(RelayCore.PostResult r) {
                try {
                    entry.put("cmid", cmid);
                } catch (Exception ignored) {
                }
                seenCmids.add(cmid);
                WalkieStore.saveNow(act);
            }

            @Override public void err(String code, String message) {
                if (!otherRoadsCarried) {
                    try {
                        entry.put("state", "on this phone only");
                        entry.put("road", "");
                    } catch (Exception ignored) {
                    }
                    WalkieStore.saveNow(act);
                    threadAdapter.notifyDataSetChanged();
                }
            }
        });
    }

    private void playEntry(JSONObject entry) {
        // v1.18: a received burst that plays through moves to
        // History; an own burst never moves (the user never
        // "receives" it). A burst with no stored audio can't play,
        // so it can't move either — the toast paths below pass no
        // callback at all.
        Runnable onDone = entry.optBoolean("mine")
                ? null : () -> markEntryPlayed(entry);
        long ts = entry.optLong("ts");
        byte[] adpcm = voiceCache.get(ts);
        if (adpcm != null) {
            playAdpcm(adpcm, entry.optInt("samples"), onDone);
            return;
        }
        // Relay bursts carry their blob on the entry itself (the relay
        // is store-and-forward, but the thread is the local record).
        String blob = entry.optString("blob", "");
        if (!blob.isEmpty()) {
            try {
                byte[] data = RelayKeys.b64StdDecode(blob);
                voiceCache.put(ts, data);
                playAdpcm(data, entry.optInt("samples"), onDone);
            } catch (Exception e) {
                Toast.makeText(act, "That burst's stored copy is unreadable.",
                        Toast.LENGTH_LONG).show();
            }
            return;
        }
        if (!entry.optString("mxc", "").isEmpty()) {
            Toast.makeText(act, "That burst rode the old Matrix internet "
                    + "path — it can't be fetched now that Control runs "
                    + "on its own relay.", Toast.LENGTH_LONG).show();
            return;
        }
        Toast.makeText(act, "That burst isn't stored on this phone.",
                Toast.LENGTH_LONG).show();
    }

    private void playAdpcm(byte[] adpcm, int samples, Runnable onDone) {
        new Thread(() -> {
            short[] pcm = Adpcm.decodeBurst(adpcm, samples > 0 ? samples : adpcm.length * 2);
            byte[] bytes = new byte[pcm.length * 2];
            for (int i = 0; i < pcm.length; i++) {
                bytes[2 * i] = (byte) (pcm[i] & 0xFF);
                bytes[2 * i + 1] = (byte) ((pcm[i] >> 8) & 0xFF);
            }
            int min = AudioTrack.getMinBufferSize(SAMPLE_RATE,
                    AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT);
            AudioTrack track = new AudioTrack(AudioManager.STREAM_MUSIC, SAMPLE_RATE,
                    AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT,
                    Math.max(min, SAMPLE_RATE), AudioTrack.MODE_STREAM);
            track.play();
            track.write(bytes, 0, bytes.length);
            try {
                Thread.sleep(bytes.length / (SAMPLE_RATE * 2) * 1000L + 250);
            } catch (Exception ignored) {
            }
            track.stop();
            track.release();
            // Played through to the end: the burst counts as heard
            // (v1.18). This player has no stop path, so reaching
            // here means the whole burst played; markEntryPlayed
            // handles its own threading.
            if (onDone != null) onDone.run();
        }, "ptt-play").start();
    }

    // ------------------------------------------------------------ text

    private void sendChannelText(String text) {
        sendChannelText(text, false);
    }

    /** Typed texts are ordinary (lowData=false); transcribed
     *  low-data sends carry the marker on every road, and the
     *  thread entry keeps it so the marker survives reloads. */
    private void sendChannelText(String text, boolean lowData) {
        if (meshLive && mesh != null) {
            mesh.sendText(text, lowData);
            JSONObject entry = WalkieStore.entry("text", myName(), text, "", 0,
                    sessionRoad, true, "sent");
            if (lowData) markLowData(entry);
            appendEntry(entry);
            return;
        }
        if (btLive && bt != null) {
            bt.sendText(text, lowData);
            JSONObject entry = WalkieStore.entry("text", myName(), text, "", 0,
                    "Bluetooth", true, "sent");
            if (lowData) markLowData(entry);
            appendEntry(entry);
            return;
        }
        JSONObject entry = WalkieStore.entry("text", myName(), text, "", 0,
                "", true, "queued");
        if (lowData) markLowData(entry);
        appendEntry(entry);
        WalkieStore.queueText(act, identity, entry.optLong("ts"), text, lowData);
        if (relayOnline()) sendTextViaRelay(entry, text, lowData);
    }

    /** Walkie texts ride the relay as a tiny JSON envelope inside the
     *  sealed/plaintext payload ({"t":"text",…}) so a receiver can
     *  tell text from raw-ADPCM bursts; bursts stay raw per §6.
     *  Low-data texts add "ld":true (LowDataEnvelope) — old builds
     *  ignore the unknown key and show the text as usual. */
    private void sendTextViaRelay(final JSONObject entry, String text, boolean lowData) {
        final String channel = relayChannelId;
        final String cmid = java.util.UUID.randomUUID().toString();
        final String blob;
        try {
            byte[] plain = LowDataEnvelope.build(text, lowData)
                    .getBytes(java.nio.charset.StandardCharsets.UTF_8);
            blob = relayKeyBytes != null
                    ? RelayCrypto.seal(relayKeyBytes, plain)
                    : RelayKeys.b64Std(plain);
        } catch (Exception e) {
            return;
        }
        RelayClient.post(act, channel, cmid, blob, new RelayClient.Cb<RelayCore.PostResult>() {
            @Override public void ok(RelayCore.PostResult r) {
                try {
                    entry.put("state", "sent");
                    entry.put("road", "Internet");
                    entry.put("cmid", cmid);
                } catch (Exception ignored) {
                }
                seenCmids.add(cmid);
                WalkieStore.removeQueued(act, identity, entry.optLong("ts"));
                WalkieStore.saveNow(act);
                threadAdapter.notifyDataSetChanged();
            }

            @Override public void err(String code, String message) {
                // stays queued; the next open path flushes it
            }
        });
    }

    private void tryFlushOutbox() {
        List<JSONObject> queued = WalkieStore.outbox(act, identity);
        if (queued.isEmpty()) return;
        for (JSONObject q : queued) {
            String text = q.optString("text", "");
            long ts = q.optLong("ts");
            boolean lowData = q.optBoolean("ld", false);
            if (meshLive && mesh != null) {
                mesh.sendText(text, lowData);
                markQueuedSent(ts, sessionRoad);
                WalkieStore.removeQueued(act, identity, ts);
            } else if (btLive && bt != null) {
                bt.sendText(text, lowData);
                markQueuedSent(ts, "Bluetooth");
                WalkieStore.removeQueued(act, identity, ts);
            } else if (relayOnline()) {
                // find the matching thread entry and send via the relay
                for (JSONObject e : threadEntries) {
                    if (e.optLong("ts") == ts && "text".equals(e.optString("kind"))) {
                        sendTextViaRelay(e, text, lowData);
                        break;
                    }
                }
            }
        }
    }

    private void markQueuedSent(long ts, String road) {
        for (JSONObject e : threadEntries) {
            if (e.optLong("ts") == ts && "queued".equals(e.optString("state"))) {
                try {
                    e.put("state", "sent");
                    e.put("road", road);
                } catch (Exception ignored) {
                }
            }
        }
        WalkieStore.saveNow(act);
        threadAdapter.notifyDataSetChanged();
    }

    // ------------------------------------------------------------ relay loop

    /** Poll the tuned channel's relay feed every ~4s while the page
     *  exists: pulls after the persisted per-channel cursor, ingests
     *  new messages into the thread. Store-and-forward: anything
     *  posted while this phone was away arrives on the next poll. */
    private void startRelayLoop() {
        if (relayRunning) return;
        relayRunning = true;
        final int gen = ++relayGen;
        ui.postDelayed(() -> relayTick(gen), 1200);
    }

    private void relayTick(final int gen) {
        if (!relayRunning || gen != relayGen) return;
        RelayClient.ensureRegistered(act);
        final String channel = relayChannelId;
        if (channel == null || channel.isEmpty()) {
            ui.postDelayed(() -> relayTick(gen), 4000);
            return;
        }
        // Single-poller rule (v1.19): the subscription service
        // owns polling for every subscribed channel, including
        // the tuned one. This loop polls the tuned channel
        // only while it is NOT subscribed — or while the
        // service is down, in which case this loop is the
        // backstop (and reconcile() tries to bring the
        // service back). Either way the outbox still flushes:
        // a subscribed channel's queued texts must not wait
        // for a retune. Both pollers share the persisted
        // cursor and the store-backed cmid dedup, so a
        // handoff tick can never deliver a duplicate.
        boolean subscribed = ChannelSubscriptions.isSubscribed(act, channelNumber);
        if (subscribed && SubscriptionService.serviceAlive) {
            tryFlushOutbox();
            ui.postDelayed(() -> relayTick(gen), 4000);
            return;
        }
        if (subscribed) SubscriptionService.reconcile(act);
        long after = RelayClient.cursor(act, channel);
        RelayClient.pull(act, channel, after, 200, new RelayClient.Cb<RelayCore.PullResult>() {
            @Override public void ok(RelayCore.PullResult r) {
                RelayClient.setCursor(act, channel, r.nextAfter);
                if (channel.equals(relayChannelId)) {
                    ingestRelay(r);
                    tryFlushOutbox(); // membership may have landed since queueing
                }
                ui.postDelayed(() -> relayTick(gen), 4000);
            }

            @Override public void err(String code, String message) {
                if ("unregistered_device".equals(code)) {
                    RelayClient.register(act, new RelayClient.Cb<Boolean>() {
                        @Override public void ok(Boolean created) { }
                        @Override public void err(String c2, String m2) { }
                    });
                }
                ui.postDelayed(() -> relayTick(gen), 6000);
            }
        });
    }

    private void ingestRelay(RelayCore.PullResult r) {
        String myPub = RelayClient.myPub(act);
        for (RelayCore.Msg m : r.messages) {
            if (!m.clientMsgId.isEmpty() && !seenCmids.add(m.clientMsgId)) continue;
            // The store-backed check is the cross-poller
            // backstop (v1.19): the subscription service may
            // already have stored this message on a handoff
            // tick, and the in-memory set alone cannot know.
            if (WalkieStore.hasCmid(act, identity, m.clientMsgId)) continue;
            // Decode and entry-shaping are shared with the
            // subscription service (RelayIngest): text rides
            // in a {"t":"text"} envelope, anything else is a
            // raw ADPCM burst (PROTOCOL §6). Low-data texts
            // carry "ld":true and keep their thread label;
            // since v1.17 EVERY received text is read aloud
            // in the sender's profile, low-data or typed —
            // own messages never speak. A blob we can't open
            // (wrong key, someone else's sealed traffic on an
            // open channel) decodes to null: not ours to show.
            RelayIngest.Decoded d = RelayIngest.decode(relayKeyBytes, m.ciphertext);
            if (d == null) continue;
            boolean mine = myPub.equals(m.sender);
            String from = mine ? myName() : ContactCard.senderName(act, m.sender);
            if (d.isText) {
                JSONObject entry = WalkieStore.entry("text", from, d.text, "", 0,
                        "Internet", mine, "sent");
                RelayIngest.stampEntry(entry, m, d);
                appendEntry(entry);
                if (!mine) {
                    maybeSpeak(from, m.sender, d.text, entry);
                }
            } else {
                JSONObject entry = WalkieStore.entry("voice", from, "", "",
                        d.samples, "Internet", mine, "sent");
                RelayIngest.stampEntry(entry, m, d);
                voiceCache.put(entry.optLong("ts"), d.plain);
                appendEntry(entry);
            }
        }
    }
}
