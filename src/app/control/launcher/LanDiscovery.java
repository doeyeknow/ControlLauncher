package app.control.launcher;

import org.json.JSONObject;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Set;

/**
 * Finds walkie-talkie hosts on the same Wi-Fi network (router/hotspot,
 * no internet needed) by UDP broadcast. The host announces itself; a
 * joiner listens, then connects with the ordinary mesh socket engine —
 * same protocol, different road in.
 */
public class LanDiscovery {

    public interface FoundListener {
        void onFound(String hostName, InetAddress address);
    }

    static final int UDP_PORT = 8990;
    private static final String MAGIC = "CONTROL-WALKIE/1";

    private volatile boolean announcing;
    private volatile boolean listening;
    private DatagramSocket announceSocket;
    private DatagramSocket listenSocket;

    public void startAnnouncing(String hostName) {
        announcing = true;
        new Thread(() -> {
            try {
                announceSocket = new DatagramSocket();
                announceSocket.setBroadcast(true);
                JSONObject o = new JSONObject();
                o.put("magic", MAGIC);
                o.put("name", hostName);
                o.put("tcp", MeshNet.PORT);
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
        }, "lan-announce").start();
    }

    public void startListening(FoundListener listener) {
        listening = true;
        new Thread(() -> {
            Set<String> seen = new HashSet<>();
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
                        if (MAGIC.equals(o.optString("magic"))) {
                            String addr = p.getAddress().getHostAddress();
                            if (seen.add(addr)) {
                                listener.onFound(o.optString("name", "Host"),
                                        p.getAddress());
                            }
                        }
                    } catch (Exception ignored) {
                    }
                }
            } catch (Exception e) {
                // socket closed on stop
            }
        }, "lan-listen").start();
    }

    public void stop() {
        announcing = false;
        listening = false;
        if (announceSocket != null) announceSocket.close();
        if (listenSocket != null) listenSocket.close();
    }
}
