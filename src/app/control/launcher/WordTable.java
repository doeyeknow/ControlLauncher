package app.control.launcher;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * The codec's word table (v1.29): a base section built from the
 * dictionary file, plus a learned section the app grows itself.
 *
 * <p>BASE: the words live in a {@link DictFile} blob; lookup is
 * a binary search over a sorted permutation index (sorted by
 * lowercase word, ties broken by position, so the FIRST
 * occurrence of a duplicated lowercase form always wins — the
 * same first-wins rule the v1.28 HashMap had). Words decode
 * from the blob one at a time, so a 370,000-word dictionary
 * costs megabytes, not tens of megabytes.
 *
 * <p>LEARNED: words the app saw in sent or received text that
 * are not in the base list. Each gets a stable index at or
 * above LEARNED_BASE, assigned in first-seen order, never
 * reused, never renumbered — append-only forever, so a code
 * learned once keeps meaning the same word. The learned
 * section is capped (MAX_LEARNED); at the cap the app stops
 * learning and keeps working.
 *
 * <p>GRADUATION (the rule the later merge build relies on):
 * when a word exists in BOTH sections, the encoder prefers the
 * BASE code, and the decoder accepts codes from EITHER section.
 * A learned word that later merges into the base file keeps
 * decoding under its old learned code, while new messages use
 * the base code.
 *
 * <p>TEACHING: a phone adopts a taught (word, index) mapping
 * from the sender — the sender's code is authoritative. If the
 * word already had a different local code, the old code stays
 * decodable as an ALIAS (messages already in flight still
 * decode), while future sends use the adopted code. If the
 * taught index already meant a different word here, that word
 * loses its learned code (it re-learns with a fresh index on
 * its next sighting) — one index never means two words.
 *
 * <p>All methods are synchronized: the table is shared by the
 * send, receive, and background-build threads, and the swap
 * of the active table in WordDict hands over a fully built
 * table, learned state carried over via a snapshot.
 *
 * <p>Pure Java, no Android types: the harness proves lookup,
 * learning, adoption, aliases, and the graduation rule with
 * the exact shipping code.
 */
public final class WordTable {

    /** Learned indices start here — far above any plausible
     *  base list, so base growth never collides with them. */
    public static final int LEARNED_BASE = 5_000_000;
    /** Cap on learned entries. */
    public static final int MAX_LEARNED = 50_000;

    private final DictFile.Parsed base;
    /** Base indices sorted by (lowercase word, index). */
    private final int[] sorted;

    private final Map<String, Integer> learnedByWord = new HashMap<>();
    private final Map<Integer, String> learnedByIndex = new HashMap<>();
    /** Old local codes kept decodable after an adoption. */
    private final Map<Integer, String> learnedAliases = new HashMap<>();
    private int nextLearned = LEARNED_BASE;
    private boolean atCap;

    /** Builds a table over a parsed dictionary blob. */
    public WordTable(DictFile.Parsed base) {
        if (base == null) throw new IllegalArgumentException("null base");
        this.base = base;
        Integer[] order = new Integer[base.count()];
        final String[] lower = new String[base.count()];
        for (int i = 0; i < base.count(); i++) {
            order[i] = i;
            lower[i] = base.wordAt(i).toLowerCase(Locale.ROOT);
        }
        Arrays.sort(order, new Comparator<Integer>() {
            @Override public int compare(Integer a, Integer b) {
                int c = lower[a].compareTo(lower[b]);
                return c != 0 ? c : Integer.compare(a, b);
            }
        });
        sorted = new int[base.count()];
        for (int i = 0; i < order.length; i++) sorted[i] = order[i];
    }

