package com.solarized.firedown.phone.dialogs;

import android.content.Intent;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.FragmentManager;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.button.MaterialButton;
import com.solarized.firedown.R;
import com.solarized.firedown.geckoview.GeckoRuntimeHelper;
import com.solarized.firedown.geckoview.GeckoUblockHelper;
import com.solarized.firedown.geckoview.GeckoUblockHelper.Category;
import com.solarized.firedown.phone.SettingsActivity;
import com.solarized.firedown.ui.adapters.TopTrackersAdapter;
import com.solarized.firedown.utils.Utils;

import java.text.NumberFormat;
import java.util.Locale;
import java.util.Map;

import javax.inject.Inject;

import dagger.hilt.android.AndroidEntryPoint;

/**
 * Bottom-sheet shown when the Home 'Trackers and ads blocked' card
 * is tapped. Replaces what used to be a generic SettingsActivity
 * launch with a contextual breakdown — what's being blocked, the
 * cumulative count, the estimated bytes saved — plus a single
 * 'Manage protection' CTA that opens settings.
 *
 * <p>Reuses {@link BaseBottomSheetDialogFragment} so width caps,
 * insets, and rotation handling are inherited.</p>
 */
@AndroidEntryPoint
public class TrackersInfoSheet extends BaseBottomSheetDialogFragment {

    private static final String TAG = "TrackersInfoSheet";

    /** Same per-blocked-request estimate the Home card uses. Keeps
     *  the bytes-saved figure consistent between the card subtitle
     *  and the sheet body so the user doesn't see two different
     *  numbers for the same thing. */
    private static final long AVG_BYTES_PER_BLOCKED_REQUEST = 50_000L;

    /** Minimum entry count before the 'Top trackers' section is revealed.
     *  Smaller lists feel half-empty and read as 'not working yet' rather
     *  than 'still building'; three rows already give a sense of texture. */
    private static final int TOP_TRACKERS_MIN_REVEAL = 3;

    @Inject
    GeckoUblockHelper mGeckoUblockHelper;
    @Inject
    GeckoRuntimeHelper mGeckoRuntimeHelper;

