package com.solarized.firedown.sync;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;

import androidx.lifecycle.LiveData;
import androidx.lifecycle.MediatorLiveData;
import androidx.work.WorkInfo;

import com.solarized.firedown.Preferences;
import com.solarized.firedown.data.di.Qualifiers;
import com.solarized.firedown.data.repository.WebBookmarkDataRepository;
import com.solarized.firedown.sync.crypto.SyncIdentity;

import java.util.List;
import java.util.Locale;
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

    public String backendUrl() {
        return prefs.getString(Preferences.SYNC_BACKEND_URL, Preferences.SYNC_DEFAULT_BACKEND);
    }

    /**
     * Sets the backend URL (hosted default or a BYO URL). A blank value resets to
     * the hosted default; a non-blank value is persisted ONLY if it's a valid
     * {@code https://host} URL (see {@link #isValidBackendUrl}). An invalid value
     * is rejected (the stored URL is left unchanged) — the UI validates first and
     * warns, this is the model-layer guardrail so garbage never reaches the wire.
     */
    public void setBackendUrl(String url) {
        String normalized = normalizeBackendUrl(url);
        if (TextUtils.isEmpty(normalized)) {
            prefs.edit().putString(Preferences.SYNC_BACKEND_URL, Preferences.SYNC_DEFAULT_BACKEND).apply();
            return;
        }
        if (isValidBackendUrl(normalized)) {
            prefs.edit().putString(Preferences.SYNC_BACKEND_URL, normalized).apply();
        }
        // else: invalid — keep the existing value (the UI surfaces the error).
    }

    /**
     * Canonicalises a user-entered backend URL to a bare {@code scheme://host[:port]}
     * base: trims, lowercases scheme+host, and drops any path/query/fragment and
     * trailing slash (the API client appends {@code /v1/...} itself, so a pasted
     * {@code https://host/v1/health} or a trailing slash would otherwise double up).
     * Returns "" for blank input (→ reset to default). A value the parser can't
     * split into scheme+host is returned trimmed-but-unchanged so {@link
     * #isValidBackendUrl} can reject it with a message.
     */
    public static String normalizeBackendUrl(String url) {
        if (url == null) {
            return "";
        }
        String trimmed = url.trim();
        if (trimmed.isEmpty()) {
            return "";
        }
        Uri uri = Uri.parse(trimmed);
        String scheme = uri.getScheme();
        String host = uri.getHost();
        if (scheme == null || host == null || host.isEmpty()) {
            return trimmed;
        }
        StringBuilder sb = new StringBuilder()
                .append(scheme.toLowerCase(Locale.ROOT))
                .append("://")
                .append(host.toLowerCase(Locale.ROOT));
        int port = uri.getPort();
        if (port > 0) {
            sb.append(':').append(port);
        }
        return sb.toString();
    }

    /** True when the active backend is Firedown's hosted default (not a BYO server). */
    public boolean isDefaultBackend() {
        return Preferences.SYNC_DEFAULT_BACKEND.equals(backendUrl());
    }

    /** The host of the active backend, for the settings summary (falls back to the raw URL). */
    public String backendHost() {
        String host = Uri.parse(backendUrl()).getHost();
        return TextUtils.isEmpty(host) ? backendUrl() : host;
    }

    /** Outcome of a {@link #testBackend} probe. */
    public enum BackendTest {
        /** Reachable AND answered with the Firedown {@code /v1/health} shape. */
        OK,
        /** Reachable, but the response wasn't a Firedown sync server. */
        NOT_FIREDOWN,
        /** Couldn't reach the host (DNS / TLS / timeout / network down). */
        UNREACHABLE
    }

    /** Result + (when OK) the server-reported version, delivered to the UI. */
    public static final class BackendTestResult {
        public final BackendTest status;
        public final String version;
        BackendTestResult(BackendTest status, String version) {
            this.status = status;
            this.version = version;
        }
    }

    /**
     * Probes the CURRENT backend's {@code /v1/health} on the disk executor and
     * posts the outcome to the main thread. Unauthenticated — works regardless of
     * sync setup state — so it's a pure "is this address a reachable Firedown
     * server" check, independent of the account/key flow.
     */
    public void testBackend(Consumer<BackendTestResult> onResult) {
        final String url = backendUrl();
        diskExecutor.execute(() -> {
            BackendTest status;
            String version = "";
            try {
                SyncApiClient api = new SyncApiClient(httpClient, url);
                SyncApiClient.Health health = api.health();
                if (health.firedown) {
                    status = BackendTest.OK;
                    version = health.version;
                } else {
                    status = BackendTest.NOT_FIREDOWN;
                }
            } catch (Exception e) {
                status = BackendTest.UNREACHABLE;
            }
            final BackendTestResult result = new BackendTestResult(status, version);
            main.post(() -> {
                if (onResult != null) {
                    onResult.accept(result);
                }
            });
        });
    }

    /**
     * A backend URL is acceptable only when it's an absolute {@code https://}
     * URL with a host. http/other schemes are rejected: the bookmark body is
     * E2E-encrypted, but the account id + Ed25519 auth headers must not cross the
     * wire in plaintext, and a stray scheme/typo would just fail every sync with
     * no useful diagnostic. This is the "oversight" on an otherwise free-text BYO
     * field — keep it strict.
     */
    public static boolean isValidBackendUrl(String url) {
        if (TextUtils.isEmpty(url)) {
            return false;
        }
        Uri uri = Uri.parse(url.trim());
        String host = uri.getHost();
        return "https".equalsIgnoreCase(uri.getScheme())
                && host != null && !host.isEmpty();
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
