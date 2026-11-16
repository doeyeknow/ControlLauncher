package app.control.launcher;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.nfc.NdefMessage;
import android.nfc.NdefRecord;
import android.nfc.NfcAdapter;
import android.nfc.Tag;
import android.nfc.tech.Ndef;
import android.os.Bundle;
import android.util.Base64;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

/**
 * Import. Accepts: an encrypted profile file (passphrase mode, or key
 * mode unlocked by a scanned/tapped/pasted key), or a QR that carries a
 * whole small profile. Key mode verifies the file's SHA-256 fingerprint
 * against the key payload before decrypting.
 */
public class ImportActivity extends ThemedActivity {

    @Override
    protected boolean recreateOnThemeChange() {
        // Theme change: a rebuild would lose the in-progress import (passphrase/key state); the new theme
        // applies the next time this screen opens.
        return false;
    }

    private static final int REQ_PICK_FILE = 7301;
    private static final int REQ_SCAN = 7302;

    private TextView status;
    private LinearLayout passphraseBox;
    private EditText passphraseField;
    private LinearLayout keyBox;
    private EditText pasteField;

    private JSONObject pendingEnvelope;
    private String pendingKeyPayload;

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

        root.addView(Ui.screenTitle(this, "Import profile"));
        root.addView(Ui.body(this,
                "Bring a profile from another phone. Importing replaces this "
                + "phone's current profile — including its identity key: "
                + "after import, this phone IS the imported identity, and "
                + "its chat and walkie memberships come with it. There is "
                + "no sign-in to redo, because there is no account."));

        status = Ui.text(this, "", 14, Ui.ACCENT);
        root.addView(status, Ui.wrap(0, 10, 0, 0, this));

        Button pick = Ui.button(this, "Pick the encrypted profile file", true);
        pick.setOnClickListener(v -> {
            Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            i.addCategory(Intent.CATEGORY_OPENABLE);
            i.setType("*/*");
            startActivityForResult(i, REQ_PICK_FILE);
        });
        root.addView(pick, Ui.wrap(0, 14, 0, 0, this));

        Button scan = Ui.button(this, "Scan a QR code", false);
        scan.setOnClickListener(v ->
                startActivityForResult(new Intent(this, ScanActivity.class), REQ_SCAN));
        root.addView(scan, Ui.wrap(0, 8, 0, 0, this));

        Button nfc = Ui.button(this, "Read the key from an NFC tag", false);
        nfc.setOnClickListener(v -> armNfcRead());
        root.addView(nfc, Ui.wrap(0, 8, 0, 0, this));

        // Passphrase prompt (shown when a passphrase file is picked)
        passphraseBox = Ui.column(this);
        passphraseBox.setVisibility(View.GONE);
        passphraseBox.addView(Ui.section(this, "Passphrase"));
        passphraseField = new EditText(this);
        passphraseField.setHint("Passphrase for this file");
        passphraseField.setTextColor(Ui.TEXT);
        passphraseField.setHintTextColor(Ui.DIM);
        passphraseField.setTextSize(16);
        passphraseField.setSingleLine(true);
        passphraseField.setInputType(android.text.InputType.TYPE_CLASS_TEXT
                | android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD);
        passphraseField.setBackgroundColor(Ui.SURFACE);
        passphraseField.setPadding(Ui.dp(this, 12), Ui.dp(this, 10),
                Ui.dp(this, 12), Ui.dp(this, 10));
        passphraseBox.addView(passphraseField);
        Button decryptPw = Ui.button(this, "Decrypt and import", true);
        decryptPw.setOnClickListener(v -> {
            char[] pw = passphraseField.getText().toString().toCharArray();
            if (pw.length == 0 || pendingEnvelope == null) return;
            status.setText("Decrypting…");
            new Thread(() -> {
                try {
                    String plain = ProfileCrypto.decryptWithPassphrase(pendingEnvelope, pw);
                    runOnUiThread(() -> install(plain));
                } catch (Exception e) {
                    runOnUiThread(() -> status.setText(
                            "That passphrase didn't work for this file."));
                }
            }).start();
        });
        passphraseBox.addView(decryptPw, Ui.wrap(0, 8, 0, 0, this));
        root.addView(passphraseBox);

