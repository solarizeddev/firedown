package com.solarized.firedown.geckoview.toolbar;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.util.LayoutDirection;
import android.view.animation.LinearInterpolator;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.interpolator.view.animation.FastOutSlowInInterpolator;

import com.solarized.firedown.data.DownloadProgress;

/**
 * The bottom bar's Downloads icon, with its progress (canvas board 19, option
 * B). At rest: Material's "download", an arrow over a tray. While anything
 * downloads, the tray's sides retract and its base becomes the progress line
 * ({@link DownloadsLineGeometry}): a faint track in the icon's colour with the
 * group's percentage filled in coral, or a short coral piece sliding along it
 * while no total is known (a live recording). One line, where the icon already
 * had one; the arrow never moves. The arrow and the line are separate shapes,
 * each in one colour, never part of one glyph recoloured.
 *
 * <p>Private tabs show the Safe Folder's lock at rest; there the lock fades
 * into the same arrow and line.
 *
 * <p>The sweep stops while the drawable is not visible (the button detached
 * or hidden) and does not run with animations turned off: the piece stands
 * still in the middle of the line.
 */
public final class DownloadProgressDrawable extends Drawable {

    private static final long MORPH_MS = 220;
    private static final long FILL_MS = 180;
    private static final long HOLD_MS = 450;
    private static final long SWEEP_MS = 1500;
    /** The track: the icon's colour at about 30%. */
    private static final float TRACK_OPACITY = 0.3f;

    private final int mSize;
    private final Path mArrow = new Path();
    private final Path mTray = new Path();
    private final Paint mInkPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mFillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mShapePaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    private int mInk = 0xFF000000;
    private int mFillColor = 0xFFFF716C;
    private int mAlpha = 255;
    @Nullable private Drawable mRestGlyph;

    private DownloadProgress mProgress = DownloadProgress.NONE;
    private boolean mHasProgress;
    /** 0 = the icon at rest, 1 = the progress line. */
    private float mActive;
    /** The fill as shown, 0..1; animated toward the progress. */
    private float mFill;
    private float mSweep;
    private boolean mSweeping;

    @Nullable private ValueAnimator mMorphAnimator;
    @Nullable private ValueAnimator mFillAnimator;
    @Nullable private ValueAnimator mSweepAnimator;

    public DownloadProgressDrawable(@NonNull Context context) {
        mSize = Math.round(24f * context.getResources().getDisplayMetrics().density);
        buildPaths();
        mInkPaint.setStyle(Paint.Style.FILL);
        mFillPaint.setStyle(Paint.Style.FILL);
        mShapePaint.setStyle(Paint.Style.FILL);
    }

    /** Material Symbols "download" (weight 400), in its 960-unit viewport. */
    private void buildPaths() {
        mArrow.moveTo(480, 640);
        mArrow.lineTo(280, 440);
        mArrow.lineTo(336, 382);
        mArrow.lineTo(440, 486);
        mArrow.lineTo(440, 160);
        mArrow.lineTo(520, 160);
        mArrow.lineTo(520, 486);
        mArrow.lineTo(624, 382);
        mArrow.lineTo(680, 440);
        mArrow.close();

        mTray.moveTo(240, 800);
        mTray.quadTo(207, 800, 183.5f, 776.5f);
        mTray.quadTo(160, 753, 160, 720);
        mTray.lineTo(160, 600);
        mTray.lineTo(240, 600);
        mTray.lineTo(240, 720);
        mTray.lineTo(720, 720);
        mTray.lineTo(720, 600);
        mTray.lineTo(800, 600);
        mTray.lineTo(800, 720);
        mTray.quadTo(800, 753, 776.5f, 776.5f);
        mTray.quadTo(753, 800, 720, 800);
        mTray.close();
    }

    // ---- inputs -------------------------------------------------------------

    /** The fill's colour: the deeper coral in light theme, the private tabs' ink there. */
    public void setFillColor(int color) {
        mFillColor = color;
        invalidateSelf();
    }

