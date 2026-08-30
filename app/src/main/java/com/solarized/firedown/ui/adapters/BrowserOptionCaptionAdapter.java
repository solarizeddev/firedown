package com.solarized.firedown.ui.adapters;

import android.text.TextUtils;
import android.util.SparseBooleanArray;
import android.view.LayoutInflater;
import android.view.View;

import androidx.annotation.NonNull;

import com.google.android.material.chip.Chip;
import com.google.android.material.chip.ChipGroup;
import com.solarized.firedown.R;
import com.solarized.firedown.data.entity.BrowserDownloadEntity;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Multi-select caption CHIPS for one video, shown inside the variant picker
 * below the quality tiles. Check-chips wrap ~3 per row where the old list
 * spent a 56dp row per language, so YouTube's 50-language case is ~17 rows
 * instead of 50. Each chip toggles inclusion; the parent fragment reads
 * {@link #getSelected()} when the user taps Download.
 *
 * <p>Not a RecyclerView adapter any more — the class populates a
 * {@link ChipGroup}; the fragment's contract ({@link #preselectLanguages} /
 * {@link #getSelected}) is unchanged. Selection is held in a
 * {@link SparseBooleanArray} keyed by position — the track list is fixed for
 * the lifetime of the picker so positional keys are stable.</p>
 */
public class BrowserOptionCaptionAdapter {

    /** Pattern stripping the " [lang]" suffix appended by GeckoInspectTask
     *  so the chip label shows the human-readable display name only.
     *  Greedy at the tail end of the string, matches "[en]", "[es-419]",
     *  "[en-auto]" — the same shapes we emit. */
    private static final Pattern LANG_SUFFIX = Pattern.compile("\\s*\\[[A-Za-z0-9-]+]\\s*$");

    private final List<BrowserDownloadEntity> mItems;
    private final SparseBooleanArray mSelected = new SparseBooleanArray();

    public BrowserOptionCaptionAdapter(@NonNull List<BrowserDownloadEntity> items) {
        this.mItems = items;
    }

    /** Preselect the rows whose language code matches any in the given set
     *  (e.g. the user's locale + English). No-op if the set is empty. Call
     *  BEFORE {@link #attachTo} so the chips build with the ticks in place. */
    public void preselectLanguages(@NonNull List<String> bcp47Codes) {
        if (bcp47Codes.isEmpty()) return;
        for (int i = 0; i < mItems.size(); i++) {
            String code = extractLangCode(mItems.get(i));
            if (code != null && bcp47Codes.contains(code)) {
                mSelected.put(i, true);
            }
        }
    }

    /** Entities the user has ticked. Order matches track-list position. */
    @NonNull
    public List<BrowserDownloadEntity> getSelected() {
        List<BrowserDownloadEntity> out = new ArrayList<>();
        for (int i = 0; i < mItems.size(); i++) {
            if (mSelected.get(i)) out.add(mItems.get(i));
        }
        return out;
    }

    /** Builds one check-chip per caption track into the group. */
    public void attachTo(@NonNull ChipGroup group) {
        group.removeAllViews();
        LayoutInflater inflater = LayoutInflater.from(group.getContext());
        for (int i = 0; i < mItems.size(); i++) {
            Chip chip = (Chip) inflater.inflate(R.layout.item_picker_chip, group, false);
            chip.setId(View.generateViewId());
            // Multi-select: the tick is the affordance that says several can
            // be picked, unlike the single-select audio chips above.
            chip.setCheckedIconVisible(true);
            chip.setText(chipLabel(group, mItems.get(i)));
            chip.setChecked(mSelected.get(i));
            final int position = i;
            chip.setOnCheckedChangeListener(
                    (button, checked) -> mSelected.put(position, checked));
            group.addView(chip);
        }
    }

    /**
     * Chip label: the localised language name (from the BCP-47 code the
     * parser appended to the filename), with a " · auto-generated" suffix
     * when the track is ASR — the one caption fact that changes a decision
     * (manual EN vs generated EN). The bare code the old list row's meta
     * line carried is dropped: it repeated the name. Falls back to the
     * entity filename (lang suffix stripped) when no code is recoverable.
     */
    private static CharSequence chipLabel(ChipGroup group, BrowserDownloadEntity entity) {
        String langCode = extractLangCode(entity);
        boolean isAuto = langCode != null && langCode.endsWith("-auto");
        String displayCode = isAuto
                ? langCode.substring(0, langCode.length() - 5)
                : langCode;

        String label = null;
        if (!TextUtils.isEmpty(displayCode)) {
            label = new Locale(displayCode).getDisplayName();
        }
        if (TextUtils.isEmpty(label)) {
            label = stripLangSuffix(entity.getFileName());
        }
        if (isAuto) {
            return PickerChips.withDimSuffix(group, label,
                    group.getResources().getString(R.string.caption_auto_label));
        }
        return label;
    }

    /** Pulls the BCP-47 tag out of the filename suffix the parser appended
     *  ("Title [en-auto].srt" → "en-auto"). Returns null when absent so the
     *  caller can fall back to the raw filename. */
    private static String extractLangCode(BrowserDownloadEntity entity) {
        String name = entity.getFileName();
        if (TextUtils.isEmpty(name)) return null;
        Matcher m = Pattern.compile("\\[([A-Za-z0-9-]+)]").matcher(name);
        String last = null;
        while (m.find()) last = m.group(1);
        return last;
    }

    private static String stripLangSuffix(String name) {
        if (TextUtils.isEmpty(name)) return "";
        return LANG_SUFFIX.matcher(name).replaceAll("");
    }
}
