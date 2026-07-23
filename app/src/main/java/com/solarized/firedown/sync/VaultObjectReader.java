package com.solarized.firedown.sync;

import android.util.Base64;

import com.solarized.firedown.sync.crypto.SyncIdentity;
import com.solarized.firedown.sync.crypto.VaultCrypto;
import com.solarized.firedown.sync.model.VaultEntry;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.security.GeneralSecurityException;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Random-access, decrypt-on-read view over ONE backed-up vault object — the
 * enabler for STREAMING a cloud file without first downloading + decrypting the
 * whole thing to disk (which is what "restore" does).
 *
 * <p>It works because the vault's on-the-wire layout is chunked and every chunk
 * is INDEPENDENTLY sealed: the plaintext is split into fixed
 * {@link VaultEngine#CHUNK_SIZE} (8 MiB) blocks and each block is AES-256-GCM'd
 * under the per-file DEK with its own IV ({@link VaultCrypto#encryptChunk}). So a
 * plaintext byte offset maps to exactly one chunk ({@code offset / CHUNK_SIZE})
 * that can be fetched (a presigned R2 GET) and decrypted on its own — a
 * player/decoder seeking anywhere in the file only pays for the ~8 MiB chunk(s)
 * covering that range, not the whole object.</p>
 *
 * <p>The DEK is unwrapped once in the constructor from the manifest's wrapped
 * blob under the account's storage master key ({@link
 * SyncIdentity#storageMasterKey()}); the server never sees either. A tiny LRU of
 * decrypted chunks avoids re-fetching the same chunk on adjacent reads (a
 * demuxer reads a chunk in many small {@code readAt} calls). Presigned GET URLs
 * expire (~15 min); a fetch failure re-mints the object's URLs ONCE
 * ({@link StorageApiClient#getObject}) and retries, so a long playback session
 * outliving the TTL resumes transparently.</p>
 *
 * <p>Not thread-safe by field, so {@link #readAt} is {@code synchronized} — a
 * media DataSource reads from a single loading thread, but the sync keeps the
 * chunk cache + lazy {@code info} fetch consistent regardless.</p>
 */
public final class VaultObjectReader {

    private static final int B64 = Base64.URL_SAFE | Base64.NO_PADDING | Base64.NO_WRAP;
    /** Decrypted-chunk LRU size. Two covers a read straddling a chunk boundary
     *  plus a small seek-back without a re-fetch; larger would hold tens of MiB
     *  of plaintext in memory for no real gain (sequential playback advances). */
    private static final int CACHE_CHUNKS = 2;

    private final StorageApiClient api;
    private final SyncIdentity identity;
    private final String objectIdHex;
    private final long length;      // plaintext bytes (the file size)
    private final int chunkCount;
    private final byte[] dek;

    /** Presigned download URLs (one per chunk), lazily fetched then re-minted on
     *  expiry. Null until the first read. */
    private StorageApiClient.ObjectInfo info;

    /** Decrypted-chunk cache, access-ordered so the eldest entry is the LRU
     *  victim. Bounded to {@link #CACHE_CHUNKS}. */
    private final Map<Integer, byte[]> cache = new LinkedHashMap<Integer, byte[]>(4, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<Integer, byte[]> eldest) {
            return size() > CACHE_CHUNKS;
        }
    };

    private boolean closed;

    public VaultObjectReader(StorageApiClient api, SyncIdentity identity, VaultEntry entry)
            throws GeneralSecurityException {
        this.api = api;
        this.identity = identity;
        this.objectIdHex = entry.objectId;
        this.length = entry.size;
        this.chunkCount = Math.max(1, entry.chunkCount);
        this.dek = VaultCrypto.unwrapDek(Base64.decode(entry.wrappedDek, B64),
                identity.storageMasterKey());
    }

    /** Total plaintext length of the file. */
    public long length() {
        return length;
    }

    /**
     * Reads up to {@code len} plaintext bytes starting at {@code pos} into
     * {@code buf} at {@code off}. Returns the number of bytes read (≥ 1) or
     * {@code -1} at end of file. Reads never span a chunk decrypt more than
     * needed — a request straddling a chunk boundary returns only up to that
     * boundary (the caller loops), which is the standard {@code InputStream}/
     * {@code DataSource} contract.
     */
    public synchronized int readAt(long pos, byte[] buf, int off, int len) throws IOException {
        if (closed) {
            throw new IOException("reader closed");
        }
        if (pos >= length || len <= 0) {
            return -1;
        }
        int chunkIndex = (int) (pos / VaultEngine.CHUNK_SIZE);
        int within = (int) (pos % VaultEngine.CHUNK_SIZE);
        byte[] chunk = decryptedChunk(chunkIndex);
        if (within >= chunk.length) {
            // A short final chunk shorter than its nominal offset — nothing here.
            return -1;
        }
        int available = chunk.length - within;
        int n = Math.min(len, available);
        // Also clamp to the file's true end (defends against a last chunk that
        // decrypts longer than the declared size — never observed, cheap guard).
        long remainingInFile = length - pos;
        if (n > remainingInFile) {
            n = (int) remainingInFile;
        }
        System.arraycopy(chunk, within, buf, off, n);
        return n;
    }

    /**
     * Reads the WHOLE object into memory (for images, which media3 can't stream
     * frame-by-frame and which are small). Refuses an object larger than
     * {@code maxBytes} with an {@link IOException} so a mislabeled huge file
     * can't OOM the app.
     */
    public synchronized byte[] readAll(long maxBytes) throws IOException {
        if (length > maxBytes) {
            throw new IOException("object too large to load in memory: " + length);
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream((int) Math.min(length, Integer.MAX_VALUE));
        byte[] buf = new byte[64 * 1024];
        long pos = 0;
        while (pos < length) {
            int n = readAt(pos, buf, 0, buf.length);
            if (n < 0) {
                break;
            }
            out.write(buf, 0, n);
            pos += n;
        }
        return out.toByteArray();
    }

    /** Fetches + decrypts chunk {@code i}, serving from the LRU when warm. */
    private byte[] decryptedChunk(int i) throws IOException {
        byte[] cached = cache.get(i);
        if (cached != null) {
            return cached;
        }
        byte[] enc = fetchChunk(i);
        byte[] plain;
        try {
            plain = VaultCrypto.decryptChunk(enc, dek);
        } catch (GeneralSecurityException e) {
            throw new IOException("chunk " + i + " decrypt failed", e);
        }
        cache.put(i, plain);
        return plain;
    }

    /** Fetches encrypted chunk {@code i} from its presigned URL, re-minting the
     *  object's URLs ONCE if the fetch fails (presigned GETs expire ~15 min into
     *  a long session). */
    private byte[] fetchChunk(int i) throws IOException {
        StorageApiClient.ObjectInfo current = ensureInfo();
        String url = urlFor(current, i);
        try {
            return api.getChunk(url);
        } catch (IOException first) {
            // Re-mint fresh presigned URLs and retry once — the create-time URLs
            // may have expired mid-session. A second failure is real (offline,
            // deleted object) and propagates.
            info = null;
            StorageApiClient.ObjectInfo refreshed = ensureInfo();
            return api.getChunk(urlFor(refreshed, i));
        }
    }

    private StorageApiClient.ObjectInfo ensureInfo() throws IOException {
        if (info == null) {
            info = api.getObject(identity, objectIdHex);
        }
        return info;
    }

    private String urlFor(StorageApiClient.ObjectInfo info, int i) throws IOException {
        if (info.downloadUrls == null || i < 0 || i >= info.downloadUrls.size()) {
            throw new IOException("no download url for chunk " + i);
        }
        return info.downloadUrls.get(i);
    }

    /** Number of chunks (from the manifest entry). */
    public int chunkCount() {
        return chunkCount;
    }

    /** Wipes the unwrapped DEK and drops the plaintext cache. Idempotent. */
    public synchronized void close() {
        closed = true;
        Arrays.fill(dek, (byte) 0);
        cache.clear();
    }
}
