package app.control.launcher;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;

/**
 * Thin forwarder (v1.1): the browser is a page of the launcher now.
 * This activity keeps the exported VIEW http/https intent filter —
 * external web links land in MainActivity on the Browser page.
 */
public class BrowserActivity extends ThemedActivity {

    @Override
    protected boolean recreateOnThemeChange() {
        // Theme change: a rebuild would reload the loaded web page; the new theme
        // applies the next time this screen opens.
        return false;
    }
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Intent target = new Intent(this, MainActivity.class);
        target.putExtra(MainActivity.EXTRA_PAGE, MainActivity.PAGE_BROWSER);
        Intent in = getIntent();
        if (in != null && Intent.ACTION_VIEW.equals(in.getAction())
                && in.getData() != null) {
            target.putExtra(MainActivity.EXTRA_URL, in.getData().toString());
        }
        startActivity(target);
        finish();
    }
}
