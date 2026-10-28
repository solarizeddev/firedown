package com.solarized.firedown.ui;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.android.material.color.MaterialColors;

import java.util.ArrayList;
import java.util.List;

/**
 * A horizontal "filmstrip" trim slider.
 *
 * <p>Renders a row of evenly-tiled thumbnails (provided by the host) with
 * two draggable handles defining a trim range. Outside the range is dimmed;
 * inside, an optional playhead marker can be driven by the host.
 *
 * <p>Custom {@link View} — no children, all painting in {@link #onDraw} and
 * all hit-testing in {@link #onTouchEvent}. Thumbnails arrive async via
 * {@link #setThumbnails}; until they do, the strip shows a placeholder fill
 * so the trim controls remain usable on slow extractions.
 *
 * <p>Bitmaps are drawn with a center-crop {@code srcRect} into each cell so
 * faces don't stretch when the source aspect ratio doesn't match the cell
 * aspect ratio — much nicer than a naive stretch when the cell is narrow
 * (e.g. 12 thumbnails on a 360 dp wide phone gives ~30 dp cells).
 */
public class FilmstripTrimSlider extends View {

    public interface OnTrimChangedListener {
        void onTrimChanged(long startMs, long endMs, boolean fromUser);
    }

    /** Fired once when the view first knows its width, so the host can
     *  decide how many thumbnails to extract. Calling
     *  {@link #setThumbnails} doesn't depend on this — it's just a hint
     *  for the extraction count. */
    public interface OnLayoutReadyListener {
        void onLayoutReady(int suggestedThumbnailCount);
    }

    private static final int HANDLE_NONE = 0;
    private static final int HANDLE_START = 1;
    private static final int HANDLE_END = 2;

    /** Visual width of each handle bar. */
    private static final int HANDLE_WIDTH_DP = 4;
    /** ± hit-test radius from a handle's center pixel. Generous because
     *  finger pads are wider than the visible bar. */
    private static final int HANDLE_TOUCH_SLOP_DP = 24;
    private static final int PLAYHEAD_WIDTH_DP = 2;
    /** dp per thumbnail in the auto count — one thumb every 64 dp gives
     *  ~5–6 frames on a phone, ~12 on a tablet. */
    private static final int THUMBNAIL_DP = 64;

    private long mDurationMs;
    private long mStartMs;
    private long mEndMs;
    private long mPlayheadMs;
    private long mMinTrimMs = 200L;
    /* 0: no limit. */
    private long mMaxTrimMs;

    private final List<Bitmap> mThumbnails = new ArrayList<>();

    private final Paint mDimPaint;
    private final Paint mHandlePaint;
    private final Paint mPlayheadPaint;
    private final Paint mPlaceholderPaint;

    private final int mHandleHalfWidth;
    private final int mHandleTouchSlop;
    private final int mPlayheadHalfWidth;
    private final int mThumbnailDp;

    private int mActiveHandle = HANDLE_NONE;
    private boolean mLayoutReadyFired;

    @Nullable
    private OnTrimChangedListener mTrimListener;
    @Nullable
    private OnLayoutReadyListener mReadyListener;

    private final Rect mSrcRect = new Rect();
    private final Rect mDstRect = new Rect();


    public FilmstripTrimSlider(Context context) {
        this(context, null);
    }

    public FilmstripTrimSlider(Context context, @Nullable AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public FilmstripTrimSlider(Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);

        float density = getResources().getDisplayMetrics().density;
        mHandleHalfWidth = Math.max(1, (int) (HANDLE_WIDTH_DP * density / 2f));
        mHandleTouchSlop = (int) (HANDLE_TOUCH_SLOP_DP * density);
        mPlayheadHalfWidth = Math.max(1, (int) (PLAYHEAD_WIDTH_DP * density / 2f));
        mThumbnailDp = (int) (THUMBNAIL_DP * density);

        int primary = MaterialColors.getColor(this,
                android.R.attr.colorPrimary, Color.MAGENTA);
        int placeholder = MaterialColors.getColor(this,
                com.google.android.material.R.attr.colorSurfaceContainerHigh, Color.DKGRAY);

        mDimPaint = new Paint();
        /* 60% black — dark enough that the trimmed-out region reads as
         * "not selected" without making the underlying frames invisible. */
        mDimPaint.setColor(Color.argb(160, 0, 0, 0));

        mHandlePaint = new Paint();
        mHandlePaint.setColor(primary);

        mPlayheadPaint = new Paint();
        mPlayheadPaint.setColor(Color.WHITE);

        mPlaceholderPaint = new Paint();
        mPlaceholderPaint.setColor(placeholder);

        setClickable(true);
    }

