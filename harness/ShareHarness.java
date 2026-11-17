package app.control.launcher;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Off-device verification for the v1.25 share-an-app screen,
 * exercising the exact shipping rules (SharePayload, ShareHttp,
 * SharePicker, ShareHotspotState):
 *
 * Legs:
 *   1. WIFI_PAYLOAD: the WPA join string, escaping of every
 *      field-breaking character, the open-network shape, and
 *      null/empty inputs.
 *   2. DOWNLOAD_URL: IPv4-literal + port validation, exact
 *      path, rejection of hostnames and malformed addresses.
 *   3. PAGE_HTML: the page carries the label, size, download
 *      link, and the no-internet line; labels are escaped.
 *   4. SIZE: byte/KB/MB/GB formatting and its boundaries.
 *   5. ROUTE: exact-match routing — page/APK served, every
 *      traversal attempt, encoded variant, and wrong method
 *      rejected, malformed lines safely parsed.
 *   6. PICKER: launcher-first ordering, alphabetical sort,
 *      exclusion of non-launchable/unreadable apps, label
 *      fallback, dedupe, input immutability.
 *   7. HOTSPOT_STATE: the full transition table — legal
 *      transitions move, illegal ones refuse and hold state.
 *   8. PICK_ADDRESS: interface-preference order and the
 *      site-local rule, with down/loopback/link-local/public
 *      addresses skipped.
 *   9. STRIP_QUOTES: one outer pair stripped, everything else
 *      untouched.
 *  10. SELF_ROW (v1.26.1): Control itself prepended to the
 *      share feed — present and first, carrying the real
 *      label and size, dropped when unreadable like any
 *      other row, duplicates collapsing to one, the input
 *      list untouched. (The drawer's own exclusion lives in
 *      AppListStore.scan, which needs a PackageManager and
 *      cannot run here; it is untouched by this change —
 *      verified by diff, not by harness.)
 *
 * The Android wiring the harness cannot see (the hotspot
 * start, the socket server's byte flow, the provider's URI
 * grant, the share sheet) is verified by the build and by
 * the on-device checklist in BUILD_NOTES; this harness pins
 * every rule that wiring depends on. The server is not
 * exercised over a loopback socket here: the sandbox garbles
 * JVM IPv4 loopback traffic, so a socket test would fail for
 * sandbox reasons, not code reasons — the routing it would
 * test is ShareHttp, pinned directly instead.
 *
 * Run:
 *   javac -encoding UTF-8 --release 17 -sourcepath src \
 *     -d harness/classes harness/ShareHarness.java
 *   java -cp harness/classes app.control.launcher.ShareHarness
 * No Android classes, no network.
 */
public final class ShareHarness {

    private static int checks;
    private static int failures;

    private static void check(String name, boolean ok) {
        checks++;
        if (ok) {
            System.out.println("PASS  " + name);
        } else {
            failures++;
            System.out.println("FAIL  " + name);
        }
    }

    private static void checkEq(String name, Object want, Object got) {
        checks++;
        if (want == null ? got == null : want.equals(got)) {
            System.out.println("PASS  " + name);
        } else {
            failures++;
            System.out.println("FAIL  " + name + "  -- want <" + want + "> got <" + got + ">");
        }
    }

    public static void main(String[] args) {
        wifiPayload();
        downloadUrl();
        pageHtml();
        size();
        route();
        picker();
        hotspotState();
        pickAddress();
        stripQuotes();
        selfRow();
        System.out.println("ShareHarness: " + (checks - failures) + "/" + checks);
        if (failures > 0) System.exit(1);
    }

