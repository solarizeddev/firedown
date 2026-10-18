package com.solarized.firedown.sync;

import com.solarized.firedown.sync.model.VaultEntry;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * Decoded sidecars of a NETWORK store (WebDAV, S3), and the loader that fills
 * it: a byte-bounded LRU keyed by account, object id and the server's version
 * of the sidecar (an ETag, else size and date), so the Downloads marks, the
 * status and the Backups list do not each re-read every sidecar, plus a small
 * pool that reads the misses four at a time. The entries are immutable and
 * shared with whoever listed them, so a list on screen costs nothing extra.
 *
 * <p>A sidecar that vanished between the listing and the read (deleted from
 * another phone) is skipped; any other failure fails the load, so a listing
 * is never silently short because the network hiccuped halfway.
 */
final class SidecarCache {

    /** Upper bound of the cache, mostly thumbnails. */
    static final long CACHE_BYTES = 16L * 1024 * 1024;
    private static final int PARALLEL_READS = 4;

    /** Reads one sidecar's bytes; null when it is gone. */
    interface Reader<H> {
        byte[] read(BackupLayout.Item<H> item) throws IOException;
    }

    /** What says a sidecar is unchanged. */
    interface Validator<H> {
        String of(BackupLayout.Item<H> item);
    }

    private static final class Slot {
        final String validator;
        final VaultEntry entry;
        final long weight;

        Slot(String validator, VaultEntry entry, long weight) {
            this.validator = validator;
            this.entry = entry;
            this.weight = weight;
        }
    }

    private final long budget;
    private final LinkedHashMap<String, Slot> slots = new LinkedHashMap<>(64, 0.75f, true);
    private long used;
    private final ExecutorService readers;

    SidecarCache(long budget, String threadName) {
        this.budget = budget;
        ThreadPoolExecutor pool = new ThreadPoolExecutor(PARALLEL_READS, PARALLEL_READS,
                30L, TimeUnit.SECONDS, new LinkedBlockingQueue<>(), r -> {
                    Thread t = new Thread(r, threadName);
                    t.setDaemon(true);
                    return t;
                });
        pool.allowCoreThreadTimeOut(true);
        this.readers = pool;
    }

    /** The pool the loads run on, for a store's other background work. */
    ExecutorService pool() {
        return readers;
    }

    /**
     * Decodes {@code sidecars}: from the cache when the validator matches,
     * else read and decoded on the pool. Returns object id → entry; an id
     * missing from the answer could not be read or opened.
     */
    <H> Map<String, VaultEntry> load(String account, List<BackupLayout.Item<H>> sidecars,
                                     Validator<H> validator, Reader<H> reader,
                                     byte[] storageKey) throws IOException {
        Map<String, VaultEntry> out = new HashMap<>();
        List<BackupLayout.Item<H>> misses = new ArrayList<>();
        for (BackupLayout.Item<H> item : sidecars) {
            String id = BackupLayout.objectIdOf(item.name, BackupLayout.ENTRY_EXT);
            VaultEntry cached = get(account, id, validator.of(item));
            if (cached != null) {
                out.put(id, cached);
            } else {
                misses.add(item);
            }
        }
        if (misses.isEmpty()) {
            return out;
        }
        List<Future<VaultEntry>> futures = new ArrayList<>(misses.size());
        for (BackupLayout.Item<H> item : misses) {
            Callable<VaultEntry> read = () -> {
                if (item.size > BackupLayout.MAX_ENTRY_BYTES) {
                    return null;
                }
                byte[] blob = reader.read(item);
                return blob == null ? null : BackupLayout.decodeEntry(blob, storageKey);
            };
            futures.add(readers.submit(read));
        }
        IOException failure = null;
        for (int i = 0; i < futures.size(); i++) {
            BackupLayout.Item<H> item = misses.get(i);
            VaultEntry entry;
            try {
                entry = futures.get(i).get();
            } catch (ExecutionException e) {
                if (failure == null) {
                    failure = e.getCause() instanceof IOException
                            ? (IOException) e.getCause()
                            : new IOException("sidecar read failed", e.getCause());
                }
                continue;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                for (Future<VaultEntry> f : futures) {
                    f.cancel(true);
                }
                throw new IOException("interrupted while reading sidecars", e);
            }
            if (entry == null) {
                continue;
            }
            String id = BackupLayout.objectIdOf(item.name, BackupLayout.ENTRY_EXT);
            put(account, id, validator.of(item), entry);
            out.put(id, entry);
        }
        if (failure != null) {
            throw failure;
        }
        return out;
    }

    private static String key(String account, String id) {
        return account + '/' + id;
    }

    synchronized VaultEntry get(String account, String id, String validator) {
        Slot slot = slots.get(key(account, id));
        return slot != null && slot.validator.equals(validator) ? slot.entry : null;
    }

    synchronized void put(String account, String id, String validator, VaultEntry entry) {
        long weight = weightOf(entry);
        Slot previous = slots.put(key(account, id), new Slot(validator, entry, weight));
        if (previous != null) {
            used -= previous.weight;
        }
        used += weight;
        Iterator<Map.Entry<String, Slot>> it = slots.entrySet().iterator();
        while (used > budget && it.hasNext()) {
            Map.Entry<String, Slot> eldest = it.next();
            used -= eldest.getValue().weight;
            it.remove();
        }
    }

    synchronized void remove(String account, String id) {
        Slot slot = slots.remove(key(account, id));
        if (slot != null) {
            used -= slot.weight;
        }
    }

    synchronized void clearAccount(String account) {
        String prefix = account + '/';
        Iterator<Map.Entry<String, Slot>> it = slots.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<String, Slot> e = it.next();
            if (e.getKey().startsWith(prefix)) {
                used -= e.getValue().weight;
                it.remove();
            }
        }
    }

    private static long weightOf(VaultEntry e) {
        return 256L + 2L * (len(e.thumb) + len(e.name) + len(e.wrappedDek)
                + len(e.origin) + len(e.mime));
    }

    private static int len(String s) {
        return s == null ? 0 : s.length();
    }
}
