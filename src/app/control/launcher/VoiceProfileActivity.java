package app.control.launcher;

import android.app.Activity;
import android.os.Bundle;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * Per-contact walkie voice profile (v1.11): which installed system
 * voice reads this contact's low-data messages aloud, and at what
 * pitch and rate. Opened from the contact screen; saved onto the
 * contact record in profile.json (see Profile.contactVoice).
 *
 * The copy here is explicit, on purpose: the voice is a synthetic
 * profile chosen on this phone — recognizable, but NOT the
 * person's real voice. No claim of voice cloning anywhere.
 */
public class VoiceProfileActivity extends ThemedActivity {

    @Override
    protected boolean recreateOnThemeChange() {
        // Theme change: a rebuild would lose unsaved voice-profile edits; the new theme
        // applies the next time this screen opens.
        return false;
    }

    private static final String DEFAULT_LABEL = "System default voice";

    private int index;
    private String contactName = "Contact";
    private LowDataSpeaker speaker;
    private Spinner voiceSpinner;
    private TextView voiceState;
    private SeekBar pitchBar;
    private SeekBar rateBar;
    private TextView pitchLabel;
    private TextView rateLabel;
    private List<String> voiceNames = new ArrayList<>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        index = getIntent().getIntExtra("index", -1);
        JSONObject contact = null;
        if (index >= 0) {
            JSONArray arr = Profile.contacts(this);
            if (index < arr.length()) contact = arr.optJSONObject(index);
        }
        if (contact == null) {
            Toast.makeText(this, "That contact is gone.", Toast.LENGTH_LONG).show();
            finish();
            return;
        }
        contactName = contact.optString("name", "Contact");
        VoiceProfile current = Profile.contactVoice(contact);

        speaker = new LowDataSpeaker(this);

        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(Ui.BG);
        LinearLayout root = Ui.column(this);
        int pad = Ui.dp(this, 20);
        root.setPadding(pad, Ui.dp(this, 20), pad, Ui.dp(this, 24));
        scroll.addView(root);
        setContentView(scroll);

        root.addView(Ui.screenTitle(this, "Walkie voice — " + contactName));
        root.addView(Ui.body(this,
                "When " + contactName + " sends a low-data walkie message, "
                + "this phone reads the text aloud in the voice below. It is "
                + "a synthetic profile you choose here — recognizable, but "
                + "not " + contactName + "'s real voice. The profile is "
                + "stored on the contact and travels with the profile "
                + "export; if a phone doesn't have the chosen voice "
                + "installed, it reads in the system default voice instead."));

        root.addView(Ui.sectionLabel(this, "Voice"));
        voiceState = Ui.text(this, "Loading voices…", 13, Ui.DIM);
        root.addView(voiceState);
        voiceSpinner = new Spinner(this);
        voiceSpinner.setEnabled(false);
        root.addView(voiceSpinner, Ui.wrap(0, 6, 0, 0, this));

        root.addView(Ui.sectionLabel(this, "Pitch"));
        pitchLabel = Ui.body(this, "");
        root.addView(pitchLabel);
        pitchBar = new SeekBar(this);
        pitchBar.setMax(150);
        pitchBar.setProgress(toProgress(current.pitch));
        pitchBar.setOnSeekBarChangeListener(simpleListener());
        root.addView(pitchBar);
        updatePitchLabel();

        root.addView(Ui.sectionLabel(this, "Speed"));
        rateLabel = Ui.body(this, "");
        root.addView(rateLabel);
        rateBar = new SeekBar(this);
        rateBar.setMax(150);
        rateBar.setProgress(toProgress(current.rate));
        rateBar.setOnSeekBarChangeListener(simpleListener());
        root.addView(rateBar);
        updateRateLabel();

        Button preview = Ui.button(this, "Preview", false);
        preview.setOnClickListener(v -> {
            LowDataSpeaker.Outcome o = speaker.preview(this,
                    "This is how " + contactName + "'s low-data messages "
                    + "will sound on this phone.", currentSelection());
            if (o == LowDataSpeaker.Outcome.QUIET) {
                Toast.makeText(this, "Quiet is on, so nothing is read aloud.",
                        Toast.LENGTH_LONG).show();
            } else if (o == LowDataSpeaker.Outcome.NO_ENGINE) {
                Toast.makeText(this, "This phone has no speech engine, so "
                        + "there is nothing to preview with.",
                        Toast.LENGTH_LONG).show();
            }
        });
        root.addView(preview, Ui.wrap(0, 18, 0, 0, this));

