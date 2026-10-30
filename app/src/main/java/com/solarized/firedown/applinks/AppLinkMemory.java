package com.solarized.firedown.applinks;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * What one tab remembers about app links, keyed by the app's package (or the
 * link's scheme when no app is named). Pure Java; times are the caller's
 * monotonic clock ({@code SystemClock.elapsedRealtime()}).
 *
 * <p>Three facts, from Firefox's AppLinksInterceptor where it has them: a
 * link to the same app within {@link #JUST_HANDLED_MS} is the page firing it
 * twice; Stay quiets page-fired links to that app for {@link #REMEMBER_MS};
 * so does one block, so the snackbar says it once.</p>
 */
public final class AppLinkMemory {

    public static final long JUST_HANDLED_MS = 2_000L;
    public static final long REMEMBER_MS = 60L * 60L * 1000L;
    static final int MAX_KEYS = 32;

    private static final long NEVER = Long.MIN_VALUE;

    private static final class Entry {
        long handledAt = NEVER;
        long stayAt = NEVER;
        long blockedAt = NEVER;
    }

    private final LinkedHashMap<String, Entry> mEntries = new LinkedHashMap<>(16, 0.75f, true);

    public synchronized boolean wasJustHandled(String key, long now) {
        Entry e = mEntries.get(key);
        return e != null && within(e.handledAt, now, JUST_HANDLED_MS);
    }

    public synchronized boolean isStayRemembered(String key, long now) {
        Entry e = mEntries.get(key);
        return e != null && within(e.stayAt, now, REMEMBER_MS);
    }

    public synchronized boolean isBlockRemembered(String key, long now) {
        Entry e = mEntries.get(key);
        return e != null && within(e.blockedAt, now, REMEMBER_MS);
    }

    public synchronized void recordHandled(String key, long now) {
        entry(key).handledAt = now;
    }

    public synchronized void rememberStay(String key, long now) {
        entry(key).stayAt = now;
    }

    public synchronized void rememberBlock(String key, long now) {
        entry(key).blockedAt = now;
    }

    synchronized int size() {
        return mEntries.size();
    }

    private Entry entry(String key) {
        Entry e = mEntries.get(key);
        if (e == null) {
            e = new Entry();
            mEntries.put(key, e);
            trim();
        }
        return e;
    }

    private void trim() {
        Iterator<Map.Entry<String, Entry>> it = mEntries.entrySet().iterator();
        while (mEntries.size() > MAX_KEYS && it.hasNext()) {
            it.next();
            it.remove();
        }
    }

    private static boolean within(long at, long now, long window) {
        if (at == NEVER) {
            return false;
        }
        long age = now - at;
        return age >= 0 && age <= window;
    }
}
