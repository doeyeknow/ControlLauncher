package app.control.launcher;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Bitmap;
import android.net.Uri;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.webkit.CookieManager;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.PopupMenu;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

/**
 * The built-in browser. Every request — pages and reader-mode images
 * alike — passes the blocklist before it loads. Reader view extracts the
 * article with Mozilla Readability and re-renders it in Control's own
 * layout; the site's styling never reaches the reader page.
 *
 * v1.5: the start page and the Home button load the profile's homepage
 * (Profile.browserHomepage — default https://launcher.eyeknow.app; a
 * stored DuckDuckGo default is migrated, a homepage the user set to
 * something else stays theirs), and the chrome buttons got real,
 * evenly spaced ≥40dp touch targets (they were mashed together by
 * the stock Button's own min-width/padding).
 */
public class BrowserPage extends LinearLayout implements PagerLayout.PagerPage {

    private final Activity act;
    private boolean destroyed;


    private WebView web;
    private EditText urlBar;
    private ProgressBar progress;
    private Button readButton;
    private String currentUrl = "";
    private boolean readerMode;
    private String readerableJs;
    private String readabilityJs;
    private boolean readerScriptsInjected;

    public BrowserPage(Activity host) {
        super(host);
        this.act = host;
        setOrientation(LinearLayout.VERTICAL);
        setBackgroundColor(0x00000000); // chrome transparent; the WebView keeps its own surface
        LinearLayout root = this;

        // The mockup's browser chrome: a pill URL bar (mono) floating
        // on the page background, small rounded nav buttons. v1.5:
        // back / forward / reload / home sit in exact 40dp boxes with
        // an even 6dp between every chrome element, so nothing is
        // mashed together and the URL bar keeps the remaining width.
        LinearLayout bar = new LinearLayout(act);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(android.view.Gravity.CENTER_VERTICAL);
        bar.setBackgroundColor(0x00000000);
        bar.setPadding(Ui.dp(act, 8), Ui.dp(act, 8), Ui.dp(act, 8), Ui.dp(act, 8));
        root.addView(bar);

        bar.addView(navButton("‹", v -> { if (web.canGoBack()) web.goBack(); }));
        bar.addView(navButton("›", v -> { if (web.canGoForward()) web.goForward(); }));
        bar.addView(navButton("↻", v -> {
            if (readerMode) exitReader();
            web.reload();
        }));
        bar.addView(navButton("⌂", v -> {
            exitReader();
            web.loadUrl(Profile.browserHomepage(act));
        }));

        urlBar = new EditText(act);
        urlBar.setSingleLine(true);
        urlBar.setTextSize(13);
        urlBar.setTypeface(android.graphics.Typeface.MONOSPACE);
        urlBar.setTextColor(Ui.TEXT);
        urlBar.setHintTextColor(Ui.DIM);
        urlBar.setHint("Search or enter address");
        urlBar.setBackground(Ui.roundedStroke(act, Ui.SURFACE, 999, Ui.LINE));
        urlBar.setPadding(Ui.dp(act, 16), Ui.dp(act, 9), Ui.dp(act, 16), Ui.dp(act, 9));
        urlBar.setImeOptions(EditorInfo.IME_ACTION_GO);
        urlBar.setInputType(android.text.InputType.TYPE_TEXT_VARIATION_URI);
        urlBar.setOnEditorActionListener((tv, action, ev) -> {
            if (action == EditorInfo.IME_ACTION_GO
                    || (ev != null && ev.getKeyCode() == KeyEvent.KEYCODE_ENTER)) {
                navigate(urlBar.getText().toString());
                return true;
            }
            return false;
        });
        LinearLayout.LayoutParams up = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        up.setMargins(0, 0, Ui.dp(act, 6), 0);
        bar.addView(urlBar, up);

        readButton = navButton("Read", 52, 13, v -> toggleReader());
        readButton.setVisibility(View.GONE);
        bar.addView(readButton);
        Button menuButton = navButton("⋮", this::showMenu);
        ((LinearLayout.LayoutParams) menuButton.getLayoutParams())
                .setMargins(0, 0, 0, 0);
        bar.addView(menuButton);

        progress = new ProgressBar(act, null, android.R.attr.progressBarStyleHorizontal);
        progress.setMax(100);
        root.addView(progress, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(act, 3)));

