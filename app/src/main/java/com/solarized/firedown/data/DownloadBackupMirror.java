package com.solarized.firedown.data;

import android.content.ContentValues;
import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.text.TextUtils;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.sqlite.db.SupportSQLiteDatabase;

import com.solarized.firedown.BuildConfig;

import java.io.File;
import java.util.HashSet;
import java.util.Set;

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
        try {
            db.execSQL("DROP TABLE IF EXISTS mirror." + TABLE);
            db.execSQL("CREATE TABLE mirror." + TABLE + " AS SELECT * FROM " + TABLE
                    + " WHERE file_safe = 0 AND file_status = 1");
            if (BuildConfig.DEBUG) {
                Log.d(TAG, "writeMirror: mirrored to " + mirror.getName());
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

        writeEncryptedPublicMirror(context, mirror);
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

    private static javax.crypto.spec.SecretKeySpec deriveKey(@NonNull Context context) throws Exception {
        String ssaid = android.provider.Settings.Secure.getString(
                context.getContentResolver(), android.provider.Settings.Secure.ANDROID_ID);
        if (TextUtils.isEmpty(ssaid)) {
            throw new IllegalStateException("no ANDROID_ID");
        }
        java.security.MessageDigest digest = java.security.MessageDigest.getInstance("SHA-256");
        byte[] key = digest.digest((KEY_CONTEXT + ssaid).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        return new javax.crypto.spec.SecretKeySpec(key, "AES");
    }

    private static void writeEncryptedPublicMirror(@NonNull Context context, @NonNull File plainMirror) {
        if (!plainMirror.exists()) {
            return;
        }
        File dir = new File(com.solarized.firedown.StoragePaths.getDownloadPath(context), PUBLIC_DIR);
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
        new java.security.SecureRandom().nextBytes(iv);
        byte[] cipherText;
        try {
            javax.crypto.Cipher cipher = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(javax.crypto.Cipher.ENCRYPT_MODE, deriveKey(context),
                    new javax.crypto.spec.GCMParameterSpec(GCM_TAG_BITS, iv));
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
            throws java.io.IOException {
        try (java.io.FileOutputStream fos = new java.io.FileOutputStream(out, false)) {
            fos.write(PUBLIC_MAGIC);
            fos.write(iv);
            fos.write(cipherText);
            fos.flush();
        }
    }

    private static byte[] readAllBytes(@NonNull File file) throws java.io.IOException {
        long length = file.length();
        if (length <= 0 || length > 64L * 1024 * 1024) {
            throw new java.io.IOException("implausible mirror size: " + length);
        }
        byte[] buf = new byte[(int) length];
        try (java.io.FileInputStream fis = new java.io.FileInputStream(file)) {
            int off = 0;
            while (off < buf.length) {
                int n = fis.read(buf, off, buf.length - off);
                if (n < 0) {
                    throw new java.io.IOException("short read at " + off);
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

    public static void rememberRestoreTree(@NonNull Context context, @NonNull android.net.Uri treeUri) {
        context.getSharedPreferences(LOCAL_PREFS, Context.MODE_PRIVATE)
                .edit().putString(KEY_RESTORE_TREE, treeUri.toString()).apply();
    }

    /**
     * SAF restore: scan a user-granted document tree (normally
     * {@code Download/Firedown}) for encrypted public mirrors and import the
     * newest decryptable one. Call on a background thread.
     *
     * <p>Looks for {@code *.fdbk} both directly in the picked folder and in
     * its {@code backup/} child (covering a user who picked either level),
     * newest-first by last-modified. Returns the number of rows imported
     * (0 is a legitimate result: everything already present),
     * {@link #RESTORE_NO_BACKUP}, or {@link #RESTORE_WRONG_DEVICE}.
     */
    public static int restoreFromTree(@NonNull Context context,
                                      @NonNull DownloadDatabase database,
                                      @NonNull android.net.Uri treeUri) {
        java.util.ArrayList<android.util.Pair<android.net.Uri, Long>> candidates = new java.util.ArrayList<>();
        try {
            String rootDocId = android.provider.DocumentsContract.getTreeDocumentId(treeUri);
            collectFdbkCandidates(context, treeUri, rootDocId, candidates, true);
        } catch (Exception e) {
            Log.e(TAG, "restoreFromTree: tree scan failed", e);
        }
        if (candidates.isEmpty()) {
            return RESTORE_NO_BACKUP;
        }
        candidates.sort((a, b) -> Long.compare(b.second, a.second));

        File plain = new File(context.getCacheDir(), "restore-mirror-" + System.currentTimeMillis() + ".db");
        try {
            for (android.util.Pair<android.net.Uri, Long> candidate : candidates) {
                try (java.io.InputStream in = context.getContentResolver().openInputStream(candidate.first)) {
                    if (in == null) {
                        continue;
                    }
                    if (decryptPublicMirror(context, in, plain)) {
                        int restored = importMirrorDatabase(database, plain);
                        Log.i(TAG, "restoreFromTree: restored " + restored + " entries from SAF mirror");
                        return restored;
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
        return RESTORE_WRONG_DEVICE;
    }

    private static void collectFdbkCandidates(@NonNull Context context,
                                              @NonNull android.net.Uri treeUri,
                                              @NonNull String parentDocId,
                                              @NonNull java.util.ArrayList<android.util.Pair<android.net.Uri, Long>> out,
                                              boolean recurseIntoBackupDir) {
        android.net.Uri children = android.provider.DocumentsContract
                .buildChildDocumentsUriUsingTree(treeUri, parentDocId);
        String[] projection = {
                android.provider.DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                android.provider.DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                android.provider.DocumentsContract.Document.COLUMN_MIME_TYPE,
                android.provider.DocumentsContract.Document.COLUMN_LAST_MODIFIED,
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
                if (android.provider.DocumentsContract.Document.MIME_TYPE_DIR.equals(mime)) {
                    if (recurseIntoBackupDir && PUBLIC_DIR.equals(name)) {
                        collectFdbkCandidates(context, treeUri, docId, out, false);
                    }
                } else if (name != null && name.endsWith(".fdbk")) {
                    out.add(new android.util.Pair<>(
                            android.provider.DocumentsContract.buildDocumentUriUsingTree(treeUri, docId),
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
                                              @NonNull java.io.InputStream in,
                                              @NonNull File outPlain) {
        try {
            java.io.ByteArrayOutputStream all = new java.io.ByteArrayOutputStream();
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
            javax.crypto.spec.GCMParameterSpec spec = new javax.crypto.spec.GCMParameterSpec(
                    GCM_TAG_BITS, blob, PUBLIC_MAGIC.length, GCM_IV_BYTES);
            javax.crypto.Cipher cipher = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(javax.crypto.Cipher.DECRYPT_MODE, deriveKey(context), spec);
            byte[] plain = cipher.doFinal(blob, headerLen, blob.length - headerLen);
            try (java.io.FileOutputStream fos = new java.io.FileOutputStream(outPlain, false)) {
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

        File mirror = mirrorFile(context);
        if (!mirror.exists()) {
            return;
        }

        SupportSQLiteDatabase db = database.getOpenHelper().getWritableDatabase();
        try (Cursor count = db.query("SELECT COUNT(*) FROM " + TABLE + " WHERE file_safe = 0")) {
            if (count.moveToFirst() && count.getInt(0) > 0) {
                if (BuildConfig.DEBUG) {
                    Log.d(TAG, "restoreIfPending: table populated — in-place update, skipping");
                }
                return;
            }
        }

        int restored = importMirrorDatabase(database, mirror);
        Log.i(TAG, "restoreIfPending: restored " + restored + " download entries from backup mirror");
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
