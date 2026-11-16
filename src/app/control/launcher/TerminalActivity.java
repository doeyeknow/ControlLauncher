package app.control.launcher;

import android.app.Activity;
import android.app.AlertDialog;
import android.graphics.Typeface;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONArray;

import java.util.ArrayList;
import java.util.List;

/**
 * A Termux-like command line inside Control (v1.6).
 *
 * Commands typed here run over the ADB shell channel — the same
 * channel a computer's {@code adb shell} uses — so they run as
 * Android's shell user, not as Control. Each command is its own
 * shell-service invocation over a fresh ADB connection
 * ({@link AdbStarter#runShellCommand}); there is no persistent
 * session, so nothing (working directory, variables) carries over
 * between commands, and the ADB shell service reports no exit
 * status, so none is shown. Wireless debugging must be on and
 * Control's key must be paired, exactly as for the no-computer
 * bridge start in Deep view.
 *
 * The "Enable deep view" preset runs the bridge starter through
 * that same channel by calling {@link AdbStarter#restartBridge} —
 * the preset adds no starter of its own and never alters the
 * starter command; the transcript shows the command with the
 * bridge token masked (see {@link AdbShell}).
 *
 * Honesty rules: the transcript shows typed commands verbatim and
 * shell output verbatim, except the bridge token value, which
 * never appears anywhere on this screen or in the audit log. A
 * failed run shows the failure's own wording — never a fake
 * success. Every run is audit-logged with origin UI (typed
 * commands by {@link AdbStarter#runShellCommand}, the preset by
 * the restart flow it reuses).
 */
public class TerminalActivity extends ThemedActivity {

    @Override
    protected boolean recreateOnThemeChange() {
        // Theme change: a rebuild would lose the terminal transcript; the new theme
        // applies the next time this screen opens.
        return false;
    }

    private static final int MAX_HISTORY = 20;
    /** Transcript cap: older output is trimmed past this size. */
    private static final int TRANSCRIPT_CAP = 120_000;
    private static final int TRANSCRIPT_KEEP = 100_000;

    private TextView transcriptView;
    private ScrollView transcriptScroll;
    private TextView statusLine;
    private TextView pairedLine;
    private EditText input;
    private Button runButton;
    private Button presetButton;
    private Button clearButton;

    private final StringBuilder transcript = new StringBuilder();
    private String token = "";
    private boolean running = false;
    private boolean destroyed = false;

    /** Most-recent-first typed commands (never holding the token). */
    private final List<String> history = new ArrayList<>();
    private int histPos = -1;
    private boolean navigatingHistory = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        token = ShellBridgeClient.token(this);
        loadHistory();

        LinearLayout root = Ui.column(this);
        int pad = Ui.dp(this, 18);
        root.setPadding(pad, Ui.dp(this, 18), pad, Ui.dp(this, 24));
        setContentView(root);

        root.addView(Ui.screenTitle(this, "Terminal"));
        root.addView(Ui.body(this,
                "Commands here run over the ADB shell channel — the same "
                + "channel a computer's adb shell uses — as Android's "
                + "shell user, not as Control. Each command runs on its "
                + "own connection; there is no persistent shell, so "
                + "things like cd do not carry over to the next command, "
                + "and no exit status is reported. Wireless debugging "
                + "must be on, and Control's key must be paired (Deep "
                + "view → Enter pairing code). Every command is written "
                + "to the audit log."));
        pairedLine = Ui.text(this, "", 13, Ui.ACCENT);
        Ui.mono(pairedLine);
        root.addView(pairedLine, Ui.wrap(0, 8, 0, 0, this));

        transcriptScroll = new ScrollView(this);
        transcriptScroll.setBackgroundColor(Ui.SURFACE);
        LinearLayout.LayoutParams scrollParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        scrollParams.setMargins(0, Ui.dp(this, 10), 0, 0);
        transcriptScroll.setLayoutParams(scrollParams);
        transcriptView = Ui.text(this, "", 13, Ui.TEXT);
        transcriptView.setTypeface(Typeface.MONOSPACE);
        transcriptView.setTextIsSelectable(true);
        transcriptView.setPadding(pad, Ui.dp(this, 10), pad, Ui.dp(this, 10));
        transcriptScroll.addView(transcriptView);
        root.addView(transcriptScroll);

        statusLine = Ui.text(this, "", 13, Ui.DIM);
        Ui.mono(statusLine);
        root.addView(statusLine, Ui.wrap(0, 8, 0, 0, this));

