package com.solarized.firedown.settings;

import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Bundle;
import android.text.TextUtils;
import android.text.format.DateUtils;
import android.text.format.Formatter;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.FileProvider;
import androidx.navigation.NavBackStackEntry;

import com.google.android.material.snackbar.Snackbar;
import com.solarized.firedown.Keys;
import com.bumptech.glide.Glide;
import com.solarized.firedown.R;
import com.solarized.firedown.data.Download;
import com.solarized.firedown.data.RestoredFileAccess;
import com.solarized.firedown.data.di.Qualifiers;
import com.solarized.firedown.data.entity.DownloadEntity;
import com.solarized.firedown.data.repository.DownloadDataRepository;
import com.solarized.firedown.glide.MimeTypeThumbnail;
import com.solarized.firedown.phone.PlayerActivity;
import com.solarized.firedown.phone.dialogs.BaseBottomSheetDialogFragment;
import com.solarized.firedown.sync.VaultThumbnail;
import com.solarized.firedown.utils.FileUriHelper;

import java.io.File;
import java.util.concurrent.Executor;

import javax.inject.Inject;

import dagger.hilt.android.AndroidEntryPoint;

/**
 * Per-item bottom sheet for a backed-up file: Restore to Downloads / Remove from
 * cloud. A rich header (preview thumbnail + name + {@code MIME · size · date},
 * mirroring the list row) over two Firedown.Widget.DialogOption rows; "Remove"
 * uses the .Final (destructive) variant — colorPrimary, the app's option-sheet
 * destructive treatment. The choice is returned to
 * {@link CloudBackupListFragment} via the NavBackStackEntry saved-state handle.
 */
@AndroidEntryPoint
public class CloudBackupItemSheetDialogFragment extends BaseBottomSheetDialogFragment {

    public static final String ARG_OBJECT_ID = "cb_object_id";
    public static final String ARG_NAME = "cb_name";
    public static final String ARG_MIME = "cb_mime";
    public static final String ARG_SIZE = "cb_size";
    public static final String ARG_DOWNLOADED_AT = "cb_downloaded_at";
    public static final String ARG_THUMB = "cb_thumb";
    /**
     * True when the file is gone from this device, so Remove destroys the last
     * copy. Passed in rather than resolved here: the list already computes the
     * whole set in ONE batch per manifest load, and a per-sheet probe would be a
     * fresh SAF/disk lookup on the main thread every time a sheet opens.
     */
    public static final String ARG_CLOUD_ONLY = "cb_cloud_only";
    /**
     * Local file path for an entry with NO stored manifest preview — the list
     * resolved it, so the header can show the same image the row does instead of
     * degrading to the mime glyph. A PATH rather than an image: since the list
     * moved its thumbnails onto Glide it holds no decoded bitmaps to hand over,
     * and a Bundle was never the right carrier for one.
     */
    public static final String ARG_LOCAL_PATH = "cb_local_path";

    /** Saved-state key the list fragment observes; value is a Bundle (below). */
    public static final String RESULT = "cb_item_result";
    public static final String RESULT_ACTION = "action";
    public static final String RESULT_OBJECT_ID = "object_id";
    public static final int ACTION_RESTORE = 0;
    public static final int ACTION_REMOVE = 1;
    /** Stream/open the cloud file in-app (no local copy, media type the app can
     *  play/show — see {@code CloudBackupStreamActivity}). */
    public static final int ACTION_OPEN = 2;

    @Inject
    DownloadDataRepository mDownloads;
    @Inject
    @Qualifiers.DiskIO
    Executor mDiskExecutor;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        mView = inflater.inflate(R.layout.fragment_cloud_backup_item_sheet, container, false);
        Bundle args = getArguments();

        String name = args != null ? args.getString(ARG_NAME) : null;
        String mime = args != null ? args.getString(ARG_MIME) : null;
        long size = args != null ? args.getLong(ARG_SIZE, 0) : 0;
        long downloadedAt = args != null ? args.getLong(ARG_DOWNLOADED_AT, 0) : 0;
        String thumb = args != null ? args.getString(ARG_THUMB) : null;

