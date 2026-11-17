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
import android.text.TextUtils;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.PopupMenu;
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
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * The walkie-talkie: one push-to-talk engine over every road that
 * reaches the channel — Internet (the Control Relay), nearby mesh
 * (Wi-Fi Direct), plain LAN Wi-Fi, and Bluetooth — with numbered
 * channel slots tuned by a radio dial, and text riding the same
 * roads, store-and-forward.
 */
public class WalkiePage extends LinearLayout implements PagerLayout.PagerPage {

    private final Activity act;
    private View tunerAnchor;
    private View pttAnchor;


    private static final int SAMPLE_RATE = 16000;

    private int channelNumber;
    private JSONObject slot;
    private String identity;

    private TextView bigNumber;
    private TextView readoutFreq;
    private TextView readoutLabel;
    // v1.23: the display window's indicators row — road/signal,
    // mode, and the unplayed count for the tuned channel.
    private TextView roadText;
    private TextView modeText;
    private TextView badgeText;
    private TunerView tuner;
    private TextView statusText;
    private TextView talkerText;
    private TextView membersText;
    private Button pttButton;
    private Button scanButton;
    private Button messagesButton;
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
    private ListView threadList;
    private EditText composer;
    // v1.20: the road sections (mesh/LAN, internet, Bluetooth) live
    // in a hidden holder and are shown one at a time in a dialog, so
    // the page itself stays fixed-height with no page scroll.
    // v1.23: the messages panel and the radio menu live the same
    // way — built once, shown in a dialog, returned to the holder
    // on dismiss — so the radio face shows only the essentials.
    private LinearLayout sectionHolder;
    private LinearLayout panelHolder;
    private View messagesPanelView;
    private View menuPanelView;
    private AlertDialog panelDialog;
    private View shownPanel;
    private View localSectionView;
    private View internetSectionView;
    private View bluetoothSectionView;
    private AlertDialog sectionDialog;
    private View shownSection;
    private final Handler ui = new Handler(Looper.getMainLooper());

    // recording / playback
    private volatile boolean recording;
    private AudioRecord recorder;
    private Thread recordThread;
    private final ByteArrayOutputStream spurtPcm = new ByteArrayOutputStream();
    private final Map<Long, byte[]> voiceCache = new HashMap<>();
    private ToneGenerator tones;

    // low-data mode (v1.11): on-device transcription + TTS voice profiles
    // v1.23: the mode is a three-way choice (Auto / Voice / Text) and
    // the old low-data switch is the Text choice. Auto measures the
    // connection with a timed relay round trip (see WalkieMode) and
    // picks TEXT on a slow link, VOICE on a fast one; manual choices
    // always win. The effective mode feeds the existing low-data
    // path — LowDataSpeaker, the envelope, the transcription dialog
    // — unchanged; lowDataOn() below is the single seam.
    private SpeechTranscriber transcriber;
    private LowDataSpeaker speaker;
    private boolean lowDataRecording;
    private volatile boolean transcribing;
    private Button modeAutoBtn;
    private Button modeVoiceBtn;
    private Button modeTextBtn;
    private Switch autoReadSwitch;
    private TextView lowDataStatus;
    private TextView readAloudStatus;
    // v1.20: switch-row statuses ride inline, single-line and
    // ellipsized; the full text lives here for the info dialog.
    private String lowDataStatusFull = "";
    private String readAloudStatusFull = "";
    private String subscribeStateFull = "";
    private LinearLayout modelBlock;
    private TextView modelStatus;
    private ProgressBar modelProgress;
    private Button modelButton;
    private boolean downloadingModel;

    // Automatic mode (v1.23): the decision in force, the last
    // probe's outcome, and the single-flight guard. The probe
    // runs on entering the page, on road change, and once a
    // minute while idle — never during a transmission.
    private WalkieMode.Mode effectiveMode = WalkieMode.Mode.VOICE;
    private WalkieMode.Probe lastProbe = WalkieMode.Probe.NONE;
    private long lastRttMs;
    private boolean probing;
    private boolean pageDead;
    private int lastMemberCount;
    private String lastBtPeer = "";

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

    // cross-transport bridging (v1.24): received messages pass
    // live to the other connected roads. The switch defaults
    // ON; off restores the pre-v1.24 behavior exactly. The
    // in-flight flags enforce single-flight per transport: a
    // forward never starts while that road's previous forward
    // is still running, and a skipped forward is never
    // retried — bridging is live only, never queued. Local
    // forwards run on the bridge pool, never on the calling
    // thread (socket writes must not stall the UI or a
    // transport reader); relay forwards use RelayClient's own
    // pool, with the flag cleared in the post callback.
    private Switch bridgeSwitch;
    private TextView bridgeState;
    private String bridgeStateFull = "";
    private static final ExecutorService BRIDGE_POOL =
            Executors.newCachedThreadPool();
    private final AtomicBoolean relayBridgeBusy = new AtomicBoolean();
    private final AtomicBoolean meshBridgeBusy = new AtomicBoolean();
    private final AtomicBoolean btBridgeBusy = new AtomicBoolean();

