package com.solarized.firedown.settings.ui;

import android.content.Context;
import android.content.res.ColorStateList;
import android.content.res.Resources;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.RippleDrawable;
import android.text.TextUtils;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;

import androidx.annotation.NonNull;
import androidx.preference.Preference;
import androidx.preference.PreferenceCategory;
import androidx.preference.PreferenceGroup;
import androidx.preference.PreferenceGroupAdapter;
import androidx.preference.PreferenceViewHolder;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.color.MaterialColors;
import com.google.android.material.shape.MaterialShapeDrawable;
import com.solarized.firedown.R;
import com.solarized.firedown.utils.SheetGroups;

/**
 * The settings screens' GROUPED CARDS — the same shape every bottom sheet
 * already wears (SheetGroups: 16dp outer corners, 4dp between rows, 2dp
 * apart), applied to the flattened preference list instead of a layout.
 *
 * <p>A GROUP is a maximal run of consecutive card rows in adapter order. A
 * {@link PreferenceCategory} ends one (its header renders as the label above
 * the next group; a TITLE-LESS category renders as a bare 16dp break — the
 * Cloud screen's unlabelled groups), and so does a {@link FlatPreference}
 * (the Cloud hero and CTA, the tracking count, the DoH field, footnotes),
 * which keeps its own full-width layout. Everything else is a card: it gets
 * the 16dp side gutter as item margins, a 2dp top margin unless it opens its
 * group, and a ripple-over-shape background whose corners follow its place
 * in the group. The ripple uses the shape as its mask (no mask layer, so
 * the opaque content layer masks it), so a press never spills past the
 * rounded corners.</p>
 *
 * <p>Corners depend on the NEIGHBOURS, so a row whose neighbour appears or
 * disappears must rebind even though it did not change. The stock adapter
 * answers every hierarchy / visibility change with notifyDataSetChanged
 * (it only diffs when a PreferenceComparisonCallback is installed, and none
 * is), which rebinds everything; the observer below covers the diffing path
 * too, so the rule "every visibility toggle fixes the corners" holds either
 * way — the settings twin of SheetGroups.applyCorners.</p>
 */
public class CardPreferenceGroupAdapter extends PreferenceGroupAdapter {

    /** Payload for the corners-only rebind, so the item animator rebinds in place. */
    private static final Object CORNERS_PAYLOAD = new Object();

    public CardPreferenceGroupAdapter(@NonNull PreferenceGroup preferenceGroup) {
        super(preferenceGroup);
        // Registered before the RecyclerView's own observer, so it is called
        // AFTER it (AdapterDataObservable notifies in reverse registration
        // order): the structural op is queued first, then this rebind.
        registerAdapterDataObserver(new NeighbourObserver());
    }

    @Override
    public void onBindViewHolder(@NonNull PreferenceViewHolder holder, int position) {
        super.onBindViewHolder(holder, position);
        Preference preference = getItem(position);
        if (preference instanceof PreferenceCategory) {
            bindCategory(holder.itemView, preference, position);
        } else if (isCard(preference)) {
            bindCard(holder, position);
        }
        // A FlatPreference keeps its own layout untouched: margins, padding
        // and background are whatever its XML says.
    }

    private boolean isCard(Preference preference) {
        return preference != null
                && !(preference instanceof PreferenceGroup)
                && !(preference instanceof FlatPreference);
    }

    private boolean isCardAt(int position) {
        if (position < 0 || position >= getItemCount()) {
            return false;
        }
        return isCard(getItem(position));
    }

    private void bindCategory(View item, Preference category, int position) {
        Resources res = item.getResources();
        boolean titled = !TextUtils.isEmpty(category.getTitle());
        int top;
        int bottom;
        if (titled) {
            top = res.getDimensionPixelSize(position == 0
                    ? R.dimen.settings_category_padding_top_first
                    : R.dimen.settings_category_padding_top);
            bottom = res.getDimensionPixelSize(R.dimen.settings_category_padding_bottom);
        } else {
            // A bare group break. At the very top there is nothing to break
            // from, so it collapses to the first-label inset.
            top = res.getDimensionPixelSize(position == 0
                    ? R.dimen.settings_category_padding_top_first
                    : R.dimen.settings_group_gap);
            bottom = 0;
        }
        item.setPaddingRelative(item.getPaddingStart(), top, item.getPaddingEnd(), bottom);
    }

