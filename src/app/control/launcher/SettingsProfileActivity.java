package app.control.launcher;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

/**
 * Profile & backup detail (Settings hub → Profile & backup): the
 * display name, contacts, and the encrypted export / import. Moved
 * out of the old Settings screen — behavior unchanged.
 */
public class SettingsProfileActivity extends ThemedActivity {

    @Override
    protected boolean recreateOnThemeChange() {
        // Theme change: a rebuild would lose the half-typed name; the new theme
        // applies the next time this screen opens.
        return false;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(Ui.BG);
        LinearLayout root = Ui.column(this);
        int pad = Ui.dp(this, 20);
        root.setPadding(pad, Ui.dp(this, 20), pad, Ui.dp(this, 24));
        scroll.addView(root);
        setContentView(scroll);

        root.addView(Ui.screenTitle(this, "Profile & backup"));
        root.addView(Ui.body(this,
                "Favorites, mutes, filter, browser, contacts, chat channels — "
                + "everything lives in one profile file that belongs to you. "
                + "Export it (always encrypted) and import it on a new phone "
                + "to bring your whole setup with you."));
        root.addView(Ui.sectionLabel(this, "Identity key"));
        TextView keyLine = Ui.text(this,
                "You are your profile key · " + Profile.relayShortFingerprint(this),
                14, Ui.ACCENT);
        keyLine.setTypeface(android.graphics.Typeface.MONOSPACE);
        root.addView(keyLine, Ui.wrap(0, 4, 0, 0, this));
        root.addView(Ui.body(this,
                "Chat and walkie have no accounts: this Ed25519 key IS the "
                + "identity. Your identity key travels only inside the "
                + "encrypted profile file; anyone holding it is you. There "
                + "is no recovery — lose the key (and every export) and the "
                + "identity is gone; a fresh profile is a new identity that "
                + "members must re-add."));
        TextView nameLabel = Ui.body(this, "Your name (chat + walkie-talkie):");
        root.addView(nameLabel, Ui.wrap(0, 12, 0, 0, this));
        LinearLayout nameRow = new LinearLayout(this);
        nameRow.setOrientation(LinearLayout.HORIZONTAL);
        EditText nameField = new EditText(this);
        nameField.setText(Profile.displayName(this).equals("Someone")
                ? "" : Profile.displayName(this));
        nameField.setHint("e.g. Alex");
        nameField.setTextColor(Ui.TEXT);
        nameField.setHintTextColor(Ui.DIM);
        nameField.setTextSize(16);
        nameField.setSingleLine(true);
        nameField.setBackgroundColor(Ui.SURFACE);
        nameField.setPadding(Ui.dp(this, 12), Ui.dp(this, 8), Ui.dp(this, 12), Ui.dp(this, 8));
        nameRow.addView(nameField, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        Button saveName = Ui.button(this, "Save", false);
        saveName.setOnClickListener(v -> {
            Profile.setDisplayName(this, nameField.getText().toString().trim());
            Toast.makeText(this, "Name saved", Toast.LENGTH_SHORT).show();
        });
        nameRow.addView(saveName);
        root.addView(nameRow, Ui.wrap(0, 6, 0, 0, this));

        Button contacts = Ui.button(this, "Contacts", false);
        contacts.setOnClickListener(v -> startActivity(new Intent(this, ContactsActivity.class)));
        root.addView(contacts, Ui.wrap(0, 8, 0, 0, this));
        Button exportBtn = Ui.button(this, "Export profile (encrypted)", true);
        exportBtn.setOnClickListener(v -> startActivity(new Intent(this, TransferActivity.class)));
        root.addView(exportBtn, Ui.wrap(0, 8, 0, 0, this));
        Button importBtn = Ui.button(this, "Import profile", false);
        importBtn.setOnClickListener(v -> startActivity(new Intent(this, ImportActivity.class)));
        root.addView(importBtn, Ui.wrap(0, 8, 0, 0, this));
    }
}
