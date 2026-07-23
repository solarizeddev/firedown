package com.solarized.firedown.phone.dialogs;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.FragmentManager;

import com.google.android.material.progressindicator.LinearProgressIndicator;
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
 * Tracking-protection detail sheet — Firedown's take on Firefox's
 * "Trackers blocked this week" protection panel, in our tonal style.
 * Opened by tapping the status row on the Enhanced Tracking Protection
 * settings screen. Everything is sourced from Gecko's persisted ETP
 * database (ContentBlockingController): a this-week hero total, a
 * per-category breakdown (icon + count + a proportion bar, one row per
 * category, zeroes included), and an all-time "N blocked since &lt;date&gt;"
 * footer.
 *
 * <p>ONE range query (0..now) yields everything — each {@link TrackingDbEvent}
 * carries a "YYYY-MM-DD" date and a count, so the this-week window is a
 * lexicographic date compare, the categories are folded from the DB's
 * finer-grained types (mirroring Firefox's TrackerBuckets), and the
 * all-time total + earliest date come from the same pass. The
 * {@code GeckoResult} callback is attached from the main thread, so it
 * dispatches there and may touch views; guarded on {@link #getView()}.
 * Counts use plurals, matching Firefox.</p>
 */
@AndroidEntryPoint
public class TrackingDetailSheet extends BaseBottomSheetDialogFragment {

    private static final String TAG = "TrackingDetailSheet";
    private static final long DAY_MS = 24L * 60L * 60L * 1000L;

    /** Category rows, in display order (matches Firefox's panel):
     *  cookies, social, fingerprinters, cryptominers, tracking content. */
    private static final int[] CAT_ICON = {
            R.drawable.cookie_24,
            R.drawable.ic_share_24,
            R.drawable.fingerprint_24,
            R.drawable.ic_memory_24,
            R.drawable.ic_baseline_image_24
    };
    private static final int[] CAT_PLURAL = {
            R.plurals.tracking_detail_cookies,
            R.plurals.tracking_detail_social,
            R.plurals.tracking_detail_fingerprinters,
            R.plurals.tracking_detail_cryptominers,
            R.plurals.tracking_detail_content
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
        String weekKey = dayFmt.format(new Date(now - 7L * DAY_MS));

        runtime.getContentBlockingController().getTrackingDbEventsByDateRange(0L, now).accept(events -> {
            if (getView() == null || events == null) return;

            long[] week = new long[CAT_ICON.length];
            long allTime = 0L;
            String minDate = null;
            for (TrackingDbEvent e : events) {
                allTime += e.count;
                if (e.date != null) {
                    if (minDate == null || e.date.compareTo(minDate) < 0) minDate = e.date;
                    if (e.date.compareTo(weekKey) >= 0) {
                        int cat = categoryOf(e.type);
                        if (cat >= 0) week[cat] += e.count;
                    }
                }
            }

            long weekTotal = 0L, maxCat = 0L;
            for (long w : week) {
                weekTotal += w;
                if (w > maxCat) maxCat = w;
            }

            ((TextView) root.findViewById(R.id.td_count))
                    .setText(NumberFormat.getInstance(Locale.getDefault()).format(weekTotal));

            LinearLayout list = root.findViewById(R.id.td_list);
            list.removeAllViews();
            LayoutInflater inflater = LayoutInflater.from(root.getContext());
            for (int i = 0; i < CAT_ICON.length; i++) {
                View rowView = inflater.inflate(R.layout.item_tracking_detail_category, list, false);
                ((ImageView) rowView.findViewById(R.id.tdc_icon)).setImageResource(CAT_ICON[i]);
                ((TextView) rowView.findViewById(R.id.tdc_label)).setText(
                        getResources().getQuantityString(CAT_PLURAL[i], (int) week[i], (int) week[i]));
                LinearProgressIndicator bar = rowView.findViewById(R.id.tdc_bar);
                if (week[i] > 0L && maxCat > 0L) {
                    bar.setProgress((int) Math.round(week[i] * 100.0 / maxCat));
                    bar.setVisibility(View.VISIBLE);
                } else {
                    bar.setVisibility(View.GONE);
                }
                list.addView(rowView);
            }

            long sinceMs = 0L;
            if (minDate != null) {
                try {
                    Date d = dayFmt.parse(minDate);
                    if (d != null) sinceMs = d.getTime();
                } catch (ParseException ignored) {
                    // Leave sinceMs 0 — the footer falls back to a plain count.
                }
            }

            TextView since = root.findViewById(R.id.td_since);
            if (sinceMs > 0L) {
                String date = DateFormat.getDateInstance(DateFormat.MEDIUM, Locale.getDefault())
                        .format(new Date(sinceMs));
                since.setText(getResources().getQuantityString(
                        R.plurals.tracking_detail_since, (int) allTime, (int) allTime, date));
            } else {
                since.setText(getResources().getQuantityString(
                        R.plurals.tracking_detail_blocked, (int) allTime, (int) allTime));
            }
        });
    }

    /** Fold the DB's finer-grained tracker types into the five displayed
     *  categories (Firefox's TrackerBuckets grouping). -1 = not shown. */
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
