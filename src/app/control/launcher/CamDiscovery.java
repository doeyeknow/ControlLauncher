package app.control.launcher;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

/**
 * Discovery-result shaping (pure Java): what a found device
 * looks like, how results from the three scan layers merge,
 * and how a device's vendor is guessed from what it
 * volunteered.
 *
 * Merge rule: one record per IP address. The richest name
 * wins (an ONVIF scope name beats an mDNS service name,
 * which beats a hostname, which beats the bare IP); ports
 * union; the vendor guess prefers ONVIF hardware scopes,
 * then mDNS service hints, then Server-header fingerprints;
 * the source set unions, so the UI can say honestly how a
 * device was found. A record is CONFIRMED when ONVIF or
 * mDNS named it as a camera; a sweep-only record is a
 * CANDIDATE — the UI labels it "possible camera", never a
 * certainty, because a web server on port 80 is not proof
 * of a camera.
 */
public final class CamDiscovery {

    /** How a device was found. */
    public static final int SRC_ONVIF = 1;
    public static final int SRC_MDNS = 2;
    public static final int SRC_SWEEP = 4;

    /** A found device, merged across layers. */
    public static final class Found {
        public final String ip;
        public String name = "";
        public String vendor = "";
        public final TreeSet<Integer> ports = new TreeSet<>();
        public int sources;
        /** ONVIF device-service addresses, when known. */
        public final List<String> xaddrs = new ArrayList<>();
        /** mDNS service types seen, when known. */
        public final List<String> mdnsServices = new ArrayList<>();

        public Found(String ip) {
            this.ip = ip;
        }

        /** True when ONVIF or mDNS identified this device
         *  as a camera; false for sweep-only candidates. */
        public boolean confirmed() {
            return (sources & (SRC_ONVIF | SRC_MDNS)) != 0;
        }

        /** Display name: the stored name, else the IP. */
        public String displayName() {
            return name.isEmpty() ? ip : name;
        }
    }

    private CamDiscovery() {}

    /**
     * Merges one layer's result into the map keyed by IP.
     * {@code nameRank} orders name quality (higher wins):
     * ONVIF scope name 3, mDNS name 2, hostname 1.
     */
    public static Found merge(Map<String, Found> byIp, String ip, int source,
                              String name, int nameRank, String vendor,
                              List<Integer> ports, List<String> xaddrs,
                              List<String> mdnsServices) {
        Found f = byIp.get(ip);
        if (f == null) {
            f = new Found(ip);
            byIp.put(ip, f);
        }
        f.sources |= source;
        if (name != null && !name.isEmpty()) {
            int existing = nameRankOf(f);
            if (nameRank >= existing) f.name = name;
        }
        if (vendor != null && !vendor.isEmpty() && f.vendor.isEmpty()) {
            f.vendor = vendor;
        }
        if (ports != null) f.ports.addAll(ports);
        if (xaddrs != null) {
            for (String x : xaddrs) {
                if (!f.xaddrs.contains(x)) f.xaddrs.add(x);
            }
        }
        if (mdnsServices != null) {
            for (String s : mdnsServices) {
                if (!f.mdnsServices.contains(s)) f.mdnsServices.add(s);
            }
        }
        return f;
    }

    /** The rank of the name a record currently carries,
     *  inferred from its sources (a record named by ONVIF
     *  outranks one named by mDNS). Approximation, kept
     *  simple: ONVIF-named records are never renamed by a
     *  lower layer, because merge() is called with the
     *  layer's rank and ONVIF's rank is highest. */
    private static int nameRankOf(Found f) {
        if (f.name.isEmpty()) return 0;
        if ((f.sources & SRC_ONVIF) != 0) return 3;
        if ((f.sources & SRC_MDNS) != 0) return 2;
        return 1;
    }

    /** Merged results in first-seen order. */
    public static List<Found> ordered(Map<String, Found> byIp) {
        return new ArrayList<>(byIp.values());
    }

    public static Map<String, Found> newMap() {
        return new LinkedHashMap<>();
    }

    // ---- vendor guessing ----

