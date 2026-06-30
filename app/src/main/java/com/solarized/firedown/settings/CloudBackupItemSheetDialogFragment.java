package com.solarized.firedown.settings;

import android.graphics.Bitmap;
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
import androidx.navigation.NavBackStackEntry;

import com.solarized.firedown.R;
import com.solarized.firedown.glide.MimeTypeThumbnail;
import com.solarized.firedown.phone.dialogs.BaseBottomSheetDialogFragment;
import com.solarized.firedown.sync.VaultThumbnail;
import com.solarized.firedown.utils.FileUriHelper;

import dagger.hilt.android.AndroidEntryPoint;

/**
 * Per-item bottom sheet for a backed-up file: Restore to Downloads / Remove from
 * cloud. A rich header (preview thumbnail + name + {@code MIME · size · date},
 * mirroring the list row) over two Firedown.Widget.DialogOption rows; "Remove" is
 * error-tinted (destructive). The choice is returned to
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

    /** Saved-state key the list fragment observes; value is a Bundle (below). */
    public static final String RESULT = "cb_item_result";
    public static final String RESULT_ACTION = "action";
    public static final String RESULT_OBJECT_ID = "object_id";
    public static final int ACTION_RESTORE = 0;
    public static final int ACTION_REMOVE = 1;

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
        bindThumb(mView.findViewById(R.id.cb_sheet_thumb), thumb, mime);

        mView.findViewById(R.id.cb_sheet_restore).setOnClickListener(v -> dispatch(ACTION_RESTORE));
        mView.findViewById(R.id.cb_sheet_remove).setOnClickListener(v -> dispatch(ACTION_REMOVE));
        return mView;
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

    private void bindThumb(ImageView thumb, String thumbData, String mime) {
        thumb.setClipToOutline(true);
        Bitmap bmp = VaultThumbnail.decode(thumbData);
        if (bmp != null) {
            thumb.setImageBitmap(bmp);
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
