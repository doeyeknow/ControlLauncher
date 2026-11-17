package app.control.launcher;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothServerSocket;
import android.bluetooth.BluetoothSocket;
import android.util.Log;

import org.json.JSONObject;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * Bluetooth Classic (RFCOMM) walkie-talkie transport — the last-resort
 * hop. One paired peer at a time. Voice travels as ADPCM frames inside
 * the same [type][len][payload] framing the mesh uses; S/V/E packets
 * mark spurt start, voice, and end.
 *
 * Honest limits: the peer must already be paired in system settings,
 * and Bluetooth range/throughput make this a bursty path — fine for
 * press-talk-release, not for an open microphone.
 */
public class BtWalkie {

    public interface Listener {
        void onState(String state);
        void onPeer(String name);
        /** meta is the bridging provenance (v1.24) when the
         *  spurt arrived relayed through a gateway, null for
         *  a direct spurt; mid is the spurt's sender-minted
         *  message id ("" when the sender predates it). */
        void onSpurtStart(String name, BridgeRule.Relayed meta, String mid);
        void onFrame(byte[] adpcmFrame);
        void onSpurtEnd();
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
    }

    static final UUID SERVICE_UUID =
            UUID.fromString("3f6a1c2e-7b4d-4e8f-9a1c-5d2e8b4f6a70");
    private static final String TAG = "BtWalkie";

    private final String myName;
    private final String channelId;
    private final Listener listener;
    private BluetoothServerSocket server;
    private BluetoothSocket socket;
    private DataOutputStream out;
    private volatile boolean running;
    private String peerName = "Bluetooth peer";

    public BtWalkie(String myName, String channelId, Listener listener) {
        this.myName = myName;
        this.channelId = channelId == null ? "" : channelId;
        this.listener = listener;
    }

    public boolean isConnected() {
        return socket != null && socket.isConnected();
    }

    public String peerName() {
        return peerName;
    }

    public void listen(BluetoothAdapter adapter) {
        running = true;
        new Thread(() -> {
            try {
                server = adapter.listenUsingRfcommWithServiceRecord("ControlWalkie", SERVICE_UUID);
                listener.onState("Bluetooth: listening — a paired phone can connect");
                while (running) {
                    BluetoothSocket s = server.accept();
                    if (s != null) attach(s);
                }
            } catch (IOException e) {
                if (running) listener.onState("Bluetooth listen failed: " + e.getMessage());
            }
        }, "bt-listen").start();
    }

    public void connect(BluetoothAdapter adapter, BluetoothDevice device) {
        running = true;
        new Thread(() -> {
            try {
                adapter.cancelDiscovery();
                BluetoothSocket s = device.createRfcommSocketToServiceRecord(SERVICE_UUID);
                s.connect();
                attach(s);
            } catch (IOException e) {
                listener.onState("Bluetooth connect failed: " + e.getMessage());
            }
        }, "bt-connect").start();
    }

