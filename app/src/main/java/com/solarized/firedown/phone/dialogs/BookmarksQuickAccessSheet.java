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
import com.solarized.firedown.data.entity.WebBookmarkEntity;
import com.solarized.firedown.data.models.WebBookmarkViewModel;
import com.solarized.firedown.phone.BookmarkActivity;
import com.solarized.firedown.ui.adapters.BookmarksQuickAccessAdapter;

import dagger.hilt.android.AndroidEntryPoint;

/**
 * Bottom sheet shown when the user long-presses the cradle Bookmarks
 * button on the home page. Surfaces the most-recent pinned bookmarks
 * as vertical rows + a 'View all bookmarks' footer that opens
 * BookmarkActivity. Mirrors {@link DownloadsQuickAccessSheet}'s
 * shape so the two long-press destinations feel like a pair.
 *
 * <p>Callback path: the sheet's host fragment (HomeFragment)
 * implements {@link Host} so the navigate-to-URL flow runs in
 * the host's context — same {@code openUri()} path the paste hero
 * and search-autocomplete use.</p>
 */
@AndroidEntryPoint
public class BookmarksQuickAccessSheet extends BaseBottomSheetDialogFragment {

    public static final String TAG = "BookmarksQuickAccessSheet";

    /** Sheet renders up to this many rows. Caller (HomeFragment's
     *  long-press handler) reads getValue() with the same cap to
     *  decide whether to open the sheet or fall through to
     *  BookmarkActivity. */
    public static final int LIMIT = 5;

    public interface Host {
        /** Invoked when the user taps a pinned-bookmark row. The
         *  sheet has already dismissed itself by the time this
         *  fires, so the host can immediately navigate. */
        void onBookmarkQuickAccessTap(@NonNull WebBookmarkEntity entity);
    }

    private WebBookmarkViewModel mViewModel;
    @Nullable private Host mHost;

    @Override
    public void onAttach(@NonNull Context context) {
        super.onAttach(context);
        if (getParentFragment() instanceof Host parentHost) {
            mHost = parentHost;
        }
    }

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        mViewModel = new ViewModelProvider(this).get(WebBookmarkViewModel.class);
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        mView = inflater.inflate(R.layout.fragment_bookmarks_quick_access_sheet, container, false);
        return mView;
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        RecyclerView recycler = view.findViewById(R.id.bookmarks_quick_access_recycler);
        BookmarksQuickAccessAdapter adapter = new BookmarksQuickAccessAdapter(entity -> {
            if (mHost != null) mHost.onBookmarkQuickAccessTap(entity);
            dismiss();
        });
        recycler.setAdapter(adapter);

        view.findViewById(R.id.bookmarks_quick_access_view_all).setOnClickListener(v -> {
            startActivity(new Intent(requireContext(), BookmarkActivity.class));
            dismiss();
        });

        mViewModel.getPinned(LIMIT).observe(getViewLifecycleOwner(), list -> {
            // Empty mid-sheet (user unpinned everything from another
            // surface) → dismiss. Unlike Downloads, an empty pinned
            // list is the *normal* state for a user who has never
            // pinned anything, so showing 'no pinned bookmarks yet'
            // would just nag — the caller already gates the
            // sheet-vs-activity decision on a non-empty list.
            if (list == null || list.isEmpty()) {
                dismiss();
                return;
            }
            adapter.submitList(list);
        });
    }
}
