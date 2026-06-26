package com.solarized.firedown.sync;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.hilt.work.HiltWorker;
import androidx.work.Data;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

import com.solarized.firedown.BuildConfig;
import com.solarized.firedown.Preferences;
import com.solarized.firedown.data.repository.WebBookmarkDataRepository;
import com.solarized.firedown.sync.crypto.SyncIdentity;

import dagger.assisted.Assisted;
import dagger.assisted.AssistedInject;
import okhttp3.OkHttpClient;

/**
 * Runs one bookmark sync pass (mirrors {@code UpdateWorker}). Loads the recovery
 * code from {@link SyncSecrets}, derives the identity, and runs {@link SyncEngine}
 * against the configured backend. No bookmark/key/credential data is ever logged.
 */
@HiltWorker
public class SyncWorker extends Worker {

    private static final String TAG = "SyncWorker";

    /** Output-data keys read by SyncSettingsFragment to report a "Sync now" result. */
    public static final String KEY_STATUS = "status";
    public static final String KEY_COUNT = "count";
    public static final String STATUS_OK = "ok";
    public static final String STATUS_ERROR = "error";

    private final Context mContext;
    private final WebBookmarkDataRepository mRepo;
    private final SharedPreferences mPrefs;
    private final OkHttpClient mClient;

    @AssistedInject
    public SyncWorker(
            @Assisted @NonNull Context context,
            @Assisted @NonNull WorkerParameters params,
            WebBookmarkDataRepository repo,
            SharedPreferences prefs,
            OkHttpClient client) {
        super(context, params);
        this.mContext = context;
        this.mRepo = repo;
        this.mPrefs = prefs;
        this.mClient = client;
    }

    @NonNull
    @Override
    public Result doWork() {
        if (!mPrefs.getBoolean(Preferences.SYNC_ENABLED, false)) {
            return Result.success();
        }
        SyncSecrets secrets = new SyncSecrets(mContext);
        byte[] code = secrets.load();
        if (code == null) {
            // Enabled but no code — nothing to do (e.g. mid sign-out).
            return Result.success();
        }

        SyncIdentity identity;
        try {
            identity = SyncIdentity.fromCode(code);
        } finally {
            SyncSecrets.wipe(code);
        }

        // Bookmark sync is pinned to the hosted server (no BYO backend — see
        // SyncManager#backendUrl). A configurable backend is a vault concern.
        SyncApiClient api = new SyncApiClient(mClient, Preferences.SYNC_DEFAULT_BACKEND);
        SyncEngine.Result r = new SyncEngine(mRepo, api, identity).run();

        if (r.ok) {
            mPrefs.edit()
                    .putLong(Preferences.SYNC_LAST_SYNCED_AT, System.currentTimeMillis())
                    .putLong(Preferences.SYNC_LAST_VERSION, r.version)
                    .putBoolean(Preferences.SYNC_LAST_ERROR, false)
                    .apply();
            return Result.success(new Data.Builder()
                    .putString(KEY_STATUS, STATUS_OK)
                    .putInt(KEY_COUNT, r.count)
                    .build());
        }
        if (r.error != null && r.error.startsWith("transient")) {
            // Network / 429 / 503 / 5xx — let WorkManager retry with backoff. No
            // output data: a retry isn't a terminal "Sync now" result.
            return Result.retry();
        }
        // A permanent error (e.g. account-taken, bad-request). Don't spin; the
        // next change/periodic run retries. Detail is never logged on release.
        // Terminal SUCCEEDED (so it doesn't retry) but tagged error so the UI
        // reports a failure rather than a false "synced".
        if (BuildConfig.DEBUG) {
            Log.d(TAG, "sync did not complete (non-retryable)");
        }
        mPrefs.edit().putBoolean(Preferences.SYNC_LAST_ERROR, true).apply();
        return Result.success(new Data.Builder()
                .putString(KEY_STATUS, STATUS_ERROR)
                .build());
    }
}