    /** The glyph shown at rest instead of the tray (the Safe Folder lock), or null for the download icon. */
    public void setRestGlyph(@Nullable Drawable glyph) {
        mRestGlyph = glyph != null ? glyph.mutate() : null;
        if (mRestGlyph != null) {
            mRestGlyph.setTint(mInk);
        }
        invalidateSelf();
    }

    /**
     * Shows a new progress. {@code animate} false (a screen just bound, a
     * mode switch) jumps to it; true morphs, fills and, when a group ends
     * with its last download finished, holds the full line a moment first.
     */
    public void setProgress(@NonNull DownloadProgress progress, boolean animate) {
        boolean wasActive = mProgress.isActive();
        DownloadProgress previous = mProgress;
        mProgress = progress;
        boolean firstValue = !mHasProgress;
        mHasProgress = true;
        boolean motion = animate && !firstValue && ValueAnimator.areAnimatorsEnabled() && isVisible();

        if (!progress.isActive()) {
            stopSweep();
            if (!wasActive) {
                return;
            }
            if (motion && progress.completed && previous.state == DownloadProgress.DETERMINATE) {
                animateFill(1f, () -> morphTo(0f, HOLD_MS));
            } else if (motion) {
                morphTo(0f, 0);
            } else {
                jumpTo(0f, 0f);
            }
            return;
        }

        float target = progress.state == DownloadProgress.DETERMINATE ? progress.percent / 100f : 0f;
        if (!wasActive) {
            // A new group: whatever the last one was doing (a finish still
            // filling or holding) is over, and its line starts empty.
            cancel(mFillAnimator);
            mFill = 0f;
        }
        if (!motion) {
            jumpTo(1f, target);
        } else {
            // A morph still pending (the way back to rest after a finish,
            // waiting out its hold: started but not yet running) must not
            // fire under a new group.
            boolean morphPending = mMorphAnimator != null && mMorphAnimator.isStarted();
            if (mActive < 1f || morphPending) {
                morphTo(1f, 0);
            }
            if (progress.state == DownloadProgress.DETERMINATE) {
                animateFill(target, null);
            }
        }
        updateSweep();
        invalidateSelf();
    }

    // ---- animation ----------------------------------------------------------

    private void jumpTo(float active, float fill) {
        cancel(mMorphAnimator);
        cancel(mFillAnimator);
        mActive = active;
        mFill = fill;
        invalidateSelf();
    }

    private void morphTo(float target, long delay) {
        cancel(mMorphAnimator);
        ValueAnimator animator = ValueAnimator.ofFloat(mActive, target);
        animator.setDuration(MORPH_MS);
        animator.setStartDelay(delay);
        animator.setInterpolator(new FastOutSlowInInterpolator());
        animator.addUpdateListener(a -> {
            mActive = (float) a.getAnimatedValue();
            invalidateSelf();
        });
        if (target == 0f) {
            animator.addListener(new AnimatorListenerAdapter() {
                private boolean mCancelled;

                @Override
                public void onAnimationCancel(Animator animation) {
                    mCancelled = true;
                }

                @Override
                public void onAnimationEnd(Animator animation) {
                    if (!mCancelled) {
                        mFill = 0f;
                    }
                }
            });
        }
        mMorphAnimator = animator;
        animator.start();
    }

    private void animateFill(float target, @Nullable Runnable then) {
        cancel(mFillAnimator);
        ValueAnimator animator = ValueAnimator.ofFloat(mFill, target);
        animator.setDuration(FILL_MS);
        animator.setInterpolator(new FastOutSlowInInterpolator());
        animator.addUpdateListener(a -> {
            mFill = (float) a.getAnimatedValue();
            invalidateSelf();
        });
        if (then != null) {
            animator.addListener(new AnimatorListenerAdapter() {
                private boolean mCancelled;

                @Override
                public void onAnimationCancel(Animator animation) {
                    mCancelled = true;
                }

                @Override
                public void onAnimationEnd(Animator animation) {
                    if (!mCancelled) {
                        then.run();
                    }
                }
            });
        }
        mFillAnimator = animator;
        animator.start();
    }

