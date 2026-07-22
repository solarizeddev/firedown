package com.solarized.firedown.phone.dialogs;

import android.content.Intent;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.FragmentManager;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.button.MaterialButton;
import com.solarized.firedown.R;
import com.solarized.firedown.geckoview.GeckoUblockHelper;
import com.solarized.firedown.geckoview.GeckoUblockHelper.Category;
import com.solarized.firedown.phone.SettingsActivity;
import com.solarized.firedown.ui.adapters.TopTrackersAdapter;
import com.solarized.firedown.utils.Utils;

import org.mozilla.geckoview.ContentBlockingController;
import org.mozilla.geckoview.ContentBlockingController.TrackingDbEvent;
import org.mozilla.geckoview.GeckoRuntime;

import java.text.DateFormat;
import java.text.NumberFormat;
import java.util.Date;
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
        // payload; the adapter handles binding + diffing. Section stays
        // hidden until the map has at least TOP_TRACKERS_MIN_REVEAL
        // entries so a fresh install (or the first hour after midnight,
        // when the list rebuilds for the day) doesn't render an empty
        // section header floating with no rows beneath it.
        mGeckoUblockHelper.getTopTrackersLive().observe(getViewLifecycleOwner(), trackers -> {
            int size = trackers == null ? 0 : trackers.size();
            boolean showSection = size >= TOP_TRACKERS_MIN_REVEAL;
            topTrackersHdr.setVisibility(showSection ? View.VISIBLE : View.GONE);
            topTrackersList.setVisibility(showSection ? View.VISIBLE : View.GONE);
            topTrackersAdapter.submitList(trackers);
        });

        action.setOnClickListener(v -> {
            startActivity(new Intent(requireContext(), SettingsActivity.class));
            dismissAllowingStateLoss();
        });

        bindTrackingDbSection(view);
    }

    /**
     * Populate the 'Tracking protection' section from Gecko's persisted
     * Enhanced Tracking Protection database (enabled in
     * {@code GeckoRuntimeHelper}, query APIs unlocked by the GeckoView
     * 153 bump). This is a SEPARATE data source from the uBlock counts
     * above: it records a different set of blocks — tracking cookies,
     * fingerprinters, cryptominers, social / bounce trackers — and,
     * unlike uBlock's in-memory count, persists across sessions and is
     * queryable by date. The two are never summed.
     *
     * <p>Queries are one-shot on sheet open. A {@code GeckoResult}
     * callback is dispatched to the thread that called {@code accept()};
     * {@code onViewCreated} runs on the main thread (which has a Looper),
     * so the callbacks land on the main thread and may touch views
     * directly. Each guards on {@link #getView()} because the sheet can
     * be dismissed before an async result arrives. The whole section
     * stays hidden until the all-time total is &gt; 0, so a freshly
     * enabled (still-empty) database shows nothing rather than a bank of
     * zeroes — the same zero-state stance as the uBlock stats above.</p>
     */
    private void bindTrackingDbSection(@NonNull View root) {
        GeckoRuntime runtime = mGeckoRuntimeHelper.getGeckoRuntime();
        if (runtime == null) return;
        ContentBlockingController controller = runtime.getContentBlockingController();

        View section = root.findViewById(R.id.trackers_info_etp_section);
        TextView allTimeView = root.findViewById(R.id.trackers_info_etp_alltime);
        TextView weekView = root.findViewById(R.id.trackers_info_etp_week);
        TextView sinceView = root.findViewById(R.id.trackers_info_etp_since);
        LinearLayout breakdown = root.findViewById(R.id.trackers_info_etp_breakdown);

        long now = System.currentTimeMillis();
        long weekAgo = now - 7L * 24L * 60L * 60L * 1000L;

        // All-time: sum every recorded event, aggregate by category for
        // the breakdown, and reveal the section only if anything was
        // recorded. dateFrom=0 (epoch) captures the whole history.
        controller.getTrackingDbEventsByDateRange(0L, now).accept(events -> {
            if (getView() == null || events == null) return;

            long total = 0L, trackers = 0L, cookies = 0L, fingerprinters = 0L,
                    cryptominers = 0L, social = 0L, bounce = 0L;
            for (TrackingDbEvent e : events) {
                total += e.count;
                switch (e.type) {
                    case TrackingDbEvent.TRACKERS_ID:
                        trackers += e.count;
                        break;
                    case TrackingDbEvent.TRACKING_COOKIES_ID:
                    case TrackingDbEvent.OTHER_COOKIES_BLOCKED_ID:
                        cookies += e.count;
                        break;
                    case TrackingDbEvent.FINGERPRINTERS_ID:
                    case TrackingDbEvent.SUSPICIOUS_FINGERPRINTERS_ID:
                        fingerprinters += e.count;
                        break;
                    case TrackingDbEvent.CRYPTOMINERS_ID:
                        cryptominers += e.count;
                        break;
                    case TrackingDbEvent.SOCIAL_ID:
                        social += e.count;
                        break;
                    case TrackingDbEvent.BOUNCETRACKERS_ID:
                        bounce += e.count;
                        break;
                    default:
                        break;
                }
            }

            if (total <= 0L) {
                section.setVisibility(View.GONE);
                return;
            }
            section.setVisibility(View.VISIBLE);
            allTimeView.setText(formatCount(total));

            breakdown.removeAllViews();
            LayoutInflater inflater = LayoutInflater.from(root.getContext());
            addEtpRow(breakdown, inflater, R.string.trackers_info_etp_cat_trackers, trackers);
            addEtpRow(breakdown, inflater, R.string.trackers_info_etp_cat_cookies, cookies);
            addEtpRow(breakdown, inflater, R.string.trackers_info_etp_cat_fingerprinters, fingerprinters);
            addEtpRow(breakdown, inflater, R.string.trackers_info_etp_cat_cryptominers, cryptominers);
            addEtpRow(breakdown, inflater, R.string.trackers_info_etp_cat_social, social);
            addEtpRow(breakdown, inflater, R.string.trackers_info_etp_cat_bounce, bounce);
        });

        // This week: sum events in the trailing 7-day window.
        controller.getTrackingDbEventsByDateRange(weekAgo, now).accept(events -> {
            if (getView() == null || events == null) return;
            long weekTotal = 0L;
            for (TrackingDbEvent e : events) {
                weekTotal += e.count;
            }
            weekView.setText(formatCount(weekTotal));
        });

        // 'Recording since <date>' — the earliest recorded date keeps the
        // all-time figure honest (recording began when this build enabled
        // the DB, not at install). 0 = nothing recorded yet; leave blank.
        controller.getTrackingDbEarliestRecordedDate().accept(dateMs -> {
            if (getView() == null || dateMs == null || dateMs <= 0L) return;
            String date = DateFormat.getDateInstance(DateFormat.MEDIUM, Locale.getDefault())
                    .format(new Date(dateMs));
            sinceView.setText(getString(R.string.trackers_info_etp_since, date));
        });
    }

    /** Add one category row to the ETP breakdown, skipping empty buckets
     *  so only categories that actually blocked something appear. */
    private void addEtpRow(@NonNull LinearLayout container,
                           @NonNull LayoutInflater inflater,
                           int labelRes, long count) {
        if (count <= 0L) return;
        View row = inflater.inflate(R.layout.item_trackers_etp_row, container, false);
        ((TextView) row.findViewById(R.id.etp_row_label)).setText(labelRes);
        ((TextView) row.findViewById(R.id.etp_row_count)).setText(formatCount(count));
        container.addView(row);
    }

    private static String formatCount(long n) {
        return NumberFormat.getInstance(Locale.getDefault()).format(n);
    }

    private static long getOrZero(@NonNull Map<Category, Long> buckets, @NonNull Category key) {
        Long v = buckets.get(key);
        return v == null ? 0L : v;
    }
}