        web = new WebView(act);
        root.addView(web, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        setupWebView();

        web.loadUrl(Profile.browserHomepage(act));
    }

    private Button navButton(String label, View.OnClickListener onClick) {
        return navButton(label, 40, 17, onClick);
    }

    private Button navButton(String label, int widthDp, float textSp,
                             View.OnClickListener onClick) {
        Button b = new Button(act);
        b.setText(label);
        b.setTextSize(textSp);
        b.setTextColor(Ui.TEXT);
        b.setBackground(Ui.roundedStroke(act, Ui.SURFACE2, 9, Ui.LINE));
        // The stock Button's own min width/height and padding are
        // what mashed the chrome together: zero them all and give
        // the button an exact box instead — a real ≥40dp touch
        // target with an even 6dp gap to the next chrome element,
        // at any phone width.
        b.setMinWidth(0);
        b.setMinimumWidth(0);
        b.setMinHeight(0);
        b.setMinimumHeight(0);
        b.setPadding(0, 0, 0, 0);
        b.setIncludeFontPadding(false);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                Ui.dp(act, widthDp), Ui.dp(act, 40));
        lp.setMargins(0, 0, Ui.dp(act, 6), 0);
        b.setLayoutParams(lp);
        b.setOnClickListener(onClick);
        return b;
    }

    private void setupWebView() {
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(Prefs.jsOn(act));
        s.setDomStorageEnabled(true);
        s.setBuiltInZoomControls(true);
        s.setDisplayZoomControls(false);
        s.setSafeBrowsingEnabled(true);
        CookieManager cm = CookieManager.getInstance();
        cm.setAcceptCookie(true);
        cm.setAcceptThirdPartyCookies(web, false);

        web.addJavascriptInterface(new ReaderBridge(), "ReaderBridge");
        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onProgressChanged(WebView view, int p) {
                progress.setProgress(p);
                progress.setVisibility(p >= 100 ? View.GONE : View.VISIBLE);
            }
        });
        web.setWebViewClient(new WebViewClient() {
            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view,
                                                                WebResourceRequest req) {
                Uri u = req.getUrl();
                String scheme = u.getScheme();
                if (("http".equals(scheme) || "https".equals(scheme))
                        && Blocklist.isBlocked(act, u.getHost())) {
                    return new WebResourceResponse("text/plain", "utf-8",
                            new ByteArrayInputStream(new byte[0]));
                }
                return super.shouldInterceptRequest(view, req);
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest req) {
                String scheme = req.getUrl().getScheme();
                if ("http".equals(scheme) || "https".equals(scheme)) {
                    if (readerMode) exitReader();
                    return false;
                }
                // tel:, mailto:, intent: etc. go to the system.
                try {
                    act.startActivity(new Intent(Intent.ACTION_VIEW, req.getUrl()));
                } catch (Exception ignored) {
                }
                return true;
            }

            @Override
            public void onPageStarted(WebView view, String url, Bitmap favicon) {
                currentUrl = url;
                readerScriptsInjected = false;
                if (!readerMode) {
                    urlBar.setText(displayUrl(url));
                    readButton.setVisibility(View.GONE);
                }
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                if (!readerMode) detectReaderable();
            }
        });
    }

    private String displayUrl(String url) {
        return url;
    }

    private void navigate(String raw) {
        String q = raw.trim();
        if (q.isEmpty()) return;
        String url;
        if (q.startsWith("http://") || q.startsWith("https://")) {
            url = q;
        } else if (q.contains(".") && !q.contains(" ")) {
            url = "https://" + q;
        } else {
            url = "https://duckduckgo.com/?q=" + Uri.encode(q);
        }
        exitReader();
        web.loadUrl(url);
        web.requestFocus();
    }

    private void showMenu(View anchor) {
        PopupMenu menu = new PopupMenu(act, anchor);
        menu.getMenu().add("Reload");
        menu.getMenu().add("Bookmark this page");
        menu.getMenu().add("Bookmarks");
        menu.getMenu().add("Share page");
        menu.getMenu().add("Open in another browser");
        menu.setOnMenuItemClickListener(item -> {
            String t = item.getTitle().toString();
            if (t.equals("Reload")) {
                if (readerMode) exitReader();
                web.reload();
            } else if (t.equals("Bookmark this page")) {
                Profile.addBookmark(act, web.getTitle(), currentUrl);
                Toast.makeText(act, "Bookmarked", Toast.LENGTH_SHORT).show();
            } else if (t.equals("Bookmarks")) {
                act.startActivity(new Intent(act, BookmarksActivity.class));
            } else if (t.equals("Share page")) {
                Intent share = new Intent(Intent.ACTION_SEND);
                share.setType("text/plain");
                share.putExtra(Intent.EXTRA_TEXT, currentUrl);
                act.startActivity(Intent.createChooser(share, "Share page"));
            } else if (t.equals("Open in another browser")) {
                Intent open = new Intent(Intent.ACTION_VIEW, Uri.parse(currentUrl));
                open.addCategory(Intent.CATEGORY_BROWSABLE);
                try {
                    act.startActivity(Intent.createChooser(open, "Open with"));
                } catch (Exception e) {
                    Toast.makeText(act, "No other browser found", Toast.LENGTH_SHORT).show();
                }
            }
            return true;
        });
        menu.show();
    }


    @Override
    public void onPageDestroy() {
        if (destroyed) return;
        destroyed = true;
        if (web != null) {
            web.destroy();
        }
    }

    @Override
    public void onPageShown() {
        if (web != null) web.onResume();
    }

    @Override
    public void onPageHidden() {
        if (web != null) web.onPause();
    }

    /** The WebView owns horizontal gestures (page pan/zoom); the pager
     *  only takes swipes that start on the browser chrome or screen edge. */
    @Override
    public boolean ownsHorizontalGesture(float x, float y) {
        if (web == null) return false;
        return x >= web.getLeft() && x < web.getRight()
                && y >= web.getTop() && y < web.getBottom();
    }

    /** Open a URL in the page (external VIEW intents land here). */
    public void openUrl(String url) {
        if (url == null || url.isEmpty()) return;
        exitReader();
        web.loadUrl(url);
    }

    // ---- Reader mode ----

    private String assetText(String name) {
        try (InputStream in = act.getAssets().open(name);
             BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            StringBuilder sb = new StringBuilder();
            char[] buf = new char[8192];
            int n;
            while ((n = r.read(buf)) > 0) sb.append(buf, 0, n);
            return sb.toString();
        } catch (Exception e) {
            return "";
        }
    }

    private void detectReaderable() {
        if (readerableJs == null) readerableJs = assetText("Readability-readerable.js");
        if (readerableJs.isEmpty()) return;
        web.evaluateJavascript(readerableJs
                + "\n;isProbablyReaderable(document, {minContentLength: 300});",
                value -> {
                    if ("true".equals(value)) {
                        readButton.setVisibility(View.VISIBLE);
                    }
                });
    }

    private void toggleReader() {
        if (readerMode) {
            exitReader();
            web.loadUrl(currentUrl);
            return;
        }
        if (readabilityJs == null) readabilityJs = assetText("Readability.js");
        String driver =
                "try {"
                + "  var clone = document.cloneNode(true);"
                + "  var article = new Readability(clone).parse();"
                + "  if (!article) { ReaderBridge.onArticle(''); }"
                + "  else {"
                + "    var holder = document.createElement('div');"
                + "    holder.innerHTML = article.content;"
                + "    holder.querySelectorAll('script,iframe,object,embed,form,button,link,style')"
                + "      .forEach(function(n){ n.remove(); });"
                + "    holder.querySelectorAll('*').forEach(function(n){"
                + "      for (var i = n.attributes.length - 1; i >= 0; i--) {"
                + "        var a = n.attributes[i];"
                + "        if (a.name.toLowerCase().indexOf('on') === 0"
                + "            || ((a.name === 'href' || a.name === 'src')"
                + "                && a.value.toLowerCase().indexOf('javascript:') === 0)) {"
                + "          n.removeAttribute(a.name);"
                + "        }"
                + "      }"
                + "    });"
                + "    article.content = holder.innerHTML;"
                + "    ReaderBridge.onArticle(JSON.stringify(article));"
                + "  }"
                + "} catch (e) { ReaderBridge.onArticle(''); }";
        web.evaluateJavascript(readabilityJs + "\n;" + driver, null);
    }

    private void exitReader() {
        if (readerMode) {
            readerMode = false;
            readButton.setText("Read");
        }
    }

    private class ReaderBridge {
        @JavascriptInterface
        public void onArticle(String json) {
            act.runOnUiThread(() -> showReader(json));
        }
    }

    private void showReader(String json) {
        String title = "";
        String byline = "";
        String published = "";
        String site = "";
        String content = "";
        int textLen = 0;
        try {
            if (json == null || json.isEmpty()) throw new Exception("empty");
            JSONObject a = new JSONObject(json);
            title = a.optString("title", "");
            byline = a.optString("byline", "");
            published = a.optString("publishedTime", "");
            site = a.optString("siteName", "");
            content = a.optString("content", "");
            textLen = a.optInt("length", 0);
        } catch (Exception e) {
            textLen = 0;
        }
        if (textLen < 400 || content.isEmpty()) {
            Toast.makeText(act, "No readable article found on this page.",
                    Toast.LENGTH_SHORT).show();
            return;
        }
        StringBuilder meta = new StringBuilder();
        if (!byline.isEmpty()) meta.append(escapeHtml(byline));
        if (!published.isEmpty()) {
            if (meta.length() > 0) meta.append(" · ");
            meta.append(escapeHtml(published.substring(0, Math.min(10, published.length()))));
        }
        if (!site.isEmpty()) {
            if (meta.length() > 0) meta.append(" · ");
            meta.append(escapeHtml(site));
        }
        String html = "<!DOCTYPE html><html><head><meta charset=\"utf-8\">"
                + "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">"
                + "<style>" + READER_CSS + "</style></head><body><article>"
                + "<h1>" + escapeHtml(title) + "</h1>"
                + (meta.length() > 0 ? "<p class=\"meta\">" + meta + "</p>" : "")
                + content
                + "</article></body></html>";
        readerMode = true;
        readButton.setVisibility(View.VISIBLE);
        readButton.setText("Full page");
        // Base URL = the page's own URL: relative image sources resolve, and
        // image requests still pass through shouldInterceptRequest (blocklist).
        web.loadDataWithBaseURL(currentUrl, html, "text/html", "utf-8", null);
    }

    private static String escapeHtml(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;");
    }

    // Reader palette = the space phone tokens, as CSS hex.
    private static final String READER_CSS =
            "html{background:#101613}"
            + "body{margin:0;padding:28px 20px 48px;background:#101613;color:#E8EDE9;"
            + "font-family:sans-serif;font-size:18px;line-height:1.65}"
            + "article{max-width:680px;margin:0 auto}"
            + "h1{font-size:30px;line-height:1.25;font-weight:600;margin:0 0 10px}"
            + "h2{font-size:24px;line-height:1.3;margin:30px 0 10px}"
            + "h3{font-size:20px;margin:26px 0 8px}"
            + "p{margin:0 0 18px}"
            + ".meta{color:#93A399;font-size:14px;margin-bottom:26px}"
            + "a{color:#4ADE80}"
            + "img,figure img{max-width:100%;height:auto;border-radius:6px}"
            + "figure{margin:22px 0}figcaption{color:#93A399;font-size:14px;margin-top:6px}"
            + "blockquote{border-left:3px solid #4ADE80;margin:22px 0;padding:4px 0 4px 16px;color:#C4CFC6}"
            + "pre,code{font-size:14px}pre{overflow-x:auto;background:#171E1A;padding:12px;border-radius:6px}"
            + "ul,ol{margin:0 0 18px;padding-left:24px}li{margin-bottom:8px}"
            + "table{border-collapse:collapse}td,th{border:1px solid #2A332D;padding:6px 10px}";
}
