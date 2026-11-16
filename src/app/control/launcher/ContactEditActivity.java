package app.control.launcher;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

public class ContactEditActivity extends ThemedActivity {

    @Override
    protected boolean recreateOnThemeChange() {
        // Theme change: a rebuild would lose the half-typed contact form; the new theme
        // applies the next time this screen opens.
        return false;
    }

    private int index;
    private EditText name, relayKey, phone, email, notes, meshKey;
    private Switch controller;
    private JSONObject existing;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        index = getIntent().getIntExtra("index", -1);

        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(Ui.BG);
        LinearLayout root = Ui.column(this);
        int pad = Ui.dp(this, 20);
        root.setPadding(pad, Ui.dp(this, 20), pad, Ui.dp(this, 24));
        scroll.addView(root);
        setContentView(scroll);

        root.addView(Ui.screenTitle(this, index < 0 ? "Add contact" : "Contact"));

        existing = null;
        if (index >= 0) {
            JSONArray arr = Profile.contacts(this);
            if (index < arr.length()) existing = arr.optJSONObject(index);
        }
        name = field(root, "Name", existing, "name");
        relayKey = field(root, "Relay public key (from their contact card)", existing, "relayKey");
        if (existing == null || existing.optString("relayKey", "").isEmpty()) {
            TextView noKey = Ui.text(this,
                    "No relay key yet — re-exchange a contact card to chat. "
                    + "The key fills itself in when you listen for their card "
                    + "(Contacts → Listen for a card); typing it by hand works too.",
                    13, Ui.SIGNAL);
            root.addView(noKey, Ui.wrap(0, 2, 0, 0, this));
        } else {
            String dmState = existing.optString("dmKey", "").isEmpty()
                    ? "Relay key on file. No DM key yet — the exchange card "
                      + "below creates one for this contact."
                    : "Relay key + DM key on file — direct messages are ready.";
            root.addView(Ui.text(this, dmState, 13, Ui.DIM), Ui.wrap(0, 2, 0, 0, this));
        }
        phone = field(root, "Phone", existing, "phone");
        email = field(root, "Email", existing, "email");
        meshKey = field(root, "Walkie-talkie channel key (optional)", existing, "meshKey");
        notes = field(root, "Notes", existing, "notes");
        controller = new Switch(this);
        controller.setText("Controller — may send authenticated SMS commands to Control");
        controller.setTextColor(Ui.TEXT);
        controller.setTextSize(15);
        controller.setChecked(existing != null && existing.optBoolean("controller", false));
        controller.setPadding(0, Ui.dp(this, 14), 0, Ui.dp(this, 4));
        root.addView(controller);
        root.addView(Ui.body(this,
                "A controller still needs the command secret. SMS is authenticated, "
                + "not encrypted — carriers can read texts."));

        Button save = Ui.button(this, "Save contact", true);
        save.setOnClickListener(v -> {
            if (name.getText().toString().trim().isEmpty()) {
                Toast.makeText(this, "A contact needs a name.", Toast.LENGTH_SHORT).show();
                return;
            }
            save();
            finish();
        });
        root.addView(save, Ui.wrap(0, 18, 0, 0, this));

