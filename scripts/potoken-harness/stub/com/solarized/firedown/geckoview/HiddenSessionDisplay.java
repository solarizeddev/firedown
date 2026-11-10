package com.solarized.firedown.geckoview;

import org.mozilla.geckoview.GeckoSession;

/** Stand-in: the harness has no surfaces. */
final class HiddenSessionDisplay {
    static void attach(GeckoSession session) { }
    static void release(GeckoSession session) { }
}
