import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.VelocityTracker;

import com.solarized.firedown.ui.FilmstripTrimSlider;

import java.lang.reflect.Field;
import java.util.Random;

/**
 * Drives the REAL FilmstripTrimSlider through touch sequences on a phone-sized
 * strip (330 dp x 64 dp at density 2.75) and checks where the range and the
 * window end up. Exit status 1 on any failure.
 */
public final class FilmstripHarness {

    private static final int W = 907;
    private static final int H = 176;
    private static final long MAX = 30_000L;
    private static final long MIN = 200L;
    private static final int DIM = Color.argb(160, 0, 0, 0);
    private static final int BAR_TRACK = Color.argb(140, 0, 0, 0);
    private static final int FRAME_MS = 16;

    private static int sPass;
    private static int sFail;

    private FilmstripTrimSlider mView;
    private int mNotifiedFirst = -1;
    private int mNotifiedLast = -1;
    private long mLastTrimStart = -1;
    private long mLastTrimEnd = -1;

    public static void main(String[] args) throws Exception {
        String only = args.length > 0 ? args[0] : "";
        if (only.isEmpty() || only.equals("short")) shortVideo();
        if (only.isEmpty() || only.equals("medium")) mediumVideo();
        if (only.isEmpty() || only.equals("long")) longVideo();
        if (only.isEmpty() || only.equals("edges")) edges();
        if (only.isEmpty() || only.equals("storm")) storm();
        System.out.println("filmstrip: " + sPass + " passed, " + sFail + " failed");
        System.exit(sFail == 0 ? 0 : 1);
    }

    private static void check(boolean ok, String what) {
        if (ok) {
            sPass++;
        } else {
            sFail++;
            System.out.println("FAIL " + what);
        }
    }

    // ---- set-up and gestures ------------------------------------------

    private static FilmstripHarness strip(long durationMs) {
        FilmstripHarness h = new FilmstripHarness();
        h.mView = new FilmstripTrimSlider(new Context());
        h.mView.setMinTrimMs(MIN);
        h.mView.setMaxTrimMs(MAX);
        h.mView.setOnTrimChangedListener((s, e, fromUser) -> {
            h.mLastTrimStart = s;
            h.mLastTrimEnd = e;
        });
        h.mView.setOnThumbnailsNeededListener((first, last) -> {
            h.mNotifiedFirst = first;
            h.mNotifiedLast = last;
        });
        h.mView.harnessLayout(W, H);
        h.mView.setDuration(durationMs);
        h.mView.setThumbnailCount(h.mView.thumbnailSlotCount());
        return h;
    }

    private long start() {
        return mView.getStartMs();
    }

    private long end() {
        return mView.getEndMs();
    }

