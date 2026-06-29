package com.solarized.firedown.phone.dialogs;

import android.annotation.SuppressLint;
import android.app.Dialog;
import android.content.res.Resources;
import android.content.res.TypedArray;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.ArrayRes;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.navigation.NavBackStackEntry;
import androidx.recyclerview.widget.RecyclerView;
import com.solarized.firedown.R;
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

public class DownloadsOptionDialogFragment extends BaseBottomSheetDialogFragment implements View.OnClickListener, OptionsAdapter.OnItemClickListener {


    private DownloadEntity mDownloadEntity;

    private int mPosition;

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


    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        mDownloadEntity = FragmentArgs.parcelable(this, Keys.ITEM_ID, DownloadEntity.class);
        Bundle bundle = getArguments();
        mPosition = bundle != null ? bundle.getInt(Keys.ITEM_POSITION) : 0;
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
        // BACK inside the "Media tools" sub-sheet pops back to the root list;
        // anywhere else it falls through to the normal sheet dismissal. Both
        // key actions are consumed while in the sub-sheet (the same
        // both-actions rule as AutoCompleteEditText's onKeyPreIme) so the
        // DOWN can't reach the dialog's default dismiss handling, with the
        // navigation done once, on UP.
        dialog.setOnKeyListener((d, keyCode, event) -> {
            if (keyCode != KeyEvent.KEYCODE_BACK || !mInTools) {
                return false;
            }
            if (event.getAction() == KeyEvent.ACTION_UP) {
                showRoot();
            }
            return true;
        });
        return dialog;
    }

    @SuppressLint("InvalidSetHasFixedSize")
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
        mRecyclerView.setHasFixedSize(true);

        // Quick-action header: Share / Open with / Rename, lifted out of the
        // list. Each button dispatches through the same icon-keyed path as a
        // list row. Shown only for finished-file cases.
        mQuickRow = mView.findViewById(R.id.options_quick_row);
        if (mShowQuickHeader) {
            mQuickRow.setVisibility(View.VISIBLE);
            mView.findViewById(R.id.qa_share).setOnClickListener(v -> dispatch(R.drawable.ic_share_24));
            mView.findViewById(R.id.qa_open_with).setOnClickListener(v -> dispatch(R.drawable.ic_web_24));
            mView.findViewById(R.id.qa_rename).setOnClickListener(v -> dispatch(R.drawable.ic_edit_24));
            mView.findViewById(R.id.qa_send).setOnClickListener(v -> dispatch(R.drawable.ic_send_lan_24));
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
    }

    /** Returns from the "Media tools" sub-sheet to the root list. */
    private void showRoot() {
        mRecyclerView.setAdapter(new OptionsAdapter(mRootItems, this));
        if (mQuickRow != null && mShowQuickHeader) mQuickRow.setVisibility(View.VISIBLE);
        mInTools = false;
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