    private void updateSweep() {
        boolean want = mProgress.state == DownloadProgress.INDETERMINATE
                && isVisible() && ValueAnimator.areAnimatorsEnabled();
        if (want == mSweeping) {
            return;
        }
        if (!want) {
            stopSweep();
            return;
        }
        mSweeping = true;
        ValueAnimator animator = ValueAnimator.ofFloat(0f, 1f);
        animator.setDuration(SWEEP_MS);
        animator.setRepeatCount(ValueAnimator.INFINITE);
        animator.setInterpolator(new LinearInterpolator());
        animator.addUpdateListener(a -> {
            mSweep = (float) a.getAnimatedValue();
            invalidateSelf();
        });
        mSweepAnimator = animator;
        animator.start();
    }

    private void stopSweep() {
        mSweeping = false;
        cancel(mSweepAnimator);
        mSweepAnimator = null;
    }

    private static void cancel(@Nullable ValueAnimator animator) {
        if (animator != null) {
            animator.cancel();
        }
    }

    @Override
    public boolean setVisible(boolean visible, boolean restart) {
        boolean changed = super.setVisible(visible, restart);
        if (!visible) {
            // Off screen: land where the animations were going (the fill's
            // end may start the way back to rest, which then lands too).
            if (mFillAnimator != null && mFillAnimator.isStarted()) {
                mFillAnimator.end();
            }
            if (mMorphAnimator != null && mMorphAnimator.isStarted()) {
                mMorphAnimator.end();
            }
        }
        updateSweep();
        return changed;
    }

    // ---- drawing ------------------------------------------------------------

    @Override
    public void draw(@NonNull Canvas canvas) {
        Rect bounds = getBounds();
        if (bounds.isEmpty()) {
            return;
        }
        float unit = bounds.width() / 24f;
        boolean rtl = getLayoutDirection() == LayoutDirection.RTL;

        if (mRestGlyph != null && mActive < 1f) {
            mRestGlyph.setBounds(bounds);
            mRestGlyph.setAlpha(Math.round(mAlpha * (1f - mActive)));
            mRestGlyph.draw(canvas);
        }

        canvas.save();
        canvas.translate(bounds.left, bounds.top);
        canvas.scale(unit, unit);

        if (mRestGlyph != null) {
            if (mActive > 0f) {
                drawActive(canvas, rtl, mActive);
            }
        } else if (mActive <= 0f) {
            drawRest(canvas);
        } else {
            drawMorph(canvas, rtl);
        }
        canvas.restore();
    }

    private void drawRest(Canvas canvas) {
        mInkPaint.setColor(withAlpha(mInk, 1f));
        canvas.save();
        canvas.scale(0.025f, 0.025f);
        canvas.drawPath(mArrow, mInkPaint);
        canvas.drawPath(mTray, mInkPaint);
        canvas.restore();
    }

    /** Between the tray and the line: sides retracting, the base moving and fading to the track. */
    private void drawMorph(Canvas canvas, boolean rtl) {
        drawArrow(canvas, 1f);
        float a = mActive;
        mShapePaint.setColor(withAlpha(mInk, 1f));
        float[] sides = DownloadsLineGeometry.sides(a);
        for (int i = 0; i + 3 < sides.length; i += 4) {
            canvas.drawRect(sides[i], sides[i + 1], sides[i + 2], sides[i + 3], mShapePaint);
        }
        float[] base = DownloadsLineGeometry.base(a);
        float trackOpacity = DownloadsLineGeometry.lerp(1f, TRACK_OPACITY, a);
        mShapePaint.setColor(withAlpha(mInk, trackOpacity));
        canvas.drawRect(base[0], base[1], base[2], base[3], mShapePaint);
        // Going back to rest, the last fill (the full line after a finish)
        // fades as it moves.
        if (mProgress.isActive() || mFill > 0f) {
            drawProgress(canvas, rtl, a, base);
        }
    }

