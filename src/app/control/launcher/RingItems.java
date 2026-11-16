package app.control.launcher;

import android.content.Intent;
import android.graphics.drawable.Drawable;

import java.util.List;

/**
 * The sub-ring model (v1.9): what a leaf on a sub-ring looks like,
 * what choosing it does, and the source the menu asks for a ring's
 * contents. MainActivity implements {@link Source} — it owns the
 * pages and the routing, so every leaf action runs the same code
 * path as doing it from the sheet, the tuner, or the list.
 */
public final class RingItems {

    private RingItems() {}

    /** One leaf on a sub-ring. Exactly one visual form applies, in
     *  this order: an app icon (direct or lazily resolved —
     *  {@link #resolveIcon()}), a number chip ({@code numberText}),
     *  or a line glyph ({@code glyph}). App icons resolve lazily so
     *  building a ring of dozens of apps never touches
     *  PackageManager on the UI thread; only the visible page's
     *  icons load, through InstalledApps' in-memory cache. */
    public static final class Item {
        public final String label;
        public final int glyph;          // PageGlyphs id, or -1
        public final String numberText;  // e.g. a channel number, or null
        public final int numberColor;    // 0 = default ink
        public final Action action;
        private final Drawable icon;     // direct artwork, or null
        private final IconSource lazyIcon;
        private Drawable resolvedIcon;
        private boolean iconResolved;

        /** Supplies an icon on first draw. */
        public interface IconSource {
            Drawable get();
        }

        private Item(String label, int glyph, Drawable icon, IconSource lazyIcon,
                     String numberText, int numberColor, Action action) {
            this.label = label;
            this.glyph = glyph;
            this.icon = icon;
            this.lazyIcon = lazyIcon;
            this.numberText = numberText;
            this.numberColor = numberColor;
            this.action = action;
        }

        /** The leaf's icon, resolving the lazy source once. Null
         *  when the leaf has no icon form. */
        public Drawable resolveIcon() {
            if (icon != null) return icon;
            if (lazyIcon == null) return null;
            if (!iconResolved) {
                iconResolved = true;
                try {
                    resolvedIcon = lazyIcon.get();
                } catch (Exception e) {
                    resolvedIcon = null;
                }
            }
            return resolvedIcon;
        }

        public static Item glyph(String label, int glyph, Action action) {
            return new Item(label, glyph, null, null, null, 0, action);
        }

        public static Item app(String label, Drawable icon, Action action) {
            return new Item(label, -1, icon, null, null, 0, action);
        }

        public static Item appLazy(String label, IconSource icon, Action action) {
            return new Item(label, -1, null, icon, null, 0, action);
        }

        public static Item number(String label, String numberText,
                                  int numberColor, Action action) {
            return new Item(label, -1, null, null, numberText, numberColor, action);
        }
    }

    /** What choosing a leaf does. Payloads are plain data — the
     *  source's activity performs the action. */
    public static final class Action {
        public static final int LAUNCH_APP = 0;       // text = package
        public static final int OPEN_INTENT = 1;      // intent
        public static final int TUNE_CHANNEL = 2;     // number = channel
        public static final int OPEN_URL = 3;         // text = url
        public static final int OPEN_CONVO = 4;       // text = channelId
        public static final int HOME_ROLE = 5;        // no payload
        public static final int NOTIF_SETTINGS = 6;   // no payload

        public final int kind;
        public final String text;
        public final int number;
        public final Intent intent;

        private Action(int kind, String text, int number, Intent intent) {
            this.kind = kind;
            this.text = text;
            this.number = number;
            this.intent = intent;
        }

        public static Action launchApp(String pkg) {
            return new Action(LAUNCH_APP, pkg, 0, null);
        }

        public static Action openIntent(Intent intent) {
            return new Action(OPEN_INTENT, null, 0, intent);
        }

        public static Action tuneChannel(int number) {
            return new Action(TUNE_CHANNEL, null, number, null);
        }

        public static Action openUrl(String url) {
            return new Action(OPEN_URL, url, 0, null);
        }

        public static Action openConvo(String channelId) {
            return new Action(OPEN_CONVO, channelId, 0, null);
        }

        public static Action homeRole() {
            return new Action(HOME_ROLE, null, 0, null);
        }

        public static Action notifSettings() {
            return new Action(NOTIF_SETTINGS, null, 0, null);
        }
    }

    /**
     * Contents + actions for sub-rings. {@link #itemsFor} runs on
     * the UI thread and must return fast: cached or cheap local
     * data only, never a PackageManager query. Returning null
     * means "still loading" — the menu shows a loading state and
     * the source calls back into the menu's refresh once the data
     * lands. An empty list is a genuinely empty ring.
     */
    public interface Source {
        List<Item> itemsFor(int parentItem);
        String emptyHint(int parentItem);
        void onLeafAction(Action action);
    }
}