    private void attach(BluetoothSocket s) {
        socket = s;
        peerName = safeName(s.getRemoteDevice());
        try {
            out = new DataOutputStream(s.getOutputStream());
            JSONObject hello = new JSONObject();
            hello.put("name", myName);
            hello.put("channel", channelId);
            send('H', hello.toString().getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            Log.w(TAG, "hello failed", e);
        }
        listener.onState("Bluetooth: connected");
        listener.onPeer(peerName);
        new Thread(() -> {
            try {
                DataInputStream in = new DataInputStream(s.getInputStream());
                while (running && socket != null && socket.isConnected()) {
                    int type = in.readByte();
                    int len = in.readUnsignedShort();
                    byte[] payload = new byte[len];
                    in.readFully(payload);
                    switch ((char) type) {
                        case 'H':
                            try {
                                peerName = new JSONObject(
                                        new String(payload, StandardCharsets.UTF_8))
                                        .optString("name", peerName);
                                listener.onPeer(peerName);
                            } catch (Exception ignored) {
                            }
                            break;
                        case 'S': {
                            // A spurt-start carries a JSON payload:
                            // a direct one names the spurt's
                            // message id (the talker is the peer);
                            // a bridged one names the original
                            // sender plus the bridging provenance.
                            // A pre-v1.24 sender sends an empty
                            // payload, and old builds ignore this
                            // packet's content either way.
                            String name = peerName;
                            BridgeRule.Relayed meta = null;
                            String mid = "";
                            if (payload.length > 0) {
                                try {
                                    JSONObject o = new JSONObject(
                                            new String(payload,
                                                    StandardCharsets.UTF_8));
                                    name = o.optString("name", peerName);
                                    meta = BridgeRule.relayedFromJson(o, "name");
                                    mid = o.optString("mid", "");
                                } catch (Exception ignored) {
                                }
                            }
                            listener.onSpurtStart(name, meta, mid);
                            break;
                        }
                        case 'V':
                            listener.onFrame(payload);
                            break;
                        case 'E':
                            listener.onSpurtEnd();
                            break;
                        case 'T': // legacy: plain or "wd"-marked
                        case 'D': // v1.32: coded text
                            try {
                                JSONObject o = new JSONObject(
                                        new String(payload, StandardCharsets.UTF_8));
                                String ch = o.optString("channel", "");
                                if (ch.isEmpty() || ch.equals(channelId)) {
                                    String teach = o.has("wdt")
                                            ? o.optString("wdt", "") : null;
                                    WordDict.Result decoded;
                                    String wire;
                                    boolean coded;
                                    if (type == 'D') {
                                        wire = o.optString("w", "");
                                        decoded = WordDict.decodeCoded(wire, teach);
                                        coded = true;
                                    } else {
                                        wire = o.optString("text", "");
                                        int wd = o.optInt("wd", 0);
                                        decoded = WordDict.decodeMarked(
                                                wire, wd, teach);
                                        coded = wd > 0;
                                    }
                                    DictStore.noteReceived(channelId, decoded);
                                    listener.onText(o.optString("from", peerName),
                                            decoded.text,
                                            o.optBoolean("ld", false),
                                            BridgeRule.relayedFromJson(o, "from"),
                                            o.optString("mid", ""), decoded.note,
                                            wire, coded);
                                }
                            } catch (Exception ignored) {
                            }
                            break;
                        default:
                            break;
                    }
                }
            } catch (IOException e) {
                // link dropped
            } finally {
                listener.onState("Bluetooth: disconnected");
            }
        }, "bt-reader").start();
    }

    private String safeName(BluetoothDevice d) {
        try {
            String n = d.getName();
            return n == null || n.isEmpty() ? "Bluetooth peer" : n;
        } catch (SecurityException e) {
            return "Bluetooth peer";
        }
    }

    /** Spurt-start carrying the spurt's message id, minted
     *  by the caller (see MeshNet.beginSpurt for why the id
     *  matters). An empty id is minted here, defensively. */
    public synchronized void sendSpurtStart(String mid) {
        try {
            JSONObject o = new JSONObject();
            o.put("mid", mid == null || mid.isEmpty()
                    ? java.util.UUID.randomUUID().toString() : mid);
            send('S', o.toString().getBytes(StandardCharsets.UTF_8));
        } catch (Exception ignored) {
        }
    }

    /** Spurt-start for a BRIDGED burst (v1.24): the payload
     *  names the ORIGINAL sender and carries the bridging
     *  provenance. Old receivers ignore the payload and show
     *  the peer as the talker. */
    public synchronized void sendSpurtStartAs(String name,
                                              BridgeRule.Relayed meta) {
        try {
            JSONObject o = new JSONObject();
            o.put("name", name);
            o.put("channel", channelId);
            BridgeRule.putRelayKeys(o, meta);
            send('S', o.toString().getBytes(StandardCharsets.UTF_8));
        } catch (Exception ignored) {
        }
    }

    public synchronized void sendFrame(byte[] adpcm) {
        send('V', adpcm);
    }

    public synchronized void sendSpurtEnd() {
        send('E', new byte[0]);
    }

    public synchronized SentText sendText(String text) {
        return sendText(text, false);
    }

    /** v1.11: low-data texts carry an "ld" flag, as on the mesh —
     *  old receivers ignore the unknown key. */
    /** Sends a text, returning what was actually put on the
     *  wire (v1.24/v1.32) — see MeshNet.sendText. */
    /** Puts the text into a 'D' packet, word-coded on the
     *  "w" key with no marker (v1.32) — the twin of
     *  MeshNet.putText, same rule on both roads. Returns
     *  the exact wire form sent. */
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

    public synchronized SentText sendText(String text, boolean lowData) {
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
            send('D', o.toString().getBytes(StandardCharsets.UTF_8));
        } catch (Exception ignored) {
        }
        return new SentText(mid, wire, text);
    }

    /** A BRIDGED text (v1.24): the packet names the ORIGINAL
     *  sender and carries the bridging provenance. */
    public synchronized void sendTextAs(String from, String text,
                                        boolean lowData,
                                        BridgeRule.Relayed meta) {
        try {
            JSONObject o = new JSONObject();
            o.put("from", from);
            putText(o, text, channelId);
            o.put("channel", channelId);
            if (lowData) o.put("ld", true);
            BridgeRule.putRelayKeys(o, meta);
            send('D', o.toString().getBytes(StandardCharsets.UTF_8));
        } catch (Exception ignored) {
        }
    }

    private void send(char type, byte[] payload) {
        if (out == null) return;
        try {
            out.writeByte(type);
            out.writeShort(payload.length);
            out.write(payload);
            out.flush();
        } catch (IOException e) {
            Log.w(TAG, "send failed", e);
        }
    }

    public void shutdown() {
        running = false;
        try {
            if (server != null) server.close();
        } catch (IOException ignored) {
        }
        try {
            if (socket != null) socket.close();
        } catch (IOException ignored) {
        }
        socket = null;
        out = null;
    }
}
