package app.control.launcher;

import android.app.AlertDialog;
import android.content.Context;
import android.widget.TextView;

import java.nio.charset.StandardCharsets;

/**
 * The long-press "View encoded message" dialog (v1.32), shared
 * by the walkie thread and the chat threads: one rendering
 * rule, one set of honest lines, no re-encoding anywhere —
 * the wire form shown is the one recorded at send/ingest
 * time, because a re-encode could differ as the dictionary
 * changes.
 *
 * Cases:
 *  - no recorded wire form (a message from before v1.32):
 *    "The coded form was not recorded for this message."
 *  - a coded message: the display form (symbol + real
 *    Unicode super/sub digits), plus "Sent as N bytes, was
 *    M bytes".
 *  - a legacy plain message: "Sent plain" plus the text.
 *  - an undecoded message: the wire form as received plus
 *    the note saying why it did not decode, and no size
 *    line (the original size is unknown).
 * A relayed entry keeps its "relayed by <name>" provenance
 * line at the top.
 */
public final class EncodedMessageDialog {
    private EncodedMessageDialog() {}

    /** Shows the dialog for one message. {@code wire} is the
     *  recorded wire form (null when unrecorded); {@code
     *  plainBytes} the original text's byte count (-1 when
     *  unknown); {@code coded} whether the payload arrived
     *  coded; {@code wordNote} the undecoded note (null when
     *  the message decoded cleanly); {@code text} the shown
     *  text; {@code relayedBy} the gateway name for a
     *  relayed entry (null otherwise). */
    public static void show(Context ctx, String wire, int plainBytes,
                            boolean coded, String wordNote, String text,
                            String relayedBy) {
        StringBuilder body = new StringBuilder();
        if (relayedBy != null && !relayedBy.isEmpty()) {
            body.append("Relayed by ").append(relayedBy).append("\n\n");
        }
        if (wire == null) {
            body.append("The coded form was not recorded for this message.");
        } else if (wordNote != null) {
            body.append("This message did not decode:\n")
                    .append(wordNote).append("\n\n")
                    .append("Received as:\n").append(wire);
        } else if (!coded) {
            body.append("Sent plain.\n\n").append(text == null ? "" : text);
        } else {
            String shown = WordDict.wireDisplayForm(wire);
            body.append(shown != null ? shown : wire);
            body.append("\n\nSent as ")
                    .append(wire.getBytes(StandardCharsets.UTF_8).length)
                    .append(" bytes");
            if (plainBytes >= 0) {
                body.append(", was ").append(plainBytes).append(" bytes");
            }
        }
        TextView tv = new TextView(ctx);
        tv.setText(body.toString());
        tv.setTextSize(16);
        tv.setPadding(48, 32, 48, 16);
        tv.setTextIsSelectable(true);
        new AlertDialog.Builder(ctx)
                .setTitle("Encoded message")
                .setView(tv)
                .setPositiveButton("Close", null)
                .show();
    }
}
