package com.solarized.firedown.addons;

import android.view.View;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.solarized.firedown.R;

import java.util.List;

/**
 * A menu row's dot when an add-on update waits for the user's OK and they
 * have not seen its question: the ⋮ menus' Add-ons row (popup_addons_dot)
 * and the Add-ons sheet's "Manage add-ons" (addon_actions_manage_dot). The
 * same fact goes in words to a screen reader, since a dot alone is colour
 * only. The bottom bar's ⋮ carries the same dot
 * (BottomNavigationBar.setAddonAttention).
 */
public final class AddonUpdateDots {

    /** The dot's end margin in the layouts, its size, and a gap before it. */
    private static final int DOT_ROOM_DP = 24 + 8 + 8;

    private AddonUpdateDots() {}

    /** {@code card}: a popup's popup_addons row. */
    public static void bindMenuRow(@NonNull View card, @Nullable List<String> attention) {
        show(card, card.findViewById(R.id.popup_addons_dot), card.findViewById(R.id.popup_addons_label),
                attention != null && !attention.isEmpty());
    }

    /**
     * Shows or hides {@code dot} on {@code card}. The label is one line,
     * ellipsized: while the dot shows it ends before the dot, so a long
     * translation is cut rather than run under it.
     */
    public static void show(@NonNull View card, @Nullable View dot, @Nullable TextView label, boolean on) {
        if (dot != null) {
            dot.setVisibility(on ? View.VISIBLE : View.GONE);
        }
        if (label != null) {
            Object own = label.getTag(R.id.addon_dot_label_padding);
            if (own == null) {
                own = label.getPaddingEnd();
                label.setTag(R.id.addon_dot_label_padding, own);
            }
            int end = on ? Math.round(DOT_ROOM_DP * label.getResources().getDisplayMetrics().density)
                    : (Integer) own;
            label.setPaddingRelative(label.getPaddingStart(), label.getPaddingTop(), end, label.getPaddingBottom());
        }
        CharSequence name = label != null ? label.getText() : null;
        card.setContentDescription(on && name != null
                ? name + ", " + card.getContext().getString(R.string.addons_update_review) : null);
    }
}
