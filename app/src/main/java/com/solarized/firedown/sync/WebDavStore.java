package com.solarized.firedown.sync;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.solarized.firedown.sync.model.VaultEntry;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.security.GeneralSecurityException;
import java.util.List;
import java.util.Map;

import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;

/**
 * Backups on a WebDAV server the user signed in to (Nextcloud, ownCloud, a
 * NAS, any WebDAV address): the same {@link BackupLayout} as a picked folder,
 * one object and one sidecar per backup under
 * {@code <collection>/Firedown Backup/<account>/objects/}, so a folder synced
 * from that server by another app reads the same. Encrypted on the phone like
 * every backup; the server stores ciphertext under random names.
 *
 * <p><b>Network, not a provider.</b> Unlike a {@link FolderStore}, nothing
 * here is cached by a sync app, so every operation is a request and the
 * failure classes matter: a refused sign-in, a missing permission, a changed
 * certificate or a collection that is gone throw
 * {@link BackupStore.UnreachableException} (the user must act); a timeout or
 * a 5xx throws a plain {@link IOException} (a retry may fix it). The backup
 * workers carry a network constraint when this store is active.
 *
 * <p><b>An object is ONE PUT</b> of its exact length, streamed as the engine
 * encrypts (no temporary copy on the phone). A server that writes uploads to
 * a temporary name and renames on completion (Nextcloud, Apache mod_dav) never
 * shows half an object; a cancelled or short upload stores nothing. The cost:
 * a dropped connection restarts that file from zero, and a proxy that caps
 * request bodies (Cloudflare's 100 MB on its free plan) caps backups too.
 *
 * <p><b>Listings are one PROPFIND</b> (Depth 1) of the objects collection,
 * then one GET per sidecar the store has not seen at that version yet
 * ({@link SidecarCache}, keyed by the server's ETag, else size and date). A
 * first listing fetches four at a time.
 */
public final class WebDavStore implements BackupStore {

    private final WebDavClient dav;
    private final HttpUrl base;
    private final SidecarCache cache = new SidecarCache(SidecarCache.CACHE_BYTES, "webdav-read");

    public WebDavStore(@NonNull WebDavConfig config, @Nullable OkHttpClient baseClient) {
        HttpUrl parsed = HttpUrl.parse(config.url);
        if (parsed == null) {
            throw new IllegalArgumentException("not an http(s) URL: " + config.url);
        }
        this.base = withTrailingSlash(parsed);
        this.dav = new WebDavClient(baseClient, base, config.user, config.password,
                config.pinSha256);
    }

    // ---- where ----

    @Override
    public boolean isConfigured() {
        return true;
    }

    /** One PROPFIND (Depth 0) of the collection. Off the main thread only. */
    @Override
    public boolean isReachable() {
        try {
            WebDavClient.Propfind p = dav.propfind(base, 0);
            return p.code == 207 && (p.self == null || p.self.collection);
        } catch (IOException | RuntimeException e) {
            return false;
        }
    }

    /** The server's name, with its port when it is not the scheme's own. */
    @NonNull
    @Override
    public String providerLabel() {
        return labelOf(base);
    }

    /** {@link #providerLabel()} of a saved address, without a store (the
     *  Backup screen asks on the main thread). "" when it does not parse. */
    @NonNull
    public static String labelOf(@Nullable String url) {
        HttpUrl parsed = url == null ? null : HttpUrl.parse(url);
        return parsed == null ? "" : labelOf(parsed);
    }

    private static String labelOf(HttpUrl url) {
        int defaultPort = HttpUrl.defaultPort(url.scheme());
        return url.port() == defaultPort ? url.host() : url.host() + ":" + url.port();
    }

    @NonNull
    @Override
    public String locationLabel() {
        return providerLabel();
    }

    // ---- layout ----

    private HttpUrl rootDir() {
        return base.newBuilder().addPathSegment(BackupLayout.ROOT_DIR).addPathSegment("").build();
    }

    private HttpUrl accountDir(String account) {
        return base.newBuilder().addPathSegment(BackupLayout.ROOT_DIR)
                .addPathSegment(account).addPathSegment("").build();
    }

    private HttpUrl objectsDir(String account) {
        return base.newBuilder().addPathSegment(BackupLayout.ROOT_DIR)
                .addPathSegment(account).addPathSegment(BackupLayout.OBJECTS_DIR)
                .addPathSegment("").build();
    }

    private HttpUrl file(String account, String name) {
        return base.newBuilder().addPathSegment(BackupLayout.ROOT_DIR)
                .addPathSegment(account).addPathSegment(BackupLayout.OBJECTS_DIR)
                .addPathSegment(name).build();
    }

    /** The collection is still there: a 404 on it means the place is gone,
     *  not that nothing was backed up. */
    private void requireBase() throws IOException {
        WebDavClient.Propfind p = dav.propfind(base, 0);
        if (p.code == 404) {
            throw new UnreachableException("the backup collection is gone from the server");
        }
        if (p.code != 207) {
            throw WebDavClient.statusError(p.code, "check the collection");
        }
    }

    /**
     * Makes {@code Firedown Backup/<account>/objects/} when it is missing.
     * One PROPFIND when it exists (every call, so a collection deleted on the
     * server is noticed before an upload goes into the void); three MKCOLs
     * the first time.
     */
    private void ensureObjectsDir(String account) throws IOException {
        WebDavClient.Propfind p = dav.propfind(objectsDir(account), 0);
        if (p.code == 207) {
            return;
        }
        if (p.code != 404) {
            throw WebDavClient.statusError(p.code, "check the backup collection");
        }
        requireBase();
        mkcol(rootDir());
        mkcol(accountDir(account));
        mkcol(objectsDir(account));
    }

