package app.control.launcher;

import android.app.Activity;
import android.app.PendingIntent;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.Uri;
import android.nfc.NdefMessage;
import android.nfc.NdefRecord;
import android.nfc.NfcAdapter;
import android.nfc.Tag;
import android.nfc.tech.Ndef;
import android.nfc.tech.NdefFormatable;
import android.os.Bundle;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONObject;

import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

/**
 * Export. A profile only ever leaves the phone encrypted:
 *  (A) a passphrase-protected file, or
 *  (B) a random-key file whose key travels as a QR code / NFC tag.
 * For small profiles the QR carries everything by itself.
 */
public class TransferActivity extends ThemedActivity {

    @Override
    protected boolean recreateOnThemeChange() {
        // Theme change: a rebuild would lose the in-progress export; the new theme
        // applies the next time this screen opens.
        return false;
    }

    private static final int REQ_SAVE_PASSPHRASE = 7201;
    private static final int REQ_SAVE_ENVELOPE = 7202;

    private TextView status;
    private ImageView qrView;
    private Button saveEnvelopeBtn;
    private Button writeTagBtn;

    private byte[] pendingFileBytes;
    private JSONObject keyEnvelope;
    private byte[] keyBytes;
    private String keyPayloadText;
    private boolean tagWriteArmed;

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

        root.addView(Ui.screenTitle(this, "Export profile"));
        root.addView(Ui.body(this,
                "Your profile is only ever exported encrypted. Your identity "
                + "key travels only inside the encrypted profile file; anyone "
                + "holding it is you — there is no password and no sign-in, "
                + "because the key IS the account. Guard exports like a key: "
                + "whoever decrypts one becomes you on chat and walkie."));

        status = Ui.text(this, "", 14, Ui.ACCENT);
        root.addView(status, Ui.wrap(0, 10, 0, 0, this));

        // ---- A: passphrase file ----
        root.addView(Ui.section(this, "Passphrase file"));
        root.addView(Ui.body(this,
                "One file, protected by a passphrase you choose. Import asks "
                + "for the same passphrase."));
        EditText pw1 = passwordField("Passphrase");
        EditText pw2 = passwordField("Repeat passphrase");
        root.addView(pw1, Ui.wrap(0, 8, 0, 0, this));
        root.addView(pw2, Ui.wrap(0, 8, 0, 0, this));
        Button savePw = Ui.button(this, "Save encrypted file", true);
        savePw.setOnClickListener(v -> {
            String a = pw1.getText().toString();
            String b = pw2.getText().toString();
            if (a.length() < 6) {
                status.setText("Use at least 6 characters.");
                return;
            }
            if (!a.equals(b)) {
                status.setText("The passphrases don't match.");
                return;
            }
            status.setText("Encrypting…");
            new Thread(() -> {
                try {
                    JSONObject env = ProfileCrypto.encryptWithPassphrase(
                            Profile.snapshot(this).toString(), a.toCharArray());
                    pendingFileBytes = env.toString().getBytes(StandardCharsets.UTF_8);
                    runOnUiThread(() -> createDocument(REQ_SAVE_PASSPHRASE));
                } catch (Exception e) {
                    runOnUiThread(() -> status.setText("Failed: " + e.getMessage()));
                }
            }).start();
        });
        root.addView(savePw, Ui.wrap(0, 10, 0, 0, this));

        // ---- B: QR / NFC transfer ----
        root.addView(Ui.section(this, "QR / NFC transfer"));
        root.addView(Ui.body(this,
                "Creates a file protected by a random key. The new phone "
                + "scans the QR (or reads an NFC tag) to get the key, then "
                + "opens the file — the key checks the file's fingerprint "
                + "before decrypting, so a swapped file fails."));
        Button makeQr = Ui.button(this, "Create transfer QR", true);
        makeQr.setOnClickListener(v -> createQrTransfer());
        root.addView(makeQr, Ui.wrap(0, 10, 0, 0, this));

        qrView = new ImageView(this);
        qrView.setBackgroundColor(android.graphics.Color.WHITE);
        int qrSize = Ui.dp(this, 260);
        LinearLayout.LayoutParams qp = new LinearLayout.LayoutParams(qrSize, qrSize);
        qp.gravity = android.view.Gravity.CENTER_HORIZONTAL;
        qp.setMargins(0, Ui.dp(this, 12), 0, Ui.dp(this, 4));
        qrView.setLayoutParams(qp);
        qrView.setVisibility(android.view.View.GONE);
        root.addView(qrView);

        saveEnvelopeBtn = Ui.button(this, "Save the encrypted file", false);
        saveEnvelopeBtn.setVisibility(android.view.View.GONE);
        saveEnvelopeBtn.setOnClickListener(v -> {
            if (keyEnvelope != null) {
                pendingFileBytes = keyEnvelope.toString().getBytes(StandardCharsets.UTF_8);
                createDocument(REQ_SAVE_ENVELOPE);
            }
        });
        root.addView(saveEnvelopeBtn, Ui.wrap(0, 8, 0, 0, this));

