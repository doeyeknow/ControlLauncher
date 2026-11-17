package app.control.launcher;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * An incremental multipart/x-mixed-replace parser for MJPEG
 * streams: bytes go in with {@link #feed}, complete JPEG
 * frames come out. All fed bytes accumulate in one buffer
 * that is consumed from the front, so a boundary split
 * across feeds — at any point, including mid-boundary — is
 * found exactly as if the bytes had arrived together. The
 * buffer stays bounded: consumed bytes are compacted away,
 * frames are capped, and a runaway part is dropped at the
 * cap, so a stream can run for hours without growing
 * memory.
 *
 * Rules, pinned by the harness:
 *   - The boundary comes from the stream's Content-Type
 *     header (parsed by {@link #boundaryFromContentType});
 *     the delimiter on the wire is "--" + boundary.
 *   - Part headers end at the first blank line (\r\n\r\n
 *     or \n\n); a part Content-Length, when present and
 *     sane, bounds the frame exactly; without one, the
 *     frame runs to the next delimiter (minus the CRLF
 *     that belongs to the delimiter).
 *   - A frame larger than {@link #MAX_FRAME_BYTES} is
 *     dropped and the parser resynchronizes at the next
 *     delimiter — one bad part never kills the stream.
 *   - Only parts starting with the JPEG SOI marker
 *     (FF D8) are emitted; a part that is not a JPEG is
 *     skipped, not shown as garbage.
 *   - A trailing "--" after a delimiter (the closing
 *     delimiter) ends the stream cleanly.
 *
 * Pure Java, no Android types, no sockets: the harness
 * feeds byte arrays in hostile splits and checks the
 * frames that come out.
 */
public final class MjpegParser {

    /** Largest single frame kept (2 MB — far above any
     *  camera's MJPEG frame; a part beyond this is dropped). */
    public static final int MAX_FRAME_BYTES = 2 * 1024 * 1024;
    /** Largest part-header block kept (8 KB). */
    public static final int MAX_HEADER_BYTES = 8192;

    private static final int HUNT = 0;
    private static final int HEADERS = 1;
    private static final int BODY = 2;

    private final byte[] delimiter;   // "--" + boundary
    private final ByteArrayOutputStream buf = new ByteArrayOutputStream();
    private int state = HUNT;
    private int partContentLength = -1;
    private boolean dropping;
    private boolean ended;

    public MjpegParser(String boundary) {
        if (boundary == null || boundary.isEmpty()) {
            throw new IllegalArgumentException("MJPEG boundary required");
        }
        this.delimiter = ("--" + boundary).getBytes(StandardCharsets.ISO_8859_1);
    }

    /**
     * Extracts the boundary from a multipart Content-Type
     * header value ("multipart/x-mixed-replace;
     * boundary=--myboundary"). Returns null when there is
     * no boundary parameter. Quotes are stripped. A
     * leading "--" in the parameter is kept verbatim: the
     * wire delimiter is "--" + the parameter as sent, and
     * cameras disagree about whether the parameter itself
     * carries the dashes, so a caller whose first form
     * yields nothing retries with the dashes stripped.
     */
    public static String boundaryFromContentType(String contentType) {
        if (contentType == null) return null;
        String lower = contentType.toLowerCase();
        int at = lower.indexOf("boundary=");
        if (at < 0) return null;
        int i = at + "boundary=".length();
        while (i < contentType.length() && contentType.charAt(i) == ' ') i++;
        String value;
        if (i < contentType.length() && contentType.charAt(i) == '"') {
            int end = contentType.indexOf('"', i + 1);
            if (end < 0) return null;
            value = contentType.substring(i + 1, end);
        } else {
            int end = i;
            while (end < contentType.length() && contentType.charAt(end) != ';'
                    && contentType.charAt(end) != ' ' && contentType.charAt(end) != '\t') end++;
            value = contentType.substring(i, end);
        }
        value = value.trim();
        return value.isEmpty() ? null : value;
    }

    /** True when the stream's closing delimiter was seen. */
    public boolean isEnded() {
        return ended;
    }

    /**
     * Feeds bytes, returning every complete JPEG frame
     * finished by this feed (usually zero or one). Frames
     * are fresh byte arrays, SOI-first.
     */
    public List<byte[]> feed(byte[] data, int off, int len) {
        List<byte[]> frames = new ArrayList<>();
        if (ended || len <= 0) return frames;
        buf.write(data, off, len);
        while (!ended) {
            byte[] b = buf.toByteArray();
            if (state == HUNT) {
                int found = indexOf(b, 0, b.length, delimiter);
                if (found < 0) {
                    // Keep a tail that could start a
                    // delimiter; drop the preamble junk.
                    int keep = Math.min(b.length, delimiter.length - 1);
                    compact(b.length - keep);
                    break;
                }
                int after = found + delimiter.length;
                if (after + 1 >= b.length) break; // need the "--" check bytes
                if (b[after] == '-' && b[after + 1] == '-') {
                    ended = true;
                    break;
                }
                int i = after;
                if (b[i] == '\r') i++;
                if (i < b.length && b[i] == '\n') i++;
                compact(i);
                state = HEADERS;
                continue;
            }
            if (state == HEADERS) {
                int blank = findBlankLine(b);
                if (blank < 0) {
                    if (b.length > MAX_HEADER_BYTES) {
                        // Header runaway: drop the part.
                        buf.reset();
                        state = HUNT;
                    }
                    break;
                }
                String headers = new String(b, 0, blank, StandardCharsets.ISO_8859_1);
                partContentLength = parsePartLength(headers);
                int skip = blank;
                // Consume the blank line: \r\n\r\n or \n\n.
                if (skip + 3 < b.length && b[skip] == '\r' && b[skip + 1] == '\n'
                        && b[skip + 2] == '\r' && b[skip + 3] == '\n') {
                    skip += 4;
                } else if (skip + 1 < b.length && b[skip] == '\n' && b[skip + 1] == '\n') {
                    skip += 2;
                } else if (skip + 2 < b.length && b[skip] == '\n'
                        && b[skip + 1] == '\r' && b[skip + 2] == '\n') {
                    skip += 3;
                } else {
                    skip += 1;
                }
                compact(skip);
                dropping = false;
                state = BODY;
                continue;
            }
            // BODY
            if (partContentLength >= 0) {
                if (b.length < partContentLength) break;
                byte[] frame = Arrays.copyOf(b, partContentLength);
                compact(partContentLength);
                if (!dropping) emit(frames, frame);
                state = HUNT;
                continue;
            }
            int found = indexOf(b, 0, b.length, delimiter);
            if (found < 0) {
                if (b.length > MAX_FRAME_BYTES + delimiter.length) {
                    // Oversized part: drop everything but a
                    // tail that could start a delimiter.
                    dropping = true;
                    int keep = delimiter.length - 1;
                    compact(b.length - keep);
                }
                break;
            }
            int frameEnd = found;
            if (frameEnd >= 2 && b[frameEnd - 2] == '\r' && b[frameEnd - 1] == '\n') {
                frameEnd -= 2;
            } else if (frameEnd >= 1 && b[frameEnd - 1] == '\n') {
                frameEnd -= 1;
            }
            byte[] frame = Arrays.copyOf(b, frameEnd);
            compact(found); // leave the delimiter for HUNT
            if (!dropping) emit(frames, frame);
            dropping = false;
            state = HUNT;
        }
        return frames;
    }

    private void emit(List<byte[]> frames, byte[] frame) {
        if (frame.length >= 2 && frame[0] == (byte) 0xFF && frame[1] == (byte) 0xD8) {
            frames.add(frame);
        }
    }

    /** Drops the first {@code n} bytes of the buffer. */
    private void compact(int n) {
        if (n <= 0) return;
        byte[] b = buf.toByteArray();
        buf.reset();
        if (n < b.length) buf.write(b, n, b.length - n);
    }

    /** Index of the blank line that ends a header block
     *  (\r\n\r\n, \n\n, or \n\r\n), or -1. The index points
     *  at the start of the blank-line sequence. */
    private static int findBlankLine(byte[] b) {
        for (int i = 0; i < b.length; i++) {
            if (b[i] == '\n') {
                if (i >= 1 && b[i - 1] == '\n') return i - 1;
                if (i >= 3 && b[i - 1] == '\r' && b[i - 2] == '\n' && b[i - 3] == '\r') {
                    return i - 3;
                }
                if (i >= 2 && b[i - 1] == '\r' && b[i - 2] == '\n') return i - 2;
            }
        }
        return -1;
    }

    private static int parsePartLength(String headers) {
        String lower = headers.toLowerCase();
        int at = lower.indexOf("content-length:");
        if (at < 0) return -1;
        int i = at + "content-length:".length();
        while (i < headers.length() && (headers.charAt(i) == ' ' || headers.charAt(i) == '\t')) i++;
        int end = i;
        while (end < headers.length() && Character.isDigit(headers.charAt(end))) end++;
        if (end == i) return -1;
        try {
            long v = Long.parseLong(headers.substring(i, end));
            return (v >= 0 && v <= MAX_FRAME_BYTES) ? (int) v : -1;
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private static int indexOf(byte[] data, int from, int end, byte[] needle) {
        outer:
        for (int i = from; i + needle.length <= end; i++) {
            for (int j = 0; j < needle.length; j++) {
                if (data[i + j] != needle[j]) continue outer;
            }
            return i;
        }
        return -1;
    }
}