    private void mkcol(HttpUrl url) throws IOException {
        int code = dav.mkcol(url);
        // 405: it exists already (another phone made it, or a race).
        if (code != 201 && code != 405 && !WebDavClient.isSuccess(code)) {
            throw WebDavClient.statusError(code, "create a collection");
        }
    }

    private BackupLayout.Listing<WebDavClient.Resource> list(String account) throws IOException {
        BackupLayout.Listing<WebDavClient.Resource> listing = new BackupLayout.Listing<>();
        WebDavClient.Propfind p = dav.propfind(objectsDir(account), 1);
        if (p.code == 404) {
            requireBase();
            return listing;
        }
        if (p.code != 207) {
            throw WebDavClient.statusError(p.code, "list backups");
        }
        for (WebDavClient.Resource r : p.children) {
            if (!r.collection) {
                listing.add(new BackupLayout.Item<>(r, r.name, r.size, r.modified));
            }
        }
        return listing;
    }

    // ---- entries ----

    @Override
    public List<VaultEntry> listEntries(String account, byte[] storageKey) throws IOException {
        return BackupLayout.collectEntries(list(account),
                sidecars -> loadEntries(account, sidecars, storageKey),
                object -> deleteQuietly(object.handle.url),
                System.currentTimeMillis());
    }

    /** Decodes the sidecars through the shared cache ({@link SidecarCache}):
     *  an unchanged one is not read again, a miss is a GET. */
    private Map<String, VaultEntry> loadEntries(String account,
                                                List<BackupLayout.Item<WebDavClient.Resource>> sidecars,
                                                byte[] storageKey) throws IOException {
        return cache.load(account, sidecars, item -> validator(item.handle),
                item -> dav.getBytes(item.handle.url, BackupLayout.MAX_ENTRY_BYTES, "read a sidecar"),
                storageKey);
    }

    /** What says a sidecar is unchanged: the ETag, else size and date. */
    private static String validator(WebDavClient.Resource r) {
        return r.etag != null ? "e:" + r.etag : "s:" + r.size + ":" + r.modified;
    }

    @Override
    public void writeEntry(String account, VaultEntry entry, byte[] storageKey)
            throws IOException, GeneralSecurityException {
        ensureObjectsDir(account);
        byte[] blob = BackupLayout.encodeEntry(entry, storageKey);
        cache.remove(account, entry.objectId);
        int code = dav.put(file(account, entry.objectId + BackupLayout.ENTRY_EXT), blob);
        if (!WebDavClient.isSuccess(code)) {
            throw WebDavClient.statusError(code, "write a sidecar");
        }
    }

    // ---- objects ----

    @Override
    public OutputStream openNewObject(String account, String objectId, long length)
            throws IOException {
        ensureObjectsDir(account);
        return dav.putStream(file(account, objectId + BackupLayout.OBJECT_EXT), length,
                "upload the backup");
    }

    @Override
    public InputStream openObject(String account, String objectId) throws IOException {
        return dav.openStream(file(account, objectId + BackupLayout.OBJECT_EXT), "read the backup");
    }

    /** Checks the object is there (HEAD), then reads it range by range. */
    @Override
    public RangeReader openObjectRanges(String account, String objectId) throws IOException {
        HttpUrl url = file(account, objectId + BackupLayout.OBJECT_EXT);
        int code = dav.head(url);
        if (code == 404) {
            throw new FileNotFoundException("object " + objectId + " is not on the server");
        }
        // 405: a server that does not do HEAD; the first read will tell.
        if (!WebDavClient.isSuccess(code) && code != 405) {
            throw WebDavClient.statusError(code, "open the backup");
        }
        return new RangeReader() {
            @Override
            public byte[] read(long offset, int length) throws IOException {
                return dav.getRange(url, offset, length, "read the backup");
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
        int code = dav.delete(file(account, objectId + BackupLayout.ENTRY_EXT));
        if (!WebDavClient.isSuccess(code) && code != 404) {
            throw WebDavClient.statusError(code, "delete a sidecar");
        }
        deleteQuietly(file(account, objectId + BackupLayout.OBJECT_EXT));
    }

    /**
     * Every sidecar first, so a listing racing this sees the entries leave
     * before their objects, then the account collection in one DELETE (a
     * collection DELETE is recursive in WebDAV). Nextcloud moves deleted
     * files to its trash bin, where they count against the quota until it is
     * emptied; that is the server's policy, not something a client can skip.
     */
    @Override
    public void deleteAccount(String account) throws IOException {
        BackupLayout.Listing<WebDavClient.Resource> listing = list(account);
        for (BackupLayout.Item<WebDavClient.Resource> item : listing.entries.values()) {
            int code = dav.delete(item.handle.url);
            if (!WebDavClient.isSuccess(code) && code != 404) {
                throw WebDavClient.statusError(code, "delete a sidecar");
            }
        }
        int code = dav.delete(accountDir(account));
        if (!WebDavClient.isSuccess(code) && code != 404) {
            throw WebDavClient.statusError(code, "delete the backups");
        }
        cache.clearAccount(account);
    }

    private void deleteQuietly(HttpUrl url) {
        try {
            dav.delete(url);
        } catch (IOException | RuntimeException ignored) {
            // best-effort: the orphan sweep is the backstop
        }
    }

    static HttpUrl withTrailingSlash(HttpUrl url) {
        List<String> segments = url.pathSegments();
        boolean slash = !segments.isEmpty() && segments.get(segments.size() - 1).isEmpty();
        HttpUrl.Builder b = url.newBuilder().query(null).fragment(null);
        if (!slash) {
            b.addPathSegment("");
        }
        return b.build();
    }
}
