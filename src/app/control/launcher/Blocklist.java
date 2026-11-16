package app.control.launcher;

import android.content.Context;
import android.util.Log;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.HashSet;
import java.util.Set;

/**
 * The shared ad/tracker domain blocklist. Used by the VPN DNS filter
 * (domain level) and by the built-in browser (request level).
 *
 * The bundled list (assets/blocklist.txt) is subdomain-collapsed, so a
 * lookup walks parent suffixes: checking "ads.example.com" also checks
 * "example.com".
 */
public final class Blocklist {
    private static final String TAG = "Blocklist";
    private static Set<String> domains;
    private static int rawCount;

    private Blocklist() {}

    public static synchronized Set<String> load(Context c) {
        if (domains != null) return domains;
        Set<String> set = new HashSet<>(65536);
        try (InputStream in = c.getAssets().open("blocklist.txt");
             BufferedReader r = new BufferedReader(new InputStreamReader(in))) {
            String line;
            while ((line = r.readLine()) != null) {
                line = line.trim();
                if (!line.isEmpty()) set.add(line);
            }
        } catch (Exception e) {
            Log.e(TAG, "failed to load blocklist", e);
        }
        domains = set;
        rawCount = set.size();
        Log.i(TAG, "blocklist loaded: " + rawCount + " domains");
        return domains;
    }

    public static int size(Context c) {
        load(c);
        return rawCount;
    }

    /** True if the host or any of its parent domains is blocklisted. */
    public static boolean isBlocked(Context c, String host) {
        if (host == null || host.isEmpty()) return false;
        Set<String> set = load(c);
        String h = host.toLowerCase();
        if (h.endsWith(".")) h = h.substring(0, h.length() - 1);
        while (true) {
            if (set.contains(h)) return true;
            int dot = h.indexOf('.');
            if (dot < 0) return false;
            h = h.substring(dot + 1);
            // stop before the bare TLD
            if (h.indexOf('.') < 0) return set.contains(h);
        }
    }
}
