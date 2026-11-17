package app.control.launcher;

import android.util.Base64;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * The walkie-talkie group network over Wi-Fi Direct.
 *
 * Topology, honestly: a Wi-Fi Direct group is a star. One device is the
 * group owner (GO) and runs the relay socket; every member connects to
 * it; the GO forwards each talk spurt to everyone else. That gives
 * "everyone in range of the group hears everyone" — and it is also the
 * limit: a member out of the owner's radio range is out of the group.
 * Multi-hop relay beyond the owner is the documented next step, not
 * something this class pretends to do.
 *
 * Wire format: [type:1][length:2][payload]. Types: H hello, W welcome,
 * S spurt start, V voice chunk, E spurt end, T text, M member list,
 * X error. The GO arbitrates the floor: while one member talks, voice
 * from anyone else is dropped at the relay — real half-duplex.
 *
 * Keyed channels: voice payloads are AES-256-GCM encrypted with a key
 * derived (SHA-256) from a shared passphrase. The hello carries a key
 * check value so a wrong passphrase is refused at the door.
 */
public class MeshNet {

    public interface Listener {
        void onState(String state);
        void onMembers(List<String> names);
        /** null name = floor free. meta is the bridging
         *  provenance (v1.24) when the spurt arrived relayed
         *  through a gateway, null for a direct spurt; mid
         *  is the spurt's sender-minted message id ("" when
         *  the sender predates it). */
        void onTalker(String name, BridgeRule.Relayed meta, String mid);
        /** meta is the bridging provenance (v1.24) when the
         *  text arrived relayed through a gateway, null for
         *  a direct text; mid is the text's sender-minted
         *  message id ("" when the sender predates it).
         *  wordNote (v1.28) is non-null when the text arrived
         *  word-coded with a dictionary this build cannot
         *  decode: the text is then the raw coded payload,
         *  shown as sent, and the note says why. wire
         *  (v1.32) is the payload exactly as received and
         *  coded says whether it arrived coded — the
         *  long-press view shows the real wire form. */
        void onText(String from, String text, boolean lowData,
                    BridgeRule.Relayed meta, String mid, String wordNote,
                    String wire, boolean coded);
        void onVoice(byte[] pcm);         // a decoded chunk to play
        void onSpurtEnd();                // play the roger beep
        void onChannel(String channelId, String channelName); // from the host's welcome
    }

    private static final String TAG = "MeshNet";
    static final int PORT = 8989;

    private final Listener listener;
    private final String myName;
    private final byte[] key;             // null = open channel
    private final String channelId;       // the walkie-talkie channel this session serves
    private final String channelName;
    private volatile boolean running;

    // Host side
    private ServerSocket server;
    private final List<Conn> clients = new CopyOnWriteArrayList<>();
    private Conn selfAsClient;            // host also plays received audio via listener directly
    private String floorOwner;            // connection label holding the floor

    // Client side
    private Conn uplink;

    private int ivCounter;

    public MeshNet(String myName, String passphrase, String channelId,
                   String channelName, Listener listener) {
        this.myName = myName;
        this.listener = listener;
        this.key = (passphrase == null || passphrase.isEmpty())
                ? null : sha256(passphrase.getBytes(StandardCharsets.UTF_8));
        this.channelId = channelId == null ? "" : channelId;
        this.channelName = channelName == null ? "" : channelName;
    }

    public String channelId() {
        return channelId;
    }

    public boolean isKeyed() {
        return key != null;
    }

    private String keyCheck() {
        if (key == null) return "";
        byte[] material = new byte[key.length + 12];
        System.arraycopy(key, 0, material, 0, key.length);
        System.arraycopy("control-mesh".getBytes(StandardCharsets.UTF_8), 0,
                material, key.length, 12);
        return Base64.encodeToString(sha256(material), Base64.NO_WRAP);
    }

    // ---- host ----

    public void startHost() {
        running = true;
        new Thread(() -> {
            try {
                server = new ServerSocket(PORT);
                listener.onState("Hosting — waiting for people to join");
                while (running) {
                    Socket s = server.accept();
                    Conn conn = new Conn(s, true);
                    clients.add(conn);
                    conn.startReader();
                }
            } catch (IOException e) {
                if (running) listener.onState("Hosting failed: " + e.getMessage());
            }
        }, "mesh-server").start();
    }