    // Inbound live-burst capture for bridging: mesh and
    // Bluetooth voice streams to the speaker with no thread
    // record, so a burst worth bridging is accumulated as it
    // plays and forwarded when the spurt ends. Each road has
    // its own lock: the spurt-start, chunk, and spurt-end
    // callbacks run on transport reader threads, and a
    // back-to-back spurt from another member must never
    // corrupt the snapshot — at worst one burst goes
    // unbridged (it still played live). The cap is 60 seconds
    // of audio; a longer spurt plays live but is not bridged,
    // rather than bridged truncated.
    private static final int MAX_BRIDGE_SPURT_PCM = 60 * SAMPLE_RATE * 2;
    private static final int MAX_BRIDGE_SPURT_ADPCM = 60 * SAMPLE_RATE / 2;
    private final Object meshSpurtLock = new Object();
    private final ByteArrayOutputStream meshSpurtPcm = new ByteArrayOutputStream();
    private String meshSpurtTalker = "";
    private BridgeRule.Relayed meshSpurtMeta;
    private String meshSpurtMid = "";
    private boolean meshSpurtOverflow;
    private final Object btSpurtLock = new Object();
    private final ByteArrayOutputStream btSpurtAdpcm = new ByteArrayOutputStream();
    private String btSpurtTalker = "";
    private BridgeRule.Relayed btSpurtMeta;
    private String btSpurtMid = "";
    private boolean btSpurtOverflow;
    // The message id of the spurt THIS phone is sending (v1.24):
    // minted at press time, carried on the local roads, and
    // reused as the relay post's client message id, so the
    // originator's own post and any gatewayed copy share one
    // bridge id and receivers land the burst once.
    private String spurtMid = "";

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
        // The effective mode is decided before the UI builds:
        // the mode section renders from it. No stored choice
        // means Auto (v1.24 default); with no probe yet the
        // decision reads as Voice until the first measurement.
        effectiveMode = WalkieMode.decide(modeChoice(), WalkieMode.Mode.VOICE,
                false, WalkieMode.Probe.NONE, 0);
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
        refreshModeSection();
        refreshSubscriptionSection();
        refreshBridgeSection();
        refreshChannelsButton();
        refreshFaceIndicators();
        refreshModeLine();
        // Relay arrivals the subscription service stored while
        // this page was away get their one bridge decision now
        // (v1.24): the local sessions live on this page, so
        // this is the first moment they can be forwarded. The
        // thread reloads first, so the scan and the lists see
        // the same entries.
        reloadThread();
        bridgePendingRelayEntries();
        // Coming onto the page is a probe trigger (v1.23): the
        // connection is tested now, and once a minute while the
        // page stays shown and idle.
        maybeProbe();
        ui.removeCallbacks(probeTick);
        ui.postDelayed(probeTick, WalkieMode.PROBE_INTERVAL_MS);
        // The subscription service is the poller for
        // subscribed channels; coming forward reconciles it
        // back to life if Samsung killed it.
        SubscriptionService.reconcile(act);
    }

    @Override
    public void onPageHidden() {
        stopJoinPlay();
        ui.removeCallbacks(probeTick);
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
                    if (id != null && id.equals(identity)) {
                        reloadThread();
                        // A background arrival on the tuned
                        // channel gets its one bridge decision
                        // now (v1.24) — the service stores it,
                        // this page forwards it to the local
                        // roads, which live here.
                        bridgePendingRelayEntries();
                    }
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
        pageDead = true;
        ui.removeCallbacks(probeTick);
        stopJoinPlay();
        relayRunning = false;
        relayGen++;
        stopLocalSessions();
        if (lan != null) lan.stop();
        if (tones != null) tones.release();
        if (transcriber != null) transcriber.shutdown();
        if (speaker != null) speaker.shutdown();
    }

    /** The channel knob and the hold-to-talk button own their
     *  touches; everything else on the face lets the pager take
     *  a horizontal swipe. The message thread and the composer
     *  live in the messages panel dialog (v1.23), a separate
     *  window the pager never sees, so they no longer anchor
     *  gestures here; the join-sound block rides inside that
     *  panel and is pager chrome no longer. */
    @Override
    public boolean ownsHorizontalGesture(float x, float y) {
        return inside(tunerAnchor, x, y) || inside(pttAnchor, x, y);
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

    // ------------------------------------------------------------ fixed-layout helpers (v1.20)

    /** The switch sections' explanations, verbatim from their old
     *  inline bodies: they now live in the info dialogs behind each
     *  switch row's status line, so the page stays fixed-height
     *  without losing a word of the guidance. */
    private static final String LOW_DATA_BODY =
            "Sends text instead of audio. You talk, this phone "
            + "transcribes your words on-device, and you check them "
            + "before anything is sent. The other phone reads the "
            + "text aloud in a synthetic voice profile you choose per "
            + "contact — recognizable, but not their real voice. "
            + "Transcription works fully offline once the speech "
            + "model is downloaded.";
    private static final String READ_ALOUD_BODY =
            "Every text this phone receives in a walkie channel is "
            + "read aloud in the sender's voice profile — a synthetic "
            + "voice you choose per contact, recognizable, but not "
            + "their real voice. Never speaks while Quiet is on.";
    private static final String SUBSCRIBE_BODY =
            "Keep listening to this channel over the internet "
            + "relay, even when you're tuned elsewhere. Messages "
            + "arrive in this channel's thread and are read aloud, "
            + "just as if you were tuned here. Each subscribed "
            + "channel is checked every few seconds — more "
            + "subscriptions means each one is checked less often "
            + "— and background listening uses a little mobile "
            + "data and battery. Quiet silences the read-aloud, "
            + "as always. The mesh and Bluetooth roads are not "
            + "affected: they only ever hear the tuned channel.";
    private static final String BRIDGE_BODY =
            "Messages received on one road are passed live to the "
            + "other roads connected right now — so a message sent "
            + "on the mesh reaches people on the internet relay, "
            + "and the other way around. A phone with data "
            + "publishes mesh and Bluetooth messages to this "
            + "channel on the relay as they arrive. Bridging is "
            + "live only: nothing is stored for later, so a "
            + "message that arrives while no phone has data stays "
            + "within the mesh, and only new messages publish "
            + "once a phone gains data. A relayed message is "
            + "marked with the phone that relayed it — that phone "
            + "vouches for delivery, not for who wrote it. "
            + "Private channels are bridged only by phones "
            + "holding the channel key. Turn it off and each road "
            + "stays separate, as before.";

    /** A small button sized for the fixed layout: 32dp tall, no
     *  vertical margins (Ui.smallButton is 40dp + 8dp of margins,
     *  which the fixed budget cannot afford on every row). The
     *  shared Ui factory is untouched — this is page-local. */
    private Button compactButton(String label) {
        Button b = Ui.smallButton(act, label);
        b.setMinHeight(Ui.dp(act, 32));
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        p.setMargins(0, 0, Ui.dp(act, 6), 0);
        b.setLayoutParams(p);
        return b;
    }

    /** One switch row: the switch plus its status on the SAME line,
     *  single-line and ellipsized, so switch states never grow the
     *  page. Tapping the status opens the section's info dialog
     *  with the full text. */
    private LinearLayout switchRow(Switch sw, TextView status) {
        LinearLayout row = Ui.row(act);
        row.addView(sw);
        status.setSingleLine(true);
        status.setEllipsize(TextUtils.TruncateAt.END);
        status.setPadding(Ui.dp(act, 8), 0, 0, 0);
        row.addView(status, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        return row;
    }

    /** The info dialog behind a switch row's status line: the
     *  section's explanation plus its full current state, so
     *  nothing the inline row truncates is ever lost. */
    private void showSwitchInfo(String title, String body, String stateFull) {
        LinearLayout box = Ui.column(act);
        box.setPadding(Ui.dp(act, 12), Ui.dp(act, 8),
                Ui.dp(act, 12), Ui.dp(act, 8));
        box.addView(Ui.body(act, body));
        if (stateFull != null && !stateFull.isEmpty()) {
            box.addView(Ui.text(act, stateFull, 13, Ui.TEXT),
                    Ui.wrap(0, 10, 0, 0, act));
        }
        ScrollView sc = new ScrollView(act);
        sc.addView(box);
        new AlertDialog.Builder(act)
                .setTitle(title)
                .setView(sc)
                .setPositiveButton("Close", null)
                .show();
    }

    /** Shows one road section in a dialog. The section view is
     *  built once and lives in the hidden holder between openings;
     *  opening moves it into the dialog, dismissing returns it —
     *  so its buttons, switches, and listeners are the same live
     *  objects, never rebuilt. */
    private void showRoadSection(String title, View section) {
        closeRoadSection();
        if (section.getParent() instanceof ViewGroup) {
            ((ViewGroup) section.getParent()).removeView(section);
        }
        LinearLayout box = Ui.column(act);
        box.setPadding(Ui.dp(act, 12), Ui.dp(act, 4),
                Ui.dp(act, 12), Ui.dp(act, 8));
        box.addView(section);
        ScrollView sc = new ScrollView(act);
        sc.addView(box);
        shownSection = section;
        sectionDialog = new AlertDialog.Builder(act)
                .setTitle(title)
                .setView(sc)
                .setPositiveButton("Close", null)
                .setOnDismissListener(d -> returnSection())
                .show();
    }

    private void returnSection() {
        if (shownSection != null && sectionHolder != null) {
            if (shownSection.getParent() instanceof ViewGroup) {
                ((ViewGroup) shownSection.getParent()).removeView(shownSection);
            }
            sectionHolder.addView(shownSection);
            shownSection = null;
        }
        sectionDialog = null;
    }

    private void closeRoadSection() {
        if (sectionDialog != null) {
            AlertDialog d = sectionDialog;
            sectionDialog = null;
            d.dismiss();
        }
    }

    /** Shows one panel (messages, radio menu) in a dialog. Same
     *  re-parenting pattern as the road sections: the panel is
     *  built once and lives in the hidden holder between
     *  openings, so its views, adapters, and listeners are the
     *  same live objects, never rebuilt. The messages panel is
     *  NOT wrapped in a ScrollView — its list scrolls
     *  internally, and nested scrolling would break it — and
     *  its window resizes for the keyboard so the composer
     *  stays visible. The menu panel scrolls inside its dialog
     *  (the no-scroll rule governs the page, not dialogs). */
    private void showPanelDialog(String title, View panel, boolean scrollable) {
        closePanelDialog();
        if (panel.getParent() instanceof ViewGroup) {
            ((ViewGroup) panel.getParent()).removeView(panel);
        }
        View content = panel;
        if (scrollable) {
            LinearLayout box = Ui.column(act);
            box.setPadding(Ui.dp(act, 12), Ui.dp(act, 4),
                    Ui.dp(act, 12), Ui.dp(act, 8));
            box.addView(panel);
            ScrollView sc = new ScrollView(act);
            sc.addView(box);
            content = sc;
        }
        shownPanel = panel;
        panelDialog = new AlertDialog.Builder(act)
                .setTitle(title)
                .setView(content)
                .setPositiveButton("Close", null)
                .setOnDismissListener(d -> returnPanel())
                .show();
        if (!scrollable && panelDialog.getWindow() != null) {
            panelDialog.getWindow().setSoftInputMode(
                    android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        }
        if (panel == messagesPanelView) refreshThreadBottomPadding();
    }

    private void returnPanel() {
        if (shownPanel != null && panelHolder != null) {
            if (shownPanel.getParent() instanceof ViewGroup) {
                ((ViewGroup) shownPanel.getParent()).removeView(shownPanel);
            }
            panelHolder.addView(shownPanel);
            shownPanel = null;
        }
        panelDialog = null;
    }

    private void closePanelDialog() {
        if (panelDialog != null) {
            AlertDialog d = panelDialog;
            panelDialog = null;
            d.dismiss();
        }
    }

    // ------------------------------------------------------------ UI

    // v1.23: the page is a RADIO. A slim header (title, All
    // channels, Configure), then the radio body — display
    // window, channel knob, status strip, speaker grille, and
    // the large talk button — then the access row (Messages,
    // Menu, Scan). The heights follow WalkieLayout's budget
    // and the face never scrolls: the message thread + composer
    // live in the messages panel dialog, and the switches,
    // roads, and subscription controls live in the radio menu
    // dialog. Nothing was dropped — everything is one tap
    // away, and every control keeps its behavior and copy.
    private void buildUi() {
        int pad = Ui.dp(act, 16);
        setPadding(pad, Ui.dp(act, WalkieLayout.PAGE_PAD_TOP_DP),
                pad, Ui.dp(act, WalkieLayout.PAGE_PAD_BOTTOM_DP));

        // The private channel's join sound plays from the BOTTOM
        // of the messages panel (v1.2 placement, audio since
        // v1.8; the panel since v1.23). Built here, parented
        // into the panel's thread region — it overlays the
        // bottom of the list there, costs no layout height,
        // and tapping it plays the join sound for the tuned
        // channel. Open channel → no block.
        playBlock = Ui.column(act);
        playBlock.setGravity(Gravity.CENTER_HORIZONTAL);
        playBlock.setBackgroundColor(Ui.SURFACE);
        playBlock.setPadding(pad, Ui.dp(act, 8), pad, Ui.dp(act, 8));
        playJoinButton = Ui.button(act, "Play join sound", true);
        playJoinButton.setMinHeight(Ui.dp(act, 36));
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

        LinearLayout header = Ui.row(act);
        TextView title = Ui.text(act, "WALKIE-TALKIE", 13, Ui.DIM);
        header.addView(title, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        channelsBtn = compactButton("All channels");
        channelsBtn.setOnClickListener(v -> act.startActivity(new Intent(act, ChannelsActivity.class)));
        header.addView(channelsBtn);
        Button editBtn = compactButton("Configure");
        editBtn.setOnClickListener(v -> {
            Intent i = new Intent(act, ChannelEditActivity.class);
            i.putExtra("number", channelNumber);
            act.startActivity(i);
        });
        header.addView(editBtn);
        addView(header);

        // ---- the radio body: one rounded panel holding the
        // display, the knob, the status strip, the grille, and
        // the talk button. It takes the remaining page height;
        // the grille inside it absorbs the slack.
        LinearLayout body = new LinearLayout(act);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setBackground(Ui.roundedStroke(act, Ui.SURFACE, 18, Ui.LINE));
        body.setPadding(pad, Ui.dp(act, WalkieLayout.BODY_PAD_TOP_DP),
                pad, Ui.dp(act, WalkieLayout.BODY_PAD_BOTTOM_DP));

        // The display window (tap = direct tune): the channel
        // number, the frequency, the label + Open/Private tag,
        // and below them the indicators row — road/signal, mode,
        // and the unplayed count for the tuned channel.
        LinearLayout display = Ui.column(act);
        display.setBackground(Ui.roundedStroke(act, Ui.PANEL, 12, Ui.LINE));
        display.setPadding(pad, Ui.dp(act, 6), pad, Ui.dp(act, 6));
        LinearLayout readoutRow = Ui.row(act);
        bigNumber = Ui.text(act, "1", 34, Ui.TEXT);
        Ui.display(bigNumber);
        readoutRow.addView(bigNumber);
        LinearLayout readoutSide = Ui.column(act);
        readoutSide.setBackgroundColor(0x00000000);
        readoutSide.setPadding(Ui.dp(act, 10), 0, 0, 0);
        readoutFreq = Ui.text(act, "", 15, Ui.ACCENT);
        readoutFreq.setTypeface(android.graphics.Typeface.MONOSPACE);
        readoutSide.addView(readoutFreq);
        readoutLabel = Ui.text(act, "", 14, Ui.TEXT);
        readoutLabel.setSingleLine(true);
        readoutLabel.setEllipsize(TextUtils.TruncateAt.END);
        readoutSide.addView(readoutLabel);
        readoutRow.addView(readoutSide);
        display.addView(readoutRow);
        LinearLayout indicatorRow = Ui.row(act);
        roadText = Ui.text(act, "", 12, Ui.DIM);
        roadText.setTypeface(android.graphics.Typeface.MONOSPACE);
        roadText.setSingleLine(true);
        roadText.setEllipsize(TextUtils.TruncateAt.END);
        indicatorRow.addView(roadText, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        modeText = Ui.text(act, "", 12, Ui.ACCENT);
        modeText.setSingleLine(true);
        modeText.setPadding(Ui.dp(act, 8), 0, 0, 0);
        indicatorRow.addView(modeText);
        badgeText = Ui.text(act, "", 12, Ui.SIGNAL);
        badgeText.setSingleLine(true);
        badgeText.setPadding(Ui.dp(act, 8), 0, 0, 0);
        badgeText.setVisibility(GONE);
        indicatorRow.addView(badgeText);
        display.addView(indicatorRow, Ui.wrap(0, 2, 0, 0, act));
        display.setOnClickListener(v -> directTuneDialog());
        body.addView(display);

        // The channel knob: CH − / CH + flanking the knurled
        // tuner band. Drag the band to sweep (detents + haptics,
        // as always); the buttons step one channel at a time.
        LinearLayout knobRow = Ui.row(act);
        Button down = compactButton("CH −");
        down.setOnClickListener(v -> step(-1));
        knobRow.addView(down);
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
        tunerAnchor = tuner;
        knobRow.addView(tuner, new LinearLayout.LayoutParams(
                0, Ui.dp(act, WalkieLayout.KNOB_DP), 1f));
        Button up = compactButton("CH +");
        up.setOnClickListener(v -> step(1));
        knobRow.addView(up);
        body.addView(knobRow, Ui.wrap(0, 4, 0, 0, act));

        // Status strip: one small line each, ellipsized — the
        // fixed layout needs the strip bounded (see WalkieLayout).
        // Empty lines collapse to zero height, as before. Every
        // setter in the transports keeps writing these same
        // views; only their size and home changed.
        statusText = Ui.text(act, "", 12, Ui.DIM);
        statusText.setSingleLine(true);
        statusText.setEllipsize(TextUtils.TruncateAt.END);
        body.addView(statusText, Ui.wrap(0, 6, 0, 0, act));
        talkerText = Ui.text(act, "", 12, Ui.ACCENT);
        talkerText.setSingleLine(true);
        talkerText.setEllipsize(TextUtils.TruncateAt.END);
        body.addView(talkerText);
        membersText = Ui.text(act, "", 12, Ui.DIM);
        membersText.setSingleLine(true);
        membersText.setEllipsize(TextUtils.TruncateAt.END);
        body.addView(membersText);

        // The speaker grille: drawn slots, flexible height — it
        // absorbs the slack on tall screens (its pattern stays
        // centered), down to the floor WalkieLayout pins.
        SpeakerGrilleView grille = new SpeakerGrilleView(act);
        grille.setMinimumHeight(Ui.dp(act, WalkieLayout.GRILLE_MIN_DP));
        body.addView(grille, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        // Push-to-talk — the dominant control on the face: the
        // large disc, dark green with the lighter ring; held →
        // accent fill. Same states and labels as always.
        LinearLayout pttWrap = new LinearLayout(act);
        pttWrap.setGravity(Gravity.CENTER);
        pttButton = new Button(act);
        pttAnchor = pttButton;
        pttButton.setText("HOLD TO\nTALK");
        pttButton.setTextSize(16);
        pttButton.setTypeface(android.graphics.Typeface.MONOSPACE, android.graphics.Typeface.BOLD);
        pttButton.setLetterSpacing(0.12f);
        pttButton.setTextColor(Ui.ACCENT);
        pttButton.setBackground(pttDrawable(false));
        int pttSide = Ui.dp(act, WalkieLayout.PTT_SIDE_DP);
        pttWrap.addView(pttButton, new LinearLayout.LayoutParams(pttSide, pttSide));
        pttButton.setOnTouchListener((v, e) -> {
            if (e.getAction() == MotionEvent.ACTION_DOWN) {
                if (lowDataOn()) startLowDataSpurt(); else startSpurt();
            }
            if (e.getAction() == MotionEvent.ACTION_UP
                    || e.getAction() == MotionEvent.ACTION_CANCEL) stopSpurt();
            return true;
        });
        body.addView(pttWrap, Ui.wrap(0, 8, 0, 0, act));
        addView(body, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        // Access row: the messages panel, the radio menu, and
        // Scan — everything the face hides is one tap away.
        LinearLayout accessRow = Ui.row(act);
        messagesButton = compactButton("Messages");
        messagesButton.setOnClickListener(v -> showPanelDialog(
                "Channel " + channelNumber + " messages",
                messagesPanelView, false));
        accessRow.addView(messagesButton);
        Button menuBtn = compactButton("Menu");
        menuBtn.setOnClickListener(v -> showPanelDialog(
                "Radio menu", menuPanelView, true));
        accessRow.addView(menuBtn);
        scanButton = compactButton("Scan");
        scanButton.setOnClickListener(v -> {
            if (scanning) stopScan(); else startScan();
        });
        accessRow.addView(scanButton);
        addView(accessRow, Ui.wrap(0, 6, 0, 0, act));

        // The panels live in the hidden holder between dialog
        // openings; GONE, so the holder measures nothing.
        panelHolder = Ui.column(act);
        panelHolder.setVisibility(GONE);
        messagesPanelView = messagesPanel();
        menuPanelView = menuPanel();
        panelHolder.addView(messagesPanelView);
        panelHolder.addView(menuPanelView);
        addView(panelHolder);

        // The hidden holder owns the road sections between dialog
        // openings; GONE, so it measures nothing.
        sectionHolder = Ui.column(act);
        sectionHolder.setVisibility(GONE);
        localSectionView = localSection();
        internetSectionView = internetSection();
        bluetoothSectionView = bluetoothSection();
        sectionHolder.addView(localSectionView);
        sectionHolder.addView(internetSectionView);
        sectionHolder.addView(bluetoothSectionView);
        addView(sectionHolder);
    }

    /** The messages panel (v1.23): the thread region + the
     *  composer, built once and shown in a dialog from the
     *  access row. The thread list is the ONLY scrolling region
     *  in the panel; the empty hint overlays its top and the
     *  join-sound block overlays its bottom on private
     *  channels, exactly as on the v1.20 page — the list pads
     *  its scrollable bottom by the block's height (see
     *  refreshPlayBlock) so no message hides under it. The
     *  panel is fixed-height so the list has room to scroll;
     *  the dialog clamps it on short screens. History rides in
     *  the composer row, naming its count, as since v1.18. */
    private View messagesPanel() {
        LinearLayout panel = Ui.column(act);
        panel.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(act, 480)));
        FrameLayout threadRegion = new FrameLayout(act);
        threadList = new ListView(act);
        threadList.setDivider(null);
        threadList.setClipToPadding(false);
        threadAdapter = new ThreadAdapter();
        historyAdapter = new HistoryAdapter();
        threadList.setAdapter(threadAdapter);
        threadList.setOnItemClickListener((p, v, pos, id) -> {
            JSONObject entry = threadEntries.get(pos);
            if ("voice".equals(entry.optString("kind"))) {
                playEntry(entry);
            } else if (!entry.optBoolean("mine")) {
                // Any received text replays aloud in the sender's
                // voice profile; own messages never speak.
                replayEntry(entry);
            }
        });
        threadList.setOnItemLongClickListener((p, v, pos, id) -> {
            showEntryMenu(v, threadEntries.get(pos));
            return true;
        });
        threadRegion.addView(threadList,
                new FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT));
        threadEmptyHint = Ui.text(act,
                "Nothing unplayed here. Messages you have heard are in History.",
                13, Ui.DIM);
        threadEmptyHint.setVisibility(GONE);
        threadRegion.addView(threadEmptyHint,
                new FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        Gravity.TOP | Gravity.START));
        threadRegion.addView(playBlock,
                new FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        Gravity.BOTTOM));
        panel.addView(threadRegion, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        LinearLayout composeRow = Ui.row(act);
        historyButton = compactButton("History");
        historyButton.setOnClickListener(v -> showHistoryDialog());
        composeRow.addView(historyButton);
        composer = Ui.input(act, "Text this channel…");
        composer.setPadding(Ui.dp(act, 12), Ui.dp(act, 8),
                Ui.dp(act, 12), Ui.dp(act, 8));
        composeRow.addView(composer, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        Button send = Ui.button(act, "Send", true);
        send.setMinHeight(Ui.dp(act, 38));
        send.setOnClickListener(v -> {
            String text = composer.getText().toString().trim();
            if (text.isEmpty()) return;
            composer.setText("");
            sendChannelText(text);
        });
        composeRow.addView(send);
        panel.addView(composeRow, Ui.wrap(0, 2, 0, 0, act));
        return panel;
    }

    /** The radio menu (v1.23): the send-mode choice, read-aloud,
     *  background listening, the private-channel flow, and the
     *  roads — everything the face hides, one tap away, with
     *  every section's copy and controls intact. Built once
     *  and shown in a scrolling dialog from the access row. */
    private View menuPanel() {
        LinearLayout panel = Ui.column(act);
        panel.setBackgroundColor(0x00000000);
        panel.addView(modeSection());
        panel.addView(readAloudSection());
        panel.addView(subscriptionSection());
        panel.addView(bridgeSection());

        // First-class private-channel flow: make this channel
        // private (or play its join sound), and listen for an
        // invite without leaving the radio.
        LinearLayout actionRow = Ui.row(act);
        channelActionBtn = compactButton("Make this channel private");
        channelActionBtn.setOnClickListener(v -> onChannelAction());
        actionRow.addView(channelActionBtn);
        Button listenInviteBtn = compactButton("Listen for invite");
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
        panel.addView(actionRow, Ui.wrap(0, 2, 0, 0, act));

        // The road sections live in their own dialogs (one tap
        // away, full copy and controls intact), as since v1.20.
        LinearLayout roadsRow = Ui.row(act);
        Button meshBtn = compactButton("Mesh & LAN");
        meshBtn.setOnClickListener(v -> showRoadSection(
                "Nearby mesh & LAN", localSectionView));
        roadsRow.addView(meshBtn);
        Button netBtn = compactButton("Internet");
        netBtn.setOnClickListener(v -> showRoadSection(
                "Internet", internetSectionView));
        roadsRow.addView(netBtn);
        Button btBtn = compactButton("Bluetooth");
        btBtn.setOnClickListener(v -> showRoadSection(
                "Bluetooth", bluetoothSectionView));
        roadsRow.addView(btBtn);
        panel.addView(roadsRow, Ui.wrap(0, 2, 0, 0, act));
        return panel;
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
        // Sound sharing (v1.26): the host plays the mesh's join
        // instructions; a nearby phone listens and joins the
        // named mesh without the manual tap dance.
        LinearLayout row3 = Ui.row(act);
        Button shareMesh = Ui.smallButton(act, "Share mesh by sound");
        shareMesh.setOnClickListener(v -> shareMeshBySound());
        Button joinSound = Ui.smallButton(act, "Join by sound");
        joinSound.setOnClickListener(v -> {
            if (act instanceof MainActivity) {
                act.startActivityForResult(
                        AudioKeyListenActivity.meshIntent(act),
                        MainActivity.REQ_WALKIE_LISTEN);
            }
        });
        row3.addView(shareMesh);
        row3.addView(joinSound);
        box.addView(row3);
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
        subscribeSwitch = new Switch(act);
        subscribeSwitch.setText("Listen in the background");
        subscribeSwitch.setTextColor(Ui.TEXT);
        subscribeSwitch.setTextSize(14);
        subscribeSwitch.setPadding(0, Ui.dp(act, 2), 0, Ui.dp(act, 2));
        subscribeSwitch.setOnCheckedChangeListener((b, on) -> onSubscribeToggled(on));
        subscribeState = Ui.text(act, "", 12, Ui.DIM);
        subscribeState.setOnClickListener(v -> showSwitchInfo(
                "Listen in the background", SUBSCRIBE_BODY, subscribeStateFull));
        box.addView(switchRow(subscribeSwitch, subscribeState));
        refreshSubscriptionSection();
        return box;
    }

    private void refreshSubscriptionSection() {
        if (subscribeSwitch == null || subscribeState == null) return;
        subscribeSwitch.setOnCheckedChangeListener(null);
        subscribeSwitch.setChecked(
                ChannelSubscriptions.isSubscribed(act, channelNumber));
        subscribeSwitch.setOnCheckedChangeListener(
                (b, on) -> onSubscribeToggled(on));
        // The channel number rides in the status line now (the
        // switch text is fixed); the full line is one tap away in
        // the info dialog, so a blocker is never truncated into a
        // mystery.
        subscribeStateFull = "Channel " + channelNumber + " — "
                + subscriptionStateLine();
        subscribeState.setText(subscribeStateFull + " ⓘ");
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

    /** Cross-transport bridging (v1.24): one switch, default
     *  ON, in the radio menu. Off means exactly the pre-v1.24
     *  behavior — each road hears only its own traffic. */
    private View bridgeSection() {
        LinearLayout box = Ui.column(act);
        box.setBackgroundColor(0x00000000);
        bridgeSwitch = new Switch(act);
        bridgeSwitch.setText("Bridge messages across transports");
        bridgeSwitch.setTextColor(Ui.TEXT);
        bridgeSwitch.setTextSize(14);
        bridgeSwitch.setPadding(0, Ui.dp(act, 2), 0, Ui.dp(act, 2));
        bridgeSwitch.setOnCheckedChangeListener((b, on) -> onBridgeToggled(on));
        bridgeState = Ui.text(act, "", 12, Ui.DIM);
        bridgeState.setOnClickListener(v -> showSwitchInfo(
                "Bridge messages across transports", BRIDGE_BODY, bridgeStateFull));
        box.addView(switchRow(bridgeSwitch, bridgeState));
        refreshBridgeSection();
        return box;
    }

    private void refreshBridgeSection() {
        if (bridgeSwitch == null || bridgeState == null) return;
        bridgeSwitch.setOnCheckedChangeListener(null);
        bridgeSwitch.setChecked(bridgingOn());
        bridgeSwitch.setOnCheckedChangeListener((b, on) -> onBridgeToggled(on));
        bridgeStateFull = bridgingOn()
                ? "Messages pass live between the mesh, Bluetooth, "
                + "and the internet relay. Nothing is stored for later."
                : "Bridging off — each road stays separate, as before.";
        bridgeState.setText(bridgeStateFull + " ⓘ");
    }

    private void onBridgeToggled(boolean on) {
        Prefs.get(act).edit().putBoolean(Prefs.KEY_BRIDGE_ON, on).apply();
        refreshBridgeSection();
    }

    /** The bridging switch, default ON. */
    private boolean bridgingOn() {
        return Prefs.get(act).getBoolean(Prefs.KEY_BRIDGE_ON, true);
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

    // ------------------------------------------------------------ send mode (v1.11 low-data, v1.23 auto)

    /** The mode in force is TEXT exactly when hold-to-talk
     *  transcribes instead of sending voice. This is the single
     *  seam between the mode decision and the existing
     *  low-data path (PTT dispatch, the model block, the
     *  transcription dialog) — everything downstream is
     *  unchanged. */
    private boolean lowDataOn() {
        return effectiveMode == WalkieMode.Mode.TEXT;
    }

    /** The stored choice. No stored value means AUTO (v1.24
     *  default); a stored value is the user's explicit pick.
     *  This never writes: no implicit choice is ever stored,
     *  and the v1.23 one-time migration from the old low-data
     *  switch is gone. */
    private WalkieMode.Choice modeChoice() {
        return WalkieMode.parseChoice(
                Prefs.get(act).getString(Prefs.KEY_WALKIE_MODE, null));
    }

    private void setModeChoice(WalkieMode.Choice c) {
        Prefs.get(act).edit()
                .putString(Prefs.KEY_WALKIE_MODE, WalkieMode.storeChoice(c))
                .apply();
        applyModeDecision();
        // Choosing Auto tests the connection right away instead
        // of waiting for the next idle tick.
        if (c == WalkieMode.Choice.AUTO) maybeProbe();
    }

    /** Recomputes the effective mode from the choice, the live
     *  sessions, and the last probe; syncs the old low-data
     *  key with the mode in force; refreshes every surface
     *  that names the mode. */
    private void applyModeDecision() {
        effectiveMode = WalkieMode.decide(modeChoice(), effectiveMode,
                localSessionLive(), lastProbe, lastRttMs);
        Prefs.get(act).edit()
                .putBoolean(Prefs.KEY_LOWDATA_ON, lowDataOn()).apply();
        refreshModeLine();
        refreshFaceIndicators();
        refreshModeSection();
    }

    private boolean localSessionLive() {
        return meshLive || btLive;
    }

    /** The idle re-test: once a minute while the page is shown.
     *  Started in onPageShown, stopped in onPageHidden. */
    private final Runnable probeTick = new Runnable() {
        @Override public void run() {
            maybeProbe();
            ui.postDelayed(this, WalkieMode.PROBE_INTERVAL_MS);
        }
    };

    /** Runs one speed probe when Auto is in force, no local
     *  session is live (a nearby link decides the mode by
     *  itself, with no probe), and no transmission is in
     *  progress. Single-flight: a probe already running is
     *  never doubled. A failed probe keeps the last decision
     *  and the face says the relay is unreachable. */
    private void maybeProbe() {
        if (pageDead) return;
        if (modeChoice() != WalkieMode.Choice.AUTO) return;
        if (localSessionLive()) {
            applyModeDecision();
            return;
        }
        if (recording || transcribing || probing) return;
        probing = true;
        RelayClient.probe(act, new RelayClient.Cb<Long>() {
            @Override public void ok(Long ms) {
                probing = false;
                lastProbe = WalkieMode.Probe.OK;
                lastRttMs = ms;
                applyModeDecision();
            }

            @Override public void err(String code, String message) {
                probing = false;
                lastProbe = WalkieMode.Probe.FAILED;
                applyModeDecision();
            }
        });
    }

    /** The mode line on the radio face: the choice, what the
     *  probe measured (or that it measured nothing), and the
     *  mode in force — worded by WalkieMode, so the harness
     *  proves the exact text. */
    private void refreshModeLine() {
        if (modeText == null) return;
        modeText.setText(WalkieMode.describe(modeChoice(), effectiveMode,
                lastProbe, lastRttMs, localSessionLive(),
                !SpeechTranscriber.isModelReady(act)));
    }

    /** The display window's road/signal line and the unplayed
     *  count: the live local session when one exists (with its
     *  member count or peer), else the relay's reachability
     *  from the last probe, else the plain Internet road. The
     *  badge counts unplayed RECEIVED messages on the tuned
     *  channel; the Messages button carries the same count. */
    private void refreshFaceIndicators() {
        if (roadText == null) return;
        String road;
        if (meshLive) {
            road = sessionRoad + (lastMemberCount > 0
                    ? " · " + lastMemberCount + " nearby" : " · open");
        } else if (btLive) {
            road = "Bluetooth" + (lastBtPeer.isEmpty() ? "" : " · " + lastBtPeer);
        } else if (lastProbe == WalkieMode.Probe.FAILED) {
            road = "Relay unreachable";
        } else {
            road = "Internet";
        }
        roadText.setText(WalkieMode.signalText(lastProbe, lastRttMs,
                localSessionLive()) + " " + road);
        int unplayed = 0;
        for (JSONObject e : threadEntries) {
            if (!e.optBoolean("mine")) unplayed++;
        }
        if (unplayed > 0) {
            badgeText.setText(unplayed + " new");
            badgeText.setVisibility(VISIBLE);
        } else {
            badgeText.setVisibility(GONE);
        }
        if (messagesButton != null) {
            messagesButton.setText(unplayed > 0
                    ? "Messages (" + unplayed + ")" : "Messages");
        }
    }

    /** The send-mode section in the radio menu: the three-way
     *  choice (Auto / Voice / Text) with the mode's status
     *  line and the speech-model block. The old low-data
     *  switch is the Text choice; its explanation survives
     *  verbatim in the info dialog behind the status line. */
    private View modeSection() {
        LinearLayout box = Ui.column(act);
        box.setBackgroundColor(0x00000000);
        LinearLayout row = Ui.row(act);
        row.addView(Ui.text(act, "Send mode", 14, Ui.TEXT));
        modeAutoBtn = modeButton("Auto", WalkieMode.Choice.AUTO);
        modeVoiceBtn = modeButton("Voice", WalkieMode.Choice.VOICE);
        modeTextBtn = modeButton("Text", WalkieMode.Choice.TEXT);
        row.addView(modeAutoBtn);
        row.addView(modeVoiceBtn);
        row.addView(modeTextBtn);
        box.addView(row);
        lowDataStatus = Ui.text(act, "", 12, Ui.DIM);
        lowDataStatus.setOnClickListener(v -> showSwitchInfo(
                "Low-data mode", LOW_DATA_BODY, lowDataStatusFull));
        box.addView(lowDataStatus, Ui.wrap(0, 2, 0, 0, act));

        // Speech-model block: shown when the mode in force is
        // TEXT but the model isn't on the phone yet, and while
        // downloading.
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

        refreshModeSection();
        return box;
    }

    private Button modeButton(String label, final WalkieMode.Choice choice) {
        Button b = compactButton(label);
        b.setOnClickListener(v -> setModeChoice(choice));
        return b;
    }

    /** Highlights the chosen mode button (accent fill) and
     *  rests the others (the plain compact style). */
    private void refreshModeButtons() {
        WalkieMode.Choice c = modeChoice();
        styleModeButton(modeAutoBtn, c == WalkieMode.Choice.AUTO);
        styleModeButton(modeVoiceBtn, c == WalkieMode.Choice.VOICE);
        styleModeButton(modeTextBtn, c == WalkieMode.Choice.TEXT);
    }

    private void styleModeButton(Button b, boolean selected) {
        if (b == null) return;
        if (selected) {
            b.setBackground(Ui.rounded(act, Ui.ACCENT, 9));
            b.setTextColor(Ui.ACCENT_INK);
            b.setTypeface(android.graphics.Typeface.create(
                    "sans-serif", android.graphics.Typeface.BOLD));
        } else {
            b.setBackground(Ui.roundedStroke(act, Ui.SURFACE, 9, Ui.LINE));
            b.setTextColor(Ui.TEXT);
            b.setTypeface(android.graphics.Typeface.MONOSPACE);
        }
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
        autoReadSwitch = new Switch(act);
        autoReadSwitch.setText("Read walkie messages aloud");
        autoReadSwitch.setTextColor(Ui.TEXT);
        autoReadSwitch.setTextSize(14);
        autoReadSwitch.setPadding(0, Ui.dp(act, 2), 0, Ui.dp(act, 2));
        autoReadSwitch.setChecked(
                Prefs.get(act).getBoolean(Prefs.KEY_LOWDATA_AUTOREAD, true));
        autoReadSwitch.setOnCheckedChangeListener((b, on) -> {
            Prefs.get(act).edit()
                    .putBoolean(Prefs.KEY_LOWDATA_AUTOREAD, on).apply();
            updateReadAloudStatus();
        });
        readAloudStatus = Ui.text(act, "", 12, Ui.DIM);
        readAloudStatus.setOnClickListener(v -> showSwitchInfo(
                "Read aloud", READ_ALOUD_BODY, readAloudStatusFull));
        box.addView(switchRow(autoReadSwitch, readAloudStatus));
        updateReadAloudStatus();
        return box;
    }

    private void updateReadAloudStatus() {
        if (readAloudStatus == null) return;
        boolean on = Prefs.get(act).getBoolean(Prefs.KEY_LOWDATA_AUTOREAD, true);
        readAloudStatusFull = on
                ? "On — never speaks while Quiet is on."
                : "Off.";
        readAloudStatus.setText(readAloudStatusFull + " ⓘ");
    }

    /** The mode section's status line + model block, from the
     *  choice and the mode in force. The model block shows
     *  exactly when TEXT is in force but the model is missing
     *  — the state where hold-to-talk is paused. */
    private void refreshModeSection() {
        if (lowDataStatus == null) return;
        refreshModeButtons();
        WalkieMode.Choice c = modeChoice();
        boolean textMode = lowDataOn();
        boolean ready = SpeechTranscriber.isModelReady(act);
        if (c == WalkieMode.Choice.AUTO) {
            lowDataStatusFull = "Auto — the connection speed is "
                    + "tested and the mode follows it. Now: "
                    + (textMode ? "Text." : "Voice.");
        } else if (c == WalkieMode.Choice.TEXT) {
            lowDataStatusFull = ready
                    ? "Text — hold to talk, then check the words "
                    + "before sending. Only the text goes out."
                    : "Text, but the speech model isn't downloaded "
                    + "yet — hold to talk is paused until it is.";
        } else {
            lowDataStatusFull = "Voice — hold to talk sends voice "
                    + "bursts as usual.";
        }
        lowDataStatus.setText(lowDataStatusFull + " ⓘ");
        if (textMode && !ready) {
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
        } else {
            modelBlock.setVisibility(GONE);
        }
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
                refreshModeSection();
                refreshModeLine();
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
        // v1.20: the readout is one row — the Open/Private tag rides
        // in the label line instead of a second row.
        readoutLabel.setText((label.isEmpty() ? "—" : label)
                + " · " + (Profile.isPrivate(s) ? "Private" : "Open"));
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
        refreshFaceIndicators();
        refreshModeLine();
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
                        + "plays from the messages panel, ready to "
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
            refreshThreadBottomPadding();
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
        refreshThreadBottomPadding();
    }

    /** The join-sound block overlays the bottom of the thread
     *  region: the list pads its scrollable bottom by the block's
     *  measured height (clipToPadding is off), so the last message
     *  scrolls clear of it instead of hiding under it. */
    private void refreshThreadBottomPadding() {
        if (threadList == null || playBlock == null) return;
        threadList.post(() -> {
            int pad = playBlock.getVisibility() == VISIBLE
                    ? playBlock.getHeight() + Ui.dp(act, 8) : 0;
            threadList.setPadding(0, 0, 0, pad);
        });
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
     *  the host activity). Joins channel invites and mesh invites;
     *  anything else gets a plain refusal. */
    public void handleHeardText(String text) {
        if (text == null) return;
        try {
            JSONObject obj = new JSONObject(text);
            if ("control-mesh-join".equals(obj.optString("app", ""))) {
                handleMeshJoin(obj);
                return;
            }
        } catch (Exception ignored) {
            // Not JSON — fall through to the invite path, which
            // refuses it plainly.
        }
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

    // ---- mesh sharing by sound (v1.26) ----

    /** Plays this phone's mesh join instructions as sound. Only
     *  while hosting: the packet names the live session — its
     *  road, the host's reachable address, and the channel
     *  identity — so the receiver can join without the manual
     *  tap dance. A phone that is not hosting has no mesh to
     *  share, and is told so. */
    private void shareMeshBySound() {
        if (!meshHost || !meshLive || mesh == null) {
            Toast.makeText(act, "Host a mesh group or start a LAN "
                    + "session first, then share it by sound.",
                    Toast.LENGTH_LONG).show();
            return;
        }
        if ("LAN".equals(sessionRoad)) {
            shareMeshLan();
        } else if (sessionRoad != null && sessionRoad.contains("mesh")) {
            shareMeshP2p();
        } else {
            Toast.makeText(act, "No mesh or LAN session is live to share.",
                    Toast.LENGTH_LONG).show();
        }
    }

    private void shareMeshP2p() {
        if (!ensureP2p()) return;
        if (android.os.Build.VERSION.SDK_INT < 29) {
            Toast.makeText(act, "This phone can't share its mesh by "
                    + "sound: reading its own Wi-Fi Direct address "
                    + "needs Android 10 or newer.",
                    Toast.LENGTH_LONG).show();
            return;
        }
        p2p.requestDeviceInfo(p2pChannel, device -> {
            String addr = device == null ? null : device.deviceAddress;
            if (addr == null || addr.isEmpty()) {
                Toast.makeText(act, "Couldn't read this phone's Wi-Fi "
                        + "Direct address — the mesh sound can't be built.",
                        Toast.LENGTH_LONG).show();
                return;
            }
            act.startActivity(AudioKeyPlayActivity.meshIntent(act,
                    AudioKeyPayload.ROAD_P2P, myName(), addr, identity,
                    Profile.slotDisplayName(slot), Profile.isPrivate(slot)));
        });
    }

    private void shareMeshLan() {
        String addr = ownLanAddress();
        if (addr == null) {
            Toast.makeText(act, "Couldn't find this phone's address on "
                    + "this Wi-Fi network — the mesh sound can't be built.",
                    Toast.LENGTH_LONG).show();
            return;
        }
        act.startActivity(AudioKeyPlayActivity.meshIntent(act,
                AudioKeyPayload.ROAD_LAN, myName(), addr, identity,
                Profile.slotDisplayName(slot), Profile.isPrivate(slot)));
    }

    /** This phone's site-local IPv4 address, or null when none is
     *  up. Interface enumeration needs no permission and never
     *  returns a loopback or link-local address. */
    private static String ownLanAddress() {
        try {
            java.util.Enumeration<java.net.NetworkInterface> ifs =
                    java.net.NetworkInterface.getNetworkInterfaces();
            while (ifs.hasMoreElements()) {
                java.net.NetworkInterface ni = ifs.nextElement();
                if (!ni.isUp() || ni.isLoopback() || ni.isVirtual()) continue;
                java.util.Enumeration<InetAddress> addrs = ni.getInetAddresses();
                while (addrs.hasMoreElements()) {
                    InetAddress a = addrs.nextElement();
                    if (a.isLoopbackAddress()) continue;
                    if (!(a instanceof java.net.Inet4Address)) continue;
                    if (a.isSiteLocalAddress()) return a.getHostAddress();
                }
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    /** A decoded mesh invite: join the mesh it names. The listen
     *  screen the user armed IS the consent — no second dialog —
     *  but the join always names the mesh first, in the toast
     *  and the status line, and a channel this phone does not
     *  have is refused plainly instead of joined. The keyed
     *  mismatch case is refused at the mesh door by the existing
     *  hello keycheck, with its honest message. */
    private void handleMeshJoin(JSONObject obj) {
        String road = obj.optString("road", "");
        String host = obj.optString("host", "");
        String address = obj.optString("address", "");
        String channelId = obj.optString("channelId", "");
        String channelName = obj.optString("channelName", "");
        if (channelName.isEmpty()) channelName = channelId;
        int number = findSlotForIdentity(channelId);
        if (number < 1) {
            Toast.makeText(act, "That mesh is on " + channelName
                    + ", a channel you don't have — ask for the channel "
                    + "invite first, then join by sound again.",
                    Toast.LENGTH_LONG).show();
            return;
        }
        if (address.isEmpty()) {
            Toast.makeText(act, "That mesh sound didn't carry an address "
                    + "to join — ask them to play it again.",
                    Toast.LENGTH_LONG).show();
            return;
        }
        String who = host.isEmpty() ? "The mesh" : host + "'s mesh";
        retune(number);
        if ("lan".equals(road)) {
            InetAddress addr;
            try {
                addr = InetAddress.getByName(address);
            } catch (Exception e) {
                Toast.makeText(act, "That mesh sound carried an address "
                        + "this phone can't reach — ask them to play it again.",
                        Toast.LENGTH_LONG).show();
                return;
            }
            Toast.makeText(act, "Joining " + who + " on " + channelName
                    + " (same Wi-Fi)…", Toast.LENGTH_LONG).show();
            mesh = newMeshSession("LAN");
            meshHost = false;
            mesh.connectToHost(addr);
            statusText.setText("Joining " + who + " on " + channelName
                    + " via LAN…");
        } else if ("p2p".equals(road)) {
            if (!ensureP2p() || !ensureNearbyPermissions()) return;
            Toast.makeText(act, "Joining " + who + " on " + channelName
                    + " (Wi-Fi Direct)… Accept the connection on the "
                    + "other phone if it asks.", Toast.LENGTH_LONG).show();
            WifiP2pDevice stub = new WifiP2pDevice();
            stub.deviceAddress = address;
            stub.deviceName = host.isEmpty() ? "Mesh host" : host;
            connectToPeer(stub);
        } else {
            Toast.makeText(act, "That mesh sound named a road this "
                    + "version doesn't know — it was ignored.",
                    Toast.LENGTH_LONG).show();
        }
    }

    /** The local slot whose identity matches the invite's channel
     *  id (wireId respected, so a moved slot still matches), or
     *  -1 when no slot speaks as that channel. */
    private int findSlotForIdentity(String channelId) {
        if (channelId == null || channelId.isEmpty()) return -1;
        for (int n = 1; n <= Profile.BANK_SIZE; n++) {
            if (channelId.equals(Profile.slotIdentity(Profile.slot(act, n)))) {
                return n;
            }
        }
        return -1;
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
        refreshFaceIndicators();
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

    /** Stamps the word-dictionary note (v1.28) onto an entry
     *  whose text arrived coded with a dictionary this build
     *  cannot decode. A null note stamps nothing. */
    private static void markWordNote(JSONObject entry, String wordNote) {
        if (wordNote == null) return;
        try {
            entry.put("wdNote", wordNote);
        } catch (Exception ignored) {
        }
    }

    /** Stamps a received text's wire form (v1.32) onto its
     *  entry: the payload exactly as received, whether it
     *  arrived coded, and the original text's byte count —
     *  -1 when the payload did not decode, since its
     *  original size is then unknown. */
    private static void stampReceivedWire(JSONObject entry, String wire,
                                          boolean coded, String wordNote,
                                          String text) {
        if (wire == null) return;
        int plainBytes = wordNote != null ? -1
                : text.getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
        WalkieStore.stampWire(entry, wire, plainBytes, coded);
    }

    /** True when an entry carries an undecodable word-coded
     *  text: it is never spoken aloud and never bridged —
     *  the gateway cannot vouch for content it cannot
     *  read, and coded text read aloud is gibberish. */
    static boolean hasWordNote(JSONObject entry) {
        return entry != null && !entry.optString("wdNote", "").isEmpty();
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
        // v1.28: an undecodable word-coded text is never
        // spoken — coded text read aloud is gibberish, and
        // the thread row already carries the honest note.
        if (hasWordNote(entry)) return;
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
        // v1.28: tapping an undecodable word-coded entry
        // restates the note instead of reading codes aloud.
        if (hasWordNote(entry)) {
            Toast.makeText(act, entry.optString("wdNote", ""),
                    Toast.LENGTH_LONG).show();
            return;
        }
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
        // v1.24: a relayed message names the phone that
        // relayed it — the gateway vouches for delivery,
        // never for authorship.
        if (e.optBoolean("relayed", false) && !e.optString("via", "").isEmpty()) {
            head += "  ·  relayed by " + e.optString("via");
        }
        rowLayout.addView(Ui.text(act, head, 11, Ui.DIM));
        if ("voice".equals(e.optString("kind"))) {
            int secs = Math.max(1, e.optInt("samples") / SAMPLE_RATE);
            rowLayout.addView(Ui.text(act,
                    "▶ Voice burst · " + secs + "s (tap to play"
                            + (inHistory ? " again" : "") + ")", 15, Ui.TEXT));
        } else {
            rowLayout.addView(Ui.text(act, e.optString("text"), 15, Ui.TEXT));
            // v1.28: an undecodable word-coded text names
            // why it shows as sent, under the text itself —
            // and carries no read-aloud hint, since tapping
            // it restates the note instead of speaking.
            if (hasWordNote(e)) {
                rowLayout.addView(Ui.text(act,
                        e.optString("wdNote", ""), 11, Ui.SIGNAL));
            }
            if (!mine && !hasWordNote(e)) {
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
                showEntryMenu(v, historyEntries.get(pos));
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

    /** The long-press menu, shared by the thread and
     *  History (v1.32): a text entry offers "View encoded
     *  message" plus the detail line; a voice entry has no
     *  coded form, so it shows the detail line directly. */
    private void showEntryMenu(View anchor, JSONObject entry) {
        if (!"text".equals(entry.optString("kind"))) {
            showEntryInfo(entry);
            return;
        }
        PopupMenu menu = new PopupMenu(act, anchor);
        menu.getMenu().add("View encoded message");
        menu.getMenu().add("Details");
        menu.setOnMenuItemClickListener(item -> {
            if ("View encoded message".equals(item.getTitle().toString())) {
                showEncodedForEntry(entry);
            } else {
                showEntryInfo(entry);
            }
            return true;
        });
        menu.show();
    }

    /** The long-press encoded view for one thread entry:
     *  the recorded wire form, never a re-encode. */
    private void showEncodedForEntry(JSONObject entry) {
        EncodedMessageDialog.show(act,
                entry.has("wire") ? entry.optString("wire") : null,
                entry.optInt("plainBytes", -1),
                entry.optBoolean("coded", false),
                entry.has("wdNote") ? entry.optString("wdNote") : null,
                entry.optString("text", ""),
                entry.optBoolean("relayed", false)
                        ? entry.optString("via", "") : null);
    }

    /** The long-press detail toast, shared by the thread and
     *  History (sender, time, road, state). */
    private void showEntryInfo(JSONObject entry) {
        String road = entry.optString("road", "");
        String when = DateFormat.getTimeInstance(DateFormat.SHORT)
                .format(new Date(entry.optLong("ts")));
        String relayed = entry.optBoolean("relayed", false)
                && !entry.optString("via", "").isEmpty()
                ? " · relayed by " + entry.optString("via") : "";
        Toast.makeText(act,
                entry.optString("from") + " · " + when
                        + (road.isEmpty() ? "" : " · via " + road)
                        + relayed
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
                lastMemberCount = names.size();
                membersText.setText(names.isEmpty()
                        ? "Session open — nobody else here yet."
                        : "On this channel nearby: " + String.join(", ", names));
                // Road change (v1.23): a live local session
                // decides the mode by itself under Auto.
                applyModeDecision();
                tryFlushOutbox();
            });
        }

        @Override public void onTalker(String name, BridgeRule.Relayed meta,
                                     String mid) {
            // An inbound spurt opens a fresh capture for
            // bridging (v1.24): the talker, the provenance
            // the spurt-start packet carried, the spurt's
            // message id, and an empty audio buffer. Own
            // spurts (name is this phone, while
            // transmitting) never open a capture — own
            // traffic is never bridged.
            if (name != null && !recording && !name.equals(myName())) {
                synchronized (meshSpurtLock) {
                    meshSpurtPcm.reset();
                    meshSpurtTalker = name;
                    meshSpurtMeta = meta;
                    meshSpurtMid = mid == null ? "" : mid;
                    meshSpurtOverflow = false;
                }
            }
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

        @Override public void onText(String from, String text, boolean lowData,
                                     BridgeRule.Relayed meta, String mid,
                                     String wordNote, String wire, boolean coded) {
            ui.post(() -> {
                // Every received text gets the bridge id
                // derived from its origin identity (v1.24): a
                // text whose id is already in the thread is
                // the same message arriving on a second road
                // — it lands once, and is neither spoken nor
                // bridged again.
                String bid = BridgeRule.localArrivalBid(mid, meta);
                if (BridgeRule.entryWithBid(
                        WalkieStore.thread(act, identity), bid)) {
                    return;
                }
                JSONObject entry = WalkieStore.entry(
                        "text", from, text, "", 0, sessionRoad, false, "sent");
                if (lowData) markLowData(entry);
                markWordNote(entry, wordNote);
                // v1.32: the wire form as received, so the
                // long-press view shows the real coded form.
                // An undecoded payload's original size is
                // unknown — the wire IS the text shown.
                stampReceivedWire(entry, wire, coded, wordNote, text);
                if (meta != null) {
                    BridgeRule.stampRelayedEntry(entry, meta);
                } else {
                    BridgeRule.stampBid(entry, bid);
                }
                BridgeRule.markBridgeSeen(entry);
                appendEntry(entry);
                maybeSpeak(entry.optString("from"), entry.optString("sender"),
                        text, entry);
                bridgeLocalText(BridgeRule.Transport.MESH, entry, text,
                        lowData, meta);
            });
        }

        @Override public void onVoice(byte[] pcm) {
            if (playTrack != null) playTrack.write(pcm, 0, pcm.length);
            synchronized (meshSpurtLock) {
                if (meshSpurtOverflow) return;
                if (meshSpurtPcm.size() + pcm.length > MAX_BRIDGE_SPURT_PCM) {
                    meshSpurtOverflow = true;
                    return;
                }
                meshSpurtPcm.write(pcm, 0, pcm.length);
            }
        }

        @Override public void onSpurtEnd() {
            // Snapshot the capture under the lock, on this
            // reader thread, before any later spurt can reset
            // it; the bridge decision runs on the UI thread.
            final byte[] pcm;
            final String talker;
            final BridgeRule.Relayed meta;
            final String mid;
            final boolean overflow;
            synchronized (meshSpurtLock) {
                pcm = meshSpurtPcm.toByteArray();
                talker = meshSpurtTalker;
                meta = meshSpurtMeta;
                mid = meshSpurtMid;
                overflow = meshSpurtOverflow;
                meshSpurtPcm.reset();
                meshSpurtTalker = "";
                meshSpurtMeta = null;
                meshSpurtMid = "";
                meshSpurtOverflow = false;
            }
            ui.post(() -> {
                beep();
                talkerText.setText("");
                stopPlayTrack();
                if (!overflow && pcm.length > 0 && !talker.isEmpty()) {
                    bridgeLocalVoice(BridgeRule.Transport.MESH, pcm, talker,
                            meta, mid);
                }
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
        // A stopped session ends any in-flight spurt: drop
        // the bridge captures so a partial burst can never
        // forward later (v1.24).
        synchronized (meshSpurtLock) {
            meshSpurtPcm.reset();
            meshSpurtTalker = "";
            meshSpurtMeta = null;
            meshSpurtOverflow = false;
        }
        synchronized (btSpurtLock) {
            btSpurtAdpcm.reset();
            btSpurtTalker = "";
            btSpurtMeta = null;
            btSpurtOverflow = false;
        }
        saveWalkieState("no live local session reported");
        stopPlayTrack();
        // Road change (v1.23): the local session is gone, so
        // under Auto the connection is tested again.
        lastMemberCount = 0;
        lastBtPeer = "";
        refreshFaceIndicators();
        maybeProbe();
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
                lastBtPeer = name != null ? name : "";
                membersText.setText("Bluetooth: paired with " + name + ".");
                // Road change (v1.23): a live local session
                // decides the mode by itself under Auto.
                applyModeDecision();
                tryFlushOutbox();
            });
        }

        @Override public void onSpurtStart(String name, BridgeRule.Relayed meta,
                                         String mid) {
            btDecoder = new Adpcm();
            // An inbound spurt opens a fresh capture for
            // bridging (v1.24), as on the mesh: the talker,
            // the provenance, the spurt's message id, and an
            // empty frame buffer.
            synchronized (btSpurtLock) {
                btSpurtAdpcm.reset();
                btSpurtTalker = name;
                btSpurtMeta = meta;
                btSpurtMid = mid == null ? "" : mid;
                btSpurtOverflow = false;
            }
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
            synchronized (btSpurtLock) {
                if (btSpurtOverflow) return;
                if (btSpurtAdpcm.size() + adpcmFrame.length > MAX_BRIDGE_SPURT_ADPCM) {
                    btSpurtOverflow = true;
                    return;
                }
                btSpurtAdpcm.write(adpcmFrame, 0, adpcmFrame.length);
            }
        }

        @Override public void onSpurtEnd() {
            final byte[] adpcm;
            final String talker;
            final BridgeRule.Relayed meta;
            final String mid;
            final boolean overflow;
            synchronized (btSpurtLock) {
                adpcm = btSpurtAdpcm.toByteArray();
                talker = btSpurtTalker;
                meta = btSpurtMeta;
                mid = btSpurtMid;
                overflow = btSpurtOverflow;
                btSpurtAdpcm.reset();
                btSpurtTalker = "";
                btSpurtMeta = null;
                btSpurtMid = "";
                btSpurtOverflow = false;
            }
            ui.post(() -> {
                beep();
                talkerText.setText("");
                stopPlayTrack();
                if (!overflow && adpcm.length > 0 && !talker.isEmpty()) {
                    bridgeBtVoice(adpcm, talker, meta, mid);
                }
            });
        }

        @Override public void onText(String from, String text, boolean lowData,
                                     BridgeRule.Relayed meta, String mid,
                                     String wordNote, String wire, boolean coded) {
            ui.post(() -> {
                // Same cross-road duplicate rule as the mesh:
                // every received text gets the bridge id
                // derived from its origin identity, and one
                // already in the thread lands once — neither
                // spoken nor bridged again.
                String bid = BridgeRule.localArrivalBid(mid, meta);
                if (BridgeRule.entryWithBid(
                        WalkieStore.thread(act, identity), bid)) {
                    return;
                }
                JSONObject entry = WalkieStore.entry(
                        "text", from, text, "", 0, "Bluetooth", false, "sent");
                if (lowData) markLowData(entry);
                markWordNote(entry, wordNote);
                stampReceivedWire(entry, wire, coded, wordNote, text);
                if (meta != null) {
                    BridgeRule.stampRelayedEntry(entry, meta);
                } else {
                    BridgeRule.stampBid(entry, bid);
                }
                BridgeRule.markBridgeSeen(entry);
                appendEntry(entry);
                maybeSpeak(entry.optString("from"), entry.optString("sender"),
                        text, entry);
                bridgeLocalText(BridgeRule.Transport.BLUETOOTH, entry, text,
                        lowData, meta);
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
                    + "in the radio menu.", Toast.LENGTH_LONG).show();
            refreshModeSection();
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
        // One message id for this spurt, shared by every road
        // it rides (v1.24): the local roads carry it in the
        // spurt-start packet, and the relay post reuses it.
        spurtMid = java.util.UUID.randomUUID().toString();
        pttButton.setText(lowData ? "LISTENING" : "TALKING");
        pttButton.setTextColor(Ui.ACCENT_INK);
        pttButton.setBackground(pttDrawable(true));
        if (!lowData) {
            if (meshLive && mesh != null) mesh.beginSpurt(spurtMid);
            if (btLive && bt != null) bt.sendSpurtStart(spurtMid);
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
        if (relayOnline()) postBurstToRelay(entry, adpcm, roads.size() > 1, spurtMid);
        spurtMid = "";
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
                                  final boolean otherRoadsCarried,
                                  final String mid) {
        final String channel = relayChannelId;
        // v1.24: when the spurt also rode a local road, the
        // relay post's client message id IS the spurt's
        // message id ("m:"+mid) — the same identity gateways
        // derive the bridge id from, so a gatewayed copy
        // dedups against this post instead of doubling it.
        // A relay-only burst keeps a random UUID.
        final String cmid = mid != null && !mid.isEmpty()
                ? "m:" + mid : java.util.UUID.randomUUID().toString();
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
                    // The own entry carries the same bridge
                    // id a gatewayed copy would, so the copy
                    // coming back dedups instead of landing
                    // as a second entry (v1.24).
                    entry.put("bid", BridgeRule.relayArrivalBid(cmid, null));
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
            SentText sent = mesh.sendText(text, lowData);
            JSONObject entry = WalkieStore.entry("text", myName(), text, "", 0,
                    sessionRoad, true, "sent");
            if (lowData) markLowData(entry);
            WalkieStore.stampWire(entry, sent.wire, sent.plainBytes, true);
            // v1.24: the own entry carries the same bridge id
            // a gatewayed copy would, so the copy coming back
            // dedups instead of landing as a second entry.
            BridgeRule.stampBid(entry, BridgeRule.localArrivalBid(sent.mid, null));
            appendEntry(entry);
            return;
        }
        if (btLive && bt != null) {
            SentText sent = bt.sendText(text, lowData);
            JSONObject entry = WalkieStore.entry("text", myName(), text, "", 0,
                    "Bluetooth", true, "sent");
            if (lowData) markLowData(entry);
            WalkieStore.stampWire(entry, sent.wire, sent.plainBytes, true);
            BridgeRule.stampBid(entry, BridgeRule.localArrivalBid(sent.mid, null));
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
            LowDataEnvelope.Built built =
                    LowDataEnvelope.buildDetailed(text, lowData, channel);
            byte[] plain = built.envelope
                    .getBytes(java.nio.charset.StandardCharsets.UTF_8);
            // v1.32: the entry records the exact wire form
            // being sent, so the long-press view shows the
            // real coded form, never a re-encode.
            WalkieStore.stampWire(entry, built.wire,
                    text.getBytes(java.nio.charset.StandardCharsets.UTF_8).length,
                    true);
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
                    BridgeRule.stampBid(entry,
                            BridgeRule.relayArrivalBid(cmid, null));
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
                SentText sent = mesh.sendText(text, lowData);
                markQueuedSent(ts, sessionRoad,
                        BridgeRule.localArrivalBid(sent.mid, null), sent);
                WalkieStore.removeQueued(act, identity, ts);
            } else if (btLive && bt != null) {
                SentText sent = bt.sendText(text, lowData);
                markQueuedSent(ts, "Bluetooth",
                        BridgeRule.localArrivalBid(sent.mid, null), sent);
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

    private void markQueuedSent(long ts, String road, String bid, SentText sent) {
        for (JSONObject e : threadEntries) {
            if (e.optLong("ts") == ts && "queued".equals(e.optString("state"))) {
                try {
                    e.put("state", "sent");
                    e.put("road", road);
                    if (sent != null) {
                        WalkieStore.stampWire(e, sent.wire, sent.plainBytes, true);
                    }
                    if (bid != null && !bid.isEmpty()) {
                        BridgeRule.stampBid(e, bid);
                    }
                } catch (Exception ignored) {
                }
            }
        }
        WalkieStore.saveNow(act);
        threadAdapter.notifyDataSetChanged();
    }

    // ------------------------------------------------------------ bridging (v1.24)

    /** True when this phone may carry a private-channel
     *  message: open channels need no key, and a private
     *  channel needs the valid 32-byte channel key. */
    private boolean holdsChannelKey() {
        return !Profile.isPrivate(slot) || relayKeyBytes != null;
    }

    /** The roads live right now, for the fan-out decision. */
    private EnumSet<BridgeRule.Transport> connectedRoads() {
        EnumSet<BridgeRule.Transport> out =
                EnumSet.noneOf(BridgeRule.Transport.class);
        if (meshLive && mesh != null) out.add(BridgeRule.Transport.MESH);
        if (btLive && bt != null) out.add(BridgeRule.Transport.BLUETOOTH);
        if (relayOnline()) out.add(BridgeRule.Transport.RELAY);
        return out;
    }

    /** The fan-out decision for one received message, with
     *  the live state filled in: the switch, the channel
     *  key, the persisted forwarded set, and whether this
     *  phone is transmitting (bridging never triggers
     *  during an active transmission). */
    private EnumSet<BridgeRule.Transport> bridgeTargets(
            BridgeRule.Transport arrivedOn, BridgeRule.Relayed meta, String bid) {
        boolean already = bid != null && !bid.isEmpty()
                && WalkieStore.hasForwardedBid(act, bid);
        Set<BridgeRule.Transport> trav = meta != null ? meta.traversed
                : EnumSet.noneOf(BridgeRule.Transport.class);
        return BridgeRule.targets(arrivedOn, connectedRoads(), bridgingOn(),
                Profile.isPrivate(slot), holdsChannelKey(), already,
                recording || transcribing, trav);
    }

    /** The provenance stamp for the forwarded copies: the
     *  same bid and origin, this phone named as the gateway,
     *  the arrival road added to traversed. The bid is the
     *  one derived at receipt — a direct message's bid comes
     *  from its origin identity, never minted here, so two
     *  gateways forward under the same bid. */
    private BridgeRule.Relayed forwardStamp(BridgeRule.Relayed meta, String bid,
                                            String origName, String origPub, long origTs,
                                            BridgeRule.Transport arrivedOn) {
        BridgeRule.Relayed base = meta != null ? meta
                : BridgeRule.Relayed.of(bid, origName, origPub, origTs, "",
                        EnumSet.noneOf(BridgeRule.Transport.class));
        return base.forwarded(myName(), arrivedOn);
    }

    /** Bridges a text received on a local road (mesh or
     *  Bluetooth). The entry already exists in the thread
     *  and already carries the bid derived at receipt; the
     *  forward records the traversed set onto it. */
    private void bridgeLocalText(BridgeRule.Transport arrivedOn,
                                 JSONObject entry, String text, boolean lowData,
                                 BridgeRule.Relayed meta) {
        // v1.28: an undecodable word-coded text is never
        // bridged — the gateway cannot vouch for content
        // it cannot read, and re-coding coded text would
        // hand the far side a doubly-wrapped payload.
        if (hasWordNote(entry)) return;
        String bid = entry.optString("bid", "");
        EnumSet<BridgeRule.Transport> to = bridgeTargets(arrivedOn, meta, bid);
        if (to.isEmpty()) return;
        BridgeRule.Relayed stamp = forwardStamp(meta, bid,
                entry.optString("from"), entry.optString("sender"),
                entry.optLong("ts"), arrivedOn);
        try {
            entry.put("trav", BridgeRule.travString(stamp.traversed));
        } catch (Exception ignored) {
        }
        WalkieStore.noteForwardedBid(act, stamp.bid);
        WalkieStore.saveNow(act);
        String origName = entry.optString("from");
        if (to.contains(BridgeRule.Transport.RELAY)) {
            postBridgeToRelay(true, text, lowData, null, stamp);
        }
        if (to.contains(BridgeRule.Transport.MESH)) {
            forwardTextToMesh(text, lowData, stamp, origName);
        }
        if (to.contains(BridgeRule.Transport.BLUETOOTH)) {
            forwardTextToBt(text, lowData, stamp, origName);
        }
    }

    /** Bridges a voice burst received on the mesh: the
     *  captured PCM is encoded to ADPCM, the shared voice
     *  path decides and forwards. */
    private void bridgeLocalVoice(BridgeRule.Transport arrivedOn,
                                  byte[] pcmBytes, String talker,
                                  BridgeRule.Relayed meta, String mid) {
        int samples = pcmBytes.length / 2;
        if (samples <= 0) return;
        short[] pcm = new short[samples];
        for (int i = 0; i < samples; i++) {
            pcm[i] = (short) ((pcmBytes[2 * i] & 0xFF) | (pcmBytes[2 * i + 1] << 8));
        }
        bridgeVoiceCommon(arrivedOn, Adpcm.encodeBurst(pcm), samples,
                talker, meta, mid);
    }

    /** Bridges a voice burst received on Bluetooth: the
     *  captured ADPCM frames forward as-is. */
    private void bridgeBtVoice(byte[] adpcm, String talker,
                               BridgeRule.Relayed meta, String mid) {
        bridgeVoiceCommon(BridgeRule.Transport.BLUETOOTH, adpcm,
                adpcm.length * 2, talker, meta, mid);
    }

    /** The shared bridged-voice path. Live bursts normally
     *  leave no thread record — but a bridged burst gets one:
     *  the entry is what tap-to-play replays, and its bid is
     *  what stops the same burst landing twice when its relay
     *  copy arrives. When nothing forwards, no entry is
     *  created at all: exactly the pre-v1.24 behavior. The
     *  entry is unplayed until played, like every received
     *  burst, and is never auto-played. */
    private void bridgeVoiceCommon(BridgeRule.Transport arrivedOn,
                                   byte[] adpcm, int samples, String talker,
                                   BridgeRule.Relayed meta, String mid) {
        String bid = BridgeRule.localArrivalBid(mid, meta);
        EnumSet<BridgeRule.Transport> to = bridgeTargets(arrivedOn, meta, bid);
        if (to.isEmpty()) return;
        String origName = meta != null ? meta.origName : talker;
        String origPub = meta != null ? meta.origPub : "";
        long origTs = meta != null && meta.origTs > 0
                ? meta.origTs : System.currentTimeMillis();
        BridgeRule.Relayed stamp = forwardStamp(meta, bid, origName, origPub,
                origTs, arrivedOn);
        String road = arrivedOn == BridgeRule.Transport.MESH
                ? sessionRoad : "Bluetooth";
        JSONObject entry = WalkieStore.entry("voice", origName, "", "",
                samples, road, false, "sent");
        if (meta != null) BridgeRule.stampRelayedEntry(entry, meta);
        try {
            entry.put("bid", stamp.bid);
            entry.put("trav", BridgeRule.travString(stamp.traversed));
            entry.put("blob", RelayKeys.b64Std(adpcm));
        } catch (Exception ignored) {
        }
        BridgeRule.markBridgeSeen(entry);
        voiceCache.put(entry.optLong("ts"), adpcm);
        appendEntry(entry);
        WalkieStore.noteForwardedBid(act, stamp.bid);
        WalkieStore.saveNow(act);
        if (to.contains(BridgeRule.Transport.RELAY)) {
            postBridgeToRelay(false, "", false, adpcm, stamp);
        }
        if (to.contains(BridgeRule.Transport.MESH)) {
            forwardVoiceToMesh(adpcm, stamp, origName);
        }
        if (to.contains(BridgeRule.Transport.BLUETOOTH)) {
            forwardVoiceToBt(adpcm, stamp, origName);
        }
    }

    /** Bridges a message received on the relay — from the
     *  page's own poll loop, or from the subscription
     *  service's store via the pending scan. The entry
     *  already exists; the decision is made exactly once
     *  (bridgeSeen), forwarded or not, so a later scan never
     *  republishes an old message. Own messages never reach
     *  here. */
    private void bridgeRelayEntry(JSONObject entry, boolean isText,
                                  String text, boolean lowData, byte[] adpcm,
                                  BridgeRule.Relayed meta) {
        if (HistoryRule.isMine(entry)) return;
        BridgeRule.markBridgeSeen(entry);
        // v1.28: an undecodable word-coded text is never
        // bridged (see bridgeLocalText). Bursts are
        // unaffected — they carry no word coding.
        if (isText && hasWordNote(entry)) {
            WalkieStore.saveNow(act);
            return;
        }
        // The bid was derived at ingest and stamped on the
        // entry; an entry without one predates bridging and
        // is never forwarded (see the pending scan).
        String bid = entry.optString("bid", "");
        if (bid.isEmpty()) {
            WalkieStore.saveNow(act);
            return;
        }
        EnumSet<BridgeRule.Transport> to = bridgeTargets(
                BridgeRule.Transport.RELAY, meta, bid);
        if (to.isEmpty()) {
            WalkieStore.saveNow(act);
            return;
        }
        BridgeRule.Relayed stamp = forwardStamp(meta, bid,
                entry.optString("from"), entry.optString("sender"),
                entry.optLong("ts"), BridgeRule.Transport.RELAY);
        try {
            entry.put("trav", BridgeRule.travString(stamp.traversed));
        } catch (Exception ignored) {
        }
        WalkieStore.noteForwardedBid(act, stamp.bid);
        WalkieStore.saveNow(act);
        String origName = entry.optString("from");
        if (to.contains(BridgeRule.Transport.MESH)) {
            if (isText) forwardTextToMesh(text, lowData, stamp, origName);
            else if (adpcm != null) forwardVoiceToMesh(adpcm, stamp, origName);
        }
        if (to.contains(BridgeRule.Transport.BLUETOOTH)) {
            if (isText) forwardTextToBt(text, lowData, stamp, origName);
            else if (adpcm != null) forwardVoiceToBt(adpcm, stamp, origName);
        }
    }

    /** The pending scan: relay arrivals the subscription
     *  service stored (it cannot forward — the local
     *  sessions live on this page) get their one bridge
     *  decision here, from the stored entry alone. Runs on
     *  the subscription broadcast and when the page comes
     *  forward. Only tuned-channel relay arrivals with a
     *  client message id qualify; everything else is left
     *  exactly as it was. */
    private void bridgePendingRelayEntries() {
        if (pageDead) return;
        for (JSONObject e : WalkieStore.thread(act, identity)) {
            if (HistoryRule.isMine(e)) continue;
            if (BridgeRule.isBridgeSeen(e)) continue;
            if (!"Internet".equals(e.optString("road", ""))) continue;
            if (e.optString("cmid", "").isEmpty()) continue;
            // No bid means the entry predates bridging (a
            // pre-v1.24 store): mark it seen and leave it —
            // upgrading must never republish old messages.
            if (e.optString("bid", "").isEmpty()) {
                BridgeRule.markBridgeSeen(e);
                WalkieStore.saveNow(act);
                continue;
            }
            boolean isText = "text".equals(e.optString("kind"));
            byte[] adpcm = null;
            if (!isText) {
                String blob = e.optString("blob", "");
                if (!blob.isEmpty()) {
                    try {
                        adpcm = RelayKeys.b64StdDecode(blob);
                    } catch (Exception ignored) {
                    }
                }
                if (adpcm == null) {
                    BridgeRule.markBridgeSeen(e);
                    WalkieStore.saveNow(act);
                    continue;
                }
            }
            bridgeRelayEntry(e, isText, e.optString("text", ""),
                    e.optBoolean("lowData", false), adpcm,
                    BridgeRule.relayedFromEntry(e));
        }
    }

    // ---- bridge sends: one single-flight guard per transport ----

    /** Publishes a bridged message to the relay channel: the
     *  payload carries the provenance (envelope keys for a
     *  text, the header line for a burst), sealed with the
     *  channel key on private channels exactly like a direct
     *  post. The post is signed by THIS phone — the gateway
     *  — never as the original sender. A failed post is not
     *  retried: bridging is live only. */
    private void postBridgeToRelay(boolean isText, String text,
                                   boolean lowData, byte[] adpcm,
                                   BridgeRule.Relayed stamp) {
        if (!relayBridgeBusy.compareAndSet(false, true)) return;
        final String channel = relayChannelId;
        final String cmid = java.util.UUID.randomUUID().toString();
        final String blob;
        try {
            byte[] plain = isText
                    ? BridgeRule.buildRelayedText(text, lowData, stamp, channel)
                            .getBytes(java.nio.charset.StandardCharsets.UTF_8)
                    : BridgeRule.buildRelayedBurstPayload(stamp, adpcm);
            blob = relayKeyBytes != null
                    ? RelayCrypto.seal(relayKeyBytes, plain)
                    : RelayKeys.b64Std(plain);
        } catch (Exception e) {
            relayBridgeBusy.set(false);
            return;
        }
        RelayClient.post(act, channel, cmid, blob,
                new RelayClient.Cb<RelayCore.PostResult>() {
                    @Override public void ok(RelayCore.PostResult r) {
                        // The gateway's own post comes back on
                        // the next pull: the cmid suppresses
                        // re-ingest, as for a direct post.
                        seenCmids.add(cmid);
                        relayBridgeBusy.set(false);
                    }

                    @Override public void err(String code, String message) {
                        relayBridgeBusy.set(false);
                    }
                });
    }

    private void forwardTextToMesh(String text, boolean lowData,
                                   BridgeRule.Relayed stamp, String origName) {
        if (!meshBridgeBusy.compareAndSet(false, true)) return;
        final MeshNet target = mesh;
        BRIDGE_POOL.execute(() -> {
            try {
                if (meshLive && target != null) {
                    target.sendTextAs(origName, text, lowData, stamp);
                }
            } catch (Exception ignored) {
            } finally {
                meshBridgeBusy.set(false);
            }
        });
    }

    private void forwardTextToBt(String text, boolean lowData,
                                 BridgeRule.Relayed stamp, String origName) {
        if (!btBridgeBusy.compareAndSet(false, true)) return;
        final BtWalkie target = bt;
        BRIDGE_POOL.execute(() -> {
            try {
                if (btLive && target != null) {
                    target.sendTextAs(origName, text, lowData, stamp);
                }
            } catch (Exception ignored) {
            } finally {
                btBridgeBusy.set(false);
            }
        });
    }

    /** Re-streams a burst onto the mesh: the ADPCM is
     *  decoded to PCM (the mesh carries PCM, encrypted per
     *  chunk on keyed sessions) and sent in the same 640-
     *  sample chunks the live path uses. Socket backpressure
     *  paces the stream — no sleeps, no extra buffering. */
    private void forwardVoiceToMesh(byte[] adpcm, BridgeRule.Relayed stamp,
                                    String origName) {
        if (!meshBridgeBusy.compareAndSet(false, true)) return;
        final MeshNet target = mesh;
        BRIDGE_POOL.execute(() -> {
            try {
                if (meshLive && target != null) {
                    short[] pcm = Adpcm.decodeBurst(adpcm, adpcm.length * 2);
                    byte[] bytes = new byte[pcm.length * 2];
                    for (int i = 0; i < pcm.length; i++) {
                        bytes[2 * i] = (byte) (pcm[i] & 0xFF);
                        bytes[2 * i + 1] = (byte) ((pcm[i] >> 8) & 0xFF);
                    }
                    target.beginSpurtAs(origName, stamp);
                    for (int off = 0; off < bytes.length; off += 1280) {
                        int n = Math.min(1280, bytes.length - off);
                        byte[] chunk = new byte[n];
                        System.arraycopy(bytes, off, chunk, 0, n);
                        target.sendVoice(chunk);
                    }
                    target.endSpurt();
                }
            } catch (Exception ignored) {
            } finally {
                meshBridgeBusy.set(false);
            }
        });
    }

    /** Re-streams a burst over Bluetooth: the ADPCM frames
     *  forward as-is, in the same 800-byte frames the live
     *  path uses. */
    private void forwardVoiceToBt(byte[] adpcm, BridgeRule.Relayed stamp,
                                  String origName) {
        if (!btBridgeBusy.compareAndSet(false, true)) return;
        final BtWalkie target = bt;
        BRIDGE_POOL.execute(() -> {
            try {
                if (btLive && target != null) {
                    target.sendSpurtStartAs(origName, stamp);
                    for (int off = 0; off < adpcm.length; off += 800) {
                        int n = Math.min(800, adpcm.length - off);
                        byte[] frame = new byte[n];
                        System.arraycopy(adpcm, off, frame, 0, n);
                        target.sendFrame(frame);
                    }
                    target.sendSpurtEnd();
                }
            } catch (Exception ignored) {
            } finally {
                btBridgeBusy.set(false);
            }
        });
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
            // v1.24: every arrival gets the bridge id derived
            // from its origin identity. One whose id is
            // already in the thread arrived on another road
            // first — it lands once, never twice.
            String bid = BridgeRule.relayArrivalBid(m.clientMsgId, d.relayed);
            if (BridgeRule.entryWithBid(WalkieStore.thread(act, identity), bid)) {
                continue;
            }
            boolean mine = myPub.equals(m.sender);
            String from = mine ? myName() : ContactCard.senderName(act, m.sender);
            String senderPub = m.sender;
            if (d.isText) {
                // v1.29: clean decodes teach the table their
                // new words, and adopted teach-block codes
                // mark this channel taught.
                DictStore.noteReceived(relayChannelId,
                        new WordDict.Result(d.text, d.wordNote, d.adopted));
                JSONObject entry = WalkieStore.entry("text", from, d.text, "", 0,
                        "Internet", mine, "sent");
                RelayIngest.stampEntry(entry, m, d);
                if (d.relayed != null) {
                    // The entry shows the ORIGINAL sender at
                    // the ORIGINAL time, marked relayed; the
                    // gateway's authenticated relay identity
                    // is kept alongside for honesty.
                    BridgeRule.stampRelayedEntry(entry, d.relayed);
                    try {
                        entry.put("viaPub", m.sender);
                    } catch (Exception ignored) {
                    }
                    from = d.relayed.origName;
                    senderPub = d.relayed.origPub;
                } else {
                    BridgeRule.stampBid(entry, bid);
                }
                appendEntry(entry);
                if (!mine) {
                    maybeSpeak(from, senderPub, d.text, entry);
                    bridgeRelayEntry(entry, true, d.text, d.lowData, null,
                            d.relayed);
                }
            } else {
                JSONObject entry = WalkieStore.entry("voice", from, "", "",
                        d.samples, "Internet", mine, "sent");
                RelayIngest.stampEntry(entry, m, d);
                if (d.relayed != null) {
                    BridgeRule.stampRelayedEntry(entry, d.relayed);
                    try {
                        entry.put("viaPub", m.sender);
                    } catch (Exception ignored) {
                    }
                } else {
                    BridgeRule.stampBid(entry, bid);
                }
                voiceCache.put(entry.optLong("ts"), d.plain);
                appendEntry(entry);
                if (!mine) {
                    bridgeRelayEntry(entry, false, "", false, d.plain,
                            d.relayed);
                }
            }
        }
    }
}
