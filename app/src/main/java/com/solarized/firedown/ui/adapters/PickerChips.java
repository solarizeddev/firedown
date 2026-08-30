package com.solarized.firedown.ui.adapters;

import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.text.style.RelativeSizeSpan;

import com.google.android.material.chip.ChipGroup;
import com.google.android.material.color.MaterialColors;

/**
 * Shared label shaping for the variant picker's chips — ONE definition so the
 * audio-track and caption chips can't drift apart (the compactDuration rule).
 */
final class PickerChips {

    private PickerChips() {
    }

    /**
     * "{@code <base> · <suffix>}" with the suffix DIM (onSurfaceVariant, 0.9x)
     * — the sketch's two-tone chip label. The base text carries the chip's
     * checked ink swap (its textColor selector); the suffix stays quiet in
     * both states, so "· auto-generated" / "· Original" reads as metadata
     * rather than part of the name.
     */
    static CharSequence withDimSuffix(ChipGroup group, String base, String suffix) {
        SpannableStringBuilder text = new SpannableStringBuilder(base);
        int start = text.length();
        text.append(" · ").append(suffix);
        int color = MaterialColors.getColor(group,
                com.google.android.material.R.attr.colorOnSurfaceVariant);
        text.setSpan(new ForegroundColorSpan(color),
                start, text.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        text.setSpan(new RelativeSizeSpan(0.9f),
                start, text.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        return text;
    }
}
