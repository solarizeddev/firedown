package com.solarized.firedown.data;

import androidx.room.Database;
import androidx.room.RoomDatabase;
import androidx.room.migration.Migration;
import androidx.sqlite.db.SupportSQLiteDatabase;

import com.solarized.firedown.data.dao.WebHistoryDao;
import com.solarized.firedown.data.entity.WebHistoryEntity;

@Database(entities = {WebHistoryEntity.class}, version = 5, exportSchema = false)
public abstract class WebHistoryDatabase extends RoomDatabase {

    public static final String DATABASE_NAME = "webhistory-db";

    public abstract WebHistoryDao webHistoryDao();

    // ── Full-text search for the URL-bar autocomplete ────────────────────────
    //
    // The dropdown's history lookup used `file_url LIKE '%term%' OR file_title
    // LIKE '%term%'` — a LEADING-wildcard infix scan that CANNOT use the
    // file_url index (and file_title has none), so it was a full table scan on
    // every keystroke, over a table kept INDEFINITELY (it only grows). This
    // backs that lookup with an FTS4 index instead (see
    // WebHistoryDataRepository.getAutoCompleteSearch).
    //
    // Deliberately NOT modeled as a Room @Entity / @Fts4: doing so makes Room
    // generate (and validate) the content-sync triggers, whose exact DDL is
    // fiddly to reproduce in a migration. As a plain, Room-unaware virtual table
    // it's invisible to Room's schema identity check (the entity set is
    // unchanged, so the identity hash is too — only the version bump drives the
    // migration), and it's queried via @RawQuery (no compile-time SQL
    // verification against the modeled schema). We own the sync triggers below.
    //
    // The triggers keep webhistory_fts in lockstep with webhistory, keyed on
    // docid = webhistory.uid. The AFTER INSERT trigger DELETEs any stale FTS row
    // first so it's idempotent even when an `INSERT OR REPLACE` (the @Insert
    // REPLACE path) replaces a webhistory row WITHOUT firing the delete trigger
    // (SQLite skips REPLACE-induced delete triggers unless recursive_triggers is
    // on, which it is not by default) — otherwise the re-insert would hit a
    // duplicate-docid constraint and fail the history insert.
    private static final String CREATE_FTS =
            "CREATE VIRTUAL TABLE IF NOT EXISTS `webhistory_fts` "
                    + "USING fts4(`file_url`, `file_title`)";
    private static final String POPULATE_FTS =
            "INSERT INTO `webhistory_fts`(`docid`, `file_url`, `file_title`) "
                    + "SELECT `uid`, `file_url`, `file_title` FROM `webhistory`";
    private static final String TRIGGER_AI =
            "CREATE TRIGGER IF NOT EXISTS `webhistory_fts_ai` AFTER INSERT ON `webhistory` BEGIN "
                    + "DELETE FROM `webhistory_fts` WHERE `docid` = new.`uid`; "
                    + "INSERT INTO `webhistory_fts`(`docid`, `file_url`, `file_title`) "
                    + "VALUES (new.`uid`, new.`file_url`, new.`file_title`); END";
    private static final String TRIGGER_AD =
            "CREATE TRIGGER IF NOT EXISTS `webhistory_fts_ad` AFTER DELETE ON `webhistory` BEGIN "
                    + "DELETE FROM `webhistory_fts` WHERE `docid` = old.`uid`; END";
    private static final String TRIGGER_AU =
            "CREATE TRIGGER IF NOT EXISTS `webhistory_fts_au` AFTER UPDATE ON `webhistory` BEGIN "
                    + "DELETE FROM `webhistory_fts` WHERE `docid` = old.`uid`; "
                    + "INSERT INTO `webhistory_fts`(`docid`, `file_url`, `file_title`) "
                    + "VALUES (new.`uid`, new.`file_url`, new.`file_title`); END";

    private static void createFtsObjects(SupportSQLiteDatabase db, boolean populate) {
        db.execSQL(CREATE_FTS);
        if (populate) {
            // Backfill from the existing rows (migration only — a fresh DB is empty).
            db.execSQL(POPULATE_FTS);
        }
        db.execSQL(TRIGGER_AI);
        db.execSQL(TRIGGER_AD);
        db.execSQL(TRIGGER_AU);
    }

    // Fresh installs create the DB at the current version via Room's
    // createAllTables (entities only) — which does NOT know about the unmodeled
    // FTS table — so the table + triggers must be created here too, or the
    // @RawQuery would hit "no such table: webhistory_fts" on a clean install.
    // Migrations cover the upgrade path; this covers the fresh-create path.
    public static final Callback FTS_CALLBACK = new Callback() {
        @Override
        public void onCreate(SupportSQLiteDatabase db) {
            super.onCreate(db);
            createFtsObjects(db, false);
        }
    };

    public static final Migration MIGRATION_1_2 = new Migration(1, 2) {
        @Override
        public void migrate(SupportSQLiteDatabase database) {
            database.execSQL("ALTER TABLE 'webhistory' ADD COLUMN 'file_icon' TEXT DEFAULT NULL");
        }
    };

    public static final Migration MIGRATION_2_3 = new Migration(2, 3) {
        @Override
        public void migrate(SupportSQLiteDatabase database) {
            database.execSQL("ALTER TABLE 'webhistory' ADD COLUMN 'file_icon_resolution' INTEGER NOT NULL DEFAULT 0");
        }
    };

    // Index file_url so the url-keyed favicon/title updates (updateIconData /
    // updateTitleByUrl) seek instead of scanning the table. The DDL matches the
    // exact form Room generates for @Index(value={"file_url"}) — same proven
    // pattern as DownloadDatabase.MIGRATION_10_11 — so the post-migration schema
    // validates against the entity (exportSchema=false still checks the identity
    // hash at open).
    public static final Migration MIGRATION_3_4 = new Migration(3, 4) {
        @Override
        public void migrate(SupportSQLiteDatabase database) {
            database.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_webhistory_file_url` "
                            + "ON `webhistory` (`file_url`)");
        }
    };

    // Build the FTS index + sync triggers and backfill it from existing rows.
    // See the CREATE_FTS / TRIGGER_* block above for why this is a plain virtual
    // table rather than a Room @Fts4 entity.
    public static final Migration MIGRATION_4_5 = new Migration(4, 5) {
        @Override
        public void migrate(SupportSQLiteDatabase database) {
            createFtsObjects(database, true);
        }
    };
}
