package app.control.launcher;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Bitmap;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONObject;

import java.util.List;

/** The launcher's own contacts — part of the profile, not the phone's.
 *  v1.3 adds the contact-card exchange: my card carries my relay
 *  public key + card DM key (PROTOCOL §5.1); hearing someone's card
 *  files both on their contact. Cards travel by sound since v1.8. */
public class ContactsActivity extends ThemedActivity {

    private static final int REQ_LISTEN = 7500;

    private List<JSONObject> contacts;
    private ContactAdapter adapter;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        LinearLayout root = Ui.column(this);
        int pad = Ui.dp(this, 20);
        root.setPadding(pad, Ui.dp(this, 20), pad, Ui.dp(this, 16));
        setContentView(root);

        root.addView(Ui.screenTitle(this, "Contacts"));
        root.addView(Ui.body(this,
                "Your people, stored in your profile — they travel with it "
                + "to your next phone. Exchange contact cards to swap relay "
                + "keys: the card carries your public key and a DM key, so "
                + "chat works with no accounts anywhere."));

        LinearLayout exchange = new LinearLayout(this);
        exchange.setOrientation(LinearLayout.HORIZONTAL);
        Button myCard = Ui.button(this, "My contact card", true);
        myCard.setOnClickListener(v ->
                startActivity(AudioKeyPlayActivity.myCardIntent(this)));
        exchange.addView(myCard, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        Button listenCard = Ui.button(this, "Listen for a card", false);
        listenCard.setOnClickListener(v ->
                startActivityForResult(AudioKeyListenActivity.contactIntent(this),
                        REQ_LISTEN));
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        sp.setMargins(Ui.dp(this, 8), 0, 0, 0);
        exchange.addView(listenCard, sp);
        root.addView(exchange, Ui.wrap(0, 10, 0, 0, this));

        ListView list = new ListView(this);
        list.setDivider(null);
        adapter = new ContactAdapter();
        list.setAdapter(adapter);
        list.setOnItemClickListener((parent, view, pos, id) -> {
            Intent i = new Intent(this, ContactEditActivity.class);
            i.putExtra("index", pos);
            startActivity(i);
        });
        root.addView(list, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        Button add = Ui.button(this, "Add contact", true);
        add.setOnClickListener(v -> {
            Intent i = new Intent(this, ContactEditActivity.class);
            i.putExtra("index", -1);
            startActivity(i);
        });
        root.addView(add, Ui.wrap(0, 10, 0, 0, this));
    }

    @Override
    protected void onResume() {
        super.onResume();
        contacts = Profile.contactList(this);
        adapter.notifyDataSetChanged();
        if (contacts.isEmpty()) {
            // adapter renders one hint row in that case
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQ_LISTEN || resultCode != RESULT_OK || data == null) return;
        String text = data.getStringExtra(AudioKeyListenActivity.RESULT_TEXT);
        JSONObject card = ContactCard.parse(text);
        if (card == null) {
            Toast.makeText(this, "That sound wasn't a contact card.",
                    Toast.LENGTH_LONG).show();
            return;
        }
        Toast.makeText(this, ContactCard.apply(this, card), Toast.LENGTH_LONG).show();
        contacts = Profile.contactList(this);
        adapter.notifyDataSetChanged();
    }

    private static String shortKey(String pubB64) {
        try {
            return RelayKeys.fingerprint(RelayKeys.b64UrlDecode(pubB64));
        } catch (Exception e) {
            return "…";
        }
    }

    private class ContactAdapter extends BaseAdapter {
        @Override public int getCount() { return contacts == null ? 0 : Math.max(1, contacts.size()); }
        @Override public Object getItem(int pos) { return contacts.get(pos); }
        @Override public long getItemId(int pos) { return pos; }

        @Override
        public View getView(int pos, View convert, ViewGroup parent) {
            if (contacts == null || contacts.isEmpty()) {
                return Ui.body(ContactsActivity.this, "No contacts yet.");
            }
            JSONObject c = contacts.get(pos);
            LinearLayout row = new LinearLayout(ContactsActivity.this);
            row.setOrientation(LinearLayout.VERTICAL);
            row.setPadding(0, Ui.dp(ContactsActivity.this, 10), 0, Ui.dp(ContactsActivity.this, 10));
            row.addView(Ui.text(ContactsActivity.this,
                    c.optString("name", "Unnamed"), 17, Ui.TEXT));
            String pub = c.optString("relayKey", "");
            if (!pub.isEmpty()) {
                TextView keyLine = Ui.text(ContactsActivity.this,
                        "relay key " + shortKey(pub)
                                + (c.optString("dmKey", "").isEmpty()
                                        ? " · no DM key yet" : " · DM ready"),
                        13, Ui.DIM);
                keyLine.setTypeface(android.graphics.Typeface.MONOSPACE);
                row.addView(keyLine);
            } else {
                row.addView(Ui.text(ContactsActivity.this,
                        "No relay key yet — re-exchange a contact card to chat",
                        13, Ui.SIGNAL));
            }
            return row;
        }
    }
}
