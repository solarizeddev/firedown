package com.solarized.firedown.ui;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.os.SystemClock;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.VelocityTracker;
import android.view.View;
import android.view.ViewConfiguration;
import android.widget.OverScroller;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.android.material.color.MaterialColors;

import java.util.ArrayList;
import java.util.List;

/**
 * A horizontal "filmstrip" trim slider.
 *
 * <p>Renders a row of thumbnails (provided by the host) with two draggable
 * handles defining a trim range. Outside the range is dimmed; inside, an
 * optional playhead marker can be driven by the host.
 *
 * <p>With a maximum range set and a video longer than {@link #WINDOW_MAX_RANGES}
 * of it, the strip shows a WINDOW of that length instead of the whole video,
 * so the longest range is half the strip rather than a sliver of it. Dragging
 * inside the range moves it, dragging the film outside it scrolls (with
 * fling), a tap on the film puts the range there, and a handle or the range
 * held near an edge scrolls the window. A thin bar along the bottom shows the
 * window and the range in the whole video. A shorter video is shown whole, as
 * before.
 *
 * <p>Thumbnails are SLOTS over the whole video ({@link #setThumbnailCount}),
 * each covering an equal share of it. The view tells the host which slots are
 * on screen or next to it ({@link OnThumbnailsNeededListener}), so a long
 * video's strip is extracted where the user looks, not all at once.
 *
 * <p>Bitmaps are drawn with a center-crop {@code srcRect} into each cell so
 * faces don't stretch when the source aspect ratio doesn't match the cell
 * aspect ratio.
 */
public class FilmstripTrimSlider extends View {

    public interface OnTrimChangedListener {
        void onTrimChanged(long startMs, long endMs, boolean fromUser);
    }

    /** Fired once when the view first knows its width, so the host can
     *  ask {@link #thumbnailSlotCount} how many thumbnails to extract. */
    public interface OnLayoutReadyListener {
        void onLayoutReady(int suggestedThumbnailCount);
    }

    /** Slots {@code first..last} (inclusive) are on screen or next to it.
     *  Called when that range changes; the host fills what it hasn't yet. */
    public interface OnThumbnailsNeededListener {
        void onThumbnailsNeeded(int first, int last);
    }

    /** The window is this many maximum ranges long. */
    public static final int WINDOW_MAX_RANGES = 2;

    /** At most this many slots over the whole video. A longer video gets
     *  wider cells; the pictures only say what is around a time. */
    private static final int MAX_THUMBNAIL_SLOTS = 120;

    private static final int MODE_NONE = 0;
    private static final int MODE_START = 1;
    private static final int MODE_END = 2;
    private static final int MODE_MOVE = 3;
    private static final int MODE_PAN = 4;

    /** Visual width of each handle bar. */
    private static final int HANDLE_WIDTH_DP = 4;
    /** ± hit-test radius from a handle's center pixel. Generous because
     *  finger pads are wider than the visible bar. */
    private static final int HANDLE_TOUCH_SLOP_DP = 24;
    private static final int PLAYHEAD_WIDTH_DP = 2;
    /** dp per thumbnail — ~5–6 frames per window on a phone. */
    private static final int THUMBNAIL_DP = 64;
    /** A handle or the range this close to an edge scrolls the window. */
    private static final int EDGE_ZONE_DP = 32;
    /** Edge scrolling at full depth moves this many windows a second. */
    private static final float EDGE_WINDOWS_PER_SECOND = 1.5f;
    private static final int POSITION_BAR_DP = 3;

    private long mDurationMs;
    private long mStartMs;
    private long mEndMs;
    private long mPlayheadMs;
    private long mMinTrimMs = 200L;
    /* 0: no limit. */
    private long mMaxTrimMs;
    /* The first time on screen. */
    private long mViewStartMs;

    private final List<Bitmap> mThumbnails = new ArrayList<>();

    private final Paint mDimPaint;
    private final Paint mHandlePaint;
    private final Paint mPlayheadPaint;
    private final Paint mPlaceholderPaint;
    private final Paint mBarTrackPaint;
    private final Paint mBarWindowPaint;

    private final int mHandleHalfWidth;
    private final int mHandleTouchSlop;
    private final int mPlayheadHalfWidth;
    private final int mThumbnailDp;
    private final int mEdgeZone;
    private final int mBarHeight;
    private final int mTouchSlop;
    private final int mMinFlingVelocity;
    private final int mMaxFlingVelocity;

