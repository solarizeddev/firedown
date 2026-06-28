package com.solarized.firedown.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;

import android.view.View;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

public class ProgressOverlayView extends View {

    private final Paint trackPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint arcPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF arcRect = new RectF();
    private int progress = 0;
    private boolean indeterminate = false;
    private float indeterminateAngle = 0f;

    /** Single shared Runnable so removeCallbacks(this) actually finds and
     *  removes the queued tick. Method-ref expressions allocate a new
     *  Runnable each evaluation, which makes removeCallbacks a no-op and
     *  lets duplicate loops accumulate. */
    private final Runnable mAnimateTick = this::animateIndeterminate;
    /** True between a successful schedule and the matching tick firing.
     *  Guards against scheduling a parallel loop when the view's
     *  visibility cycles back to VISIBLE during a bind that didn't
     *  actually stop the prior loop. */
    private boolean mTickScheduled = false;

    public ProgressOverlayView(@NonNull Context context) {
        this(context, null);
    }

    public ProgressOverlayView(@NonNull Context context, @Nullable AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public ProgressOverlayView(@NonNull Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init();
    }

    private void init() {
        // No background fill — the host card now carries the active
        // 'wash' surface that signals 'live'. Painting an extra coral
        // wash on top double-tinted the grid tile and overpowered the
        // wash. The ring + arc + percent label do all the live-signal
        // work; the surface beneath is whatever the adapter set.

        trackPaint.setStyle(Paint.Style.STROKE);
        trackPaint.setColor(0x40ff716c);
        trackPaint.setStrokeCap(Paint.Cap.ROUND);

        arcPaint.setStyle(Paint.Style.STROKE);
        arcPaint.setColor(0xFFff716c);
        arcPaint.setStrokeCap(Paint.Cap.ROUND);

        textPaint.setColor(0xFFff716c);
        textPaint.setTextAlign(Paint.Align.CENTER);
    }

    public void setProgress(int progress) {
        int clamped = Math.max(0, Math.min(100, progress));
        if (this.progress != clamped) {
            this.progress = clamped;
            invalidate();
        }
    }

    public int getProgress() {
        return progress;
    }

    public void setIndeterminate(boolean indeterminate) {
        if (this.indeterminate == indeterminate) return;
        this.indeterminate = indeterminate;
        if (indeterminate) {
            scheduleTick();
        } else {
            removeCallbacks(mAnimateTick);
            mTickScheduled = false;
        }
        invalidate();
    }

    /** Single entry point for posting the tick — dedupes against the
     *  shared Runnable so duplicate kicks (visibility cycles, attach
     *  fires, setIndeterminate(true) calls) collapse into a single
     *  scheduled tick instead of spawning parallel loops. */
    private void scheduleTick() {
        if (mTickScheduled) return;
        if (!indeterminate || getVisibility() != VISIBLE || !isAttachedToWindow()) return;
        mTickScheduled = true;
        post(mAnimateTick);
    }

    private void animateIndeterminate() {
        mTickScheduled = false;
        // Stop the loop the moment we're hidden / unattached / off-state.
        // Recycled holders in the RecyclerView's pool used to keep their
        // animate callback queued because the previous loop only exited
        // when setIndeterminate(false) was called explicitly — flipping
        // visibility GONE on rebind didn't break the chain, so during
        // cold-start scroll several offscreen holders were still ticking
        // an invalidate every 16ms each, competing with bind work and
        // Glide decodes for the main looper.
        if (!indeterminate || getVisibility() != VISIBLE || !isAttachedToWindow()) return;
        indeterminateAngle = (indeterminateAngle + 6f) % 360f;
        invalidate();
        mTickScheduled = true;
        postDelayed(mAnimateTick, 16);
    }

    @Override
    protected void onDraw(@NonNull Canvas canvas) {
        super.onDraw(canvas);

        int w = getWidth();
        int h = getHeight();
        if (w == 0 || h == 0) return;

        float cx = w / 2f;
        float cy = h / 2f;
        // 0.28 of the shorter side: the ring is the download tile's focal point
        // (it owns the picture zone now that the placeholder glyph is gone), so
        // it wants to read as the hero, not a small overlay. Only grid/dense
        // download tiles show this view — the list uses the inline bar — so the
        // bump doesn't touch list rows.
        float radius = Math.min(w, h) * 0.28f;
        float strokeWidth = radius * 0.25f;

        trackPaint.setStrokeWidth(strokeWidth);
        arcPaint.setStrokeWidth(strokeWidth);

        arcRect.set(cx - radius, cy - radius, cx + radius, cy + radius);

        canvas.drawCircle(cx, cy, radius, trackPaint);

        if (indeterminate) {
            canvas.drawArc(arcRect, indeterminateAngle, 90f, false, arcPaint);
        } else {
            float sweep = (progress / 100f) * 360f;
            canvas.drawArc(arcRect, -90, sweep, false, arcPaint);

            textPaint.setTextSize(radius * 0.7f);
            String label = progress + "%";
            float textY = cy - ((textPaint.descent() + textPaint.ascent()) / 2f);
            canvas.drawText(label, cx, textY, textPaint);
        }
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        indeterminate = false;
        removeCallbacks(mAnimateTick);
        mTickScheduled = false;
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        // If we were re-attached while still indeterminate (RecyclerView
        // pulled the holder back out of the pool with the flag still
        // set), restart the loop via the dedupe-aware scheduler.
        if (indeterminate) scheduleTick();
    }

    @Override
    public void setVisibility(int visibility) {
        int prev = getVisibility();
        super.setVisibility(visibility);
        if (visibility != VISIBLE) {
            // Hidden — kill the queued tick so the offscreen view stops
            // burning frames.
            removeCallbacks(mAnimateTick);
            mTickScheduled = false;
        } else if (indeterminate && prev != VISIBLE) {
            // Back to VISIBLE while still flagged indeterminate; kick
            // through the dedupe-aware scheduler.
            scheduleTick();
        }
    }
}