        if (existing != null && !existing.optString("relayKey", "").isEmpty()) {
            final String peerPub = existing.optString("relayKey");
            final String dmKey = existing.optString("dmKey", "");
            final String existingName = existing.optString("name", "Chat");
            Button msg = Ui.button(this, "Message", true);
            msg.setOnClickListener(v -> startDm(peerPub, dmKey, existingName));
            root.addView(msg, Ui.wrap(0, 8, 0, 0, this));
        }
        if (existing != null) {
            final String existingName = existing.optString("name", "them");
            Button card = Ui.button(this, "Exchange card for " + existingName, false);
            card.setOnClickListener(v -> showExchangeCard(existingName));
            root.addView(card, Ui.wrap(0, 8, 0, 0, this));
            // v1.11: the synthetic voice this contact's low-data
            // walkie messages are read in (never their real voice).
            Button voice = Ui.button(this, "Walkie voice profile", false);
            voice.setOnClickListener(v -> {
                save();
                Intent i = new Intent(this, VoiceProfileActivity.class);
                i.putExtra("index", index);
                startActivity(i);
            });
            root.addView(voice, Ui.wrap(0, 8, 0, 0, this));
        }
        if (index >= 0) {
            Button del = Ui.button(this, "Delete contact", false);
            del.setOnClickListener(v -> {
                JSONArray arr = Profile.contacts(this);
                JSONArray next = new JSONArray();
                for (int i = 0; i < arr.length(); i++) {
                    if (i != index) next.put(arr.opt(i));
                }
                Profile.saveContacts(this, next);
                finish();
            });
            root.addView(del, Ui.wrap(0, 8, 0, 0, this));
        }
    }

    private EditText field(LinearLayout root, String hint, JSONObject from, String key) {
        EditText e = new EditText(this);
        e.setHint(hint);
        if (from != null) e.setText(from.optString(key, ""));
        e.setTextColor(Ui.TEXT);
        e.setHintTextColor(Ui.DIM);
        e.setTextSize(16);
        e.setSingleLine(true);
        e.setBackground(Ui.roundedStroke(this, Ui.SURFACE, 12, Ui.LINE));
        e.setPadding(Ui.dp(this, 12), Ui.dp(this, 10), Ui.dp(this, 12), Ui.dp(this, 10));
        root.addView(e, Ui.wrap(0, 10, 0, 0, this));
        return e;
    }

    private void save() {
        try {
            // Start from the stored record so fields the form doesn't
            // show (dmKey, legacy matrixId, anything future) survive.
            JSONObject c = existing != null
                    ? new JSONObject(existing.toString()) : new JSONObject();
            c.put("name", name.getText().toString().trim());
            c.put("relayKey", relayKey.getText().toString().trim());
            c.put("phone", phone.getText().toString().trim());
            c.put("email", email.getText().toString().trim());
            c.put("meshKey", meshKey.getText().toString().trim());
            c.put("notes", notes.getText().toString().trim());
            c.put("controller", controller.isChecked());
            JSONArray arr = Profile.contacts(this);
            JSONArray next = new JSONArray();
            for (int i = 0; i < arr.length(); i++) {
                next.put(i == index ? c : arr.get(i));
            }
            if (index < 0) next.put(c);
            Profile.saveContacts(this, next);
            existing = c;
        } catch (Exception ignored) {
        }
    }

    /** The per-contact exchange card: carries MY relay key + the DM
     *  key stored for THIS contact (generated on first use). Because
     *  an existing DM key is never overwritten (first card wins), the
     *  other side's card for me carries the same key back. */
    private void showExchangeCard(String contactName) {
        save(); // make sure the record (and a fresh DM key) persists
        try {
            JSONArray arr = Profile.contacts(this);
            if (index < 0 || index >= arr.length()) return;
            JSONObject contact = arr.getJSONObject(index);
            String dm = contact.optString("dmKey", "");
            if (dm.isEmpty()) {
                dm = Profile.newChannelKey();
                contact.put("dmKey", dm);
                Profile.saveContacts(this, arr);
            }
            startActivity(AudioKeyPlayActivity.exchangeCardIntent(
                    this, contactName, dm));
        } catch (Exception e) {
            Toast.makeText(this, "Couldn't build the card.",
                    Toast.LENGTH_LONG).show();
        }
    }

    private void startDm(String peerPub, String dmKey, String display) {
        if (dmKey == null || dmKey.isEmpty()) {
            Toast.makeText(this, "No DM key for this contact yet — play "
                    + "them the exchange card below and have them listen, "
                    + "or listen for theirs. If they already heard YOUR card, "
                    + "the conversation is waiting in Chat.",
                    Toast.LENGTH_LONG).show();
            return;
        }
        String channelId = RelayIds.dmChannelId(dmKey);
        if (channelId == null) {
            Toast.makeText(this, "That DM key isn't usable — re-exchange "
                    + "a contact card.", Toast.LENGTH_LONG).show();
            return;
        }
        Intent i = new Intent(this, RoomActivity.class);
        i.putExtra("channelId", channelId);
        i.putExtra("channelKey", dmKey);
        i.putExtra("name", display);
        i.putExtra("peerPub", peerPub);
        startActivity(i);
    }
}
