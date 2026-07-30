package com.solarized.firedown.sync;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;

import androidx.annotation.Nullable;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MediatorLiveData;
import androidx.work.WorkInfo;

import com.solarized.firedown.Preferences;
import com.solarized.firedown.data.di.Qualifiers;
import com.solarized.firedown.data.repository.WebBookmarkDataRepository;
import com.solarized.firedown.sync.crypto.SyncIdentity;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.function.Consumer;

import javax.inject.Inject;
import javax.inject.Singleton;

import dagger.hilt.android.qualifiers.ApplicationContext;
import okhttp3.OkHttpClient;

/**
 * The bookmarks-sync facade the UI talks to: enable/disable, show/restore the
 * recovery code, choose the backend, trigger a sync, sign out. It owns the wiring
 * between the bookmark repository (tombstone-on-delete + change trigger) and the
 * WorkManager scheduler. Network work runs in the {@link SyncWorker}; the manager
 * only debounces change triggers and flips state.
 */
@Singleton
public class SyncManager {

    /** Sync states for the Bookmarks toolbar indicator. */
    public static final int STATE_OFF = 0;     // disabled — tap to set up
    public static final int STATE_SYNCED = 1;  // enabled, idle, last run ok
    public static final int STATE_SYNCING = 2; // a sync is running now
    public static final int STATE_ERROR = 3;   // last terminal run failed

    /** Debounce window so a burst of bookmark edits coalesces into one push. */
    private static final long DEBOUNCE_MILLIS = 8000;

    private final Context context;
    private final WebBookmarkDataRepository repo;
    private final SharedPreferences prefs;
    private final SyncScheduler scheduler;
    private final Executor diskExecutor;
    private final OkHttpClient httpClient;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Runnable debounced = this::syncNow;

    @Inject
    public SyncManager(@ApplicationContext Context context,
                       WebBookmarkDataRepository repo,
                       SharedPreferences prefs,
                       SyncScheduler scheduler,
                       @Qualifiers.DiskIO Executor diskExecutor,
                       OkHttpClient httpClient) {
        this.context = context;
        this.repo = repo;
        this.prefs = prefs;
        this.scheduler = scheduler;
        this.diskExecutor = diskExecutor;
        this.httpClient = httpClient;
    }

    /** Wires repository semantics + scheduling from persisted state. Call at boot. */
    public void init() {
        boolean enabled = isEnabled();
        repo.setSyncEnabled(enabled);
        if (enabled) {
            repo.setSyncChangeTrigger(this::onLocalChange);
            scheduler.schedulePeriodic();
        }
    }

    public boolean isEnabled() {
        return prefs.getBoolean(Preferences.SYNC_ENABLED, false);
    }

    /**
     * The sync backend base URL. Bookmark sync is a free, E2E-encrypted feature
     * pinned to Firedown's hosted server — there is intentionally NO user-facing
     * backend picker (the manual Netscape import/export is the escape hatch for
     * anyone who doesn't want to use it). A configurable / self-hosted backend is
     * reserved for the downloads vault, where pointing at your own object storage
     * is the point. Kept as a method (not an inlined constant) so the sync engine
     * has one source for the URL and so re-introducing configurability for the
     * vault later is a one-line change.
     */
    public String backendUrl() {
        return Preferences.SYNC_DEFAULT_BACKEND;
    }

    public long lastSyncedAt() {
        return prefs.getLong(Preferences.SYNC_LAST_SYNCED_AT, 0);
    }

    public long lastVersion() {
        return prefs.getLong(Preferences.SYNC_LAST_VERSION, 0);
    }

    /**
     * Enables sync reusing the EXISTING shared recovery code. The account is
     * shared with Cloud Backup (one code derives the same account on every
     * service), so a device that already holds a key — Cloud Backup set up
     * first, or a key created on the hub — must never be walked through the
     * create/restore setup again. Registration + first push happen in the
     * worker, same as the new-code path. Callers guard on {@link #hasCode()}.
     */
    public void enableWithExistingCode() {
        turnOn();
        syncNow();
    }

