package com.solarized.firedown.sync;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.work.WorkManager;

import com.solarized.firedown.BuildConfig;
import com.solarized.firedown.Preferences;
import com.solarized.firedown.data.RestoredFileAccess;
import com.solarized.firedown.data.di.NetworkModule;
import com.solarized.firedown.data.di.Qualifiers;
import com.solarized.firedown.data.entity.DownloadEntity;
import com.solarized.firedown.data.repository.DownloadDataRepository;
import com.solarized.firedown.glide.VaultObjectModel;
import com.solarized.firedown.sync.crypto.SyncIdentity;
import com.solarized.firedown.sync.model.VaultEntry;
import com.solarized.firedown.utils.FileUriHelper;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import javax.inject.Inject;
import javax.inject.Singleton;

import dagger.hilt.android.qualifiers.ApplicationContext;

/**
 * The Backup facade the UI talks to: the folder, its reachability, what is in
 * it, the recovery code, and the erasure. The actual per-file backup/restore
 * work runs in {@link VaultBackupWorker}/{@link VaultRestoreWorker}; this
 * manager owns the folder glue and the management actions.
 *
 * <p><b>Backup vs. Safe Folder.</b> This is the encrypted backup of finished
 * downloads into a FOLDER the user picks (a Storage Access Framework tree —
 * Google Drive, Dropbox, Nextcloud, an SD card…) — distinct from the local
 * "Safe Folder" ({@code file_safe}), which never leaves the device.
 * User-facing copy says "Backup"; internally the class names keep the
 * {@code Cloud*}/{@code Vault*} prefixes of the hosted service this replaced
 * (the shapes survived the server).
 *
 * <p><b>Shared identity.</b> Backup reuses the bookmark-sync recovery code
 * ({@link SyncSecrets}) — one code derives the storage key that encrypts every
 * object and the account id that names the folder's sub-directory
 * ({@link SyncIdentity}). There is deliberately no on/off switch: a backup
 * action sets it up on demand. {@link Preferences#CLOUD_BACKUP_ENABLED} only
 * tracks "a folder was chosen, keep the shared code" so signing out of one
 * feature can't strand the other.
 *
 * <p><b>There is no server, so there is no status to fetch.</b> "Status" here
 * is two local facts — is the folder reachable, and what does it hold — read
 * through the provider on a bounded pool (a provider can block on its own
 * network), with the last good answer cached in memory and the totals
 * mirrored to prefs for the folder-lost state's "Last seen".
 */
@Singleton
public class CloudBackupManager {

    private static final String TAG_LOG = CloudBackupManager.class.getSimpleName();

    /** Tag on every Backup transfer (backup + restore) WorkManager job, so the
     *  UI can observe "is a transfer running right now?" across both. */
    public static final String WORK_TAG = "cloud_backup_transfer";
    /** The hosted service's periodic account watch, cancelled once at boot. */
    private static final String LEGACY_WATCH_WORK = "cloud-watch";

    private final Context context;
    private final SharedPreferences prefs;
    /** The picked folder (grant, picker-side checks). The ACTIVE place is
     *  {@link #store()}, whichever kind {@link BackupStores#place} names. */
    private final FolderStore folders;
    /** A small BOUNDED pool — NOT the DiskIO/HeavyIO single-thread lanes — for
     *  the folder ops (listings, sidecar reads, deletes). A provider can take
     *  its time (Drive answers a cold listing from the network), so a list
     *  load must not queue behind a delete; capped at 3 so a big multi-select
     *  can't spawn a thread per delete. Threads idle out. */
    private final ExecutorService folderExecutor = newFolderExecutor();

    private static ExecutorService newFolderExecutor() {
        ThreadPoolExecutor pool = new ThreadPoolExecutor(
                3, 3, 60L, TimeUnit.SECONDS, new LinkedBlockingQueue<>());
        pool.allowCoreThreadTimeOut(true);
        return pool;
    }

    private final Executor heavyExecutor;
    private final DownloadDataRepository downloads;
    private final Handler main = new Handler(Looper.getMainLooper());

