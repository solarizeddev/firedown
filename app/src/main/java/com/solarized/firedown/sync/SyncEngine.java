package com.solarized.firedown.sync;

import com.solarized.firedown.data.entity.WebBookmarkEntity;
import com.solarized.firedown.data.repository.WebBookmarkDataRepository;
import com.solarized.firedown.sync.crypto.BookmarkBlob;
import com.solarized.firedown.sync.crypto.SyncIdentity;
import com.solarized.firedown.sync.model.BookmarkDoc;
import com.solarized.firedown.sync.model.Merge;
import com.solarized.firedown.sync.model.SyncItem;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The sync engine: pull → decrypt → merge (per-URL LWW + tombstones) → apply in a
 * Room transaction → push, with 409 re-merge retry (docs/BOOKMARKS_SYNC.md §6).
 * The server stays dumb; all merge logic is client-side and pure (see
 * {@link Merge}). Runs entirely on a worker thread.
 */
public final class SyncEngine {

    /** Tombstones are kept this long so the deletion can reach other devices. */
    private static final long TOMBSTONE_TTL_MILLIS = 90L * 24 * 60 * 60 * 1000;
    private static final int MAX_CONFLICT_RETRIES = 5;

    private final WebBookmarkDataRepository repo;
    private final SyncApiClient api;
    private final SyncIdentity identity;
    private final byte[] fileKey;

    public SyncEngine(WebBookmarkDataRepository repo, SyncApiClient api, SyncIdentity identity) {
        this.repo = repo;
        this.api = api;
        this.identity = identity;
        this.fileKey = identity.fileKey();
    }

    /** Result of a sync pass. */
    public static final class Result {
        public final boolean ok;
        public final long version;
        public final String error; // null on success
        Result(boolean ok, long version, String error) {
            this.ok = ok;
            this.version = version;
            this.error = error;
        }
    }

    /**
     * Runs one full sync pass. Registers on demand if the account is unknown.
     * Returns the resulting server version on success.
     */
    public Result run() {
        try {
            return runOnce(true);
        } catch (SyncApiClient.TransientException te) {
            return new Result(false, 0, "transient:" + te.getMessage());
        } catch (Exception e) {
            return new Result(false, 0, e.getMessage());
        }
    }

    private Result runOnce(boolean allowRegister) throws Exception {
        long now = System.currentTimeMillis();

        // Local snapshot (incl. tombstones) -> SyncItems keyed by normalized URL.
        List<SyncItem> local = fromEntities(repo.getAllForSync());

        // Pull remote.
        SyncApiClient.Pull pull;
        try {
            pull = api.pull(identity);
        } catch (SyncApiClient.FatalException fe) {
            if (allowRegister && "unknown-account".equals(fe.slug)) {
                api.register(identity);
                return runOnce(false);
            }
            throw fe;
        }

        List<SyncItem> remote = new ArrayList<>();
        long remoteVersion = pull.version;
        if (!pull.notFound && pull.ciphertext != null && pull.ciphertext.length > 0) {
            String json = BookmarkBlob.decrypt(pull.ciphertext, fileKey);
            remote = BookmarkDoc.fromJson(json);
        }

        // Merge + GC, apply locally, push.
        return mergeApplyPush(local, remote, remoteVersion, now, allowRegister, 0);
    }

    private Result mergeApplyPush(List<SyncItem> local, List<SyncItem> remote, long remoteVersion,
                                  long now, boolean allowRegister, int attempt) throws Exception {
        List<SyncItem> merged = Merge.merge(local, remote);
        merged = Merge.gcTombstones(merged, now, TOMBSTONE_TTL_MILLIS);

        // Apply the merged set to local Room (one REPLACE batch) and GC local
        // tombstones past the TTL.
        repo.applyMerged(toEntities(merged));
        repo.gcTombstones(now - TOMBSTONE_TTL_MILLIS);

        // Skip the push when the server is already current (no local-only change).
        if (sameAsRemote(merged, remote)) {
            return new Result(true, remoteVersion, null);
        }

        byte[] blob = BookmarkBlob.encrypt(BookmarkDoc.toJson(merged, now), fileKey);
        SyncApiClient.Put put;
        try {
            put = api.push(identity, blob, remoteVersion);
        } catch (SyncApiClient.FatalException fe) {
            if (allowRegister && "unknown-account".equals(fe.slug)) {
                api.register(identity);
                return mergeApplyPush(merged, remote, remoteVersion, now, false, attempt);
            }
            throw fe;
        }

        if (put.ok) {
            return new Result(true, put.version, null);
        }

        // 409: someone wrote between pull and push — re-pull, re-merge, retry.
        if (attempt >= MAX_CONFLICT_RETRIES) {
            return new Result(false, put.serverVersion, "version-conflict (gave up)");
        }
        SyncApiClient.Pull again = api.pull(identity);
        List<SyncItem> freshRemote = new ArrayList<>();
        long freshVersion = again.version;
        if (!again.notFound && again.ciphertext != null && again.ciphertext.length > 0) {
            freshRemote = BookmarkDoc.fromJson(BookmarkBlob.decrypt(again.ciphertext, fileKey));
        }
        return mergeApplyPush(merged, freshRemote, freshVersion, System.currentTimeMillis(),
                allowRegister, attempt + 1);
    }

    // ---- entity <-> item mapping ----

    private static List<SyncItem> fromEntities(List<WebBookmarkEntity> rows) {
        List<SyncItem> out = new ArrayList<>(rows == null ? 0 : rows.size());
        if (rows == null) {
            return out;
        }
        for (WebBookmarkEntity e : rows) {
            String url = WebBookmarkDataRepository.canonicalUrl(e.getUrl());
            if (url == null || url.isEmpty()) {
                continue;
            }
            out.add(new SyncItem(url, e.getTitle(), e.getDate(), e.getIcon(), e.getPreview(),
                    e.getUpdatedAt(), e.isDeleted(), e.getDeletedAt()));
        }
        return out;
    }

    private static List<WebBookmarkEntity> toEntities(List<SyncItem> items) {
        List<WebBookmarkEntity> out = new ArrayList<>(items.size());
        for (SyncItem it : items) {
            WebBookmarkEntity e = new WebBookmarkEntity();
            e.setFileUrl(it.url);
            e.setId(WebBookmarkDataRepository.bookmarkIdFor(it.url));
            e.setFileTitle(it.title);
            e.setFileDate(it.date);
            e.setFileIcon(it.icon);
            e.setFilePreview(it.preview);
            e.setUpdatedAt(it.updatedAt);
            e.setDeleted(it.deleted);
            e.setDeletedAt(it.deletedAt);
            out.add(e);
        }
        return out;
    }

    /** Whether the merged set already equals the remote (so no push is needed). */
    private static boolean sameAsRemote(List<SyncItem> merged, List<SyncItem> remote) {
        Map<String, Long> a = signature(merged);
        Map<String, Long> b = signature(remote);
        return a.equals(b);
    }

    private static Map<String, Long> signature(List<SyncItem> items) {
        Map<String, Long> sig = new HashMap<>();
        for (SyncItem it : items) {
            // Encode (deleted, effectiveTs) into one comparable value.
            long v = (it.deleted ? 1L : 0L) ^ (it.effectiveTs() << 1);
            sig.put(it.url, v);
        }
        return sig;
    }
}
