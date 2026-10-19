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
import androidx.recyclerview.widget.SimpleItemAnimator;

import com.solarized.firedown.R;
import com.solarized.firedown.data.models.GeckoStateViewModel;
import com.solarized.firedown.data.models.IncognitoStateViewModel;
import com.solarized.firedown.geckoview.GeckoUblockHelper;
import com.solarized.firedown.ui.adapters.BlockedTrackerDetailAdapter;

import java.util.ArrayList;
import java.util.List;

/**
 * Drill-down sheet listing the uBlock-blocked hosts for the active
 * page ("23 requests blocked on this page"). Opened from the
 * SecurityStateSheet's "N blocked on this page" row.
 *
 * <p>The ETP-side twin of this sheet (per-category tracker counts) was
 * removed with the per-site tracking switch: the count row on the security
 * sheet is uBlock's tally and drills here only. Its rows share the anatomy
 * of the Settings "Most blocked domains today" list (one grouped card per
 * host, the site's own name brighter, the count always shown); it used to
 * be a "Blocked ads" sheet of 13sp monospace lines, which named one kind of
 * request for a list of all of them.
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
    private BlockedTrackerDetailAdapter mAdapter;
    private TextView mSubtitle;
    private View mHeader;
    private View mEmptyView;
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
        mHeader = mView.findViewById(R.id.detail_header);
        mEmptyView = mView.findViewById(R.id.detail_empty);
        mRecyclerView = mView.findViewById(R.id.detail_recycler);

        // BlockedTrackerDetailAdapter: one grouped card per host, the count
        // trailing. uBlock blocks carry no category, so there are host rows
        // only. (Today's "Most blocked domains" list lives on Settings →
        // Privacy → Trackers and ads blocked as CountPreference rows of the
        // same anatomy; this is the per-page list.)
        mAdapter = new BlockedTrackerDetailAdapter();
        mRecyclerView.setLayoutManager(new LinearLayoutManager(requireContext()));
        mRecyclerView.setAdapter(mAdapter);
        // A count that ticks while the sheet is open is a CHANGE, and the
        // default change animation cross-faded the row each time — the list
        // flashed on every block burst. New hosts still animate in and rows
        // still move as the list re-sorts; only the cross-fade is off.
        RecyclerView.ItemAnimator animator = mRecyclerView.getItemAnimator();
        if (animator instanceof SimpleItemAnimator simple) {
            simple.setSupportsChangeAnimations(false);
        }
        mRecyclerView.setHasFixedSize(false);

        return mView;
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        // Subscribe to the per-mode page-blocks stream. firedown.js now pushes
        // pageBlocks for the active tab on every block burst (piggybacked on its
        // updateToolbarIcon hook, debounced 250ms), so this list updates live
        // while the sheet is open — no polling. The requestPageBlocks() below is
        // just a one-shot seed for the current state on open.
        LiveData<List<GeckoUblockHelper.HostCount>> stream = mIsIncognito
                ? mIncognitoStateViewModel.getPageBlocks()
                : mGeckoStateViewModel.getPageBlocks();

        stream.observe(getViewLifecycleOwner(), this::render);

        // Seed the initial state. The previous stream value (if any) keeps
        // painting until the response lands, so a quick open→close→reopen
        // doesn't blank the list.
        mGeckoRuntimeHelper.requestPageBlocks();
    }


    private void render(@Nullable List<GeckoUblockHelper.HostCount> items) {
        if (items == null || items.isEmpty()) {
            // The empty state stands alone: "0 requests blocked on this
            // page" over "Nothing blocked on this page yet" says it twice.
            mEmptyView.setVisibility(View.VISIBLE);
            mHeader.setVisibility(View.GONE);
            mRecyclerView.setVisibility(View.GONE);
            mAdapter.submitList(null);
            return;
        }

        int total = 0;
        List<BlockedTrackerDetailAdapter.Item> rows = new ArrayList<>(items.size());
        for (GeckoUblockHelper.HostCount hc : items) {
            total += hc.count;
            rows.add(new BlockedTrackerDetailAdapter.HostRow(hc.host, hc.domain, hc.count));
        }

        mEmptyView.setVisibility(View.GONE);
        mHeader.setVisibility(View.VISIBLE);
        mRecyclerView.setVisibility(View.VISIBLE);
        mAdapter.submitList(rows);

        mSubtitle.setText(getResources().getQuantityString(
                R.plurals.blocked_ads_summary, total, total));
    }
}
