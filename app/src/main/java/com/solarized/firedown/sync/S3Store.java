package com.solarized.firedown.sync;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.solarized.firedown.sync.model.VaultEntry;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.security.GeneralSecurityException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;

/**
 * Backups in an S3-compatible bucket the user brought keys for (Backblaze B2,
 * Cloudflare R2, Wasabi, Hetzner, Scaleway, IDrive e2, Storj, MinIO…): the
 * same {@link BackupLayout} as a picked folder, one object and one sidecar per
 * backup under {@code <prefix>Firedown Backup/<account>/objects/}. Encrypted on
 * the phone like every backup; the bucket holds ciphertext under random names.
 *
 * <p><b>Uploads: one part in memory at a time, every part signed.</b> An
 * object up to {@link #partSize} (one FDVC chunk, 8 MiB + 34 bytes, for any
 * file under ~78 GiB) is ONE PUT; a larger one is a multipart upload whose
 * parts are whole FDVC chunks, so every part but the last has the same
 * length (R2 requires it) and is at least 5 MiB (everyone requires it). The
 * part is buffered (never the whole object, never on disk), which is what
 * lets it be signed with its real SHA-256 and resent after a dropped
 * connection or a 503 without restarting the file. A stream closed short (a
 * cancel, a file that shrank) or a failure aborts the multipart upload, so no
 * billed parts are left behind by a running process. One a process death
 * leaves behind is aborted by the next listing's sweep once it is a day old
 * ({@link #STALE_UPLOAD_MS}); a lifecycle rule for incomplete uploads on the
 * bucket is the belt (R2 has one by default, seven days).
 *
 * <p><b>Listings</b> are {@code ListObjectsV2} pages (sizes and dates come
 * from the listing, no HEAD per object), then one GET per sidecar not seen at
 * that ETag ({@link SidecarCache}). <b>Reads</b> are one GET (a restore) or
 * one ranged GET per 8 MiB chunk (the stream player).
 *
 * <p>Failure classes are {@link S3Client}'s: anything that needs the user is
 * an {@link BackupStore.UnreachableException}, a full account
 * {@link BackupStore.FullException}, the rest a plain {@link IOException}.
 * The backup workers carry a network constraint while this store is active.
 */
public final class S3Store implements BackupStore {

    /** S3's cap on parts per upload. */
    static final int MAX_PARTS = 10_000;
    /** One FDVC chunk as stored. */
    static final long STRIDE = VaultEngine.CHUNK_SIZE + VaultEngine.CHUNK_OVERHEAD;
    /** An unfinished upload older than this was left by a process that died. */
    static final long STALE_UPLOAD_MS = TimeUnit.HOURS.toMillis(24);
    /** The stale-upload sweep runs at most this often per store. */
    static final long SWEEP_INTERVAL_MS = TimeUnit.HOURS.toMillis(24);
    /** A listing longer than this many pages (a million keys) is refused
     *  rather than read short. */
    private static final int MAX_LIST_PAGES = 1000;
    private static final int PAGE_KEYS = 1000;

    private final S3Client s3;
    private final S3Config config;
    private final SidecarCache cache = new SidecarCache(SidecarCache.CACHE_BYTES, "s3-read");
    /** Upload ids this store has open: the sweep never aborts one of these. */
    private final Set<String> openUploads = Collections.synchronizedSet(new HashSet<>());
    private volatile long lastSweep;

    public S3Store(@NonNull S3Config config, @Nullable OkHttpClient baseClient) {
        this.config = config;
        this.s3 = new S3Client(baseClient, config);
    }

    /** The wire (for the harness: its sleeper). */
    S3Client client() {
        return s3;
    }

    // ---- where ----

    @Override
    public boolean isConfigured() {
        return true;
    }

    /** One {@code ListObjectsV2} of a single key under the prefix. Off the
     *  main thread only. */
    @Override
    public boolean isReachable() {
        try {
            s3.listObjects(rootPrefix(), null, 1, "check the bucket");
            return true;
        } catch (IOException | RuntimeException e) {
            return false;
        }
    }

    @NonNull
    @Override
    public String providerLabel() {
        return providerOf(config.endpoint);
    }

    @NonNull
    @Override
    public String locationLabel() {
        return locationOf(config.bucket, config.prefix);
    }

