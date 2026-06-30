package com.solarized.firedown.sync;

import android.util.Base64;

import com.solarized.firedown.sync.crypto.BookmarkBlob;
import com.solarized.firedown.sync.crypto.SyncIdentity;
import com.solarized.firedown.sync.crypto.VaultCrypto;
import com.solarized.firedown.sync.model.VaultEntry;
import com.solarized.firedown.sync.model.VaultManifest;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.security.GeneralSecurityException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;

/**
 * The storage-vault engine: encrypts a local download and uploads it as an object
 * (chunk → AES-256-GCM → presigned PUT → complete), records it in the encrypted
 * manifest (OCC), and restores by the reverse path. Runs on a worker thread.
 *
 * <p>All encryption is client-side ({@link VaultCrypto}); the manifest blob reuses
 * {@link BookmarkBlob}'s gzip+GCM framing under the storage master key. The
 * StorageApiClient brokers presigned R2 URLs — the bytes go phone&lt;-&gt;R2
 * directly, never through the server.</p>
 */
public final class VaultEngine {

    /** 8 MiB plaintext per chunk → resumable, independently encrypted. */
    private static final int CHUNK_SIZE = 8 * 1024 * 1024;
    /** Per-chunk ciphertext overhead: 5 magic + 1 ver + 12 IV + 16 GCM tag. */
    private static final int CHUNK_OVERHEAD = 34;
    // Headroom for OCC contention: manifest writers now run concurrently (a
    // user delete on the net pool can race a backup's commit on a worker thread),
    // so allow a few more re-pull/re-push rounds before giving up.
    private static final int MAX_CONFLICT_RETRIES = 8;
    private static final int B64 = Base64.URL_SAFE | Base64.NO_PADDING | Base64.NO_WRAP;

    /** Per-chunk upload progress (plaintext bytes), so the UI can show a
     *  determinate per-item bar like the Downloads list. */
    public interface ProgressListener {
        void onProgress(long bytesDone, long bytesTotal);
    }

    private final StorageApiClient api;
    private final SyncIdentity identity;
    private final byte[] storageKey;

    public VaultEngine(StorageApiClient api, SyncIdentity identity) {
        this.api = api;
        this.identity = identity;
        this.storageKey = identity.storageMasterKey();
    }

    /** Returns the current manifest (the list of backed-up files), or empty. */
    public List<VaultEntry> loadManifest() throws IOException, GeneralSecurityException {
        StorageApiClient.ManifestPull pull = api.pullManifest(identity);
        if (pull.notFound) {
            return new ArrayList<>();
        }
        return VaultManifest.fromJson(BookmarkBlob.decrypt(pull.ciphertext, storageKey));
    }

    /**
     * Encrypts {@code file} and uploads it as a new vault object, then records it
     * in the manifest. Registers the account on demand (idempotent). {@code thumb}
     * is a tiny base64 preview stored in the (encrypted) manifest, or null.
     *
     * <p><b>Dedup:</b> if this file is ALREADY backed up (same name + size), no new
     * object is created — the existing entry is returned. So tapping "Back up to
     * cloud" again on the same download is a no-op, not a duplicate that re-uploads
     * and re-consumes quota.
     */
    public VaultEntry backupFile(File file, String mime, String thumb)
            throws IOException, GeneralSecurityException {
        return backupFile(file, mime, thumb, null);
    }

