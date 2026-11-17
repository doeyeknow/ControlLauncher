package app.control.launcher;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * The on-device dictionary file format (v1.29): the packed,
 * self-contained build of a dictionary.txt download. The app
 * fetches dictionary.txt from the ControlDictionary repo,
 * verifies its SHA-256 against version.json, and builds this
 * blob — deterministically, so the same download always builds
 * the same bytes on every phone, and atomically, so a
 * half-written file is never used.
 *
 * <p>FORMAT: magic "CDIC" (4 bytes), format int (1), count int,
 * (count+1) big-endian int offsets, then the concatenated UTF-8
 * word bytes. Offsets[i] is the start of word i in the blob
 * section; offsets[count] is the blob length. The order of the
 * words is the code table, exactly as in dictionary.txt.
 *
 * <p>BUILD RULES: lines split on '\n' with a trailing '\r'
 * stripped; blank lines skipped; a word must be 1..64 chars of
 * letters plus the ASCII apostrophe (the only shapes the codec
 * can ever match — anything else could never encode, so it is
 * refused rather than stored dead). At most MAX_WORDS entries.
 *
 * <p>PARSE RULES: every structural claim is re-checked — magic,
 * format, count, offset monotonicity and bounds, exact blob
 * length, strict UTF-8 round-trip per word, and the same word
 * shape rule. Anything off returns null (corrupt): the caller
 * keeps the old file and rebuilds in the background. Parse
 * never throws.
 *
 * <p>Pure Java, no Android types: the harness proves the build
 * determinism and the corruption rules with the exact shipping
 * code.
 */
public final class DictFile {
    private DictFile() {}

    static final byte[] MAGIC = {'C', 'D', 'I', 'C'};
    static final int FORMAT = 1;
    static final int MAX_WORDS = 2_000_000;
    static final int MAX_WORD_CHARS = 64;
    static final int MAX_BLOB_BYTES = 32 * 1024 * 1024;

    /** A parsed blob: the raw bytes plus the offset table.
     *  Words stay in the blob until wordAt() decodes one, so
     *  a 370,000-word dictionary costs ~7 MB, not ~40 MB of
     *  String objects. */
    public static final class Parsed {
        final byte[] blob;
        final int[] offsets;
        final int blobStart;
        final int count;

        Parsed(byte[] blob, int[] offsets, int blobStart) {
            this.blob = blob;
            this.offsets = offsets;
            this.blobStart = blobStart;
            this.count = offsets.length - 1;
        }

        public int count() {
            return count;
        }

        /** The stored form of one entry, or null out of range.
         *  Decodes only that word's slice. */
        public String wordAt(int index) {
            if (index < 0 || index >= count) return null;
            int s = blobStart + offsets[index];
            int e = blobStart + offsets[index + 1];
            return new String(blob, s, e - s, StandardCharsets.UTF_8);
        }
    }

    /** Builds the blob from dictionary.txt bytes, or null when
     *  the input breaks the build rules (empty, too many
     *  words, an unshaped word). Deterministic: the same input
     *  always yields the same bytes. */
    public static byte[] build(byte[] dictionaryTxt) {
        if (dictionaryTxt == null || dictionaryTxt.length == 0) return null;
        List<byte[]> words = new ArrayList<>();
        int totalBytes = 0;
        int i = 0;
        int n = dictionaryTxt.length;
        while (i < n) {
            int j = i;
            while (j < n && dictionaryTxt[j] != '\n') j++;
            int end = j;
            if (end > i && dictionaryTxt[end - 1] == '\r') end--;
            if (end > i) {
                byte[] raw = new byte[end - i];
                System.arraycopy(dictionaryTxt, i, raw, 0, raw.length);
                String word = decodeStrict(raw);
                if (word == null || !validWord(word)) return null;
                if (words.size() >= MAX_WORDS) return null;
                words.add(raw);
                totalBytes += raw.length;
                if (totalBytes > MAX_BLOB_BYTES) return null;
            }
            i = j + 1;
        }
        if (words.isEmpty()) return null;
        int count = words.size();
        int headerLen = 4 + 4 + 4 + 4 * (count + 1);
        byte[] out = new byte[headerLen + totalBytes];
        System.arraycopy(MAGIC, 0, out, 0, 4);
        putInt(out, 4, FORMAT);
        putInt(out, 8, count);
        int off = 0;
        for (int k = 0; k < count; k++) {
            putInt(out, 12 + 4 * k, off);
            byte[] w = words.get(k);
            System.arraycopy(w, 0, out, headerLen + off, w.length);
            off += w.length;
        }
        putInt(out, 12 + 4 * count, off);
        return out;
    }

    /** Parses a blob, or null when anything fails validation.
     *  Never throws. */
    public static Parsed parse(byte[] blob) {
        try {
            if (blob == null || blob.length < 12) return null;
            if (blob[0] != MAGIC[0] || blob[1] != MAGIC[1]
                    || blob[2] != MAGIC[2] || blob[3] != MAGIC[3]) return null;
            if (getInt(blob, 4) != FORMAT) return null;
            int count = getInt(blob, 8);
            if (count < 1 || count > MAX_WORDS) return null;
            int headerLen = 12 + 4 * (count + 1);
            if (blob.length < headerLen || blob.length > headerLen + MAX_BLOB_BYTES) return null;
            int[] offsets = new int[count + 1];
            int prev = 0;
            for (int k = 0; k <= count; k++) {
                int o = getInt(blob, 12 + 4 * k);
                if (o < prev) return null;
                offsets[k] = o;
                prev = o;
            }
            if (offsets[0] != 0) return null;
            if (headerLen + offsets[count] != blob.length) return null;
            Parsed p = new Parsed(blob, offsets, headerLen);
            for (int k = 0; k < count; k++) {
                int s = headerLen + offsets[k];
                int e = headerLen + offsets[k + 1];
                if (e <= s) return null;
                byte[] raw = new byte[e - s];
                System.arraycopy(blob, s, raw, 0, raw.length);
                String word = decodeStrict(raw);
                if (word == null || !validWord(word)) return null;
            }
            return p;
        } catch (Exception e) {
            return null;
        }
    }

    /** Strict UTF-8 decode: malformed sequences are rejected
     *  (a lossy decode would not re-encode to the same bytes). */
    static String decodeStrict(byte[] raw) {
        String s = new String(raw, StandardCharsets.UTF_8);
        byte[] back = s.getBytes(StandardCharsets.UTF_8);
        if (back.length != raw.length) return null;
        for (int i = 0; i < raw.length; i++) {
            if (back[i] != raw[i]) return null;
        }
        return s;
    }

    /** The codec's word shape: 1..64 chars, letters plus the
     *  ASCII apostrophe. Anything else could never match an
     *  encoded word, so it is refused at build/parse time. */
    public static boolean validWord(String word) {
        if (word == null) return false;
        int len = word.length();
        if (len < 1 || len > MAX_WORD_CHARS) return false;
        for (int i = 0; i < len; i++) {
            char c = word.charAt(i);
            if (c != '\'' && !Character.isLetter(c)) return false;
        }
        return true;
    }

    static void putInt(byte[] out, int at, int v) {
        out[at] = (byte) (v >>> 24);
        out[at + 1] = (byte) (v >>> 16);
        out[at + 2] = (byte) (v >>> 8);
        out[at + 3] = (byte) v;
    }

    static int getInt(byte[] in, int at) {
        return ((in[at] & 0xFF) << 24) | ((in[at + 1] & 0xFF) << 16)
                | ((in[at + 2] & 0xFF) << 8) | (in[at + 3] & 0xFF);
    }
}