    // ---- client ----

    public void connectToHost(InetAddress hostAddress) {
        running = true;
        new Thread(() -> {
            Exception last = null;
            for (int attempt = 0; attempt < 12 && running; attempt++) {
                try {
                    Socket s = new Socket(hostAddress, PORT);
                    uplink = new Conn(s, false);
                    uplink.sendHello();
                    uplink.startReader();
                    listener.onState("Connected");
                    return;
                } catch (Exception e) {
                    last = e;
                    try {
                        Thread.sleep(500);
                    } catch (InterruptedException ignored) {
                    }
                }
            }
            listener.onState("Could not reach the channel host"
                    + (last != null ? ": " + last.getMessage() : ""));
        }, "mesh-connect").start();
    }

    // ---- talking ----

    /** Sends spurt-start, then frames via sendVoice, then spurt-end.
     *  mid is the spurt's message id, minted by the caller:
     *  it is the origin identity gateways derive the bridge
     *  id from (v1.24), and the caller's own relay post uses
     *  the same id, so a gatewayed copy dedups against the
     *  originator's own post instead of doubling it. An empty
     *  mid is minted here, defensively — the packet always
     *  carries one. */
    public void beginSpurt(String mid) {
        broadcastOrSend(new Packet('S', jsonName(mid)));
    }

    /** Spurt-start for a BRIDGED burst (v1.24): the packet
     *  names the ORIGINAL sender and carries the bridging
     *  provenance, so receivers show who really spoke and
     *  the loop rules can follow the message. Old receivers
     *  read only the name and ignore the extra keys. */
    public void beginSpurtAs(String name, BridgeRule.Relayed meta) {
        try {
            JSONObject o = new JSONObject();
            o.put("name", name);
            o.put("channel", channelId);
            BridgeRule.putRelayKeys(o, meta);
            broadcastOrSend(new Packet('S',
                    o.toString().getBytes(StandardCharsets.UTF_8)));
        } catch (Exception ignored) {
        }
    }

    public void sendVoice(byte[] pcm) {
        byte[] payload = pcm;
        if (key != null) {
            payload = encrypt(pcm);
            if (payload == null) return;
        }
        broadcastOrSend(new Packet('V', payload));
    }

    public void endSpurt() {
        broadcastOrSend(new Packet('E', new byte[0]));
    }

    public SentText sendText(String text) {
        return sendText(text, false);
    }

    /** v1.11: low-data texts carry an "ld" flag in the text
     *  packet so the receiver knows to read them aloud. Old
     *  receivers ignore the unknown key and show the text
     *  as usual; the flag is omitted for ordinary texts.
     *  The host relays text packets verbatim, so the flag
     *  rides along untouched. */
    /** Sends a text, returning what was actually put on the
     *  wire (v1.24/v1.32): the message id minted for it —
     *  the caller stamps the matching thread entry with the
     *  derived bridge id, so a gatewayed copy of its own
     *  text dedups instead of landing twice — plus the
     *  exact coded wire form, so the entry records the real
     *  coded form for the long-press view. */
    /** Puts the text into a 'D' packet, word-coded on the
     *  "w" key with no marker (v1.32: every message coded,
     *  the packet type is the self-identifying shape). The
     *  returned wire form is the exact payload sent. */
    static String putText(JSONObject o, String text) throws Exception {
        return putText(o, text, null);
    }

    /** Scope-aware (v1.29): the teach block rides as "wdt"
     *  when the scope has words to learn. A null scope
     *  means no teaching (base codes only). */
    static String putText(JSONObject o, String text, String scope) throws Exception {
        WordDict.Encoded e = DictStore.encodeForScope(scope, text);
        String wire = e == null
                ? WordDict.escapeOnly(text == null ? "" : text) : e.wire;
        o.put("w", wire);
        if (e != null && e.teach != null && !e.teach.isEmpty()) {
            o.put("wdt", e.teach);
        }
        return wire;
    }

    public SentText sendText(String text, boolean lowData) {
        String mid = java.util.UUID.randomUUID().toString();
        String wire = "";
        try {
            JSONObject o = new JSONObject();
            o.put("from", myName);
            wire = putText(o, text, channelId);
            o.put("channel", channelId);
            // v1.24: the message id is the origin identity
            // gateways derive the bridge id from. Old
            // receivers ignore the unknown key.
            o.put("mid", mid);
            if (lowData) o.put("ld", true);
            broadcastOrSend(new Packet('D', o.toString().getBytes(StandardCharsets.UTF_8)));
        } catch (Exception ignored) {
        }
        return new SentText(mid, wire, text);
    }