        LinearLayout inputRow = Ui.row(this);
        TextView prompt = Ui.text(this, "$", 16, Ui.ACCENT);
        prompt.setTypeface(Typeface.MONOSPACE);
        prompt.setPadding(0, 0, Ui.dp(this, 8), 0);
        inputRow.addView(prompt);
        input = Ui.input(this, "shell command");
        input.setTypeface(Typeface.MONOSPACE);
        input.setImeOptions(EditorInfo.IME_ACTION_GO);
        LinearLayout.LayoutParams inputParams = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        input.setLayoutParams(inputParams);
        input.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_GO
                    || (event != null && event.getKeyCode() == KeyEvent.KEYCODE_ENTER
                            && event.getAction() == KeyEvent.ACTION_DOWN)) {
                runTyped();
                return true;
            }
            return false;
        });
        input.setOnKeyListener((v, keyCode, event) -> {
            if (event.getAction() != KeyEvent.ACTION_DOWN) return false;
            if (keyCode == KeyEvent.KEYCODE_DPAD_UP) {
                stepHistory(1);
                return true;
            }
            if (keyCode == KeyEvent.KEYCODE_DPAD_DOWN) {
                stepHistory(-1);
                return true;
            }
            return false;
        });
        input.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int st, int c, int a) {}
            @Override public void onTextChanged(CharSequence s, int st, int b, int c) {
                if (!navigatingHistory) histPos = -1;
            }
            @Override public void afterTextChanged(Editable s) {}
        });
        inputRow.addView(input);
        runButton = Ui.button(this, "Run", true);
        LinearLayout.LayoutParams runParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        runParams.setMargins(Ui.dp(this, 8), 0, 0, 0);
        runButton.setLayoutParams(runParams);
        runButton.setOnClickListener(v -> runTyped());
        inputRow.addView(runButton);
        root.addView(inputRow, Ui.wrap(0, 4, 0, 0, this));

        presetButton = Ui.button(this, "Enable deep view", true);
        presetButton.setOnClickListener(v -> runPreset());
        root.addView(presetButton, Ui.wrap(0, 10, 0, 0, this));
        root.addView(Ui.body(this,
                "Runs the bridge starter — the same line Deep view shows "
                + "for a computer — through this phone's own ADB shell "
                + "channel, then waits for the bridge to answer. The "
                + "token inside the command is masked on screen."));

        LinearLayout row = Ui.row(this);
        Button recents = Ui.smallButton(this, "Recents");
        recents.setOnClickListener(v -> showRecents());
        row.addView(recents);
        Button pairingHelp = Ui.smallButton(this, "Pairing help");
        pairingHelp.setOnClickListener(v -> showPairingHelp());
        row.addView(pairingHelp);
        clearButton = Ui.smallButton(this, "Clear");
        clearButton.setOnClickListener(v -> {
            transcript.setLength(0);
            renderTranscript();
            appendLine("Control terminal — commands run as the shell user over ADB.");
        });
        row.addView(clearButton);
        root.addView(row);

        appendLine("Control terminal — commands run as the shell user over ADB.");
        appendLine("Type a command above, or tap Enable deep view to start the bridge.");
        refreshPairedLine();
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshPairedLine();
    }

    @Override
    protected void onDestroy() {
        destroyed = true;
        super.onDestroy();
    }

    private void refreshPairedLine() {
        boolean paired = Prefs.get(this).getBoolean(Prefs.KEY_ADB_PAIRED, false);
        pairedLine.setText(paired
                ? "ADB key: paired — commands can run while Wireless debugging is on."
                : "ADB key: not paired yet — pair once from Deep view (Enter pairing code).");
    }

    // ---- typed commands ----

    private void runTyped() {
        if (running) return;
        String command = input.getText().toString().trim();
        if (command.isEmpty()) return;
        input.setText("");
        histPos = -1;
        recordHistory(command);
        appendLine(AdbShell.echoLine(command, token));
        setRunning(true, "Running…");
        AdbStarter.runShellCommand(this, command, new AdbStarter.ShellCallback() {
            @Override public void onStatus(String line) {
                runOnUiThread(() -> {
                    if (!destroyed) statusLine.setText(line);
                });
            }

            @Override public void onDone(boolean ok, String output, String error) {
                runOnUiThread(() -> {
                    if (destroyed) return;
                    if (ok) {
                        String out = output == null ? "" : output;
                        appendLine(out.isEmpty() ? "(no output)" : trimTrailingBlank(out));
                    } else {
                        appendLine("✗ " + error);
                    }
                    setRunning(false, "");
                    refreshPairedLine();
                });
            }
        });
    }

    // ---- the preset: the starter, through the ADB channel ----

    private void runPreset() {
        if (running) return;
        appendLine(AdbShell.echoLine(ShellBridgeClient.shellStarterCommand(this), token));
        appendLine("— starting the bridge over this phone's ADB channel… —");
        setRunning(true, "Starting the bridge…");
        AdbStarter.restartBridge(this, new AdbStarter.Callback() {
            @Override public void onStatus(String line) {
                runOnUiThread(() -> {
                    if (destroyed) return;
                    statusLine.setText(line);
                    appendLine(AdbShell.redact(line, token));
                });
            }

            @Override public void onDone(boolean up, String summary) {
                runOnUiThread(() -> {
                    if (destroyed) return;
                    appendLine((up ? "✓ " : "✗ ")
                            + AdbShell.redact(summary, token));
                    setRunning(false, "");
                    refreshPairedLine();
                });
            }
        });
    }

    private void setRunning(boolean on, String status) {
        running = on;
        statusLine.setText(status);
        runButton.setEnabled(!on);
        presetButton.setEnabled(!on);
        clearButton.setEnabled(!on);
    }

    // ---- transcript ----

    private void appendLine(String line) {
        if (transcript.length() > 0) transcript.append('\n');
        transcript.append(line);
        if (transcript.length() > TRANSCRIPT_CAP) {
            transcript.delete(0, transcript.length() - TRANSCRIPT_KEEP);
            transcript.insert(0, "[earlier output trimmed]\n");
        }
        renderTranscript();
    }

    private void renderTranscript() {
        transcriptView.setText(transcript.toString());
        transcriptScroll.post(() -> transcriptScroll.fullScroll(View.FOCUS_DOWN));
    }

    private static String trimTrailingBlank(String s) {
        int end = s.length();
        while (end > 0 && (s.charAt(end - 1) == '\n' || s.charAt(end - 1) == '\r')) end--;
        return s.substring(0, end);
    }

    // ---- history: recents dialog + up/down keys ----

    private void stepHistory(int direction) {
        if (history.isEmpty()) return;
        // direction +1 = older (up), -1 = newer (down). histPos -1
        // means "the fresh line"; history is most-recent-first.
        int next = histPos + direction;
        if (next < -1) next = -1;
        if (next >= history.size()) next = history.size() - 1;
        histPos = next;
        navigatingHistory = true;
        input.setText(histPos < 0 ? "" : history.get(histPos));
        input.setSelection(input.getText().length());
        navigatingHistory = false;
    }

    private void showRecents() {
        if (history.isEmpty()) {
            new AlertDialog.Builder(this)
                    .setTitle("Recent commands")
                    .setMessage("No commands yet this install. Commands you run "
                            + "appear here, most recent first.")
                    .setPositiveButton("OK", null)
                    .show();
            return;
        }
        String[] items = history.toArray(new String[0]);
        new AlertDialog.Builder(this)
                .setTitle("Recent commands")
                .setItems(items, (d, which) -> {
                    input.setText(history.get(which));
                    input.setSelection(input.getText().length());
                    input.requestFocus();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void recordHistory(String command) {
        if (!AdbShell.shouldRecord(command, token)) return;
        history.remove(command);
        history.add(0, command);
        while (history.size() > MAX_HISTORY) history.remove(history.size() - 1);
        saveHistory();
    }

    private void loadHistory() {
        history.clear();
        try {
            String raw = Prefs.get(this).getString(Prefs.KEY_TERMINAL_HISTORY, "");
            JSONArray arr = new JSONArray(raw == null ? "" : raw);
            for (int i = 0; i < arr.length() && history.size() < MAX_HISTORY; i++) {
                String c = arr.optString(i, "");
                if (!c.isEmpty() && AdbShell.shouldRecord(c, token)) history.add(c);
            }
        } catch (Exception ignored) {
        }
    }

    private void saveHistory() {
        try {
            JSONArray arr = new JSONArray();
            for (String c : history) arr.put(c);
            Prefs.get(this).edit().putString(Prefs.KEY_TERMINAL_HISTORY, arr.toString()).apply();
        } catch (Exception ignored) {
        }
    }

    // ---- pairing guidance (the flow itself lives in Deep view) ----

    private void showPairingHelp() {
        new AlertDialog.Builder(this)
                .setTitle("Pairing Control's key")
                .setMessage("Commands need Control's ADB key paired with this "
                        + "phone — the same one-time setup as the no-computer "
                        + "bridge start:\n\n"
                        + "1. Developer options → Wireless debugging → turn it ON.\n"
                        + "2. Tap “Pair device with pairing code” there and "
                        + "leave the dialog open.\n"
                        + "3. In Deep view, tap “Enter pairing code” and type "
                        + "the 6 digits.\n\n"
                        + "After that, each reboot is just step 1 — no code, "
                        + "no computer.")
                .setPositiveButton("Open Deep view", (d, w) -> finish())
                .setNegativeButton("Stay here", null)
                .show();
    }
}