    /**
     * Who holds a bucket at {@code endpoint}, in the user's words: the
     * provider's name for the known hosts ("Backblaze B2"), else the host
     * with its port when it is not the scheme's own. Cheap; "" when the
     * endpoint does not parse.
     */
    @NonNull
    public static String providerOf(@Nullable String endpoint) {
        HttpUrl url = endpoint == null ? null : HttpUrl.parse(endpoint);
        if (url == null) {
            return "";
        }
        String host = url.host().toLowerCase(Locale.ROOT);
        String[][] known = {
                {"backblazeb2.com", "Backblaze B2"},
                {"r2.cloudflarestorage.com", "Cloudflare R2"},
                {"wasabisys.com", "Wasabi"},
                {"amazonaws.com", "Amazon S3"},
                {"your-objectstorage.com", "Hetzner"},
                {"scw.cloud", "Scaleway"},
                {"idrivee2.com", "IDrive e2"},
                {"storjshare.io", "Storj"},
                {"digitaloceanspaces.com", "DigitalOcean Spaces"},
        };
        for (String[] k : known) {
            if (host.equals(k[0]) || host.endsWith("." + k[0])) {
                return k[1];
            }
        }
        if (host.contains(".idrivee2-")) {
            return "IDrive e2";
        }
        String shown = url.host().indexOf(':') >= 0 ? "[" + url.host() + "]" : url.host();
        int defaultPort = HttpUrl.defaultPort(url.scheme());
        return url.port() == defaultPort ? shown : shown + ":" + url.port();
    }

    /** {@code bucket} or {@code bucket/prefix}, for the Backup screen. */
    @NonNull
    public static String locationOf(@Nullable String bucket, @Nullable String prefix) {
        String b = bucket == null ? "" : bucket;
        String p = prefix == null ? "" : prefix;
        while (p.endsWith("/")) {
            p = p.substring(0, p.length() - 1);
        }
        return p.isEmpty() ? b : b + "/" + p;
    }

    // ---- layout ----

    private String rootPrefix() {
        return config.prefix + BackupLayout.ROOT_DIR + "/";
    }

    private String accountPrefix(String account) {
        return rootPrefix() + account + "/";
    }

    private String objectsPrefix(String account) {
        return accountPrefix(account) + BackupLayout.OBJECTS_DIR + "/";
    }

    private String key(String account, String name) {
        return objectsPrefix(account) + name;
    }

    /** Every key under {@code prefix}, page by page; never short. */
    private List<S3Client.ObjectInfo> listAll(String prefix, String what) throws IOException {
        List<S3Client.ObjectInfo> out = new ArrayList<>();
        String token = null;
        int pages = 0;
        do {
            if (++pages > MAX_LIST_PAGES) {
                throw new IOException(what + ": more than " + MAX_LIST_PAGES + " pages");
            }
            S3Client.ListPage page = s3.listObjects(prefix, token, PAGE_KEYS, what);
            out.addAll(page.objects);
            token = page.next;
        } while (token != null);
        return out;
    }

    private BackupLayout.Listing<S3Client.ObjectInfo> list(String account) throws IOException {
        String prefix = objectsPrefix(account);
        BackupLayout.Listing<S3Client.ObjectInfo> listing = new BackupLayout.Listing<>();
        for (S3Client.ObjectInfo o : listAll(prefix, "list backups")) {
            String name = o.key.substring(prefix.length());
            if (name.isEmpty() || name.indexOf('/') >= 0) {
                continue;
            }
            listing.add(new BackupLayout.Item<>(o, name, o.size, o.modified));
        }
        return listing;
    }

    /** Now by the server's clock (the phone's plus the learned skew), so
     *  ages compare with the dates the server wrote. */
    private long serverNow() {
        return s3.serverNow();
    }

    // ---- entries ----

    @Override
    public List<VaultEntry> listEntries(String account, byte[] storageKey) throws IOException {
        List<VaultEntry> entries = BackupLayout.collectEntries(list(account),
                sidecars -> cache.load(account, sidecars, S3Store::validator,
                        item -> s3.getObjectBytes(item.handle.key, BackupLayout.MAX_ENTRY_BYTES,
                                "read a sidecar"),
                        storageKey),
                object -> deleteQuietly(object.handle.key),
                serverNow());
        maybeSweepUploads(account);
        return entries;
    }