    /** A BRIDGED text (v1.24): the packet names the ORIGINAL
     *  sender and carries the bridging provenance, so
     *  receivers show who really wrote it and the loop rules
     *  can follow the message. The host relays 'D' packets
     *  verbatim, so the provenance rides along untouched. */
    public void sendTextAs(String from, String text, boolean lowData,
                           BridgeRule.Relayed meta) {
        try {
            JSONObject o = new JSONObject();
            o.put("from", from);
            putText(o, text, channelId);
            o.put("channel", channelId);
            if (lowData) o.put("ld", true);
            BridgeRule.putRelayKeys(o, meta);
            broadcastOrSend(new Packet('D', o.toString().getBytes(StandardCharsets.UTF_8)));
        } catch (Exception ignored) {
        }
    }

    private byte[] jsonName(String mid) {
        try {
            JSONObject o = new JSONObject();
            o.put("name", myName);
            o.put("channel", channelId);
            o.put("mid", mid == null || mid.isEmpty()
                    ? java.util.UUID.randomUUID().toString() : mid);
            return o.toString().getBytes(StandardCharsets.UTF_8);
        } catch (Exception e) {
            return myName.getBytes(StandardCharsets.UTF_8);
        }
    }

    /** Host: relay to members (with floor rules). Client: send to host. */
    private void broadcastOrSend(Packet p) {
        if (uplink != null) {
            uplink.send(p);
        } else {
            // I am the host (or standalone). My own spurt claims the floor.
            synchronized (this) {
                if (p.type == 'S') floorOwner = "self";
                if (p.type == 'E') floorOwner = null;
            }
            for (Conn c : clients) c.send(p);
            if (p.type == 'S') listener.onTalker(myName, null, "");
            if (p.type == 'E') listener.onTalker(null, null, "");
        }
    }

    public synchronized boolean floorFree() {
        return floorOwner == null;
    }

    public void shutdown() {
        running = false;
        try {
            if (server != null) server.close();
        } catch (IOException ignored) {
        }
        for (Conn c : clients) c.close();
        clients.clear();
        if (uplink != null) uplink.close();
        uplink = null;
    }

    // ---- connection handling ----

    private class Conn {
        final Socket socket;
        final boolean inbound;      // true on the host side
        final DataOutputStream out;
        String name = "Someone";
        boolean greeted;

        Conn(Socket s, boolean inbound) throws IOException {
            this.socket = s;
            this.inbound = inbound;
            this.out = new DataOutputStream(s.getOutputStream());
        }

        void sendHello() {
            try {
                JSONObject o = new JSONObject();
                o.put("name", myName);
                o.put("keycheck", keyCheck());
                send(new Packet('H', o.toString().getBytes(StandardCharsets.UTF_8)));
            } catch (Exception ignored) {
            }
        }

        synchronized void send(Packet p) {
            try {
                out.writeByte(p.type);
                out.writeShort(p.payload.length);
                out.write(p.payload);
                out.flush();
            } catch (IOException e) {
                close();
            }
        }

        void startReader() {
            new Thread(() -> {
                try {
                    DataInputStream in = new DataInputStream(socket.getInputStream());
                    while (running) {
                        int type = in.readByte();
                        int len = in.readUnsignedShort();
                        byte[] payload = new byte[len];
                        in.readFully(payload);
                        handle(new Packet((char) type, payload), this);
                    }
                } catch (IOException e) {
                    // dropped
                } finally {
                    close();
                    if (inbound) {
                        clients.remove(this);
                        broadcastMembers();
                        synchronized (MeshNet.this) {
                            if (name.equals(floorOwner)) floorOwner = null;
                        }
                    }
                }
            }, "mesh-conn").start();
        }

        void close() {
            try {
                socket.close();
            } catch (IOException ignored) {
            }
        }
    }

