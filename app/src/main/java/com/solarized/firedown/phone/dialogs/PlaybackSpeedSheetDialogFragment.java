package com.solarized.firedown.phone.dialogs;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.fragment.app.Fragment;

import com.google.android.material.bottomsheet.BottomSheetBehavior;
import com.google.android.material.bottomsheet.BottomSheetDialogFragment;
import com.google.android.material.radiobutton.MaterialRadioButton;
import com.solarized.firedown.R;
import com.solarized.firedown.phone.player.PlaybackSpeeds;
import com.solarized.firedown.utils.SheetGroups;

import java.util.Locale;

/**
 * The players' Playback speed sheet: one radio row per speed in
 * {@link PlaybackSpeeds#SPEEDS}. A plain {@link BottomSheetDialogFragment}, as
 * the Subtitles sheet is (neither player activity has a NavHostFragment).
 * Its {@link Host} is the fragment that showed it (MediaViewerFragment, in
 * its child fragment manager) or else the activity (the backup stream
 * player), and it reads the player's speed whenever its view is built.
 */
public class PlaybackSpeedSheetDialogFragment extends BottomSheetDialogFragment {

    public static final String TAG = "speed-sheet";

    /** The fragment or activity that shows the sheet. */
    public interface Host {
        float getPlaybackSpeed();

        void onPlaybackSpeedChosen(float speed);
    }

    @Override
    public int getTheme() {
        return R.style.Theme_FireDown_BottomSheetDialogTheme;
    }

    @Nullable
    private Host host() {
        Fragment parent = getParentFragment();
        if (parent instanceof Host) {
            return (Host) parent;
        }
        return getActivity() instanceof Host ? (Host) getActivity() : null;
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.fragment_dialog_player_speed, container, false);
        Host host = host();
        if (host == null) {
            // Restored without its player (process death): nothing to set.
            dismissAllowingStateLoss();
            return view;
        }
        LinearLayout options = view.findViewById(R.id.speed_options);
        int current = PlaybackSpeeds.nearestIndex(host.getPlaybackSpeed());
        Locale locale = Locale.getDefault();
        for (int i = 0; i < PlaybackSpeeds.SPEEDS.length; i++) {
            final float speed = PlaybackSpeeds.SPEEDS[i];
            View row = inflater.inflate(R.layout.item_player_subtitle, options, false);
            TextView label = row.findViewById(R.id.subtitle_label);
            MaterialRadioButton radio = row.findViewById(R.id.subtitle_radio);
            row.findViewById(R.id.subtitle_detail).setVisibility(View.GONE);
            label.setText(speed == PlaybackSpeeds.NORMAL
                    ? getString(R.string.player_speed_normal)
                    : PlaybackSpeeds.label(speed, locale));
            radio.setChecked(i == current);
            row.setOnClickListener(v -> {
                Host chosen = host();
                if (chosen != null) {
                    chosen.onPlaybackSpeedChosen(speed);
                }
                dismissAllowingStateLoss();
            });
            options.addView(row);
        }
        SheetGroups.applyCorners(options);
        return view;
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        // The bottom system-bar inset as padding, as the Subtitles sheet does.
        ViewCompat.setOnApplyWindowInsetsListener(view, (v, windowInsets) -> {
            Insets insets = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars()
                    | WindowInsetsCompat.Type.displayCutout());
            v.setPadding(0, 0, 0, insets.bottom);
            return WindowInsetsCompat.CONSUMED;
        });
    }

    @Override
    public void onStart() {
        super.onStart();
        View view = getView();
        if (view == null || !(view.getParent() instanceof View)) {
            return;
        }
        // Open fully: a landscape player leaves a short viewport.
        BottomSheetBehavior<View> behavior = BottomSheetBehavior.from((View) view.getParent());
        int maxWidthPx = getResources().getDimensionPixelSize(R.dimen.bottom_sheet_max_width);
        behavior.setMaxWidth(maxWidthPx > 0 ? maxWidthPx : -1);
        behavior.setSkipCollapsed(true);
        behavior.setState(BottomSheetBehavior.STATE_EXPANDED);
    }
}
