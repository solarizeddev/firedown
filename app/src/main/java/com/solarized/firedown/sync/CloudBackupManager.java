package com.solarized.firedown.sync;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;

import com.solarized.firedown.Preferences;
import com.solarized.firedown.data.di.Qualifiers;
import com.solarized.firedown.sync.crypto.SyncIdentity;
import com.solarized.firedown.sync.model.VaultEntry;

import java.util.List;
import java.util.concurrent.Executor;
import java.util.function.Consumer;

import javax.inject.Inject;
import javax.inject.Singleton;

import dagger.hilt.android.qualifiers.ApplicationContext;
import okhttp3.OkHttpClient;

/**
 * The Cloud Backup facade the UI talks to: report usage, show the recovery code,
 * and erase everything from the server. The actual per-file backup/restore work
 * runs in {@link VaultBackupWorker}/{@link VaultRestoreWorker}; this manager owns
 * the account glue and the management actions.
 *
 * <p><b>Cloud Backup vs. Safe Folder.</b> This is the encrypted <em>cloud</em>
 * backup of finished downloads (to {@code storage.firedown.app}) — distinct from
 * the local "Safe Folder" ({@code file_safe}), which never leaves the device.
 * User-facing copy says "Cloud Backup"; internally the storage client/engine keep
 * the {@code Vault*} names.
 *
 * <p><b>Shared identity.</b> Cloud Backup reuses the bookmark-sync recovery code
 * ({@link SyncSecrets}) — one recovery code derives the same account on every
 * service ({@link SyncIdentity}), with a distinct content key for storage. There
 * is deliberately no on/off switch: a backup action sets it up on demand. The
 * {@link Preferences#CLOUD_BACKUP_ENABLED} flag only tracks "has data, keep the
 * shared code" so signing out of one feature can't strand the other.
 */
@Singleton
public class CloudBackupManager {

    private final Context context;
    private final SharedPreferences prefs;
    private final Executor diskExecutor;
    private final OkHttpClient httpClient;
    private final Handler main = new Handler(Looper.getMainLooper());

    @Inject
    public CloudBackupManager(@ApplicationContext Context context,
                              SharedPreferences prefs,
                              @Qualifiers.DiskIO Executor diskExecutor,
                              OkHttpClient httpClient) {
        this.context = context;
        this.prefs = prefs;
        this.diskExecutor = diskExecutor;
        this.httpClient = httpClient;
    }

    /** The storage backend base URL (paid vault — a BYO picker may come later). */
    public String backendUrl() {
        return Preferences.STORAGE_DEFAULT_BACKEND;
    }

    /**
     * Whether Cloud Backup is set up on this device: the shared recovery code is
     * present AND the user has actually used it (has data on the server). A code
     * present only because bookmark sync is on does NOT count as Cloud Backup
     * being set up.
     */
    public boolean isSetUp() {
        return prefs.getBoolean(Preferences.CLOUD_BACKUP_ENABLED, false)
                && new SyncSecrets(context).hasCode();
    }

    /** Marks Cloud Backup as in use (called after the first successful backup). */
    public void markEnabled() {
        prefs.edit().putBoolean(Preferences.CLOUD_BACKUP_ENABLED, true).apply();
    }

    /**
     * Whether a recovery code exists on this device at all — set up by bookmark
     * sync OR a prior Cloud Backup. When true, a backup can reuse it directly; when
     * false, the first backup must mint one ({@link #createNewCode()}).
     */
    public boolean hasAccount() {
        return new SyncSecrets(context).hasCode();
    }

    /**
     * First-time setup: generates and stores a fresh recovery code and marks Cloud
     * Backup in use, returning the grouped code for the "save this — it's the only
     * key" dialog. OVERWRITES any existing code, so callers MUST guard on
     * {@link #hasAccount()} first (an existing account is reused, never replaced).
     */
    public String createNewCode() {
        byte[] code = SyncIdentity.generateRecoveryCode();
        new SyncSecrets(context).store(code);
        String grouped = SyncIdentity.grouped(SyncIdentity.encodeRecoveryCode(code));
        SyncSecrets.wipe(code);
        markEnabled();
        return grouped;
    }

    /** Current backed-up usage (file count + total original bytes). */
    public static final class Usage {
        public final boolean setUp;
        public final int fileCount;
        public final long totalBytes;

        Usage(boolean setUp, int fileCount, long totalBytes) {
            this.setUp = setUp;
            this.fileCount = fileCount;
            this.totalBytes = totalBytes;
        }
    }

    /**
     * Loads the current usage off the disk executor and posts it to the main
     * thread. On any error (offline, etc.) reports a not-set-up/zero usage rather
     * than throwing — this drives a settings summary, not a critical path.
     */
    public void loadUsage(Consumer<Usage> onResult) {
        diskExecutor.execute(() -> {
            Usage usage;
            byte[] code = new SyncSecrets(context).load();
            if (code == null || !isSetUp()) {
                usage = new Usage(false, 0, 0);
            } else {
                Usage computed;
                try {
                    SyncIdentity identity = SyncIdentity.fromCode(code);
                    StorageApiClient api = new StorageApiClient(httpClient, backendUrl());
                    VaultEngine engine = new VaultEngine(api, identity);
                    List<VaultEntry> entries = engine.loadManifest();
                    long total = 0;
                    for (VaultEntry e : entries) {
                        total += e.size;
                    }
                    computed = new Usage(true, entries.size(), total);
                } catch (Exception e) {
                    // Offline / transient — keep the screen usable, show nothing.
                    computed = new Usage(true, -1, -1);
                } finally {
                    SyncSecrets.wipe(code);
                }
                usage = computed;
            }
            final Usage out = usage;
            main.post(() -> onResult.accept(out));
        });
    }

    /** The grouped recovery code for display, or null if not set up. */
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
     * Erases all cloud-backup data from the server (objects + manifest + account),
     * then — only on success — clears the local Cloud Backup flag. The shared
     * recovery code is wiped only when bookmark sync no longer needs it either
     * (symmetric with {@link SyncManager#disable()}). Runs on the disk executor;
     * {@code onResult} is posted to the main thread.
     */
    public void deleteAllData(Consumer<Boolean> onResult) {
        diskExecutor.execute(() -> {
            boolean ok;
            byte[] code = new SyncSecrets(context).load();
            if (code == null) {
                ok = true; // no key here — nothing server-side we can address
            } else {
                try {
                    SyncIdentity identity = SyncIdentity.fromCode(code);
                    StorageApiClient api = new StorageApiClient(httpClient, backendUrl());
                    api.register(identity); // idempotent — makes the signed DELETE resolvable
                    api.deleteAccount(identity);
                    ok = true;
                } catch (Exception e) {
                    ok = false;
                } finally {
                    SyncSecrets.wipe(code);
                }
            }
            final boolean success = ok;
            main.post(() -> {
                if (success) {
                    prefs.edit().putBoolean(Preferences.CLOUD_BACKUP_ENABLED, false).apply();
                    if (!prefs.getBoolean(Preferences.SYNC_ENABLED, false)) {
                        new SyncSecrets(context).clear();
                    }
                }
                onResult.accept(success);
            });
        });
    }
}