    /**
     * Enables sync with a freshly generated recovery code. The grouped code is
     * delivered to {@code onCode} (main thread) for the "save this, no recovery"
     * screen; registration + first push happen in the worker.
     *
     * <p>MONEY-LOSS BACKSTOP: this never mints over an existing shared code.
     * The code is the ONLY key to the shared account — for a user with a paid
     * Cloud Backup plan, overwriting it silently bricks the balance and every
     * encrypted backup (unless they saved the old code; this exact hole shipped
     * as the bookmarks toggle offering "start new" to an already-paid user).
     * Callers should route through {@link #enableWithExistingCode} when
     * {@link #hasCode()}; if one slips through, the existing code is reused and
     * delivered to {@code onCode} so the "save this" dialog shows the REAL key.
     */
    public void enableWithNewCode(Consumer<String> onCode) {
        SyncSecrets secrets = new SyncSecrets(context);
        byte[] existing = secrets.load();
        if (existing != null) {
            String grouped = SyncIdentity.grouped(SyncIdentity.encodeRecoveryCode(existing));
            SyncSecrets.wipe(existing);
            turnOn();
            if (onCode != null) {
                onCode.accept(grouped);
            }
            syncNow();
            return;
        }
        byte[] code = SyncIdentity.generateRecoveryCode();
        secrets.store(code);
        String grouped = SyncIdentity.grouped(SyncIdentity.encodeRecoveryCode(code));
        SyncSecrets.wipe(code);
        turnOn();
        if (onCode != null) {
            onCode.accept(grouped);
        }
        syncNow();
    }

    /**
     * Creates the shared account key on this device WITHOUT enabling any feature.
     * The key is the gateway: it must exist before bookmarks sync or cloud backup
     * can be turned on (the Cloud hub disables those rows until it does). Unlike
     * {@link #enableWithNewCode} this does NOT turn on bookmark sync, and unlike
     * {@link CloudBackupManager#createNewCode} it does NOT mark cloud backup in use
     * — creating a key commits the user to nothing but holding the key. The grouped
     * code is delivered to {@code onCode} (caller shows the "save this — it's the
     * only key" dialog with the mandatory saved-gate). No-op-safe if a key already
     * exists is the CALLER's responsibility (guard on {@link #hasCode()}); this
     * always overwrites, matching the other minting paths.
     */
    public void createRecoveryCode(Consumer<String> onCode) {
        byte[] code = SyncIdentity.generateRecoveryCode();
        new SyncSecrets(context).store(code);
        String grouped = SyncIdentity.grouped(SyncIdentity.encodeRecoveryCode(code));
        SyncSecrets.wipe(code);
        if (onCode != null) {
            onCode.accept(grouped);
        }
    }

    /** Whether a recovery code exists on this device (the account gateway). */
    public boolean hasCode() {
        return new SyncSecrets(context).hasCode();
    }

    /**
     * Enables sync by restoring from an existing recovery code (new device). The
     * code is validated by deriving the identity; {@code onResult} reports
     * success/failure on the main thread. On success a pull+merge runs.
     */
    public void restoreWithCode(String enteredCode, Consumer<Boolean> onResult) {
        diskExecutor.execute(() -> {
            boolean ok;
            try {
                byte[] code = SyncIdentity.decodeRecoveryCode(enteredCode);
                SyncIdentity.fromCode(code); // validates shape
                new SyncSecrets(context).store(code);
                SyncSecrets.wipe(code);
                ok = true;
            } catch (Exception e) {
                ok = false;
            }
            final boolean success = ok;
            main.post(() -> {
                if (success) {
                    turnOn();
                    syncNow();
                }
                if (onResult != null) {
                    onResult.accept(success);
                }
            });
        });
    }

