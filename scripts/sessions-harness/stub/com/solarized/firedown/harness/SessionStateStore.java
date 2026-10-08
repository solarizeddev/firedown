package com.solarized.firedown.harness;

import android.content.Context;

/** The one call the spliced strict reader makes into the state store: a
 *  session_ref is re-anchored by basename on a real install; here it is
 *  returned as-is so the harness can assert the ref round-trips. */
final class SessionStateStore {
    static String resolve(Context context, String ref) {
        return ref;
    }
}
