package com.solarized.firedown.settings;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.ViewModel;

import com.solarized.firedown.sync.CloudBackupManager;
import com.solarized.firedown.sync.ServerTrust;
import com.solarized.firedown.sync.WebDavCheck;

import javax.inject.Inject;

import dagger.hilt.android.lifecycle.HiltViewModel;

/**
 * The WebDAV sign-in page's state, kept across a rotation: whether a check
 * is running, the last check's failure (rendered on its field until the
 * user edits one), a certificate waiting for the user's trust (re-shown if
 * the dialog was torn down with the view), and a one-shot "connected".
 * The check itself runs in {@link CloudBackupManager}, which also saves the
 * server on success, so a check the user walked away from still lands.
 */
@HiltViewModel
public class WebDavConnectViewModel extends ViewModel {

    /** One snapshot of the page. */
    public static final class State {
        public final boolean checking;
        @Nullable
        public final WebDavCheck.Kind error;
        @Nullable
        public final ServerTrust.CertInfo trust;
        public final boolean connected;

        State(boolean checking, @Nullable WebDavCheck.Kind error,
              @Nullable ServerTrust.CertInfo trust, boolean connected) {
            this.checking = checking;
            this.error = error;
            this.trust = trust;
            this.connected = connected;
        }
    }

    private final CloudBackupManager mBackup;
    private final MutableLiveData<State> mState =
            new MutableLiveData<>(new State(false, null, null, false));

    @Inject
    public WebDavConnectViewModel(CloudBackupManager backup) {
        mBackup = backup;
    }

    @NonNull
    public LiveData<State> state() {
        return mState;
    }

    @NonNull
    private State current() {
        State s = mState.getValue();
        return s != null ? s : new State(false, null, null, false);
    }

    /**
     * Runs the check. {@code password} null = keep the saved one (a
     * reconnect); {@code pin} = a certificate the user just trusted, else
     * null (the manager reuses a pin already saved for the same server).
     */
    public void connect(@NonNull String address, @NonNull String user,
                        @Nullable String password, @Nullable String pin) {
        if (current().checking) {
            return;
        }
        mState.setValue(new State(true, null, null, false));
        mBackup.checkAndAdoptWebDav(address, user, password, pin, result -> {
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
