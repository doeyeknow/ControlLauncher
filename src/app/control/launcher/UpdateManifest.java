package app.control.launcher;

import java.util.Locale;
import java.util.Map;

/**
 * The update manifest (v1.27): the pure-Java rules for reading
 * {@code version.json} from the launcher site, deciding whether it
 * names a newer version, and building the URLs the updater fetches.
 * No Android imports — the harness proves the exact code the app
 * runs. JSON goes through {@link RelayJson}, the same tiny parser
 * the relay client uses, because android.jar's org.json stubs
 * throw off-device.
 *
 * <p>The manifest shape is fixed and flat:
 * {@code {"versionCode":32,"versionName":"1.27",
 * "apk":"control-launcher.apk","sha256":"<64 hex>","size":20694398}}.
 * Every field is required and strictly validated: a manifest that
 * fails any check is unusable, never half-trusted. Unknown extra
 * fields are ignored so the format can grow without breaking old
 * builds.
 *
 * <p>URL rules: everything is HTTPS, always. The manifest URL is a
 * constant; the APK field may be a relative path (resolved against
 * the manifest's origin) or an absolute HTTPS URL. Anything else —
 * plain http, a scheme-relative URL, an empty value — is refused.
 * The manifest fetch is cache-busted with a timestamp so a stale
 * cached copy can never hide an update.
 */
public final class UpdateManifest {

    /** The one place updates come from. HTTPS only, no fallback. */
    public static final String MANIFEST_URL =
            "https://launcher.eyeknow.app/version.json";

    /** How long a quiet launch check stays fresh (1 hour). The
     *  Settings row always forces a fresh check when tapped, so
     *  this throttle only quiets repeat launches. */
    public static final long QUIET_CHECK_FRESH_MS = 60L * 60L * 1000L;

    /** The largest manifest the fetcher will read (64 KB). A
     *  version file is a few hundred bytes; anything bigger is
     *  not a manifest. */
    public static final int MAX_MANIFEST_BYTES = 64 * 1024;

    private UpdateManifest() {}

    /** One parsed, validated manifest. */
    public static final class Manifest {
        public final int versionCode;
        public final String versionName;
        /** The APK field exactly as the manifest wrote it
         *  (relative path or absolute HTTPS URL). */
        public final String apk;
        /** Lowercase 64-hex SHA-256 of the APK bytes. */
        public final String sha256;
        public final long size;

        Manifest(int versionCode, String versionName, String apk,
                 String sha256, long size) {
            this.versionCode = versionCode;
            this.versionName = versionName;
            this.apk = apk;
            this.sha256 = sha256;
            this.size = size;
        }
    }

    /** The result of parsing: a manifest, or a plain-language
     *  reason it could not be trusted. Never both, never neither. */
    public static final class ParseResult {
        public final Manifest manifest;
        public final String error;

        private ParseResult(Manifest manifest, String error) {
            this.manifest = manifest;
            this.error = error;
        }

        public static ParseResult ok(Manifest m) {
            return new ParseResult(m, "");
        }

        public static ParseResult bad(String error) {
            return new ParseResult(null, error);
        }

        public boolean isOk() {
            return manifest != null;
        }
    }

