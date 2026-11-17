package app.control.launcher;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * The ONVIF wire shapes the camera scan needs: the
 * WS-Discovery Probe, ProbeMatches parsing, and the two
 * media-service SOAP calls (GetSnapshotUri, GetStreamUri).
 *
 * Parsing is namespace-tolerant string scanning, not a DOM
 * parse: cameras disagree about namespace prefixes
 * (wsd, d, wsdp, …), and the fields needed here — XAddrs,
 * scopes, the media URI — are simple text elements. The
 * scanner matches on local names, ignores unknown content,
 * and returns empty results for malformed input instead of
 * throwing. Nothing here executes anything from a device's
 * reply: addresses are validated before use by the caller.
 *
 * Pure Java, no Android types, no sockets: the harness
 * proves the probe shape and the parsers against canned
 * device replies, including hostile ones.
 */
public final class CamOnvif {

    /** The WS-Discovery multicast group + port (standard). */
    public static final String MULTICAST_ADDRESS = "239.255.255.250";
    public static final int MULTICAST_PORT = 3702;

    /** One device from a ProbeMatches reply. */
    public static final class Device {
        /** Device service addresses (XAddrs), in reply order. */
        public final List<String> xaddrs = new ArrayList<>();
        /** Scope URIs, in reply order. */
        public final List<String> scopes = new ArrayList<>();
        /** Endpoint reference address, when present. */
        public String endpointRef = "";
    }

    private CamOnvif() {}

    /**
     * Builds a WS-Discovery Probe envelope. The MessageID
     * is a fresh UUID URN per probe (replies echo it in
     * RelatesTo; the scanner does not require the echo —
     * some cameras omit it — but a fresh id keeps probes
     * distinguishable). Types = the ONVIF device type, so
     * non-camera WS-Discovery speakers stay quiet.
     */
    public static String probeEnvelope() {
        return probeEnvelope("uuid:" + UUID.randomUUID().toString());
    }

    static String probeEnvelope(String messageId) {
        return "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
                + "<s:Envelope xmlns:s=\"http://www.w3.org/2003/05/soap-envelope\" "
                + "xmlns:a=\"http://schemas.xmlsoap.org/ws/2004/08/addressing\" "
                + "xmlns:d=\"http://schemas.xmlsoap.org/ws/2005/04/discovery\">"
                + "<s:Header>"
                + "<a:Action>http://schemas.xmlsoap.org/ws/2005/04/discovery/Probe</a:Action>"
                + "<a:MessageID>" + messageId + "</a:MessageID>"
                + "<a:To>urn:schemas-xmlsoap-org:ws:2005:04:discovery</a:To>"
                + "</s:Header><s:Body><d:Probe><d:Types "
                + "xmlns:dn=\"http://www.onvif.org/ver10/network/wsdl\">"
                + "dn:NetworkVideoTransmitter"
                + "</d:Types></d:Probe></s:Body></s:Envelope>";
    }

    /**
     * Parses ProbeMatches from a discovery reply. Returns
     * every ProbeMatch found (usually one per datagram).
     * A reply with no matches, or unparseable bytes,
     * yields an empty list — never an exception.
     */
    public static List<Device> parseProbeMatches(String xml) {
        List<Device> out = new ArrayList<>();
        if (xml == null) return out;
        int i = 0;
        while (true) {
            int start = findOpenTag(xml, "ProbeMatch", i);
            if (start < 0) break;
            int contentStart = xml.indexOf('>', start);
            if (contentStart < 0) break;
            contentStart++;
            int end = findCloseTag(xml, "ProbeMatch", contentStart);
            if (end < 0) break;
            String body = xml.substring(contentStart, end);
            Device d = new Device();
            String xaddrs = elementText(body, "XAddrs");
            if (xaddrs != null) {
                for (String part : xaddrs.trim().split("\\s+")) {
                    if (!part.isEmpty()) d.xaddrs.add(part);
                }
            }
            String scopes = elementText(body, "Scopes");
            if (scopes != null) {
                for (String part : scopes.trim().split("\\s+")) {
                    if (!part.isEmpty()) d.scopes.add(part);
                }
            }
            String epr = elementText(body, "Address");
            if (epr != null) d.endpointRef = epr.trim();
            out.add(d);
            i = end;
        }
        return out;
    }

    /**
     * The friendly name from ONVIF scopes: the
     * onvif://www.onvif.org/name/<name> scope, URI-decoded
     * (cameras percent-encode spaces). Returns "" when no
     * name scope is present.
     */
    public static String nameFromScopes(List<String> scopes) {
        if (scopes == null) return "";
        for (String s : scopes) {
            String key = "onvif://www.onvif.org/name/";
            int at = s.indexOf(key);
            if (at >= 0) {
                String raw = s.substring(at + key.length());
                return uriDecode(raw);
            }
        }
        return "";
    }

    /** The hardware/model scope value, or "" when absent. */
    public static String hardwareFromScopes(List<String> scopes) {
        if (scopes == null) return "";
        for (String s : scopes) {
            String key = "onvif://www.onvif.org/hardware/";
            int at = s.indexOf(key);
            if (at >= 0) return uriDecode(s.substring(at + key.length()));
        }
        return "";
    }

    private static String uriDecode(String s) {
        StringBuilder out = new StringBuilder();
        int i = 0;
        while (i < s.length()) {
            char c = s.charAt(i);
            if (c == '%' && i + 2 < s.length()) {
                try {
                    int v = Integer.parseInt(s.substring(i + 1, i + 3), 16);
                    out.append((char) v);
                    i += 3;
                    continue;
                } catch (NumberFormatException e) {
                    // fall through: keep the '%' literally
                }
            }
            out.append(c);
            i++;
        }
        return out.toString();
    }

