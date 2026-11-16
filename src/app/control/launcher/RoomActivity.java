package app.control.launcher;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;

/**
 * Thin forwarder (v1.5): a single conversation is the Chat pager
 * page's in-page thread now (see ChatPage / ChatThreadView). The
 * extras this activity used to consume — "channelId", "channelKey",
 * "name", "peerPub", e.g. from a contact's DM button — are carried
 * across to MainActivity, which routes them into the Chat page's
 * thread. There is no second thread surface.
 */
public class RoomActivity extends ThemedActivity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Intent target = new Intent(this, MainActivity.class);
        target.putExtra(MainActivity.EXTRA_PAGE, MainActivity.PAGE_CHAT);
        Intent mine = getIntent();
        if (mine != null) {
            copy(mine, target, "channelId", MainActivity.EXTRA_CHAT_CHANNEL_ID);
            copy(mine, target, "channelKey", MainActivity.EXTRA_CHAT_CHANNEL_KEY);
            copy(mine, target, "name", MainActivity.EXTRA_CHAT_NAME);
            copy(mine, target, "peerPub", MainActivity.EXTRA_CHAT_PEER);
        }
        startActivity(target);
        finish();
    }

    private static void copy(Intent from, Intent to, String oldKey, String newKey) {
        String v = from.getStringExtra(oldKey);
        if (v != null) to.putExtra(newKey, v);
    }
}