    private void bindCard(PreferenceViewHolder holder, int position) {
        View item = holder.itemView;
        Resources res = item.getResources();
        boolean first = !isCardAt(position - 1);
        boolean last = !isCardAt(position + 1);

        int gutter = res.getDimensionPixelSize(R.dimen.settings_card_gutter);
        int gap = first ? 0 : res.getDimensionPixelSize(R.dimen.sheet_row_gap);
        setMargins(item, gutter, gap, gutter);

        // resetState() put the inflated background back before this bind,
        // so the card background is set on every bind; the tag only saves
        // rebuilding the drawable when the row's group position is unchanged.
        CardBackground current = (CardBackground) item.getTag(R.id.settings_card_background);
        if (current == null || current.first != first || current.last != last) {
            current = new CardBackground(first, last, buildBackground(item, first, last));
            item.setTag(R.id.settings_card_background, current);
        }
        item.setBackground(current.drawable);

        // The stock 56dp icon frame leaves a 32dp hole after the 24dp icon,
        // which inside a 16dp-inset card pushes the title to 88dp from the
        // screen edge; 40dp keeps the 16dp icon gap of the sheet rows. The
        // frame is the icon's parent in every stock row layout, and a hidden
        // frame (iconSpaceReserved=false, no icon) ignores its min width.
        View icon = holder.findViewById(android.R.id.icon);
        if (icon != null) {
            ViewParent frame = icon.getParent();
            if (frame instanceof View frameView) {
                frameView.setMinimumWidth(res.getDimensionPixelSize(R.dimen.settings_card_icon_frame));
            }
        }
    }

    private static void setMargins(View item, int side, int top, int bottom) {
        ViewGroup.LayoutParams params = item.getLayoutParams();
        if (!(params instanceof ViewGroup.MarginLayoutParams margins)) {
            return;
        }
        if (margins.getMarginStart() == side && margins.getMarginEnd() == side
                && margins.topMargin == top && margins.bottomMargin == bottom) {
            return;
        }
        margins.setMarginStart(side);
        margins.setMarginEnd(side);
        margins.topMargin = top;
        margins.bottomMargin = bottom;
        item.setLayoutParams(margins);
    }

    @NonNull
    private static Drawable buildBackground(View item, boolean first, boolean last) {
        Context context = item.getContext();
        MaterialShapeDrawable fill = new MaterialShapeDrawable(SheetGroups.shape(context, first, last));
        fill.setFillColor(ColorStateList.valueOf(MaterialColors.getColor(item,
                com.google.android.material.R.attr.colorSurfaceContainerHigh)));
        int ripple = MaterialColors.getColor(item, android.R.attr.colorControlHighlight);
        return new RippleDrawable(ColorStateList.valueOf(ripple), fill, null);
    }

    private static final class CardBackground {
        final boolean first;
        final boolean last;
        final Drawable drawable;

        CardBackground(boolean first, boolean last, Drawable drawable) {
            this.first = first;
            this.last = last;
            this.drawable = drawable;
        }
    }

    /**
     * A row inserted / removed / moved changes its neighbours' corners
     * without changing them, so every structural change rebinds the list
     * with a payload (in place, no crossfade). Settings lists are short.
     */
    private final class NeighbourObserver extends RecyclerView.AdapterDataObserver {
        @Override
        public void onItemRangeInserted(int positionStart, int itemCount) {
            rebindAll();
        }

        @Override
        public void onItemRangeRemoved(int positionStart, int itemCount) {
            rebindAll();
        }

        @Override
        public void onItemRangeMoved(int fromPosition, int toPosition, int itemCount) {
            rebindAll();
        }

        private void rebindAll() {
            int count = getItemCount();
            if (count > 0) {
                notifyItemRangeChanged(0, count, CORNERS_PAYLOAD);
            }
        }
    }
}
