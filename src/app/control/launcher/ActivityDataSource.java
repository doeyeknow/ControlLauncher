package app.control.launcher;

import java.util.List;

/**
 * Seam for the Activity / Control Room data.
 *
 * The v1 implementation is AndroidActivityDataSource and uses only
 * normal, user-granted Android APIs. A future privileged helper (for
 * example a separately approved Shizuku tier) could implement this same
 * interface without changing the screen or implying capabilities v1
 * does not have.
 */
public interface ActivityDataSource {

    final class AppUsage {
        public final String pkg;
        public final String app;
        public final long foregroundMs;
        public final long lastUsed;

        AppUsage(String pkg, String app, long foregroundMs, long lastUsed) {
            this.pkg = pkg;
            this.app = app;
            this.foregroundMs = foregroundMs;
            this.lastUsed = lastUsed;
        }
    }

    final class Transition {
        public final long time;
        public final String pkg;
        public final String app;
        public final boolean foreground;

        Transition(long time, String pkg, String app, boolean foreground) {
            this.time = time;
            this.pkg = pkg;
            this.app = app;
            this.foreground = foreground;
        }
    }

    final class NetworkUsage {
        public final String id;
        public final String app;
        public final long rxBytes;
        public final long txBytes;

        NetworkUsage(String id, String app, long rxBytes, long txBytes) {
            this.id = id;
            this.app = app;
            this.rxBytes = rxBytes;
            this.txBytes = txBytes;
        }

        public long totalBytes() {
            return rxBytes + txBytes;
        }
    }

    boolean hasUsageAccess();

    List<NotificationFeedStore.Entry> notifications();

    List<AppUsage> appUsageToday() throws Exception;

    List<Transition> transitionsToday() throws Exception;

    List<NetworkUsage> networkUsage(long start, long end) throws Exception;

    List<String> ownStatus();

    List<OwnLog.Entry> ownLogs();
}
