package com.solarized.firedown.sync;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;

import com.solarized.firedown.Preferences;
import com.solarized.firedown.data.di.Qualifiers;
import com.solarized.firedown.data.repository.WebBookmarkDataRepository;
import com.solarized.firedown.sync.crypto.SyncIdentity;

import java.util.concurrent.Executor;
import java.util.function.Consumer;

import javax.inject.Inject;
import javax.inject.Singleton;

import dagger.hilt.android.qualifiers.ApplicationContext;

/**
 * The bookmarks-sync facade the UI talks to: enable/disable, show/restore the
 * recovery code, choose the backend, trigger a sync, sign out. It owns the wiring
 * between the bookmark repository (tombstone-on-delete + change trigger) and the
 * WorkManager scheduler. Network work runs in the {@link SyncWorker}; the manager
 * only debounces change triggers and flips state.
 */
@Singleton
public class SyncManager {

    /** Debounce window so a burst of bookmark edits coalesces into one push. */
    private static final long DEBOUNCE_MILLIS = 8000;

    private final Context context;
    private final WebBookmarkDataRepository repo;
    private final SharedPreferences prefs;
    private final SyncScheduler scheduler;
    private final Executor diskExecutor;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Runnable debounced = this::syncNow;

    @Inject
    public SyncManager(@ApplicationContext Context context,
                       WebBookmarkDataRepository repo,
                       SharedPreferences prefs,
                       SyncScheduler scheduler,
                       @Qualifiers.DiskIO Executor diskExecutor) {
        this.context = context;
        this.repo = repo;
        this.prefs = prefs;
        this.scheduler = scheduler;
        this.diskExecutor = diskExecutor;
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

    public String backendUrl() {
        return prefs.getString(Preferences.SYNC_BACKEND_URL, Preferences.SYNC_DEFAULT_BACKEND);
    }

    /** Sets the backend URL (hosted default or a BYO URL). Falls back to default if blank. */
    public void setBackendUrl(String url) {
        String value = TextUtils.isEmpty(url) ? Preferences.SYNC_DEFAULT_BACKEND : url.trim();
        prefs.edit().putString(Preferences.SYNC_BACKEND_URL, value).apply();
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

    /** Disables sync and wipes the local keys (the public bookmarks stay). */
    public void disable() {
        prefs.edit().putBoolean(Preferences.SYNC_ENABLED, false).apply();
        repo.setSyncEnabled(false);
        repo.setSyncChangeTrigger(null);
        scheduler.cancel();
        new SyncSecrets(context).clear();
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

    /** Enqueues an immediate sync (e.g. "Sync now" / app foreground). */
    public void syncNow() {
        if (isEnabled()) {
            scheduler.syncNow();
        }
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
