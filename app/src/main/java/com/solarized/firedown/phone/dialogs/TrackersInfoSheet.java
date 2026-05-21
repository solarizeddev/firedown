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

import com.google.android.material.button.MaterialButton;
import com.solarized.firedown.R;
import com.solarized.firedown.geckoview.GeckoUblockHelper;
import com.solarized.firedown.geckoview.GeckoUblockHelper.Category;
import com.solarized.firedown.phone.SettingsActivity;
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
        View breakdownView = view.findViewById(R.id.trackers_info_breakdown);
        TextView scriptsView = view.findViewById(R.id.trackers_info_breakdown_scripts);
        TextView pixelsView  = view.findViewById(R.id.trackers_info_breakdown_pixels);
        TextView framesView  = view.findViewById(R.id.trackers_info_breakdown_frames);
        TextView otherView   = view.findViewById(R.id.trackers_info_breakdown_other);
        MaterialButton action = view.findViewById(R.id.trackers_info_action);

        mGeckoUblockHelper.getCumulativeBlockedLive().observe(getViewLifecycleOwner(), blocked -> {
            long n = blocked == null ? 0L : blocked;
            if (n <= 0) {
                // Zero-state: avoid '0' as a hero number — it reads
                // as 'protection is broken' rather than 'fresh
                // install with no browsing yet'. Show the same
                // 'Protection active' label the home card falls
                // back to, hide the bytes-saved line and the
                // breakdown rows so the sheet doesn't render four
                // zeroes that would imply nothing is being blocked.
                countView.setText(R.string.home_trackers_subtitle_idle);
                savedView.setVisibility(View.GONE);
                breakdownView.setVisibility(View.GONE);
                return;
            }
            countView.setText(NumberFormat.getInstance(Locale.getDefault()).format(n));
            savedView.setVisibility(View.VISIBLE);
            savedView.setText(getString(R.string.trackers_info_saved,
                    Utils.readableFileSize(n * AVG_BYTES_PER_BLOCKED_REQUEST)));
            breakdownView.setVisibility(View.VISIBLE);
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

        // 'Today' line — hidden at zero so it doesn't render a redundant
        // '0 today' on first launch or a quiet day.
        mGeckoUblockHelper.getTodayBlockedLive().observe(getViewLifecycleOwner(), today -> {
            long n = today == null ? 0L : today;
            if (n <= 0) {
                todayView.setVisibility(View.GONE);
                return;
            }
            todayView.setVisibility(View.VISIBLE);
            todayView.setText(getString(R.string.trackers_info_today,
                    NumberFormat.getInstance(Locale.getDefault()).format(n)));
        });

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
