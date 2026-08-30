package com.solarized.firedown.phone.dialogs;

import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.widget.Toolbar;
import androidx.lifecycle.ViewModelProvider;
import androidx.recyclerview.widget.RecyclerView;

import com.solarized.firedown.Keys;
import com.solarized.firedown.R;
import com.solarized.firedown.data.OptionItem;
import com.solarized.firedown.data.entity.BrowserDownloadEntity;
import com.solarized.firedown.data.entity.OptionEntity;
import com.solarized.firedown.data.models.FragmentsOptionsViewModel;
import com.solarized.firedown.phone.fragments.BaseFocusFragment;
import com.solarized.firedown.ui.adapters.OptionsAdapter;
import com.solarized.firedown.utils.CaptureUrlActions;
import com.solarized.firedown.utils.FragmentArgs;

import java.util.ArrayList;
import java.util.List;

/**
 * The Captured item's ⋮ menu (issue #302) — an IN-SHEET page of the holder
 * sheet, pushed like the variants picker (slide-in, Back pops), never a
 * second modal bottom sheet stacked on the captured one (Material guidance
 * and the app's own precedents: the Downloads sheet swaps its Media-tools
 * sub-list in place, the sync QR panel rejected dialog-on-dialog; the
 * standalone-sheet first cut also re-derived theming the holder's child
 * context provides for free). Rows, each present only when it applies:
 *
 * <ul>
 *   <li>Copy URL / Share URL — the capture carries an external URL (see
 *       {@link CaptureUrlActions#externalUrl}); each acts then pops back to
 *       the list.</li>
 *   <li>Open in another app — additionally something on the device resolves
 *       the VIEW intent.</li>
 *   <li>Select quality — multi-variant captures; fires the same
 *       {@code item_download_more} event the ⋮ used to fire directly, so the
 *       holder pushes the unchanged variant picker ON TOP of this page and
 *       Back returns here.</li>
 * </ul>
 *
 * The row ⋮ shows only when at least one of these applies
 * (BrowserOptionAdapter's hasActions gate — keep the two rule sets in step).
 * Rows are identified by their icon res (the DownloadsOptionDialogFragment
 * dispatch contract). The LAST row wears the DialogOption.Final accent
 * treatment, like the Downloads sheet's closing option.
 */
public class BrowserCaptureItemMenuFragment extends BaseFocusFragment
        implements OptionsAdapter.OnItemClickListener {

    private BrowserDownloadEntity mEntity;
    @Nullable private String mUrl;
    private FragmentsOptionsViewModel mFragmentsViewModel;

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        mEntity = FragmentArgs.parcelable(this, Keys.ITEM_ID, BrowserDownloadEntity.class);
        mFragmentsViewModel = new ViewModelProvider(mActivity).get(FragmentsOptionsViewModel.class);
        // Null on restore is handled in onCreateView — pop back to the list,
        // the same convention as the variants fragment.
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        if (mEntity == null) {
            // Args lost on restore. Deferred so we don't re-enter the
            // parent's child FragmentManager mid-transaction (the variants
            // fragment's rule).
            new Handler(Looper.getMainLooper()).post(this::dispatchCancel);
            return null;
        }

        // Inflate against the container's context so the page takes the
        // holder sheet's (possibly incognito) theme, like every other child.
        LayoutInflater themedInflater = container != null
                ? LayoutInflater.from(container.getContext())
                : inflater;
        View view = themedInflater.inflate(
                R.layout.fragment_dialog_browser_options_item_menu, container, false);

        Toolbar toolbar = view.findViewById(R.id.toolbar);
        toolbar.setContentInsetsAbsolute(
                getResources().getDimensionPixelSize(R.dimen.address_bar_inset), 0);
        toolbar.setTitle(mEntity.getFileName());
        toolbar.setNavigationOnClickListener(v -> dispatchCancel());

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

        RecyclerView recyclerView = view.findViewById(R.id.recycler_view);
        // Two-arg ctor = FINAL-item treatment on the last row (the
        // DialogOption.Final colorPrimary text+tint the Downloads sheet
        // gives its last option) — maintainer's call: the closing row of the
        // menu wears the accent. Note it keys on POSITION, so it lands on
        // Select quality for multi-variant items and on whatever row closes
        // a shorter menu (Open in another app for a radio stream).
        recyclerView.setAdapter(new OptionsAdapter(items, this));
        // No setHasFixedSize: the list is wrap_content in the scrolling
        // direction (the sheet hugs this page), which that optimization is
        // invalid for (lint InvalidSetHasFixedSize) — and pointless on a
        // static ≤4-row menu.

        return view;
    }

    @Override
    public void onItemClick(int position, OptionItem item) {
        int icon = item.getIconRes();
        if (icon == R.drawable.ic_copy_24 && mUrl != null) {
            CaptureUrlActions.copy(requireContext(), mUrl);
            dispatchCancel();
        } else if (icon == R.drawable.ic_share_24 && mUrl != null) {
            CaptureUrlActions.share(requireContext(), mUrl);
            dispatchCancel();
        } else if (icon == R.drawable.ic_open_in_new_24 && mUrl != null) {
            CaptureUrlActions.openExternal(requireContext(), mEntity, mUrl);
            dispatchCancel();
        } else if (icon == R.drawable.ic_movie_24) {
            // The pre-menu ⋮ behavior: the holder listens for this event id
            // and pushes the variant picker onto the child back stack — ON
            // TOP of this page, so Back from the picker returns here.
            OptionEntity option = new OptionEntity();
            option.setId(R.id.item_download_more);
            option.setBrowserDownloadEntity(mEntity);
            mFragmentsViewModel.onOptionsSelected(option);
        }
    }

    /** Pops this page off the holder's child back stack (the cancel_button
     *  event the holder already maps to popBackStack). */
    private void dispatchCancel() {
        OptionEntity option = new OptionEntity();
        option.setId(R.id.cancel_button);
        mFragmentsViewModel.onOptionsSelected(option);
    }
}
