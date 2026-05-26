package com.solarized.firedown.data;

import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.util.Log;

import com.solarized.firedown.data.dao.WebBookmarkDao;
import com.solarized.firedown.data.di.Qualifiers;
import com.solarized.firedown.data.entity.WebBookmarkEntity;

import java.io.File;
import java.util.concurrent.Executor;

import javax.inject.Inject;
import javax.inject.Singleton;

import dagger.hilt.android.qualifiers.ApplicationContext;

/**
 * One-time migration that lifts every row out of the legacy
 * {@code shortcuts-db} into the bookmarks DB as regular bookmarks,
 * then deletes the shortcuts DB on disk. User data is preserved;
 * the 'shortcut' tier itself doesn't survive — Firedown's home no
 * longer has a shortcuts surface, and bookmarks don't carry a
 * pinned/favorite tier any more.
 *
 * <p>The standalone 'shortcuts' concept and its Room database have
 * been retired (see commit). Existing users get their shortcuts
 * back as pinned bookmarks; the bookmarks list renders pinned items
 * at the top with a pin badge.</p>
 *
 * <p>Reads shortcuts via raw SQLite — there's no Room entity / DAO
 * left in the codebase for the legacy table, so the migrator owns
 * its own schema knowledge. Idempotent via a SharedPreferences flag
 * that flips after the first successful run.</p>
 */
@Singleton
public class LegacyShortcutsMigrator {

    private static final String TAG = "LegacyShortcutsMigrator";

    private static final String LEGACY_DB_NAME = "shortcuts-db";
    private static final String PREF_MIGRATED = "legacy_shortcuts_migrated_v1";

    private final Context mContext;
    private final WebBookmarkDao mBookmarkDao;
    private final SharedPreferences mPreferences;
    private final Executor mDiskExecutor;

    @Inject
    public LegacyShortcutsMigrator(
            @ApplicationContext Context context,
            WebBookmarkDao bookmarkDao,
            SharedPreferences preferences,
            @Qualifiers.DiskIO Executor diskExecutor) {
        this.mContext = context;
        this.mBookmarkDao = bookmarkDao;
        this.mPreferences = preferences;
        this.mDiskExecutor = diskExecutor;
    }

    /**
     * Posts the migration to the disk executor. Safe to call on every
     * app launch — checks the SharedPreferences flag before doing any
     * work, and short-circuits if the legacy DB file isn't on disk
     * (fresh install).
     */
    public void runIfNeeded() {
        if (mPreferences.getBoolean(PREF_MIGRATED, false)) {
            return;
        }
        mDiskExecutor.execute(this::runOnDiskThread);
    }

    private void runOnDiskThread() {
        File legacyDb = mContext.getDatabasePath(LEGACY_DB_NAME);
        if (!legacyDb.exists()) {
            // Fresh install — no legacy shortcuts to migrate. Mark
            // migrated so we don't probe the disk on every launch.
            markMigrated();
            return;
        }

        int copied = 0;
        SQLiteDatabase db = null;
        try {
            db = SQLiteDatabase.openDatabase(
                    legacyDb.getAbsolutePath(),
                    null,
                    SQLiteDatabase.OPEN_READONLY);
            Cursor c = db.rawQuery(
                    "SELECT file_url, file_title, file_icon, file_date FROM shortcuts",
                    null);
            try {
                int colUrl = c.getColumnIndex("file_url");
                int colTitle = c.getColumnIndex("file_title");
                int colIcon = c.getColumnIndex("file_icon");
                int colDate = c.getColumnIndex("file_date");
                while (c.moveToNext()) {
                    String url = colUrl >= 0 ? c.getString(colUrl) : null;
                    if (url == null || url.isEmpty()) continue;

                    WebBookmarkEntity entity = new WebBookmarkEntity();
                    // Same canonical id as every other bookmark create
                    // path so the migrated row collides cleanly with
                    // any existing bookmark for this URL on REPLACE.
                    entity.setId(com.solarized.firedown.data.repository
                            .WebBookmarkDataRepository.bookmarkIdFor(url));
                    entity.setFileUrl(url);
                    entity.setFileTitle(colTitle >= 0 ? c.getString(colTitle) : url);
                    entity.setFileIcon(colIcon >= 0 ? c.getString(colIcon) : null);
                    entity.setFileDate(colDate >= 0 ? c.getLong(colDate)
                            : System.currentTimeMillis());
                    entity.setFilePreview(null);

                    // REPLACE conflict strategy on the DAO: if a
                    // bookmark already exists for this URL (same
                    // hashCode), we promote it to pinned and use the
                    // shortcut's title/icon. Loses any existing
                    // preview text, which is acceptable — the user
                    // explicitly pinned this URL via shortcuts.
                    mBookmarkDao.insert(entity);
                    copied++;
                }
            } finally {
                c.close();
            }
        } catch (Exception e) {
            Log.e(TAG, "Migration failed; will retry on next launch", e);
            return;
        } finally {
            if (db != null) {
                try { db.close(); } catch (Exception ignored) {}
            }
        }

        // Delete every file Android creates for a SQLite DB (the
        // main file, plus -journal / -wal / -shm sidecars).
        mContext.deleteDatabase(LEGACY_DB_NAME);

        Log.i(TAG, "Migrated " + copied + " shortcut(s) -> pinned bookmarks");
        markMigrated();
    }

    private void markMigrated() {
        mPreferences.edit().putBoolean(PREF_MIGRATED, true).apply();
    }
}
