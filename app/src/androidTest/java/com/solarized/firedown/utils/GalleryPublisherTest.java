package com.solarized.firedown.utils;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

import android.content.ContentResolver;
import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.net.Uri;
import android.provider.MediaStore;

import androidx.preference.PreferenceManager;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.solarized.firedown.Preferences;
import com.solarized.firedown.StoragePaths;
import com.solarized.firedown.data.RestoredFileAccess;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;

/**
 * {@link GalleryPublisher}: the media type a file is scanned under, and the
 * switch-on path landing a picture in MediaStore. The scan is the system's
 * (MediaScannerConnection), so the second part needs a device whose download
 * folder is on shared storage; it is skipped where the folder fell back to
 * app-private storage. Restores the "Add to media gallery" switch afterwards.
 *
 * <p>Run from Android Studio (right-click → Debug, debug variant) or:
 * <pre>
 *   ./gradlew connectedDebugAndroidTest \
 *     -Pandroid.testInstrumentationRunnerArguments.class=com.solarized.firedown.utils.GalleryPublisherTest
 * </pre>
 */
@RunWith(AndroidJUnit4.class)
public class GalleryPublisherTest {

    private Context mApp;
    private SharedPreferences mPrefs;
    private boolean mHadPref;
    private boolean mOldPref;
    private File mFile;

    @Before
    public void setUp() {
        mApp = InstrumentationRegistry.getInstrumentation().getTargetContext().getApplicationContext();
        mPrefs = PreferenceManager.getDefaultSharedPreferences(mApp);
        mHadPref = mPrefs.contains(Preferences.SETTINGS_GALLERY);
        mOldPref = mPrefs.getBoolean(Preferences.SETTINGS_GALLERY, false);
    }

    @After
    public void tearDown() {
        SharedPreferences.Editor editor = mPrefs.edit();
        if (mHadPref) {
            editor.putBoolean(Preferences.SETTINGS_GALLERY, mOldPref);
        } else {
            editor.remove(Preferences.SETTINGS_GALLERY);
        }
        editor.commit();
        if (mFile != null) {
            String path = mFile.getAbsolutePath();
            mFile.delete();
            GalleryPublisher.forget(mApp, path);
            try {
                mApp.getContentResolver().delete(MediaStore.Files.getContentUri("external"),
                        MediaStore.MediaColumns.DATA + " = ?", new String[]{path});
            } catch (RuntimeException ignored) {
                // a row the system owns; the deleted file drops it on its own
            }
        }
    }

    @Test
    public void theStoredTypeWinsWhenItIsMedia() {
        assertEquals("image/png", GalleryPublisher.mediaTypeFor("/x/a.bin", "image/png"));
        assertEquals("video/mp4", GalleryPublisher.mediaTypeFor("/x/a", "video/mp4"));
        assertEquals("audio/mpeg", GalleryPublisher.mediaTypeFor("/x/a.txt", "audio/mpeg"));
    }

    @Test
    public void aNonMediaStoredTypeFallsBackToTheFileName() {
        assertEquals("video/mp4", GalleryPublisher.mediaTypeFor("/x/clip.mp4", "application/octet-stream"));
        assertEquals("image/jpeg", GalleryPublisher.mediaTypeFor("/x/photo.jpg", null));
        assertEquals("audio/mpeg", GalleryPublisher.mediaTypeFor("/x/song.mp3", ""));
    }

    @Test
    public void aFileThatIsNoMediaIsNeverScanned() {
        assertNull(GalleryPublisher.mediaTypeFor("/x/paper.pdf", "application/pdf"));
        assertNull(GalleryPublisher.mediaTypeFor("/x/archive.zip", null));
        assertNull(GalleryPublisher.mediaTypeFor("/x/blob", "application/octet-stream"));
    }

    @Test
    public void withTheSwitchOnAPictureLandsInMediaStore() throws Exception {
        StoragePaths.ensureDownloadPath(mApp);
        String folder = StoragePaths.getDownloadPath(mApp);
        assumeTrue("the download folder is not on shared storage on this device",
                RestoredFileAccess.isSharedStoragePath(folder));

        mFile = new File(folder, "gallery-publisher-" + System.nanoTime() + ".png");
        writePng(mFile);
        mPrefs.edit().putBoolean(Preferences.SETTINGS_GALLERY, true).commit();

        GalleryPublisher.publishIfEnabled(mApp, mFile.getAbsolutePath(), "application/octet-stream");

        assertTrue("no MediaStore row for " + mFile, awaitRow(mFile.getAbsolutePath(), 15_000));
    }

    // ---------------------------------------------------------------------

    private static void writePng(File file) throws IOException {
        Bitmap bitmap = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888);
        bitmap.eraseColor(0xFFFF716C);
        try (FileOutputStream out = new FileOutputStream(file)) {
            assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, out));
        } finally {
            bitmap.recycle();
        }
    }

    private boolean awaitRow(String path, long ms) throws InterruptedException {
        ContentResolver resolver = mApp.getContentResolver();
        Uri files = MediaStore.Files.getContentUri("external");
        long deadline = System.currentTimeMillis() + ms;
        while (System.currentTimeMillis() < deadline) {
            try (Cursor c = resolver.query(files, new String[]{MediaStore.MediaColumns._ID},
                    MediaStore.MediaColumns.DATA + " = ?", new String[]{path}, null)) {
                if (c != null && c.moveToFirst()) {
                    return true;
                }
            }
            Thread.sleep(250);
        }
        return false;
    }
}