    @Inject
    public CloudBackupManager(@ApplicationContext Context context,
                              SharedPreferences prefs,
                              @Qualifiers.HeavyIO Executor heavyExecutor,
                              DownloadDataRepository downloads) {
        this.context = context;
        this.prefs = prefs;
        this.heavyExecutor = heavyExecutor;
        this.downloads = downloads;
        this.folders = new FolderStore(context, prefs);
    }

    /** The picked folder (grant, layout, reachability), whether or not it is
     *  the active place. */
    public FolderStore folderStore() {
        return folders;
    }

    /** The active backup place ({@link BackupStores}). Off the main thread:
     *  the first call for a WebDAV server or an S3 bucket unwraps its keys
     *  and loads the trust store. The cheap questions (set up? where?) read
     *  the settings instead. */
    BackupStore store() {
        return BackupStores.open(context, folders);
    }

    /** The kind of place chosen ({@link BackupStores#place}). Cheap. */
    @NonNull
    public BackupStores.Place place() {
        return BackupStores.place(context);
    }

    /** Whether the active place is an S3 bucket. */
    public boolean usesS3() {
        return place() == BackupStores.Place.S3;
    }

    /** Whether the active place is reached over the network (a WebDAV
     *  server or an S3 bucket): the remedy for a lost one is "Reconnect". */
    public boolean usesServer() {
        return place() != BackupStores.Place.FOLDER;
    }

    /** Cancels the hosted service's periodic account watch, which WorkManager
     *  keeps across updates until something cancels it. Idempotent. */
    public void cancelLegacyWatch() {
        WorkManager.getInstance(context).cancelUniqueWork(LEGACY_WATCH_WORK);
    }

    /**
     * Whether Backup is set up on this device: a backup folder has been chosen
     * AND the shared recovery code is present. A code present only because
     * bookmark sync is on does NOT count — nothing is backed up anywhere
     * without a folder.
     */
    public boolean isSetUp() {
        return hasFolder() && hasAccount();
    }

    /** Whether a backup place has been chosen (reachable or not): a folder,
     *  a WebDAV server or an S3 bucket. */
    public boolean hasFolder() {
        switch (place()) {
            case WEBDAV:
                return new WebDavSettings(context).isConfigured();
            case S3:
                return new S3Settings(context).isConfigured();
            case FOLDER:
            default:
                return folders.isConfigured();
        }
    }

    /** Marks Backup as in use (a folder was chosen). */
    public void markEnabled() {
        prefs.edit().putBoolean(Preferences.CLOUD_BACKUP_ENABLED, true).apply();
    }

    /**
     * Whether a recovery code exists on this device at all — set up by bookmark
     * sync OR a prior backup. When true, a backup can reuse it directly; when
     * false, the first backup must mint one ({@link #createNewCode()}).
     */
    public boolean hasAccount() {
        return new SyncSecrets(context).hasCode();
    }

    /**
     * First-time setup: generates and stores a fresh recovery code, returning
     * the grouped code for the "save this — it's the only key" dialog.
     * OVERWRITES any existing code, so callers MUST guard on
     * {@link #hasAccount()} first (an existing code is reused, never replaced).
     */
    public String createNewCode() {
        byte[] code = SyncIdentity.generateRecoveryCode();
        new SyncSecrets(context).store(code);
        String grouped = SyncIdentity.grouped(SyncIdentity.encodeRecoveryCode(code));
        SyncSecrets.wipe(code);
        return grouped;
    }

    /**
     * Adopts the tree the user just picked as THE backup folder (the grant is
     * taken and persisted) and marks Backup in use. A different folder holds
     * different files, so every cached figure is dropped — the next status
     * load reads the new folder.
     */
    public void adoptFolder(@NonNull Uri treeUri) {
        // One place at a time: a picked folder replaces a server, whose saved
        // sign-in goes with it (nothing on the server is touched).
        folders.adopt(treeUri);
        BackupStores.choose(context, BackupStores.Place.FOLDER);
        markEnabled();
        forgetCachedStatus();
        storeTotals(-1, -1);
    }

