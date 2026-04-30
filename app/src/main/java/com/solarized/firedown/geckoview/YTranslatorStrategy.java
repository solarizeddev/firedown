package com.solarized.firedown.geckoview;

import android.animation.ValueAnimator;
import android.view.View;
import android.view.animation.DecelerateInterpolator;

/**
 * Helper class with methods for different behaviors when translating a {@link View} on the Y axis.
 *
 * <p>Matches upstream {@code ViewYTranslationStrategy} from android-components, translated to Java.
 *
 * <p><b>Key fix vs previous Firedown implementation:</b>
 * {@code BottomviewBehaviorStrategy.wasLastExpanding} is now set to
 * {@code targetTranslationY <= view.getTranslationY()} (moving <em>toward</em> 0 = expanding),
 * matching upstream {@code BottomViewBehaviorStrategy.animateToTranslationY}.
 * The old code used {@code >=} which inverted the flag and caused {@code forceExpandWithAnimation}
 * to incorrectly suppress expand animations for the bottom bar.
 *
 * <p>{@code TopviewBehaviorStrategy.wasLastExpanding} was already correct
 * ({@code targetTranslationY >= view.getTranslationY()} = moving toward 0 = expanding).
 */
public abstract class YTranslatorStrategy {

    static final long SNAP_ANIMATION_DURATION = 150L;

    // Legacy constants kept for source compatibility.
    public static final int TOP    = 0;
    public static final int BOTTOM = 1;

    ValueAnimator animator;
    ViewPosition  viewPosition;

    /** Snap to collapsed or expanded, whichever is closer, with animation. */
    public abstract void snapWithAnimation(View view);

    /** Snap to collapsed or expanded immediately (no animation). */
    public abstract void snapImmediately(View view);

    /** Translate the view to its fully-visible position with animation. */
    public abstract void expandWithAnimation(View view);

    /**
     * Force-expand with animation if conditions are met (not already expanded, not already
     * expanding, and {@code distance} implies an upward swipe).
     */
    public abstract void forceExpandWithAnimation(View view, float distance);

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

    // ── Bottom bar strategy ───────────────────────────────────────────────────────────────────────

    /**
     * Translates a bottom-anchored {@link View} on the Y axis between 0 (fully visible) and
     * {@code view.getHeight()} (fully hidden).
     *
     * <p>Matches upstream {@code BottomViewBehaviorStrategy}.
     */
    public static class BottomviewBehaviorStrategy extends YTranslatorStrategy {

        /**
         * {@code true} when the last animation moved toward 0 (= expanding / showing the bar).
         *
         * <p><b>BUG FIX:</b> upstream sets this in {@code animateToTranslationY} as
         * {@code targetTranslationY <= view.getTranslationY()} — i.e. the target is <em>lower</em>
         * on the Y axis, meaning the bar is moving up toward its visible position.
         * The previous Firedown code used {@code >=} which inverted the semantic.
         */
        private boolean wasLastExpanding = false;

        public BottomviewBehaviorStrategy() {
            animator = new ValueAnimator();
            animator.setInterpolator(new DecelerateInterpolator());
            animator.setDuration(SNAP_ANIMATION_DURATION);
        }

        @Override
        public void snapWithAnimation(View view) {
            if (view.getTranslationY() >= (view.getHeight() / 2f)) {
                collapseWithAnimation(view);
            } else {
                expandWithAnimation(view);
            }
        }

        @Override
        public void snapImmediately(View view) {
            if (animator.isStarted()) {
                animator.end();
            } else if (view != null) {
                float ty     = view.getTranslationY();
                int   height = view.getHeight();
                view.setTranslationY(ty >= (float) height / 2 ? height : 0f);
            }
        }

        @Override
        public void expandWithAnimation(View view) {
            animateToTranslationY(view, 0f);
        }

        @Override
        public void forceExpandWithAnimation(View view, float distance) {
            boolean shouldExpand    = distance < 0;
            boolean isExpanded      = view.getTranslationY() == 0f;
            // Upstream guard: !wasLastExpanding (not "!isExpandingInProgress && wasLastExpanding")
            if (shouldExpand && !isExpanded && !wasLastExpanding) {
                animator.cancel();
                expandWithAnimation(view);
            }
        }

        @Override
        public void collapseWithAnimation(View view) {
            animateToTranslationY(view, (float) view.getHeight());
        }

        @Override
        public void translate(View view, float distance) {
            view.setTranslationY(
                    Math.max(0f, Math.min(view.getHeight(), view.getTranslationY() + distance)));
        }

        /**
         * Expanding = moving toward 0 (smaller translationY = bar sliding up into view).
         * Target ≤ current means we're moving toward 0.
         */
        @Override
        public void animateToTranslationY(View view, float targetTranslationY) {
            wasLastExpanding = targetTranslationY <= view.getTranslationY();
            super.animateToTranslationY(view, targetTranslationY);
        }
    }

    // ── Top bar strategy ──────────────────────────────────────────────────────────────────────────

    /**
     * Translates a top-anchored {@link View} on the Y axis between
     * {@code -view.getHeight()} (fully hidden) and 0 (fully visible).
     *
     * <p>Matches upstream {@code TopViewBehaviorStrategy} — logic was already correct.
     */
    public static class TopviewBehaviorStrategy extends YTranslatorStrategy {

        /** {@code true} when the last animation moved toward 0 (= expanding / showing the bar). */
        private boolean wasLastExpanding = false;

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
            animateToTranslationY(view, 0f);
        }

        @Override
        public void forceExpandWithAnimation(View view, float distance) {
            boolean isExpandingInProgress = animator.isStarted() && wasLastExpanding;
            boolean shouldExpand          = distance < 0;
            boolean isExpanded            = view.getTranslationY() == 0f;
            if (shouldExpand && !isExpanded && !isExpandingInProgress) {
                animator.cancel();
                expandWithAnimation(view);
            }
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

        /**
         * Expanding = moving toward 0 (larger translationY = bar sliding down into view).
         * Target ≥ current means we're moving toward 0.
         */
        @Override
        public void animateToTranslationY(View view, float targetTranslationY) {
            wasLastExpanding = targetTranslationY >= view.getTranslationY();
            super.animateToTranslationY(view, targetTranslationY);
        }
    }
}