    /**
     * Links this device to an existing account from its recovery code — the
     * account-level restore for a fresh install / new device. Stores the code
     * (which re-derives the shared account, so downloads backup + storage credit
     * come back) and marks Cloud Backup in use so its screen reflects the restored
     * data. Deliberately does NOT enable bookmark sync (that stays a separate
     * opt-in — unlike {@link #restoreWithCode}, the bookmark-sync path): the code
     * is simply now available for it too. {@code onResult} reports success/failure
     * on the main thread.
     *
     * <p><b>Every cached per-account value is dropped with the old code</b> — this
     * is what makes adopting a code safe when the device ALREADY had one (the
     * two-devices-two-codes case, where the second device created its own before
     * being pointed at the first's account). The stored code is the account, so
     * anything derived from the previous one is now about a DIFFERENT account:
     * {@code SYNC_LAST_VERSION} is the bookmark document's OCC version and would
     * make the next push fight the adopted account's document (or, worse, look
     * like a legitimate ancestor of it); the last-synced/last-error pair would
     * report the old account's sync as this one's; {@code CLOUD_PLAN_*} is the
     * local purchase shape behind the roadmap's offline step-② check-off; and
     * {@code CLOUD_LAST_TOTAL_BYTES} is the durable total the home resting line
     * paints before any network pull lands, so leaving it would show the OTHER
     * device's figure as this account's — confidently and offline. All of it is
     * re-derived from server truth on the next load. Nothing on the SERVER is
     * touched: the previous account's files and credit survive under their own
     * code, which is the only key to them (hence the warning the caller shows
     * before this runs). {@code ensureRegistered}'s marker needs no clearing —
     * it is keyed by {@code accountBase32()}, so a new account simply misses it.
     */
    public void linkWithCode(String enteredCode, Consumer<Boolean> onResult) {
        diskExecutor.execute(() -> {
            boolean ok;
            try {
                byte[] code = SyncIdentity.decodeRecoveryCode(enteredCode);
                SyncIdentity.fromCode(code); // validates shape
                new SyncSecrets(context).store(code);
                SyncSecrets.wipe(code);
                prefs.edit()
                        .putBoolean(Preferences.CLOUD_BACKUP_ENABLED, true)
                        .remove(Preferences.SYNC_LAST_VERSION)
                        .remove(Preferences.SYNC_LAST_SYNCED_AT)
                        .remove(Preferences.SYNC_LAST_ERROR)
                        .remove(Preferences.CLOUD_PLAN_SIZE_GB)
                        .remove(Preferences.CLOUD_PLAN_DURATION_MONTHS)
                        .remove(Preferences.CLOUD_LAST_TOTAL_BYTES)
                        .apply();
                ok = true;
            } catch (Exception e) {
                ok = false;
            }
            final boolean success = ok;
            main.post(() -> {
                if (onResult != null) {
                    onResult.accept(success);
                }
            });
        });
    }

