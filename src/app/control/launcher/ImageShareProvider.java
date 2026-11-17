package app.control.launcher;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.UriMatcher;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;

import java.io.File;
import java.io.FileNotFoundException;
import java.security.SecureRandom;
import java.util.HashMap;
import java.util.Map;

/**
 * The content provider behind screenshot View + Share (v1.35).
 *
 * The same narrow shape as ApkShareProvider: it serves exactly
 * ONE file, the screenshot the Deep controls screen registered
 * for the current view/share, addressed by an unguessable
 * token. There is no path mapping, no directory, and no way to
 * ask for any other file: an unknown token, or any mode but
 * read, fails. The provider is not exported; the receiving app
 * reads only through the URI grant the intent carries.
 *
 * The Deep controls screen registers the screenshot immediately
 * before firing the view/share intent, so the token lives only
 * as long as the share does. A previous registration is
 * replaced, never accumulated.
 */
public class ImageShareProvider extends ContentProvider {

    public static final String AUTHORITY = "app.control.launcher.imageshare";
    public static final String PNG_MIME = "image/png";

    private static final UriMatcher MATCHER = new UriMatcher(UriMatcher.NO_MATCH);
    private static final int TOKEN_FILE = 1;

    static {
        MATCHER.addURI(AUTHORITY, "*/shot.png", TOKEN_FILE);
    }

    private static final Object LOCK = new Object();
    private static final Map<String, Entry> REGISTRY = new HashMap<>();

    private static final class Entry {
        final File file;
        final String displayName;

        Entry(File file, String displayName) {
            this.file = file;
            this.displayName = displayName;
        }
    }

    @Override
    public boolean onCreate() {
        return true;
    }

    /**
     * Registers the one screenshot the next view/share serves,
     * replacing any previous registration. Returns the content
     * URI to hand to the intent.
     */
    public static Uri register(File pngFile, String displayName) {
        byte[] rand = new byte[16];
        new SecureRandom().nextBytes(rand);
        StringBuilder token = new StringBuilder(32);
        for (byte b : rand) {
            token.append(String.format("%02x", b & 0xff));
        }
        synchronized (LOCK) {
            REGISTRY.clear();
            REGISTRY.put(token.toString(), new Entry(pngFile, displayName));
        }
        return new Uri.Builder()
                .scheme("content")
                .authority(AUTHORITY)
                .appendPath(token.toString())
                .appendPath("shot.png")
                .build();
    }

    private Entry lookup(Uri uri) {
        if (MATCHER.match(uri) != TOKEN_FILE) return null;
        String token = uri.getPathSegments().get(0);
        synchronized (LOCK) {
            return REGISTRY.get(token);
        }
    }

    @Override
    public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        if (!"r".equals(mode) && !"rt".equals(mode)) {
            throw new FileNotFoundException("read-only");
        }
        Entry entry = lookup(uri);
        if (entry == null || entry.file == null || !entry.file.isFile()) {
            throw new FileNotFoundException("no such share");
        }
        return ParcelFileDescriptor.open(entry.file, ParcelFileDescriptor.MODE_READ_ONLY);
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection,
                        String[] selectionArgs, String sortOrder) {
        Entry entry = lookup(uri);
        if (entry == null) return null;
        MatrixCursor cursor = new MatrixCursor(
                new String[]{OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE});
        cursor.addRow(new Object[]{entry.displayName, entry.file.length()});
        return cursor;
    }

    @Override
    public String getType(Uri uri) {
        return lookup(uri) != null ? PNG_MIME : null;
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        throw new UnsupportedOperationException("read-only");
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        throw new UnsupportedOperationException("read-only");
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        throw new UnsupportedOperationException("read-only");
    }
}
