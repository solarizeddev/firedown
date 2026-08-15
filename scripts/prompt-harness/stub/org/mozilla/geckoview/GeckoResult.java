package org.mozilla.geckoview;
import java.util.*;
/** Mirrors the one real behavior that matters here: completing twice THROWS. */
public class GeckoResult<T> {
    public static final List<GeckoResult<?>> ALL = new ArrayList<>();
    public boolean completed; public T value;
    public GeckoResult() { ALL.add(this); }
    public void complete(T v) {
        if (completed) throw new IllegalStateException("Cannot complete the same GeckoResult twice");
        completed = true; value = v;
    }
    public static <T> GeckoResult<T> fromValue(T v) { GeckoResult<T> r = new GeckoResult<>(); r.complete(v); return r; }
}
