package com.solarized.firedown.ui;

import android.content.Context;
import android.content.Intent;
import android.graphics.drawable.ColorDrawable;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.PopupWindow;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.lifecycle.LifecycleOwner;
import androidx.lifecycle.LiveData;
import androidx.recyclerview.widget.RecyclerView;

import com.solarized.firedown.R;
import com.solarized.firedown.data.entity.DownloadEntity;
import com.solarized.firedown.phone.DownloadsActivity;
import com.solarized.firedown.ui.adapters.DownloadsQuickAccessAdapter;

import java.util.List;

/**
 * Quick-access popup shown when the user long-presses the bottom
 * bar's Downloads button. Surfaces up to four most-recent finished
 * regular downloads as thumbnail tiles; a tap opens the file, "View
 * all" launches DownloadsActivity. Used only from regular-mode
 * fragments — vault / incognito surfaces deliberately skip it so a
 * long-press doesn't leak private file names into a public popup.
 *
 * <p>One-shot per long-press: build, observe, show, dismiss. Holding
 * onto a {@code Lifecycle}-bound observer ensures the popup tears
 * itself down with the host fragment if the user backgrounds the app
 * while it's open.</p>
 */
public class DownloadsQuickAccessPopup {

    /** Hard cap matching {@code RecentDownloadsViewModel.LIMIT}. The
     *  ViewModel already issues a {@code LIMIT :n} query, so this
     *  trim() is a belt-and-braces guard in case a future caller
     *  passes a larger LiveData by mistake. */
    private static final int MAX_TILES = 4;

    public interface Host {
        /** Called when the user taps a tile — host runs the open-item flow. */
        void onQuickAccessFileTap(@NonNull DownloadEntity entity);
    }

    private DownloadsQuickAccessPopup() {}

    /**
     * Anchors a popup above {@code anchor}. {@code recent} is observed
     * for the popup's lifetime; the popup hides itself if the list
     * goes empty after open. Returns {@code false} when there's
     * nothing recent to display so the caller can fall back to its
     * default long-press behaviour (typically: do the single-tap
     * action instead, so the user isn't left wondering whether the
     * long-press registered).
     */
    public static boolean show(@NonNull Context context,
                               @NonNull LifecycleOwner lifecycleOwner,
                               @NonNull LiveData<List<DownloadEntity>> recent,
                               @NonNull View anchor,
                               @NonNull Host host) {

        List<DownloadEntity> initial = recent.getValue();
        if (initial == null || initial.isEmpty()) {
            return false;
        }

        View content = LayoutInflater.from(context)
                .inflate(R.layout.popup_downloads_quick_access, null, false);

        PopupWindow window = new PopupWindow(content,
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
                true);
        window.setOutsideTouchable(true);
        window.setBackgroundDrawable(new ColorDrawable(android.graphics.Color.TRANSPARENT));
        window.setElevation(context.getResources().getDisplayMetrics().density * 8f);

        RecyclerView recycler = content.findViewById(R.id.quick_access_recycler);
        DownloadsQuickAccessAdapter adapter = new DownloadsQuickAccessAdapter(entity -> {
            host.onQuickAccessFileTap(entity);
            window.dismiss();
        });
        recycler.setAdapter(adapter);
        adapter.submitList(trim(initial));

        content.findViewById(R.id.quick_access_view_all).setOnClickListener(v -> {
            context.startActivity(new Intent(context, DownloadsActivity.class));
            window.dismiss();
        });

        recent.observe(lifecycleOwner, list -> {
            if (list == null || list.isEmpty()) {
                window.dismiss();
                return;
            }
            adapter.submitList(trim(list));
        });

        window.setOnDismissListener(() -> recent.removeObservers(lifecycleOwner));

        // Anchor above the bar button. PopupWindow.showAsDropDown
        // measures from the anchor's bottom-left by default; we want
        // the popup above and slightly inset so it doesn't smash into
        // the screen edge — measure the content first so we know how
        // much to lift by.
        content.measure(View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
        int yOffset = -(anchor.getHeight() + content.getMeasuredHeight());
        window.showAsDropDown(anchor, 0, yOffset, Gravity.START);
        return true;
    }

    @NonNull
    private static List<DownloadEntity> trim(@NonNull List<DownloadEntity> list) {
        return list.size() > MAX_TILES ? list.subList(0, MAX_TILES) : list;
    }
}