        Button save = Ui.button(this, "Save voice profile", true);
        save.setOnClickListener(v -> {
            Profile.setContactVoice(this, index, currentSelection());
            Toast.makeText(this, "Voice profile saved for " + contactName + ".",
                    Toast.LENGTH_SHORT).show();
            finish();
        });
        root.addView(save, Ui.wrap(0, 8, 0, 0, this));

        Button reset = Ui.button(this, "Reset to default voice", false);
        reset.setOnClickListener(v -> {
            Profile.setContactVoice(this, index, VoiceProfile.defaults());
            Toast.makeText(this, "Voice profile reset for " + contactName + ".",
                    Toast.LENGTH_SHORT).show();
            finish();
        });
        root.addView(reset, Ui.wrap(0, 8, 0, 0, this));

        loadVoices(current);
    }

    private SeekBar.OnSeekBarChangeListener simpleListener() {
        return new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar bar, int progress, boolean fromUser) {
                updatePitchLabel();
                updateRateLabel();
            }

            @Override public void onStartTrackingTouch(SeekBar bar) {}

            @Override public void onStopTrackingTouch(SeekBar bar) {}
        };
    }

    private void loadVoices(final VoiceProfile current) {
        speaker.voices((available, names) -> {
            if (!available) {
                voiceState.setText("This phone has no speech engine, so "
                        + "walkie messages can't be read aloud here. The "
                        + "text still shows in the channel thread.");
                voiceSpinner.setEnabled(false);
                return;
            }
            voiceNames = new ArrayList<>(names);
            List<String> labels = new ArrayList<>();
            labels.add(DEFAULT_LABEL);
            labels.addAll(voiceNames);
            ArrayAdapter<String> adapter = new ArrayAdapter<>(this,
                    android.R.layout.simple_spinner_dropdown_item, labels);
            voiceSpinner.setAdapter(adapter);
            voiceSpinner.setEnabled(true);
            voiceState.setText(voiceNames.isEmpty()
                    ? "No extra voices installed — the system default is used."
                    : voiceNames.size() + " installed voice(s) on this phone. "
                      + "Only voices that work offline are listed.");
            String wanted = current.voiceName;
            int sel = 0;
            for (int i = 0; i < voiceNames.size(); i++) {
                if (voiceNames.get(i).equals(wanted)) {
                    sel = i + 1;
                    break;
                }
            }
            voiceSpinner.setSelection(sel);
        });
    }

    private VoiceProfile currentSelection() {
        String voice = "";
        int pos = voiceSpinner.getSelectedItemPosition();
        if (pos > 0 && pos - 1 < voiceNames.size()) {
            voice = voiceNames.get(pos - 1);
        }
        return new VoiceProfile(voice, fromProgress(pitchBar.getProgress()),
                fromProgress(rateBar.getProgress()));
    }

    /** 0..150 progress ↔ 0.5..2.0 value. */
    private static int toProgress(float v) {
        return Math.round((v - VoiceProfile.MIN_PITCH) / (VoiceProfile.MAX_PITCH - VoiceProfile.MIN_PITCH) * 150);
    }

    private static float fromProgress(int p) {
        return VoiceProfile.MIN_PITCH + (p / 150f) * (VoiceProfile.MAX_PITCH - VoiceProfile.MIN_PITCH);
    }

    private void updatePitchLabel() {
        if (pitchLabel != null && pitchBar != null) {
            pitchLabel.setText(String.format("Pitch: %.2f", fromProgress(pitchBar.getProgress())));
        }
    }

    private void updateRateLabel() {
        if (rateLabel != null && rateBar != null) {
            rateLabel.setText(String.format("Speed: %.2f", fromProgress(rateBar.getProgress())));
        }
    }

    @Override
    protected void onDestroy() {
        if (speaker != null) speaker.shutdown();
        super.onDestroy();
    }
}