        ((TextView) mView.findViewById(R.id.cb_sheet_title)).setText(name);
        ((TextView) mView.findViewById(R.id.cb_sheet_meta)).setText(metaFor(mime, size, downloadedAt));
        bindThumb(mView.findViewById(R.id.cb_sheet_thumb), thumb,
                args != null ? args.getString(ARG_LOCAL_PATH) : null, mime);
        // The one place the removed row marker's information lives now: stated
        // once, next to the action it qualifies. Absent (false) is also what an
        // unresolved lookup yields, which is the safe direction — the sheet
        // stays silent rather than claiming a last copy it isn't sure about.
        if (args != null && args.getBoolean(ARG_CLOUD_ONLY, false)) {
            mView.findViewById(R.id.cb_sheet_only_copy).setVisibility(View.VISIBLE);
        }

        mView.findViewById(R.id.cb_sheet_restore).setOnClickListener(v -> dispatch(ACTION_RESTORE));
        mView.findViewById(R.id.cb_sheet_remove).setOnClickListener(v -> dispatch(ACTION_REMOVE));
        // Streamable media (video/audio/image) can be OPENED without a local copy
        // — the stream activity decrypts chunks on demand. Show the row now bound
        // to ACTION_OPEN; the local-copy probe below rebinds it to a direct open
        // when a local file exists (local wins, for any type).
        if (isStreamable(mime)) {
            View open = mView.findViewById(R.id.cb_sheet_open);
            open.setVisibility(View.VISIBLE);
            open.setOnClickListener(v -> dispatch(ACTION_OPEN));
        }
        revealOpenIfLocal(name, size, mime);
        return mView;
    }

    /** Media types this app can play/show in-app (so a cloud-only entry can be
     *  streamed). Everything else must be restored to disk to open. */
    private static boolean isStreamable(String mime) {
        return FileUriHelper.isVideo(mime) || FileUriHelper.isAudio(mime)
                || FileUriHelper.isImage(mime);
    }

    /**
     * Rebinds the "Open" row to a DIRECT local open once a background probe finds
     * a LOCAL copy of this file — name+size against the download table, the same
     * content key the backup engine dedups by. A local copy wins over streaming
     * for any type (incl. non-media, whose row is otherwise hidden — restore is
     * their only door). The probe is a single indexed DB lookup on the DiskIO
     * lane; the row appearing/updating a beat after the sheet is fine (it sits
     * BELOW the header, so nothing the user is reading moves).
     */
    private void revealOpenIfLocal(String name, long size, String mime) {
        if (name == null || size <= 0) {
            return;
        }
        mDiskExecutor.execute(() -> {
            DownloadEntity local = mDownloads.findByNameSize(name, size);
            // FINISHED + non-safe only: findByNameSize has no status/safe
            // filter, and ACTION_VIEW on a mid-download partial or a vault
            // entry's on-disk CIPHERTEXT would open garbage.
            if (local == null || local.getFileStatus() != Download.FINISHED
                    || local.isFileSafe()) {
                return;
            }
            String path = local.getFilePath();
            if (path == null || !isAdded() || mView == null) {
                return;
            }
            String resolvedMime = !TextUtils.isEmpty(local.getFileMimeType())
                    ? local.getFileMimeType() : mime;
            mView.post(() -> {
                if (!isAdded() || mView == null) {
                    return;
                }
                View open = mView.findViewById(R.id.cb_sheet_open);
                open.setVisibility(View.VISIBLE);
                open.setOnClickListener(v -> openLocal(local, resolvedMime));
            });
        });
    }

    /**
     * Open the LOCAL copy. Media (image/SVG/video/audio) goes to the in-app
     * {@link PlayerActivity} — the SAME viewer the Downloads list uses
     * ({@code BaseFocusFragment.startPlayerActivity}), so a backed-up file plays
     * in Firedown's own player, not an external app. PlayerActivity is a plain
     * Activity that takes the {@link DownloadEntity} as a {@link Keys#ITEM_ID}
     * extra, so it IS reachable from this Settings-hosted sheet (the old belief
     * that it wasn't is why this bounced to ACTION_VIEW). Non-media (doc/archive/
     * apk) has no in-app viewer, so it still falls back to a system ACTION_VIEW.
     */
    private void openLocal(DownloadEntity entity, String mime) {
        if (FileUriHelper.isImage(mime) || FileUriHelper.isSVG(mime)
                || FileUriHelper.isVideo(mime) || FileUriHelper.isAudio(mime)) {
            Intent play = new Intent(requireContext(), PlayerActivity.class);
            play.putExtra(Keys.ITEM_ID, entity);
            // Backup context: no Share (see PlayerActivity.EXTRA_HIDE_SHARE) —
            // a backed-up item isn't a share surface, and a 4 GB local file has
            // no viable share target anyway.
            play.putExtra(PlayerActivity.EXTRA_HIDE_SHARE, true);
            startActivity(play);
            mNavController.popBackStack();
            return;
        }
        openWithSystem(entity.getFilePath(), mime);
    }

    /** ACTION_VIEW on the local copy — owned file via FileProvider, a restored
     *  foreign-owned file via its persisted SAF grant (openableUri returns the
     *  content:// form for those; see RestoredFileAccess). The non-media fallback
     *  for {@link #openLocal}; media goes to the in-app PlayerActivity instead. */
    private void openWithSystem(String path, String mime) {
        try {
            Uri openable = RestoredFileAccess.openableUri(requireContext(), path);
            Uri uri;
            if (openable != null && "content".equals(openable.getScheme())) {
                uri = openable;
            } else {
                uri = FileProvider.getUriForFile(requireContext(),
                        requireContext().getPackageName() + ".fileprovider", new File(path));
            }
            Intent intent = new Intent(Intent.ACTION_VIEW);
            intent.setDataAndType(uri, mime);
            intent.setFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(intent);
            mNavController.popBackStack();
        } catch (ActivityNotFoundException | IllegalArgumentException e) {
            Snackbar.make(requireActivity().findViewById(android.R.id.content),
                    R.string.error_file_type_unknown, Snackbar.LENGTH_LONG).show();
        }
    }

    /** "MIME · size · date" — the same facts the list row shows. */
    private String metaFor(String mime, long size, long downloadedAt) {
        StringBuilder sb = new StringBuilder();
        String mimeLabel = mime != null ? FileUriHelper.getLongMimeText(requireContext(), mime) : null;
        if (!TextUtils.isEmpty(mimeLabel)) {
            sb.append(mimeLabel);
        }
        if (size > 0) {
            if (sb.length() > 0) {
                sb.append(" · ");
            }
            sb.append(Formatter.formatShortFileSize(requireContext(), size));
        }
        if (downloadedAt > 0) {
            if (sb.length() > 0) {
                sb.append(" · ");
            }
            sb.append(DateUtils.getRelativeTimeSpanString(downloadedAt,
                    System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS));
        }
        return sb.toString();
    }

    private void bindThumb(ImageView thumb, String thumbData, String localPath, String mime) {
        thumb.setClipToOutline(true);
        Bitmap bmp = VaultThumbnail.decode(thumbData);
        if (bmp != null) {
            thumb.setImageBitmap(bmp);
        } else if (localPath != null) {
            // No stored preview but the file is still on this device: load it
            // through Glide, which already holds the Downloads list's thumbnail
            // for it. The mime glyph is placeholder AND error, so a miss or a
            // failure lands on exactly the state this branch replaces.
            Drawable glyph = MimeTypeThumbnail.generateDrawable(
                    requireContext(), mime != null ? mime : "application/octet-stream", true);
            Glide.with(this)
                    .load(new File(localPath))
                    .placeholder(glyph)
                    .error(glyph)
                    .dontAnimate()
                    .into(thumb);
        } else {
            String mt = mime != null ? mime : "application/octet-stream";
            thumb.setImageDrawable(MimeTypeThumbnail.generateDrawable(requireContext(), mt, true));
        }
    }

    private void dispatch(int action) {
        Bundle args = getArguments();
        String objectId = args != null ? args.getString(ARG_OBJECT_ID) : null;
        NavBackStackEntry prev = mNavController.getPreviousBackStackEntry();
        if (prev != null && objectId != null) {
            Bundle result = new Bundle();
            result.putInt(RESULT_ACTION, action);
            result.putString(RESULT_OBJECT_ID, objectId);
            prev.getSavedStateHandle().set(RESULT, result);
        }
        mNavController.popBackStack();
    }
}
