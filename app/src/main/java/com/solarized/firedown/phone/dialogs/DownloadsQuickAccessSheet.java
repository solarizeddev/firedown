package com.solarized.firedown.phone.dialogs;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.lifecycle.ViewModelProvider;
import androidx.recyclerview.widget.RecyclerView;

import com.solarized.firedown.R;
import com.solarized.firedown.data.entity.DownloadEntity;
import com.solarized.firedown.data.models.RecentDownloadsViewModel;
import com.solarized.firedown.phone.DownloadsActivity;
import com.solarized.firedown.ui.adapters.DownloadsQuickAccessAdapter;

import dagger.hilt.android.AndroidEntryPoint;

/**
 * Bottom sheet shown when the user long-presses the bottom-bar
 * Downloads button. Surfaces the four most-recent finished regular
 * downloads as vertical rows + a 'View all' entry that opens
 * DownloadsActivity. The sheet self-dismisses when the underlying
 * LiveData drops to empty (e.g. the user deletes all downloads while
 * the sheet is still open).
 *
 * <p>Callback path: the sheet's host fragment (HomeFragment or
 * BrowserFragment) implements {@link Host} so the open-file flow
 * runs in the host's context — same {@code openItem()} call as the
 * main downloads list, including the shared-element transition into
 * PlayerActivity for video/audio.</p>
 */
@AndroidEntryPoint
public class DownloadsQuickAccessSheet extends BaseBottomSheetDialogFragment {

    public static final String TAG = "DownloadsQuickAccessSheet";

    public interface Host {
        /** Invoked when the user taps a tile. The sheet has already
         *  dismissed itself by the time this fires, so the host can
         *  immediately start an activity / transition. */
        void onQuickAccessFileTap(@NonNull DownloadEntity entity);
    }

    private RecentDownloadsViewModel mViewModel;
    @Nullable private Host mHost;

    @Override
    public void onAttach(@NonNull Context context) {
        super.onAttach(context);
        // Host lookup: parent fragment owns the open-file flow. If
        // it's missing or the wrong type the sheet still functions
        // (View all + dismiss); tiles just become no-ops.
        if (getParentFragment() instanceof Host parentHost) {
            mHost = parentHost;
        }
    }

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        mViewModel = new ViewModelProvider(this).get(RecentDownloadsViewModel.class);
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        mView = inflater.inflate(R.layout.fragment_downloads_quick_access_sheet, container, false);
        return mView;
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        RecyclerView recycler = view.findViewById(R.id.quick_access_recycler);
        View emptyView = view.findViewById(R.id.quick_access_empty);
        DownloadsQuickAccessAdapter adapter = new DownloadsQuickAccessAdapter(entity -> {
            if (mHost != null) mHost.onQuickAccessFileTap(entity);
            dismiss();
        });
        recycler.setAdapter(adapter);

        view.findViewById(R.id.quick_access_view_all).setOnClickListener(v -> {
            startActivity(new Intent(requireContext(), DownloadsActivity.class));
            dismiss();
        });

        mViewModel.getRecent().observe(getViewLifecycleOwner(), list -> {
            boolean empty = list == null || list.isEmpty();
            // Switch to the empty placeholder rather than dismissing —
            // the user explicitly long-pressed the Downloads button so
            // dropping their sheet feels unresponsive. The placeholder
            // keeps the 'View all' button reachable so they can still
            // jump to the full downloads list if they want.
            recycler.setVisibility(empty ? View.GONE : View.VISIBLE);
            emptyView.setVisibility(empty ? View.VISIBLE : View.GONE);
            adapter.submitList(empty ? java.util.Collections.emptyList() : list);
        });
    }
}