    /** As {@link #backupFile(File, String, String)}, reporting per-chunk upload
     *  progress to {@code progress} (may be null). */
    public VaultEntry backupFile(File file, String mime, String thumb, ProgressListener progress)
            throws IOException, GeneralSecurityException {
        api.register(identity); // idempotent — makes the signed requests resolvable

        long size = file.length();
        VaultEntry existing = findExisting(file.getName(), size);
        if (existing != null) {
            // Already backed up — don't duplicate the object. But if it was backed
            // up before previews existed (no thumb) and we have one now, backfill it
            // into the manifest (cheap, no re-upload) so the list can show it.
            if (existing.thumb == null && thumb != null) {
                VaultEntry withThumb = new VaultEntry(existing.objectId, existing.wrappedDek,
                        existing.name, existing.size, existing.mime, existing.downloadedAt,
                        existing.chunkCount, thumb);
                addToManifest(withThumb); // replaces (removeById + add) — same objectId
                return withThumb;
            }
            return existing;
        }
        int chunkCount = (int) ((size + CHUNK_SIZE - 1) / CHUNK_SIZE);
        if (chunkCount == 0) {
            chunkCount = 1; // an empty file still uploads one (empty) chunk
        }
        // Declare the CIPHERTEXT size for quota (plaintext + per-chunk overhead);
        // the server reconciles to the real R2 size at complete regardless.
        long declared = size + (long) chunkCount * CHUNK_OVERHEAD;

        byte[] dek = VaultCrypto.generateDek();
        try {
            StorageApiClient.CreatedObject created = api.createObject(identity, declared, chunkCount);
            if (created.uploadUrls.size() != chunkCount) {
                throw new IOException("server returned " + created.uploadUrls.size()
                        + " upload urls for " + chunkCount + " chunks");
            }

            try (InputStream in = new FileInputStream(file)) {
                byte[] buf = new byte[CHUNK_SIZE];
                long uploaded = 0;
                if (progress != null) {
                    progress.onProgress(0, size); // 0% up front (determinate)
                }
                for (int i = 0; i < chunkCount; i++) {
                    int n = readFully(in, buf);
                    byte[] plain = (n == buf.length) ? buf : Arrays.copyOf(buf, n);
                    byte[] enc = VaultCrypto.encryptChunk(plain, dek);
                    api.putChunk(created.uploadUrls.get(i), enc);
                    uploaded += n;
                    if (progress != null) {
                        progress.onProgress(uploaded, size);
                    }
                }
            }
            api.completeObject(identity, created.objectId);

            String wrappedDek = Base64.encodeToString(VaultCrypto.wrapDek(dek, storageKey), B64);
            VaultEntry entry = new VaultEntry(created.objectId, wrappedDek, file.getName(),
                    size, mime, System.currentTimeMillis(), chunkCount, thumb);
            // Dedup-checked commit (closes the concurrency window the start-time
            // findExisting can't: two backups of the same file CONTENT at different
            // paths have different unique-work keys, so they run concurrently and
            // both pass the start-time check before either commits). The OCC mutate
            // re-pulls the latest manifest; if another backup of the same name+size
            // already committed, we keep THAT entry and drop our just-uploaded
            // object as an orphan, so the manifest never gains a duplicate.
            VaultEntry committed = commitDeduped(entry);
            if (!committed.objectId.equals(entry.objectId)) {
                try {
                    api.deleteObject(identity, created.objectId); // free the orphan
                } catch (Exception ignored) {
                    // best-effort — the object is unreferenced regardless
                }
            }
            return committed;
        } finally {
            Arrays.fill(dek, (byte) 0);
        }
    }

    /** The existing manifest entry for a file (matched by name + size), or null. */
    private VaultEntry findExisting(String name, long size) throws IOException, GeneralSecurityException {
        for (VaultEntry e : loadManifest()) {
            if (size == e.size && name != null && name.equals(e.name)) {
                return e;
            }
        }
        return null;
    }

    /**
     * Restores a vault entry to {@code dest}: fetches each chunk from R2,
     * decrypts, and reassembles. The file's per-file DEK is unwrapped from the
     * manifest entry under the storage master key.
     */
    public void restoreFile(VaultEntry entry, File dest) throws IOException, GeneralSecurityException {
        byte[] dek = VaultCrypto.unwrapDek(Base64.decode(entry.wrappedDek, B64), storageKey);
        try {
            StorageApiClient.ObjectInfo info = api.getObject(identity, entry.objectId);
            try (OutputStream out = new FileOutputStream(dest)) {
                for (String url : info.downloadUrls) {
                    byte[] plain = VaultCrypto.decryptChunk(api.getChunk(url), dek);
                    out.write(plain);
                }
            }
        } finally {
            Arrays.fill(dek, (byte) 0);
        }
    }