    /** What says a sidecar is unchanged: the ETag, else size and date. */
    private static String validator(BackupLayout.Item<S3Client.ObjectInfo> item) {
        S3Client.ObjectInfo o = item.handle;
        return o.etag != null ? "e:" + o.etag : "s:" + o.size + ":" + o.modified;
    }

    /**
     * Aborts this account's unfinished multipart uploads older than
     * {@link #STALE_UPLOAD_MS}, at most once per {@link #SWEEP_INTERVAL_MS},
     * in the background and best-effort (a provider without the call just
     * keeps its parts until a lifecycle rule removes them). An upload this
     * store has open is never touched.
     */
    private void maybeSweepUploads(String account) {
        long now = System.currentTimeMillis();
        if (now - lastSweep < SWEEP_INTERVAL_MS) {
            return;
        }
        lastSweep = now;
        String prefix = objectsPrefix(account);
        cache.pool().execute(() -> sweepUploads(prefix));
    }

    /** The sweep itself; returns how many uploads it aborted. */
    int sweepUploads(String prefix) {
        int aborted = 0;
        try {
            long now = serverNow();
            for (S3Client.Upload u : s3.listUploads(prefix, MAX_PARTS, "list unfinished uploads")) {
                if (openUploads.contains(u.uploadId)) {
                    continue;
                }
                if (u.initiated > 0 && now - u.initiated > STALE_UPLOAD_MS) {
                    s3.abortMultipart(u.key, u.uploadId, "abort a stale upload");
                    aborted++;
                }
            }
        } catch (IOException | RuntimeException ignored) {
            // best-effort: a lifecycle rule on the bucket is the backstop
        }
        return aborted;
    }

    @Override
    public void writeEntry(String account, VaultEntry entry, byte[] storageKey)
            throws IOException, GeneralSecurityException {
        byte[] blob = BackupLayout.encodeEntry(entry, storageKey);
        cache.remove(account, entry.objectId);
        s3.putObject(key(account, entry.objectId + BackupLayout.ENTRY_EXT), blob, 0, blob.length,
                "write a sidecar");
    }

    // ---- objects ----

    /**
     * The part size for an object of {@code length} bytes: as many whole FDVC
     * chunks as keep the part count within {@link #MAX_PARTS}, one for any
     * file under ~78 GiB. An object up to this size is a single PUT.
     */
    static long partSize(long length) {
        long chunks = Math.max(1, (length + STRIDE - 1) / STRIDE);
        long chunksPerPart = Math.max(1, (chunks + MAX_PARTS - 1) / MAX_PARTS);
        return chunksPerPart * STRIDE;
    }

    @Override
    public OutputStream openNewObject(String account, String objectId, long length)
            throws IOException {
        if (length < 0) {
            throw new IOException("negative length");
        }
        return new Upload(key(account, objectId + BackupLayout.OBJECT_EXT), length);
    }

    @Override
    public InputStream openObject(String account, String objectId) throws IOException {
        return s3.openObject(key(account, objectId + BackupLayout.OBJECT_EXT), "read the backup");
    }

    /** Checks the object is there (HEAD), then reads it range by range. */
    @Override
    public RangeReader openObjectRanges(String account, String objectId) throws IOException {
        String key = key(account, objectId + BackupLayout.OBJECT_EXT);
        if (!s3.headObject(key, "open the backup")) {
            throw new FileNotFoundException("object " + objectId + " is not in the bucket");
        }
        return new RangeReader() {
            @Override
            public byte[] read(long offset, int length) throws IOException {
                return s3.getRange(key, offset, length, "read the backup");
            }

            @Override
            public void close() {
                // Nothing held between reads: every read is its own request.
            }
        };
    }

    /** The sidecar first (unreference), then the object, best-effort. */
    @Override
    public void deleteObject(String account, String objectId) throws IOException {
        cache.remove(account, objectId);
        s3.deleteObject(key(account, objectId + BackupLayout.ENTRY_EXT), "delete a sidecar");
        deleteQuietly(key(account, objectId + BackupLayout.OBJECT_EXT));
    }

