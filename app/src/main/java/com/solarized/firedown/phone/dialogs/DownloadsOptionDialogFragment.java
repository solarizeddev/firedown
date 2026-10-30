package com.solarized.firedown.phone.dialogs;

import android.app.Dialog;
import android.content.res.Resources;
import android.content.res.TypedArray;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.activity.ComponentDialog;
import androidx.activity.OnBackPressedCallback;
import androidx.annotation.ArrayRes;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.navigation.NavBackStackEntry;
import androidx.recyclerview.widget.RecyclerView;
import com.solarized.firedown.R;
import com.solarized.firedown.sync.CloudBackupManager;
import com.solarized.firedown.data.Download;
import com.solarized.firedown.data.OptionItem;
import com.solarized.firedown.data.entity.DownloadEntity;
import com.solarized.firedown.data.entity.OptionEntity;
import com.solarized.firedown.IntentActions;
import com.solarized.firedown.ui.adapters.OptionsAdapter;
import com.solarized.firedown.utils.FileUriHelper;
import com.solarized.firedown.utils.FragmentArgs;
import com.solarized.firedown.Keys;

import java.util.ArrayList;
import java.util.List;

import javax.inject.Inject;

import dagger.hilt.android.AndroidEntryPoint;

@AndroidEntryPoint
public class DownloadsOptionDialogFragment extends BaseBottomSheetDialogFragment implements View.OnClickListener, OptionsAdapter.OnItemClickListener {

    /** Names the backup row after the folder's provider ("Back up to Google
     *  Drive") — the one row whose label depends on state. Injected here
     *  rather than read from the base: a class declaring {@code @Inject}
     *  fields must itself be {@code @AndroidEntryPoint} (the
     *  DownloadFragment.onRestoreTreePicked NPE). */
    @Inject
    CloudBackupManager mCloudBackup;


    private DownloadEntity mDownloadEntity;

    private int mPosition;

    /** This file's backup is enqueued or running (Keys.BACKUP_IN_FLIGHT, the
     *  Downloads list's own state handed over at open): the backup row reads
     *  "Cancel backup" and the fragment cancels on the tap. */
    private boolean mBackupInFlight;

    private RecyclerView mRecyclerView;
    private View mQuickRow;
    private List<OptionItem> mRootItems;
    // Quick-action header (Share / Open with / Rename) shows for finished-file
    // cases; the video case also has the "Media tools" group → sub-list.
    private boolean mShowQuickHeader;
    // True while the "Media tools" sub-sheet is showing — system BACK then
    // returns to the root list instead of dismissing the whole sheet, matching
    // the in-sheet navigation the group row's chevron promises.
    private boolean mInTools;