    public static void show(@NonNull FragmentManager fm) {
        if (fm.findFragmentByTag(TAG) != null) return;
        if (fm.isStateSaved()) return;
        new TrackersInfoSheet().show(fm, TAG);
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        LayoutInflater themedInflater = container != null
                ? LayoutInflater.from(container.getContext())
                : inflater;
        mView = themedInflater.inflate(R.layout.fragment_dialog_trackers_info,
                container, false);
        return mView;
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        TextView countView = view.findViewById(R.id.trackers_info_count);
        TextView savedView = view.findViewById(R.id.trackers_info_saved);
        TextView todayView = view.findViewById(R.id.trackers_info_today);
        View idleView      = view.findViewById(R.id.trackers_info_idle);
        View statsRow      = view.findViewById(R.id.trackers_info_stats_row);
        View breakdownHdr  = view.findViewById(R.id.trackers_info_breakdown_header);
        View breakdownView = view.findViewById(R.id.trackers_info_breakdown);
        TextView scriptsView = view.findViewById(R.id.trackers_info_breakdown_scripts);
        TextView pixelsView  = view.findViewById(R.id.trackers_info_breakdown_pixels);
        TextView framesView  = view.findViewById(R.id.trackers_info_breakdown_frames);
        TextView otherView   = view.findViewById(R.id.trackers_info_breakdown_other);
        View topTrackersHdr  = view.findViewById(R.id.trackers_info_top_trackers_header);
        RecyclerView topTrackersList = view.findViewById(R.id.trackers_info_top_trackers);
        MaterialButton topTrackersToggle = view.findViewById(R.id.trackers_info_top_trackers_toggle);

        TopTrackersAdapter topTrackersAdapter = new TopTrackersAdapter();
        topTrackersList.setLayoutManager(new LinearLayoutManager(view.getContext()));
        topTrackersList.setAdapter(topTrackersAdapter);
        // Top-N list is small and re-sorted on each push, so disable
        // change animations to avoid the brief alpha-cross when counts
        // tick up — keeps the rows visually steady while the user reads.
        topTrackersList.setItemAnimator(null);
        MaterialButton action = view.findViewById(R.id.trackers_info_action);

        mGeckoUblockHelper.getCumulativeBlockedLive().observe(getViewLifecycleOwner(), blocked -> {
            long n = blocked == null ? 0L : blocked;
            if (n <= 0) {
                // Zero-state: three '0 / 0 / 0' stat cards would read
                // as 'protection is broken' rather than 'fresh install,
                // no browsing yet'. Hide the cards and the breakdown,
                // surface the 'Protection active' idle line instead.
                idleView.setVisibility(View.VISIBLE);
                statsRow.setVisibility(View.GONE);
                breakdownHdr.setVisibility(View.GONE);
                breakdownView.setVisibility(View.GONE);
                return;
            }
            idleView.setVisibility(View.GONE);
            statsRow.setVisibility(View.VISIBLE);
            breakdownHdr.setVisibility(View.VISIBLE);
            breakdownView.setVisibility(View.VISIBLE);
            NumberFormat fmt = NumberFormat.getInstance(Locale.getDefault());
            countView.setText(fmt.format(n));
            // Bytes-saved card shows the figure alone (e.g. '580 MB');
            // the surrounding sentence in trackers_info_saved is no
            // longer needed because the 'Data saved' label is the
            // card's caption.
            savedView.setText(Utils.readableFileSize(n * AVG_BYTES_PER_BLOCKED_REQUEST));
        });

        // Per-category breakdown — firedown.js buckets blocked requests
        // by fctxt.itype (script / pixel / frame / other) and pushes the
        // four-key map. Live-observed so the rows tick up while the
        // sheet is open if the user navigates an ad-heavy site behind it.
        mGeckoUblockHelper.getCategoryBlockedLive().observe(getViewLifecycleOwner(), buckets -> {
            if (buckets == null) return;
            NumberFormat fmt = NumberFormat.getInstance(Locale.getDefault());
            scriptsView.setText(fmt.format(getOrZero(buckets, Category.SCRIPTS)));
            pixelsView .setText(fmt.format(getOrZero(buckets, Category.PIXELS)));
            framesView .setText(fmt.format(getOrZero(buckets, Category.FRAMES)));
            otherView  .setText(fmt.format(getOrZero(buckets, Category.OTHER)));
        });

        // Today stat-card value — formatted number only, with the
        // 'Today' caption baked into the card label. Quiet day (n=0)
        // still renders '0' here because zeroing one card while the
        // other two carry data is fine; the all-zero case is handled
        // by the cumulative observer above.
        mGeckoUblockHelper.getTodayBlockedLive().observe(getViewLifecycleOwner(), today -> {
            long n = today == null ? 0L : today;
            todayView.setText(NumberFormat.getInstance(Locale.getDefault()).format(n));
        });

        // Top-trackers list. firedown.js sends a pre-sorted top-N
        // payload; the adapter handles binding + diffing. Section +
        // Clear button stay hidden until the map has at least
        // TOP_TRACKERS_MIN_REVEAL entries so a fresh install (or the
        // moment after a Clear) doesn't render an empty section
        // header floating with no rows beneath it.
        mGeckoUblockHelper.getTopTrackersLive().observe(getViewLifecycleOwner(), trackers -> {
            int size = trackers == null ? 0 : trackers.size();
            boolean showSection = size >= TOP_TRACKERS_MIN_REVEAL;
            topTrackersHdr.setVisibility(showSection ? View.VISIBLE : View.GONE);
            topTrackersList.setVisibility(showSection ? View.VISIBLE : View.GONE);
            topTrackersToggle.setVisibility(showSection ? View.VISIBLE : View.GONE);
            topTrackersAdapter.submitList(trackers);
        });

        topTrackersToggle.setOnClickListener(v -> mGeckoRuntimeHelper.clearTopTrackers());

        action.setOnClickListener(v -> {
            startActivity(new Intent(requireContext(), SettingsActivity.class));
            dismissAllowingStateLoss();
        });
    }

    private static long getOrZero(@NonNull Map<Category, Long> buckets, @NonNull Category key) {
        Long v = buckets.get(key);
        return v == null ? 0L : v;
    }
}
