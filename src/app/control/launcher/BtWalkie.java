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
        void onSpurtStart(String name);
        void onFrame(byte[] adpcmFrame);
        void onSpurtEnd();
        void onText(String from, String text, boolean lowData);
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
                        case 'S':
                            listener.onSpurtStart(peerName);
                            break;
                        case 'V':
                            listener.onFrame(payload);
                            break;
                        case 'E':
                            listener.onSpurtEnd();
                            break;
                        case 'T':
                            try {
                                JSONObject o = new JSONObject(
                                        new String(payload, StandardCharsets.UTF_8));
                                String ch = o.optString("channel", "");
                                if (ch.isEmpty() || ch.equals(channelId)) {
                                    listener.onText(o.optString("from", peerName),
                                            o.optString("text", ""),
                                            o.optBoolean("ld", false));
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

    public synchronized void sendSpurtStart() {
        send('S', new byte[0]);
    }

    public synchronized void sendFrame(byte[] adpcm) {
        send('V', adpcm);
    }

    public synchronized void sendSpurtEnd() {
        send('E', new byte[0]);
    }

    public synchronized void sendText(String text) {
        sendText(text, false);
    }

    /** v1.11: low-data texts carry an "ld" flag, as on the mesh —
     *  old receivers ignore the unknown key. */
    public synchronized void sendText(String text, boolean lowData) {
        try {
            JSONObject o = new JSONObject();
            o.put("from", myName);
            o.put("text", text);
            o.put("channel", channelId);
            if (lowData) o.put("ld", true);
            send('T', o.toString().getBytes(StandardCharsets.UTF_8));
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