    /**
     * Checks a just-picked tree can hold backups ({@link FolderStore#checkWritable})
     * and adopts it only if it can. Runs on the folder pool (a provider may go
     * to the network); the answer lands on the main thread, true = adopted. A
     * failed check adopts nothing, so the previous folder, its grant and its
     * cached status all stay as they were. The adopt happens here rather than
     * in the screen so a pick still lands when the user left the screen
     * while the check ran.
     */
    public void checkAndAdoptFolder(@NonNull Uri treeUri, @NonNull Consumer<Boolean> onResult) {
        folderExecutor.execute(() -> {
            boolean writable;
            try {
                folders.checkWritable(treeUri);
                writable = true;
            } catch (IOException | RuntimeException e) {
                if (BuildConfig.DEBUG) {
                    Log.w(TAG_LOG, "checkAndAdoptFolder: the picked folder failed the write check", e);
                }
                writable = false;
            }
            boolean adopt = writable;
            main.post(() -> {
                if (adopt) {
                    adoptFolder(treeUri);
                }
                onResult.accept(adopt);
            });
        });
    }

    /**
     * Finds and tests a WebDAV server ({@link WebDavCheck}) and, when it
     * passes, makes it THE backup place: the sign-in is saved, the picked
     * folder (if any) is forgotten (its files stay where they are), and every
     * cached figure is dropped. Runs on the folder pool; the result lands on
     * the main thread. Anything but {@link WebDavCheck.Kind#OK} saves nothing,
     * so the current place stays as it was. {@code pinSha256} is the leaf the
     * user chose to trust after an {@link WebDavCheck.Kind#UNTRUSTED_CERT}
     * answer, else null. A reconnect may leave {@code password} null to use
     * the saved one, and a null pin reuses a certificate already trusted for
     * the same server ({@link WebDavCheck#runReusing}); the saved sign-in is
     * unwrapped here, off the main thread.
     */
    public void checkAndAdoptWebDav(@NonNull String address, @NonNull String user,
                                    @Nullable String password, @Nullable String pinSha256,
                                    @NonNull Consumer<WebDavCheck.Result> onResult) {
        folderExecutor.execute(() -> {
            WebDavCheck.Result result;
            try {
                WebDavSettings settings = new WebDavSettings(context);
                WebDavConfig saved = settings.isConfigured() ? settings.load() : null;
                result = WebDavCheck.runReusing(NetworkModule.globalClient,
                        saved, address, user, password, pinSha256);
            } catch (RuntimeException e) {
                // Uncaught on the pool it would end the process; the page
                // needs an answer either way.
                result = WebDavCheck.Result.of(WebDavCheck.Kind.UNREACHABLE);
            }
            WebDavCheck.Result answer = result;
            main.post(() -> {
                if (answer.kind == WebDavCheck.Kind.OK && answer.config != null) {
                    adoptWebDav(answer.config);
                }
                onResult.accept(answer);
            });
        });
    }

    /** Saves a checked WebDAV sign-in as the backup place (see
     *  {@link #checkAndAdoptWebDav}). */
    public void adoptWebDav(@NonNull WebDavConfig config) {
        new WebDavSettings(context).save(config);
        folders.forget();
        BackupStores.choose(context, BackupStores.Place.WEBDAV);
        markEnabled();
        forgetCachedStatus();
        storeTotals(-1, -1);
    }

    /**
     * Tests an S3 bucket ({@link S3Check}) and, when it passes, makes it THE
     * backup place: the bucket and its keys are saved, the picked folder or a
     * WebDAV server (if any) is forgotten (their files stay where they are),
     * and every cached figure is dropped. Runs on the folder pool; the result
     * lands on the main thread. Anything but {@link S3Check.Kind#OK} saves
     * nothing. A reconnect may leave {@code secret} null to use the saved one
     * (same endpoint, bucket and access key id only), and a null pin reuses a
     * certificate already trusted for the same endpoint
     * ({@link S3Check#runReusing}); the saved keys are unwrapped here, off the
     * main thread.
     */
    public void checkAndAdoptS3(@NonNull String endpoint, @NonNull String region,
                                @NonNull String bucket, @NonNull String prefix,
                                @NonNull String accessKeyId, @Nullable String secret,
                                @NonNull S3Config.Addressing addressing,
                                @Nullable String pinSha256,
                                @NonNull Consumer<S3Check.Result> onResult) {
        folderExecutor.execute(() -> {
            S3Check.Result result;
            try {
                S3Settings settings = new S3Settings(context);
                S3Config saved = settings.isConfigured() ? settings.load() : null;
                result = S3Check.runReusing(NetworkModule.globalClient, saved, endpoint, region,
                        bucket, prefix, accessKeyId, secret, addressing, pinSha256);
            } catch (RuntimeException e) {
                result = S3Check.Result.of(S3Check.Kind.UNREACHABLE);
            }
            S3Check.Result answer = result;
            main.post(() -> {
                if (answer.kind == S3Check.Kind.OK && answer.config != null) {
                    adoptS3(answer.config);
                }
                onResult.accept(answer);
            });
        });
    }