    /**
     * Every key of the account, sidecars first so a listing racing this sees
     * the entries leave before their objects, in {@code DeleteObjects}
     * batches; then the account's unfinished uploads. S3 has no directories,
     * so nothing else is left.
     */
    @Override
    public void deleteAccount(String account) throws IOException {
        String prefix = accountPrefix(account);
        List<S3Client.ObjectInfo> all = listAll(prefix, "list the backups");
        List<String> sidecars = new ArrayList<>();
        List<String> rest = new ArrayList<>();
        for (S3Client.ObjectInfo o : all) {
            String id = BackupLayout.objectIdOf(o.key.substring(o.key.lastIndexOf('/') + 1),
                    BackupLayout.ENTRY_EXT);
            if (id != null) {
                sidecars.add(o.key);
            } else {
                rest.add(o.key);
            }
        }
        s3.deleteObjects(sidecars, "delete the sidecars");
        s3.deleteObjects(rest, "delete the backups");
        cache.clearAccount(account);
        try {
            for (S3Client.Upload u : s3.listUploads(prefix, MAX_PARTS, "list unfinished uploads")) {
                if (!openUploads.contains(u.uploadId)) {
                    s3.abortMultipart(u.key, u.uploadId, "abort an upload");
                }
            }
        } catch (IOException | RuntimeException ignored) {
            // best-effort: the parts are not backups, a lifecycle rule ends them
        }
    }

    private void deleteQuietly(String key) {
        try {
            s3.deleteObject(key, "delete an orphan");
        } catch (IOException | RuntimeException ignored) {
            // best-effort: the orphan sweep is the backstop
        }
    }

    // ---- the upload ----

    /**
     * An object written through {@link OutputStream} of exactly
     * {@code length} bytes: buffered and PUT on close when it fits one part,
     * else uploaded part by part as each fills. Closing short, writing past
     * {@code length}, or any failure aborts (nothing stored, no parts left).
     */
    private final class Upload extends OutputStream {
        private final String key;
        private final long length;
        private final boolean multipart;
        private final byte[] buf;
        private final List<String> etags = new ArrayList<>();
        private int fill;
        private long written;
        @Nullable
        private String uploadId;
        private boolean closed;

        Upload(String key, long length) {
            this.key = key;
            this.length = length;
            long part = partSize(length);
            this.multipart = length > part;
            this.buf = new byte[(int) (multipart ? part : length)];
        }

        @Override
        public void write(int b) throws IOException {
            write(new byte[]{(byte) b}, 0, 1);
        }

        @Override
        public void write(@NonNull byte[] b, int off, int len) throws IOException {
            if (closed) {
                throw new IOException("upload the backup: stream closed");
            }
            if (len > length - written) {
                fail();
                throw new IOException("upload the backup: more than the declared "
                        + length + " bytes");
            }
            int at = off;
            int left = len;
            while (left > 0) {
                int n = Math.min(left, buf.length - fill);
                System.arraycopy(b, at, buf, fill, n);
                fill += n;
                at += n;
                left -= n;
                written += n;
                if (multipart && fill == buf.length) {
                    sendPart();
                }
            }
        }

        private void sendPart() throws IOException {
            try {
                if (uploadId == null) {
                    uploadId = s3.createMultipart(key, "start the upload");
                    openUploads.add(uploadId);
                }
                etags.add(s3.uploadPart(key, uploadId, etags.size() + 1, buf, 0, fill,
                        "upload part " + (etags.size() + 1)));
                fill = 0;
            } catch (IOException | RuntimeException e) {
                fail();
                throw e;
            }
        }

        @Override
        public void close() throws IOException {
            if (closed) {
                return;
            }
            if (written != length) {
                fail();
                throw new IOException("upload the backup: " + written + " of " + length
                        + " bytes written; nothing stored");
            }
            closed = true;
            try {
                if (!multipart) {
                    s3.putObject(key, buf, 0, fill, "upload the backup");
                    return;
                }
                if (fill > 0) {
                    sendPart();
                }
                s3.completeMultipart(key, uploadId, etags, "finish the upload");
                openUploads.remove(uploadId);
            } catch (IOException | RuntimeException e) {
                fail();
                throw e;
            }
        }

        /** Closed for good; an open multipart upload is aborted, best-effort
         *  (the sweep is the backstop when even that fails). */
        private void fail() {
            closed = true;
            String id = uploadId;
            uploadId = null;
            if (id == null) {
                return;
            }
            try {
                s3.abortMultipart(key, id, "abort the upload");
            } catch (IOException | RuntimeException ignored) {
                // left to the sweep and the bucket's lifecycle rule
            }
            openUploads.remove(id);
        }
    }
}