        // Key unlock (shown when a key-mode file is picked, or a key arrives first)
        keyBox = Ui.column(this);
        keyBox.setVisibility(View.GONE);
        keyBox.addView(Ui.section(this, "Unlock with the key"));
        keyBox.addView(Ui.body(this,
                "Scan the transfer QR, read the NFC tag, or paste the key "
                + "payload here. The key checks the file's fingerprint "
                + "before anything is decrypted."));
        pasteField = new EditText(this);
        pasteField.setHint("Paste key payload");
        pasteField.setTextColor(Ui.TEXT);
        pasteField.setHintTextColor(Ui.DIM);
        pasteField.setTextSize(14);
        pasteField.setBackgroundColor(Ui.SURFACE);
        pasteField.setPadding(Ui.dp(this, 12), Ui.dp(this, 10),
                Ui.dp(this, 12), Ui.dp(this, 10));
        keyBox.addView(pasteField, Ui.wrap(0, 8, 0, 0, this));
        Button usePaste = Ui.button(this, "Use pasted key", true);
        usePaste.setOnClickListener(v -> onKeyPayload(pasteField.getText().toString().trim()));
        keyBox.addView(usePaste, Ui.wrap(0, 8, 0, 0, this));
        Button scanKey = Ui.button(this, "Scan the key QR", false);
        scanKey.setOnClickListener(v ->
                startActivityForResult(new Intent(this, ScanActivity.class), REQ_SCAN));
        keyBox.addView(scanKey, Ui.wrap(0, 8, 0, 0, this));
        Button nfcKey = Ui.button(this, "Read the key from an NFC tag", false);
        nfcKey.setOnClickListener(v -> armNfcRead());
        keyBox.addView(nfcKey, Ui.wrap(0, 8, 0, 0, this));
        root.addView(keyBox);
    }

    // ---- inputs ----

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode != RESULT_OK || data == null) return;
        if (requestCode == REQ_SCAN) {
            String text = data.getStringExtra(ScanActivity.RESULT_TEXT);
            if (text != null) onScanned(text);
        } else if (requestCode == REQ_PICK_FILE && data.getData() != null) {
            readPickedFile(data.getData());
        }
    }

    private void readPickedFile(Uri uri) {
        new Thread(() -> {
            try {
                String text;
                try (InputStream in = getContentResolver().openInputStream(uri);
                     BufferedReader r = new BufferedReader(
                             new InputStreamReader(in, StandardCharsets.UTF_8))) {
                    StringBuilder sb = new StringBuilder();
                    char[] buf = new char[8192];
                    int n;
                    while ((n = r.read(buf)) > 0) sb.append(buf, 0, n);
                    text = sb.toString();
                }
                JSONObject o = new JSONObject(text);
                runOnUiThread(() -> onEnvelope(o));
            } catch (Exception e) {
                runOnUiThread(() -> status.setText(
                        "That file isn't a Control profile export."));
            }
        }).start();
    }

    private void onScanned(String text) {
        // Keys travel by sound since v1.8 — a key QR scanned here no
        // longer joins or files anything. Name what it was and point
        // at the screen that listens for it; only profiles still
        // arrive by QR (below).
        if (ChannelInvite.isInvite(text)) {
            status.setText("That's a channel invite, and invites travel by "
                    + "sound now — ask the other phone to play the join "
                    + "sound and listen from the Walkie page (Listen for "
                    + "invite). This screen only takes profile codes.");
            return;
        }
        if (ContactCard.parse(text) != null) {
            status.setText("That's a contact card, and cards travel by "
                    + "sound now — ask the other phone to play it and listen "
                    + "from Contacts (Listen for a card). This screen only "
                    + "takes profile codes.");
            return;
        }
        if (ChatPage.parseChatKey(text) != null) {
            status.setText("That's a chat key, and keys travel by sound "
                    + "now — ask the other phone to play it and listen from "
                    + "Chat (Listen). This screen only takes profile codes.");
            return;
        }
        try {
            JSONObject o = new JSONObject(text);
            if (ProfileCrypto.isQrFullPayload(o)) {
                status.setText("Decrypting…");
                new Thread(() -> {
                    try {
                        String plain = ProfileCrypto.decryptQrPayload(o);
                        runOnUiThread(() -> install(plain));
                    } catch (Exception e) {
                        runOnUiThread(() -> status.setText(
                                "That QR didn't decrypt — is it a Control profile QR?"));
                    }
                }).start();
            } else if (ProfileCrypto.isKeyPayload(o)) {
                onKeyPayload(text);
            } else if (ProfileCrypto.isEnvelope(o)) {
                onEnvelope(o);
            } else {
                status.setText("That QR isn't a Control profile code.");
            }
        } catch (Exception e) {
            status.setText("That QR isn't a Control profile code.");
        }
    }

    private void onEnvelope(JSONObject env) {
        if (!ProfileCrypto.isEnvelope(env)) {
            status.setText("That file isn't an encrypted Control export.");
            return;
        }
        pendingEnvelope = env;
        String mode = env.optString("mode", "");
        passphraseBox.setVisibility("passphrase".equals(mode) ? View.VISIBLE : View.GONE);
        keyBox.setVisibility("key".equals(mode) ? View.VISIBLE : View.GONE);
        if ("passphrase".equals(mode)) {
            status.setText("File loaded. Enter its passphrase.");
        } else if ("key".equals(mode)) {
            status.setText("File loaded. It needs its key — scan, tap, or paste it.");
            if (pendingKeyPayload != null) onKeyPayload(pendingKeyPayload);
        } else {
            status.setText("Unknown export mode in that file.");
        }
    }

    private void onKeyPayload(String text) {
        try {
            JSONObject payload = new JSONObject(text);
            if (!ProfileCrypto.isKeyPayload(payload)) {
                status.setText("That isn't a Control key.");
                return;
            }
            pendingKeyPayload = text;
            if (pendingEnvelope == null) {
                keyBox.setVisibility(View.VISIBLE);
                status.setText("Key received. Now pick the encrypted profile file.");
                return;
            }
            String expected = payload.getString("h");
            String actual = ProfileCrypto.ciphertextHash(pendingEnvelope);
            if (!expected.equals(actual)) {
                status.setText("This key doesn't match the file (fingerprint "
                        + "mismatch) — nothing was imported.");
                return;
            }
            byte[] keyBytes = Base64.decode(payload.getString("k"), Base64.DEFAULT);
            status.setText("Fingerprint matches. Decrypting…");
            new Thread(() -> {
                try {
                    String plain = ProfileCrypto.decryptWithKey(pendingEnvelope, keyBytes);
                    runOnUiThread(() -> install(plain));
                } catch (Exception e) {
                    runOnUiThread(() -> status.setText("Decryption failed."));
                }
            }).start();
        } catch (Exception e) {
            status.setText("That isn't a Control key.");
        }
    }

    // ---- NFC ----

    private void armNfcRead() {
        NfcAdapter adapter = NfcAdapter.getDefaultAdapter(this);
        if (adapter == null) {
            status.setText("This phone has no NFC.");
            return;
        }
        if (!adapter.isEnabled()) {
            status.setText("NFC is off — turn it on in system settings.");
            return;
        }
        status.setText("Hold the tag against the back of the phone…");
        adapter.enableReaderMode(this, tag -> {
            String text = null;
            try {
                Ndef ndef = Ndef.get(tag);
                if (ndef != null) {
                    ndef.connect();
                    NdefMessage msg = ndef.getNdefMessage();
                    ndef.close();
                    if (msg != null && msg.getRecords().length > 0) {
                        text = textFromRecord(msg.getRecords()[0]);
                    }
                }
            } catch (Exception ignored) {
            }
            final String payload = text;
            runOnUiThread(() -> {
                if (payload != null) onKeyPayload(payload);
                else status.setText("Couldn't read a key from that tag.");
            });
        }, NfcAdapter.FLAG_READER_NFC_A | NfcAdapter.FLAG_READER_NFC_B
                | NfcAdapter.FLAG_READER_SKIP_NDEF_CHECK, null);
    }

    private static String textFromRecord(NdefRecord record) {
        byte[] payload = record.getPayload();
        if (payload.length == 0) return null;
        // RTD_TEXT: first byte = status (bit 7 UTF-8 flag, low 6 bits = language length)
        int langLen = payload[0] & 0x3F;
        boolean utf16 = (payload[0] & 0x80) != 0;
        try {
            return new String(payload, 1 + langLen, payload.length - 1 - langLen,
                    utf16 ? StandardCharsets.UTF_16 : StandardCharsets.UTF_8);
        } catch (Exception e) {
            return null;
        }
    }

    // ---- install ----

    private void install(String plainJson) {
        try {
            JSONObject profile = new JSONObject(plainJson);
            String error = Profile.install(this, profile);
            if (error != null) {
                status.setText(error);
                return;
            }
            // The identity key DID travel — it is inside the profile,
            // by design: after import, this phone IS the imported
            // identity (chat and walkie memberships come with it).
            // Clear any stale "registered" flag so the key registers
            // fresh with the relay under its own name.
            Prefs.get(this).edit()
                    .remove(Prefs.KEY_RELAY_REGISTERED)
                    .remove(Prefs.KEY_RELAY_REG_ATTEMPT)
                    .putBoolean(Prefs.KEY_FIRSTRUN_DONE, true)
                    .apply();
            Toast.makeText(this,
                    "Profile imported — this phone now carries that "
                            + "profile's identity key.",
                    Toast.LENGTH_LONG).show();
            // Setup (v1.15) starts this activity for a result and
            // advances on RESULT_OK. Standalone callers ignore
            // the result, so this changes nothing for them.
            setResult(RESULT_OK);
            finish();
        } catch (Exception e) {
            status.setText("The decrypted file wasn't a valid profile.");
        }
    }
}
