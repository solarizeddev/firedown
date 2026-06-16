package com.solarized.firedown.data;

import android.content.ContentValues;
import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.net.Uri;
import android.provider.DocumentsContract;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.Log;
import android.util.Pair;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.sqlite.db.SupportSQLiteDatabase;

import com.solarized.firedown.BuildConfig;
import com.solarized.firedown.StoragePaths;

import java.io.File;
import androidx.preference.PreferenceManager;
import java.util.UUID;
import java.io.IOException;
import java.io.FileOutputStream;
import java.io.FileInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Set;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * Sanitized backup mirror of the download database for Android Auto Backup.
 *
 * <p><b>Why a mirror and not the database itself:</b> the {@code download-db}
 * file holds BOTH the public Downloads rows and the safe-vault rows
 * ({@code file_safe = 1} — names, origin URLs, file paths of vaulted items).
 * Auto Backup is file-granular, so backing up the database file would ship
 * vault metadata to the cloud. Instead, every time the app goes to the
 * background we re-write {@code filesDir/backup/downloads-mirror.db}
 * containing ONLY the finished, non-safe rows, and the backup rules
 * ({@code backup_rules.xml} / {@code data_extraction.xml}) include exactly
 * that one file — never the real database, never the vault.
 *
 * <p><b>Restore:</b> on a fresh install restored from backup, the mirror file
 * reappears while the real database starts empty. {@link #restoreIfPending}
 * copies the mirror rows back into the live table — once per install, guarded
 * three ways: a marker in {@code backup_local.xml} (a prefs file EXCLUDED
 * from backup, so it never travels with a restore), an empty-table check (an
 * in-place update keeps its rows and must never re-import), and the mirror's
 * existence. Rows are copied by column-name intersection so a schema a
 * version ahead/behind degrades gracefully instead of failing the whole
 * restore; {@code uid} is dropped (autoincrement re-assigns) and
 * {@code file_safe} is forced to 0 defensively.
 *
 * <p>The restored entries point at the surviving public
 * {@code Download/Firedown} files. Note the scoped-storage caveat: on
 * Android 13+ a reinstalled app no longer OWNS those files, so playback may
 * need a permission grant even though the entries are listed — the metadata
 * (origin, title, duration) is preserved regardless, which the files alone
 * could never give back.
 */
public final class DownloadBackupMirror {

    private static final String TAG = DownloadBackupMirror.class.getSimpleName();

    /** Keep in sync with backup_rules.xml and data_extraction.xml. */
    private static final String MIRROR_DIR = "backup";
    private static final String MIRROR_FILE = "downloads-mirror.db";

    /** Prefs file EXCLUDED from backup — install-local state only. */
    private static final String LOCAL_PREFS = "backup_local";
    private static final String KEY_RESTORE_DONE = "mirror_restore_done";

    private static final String TABLE = "download";

    private DownloadBackupMirror() {
        // Static utility.
    }

    private static File mirrorFile(@NonNull Context context) {
        return new File(new File(context.getFilesDir(), MIRROR_DIR), MIRROR_FILE);
    }

    /**
     * Re-write the mirror from the live database. Call on a background thread
     * whenever the app goes to the background (the same trigger Auto Backup
     * keys off). Cheap: one CREATE TABLE AS SELECT over the indexed
     * {@code file_safe} partition.
     *
     * <p>Only FINISHED ({@code file_status = 1}) non-safe rows are mirrored:
     * in-flight/queued rows are dead after a reinstall (their runnables and
     * temp state died with the process), and vault rows must never leave the
     * device.
     */
    public static void writeMirror(@NonNull Context context, @NonNull DownloadDatabase database) {
        File mirror = mirrorFile(context);
        File dir = mirror.getParentFile();
        if (dir != null && !dir.exists() && !dir.mkdirs()) {
            Log.w(TAG, "writeMirror: cannot create " + dir);
            return;
        }
        SupportSQLiteDatabase db = database.getOpenHelper().getWritableDatabase();
        try {
            db.execSQL("ATTACH DATABASE ? AS mirror", new Object[]{mirror.getAbsolutePath()});
        } catch (Exception e) {
            Log.e(TAG, "writeMirror: attach failed", e);
            return;
        }
        long mirrorRows = 0;
        try {
            db.execSQL("DROP TABLE IF EXISTS mirror." + TABLE);
            db.execSQL("CREATE TABLE mirror." + TABLE + " AS SELECT * FROM " + TABLE
                    + " WHERE file_safe = 0 AND file_status = 1");
            try (Cursor count = db.query("SELECT COUNT(*) FROM mirror." + TABLE)) {
                if (count.moveToFirst()) {
                    mirrorRows = count.getLong(0);
                }
            }
            if (BuildConfig.DEBUG) {
                Log.d(TAG, "writeMirror: mirrored " + mirrorRows + " rows to " + mirror.getName());
            }
        } catch (Exception e) {
            Log.e(TAG, "writeMirror: copy failed", e);
        } finally {
            try {
                db.execSQL("DETACH DATABASE mirror");
            } catch (Exception e) {
                Log.e(TAG, "writeMirror: detach failed", e);
            }
        }

        // Never PUBLISH an empty backup. A fresh install (the reinstall
        // window, before the user runs the SAF restore) has an empty database,
        // so this would otherwise write an empty public mirror with a NEWER
        // timestamp than the real backup left by the previous install — and
        // since restoreFromTree takes the newest decryptable mirror, the user
        // would restore that empty one and see "0 entries". The filesDir
        // mirror (Auto Backup) is still recreated above; only the public
        // .fdbk write is gated, so a genuinely-empty backup never clobbers /
        // out-dates a real one. (restoreFromTree also now tries every
        // candidate, not just the newest — belt and suspenders.)
        if (mirrorRows > 0) {
            writeEncryptedPublicMirror(context, mirror);
        }
    }

    // ------------------------------------------------------------------
    // Encrypted public copy — the transport-free recovery path
    // ------------------------------------------------------------------
    //
    // The filesDir mirror above rides Android Auto Backup, which needs a
    // backup TRANSPORT (Google's on stock devices, Seedvault on de-Googled
    // ROMs). For devices with neither, a second, ENCRYPTED copy of the same
    // mirror is written into the public download folder, which survives
    // uninstall exactly like the media files do; a post-reinstall SAF folder
    // grant lets the new install read and import it.
    //
    // Encryption: AES-256-GCM with a key derived from ANDROID_ID (SSAID).
    // SSAID is scoped per (app signing key, device, user) since Android 8:
    // it SURVIVES uninstall/reinstall of the same-signed APK, and every
    // OTHER app sees a different value — so a file manager or another app
    // that reads the public file cannot derive the key, and nothing secret
    // is embedded in the APK (the key is device-bound, not in the code).
    // Consequence, by design: the file is only decryptable by Firedown on
    // the SAME device — cross-device migration is the backup transport's
    // job, not this file's. A factory reset or signing-key change also
    // rotates SSAID and orphans old mirrors (restore just skips what it
    // cannot decrypt).

    /** Public mirror format: MAGIC | 12-byte GCM IV | ciphertext. */
    private static final byte[] PUBLIC_MAGIC = {'F', 'D', 'B', 'K', '1'};
    private static final String PUBLIC_DIR = "backup";
    private static final String PUBLIC_FILE = "downloads-mirror.fdbk";
    private static final int GCM_IV_BYTES = 12;
    private static final int GCM_TAG_BITS = 128;
    private static final String KEY_CONTEXT = "firedown-mirror-v1:";

    private static SecretKeySpec deriveKey(@NonNull Context context) throws Exception {
        String ssaid = Settings.Secure.getString(
                context.getContentResolver(), Settings.Secure.ANDROID_ID);
        if (TextUtils.isEmpty(ssaid)) {
            throw new IllegalStateException("no ANDROID_ID");
        }
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        byte[] key = digest.digest((KEY_CONTEXT + ssaid).getBytes(StandardCharsets.UTF_8));
        return new SecretKeySpec(key, "AES");
    }

    private static void writeEncryptedPublicMirror(@NonNull Context context, @NonNull File plainMirror) {
        if (!plainMirror.exists()) {
            return;
        }
        File dir = new File(StoragePaths.getDownloadPath(context), PUBLIC_DIR);
        if (!dir.exists() && !dir.mkdirs()) {
            // Public storage unavailable/unwritable — Auto Backup still has
            // the private mirror; nothing else to do.
            if (BuildConfig.DEBUG) {
                Log.d(TAG, "public mirror: cannot create " + dir);
            }
            return;
        }
        byte[] plain;
        try {
            plain = readAllBytes(plainMirror);
        } catch (Exception e) {
            Log.e(TAG, "public mirror: read failed", e);
            return;
        }
        byte[] iv = new byte[GCM_IV_BYTES];
        new SecureRandom().nextBytes(iv);
        byte[] cipherText;
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, deriveKey(context),
                    new GCMParameterSpec(GCM_TAG_BITS, iv));
            cipherText = cipher.doFinal(plain);
        } catch (Exception e) {
            Log.e(TAG, "public mirror: encrypt failed", e);
            return;
        }
        // Fixed name first. After a reinstall the previous install's file at
        // this path is foreign-owned (invisible but name-colliding on
        // Android 11+), so a failed open falls back to a timestamped name —
        // the restore side scans for every *.fdbk and takes the newest it
        // can decrypt.
        File out = new File(dir, PUBLIC_FILE);
        try {
            writeMirrorBytes(out, iv, cipherText);
        } catch (Exception first) {
            out = new File(dir, "downloads-mirror-" + System.currentTimeMillis() + ".fdbk");
            try {
                writeMirrorBytes(out, iv, cipherText);
            } catch (Exception second) {
                Log.e(TAG, "public mirror: write failed", second);
                return;
            }
        }
        if (BuildConfig.DEBUG) {
            Log.d(TAG, "public mirror: wrote " + out.getName() + " (" + cipherText.length + " bytes)");
        }
    }

    private static void writeMirrorBytes(@NonNull File out, @NonNull byte[] iv, @NonNull byte[] cipherText)
            throws IOException {
        try (FileOutputStream fos = new FileOutputStream(out, false)) {
            fos.write(PUBLIC_MAGIC);
            fos.write(iv);
            fos.write(cipherText);
            fos.flush();
        }
    }

    private static byte[] readAllBytes(@NonNull File file) throws IOException {
        long length = file.length();
        if (length <= 0 || length > 64L * 1024 * 1024) {
            throw new IOException("implausible mirror size: " + length);
        }
        byte[] buf = new byte[(int) length];
        try (FileInputStream fis = new FileInputStream(file)) {
            int off = 0;
            while (off < buf.length) {
                int n = fis.read(buf, off, buf.length - off);
                if (n < 0) {
                    throw new IOException("short read at " + off);
                }
                off += n;
            }
        }
        return buf;
    }

    /** {@link #restoreFromTree} result: no {@code .fdbk} found in the tree. */
    public static final int RESTORE_NO_BACKUP = -1;
    /** {@link #restoreFromTree} result: mirror(s) found, none decryptable —
     *  written by a different device or signing identity. */
    public static final int RESTORE_WRONG_DEVICE = -2;

    /** Install-local record of the SAF tree the user granted for restore —
     *  kept for the future content-URI playback fallback on Android 13+. */
    private static final String KEY_RESTORE_TREE = "restore_tree_uri";

    public static void rememberRestoreTree(@NonNull Context context, @NonNull Uri treeUri) {
        context.getSharedPreferences(LOCAL_PREFS, Context.MODE_PRIVATE)
                .edit().putString(KEY_RESTORE_TREE, treeUri.toString()).apply();
    }

    /** The persisted SAF tree grant from the last restore (the
     *  {@code Download/Firedown} folder the user picked), or {@code null} if
     *  none. {@link RestoredFileAccess} uses it to open foreign-owned restored
     *  files this install can't read by path. */
    @Nullable
    public static Uri getRestoreTree(@NonNull Context context) {
        String stored = context.getSharedPreferences(LOCAL_PREFS, Context.MODE_PRIVATE)
                .getString(KEY_RESTORE_TREE, null);
        return TextUtils.isEmpty(stored) ? null : Uri.parse(stored);
    }

    /**
     * SAF restore: scan a user-granted document tree (normally
     * {@code Download/Firedown}) for encrypted public mirrors and import the
     * newest decryptable one. Call on a background thread.
     *
     * <p>Looks for {@code *.fdbk} both directly in the picked folder and in
     * its {@code backup/} child (covering a user who picked either level), and
     * imports EVERY one it can decrypt (not just the newest — see the loop
     * comment for the reinstall-window empty-mirror reason), summing the rows.
     * Returns the number of rows imported (0 is a legitimate result: everything
     * already present), {@link #RESTORE_NO_BACKUP}, or
     * {@link #RESTORE_WRONG_DEVICE}.
     */
    public static int restoreFromTree(@NonNull Context context,
                                      @NonNull DownloadDatabase database,
                                      @NonNull Uri treeUri) {
        ArrayList<Pair<Uri, Long>> candidates = new ArrayList<>();
        try {
            String rootDocId = DocumentsContract.getTreeDocumentId(treeUri);
            collectFdbkCandidates(context, treeUri, rootDocId, candidates, true);
        } catch (Exception e) {
            Log.e(TAG, "restoreFromTree: tree scan failed", e);
        }
        if (candidates.isEmpty()) {
            return RESTORE_NO_BACKUP;
        }
        candidates.sort((a, b) -> Long.compare(b.second, a.second));

        File plain = new File(context.getCacheDir(), "restore-mirror-" + System.currentTimeMillis() + ".db");
        boolean anyDecrypted = false;
        int totalRestored = 0;
        try {
            // Try EVERY decryptable mirror, not just the newest. After a
            // reinstall the app's first background re-writes a public mirror
            // from the (still-empty) fresh database, so the NEWEST .fdbk in the
            // folder can be an EMPTY one that decrypts fine and imports 0 rows
            // — the real backup is an OLDER file. Bailing on the first
            // decryptable candidate would import that empty mirror and report
            // "0 restored" while the actual data sits one file back. So we
            // decrypt + import all of them and SUM: importMirrorDatabase dedups
            // by file_path against the (growing) live table, so overlapping
            // mirrors contribute each row once and re-imports add 0.
            for (Pair<Uri, Long> candidate : candidates) {
                try (InputStream in = context.getContentResolver().openInputStream(candidate.first)) {
                    if (in == null) {
                        continue;
                    }
                    if (decryptPublicMirror(context, in, plain)) {
                        anyDecrypted = true;
                        totalRestored += importMirrorDatabase(database, plain);
                    }
                } catch (Exception e) {
                    if (BuildConfig.DEBUG) {
                        Log.d(TAG, "restoreFromTree: candidate failed", e);
                    }
                }
            }
        } finally {
            if (plain.exists() && !plain.delete()) {
                Log.w(TAG, "restoreFromTree: temp mirror not deleted");
            }
        }
        // No candidate decrypted at all → written by a different device/identity.
        if (!anyDecrypted) {
            return RESTORE_WRONG_DEVICE;
        }
        Log.i(TAG, "restoreFromTree: restored " + totalRestored + " entries from SAF mirror");
        // Any completed restore retires the reinstall banner, whichever door
        // (empty state / Settings / banner) launched the flow.
        clearRestoreBanner(context);
        return totalRestored;
    }

    private static void collectFdbkCandidates(@NonNull Context context,
                                              @NonNull Uri treeUri,
                                              @NonNull String parentDocId,
                                              @NonNull ArrayList<Pair<Uri, Long>> out,
                                              boolean recurseIntoBackupDir) {
        Uri children = DocumentsContract
                .buildChildDocumentsUriUsingTree(treeUri, parentDocId);
        String[] projection = {
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE,
                DocumentsContract.Document.COLUMN_LAST_MODIFIED,
        };
        try (Cursor cursor = context.getContentResolver().query(children, projection, null, null, null)) {
            if (cursor == null) {
                return;
            }
            while (cursor.moveToNext()) {
                String docId = cursor.getString(0);
                String name = cursor.getString(1);
                String mime = cursor.getString(2);
                long modified = cursor.isNull(3) ? 0L : cursor.getLong(3);
                if (DocumentsContract.Document.MIME_TYPE_DIR.equals(mime)) {
                    if (recurseIntoBackupDir && PUBLIC_DIR.equals(name)) {
                        collectFdbkCandidates(context, treeUri, docId, out, false);
                    }
                } else if (name != null && name.endsWith(".fdbk")) {
                    out.add(new Pair<>(
                            DocumentsContract.buildDocumentUriUsingTree(treeUri, docId),
                            modified));
                }
            }
        } catch (Exception e) {
            if (BuildConfig.DEBUG) {
                Log.d(TAG, "collectFdbkCandidates: query failed for " + parentDocId, e);
            }
        }
    }

    /**
     * Decrypt an encrypted public mirror (a {@code .fdbk} stream read through
     * a SAF grant after reinstall) back into a plain SQLite file. Returns
     * false — without logging secrets — when the payload isn't ours or was
     * written by a different device/signing identity (SSAID mismatch makes
     * GCM authentication fail). The SAF restore flow feeds the result to
     * {@link #importMirrorDatabase}.
     */
    public static boolean decryptPublicMirror(@NonNull Context context,
                                              @NonNull InputStream in,
                                              @NonNull File outPlain) {
        try {
            ByteArrayOutputStream all = new ByteArrayOutputStream();
            byte[] chunk = new byte[8192];
            int n;
            int total = 0;
            while ((n = in.read(chunk)) > 0) {
                total += n;
                if (total > 64 * 1024 * 1024) {
                    return false;
                }
                all.write(chunk, 0, n);
            }
            byte[] blob = all.toByteArray();
            int headerLen = PUBLIC_MAGIC.length + GCM_IV_BYTES;
            if (blob.length <= headerLen) {
                return false;
            }
            for (int i = 0; i < PUBLIC_MAGIC.length; i++) {
                if (blob[i] != PUBLIC_MAGIC[i]) {
                    return false;
                }
            }
            GCMParameterSpec spec = new GCMParameterSpec(
                    GCM_TAG_BITS, blob, PUBLIC_MAGIC.length, GCM_IV_BYTES);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, deriveKey(context), spec);
            byte[] plain = cipher.doFinal(blob, headerLen, blob.length - headerLen);
            try (FileOutputStream fos = new FileOutputStream(outPlain, false)) {
                fos.write(plain);
                fos.flush();
            }
            return true;
        } catch (Exception e) {
            if (BuildConfig.DEBUG) {
                Log.d(TAG, "decryptPublicMirror: rejected", e);
            }
            return false;
        }
    }

    /**
     * One-shot restore of mirrored rows into an empty download table. Call on
     * a background thread at app startup. No-op unless ALL of: the
     * install-local marker is absent (fresh install — the marker file is
     * excluded from backup), the live table has no non-safe rows (a fresh
     * database, not an in-place update), and a mirror file exists (i.e. a
     * backup restore actually delivered one).
     */
    public static void restoreIfPending(@NonNull Context context, @NonNull DownloadDatabase database) {
        SharedPreferences prefs = context.getSharedPreferences(LOCAL_PREFS, Context.MODE_PRIVATE);
        if (prefs.getBoolean(KEY_RESTORE_DONE, false)) {
            return;
        }
        // Whatever happens below, never attempt again on this install — a
        // failed half-restore retried against a now-populated table would
        // duplicate rows.
        prefs.edit().putBoolean(KEY_RESTORE_DONE, true).apply();

        boolean reinstall = detectReinstall(context, prefs);

        int restored = 0;
        File mirror = mirrorFile(context);
        if (mirror.exists()) {
            SupportSQLiteDatabase db = database.getOpenHelper().getWritableDatabase();
            boolean populated = false;
            try (Cursor count = db.query("SELECT COUNT(*) FROM " + TABLE + " WHERE file_safe = 0")) {
                populated = count.moveToFirst() && count.getInt(0) > 0;
            }
            if (populated) {
                if (BuildConfig.DEBUG) {
                    Log.d(TAG, "restoreIfPending: table populated — in-place update, skipping");
                }
                return;
            }
            restored = importMirrorDatabase(database, mirror);
            Log.i(TAG, "restoreIfPending: restored " + restored + " download entries from backup mirror");
        }

        // Detected reinstall whose automatic restore brought nothing back
        // (no mirror in the backup, quota, partial restore): the encrypted
        // public mirror may still be sitting in Download/Firedown, so arm the
        // Downloads-screen banner pointing at the SAF restore. A reinstall
        // the auto-restore DID handle needs no banner.
        if (reinstall && restored == 0) {
            prefs.edit().putBoolean(KEY_RESTORE_BANNER, true).apply();
            Log.i(TAG, "restoreIfPending: reinstall detected, auto-restore empty — banner armed");
        }
    }

    // ------------------------------------------------------------------
    // Reinstall detection — the sentinel pair
    // ------------------------------------------------------------------
    //
    // "Did this install's shared prefs come back from a backup?" can't be
    // answered by "are the default prefs non-empty" — App.onCreate writes
    // boot-keys (e.g. the history-purge timestamp) before this runs, so a
    // fresh install is never empty. Instead a random sentinel UUID is kept
    // in BOTH prefs files: the DEFAULT prefs (which Auto Backup backs up)
    // and backup_local.xml (which is excluded). On a same-install launch the
    // two match. After a reinstall-with-restore the default-prefs sentinel
    // survives the trip through the backup while the local one died with the
    // install → present-but-mismatched = reinstall. Boot-written keys can't
    // fake that, and an in-place update keeps both files so it never trips.
    // (A backup written by a pre-sentinel app version has no sentinel →
    // undetected → no banner; acceptable roll-out behavior.)

    private static final String KEY_SENTINEL_LOCAL = "install_sentinel";
    /** Lives in the DEFAULT shared prefs — deliberately backed up. */
    private static final String KEY_SENTINEL_BACKED_UP =
            "com.solarized.firedown.preferences.backup.install.sentinel";
    private static final String KEY_RESTORE_BANNER = "restore_banner_pending";

    private static boolean detectReinstall(@NonNull Context context, @NonNull SharedPreferences localPrefs) {
        SharedPreferences defaultPrefs =
                PreferenceManager.getDefaultSharedPreferences(context);
        String backedUp = defaultPrefs.getString(KEY_SENTINEL_BACKED_UP, null);
        String local = localPrefs.getString(KEY_SENTINEL_LOCAL, null);

        boolean reinstall = backedUp != null && !backedUp.equals(local);

        // (Re)pair the sentinels for this install either way.
        String sentinel = UUID.randomUUID().toString();
        defaultPrefs.edit().putString(KEY_SENTINEL_BACKED_UP, sentinel).apply();
        localPrefs.edit().putString(KEY_SENTINEL_LOCAL, sentinel).apply();
        return reinstall;
    }

    /** Whether the Downloads screen should show the "restore your previous
     *  downloads" banner (reinstall detected, automatic restore came back
     *  empty, user hasn't dismissed or completed a restore yet). */
    public static boolean isRestoreBannerPending(@NonNull Context context) {
        return context.getSharedPreferences(LOCAL_PREFS, Context.MODE_PRIVATE)
                .getBoolean(KEY_RESTORE_BANNER, false);
    }

    /** Permanently retire the banner: user dismissed it, or a restore ran. */
    public static void clearRestoreBanner(@NonNull Context context) {
        context.getSharedPreferences(LOCAL_PREFS, Context.MODE_PRIVATE)
                .edit().putBoolean(KEY_RESTORE_BANNER, false).apply();
    }

    /**
     * Copy every row of a plain mirror SQLite file into the live download
     * table. Shared by the Auto Backup restore above and the SAF restore flow
     * (which first runs an encrypted public mirror through
     * {@link #decryptPublicMirror}). Returns the number of rows inserted.
     *
     * <p>Rows are copied by column-name intersection with the LIVE table —
     * the mirror may come from a different app version, and a missing/extra
     * column must degrade that row (or just that column), never the whole
     * import. {@code uid} is dropped (autoincrement re-assigns) and
     * {@code file_safe} is forced to 0 so no mirror, however obtained, can
     * inject entries into the vault list.
     */
    public static int importMirrorDatabase(@NonNull DownloadDatabase database, @NonNull File plainMirror) {
        SupportSQLiteDatabase db = database.getOpenHelper().getWritableDatabase();

        Set<String> liveColumns = new HashSet<>();
        try (Cursor info = db.query("PRAGMA table_info(" + TABLE + ")")) {
            int nameIdx = info.getColumnIndex("name");
            while (info.moveToNext()) {
                liveColumns.add(info.getString(nameIdx));
            }
        }

        // Dedup by file_path: the SAF restore button can run against a
        // NON-empty table (the user may have downloaded again before tapping
        // Restore, or tap twice) — a row whose path already exists must not
        // be duplicated. Makes the import idempotent.
        Set<String> existingPaths = new HashSet<>();
        try (Cursor paths = db.query("SELECT file_path FROM " + TABLE)) {
            while (paths.moveToNext()) {
                if (!paths.isNull(0)) {
                    existingPaths.add(paths.getString(0));
                }
            }
        }

        int restored = 0;
        SQLiteDatabase src = null;
        try {
            src = SQLiteDatabase.openDatabase(plainMirror.getAbsolutePath(), null, SQLiteDatabase.OPEN_READONLY);
            try (Cursor rows = src.rawQuery("SELECT * FROM " + TABLE, null)) {
                String[] cols = rows.getColumnNames();
                while (rows.moveToNext()) {
                    ContentValues values = new ContentValues();
                    for (int i = 0; i < cols.length; i++) {
                        String col = cols[i];
                        // uid: let autoincrement re-assign; unknown columns:
                        // not in this version's schema, drop.
                        if ("uid".equals(col) || !liveColumns.contains(col)) {
                            continue;
                        }
                        if (rows.isNull(i)) {
                            values.putNull(col);
                        } else if (rows.getType(i) == Cursor.FIELD_TYPE_INTEGER) {
                            values.put(col, rows.getLong(i));
                        } else if (rows.getType(i) == Cursor.FIELD_TYPE_FLOAT) {
                            values.put(col, rows.getDouble(i));
                        } else if (rows.getType(i) == Cursor.FIELD_TYPE_BLOB) {
                            values.put(col, rows.getBlob(i));
                        } else {
                            values.put(col, rows.getString(i));
                        }
                    }
                    // Defense in depth: the mirror is written without vault
                    // rows, but a tampered/foreign mirror must not be able to
                    // inject entries into the vault list.
                    values.put("file_safe", 0);
                    // A restored file is re-owned via the SAF grant — its
                    // thumbnail availability must be RE-DERIVED, never trusted
                    // from the mirror (which may predate the file or carry a
                    // stale negative-cache from a pre-grant access failure).
                    // Clear it so the Glide pipeline retries instead of
                    // short-circuiting to a mime icon forever.
                    if (liveColumns.contains("file_thumbnail_unavailable")) {
                        values.put("file_thumbnail_unavailable", 0);
                    }
                    String rowPath = values.getAsString("file_path");
                    if (TextUtils.isEmpty(rowPath) || existingPaths.contains(rowPath)) {
                        continue;
                    }
                    existingPaths.add(rowPath);
                    try {
                        db.insert(TABLE, SQLiteDatabase.CONFLICT_IGNORE, values);
                        restored++;
                    } catch (Exception e) {
                        // Per-row: a NOT NULL column added in a newer schema
                        // without a default fails that row, not the restore.
                        if (BuildConfig.DEBUG) {
                            Log.d(TAG, "importMirrorDatabase: row skipped", e);
                        }
                    }
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "importMirrorDatabase: failed after " + restored + " rows", e);
        } finally {
            if (src != null) {
                try {
                    src.close();
                } catch (Exception ignored) {
                }
            }
        }
        return restored;
    }
}