    /** Drops a vault entry from the manifest, then frees its object. */
    public void deleteEntry(VaultEntry entry) throws IOException, GeneralSecurityException {
        // Unreference FIRST (manifest), then free the object. If the object delete
        // fails we leak quota (harmless, server GC); the reverse order risks a
        // GHOST entry that points at a deleted object and can't be restored.
        mutateManifest(entries -> removeById(entries, entry.objectId));
        try {
            api.deleteObject(identity, entry.objectId); // 204/404 both succeed
        } catch (Exception ignored) {
            // best-effort — the entry is already gone from the manifest
        }
    }

    /**
     * Batch delete: removes ALL given entries from the manifest in ONE OCC mutation
     * (so N deletes don't fire N concurrent manifest mutations that contend on the
     * version), then frees each object best-effort. Same unreference-first ordering
     * as {@link #deleteEntry}.
     */
    public void deleteEntries(List<VaultEntry> toDelete) throws IOException, GeneralSecurityException {
        if (toDelete == null || toDelete.isEmpty()) {
            return;
        }
        mutateManifest(entries -> {
            for (VaultEntry e : toDelete) {
                removeById(entries, e.objectId);
            }
        });
        for (VaultEntry e : toDelete) {
            try {
                api.deleteObject(identity, e.objectId);
            } catch (Exception ignored) {
                // best-effort — already unreferenced
            }
        }
    }

    // ---- manifest mutation under OCC ----

    private interface ManifestMutation {
        void apply(List<VaultEntry> entries);
    }

    private void addToManifest(VaultEntry entry) throws IOException, GeneralSecurityException {
        mutateManifest(entries -> {
            removeById(entries, entry.objectId); // idempotent re-add
            entries.add(entry);
        });
    }

    /**
     * Adds {@code entry} to the manifest UNLESS a different object with the same
     * file name + size already exists (a concurrent backup of the same content
     * that committed first) — in which case that existing entry is kept and
     * returned, and {@code entry} is NOT added. Returns whichever entry is now in
     * the manifest (so the caller can tell whether its object became an orphan).
     */
    private VaultEntry commitDeduped(VaultEntry entry) throws IOException, GeneralSecurityException {
        VaultEntry[] result = new VaultEntry[1];
        mutateManifest(entries -> {
            for (VaultEntry e : entries) {
                if (e.size == entry.size && entry.name != null && entry.name.equals(e.name)
                        && !e.objectId.equals(entry.objectId)) {
                    result[0] = e; // someone else won the race — keep theirs
                    return;
                }
            }
            removeById(entries, entry.objectId); // idempotent re-add
            entries.add(entry);
            result[0] = entry;
        });
        return result[0];
    }

    /**
     * Re-pull → apply → push the manifest with optimistic concurrency, retrying on
     * a 409 conflict (another device pushed in between). Identical mechanics to the
     * bookmark doc PUT.
     */
    private void mutateManifest(ManifestMutation mutation) throws IOException, GeneralSecurityException {
        for (int attempt = 0; attempt < MAX_CONFLICT_RETRIES; attempt++) {
            StorageApiClient.ManifestPull pull = api.pullManifest(identity);
            List<VaultEntry> entries = pull.notFound
                    ? new ArrayList<>()
                    : VaultManifest.fromJson(BookmarkBlob.decrypt(pull.ciphertext, storageKey));
            mutation.apply(entries);
            byte[] blob = BookmarkBlob.encrypt(
                    VaultManifest.toJson(entries, System.currentTimeMillis()), storageKey);
            StorageApiClient.ManifestPut put = api.pushManifest(identity, blob, pull.notFound ? 0 : pull.version);
            if (put.ok) {
                return;
            }
            // 409 version-conflict → loop and re-pull/re-apply on the new version.
        }
        throw new IOException("vault manifest push conflict after " + MAX_CONFLICT_RETRIES + " retries");
    }

    private static void removeById(List<VaultEntry> entries, String objectId) {
        for (Iterator<VaultEntry> it = entries.iterator(); it.hasNext(); ) {
            if (objectId.equals(it.next().objectId)) {
                it.remove();
            }
        }
    }

    /** Reads up to buf.length bytes, tolerating short reads; returns the count. */
    private static int readFully(InputStream in, byte[] buf) throws IOException {
        int total = 0;
        while (total < buf.length) {
            int n = in.read(buf, total, buf.length - total);
            if (n < 0) {
                break;
            }
            total += n;
        }
        return total;
    }
}