    private static void wifiPayload() {
        checkEq("wifi basic",
                "WIFI:T:WPA;S:Hotspot123;P:s3cretpass;H:false;;",
                SharePayload.wifiConfig("Hotspot123", "s3cretpass"));
        checkEq("wifi escapes semicolon",
                "WIFI:T:WPA;S:My\\;Net;P:p;H:false;;",
                SharePayload.wifiConfig("My;Net", "p"));
        checkEq("wifi escapes all specials",
                "WIFI:T:WPA;S:a\\\\b\\,c\\:d\\\"e;P:x\\;y;H:false;;",
                SharePayload.wifiConfig("a\\b,c:d\"e", "x;y"));
        checkEq("wifi open network",
                "WIFI:T:nopass;S:OpenNet;;",
                SharePayload.wifiConfig("OpenNet", ""));
        checkEq("wifi null password is open",
                "WIFI:T:nopass;S:OpenNet;;",
                SharePayload.wifiConfig("OpenNet", null));
        checkEq("wifi null ssid",
                "WIFI:T:WPA;S:;P:p;H:false;;",
                SharePayload.wifiConfig(null, "p"));
        checkEq("wifi password with spaces kept",
                "WIFI:T:WPA;S:Net;P:two words;H:false;;",
                SharePayload.wifiConfig("Net", "two words"));
    }

    private static void downloadUrl() {
        checkEq("url basic",
                "http://192.168.43.1:8091/app.apk",
                SharePayload.downloadUrl("192.168.43.1", 8091));
        checkEq("url port 1", "http://10.0.0.1:1/app.apk",
                SharePayload.downloadUrl("10.0.0.1", 1));
        checkEq("url port 65535", "http://10.0.0.1:65535/app.apk",
                SharePayload.downloadUrl("10.0.0.1", 65535));
        check("url rejects hostname", SharePayload.downloadUrl("phone.local", 80) == null);
        check("url rejects octet over 255", SharePayload.downloadUrl("192.168.1.300", 80) == null);
        check("url rejects 3 parts", SharePayload.downloadUrl("192.168.1", 80) == null);
        check("url rejects 5 parts", SharePayload.downloadUrl("1.2.3.4.5", 80) == null);
        check("url rejects empty part", SharePayload.downloadUrl("192.168..1", 80) == null);
        check("url rejects letters", SharePayload.downloadUrl("192.168.a.1", 80) == null);
        check("url rejects null", SharePayload.downloadUrl(null, 80) == null);
        check("url rejects empty", SharePayload.downloadUrl("", 80) == null);
        check("url rejects port 0", SharePayload.downloadUrl("192.168.1.1", 0) == null);
        check("url rejects port 65536", SharePayload.downloadUrl("192.168.1.1", 65536) == null);
        check("url rejects negative port", SharePayload.downloadUrl("192.168.1.1", -1) == null);
    }

    private static void pageHtml() {
        String html = SharePayload.pageHtml("Control", "20.6 MB");
        check("page has label", html.contains("Control"));
        check("page has size", html.contains("20.6 MB"));
        check("page has apk link", html.contains("href=\"/app.apk\""));
        check("page says no internet", html.contains("no internet"));
        check("page is a document", html.startsWith("<!doctype html>"));
        String evil = SharePayload.pageHtml("<script>alert(1)</script>", "1 KB");
        check("page escapes script tag",
                evil.contains("&lt;script&gt;") && !evil.contains("<script>alert"));
        String quoted = SharePayload.pageHtml("A\"B & C'D", "1 KB");
        check("page escapes quotes and amp",
                quoted.contains("A&quot;B &amp; C&#39;D"));
        check("page null label falls back",
                SharePayload.pageHtml(null, "1 KB").contains(">app<")
                        || SharePayload.pageHtml(null, "1 KB").contains("Install app"));
    }

    private static void size() {
        checkEq("size 0", "0 B", SharePayload.formatSize(0));
        checkEq("size negative", "0 B", SharePayload.formatSize(-5));
        checkEq("size bytes", "512 B", SharePayload.formatSize(512));
        checkEq("size 1023", "1023 B", SharePayload.formatSize(1023));
        checkEq("size 1 KB", "1 KB", SharePayload.formatSize(1024));
        checkEq("size 53 KB", "53 KB", SharePayload.formatSize(53 * 1024 + 512));
        checkEq("size MB", "20.6 MB", SharePayload.formatSize(21611561));
        checkEq("size 1 MB", "1.0 MB", SharePayload.formatSize(1024 * 1024));
        checkEq("size GB", "1.5 GB", SharePayload.formatSize(1610612736L));
    }