    private void handle(Packet p, Conn from) {
        boolean iAmHost = uplink == null;
        switch (p.type) {
            case 'H': {
                if (!iAmHost || !from.inbound) return;
                try {
                    JSONObject o = new JSONObject(new String(p.payload, StandardCharsets.UTF_8));
                    String theirCheck = o.optString("keycheck", "");
                    if (key != null && !keyCheck().equals(theirCheck)) {
                        from.send(new Packet('X',
                                "Wrong channel key".getBytes(StandardCharsets.UTF_8)));
                        from.close();
                        return;
                    }
                    if (key == null && !theirCheck.isEmpty()) {
                        from.send(new Packet('X',
                                "This channel is open; your key doesn't apply"
                                        .getBytes(StandardCharsets.UTF_8)));
                        from.close();
                        return;
                    }
                    from.name = o.optString("name", "Someone");
                    from.greeted = true;
                    // Welcome: tell the joiner which channel this group serves.
                    try {
                        JSONObject w = new JSONObject();
                        w.put("channelId", channelId);
                        w.put("channelName", channelName);
                        w.put("keyed", key != null);
                        from.send(new Packet('W',
                                w.toString().getBytes(StandardCharsets.UTF_8)));
                    } catch (Exception ignored) {
                    }
                    broadcastMembers();
                } catch (Exception ignored) {
                }
                break;
            }
            case 'S': {
                if (iAmHost) {
                    synchronized (this) {
                        if (floorOwner == null) {
                            floorOwner = from.name;
                            relay(p, from);
                            for (Conn c : clients) {
                                if (c != from) { /* S also relayed below */ }
                            }
                            listener.onTalker(from.name, spurtMeta(p), spurtMid(p));
                        }
                        // floor busy: swallow their spurt entirely
                    }
                } else {
                    try {
                        JSONObject o = new JSONObject(new String(p.payload, StandardCharsets.UTF_8));
                        listener.onTalker(o.optString("name", "Someone"),
                                BridgeRule.relayedFromJson(o, "name"),
                                o.optString("mid", ""));
                    } catch (Exception e) {
                        listener.onTalker("Someone", null, "");
                    }
                }
                break;
            }
            case 'V': {
                if (iAmHost) {
                    synchronized (this) {
                        if (!from.name.equals(floorOwner)) return; // off-floor: drop
                    }
                    relay(p, from);
                }
                byte[] pcm = p.payload;
                if (key != null) {
                    pcm = decrypt(pcm);
                    if (pcm == null) return;
                }
                listener.onVoice(pcm);
                break;
            }
            case 'E': {
                if (iAmHost) {
                    synchronized (this) {
                        if (from.name.equals(floorOwner)) floorOwner = null;
                        else return;
                    }
                    relay(p, from);
                }
                listener.onTalker(null, null, "");
                listener.onSpurtEnd();
                break;
            }
            case 'W': {
                try {
                    JSONObject o = new JSONObject(new String(p.payload, StandardCharsets.UTF_8));
                    listener.onChannel(o.optString("channelId", ""),
                            o.optString("channelName", ""));
                } catch (Exception ignored) {
                }
                break;
            }
            case 'T': // legacy: plain or "wd"-marked text
                handleText(p, from, iAmHost, false);
                break;
            case 'D': // v1.32: coded text, self-identifying
                handleText(p, from, iAmHost, true);
                break;
            case 'M': {
                try {
                    JSONObject o = new JSONObject(new String(p.payload, StandardCharsets.UTF_8));
                    JSONArray arr = o.optJSONArray("members");
                    List<String> names = new ArrayList<>();
                    if (arr != null) {
                        for (int i = 0; i < arr.length(); i++) names.add(arr.optString(i));
                    }
                    listener.onMembers(names);
                } catch (Exception ignored) {
                }
                break;
            }
            case 'X': {
                listener.onState("Host refused: "
                        + new String(p.payload, StandardCharsets.UTF_8));
                break;
            }
            default:
                break;
        }
    }

