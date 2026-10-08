package com.solarized.firedown.utils;

import android.content.Context;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.IdRes;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.android.material.card.MaterialCardView;
import com.google.android.material.shape.CornerFamily;
import com.google.android.material.shape.ShapeAppearanceModel;
import com.solarized.firedown.R;

/**
 * Corner bookkeeping for the GROUPED CARD ROWS every bottom sheet uses
 * (the security sheet's shape, generalised): rows of one group are
 * selectable {@link MaterialCardView}s on colorSurfaceContainerLow, 2dp
 * apart, and the group reads as one block because its OUTER corners are
 * 16dp while the corners BETWEEN rows are 4dp — the Material 3
 * Expressive / Android-16-Settings grouping. A layout can declare that
 * statically ({@code ShapeAppearanceOverlay.FireDown.SheetGroupTop} /
 * {@code …Bottom} / {@code …Middle} / {@code …Single}), but a group whose
 * rows HIDE at runtime (the popups' Translate / Quit / Downloads / Safe
 * Folder rows, the certificate sheet's empty fields, the cloud item
 * sheet's Open row) would then show a 4dp corner at the edge of the
 * block whenever its first or last row is GONE. {@link #applyCorners}
 * recomputes the shape from the VISIBLE cards, so every visibility
 * toggle on such a group is followed by one call — the rule is "toggle,
 * then applyCorners(group)", never a static shape on a row that can hide.
 *
 * <p>Adapters do the same per position through {@link #shapeFor}: a
 * RecyclerView list of card rows passes (position, count) at bind time
 * and gets the matching shape, with the same radii.
 */
public final class SheetGroups {

    private SheetGroups() {}

    /**
     * Recompute the corners of every visible {@link MaterialCardView}
     * that is a DIRECT child of {@code group} so the first visible one
     * carries the outer top corners, the last the outer bottom corners,
     * and the ones between the inner 4dp — a lone visible row gets all
     * four outer corners. Non-card children (a section label, a hairline
     * spacer) are skipped, not counted. Safe on a null group so callers
     * can hand over a {@code findViewById} result unchecked.
     */
    public static void applyCorners(@Nullable ViewGroup group) {
        if (group == null) return;
        MaterialCardView first = null;
        MaterialCardView last = null;
        int childCount = group.getChildCount();
        for (int i = 0; i < childCount; i++) {
            View child = group.getChildAt(i);
            if (!(child instanceof MaterialCardView) || child.getVisibility() != View.VISIBLE) {
                continue;
            }
            if (first == null) first = (MaterialCardView) child;
            last = (MaterialCardView) child;
        }
        if (first == null) return;
        for (int i = 0; i < childCount; i++) {
            View child = group.getChildAt(i);
            if (!(child instanceof MaterialCardView) || child.getVisibility() != View.VISIBLE) {
                continue;
            }
            MaterialCardView card = (MaterialCardView) child;
            card.setShapeAppearanceModel(shape(card.getContext(), card == first, card == last));
        }
    }

    /** {@link #applyCorners(ViewGroup)} for several groups found by id under one root. */
    public static void applyCorners(@NonNull View root, @IdRes int... groupIds) {
        for (int id : groupIds) {
            View group = root.findViewById(id);
            if (group instanceof ViewGroup) applyCorners((ViewGroup) group);
        }
    }

    /**
     * The shape for a card at {@code position} of a {@code count}-row
     * group — what an adapter binds per row. {@code count <= 1} yields
     * the all-outer single-row shape.
     */
    @NonNull
    public static ShapeAppearanceModel shapeFor(@NonNull Context context, int position, int count) {
        return shape(context, position == 0, position >= count - 1);
    }

    /** Set the group corners of one card from its first/last role. */
    public static void apply(@NonNull MaterialCardView card, boolean first, boolean last) {
        card.setShapeAppearanceModel(shape(card.getContext(), first, last));
    }

    @NonNull
    private static ShapeAppearanceModel shape(@NonNull Context context, boolean first, boolean last) {
        float outer = context.getResources().getDimension(R.dimen.sheet_group_corner_outer);
        float inner = context.getResources().getDimension(R.dimen.sheet_group_corner_inner);
        float top = first ? outer : inner;
        float bottom = last ? outer : inner;
        return ShapeAppearanceModel.builder()
                .setTopLeftCorner(CornerFamily.ROUNDED, top)
                .setTopRightCorner(CornerFamily.ROUNDED, top)
                .setBottomLeftCorner(CornerFamily.ROUNDED, bottom)
                .setBottomRightCorner(CornerFamily.ROUNDED, bottom)
                .build();
    }
}
