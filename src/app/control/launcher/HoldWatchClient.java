package app.control.launcher;

import android.content.Context;
import android.net.LocalSocket;
import android.net.LocalSocketAddress;
import android.os.Handler;
import android.os.Looper;
import android.util.DisplayMetrics;
import android.view.WindowManager;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

/**
 * App-side client for the bridge's streaming HOLD_WATCH op
 * (v1.14). One long-lived connection: the request carries the
 * hold radius (computed from this display's metrics) and the
 * hold threshold, the bridge answers an ack line and then one
 * JSON line per detected hold, and the client maps each hold's
 * normalized native-orientation coordinates to current display
 * pixels (rotation read fresh per hold) before delivering it.
 *
 * Transport preference mirrors ShellBridgeClient: the abstract
 * local socket first, loopback TCP as the fallback. The client
 * never sees the raw touch stream — only hold points, one line
 * each — and it never logs coordinates.
 *
 * Lifecycle: {@link #start} spawns the reader thread;
 * {@link #stop} closes the socket, which the bridge notices
 * (its drain thread ends the watch and kills getevent). Any
 * end — clean close, error, refused ack — is reported once via
 * {@link Callback#onEnded} on the main thread.
 */
public final class HoldWatchClient {

    /** Hold radius on screen: the same 12dp the ring's dwell
     *  tolerance uses — a hold is a finger that stays put. */
    public static final float HOLD_RADIUS_DP = 12f;

    /** Stationary time that fires a hold (strictly greater-than
     *  in the parser). */
    public static final long HOLD_MS = 1000L;

    public interface Callback {
        /** A hold, in current-orientation display pixels. */
        void onHold(float xPx, float yPx);

        /** The watch ended (bridge down, refused, error, or
         *  stopped). The reason is a short human line, never a
         *  coordinate. */
        void onEnded(String reason);
    }

    private final Context app;
    private final Callback callback;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private Thread worker;
    private volatile boolean stopped;
    private volatile java.io.Closeable socket;

    public HoldWatchClient(Context context, Callback callback) {
        this.app = context.getApplicationContext();
        this.callback = callback;
    }

    public synchronized void start() {
        if (worker != null && worker.isAlive()) return;
        stopped = false;
        worker = new Thread(this::run, "hold-watch");
        worker.setDaemon(true);
        worker.start();
    }

    public synchronized void stop() {
        stopped = true;
        try {
            if (socket != null) socket.close();
        } catch (Exception ignored) {
        }
        if (worker != null) worker.interrupt();
    }

    private void run() {
        String endReason = "The hold watch ended.";
        try {
            endReason = runStream();
        } catch (Exception e) {
            endReason = "The hold watch failed: "
                    + (e.getMessage() == null ? "connection error" : e.getMessage());
        }
        final String reason = stopped ? "The hold watch was stopped." : endReason;
        mainHandler.post(() -> callback.onEnded(reason));
    }

    private String runStream() throws Exception {
        DisplayMetrics dm = app.getResources().getDisplayMetrics();
        double radiusNorm = HoldCoordinateMapper.radiusNorm(
                dm.density, dm.widthPixels, dm.heightPixels, HOLD_RADIUS_DP);
        JSONObject request = new JSONObject();
        request.put("op", "HOLD_WATCH");
        request.put("token", ShellBridgeClient.token(app));
        request.put("radiusNorm", radiusNorm);
        request.put("holdMs", HOLD_MS);

        Stream stream = connect();
        if (stream == null) {
            return "The bridge is not running.";
        }
        socket = stream.closeable;
        if (stopped) {
            // stop() landed during the connect: close what just
            // opened instead of leaving an orphaned watch running.
            try {
                stream.closeable.close();
            } catch (Exception ignored) {
            }
            return "The hold watch was stopped.";
        }
        OutputStream out = stream.out;
        out.write((request.toString() + "\n").getBytes(StandardCharsets.UTF_8));
        out.flush();
        BufferedReader reader = new BufferedReader(
                new InputStreamReader(stream.in, StandardCharsets.UTF_8));
        String ack = reader.readLine();
        if (ack == null) return "The bridge closed the hold watch.";
        JSONObject ackJson;
        try {
            ackJson = new JSONObject(ack);
        } catch (Exception e) {
            return "The bridge answered the hold watch with bad data.";
        }
        if (!ackJson.optBoolean("ok", false)) {
            // Defense in depth (v1.15.1): a bridge too old for
            // HOLD_WATCH refuses with the allowlist error. Name
            // the stale bridge so the log tells the truth even
            // if the version gate was somehow bypassed — the
            // version gate in reconcile() should have kept the
            // service from starting at all against this bridge.
            if (BridgeGate.isStaleRefusal(ackJson)) {
                return "The bridge is too old — restart it (Deep view → Restart).";
            }
            String err = ackJson.optString("error", "");
            return err.isEmpty() ? "The bridge refused the hold watch." : err;
        }
        String line;
        while (!stopped && (line = reader.readLine()) != null) {
            JSONObject hold;
            try {
                hold = new JSONObject(line);
            } catch (Exception ignored) {
                continue;
            }
            if (!hold.optBoolean("hold", false)) continue;
            double nx = hold.optDouble("nx", -1.0);
            double ny = hold.optDouble("ny", -1.0);
            if (nx < 0.0 || nx > 1.0 || ny < 0.0 || ny > 1.0) continue;
            float[] xy = mapHold(nx, ny);
            mainHandler.post(() -> callback.onHold(xy[0], xy[1]));
        }
        return stopped
                ? "The hold watch was stopped."
                : "The bridge ended the hold watch.";
    }

    /** Current rotation + metrics, read fresh per hold so a
     *  rotation mid-watch maps the next hold correctly. */
    private float[] mapHold(double nx, double ny) {
        int rotation = HoldCoordinateMapper.ROTATION_0;
        try {
            WindowManager wm = (WindowManager) app.getSystemService(Context.WINDOW_SERVICE);
            if (wm != null) rotation = wm.getDefaultDisplay().getRotation();
        } catch (Exception ignored) {
        }
        DisplayMetrics dm = app.getResources().getDisplayMetrics();
        return HoldCoordinateMapper.map(nx, ny, rotation,
                dm.widthPixels, dm.heightPixels);
    }

    private static final class Stream {
        java.io.InputStream in;
        OutputStream out;
        java.io.Closeable closeable;
    }

    /** Local socket first (peer-UID verified by the bridge),
     *  loopback TCP second — the ShellBridgeClient order. */
    private Stream connect() {
        LocalSocket local = new LocalSocket();
        try {
            local.connect(new LocalSocketAddress(ShellBridgeMain.SOCKET_NAME,
                    LocalSocketAddress.Namespace.ABSTRACT), 2500);
            Stream s = new Stream();
            s.in = local.getInputStream();
            s.out = local.getOutputStream();
            s.closeable = local;
            return s;
        } catch (Exception e) {
            try {
                local.close();
            } catch (Exception ignored) {
            }
        }
        try {
            Socket tcp = new Socket(InetAddress.getByName("127.0.0.1"),
                    ShellBridgeMain.TCP_PORT);
            Stream s = new Stream();
            s.in = tcp.getInputStream();
            s.out = tcp.getOutputStream();
            s.closeable = tcp;
            return s;
        } catch (Exception e) {
            return null;
        }
    }
}
