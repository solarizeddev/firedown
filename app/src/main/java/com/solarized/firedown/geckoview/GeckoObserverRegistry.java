package com.solarized.firedown.geckoview;

import android.util.Log;

import java.util.ArrayList;
import java.util.List;

import javax.inject.Inject;
import javax.inject.Singleton;


/**
 * Registry for {@link GeckoObserver} instances.
 *
 * <h3>Design change: strong references, no CopyOnWriteArrayList</h3>
 *
 * <p>The previous implementation used {@code WeakReference<GeckoObserver>}
 * inside a {@link java.util.concurrent.CopyOnWriteArrayList}.  This had
 * two problems:</p>
 *
 * <ol>
 *   <li><b>COWAL removal during iteration is O(n) per removal</b> — each
 *       {@code remove()} call copies the entire backing array.  If several
 *       weak refs were GC'd simultaneously, {@code notifyObservers()} would
 *       do O(n²) array copies.</li>
 *   <li><b>WeakReferences are misleading here</b> — observers already have
 *       a well-defined lifecycle ({@code register} in {@code ON_CREATE},
 *       {@code unregister} in {@code ON_DESTROY}).  Weak refs only add GC
 *       non-determinism: an observer can be collected mid-event-dispatch if
 *       nothing else holds it, causing silent observer loss.</li>
 * </ol>
 *
 * <p>The new implementation uses a plain {@link ArrayList} with strong
 * references.  This is safe because:</p>
 * <ul>
 *   <li>All calls happen on the main thread (GeckoView delegates are
 *       always dispatched on main).</li>
 *   <li>The observer count is tiny (typically 1, at most 2–3).</li>
 *   <li>{@code notifyObservers()} iterates a snapshot (size-indexed loop)
 *       so concurrent {@code register}/{@code unregister} from within a
 *       callback is safe — it modifies the list but not the snapshot.</li>
 * </ul>
 *
 * <p>If you ever need thread-safety (unlikely given GeckoView's threading
 * model), wrap the list in {@code Collections.synchronizedList()} and
 * snapshot in {@code notifyObservers()} under the same lock.</p>
 */
@Singleton
public class GeckoObserverRegistry {

    private static final String TAG = GeckoObserverRegistry.class.getSimpleName();

    private final List<GeckoObserver> mObservers = new ArrayList<>();

    @Inject
    public GeckoObserverRegistry() {}

    /**
     * Registers an observer.  If already registered, this is a no-op
     * (prevents duplicate notifications from redundant {@code register}
     * calls in {@code enterBrowsing()}).
     */
    public void register(GeckoObserver observer) {
        if (observer == null) return;
        if (!mObservers.contains(observer)) {
            mObservers.add(observer);
        }
    }

    /**
     * Unregisters an observer.  Safe to call if not registered.
     */
    public void unregister(GeckoObserver observer) {
        mObservers.remove(observer);
    }

    /**
     * Notifies all registered observers.
     *
     * <p>Uses a size-indexed loop over the live list.  If an observer
     * calls {@code register()} or {@code unregister()} from within its
     * callback, the modification is visible on the next iteration but
     * does not cause a {@link java.util.ConcurrentModificationException}
     * because we index by position, not by iterator.</p>
     *
     * <p>If a callback throws, the exception is logged and swallowed so
     * remaining observers still receive the event.</p>
     */
    public void notifyObservers(GeckoObserverInvoker invoker, Object... objects) {
        // Snapshot the observer list so concurrent modification during
        // dispatch (register/unregister called from within a callback)
        // cannot cause us to skip an observer. The previous size-indexed
        // approach could skip the observer that slid into a removed slot.
        GeckoObserver[] snapshot;
        int size = mObservers.size();
        if (size == 0) return;
        snapshot = mObservers.toArray(new GeckoObserver[size]);
        for (GeckoObserver observer : snapshot) {
            try {
                invoker.callMethod(observer, objects);
            } catch (Exception e) {
                Log.e(TAG, "Observer " + observer.getClass().getSimpleName()
                        + " threw during " + invoker, e);
            }
        }
    }
}