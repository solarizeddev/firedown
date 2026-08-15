package com.solarized.firedown.geckoview;

import android.os.SystemClock;

/**
 * The per-tab prompt flood breaker: a page must not be able to nag in a
 * modal loop (a {@code while(true) alert()}, or a permission re-request
 * storm). Once {@link #PROMPT_FLOOD_COUNT} dialogs have been shown on a tab
 * inside {@link #PROMPT_FLOOD_WINDOW_MS}, further prompts are auto-dismissed
 * until the window drains. Self-healing — a page that stops prompting gets
 * dialogs back after the window — and measured on uptimeMillis so a
 * wall-clock jump can't wedge it.
 *
 * <p>One instance per {@link GeckoState}, owned there; extracted to its own
 * class so {@code scripts/prompt-harness} can compile and drive the REAL
 * ring under a stubbed {@code SystemClock} (GeckoState itself is not
 * harness-compilable — it drags half the app's imports).</p>
 */
public class PromptFloodGate {

    private static final int PROMPT_FLOOD_COUNT = 3;
    private static final long PROMPT_FLOOD_WINDOW_MS = 10_000;

    private final long[] mShownAt = new long[PROMPT_FLOOD_COUNT];
    private int mIdx;

    /** Record one actually-SHOWN dialog. */
    public void recordPromptShown() {
        mShownAt[mIdx] = SystemClock.uptimeMillis();
        mIdx = (mIdx + 1) % PROMPT_FLOOD_COUNT;
    }

    /** True when showing one more dialog would exceed the flood budget. */
    public boolean isPromptFlooding() {
        // mIdx points at the OLDEST recorded dialog. If even that one is
        // still inside the window, the next dialog would be the (COUNT+1)th
        // within it — flooding.
        long oldest = mShownAt[mIdx];
        if (oldest == 0) {
            return false;
        }
        return SystemClock.uptimeMillis() - oldest < PROMPT_FLOOD_WINDOW_MS;
    }
}
