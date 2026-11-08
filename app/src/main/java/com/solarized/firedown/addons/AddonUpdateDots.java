package com.solarized.firedown.addons;

import android.view.View;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.solarized.firedown.R;

import java.util.List;

/**
 * The menus' Add-ons row when an add-on update waits for the user's OK
 * and they have not seen its question: the coral dot at the row's end
 * (popup_addons_dot), and the same fact in words for a screen reader, since
 * a dot alone is colour only. The bottom bar's ⋮ carries the same dot
 * (BottomNavigationBar.setAddonAttention).
 */
public final class AddonUpdateDots {

    private AddonUpdateDots() {}

    /** {@code card}: the popup's popup_addons row. */
    public static void bindMenuRow(@NonNull View card, @Nullable List<String> attention) {
        boolean on = attention != null && !attention.isEmpty();
        View dot = card.findViewById(R.id.popup_addons_dot);
        if (dot != null) {
            dot.setVisibility(on ? View.VISIBLE : View.GONE);
        }
        TextView label = card.findViewById(R.id.popup_addons_label);
        CharSequence name = label != null ? label.getText() : card.getContext().getString(R.string.addons_title);
        card.setContentDescription(on
                ? name + ", " + card.getContext().getString(R.string.addons_update_review) : null);
    }
}