    /** Builds a table from an in-memory word array (the
     *  built-in v1 list): the array goes through the same
     *  DictFile build the downloaded file does, so both paths
     *  share one representation. */
    public static WordTable fromWords(String[] words) {
        StringBuilder sb = new StringBuilder();
        for (String w : words) sb.append(w).append('\n');
        byte[] blob = DictFile.build(
                sb.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
        DictFile.Parsed p = blob == null ? null : DictFile.parse(blob);
        if (p == null) throw new IllegalStateException("builtin list rejected");
        return new WordTable(p);
    }

    public synchronized int baseCount() {
        return base.count();
    }

    /** The stored form of a base entry, or null out of range. */
    public synchronized String baseWordAt(int index) {
        return base.wordAt(index);
    }

    /** The base index of a lowercase word, or null. First
     *  occurrence wins among duplicate lowercase forms. */
    public synchronized Integer baseIndex(String lower) {
        if (lower == null || lower.isEmpty()) return null;
        int lo = 0;
        int hi = sorted.length - 1;
        int found = -1;
        while (lo <= hi) {
            int mid = (lo + hi) >>> 1;
            String key = base.wordAt(sorted[mid]).toLowerCase(Locale.ROOT);
            int c = key.compareTo(lower);
            if (c < 0) lo = mid + 1;
            else if (c > 0) hi = mid - 1;
            else {
                found = mid;
                hi = mid - 1; // keep searching left for the first
            }
        }
        return found < 0 ? null : sorted[found];
    }

    /** The stored form for any code index: base, learned, or
     *  alias — or null when the index means nothing here. */
    public synchronized String wordAt(int index) {
        if (index >= 0 && index < base.count()) return base.wordAt(index);
        String w = learnedByIndex.get(index);
        if (w != null) return w;
        return learnedAliases.get(index);
    }

    /** The learned index of a lowercase word, or null. */
    public synchronized Integer learnedIndex(String lower) {
        return learnedByWord.get(lower);
    }

    public synchronized int learnedCount() {
        return learnedByWord.size();
    }

    /** Learned words in first-learned (index) order — the
     *  order proposals go out in, so the oldest learned word
     *  is proposed first. Aliases are not included: they are
     *  decoding history, not words this phone learned. */
    public synchronized List<String> learnedWordsByIndex() {
        List<Integer> idx = new ArrayList<>(learnedByIndex.keySet());
        idx.sort(null);
        List<String> out = new ArrayList<>(idx.size());
        for (int i : idx) out.add(learnedByIndex.get(i));
        return out;
    }

    public synchronized boolean atCap() {
        return atCap;
    }

    /** Learns a word in its stored (lowercase) form, returning
     *  its learned index — the existing one when already
     *  learned, -1 when the word is in the base list, is not
     *  a learnable shape, or the cap is reached. */
    public synchronized int learn(String storedLower) {
        if (storedLower == null || !learnableShape(storedLower)) return -1;
        if (baseIndex(storedLower) != null) return -1;
        Integer had = learnedByWord.get(storedLower);
        if (had != null) return had;
        if (learnedByWord.size() >= MAX_LEARNED) {
            atCap = true;
            return -1;
        }
        int idx = nextLearned++;
        learnedByWord.put(storedLower, idx);
        learnedByIndex.put(idx, storedLower);
        return idx;
    }

    /** Adopts a taught (word, index) mapping: the sender's
     *  code is authoritative. Returns true when the table
     *  changed. A word already in the base list is never
     *  recorded as learned (graduation: the base code wins
     *  for encoding) — but the taught code is kept as an
     *  alias, so it still decodes. A displaced local code
     *  stays decodable as an alias; a displaced word loses
     *  its code and re-learns later. */
    public synchronized boolean adopt(String storedLower, int index) {
        if (storedLower == null || !learnableShape(storedLower)) return false;
        if (index < LEARNED_BASE || index >= LEARNED_BASE + MAX_LEARNED) return false;
        if (baseIndex(storedLower) != null) {
            // Graduated word: the sender's code must still
            // decode, so alias it — unless the index holds
            // a live learned code for another word, which a
            // graduated teach must never displace.
            if (learnedByIndex.containsKey(index)) return false;
            String had = learnedAliases.get(index);
            if (storedLower.equals(had)) return false;
            learnedAliases.put(index, storedLower);
            if (index >= nextLearned) nextLearned = index + 1;
            return true;
        }
        Integer had = learnedByWord.get(storedLower);
        if (had != null && had == index) return false;
        String displaced = learnedByIndex.get(index);
        if (displaced != null && !displaced.equals(storedLower)) {
            learnedByWord.remove(displaced);
        }
        if (had != null) {
            learnedByIndex.remove(had);
            learnedAliases.put(had, storedLower);
        }
        learnedByWord.put(storedLower, index);
        learnedByIndex.put(index, storedLower);
        if (index >= nextLearned) nextLearned = index + 1;
        return true;
    }

    /** Learns every learnable unknown word in plain text,
     *  returning how many were newly learned. Callers pass
     *  ONLY plain sent text or cleanly decoded received text
     *  — never an undecoded payload, whose code fragments
     *  would poison the table. */
    public synchronized int learnFromText(String text) {
        if (text == null || text.isEmpty()) return 0;
        int added = 0;
        int n = text.length();
        int i = 0;
        while (i < n) {
            char c = text.charAt(i);
            if (WordDict.isWordChar(c)) {
                int j = i + 1;
                while (j < n && WordDict.isWordChar(text.charAt(j))) j++;
                String word = text.substring(i, j);
                if (WordDict.hasLetter(word) && learnableForm(word)) {
                    String lower = word.toLowerCase(Locale.ROOT);
                    if (learnedByWord.get(lower) == null
                            && baseIndex(lower) == null
                            && learn(lower) >= LEARNED_BASE) added++;
                }
                i = j;
            } else {
                i++;
            }
        }
        return added;
    }

    /** A word form worth learning: 2..64 chars, all-lowercase
     *  or Capitalized. ALL-CAPS runs and single letters are
     *  skipped — they would never encode under the codec's
     *  capitalization rule, so learning them only spends
     *  codes. */
    static boolean learnableForm(String word) {
        if (word.length() < 2 || word.length() > DictFile.MAX_WORD_CHARS) return false;
        if (WordDict.isAllLower(word)) return true;
        return WordDict.isCapitalized(word);
    }

    /** The stored shape of a learned word: lowercase,
     *  letters plus apostrophe, 2..64 chars. */
    static boolean learnableShape(String stored) {
        if (stored.length() < 2 || stored.length() > DictFile.MAX_WORD_CHARS) return false;
        for (int i = 0; i < stored.length(); i++) {
            char c = stored.charAt(i);
            if (c != '\'' && !Character.isLetter(c)) return false;
            if (Character.isLetter(c) && !Character.isLowerCase(c)) return false;
        }
        return true;
    }

    // ---- learned persistence ----

    /** Serializes the learned section: one "index word" line
     *  per entry, sorted by index. Aliases are NOT persisted
     *  — they exist only to decode messages already in flight,
     *  and re-teaching recreates anything still needed. */
    public synchronized String serializeLearned() {
        List<Integer> idx = new ArrayList<>(learnedByIndex.keySet());
        idx.sort(null);
        StringBuilder sb = new StringBuilder();
        for (int i : idx) {
            sb.append(i).append(' ').append(learnedByIndex.get(i)).append('\n');
        }
        return sb.toString();
    }

    /** Loads a serialized learned section, defensively:
     *  malformed lines, out-of-range indices, unshaped words,
     *  duplicates, and anything past the cap are skipped.
     *  Returns the number of entries loaded. */
    public synchronized int loadLearned(String text) {
        if (text == null || text.isEmpty()) return 0;
        int loaded = 0;
        Set<String> seenWords = new HashSet<>();
        Set<Integer> seenIdx = new HashSet<>();
        for (String line : text.split("\n")) {
            String t = line.trim();
            if (t.isEmpty()) continue;
            int sp = t.indexOf(' ');
            if (sp <= 0) continue;
            int idx;
            try {
                idx = Integer.parseInt(t.substring(0, sp));
            } catch (Exception e) {
                continue;
            }
            String word = t.substring(sp + 1).trim();
            if (idx < LEARNED_BASE || idx >= LEARNED_BASE + MAX_LEARNED) continue;
            if (!learnableShape(word)) continue;
            if (!seenWords.add(word) || !seenIdx.add(idx)) continue;
            if (baseIndex(word) != null) {
                // Graduated into the base list: the base
                // code wins for encoding, but the old
                // learned code stays decodable as an alias
                // for in-flight messages.
                if (!learnedByIndex.containsKey(idx)
                        && !learnedAliases.containsKey(idx)) {
                    learnedAliases.put(idx, word);
                    if (idx >= nextLearned) nextLearned = idx + 1;
                }
                continue;
            }
            if (learnedByWord.size() >= MAX_LEARNED) {
                atCap = true;
                break;
            }
            learnedByWord.put(word, idx);
            learnedByIndex.put(idx, word);
            if (idx >= nextLearned) nextLearned = idx + 1;
            loaded++;
        }
        return loaded;
    }

    /** A snapshot of the learned state, for carrying it over
     *  a base-table swap. */
    public static final class LearnedState {
        final Map<String, Integer> byWord;
        final Map<Integer, String> byIndex;
        final Map<Integer, String> aliases;
        final int next;
        final boolean capped;

        LearnedState(Map<String, Integer> byWord, Map<Integer, String> byIndex,
                     Map<Integer, String> aliases, int next, boolean capped) {
            this.byWord = byWord;
            this.byIndex = byIndex;
            this.aliases = aliases;
            this.next = next;
            this.capped = capped;
        }
    }

    public synchronized LearnedState snapshotLearned() {
        return new LearnedState(new HashMap<>(learnedByWord),
                new HashMap<>(learnedByIndex),
                new HashMap<>(learnedAliases), nextLearned, atCap);
    }

    /** Restores learned state onto this table (a fresh base
     *  build). Entries whose word graduated into the new base
     *  stop being learned — the base code wins from here on
     *  — but their old codes are kept as aliases, so
     *  in-flight messages coded with them still decode. */
    public synchronized void restoreLearned(LearnedState s) {
        if (s == null) return;
        learnedByWord.clear();
        learnedByIndex.clear();
        learnedAliases.clear();
        for (Map.Entry<String, Integer> e : s.byWord.entrySet()) {
            if (baseIndex(e.getKey()) != null) {
                if (!learnedByIndex.containsKey(e.getValue())) {
                    learnedAliases.put(e.getValue(), e.getKey());
                }
                continue;
            }
            learnedByWord.put(e.getKey(), e.getValue());
            learnedByIndex.put(e.getValue(), e.getKey());
        }
        for (Map.Entry<Integer, String> e : s.aliases.entrySet()) {
            if (learnedByIndex.containsKey(e.getKey())) continue;
            if (learnedAliases.containsKey(e.getKey())) continue;
            learnedAliases.put(e.getKey(), e.getValue());
        }
        nextLearned = s.next;
        atCap = s.capped;
    }
}
