package app.control.launcher;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Bitmap;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.MultiFormatWriter;
import com.google.zxing.common.BitMatrix;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Configure one channel slot: label, open/private mode, the private
 * key (the privacy-code equivalent), member contacts, and the join
 * sound. Internet membership runs over the Control Relay: checked
 * members who carry a relay key are added by signed member-add when
 * the channel is bound (see WalkiePage). Everything is optional —
 * an untouched slot is an open channel.
 */
public class ChannelEditActivity extends ThemedActivity {

    @Override
    protected boolean recreateOnThemeChange() {
        // Theme change: a rebuild would lose the half-typed channel form; the new theme
        // applies the next time this screen opens.
        return false;
    }

    private int number;
    private JSONObject slot;
    private EditText labelInput;
    private Switch privateSwitch;
    private LinearLayout privateBox;
    private TextView keyText;
    private final List<CheckBox> memberBoxes = new ArrayList<>();
    private final List<JSONObject> memberContacts = new ArrayList<>();
    private TextView relayText;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        number = getIntent().getIntExtra("number", 1);
        slot = Profile.slot(this, number);

        LinearLayout root = Ui.column(this);
        int pad = Ui.dp(this, 16);
        root.setPadding(pad, pad, pad, pad);
        android.widget.ScrollView scroll = new android.widget.ScrollView(this);
        scroll.addView(root);
        setContentView(scroll);

        root.addView(Ui.screenTitle(this, "Channel " + number));
        root.addView(Ui.body(this, TunerView.frequencyText(number)
                + " — the number is the channel's identity, like a real radio."));

        root.addView(Ui.sectionLabel(this, "Label"));
        labelInput = Ui.input(this, "Label (optional), e.g. Worksite");
        labelInput.setText(slot.optString("label", ""));
        root.addView(labelInput, Ui.wrap(0, 0, 0, 4, this));

        root.addView(Ui.sectionLabel(this, "Mode"));
        privateSwitch = new Switch(this);
        privateSwitch.setText("Private — only members with this channel's key");
        privateSwitch.setTextColor(Ui.TEXT);
        privateSwitch.setTextSize(15);
        privateSwitch.setChecked(Profile.isPrivate(slot));
        root.addView(privateSwitch, Ui.wrap(0, 0, 0, 4, this));
        root.addView(Ui.body(this,
                "Open: any nearby device tuned to channel " + number + " can join, "
                + "like a real radio channel. Private: the key below works like a "
                + "privacy code — same number, different group."));

        privateBox = Ui.column(this);
        root.addView(privateBox);

        keyText = Ui.body(this, "");
        privateBox.addView(keyText);
        Button newKey = Ui.button(this, "Generate a new key", false);
        newKey.setOnClickListener(v -> {
            try {
                slot.put("key", Profile.newChannelKey());
            } catch (Exception ignored) {
            }
            refreshKey();
            Toast.makeText(this, "New key. Members need the new invite.",
                    Toast.LENGTH_LONG).show();
        });
        privateBox.addView(newKey, Ui.wrap(0, 6, 0, 0, this));

        Button invite = Ui.button(this, "Play join sound for this channel", false);
        invite.setOnClickListener(v -> showInvite());
        privateBox.addView(invite, Ui.wrap(0, 6, 0, 0, this));

        privateBox.addView(Ui.sectionLabel(this, "Members (your contacts)"));
        JSONArray members = slot.optJSONArray("members");
        Set<String> memberIds = new HashSet<>();
        if (members != null) {
            for (int i = 0; i < members.length(); i++) memberIds.add(members.optString(i));
        }
        for (JSONObject contact : Profile.contactList(this)) {
            String pub = contact.optString("relayKey", "");
            String name = contact.optString("name", "");
            CheckBox cb = new CheckBox(this);
            cb.setText(name + (pub.isEmpty()
                    ? " (no relay key yet — exchange a contact card to add them on the internet road)"
                    : " · key " + shortKey(pub)));
            cb.setTextColor(Ui.TEXT);
            cb.setTextSize(15);
            cb.setChecked(memberIds.contains(pub.isEmpty() ? "name:" + name : pub));
            memberBoxes.add(cb);
            memberContacts.add(contact);
            privateBox.addView(cb);
        }
        if (Profile.contactList(this).isEmpty()) {
            privateBox.addView(Ui.body(this,
                    "No contacts yet — add some in Settings → Contacts."));
        }
        privateSwitch.setOnCheckedChangeListener((bv, checked) -> {
            privateBox.setVisibility(checked ? View.VISIBLE : View.GONE);
            if (checked && slot.optString("key", "").isEmpty()) {
                try {
                    slot.put("key", Profile.newChannelKey());
                } catch (Exception ignored) {
                }
                refreshKey();
            }
        });
        privateBox.setVisibility(Profile.isPrivate(slot) ? View.VISIBLE : View.GONE);
        refreshKey();