    /** Saves a checked bucket as the backup place (see {@link #checkAndAdoptS3}). */
    public void adoptS3(@NonNull S3Config config) {
        new S3Settings(context).save(config);
        folders.forget();
        BackupStores.choose(context, BackupStores.Place.S3);
        markEnabled();
        forgetCachedStatus();
        storeTotals(-1, -1);
    }

    /**
     * The saved bucket's fields for the sign-in page's prefill, without its
     * secret: unwrapping the access key id is a keystore call, so this runs
     * on the folder pool and answers on the main thread (null when no bucket
     * is saved or it can no longer be read).
     */
    public void loadS3Prefill(@NonNull Consumer<S3Config> onResult) {
        folderExecutor.execute(() -> {
            S3Settings settings = new S3Settings(context);
            S3Config saved = settings.isConfigured() ? settings.load() : null;
            S3Config shown = saved == null ? null : new S3Config(saved.endpoint, saved.region,
                    saved.bucket, saved.prefix, saved.accessKeyId, "", saved.pathStyle,
                    saved.addressing, saved.pinSha256);
            main.post(() -> onResult.accept(shown));
        });
    }

    /** A human name for where the folder lives ("Google Drive", "SD card"),
     *  or "" when no folder is chosen. Cheap (no provider round trip). */
    @NonNull
    public String providerLabel() {
        switch (place()) {
            case WEBDAV:
                return WebDavStore.labelOf(new WebDavSettings(context).url());
            case S3:
                return S3Store.providerOf(new S3Settings(context).endpoint());
            case FOLDER:
            default:
                return folders.providerLabel();
        }
    }

    /** Provider + the picked folder's name ("Google Drive › Backups"), the
     *  provider alone until a reachability read has named the folder.
     *  Cheap (prefs only). */
    @NonNull
    public String locationLabel() {
        switch (place()) {
            case WEBDAV:
                return WebDavStore.labelOf(new WebDavSettings(context).url());
            case S3: {
                S3Settings s3 = new S3Settings(context);
                return S3Store.locationOf(s3.bucket(), s3.prefix());
            }
            case FOLDER:
            default:
                return folders.locationLabel();
        }
    }

    /** Combined status snapshot for the hero + the Backups header. */
    public static final class Status {
        public final boolean setUp;
        /** Whether the folder answered just now. False also when not set up. */
        public final boolean reachable;
        public final int fileCount;   // -1 = unavailable (folder unreachable / not loaded)
        public final long totalBytes; // -1 = unavailable
        public final String providerLabel;
        /** Provider › folder name ({@link BackupStore#locationLabel()}), read
         *  AFTER the reachability probe so a first read already names the
         *  folder. */
        public final String location;

        Status(boolean setUp, boolean reachable, int fileCount, long totalBytes,
               String providerLabel, String location) {
            this.setUp = setUp;
            this.reachable = reachable;
            this.fileCount = fileCount;
            this.totalBytes = totalBytes;
            this.providerLabel = providerLabel;
            this.location = location;
        }
    }

    /**
     * Last SUCCESSFUL {@link Status} snapshot, kept for the singleton's lifetime.
     * The hero re-binds on every screen entry; without this it renders the
     * empty state for the provider round-trip and visibly "jumps" once the
     * listing lands. The screen paints this synchronously and the fresh
     * {@link #loadStatus} result then UPDATES it in place. Dropped by
     * {@link #adoptFolder} and {@link #deleteAllData}.
     */
    private volatile Status mLastStatus;

    /** The last successful status snapshot, or null before the first load. */
    public Status lastStatus() {
        return mLastStatus;
    }

