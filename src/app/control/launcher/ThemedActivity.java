package app.control.launcher;

import android.app.Activity;
import android.os.Bundle;

/**
 * Base class for every activity (v1.15): applies the stored
 * theme before any view is built, and rebuilds the activity on
 * resume when the theme changed while it was paused (the theme
 * switch in Settings, or the theme step of setup).
 *
 * Activities holding transient state a rebuild would lose — a
 * half-typed form, an open camera or audio session, a terminal
 * transcript, a loaded web page — override
 * {@link #recreateOnThemeChange()} to return false. They pick
 * up the new theme the next time the screen opens, which the
 * user never has to think about.
 */
public class ThemedActivity extends Activity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        Ui.applyThemeStyle(this);
        Ui.refreshTheme(this);
        super.onCreate(savedInstanceState);
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (recreateOnThemeChange() && Ui.refreshTheme(this)) {
            recreate();
        }
    }

    /** False for activities where a rebuild would lose the
     *  user's in-progress work. See the class comment. */
    protected boolean recreateOnThemeChange() {
        return true;
    }
}
