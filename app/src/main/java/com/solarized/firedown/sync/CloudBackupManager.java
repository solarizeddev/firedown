package com.solarized.firedown.sync;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.solarized.firedown.BuildConfig;

import com.solarized.firedown.GlideHelper;
import com.solarized.firedown.Preferences;
import com.solarized.firedown.data.di.Qualifiers;
import com.solarized.firedown.data.entity.DownloadEntity;
import com.solarized.firedown.data.repository.DownloadDataRepository;
import com.solarized.firedown.sync.crypto.SyncIdentity;
import com.solarized.firedown.sync.model.VaultEntry;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
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

    private static final String TAG_LOG = CloudBackupManager.class.getSimpleName();

    /** Tag on every Cloud Backup transfer (upload + restore) WorkManager job, so
     *  the UI can observe "is a transfer running right now?" across both. */
    public static final String WORK_TAG = "cloud_backup_transfer";

    private final Context context;
    private final SharedPreferences prefs;
    /** A small BOUNDED pool — NOT the DiskIO/HeavyIO single-thread lanes — for the
     *  cloud ops that hit the network (manifest pull/push, object delete). Those
     *  are slow and must run CONCURRENTLY (a list load must not queue behind a
     *  delete's round-trips — the "dead slow Loading…" symptom), but the pool is
     *  CAPPED at 3 so a big multi-select can't spawn a thread per delete and
     *  hammer the manifest with concurrent OCC mutations. Threads idle out
     *  (`allowCoreThreadTimeOut`) so a rarely-used feature keeps none alive. */
    private final ExecutorService netExecutor = newNetExecutor();

    private static ExecutorService newNetExecutor() {
        ThreadPoolExecutor pool = new ThreadPoolExecutor(
                3, 3, 60L, TimeUnit.SECONDS, new LinkedBlockingQueue<>());
        pool.allowCoreThreadTimeOut(true);
        return pool;
    }
    private final Executor heavyExecutor;
    private final OkHttpClient httpClient;
    private final DownloadDataRepository downloads;
    private final Handler main = new Handler(Looper.getMainLooper());

    @Inject
    public CloudBackupManager(@ApplicationContext Context context,
                              SharedPreferences prefs,
                              @Qualifiers.HeavyIO Executor heavyExecutor,
                              OkHttpClient httpClient,
                              DownloadDataRepository downloads) {
        this.context = context;
        this.prefs = prefs;
        this.heavyExecutor = heavyExecutor;
        this.httpClient = httpClient;
        this.downloads = downloads;
    }

    /** The storage backend base URL (paid vault — a BYO picker may come later). */
    public String backendUrl() {
        return Preferences.STORAGE_DEFAULT_BACKEND;
    }

    /** Marks which account has been registered on this install (account base32). */
    private static final String KEY_REGISTERED_ACCOUNT = "storage.registered.account";
    private static final Object REGISTER_LOCK = new Object();

    /**
     * Registers the account at most ONCE per install, instead of on every backup.
     * Registration is idempotent, but Cloudflare rate-limits the register/challenge
     * endpoints (per IP), so re-registering on every upload bursts them and trips a
     * 429 — and an existing account never needs re-registering for the signed
     * storage calls to resolve. The static lock serializes concurrent first-time
     * backups so they don't all register at once (the flag isn't set until the
     * first completes). After the marker is set, this is a cheap prefs read.
     */
    public static void ensureRegistered(SharedPreferences prefs, StorageApiClient api,
                                        SyncIdentity identity) throws IOException {
        String account = identity.accountBase32();
        synchronized (REGISTER_LOCK) {
            if (account.equals(prefs.getString(KEY_REGISTERED_ACCOUNT, null))) {
                return;
            }
            api.register(identity); // idempotent
            prefs.edit().putString(KEY_REGISTERED_ACCOUNT, account).apply();
        }
    }

    /** Forgets the registered-account marker (after erasing all data, so the next
     *  backup re-creates the account). */
    public static void clearRegistered(SharedPreferences prefs) {
        synchronized (REGISTER_LOCK) {
            prefs.edit().remove(KEY_REGISTERED_ACCOUNT).apply();
        }
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
        netExecutor.execute(() -> {
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

    /**
     * Loads the metered quota/balance off the net executor and posts it to the
     * main thread (null when offline / not set up / no server account yet). Drives
     * the Cloud Backup status hero and the home status line. Deliberately does NOT
     * force registration (like {@link #loadUsage}): a signed GET against an
     * un-created account just fails and yields null, which reads as "not set up".
     */
    public void loadQuota(Consumer<StorageApiClient.Quota> onResult) {
        netExecutor.execute(() -> {
            StorageApiClient.Quota quota = null;
            byte[] code = new SyncSecrets(context).load();
            if (code != null && isSetUp()) {
                try {
                    SyncIdentity identity = SyncIdentity.fromCode(code);
                    StorageApiClient api = new StorageApiClient(httpClient, backendUrl());
                    quota = api.quota(identity);
                } catch (Exception e) {
                    quota = null; // offline / not yet on the server — keep it graceful
                } finally {
                    SyncSecrets.wipe(code);
                }
            } else {
                SyncSecrets.wipe(code);
            }
            final StorageApiClient.Quota out = quota;
            main.post(() -> onResult.accept(out));
        });
    }

    /** Combined status snapshot for the status hero + home line: reconciled
     *  set-up state, backed-up usage, and the metered quota, in one load. */
    public static final class Status {
        public final boolean setUp;   // reconciled (false if auto-cleared as empty)
        public final int fileCount;   // -1 = unavailable (offline / not loaded)
        public final long totalBytes; // -1 = unavailable
        public final StorageApiClient.Quota quota; // null = unavailable / not loaded

        Status(boolean setUp, int fileCount, long totalBytes, StorageApiClient.Quota quota) {
            this.setUp = setUp;
            this.fileCount = fileCount;
            this.totalBytes = totalBytes;
            this.quota = quota;
        }
    }

    /**
     * Loads usage (manifest) + quota together off the net executor and posts a
     * {@link Status} to the main thread. Centralizes a GUARDED auto-clear: when
     * BOTH loads succeed and reveal a genuinely dead account — metered, spent
     * (balance ≤ 0), and zero files backed up — Cloud Backup is no longer in use
     * (e.g. reaped server-side after runout), so the local {@code
     * CLOUD_BACKUP_ENABLED} flag is cleared and {@code setUp} comes back false so
     * the UI (status hero + home line) hides itself.
     *
     * <p>The guard is on a SUCCESSFUL response: an offline / transient failure
     * yields unknown values and leaves the flag untouched, so a network blip can
     * never wrongly retire Cloud Backup. An account with files, or with credit, or
     * in the unmetered phase (no balance concept) is never auto-cleared — a
     * grace-period user with files still keeps the "top up" prompt. The shared
     * recovery code is left in place (bookmark sync may still need it).
     */
    public void loadStatus(Consumer<Status> onResult) {
        netExecutor.execute(() -> {
            boolean setUp = isSetUp();
            int files = -1;
            long bytes = -1;
            StorageApiClient.Quota quota = null;
            byte[] code = new SyncSecrets(context).load();
            // Load whenever a CODE exists — not only when already marked set up.
            // The server is the ground truth: a FUNDED account (credit bought, no
            // file backed up yet) has a real balance the flag doesn't know about
            // (the flag was historically only set by the first successful backup,
            // which made a paid plan invisible everywhere — the status hero showed
            // "nothing backed up yet" with no balance, the files row hid, and a
            // bookmark-sync sign-out would even have WIPED the code of a paid
            // account, see SyncManager.disable). For a code that's genuinely
            // bookmarks-only the account isn't registered on storage, so the loads
            // fail and fall through to unknown — same outcome as before.
            if (code != null) {
                try {
                    SyncIdentity identity = SyncIdentity.fromCode(code);
                    StorageApiClient api = new StorageApiClient(httpClient, backendUrl());
                    VaultEngine engine = new VaultEngine(api, identity);
                    List<VaultEntry> entries = engine.loadManifest(); // must succeed
                    files = entries.size();
                    long total = 0;
                    for (VaultEntry e : entries) {
                        total += e.size;
                    }
                    bytes = total;
                    quota = api.quota(identity); // must succeed
                    // A completed purchase on THIS install leaves the plan shape in
                    // prefs (BuyCreditViewModel writes it at redeem success). It's
                    // the paid signal the SERVER can't give on an unmetered
                    // deployment — there quota.metered is false and the redeemed
                    // balance is never reported, so a funded-but-empty account
                    // would otherwise look identical to a never-paid one.
                    boolean hasLocalPlan = prefs.getInt(Preferences.CLOUD_PLAN_SIZE_GB, 0) > 0;
                    if (quota.metered && quota.balanceMicroGbMonths <= 0 && files == 0) {
                        prefs.edit().putBoolean(Preferences.CLOUD_BACKUP_ENABLED, false).apply();
                        setUp = false;
                    } else if (!setUp
                            && (files > 0
                            || (quota.metered && quota.balanceMicroGbMonths > 0)
                            || hasLocalPlan)) {
                        // The mirror of the auto-clear: the server reveals a LIVE
                        // account (files backed up, or a paid balance) the local
                        // flag missed — e.g. credit bought before markEnabled-at-
                        // purchase existed, or prefs lost while the server kept the
                        // data. Heal the flag so the status hero, home line,
                        // Downloads-overflow routing and the sign-out code-wipe
                        // guard all see the account. Same success-only guard as the
                        // clear: an offline blip can never wrongly flip it.
                        markEnabled();
                        setUp = true;
                    }
                } catch (Exception e) {
                    // Offline / transient — leave the flag alone, values unknown.
                    files = -1;
                    bytes = -1;
                    quota = null;
                } finally {
                    SyncSecrets.wipe(code);
                }
            } else {
                setUp = false;
            }
            final Status out = new Status(setUp, files, bytes, quota);
            main.post(() -> onResult.accept(out));
        });
    }

    /**
     * Loads the backed-up file list (manifest entries) off the disk executor and
     * posts it to the main thread. {@code onError} is invoked (main thread) on any
     * failure so the UI can show an error state rather than an empty list.
     */
    public void loadEntries(Consumer<List<VaultEntry>> onResult, Runnable onError) {
        netExecutor.execute(() -> {
            byte[] code = new SyncSecrets(context).load();
            if (code == null) {
                main.post(() -> onResult.accept(new ArrayList<>()));
                return;
            }
            try {
                SyncIdentity identity = SyncIdentity.fromCode(code);
                StorageApiClient api = new StorageApiClient(httpClient, backendUrl());
                VaultEngine engine = new VaultEngine(api, identity);
                List<VaultEntry> entries = engine.loadManifest();
                main.post(() -> onResult.accept(entries));
            } catch (Exception e) {
                // Was swallowed silently — on-device the Backups list ended on
                // "No backups yet" right after three successful uploads because
                // this pull failed (saturated uplink) with no trace anywhere.
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
     * Removes one backed-up file from the cloud (deletes the object and drops it
     * from the manifest) off the disk executor. {@code onResult} is posted to the
     * main thread.
     */
    public void deleteEntry(VaultEntry entry, Consumer<Boolean> onResult) {
        deleteEntries(Collections.singletonList(entry), onResult);
    }

    /**
     * Removes several backed-up files from the cloud in ONE manifest mutation
     * (then frees each object), off the net executor; {@code onResult} is posted to
     * the main thread. Batching avoids N concurrent OCC manifest mutations.
     */
    public void deleteEntries(List<VaultEntry> entries, Consumer<Boolean> onResult) {
        netExecutor.execute(() -> {
            boolean ok;
            byte[] code = new SyncSecrets(context).load();
            if (code == null) {
                ok = false;
            } else {
                try {
                    SyncIdentity identity = SyncIdentity.fromCode(code);
                    StorageApiClient api = new StorageApiClient(httpClient, backendUrl());
                    VaultEngine engine = new VaultEngine(api, identity);
                    engine.deleteEntries(entries);
                    ok = true;
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
     * Best-effort backfill of a missing preview for an entry backed up before
     * thumbnails existed: locates the local copy (name + size) and decodes a
     * preview from it on the heavy executor, posting the bitmap (or null if no
     * local copy / not previewable) to the main thread.
     *
     * <p>The decode goes through the Downloads list's OWN Glide pipeline first
     * ({@link GlideHelper#downloadThumbSync} — identical cache keys), so a file
     * ever rendered in Downloads is served from Glide's warm memory/disk cache
     * instead of re-extracting a video frame on every visit to this screen, and
     * a cold miss warms that cache for the list. {@link VaultThumbnail} stays as
     * the fallback for what that pipeline can't hand back as a bitmap (GIF/SVG
     * drawables, or a Glide decode failure where MediaMetadataRetriever still
     * succeeds). Display-only — the manifest is NOT written back (no re-upload,
     * no OCC churn); the preview persists once the file is backed up again.
     */
    public void resolveLocalThumb(VaultEntry entry, Consumer<Bitmap> onThumb) {
        heavyExecutor.execute(() -> {
            Bitmap thumb = null;
            try {
                DownloadEntity local = downloads.findByNameSize(entry.name, entry.size);
                if (local != null && local.getFilePath() != null) {
                    // No File.exists() gate: exists() is FALSE for a restored
                    // foreign-owned file that IS readable via the SAF grant —
                    // both decode paths resolve access themselves (the Glide
                    // DownloadEntity loaders and VaultThumbnail each try the
                    // direct path, then the grant) and yield null when neither
                    // works.
                    thumb = GlideHelper.downloadThumbSync(context, local, VaultThumbnail.MAX_DIM);
                    if (thumb == null) {
                        // Same exact frame the Downloads list renders for this
                        // file.
                        thumb = VaultThumbnail.generateBitmap(context, local.getFilePath(),
                                entry.mime, GlideHelper.thumbnailFrameUs(local));
                    }
                }
            } catch (Exception ignored) {
                // Best-effort — fall through to the mime glyph.
            }
            final Bitmap out = thumb;
            main.post(() -> onThumb.accept(out));
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
        netExecutor.execute(() -> {
            boolean ok;
            byte[] code = new SyncSecrets(context).load();
            if (code == null) {
                ok = true; // no key here — nothing server-side we can address
            } else {
                try {
                    SyncIdentity identity = SyncIdentity.fromCode(code);
                    StorageApiClient api = new StorageApiClient(httpClient, backendUrl());
                    ensureRegistered(prefs, api, identity); // makes the signed DELETE resolvable
                    api.deleteAccount(identity);
                    clearRegistered(prefs); // account gone — next backup re-registers
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
                    prefs.edit()
                            .putBoolean(Preferences.CLOUD_BACKUP_ENABLED, false)
                            // The account is gone; a stale plan shape would feed
                            // the status hero a runway for a balance that no
                            // longer exists.
                            .remove(Preferences.CLOUD_PLAN_SIZE_GB)
                            .remove(Preferences.CLOUD_PLAN_DURATION_MONTHS)
                            .apply();
                    if (!prefs.getBoolean(Preferences.SYNC_ENABLED, false)) {
                        new SyncSecrets(context).clear();
                    }
                }
                onResult.accept(success);
            });
        });
    }
}
