package com.solarized.firedown.sync;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;

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
     * Enables sync with a freshly generated recovery code. The grouped code is
     * delivered to {@code onCode} (main thread) for the "save this, no recovery"
     * screen; registration + first push happen in the worker.
     */
    public void enableWithNewCode(Consumer<String> onCode) {
        byte[] code = SyncIdentity.generateRecoveryCode();
        SyncSecrets secrets = new SyncSecrets(context);
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
     */
    public void linkWithCode(String enteredCode, Consumer<Boolean> onResult) {
        diskExecutor.execute(() -> {
            boolean ok;
            try {
                byte[] code = SyncIdentity.decodeRecoveryCode(enteredCode);
                SyncIdentity.fromCode(code); // validates shape
                new SyncSecrets(context).store(code);
                SyncSecrets.wipe(code);
                prefs.edit().putBoolean(Preferences.CLOUD_BACKUP_ENABLED, true).apply();
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