    /**
     * Parses and strictly validates manifest JSON. Null, empty,
     * non-object, and malformed bodies all fail with a reason;
     * so does any missing or out-of-range field.
     */
    public static ParseResult parse(String json) {
        if (json == null || json.trim().isEmpty()) {
            return ParseResult.bad("The update file was empty.");
        }
        String trimmed = json.trim();
        if (!trimmed.startsWith("{") || !trimmed.endsWith("}")) {
            return ParseResult.bad("The update file is not a JSON object.");
        }
        Map<String, Object> m;
        try {
            Object parsed = RelayJson.parse(trimmed);
            if (!(parsed instanceof Map)) {
                return ParseResult.bad("The update file is not a JSON object.");
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> cast = (Map<String, Object>) parsed;
            m = cast;
        } catch (Exception e) {
            return ParseResult.bad("The update file could not be read.");
        }

        Object codeRaw = m.get("versionCode");
        long code = numberValue(codeRaw, -1);
        if (code <= 0 || code > Integer.MAX_VALUE) {
            return ParseResult.bad("The update file has no valid version code.");
        }
        String name = stringValue(m.get("versionName"));
        if (name.isEmpty()) {
            return ParseResult.bad("The update file has no version name.");
        }
        String apk = stringValue(m.get("apk"));
        if (apk.isEmpty()) {
            return ParseResult.bad("The update file names no download.");
        }
        String sha = stringValue(m.get("sha256"));
        if (!UpdateVerify.isValidSha256Hex(sha)) {
            return ParseResult.bad("The update file has no valid checksum.");
        }
        Object sizeRaw = m.get("size");
        long size = numberValue(sizeRaw, -1);
        if (size <= 0) {
            return ParseResult.bad("The update file has no valid size.");
        }
        return ParseResult.ok(new Manifest((int) code, name, apk,
                sha.toLowerCase(Locale.US), size));
    }

    /** A JSON number, or a string holding one; anything else is
     *  the fallback. Booleans and null are never numbers here. */
    private static long numberValue(Object v, long fallback) {
        if (v instanceof Number) return ((Number) v).longValue();
        if (v instanceof String) {
            try {
                return Long.parseLong(((String) v).trim());
            } catch (Exception e) {
                return fallback;
            }
        }
        return fallback;
    }

    private static String stringValue(Object v) {
        if (v instanceof String) return ((String) v).trim();
        return "";
    }

    /** True when the manifest names a strictly newer version.
     *  Equal or lower version codes are never updates: no
     *  downgrades, no same-version reinstalls. */
    public static boolean isUpdate(Manifest m, int installedCode) {
        return m != null && m.versionCode > installedCode;
    }

    /**
     * The manifest URL with a cache-busting timestamp, so a
     * proxy or CDN edge can never serve a stale copy and hide
     * an update. The timestamp is caller-supplied (millis)
     * so the harness can pin the exact output.
     */
    public static String cacheBustedUrl(String baseUrl, long nowMs) {
        if (baseUrl == null) return "";
        String sep = baseUrl.contains("?") ? "&" : "?";
        return baseUrl + sep + "t=" + nowMs;
    }

    /** True when the URL is absolute HTTPS — the only scheme
     *  the updater ever fetches. Case-insensitive scheme. */
    public static boolean isHttpsUrl(String url) {
        if (url == null) return false;
        String u = url.trim().toLowerCase(Locale.US);
        return u.startsWith("https://") && u.length() > "https://".length();
    }

    /**
     * Resolves the manifest's {@code apk} field to the absolute
     * URL the downloader fetches. A relative path resolves
     * against the manifest's origin (scheme + host, no path —
     * the manifest lives at the site root, and the APK beside
     * it). An absolute URL must be HTTPS. Anything else —
     * http, scheme-relative, a bare host, an empty value, a
     * path that escapes the root ({@code ..}) — is refused
     * with a null return.
     */
    public static String resolveApkUrl(String manifestUrl, String apkField) {
        if (manifestUrl == null || apkField == null) return null;
        String apk = apkField.trim();
        if (apk.isEmpty()) return null;
        if (apk.startsWith("//")) return null;
        if (apk.contains("://")) {
            return isHttpsUrl(apk) ? apk : null;
        }
        if (apk.startsWith("..")) return null;
        if (apk.contains("/../") || apk.endsWith("/..")) return null;
        String origin = originOf(manifestUrl);
        if (origin == null) return null;
        while (apk.startsWith("/")) apk = apk.substring(1);
        if (apk.isEmpty()) return null;
        return origin + "/" + apk;
    }

    /** {@code https://host} from an absolute HTTPS URL, or null. */
    static String originOf(String url) {
        if (!isHttpsUrl(url)) return null;
        String rest = url.trim().substring("https://".length());
        int slash = rest.indexOf('/');
        String host = slash < 0 ? rest : rest.substring(0, slash);
        int q = host.indexOf('?');
        if (q >= 0) host = host.substring(0, q);
        int hash = host.indexOf('#');
        if (hash >= 0) host = host.substring(0, hash);
        if (host.isEmpty()) return null;
        return "https://" + host;
    }

    /**
     * Whether a quiet (unforced) launch check should run: never
     * checked, or the last check is older than the freshness
     * window. A forced check (the Settings row) always runs.
     */
    public static boolean shouldQuietCheck(long lastCheckAtMs, long nowMs) {
        if (lastCheckAtMs <= 0) return true;
        if (nowMs < lastCheckAtMs) return true; // clock moved back: re-check
        return (nowMs - lastCheckAtMs) > QUIET_CHECK_FRESH_MS;
    }
}
