package android.util;

import java.util.LinkedHashMap;
import java.util.Map;

/** android.util.LruCache's contract, minimally: an access-ordered map, a
 *  byte size from sizeOf, the eldest evicted until the budget holds. */
public class LruCache<K, V> {
    private final LinkedHashMap<K, V> map = new LinkedHashMap<>(0, 0.75f, true);
    private final int maxSize;
    private int size;

    public LruCache(int maxSize) { this.maxSize = maxSize; }

    protected int sizeOf(K key, V value) { return 1; }

    public final synchronized V get(K key) { return map.get(key); }

    public final synchronized V put(K key, V value) {
        V previous = map.put(key, value);
        if (previous != null) size -= sizeOf(key, previous);
        size += sizeOf(key, value);
        trimToSize(maxSize);
        return previous;
    }

    public synchronized void trimToSize(int max) {
        while (size > max && !map.isEmpty()) {
            Map.Entry<K, V> eldest = map.entrySet().iterator().next();
            map.remove(eldest.getKey());
            size -= sizeOf(eldest.getKey(), eldest.getValue());
        }
    }

    public final synchronized V remove(K key) {
        V previous = map.remove(key);
        if (previous != null) size -= sizeOf(key, previous);
        return previous;
    }

    public final synchronized void evictAll() { map.clear(); size = 0; }

    public final synchronized int size() { return size; }
}
