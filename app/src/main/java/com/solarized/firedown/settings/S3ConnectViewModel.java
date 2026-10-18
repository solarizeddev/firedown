package com.solarized.firedown.settings;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.ViewModel;

import com.solarized.firedown.sync.CloudBackupManager;
import com.solarized.firedown.sync.S3Check;
import com.solarized.firedown.sync.S3Config;
import com.solarized.firedown.sync.ServerTrust;

import javax.inject.Inject;

import dagger.hilt.android.lifecycle.HiltViewModel;

/**
 * The S3 sign-in page's state, kept across a rotation (the WebDAV page's
 * view model, with the bucket's fields): whether a check is running, the
 * last check's failure (rendered on its field until the user edits one), a
 * certificate waiting for the user's trust (re-shown if the dialog was torn
 * down with the view), and a one-shot "connected". The check runs in
 * {@link CloudBackupManager}, which also saves the bucket on success, so a
 * check the user walked away from still lands.
 *
 * <p>It also holds the saved bucket's fields ({@link #saved()}, without the
 * secret): reading them unwraps the access key ID, a keystore call, so they
 * are loaded once off the main thread and survive a rotation here.
 */
@HiltViewModel
public class S3ConnectViewModel extends ViewModel {

    /** One snapshot of the page. */
    public static final class State {
        public final boolean checking;
        @Nullable
        public final S3Check.Kind error;
        @Nullable
        public final ServerTrust.CertInfo trust;
        public final boolean connected;

        State(boolean checking, @Nullable S3Check.Kind error,
              @Nullable ServerTrust.CertInfo trust, boolean connected) {
            this.checking = checking;
            this.error = error;
            this.trust = trust;
            this.connected = connected;
        }
    }

    /** The saved bucket's fields, or none; null until they are read. */
    public static final class Saved {
        @Nullable
        public final S3Config config;

        Saved(@Nullable S3Config config) {
            this.config = config;
        }
    }

    private final CloudBackupManager mBackup;
    private final MutableLiveData<State> mState =
            new MutableLiveData<>(new State(false, null, null, false));
    private final MutableLiveData<Saved> mSaved = new MutableLiveData<>();
    private boolean mSavedRequested;

    @Inject
    public S3ConnectViewModel(CloudBackupManager backup) {
        mBackup = backup;
    }

    @NonNull
    public LiveData<State> state() {
        return mState;
    }

    /** The saved bucket (its secret blanked), read once per view model. */
    @NonNull
    public LiveData<Saved> saved() {
        if (!mSavedRequested) {
            mSavedRequested = true;
            mBackup.loadS3Prefill(config -> mSaved.setValue(new Saved(config)));
        }
        return mSaved;
    }

    @NonNull
    private State current() {
        State s = mState.getValue();
        return s != null ? s : new State(false, null, null, false);
    }

    /**
     * Runs the check. {@code secret} null = keep the saved one (a
     * reconnect, same endpoint, bucket and key ID); {@code pin} = a
     * certificate the user just trusted, else null (the manager reuses a
     * pin already saved for the same endpoint).
     */
    public void connect(@NonNull String endpoint, @NonNull String region, @NonNull String bucket,
                        @NonNull String prefix, @NonNull String accessKeyId,
                        @Nullable String secret, @NonNull S3Config.Addressing addressing,
                        @Nullable String pin) {
        if (current().checking) {
            return;
        }
        mState.setValue(new State(true, null, null, false));
        mBackup.checkAndAdoptS3(endpoint, region, bucket, prefix, accessKeyId, secret,
                addressing, pin, result -> {
                    switch (result.kind) {
                        case OK:
                            mState.setValue(new State(false, null, null, true));
                            break;
                        case UNTRUSTED_CERT:
                            mState.setValue(new State(false, null, result.cert, false));
                            break;
                        default:
                            mState.setValue(new State(false, result.kind, null, false));
                            break;
                    }
                });
    }

    /** The trust prompt was answered (either way). */
    public void dismissTrust() {
        State s = current();
        if (s.trust != null) {
            mState.setValue(new State(s.checking, s.error, null, s.connected));
        }
    }

    /** The user edited a field: the last failure no longer describes it. */
    public void clearError() {
        State s = current();
        if (s.error != null) {
            mState.setValue(new State(s.checking, null, s.trust, s.connected));
        }
    }

    /** The page acted on "connected" (it left). */
    public void consumeConnected() {
        State s = current();
        if (s.connected) {
            mState.setValue(new State(s.checking, s.error, s.trust, false));
        }
    }
}
