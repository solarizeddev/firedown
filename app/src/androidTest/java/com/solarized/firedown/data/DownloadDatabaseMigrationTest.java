package com.solarized.firedown.data;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteException;

import androidx.room.testing.MigrationTestHelper;
import androidx.sqlite.db.SupportSQLiteDatabase;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.ArrayList;
import java.util.List;

/**
 * {@code MIGRATION_13_14} on a REAL version-13 database file, built from the
 * exported schema ({@code app/schemas}, added to the test APK's assets):
 * <ul>
 *   <li>a row holding a whole {@code data:} URL in {@code file_url} and
 *       {@code file_origin_url} keeps only the header, and a {@code data:}
 *       {@code file_img} points back at the file;</li>
 *   <li>a database left in the one-way-door state (Room's persistent
 *       invalidation triggers without their table — every write to
 *       {@code download} fails at statement compile) migrates anyway: the
 *       guard drops the orphaned triggers before the first UPDATE;</li>
 *   <li>with the table present the triggers are left alone.</li>
 * </ul>
 * Uses its own database name; the app's {@code download-db} is never opened.
 *
 * <p>Run from Android Studio (right-click → Debug, debug variant) or:
 * <pre>
 *   ./gradlew connectedDebugAndroidTest \
 *     -Pandroid.testInstrumentationRunnerArguments.class=com.solarized.firedown.data.DownloadDatabaseMigrationTest
 * </pre>
 */
@RunWith(AndroidJUnit4.class)
public class DownloadDatabaseMigrationTest {

    private static final String DB = "download-db-migration-test";
    private static final String PAYLOAD = "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNkYPhfDwAChwGA60e6kgAAAABJRU5ErkJggg==";
    private static final String HEADER = "data:image/png;base64,";
    private static final String PATH = "/storage/emulated/0/Download/Firedown/inline.png";

    @Rule
    public final MigrationTestHelper helper = new MigrationTestHelper(
            InstrumentationRegistry.getInstrumentation(), DownloadDatabase.class);

    private Context mApp;

    @Before
    public void setUp() {
        mApp = InstrumentationRegistry.getInstrumentation().getTargetContext();
        mApp.deleteDatabase(DB);
    }

    @After
    public void tearDown() {
        mApp.deleteDatabase(DB);
    }

    @Test
    public void dataUrlPayloadsAreCutToTheirHeader() throws Exception {
        SupportSQLiteDatabase v13 = helper.createDatabase(DB, 13);
        insertRow(v13, 1, HEADER + PAYLOAD, HEADER + PAYLOAD, HEADER + PAYLOAD, PATH);
        insertRow(v13, 2, "https://example.test/clip.mp4", "https://example.test/page",
                "https://example.test/poster.jpg", "/storage/emulated/0/Download/Firedown/clip.mp4");
        v13.close();

        SupportSQLiteDatabase v14 = helper.runMigrationsAndValidate(DB, 14, true,
                DownloadDatabase.MIGRATION_13_14);
        try {
            String[] inline = row(v14, 1);
            assertEquals(HEADER, inline[0]);
            assertEquals(HEADER, inline[1]);
            assertEquals("the picture points back at the file", PATH, inline[2]);

            String[] plain = row(v14, 2);
            assertEquals("https://example.test/clip.mp4", plain[0]);
            assertEquals("https://example.test/page", plain[1]);
            assertEquals("https://example.test/poster.jpg", plain[2]);
        } finally {
            v14.close();
        }
    }

    @Test
    public void orphanedTrackingTriggersAreDroppedBeforeTheUpdate() throws Exception {
        SupportSQLiteDatabase v13 = helper.createDatabase(DB, 13);
        insertRow(v13, 1, HEADER + PAYLOAD, HEADER + PAYLOAD, HEADER + PAYLOAD, PATH);
        createTrackingTriggers(v13);
        // The trap is real: with the triggers and no table, a write fails.
        assertThrows(SQLiteException.class,
                () -> v13.execSQL("UPDATE download SET file_progress = file_progress"));
        v13.close();

        SupportSQLiteDatabase v14 = helper.runMigrationsAndValidate(DB, 14, true,
                DownloadDatabase.MIGRATION_13_14);
        try {
            assertEquals(HEADER, row(v14, 1)[0]);
            assertTrue("the orphaned triggers are gone", trackingTriggers(v14).isEmpty());
            // And a write works again.
            v14.execSQL("UPDATE download SET file_progress = file_progress");
        } finally {
            v14.close();
        }
    }