    public void setOnTrimChangedListener(@Nullable OnTrimChangedListener l) {
        mTrimListener = l;
    }

    public void setOnLayoutReadyListener(@Nullable OnLayoutReadyListener l) {
        mReadyListener = l;
        /* If we were already laid out before the listener was set, fire
         * immediately so the host doesn't hang waiting. */
        if (l != null && !mLayoutReadyFired && getWidth() > 0) {
            mLayoutReadyFired = true;
            l.onLayoutReady(suggestedThumbnailCount(getWidth()));
        }
    }

    public void setDuration(long durationMs) {
        if (durationMs <= 0) return;
        mDurationMs = durationMs;
        mStartMs = 0;
        mEndMs = mMaxTrimMs > 0 ? Math.min(durationMs, mMaxTrimMs) : durationMs;
        invalidate();
        notifyTrimChanged(false);
    }

    public void setMinTrimMs(long minMs) {
        mMinTrimMs = Math.max(0L, minMs);
    }

    /** The longest range; 0 for none. Set before {@link #setDuration}. A
     *  handle dragged past it carries the other one along: the end dragged
     *  right moves the whole range later, the start dragged left earlier.
     *  Clamping instead would leave a long video's later part out of
     *  reach, the start stopped by the end and the end by the limit. */
    public void setMaxTrimMs(long maxMs) {
        mMaxTrimMs = Math.max(0L, maxMs);
    }

    public long getStartMs() {
        return mStartMs;
    }

    public long getEndMs() {
        return mEndMs;
    }

    public void setThumbnails(@Nullable List<Bitmap> thumbnails) {
        mThumbnails.clear();
        if (thumbnails != null) mThumbnails.addAll(thumbnails);
        invalidate();
    }

    /** Pre-allocates {@code count} null slots so the strip can render
     *  placeholder cells while bitmaps stream in via
     *  {@link #setThumbnailAt}. Pairs with the host's per-frame extract
     *  loop — view shows a meaningful skeleton instead of one monolith
     *  rendered after the whole batch finishes. */
    public void setThumbnailCount(int count) {
        mThumbnails.clear();
        for (int i = 0; i < count; i++) mThumbnails.add(null);
        invalidate();
    }

    /** Replaces a single slot by index. No-op if out of range so
     *  callers don't have to coordinate against view recreation
     *  exactly — a stale post is harmless. */
    public void setThumbnailAt(int index, @Nullable Bitmap bitmap) {
        if (index < 0 || index >= mThumbnails.size()) return;
        mThumbnails.set(index, bitmap);
        invalidate();
    }

    public void setPlayhead(long ms) {
        if (ms != mPlayheadMs) {
            mPlayheadMs = ms;
            invalidate();
        }
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        if (w > 0 && !mLayoutReadyFired && mReadyListener != null) {
            mLayoutReadyFired = true;
            mReadyListener.onLayoutReady(suggestedThumbnailCount(w));
        }
    }

    private int suggestedThumbnailCount(int width) {
        return Math.max(1, width / mThumbnailDp);
    }

    @Override
    protected void onDraw(@NonNull Canvas canvas) {
        super.onDraw(canvas);

        int w = getWidth();
        int h = getHeight();
        if (w <= 0 || h <= 0) return;

        drawFilmstrip(canvas, w, h);

        /* No duration → no trim to render. The strip alone is fine while
         * the player is still preparing. */
        if (mDurationMs <= 0) return;

        int startX = msToPixel(mStartMs, w);
        int endX = msToPixel(mEndMs, w);

        if (startX > 0) canvas.drawRect(0, 0, startX, h, mDimPaint);
        if (endX < w) canvas.drawRect(endX, 0, w, h, mDimPaint);

        canvas.drawRect(startX - mHandleHalfWidth, 0,
                startX + mHandleHalfWidth, h, mHandlePaint);
        canvas.drawRect(endX - mHandleHalfWidth, 0,
                endX + mHandleHalfWidth, h, mHandlePaint);

        if (mPlayheadMs >= mStartMs && mPlayheadMs <= mEndMs) {
            int pX = msToPixel(mPlayheadMs, w);
            canvas.drawRect(pX - mPlayheadHalfWidth, 0,
                    pX + mPlayheadHalfWidth, h, mPlayheadPaint);
        }
    }

