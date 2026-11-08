package com.solarized.firedown.settings.ui;

import android.content.Context;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.view.ViewCompat;
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat.AccessibilityActionCompat;
import androidx.preference.Preference;
import androidx.preference.PreferenceViewHolder;

import com.solarized.firedown.R;

/**
 * An installed add-on row that can carry a "Review" button: shown while
 * the add-on's update waits and its permission question has not been seen
 * (the ⋮ dot's rule, so the two clear together). A tap on the row still
 * opens the add-on's page; the button runs {@link #setOnReviewListener}.
 * The button is hidden from screen readers, which get it as a custom
 * action on the row instead (one node).
 */
public class ReviewPreference extends Preference {

    @Nullable
    private Runnable mOnReview;
    private boolean mReview;

    public ReviewPreference(@NonNull Context context) {
        super(context);
        setWidgetLayoutResource(R.layout.preference_widget_review);
    }

    public void setOnReviewListener(@Nullable Runnable onReview) {
        mOnReview = onReview;
    }

    /** Shows or hides the button; rebinds only on a change. */
    public void setReview(boolean review) {
        if (mReview != review) {
            mReview = review;
            notifyChanged();
        }
    }

    @Override
    public void onBindViewHolder(@NonNull PreferenceViewHolder holder) {
        super.onBindViewHolder(holder);
        Runnable onReview = mOnReview;
        boolean shown = mReview && onReview != null;
        View frame = holder.findViewById(android.R.id.widget_frame);
        if (frame != null) {
            frame.setVisibility(shown ? View.VISIBLE : View.GONE);
        }
        View action = holder.findViewById(R.id.preference_action);
        if (action != null) {
            action.setOnClickListener(shown ? v -> onReview.run() : null);
        }
        ViewCompat.removeAccessibilityAction(holder.itemView, R.id.addon_review_action);
        if (shown) {
            CharSequence label = getContext().getString(R.string.addon_review_update);
            ViewCompat.replaceAccessibilityAction(holder.itemView,
                    new AccessibilityActionCompat(R.id.addon_review_action, label), label,
                    (view, args) -> {
                        onReview.run();
                        return true;
                    });
        }
    }
}
