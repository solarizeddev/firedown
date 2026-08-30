package com.solarized.firedown.ui.adapters;

import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.android.material.chip.Chip;
import com.google.android.material.chip.ChipGroup;
import com.solarized.firedown.R;
import com.solarized.firedown.data.entity.AudioTrackEntity;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Single-select audio-track CHIPS for a multi-audio-track video (YouTube
 * auto-dubbing), shown inside the variant picker between the quality tiles
 * and the captions. Chips, not tiles: a dub name ("Español (Latinoamérica)")
 * clips in a fixed grid column but flows at its natural width as a chip.
 * The checked state carries selection (the tonal picker-chip treatment, no
 * radio); the group's {@code selectionRequired} keeps exactly one checked.
 *
 * <p>Not a RecyclerView adapter any more — the class populates a
 * {@link ChipGroup} and keeps the selection index; the fragment's contract
 * ({@link #getSelectedTrack()} / {@link #isNonDefaultSelected()}) is
 * unchanged. The list arrives original-track-first (background.js
 * buildAudioTrackOptions), so position 0 is the preselected default and a
 * no-op confirms the original language.</p>
 */
public class BrowserOptionAudioTrackAdapter {

    private final List<AudioTrackEntity> mTracks;
    private int mSelectedPosition = 0;

    public BrowserOptionAudioTrackAdapter(@NonNull List<AudioTrackEntity> tracks) {
        mTracks = new ArrayList<>(tracks);
    }

    /** The track the user has selected (position 0 = the original default). */
    @Nullable
    public AudioTrackEntity getSelectedTrack() {
        if (mSelectedPosition < 0 || mSelectedPosition >= mTracks.size()) return null;
        return mTracks.get(mSelectedPosition);
    }

    /** True when the selection differs from the preselected default track. */
    public boolean isNonDefaultSelected() {
        return mSelectedPosition > 0;
    }

    /**
     * Builds one chip per track into the group, original first and checked.
     * Selection is tracked by CHILD INDEX (chip ids are generated), which is
     * stable — the group is populated once per picker.
     */
    public void attachTo(@NonNull ChipGroup group) {
        group.removeAllViews();
        LayoutInflater inflater = LayoutInflater.from(group.getContext());
        for (int i = 0; i < mTracks.size(); i++) {
            Chip chip = (Chip) inflater.inflate(R.layout.item_picker_chip, group, false);
            chip.setId(View.generateViewId());
            // Single-select: the fill carries the choice, like the quality
            // tiles beside it — no per-chip check tick.
            chip.setCheckedIconVisible(false);
            chip.setText(chipLabel(group, mTracks.get(i)));
            group.addView(chip);
        }
        if (group.getChildCount() > 0) {
            int checked = Math.min(mSelectedPosition, group.getChildCount() - 1);
            ((Chip) group.getChildAt(checked)).setChecked(true);
        }
        group.setOnCheckedStateChangeListener((g, checkedIds) -> {
            // selectionRequired keeps the list non-empty after init.
            if (checkedIds.isEmpty()) {
                return;
            }
            View checkedChip = g.findViewById(checkedIds.get(0));
            int position = g.indexOfChild(checkedChip);
            if (position >= 0) {
                mSelectedPosition = position;
            }
        });
    }

    /**
     * Chip label: the track's display name (falling back to the localized
     * language name resolved from the id's BCP-47 part, then the raw id),
     * with an explicit " · Original" suffix on the default — the display
     * name usually says it, but not in every locale, and the mark is what
     * tells the dub chips apart at a glance. The bare BCP-47 code the old
     * list row's meta line carried is dropped: it repeated the name.
     */
    private static CharSequence chipLabel(ChipGroup group, AudioTrackEntity track) {
        String name = track.getName();
        if (TextUtils.isEmpty(name)) {
            String code = track.getLanguageCode();
            name = !TextUtils.isEmpty(code)
                    ? Locale.forLanguageTag(code).getDisplayName()
                    : track.getId();
        }
        if (track.isOriginal()) {
            return PickerChips.withDimSuffix(group, name, group.getResources()
                    .getString(R.string.audio_track_original_label));
        }
        return name;
    }
}