    private static void route() {
        checkEq("route GET /", ShareHttp.Route.PAGE,
                ShareHttp.routeLine("GET / HTTP/1.1"));
        checkEq("route GET /app.apk", ShareHttp.Route.APK,
                ShareHttp.routeLine("GET /app.apk HTTP/1.1"));
        checkEq("route HEAD /", ShareHttp.Route.PAGE,
                ShareHttp.routeLine("HEAD / HTTP/1.0"));
        checkEq("route HEAD /app.apk", ShareHttp.Route.APK,
                ShareHttp.routeLine("HEAD /app.apk HTTP/1.1"));
        checkEq("route query stripped", ShareHttp.Route.APK,
                ShareHttp.routeLine("GET /app.apk?x=1 HTTP/1.1"));
        checkEq("route fragment stripped", ShareHttp.Route.PAGE,
                ShareHttp.routeLine("GET /#top HTTP/1.1"));
        checkEq("route POST rejected", ShareHttp.Route.NOT_FOUND,
                ShareHttp.routeLine("POST /app.apk HTTP/1.1"));
        checkEq("route PUT rejected", ShareHttp.Route.NOT_FOUND,
                ShareHttp.routeLine("PUT / HTTP/1.1"));
        checkEq("route DELETE rejected", ShareHttp.Route.NOT_FOUND,
                ShareHttp.routeLine("DELETE /app.apk HTTP/1.1"));
        checkEq("route lowercase method rejected", ShareHttp.Route.NOT_FOUND,
                ShareHttp.routeLine("get / HTTP/1.1"));
        checkEq("route traversal dotdot", ShareHttp.Route.NOT_FOUND,
                ShareHttp.routeLine("GET /../ HTTP/1.1"));
        checkEq("route traversal after apk", ShareHttp.Route.NOT_FOUND,
                ShareHttp.routeLine("GET /app.apk/../ HTTP/1.1"));
        checkEq("route traversal encoded", ShareHttp.Route.NOT_FOUND,
                ShareHttp.routeLine("GET /%2e%2e/ HTTP/1.1"));
        checkEq("route traversal encoded apk", ShareHttp.Route.NOT_FOUND,
                ShareHttp.routeLine("GET /%2e%2e/app.apk HTTP/1.1"));
        checkEq("route double slash", ShareHttp.Route.NOT_FOUND,
                ShareHttp.routeLine("GET //app.apk HTTP/1.1"));
        checkEq("route backslash", ShareHttp.Route.NOT_FOUND,
                ShareHttp.routeLine("GET /..\\ HTTP/1.1"));
        checkEq("route dot segment", ShareHttp.Route.NOT_FOUND,
                ShareHttp.routeLine("GET /./app.apk HTTP/1.1"));
        checkEq("route case differs", ShareHttp.Route.NOT_FOUND,
                ShareHttp.routeLine("GET /APP.APK HTTP/1.1"));
        checkEq("route other file", ShareHttp.Route.NOT_FOUND,
                ShareHttp.routeLine("GET /other.apk HTTP/1.1"));
        checkEq("route favicon", ShareHttp.Route.NOT_FOUND,
                ShareHttp.routeLine("GET /favicon.ico HTTP/1.1"));
        checkEq("route null line", ShareHttp.Route.NOT_FOUND,
                ShareHttp.routeLine(null));
        checkEq("route empty line", ShareHttp.Route.NOT_FOUND,
                ShareHttp.routeLine(""));
        checkEq("route two parts", ShareHttp.Route.NOT_FOUND,
                ShareHttp.routeLine("GET /"));
        checkEq("route four parts", ShareHttp.Route.NOT_FOUND,
                ShareHttp.routeLine("GET / HTTP/1.1 extra"));
        checkEq("route bad version", ShareHttp.Route.NOT_FOUND,
                ShareHttp.routeLine("GET / FTP/1.0"));
        checkEq("route null request", ShareHttp.Route.NOT_FOUND,
                ShareHttp.route(null));
        ShareHttp.Request parsed = ShareHttp.parseRequestLine("GET /app.apk?dl=1 HTTP/1.1");
        check("parse keeps method", parsed != null && "GET".equals(parsed.method));
        check("parse strips query", parsed != null && "/app.apk".equals(parsed.path));
        check("parse null returns null", ShareHttp.parseRequestLine(null) == null);
    }

