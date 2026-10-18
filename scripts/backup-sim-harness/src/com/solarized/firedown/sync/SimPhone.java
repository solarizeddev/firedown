package com.solarized.firedown.sync;

import android.content.Context;
import android.content.SharedPreferences;

import com.solarized.firedown.data.di.NetworkModule;
import com.solarized.firedown.okhttp.UserAgentInterceptor;
import com.solarized.firedown.sync.crypto.SyncIdentity;
import com.solarized.firedown.sync.model.VaultEntry;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.net.Proxy;
import java.nio.file.Files;
import java.security.GeneralSecurityException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

import okhttp3.OkHttpClient;
import simapi.Phone;

/**
 * One phone: its own prefs (a MemContext standing in for the app's two prefs
 * files), its own OkHttp client tagged with a User-Agent the fake servers use
 * to tell phones apart, and the REAL path from the settings to the bytes:
 * S3Check / WebDavCheck → S3Settings / WebDavSettings → BackupStores.choose →
 * BackupStores.open → VaultEngine / VaultObjectReader. Loaded by a class
 * loader of its own, so every static of the real classes is this phone's.
 */
public final class SimPhone implements Phone {

    private final String name;
    private final File work;
    private final MemContext context = new MemContext();
    private final MemPrefs appPrefs = new MemPrefs();
    private final OkHttpClient base;
    private final List<VaultEngine> running = new CopyOnWriteArrayList<>();
    private volatile SyncIdentity identity;

    public SimPhone(String name, String workDir) {
        this.name = name;
        this.work = new File(workDir, name);
        if (!work.isDirectory() && !work.mkdirs()) {
            throw new IllegalStateException("no work dir " + work);
        }
        this.base = new OkHttpClient.Builder()
                .proxy(Proxy.NO_PROXY)
                .addInterceptor(new UserAgentInterceptor("sim-" + name))
                .build();
        NetworkModule.globalClient = base;
    }

    @Override
    public String name() {
        return name;
    }

    // ---- the place ----

    @Override
    public void useS3(String endpoint, String bucket, String prefix, String keyId, String secret) {
        S3Check.Result r = S3Check.run(base, endpoint, "", bucket, prefix, keyId, secret,
                S3Config.Addressing.PATH, null);
        if (r.kind != S3Check.Kind.OK || r.config == null) {
            throw new IllegalStateException(name + ": the S3 check answered " + r.kind);
        }
        new S3Settings(context).save(r.config);
        BackupStores.choose(context, BackupStores.Place.S3);
    }

    @Override
    public void useDav(String url, String user, String password) {
        WebDavCheck.Result r = WebDavCheck.run(base, url, user, password, null);
        if (r.kind != WebDavCheck.Kind.OK || r.config == null) {
            throw new IllegalStateException(name + ": the WebDAV check answered " + r.kind);
        }
        new WebDavSettings(context).save(r.config);
        BackupStores.choose(context, BackupStores.Place.WEBDAV);
    }

    /** The active place, the way every worker gets it; retries never sleep. */
    private BackupStore store() {
        BackupStore store = BackupStores.open(context, appPrefs);
        if (store instanceof S3Store) {
            ((S3Store) store).client().sleeper = ms -> {
            };
        }
        if (!store.isConfigured()) {
            throw new IllegalStateException(name + ": no place configured");
        }
        return store;
    }

    @Override
    public void setCode(byte[] code) {
        identity = SyncIdentity.fromCode(code.clone());
    }

    @Override
    public String account() {
        return identity.accountBase32();
    }

    @Override
    public void setClockOffset(long millis) {
        S3Client.phoneClock = () -> System.currentTimeMillis() + millis;
    }

    // ---- backups ----

    private VaultEngine engine() {
        if (identity == null) {
            throw new IllegalStateException(name + ": no recovery code");
        }
        return new VaultEngine(store(), identity);
    }

    @Override
    public String backup(String fileName, long size, Callable<InputStream> source, String thumb,
                         String origin) throws Exception {
        VaultEngine engine = engine();
        running.add(engine);
        try {
            VaultEntry e = engine.backupStream(fileName, size, () -> {
                try {
                    return source.call();
                } catch (IOException ex) {
                    throw ex;
                } catch (Exception ex) {
                    throw new IOException(ex);
                }
            }, "application/octet-stream", thumb, null, origin, 0);
            return e.objectId;
        } finally {
            running.remove(engine);
        }
    }

    @Override
    public void cancelBackups() {
        for (VaultEngine e : running) {
            e.cancel();
        }
    }

