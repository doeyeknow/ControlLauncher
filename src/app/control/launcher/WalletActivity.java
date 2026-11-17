package app.control.launcher;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.KeyguardManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Typeface;
import android.hardware.biometrics.BiometricManager;
import android.hardware.biometrics.BiometricPrompt;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.util.Log;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;

/**
 * The wallet (v1.7): cards stored on this phone, payment handed
 * off to the phone's real wallet app.
 *
 * The screen carries no explanations (v1.42): only the
 * controls' own labels, card identifiers, and text reporting a
 * result or a failure. The rules below are enforced in code —
 * they used to be stated on the screen itself, and are not
 * anymore:
 *  - Cards are DEVICE-LOCAL: the vault file is not part of the
 *    portable profile, so cards never travel with a profile
 *    export or import, and a profile wipe leaves them alone.
 *  - The full number is stored only as AES-256-GCM ciphertext
 *    under an Android Keystore key (see {@link WalletStore});
 *    only the last 4 is ever shown unmasked without a check.
 *  - Control NEVER stores the security code — there is no CVV
 *    field anywhere in this screen.
 *  - Showing a full number requires the device credential
 *    (framework BiometricPrompt with device-credential allowed
 *    on API 29+; the keyguard confirm intent below that). With
 *    no screen lock set, reveal is blocked outright — never shown
 *    without the check. A shown number re-masks after 30 seconds
 *    or when the screen is left. (The framework prompt is the
 *    Builder-style API — PromptInfo belongs to androidx, which
 *    this app does not vendor.)
 *  - Pay opens Google Pay or Samsung Wallet, where the tap
 *    happens. Control cannot pre-select a card there and never
 *    claims to: there is no NFC payment code in this app, no
 *    "tap here" affordance, and the Pay button is one shared
 *    button (not per-card), because the handoff cannot carry a
 *    card choice with it.
 *  - The audit log records nickname + last 4 only, through
 *    {@link WalletCards#auditLine} — a full number has no path
 *    into the log.
 */
public class WalletActivity extends ThemedActivity {

    @Override
    protected boolean recreateOnThemeChange() {
        // Theme change: a rebuild would lose the half-typed card form; the new theme
        // applies the next time this screen opens.
        return false;
    }

    private static final String GOOGLE_PAY_PKG = "com.google.android.apps.walletnfcrel";
    private static final String SAMSUNG_PAY_PKG = "com.samsung.android.spay";
    private static final int REQ_CONFIRM_CREDENTIAL = 71;
    private static final long REMASK_MS = 30_000;

    private LinearLayout cardList;
    private TextView statusLine;
    private Button payButton;
    private Button changePayAppButton;
    private TextView payAppLine;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private String revealedId;
    private TextView revealedNumberView;
    private Button revealedShowButton;
    private String revealedDigits = "";
    private final Runnable remaskRunnable = this::remask;

    /** Card awaiting the keyguard confirm result (API 26–28). */
    private WalletStore.Card pendingReveal;
    private TextView pendingNumberView;
    private Button pendingShowButton;

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

        root.addView(Ui.screenTitle(this, "Wallet"));

        root.addView(Ui.section(this, "Pay"));
        payButton = Ui.button(this, "Pay", true);
        root.addView(payButton, Ui.wrap(0, 10, 0, 0, this));
        payButton.setOnClickListener(v -> onPayPressed());
        payAppLine = Ui.text(this, "", 13, Ui.DIM);
        Ui.mono(payAppLine);
        root.addView(payAppLine, Ui.wrap(0, 8, 0, 0, this));
        changePayAppButton = Ui.smallButton(this, "Change pay app");
        changePayAppButton.setOnClickListener(v -> showPayAppChooser());
        root.addView(changePayAppButton);
        refreshPayRow();

        root.addView(Ui.section(this, "Cards"));
        statusLine = Ui.text(this, "", 13, Ui.DIM);
        Ui.mono(statusLine);
        root.addView(statusLine);
        cardList = new LinearLayout(this);
        cardList.setOrientation(LinearLayout.VERTICAL);
        root.addView(cardList);

        Button addButton = Ui.button(this, "Add a card", false);
        root.addView(addButton, Ui.wrap(0, 14, 0, 0, this));
        addButton.setOnClickListener(v -> showCardForm(null));

