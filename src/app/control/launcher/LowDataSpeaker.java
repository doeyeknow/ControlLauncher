package app.control.launcher;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import android.speech.tts.Voice;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Reads walkie messages aloud in the sender's per-contact
 * voice profile (a synthetic stand-in — pitch, rate, and one of the
 * phone's installed system voices — never the person's real voice).
 * Since v1.17 every received walkie text is read aloud, not only
 * low-data ones; the class name is unchanged (it is the same single
 * speech path, extended, not a second one).
 *
 * The QUIET rule is enforced here, at the single choke point, with
 * live values: SpeakGate decides every utterance, and QUIET-all
 * silences speech in every case, including manual replays. The
 * caller never speaks around this class.
 *
 * Voice selection is offline-safe: voices that require network
 * synthesis are never chosen (a low-data message must be readable
 * with no connection). The stored voice name falls back to the
 * system default voice when it isn't installed on this phone.
 *
 * TextToSpeech init is asynchronous; the first utterance requested
 * before init completes is held (latest wins) and spoken once the
 * engine is ready. When no TTS engine exists on the phone, every
 * speak reports NO_ENGINE and the text simply stays visible in the
 * thread — speech is an enhancement, never the message itself.
 *
 * Since v1.18 a speak may carry an onDone callback, run exactly
 * once when that utterance finishes (the walkie uses it to move
 * a heard message to History). A failed utterance (onError), an
 * interrupted one (stop/shutdown), or one dropped because init
 * failed never runs its callback — only a completed playback
 * counts as heard.
 */
public final class LowDataSpeaker {
    public enum Outcome {
        SPOKEN,
        /** QUIET-all is on. */
        QUIET,
        /** Read-aloud is off and this wasn't a manual replay. */
        MUTED,
        /** Nothing to say (null or blank text). */
        EMPTY,
        /** No TTS engine on this phone (or init failed). */
        NO_ENGINE
    }

    public interface VoicesCb {
        /** available=false when no TTS engine exists on the phone. */
        void onVoices(boolean available, List<String> voiceNames);
    }

    private final Handler ui = new Handler(Looper.getMainLooper());
    private TextToSpeech tts;
    private boolean initDone;
    private boolean initOk;
    private final List<VoicesCb> pendingVoices = new ArrayList<>();
    private Pending pendingUtterance;
    private final UtteranceCallbacks callbacks = new UtteranceCallbacks();

    private static final class Pending {
        final String text;
        final VoiceProfile profile;
        final Runnable onDone;

        Pending(String text, VoiceProfile profile, Runnable onDone) {
            this.text = text;
            this.profile = profile;
            this.onDone = onDone;
        }
    }

    public LowDataSpeaker(Context context) {
        final Context app = context.getApplicationContext();
        try {
            tts = new TextToSpeech(app, status -> {
                initDone = true;
                initOk = status == TextToSpeech.SUCCESS;
                if (initOk) {
                    try {
                        tts.setOnUtteranceProgressListener(
                                new UtteranceProgressListener() {
                                    @Override public void onStart(String id) {}
                                    @Override public void onDone(String id) {
                                        final Runnable cb = callbacks.take(id);
                                        if (cb != null) ui.post(cb);
                                    }
                                    @Override public void onError(String id) {
                                        // A failed utterance is never
                                        // heard: drop its callback.
                                        callbacks.drop(id);
                                    }
                                });
                    } catch (Exception ignored) {
                    }
                }
                flushVoices();
                flushPending();
            });
        } catch (Exception e) {
            initDone = true;
            initOk = false;
            tts = null;
        }
    }

    /**
     * Speaks text in a voice profile, gated by SpeakGate with live
     * pref values. Returns the outcome; only SPOKEN means audio
     * left the speaker (or is queued behind init). Since v1.17 the
     * low-data marker plays no part here: any received walkie text
     * may be spoken; the gate's only inputs are the read-aloud
     * switch, QUIET, and whether the user asked for this one.
     *
     * @param manual true for a tap-to-replay or a voice preview:
     *               bypasses the read-aloud switch, never QUIET.
     */
    public Outcome speak(Context c, String text, VoiceProfile profile,
                         boolean manual) {
        return speak(c, text, profile, manual, null);
    }