    private static void picker() {
        String own = "app.control.launcher";
        List<SharePicker.Raw> in = new ArrayList<>(Arrays.asList(
                new SharePicker.Raw("com.zebra", "Zebra", true, 1000),
                new SharePicker.Raw(own, "Control", true, 2000),
                new SharePicker.Raw("com.apple", "apple", true, 1000),
                new SharePicker.Raw("com.banana", "Banana", true, 1000),
                new SharePicker.Raw("com.nolaunch", "NoLaunch", false, 1000),
                new SharePicker.Raw("com.nofile", "NoFile", true, 0),
                new SharePicker.Raw("com.neg", "Neg", true, -1),
                new SharePicker.Raw("", "Blank", true, 1000),
                new SharePicker.Raw(null, "NullPkg", true, 1000),
                null,
                new SharePicker.Raw("com.noname", "  ", true, 1000),
                new SharePicker.Raw("com.zebra", "Zebra Again", true, 9999),
                new SharePicker.Raw("com.tie.a", "Same", true, 1000),
                new SharePicker.Raw("com.tie.b", "same", true, 1000)));
        int inSize = in.size();
        List<SharePicker.Row> rows = SharePicker.shape(in, own);
        checkEq("picker count", 7, rows.size());
        checkEq("picker launcher first", own, rows.get(0).pkg);
        checkEq("picker second is apple (case-insensitive)",
                "com.apple", rows.get(1).pkg);
        checkEq("picker third is banana", "com.banana", rows.get(2).pkg);
        check("picker blank label falls back to package",
                findRow(rows, "com.noname") != null
                        && "com.noname".equals(findRow(rows, "com.noname").label));
        check("picker dedupe keeps first",
                findRow(rows, "com.zebra") != null
                        && "Zebra".equals(findRow(rows, "com.zebra").label));
        check("picker tie broken by package",
                rows.indexOf(findRow(rows, "com.tie.a"))
                        < rows.indexOf(findRow(rows, "com.tie.b")));
        check("picker drops non-launchable", findRow(rows, "com.nolaunch") == null);
        check("picker drops unreadable", findRow(rows, "com.nofile") == null);
        check("picker input unmodified", in.size() == inSize);
        check("picker null input is empty", SharePicker.shape(null, own).isEmpty());
        check("picker empty input is empty",
                SharePicker.shape(new ArrayList<SharePicker.Raw>(), own).isEmpty());
        List<SharePicker.Row> noOwn = SharePicker.shape(Arrays.asList(
                new SharePicker.Raw("com.b", "B", true, 10),
                new SharePicker.Raw("com.a", "A", true, 10)), null);
        checkEq("picker no own package sorts plain", "com.a", noOwn.get(0).pkg);
    }

    private static SharePicker.Row findRow(List<SharePicker.Row> rows, String pkg) {
        for (SharePicker.Row r : rows) {
            if (r.pkg.equals(pkg)) return r;
        }
        return null;
    }