        refreshCards();
    }

    @Override
    protected void onPause() {
        super.onPause();
        remask();
    }

    // ---- card list ----

    private void refreshCards() {
        remask();
        cardList.removeAllViews();
        List<WalletStore.Card> cards;
        try {
            cards = WalletStore.list(this);
        } catch (WalletStore.CorruptException e) {
            statusLine.setText("Stored cards couldn't be read. Nothing was changed.");
            Button fresh = Ui.button(this, "Start fresh (deletes stored cards)", false);
            fresh.setOnClickListener(v -> {
                WalletStore.clearAll(this);
                OwnLog.log(this, "Wallet", "Wallet data cleared after a read failure, origin UI");
                refreshCards();
            });
            cardList.addView(fresh, Ui.wrap(0, 10, 0, 0, this));
            return;
        }
        if (cards.isEmpty()) {
            // Empty wallet (v1.42): no text at all — the Add
            // control below is the whole empty state.
            statusLine.setText("");
            return;
        }
        // The list speaks for itself; no count line (v1.42).
        statusLine.setText("");
        for (WalletStore.Card card : cards) {
            cardList.addView(cardRow(card), Ui.wrap(0, 10, 0, 0, this));
        }
    }

    private LinearLayout cardRow(WalletStore.Card card) {
        LinearLayout row = Ui.card(this);
        int pad = Ui.dp(this, 14);
        row.setPadding(pad, pad, pad, pad);

        LinearLayout top = Ui.row(this);
        TextView nick = Ui.text(this, card.nickname, 17, Ui.TEXT);
        nick.setTypeface(android.graphics.Typeface.create("sans-serif", android.graphics.Typeface.BOLD));
        LinearLayout.LayoutParams nickParams = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        top.addView(nick, nickParams);
        TextView net = Ui.text(this, card.network, 13, Ui.DIM);
        Ui.mono(net);
        top.addView(net);
        row.addView(top);

        LinearLayout numRow = Ui.row(this);
        TextView number = Ui.text(this, WalletCards.masked(card.last4), 16, Ui.TEXT);
        Ui.mono(number);
        LinearLayout.LayoutParams numParams = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        numRow.addView(number, numParams);
        Button show = Ui.smallButton(this, "Show");
        numRow.addView(show);
        row.addView(numRow, Ui.wrap(0, 8, 0, 0, this));
        show.setOnClickListener(v -> {
            if (card.id.equals(revealedId)) {
                remask();
            } else {
                requestReveal(card, number, show);
            }
        });

        TextView meta = Ui.text(this, card.name + "  ·  expires "
                + WalletCards.expiryDisplay(card.expMonth, card.expYear), 13, Ui.DIM);
        row.addView(meta, Ui.wrap(0, 4, 0, 0, this));

        LinearLayout actions = Ui.row(this);
        actions.setGravity(Gravity.END);
        Button edit = Ui.smallButton(this, "Edit");
        edit.setOnClickListener(v -> showCardForm(card));
        Button del = Ui.smallButton(this, "Delete");
        del.setOnClickListener(v -> confirmDelete(card));
        actions.addView(edit);
        actions.addView(del);
        row.addView(actions, Ui.wrap(0, 6, 0, 0, this));
        return row;
    }

    // ---- reveal gate ----

    private void requestReveal(WalletStore.Card card, TextView numberView, Button showButton) {
        KeyguardManager km = (KeyguardManager) getSystemService(Context.KEYGUARD_SERVICE);
        if (km == null || !km.isDeviceSecure()) {
            Toast.makeText(this, "Set a screen lock first.", Toast.LENGTH_LONG).show();
            return;
        }
        if (Build.VERSION.SDK_INT >= 29) {
            showBiometricPrompt(card, numberView, showButton);
        } else {
            Intent intent = km.createConfirmDeviceCredentialIntent(
                    "Show card number", card.nickname + " •••• " + card.last4);
            if (intent == null) {
                Toast.makeText(this, "No device credential is set up.", Toast.LENGTH_LONG).show();
                return;
            }
            pendingReveal = card;
            pendingNumberView = numberView;
            pendingShowButton = showButton;
            startActivityForResult(intent, REQ_CONFIRM_CREDENTIAL);
        }
    }

    private void showBiometricPrompt(WalletStore.Card card, TextView numberView, Button showButton) {
        BiometricPrompt.Builder builder = new BiometricPrompt.Builder(this)
                .setTitle("Show card number")
                .setSubtitle(card.nickname + " •••• " + card.last4);
        if (Build.VERSION.SDK_INT >= 30) {
            builder.setAllowedAuthenticators(
                    BiometricManager.Authenticators.BIOMETRIC_STRONG
                            | BiometricManager.Authenticators.DEVICE_CREDENTIAL);
        } else {
            // API 29: device-credential fallback via the older setter.
            builder.setDeviceCredentialAllowed(true);
        }
        builder.build().authenticate(new android.os.CancellationSignal(), getMainExecutor(),
                new BiometricPrompt.AuthenticationCallback() {
                    @Override
                    public void onAuthenticationSucceeded(
                            BiometricPrompt.AuthenticationResult result) {
                        reveal(card, numberView, showButton);
                    }

                    @Override
                    public void onAuthenticationFailed() {
                        Toast.makeText(WalletActivity.this,
                                "Not recognized — try again.", Toast.LENGTH_SHORT).show();
                    }

                    @Override
                    public void onAuthenticationError(int errorCode, CharSequence errString) {
                        if (errorCode != BiometricPrompt.BIOMETRIC_ERROR_USER_CANCELED) {
                            Toast.makeText(WalletActivity.this,
                                    "Couldn't confirm: " + errString, Toast.LENGTH_LONG).show();
                        }
                    }
                });
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_CONFIRM_CREDENTIAL) {
            if (resultCode == RESULT_OK && pendingReveal != null) {
                reveal(pendingReveal, pendingNumberView, pendingShowButton);
            }
            pendingReveal = null;
            pendingNumberView = null;
            pendingShowButton = null;
        }
    }

    /** Decrypts and shows the number (the credential gate has
     *  already passed). Runs the keystore op off the UI thread. */
    private void reveal(WalletStore.Card card, TextView numberView, Button showButton) {
        new Thread(() -> {
            String digits;
            try {
                digits = WalletStore.unseal(card);
            } catch (Exception e) {
                runOnUiThread(() -> Toast.makeText(this,
                        "Couldn't unlock that card.", Toast.LENGTH_LONG).show());
                return;
            }
            runOnUiThread(() -> {
                remask();
                revealedId = card.id;
                revealedNumberView = numberView;
                revealedShowButton = showButton;
                revealedDigits = digits;
                numberView.setText(WalletCards.grouped(digits));
                showButton.setText("Hide");
                handler.postDelayed(remaskRunnable, REMASK_MS);
                OwnLog.log(this, "Wallet",
                        WalletCards.auditLine("number shown", card.nickname, card.last4));
            });
        }).start();
    }

    private void remask() {
        handler.removeCallbacks(remaskRunnable);
        if (revealedId != null && revealedNumberView != null) {
            revealedNumberView.setText(WalletCards.masked(revealedDigits));
            if (revealedShowButton != null) revealedShowButton.setText("Show");
        }
        revealedId = null;
        revealedNumberView = null;
        revealedShowButton = null;
        revealedDigits = "";
    }

    // ---- add / edit ----

    /** {@code existing} null → add form (number editable);
     *  non-null → edit form (nickname/name/network/expiry only —
     *  the number is immutable once stored). */
    private void showCardForm(WalletStore.Card existing) {
        boolean adding = existing == null;
        LinearLayout form = new LinearLayout(this);
        form.setOrientation(LinearLayout.VERTICAL);
        int pad = Ui.dp(this, 4);
        form.setPadding(pad, pad, pad, pad);

        EditText nickInput = Ui.input(this, "Nickname (e.g. Everyday)");
        EditText nameInput = Ui.input(this, "Name on card");
        Spinner networkSpinner = new Spinner(this);
        ArrayAdapter<String> adapter = new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_dropdown_item, WalletCards.NETWORKS);
        networkSpinner.setAdapter(adapter);
        EditText monthInput = Ui.input(this, "Expiry month (MM)");
        monthInput.setInputType(InputType.TYPE_CLASS_NUMBER);
        EditText yearInput = Ui.input(this, "Expiry year (YYYY)");
        yearInput.setInputType(InputType.TYPE_CLASS_NUMBER);
        EditText numberInput = null;
        if (adding) {
            numberInput = Ui.input(this, "Card number");
            numberInput.setInputType(InputType.TYPE_CLASS_NUMBER);
        }

        form.addView(nickInput, Ui.wrap(0, 6, 0, 0, this));
        form.addView(nameInput, Ui.wrap(0, 6, 0, 0, this));
        form.addView(networkSpinner, Ui.wrap(0, 6, 0, 0, this));
        if (adding) form.addView(numberInput, Ui.wrap(0, 6, 0, 0, this));
        form.addView(monthInput, Ui.wrap(0, 6, 0, 0, this));
        form.addView(yearInput, Ui.wrap(0, 6, 0, 0, this));

        if (!adding) {
            nickInput.setText(existing.nickname);
            nameInput.setText(existing.name);
            networkSpinner.setSelection(indexOfNetwork(existing.network));
            monthInput.setText(String.valueOf(existing.expMonth));
            yearInput.setText(String.valueOf(existing.expYear));
        }

        EditText finalNumberInput = numberInput;
        new AlertDialog.Builder(this)
                .setTitle(adding ? "Add a card" : "Edit card")
                .setView(form)
                .setPositiveButton(adding ? "Add" : "Save", (d, which) -> {
                    saveCardForm(existing, nickInput, nameInput, networkSpinner,
                            monthInput, yearInput, finalNumberInput);
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private static int indexOfNetwork(String network) {
        for (int i = 0; i < WalletCards.NETWORKS.length; i++) {
            if (WalletCards.NETWORKS[i].equals(network)) return i;
        }
        return WalletCards.NETWORKS.length - 1;
    }

    private void saveCardForm(WalletStore.Card existing, EditText nickInput,
                              EditText nameInput, Spinner networkSpinner,
                              EditText monthInput, EditText yearInput,
                              EditText numberInput) {
        String nickname = nickInput.getText().toString().trim();
        String name = nameInput.getText().toString().trim();
        String network = (String) networkSpinner.getSelectedItem();
        int month;
        int year;
        try {
            month = Integer.parseInt(monthInput.getText().toString().trim());
            year = Integer.parseInt(yearInput.getText().toString().trim());
        } catch (NumberFormatException e) {
            Toast.makeText(this, "Expiry must be a month (1–12) and a 4-digit year.",
                    Toast.LENGTH_LONG).show();
            return;
        }
        if (nickname.isEmpty()) {
            Toast.makeText(this, "Give the card a nickname.", Toast.LENGTH_LONG).show();
            return;
        }
        if (name.isEmpty()) {
            Toast.makeText(this, "Enter the name on the card.", Toast.LENGTH_LONG).show();
            return;
        }
        Calendar now = Calendar.getInstance();
        if (!WalletCards.expiryValid(month, year,
                now.get(Calendar.YEAR), now.get(Calendar.MONTH) + 1)) {
            Toast.makeText(this, "That expiry date isn't valid or is in the past.",
                    Toast.LENGTH_LONG).show();
            return;
        }
        if (existing == null) {
            String digits = WalletCards.digitsOnly(numberInput.getText().toString());
            if (!WalletCards.luhnValid(digits)) {
                Toast.makeText(this, "That card number doesn't check out.",
                        Toast.LENGTH_LONG).show();
                return;
            }
            addCardWithRecovery(nickname, name, network, month, year, digits);
            return;
        }
        try {
            WalletStore.updateMeta(this, existing.id, nickname, name, network, month, year);
            OwnLog.log(this, "Wallet",
                    WalletCards.auditLine("edited", nickname, existing.last4));
        } catch (Exception e) {
            showStoreError("save that card", e);
            return;
        }
        refreshCards();
    }

    /** Add a card, with the v1.39 key-regeneration recovery.
     *  When encrypt fails under every cipher supplier, the
     *  persisted key entry itself may be unusable (the leading
     *  theory — see WalletKeyRecovery): an empty vault
     *  regenerates silently and retries once; a vault holding
     *  cards asks first, naming the consequence. Any other
     *  failure shape goes straight to the full-failure dialog
     *  with nothing retried. */
    private void addCardWithRecovery(String nickname, String name, String network,
                                     int month, int year, String digits) {
        Exception first;
        try {
            WalletStore.Card card = WalletStore.add(this, nickname, name, network,
                    month, year, digits);
            onCardAdded(card);
            return;
        } catch (Exception e) {
            first = e;
        }
        if (!(first instanceof WalletCrypto.AllFailedException)) {
            showFullFailure("Couldn't save that card", failureDetail(first));
            return;
        }
        Integer count = null;
        try {
            count = WalletStore.list(this).size();
        } catch (Exception ignored) {
            // Unknown count: the decision asks, never assumes empty.
        }
        WalletKeyRecovery.Action action =
                WalletKeyRecovery.decide(true, count, false);
        if (action == WalletKeyRecovery.Action.REGENERATE_SILENTLY) {
            retryAfterRegeneration(nickname, name, network, month, year, digits,
                    failureDetail(first));
        } else if (action == WalletKeyRecovery.Action.ASK_BEFORE_REGENERATE) {
            String firstDetail = failureDetail(first);
            new AlertDialog.Builder(this)
                    .setTitle(WalletKeyRecovery.confirmTitle())
                    .setMessage(WalletKeyRecovery.confirmBody())
                    .setNegativeButton("Cancel", null)
                    .setPositiveButton("Create new key", (d, w) ->
                            retryAfterRegeneration(nickname, name, network,
                                    month, year, digits, firstDetail))
                    .show();
        } else {
            showFullFailure("Couldn't save that card", failureDetail(first));
        }
    }

    /** Regenerate the key once and retry the save once. Never
     *  loops: one regeneration per save attempt, then the
     *  failure stands — with BOTH attempts' text in the dialog
     *  so the next report carries the full story. */
    private void retryAfterRegeneration(String nickname, String name, String network,
                                        int month, int year, String digits,
                                        String firstDetail) {
        try {
            WalletStore.regenerateKey();
        } catch (Exception regenFailed) {
            showFullFailure("Couldn't save that card",
                    "First attempt:\n" + firstDetail
                    + "\n\nCreating a new key failed:\n"
                    + failureDetail(regenFailed));
            return;
        }
        try {
            WalletStore.Card card = WalletStore.add(this, nickname, name, network,
                    month, year, digits);
            onCardAdded(card);
        } catch (Exception second) {
            showFullFailure("Couldn't save that card",
                    "First attempt:\n" + firstDetail
                    + "\n\nAfter creating a new key:\n" + failureDetail(second));
        }
    }

    private void onCardAdded(WalletStore.Card card) {
        OwnLog.log(this, "Wallet",
                WalletCards.auditLine("added", card.nickname, card.last4));
        refreshCards();
    }

    /** One failure's complete text: class + full message, plus
     *  the cause's class + message when present (the cause
     *  carries the last cipher attempt's uncapped words). */
    private String failureDetail(Exception e) {
        StringBuilder sb = new StringBuilder();
        sb.append(e.getClass().getSimpleName()).append(": ")
                .append(String.valueOf(e.getMessage()));
        Throwable cause = e.getCause();
        if (cause != null && cause != e) {
            sb.append("\nCaused by: ").append(cause.getClass().getSimpleName())
                    .append(": ").append(String.valueOf(cause.getMessage()));
        }
        return sb.toString();
    }

    /** A save failure, shown in full: scrollable, selectable,
     *  nothing truncated (v1.39, the v1.38 refusal-dialog
     *  pattern). The per-attempt details must survive to the
     *  next report. */
    private void showFullFailure(String title, String body) {
        Log.e("WalletActivity", title + ": " + body);
        TextView text = new TextView(this);
        text.setText(body);
        text.setTextSize(14);
        text.setTypeface(Typeface.MONOSPACE);
        text.setTextColor(Ui.TEXT);
        text.setTextIsSelectable(true);
        int p = Ui.dp(this, 20);
        text.setPadding(p, Ui.dp(this, 8), p, Ui.dp(this, 8));
        ScrollView scroll = new ScrollView(this);
        scroll.addView(text);
        new AlertDialog.Builder(this)
                .setTitle(title)
                .setView(scroll)
                .setPositiveButton("Close", null)
                .show();
    }

    /** A vault failure, shown with its real cause: the exception's
     *  class + message, capped at a readable length, and logged.
     *  The old bare toasts ("Couldn't save that card.") hid the
     *  Keystore-provider bug completely — a store failure must
     *  never again fail silently about its cause. Validation
     *  toasts elsewhere in this file are untouched. */
    private void showStoreError(String verb, Exception e) {
        String detail = e.getClass().getSimpleName()
                + ": " + String.valueOf(e.getMessage());
        String msg = "Couldn't " + verb + ": " + detail;
        if (msg.length() > 160) msg = msg.substring(0, 157) + "…";
        Log.e("WalletActivity", msg, e);
        Toast.makeText(this, msg, Toast.LENGTH_LONG).show();
    }

    private void confirmDelete(WalletStore.Card card) {
        new AlertDialog.Builder(this)
                .setTitle("Delete this card?")
                .setMessage(card.nickname + " •••• " + card.last4
                        + " will be removed from this phone. This can't be undone.")
                .setPositiveButton("Delete", (d, which) -> {
                    try {
                        WalletStore.delete(this, card.id);
                    } catch (Exception e) {
                        showStoreError("delete that card", e);
                        return;
                    }
                    OwnLog.log(this, "Wallet",
                            WalletCards.auditLine("deleted", card.nickname, card.last4));
                    refreshCards();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    // ---- Pay handoff ----

    private static final class PayApp {
        final String pkg;
        final String label;

        PayApp(String pkg, String label) {
            this.pkg = pkg;
            this.label = label;
        }
    }

    private List<PayApp> installedPayApps() {
        List<PayApp> out = new ArrayList<>();
        PackageManager pm = getPackageManager();
        if (pm.getLaunchIntentForPackage(GOOGLE_PAY_PKG) != null) {
            out.add(new PayApp(GOOGLE_PAY_PKG, "Google Pay"));
        }
        if (pm.getLaunchIntentForPackage(SAMSUNG_PAY_PKG) != null) {
            out.add(new PayApp(SAMSUNG_PAY_PKG, "Samsung Wallet"));
        }
        return out;
    }

    private void refreshPayRow() {
        List<PayApp> apps = installedPayApps();
        String remembered = Prefs.get(this).getString(Prefs.KEY_WALLET_PAY_APP, "");
        PayApp chosen = null;
        for (PayApp app : apps) {
            if (app.pkg.equals(remembered)) chosen = app;
        }
        if (apps.isEmpty()) {
            payAppLine.setText("No wallet app found on this phone.");
            changePayAppButton.setVisibility(android.view.View.GONE);
        } else if (chosen != null) {
            payAppLine.setText("Pay app: " + chosen.label);
            changePayAppButton.setVisibility(
                    apps.size() > 1 ? android.view.View.VISIBLE : android.view.View.GONE);
        } else if (apps.size() == 1) {
            payAppLine.setText("Pay app: " + apps.get(0).label);
            changePayAppButton.setVisibility(android.view.View.GONE);
        } else {
            payAppLine.setText("Pay app: not chosen.");
            changePayAppButton.setVisibility(android.view.View.GONE);
        }
    }

    private void onPayPressed() {
        List<PayApp> apps = installedPayApps();
        if (apps.isEmpty()) {
            showNoPayAppDialog();
            return;
        }
        String remembered = Prefs.get(this).getString(Prefs.KEY_WALLET_PAY_APP, "");
        for (PayApp app : apps) {
            if (app.pkg.equals(remembered)) {
                launchPayApp(app);
                return;
            }
        }
        if (apps.size() == 1) {
            launchPayApp(apps.get(0));
            return;
        }
        showPayAppChooser();
    }

    private void showPayAppChooser() {
        List<PayApp> apps = installedPayApps();
        if (apps.isEmpty()) {
            showNoPayAppDialog();
            return;
        }
        if (apps.size() == 1) {
            launchPayApp(apps.get(0));
            return;
        }
        String[] labels = new String[apps.size()];
        for (int i = 0; i < apps.size(); i++) labels[i] = apps.get(i).label;
        new AlertDialog.Builder(this)
                .setTitle("Pay with")
                .setItems(labels, (d, which) -> {
                    PayApp app = apps.get(which);
                    Prefs.get(this).edit().putString(Prefs.KEY_WALLET_PAY_APP, app.pkg).apply();
                    refreshPayRow();
                    launchPayApp(app);
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void launchPayApp(PayApp app) {
        Intent launch = getPackageManager().getLaunchIntentForPackage(app.pkg);
        if (launch == null) {
            Toast.makeText(this, app.label + " couldn't be opened.",
                    Toast.LENGTH_LONG).show();
            refreshPayRow();
            return;
        }
        try {
            startActivity(launch);
        } catch (Exception e) {
            Toast.makeText(this, app.label + " couldn't be opened.",
                    Toast.LENGTH_LONG).show();
            return;
        }
        OwnLog.log(this, "Wallet",
                WalletCards.auditLine("pay pressed", "", "") + " — opened " + app.label);
    }

    private void showNoPayAppDialog() {
        new AlertDialog.Builder(this)
                .setTitle("No wallet app installed")
                .setPositiveButton("Get Google Pay", (d, which) -> openPlayStore(GOOGLE_PAY_PKG))
                .setNeutralButton("Get Samsung Wallet", (d, which) -> openPlayStore(SAMSUNG_PAY_PKG))
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void openPlayStore(String pkg) {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW,
                    Uri.parse("market://details?id=" + pkg)));
        } catch (Exception e) {
            try {
                startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(
                        "https://play.google.com/store/apps/details?id=" + pkg)));
            } catch (Exception ignored) {
                Toast.makeText(this, "Couldn't open the Play Store.",
                        Toast.LENGTH_LONG).show();
            }
        }
    }
}
