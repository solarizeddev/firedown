package com.solarized.firedown.sync;

import android.text.TextUtils;
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

    /** 8 MiB plaintext per chunk → resumable, independently encrypted. Public so
     *  the streaming reader ({@code VaultObjectReader}) can map a plaintext file
     *  offset to its chunk index without re-deriving this constant. */
    public static final int CHUNK_SIZE = 8 * 1024 * 1024;
    /** Per-chunk ciphertext overhead: 5 magic + 1 ver + 12 IV + 16 GCM tag. */
    private static final int CHUNK_OVERHEAD = 34;
    // Headroom for OCC contention: manifest writers now run concurrently (a
    // user delete on the net pool can race a backup's commit on a worker thread),
    // so allow a few more re-pull/re-push rounds before giving up.
    private static final int MAX_CONFLICT_RETRIES = 8;
    // Upper bound on presigned-URL re-mints during ONE upload. A refresh covers
    // UploadPresignTTL of further chunks, so a real upload needs at most
    // (upload_time / TTL) refreshes — a handful even for a multi-GB file on a slow
    // link. This is generous headroom, not a per-chunk cost; tripping it means
    // something is wrong (a chunk 403s even with a fresh URL) → surface it so the
    // worker retries (and its run-attempt ceiling ultimately gives up cleanly).
    private static final int MAX_URL_REFRESHES = 200;
    // Consecutive 403s on the SAME chunk before giving up as "presign REJECTED,
    // not expired". A legitimately expired URL is cured by ONE refresh (a fresh
    // mint is valid for a full UploadPresignTTL and the retry PUTs immediately);
    // a second consecutive expiry can only be a link so slow that ONE chunk's
    // PUT outlives the TTL — allowed once. A THIRD 403 on a seconds-old URL is
    // structurally impossible as expiry: R2 is rejecting the SIGNATURE (server
    // clock skew / rolled R2 credentials / bucket-endpoint change), which no
    // amount of refreshing fixes — the old loop burned all 200 refreshes
    // (~400 round-trips) on one chunk and then still reported the misleading
    // "presign expired". Counting a detectable failure per the timer-vs-count
    // rule; the counter resets on any successful PUT.
    private static final int MAX_SAME_CHUNK_EXPIRIES = 2;
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
        return backupFile(file, mime, thumb, null, null, 0L);
    }

    /** As {@link #backupFile(File, String, String)}, reporting per-chunk upload
     *  progress to {@code progress} (may be null) and recording, in the manifest so
     *  a restored file's row matches the original: {@code origin} (the download's
     *  origin URL, may be null) for its {@code MIME · domain}, and
     *  {@code downloadedAt} (the download's own date, 0 = unknown) for its date. */
    public VaultEntry backupFile(File file, String mime, String thumb,
                                 ProgressListener progress, String origin, long downloadedAt)
            throws IOException, GeneralSecurityException {
        return backupStream(file.getName(), file.length(),
                () -> new FileInputStream(file), mime, thumb, progress, origin, downloadedAt);
    }

    /**
     * Opens the plaintext of the file being backed up. A plain FileInputStream for
     * an owned file (the {@link #backupFile(File, String, String, ProgressListener, String)}
     * delegate); the worker passes a SAF-grant stream for a RESTORED foreign-owned
     * file — on Android 11+ a reinstalled app doesn't own its old public files, so
     * a direct File open EACCES-es (see {@code RestoredFileAccess}). Opened exactly
     * once per attempt; the upload reads it sequentially (chunk retries re-PUT the
     * in-memory ciphertext, never re-read the source).
     */
    public interface StreamSource {
        InputStream open() throws IOException;
    }

    /** The access-agnostic backup core: {@code name}/{@code size} identify the
     *  content (the dedup key), {@code source} supplies the plaintext bytes. */
    public VaultEntry backupStream(String name, long size, StreamSource source,
                                   String mime, String thumb, ProgressListener progress,
                                   String origin, long downloadedAt)
            throws IOException, GeneralSecurityException {
        // NOTE: the account must already be registered (the worker calls
        // CloudBackupManager.ensureRegistered first). Registration is NOT done here
        // per-backup — that bursts Cloudflare's rate-limited register endpoints.
        VaultEntry existing = findExisting(name, size);
        if (existing != null) {
            // Already backed up — don't duplicate the object. But an entry written
            // by an older build can be MISSING fields this one knows: the preview
            // (added when thumbnails shipped) and the origin (added so a restored
            // row shows its real domain instead of "cloud://firedown"). Backfill
            // whatever is absent straight into the manifest — cheap, no re-upload.
            // Only the thumb used to be repaired here, which is why re-backing up
            // a legacy file never fixed its origin.
            String mergedThumb = existing.thumb != null ? existing.thumb : thumb;
            String mergedOrigin = !TextUtils.isEmpty(existing.origin) ? existing.origin : origin;
            boolean gainedThumb = existing.thumb == null && mergedThumb != null;
            boolean gainedOrigin = TextUtils.isEmpty(existing.origin)
                    && !TextUtils.isEmpty(mergedOrigin);
            if (gainedThumb || gainedOrigin) {
                VaultEntry repaired = new VaultEntry(existing.objectId, existing.wrappedDek,
                        existing.name, existing.size, existing.mime, existing.downloadedAt,
                        existing.chunkCount, mergedThumb, mergedOrigin);
                addToManifest(repaired); // replaces (removeById + add) — same objectId
                return repaired;
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
        // The ciphertext length of every chunk but the last. The server signs this
        // into each presigned PUT's Content-Length, so it MUST equal what
        // encryptChunk actually produces for a full plaintext chunk — a mismatch
        // fails the signature on every upload. The last chunk's length is derived
        // server-side as declared - (chunkCount-1)*ciphertextChunk, which is
        // exactly (size - (chunkCount-1)*CHUNK_SIZE) + CHUNK_OVERHEAD.
        long ciphertextChunk = (long) CHUNK_SIZE + CHUNK_OVERHEAD;

        byte[] dek = VaultCrypto.generateDek();
        try {
            StorageApiClient.CreatedObject created =
                    api.createObject(identity, declared, chunkCount, ciphertextChunk);
            if (created.uploadUrls.size() != chunkCount) {
                throw new IOException("server returned " + created.uploadUrls.size()
                        + " upload urls for " + chunkCount + " chunks");
            }

            // Mutable copy so an expired-URL refresh can swap in fresh URLs mid-upload
            // (a large file on a slow link outlives the create-time UploadPresignTTL).
            List<String> uploadUrls = new ArrayList<>(created.uploadUrls);
            int refreshes = 0;
            try (InputStream in = source.open()) {
                byte[] buf = new byte[CHUNK_SIZE];
                long uploaded = 0;
                if (progress != null) {
                    progress.onProgress(0, size); // 0% up front (determinate)
                }
                for (int i = 0; i < chunkCount; i++) {
                    int n = readFully(in, buf);
                    byte[] plain = (n == buf.length) ? buf : Arrays.copyOf(buf, n);
                    byte[] enc = VaultCrypto.encryptChunk(plain, dek);
                    int sameChunkExpiries = 0;
                    while (true) {
                        try {
                            api.putChunk(uploadUrls.get(i), enc);
                            break;
                        } catch (StorageApiClient.PresignExpiredException expired) {
                            // The presigned URLs expired mid-upload. Re-mint fresh ones
                            // for the (still-pending) object and retry THIS chunk, rather
                            // than fail the whole upload and re-run from chunk 0. Bounded:
                            // a fresh batch covers UploadPresignTTL of further chunks, so
                            // refreshes scale with (upload time / TTL), not chunk count —
                            // MAX_URL_REFRESHES is generous headroom, not a per-chunk cost.
                            if (++sameChunkExpiries > MAX_SAME_CHUNK_EXPIRIES) {
                                // A just-minted URL 403'd again: the presign is being
                                // REJECTED, not expiring — a server-side signing problem
                                // (VPS clock skew, rolled R2 credentials, bucket/endpoint
                                // change). Refreshing can't fix it; name the real cause
                                // instead of looping 200 refreshes into "presign expired".
                                // Include the underlying R2 detail (its <Code> or a
                                // body snippet, from putChunk's message) IN the thrown
                                // text so it reaches the smoke-test result / snackbar,
                                // not only a device logcat — SignatureDoesNotMatch vs an
                                // access/challenge 403 is the whole diagnosis.
                                throw new IOException("chunk PUT 403 on freshly minted URLs"
                                        + " — presign rejected by storage, not expired ["
                                        + expired.getMessage() + "]"
                                        + " (server clock / R2 credentials / bucket;"
                                        + " run storage-api --r2-check)", expired);
                            }
                            if (refreshes++ >= MAX_URL_REFRESHES) {
                                throw expired; // give up refreshing → worker retries
                            }
                            List<String> fresh = api.refreshUploadUrls(identity, created.objectId);
                            if (fresh.size() != chunkCount) {
                                throw new IOException("refresh returned " + fresh.size()
                                        + " upload urls for " + chunkCount + " chunks");
                            }
                            uploadUrls = fresh;
                        }
                    }
                    uploaded += n;
                    if (progress != null) {
                        progress.onProgress(uploaded, size);
                    }
                }
            }
            api.completeObject(identity, created.objectId);

            String wrappedDek = Base64.encodeToString(VaultCrypto.wrapDek(dek, storageKey), B64);
            // downloadedAt is the DOWNLOAD's own date, not "now" — the field name
            // means what it says, and a restored row must land in the same date
            // section the original sat in. Stamping the BACKUP time here made a
            // restored file jump to "Last 7 days" (and, with the date suppressed
            // in bounded buckets, show no date at all). 0 = caller didn't know it.
            long stamp = downloadedAt > 0 ? downloadedAt : System.currentTimeMillis();
            VaultEntry entry = new VaultEntry(created.objectId, wrappedDek, name,
                    size, mime, stamp, chunkCount, thumb, origin);
            // Dedup-checked commit (closes the concurrency window the start-time
            // findExisting can't: TWO DEVICES backing up the same file content race —
            // each pulls a manifest without the other's entry and both pass their
            // start-time check before either commits; on-device the enqueueUniqueWork
            // KEEP key is name+size, so same-device duplicates are already collapsed).
            // The OCC mutate re-pulls the latest manifest; if another backup of the
            // same name+size already committed, we keep THAT entry and drop our
            // just-uploaded object as an orphan, so the manifest never gains a dup.
            VaultEntry committed;
            try {
                committed = commitDeduped(entry);
            } catch (ManifestConflictException ce) {
                // The manifest push was cleanly rejected on every OCC attempt, so the
                // entry DEFINITELY never committed — but completeObject above DID, so
                // our object is now an unreferenced orphan (committed server-side,
                // absent from the manifest, billed/quota-counted, invisible to the UI,
                // and reaped by nothing). Free it before rethrowing, so the
                // WorkManager retry re-uploads cleanly instead of leaking one orphan
                // per attempt. A GENERIC IOException from the push is NOT caught here:
                // it's ambiguous (the push may have committed with the response lost),
                // and blind-deleting then would ghost a referenced object.
                try {
                    api.deleteObject(identity, created.objectId);
                } catch (Exception ignored) {
                    // best-effort — retrying the backup is what actually recovers
                }
                throw ce;
            }
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
        restoreFile(entry, dest, null);
    }

    /**
     * As {@link #restoreFile(VaultEntry, File)}, reporting per-chunk progress so a
     * restore can drive a live download row (the reverse of {@link #backupFile}'s
     * upload progress). {@code bytesTotal} is the entry's plaintext size, so the
     * summed decrypted chunk lengths reach it exactly; {@code progress} may be
     * null (the no-progress overload).
     */
    public void restoreFile(VaultEntry entry, File dest, ProgressListener progress)
            throws IOException, GeneralSecurityException {
        byte[] dek = VaultCrypto.unwrapDek(Base64.decode(entry.wrappedDek, B64), storageKey);
        try {
            StorageApiClient.ObjectInfo info = api.getObject(identity, entry.objectId);
            long done = 0;
            try (OutputStream out = new FileOutputStream(dest)) {
                for (String url : info.downloadUrls) {
                    byte[] plain = VaultCrypto.decryptChunk(api.getChunk(url), dek);
                    out.write(plain);
                    done += plain.length;
                    if (progress != null) {
                        progress.onProgress(done, entry.size);
                    }
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
            // 409 version-conflict → back off with jitter, then re-pull/re-apply on
            // the new version. Without the backoff, several backup workers finishing
            // together (each commits its own entry) re-pull in lockstep and keep
            // colliding — a thundering herd that can exhaust the retries. Exponential
            // (~50·2^n ms) + up to 50 ms random jitter de-syncs them.
            sleepBackoff(attempt);
        }
        // Exhausted: every attempt was cleanly REJECTED (409 version-conflict), so
        // the push DEFINITELY never committed. Throw the typed conflict exception
        // (still an IOException → the worker's IOException→retry branch retries the
        // whole backup) so backupFile can safely free a just-completed orphan object
        // before the retry — a generic network IOException is ambiguous (the push
        // may have committed with the response lost) and must NOT trigger that.
        throw new ManifestConflictException(
                "vault manifest push conflict after " + MAX_CONFLICT_RETRIES + " retries");
    }

    /**
     * Thrown when {@link #mutateManifest} exhausts its OCC retries — every push was
     * cleanly rejected with a 409 version-conflict, so the mutation DEFINITELY never
     * committed server-side. Distinct from a generic {@link IOException} (a network
     * drop mid-push, which is AMBIGUOUS — the push may have landed with the response
     * lost). Callers that just completed a new object use this distinction: on a
     * definitive conflict they free the now-unreferenced object; on an ambiguous
     * error they leave it (blind-deleting a maybe-committed object would ghost the
     * manifest). Still an {@link IOException} so the worker retries either way.
     */
    public static final class ManifestConflictException extends IOException {
        ManifestConflictException(String message) {
            super(message);
        }
    }

    /** Exponential backoff with jitter between OCC attempts. Interrupt-aware:
     *  restores the interrupt flag and surfaces it as an IOException so a
     *  cancelled worker unwinds promptly. */
    private static void sleepBackoff(int attempt) throws IOException {
        long base = 50L << Math.min(attempt, 5);      // 50,100,…,1600 ms, capped
        long jitter = (long) (Math.random() * 50);    // de-sync concurrent writers
        try {
            Thread.sleep(base + jitter);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted during manifest backoff", e);
        }
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