    // ---- media SOAP ----

    /**
     * Builds a GetSnapshotUri request for a profile token.
     * The token is XML-escaped; an empty token requests the
     * default profile's snapshot URI (many cameras accept
     * an empty token, and the caller falls back to pattern
     * probes when the call fails).
     */
    public static String getSnapshotUriEnvelope(String profileToken) {
        return "<s:Envelope xmlns:s=\"http://www.w3.org/2003/05/soap-envelope\" "
                + "xmlns:trt=\"http://www.onvif.org/ver10/media/wsdl\">"
                + "<s:Body><trt:GetSnapshotUri><trt:ProfileToken>"
                + xmlEscape(profileToken == null ? "" : profileToken)
                + "</trt:ProfileToken></trt:GetSnapshotUri></s:Body></s:Envelope>";
    }

    /**
     * Builds a GetStreamUri request for an MJPEG (RTP-
     * unicast is requested; the returned URI's scheme
     * tells the caller what it actually is — an rtsp://
     * URI is NOT an MJPEG stream and the caller must not
     * treat it as one).
     */
    public static String getStreamUriEnvelope(String profileToken) {
        return "<s:Envelope xmlns:s=\"http://www.w3.org/2003/05/soap-envelope\" "
                + "xmlns:trt=\"http://www.onvif.org/ver10/media/wsdl\" "
                + "xmlns:tt=\"http://www.onvif.org/ver10/schema\">"
                + "<s:Body><trt:GetStreamUri><trt:StreamSetup>"
                + "<tt:Stream>RTP-Unicast</tt:Stream>"
                + "<tt:Transport><tt:Protocol>HTTP</tt:Protocol></tt:Transport>"
                + "</trt:StreamSetup><trt:ProfileToken>"
                + xmlEscape(profileToken == null ? "" : profileToken)
                + "</trt:ProfileToken></trt:GetStreamUri></s:Body></s:Envelope>";
    }

    /** Extracts the MediaUri Uri from a GetSnapshotUri /
     *  GetStreamUri reply, or "" when absent. A SOAP Fault
     *  yields "" — the caller falls back, it does not
     *  surface fault XML to the user. */
    public static String mediaUri(String soapReply) {
        if (soapReply == null) return "";
        String uri = elementText(soapReply, "Uri");
        return uri == null ? "" : uri.trim();
    }

    /** True when a SOAP reply is a Fault. */
    public static boolean isFault(String soapReply) {
        return soapReply != null && findOpenTag(soapReply, "Fault", 0) >= 0;
    }

    private static String xmlEscape(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;").replace("'", "&apos;");
    }

    // ---- namespace-tolerant element scanning ----

    /** Finds "<prefix:LocalName" or "<LocalName" at or after
     *  {@code from}, returning the index of '<', or -1. A
     *  following char must end the tag name (space, '>',
     *  '/', or end) so "XAddrs2" never matches "XAddrs". */
    static int findOpenTag(String xml, String localName, int from) {
        int i = from;
        while (true) {
            int lt = xml.indexOf('<', i);
            if (lt < 0) return -1;
            int j = lt + 1;
            if (j < xml.length() && xml.charAt(j) == '/') {
                i = j + 1;
                continue;
            }
            int nameEnd = j;
            while (nameEnd < xml.length()) {
                char c = xml.charAt(nameEnd);
                if (c == ' ' || c == '\t' || c == '\n' || c == '\r' || c == '>' || c == '/') break;
                nameEnd++;
            }
            String name = xml.substring(j, nameEnd);
            int colon = name.lastIndexOf(':');
            String local = colon >= 0 ? name.substring(colon + 1) : name;
            if (local.equals(localName)) return lt;
            i = nameEnd;
        }
    }

    /** Finds "</prefix:LocalName>" or "</LocalName>" at or
     *  after {@code from}, returning the index of '<'. */
    static int findCloseTag(String xml, String localName, int from) {
        int i = from;
        while (true) {
            int lt = xml.indexOf("</", i);
            if (lt < 0) return -1;
            int j = lt + 2;
            int nameEnd = j;
            while (nameEnd < xml.length()) {
                char c = xml.charAt(nameEnd);
                if (c == ' ' || c == '\t' || c == '\n' || c == '\r' || c == '>') break;
                nameEnd++;
            }
            String name = xml.substring(j, nameEnd);
            int colon = name.lastIndexOf(':');
            String local = colon >= 0 ? name.substring(colon + 1) : name;
            if (local.equals(localName)) return lt;
            i = nameEnd;
        }
    }

    /** The text content of the first element with this
     *  local name, XML-unescaped, or null when absent.
     *  Self-closing elements yield "". Nested elements
     *  inside are returned as raw text (callers only ask
     *  for leaf elements). */
    static String elementText(String xml, String localName) {
        int open = findOpenTag(xml, localName, 0);
        if (open < 0) return null;
        int gt = xml.indexOf('>', open);
        if (gt < 0) return null;
        if (gt > open && xml.charAt(gt - 1) == '/') return "";
        int close = findCloseTag(xml, localName, gt + 1);
        if (close < 0) return null;
        String raw = xml.substring(gt + 1, close);
        return raw.replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"")
                .replace("&apos;", "'").replace("&amp;", "&");
    }
}
