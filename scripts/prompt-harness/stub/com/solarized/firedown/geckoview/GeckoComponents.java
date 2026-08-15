package com.solarized.firedown.geckoview;
import org.mozilla.geckoview.GeckoResult;
import java.util.concurrent.atomic.AtomicBoolean;
/** Only what the manager touches: PermissionResult. Kept behaviorally
 *  identical to the real nested class (one-shot latch over a GeckoResult) —
 *  small enough to keep in step by eye; the REAL latch is six lines. */
public class GeckoComponents {
    public static final class PermissionResult {
        private final GeckoResult<Integer> mResult = new GeckoResult<>();
        private final AtomicBoolean mAnswered = new AtomicBoolean(false);
        public void complete(int value) { if (mAnswered.compareAndSet(false, true)) mResult.complete(value); }
        public boolean isAnswered() { return mAnswered.get(); }
        public GeckoResult<Integer> result() { return mResult; }
    }
}