    /**
     * Back inside "Media tools" returns to the root list. A back callback, not
     * a KEYCODE_BACK listener: Android 16 (targetSdk 36) no longer sends the
     * key event, so the listener left Back closing the whole sheet.
     */
    private final OnBackPressedCallback mToolsBack = new OnBackPressedCallback(false) {
        @Override
        public void handleOnBackPressed() {
            showRoot();
        }
    };


    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        mDownloadEntity = FragmentArgs.parcelable(this, Keys.ITEM_ID, DownloadEntity.class);
        Bundle bundle = getArguments();
        mPosition = bundle != null ? bundle.getInt(Keys.ITEM_POSITION) : 0;
        mBackupInFlight = bundle != null && bundle.getBoolean(Keys.BACKUP_IN_FLIGHT, false);
        // Null on restore is handled by onCreateDialog / onCreateView.
    }

    @Override
    protected boolean isMaxHeightCapped() {
        return false;
    }

    @NonNull
    @Override
    public Dialog onCreateDialog(@Nullable Bundle savedInstanceState) {
        if (mDownloadEntity == null) {
            Dialog dialog = new Dialog(requireContext());
            dialog.setOnShowListener(d -> dismissAllowingStateLoss());
            return dialog;
        }
        Dialog dialog = super.onCreateDialog(savedInstanceState);
        if (dialog instanceof ComponentDialog) {
            ((ComponentDialog) dialog).getOnBackPressedDispatcher().addCallback(this, mToolsBack);
        }
        return dialog;
    }

    @Nullable
    @Override
    public View onCreateView(LayoutInflater inflater,
                             @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {

        if (mDownloadEntity == null) return null;

        // get the views and attach the listener

        mView = inflater.inflate(R.layout.fragment_dialog_options, container, false);

        mRecyclerView = mView.findViewById(R.id.recycler_view);

        // resolveArrayPair also sets mShowQuickHeader for this download's case.
        int[] arrayPair = resolveArrayPair();
        mRootItems = loadOptionItems(arrayPair[0], arrayPair[1]);

        mRecyclerView.setAdapter(new OptionsAdapter(mRootItems, this));
        // No setHasFixedSize: inside the wrap-height bottom sheet the
        // recycler is measured AT_MOST (effectively wrap in the scroll
        // direction — lint InvalidSetHasFixedSize). Harmless here because
        // the Media-tools sub-list swaps via a fresh setAdapter() (which
        // always relayouts), but the flag buys nothing and would silently
        // break the sheet's height if a notifyItem* ever appears.

        // Quick-action header: Share / Open with / Rename, lifted out of the
        // list. Each button dispatches through the same icon-keyed path as a
        // list row. Shown only for finished-file cases.
        mQuickRow = mView.findViewById(R.id.options_quick_row);
        if (mShowQuickHeader) {
            mQuickRow.setVisibility(View.VISIBLE);
            mView.findViewById(R.id.qa_share).setOnClickListener(v -> dispatch(R.drawable.ic_share_24));
            mView.findViewById(R.id.qa_open_with).setOnClickListener(v -> dispatch(R.drawable.ic_web_24));
            mView.findViewById(R.id.qa_rename).setOnClickListener(v -> dispatch(R.drawable.ic_edit_24));
            mView.findViewById(R.id.qa_send).setOnClickListener(v -> dispatch(R.drawable.ic_p2p_send_24));
        }

        return mView;

    }

    /**
     * Returns {labelArrayRes, iconArrayRes} based on download state, and sets
     * {@link #mShowQuickHeader} (the Share/Open with/Rename header shows for
     * the finished-file cases — video, non-video, archive — but not for the
     * in-progress / errored / encrypted cases).
     */
    private int[] resolveArrayPair() {
        mShowQuickHeader = false;
        if (mDownloadEntity.getFileErrorType() != Download.PROGRESS) {
            return new int[]{R.array.download_options_error_type, R.array.download_options_error_icon};
        }
        if (mDownloadEntity.getFileStatus() == Download.PROGRESS) {
            return new int[]{R.array.download_options_pause_type, R.array.download_options_pause_icon};
        }
        if (mDownloadEntity.isFileSafe()) {
            return new int[]{R.array.download_options_encrypted_type, R.array.download_options_encrypted_icon};
        }
        mShowQuickHeader = true;
        if (FileUriHelper.isZip(mDownloadEntity.getFileMimeType())) {
            return new int[]{R.array.download_options_archive_type, R.array.download_options_archive_icon};
        }
        if (!FileUriHelper.isVideo(mDownloadEntity.getFileMimeType())) {
            return new int[]{R.array.download_options_no_thumb_type, R.array.download_options_no_thumb_icon};
        }
        return new int[]{R.array.download_options_type, R.array.download_options_icon};
    }

    private List<OptionItem> loadOptionItems(@ArrayRes int labelsRes, @ArrayRes int iconsRes) {
        Resources res = getResources();
        String[] labels = res.getStringArray(labelsRes);
        TypedArray icons = res.obtainTypedArray(iconsRes);

        List<OptionItem> items = new ArrayList<>(icons.length());
        try {
            for (int i = 0; i < labels.length; i++) {
                int iconResId = icons.getResourceId(i, 0);
                // The "Media tools" group row gets a trailing chevron to
                // signal it opens a sub-list rather than firing an action.
                if (iconResId == R.drawable.ic_movie_24) {
                    items.add(new OptionItem(labels[i], iconResId, 0, R.drawable.ic_chevron_right_24));
                } else if (iconResId == R.drawable.cloud_upload_24) {
                    items.add(new OptionItem(backupLabel(labels[i]), iconResId));
                } else {
                    items.add(new OptionItem(labels[i], iconResId));
                }
            }
        } finally {
            icons.recycle();
        }
        return items;
    }

    /**
     * The backup row's label follows the backup's state: "Back up to Google
     * Drive" once a folder is chosen (the provider is the fact the user is
     * deciding on — a row reading "Back up" says nothing about where), "Set
     * up backup" while the code or the folder is still missing (the tap then
     * runs the setup, so the verb must say so), and the array's plain label
     * when the provider has no name the system can give us.
     */
    private String backupLabel(String arrayLabel) {
        if (mBackupInFlight) {
            // The row keeps its icon (the fragment routes the tap by icon id)
            // and only its verb flips; the fragment re-checks the live state
            // before cancelling, so a backup that ended while the sheet was
            // open just starts a new one, as the label would have promised.
            return getString(R.string.cloud_backup_cancel_action);
        }
        if (mCloudBackup == null) {
            return arrayLabel;
        }
        if (!mCloudBackup.isSetUp()) {
            return getString(R.string.folder_backup_setup_action);
        }
        String provider = mCloudBackup.providerLabel();
        if (provider == null || provider.isEmpty()) {
            return arrayLabel;
        }
        return getString(R.string.cloud_backup_action_to, provider);
    }

    /**
     * Swaps the list to the video-only "Media tools" sub-sheet in place
     * (Extract audio / Create GIF / Save frame / Regenerate thumbnail),
     * led by a back row. No destructive final item, so it's bound with the
     * no-final adapter. The quick-action header is hidden here.
     */
    private void showTools() {
        List<OptionItem> tools = new ArrayList<>();
        tools.add(new OptionItem(getString(R.string.media_tools), R.drawable.ic_arrow_back_24));
        tools.add(OptionItem.separator());
        tools.addAll(loadOptionItems(R.array.download_options_tools_type,
                R.array.download_options_tools_icon));
        mRecyclerView.setAdapter(new OptionsAdapter(tools, this, false));
        if (mQuickRow != null) mQuickRow.setVisibility(View.GONE);
        mInTools = true;
        mToolsBack.setEnabled(true);
    }

    /** Returns from the "Media tools" sub-sheet to the root list. */
    private void showRoot() {
        mRecyclerView.setAdapter(new OptionsAdapter(mRootItems, this));
        if (mQuickRow != null && mShowQuickHeader) mQuickRow.setVisibility(View.VISIBLE);
        mInTools = false;
        mToolsBack.setEnabled(false);
    }

    /**
     * Pops back to the Downloads list with the chosen action, keyed by its
     * icon res id (the dispatch contract handleOptionSelection switches on).
     * Shared by list-row clicks and the quick-action header buttons.
     */
    private void dispatch(int actionId) {
        NavBackStackEntry navBackStackEntry = mNavController.getPreviousBackStackEntry();
        if (navBackStackEntry != null) {
            OptionEntity optionEntity = new OptionEntity();
            optionEntity.setId(actionId);
            optionEntity.setDownloadEntity(mDownloadEntity);
            optionEntity.setPosition(mPosition);
            navBackStackEntry.getSavedStateHandle().set(IntentActions.DOWNLOAD_ITEM, optionEntity);
        }
        mNavController.popBackStack();
    }

    @Override
    public void onClick(View view) {
        NavBackStackEntry navBackStackEntry = mNavController.getPreviousBackStackEntry();

        if (navBackStackEntry != null) {
            OptionEntity optionEntity = new OptionEntity();
            optionEntity.setId(view.getId());
            optionEntity.setDownloadEntity(mDownloadEntity);
            optionEntity.setPosition(mPosition);
            navBackStackEntry.getSavedStateHandle().set(IntentActions.DOWNLOAD_ITEM, optionEntity);
        }
        mNavController.popBackStack();
    }


    @Override
    public void onItemClick(int position, OptionItem item) {
        if (position == RecyclerView.NO_POSITION)
            return;

        int iconRes = item.getIconRes();
        // The "Media tools" group row and the sub-sheet's back row navigate
        // within the sheet rather than firing a download action.
        if (iconRes == R.drawable.ic_movie_24) {
            showTools();
            return;
        }
        if (iconRes == R.drawable.ic_arrow_back_24) {
            showRoot();
            return;
        }
        dispatch(iconRes);
    }
}