    @Test
    public void trackingTriggersWithTheirTableAreKept() throws Exception {
        SupportSQLiteDatabase v13 = helper.createDatabase(DB, 13);
        insertRow(v13, 1, HEADER + PAYLOAD, HEADER + PAYLOAD, HEADER + PAYLOAD, PATH);
        v13.execSQL("CREATE TABLE IF NOT EXISTS room_table_modification_log "
                + "(table_id INTEGER PRIMARY KEY, invalidated INTEGER NOT NULL DEFAULT 0)");
        v13.execSQL("INSERT OR IGNORE INTO room_table_modification_log VALUES (0, 0)");
        createTrackingTriggers(v13);
        v13.close();

        // The extra table is Room's own; the schema check must not count it.
        SupportSQLiteDatabase v14 = helper.runMigrationsAndValidate(DB, 14, false,
                DownloadDatabase.MIGRATION_13_14);
        try {
            assertEquals(HEADER, row(v14, 1)[0]);
            assertEquals(3, trackingTriggers(v14).size());
            try (Cursor c = v14.query("SELECT invalidated FROM room_table_modification_log WHERE table_id = 0")) {
                assertTrue(c.moveToFirst());
                assertEquals("the kept triggers fired on the migration's UPDATE", 1, c.getInt(0));
            }
        } finally {
            v14.close();
        }
    }

    // ---------------------------------------------------------------------

    private static void insertRow(SupportSQLiteDatabase db, int uid, String url, String origin,
                                  String img, String path) {
        db.execSQL("INSERT INTO download (uid, file_parent_id, file_type, file_url, file_name, file_img,"
                        + " file_path, file_mime_type, file_origin_url, file_progress, file_date, file_size,"
                        + " file_live, file_error_message, file_status, file_encrypted,"
                        + " file_thumbnail_duration, file_duration, file_safe, file_thumbnail_unavailable)"
                        + " VALUES (?, 0, 1, ?, 'inline.png', ?, ?, 'image/png', ?, 100, ?, 68, 0, 0, 1, 0, 0, 0, 0, 0)",
                new Object[]{uid, url, img, path, origin, System.currentTimeMillis()});
    }

    /** Room's persistent-mode triggers, as a pre-fix binary left them: pointing at a table it dropped. */
    private static void createTrackingTriggers(SupportSQLiteDatabase db) {
        for (String event : new String[]{"INSERT", "UPDATE", "DELETE"}) {
            db.execSQL("CREATE TRIGGER IF NOT EXISTS `room_table_modification_trigger_download_" + event + "`"
                    + " AFTER " + event + " ON `download` BEGIN"
                    + " UPDATE room_table_modification_log SET invalidated = 1 WHERE table_id = 0 AND invalidated = 0;"
                    + " END");
        }
    }

    private static List<String> trackingTriggers(SupportSQLiteDatabase db) {
        List<String> names = new ArrayList<>();
        try (Cursor c = db.query("SELECT name FROM sqlite_master WHERE type = 'trigger'"
                + " AND name LIKE 'room_table_modification_trigger_download_%'")) {
            while (c.moveToNext()) {
                names.add(c.getString(0));
            }
        }
        return names;
    }

    /** {@code file_url, file_origin_url, file_img} of a row. */
    private static String[] row(SupportSQLiteDatabase db, int uid) {
        try (Cursor c = db.query("SELECT file_url, file_origin_url, file_img FROM download WHERE uid = " + uid)) {
            assertTrue("row " + uid, c.moveToFirst());
            assertFalse(c.isNull(0));
            return new String[]{c.getString(0), c.getString(1), c.getString(2)};
        }
    }
}