    private int mMode = MODE_NONE;
    private int mDownX;
    private int mLastX;
    private boolean mMoved;
    /* MODE_MOVE: the finger's time minus the range start, at the touch. */
    private long mGrabOffsetMs;
    /* MODE_PAN: the window start at the touch. */
    private long mPanAnchorMs;

    @Nullable
    private VelocityTracker mVelocityTracker;
    private final OverScroller mScroller;

    /* Edge scrolling, ms of video per second; negative scrolls earlier. */
    private long mEdgeSpeedMs;
    private boolean mEdgeScrolling;
    private long mEdgeLastFrameMs;

    private boolean mLayoutReadyFired;
    private int mNotifiedFirst = -1;
    private int mNotifiedLast = -1;

    @Nullable
    private OnTrimChangedListener mTrimListener;
    @Nullable
    private OnLayoutReadyListener mReadyListener;
    @Nullable
    private OnThumbnailsNeededListener mThumbnailsListener;

    private final Rect mSrcRect = new Rect();
    private final Rect mDstRect = new Rect();

    private final Runnable mEdgeScroll = new Runnable() {
        @Override
        public void run() {
            if (mEdgeSpeedMs == 0 || (mMode != MODE_START && mMode != MODE_END
                    && mMode != MODE_MOVE)) {
                mEdgeScrolling = false;
                return;
            }
            long now = SystemClock.uptimeMillis();
            long elapsed = Math.min(32L, Math.max(1L, now - mEdgeLastFrameMs));
            mEdgeLastFrameMs = now;
            long step = mEdgeSpeedMs * elapsed / 1000L;
            if (step == 0) {
                step = mEdgeSpeedMs > 0 ? 1 : -1;
            }
            if (setViewStart(mViewStartMs + step)) {
                applyDrag(mLastX);
            }
            if (edgeScrollBlocked()) {
                mEdgeSpeedMs = 0;
                mEdgeScrolling = false;
                return;
            }
            postOnAnimation(this);
        }
    };


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
        mEdgeZone = (int) (EDGE_ZONE_DP * density);
        mBarHeight = Math.max(2, (int) (POSITION_BAR_DP * density));

        ViewConfiguration config = ViewConfiguration.get(context);
        mTouchSlop = config.getScaledTouchSlop();
        mMinFlingVelocity = config.getScaledMinimumFlingVelocity();
        mMaxFlingVelocity = config.getScaledMaximumFlingVelocity();
        mScroller = new OverScroller(context);

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

        mBarTrackPaint = new Paint();
        mBarTrackPaint.setColor(Color.argb(140, 0, 0, 0));

        mBarWindowPaint = new Paint();
        mBarWindowPaint.setColor(Color.argb(220, 255, 255, 255));

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

    public void setOnThumbnailsNeededListener(@Nullable OnThumbnailsNeededListener l) {
        mThumbnailsListener = l;
        mNotifiedFirst = -1;
        mNotifiedLast = -1;
        notifyVisibleSlots();
    }