    private static void hotspotState() {
        ShareHotspotState s = new ShareHotspotState();
        checkEq("state starts IDLE", ShareHotspotState.State.IDLE, s.state());
        check("stop from IDLE refused", !s.requestStop());
        checkEq("state still IDLE", ShareHotspotState.State.IDLE, s.state());
        check("onStarted from IDLE refused", !s.onStarted());
        check("onFailed from IDLE refused", !s.onFailed());
        check("onStopped from IDLE refused", !s.onStopped());
        check("start from IDLE ok", s.requestStart());
        checkEq("state STARTING", ShareHotspotState.State.STARTING, s.state());
        check("second start refused", !s.requestStart());
        check("stop from STARTING ok", s.requestStop());
        checkEq("state STOPPING", ShareHotspotState.State.STOPPING, s.state());
        check("start from STOPPING refused", !s.requestStart());
        check("onStarted from STOPPING refused", !s.onStarted());
        check("onStopped from STOPPING ok", s.onStopped());
        checkEq("state IDLE again", ShareHotspotState.State.IDLE, s.state());
        s.requestStart();
        check("onFailed from STARTING ok", s.onFailed());
        checkEq("state IDLE after fail", ShareHotspotState.State.IDLE, s.state());
        s.requestStart();
        check("onStarted from STARTING ok", s.onStarted());
        checkEq("state RUNNING", ShareHotspotState.State.RUNNING, s.state());
        check("start from RUNNING refused", !s.requestStart());
        check("onFailed from RUNNING refused", !s.onFailed());
        check("onStopped from RUNNING ok (system stop)", s.onStopped());
        checkEq("state IDLE after system stop", ShareHotspotState.State.IDLE, s.state());
        s.requestStart();
        s.onStarted();
        check("stop from RUNNING ok", s.requestStop());
        check("second stop refused", !s.requestStop());
        check("onStopped from STOPPING ok", s.onStopped());
        checkEq("state IDLE at end", ShareHotspotState.State.IDLE, s.state());
    }

    private static ShareHotspotState.Iface iface(String name, boolean up, String... addrs) {
        return new ShareHotspotState.Iface(name, up, Arrays.asList(addrs));
    }

    private static void pickAddress() {
        checkEq("pick ap first",
                "192.168.43.1",
                ShareHotspotState.pickAddress(Arrays.asList(
                        iface("wlan0", true, "192.168.1.5"),
                        iface("ap0", true, "192.168.43.1"))));
        checkEq("pick softap over wlan",
                "192.168.44.1",
                ShareHotspotState.pickAddress(Arrays.asList(
                        iface("wlan0", true, "192.168.1.5"),
                        iface("softap0", true, "192.168.44.1"))));
        checkEq("pick wlan over p2p",
                "192.168.1.5",
                ShareHotspotState.pickAddress(Arrays.asList(
                        iface("p2p0", true, "192.168.49.1"),
                        iface("wlan0", true, "192.168.1.5"))));
        checkEq("pick p2p over other",
                "192.168.49.1",
                ShareHotspotState.pickAddress(Arrays.asList(
                        iface("rmnet0", true, "10.1.2.3"),
                        iface("p2p0", true, "192.168.49.1"))));
        checkEq("pick any up fallback",
                "10.1.2.3",
                ShareHotspotState.pickAddress(Arrays.asList(
                        iface("rmnet0", true, "10.1.2.3"))));
        checkEq("pick skips down interface",
                "192.168.43.1",
                ShareHotspotState.pickAddress(Arrays.asList(
                        iface("ap0", false, "192.168.99.1"),
                        iface("wlan0", true, "192.168.43.1"))));
        checkEq("pick skips loopback",
                "192.168.43.1",
                ShareHotspotState.pickAddress(Arrays.asList(
                        iface("ap0", true, "127.0.0.1", "192.168.43.1"))));
        check("pick skips link-local only",
                ShareHotspotState.pickAddress(Arrays.asList(
                        iface("ap0", true, "169.254.1.2"))) == null);
        check("pick skips public only",
                ShareHotspotState.pickAddress(Arrays.asList(
                        iface("rmnet0", true, "8.8.8.8"))) == null);
        checkEq("pick 172.16 boundary ok",
                "172.16.0.1",
                ShareHotspotState.pickAddress(Arrays.asList(
                        iface("ap0", true, "172.16.0.1"))));
        checkEq("pick 172.31 boundary ok",
                "172.31.255.1",
                ShareHotspotState.pickAddress(Arrays.asList(
                        iface("ap0", true, "172.31.255.1"))));
        check("pick 172.15 rejected",
                ShareHotspotState.pickAddress(Arrays.asList(
                        iface("ap0", true, "172.15.0.1"))) == null);
        check("pick 172.32 rejected",
                ShareHotspotState.pickAddress(Arrays.asList(
                        iface("ap0", true, "172.32.0.1"))) == null);
        check("pick null list", ShareHotspotState.pickAddress(null) == null);
        check("pick empty list",
                ShareHotspotState.pickAddress(new ArrayList<ShareHotspotState.Iface>()) == null);
        checkEq("pick skips null entries",
                "192.168.43.1",
                ShareHotspotState.pickAddress(Arrays.asList(
                        null, iface("ap0", true, "192.168.43.1"))));
    }