    private long field(String name) {
        try {
            Field f = FilmstripTrimSlider.class.getDeclaredField(name);
            f.setAccessible(true);
            return ((Number) f.get(mView)).longValue();
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    private long viewStart() {
        return field("mViewStartMs");
    }

    private long duration() {
        return field("mDurationMs");
    }

    private long window() {
        long d = duration();
        return d > 2 * MAX ? 2 * MAX : d;
    }

    private int px(long ms) {
        return (int) ((ms - viewStart()) * W / window());
    }

    private int mModeAtDown;

    private void touch(int action, int x) {
        mView.onTouchEvent(MotionEvent.of(action, x));
        if (action == MotionEvent.ACTION_DOWN) {
            mModeAtDown = (int) field("mMode");
        }
    }

    private void frame() {
        SystemClock.advance(FRAME_MS);
        mView.harnessFrame();
    }

    private void settle() {
        for (int i = 0; i < 2000; i++) {
            SystemClock.advance(FRAME_MS);
            if (!mView.harnessFrame()) {
                return;
            }
        }
    }

    /** A drag from a to b in steps, a frame per step; held {@code holdMs}
     *  at b before lifting. */
    private void drag(int a, int b, int steps, long holdMs, boolean lift) {
        touch(MotionEvent.ACTION_DOWN, a);
        for (int i = 1; i <= steps; i++) {
            frame();
            touch(MotionEvent.ACTION_MOVE, a + (b - a) * i / steps);
        }
        for (long t = 0; t < holdMs; t += FRAME_MS) {
            frame();
            touch(MotionEvent.ACTION_MOVE, b);
        }
        if (lift) {
            frame();
            touch(MotionEvent.ACTION_UP, b);
        }
    }

    private void slowDrag(int a, int b) {
        drag(a, b, 40, 120, true);
    }

    private void tap(int x) {
        touch(MotionEvent.ACTION_DOWN, x);
        frame();
        touch(MotionEvent.ACTION_UP, x);
    }

    private Canvas draw() {
        Canvas c = new Canvas();
        mView.harnessDraw(c);
        return c;
    }

    private boolean barDrawn() {
        for (Canvas.Op op : draw().ops) {
            if (!op.bitmap && op.color == BAR_TRACK) {
                return true;
            }
        }
        return false;
    }

    /** The cells (pictures or placeholders) cover the strip edge to edge. */
    private boolean cellsCover() {
        boolean[] covered = new boolean[W];
        for (Canvas.Op op : draw().ops) {
            boolean cell = op.bitmap || op.color == Color.DKGRAY;
            if (!cell || op.top != 0 || op.bottom != H) {
                continue;
            }
            for (int x = (int) Math.max(0, op.left); x < Math.min(W, op.right); x++) {
                covered[x] = true;
            }
        }
        for (boolean b : covered) {
            if (!b) {
                return false;
            }
        }
        return true;
    }

    private boolean asksForVisibleSlots() {
        int count = mView.thumbnailSlotCount();
        long d = duration();
        int first = (int) Math.min(count - 1, viewStart() * count / d);
        int last = (int) Math.min(count - 1, (viewStart() + window()) * count / d);
        return mNotifiedFirst <= first && mNotifiedLast >= last;
    }

    // ---- cases ----------------------------------------------------------

    private static void shortVideo() {
        FilmstripHarness h = strip(20_000L);
        check(h.start() == 0 && h.end() == 20_000, "short: the whole 20 s video selected");
        check(!h.barDrawn(), "short: no position bar on a video shown whole");
        check(h.mView.thumbnailSlotCount() == W / 176, "short: one slot per 64 dp");
        check(h.mNotifiedFirst == 0 && h.mNotifiedLast == W / 176 - 1, "short: every slot asked for");
        check(h.cellsCover(), "short: cells cover the strip");

        h.slowDrag(W / 2, W / 2 + 200);
        check(h.start() == 0 && h.end() == 20_000, "short: dragging a full range moves nothing");

        h.slowDrag(W, W / 2);
        check(Math.abs(h.end() - 10_000) < 100, "short: end handle dragged to the middle: " + h.end());

        long before = h.start();
        long length = h.end() - h.start();
        h.slowDrag(200, 500);
        long moved = h.start() - before;
        check(h.end() - h.start() == length && Math.abs(moved - 300L * 20_000 / W) < 100,
                "short: the range follows a drag inside it: moved " + moved);

        h.tap(W - 20);
        check(h.end() == 20_000 && h.end() - h.start() == length,
                "short: a tap near the end puts the range at the end");
        check(h.mView.clicks == 1, "short: a tap is a click");
    }

    private static void mediumVideo() {
        FilmstripHarness h = strip(50_000L);
        check(h.end() == MAX, "medium: 50 s selects the first 30 s");
        check(!h.barDrawn(), "medium: shown whole (50 s is under two limits)");
        h.slowDrag(h.px(MAX), W);
        check(h.end() == 50_000 && h.start() == 20_000,
                "medium: the end dragged to the video's end carries the start: " + h.start() + ".." + h.end());
    }

    private static void longVideo() {
        long d = 9 * 60_000L;
        FilmstripHarness h = strip(d);
        int width = h.px(h.end()) - h.px(h.start());
        check(width >= W * 45 / 100, "long: the 30 s range is half the strip, not a sliver: " + width + " px");
        check(h.barDrawn(), "long: the position bar is drawn");
        check(h.mView.thumbnailSlotCount() == (int) Math.ceil(5 * 9.0),
                "long: slots over the whole video: " + h.mView.thumbnailSlotCount());
        check(h.asksForVisibleSlots() && h.mNotifiedFirst == 0,
                "long: the window's slots asked for: " + h.mNotifiedFirst + ".." + h.mNotifiedLast);
        check(h.mNotifiedLast < h.mView.thumbnailSlotCount() / 2,
                "long: only the slots near the window asked for: last " + h.mNotifiedLast);
        check(h.cellsCover(), "long: cells cover the strip");

        h.slowDrag(W - 50, 100);
        long expect = (long) (W - 150) * 60_000L / W;
        check(Math.abs(h.viewStart() - expect) < 200, "long: dragging the film scrolls it: "
                + h.viewStart() + " ~ " + expect);
        check(h.start() == 0 && h.end() == MAX, "long: scrolling leaves the range where it was");
        check(h.asksForVisibleSlots(), "long: a scroll asks for the slots it shows");
        check(h.cellsCover(), "long: cells cover the scrolled strip");

        h.tap(W / 2);
        long middle = h.viewStart() + 30_000L;
        check(Math.abs((h.start() + h.end()) / 2 - middle) < 200 && h.end() - h.start() == MAX,
                "long: a tap puts the range there: " + h.start() + ".." + h.end());

        h.drag(W - 30, 20, 4, 0, true);
        long lifted = h.viewStart();
        h.settle();
        check(h.viewStart() > lifted + 10_000, "long: a fling scrolls on after the finger lifts: "
                + lifted + " -> " + h.viewStart());
        check(h.viewStart() <= d - 60_000, "long: a fling stops at the end of the video");

        h.drag(W - 10, 100, 4, 0, true);
        h.settle();
        h.drag(W - 10, 100, 4, 0, true);
        h.settle();
        h.drag(W - 10, 100, 4, 0, true);
        h.settle();
        check(h.viewStart() == d - 60_000, "long: flings reach the end of the video: " + h.viewStart());

        h.tap(W - 5);
        check(h.end() == d && h.end() - h.start() == MAX, "long: the last 30 s can be selected");
    }

    private static void edges() {
        long d = 9 * 60_000L;

        FilmstripHarness h = strip(d);
        int grab = (h.px(h.start()) + h.px(h.end())) / 2;
        h.drag(grab, W - 5, 20, 8_000, true);
        check(h.end() == d && h.viewStart() == d - 60_000,
                "edges: the range held against the right edge rides to the end: " + h.start() + ".." + h.end());
        check(h.end() - h.start() == MAX, "edges: the ride keeps the range's length");
        check(h.asksForVisibleSlots(), "edges: the ride asks for the slots it shows");

        grab = (h.px(h.start()) + h.px(h.end())) / 2;
        h.drag(grab, 5, 20, 8_000, true);
        check(h.start() == 0 && h.viewStart() == 0, "edges: and against the left edge back to the start");

        h = strip(d);
        h.drag(h.px(h.end()), W - 5, 20, 3_000, true);
        check(h.end() > 120_000 && h.end() - h.start() == MAX,
                "edges: the end handle held at the edge carries the range: " + h.start() + ".." + h.end());
        long end = h.end();
        h.drag(h.px(h.start()), 5, 20, 1_000, true);
        check(h.start() < end - MAX - 10_000 && h.end() - h.start() == MAX,
                "edges: the start handle held at the left edge carries it back: " + h.start() + ".." + h.end());

        h = strip(d);
        h.drag(h.px(0) + 2, W - 5, 30, 2_000, true);
        check(h.viewStart() == 0, "edges: a start handle pinned against the end doesn't scroll the window: "
                + h.viewStart());
        check(h.end() - h.start() == MIN, "edges: and stops one minimum before the end");

        h = strip(d);
        h.drag(W / 4, W - 5, 20, 0, false);
        h.mView.harnessDetach();
        for (int i = 0; i < 20; i++) {
            h.frame();
        }
        long after = h.viewStart();
        h.frame();
        check(h.viewStart() == after, "edges: detaching stops the edge scroll");

        check(VelocityTracker.obtained >= VelocityTracker.recycled, "edges: trackers balanced");
    }

    /** Random gestures on many lengths; the range and window stay valid. */
    private static void storm() {
        long[] lengths = {150, 200, 1_000, 29_999, 30_000, 45_000, 60_000, 60_001,
                180_000, 540_000, 7_200_000};
        Random random = new Random(7);
        int gestures = 0;
        for (long d : lengths) {
            FilmstripHarness h = strip(d);
            boolean ok = true;
            String why = "";
            for (int g = 0; g < 1500 && ok; g++) {
                int kind = random.nextInt(6);
                int a;
                int b = random.nextInt(W + 40) - 20;
                if (kind == 0) {
                    a = h.px(h.start());
                } else if (kind == 1) {
                    a = h.px(h.end());
                } else if (kind == 2) {
                    a = (h.px(h.start()) + h.px(h.end())) / 2;
                } else {
                    a = random.nextInt(W);
                }
                a = Math.max(0, Math.min(W - 1, a));
                if (kind == 5) {
                    h.tap(a);
                } else {
                    int steps = 1 + random.nextInt(12);
                    long hold = random.nextInt(4) == 0 ? random.nextInt(1500) : 0;
                    h.drag(a, b, steps, hold, true);
                    if (h.mModeAtDown == 3) {
                        long v = h.viewStart();
                        if (h.start() < v || h.end() > v + h.window()) {
                            ok = false;
                            why = "a dragged range left the screen";
                        }
                    }
                }
                h.settle();
                gestures++;
                long s = h.start();
                long e = h.end();
                long v = h.viewStart();
                if (s < 0 || e > d || s >= e) {
                    ok = false;
                    why = "range outside the video " + s + ".." + e;
                } else if (e - s < Math.min(MIN, d)) {
                    ok = false;
                    why = "range under the minimum " + s + ".." + e;
                } else if (e - s > MAX) {
                    ok = false;
                    why = "range over the limit " + s + ".." + e;
                } else if (v < 0 || v > Math.max(0, d - h.window())) {
                    ok = false;
                    why = "window outside the video " + v;
                } else if (!h.cellsCover()) {
                    ok = false;
                    why = "a gap between cells at window " + v;
                } else if (!h.asksForVisibleSlots()) {
                    ok = false;
                    why = "visible slots not asked for at window " + v;
                } else if (h.mLastTrimStart != s || h.mLastTrimEnd != e) {
                    ok = false;
                    why = "the host wasn't told " + s + ".." + e;
                }
            }
            check(ok, "storm " + d + " ms: " + (ok ? "valid" : why));
        }
        check(gestures > 10_000, "storm: " + gestures + " gestures");
    }
}
