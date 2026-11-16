package app.control.launcher;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;

/**
 * Thin forwarder (v1.1): the all-apps drawer was the Apps page of the
 * launcher; in v1.3 it is the bottom sheet, so this lands in
 * MainActivity with the sheet raised.
 */
public class DrawerActivity extends ThemedActivity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Intent target = new Intent(this, MainActivity.class);
        target.putExtra(MainActivity.EXTRA_APPS, true);
        startActivity(target);
        finish();
    }
}