    /**
     * The content keys ({@link #contentKey}) of the last SUCCESSFUL listing
     * made by {@link #loadBackedUpKeys}, or null before the first one in this
     * process. Positive evidence exactly as far as that listing was: corrected
     * by the mutations this manager makes itself ({@link #deleteEntries}
     * removes its keys; {@link #deleteAllData} and {@link #forgetCachedStatus}
     * drop it). A consumer paints from it synchronously, then lets the fresh
     * listing UPDATE it; the one decision that DELETES a phone copy (the
     * offload confirm) never reads it.
     */
    private volatile Set<String> mLastBackedUpKeys;

    /** The cached key set from the last successful {@link #loadBackedUpKeys}
     *  (an unmodifiable copy), or null when there is none. Main-thread safe. */
    public Set<String> cachedBackedUpKeys() {
        Set<String> keys = mLastBackedUpKeys;
        return keys == null ? null : Collections.unmodifiableSet(new HashSet<>(keys));
    }

    /**
     * Drops the in-memory snapshot because what it describes changed under
     * us — a different recovery code adopted (SyncManager.linkWithCode) or a
     * different folder chosen. Without it the hero would paint the previous
     * account's figures for the whole round-trip.
     */
    public void forgetCachedStatus() {
        mLastStatus = null;
        mLastBackedUpKeys = null;
    }

    /**
     * The last successfully-read total bytes, PERSISTED across process death,
     * or -1 when unknown — the durable floor under {@link #lastStatus()} for a
     * cold start, and the figure the folder-lost state shows as "Last seen".
     */
    public long lastKnownTotalBytes() {
        return prefs.getLong(Preferences.CLOUD_LAST_TOTAL_BYTES, -1);
    }

    /** The file-count twin of {@link #lastKnownTotalBytes()} (-1 = unknown). */
    public int lastKnownFileCount() {
        return prefs.getInt(Preferences.CLOUD_LAST_FILE_COUNT, -1);
    }

    /** Persists (or clears, with -1) the durable totals. */
    private void storeTotals(int files, long bytes) {
        SharedPreferences.Editor edit = prefs.edit();
        if (bytes < 0) {
            edit.remove(Preferences.CLOUD_LAST_TOTAL_BYTES);
        } else {
            edit.putLong(Preferences.CLOUD_LAST_TOTAL_BYTES, bytes);
        }
        if (files < 0) {
            edit.remove(Preferences.CLOUD_LAST_FILE_COUNT);
        } else {
            edit.putInt(Preferences.CLOUD_LAST_FILE_COUNT, files);
        }
        edit.apply();
    }

    /**
     * Reads the folder (reachability + listing) off the folder pool and posts
     * a {@link Status} to the main thread. An unreachable folder yields
     * {@code reachable=false} with the LAST KNOWN totals (so the lost state can
     * say what was there); a reachable one updates the in-memory snapshot and
     * the durable totals. Never touches the recovery code when no folder is
     * chosen.
     */
    public void loadStatus(Consumer<Status> onResult) {
        folderExecutor.execute(() -> {
            BackupStore store = store();
            boolean setUp = store.isConfigured() && hasAccount();
            String label = store.providerLabel();
            Status result;
            if (!setUp) {
                result = new Status(false, false, -1, -1, label, store.locationLabel());
            } else if (!store.isReachable()) {
                result = new Status(true, false, lastKnownFileCount(), lastKnownTotalBytes(),
                        label, store.locationLabel());
            } else {
                byte[] code = new SyncSecrets(context).load();
                int files = -1;
                long bytes = -1;
                if (code != null) {
                    try {
                        SyncIdentity identity = SyncIdentity.fromCode(code);
                        VaultEngine engine = new VaultEngine(store, identity);
                        List<VaultEntry> entries = engine.loadManifest();
                        files = entries.size();
                        long total = 0;
                        for (VaultEntry e : entries) {
                            total += e.size;
                        }
                        bytes = total;
                    } catch (Exception e) {
                        if (BuildConfig.DEBUG) {
                            Log.w(TAG_LOG, "loadStatus: listing failed", e);
                        }
                    } finally {
                        SyncSecrets.wipe(code);
                    }
                }
                String location = store.locationLabel();
                if (files >= 0) {
                    result = new Status(true, true, files, bytes, label, location);
                    mLastStatus = result;
                    storeTotals(files, bytes);
                } else {
                    // Reachable but the listing failed (a provider hiccup):
                    // report unreachable-with-last-known rather than invent.
                    result = new Status(true, false, lastKnownFileCount(), lastKnownTotalBytes(),
                            label, location);
                }
            }
            final Status out = result;
            main.post(() -> onResult.accept(out));
        });
    }