    /** The private tabs' version: the arrow and the line fade in over the lock. */
    private void drawActive(Canvas canvas, boolean rtl, float opacity) {
        drawArrow(canvas, opacity);
        float[] base = DownloadsLineGeometry.base(1f);
        mShapePaint.setColor(withAlpha(mInk, TRACK_OPACITY * opacity));
        canvas.drawRect(base[0], base[1], base[2], base[3], mShapePaint);
        drawProgress(canvas, rtl, opacity, base);
    }

    private void drawArrow(Canvas canvas, float opacity) {
        mInkPaint.setColor(withAlpha(mInk, opacity));
        canvas.save();
        canvas.scale(0.025f, 0.025f);
        canvas.drawPath(mArrow, mInkPaint);
        canvas.restore();
    }

    private void drawProgress(Canvas canvas, boolean rtl, float opacity, float[] base) {
        mFillPaint.setColor(withAlpha(mFillColor, opacity));
        float[] span;
        if (mProgress.state == DownloadProgress.INDETERMINATE) {
            span = mSweeping ? DownloadsLineGeometry.segment(mSweep, rtl) : DownloadsLineGeometry.stillSegment();
        } else {
            span = DownloadsLineGeometry.fill(mFill, rtl);
        }
        if (span.length < 2) {
            return;
        }
        // The line may still be moving into place: map the span onto it.
        float lineWidth = DownloadsLineGeometry.LINE_RIGHT - DownloadsLineGeometry.LINE_LEFT;
        float scale = (base[2] - base[0]) / lineWidth;
        float left = base[0] + (span[0] - DownloadsLineGeometry.LINE_LEFT) * scale;
        float right = base[0] + (span[1] - DownloadsLineGeometry.LINE_LEFT) * scale;
        canvas.drawRect(left, base[1], right, base[3], mFillPaint);
    }

    private int withAlpha(int color, float opacity) {
        int alpha = Math.round(((color >>> 24) & 0xFF) * opacity * (mAlpha / 255f));
        return (color & 0x00FFFFFF) | (Math.max(0, Math.min(255, alpha)) << 24);
    }

    // ---- Drawable plumbing ---------------------------------------------------

    @Override
    public void setTintList(@Nullable ColorStateList tint) {
        if (tint != null) {
            mInk = tint.getColorForState(getState(), tint.getDefaultColor());
            if (mRestGlyph != null) {
                mRestGlyph.setTint(mInk);
            }
            invalidateSelf();
        }
    }

    @Override
    public void setTint(int tintColor) {
        setTintList(ColorStateList.valueOf(tintColor));
    }

    @Override
    public void setAlpha(int alpha) {
        mAlpha = alpha;
        invalidateSelf();
    }

    @Override
    public int getAlpha() {
        return mAlpha;
    }

    @Override
    public void setColorFilter(@Nullable ColorFilter colorFilter) {
        mInkPaint.setColorFilter(colorFilter);
        mShapePaint.setColorFilter(colorFilter);
        mFillPaint.setColorFilter(colorFilter);
        invalidateSelf();
    }

    @Override
    public int getOpacity() {
        return PixelFormat.TRANSLUCENT;
    }

    @Override
    public int getIntrinsicWidth() {
        return mSize;
    }

    @Override
    public int getIntrinsicHeight() {
        return mSize;
    }

    @Override
    public boolean onLayoutDirectionChanged(int layoutDirection) {
        invalidateSelf();
        return true;
    }

    /** One per button; tinting it in place is what the button wants. */
    @NonNull
    @Override
    public Drawable mutate() {
        return this;
    }
}
