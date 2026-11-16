package app.control.launcher;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;

/**
 * Thin forwarder (v1.5): chat is a page of the launcher now (see
 * ChatPage). Anything still pointing at this activity — the Settings
 * hub row, older intents — lands on MainActivity at the Chat page.
 * There is no second chat surface.
 */
public class ChatActivity extends ThemedActivity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Intent target = new Intent(this, MainActivity.class);
        target.putExtra(MainActivity.EXTRA_PAGE, MainActivity.PAGE_CHAT);
        startActivity(target);
        finish();
    }
}