    /**
     * Loads the backed-up file list (the sidecars) off the folder pool and
     * posts it to the main thread. {@code onError} is invoked (main thread) on
     * any failure — the folder unreachable, a listing that failed — so the UI
     * can show an error state rather than an empty list.
     */
    public void loadEntries(Consumer<List<VaultEntry>> onResult, Runnable onError) {
        folderExecutor.execute(() -> {
            byte[] code = new SyncSecrets(context).load();
            BackupStore store = store();
            if (code == null || !store.isConfigured()) {
                SyncSecrets.wipe(code);
                main.post(() -> onResult.accept(new ArrayList<>()));
                return;
            }
            try {
                SyncIdentity identity = SyncIdentity.fromCode(code);
                VaultEngine engine = new VaultEngine(store, identity);
                List<VaultEntry> entries = engine.loadManifest();
                sortNewestFirst(entries);
                main.post(() -> onResult.accept(entries));
            } catch (Exception e) {
                if (BuildConfig.DEBUG) {
                    Log.e(TAG_LOG, "loadEntries failed", e);
                }
                main.post(onError);
            } finally {
                SyncSecrets.wipe(code);
            }
        });
    }

    /**
     * Orders the Backups list <b>newest backup first</b>, applied here at the
     * one load choke point so every consumer of {@link #loadEntries} gets the
     * same order. Stable sort on {@link VaultEntry#backedUpAt} descending;
     * entries with no timestamp (written by a build before it existed) keep
     * their listing order at the tail. Deliberately NOT sorted on
     * {@code downloadedAt}: that is the local download's date, so a clip
     * downloaded last year and backed up today would sort to the bottom of the
     * very list that just gained it.
     */
    static void sortNewestFirst(List<VaultEntry> entries) {
        Collections.sort(entries, (a, b) -> Long.compare(b.backedUpAt, a.backedUpAt));
    }

    /**
     * The content key a download and its backed-up entry share:
     * {@code name + ' ' + size}. Matches {@link VaultEngine}'s own dedup key
     * (name + size), so the Downloads list can tell which of its rows are backed
     * up by a cheap set lookup.
     */
    public static String contentKey(String name, long size) {
        return (name == null ? "" : name) + ' ' + size;
    }

    /**
     * Loads the set of backed-up content keys ({@link #contentKey}) off the
     * folder pool and posts it to the main thread, for the Downloads list's
     * "backed up" mark. Empty when not set up / the folder is unreachable / on
     * any error — a mark is shown ONLY for a key we positively saw, never a
     * wrong "not backed up". A non-user (no folder) returns empty with NO
     * provider touch.
     */
    public void loadBackedUpKeys(Consumer<Set<String>> onResult) {
        folderExecutor.execute(() -> {
            Set<String> keys = new HashSet<>();
            byte[] code = new SyncSecrets(context).load();
            if (code != null && isSetUp()) {
                try {
                    SyncIdentity identity = SyncIdentity.fromCode(code);
                    VaultEngine engine = new VaultEngine(store(), identity);
                    for (VaultEntry e : engine.loadManifest()) {
                        keys.add(contentKey(e.name, e.size));
                    }
                    // A successful listing is the only thing that (re)fills the
                    // cache; a failure leaves the previous snapshot in place
                    // and answers THIS call with the empty set.
                    mLastBackedUpKeys = new HashSet<>(keys);
                } catch (Exception e) {
                    // Unreachable / transient — empty set (no marks), best-effort.
                } finally {
                    SyncSecrets.wipe(code);
                }
            } else {
                SyncSecrets.wipe(code);
            }
            final Set<String> out = keys;
            main.post(() -> onResult.accept(out));
        });
    }

