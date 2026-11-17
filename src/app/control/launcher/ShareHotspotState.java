package app.control.launcher;

import java.util.List;

/**
 * The hotspot share's lifecycle (v1.25), as pure Java the
 * harness can prove: a four-state machine plus the rule that
 * picks the hotspot interface's address from the enumerated
 * interfaces.
 *
 * States: IDLE (nothing running) -> STARTING (the system was
 * asked) -> RUNNING (hotspot up, server bound) -> STOPPING (a
 * stop was requested) -> IDLE. Every transition is guarded:
 * a second start while one is in flight is refused, a stop
 * when nothing runs is a no-op, and a system-initiated stop
 * (the `onStopped` callback) lands in IDLE from any live
 * state. The activity drives the real hotspot and server;
 * this class only decides which transitions are legal, so a
 * stray callback can never leave the screen believing a dead
 * hotspot is still sharing.
 *
 * Address selection: Android exposes no API for the
 * local-only hotspot's gateway address, so the activity
 * enumerates the network interfaces and this rule picks one:
 * the first up interface whose name starts with `ap` or
 * `softap`, then `wlan`, then `p2p`, then any up interface —
 * in each case the first site-local IPv4 address (loopback
 * and link-local never count). The preference order is a
 * heuristic, stated honestly in BUILD_NOTES; the on-device
 * test decides whether the A16's hotspot interface matches
 * it.
 */
public final class ShareHotspotState {

    public enum State { IDLE, STARTING, RUNNING, STOPPING }

    /** One enumerated interface, as the Android side adapts it. */
    public static final class Iface {
        public final String name;
        public final boolean up;
        public final List<String> ipv4Addresses;

        public Iface(String name, boolean up, List<String> ipv4Addresses) {
            this.name = name;
            this.up = up;
            this.ipv4Addresses = ipv4Addresses;
        }
    }

    private State state = State.IDLE;

    public State state() {
        return state;
    }

    /** IDLE -> STARTING. False (no change) from any other state. */
    public boolean requestStart() {
        if (state != State.IDLE) return false;
        state = State.STARTING;
        return true;
    }

    /** STARTING -> RUNNING. False (no change) from any other state. */
    public boolean onStarted() {
        if (state != State.STARTING) return false;
        state = State.RUNNING;
        return true;
    }

    /** STARTING -> IDLE (the system refused the hotspot). */
    public boolean onFailed() {
        if (state != State.STARTING) return false;
        state = State.IDLE;
        return true;
    }

    /** STARTING/RUNNING -> STOPPING. False (no change) otherwise. */
    public boolean requestStop() {
        if (state != State.STARTING && state != State.RUNNING) return false;
        state = State.STOPPING;
        return true;
    }

    /** Any live state -> IDLE (the hotspot is gone, by request
     *  or by the system). False (no change) from IDLE. */
    public boolean onStopped() {
        if (state == State.IDLE) return false;
        state = State.IDLE;
        return true;
    }

    /** Picks the hotspot gateway address from enumerated
     *  interfaces, or null when none qualifies. */
    public static String pickAddress(List<Iface> ifaces) {
        if (ifaces == null) return null;
        String[] prefixes = {"ap", "softap", "wlan", "p2p"};
        for (String prefix : prefixes) {
            String found = firstSiteLocal(ifaces, prefix);
            if (found != null) return found;
        }
        return firstSiteLocal(ifaces, null);
    }

    private static String firstSiteLocal(List<Iface> ifaces, String prefix) {
        for (Iface i : ifaces) {
            if (i == null || !i.up) continue;
            if (i.name == null) continue;
            if (prefix != null && !i.name.startsWith(prefix)) continue;
            if (i.ipv4Addresses == null) continue;
            for (String addr : i.ipv4Addresses) {
                if (isSiteLocal(addr)) return addr;
            }
        }
        return null;
    }

    /** 10/8, 172.16/12, or 192.168/16 — never loopback (127/8)
     *  or link-local (169.254/16). */
    static boolean isSiteLocal(String addr) {
        if (addr == null) return false;
        String[] parts = addr.split("\\.", -1);
        if (parts.length != 4) return false;
        int[] o = new int[4];
        for (int i = 0; i < 4; i++) {
            if (parts[i].isEmpty() || parts[i].length() > 3) return false;
            int v = 0;
            for (int j = 0; j < parts[i].length(); j++) {
                char c = parts[i].charAt(j);
                if (c < '0' || c > '9') return false;
                v = v * 10 + (c - '0');
            }
            if (v > 255) return false;
            o[i] = v;
        }
        if (o[0] == 10) return true;
        if (o[0] == 172 && o[1] >= 16 && o[1] <= 31) return true;
        if (o[0] == 192 && o[1] == 168) return true;
        return false;
    }
}
