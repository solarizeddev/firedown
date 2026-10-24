package com.solarized.firedown.utils;

import android.content.Context;
import android.media.MediaScannerConnection;
import android.os.Build;
import android.text.TextUtils;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.preference.PreferenceManager;

import com.solarized.firedown.Preferences;
import com.solarized.firedown.data.RestoredFileAccess;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Surfaces a finished media file to the system gallery through
 * {@link MediaScannerConnection#scanFile}, so gallery apps and media pickers
 * list it. ONE door for every producer of a public media file — the download
 * engine's finish, a renamed or unlocked file, a converted one (a GIF, an
 * audio track, a saved frame, an extracted archive), a restored backup, a
 * received share — gated HERE on the "Add to media gallery" switch, so no
 * producer re-reads the preference its own way or forgets it. Image, video
 * and audio only: a PDF or an APK is not scanned (nothing to show, a risk of
 * misclassification). A row whose stored type is not a media type (a CDN's
 * {@code application/octet-stream}) is typed by its file name, so the switch
 * covers the video the user watched, not only the ones a server labelled.
 *
 * <p>The caller owns the privacy gate: a vault file is never passed in —
 * once scanned, every gallery app on the device indexes it. {@link #forget}
 * needs no switch: it scans a path that no longer holds a file (deleted,
 * renamed, moved into the vault), which only REMOVES a stale row.</p>
 */
public final class GalleryPublisher {

    private GalleryPublisher() {}

    /** Publishes when the switch is on; a no-op otherwise. */
    public static void publishIfEnabled(@NonNull Context context,
                                        @Nullable String filePath,
                                        @Nullable String mimeType) {
        if (!Preferences.getSaveToGallery(PreferenceManager.getDefaultSharedPreferences(context))) {
            return;
        }
        if (TextUtils.isEmpty(filePath) || !RestoredFileAccess.isSharedStoragePath(filePath)) {
            return;
        }
        String mediaType = mediaTypeFor(filePath, mimeType);
        if (mediaType == null) {
            return;
        }
        MediaScannerConnection.scanFile(
                context.getApplicationContext(),
                new String[]{filePath},
                new String[]{mediaType},
                null);
    }

    /**
     * Drops the MediaStore row of a path that no longer holds a file: the
     * gallery otherwise keeps a broken entry for a deleted, renamed or locked
     * file until the system rescans on its own. Shared storage only — a
     * vault path handed to the scanner is a private file name in the
     * framework's own logcat lines — and only before Android 11: from there
     * the app's file operations go through MediaProvider's FUSE layer,
     * which updates the row itself, and a scan per deleted file is pure
     * cost on a batch delete.
     */
    public static void forget(@NonNull Context context, @Nullable String filePath) {
        forgetAll(context, Collections.singletonList(filePath));
    }

    /** {@link #forget} for a batch: one scanner request for every path. */
    public static void forgetAll(@NonNull Context context, @NonNull List<String> filePaths) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            return;
        }
        List<String> shared = new ArrayList<>();
        for (String path : filePaths) {
            if (!TextUtils.isEmpty(path) && RestoredFileAccess.isSharedStoragePath(path)) {
                shared.add(path);
            }
        }
        if (shared.isEmpty()) {
            return;
        }
        MediaScannerConnection.scanFile(
                context.getApplicationContext(),
                shared.toArray(new String[0]),
                null,
                null);
    }

    /**
     * Publishes every finished public media file at once — the backfill for a
     * switch turned on after the downloads were made, one scanner request.
     */
    public static void publishAll(@NonNull Context context, @NonNull List<String> paths,
                                  @NonNull List<String> mimeTypes) {
        List<String> scanPaths = new ArrayList<>();
        List<String> scanTypes = new ArrayList<>();
        for (int i = 0; i < paths.size(); i++) {
            String path = paths.get(i);
            if (TextUtils.isEmpty(path) || !RestoredFileAccess.isSharedStoragePath(path)) {
                continue;
            }
            String mediaType = mediaTypeFor(path, mimeTypes.get(i));
            if (mediaType == null) {
                continue;
            }
            scanPaths.add(path);
            scanTypes.add(mediaType);
        }
        if (scanPaths.isEmpty()) {
            return;
        }
        MediaScannerConnection.scanFile(
                context.getApplicationContext(),
                scanPaths.toArray(new String[0]),
                scanTypes.toArray(new String[0]),
                null);
    }

    /** The media type to scan under: the stored one when it is media, else the file name's, else none. */
    @Nullable
    static String mediaTypeFor(@NonNull String filePath, @Nullable String mimeType) {
        if (isMedia(mimeType)) {
            return mimeType;
        }
        String fromName = FileUriHelper.getMimeTypeFromFile(filePath);
        return isMedia(fromName) ? fromName : null;
    }

    private static boolean isMedia(@Nullable String mimeType) {
        if (TextUtils.isEmpty(mimeType)) return false;
        return FileUriHelper.isImage(mimeType)
                || FileUriHelper.isVideo(mimeType)
                || FileUriHelper.isAudio(mimeType);
    }
}
