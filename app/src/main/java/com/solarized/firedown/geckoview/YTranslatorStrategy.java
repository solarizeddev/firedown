package com.solarized.firedown.geckoview;

import android.animation.ValueAnimator;
import android.view.View;
import android.view.animation.DecelerateInterpolator;

/**
 * Helper class with methods for different behaviors when translating a {@link View} on the Y axis.
 *
 * <p>Matches upstream {@code ViewYTranslationStrategy} from android-components, translated to
 * Java — reduced to the TOP strategy only. The old {@code BottomviewBehaviorStrategy} was
 * deleted when {@code BottomNavigationBehavior} became a passive follower slaved to the
 * toolbar's translation (see that class's header): the bottom bar no longer owns a translator,
 * so the bottom strategy (and its inverted-{@code wasLastExpanding} bug-fix lore) had no
 * remaining caller. Don't resurrect it for the bottom bar — mirror the toolbar instead.
 */
public abstract class YTranslatorStrategy {

    static final long SNAP_ANIMATION_DURATION = 150L;

    ValueAnimator animator;

    /** Snap to collapsed or expanded, whichever is closer, with animation. */
    public abstract void snapWithAnimation(View view);

    /** Snap to collapsed or expanded immediately (no animation). */
    public abstract void snapImmediately(View view);

    /** Translate the view to its fully-visible position with animation. */
    public abstract void expandWithAnimation(View view);

    /** Translate the view to its fully-hidden position with animation. */
    public abstract void collapseWithAnimation(View view);

    /** Translate {@code view} immediately by {@code distance} pixels (clamped to valid range). */
    public abstract void translate(View view, float distance);

    /**
     * Animate {@code view} to {@code targetTranslationY} over {@link #SNAP_ANIMATION_DURATION} ms.
     */
    public void animateToTranslationY(View view, float targetTranslationY) {
        animator.removeAllUpdateListeners();
        animator.addUpdateListener(valueAnimator -> {
            Float value = (Float) valueAnimator.getAnimatedValue();
            if (value == null) throw new NullPointerException("animated value is null");
            view.setTranslationY(value);
        });
        animator.setFloatValues(view.getTranslationY(), targetTranslationY);
        animator.start();
    }

    /** Cancel any in-progress translation animation. */
    public final void cancelInProgressTranslation() {
        animator.cancel();
    }

    // ── Top bar strategy ──────────────────────────────────────────────────────────────────────────

    /**
     * Translates a top-anchored {@link View} on the Y axis between
     * {@code -view.getHeight()} (fully hidden) and 0 (fully visible).
     *
     * <p>Matches upstream {@code TopViewBehaviorStrategy}.
     */
    public static class TopviewBehaviorStrategy extends YTranslatorStrategy {

        public TopviewBehaviorStrategy() {
            animator = new ValueAnimator();
            animator.setInterpolator(new DecelerateInterpolator());
            animator.setDuration(SNAP_ANIMATION_DURATION);
        }

        @Override
        public void snapWithAnimation(View view) {
            if (view.getTranslationY() >= -(view.getHeight() / 2f)) {
                expandWithAnimation(view);
            } else {
                collapseWithAnimation(view);
            }
        }

        @Override
        public void snapImmediately(View view) {
            if (animator.isStarted()) {
                animator.end();
            } else if (view != null) {
                float ty     = view.getTranslationY();
                int   height = view.getHeight();
                view.setTranslationY(ty >= (float) -height / 2 ? 0f : -height);
            }
        }

        @Override
        public void expandWithAnimation(View view) {
            // Already fully expanded with nothing in flight — don't spin a
            // no-op 150 ms animator. The scroll policy calls expand on every
            // page start / tab switch / resume / IME close, and in the common
            // case the bars are already pinned at 0; without this the render
            // loop is kept awake ~9 frames per event for nothing. When a
            // collapse is mid-flight (animator started), fall through so the
            // expand correctly replaces it.
            if (view.getTranslationY() == 0f && !animator.isStarted()) {
                return;
            }
            animateToTranslationY(view, 0f);
        }

        @Override
        public void collapseWithAnimation(View view) {
            animateToTranslationY(view, -view.getHeight());
        }

        @Override
        public void translate(View view, float distance) {
            view.setTranslationY(
                    Math.min(0f, Math.max(-view.getHeight(), view.getTranslationY() - distance)));
        }
    }
}