    /** Removes one backed-up file from the folder off the folder pool.
     *  {@code onResult} is posted to the main thread. */
    public void deleteEntry(VaultEntry entry, Consumer<Boolean> onResult) {
        deleteEntries(Collections.singletonList(entry), onResult);
    }

    /** Removes several backed-up files from the folder (sidecar first, then
     *  object, each), off the folder pool; {@code onResult} is posted to the
     *  main thread. */
    public void deleteEntries(List<VaultEntry> entries, Consumer<Boolean> onResult) {
        folderExecutor.execute(() -> {
            boolean ok;
            byte[] code = new SyncSecrets(context).load();
            if (code == null) {
                ok = false;
            } else {
                try {
                    SyncIdentity identity = SyncIdentity.fromCode(code);
                    VaultEngine engine = new VaultEngine(store(), identity);
                    engine.deleteEntries(entries);
                    ok = true;
                    // Keep the key cache honest for our own mutation: a
                    // removed entry must not keep painting a mark or counting
                    // toward "Free up space" until the next listing.
                    Set<String> cached = mLastBackedUpKeys;
                    if (cached != null) {
                        Set<String> next = new HashSet<>(cached);
                        for (VaultEntry e : entries) {
                            next.remove(contentKey(e.name, e.size));
                        }
                        mLastBackedUpKeys = next;
                    }
                    mLastStatus = null; // usage changed — the next load re-counts
                } catch (Exception e) {
                    ok = false;
                } finally {
                    SyncSecrets.wipe(code);
                }
            }
            final boolean success = ok;
            main.post(() -> onResult.accept(success));
        });
    }

    /**
     * Checks — off the main thread — whether this backed-up file is ALREADY in
     * the Downloads folder, using the same name+size+exists test {@link
     * VaultRestoreWorker} uses to no-op a restore. Lets the Backups list decide
     * up-front between "already in your downloads" and enqueuing a restore.
     * {@code onResult} is posted to the main thread.
     */
    public void isAlreadyDownloaded(VaultEntry entry, Consumer<Boolean> onResult) {
        folderExecutor.execute(() -> {
            boolean present = false;
            try {
                DownloadEntity local = downloads.findByNameSize(entry.name, entry.size);
                present = local != null && local.getFilePath() != null
                        && new File(local.getFilePath()).exists();
            } catch (Exception ignored) {
                // Best-effort — treat as not present and let the worker's own
                // check be the backstop.
            }
            final boolean out = present;
            main.post(() -> onResult.accept(out));
        });
    }

    /**
     * Resolves, in ONE background pass, which of these entries no longer have a
     * local copy — the set the Backups list treats as "only in the backup".
     * Batched on purpose (once per listing, not per bound row). "Local" means
     * a download row whose file is actually READABLE (owned, or through the
     * persisted SAF grant of a restored file) — a lookup that THROWS leaves the
     * id OUT of the set, so an unknown never claims "only copy". Result is
     * posted to the main thread, keyed by objectId.
     */
    public void resolveCloudOnly(List<VaultEntry> entries, Consumer<Set<String>> onResult) {
        if (entries == null || entries.isEmpty()) {
            main.post(() -> onResult.accept(Collections.emptySet()));
            return;
        }
        final List<VaultEntry> snapshot = new ArrayList<>(entries);
        heavyExecutor.execute(() -> {
            Set<String> cloudOnly = new HashSet<>();
            for (VaultEntry entry : snapshot) {
                try {
                    DownloadEntity local = downloads.findByNameSize(entry.name, entry.size);
                    if (local == null || !hasReadableFile(local)) {
                        cloudOnly.add(entry.objectId);
                    }
                } catch (Exception e) {
                    if (BuildConfig.DEBUG) {
                        Log.d(TAG_LOG, "resolveCloudOnly: lookup failed", e);
                    }
                }
            }
            main.post(() -> onResult.accept(cloudOnly));
        });
    }

    /** True when the download row's file can actually be READ — directly when
     *  this install owns it, else through the persisted SAF grant for a restored
     *  foreign-owned file ({@code openableUri} covers both and yields null when
     *  neither works; {@code exists()} is false for a readable foreign-owned
     *  file and must not be used here). */
    private boolean hasReadableFile(DownloadEntity local) {
        return RestoredFileAccess.openableUri(context, local.getFilePath()) != null;
    }