    public void setDuration(long durationMs) {
        if (durationMs <= 0) return;
        mDurationMs = durationMs;
        mStartMs = 0;
        mEndMs = mMaxTrimMs > 0 ? Math.min(durationMs, mMaxTrimMs) : durationMs;
        mViewStartMs = 0;
        mScroller.forceFinished(true);
        invalidate();
        notifyTrimChanged(false);
        notifyVisibleSlots();
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

    /** How many slots to extract for the whole video: about one per
     *  {@link #THUMBNAIL_DP} of a window, at most {@link #MAX_THUMBNAIL_SLOTS}.
     *  Needs the width and the duration. */
    public int thumbnailSlotCount() {
        int perWindow = suggestedThumbnailCount(getWidth());
        long windowMs = windowMs();
        if (mDurationMs <= 0 || windowMs >= mDurationMs) {
            return perWindow;
        }
        long slots = (perWindow * mDurationMs + windowMs - 1) / windowMs;
        return (int) Math.min(MAX_THUMBNAIL_SLOTS, slots);
    }

    /** Pre-allocates {@code count} empty slots over the whole video; they
     *  render as placeholder cells until {@link #setThumbnailAt} fills them. */
    public void setThumbnailCount(int count) {
        mThumbnails.clear();
        for (int i = 0; i < count; i++) mThumbnails.add(null);
        mNotifiedFirst = -1;
        mNotifiedLast = -1;
        invalidate();
        notifyVisibleSlots();
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

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        removeCallbacks(mEdgeScroll);
        mEdgeScrolling = false;
        mScroller.forceFinished(true);
    }

    private int suggestedThumbnailCount(int width) {
        return Math.max(1, width / mThumbnailDp);
    }

    /** The length of video on screen. */
    private long windowMs() {
        if (mMaxTrimMs > 0 && mDurationMs > WINDOW_MAX_RANGES * mMaxTrimMs) {
            return WINDOW_MAX_RANGES * mMaxTrimMs;
        }
        return mDurationMs;
    }

    private boolean isWindowed() {
        return mDurationMs > 0 && windowMs() < mDurationMs;
    }

    /** Moves the window, kept inside the video. @return whether it moved. */
    private boolean setViewStart(long ms) {
        long max = Math.max(0L, mDurationMs - windowMs());
        long clamped = clamp(ms, 0L, max);
        if (clamped == mViewStartMs) {
            return false;
        }
        mViewStartMs = clamped;
        invalidate();
        notifyVisibleSlots();
        return true;
    }

    @Override
    protected void onDraw(@NonNull Canvas canvas) {
        super.onDraw(canvas);

        int w = getWidth();
        int h = getHeight();
        if (w <= 0 || h <= 0) return;

        canvas.save();
        canvas.clipRect(0, 0, w, h);

        drawFilmstrip(canvas, w, h);

        /* No duration → no trim to render. The strip alone is fine while
         * the player is still preparing. */
        if (mDurationMs <= 0) {
            canvas.restore();
            return;
        }

        int startX = msToPixel(mStartMs, w);
        int endX = msToPixel(mEndMs, w);

        if (startX > 0) canvas.drawRect(0, 0, Math.min(startX, w), h, mDimPaint);
        if (endX < w) canvas.drawRect(Math.max(endX, 0), 0, w, h, mDimPaint);

        canvas.drawRect(startX - mHandleHalfWidth, 0,
                startX + mHandleHalfWidth, h, mHandlePaint);
        canvas.drawRect(endX - mHandleHalfWidth, 0,
                endX + mHandleHalfWidth, h, mHandlePaint);

        if (mPlayheadMs >= mStartMs && mPlayheadMs <= mEndMs) {
            int pX = msToPixel(mPlayheadMs, w);
            canvas.drawRect(pX - mPlayheadHalfWidth, 0,
                    pX + mPlayheadHalfWidth, h, mPlayheadPaint);
        }

        if (isWindowed()) {
            drawPositionBar(canvas, w, h);
        }
        canvas.restore();
    }

    /** The whole video along the bottom: the window in white, the range in
     *  the handles' colour, so a range scrolled off screen is still found. */
    private void drawPositionBar(Canvas canvas, int w, int h) {
        int top = h - mBarHeight;
        canvas.drawRect(0, top, w, h, mBarTrackPaint);

        int windowLeft = (int) (mViewStartMs * w / mDurationMs);
        int windowRight = (int) ((mViewStartMs + windowMs()) * w / mDurationMs);
        canvas.drawRect(windowLeft, top, windowRight, h, mBarWindowPaint);

        int rangeLeft = (int) (mStartMs * w / mDurationMs);
        int rangeRight = (int) (mEndMs * w / mDurationMs);
        /* At least a handle's width, or a short range is invisible. */
        if (rangeRight - rangeLeft < 2 * mHandleHalfWidth) {
            rangeRight = rangeLeft + 2 * mHandleHalfWidth;
        }
        canvas.drawRect(rangeLeft, top, rangeRight, h, mHandlePaint);
    }

    private void drawFilmstrip(Canvas canvas, int w, int h) {
        int count = mThumbnails.size();
        if (count == 0 || mDurationMs <= 0) {
            canvas.drawRect(0, 0, w, h, mPlaceholderPaint);
            return;
        }

        int first = slotAt(mViewStartMs);
        int last = slotAt(mViewStartMs + windowMs());
        for (int i = first; i <= last; i++) {
            int left = msToPixel(i * mDurationMs / count, w);
            int right = msToPixel((i + 1) * mDurationMs / count, w);
            if (right <= left || right <= 0 || left >= w) {
                continue;
            }
            mDstRect.set(left, 0, right, h);

            Bitmap b = mThumbnails.get(i);
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

    /** The slot holding a time, clamped to the slots there are. */
    private int slotAt(long ms) {
        int count = mThumbnails.size();
        if (count == 0 || mDurationMs <= 0) {
            return 0;
        }
        long slot = ms * count / mDurationMs;
        return (int) clamp(slot, 0L, count - 1);
    }

    /** Asks for the slots on screen plus half a window on each side, so a
     *  scroll finds its neighbours already there. */
    private void notifyVisibleSlots() {
        if (mThumbnailsListener == null || mThumbnails.isEmpty() || mDurationMs <= 0) {
            return;
        }
        long margin = windowMs() / 2;
        int first = slotAt(mViewStartMs - margin);
        int last = slotAt(mViewStartMs + windowMs() + margin);
        if (first == mNotifiedFirst && last == mNotifiedLast) {
            return;
        }
        mNotifiedFirst = first;
        mNotifiedLast = last;
        mThumbnailsListener.onThumbnailsNeeded(first, last);
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (mDurationMs <= 0) return false;

        int x = (int) event.getX();
        int w = getWidth();
        if (w <= 0) return false;

        if (mVelocityTracker == null) {
            mVelocityTracker = VelocityTracker.obtain();
        }
        mVelocityTracker.addMovement(event);

        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN: {
                mScroller.forceFinished(true);
                mDownX = x;
                mLastX = x;
                mMoved = false;
                mMode = pickMode(x, w);
                if (mMode == MODE_MOVE) {
                    mGrabOffsetMs = fingerMs(x, w) - mStartMs;
                } else if (mMode == MODE_PAN) {
                    mPanAnchorMs = mViewStartMs;
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
                if (mMode == MODE_NONE) return false;
                mLastX = x;
                if (!mMoved && Math.abs(x - mDownX) > mTouchSlop) {
                    mMoved = true;
                }
                /* A handle follows at once; the range and the film wait
                 * for a drag, so a tap doesn't nudge them. */
                if (mMode == MODE_PAN) {
                    if (mMoved) {
                        setViewStart(mPanAnchorMs - (long) (x - mDownX) * windowMs() / w);
                    }
                } else if (mMode == MODE_MOVE) {
                    if (mMoved) {
                        applyDrag(x);
                        updateEdgeScroll(x, w);
                    }
                } else {
                    applyDrag(x);
                    updateEdgeScroll(x, w);
                }
                return true;
            }

            case MotionEvent.ACTION_UP: {
                if (mMode == MODE_PAN) {
                    if (!mMoved) {
                        placeRangeAt(x, w);
                        performClick();
                    } else {
                        fling(w);
                    }
                }
                endTouch();
                return true;
            }

            case MotionEvent.ACTION_CANCEL: {
                endTouch();
                return true;
            }
        }
        return false;
    }

    @Override
    public boolean performClick() {
        return super.performClick();
    }

    private int pickMode(int x, int w) {
        int startX = msToPixel(mStartMs, w);
        int endX = msToPixel(mEndMs, w);
        int distStart = Math.abs(x - startX);
        int distEnd = Math.abs(x - endX);

        /* Pick the nearer handle when the touch is within slop of
         * both. Tie-break to the start handle since it's the more
         * commonly adjusted one. */
        if (distStart <= mHandleTouchSlop && distStart <= distEnd) {
            return MODE_START;
        }
        if (distEnd <= mHandleTouchSlop) {
            return MODE_END;
        }
        if (x > startX && x < endX) {
            return MODE_MOVE;
        }
        return MODE_PAN;
    }

    private void endTouch() {
        mMode = MODE_NONE;
        mEdgeSpeedMs = 0;
        removeCallbacks(mEdgeScroll);
        mEdgeScrolling = false;
        if (mVelocityTracker != null) {
            mVelocityTracker.recycle();
            mVelocityTracker = null;
        }
        if (getParent() != null) {
            getParent().requestDisallowInterceptTouchEvent(false);
        }
    }

    /** Applies the finger at {@code x} to the handle or range being dragged. */
    private void applyDrag(int x) {
        int w = getWidth();
        if (w <= 0) return;

        long oldStart = mStartMs;
        long oldEnd = mEndMs;
        if (mMode == MODE_START) {
            long ms = clamp(fingerMs(x, w), 0L, mDurationMs);
            long maxStart = Math.max(0L, mEndMs - mMinTrimMs);
            mStartMs = clamp(ms, 0L, maxStart);
            if (mMaxTrimMs > 0 && mEndMs - mStartMs > mMaxTrimMs) {
                mEndMs = mStartMs + mMaxTrimMs;
            }
        } else if (mMode == MODE_END) {
            long ms = clamp(fingerMs(x, w), 0L, mDurationMs);
            long minEnd = Math.min(mDurationMs, mStartMs + mMinTrimMs);
            mEndMs = clamp(ms, minEnd, mDurationMs);
            if (mMaxTrimMs > 0 && mEndMs - mStartMs > mMaxTrimMs) {
                mStartMs = mEndMs - mMaxTrimMs;
            }
        } else if (mMode == MODE_MOVE) {
            /* The range stays on screen; held against an edge, the edge
             * scroll carries it on. */
            long length = mEndMs - mStartMs;
            long lowest = mViewStartMs;
            long highest = Math.min(mDurationMs, mViewStartMs + windowMs()) - length;
            if (highest < lowest) {
                highest = lowest;
            }
            mStartMs = clamp(fingerMs(x, w) - mGrabOffsetMs, lowest, highest);
            mEndMs = mStartMs + length;
        }
        if (mStartMs != oldStart || mEndMs != oldEnd) {
            invalidate();
            notifyTrimChanged(true);
        }
    }

    /** Starts, changes or stops edge scrolling for the finger at {@code x}:
     *  a handle in an edge zone, or the range's own edge in one. The deeper
     *  in, the faster. */
    private void updateEdgeScroll(int x, int w) {
        if (!isWindowed()) {
            mEdgeSpeedMs = 0;
            return;
        }
        int lead = x;
        int trail = x;
        if (mMode == MODE_MOVE) {
            long windowMs = windowMs();
            lead = x - (int) (mGrabOffsetMs * w / windowMs);
            trail = lead + (int) ((mEndMs - mStartMs) * w / windowMs);
        }
        float depth = 0f;
        if (lead < mEdgeZone) {
            depth = -Math.min(1f, (mEdgeZone - lead) / (float) mEdgeZone);
        } else if (trail > w - mEdgeZone) {
            depth = Math.min(1f, (trail - (w - mEdgeZone)) / (float) mEdgeZone);
        }
        mEdgeSpeedMs = (long) (depth * EDGE_WINDOWS_PER_SECOND * windowMs());
        if (edgeScrollBlocked()) {
            mEdgeSpeedMs = 0;
        }
        if (mEdgeSpeedMs != 0 && !mEdgeScrolling) {
            mEdgeScrolling = true;
            mEdgeLastFrameMs = SystemClock.uptimeMillis();
            postOnAnimation(mEdgeScroll);
        }
    }

    /** A handle pushed against the other one can't follow the finger, so
     *  scrolling on would carry the pair off screen under it. */
    private boolean edgeScrollBlocked() {
        if (mMode == MODE_START && mEdgeSpeedMs > 0) {
            return mStartMs >= mEndMs - mMinTrimMs;
        }
        if (mMode == MODE_END && mEdgeSpeedMs < 0) {
            return mEndMs <= mStartMs + mMinTrimMs;
        }
        return false;
    }

    /** A tap on the film puts the range there, centred, its length kept. */
    private void placeRangeAt(int x, int w) {
        long length = mEndMs - mStartMs;
        long latest = Math.max(0L, mDurationMs - length);
        long start = clamp(fingerMs(x, w) - length / 2, 0L, latest);
        if (start == mStartMs) {
            return;
        }
        mStartMs = start;
        mEndMs = start + length;
        if (mStartMs < mViewStartMs) {
            setViewStart(mStartMs);
        } else if (mEndMs > mViewStartMs + windowMs()) {
            setViewStart(mEndMs - windowMs());
        }
        invalidate();
        notifyTrimChanged(true);
    }

    private void fling(int w) {
        if (mVelocityTracker == null || !isWindowed()) {
            return;
        }
        mVelocityTracker.computeCurrentVelocity(1000, mMaxFlingVelocity);
        int velocity = (int) mVelocityTracker.getXVelocity();
        if (Math.abs(velocity) < mMinFlingVelocity) {
            return;
        }
        long windowMs = windowMs();
        int scrollX = (int) (mViewStartMs * w / windowMs);
        int maxScrollX = (int) ((mDurationMs - windowMs) * w / windowMs);
        mScroller.fling(scrollX, 0, -velocity, 0, 0, maxScrollX, 0, 0);
        postInvalidateOnAnimation();
    }

    @Override
    public void computeScroll() {
        super.computeScroll();
        int w = getWidth();
        if (w <= 0 || mDurationMs <= 0) {
            return;
        }
        if (mScroller.computeScrollOffset()) {
            setViewStart((long) mScroller.getCurrX() * windowMs() / w);
            postInvalidateOnAnimation();
        }
    }

    /** The time under {@code x}, not clamped to the video. */
    private long fingerMs(int x, int w) {
        return mViewStartMs + (long) x * windowMs() / w;
    }

    private int msToPixel(long ms, int w) {
        return (int) (((ms - mViewStartMs) * (long) w) / windowMs());
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
