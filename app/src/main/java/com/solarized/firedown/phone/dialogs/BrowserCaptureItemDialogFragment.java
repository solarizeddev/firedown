package com.solarized.firedown.phone.dialogs;

import android.app.Dialog;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.lifecycle.ViewModelProvider;
import androidx.recyclerview.widget.RecyclerView;

import com.solarized.firedown.Keys;
import com.solarized.firedown.R;
import com.solarized.firedown.data.OptionItem;
import com.solarized.firedown.data.entity.BrowserDownloadEntity;
import com.solarized.firedown.data.entity.OptionEntity;
import com.solarized.firedown.data.models.FragmentsOptionsViewModel;
import com.solarized.firedown.ui.adapters.OptionsAdapter;
import com.solarized.firedown.utils.CaptureUrlActions;
import com.solarized.firedown.utils.FragmentArgs;

import java.util.ArrayList;
import java.util.List;

/**
 * The Captured row's ⋮ options sheet (issue #302) — the app-standard
 * {@link OptionsAdapter} bottom sheet ({@link DownloadsOptionDialogFragment}
 * pattern, {@code Firedown.Widget.DialogOption} rows), opened as a nav
 * {@code <dialog>} on top of the Captured sheet exactly like
 * {@code dialog_save_file}. Rows, each shown only when it applies:
 *
 * <ul>
 *   <li>Copy URL / Share URL — the capture carries an external URL (see
 *       {@link CaptureUrlActions#externalUrl}).</li>
 *   <li>Open in another app — additionally something on the device resolves
 *       the VIEW intent.</li>
 *   <li>Select quality — multi-variant captures; fires the same
 *       {@code item_download_more} option event the ⋮ used to fire directly,
 *       so the holder sheet pushes the unchanged variant picker.</li>
 * </ul>
 *
 * The row-⋮ itself shows only when at least one of these applies
 * (BrowserOptionAdapter's hasActions gate — keep the two rule sets in step).
 * No destructive row, so the no-final adapter is used.
 */
public class BrowserCaptureItemDialogFragment extends BaseBottomSheetDialogFragment
        implements OptionsAdapter.OnItemClickListener {

    private BrowserDownloadEntity mEntity;
    @Nullable private String mUrl;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        mEntity = FragmentArgs.parcelable(this, Keys.ITEM_ID, BrowserDownloadEntity.class);
        // Null on restore is handled by onCreateDialog / onCreateView.
    }

    @NonNull
    @Override
    public Dialog onCreateDialog(@Nullable Bundle savedInstanceState) {
        if (mEntity == null) {
            // Args lost on restore — nothing to act on; dismiss on show
            // (the DownloadsOptionDialogFragment convention).
            Dialog dialog = new Dialog(requireContext());
            dialog.setOnShowListener(d -> dismissAllowingStateLoss());
            return dialog;
        }
        return super.onCreateDialog(savedInstanceState);
    }

    @Nullable
    @Override
    public View onCreateView(LayoutInflater inflater,
                             @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        if (mEntity == null) {
            return null;
        }

        mView = inflater.inflate(R.layout.fragment_dialog_options, container, false);

        mUrl = CaptureUrlActions.externalUrl(mEntity);

        List<OptionItem> items = new ArrayList<>();
        if (mUrl != null) {
            items.add(new OptionItem(getString(R.string.capture_copy_url),
                    R.drawable.ic_copy_24));
            items.add(new OptionItem(getString(R.string.capture_share_url),
                    R.drawable.ic_share_24));
            if (CaptureUrlActions.canOpenExternal(requireContext(), mEntity, mUrl)) {
                items.add(new OptionItem(getString(R.string.open_in_app_title),
                        R.drawable.ic_open_in_new_24));
            }
        }
        if (mEntity.getHasVariants()) {
            items.add(new OptionItem(getString(R.string.capture_show_variants),
                    R.drawable.ic_movie_24));
        }

        RecyclerView recyclerView = mView.findViewById(R.id.recycler_view);
        recyclerView.setAdapter(new OptionsAdapter(items, this, false));
        recyclerView.setHasFixedSize(true);

        return mView;
    }

    @Override
    public void onItemClick(int position, OptionItem item) {
        int icon = item.getIconRes();
        if (icon == R.drawable.ic_copy_24 && mUrl != null) {
            CaptureUrlActions.copy(requireContext(), mUrl);
        } else if (icon == R.drawable.ic_share_24 && mUrl != null) {
            CaptureUrlActions.share(requireContext(), mUrl);
        } else if (icon == R.drawable.ic_open_in_new_24 && mUrl != null) {
            CaptureUrlActions.openExternal(requireContext(), mEntity, mUrl);
        } else if (icon == R.drawable.ic_movie_24) {
            // The pre-menu ⋮ behavior: the holder sheet (still alive behind
            // this dialog) listens for this event id and pushes the variant
            // picker onto its child back stack.
            OptionEntity option = new OptionEntity();
            option.setId(R.id.item_download_more);
            option.setBrowserDownloadEntity(mEntity);
            new ViewModelProvider(mActivity).get(FragmentsOptionsViewModel.class)
                    .onOptionsSelected(option);
        }
        dismiss();
    }
}
