package com.solarized.firedown.phone.dialogs;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.ViewModelProvider;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.solarized.firedown.R;
import com.solarized.firedown.data.models.GeckoStateViewModel;
import com.solarized.firedown.data.models.IncognitoStateViewModel;
import com.solarized.firedown.geckoview.GeckoUblockHelper;

import java.util.List;

/**
 * Drill-down sheet listing the uBlock-blocked hosts for the active
 * page. Opened from the SecurityStateSheet's "Ads blocked" stat card.
 *
 * <p>Sibling of {@link BlockedTrackersDetailDialogFragment} but the
 * two sheets serve different mechanisms: that one surfaces the ETP
 * (Enhanced Tracking Protection) per-category counts, this one
 * surfaces uBlock's filter-list blocks. We can't honestly merge them
 * — uBlock's static engine throws away filter-list origin at compile
 * time, so we can't classify a uBlock block as "ad" vs "tracker" the
 * way ETP can; the user-facing distinction is "what got blocked"
 * vs "what got fingerprinted/cross-site-tracked".</p>
 *
 * <p>Data flow: this sheet does NOT poll uBlock. It asks once on
 * open via {@link com.solarized.firedown.geckoview.GeckoRuntimeHelper#requestPageBlocks()},
 * and firedown.js responds with the active tab's hostname tally via
 * {@code pageBlocks} on the native port. The
 * {@link com.solarized.firedown.geckoview.GeckoUblockHelper}
 * LiveData is per-mode, so an incognito sheet only sees incognito
 * data and vice versa.</p>
 */
public class BlockedAdsDetailDialogFragment extends BaseBottomSheetDialogFragment {

    private GeckoStateViewModel mGeckoStateViewModel;
    private IncognitoStateViewModel mIncognitoStateViewModel;
    private com.solarized.firedown.ui.adapters.TopTrackersAdapter mAdapter;
    private TextView mSubtitle;
    private TextView mEmptyView;
    private RecyclerView mRecyclerView;

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        mGeckoStateViewModel = new ViewModelProvider(mActivity).get(GeckoStateViewModel.class);
        mIncognitoStateViewModel = new ViewModelProvider(mActivity).get(IncognitoStateViewModel.class);
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        LayoutInflater themedInflater = container != null
                ? LayoutInflater.from(container.getContext())
                : inflater;
        mView = themedInflater.inflate(R.layout.fragment_dialog_blocked_ads_detail, container, false);

        mSubtitle = mView.findViewById(R.id.detail_subtitle);
        mEmptyView = mView.findViewById(R.id.detail_empty);
        mRecyclerView = mView.findViewById(R.id.detail_recycler);

        // TopTrackersAdapter already binds the (host, count) tuple
        // shape we want — same row layout (item_top_tracker.xml)
        // shipped by TrackersInfoSheet. No need for a new adapter.
        mAdapter = new com.solarized.firedown.ui.adapters.TopTrackersAdapter();
        mRecyclerView.setLayoutManager(new LinearLayoutManager(requireContext()));
        mRecyclerView.setAdapter(mAdapter);
        mRecyclerView.setHasFixedSize(false);

        return mView;
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        // Subscribe to the per-mode page-blocks stream — empty by
        // cold-start, populates after the requestPageBlocks below
        // round-trips through firedown.js.
        LiveData<List<GeckoUblockHelper.HostCount>> stream = mIsIncognito
                ? mIncognitoStateViewModel.getPageBlocks()
                : mGeckoStateViewModel.getPageBlocks();

        stream.observe(getViewLifecycleOwner(), this::render);

        // Trigger the JS round-trip. The previous stream value (if
        // any) keeps painting until the response lands, so a quick
        // open→close→reopen cycle doesn't blank the list.
        mGeckoRuntimeHelper.requestPageBlocks();
    }


    private void render(@Nullable List<GeckoUblockHelper.HostCount> items) {
        if (items == null || items.isEmpty()) {
            mEmptyView.setVisibility(View.VISIBLE);
            mRecyclerView.setVisibility(View.GONE);
            mSubtitle.setText(getResources().getQuantityString(
                    R.plurals.blocked_ads_summary, 0, 0));
            return;
        }

        int total = 0;
        for (GeckoUblockHelper.HostCount hc : items) {
            total += hc.count;
        }

        mEmptyView.setVisibility(View.GONE);
        mRecyclerView.setVisibility(View.VISIBLE);
        mAdapter.submitList(items);

        mSubtitle.setText(getResources().getQuantityString(
                R.plurals.blocked_ads_summary, total, total));
    }
}
