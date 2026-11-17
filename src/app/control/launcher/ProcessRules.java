package app.control.launcher;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Shaping for the Deep controls running-processes list (v1.33).
 * Pure Java — no Android classes — so the harness exercises the
 * exact shipping code.
 *
 * Input is the bridge's PROCESSES op: fixed {@code ps -A} output
 * (toybox columns: USER PID PPID VSZ RSS WCHAN ADDR S NAME).
 * Nothing here invents data: a row carries exactly what ps
 * reported, plus two derived facts that are computed, never
 * guessed — the package candidate (the process name up to the
 * first ':', and only when it looks like a package name AND is
 * installed) and the governance flags (system user, refuse-list
 * membership). A process with no known package mapping gets no
 * Kill/Disable commands at all.
 *
 * This class also owns the bridge refuse-lists (single source of
 * truth — ShellBridgeMain reads them from here, so the list the
 * UI warns about is the list the bridge enforces) and the
 * v1.34 group classification the filter control consumes:
 * CONTROLLABLE vs SYSTEM, computed from exactly the signals
 * above — no new heuristics, no new bridge data.
 */
public final class ProcessRules {

    /** Packages no bridge write may touch at all. */
    public static final Set<String> REFUSE_ALL = new HashSet<>(Arrays.asList(
            "app.control.launcher", "android", "com.android.systemui",
            "com.android.settings", "com.android.shell", "com.android.phone",
            "com.android.server.telecom", "com.android.packageinstaller",
            "com.google.android.packageinstaller",
            "com.android.permissioncontroller",
            "com.google.android.permissioncontroller"));

    /** Packages additionally protected from force-stop / disable / clear. */
    public static final Set<String> REFUSE_DESTRUCTIVE = new HashSet<>(Arrays.asList(
            "com.google.android.gms", "com.google.android.gsf",
            "com.android.vending", "com.google.android.packageinstaller",
            "com.android.providers.settings", "com.android.providers.telephony",
            "com.android.providers.media", "com.android.providers.downloads",
            "com.android.bluetooth", "com.android.nfc",
            "com.android.networkstack", "com.android.networkstack.tethering",
            "com.google.android.networkstack", "com.android.wifi.resources",
            "com.google.android.wifi.resources", "com.android.ons",
            "com.android.stk", "com.google.android.euicc",
            "com.android.providers.blockednumber",
            "com.android.providers.userdictionary"));

    private static final Pattern PACKAGE_PATTERN = Pattern.compile(
            "^[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+$");
    private static final Pattern APP_USER_PATTERN = Pattern.compile("^u\\d+_[ai]\\d+$");

    /** One shaped ps row. All fields are ps-reported except
     *  {@link #packageName} (null when no mapping is known),
     *  {@link #systemUser}, {@link #refuseReason} and
     *  {@link #warning}, which are computed by the rules below. */
    public static final class Row {
        public final String user;
        public final int pid;
        public final int ppid;
        public final long vszKb;
        public final long rssKb;
        public final String state;
        public final String name;
        /** Installed package this process belongs to, or null
         *  when no mapping is known. Never guessed: the name
         *  must look like a package AND be installed. */
        public final String packageName;
        /** True when the owner is not an app user (uN_aNNN /
         *  uN_iNNN): system, root, radio, shell, and friends. */
        public final boolean systemUser;
        /** Plain-words reason when the package is on a
         *  refuse-list, null otherwise. */
        public final String refuseReason;
        /** True when the row must carry a visible warning:
         *  a system process, or a refuse-listed package. */
        public final boolean warning;

        Row(String user, int pid, int ppid, long vszKb, long rssKb,
            String state, String name, String packageName,
            boolean systemUser, String refuseReason) {
            this.user = user;
            this.pid = pid;
            this.ppid = ppid;
            this.vszKb = vszKb;
            this.rssKb = rssKb;
            this.state = state;
            this.name = name;
            this.packageName = packageName;
            this.systemUser = systemUser;
            this.refuseReason = refuseReason;
            this.warning = systemUser || refuseReason != null;
        }
    }

    private ProcessRules() {}