    /**
     * The same speak, with an onDone callback run exactly once on
     * the main thread when THIS utterance finishes. Gated, empty,
     * and engine-less speaks never run it (they return before any
     * utterance exists); a speak held for init carries the
     * callback into the pending utterance, and it is dropped —
     * never run — if init then fails.
     */
    public Outcome speak(Context c, String text, VoiceProfile profile,
                         boolean manual, Runnable onDone) {
        if (text == null || text.trim().isEmpty()) return Outcome.EMPTY;
        SpeakGate.Decision d = SpeakGate.decide(
                Prefs.get(c).getBoolean(Prefs.KEY_LOWDATA_AUTOREAD, true),
                Prefs.get(c).getBoolean(Prefs.KEY_QUIET_ALL, false),
                manual);
        switch (d) {
            case SKIP_QUIET: return Outcome.QUIET;
            case SKIP_MUTED: return Outcome.MUTED;
            case SPEAK: break;
        }
        if (!initDone) {
            pendingUtterance = new Pending(text, profile, onDone);
            return Outcome.SPOKEN;
        }
        if (!initOk || tts == null) return Outcome.NO_ENGINE;
        speakNow(text, profile, onDone);
        return Outcome.SPOKEN;
    }

    /** The voice-profile Preview path: a manual replay, so it
     *  bypasses the read-aloud switch but never QUIET. */
    public Outcome preview(Context c, String text, VoiceProfile profile) {
        return speak(c, text, profile, true);
    }

    /** Installed, offline-usable voice names, sorted, via callback
     *  (init is async). The list never includes network-only voices. */
    public void voices(VoicesCb cb) {
        if (initDone) {
            cb.onVoices(initOk && tts != null, voiceNames());
        } else {
            pendingVoices.add(cb);
        }
    }

    /** The resolved voice names for profile fallback decisions. */
    public List<String> voiceNames() {
        if (!initOk || tts == null) return Collections.emptyList();
        try {
            Set<Voice> voices = tts.getVoices();
            if (voices == null) return Collections.emptyList();
            List<String> names = new ArrayList<>();
            for (Voice v : voices) {
                if (v == null || v.getName() == null) continue;
                if (!isOfflineUsable(v)) continue;
                names.add(v.getName());
            }
            Collections.sort(names);
            return names;
        } catch (Exception e) {
            return Collections.emptyList();
        }
    }

    public void stop() {
        pendingUtterance = null;
        // Interrupted utterances are not heard: drop every
        // pending completion callback without running any.
        callbacks.clear();
        if (tts != null) {
            try {
                tts.stop();
            } catch (Exception ignored) {
            }
        }
    }

    public void shutdown() {
        pendingUtterance = null;
        callbacks.clear();
        pendingVoices.clear();
        if (tts != null) {
            try {
                tts.stop();
                tts.shutdown();
            } catch (Exception ignored) {
            }
            tts = null;
        }
    }

    private void flushVoices() {
        if (pendingVoices.isEmpty()) return;
        List<VoicesCb> cbs = new ArrayList<>(pendingVoices);
        pendingVoices.clear();
        List<String> names = voiceNames();
        boolean ok = initOk && tts != null;
        for (VoicesCb cb : cbs) {
            try {
                cb.onVoices(ok, names);
            } catch (Exception ignored) {
            }
        }
    }

    private void flushPending() {
        Pending p = pendingUtterance;
        pendingUtterance = null;
        if (p == null || !initOk || tts == null) return;
        speakNow(p.text, p.profile, p.onDone);
    }

    private void speakNow(String text, VoiceProfile profile, Runnable onDone) {
        VoiceProfile p = profile == null ? VoiceProfile.defaults() : profile;
        String utteranceId = "lowdata-" + UUID.randomUUID();
        try {
            String wanted = p.resolve(voiceNames());
            if (!wanted.isEmpty()) {
                Voice voice = findVoice(wanted);
                if (voice != null) tts.setVoice(voice);
            }
            tts.setPitch(p.pitch);
            tts.setSpeechRate(p.rate);
            int r = tts.speak(text, TextToSpeech.QUEUE_ADD, null, utteranceId);
            if (r == TextToSpeech.SUCCESS) {
                callbacks.add(utteranceId, onDone);
            }
            // A rejected speak (ERROR) leaves no utterance behind,
            // so no callback is registered and onDone never runs.
        } catch (Exception ignored) {
            // A TTS failure is never fatal to the thread: the text
            // is visible regardless, and the next message retries.
            callbacks.drop(utteranceId);
        }
    }

    private Voice findVoice(String name) {
        try {
            Set<Voice> voices = tts.getVoices();
            if (voices == null) return null;
            for (Voice v : voices) {
                if (v != null && name.equals(v.getName()) && isOfflineUsable(v)) {
                    return v;
                }
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    /**
     * A voice is offline-usable when it does not declare network
     * synthesis. Voices that declare neither feature (older
     * engines) are included — the engine default is the fallback
     * if one ever needs the network.
     */
    private static boolean isOfflineUsable(Voice v) {
        try {
            Set<String> features = v.getFeatures();
            if (features == null) return true;
            return !features.contains(TextToSpeech.Engine.KEY_FEATURE_NETWORK_SYNTHESIS);
        } catch (Exception e) {
            return true;
        }
    }
}
