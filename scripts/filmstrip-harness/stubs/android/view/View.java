package android.view;

import android.content.Context;
import android.content.res.Resources;
import android.graphics.Canvas;
import android.util.AttributeSet;

import java.util.ArrayList;
import java.util.List;

/** What the slider uses of View, plus the harness's own handles: a size,
 *  the animation queue, a frame pump. */
public class View {
    private int mWidth;
    private int mHeight;
    private final List<Runnable> mAnimations = new ArrayList<>();
    private boolean mScrollPending;
    public int clicks;
    public boolean parentDisallowed;

    private final ViewParent mParent = disallow -> parentDisallowed = disallow;

    public View(Context context, AttributeSet attrs, int defStyleAttr) {
    }

    public Resources getResources() {
        return Resources.INSTANCE;
    }

    public final int getWidth() {
        return mWidth;
    }

    public final int getHeight() {
        return mHeight;
    }

    public final void harnessLayout(int width, int height) {
        int oldWidth = mWidth;
        int oldHeight = mHeight;
        mWidth = width;
        mHeight = height;
        onSizeChanged(width, height, oldWidth, oldHeight);
    }

    public void invalidate() {
    }

    public void postInvalidateOnAnimation() {
        mScrollPending = true;
    }

    public void postOnAnimation(Runnable action) {
        mAnimations.add(action);
    }

    public boolean removeCallbacks(Runnable action) {
        mAnimations.remove(action);
        return true;
    }

    public final ViewParent getParent() {
        return mParent;
    }

    public void setClickable(boolean clickable) {
    }

    public boolean performClick() {
        clicks++;
        return true;
    }

    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
    }

    protected void onDraw(Canvas canvas) {
    }

    public void computeScroll() {
    }

    protected void onDetachedFromWindow() {
    }

    public boolean onTouchEvent(MotionEvent event) {
        return false;
    }

    /** One frame: queued animation callbacks, then the scroll a draw would do. */
    public final boolean harnessFrame() {
        boolean busy = false;
        if (!mAnimations.isEmpty()) {
            List<Runnable> now = new ArrayList<>(mAnimations);
            mAnimations.clear();
            for (Runnable r : now) {
                r.run();
            }
            busy = true;
        }
        if (mScrollPending) {
            mScrollPending = false;
            computeScroll();
            busy = true;
        }
        return busy;
    }

    public final void harnessDraw(Canvas canvas) {
        onDraw(canvas);
    }

    public final void harnessDetach() {
        onDetachedFromWindow();
    }
}
