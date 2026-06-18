package com.solarized.firedown.data;

import android.content.Context;
import android.net.Uri;
import android.os.Environment;
import android.os.ParcelFileDescriptor;
import android.provider.DocumentsContract;
import android.text.TextUtils;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.solarized.firedown.BuildConfig;

import java.io.File;
import java.io.FileNotFoundException;

/**
 * Read access to RESTORED download files that this install does NOT own.
 *
 * <p>After a reinstall-with-restore the rows point at public
 * {@code Download/Firedown} files created by the PREVIOUS install. On Android
 * 11+ scoped storage the reinstalled app no longer OWNS them, so a direct
 * {@code File}/{@code FileProvider} open fails with {@code EACCES} — the
 * symptom is restored entries with no thumbnail that play nothing. The SAF
 * restore flow already persisted a tree grant on {@code Download/Firedown}
 * ({@link DownloadBackupMirror#rememberRestoreTree}); this maps an absolute
 * file path to a {@link DocumentsContract} child URI under that grant, which
 * the ContentResolver CAN open (the grant is persisted — no new prompt).
 *
 * <p>Always try the direct file FIRST: everything downloaded by THIS install
 * is owned, opens faster, and needs no grant — the SAF route is the fallback
 * only for the foreign, restored files. So this is a no-cost passthrough on
 * the common path and degrades to "as before" when there is no grant.
 */
public final class RestoredFileAccess {

    private static final String TAG = RestoredFileAccess.class.getSimpleName();

    private RestoredFileAccess() {
        // Static utility.
    }

    /**
     * An openable URI for a download's absolute path: a {@code file://} URI
     * when this install can read the file directly, otherwise the
     * {@code content://} SAF document URI under the persisted restore grant,
     * or {@code null} when neither is available. The {@code content://} form
     * is openable via {@code ContentResolver} and by media3's
     * {@code DefaultDataSource}.
     */
    @Nullable
    public static Uri openableUri(@NonNull Context context, @Nullable String absPath) {
        if (TextUtils.isEmpty(absPath)) {
            return null;
        }
        File file = new File(absPath);
        if (file.canRead()) {
            return Uri.fromFile(file);
        }
        return safDocumentUri(context, absPath);
    }