        root.addView(Ui.sectionLabel(this, "Internet (Control Relay)"));
        relayText = Ui.body(this, "");
        root.addView(relayText);
        refreshRelay();

        // v1.19: subscribing listens to this channel in the
        // background over the relay. It applies immediately
        // (it is not part of the slot form the Save button
        // writes) and follows the channel number.
        Switch subscribeSwitch = new Switch(this);
        subscribeSwitch.setText("Listen to this channel in the background");
        subscribeSwitch.setTextColor(Ui.TEXT);
        subscribeSwitch.setTextSize(15);
        subscribeSwitch.setChecked(ChannelSubscriptions.isSubscribed(this, number));
        subscribeSwitch.setOnCheckedChangeListener((bv, on) -> {
            ChannelSubscriptions.setSubscribed(this, number, on);
            if (on) {
                // Membership sync against the SAVED slot config
                // (the form is not saved as a side effect of
                // this toggle — unsaved edits take effect when
                // the user taps Save, and the next tune runs
                // the sync again with the new config).
                JSONObject saved = Profile.slot(this, number);
                String id = RelayIds.walkieChannelId(
                        RelayIds.wireNumber(number, saved.optString("wireId", "")),
                        Profile.isPrivate(saved), saved.optString("key", ""));
                RelayMembership.sync(this, saved, id);
                SubscriptionService.reconcile(this);
                SubscriptionService.kick(this);
            } else {
                SubscriptionService.reconcile(this);
            }
            Toast.makeText(this, on
                    ? "Channel " + number + " will keep arriving in the background."
                    : "Channel " + number + " will only be heard while tuned here.",
                    Toast.LENGTH_SHORT).show();
        });
        root.addView(subscribeSwitch, Ui.wrap(0, 8, 0, 0, this));
        root.addView(Ui.body(this,
                "Applies right away — no need to tap Save. Background "
                + "listening uses a little data and battery, and more "
                + "subscriptions means each channel is checked less "
                + "often. Private channels need relay membership: if "
                + "this phone isn't a member, the radio screen says "
                + "so and background listening waits."));

        Button save = Ui.button(this, "Save channel", true);
        save.setOnClickListener(v -> {
            saveSlot();
            Toast.makeText(this, "Channel " + number + " saved.", Toast.LENGTH_SHORT).show();
            finish();
        });
        root.addView(save, Ui.wrap(0, 16, 0, 0, this));

        Button reset = Ui.button(this, "Reset to open, unlabeled default", false);
        reset.setOnClickListener(v -> {
            Profile.resetSlot(this, number);
            Toast.makeText(this, "Channel " + number + " reset.", Toast.LENGTH_SHORT).show();
            finish();
        });
        root.addView(reset, Ui.wrap(0, 8, 0, 0, this));
    }

    private void refreshKey() {
        String key = slot.optString("key", "");
        keyText.setText(key.isEmpty() ? "No key yet."
                : "Channel key: " + (key.length() > 18 ? key.substring(0, 18) + "…" : key)
                + "\nShare it only via the join sound or a profile exchange.");
    }

    private static String shortKey(String pubB64) {
        try {
            return RelayKeys.fingerprint(RelayKeys.b64UrlDecode(pubB64));
        } catch (Exception e) {
            return "…";
        }
    }

    private void refreshRelay() {
        if (Profile.isPrivate(slot)) {
            String id = RelayIds.walkieChannelId(
                    RelayIds.wireNumber(number, slot.optString("wireId", "")),
                    true, slot.optString("key", ""));
            relayText.setText("On the internet road this channel rides the "
                    + "Control relay as " + (id == null ? "(no valid key)" : id)
                    + ". Checked members with relay keys are added over the "
                    + "relay when the channel is tuned; bursts and texts are "
                    + "sealed with the channel key, and the relay stores "
                    + "ciphertext only.");
        } else {
            relayText.setText("Open channels ride the relay as open:" + number
                    + " — public by design, no membership and no sealing, "
                    + "like a real radio channel.");
        }
    }

    private void saveSlot() {
        try {
            slot.put("label", labelInput.getText().toString().trim());
            slot.put("mode", privateSwitch.isChecked() ? "private" : "open");
            JSONArray members = new JSONArray();
            for (int i = 0; i < memberBoxes.size(); i++) {
                if (!memberBoxes.get(i).isChecked()) continue;
                JSONObject contact = memberContacts.get(i);
                String pub = contact.optString("relayKey", "");
                members.put(pub.isEmpty() ? "name:" + contact.optString("name", "") : pub);
            }
            slot.put("members", members);
        } catch (Exception ignored) {
        }
        Profile.saveSlot(this, slot);
    }

    private void showInvite() {
        saveSlot();
        // The join sound screen (same screen the Walkie page's
        // "Share join sound" opens), with the "this sound is the
        // key" warning on it.
        startActivity(AudioKeyPlayActivity.channelIntent(this, slot));
    }
}