    /** A text packet: 'T' is the legacy shape (plain text
     *  or a "wd"-marked coded payload on the "text" key);
     *  'D' is the v1.32 shape (the coded payload on the "w"
     *  key, strict-decoded). The host relays either
     *  verbatim before decoding, so bridged provenance
     *  rides along untouched. */
    private void handleText(Packet p, Conn from, boolean iAmHost, boolean codedShape) {
        if (iAmHost) relay(p, from);
        try {
            JSONObject o = new JSONObject(new String(p.payload, StandardCharsets.UTF_8));
            String ch = o.optString("channel", "");
            if (!ch.isEmpty() && !ch.equals(channelId)) return; // another channel's traffic
            String teach = o.has("wdt") ? o.optString("wdt", "") : null;
            WordDict.Result decoded;
            String wire;
            boolean coded;
            if (codedShape) {
                wire = o.optString("w", "");
                decoded = WordDict.decodeCoded(wire, teach);
                coded = true;
            } else {
                wire = o.optString("text", "");
                int wd = o.optInt("wd", 0);
                decoded = WordDict.decodeMarked(wire, wd, teach);
                coded = wd > 0;
            }
            DictStore.noteReceived(channelId, decoded);
            listener.onText(o.optString("from", "Someone"),
                    decoded.text, o.optBoolean("ld", false),
                    BridgeRule.relayedFromJson(o, "from"),
                    o.optString("mid", ""), decoded.note, wire, coded);
        } catch (Exception ignored) {
        }
    }

    /** Bridging provenance from a spurt-start packet, or null
     *  for a direct spurt or an unparsable packet. The host
     *  side uses this: it names the talker from the
     *  connection, but the packet carries the provenance. */
    private BridgeRule.Relayed spurtMeta(Packet p) {
        try {
            JSONObject o = new JSONObject(new String(p.payload, StandardCharsets.UTF_8));
            return BridgeRule.relayedFromJson(o, "name");
        } catch (Exception ignored) {
            return null;
        }
    }

    /** The spurt message id from a spurt-start packet, or ""
     *  for a pre-v1.24 sender or an unparsable packet. */
    private String spurtMid(Packet p) {
        try {
            JSONObject o = new JSONObject(new String(p.payload, StandardCharsets.UTF_8));
            return o.optString("mid", "");
        } catch (Exception ignored) {
            return "";
        }
    }

    private void relay(Packet p, Conn except) {
        for (Conn c : clients) {
            if (c != except && c.greeted) c.send(p);
        }
    }

    private void broadcastMembers() {
        List<String> names = new ArrayList<>();
        names.add(myName);
        for (Conn c : clients) if (c.greeted) names.add(c.name);
        listener.onMembers(names);
        try {
            JSONObject o = new JSONObject();
            JSONArray arr = new JSONArray();
            for (String n : names) arr.put(n);
            o.put("members", arr);
            Packet p = new Packet('M', o.toString().getBytes(StandardCharsets.UTF_8));
            for (Conn c : clients) if (c.greeted) c.send(p);
        } catch (Exception ignored) {
        }
    }

    // ---- crypto ----

    private byte[] encrypt(byte[] pcm) {
        try {
            byte[] iv = new byte[12];
            int c = ++ivCounter;
            iv[8] = (byte) (c >> 24);
            iv[9] = (byte) (c >> 16);
            iv[10] = (byte) (c >> 8);
            iv[11] = (byte) c;
            // Mix in a per-session random base so IVs never repeat after restart.
            System.arraycopy(sessionSalt, 0, iv, 0, 8);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"),
                    new GCMParameterSpec(128, iv));
            byte[] ct = cipher.doFinal(pcm);
            byte[] out = new byte[12 + ct.length];
            System.arraycopy(iv, 0, out, 0, 12);
            System.arraycopy(ct, 0, out, 12, ct.length);
            return out;
        } catch (Exception e) {
            return null;
        }
    }

    private byte[] decrypt(byte[] data) {
        try {
            if (data.length < 13) return null;
            byte[] iv = new byte[12];
            System.arraycopy(data, 0, iv, 0, 12);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"),
                    new GCMParameterSpec(128, iv));
            return cipher.doFinal(data, 12, data.length - 12);
        } catch (Exception e) {
            return null;
        }
    }

    private final byte[] sessionSalt = makeSalt();

    private static byte[] makeSalt() {
        byte[] s = new byte[8];
        new java.security.SecureRandom().nextBytes(s);
        return s;
    }

    private static byte[] sha256(byte[] data) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(data);
        } catch (Exception e) {
            return new byte[32];
        }
    }

    private static final class Packet {
        final char type;
        final byte[] payload;

        Packet(char type, byte[] payload) {
            this.type = type;
            this.payload = payload;
        }
    }
}
