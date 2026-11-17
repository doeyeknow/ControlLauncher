package app.control.launcher;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Which learned codes each peer/channel has been taught
 * (v1.29): a learned code goes on the wire to a scope only
 * after that scope was taught it. Scopes are channel ids for
 * walkie roads ("open:12", "priv:3:…") and chat channel ids
 * ("chat:…", "dm:…") — the same string the road already uses
 * to name the conversation.
 *
 * <p>Persistence is one text file, one line per scope:
 * "scope\tidx,idx,idx". Parsing is defensive: bad scopes,
 * bad indices, and anything past the caps are skipped, never
 * fatal. A scope the store never saw reads as "taught
 * nothing", so a lost file only costs re-teaching — never a
 * wrong decode.
 *
 * <p>Pure Java, no Android types: the harness proves the
 * round-trip and the caps with the exact shipping code.
 */
public final class TaughtStore {

    public static final int MAX_SCOPES = 500;

    private final Map<String, Set<Integer>> taught = new HashMap<>();

    /** The learned indices a scope has been taught: a copy,
     *  empty when the scope is unknown or invalid. */
    public synchronized Set<Integer> forScope(String scope) {
        if (!validScope(scope)) return Collections.emptySet();
        Set<Integer> s = taught.get(scope);
        return s == null ? Collections.emptySet() : new HashSet<>(s);
    }

    /** Marks indices taught for a scope. Returns true when
     *  the store changed. Invalid scopes and out-of-range
     *  indices are ignored. */
    public synchronized boolean markTaught(String scope,
                                           java.util.Collection<Integer> indices) {
        if (!validScope(scope) || indices == null || indices.isEmpty()) return false;
        Set<Integer> s = taught.get(scope);
        if (s == null) {
            if (taught.size() >= MAX_SCOPES) return false;
            s = new HashSet<>();
            taught.put(scope, s);
        }
        boolean changed = false;
        for (Integer idx : indices) {
            if (idx == null) continue;
            if (idx < WordTable.LEARNED_BASE
                    || idx >= WordTable.LEARNED_BASE + WordTable.MAX_LEARNED) continue;
            changed |= s.add(idx);
        }
        return changed;
    }

    /** A scope is a short string with no whitespace or
     *  control characters — channel ids already qualify. */
    public static boolean validScope(String scope) {
        if (scope == null || scope.isEmpty() || scope.length() > 256) return false;
        for (int i = 0; i < scope.length(); i++) {
            if (scope.charAt(i) <= ' ') return false;
        }
        return true;
    }

    /** Serializes the store, scopes sorted. */
    public synchronized String serialize() {
        List<String> scopes = new ArrayList<>(taught.keySet());
        Collections.sort(scopes);
        StringBuilder sb = new StringBuilder();
        for (String scope : scopes) {
            Set<Integer> s = taught.get(scope);
            if (s == null || s.isEmpty()) continue;
            List<Integer> idx = new ArrayList<>(s);
            Collections.sort(idx);
            sb.append(scope).append('\t');
            for (int i = 0; i < idx.size(); i++) {
                if (i > 0) sb.append(',');
                sb.append(idx.get(i));
            }
            sb.append('\n');
        }
        return sb.toString();
    }

    /** Loads serialized state, defensively. Bad lines are
     *  skipped; loading replaces the previous state. */
    public synchronized void load(String text) {
        taught.clear();
        if (text == null || text.isEmpty()) return;
        for (String line : text.split("\n")) {
            if (taught.size() >= MAX_SCOPES) break;
            int tab = line.indexOf('\t');
            if (tab <= 0) continue;
            String scope = line.substring(0, tab);
            if (!validScope(scope)) continue;
            Set<Integer> s = new HashSet<>();
            for (String part : line.substring(tab + 1).split(",")) {
                String t = part.trim();
                if (t.isEmpty()) continue;
                int idx;
                try {
                    idx = Integer.parseInt(t);
                } catch (Exception e) {
                    continue;
                }
                if (idx < WordTable.LEARNED_BASE
                        || idx >= WordTable.LEARNED_BASE + WordTable.MAX_LEARNED) continue;
                s.add(idx);
                if (s.size() >= WordTable.MAX_LEARNED) break;
            }
            if (!s.isEmpty()) taught.put(scope, s);
        }
    }
}