    private void drawFilmstrip(Canvas canvas, int w, int h) {
        if (mThumbnails.isEmpty()) {
            canvas.drawRect(0, 0, w, h, mPlaceholderPaint);
            return;
        }

        int count = mThumbnails.size();
        for (int i = 0; i < count; i++) {
            Bitmap b = mThumbnails.get(i);
            int left = (int) ((long) i * w / count);
            int right = (int) ((long) (i + 1) * w / count);
            mDstRect.set(left, 0, right, h);

            if (b == null || b.isRecycled()) {
                canvas.drawRect(mDstRect, mPlaceholderPaint);
                continue;
            }

            /* Center-crop: pick the largest sub-rect of the bitmap that
             * matches the cell's aspect ratio, anchored at the bitmap's
             * center. Avoids the "stretched faces" look that a plain
             * stretch would produce when the cell is narrower than the
             * thumbnail's native aspect. */
            int dstW = right - left;
            int dstH = h;
            int bmpW = b.getWidth();
            int bmpH = b.getHeight();
            float bmpAr = bmpW / (float) bmpH;
            float dstAr = dstW / (float) dstH;

            if (bmpAr > dstAr) {
                int cropW = Math.max(1, (int) (bmpH * dstAr));
                int srcLeft = (bmpW - cropW) / 2;
                mSrcRect.set(srcLeft, 0, srcLeft + cropW, bmpH);
            } else {
                int cropH = Math.max(1, (int) (bmpW / dstAr));
                int srcTop = (bmpH - cropH) / 2;
                mSrcRect.set(0, srcTop, bmpW, srcTop + cropH);
            }

            canvas.drawBitmap(b, mSrcRect, mDstRect, null);
        }
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (mDurationMs <= 0) return false;

        int x = (int) event.getX();
        int w = getWidth();

        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN: {
                int startX = msToPixel(mStartMs, w);
                int endX = msToPixel(mEndMs, w);
                int distStart = Math.abs(x - startX);
                int distEnd = Math.abs(x - endX);

                /* Pick the nearer handle when the touch is within slop of
                 * both. Tie-break to the start handle since it's the more
                 * commonly adjusted one. */
                if (distStart <= mHandleTouchSlop && distStart <= distEnd) {
                    mActiveHandle = HANDLE_START;
                } else if (distEnd <= mHandleTouchSlop) {
                    mActiveHandle = HANDLE_END;
                } else {
                    return false;
                }

                /* Tell the parent (e.g. CoordinatorLayout) to back off so
                 * a horizontal drag here doesn't get hijacked by an
                 * outer scroll container. */
                if (getParent() != null) {
                    getParent().requestDisallowInterceptTouchEvent(true);
                }
                return true;
            }

            case MotionEvent.ACTION_MOVE: {
                if (mActiveHandle == HANDLE_NONE) return false;
                long ms = pixelToMs(x, w);
                if (mActiveHandle == HANDLE_START) {
                    long maxStart = Math.max(0L, mEndMs - mMinTrimMs);
                    mStartMs = clamp(ms, 0L, maxStart);
                    if (mMaxTrimMs > 0 && mEndMs - mStartMs > mMaxTrimMs) {
                        mEndMs = mStartMs + mMaxTrimMs;
                    }
                } else {
                    long minEnd = Math.min(mDurationMs, mStartMs + mMinTrimMs);
                    mEndMs = clamp(ms, minEnd, mDurationMs);
                    if (mMaxTrimMs > 0 && mEndMs - mStartMs > mMaxTrimMs) {
                        mStartMs = mEndMs - mMaxTrimMs;
                    }
                }
                invalidate();
                notifyTrimChanged(true);
                return true;
            }

            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL: {
                if (mActiveHandle != HANDLE_NONE) {
                    mActiveHandle = HANDLE_NONE;
                    if (getParent() != null) {
                        getParent().requestDisallowInterceptTouchEvent(false);
                    }
                    return true;
                }
                return false;
            }
        }
        return false;
    }

    private int msToPixel(long ms, int w) {
        return (int) ((ms * (long) w) / mDurationMs);
    }

    private long pixelToMs(int px, int w) {
        if (w <= 0) return 0L;
        if (px <= 0) return 0L;
        if (px >= w) return mDurationMs;
        return ((long) px * mDurationMs) / w;
    }

    private static long clamp(long v, long min, long max) {
        if (v < min) return min;
        return Math.min(v, max);
    }

    private void notifyTrimChanged(boolean fromUser) {
        if (mTrimListener != null) {
            mTrimListener.onTrimChanged(mStartMs, mEndMs, fromUser);
        }
    }
}
