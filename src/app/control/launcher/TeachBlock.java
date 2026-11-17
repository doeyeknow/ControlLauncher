package app.control.launcher;

import java.util.ArrayList;
import java.util.List;

/**
 * The in-band teaching block (v1.29): when a message uses a
 * learned word the peer has not been taught yet, the word
 * travels spelled out in the text AND its (word, code)
 * assignment rides the envelope, so the receiver learns the
 * code and later messages can use it.
 *
 * <p>FORMAT: entries joined by ',', each "word:CODE" — the
 * stored (lowercase) word, a colon, and the wire code string
 * for the learned index. Words hold only letters and the
 * apostrophe, codes only alphanumerics, so neither separator
 * can ever appear inside an entry. At most MAX_ENTRIES entries;
 * the rest wait for a later message.
 *
 * <p>Parsing is defensive: malformed entries, unshaped words,
 * unparseable codes, and codes outside the learned range are
 * skipped, never fatal. Parsing never throws.
 *
 * <p>Pure Java, no Android types.
 */
public final class TeachBlock {
    private TeachBlock() {}

    public static final int MAX_ENTRIES = 200;

    public static final class Entry {
        public final String word;
        public final int index;

        public Entry(String word, int index) {
            this.word = word;
            this.index = index;
        }
    }

    /** Builds a teach block from entries, skipping invalid
     *  ones and capping the count. Returns "" when nothing
     *  valid remains. */
    public static String build(List<Entry> entries) {
        if (entries == null || entries.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        int n = 0;
        for (Entry e : entries) {
            if (e == null || e.word == null) continue;
            if (!WordTable.learnableShape(e.word)) continue;
            if (e.index < WordTable.LEARNED_BASE
                    || e.index >= WordTable.LEARNED_BASE + WordTable.MAX_LEARNED) continue;
            String code = WordDict.codeFor(e.index);
            if (code.isEmpty()) continue;
            if (n > 0) sb.append(',');
            sb.append(e.word).append(':').append(code);
            if (++n >= MAX_ENTRIES) break;
        }
        return sb.toString();
    }

    /** Parses a teach block, skipping malformed entries.
     *  Never throws; null/empty input yields no entries. */
    public static List<Entry> parse(String block) {
        List<Entry> out = new ArrayList<>();
        if (block == null || block.isEmpty()) return out;
        for (String part : block.split(",", -1)) {
            if (out.size() >= MAX_ENTRIES) break;
            int colon = part.indexOf(':');
            if (colon <= 0 || colon == part.length() - 1) continue;
            String word = part.substring(0, colon);
            if (!WordTable.learnableShape(word)) continue;
            int idx = WordDict.parseCodeString(part.substring(colon + 1));
            if (idx < WordTable.LEARNED_BASE
                    || idx >= WordTable.LEARNED_BASE + WordTable.MAX_LEARNED) continue;
            out.add(new Entry(word, idx));
        }
        return out;
    }
}