    /**
     * Guesses a vendor from volunteered strings: ONVIF
     * hardware scope, mDNS service/instance names, or an
     * HTTP Server header. Returns "" when nothing matches.
     * The guess only pre-fills the vendor label and orders
     * the snapshot-pattern probes — it is never presented
     * as a certainty and never gates anything.
     */
    public static String guessVendor(String... hints) {
        if (hints == null) return "";
        String joined = "";
        for (String h : hints) {
            if (h != null) joined += " " + h.toLowerCase();
        }
        if (joined.contains("hikvision") || joined.contains("hik-")) return "Hikvision";
        if (joined.contains("dahua")) return "Dahua";
        if (joined.contains("reolink")) return "Reolink";
        if (joined.contains("amcrest")) return "Amcrest";
        if (joined.contains("axis")) return "Axis";
        if (joined.contains("unifi") || joined.contains("ubiquiti")) return "UniFi";
        if (joined.contains("foscam")) return "Foscam";
        if (joined.contains("tp-link") || joined.contains("tplink") || joined.contains("vigi")) return "TP-Link";
        if (joined.contains("hanwha") || joined.contains("wisenet") || joined.contains("samsung")) return "Hanwha";
        if (joined.contains("lorex")) return "Lorex";
        if (joined.contains("swann")) return "Swann";
        if (joined.contains("zosi")) return "Zosi";
        if (joined.contains("annke")) return "Annke";
        if (joined.contains("wyze")) return "Wyze";
        if (joined.contains("ring")) return "Ring";
        if (joined.contains("nest")) return "Nest";
        if (joined.contains("eufy")) return "Eufy";
        if (joined.contains("blink")) return "Blink";
        return "";
    }

    /**
     * Whether a sweep probe's answer looks camera-like:
     * an RTSP port open, or a Server header from the
     * fingerprint list. Generic web servers (Apache,
     * nginx, lighttpd without other signals) do NOT
     * qualify — the sweep must not fill the results with
     * routers, printers, and NAS boxes.
     */
    public static boolean sweepLooksLikeCamera(boolean rtspOpen, String serverHeader,
                                               String bodyHint) {
        if (rtspOpen) return true;
        String s = ((serverHeader == null ? "" : serverHeader)
                + " " + (bodyHint == null ? "" : bodyHint)).toLowerCase();
        if (s.contains("hikvision") || s.contains("dahua") || s.contains("reolink")
                || s.contains("amcrest") || s.contains("axis") || s.contains("foscam")
                || s.contains("wisenet") || s.contains("lorex") || s.contains("swann")
                || s.contains("zosi") || s.contains("annke") || s.contains("vigi")
                || s.contains("ipcam") || s.contains("ip camera")
                || s.contains("network camera") || s.contains("webcam")) {
            return true;
        }
        // GoAhead and Boa are the embedded servers most
        // cameras ship; on their own they are weak evidence,
        // so they qualify only with a camera-ish body hint.
        if ((s.contains("goahead") || s.contains("boa/")) && bodyHint != null
                && (bodyHint.toLowerCase().contains("camera")
                || bodyHint.toLowerCase().contains("snapshot")
                || bodyHint.toLowerCase().contains("live")
                || bodyHint.toLowerCase().contains("video"))) {
            return true;
        }
        return false;
    }

    /** mDNS service types the scan listens for. Plain
     *  _http._tcp is included because some cameras
     *  advertise nothing else — but it alone never marks
     *  a device as a camera (see mdnsMarksCamera). */
    public static final String[] MDNS_SERVICES = {
            "_http._tcp",
            "_rtsp._tcp",
            "_onvif._tcp",
            "_axis-video._tcp",
            "_camera._tcp",
    };

    /** True when an mDNS service type marks the device as
     *  camera-like (plain _http._tcp alone does not — every
     *  printer and router advertises it). */
    public static boolean mdnsMarksCamera(String serviceType) {
        if (serviceType == null) return false;
        String t = serviceType.toLowerCase();
        return t.startsWith("_rtsp.") || t.startsWith("_onvif.")
                || t.startsWith("_axis-video.") || t.startsWith("_camera.");
    }

    /** True when an mDNS instance name looks camera-like
     *  (a vendor name or "cam"/"camera" in the name) — the
     *  rule that lets a plain _http._tcp advertisement
     *  count as a camera sighting. */
    public static boolean mdnsInstanceLooksCamera(String instanceName) {
        if (instanceName == null) return false;
        String n = instanceName.toLowerCase();
        if (!guessVendor(instanceName).isEmpty()) return true;
        return n.contains("cam") || n.contains("ipcam");
    }

    /**
     * The host addresses of a /24, given one address in
     * it: .1–.254, skipping the network (.0), broadcast
     * (.255), and the given address itself. Returns an
     * empty list for anything that is not an IPv4
     * dotted-quad. The sweep probes these, and only
     * these — never beyond the current subnet.
     */
    public static List<String> hosts24(String ip) {
        List<String> out = new ArrayList<>();
        if (ip == null) return out;
        String[] parts = ip.split("\\.");
        if (parts.length != 4) return out;
        int[] octets = new int[4];
        for (int i = 0; i < 4; i++) {
            try {
                octets[i] = Integer.parseInt(parts[i]);
            } catch (NumberFormatException e) {
                return new ArrayList<>();
            }
            if (octets[i] < 0 || octets[i] > 255) return new ArrayList<>();
        }
        String prefix = octets[0] + "." + octets[1] + "." + octets[2] + ".";
        for (int last = 1; last <= 254; last++) {
            if (last == octets[3]) continue;
            out.add(prefix + last);
        }
        return out;
    }
}