    private static void stripQuotes() {
        checkEq("quotes stripped", "MyNet", SharePayload.stripQuotes("\"MyNet\""));
        checkEq("bare kept", "MyNet", SharePayload.stripQuotes("MyNet"));
        checkEq("null kept", null, SharePayload.stripQuotes(null));
        checkEq("empty kept", "", SharePayload.stripQuotes(""));
        checkEq("lone quote kept", "\"", SharePayload.stripQuotes("\""));
        checkEq("inner quotes kept", "a\"b", SharePayload.stripQuotes("\"a\"b\""));
        checkEq("two chars of quotes", "", SharePayload.stripQuotes("\"\""));
    }

    private static void selfRow() {
        String own = "app.control.launcher";
        // The feed as the drawer cache hands it over: Control
        // absent, by the scan's design.
        List<SharePicker.Raw> feed = new ArrayList<>(Arrays.asList(
                new SharePicker.Raw("com.zebra", "Zebra", true, 1000),
                new SharePicker.Raw("com.apple", "apple", true, 1000)));
        int feedSize = feed.size();
        List<SharePicker.Row> rows = SharePicker.shape(
                SharePicker.prependSelf(feed, own, "Control", 20694398L), own);
        checkEq("self row count", 3, rows.size());
        checkEq("self row is first", own, rows.get(0).pkg);
        checkEq("self row carries real label", "Control", rows.get(0).label);
        checkEq("self row carries real size", 20694398L, rows.get(0).apkSizeBytes);
        checkEq("self row others follow sorted", "com.apple", rows.get(1).pkg);
        check("prependSelf leaves input unmodified", feed.size() == feedSize);

        // Unreadable own APK: the self row drops like any other
        // row, never a row that fails when tapped.
        List<SharePicker.Row> dropped = SharePicker.shape(
                SharePicker.prependSelf(feed, own, "Control", 0), own);
        checkEq("self row dropped when unreadable", 2, dropped.size());
        check("self row absent when unreadable",
                findRow(dropped, own) == null);
        List<SharePicker.Row> negative = SharePicker.shape(
                SharePicker.prependSelf(feed, own, "Control", -7), own);
        check("self row dropped on negative size",
                findRow(negative, own) == null);

        // A duplicate own entry already in the feed collapses
        // to one row, and the prepended self entry wins (first
        // entry kept), so the label and size are the real ones.
        List<SharePicker.Raw> dupFeed = new ArrayList<>(Arrays.asList(
                new SharePicker.Raw(own, "Stale", true, 111),
                new SharePicker.Raw("com.apple", "apple", true, 1000)));
        List<SharePicker.Row> dupRows = SharePicker.shape(
                SharePicker.prependSelf(dupFeed, own, "Control", 20694398L), own);
        checkEq("self duplicate collapses to one row", 2, dupRows.size());
        checkEq("self duplicate keeps prepended label",
                "Control", findRow(dupRows, own).label);
        checkEq("self duplicate keeps prepended size",
                20694398L, findRow(dupRows, own).apkSizeBytes);

        // Null feed: only the self row survives.
        List<SharePicker.Row> onlySelf = SharePicker.shape(
                SharePicker.prependSelf(null, own, "Control", 500), own);
        checkEq("self row alone on null feed", 1, onlySelf.size());
        checkEq("self row alone is own", own, onlySelf.get(0).pkg);

        // Blank own label falls back to the package, like any row.
        List<SharePicker.Row> blankLabel = SharePicker.shape(
                SharePicker.prependSelf(feed, own, "  ", 500), own);
        checkEq("self blank label falls back to package",
                own, blankLabel.get(0).label);
    }
}
