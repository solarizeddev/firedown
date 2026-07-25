package com.solarized.firedown.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;

import android.view.View;
import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.ColorUtils;
import androidx.annotation.Nullable;

import com.solarized.firedown.R;

public class ProgressOverlayView extends View {

    /** Target ring radius in dp. Chosen so the ring's OUTER diameter
     *  (2*radius + stroke, and stroke = radius*0.25 → 2.25*radius) equals the
     *  50dp centered mime glyph shown for non-progress tiles, so the two
     *  states' focal elements are the same size. 50 / 2.25 ≈ 22.2.
     *  Clamped to the view in onDraw so it never overflows. */
    private static final float RING_RADIUS_DP = 22.2f;

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
        init(context);
    }

    private void init(@NonNull Context context) {
        // Theme-aware, not hardcoded: in light theme the arc has to separate
        // from its own track, and #f0716c cannot (see R.color.progress_indicator
        // — nothing lighter than the brand clears 3:1 against it, white tops out
        // at 2.88). progress_indicator is a deeper coral in light and the brand
        // value in dark, where the brand already clears it.
        int accent = ContextCompat.getColor(context, R.color.progress_indicator);
        // No background fill — the host card now carries the active
        // 'wash' surface that signals 'live'. Painting an extra coral
        // wash on top double-tinted the grid tile and overpowered the
        // wash. The ring + arc + percent label do all the live-signal
        // work; the surface beneath is whatever the adapter set.

        trackPaint.setStyle(Paint.Style.STROKE);
        trackPaint.setColor(ColorUtils.setAlphaComponent(accent, 0x40));
        trackPaint.setStrokeCap(Paint.Cap.ROUND);

        arcPaint.setStyle(Paint.Style.STROKE);
        arcPaint.setColor(accent);
        arcPaint.setStrokeCap(Paint.Cap.ROUND);

        textPaint.setColor(accent);
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
        // Fixed target size (~54dp ring), clamped to fit the view. The progress
        // ring is the in-progress counterpart of the centered mime glyph shown
        // for non-progress tiles, so it must read at the SAME size and sit
        // centered the same way. Sizing off the shorter side alone made the
        // ring tiny in the grid's wide-but-short picture zone (the ring shrank
        // to the zone height while the glyph kept its fixed size) — which left
        // it looking small and lost. A fixed target keeps the two consistent.
        // The 0.42 clamp only kicks in for a view too small to hold the target.
        // Only grid/dense download tiles show this view (the list uses the
        // inline bar), so this never touches list rows.
        float density = getResources().getDisplayMetrics().density;
        float radius = Math.min(Math.min(w, h) * 0.42f, RING_RADIUS_DP * density);
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
