package com.solarized.firedown.ui;

import android.content.Context;
import android.graphics.Matrix;
import android.graphics.PointF;
import android.graphics.drawable.Drawable;
import android.util.AttributeSet;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;

import androidx.annotation.NonNull;
import androidx.appcompat.widget.AppCompatImageView;

public class ZoomableImageView extends AppCompatImageView {

    private static final float MIN_ZOOM = 1.0f;
    private static final float MAX_ZOOM = 8.0f;
    private static final float DOUBLE_TAP_ZOOM = 3.0f;

    private final Matrix mMatrix = new Matrix();
    private final float[] mMatrixValues = new float[9];
    private final PointF mLastTouch = new PointF();

    private float mBaseScale = 1.0f;

    private ScaleGestureDetector mScaleDetector;
    private GestureDetector mGestureDetector;

    private boolean mIsScaling = false;
    private int mActivePointerId = MotionEvent.INVALID_POINTER_ID;

    public ZoomableImageView(Context context) {
        super(context);
        init(context);
    }

    public ZoomableImageView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init(context);
    }

    public ZoomableImageView(Context context, AttributeSet attrs, int defStyle) {
        super(context, attrs, defStyle);
        init(context);
    }

    private void init(Context context) {
        setScaleType(ScaleType.MATRIX);

        mScaleDetector = new ScaleGestureDetector(context, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
            @Override
            public boolean onScaleBegin(@NonNull ScaleGestureDetector detector) {
                mIsScaling = true;
                return true;
            }

            @Override
            public boolean onScale(@NonNull ScaleGestureDetector detector) {
                float factor = detector.getScaleFactor();
                float currentZoom = getZoomLevel();
                float targetZoom = currentZoom * factor;

                if (targetZoom < MIN_ZOOM) factor = MIN_ZOOM / currentZoom;
                else if (targetZoom > MAX_ZOOM) factor = MAX_ZOOM / currentZoom;

                mMatrix.postScale(factor, factor, detector.getFocusX(), detector.getFocusY());
                applyMatrix();
                return true;
            }

            @Override
            public void onScaleEnd(@NonNull ScaleGestureDetector detector) {
                mIsScaling = false;
                if (getZoomLevel() < MIN_ZOOM) fitCenter();
                else clampTranslation();
            }
        });

        mGestureDetector = new GestureDetector(context, new GestureDetector.SimpleOnGestureListener() {
            @Override
            public boolean onDoubleTap(@NonNull MotionEvent e) {
                float currentZoom = getZoomLevel();
                float targetZoom;
                if (currentZoom < DOUBLE_TAP_ZOOM - 0.1f) {
                    targetZoom = DOUBLE_TAP_ZOOM;
                } else {
                    targetZoom = MIN_ZOOM;
                }
                float factor = targetZoom / currentZoom;
                mMatrix.postScale(factor, factor, e.getX(), e.getY());
                clampTranslation();
                return true;
            }
        });
    }

    /**
     * Returns zoom level relative to the base fit-center scale.
     * 1.0 = fitted, 3.0 = 3x zoomed in, etc.
     */
    private float getZoomLevel() {
        mMatrix.getValues(mMatrixValues);
        float absScale = mMatrixValues[Matrix.MSCALE_X];
        return mBaseScale > 0 ? absScale / mBaseScale : 1.0f;
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        fitCenter();
    }

    @Override
    public void setImageDrawable(Drawable drawable) {
        super.setImageDrawable(drawable);
        if (drawable != null && getWidth() > 0) {
            fitCenter();
        }
    }

    private void fitCenter() {
        Drawable d = getDrawable();
        if (d == null) return;

        int dw = d.getIntrinsicWidth();
        int dh = d.getIntrinsicHeight();
        int vw = getWidth();
        int vh = getHeight();
        if (dw <= 0 || dh <= 0 || vw <= 0 || vh <= 0) return;

        mBaseScale = Math.min((float) vw / dw, (float) vh / dh);
        float dx = (vw - dw * mBaseScale) / 2f;
        float dy = (vh - dh * mBaseScale) / 2f;

        mMatrix.reset();
        mMatrix.postScale(mBaseScale, mBaseScale);
        mMatrix.postTranslate(dx, dy);
        setImageMatrix(mMatrix);
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        mScaleDetector.onTouchEvent(event);
        mGestureDetector.onTouchEvent(event);

        if (mIsScaling) return true;

        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN -> {
                mActivePointerId = event.getPointerId(0);
                mLastTouch.set(event.getX(), event.getY());
            }
            case MotionEvent.ACTION_MOVE -> {
                if (mActivePointerId == MotionEvent.INVALID_POINTER_ID) break;
                int idx = event.findPointerIndex(mActivePointerId);
                if (idx < 0) break;
                float dx = event.getX(idx) - mLastTouch.x;
                float dy = event.getY(idx) - mLastTouch.y;
                if (getZoomLevel() > MIN_ZOOM) {
                    mMatrix.postTranslate(dx, dy);
                    applyMatrix();
                }
                mLastTouch.set(event.getX(idx), event.getY(idx));
            }
            case MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                mActivePointerId = MotionEvent.INVALID_POINTER_ID;
                clampTranslation();
            }
            case MotionEvent.ACTION_POINTER_UP -> {
                int pointerIdx = event.getActionIndex();
                int pointerId = event.getPointerId(pointerIdx);
                if (pointerId == mActivePointerId) {
                    int newIdx = pointerIdx == 0 ? 1 : 0;
                    mActivePointerId = event.getPointerId(newIdx);
                    mLastTouch.set(event.getX(newIdx), event.getY(newIdx));
                }
            }
        }
        return true;
    }

    private void clampTranslation() {
        Drawable d = getDrawable();
        if (d == null) return;

        if (getZoomLevel() <= MIN_ZOOM) {
            fitCenter();
            return;
        }

        mMatrix.getValues(mMatrixValues);
        float absScale = mMatrixValues[Matrix.MSCALE_X];
        float tx = mMatrixValues[Matrix.MTRANS_X];
        float ty = mMatrixValues[Matrix.MTRANS_Y];

        float scaledW = d.getIntrinsicWidth() * absScale;
        float scaledH = d.getIntrinsicHeight() * absScale;
        int vw = getWidth();
        int vh = getHeight();

        float dx = 0, dy = 0;

        if (scaledW > vw) {
            if (tx > 0) dx = -tx;
            else if (tx + scaledW < vw) dx = vw - tx - scaledW;
        } else {
            dx = (vw - scaledW) / 2f - tx;
        }

        if (scaledH > vh) {
            if (ty > 0) dy = -ty;
            else if (ty + scaledH < vh) dy = vh - ty - scaledH;
        } else {
            dy = (vh - scaledH) / 2f - ty;
        }

        mMatrix.postTranslate(dx, dy);
        applyMatrix();
    }

    private void applyMatrix() {
        setImageMatrix(mMatrix);
    }
}