    /**
     * Resolves Glide MODELS for entries that have no stored sidecar preview, in
     * ONE executor task and ONE main-thread callback (objectId → model; entries
     * with nothing resolved omitted). Two model kinds come back: a
     * {@link DownloadEntity} (the local copy — loaded through
     * {@code GlideHelper.load}, byte-identical keys to the Downloads list), or
     * a {@link VaultObjectModel} (no local copy, IMAGES only: decode the
     * preview straight from the encrypted object).
     */
    public void resolveLocalThumbs(List<VaultEntry> entries, Consumer<Map<String, Object>> onModels) {
        heavyExecutor.execute(() -> {
            Map<String, Object> out = new HashMap<>();
            for (VaultEntry entry : entries) {
                if (entry == null || entry.objectId == null || entry.name == null) {
                    continue;
                }
                Object model = resolveLocalThumbBlocking(entry);
                if (model != null) {
                    out.put(entry.objectId, model);
                }
            }
            main.post(() -> onModels.accept(out));
        });
    }

    @Nullable
    private Object resolveLocalThumbBlocking(VaultEntry entry) {
        try {
            DownloadEntity local = downloads.findByNameSize(entry.name, entry.size);
            // No File.exists() gate: exists() is FALSE for a restored
            // foreign-owned file that IS readable via the SAF grant — the
            // Glide DownloadEntity loaders resolve access themselves.
            if (local != null && local.getFilePath() != null) {
                return local;
            }
            if (FileUriHelper.isImage(entry.mime)) {
                return VaultObjectModel.of(entry);
            }
        } catch (Exception ignored) {
            // Best-effort — fall through to the mime glyph.
        }
        return null;
    }

    /** The single-entry form of {@link #resolveLocalThumbs}. */
    public void resolveLocalThumb(VaultEntry entry, Consumer<Object> onModel) {
        heavyExecutor.execute(() -> {
            final Object out = resolveLocalThumbBlocking(entry);
            main.post(() -> onModel.accept(out));
        });
    }

    /** The grouped recovery code for display, or null if none. */
    public String recoveryCodeForDisplay() {
        byte[] code = new SyncSecrets(context).load();
        if (code == null) {
            return null;
        }
        String grouped = SyncIdentity.grouped(SyncIdentity.encodeRecoveryCode(code));
        SyncSecrets.wipe(code);
        return grouped;
    }

    /**
     * Erases every backed-up file of this account from the folder (its
     * sub-directory: objects, sidecars, the directory itself), off the folder
     * pool; {@code onResult} is posted to the main thread. The folder stays
     * chosen and the recovery code stays — this is "delete the files", not
     * "leave": the next backup writes into the same folder again. Every
     * in-flight/queued transfer is cancelled FIRST (backups AND restores both
     * carry {@link #WORK_TAG}), or a queued backup would re-create the
     * directory seconds after the user erased it.
     */
    public void deleteAllData(Consumer<Boolean> onResult) {
        WorkManager.getInstance(context).cancelAllWorkByTag(WORK_TAG);
        folderExecutor.execute(() -> {
            boolean ok;
            byte[] code = new SyncSecrets(context).load();
            BackupStore store = store();
            if (code == null || !store.isConfigured()) {
                ok = true; // nothing addressable — nothing to erase
                SyncSecrets.wipe(code);
            } else {
                try {
                    SyncIdentity identity = SyncIdentity.fromCode(code);
                    VaultEngine engine = new VaultEngine(store, identity);
                    engine.deleteAll();
                    ok = true;
                } catch (Exception e) {
                    if (BuildConfig.DEBUG) {
                        Log.e(TAG_LOG, "deleteAllData failed", e);
                    }
                    ok = false;
                } finally {
                    SyncSecrets.wipe(code);
                }
            }
            final boolean success = ok;
            main.post(() -> {
                if (success) {
                    mLastStatus = null;       // usage changed — drop the stale snapshot
                    mLastBackedUpKeys = null; // nothing is backed up any more
                    storeTotals(0, 0);        // known-empty, not unknown
                }
                onResult.accept(success);
            });
        });
    }
}
