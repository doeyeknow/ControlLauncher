package app.control.launcher;

import org.json.JSONObject;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;

/**
 * Gateway advertisements for mesh internet (v1.10), modeled on
 * LanDiscovery: a phone sharing its connection broadcasts a UDP
 * announcement every 2 seconds; an offline phone listens and
 * learns the gateway's address, name, and identity public key.
 *
 * The announcement carries the gateway's Ed25519 public key so
 * the client's auth signature can be bound to THAT gateway
 * (see GatewayAuth). It carries no location, no traffic, and
 * nothing identifying the phone beyond its mesh display name.
 */
public class GatewayDiscovery {

    public interface Listener {
        void onGateway(Gateway gateway);
    }

    public static final class Gateway {
        public final String name;
        public final String pubB64;
        public final InetAddress address;
        public final int tcpPort;
        public final String transport;

        public Gateway(String name, String pubB64, InetAddress address,
                       int tcpPort, String transport) {
            this.name = name;
            this.pubB64 = pubB64;
            this.address = address;
            this.tcpPort = tcpPort;
            this.transport = transport;
        }
    }

    public static final int UDP_PORT = 8991;
    public static final int RELAY_PORT = 8992;
    public static final String MAGIC = "CONTROL-GATEWAY/1";

    private volatile boolean announcing;
    private volatile boolean listening;
    private DatagramSocket announceSocket;
    private DatagramSocket listenSocket;

    public void startAnnouncing(String name, String pubB64, String transport) {
        if (announcing) return;
        announcing = true;
        new Thread(() -> {
            try {
                announceSocket = new DatagramSocket();
                announceSocket.setBroadcast(true);
                JSONObject o = new JSONObject();
                o.put("magic", MAGIC);
                o.put("name", name);
                o.put("pub", pubB64);
                o.put("tcp", RELAY_PORT);
                o.put("transport", transport);
                byte[] data = o.toString().getBytes(StandardCharsets.UTF_8);
                InetAddress broadcast = InetAddress.getByName("255.255.255.255");
                while (announcing) {
                    DatagramPacket p = new DatagramPacket(data, data.length, broadcast, UDP_PORT);
                    announceSocket.send(p);
                    Thread.sleep(2000);
                }
            } catch (Exception e) {
                // interface went away; stay silent — state is shown elsewhere
            }
        }, "gw-announce").start();
    }

    public void stopAnnouncing() {
        announcing = false;
        if (announceSocket != null) announceSocket.close();
        announceSocket = null;
    }

    public void startListening(Listener listener) {
        if (listening) return;
        listening = true;
        new Thread(() -> {
            try {
                listenSocket = new DatagramSocket(UDP_PORT);
                listenSocket.setBroadcast(true);
                byte[] buf = new byte[1024];
                while (listening) {
                    DatagramPacket p = new DatagramPacket(buf, buf.length);
                    listenSocket.receive(p);
                    String text = new String(p.getData(), 0, p.getLength(),
                            StandardCharsets.UTF_8);
                    try {
                        JSONObject o = new JSONObject(text);
                        if (!MAGIC.equals(o.optString("magic"))) continue;
                        String pub = o.optString("pub", "");
                        if (pub.isEmpty()) continue;
                        listener.onGateway(new Gateway(
                                o.optString("name", "Phone"),
                                pub,
                                p.getAddress(),
                                o.optInt("tcp", RELAY_PORT),
                                o.optString("transport", "")));
                    } catch (Exception ignored) {
                    }
                }
            } catch (Exception e) {
                // socket closed on stop
            }
        }, "gw-listen").start();
    }

    public void stopListening() {
        listening = false;
        if (listenSocket != null) listenSocket.close();
        listenSocket = null;
    }

    public void stop() {
        stopAnnouncing();
        stopListening();
    }
}