    /**
     * Shape raw {@code ps -A} output into rows, in ps order.
     * The header line, blank lines, and lines that do not parse
     * as ps rows are skipped — a weird line never becomes a
     * fake process. {@code installedPackages} is the set of
     * package names installed on the phone (null = none known:
     * every row reads as unmapped, never guessed).
     */
    public static List<Row> parse(String psOutput, Set<String> installedPackages) {
        List<Row> rows = new ArrayList<>();
        if (psOutput == null) return rows;
        for (String line : psOutput.split("\\n")) {
            String trimmed = line.trim();
            if (trimmed.isEmpty()) continue;
            String[] parts = trimmed.split("\\s+", 9);
            if (parts.length < 9) continue;
            if ("USER".equals(parts[0])) continue;
            int pid;
            int ppid;
            long vsz;
            long rss;
            try {
                pid = Integer.parseInt(parts[1]);
                ppid = Integer.parseInt(parts[2]);
                vsz = Long.parseLong(parts[3]);
                rss = Long.parseLong(parts[4]);
            } catch (NumberFormatException e) {
                continue;
            }
            String user = parts[0];
            String state = parts[7];
            String name = parts[8].trim();
            if (name.isEmpty()) continue;
            String candidate = packageCandidate(name);
            String pkg = null;
            if (candidate != null && installedPackages != null
                    && installedPackages.contains(candidate)) {
                pkg = candidate;
            }
            boolean systemUser = !APP_USER_PATTERN.matcher(user).matches();
            String reason = pkg == null ? null : refuseReason(pkg);
            rows.add(new Row(user, pid, ppid, vsz, rss, state, name,
                    pkg, systemUser, reason));
        }
        return rows;
    }

    /**
     * The package a process name could belong to: the name up to
     * the first ':' (secondary processes are named
     * {@code <package>:<suffix>}), and only when the result
     * looks like a package name. Anything else — system_server,
     * zygote64, kernel threads, app_process — is null.
     */
    public static String packageCandidate(String name) {
        if (name == null) return null;
        String base = name;
        int colon = name.indexOf(':');
        if (colon >= 0) base = name.substring(0, colon);
        if (base.isEmpty()) return null;
        return PACKAGE_PATTERN.matcher(base).matches() ? base : null;
    }

    /** Plain-words refuse reason for a package, or null when
     *  the package is on no refuse-list. */
    public static String refuseReason(String pkg) {
        if (pkg == null) return null;
        if (REFUSE_ALL.contains(pkg)) {
            return "No bridge write may touch this package — it is on the bridge refuse-list.";
        }
        if (REFUSE_DESTRUCTIVE.contains(pkg)) {
            return "Force-stop, disable, and clear-data are protected for this package "
                    + "by the bridge refuse-list.";
        }
        return null;
    }

    /** One status line for a row: PID, state, memory. */
    public static String statusLine(Row row) {
        StringBuilder out = new StringBuilder();
        out.append("PID ").append(row.pid)
                .append(" · ").append(row.state)
                .append(" · ").append(humanKb(row.rssKb));
        if (row.systemUser) out.append(" · system process");
        if (row.refuseReason != null) out.append(" · protected");
        return out.toString();
    }

    /** The Details dialog body: everything ps reported, plus
     *  the mapping and governance facts. Nothing invented — a
     *  row with no mapping says so, and the command line shown
     *  is the process name exactly as ps reported it. */
    public static String detailText(Row row) {
        StringBuilder out = new StringBuilder();
        out.append("Process: ").append(row.name).append('\n');
        out.append("PID: ").append(row.pid)
                .append(" · Parent PID: ").append(row.ppid).append('\n');
        out.append("User: ").append(row.user).append('\n');
        out.append("State: ").append(stateWord(row.state)).append('\n');
        out.append("Memory: ").append(humanKb(row.rssKb))
                .append(" resident · ").append(humanKb(row.vszKb))
                .append(" virtual\n");
        if (row.packageName != null) {
            out.append("Package: ").append(row.packageName).append('\n');
        } else {
            out.append("Package: no package mapping is known for this process — "
                    + "commands that need a package are not offered.\n");
        }
        if (row.refuseReason != null) {
            out.append("Protected: ").append(row.refuseReason).append('\n');
        } else if (row.systemUser) {
            out.append("This is a system process, not an app process.\n");
        }
        return out.toString().trim();
    }

    /** Plain word for a ps state letter; unknown letters are
     *  named as-is, never mapped to a guessed meaning. */
    public static String stateWord(String state) {
        if (state == null || state.isEmpty()) return "unknown";
        switch (state.charAt(0)) {
            case 'R': return "Running";
            case 'S': return "Sleeping";
            case 'D': return "Waiting on disk";
            case 'T': return "Stopped";
            case 'Z': return "Zombie";
            case 'X': return "Dead";
            case 'I': return "Idle";
            default: return "State " + state;
        }
    }

    /** KB as "512 KB" / "2 MB" (nearest MB at/above 1 MB). */
    public static String humanKb(long kb) {
        if (kb < 1024) return kb + " KB";
        long mb = Math.round(kb / 1024.0);
        return mb + " MB";
    }

    // ---- v1.34: group classification for the filter control ----

    /** The two groups the filter control separates. A row is
     *  CONTROLLABLE exactly when its long-press menu offers
     *  Kill and Disable/Enable; everything else is SYSTEM. */
    public enum Group {
        CONTROLLABLE, SYSTEM
    }