    private static Map<String, Object> toMap(VaultEntry e) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("objectId", e.objectId);
        m.put("name", e.name);
        m.put("size", e.size);
        m.put("thumb", e.thumb);
        m.put("origin", e.origin);
        m.put("backedUpAt", e.backedUpAt);
        m.put("chunkCount", e.chunkCount);
        m.put("wrappedDek", e.wrappedDek);
        m.put("mime", e.mime);
        m.put("downloadedAt", e.downloadedAt);
        return m;
    }

    private static VaultEntry fromMap(Map<String, Object> m) {
        return new VaultEntry((String) m.get("objectId"), (String) m.get("wrappedDek"),
                (String) m.get("name"), (Long) m.get("size"), (String) m.get("mime"),
                (Long) m.get("downloadedAt"), (Integer) m.get("chunkCount"),
                (String) m.get("thumb"), (String) m.get("origin"), (Long) m.get("backedUpAt"));
    }

    @Override
    public List<Map<String, Object>> list() throws IOException {
        List<Map<String, Object>> out = new ArrayList<>();
        for (VaultEntry e : engine().loadManifest()) {
            out.add(toMap(e));
        }
        return out;
    }

    private VaultEntry find(String objectId) throws IOException {
        for (VaultEntry e : engine().loadManifest()) {
            if (e.objectId.equals(objectId)) {
                return e;
            }
        }
        throw new FileNotFoundException(name + ": no entry " + objectId + " in the listing");
    }

    @Override
    public byte[] restore(String objectId) throws Exception {
        return restore(find(objectId));
    }

    @Override
    public byte[] restore(Map<String, Object> entry) throws Exception {
        return restore(fromMap(entry));
    }

    private byte[] restore(VaultEntry e) throws Exception {
        File dest = File.createTempFile("restore", ".bin", work);
        try {
            engine().restoreFile(e, dest, null);
            return Files.readAllBytes(dest.toPath());
        } finally {
            Files.deleteIfExists(dest.toPath());
        }
    }

    @Override
    public Reader open(String objectId) throws Exception {
        return open(find(objectId));
    }

    @Override
    public Reader open(Map<String, Object> entry) throws Exception {
        return open(fromMap(entry));
    }

    private Reader open(VaultEntry entry) throws Exception {
        VaultObjectReader r = new VaultObjectReader(store(), identity, entry);
        return new Reader() {
            @Override
            public long length() {
                return r.length();
            }

            @Override
            public int readAt(long pos, byte[] buf, int off, int len) throws IOException {
                return r.readAt(pos, buf, off, len);
            }

            @Override
            public void close() {
                r.close();
            }
        };
    }

    @Override
    public void delete(String objectId) throws IOException {
        engine().deleteEntry(find(objectId));
    }

    @Override
    public void delete(Map<String, Object> entry) throws IOException {
        engine().deleteEntry(fromMap(entry));
    }

    @Override
    public void deleteAll() throws IOException {
        engine().deleteAll();
    }

    @Override
    public int sweepUploadsNow() {
        BackupStore store = store();
        if (!(store instanceof S3Store)) {
            return -1;
        }
        S3Config config = new S3Settings(context).load();
        String prefix = config.prefix + BackupLayout.ROOT_DIR + "/" + account() + "/"
                + BackupLayout.OBJECTS_DIR + "/";
        return ((S3Store) store).sweepUploads(prefix);
    }

    @Override
    public String describe(Throwable t) {
        Throwable e = t;
        if (e instanceof IOException && e.getCause() instanceof GeneralSecurityException) {
            return "crypto";
        }
        if (e instanceof S3Client.RefusedException) {
            return "refused:" + ((S3Client.RefusedException) e).refusal;
        }
        if (e instanceof BackupStore.UnreachableException) {
            return "unreachable";
        }
        if (e instanceof BackupStore.FullException) {
            return "full";
        }
        if (e instanceof FileNotFoundException) {
            return "notfound";
        }
        if (e instanceof S3Client.ServerException) {
            S3Client.ServerException se = (S3Client.ServerException) e;
            return "server:" + se.status + ":" + se.code;
        }
        if (e instanceof GeneralSecurityException) {
            return "crypto";
        }
        if (e instanceof IOException) {
            return "io";
        }
        return e.getClass().getName();
    }

    // ---- the app's prefs, in memory ----

    /** Prefs held in memory; apply = commit, both immediate. */
    static final class MemPrefs implements SharedPreferences {
        final Map<String, Object> values = new ConcurrentHashMap<>();

        @Override
        public String getString(String key, String defValue) {
            Object v = values.get(key);
            return v instanceof String ? (String) v : defValue;
        }

        @Override
        public boolean getBoolean(String key, boolean defValue) {
            Object v = values.get(key);
            return v instanceof Boolean ? (Boolean) v : defValue;
        }

        @Override
        public boolean contains(String key) {
            return values.containsKey(key);
        }

        @Override
        public Editor edit() {
            Map<String, Object> pending = new HashMap<>();
            List<String> removed = new ArrayList<>();
            return new Editor() {
                @Override
                public Editor putString(String key, String value) {
                    pending.put(key, value);
                    return this;
                }

                @Override
                public Editor putBoolean(String key, boolean value) {
                    pending.put(key, value);
                    return this;
                }

                @Override
                public Editor remove(String key) {
                    removed.add(key);
                    return this;
                }

                @Override
                public void apply() {
                    commit();
                }

                @Override
                public boolean commit() {
                    synchronized (values) {
                        for (String k : removed) {
                            values.remove(k);
                        }
                        values.putAll(pending);
                    }
                    return true;
                }
            };
        }
    }

    static final class MemContext extends Context {
        final Map<String, MemPrefs> files = new ConcurrentHashMap<>();

        @Override
        public Context getApplicationContext() {
            return this;
        }

        @Override
        public SharedPreferences getSharedPreferences(String name, int mode) {
            return files.computeIfAbsent(name, n -> new MemPrefs());
        }
    }
}
