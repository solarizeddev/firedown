package android.view;

import java.util.ArrayList;
import java.util.List;

/** Velocity over the last 100 ms of movement, as the platform estimates it. */
public class VelocityTracker {
    private final List<MotionEvent> mEvents = new ArrayList<>();
    private float mVelocity;
    public static int obtained;
    public static int recycled;

    public static VelocityTracker obtain() {
        obtained++;
        return new VelocityTracker();
    }

    public void addMovement(MotionEvent event) {
        mEvents.add(event);
    }

    public void computeCurrentVelocity(int units, float max) {
        mVelocity = 0f;
        if (mEvents.isEmpty()) {
            return;
        }
        MotionEvent last = mEvents.get(mEvents.size() - 1);
        MotionEvent first = last;
        for (int i = mEvents.size() - 1; i >= 0; i--) {
            MotionEvent e = mEvents.get(i);
            if (last.getEventTime() - e.getEventTime() > 100) {
                break;
            }
            first = e;
        }
        long dt = last.getEventTime() - first.getEventTime();
        if (dt <= 0) {
            return;
        }
        float v = (last.getX() - first.getX()) * units / dt;
        mVelocity = Math.max(-max, Math.min(max, v));
    }

    public float getXVelocity() {
        return mVelocity;
    }

    public void recycle() {
        recycled++;
    }
}
