package app.control.launcher;

import android.content.Context;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;

import java.net.InetAddress;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * mDNS (NSD) discovery of the phone's own wireless-debugging services:
 * {@code _adb-tls-pairing._tcp} (present while the pairing dialog is
 * open) and {@code _adb-tls-connect._tcp} (present while wireless
 * debugging is on and the connect service is up). Framework NsdManager
 * only — no protocol work happens here; this just finds the port,
 * which Android picks fresh each time wireless debugging is enabled.
 */
public final class AdbMdns {

    public static final String TYPE_PAIRING = "_adb-tls-pairing._tcp";
    public static final String TYPE_CONNECT = "_adb-tls-connect._tcp";

    public interface Callback {
        void onFound(InetAddress host, int port, String serviceName);
        void onError(String message);
    }

    public static final class Session {
        private NsdManager mgr;
        private NsdManager.DiscoveryListener listener;
        private final AtomicBoolean stopped = new AtomicBoolean(false);

        public void stop() {
            if (!stopped.compareAndSet(false, true)) return;
            try {
                if (mgr != null && listener != null) mgr.stopServiceDiscovery(listener);
            } catch (Exception ignored) {
            }
        }
    }

    private AdbMdns() {}

    /** Discover the first matching service and resolve it. The caller
     *  owns the returned session and should stop() it when done. */
    public static Session discover(Context c, final String type, final Callback cb) {
        final Session session = new Session();
        final AtomicBoolean reported = new AtomicBoolean(false);
        try {
            session.mgr = (NsdManager) c.getSystemService(Context.NSD_SERVICE);
            if (session.mgr == null) {
                cb.onError("This phone has no mDNS (NSD) service.");
                return session;
            }
            session.listener = new NsdManager.DiscoveryListener() {
                @Override public void onDiscoveryStarted(String serviceType) {}

                @Override public void onServiceFound(NsdServiceInfo info) {
                    if (!reported.compareAndSet(false, true)) return;
                    try {
                        session.mgr.resolveService(info,
                                new NsdManager.ResolveListener() {
                                    @Override public void onResolveFailed(
                                            NsdServiceInfo i, int errorCode) {
                                        session.stop();
                                        cb.onError("Found the service but could not "
                                                + "resolve it (error " + errorCode + ").");
                                    }

                                    @Override public void onServiceResolved(
                                            NsdServiceInfo i) {
                                        session.stop();
                                        cb.onFound(i.getHost(), i.getPort(),
                                                i.getServiceName());
                                    }
                                });
                    } catch (Exception e) {
                        session.stop();
                        cb.onError("Resolve failed: " + e.getMessage());
                    }
                }

                @Override public void onServiceLost(NsdServiceInfo info) {}

                @Override public void onDiscoveryStopped(String serviceType) {}

                @Override public void onStartDiscoveryFailed(String serviceType, int errorCode) {
                    if (reported.compareAndSet(false, true)) {
                        cb.onError("Discovery failed to start (error " + errorCode + ").");
                    }
                }

                @Override public void onStopDiscoveryFailed(String serviceType, int errorCode) {}
            };
            session.mgr.discoverServices(type, NsdManager.PROTOCOL_DNS_SD, session.listener);
        } catch (Exception e) {
            cb.onError("Discovery failed: " + e.getMessage());
        }
        return session;
    }
}
