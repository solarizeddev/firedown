package com.solarized.firedown.phone.dialogs;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.FragmentManager;

import com.solarized.firedown.R;

import org.mozilla.geckoview.ContentBlockingController.TrackingDbEvent;
import org.mozilla.geckoview.GeckoRuntime;

import java.text.DateFormat;
import java.text.NumberFormat;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

import dagger.hilt.android.AndroidEntryPoint;

/**
 * Tracking-protection detail sheet, styled to match the Home trackers sheet:
 * a single total counter, a "Blocked by type" header, and clean label -> count
 * rows (no hero glyph, no progress bars). Opened by tapping the status row on
 * the Enhanced Tracking Protection settings screen; all data is Gecko's
 * persisted ETP database (ContentBlockingController).
 *
 * <p>ONE range query (0..now) yields the all-time total, the per-category
 * breakdown (the DB's finer types folded into the five displayed categories,
 * mirroring Firefox's TrackerBuckets), and the earliest recorded date for the
 * "Recording since" line. The {@code GeckoResult} callback is attached from the
 * main thread, so it dispatches there and may touch views; guarded on
 * {@link #getView()}.</p>
 */
@AndroidEntryPoint
public class TrackingDetailSheet extends BaseBottomSheetDialogFragment {

    private static final String TAG = "TrackingDetailSheet";

    /** Count views, in the layout's row order:
     *  cookies, social, fingerprinters, cryptominers, tracking content. */
    private static final int[] CAT_COUNT_VIEW = {
            R.id.td_cnt_cookies,
            R.id.td_cnt_social,
            R.id.td_cnt_fp,
            R.id.td_cnt_crypto,
            R.id.td_cnt_content
    };

    public static void show(@NonNull FragmentManager fm) {
        if (fm.findFragmentByTag(TAG) != null) return;
        if (fm.isStateSaved()) return;
        new TrackingDetailSheet().show(fm, TAG);
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        LayoutInflater themed = container != null
                ? LayoutInflater.from(container.getContext())
                : inflater;
        mView = themed.inflate(R.layout.fragment_dialog_tracking_detail, container, false);
        return mView;
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        load(view);
    }

    private void load(@NonNull View root) {
        GeckoRuntime runtime = mGeckoRuntimeHelper.getGeckoRuntime();
        if (runtime == null) return;

        long now = System.currentTimeMillis();
        SimpleDateFormat dayFmt = new SimpleDateFormat("yyyy-MM-dd", Locale.US);

        runtime.getContentBlockingController().getTrackingDbEventsByDateRange(0L, now).accept(events -> {
            if (getView() == null || events == null) return;

            long[] cat = new long[CAT_COUNT_VIEW.length];
            long total = 0L;
            String minDate = null;
            for (TrackingDbEvent e : events) {
                total += e.count;
                int c = categoryOf(e.type);
                if (c >= 0) cat[c] += e.count;
                if (e.date != null && (minDate == null || e.date.compareTo(minDate) < 0)) {
                    minDate = e.date;
                }
            }

            NumberFormat nf = NumberFormat.getInstance(Locale.getDefault());
            ((TextView) root.findViewById(R.id.td_total)).setText(nf.format(total));
            for (int i = 0; i < CAT_COUNT_VIEW.length; i++) {
                ((TextView) root.findViewById(CAT_COUNT_VIEW[i])).setText(nf.format(cat[i]));
            }

            TextView since = root.findViewById(R.id.td_since);
            long sinceMs = 0L;
            if (minDate != null) {
                try {
                    Date d = dayFmt.parse(minDate);
                    if (d != null) sinceMs = d.getTime();
                } catch (ParseException ignored) {
                    // Leave sinceMs 0 — the 'since' line is hidden.
                }
            }
            if (sinceMs > 0L) {
                String date = DateFormat.getDateInstance(DateFormat.MEDIUM, Locale.getDefault())
                        .format(new Date(sinceMs));
                since.setText(getString(R.string.tracking_status_since, date));
                since.setVisibility(View.VISIBLE);
            } else {
                since.setVisibility(View.GONE);
            }
        });
    }

    /** Fold the DB's finer tracker types into the five displayed categories
     *  (Firefox's TrackerBuckets grouping). -1 = not shown. */
    private static int categoryOf(int type) {
        switch (type) {
            case TrackingDbEvent.OTHER_COOKIES_BLOCKED_ID:
            case TrackingDbEvent.TRACKING_COOKIES_ID:
                return 0; // cross-site tracking cookies
            case TrackingDbEvent.SOCIAL_ID:
                return 1; // social media trackers
            case TrackingDbEvent.FINGERPRINTERS_ID:
            case TrackingDbEvent.SUSPICIOUS_FINGERPRINTERS_ID:
                return 2; // fingerprinters
            case TrackingDbEvent.CRYPTOMINERS_ID:
                return 3; // cryptominers
            case TrackingDbEvent.TRACKERS_ID:
            case TrackingDbEvent.BOUNCETRACKERS_ID:
                return 4; // tracking content (incl. redirect/bounce)
            default:
                return -1;
        }
    }
}