        writeTagBtn = Ui.button(this, "Write the key to an NFC tag", false);
        writeTagBtn.setVisibility(android.view.View.GONE);
        writeTagBtn.setOnClickListener(v -> armTagWrite());
        root.addView(writeTagBtn, Ui.wrap(0, 8, 0, 0, this));
    }

    private EditText passwordField(String hint) {
        EditText e = new EditText(this);
        e.setHint(hint);
        e.setTextColor(Ui.TEXT);
        e.setHintTextColor(Ui.DIM);
        e.setTextSize(16);
        e.setSingleLine(true);
        e.setInputType(android.text.InputType.TYPE_CLASS_TEXT
                | android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD);
        e.setBackgroundColor(Ui.SURFACE);
        e.setPadding(Ui.dp(this, 12), Ui.dp(this, 10), Ui.dp(this, 12), Ui.dp(this, 10));
        return e;
    }

    private void createQrTransfer() {
        status.setText("Encrypting…");
        new Thread(() -> {
            try {
                Object[] res = ProfileCrypto.encryptWithRandomKey(
                        Profile.snapshot(this).toString());
                keyEnvelope = (JSONObject) res[0];
                keyBytes = (byte[]) res[1];
                String hash = ProfileCrypto.ciphertextHash(keyEnvelope);
                keyPayloadText = ProfileCrypto.keyPayload(keyBytes, hash);
                String full = ProfileCrypto.qrFullPayload(keyEnvelope, keyBytes);
                final String qrText = full != null ? full : keyPayloadText;
                final boolean wholeInQr = full != null;
                runOnUiThread(() -> {
                    qrView.setImageBitmap(QrRender.render(qrText, Ui.dp(this, 260)));
                    qrView.setVisibility(android.view.View.VISIBLE);
                    saveEnvelopeBtn.setVisibility(wholeInQr
                            ? android.view.View.GONE : android.view.View.VISIBLE);
                    writeTagBtn.setVisibility(android.view.View.VISIBLE);
                    status.setText(wholeInQr
                            ? "This profile is small — the QR carries everything. "
                            + "Scan it on the new phone and you're done."
                            : "Show this QR to the new phone, and send it the "
                            + "encrypted file too (save it below).");
                });
            } catch (Exception e) {
                runOnUiThread(() -> status.setText("Failed: " + e.getMessage()));
            }
        }).start();
    }

    private void createDocument(int requestCode) {
        Intent i = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("application/json");
        i.putExtra(Intent.EXTRA_TITLE, "control-profile.json");
        startActivityForResult(i, requestCode);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if ((requestCode == REQ_SAVE_PASSPHRASE || requestCode == REQ_SAVE_ENVELOPE)
                && resultCode == RESULT_OK && data != null && data.getData() != null
                && pendingFileBytes != null) {
            Uri uri = data.getData();
            new Thread(() -> {
                try (OutputStream out = getContentResolver().openOutputStream(uri)) {
                    out.write(pendingFileBytes);
                    runOnUiThread(() -> status.setText("Encrypted file saved."));
                } catch (Exception e) {
                    runOnUiThread(() -> status.setText("Save failed: " + e.getMessage()));
                }
            }).start();
        }
    }

    // ---- NFC tag writing ----

    private void armTagWrite() {
        NfcAdapter adapter = NfcAdapter.getDefaultAdapter(this);
        if (adapter == null) {
            status.setText("This phone has no NFC.");
            return;
        }
        if (!adapter.isEnabled()) {
            status.setText("NFC is off — turn it on in system settings.");
            return;
        }
        tagWriteArmed = true;
        PendingIntent pi = PendingIntent.getActivity(this, 0,
                new Intent(this, getClass()).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
                PendingIntent.FLAG_MUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        IntentFilter[] filters = {new IntentFilter(NfcAdapter.ACTION_TAG_DISCOVERED)};
        String[][] tech = {{Ndef.class.getName()}, {NdefFormatable.class.getName()}};
        adapter.enableForegroundDispatch(this, pi, filters, tech);
        status.setText("Now hold an NFC tag against the back of the phone…");
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        if (!tagWriteArmed || keyPayloadText == null) return;
        Tag tag = intent.getParcelableExtra(NfcAdapter.EXTRA_TAG);
        if (tag == null) return;
        new Thread(() -> {
            String result;
            try {
                NdefRecord record = NdefRecord.createTextRecord("en", keyPayloadText);
                NdefMessage msg = new NdefMessage(new NdefRecord[]{record});
                Ndef ndef = Ndef.get(tag);
                if (ndef != null) {
                    ndef.connect();
                    if (!ndef.isWritable()) throw new Exception("tag is read-only");
                    if (ndef.getMaxSize() < msg.getByteArrayLength()) {
                        throw new Exception("tag too small for the key");
                    }
                    ndef.writeNdefMessage(msg);
                    ndef.close();
                    result = "Key written. On the new phone: Import → pick the file → read this tag.";
                } else {
                    NdefFormatable fmt = NdefFormatable.get(tag);
                    if (fmt == null) throw new Exception("tag is not NDEF-capable");
                    fmt.connect();
                    fmt.format(msg);
                    fmt.close();
                    result = "Tag formatted and key written.";
                }
            } catch (Exception e) {
                result = "Tag write failed: " + e.getMessage();
            }
            final String r = result;
            runOnUiThread(() -> {
                status.setText(r);
                tagWriteArmed = false;
                NfcAdapter adapter = NfcAdapter.getDefaultAdapter(this);
                if (adapter != null) adapter.disableForegroundDispatch(this);
            });
        }).start();
    }
}