    /**
     * The group a row belongs to, from the existing signals
     * only: CONTROLLABLE when the row has a known package
     * mapping AND is not refuse-listed AND is not system-marked
     * — the exact rows whose menu offers commands. Every other
     * row (system-marked, refuse-listed, or unmapped) is SYSTEM.
     * The filter never changes what a row can do, only which
     * rows are shown.
     */
    public static Group groupFor(Row row) {
        if (row != null && row.packageName != null
                && row.refuseReason == null && !row.systemUser) {
            return Group.CONTROLLABLE;
        }
        return Group.SYSTEM;
    }

    /** The rows of one group, in the order given (ps order).
     *  A null list yields an empty list, never null. */
    public static List<Row> inGroup(List<Row> rows, Group group) {
        List<Row> out = new ArrayList<>();
        if (rows == null) return out;
        for (Row row : rows) {
            if (groupFor(row) == group) out.add(row);
        }
        return out;
    }

    /** How many rows of a group a fetched set holds. */
    public static int countGroup(List<Row> rows, Group group) {
        return inGroup(rows, group).size();
    }

    /**
     * The section status line for a rendered list. It always
     * names the fetched total and both group sizes, so the
     * split is visible at a glance, and it names the cap
     * honestly: the cap bounds the rows RENDERED, and under a
     * filter it bounds the rendered rows of that group — never
     * implying the filter searched beyond the fetched set.
     * {@code selected} is null for All. {@code shown} is how
     * many rows are actually rendered.
     */
    public static String listStatus(List<Row> rows, Group selected, int shown) {
        int total = rows == null ? 0 : rows.size();
        int controllable = countGroup(rows, Group.CONTROLLABLE);
        int system = total - controllable;
        StringBuilder out = new StringBuilder();
        if (selected == null) {
            out.append(total).append(" processes — ")
                    .append(controllable).append(" can be controlled · ")
                    .append(system).append(" system");
            if (shown < total) {
                out.append(" — showing the first ").append(shown);
            }
        } else if (selected == Group.CONTROLLABLE) {
            out.append(controllable).append(" processes you can control · ")
                    .append(system).append(" system (").append(total).append(" loaded)");
            if (shown < controllable) {
                out.append(" — showing the first ").append(shown)
                        .append(" of this group");
            }
        } else {
            out.append(system).append(" system processes · ")
                    .append(controllable).append(" you can control (")
                    .append(total).append(" loaded)");
            if (shown < system) {
                out.append(" — showing the first ").append(shown)
                        .append(" of this group");
            }
        }
        return out.toString();
    }

    /** The honest empty state when a group holds no rows in
     *  the current fetch — never a stale list. */
    public static String emptyGroupText(Group selected) {
        if (selected == Group.CONTROLLABLE) {
            return "No processes you can control in this load — "
                    + "Refresh to load a fresh list.";
        }
        return "No system processes in this load — "
                + "Refresh to load a fresh list.";
    }

    // ---- v1.37: long-press menu decisions ----

    /** One long-press menu item, decided in pure Java so the
     *  harness pins the rules. */
    public static final class MenuItem {
        public final String label;
        public final boolean enabled;
        /** "details", "kill", "toggle", or "none" (an
         *  explanatory item that runs nothing). */
        public final String action;

        MenuItem(String label, boolean enabled, String action) {
            this.label = label;
            this.enabled = enabled;
            this.action = action;
        }
    }

    /**
     * The long-press menu for one process row, in order.
     * Details is always offered. Kill and Enable/Disable need
     * a known package mapping: without one the menu carries an
     * explicit disabled item saying why, instead of silently
     * omitting the actions. Enable/Disable is present on EVERY
     * row with a known package — including protected rows,
     * where the governed confirm names the coming refusal when
     * the override is off and the bridge refuses (the same
     * pattern Kill already uses for refused targets). Control
     * itself keeps its disabled explainer: it is refused
     * always, and the menu says so rather than offering a
     * command that can never run. {@code enabledState} is the
     * fresh enabled state read at menu-open time (null when
     * the package vanished since the list loaded — the label
     * defaults to Disable and the governed path refuses
     * honestly).
     */
    public static List<MenuItem> menuItems(Row row, Boolean enabledState) {
        List<MenuItem> out = new ArrayList<>();
        out.add(new MenuItem("Details", true, "details"));
        if (row == null || row.packageName == null) {
            out.add(new MenuItem(
                    "No package found for this process — nothing to disable",
                    false, "none"));
            return out;
        }
        if (ProtectedOverride.isOwnPackage(row.packageName)) {
            out.add(new MenuItem("Control itself can never be disabled",
                    false, "none"));
            return out;
        }
        out.add(new MenuItem("Kill", true, "kill"));
        boolean enabled = enabledState == null || enabledState;
        boolean protectedPkg = ProtectedOverride.isProtected(row.packageName);
        out.add(new MenuItem(ProtectedOverride.menuLabel(enabled, protectedPkg),
                true, "toggle"));
        return out;
    }
}
