package app.control.launcher;

import java.nio.charset.StandardCharsets;

/**
 * What a mesh/Bluetooth text send actually put on the wire
 * (v1.32): the message id the caller derives the bridge id
 * from, plus the exact coded wire form that was sent and
 * both byte counts — so the thread entry can record the
 * real coded form for the long-press view instead of a
 * re-encode, which could differ as the dictionary changes.
 *
 * Pure Java, no Android types.
 */
public final class SentText {
    /** The message id minted for this send. */
    public final String mid;
    /** The exact wire form sent ("" when nothing was
     *  coded, e.g. an empty message). */
    public final String wire;
    /** The wire form's size in UTF-8 bytes. */
    public final int wireBytes;
    /** The typed text's size in UTF-8 bytes. */
    public final int plainBytes;

    public SentText(String mid, String wire, String plainText) {
        this.mid = mid == null ? "" : mid;
        this.wire = wire == null ? "" : wire;
        this.wireBytes = this.wire.getBytes(StandardCharsets.UTF_8).length;
        this.plainBytes = plainText == null ? 0
                : plainText.getBytes(StandardCharsets.UTF_8).length;
    }
}