    /**
     * True when {@code text} decodes as a recovery code — the SHAPE check the
     * QR-scan path needs before it offers a scanned payload to the user.
     *
     * <p>Cheap and local: {@code decodeRecoveryCode} is Crockford base32 plus a
     * length assertion, no IO and no key material, so this is safe on the main
     * thread (unlike {@link #linkWithCode}, which writes the keystore). A
     * recovery code carries no prefix or magic bytes, so "is this one?" can only
     * be answered by trying the decode — which is exactly why the scanner is
     * given no prefix to match and the CALLER validates instead.
     *
     * <p>Shape only, never authenticity: any 32 bytes of valid base32 pass. A
     * well-formed code for an account that does not exist is indistinguishable
     * here and surfaces later as an empty account, which is the same outcome as
     * typing one — hence the user still confirms before it is stored.
     */
    public boolean looksLikeRecoveryCode(@Nullable String text) {
        if (text == null || text.trim().isEmpty()) {
            return false;
        }
        try {
            byte[] code = SyncIdentity.decodeRecoveryCode(text.trim());
            SyncSecrets.wipe(code);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /** Disables sync and wipes the local keys (the public bookmarks stay). */
    public void disable() {
        prefs.edit().putBoolean(Preferences.SYNC_ENABLED, false).apply();
        repo.setSyncEnabled(false);
        repo.setSyncChangeTrigger(null);
        scheduler.cancel();
        // The recovery code is SHARED with Cloud Backup (one account across
        // services). Only wipe it when no other feature still needs it —
        // otherwise signing out of bookmark sync would silently lock the user
        // out of their cloud-backed downloads. Cloud Backup wipes it symmetrically
        // (gated on SYNC_ENABLED) in CloudBackupManager.deleteAllData.
        if (!prefs.getBoolean(Preferences.CLOUD_BACKUP_ENABLED, false)) {
            new SyncSecrets(context).clear();
        }
    }

    /**
     * Erases the encrypted document from the server (right-to-erasure), then —
     * only on success — tears down sync locally (disable + wipe key). Runs on the
     * disk executor; {@code onResult} is posted to the main thread.
     *
     * <p>On a network/server failure NOTHING local changes: the user keeps their
     * recovery key and can retry, so we never strand them "off locally but data
     * still on the server with no key left to delete it". register() is called
     * first (idempotent) so the signed DELETE authenticates even if this device
     * never pushed.
     */
    public void deleteServerData(Consumer<Boolean> onResult) {
        diskExecutor.execute(() -> {
            boolean ok;
            byte[] code = new SyncSecrets(context).load();
            if (code == null) {
                // No key here — nothing to delete server-side; just clean up locally.
                ok = true;
            } else {
                try {
                    SyncIdentity identity = SyncIdentity.fromCode(code);
                    SyncApiClient api = new SyncApiClient(httpClient, backendUrl());
                    api.register(identity); // idempotent — makes the signed DELETE resolvable
                    api.delete(identity);
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
                    disable(); // safe now: server data gone → wipe local key + turn off
                }
                if (onResult != null) {
                    onResult.accept(success);
                }
            });
        });
    }

    /** Returns the grouped recovery code for display, or null if not set up. */
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
     * Enqueues an immediate sync (e.g. "Sync now" / app foreground). Returns the
     * WorkManager request id so the caller can observe the result, or null when
     * sync is disabled (no work enqueued).
     */
    public UUID syncNow() {
        if (isEnabled()) {
            return scheduler.syncNow();
        }
        return null;
    }

    /**
     * Observable sync state for the Bookmarks toolbar indicator (one of the
     * {@code STATE_*} codes). Recomputed whenever the sync work changes — a
     * running job → SYNCING; otherwise OFF when disabled, ERROR when the last
     * terminal run failed, else SYNCED.
     */
    public LiveData<Integer> observeState() {
        MediatorLiveData<Integer> out = new MediatorLiveData<>();
        out.addSource(scheduler.observeWork(), infos -> out.setValue(computeState(infos)));
        return out;
    }

    private int computeState(List<WorkInfo> infos) {
        if (!isEnabled()) {
            return STATE_OFF;
        }
        if (infos != null) {
            for (WorkInfo wi : infos) {
                if (wi.getState() == WorkInfo.State.RUNNING) {
                    return STATE_SYNCING;
                }
            }
        }
        if (prefs.getBoolean(Preferences.SYNC_LAST_ERROR, false)) {
            return STATE_ERROR;
        }
        return STATE_SYNCED;
    }

    private void turnOn() {
        prefs.edit().putBoolean(Preferences.SYNC_ENABLED, true).apply();
        repo.setSyncEnabled(true);
        repo.setSyncChangeTrigger(this::onLocalChange);
        scheduler.schedulePeriodic();
    }

    private void onLocalChange() {
        // Coalesce a burst of edits into one push (debounced on the main thread).
        main.removeCallbacks(debounced);
        main.postDelayed(debounced, DEBOUNCE_MILLIS);
    }
}