    /**
     * Open a download's file read-only: the direct file when owned, else the
     * persisted SAF grant. Returns {@code null} when neither works (the caller
     * treats that as a missing file). The caller owns the returned descriptor.
     */
    @Nullable
    public static ParcelFileDescriptor openReadOnly(@NonNull Context context, @Nullable String absPath) {
        if (TextUtils.isEmpty(absPath)) {
            return null;
        }
        File file = new File(absPath);
        if (file.canRead()) {
            try {
                return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY);
            } catch (Exception e) {
                // Owned-path open failed unexpectedly — fall through to SAF.
            }
        }
        Uri saf = safDocumentUri(context, absPath);
        if (saf == null) {
            return null;
        }
        try {
            return context.getContentResolver().openFileDescriptor(saf, "r");
        } catch (Exception e) {
            if (BuildConfig.DEBUG) {
                Log.d(TAG, "openReadOnly: SAF open failed", e);
            }
            return null;
        }
    }

    /**
     * The byte length of a download's file: {@code File.length()} when owned,
     * else the SAF descriptor's stat size for a foreign-owned restored file,
     * or {@code 0} when neither is available.
     */
    public static long length(@NonNull Context context, @Nullable String absPath) {
        if (TextUtils.isEmpty(absPath)) {
            return 0;
        }
        File file = new File(absPath);
        if (file.canRead()) {
            return file.length();
        }
        try (ParcelFileDescriptor pfd = openReadOnly(context, absPath)) {
            if (pfd != null) {
                long size = pfd.getStatSize();
                return size > 0 ? size : 0;
            }
        } catch (Exception e) {
            if (BuildConfig.DEBUG) {
                Log.d(TAG, "length: failed", e);
            }
        }
        return 0;
    }

    /**
     * Map an absolute path under primary shared storage to a child document
     * URI of the persisted restore tree grant, or {@code null} when there is
     * no grant or the path isn't under it. The externalstorage provider maps a
     * tree doc id like {@code primary:Download/Firedown} to
     * {@code <externalStorageDir>/Download/Firedown}, so the child doc id is
     * the tree's own doc id plus the path tail relative to that directory.
     */
    @Nullable
    public static Uri safDocumentUri(@NonNull Context context, @Nullable String absPath) {
        if (TextUtils.isEmpty(absPath)) {
            return null;
        }
        Uri tree = DownloadBackupMirror.getRestoreTree(context);
        if (tree == null) {
            return null;
        }
        String treeDocId;
        try {
            treeDocId = DocumentsContract.getTreeDocumentId(tree);
        } catch (Exception e) {
            return null;
        }
        int colon = treeDocId.indexOf(':');
        if (colon < 0) {
            return null;
        }
        File externalRoot = Environment.getExternalStorageDirectory();
        if (externalRoot == null) {
            return null;
        }
        // The directory the tree grant denotes, e.g.
        // /storage/emulated/0/Download/Firedown for "primary:Download/Firedown".
        String treeRelative = treeDocId.substring(colon + 1);
        String rootPath = externalRoot.getAbsolutePath();
        String treeDir = TextUtils.isEmpty(treeRelative) ? rootPath : rootPath + "/" + treeRelative;
        String prefix = treeDir + "/";
        if (!absPath.startsWith(prefix)) {
            return null;
        }
        String tail = absPath.substring(prefix.length());
        String childDocId = treeDocId + "/" + tail;
        try {
            return DocumentsContract.buildDocumentUriUsingTree(tree, childDocId);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * True if {@code absPath} lives under primary shared storage (e.g.
     * {@code /storage/emulated/0/...}). A file there that this install can't
     * delete with {@code File.delete()} is a foreign-owned restored file
     * (Android 11+ scoped storage) — removable only through a SAF tree WRITE
     * grant. App-private vault paths ({@code filesDir}) are never under this
     * root, so they never take the grant path.
     */
    public static boolean isSharedStoragePath(@Nullable String absPath) {
        if (TextUtils.isEmpty(absPath)) {
            return false;
        }
        File externalRoot = Environment.getExternalStorageDirectory();
        if (externalRoot == null) {
            return false;
        }
        return absPath.startsWith(externalRoot.getAbsolutePath() + "/");
    }

    /**
     * True ONLY when a download's file is VERIFIABLY gone: the path sits under
     * the persisted SAF restore grant but the document doesn't exist (the user
     * deleted it). Returns false when the file is present, when there's no
     * grant, or when the path isn't under the grant tree — i.e. whenever we
     * CANNOT prove the file is missing. A foreign file's absence is otherwise
     * untrustworthy (the File API can't see it without the grant — the same
     * caveat the MediaListenerWorker missing-file sweep is built around), so
     * "can't tell" must mean "keep it", never "drop it". Used by the mirror
     * import to avoid resurrecting a row whose file was already deleted.
     */
    public static boolean isRestoredFileMissing(@NonNull Context context, @Nullable String absPath) {
        if (TextUtils.isEmpty(absPath)) {
            return false;
        }
        if (new File(absPath).exists()) {
            return false; // owned and present
        }
        Uri saf = safDocumentUri(context, absPath);
        if (saf == null) {
            return false; // no grant / outside the tree → can't verify
        }
        // Probe existence by OPENING the document, NOT by querying its metadata.
        // ExternalStorageProvider.queryDocument builds a cursor row from the
        // doc-id PATH without confirming the file is on disk, so a deleted file
        // still returns a row (looks present); opening the document forces the
        // provider to touch the real file. A deleted file surfaces as a missing
        // file from the provider — but NOT always as a top-level
        // FileNotFoundException: ExternalStorageProvider verifies the tree-child
        // relationship first and rethrows the FileNotFoundException wrapped in an
        // IllegalArgumentException ("Failed to determine if X is child of Y:
        // java.io.FileNotFoundException: Missing file ..."). So we scan the whole
        // throwable chain/message for that missing-file signal (treat = gone) and
        // keep the row on any OTHER failure (conservative — never drop on a
        // transient/permission error).
        try (ParcelFileDescriptor pfd =
                     context.getContentResolver().openFileDescriptor(saf, "r")) {
            return pfd == null;
        } catch (Exception e) {
            if (indicatesMissingFile(e)) {
                return true; // the document is gone
            }
            if (BuildConfig.DEBUG) {
                Log.d(TAG, "isRestoredFileMissing: inconclusive for " + absPath, e);
            }
            return false;
        }
    }

    /**
     * True if a throwable (anywhere in its cause chain) reports that the backing
     * file is missing. ExternalStorageProvider raises a deleted file either as a
     * {@link FileNotFoundException} or, on the tree-child verification path, as an
     * {@code IllegalArgumentException} that embeds the {@code FileNotFoundException}
     * /{@code "Missing file"} text in its message. Those framework strings are
     * AOSP-internal English constants, so matching them is stable.
     */
    private static boolean indicatesMissingFile(@Nullable Throwable t) {
        for (Throwable c = t; c != null; c = c.getCause()) {
            if (c instanceof FileNotFoundException) {
                return true;
            }
            String message = c.getMessage();
            if (message != null
                    && (message.contains("FileNotFoundException")
                    || message.contains("Missing file"))) {
                return true;
            }
        }
        return false;
    }
}
