package app.control.launcher;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;

/**
 * Thin forwarder (v1.1): the walkie-talkie is a page of the launcher
 * now (see WalkiePage). Anything still pointing at this activity lands
 * on the Walkie page.
 */
public class WalkieActivity extends ThemedActivity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Intent target = new Intent(this, MainActivity.class);
        target.putExtra(MainActivity.EXTRA_PAGE, MainActivity.PAGE_WALKIE);
        startActivity(target);
        finish();
    }
}
