package app.control.launcher;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * device_config flag shaping (v1.37). Pure Java — no Android
 * classes — so the harness exercises the exact shipping code.
 *
 * {@code cmd device_config list} answers in one of two shapes,
 * and the parser handles both without guessing:
 *
 *   - flag lines: {@code namespace/key=value} (the no-namespace
 *     list) or {@code key=value} (a list scoped to one
 *     namespace, which supplies the namespace);
 *   - bare namespace names, one per line (some builds list the
 *     namespaces first — the caller then fetches each namespace
 *     separately).
 *
 * Lines that match neither shape are skipped, never invented
 * into flags. A value may itself contain '=' (the first '='
 * splits) and may be empty. The same flag listed twice keeps
 * the last value seen.
 *
 * Grouping is the category rule Adam asked for: namespaces are
 * the categories, alphabetical; flags are alphabetical within
 * their category.
 */
public final class ConfigFlags {

    /** One flag: its category, key, and current raw value. */
    public static final class Flag {
        public final String namespace;
        public final String key;
        public final String value;

        Flag(String namespace, String key, String value) {
            this.namespace = namespace;
            this.key = key;
            this.value = value;
        }
    }

    /** One category: a namespace and its flags, sorted. */
    public static final class Group {
        public final String namespace;
        public final List<Flag> flags;

        Group(String namespace, List<Flag> flags) {
            this.namespace = namespace;
            this.flags = flags;
        }
    }

    /** What one list call produced: flags, namespace names, or
     *  neither (unparseable output — the caller shows it raw). */
    public static final class ParseResult {
        public final List<Flag> flags;
        /** Namespace names, in listed order, de-duplicated. */
        public final List<String> namespaces;

        ParseResult(List<Flag> flags, List<String> namespaces) {
            this.flags = flags;
            this.namespaces = namespaces;
        }

        /** True when at least one flag line parsed. */
        public boolean sawFlags() {
            return !flags.isEmpty();
        }

        /** True when the output carried namespace names and no
         *  flags: the caller should fetch each namespace. */
        public boolean sawNamespacesOnly() {
            return flags.isEmpty() && !namespaces.isEmpty();
        }
    }

    private ConfigFlags() {}

    /**
     * Parse one {@code device_config list} output.
     * {@code defaultNamespace} is the namespace the call was
     * scoped to (null/empty for the unscoped list): bare
     * {@code key=value} lines take it as their namespace, and
     * without one they are skipped.
     */
    public static ParseResult parse(String text, String defaultNamespace) {
        Map<String, Flag> byId = new LinkedHashMap<>();
        List<String> namespaces = new ArrayList<>();
        if (text != null) {
            for (String rawLine : text.split("\\n")) {
                String line = rawLine.trim();
                if (line.isEmpty()) continue;
                int eq = line.indexOf('=');
                if (eq > 0) {
                    String left = line.substring(0, eq).trim();
                    String value = line.substring(eq + 1);
                    String namespace;
                    String key;
                    int slash = left.indexOf('/');
                    if (slash > 0) {
                        namespace = left.substring(0, slash).trim();
                        key = left.substring(slash + 1).trim();
                    } else if (defaultNamespace != null && !defaultNamespace.isEmpty()) {
                        namespace = defaultNamespace;
                        key = left;
                    } else {
                        continue;
                    }
                    if (!BridgeOps.validNamespace(namespace)) continue;
                    if (!BridgeOps.validConfigKey(key)) continue;
                    byId.put(namespace + "/" + key, new Flag(namespace, key, value));
                } else if (BridgeOps.validNamespace(line)) {
                    if (!namespaces.contains(line)) namespaces.add(line);
                }
                // Anything else is skipped, never guessed.
            }
        }
        return new ParseResult(new ArrayList<>(byId.values()), namespaces);
    }

    /** Group flags by namespace: categories alphabetical,
     *  flags alphabetical within each category. */
    public static List<Group> group(List<Flag> flags) {
        Map<String, List<Flag>> byNamespace = new LinkedHashMap<>();
        if (flags != null) {
            for (Flag flag : flags) {
                List<Flag> list = byNamespace.get(flag.namespace);
                if (list == null) {
                    list = new ArrayList<>();
                    byNamespace.put(flag.namespace, list);
                }
                list.add(flag);
            }
        }
        List<String> names = new ArrayList<>(byNamespace.keySet());
        Collections.sort(names);
        List<Group> out = new ArrayList<>();
        for (String name : names) {
            List<Flag> list = byNamespace.get(name);
            Collections.sort(list, new Comparator<Flag>() {
                @Override
                public int compare(Flag a, Flag b) {
                    return a.key.compareTo(b.key);
                }
            });
            out.add(new Group(name, list));
        }
        return out;
    }

    /** How many flags a grouped list holds in total. */
    public static int totalFlags(List<Group> groups) {
        int total = 0;
        if (groups != null) {
            for (Group group : groups) total += group.flags.size();
        }
        return total;
    }

    /** Display words for a flag's raw value: empty reads
     *  "(empty)", never a blank row. */
    public static String displayValue(String value) {
        if (value == null) return "(not set)";
        if (value.isEmpty()) return "(empty)";
        return value;
    }

    /** True when the value is exactly "true" or "false": the
     *  only values that get a toggle switch (v1.38).
     *  device_config values are strings, so anything else
     *  keeps the long-press menu instead of a switch. */
    public static boolean isBooleanValue(String value) {
        return "true".equals(value) || "false".equals(value);
    }

    /** The flipped value for a boolean flag, or null when the
     *  value is not exactly "true"/"false". The toggle reads
     *  the flag fresh and flips what it finds. */
    public static String toggleValue(String value) {
        if ("true".equals(value)) return "false";
        if ("false".equals(value)) return "true";
        return null;
    }

    /** Every namespace a list call revealed — the namespaces
     *  of its flag lines plus any bare namespace names —
     *  sorted alphabetically, de-duplicated. Drives the
     *  flags picker (v1.38). */
    public static List<String> sortedNamespaces(ParseResult result) {
        java.util.TreeSet<String> names = new java.util.TreeSet<>();
        if (result != null) {
            for (Flag flag : result.flags) {
                if (flag.namespace != null && !flag.namespace.isEmpty()) {
                    names.add(flag.namespace);
                }
            }
            for (String name : result.namespaces) {
                if (name != null && !name.isEmpty()) names.add(name);
            }
        }
        return new ArrayList<>(names);
    }
}
