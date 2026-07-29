package com.solarized.firedown.sync;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import androidx.work.WorkManager;

import com.solarized.firedown.BuildConfig;
import com.solarized.firedown.data.RestoredFileAccess;

import com.bumptech.glide.Glide;
import com.bumptech.glide.load.engine.DiskCacheStrategy;
import com.solarized.firedown.GlideHelper;
import com.solarized.firedown.Preferences;
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
import java.util.HashSet;
import java.util.List;
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
     * Registers the storage account in the BACKGROUND, kicked right after the
     * recovery code is created or adopted on the Cloud screen. Registration is
     * where the server applies its one-time free STARTER CREDIT (metered mode),
     * so doing it at code creation — instead of waiting for the first backup's
     * {@link #ensureRegistered} — lands the grant before the user reaches the
     * download sheet, and the status hero's next quota load shows the granted
     * runway instead of pointing a funded-by-trial user at "Add storage credit".
     *
     * <p>Best-effort by design: offline just means the first backup registers
     * (and the server grants) then — {@code ensureRegistered} is once-per-install
     * and the server grant is once-per-account, so nothing double-applies.
     * Deliberately NOT folded into {@link #loadStatus}: that also runs for
     * bookmarks-ONLY codes, which must not get storage accounts minted for them
     * (their storage loads are supposed to fail — see the loadStatus comment).
     * Here the user is on the CLOUD screen creating/adopting a key, which is
     * cloud intent. {@code onDone} is posted to main either way.
     */
    public void registerInBackground(Runnable onDone) {
        netExecutor.execute(() -> {
            byte[] code = new SyncSecrets(context).load();
            if (code != null) {
                try {
                    SyncIdentity identity = SyncIdentity.fromCode(code);
                    StorageApiClient api = new StorageApiClient(httpClient, backendUrl());
                    ensureRegistered(prefs, api, identity);
                } catch (IOException | RuntimeException e) {
                    // Offline / transient — the first backup retries via
                    // ensureRegistered; the grant self-heals on that register.
                }
            }
            if (onDone != null) {
                main.post(onDone);
            }
        });
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
     * Last SUCCESSFUL {@link Status} snapshot, kept for the singleton's lifetime.
     * The status hero re-binds on every screen entry; without this it renders the
     * empty/unknown state for the network round-trip and visibly "jumps" once the
     * load lands. The screen paints this snapshot synchronously and the fresh
     * {@link #loadStatus} result then UPDATES it in place. Cleared by
     * {@link #deleteAllData} (the account is gone — a stale snapshot would show a
     * ghost balance).
     */
    private volatile Status mLastStatus;

    /** The last successful status snapshot, or null before the first load. */
    public Status lastStatus() {
        return mLastStatus;
    }

    /**
     * The last successfully-read total bytes, PERSISTED across process death, or
     * -1 when unknown. {@link #lastStatus()} only survives for the singleton's
     * lifetime, so on a cold start it is null and a caller that binds
     * cached-first has nothing to paint until the network answers; this is the
     * durable floor under it.
     *
     * <p>Deliberately narrower than {@code lastStatus()}: the quota is NOT
     * persisted. Quota drives the read-only grace ALARM, and an alarm restored
     * from disk could warn about deletion on an account that has since topped
     * up. Only the calm lifetime figure is cached.
     *
     * <p>Safe to read back only because it is cleared wherever the data can
     * vanish — see {@link #deleteAllData} and the dead-account reconcile in
     * {@link #loadStatus}. Callers must still gate on {@link #isSetUp()}.
     */
    public long lastKnownTotalBytes() {
        return prefs.getLong(Preferences.CLOUD_LAST_TOTAL_BYTES, -1);
    }

    /** Persists (or clears, with -1) the durable total. */
    private void storeTotalBytes(long bytes) {
        if (bytes < 0) {
            prefs.edit().remove(Preferences.CLOUD_LAST_TOTAL_BYTES).apply();
        } else {
            prefs.edit().putLong(Preferences.CLOUD_LAST_TOTAL_BYTES, bytes).apply();
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
                    // Hoisted so it is INDEPENDENT of the branch below. Inline in
                    // the else-if, the balance test is provably redundant and the
                    // IDE says so ("always true when reached"): reaching it means
                    // files == 0 and the first branch was false, which together
                    // imply balance > 0 whenever metered. But the redundancy is
                    // DEFENSIVE, not accidental — written as bare `quota.metered`
                    // the else-if would silently change meaning if the clear
                    // branch above were ever edited. Computing it up front keeps
                    // the self-contained form, keeps "a paid balance" readable at
                    // the point of use, and removes the implication the analyzer
                    // was reporting.
                    boolean liveAccount = files > 0
                            || (quota.metered && quota.balanceMicroGbMonths > 0)
                            || hasLocalPlan;
                    if (quota.metered && quota.balanceMicroGbMonths <= 0 && files == 0) {
                        prefs.edit().putBoolean(Preferences.CLOUD_BACKUP_ENABLED, false).apply();
                        setUp = false;
                        // The account is dead (reaped). Drop the durable total
                        // too: isSetUp() already gates the home pill off, but a
                        // stale figure left behind would come back the moment
                        // the flag healed.
                        storeTotalBytes(-1);
                    } else if (!setUp && liveAccount) {
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
            Status result = new Status(setUp, files, bytes, quota);
            if (files >= 0) {
                // Both loads succeeded — remember the snapshot so the next screen
                // entry paints instantly instead of blanking for the round-trip,
                // and mirror the total to prefs so the next COLD start does too
                // (the snapshot above dies with the process).
                mLastStatus = result;
                storeTotalBytes(bytes);
            } else if (code != null && mLastStatus != null) {
                // Offline/transient with a known-good earlier snapshot: serve the
                // snapshot rather than unknowns, so the hero never blanks out on a
                // network blip (the flag-reconcile above only ever runs on a
                // SUCCESSFUL load, so this can't mask an auto-clear/heal).
                result = mLastStatus;
            }
            final Status out = result;
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
                sortNewestFirst(entries);
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
     * Orders the Backups list <b>newest backup first</b> — the default the screen
     * shows, applied here at the one load choke point rather than in the fragment
     * so every consumer of {@link #loadEntries} gets the same order.
     *
     * <p>The manifest itself is append-ordered ({@code VaultEngine.addToManifest}
     * does {@code entries.add}), so before this the list was oldest-first and a
     * fresh backup landed at the BOTTOM — the opposite of what you want after
     * pressing "Back up to cloud".
     *
     * <p>Two-step because {@link VaultEntry#backedUpAt} is 0 on every entry
     * committed before that field existed:
     * <ol>
     *   <li><b>Reverse</b> the manifest order, which for legacy entries IS
     *       newest-first by construction (append order = backup order).</li>
     *   <li><b>Stable</b> sort by {@code backedUpAt} descending. Java's sort is
     *       stable, so entries sharing a key — i.e. all the legacy 0s — keep the
     *       reversed order from step 1, and timestamped entries sort above them.
     *       That is correct rather than merely convenient: a timestamp can only
     *       exist on an entry committed after this shipped, so every legacy entry
     *       genuinely IS older.</li>
     * </ol>
     *
     * <p>Deliberately NOT sorted on {@code downloadedAt}: that is the local
     * download's date, so a clip downloaded last year and backed up today would
     * sort to the bottom of the very list that just gained it.
     */
    static void sortNewestFirst(List<VaultEntry> entries) {
        Collections.reverse(entries);
        Collections.sort(entries, (a, b) -> Long.compare(b.backedUpAt, a.backedUpAt));
    }

    /**
     * The content key a download and its backed-up manifest entry share:
     * {@code name + NUL + size}. Matches {@link VaultEngine}'s own dedup key
     * (name + size), so the Downloads list can tell which of its rows are backed
     * up by a cheap set lookup.
     */
    public static String contentKey(String name, long size) {
        return (name == null ? "" : name) + ' ' + size;
    }

    /**
     * Loads the set of backed-up content keys ({@link #contentKey}) off the net
     * executor and posts it to the main thread, for the Downloads list's
     * "backed up to cloud" badge. Empty when not set up / offline / on any error
     * — a badge is shown ONLY for a key we positively saw, never a wrong
     * "not backed up". Cloud-backup users pay one manifest pull; a non-user
     * (no code / not set up) returns empty with NO network touch.
     */
    public void loadBackedUpKeys(Consumer<Set<String>> onResult) {
        netExecutor.execute(() -> {
            Set<String> keys = new HashSet<>();
            byte[] code = new SyncSecrets(context).load();
            if (code != null && isSetUp()) {
                try {
                    SyncIdentity identity = SyncIdentity.fromCode(code);
                    StorageApiClient api = new StorageApiClient(httpClient, backendUrl());
                    VaultEngine engine = new VaultEngine(api, identity);
                    for (VaultEntry e : engine.loadManifest()) {
                        keys.add(contentKey(e.name, e.size));
                    }
                } catch (Exception e) {
                    // Offline / transient — empty set (no badges), best-effort.
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
     * Checks — off the main thread — whether this backed-up file is ALREADY in
     * the Downloads folder, using the same name+size+exists test {@link
     * VaultRestoreWorker} uses to no-op a restore. Lets the Backups list decide
     * up-front between showing "already in your downloads" and actually enqueuing
     * a restore, instead of starting a worker only for it to immediately report
     * already-present (which flashed "Restore started" then "Already in your
     * downloads"). Runs on the concurrent net pool — a fast indexed DB read, kept
     * off the single heavy lane so it isn't queued behind a thumbnail decode, so
     * the decision (and its snackbar) lands promptly AFTER the item sheet has
     * dismissed. {@code onResult} is posted to the main thread.
     */
    public void isAlreadyDownloaded(VaultEntry entry, Consumer<Boolean> onResult) {
        netExecutor.execute(() -> {
            boolean present = false;
            try {
                DownloadEntity local = downloads.findByNameSize(entry.name, entry.size);
                present = local != null && local.getFilePath() != null
                        && new File(local.getFilePath()).exists();
            } catch (Exception ignored) {
                // Best-effort — on any error treat as not present and let the
                // worker's own check be the backstop.
            }
            final boolean out = present;
            main.post(() -> onResult.accept(out));
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
    /**
     * Resolves, in ONE background pass, which of these entries no longer have a
     * local copy — the set the Backups list marks "Not on this device".
     *
     * <p>Batched on purpose. The same {@code findByNameSize} lookup already
     * backs {@link #resolveLocalThumb} and the item sheet's Open row, but doing
     * it per BOUND ROW would re-query on every scroll and every selection tick;
     * this runs once per manifest load and hands the adapter a finished set.
     *
     * <p>"Local" here means a download row whose file is actually READABLE, not
     * merely a row that exists: a user who deleted the file from Downloads (or
     * whose restored file lost its grant) must read as cloud-only, because that
     * is exactly when removing the backup would lose the file for good. The
     * readability probe mirrors the existence rules the missing-file sweep uses
     * — a path that is null, or a File that neither exists nor resolves through
     * the SAF grant, counts as gone.
     *
     * <p>Result is posted to the main thread. Entries are keyed by objectId.
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
                    // A lookup failure must not CLAIM the file is gone — that
                    // would tell the user their only copy is in the cloud on the
                    // strength of a DB hiccup. Leave it unmarked.
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
     *  foreign-owned file. {@code openableUri} already covers both and yields
     *  null when neither works, so this needs no separate exists() probe (and
     *  must not use one: exists() is false for a readable foreign-owned file,
     *  which would mark a perfectly present file "Not on this device"). */
    private boolean hasReadableFile(DownloadEntity local) {
        return RestoredFileAccess.openableUri(context, local.getFilePath()) != null;
    }

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
                if (thumb == null && FileUriHelper.isImage(entry.mime)) {
                    // No local copy (deleted, or never on this install) and no
                    // stored manifest thumb — so the row would fall to the mime
                    // glyph. Decode the preview straight from the CLOUD object via
                    // the vault Glide ModelLoader (decrypt-on-read). Gated to
                    // images: a video frame can't be pulled from an encrypted
                    // media stream without a temp-file restore, and modern video
                    // backups already carry a manifest thumb. Glide caches the
                    // decoded result by objectId, so this is one fetch, not one
                    // per list load.
                    try {
                        thumb = Glide.with(context).asBitmap()
                                .load(VaultObjectModel.of(entry))
                                // Don't persist decrypted vault bytes to disk cache
                                // (the file must stay encrypted at rest); this decodes
                                // a small preview in memory only.
                                .diskCacheStrategy(DiskCacheStrategy.NONE)
                                .submit(VaultThumbnail.MAX_DIM, VaultThumbnail.MAX_DIM)
                                .get();
                    } catch (Exception ignored) {
                        // Offline / decode failure — fall through to the mime glyph.
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
        // Cancel every in-flight/queued transfer FIRST (backups AND restores —
        // both carry WORK_TAG). Without this, a RUNNING upload kept feeding the
        // presigned URLs and then failed its complete (row already deleted),
        // surfacing a spurious "couldn't back up" error and orphaning its
        // just-uploaded chunks; worse, a QUEUED backup ran AFTER the wipe,
        // re-registered, and quietly re-created a manifest — resurrecting cloud
        // data seconds after the user erased everything. Cancelling on the
        // failure path too is fine: the user asked for erasure, not uploads.
        // (A chunk PUT completing inside the cancel-propagation window can
        // still orphan bytes in R2 — same accepted best-effort class as the
        // server's detached chunk free.)
        WorkManager.getInstance(context).cancelAllWorkByTag(WORK_TAG);
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
                    // "Delete backed-up files" is SCOPED: the server erases
                    // objects + manifest but KEEPS the quota row, so a paid
                    // GB-month balance survives. Deliberately do NOT wipe the
                    // plan prefs or the recovery code here — the old full-wipe
                    // cleanup (flag off, plan removed, code cleared when
                    // bookmarks were off) STRANDED that surviving balance: the
                    // code is the only key to it. The loadStatus reconcile owns
                    // the flag from the server truth (a metered spent+empty
                    // account still auto-retires; a funded one stays visible).
                    mLastStatus = null; // usage changed — drop the stale snapshot
                    // …and the durable mirror of it. This clear is what makes
                    // lastKnownTotalBytes() safe to read back: the erase
                    // deliberately KEEPS CLOUD_BACKUP_ENABLED (the surviving paid
                    // balance is reachable only via the recovery code), so
                    // isSetUp() stays true and a persisted total would otherwise
                    // render a confident, wrong figure on the next cold start.
                    storeTotalBytes(-1);
                }
                onResult.accept(success);
            });
        });
    }
}
