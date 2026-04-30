package com.solarized.firedown.geckoview;

import android.content.Context;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;

import androidx.annotation.NonNull;

public class BrowserGestureDetector {

    private final ScaleGestureDetector scaleGestureDetector;

    private final GestureDetector mGestureDetector;

    public interface BrowserGestureListener{

        void onScroll(float distanceX, float distanceY);

        void onVerticalScroll(float distance);

        void onHorizontalScroll(float distance);

        void onScaleBegin(float scaleFactor);

        void onScale(float scaleFactor);

        void onScaleEnd(float scaleFactor);
    }

    private final BrowserGestureListener mCallback;


    public BrowserGestureDetector(Context context, BrowserGestureListener browserGestureListener){

        mCallback = browserGestureListener;

        scaleGestureDetector = new ScaleGestureDetector(context, new CustomScaleDetectorListener());

        mGestureDetector = new GestureDetector(context, new CustomScrollDetectorListener());

    }


    public boolean handleTouchEvent(MotionEvent event){
        int eventAction = event.getAction();

        // A double tap for a quick scale gesture (quick double tap followed by a drag)
        // would trigger a ACTION_CANCEL event before the MOVE_EVENT.
        // This would prevent the scale detector from properly inferring the movement.
        // We'll want to ignore ACTION_CANCEL but process the next stream of events.
        if (eventAction != MotionEvent.ACTION_CANCEL) {
            scaleGestureDetector.onTouchEvent(event);
        }

        // Ignore scrolling if zooming is already in progress.
        // Always pass motion begin / end events just to have the detector ready
        // to infer scrolls when the scale gesture ended.
        if (!scaleGestureDetector.isInProgress() ||
                eventAction == MotionEvent.ACTION_DOWN ||
                eventAction == MotionEvent.ACTION_UP ||
                eventAction == MotionEvent.ACTION_CANCEL
        ) {
            try {
                return mGestureDetector.onTouchEvent(event);
            } catch (Exception e) {
                return false;
            }
        } else {
            return false;
        }
    }

    private class CustomScrollDetectorListener extends GestureDetector.SimpleOnGestureListener{
        @Override
        public boolean onScroll(MotionEvent previousEvent, @NonNull MotionEvent currentEvent, float distanceX, float distanceY) {
            if(mCallback != null)
                mCallback.onScroll(distanceX, distanceY);

            // We got many crashes because of the initial event - ACTION_DOWN being null.
            // Investigations to be continued in android-components/issues/8552.
            // In the meantime we'll protect against this with a simple null check.
            if (previousEvent != null) {
                if (Math.abs(currentEvent.getY() - previousEvent.getY()) >= Math.abs(currentEvent.getX() - previousEvent.getX())) {
                    if(mCallback != null)
                        mCallback.onVerticalScroll(distanceY);
                } else {
                    if(mCallback != null)
                        mCallback.onHorizontalScroll(distanceX);
                }
            }
            return true;
        }
    }

    private class CustomScaleDetectorListener extends ScaleGestureDetector.SimpleOnScaleGestureListener{

        @Override
        public boolean onScaleBegin(@NonNull ScaleGestureDetector detector) {
            if(mCallback != null)
                mCallback.onScaleBegin(detector.getScaleFactor());
            return true;
        }

        @Override
        public boolean onScale(@NonNull ScaleGestureDetector detector) {
            if(mCallback != null)
                mCallback.onScale(detector.getScaleFactor());
            return true;
        }

        @Override
        public void onScaleEnd(@NonNull ScaleGestureDetector detector) {
            if(mCallback != null)
                mCallback.onScaleEnd(detector.getScaleFactor());
        }
    }
}