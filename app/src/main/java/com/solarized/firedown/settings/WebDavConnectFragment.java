package com.solarized.firedown.settings;

import android.content.Context;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;
import androidx.navigation.fragment.NavHostFragment;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.progressindicator.LinearProgressIndicator;
import com.google.android.material.snackbar.Snackbar;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;
import com.solarized.firedown.R;
import com.solarized.firedown.sync.CloudBackupManager;
import com.solarized.firedown.sync.ServerTrust;
import com.solarized.firedown.sync.WebDavCheck;
import com.solarized.firedown.sync.WebDavSettings;

import javax.inject.Inject;

import dagger.hilt.android.AndroidEntryPoint;
import okhttp3.HttpUrl;

/**
 * Backup → "Where to back up" → WebDAV server: sign in to a server that will
 * keep the backups (canvas boards 6 and 7). The check
 * ({@link CloudBackupManager#checkAndAdoptWebDav}) finds the WebDAV address
 * behind what was typed, signs in, and writes and deletes a test file in
 * {@code Firedown Backup}; on success the server is THE backup place and the
 * page leaves with the "Backup folder: <host>" snackbar the folder pick also
 * shows.
 *
 * <p>Each failure lands on the field it is about (see the layout's note);
 * a certificate the phone does not accept opens "Trust this server?" with
 * its fingerprint, and trusting it re-runs the check with that pin. A plain
 * http address is allowed behind a warning and "Connect anyway". Opened
 * again while a server is saved, the page is prefilled with its address and
 * user, and an empty password keeps the saved one (the manager also keeps a
 * certificate already trusted for the same server), so a reconnect after a
 * dropped network is one tap.
 */
@AndroidEntryPoint
public class WebDavConnectFragment extends Fragment {

    @Inject
    CloudBackupManager mCloudBackup;

    private WebDavConnectViewModel mViewModel;
    private TextInputLayout mAddressLayout;
    private TextInputLayout mUserLayout;
    private TextInputLayout mPasswordLayout;
    private TextInputEditText mAddress;
    private TextInputEditText mUser;
    private TextInputEditText mPassword;
    private MaterialButton mConnect;
    private LinearProgressIndicator mProgress;
    private TextView mCheckingNote;
    @Nullable
    private AlertDialog mTrustDialog;
    private boolean mChecking;
    /** The saved server's address and user, when one is saved: the form
     *  then may keep the saved password. */
    @Nullable
    private String mSavedUrl;
    @Nullable
    private String mSavedUser;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_webdav_connect, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        // Edge-to-edge activity: pad for the navigation bar AND the keyboard,
        // so Connect and the focused field are never drawn under either.
        ViewCompat.setOnApplyWindowInsetsListener(view, (v, windowInsets) -> {
            Insets insets = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars()
                    | WindowInsetsCompat.Type.displayCutout()
                    | WindowInsetsCompat.Type.ime());
            v.setPadding(insets.left, 0, insets.right, insets.bottom);
            return WindowInsetsCompat.CONSUMED;
        });

        mAddressLayout = view.findViewById(R.id.webdav_address_layout);
        mUserLayout = view.findViewById(R.id.webdav_user_layout);
        mPasswordLayout = view.findViewById(R.id.webdav_password_layout);
        mAddress = view.findViewById(R.id.webdav_address);
        mUser = view.findViewById(R.id.webdav_user);
        mPassword = view.findViewById(R.id.webdav_password);
        mConnect = view.findViewById(R.id.webdav_connect);
        mProgress = view.findViewById(R.id.webdav_progress);
        mCheckingNote = view.findViewById(R.id.webdav_checking_note);

        WebDavSettings saved = new WebDavSettings(requireContext());
        if (saved.isConfigured()) {
            mSavedUrl = saved.url();
            mSavedUser = saved.user();
            if (savedInstanceState == null) {
                mAddress.setText(mSavedUrl);
                mUser.setText(mSavedUser);
            }
        }

        mViewModel = new ViewModelProvider(this).get(WebDavConnectViewModel.class);

        mPassword.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_DONE && mConnect.isEnabled()) {
                connect(null);
                return true;
            }
            return false;
        });
        mConnect.setOnClickListener(v -> connect(null));

        mViewModel.state().observe(getViewLifecycleOwner(), this::render);
        refreshForm();
    }

    @Override
    public void onViewStateRestored(@Nullable Bundle savedInstanceState) {
        super.onViewStateRestored(savedInstanceState);
        // Watchers go on AFTER the fields got their text back: a restore is a
        // setText, and a watcher attached earlier would read a rotation as the
        // user editing a field and wipe the error shown under it.
        TextWatcher watcher = new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                mViewModel.clearError();
                refreshForm();
            }
        };
        mAddress.addTextChangedListener(watcher);
        mUser.addTextChangedListener(watcher);
        mPassword.addTextChangedListener(watcher);
        refreshForm();
    }

    @Override
    public void onDestroyView() {
        // Torn down with the view; the view model still holds the
        // certificate, so a recreated page asks again.
        if (mTrustDialog != null) {
            mTrustDialog.dismiss();
            mTrustDialog = null;
        }
        super.onDestroyView();
    }

    private void render(WebDavConnectViewModel.State state) {
        mChecking = state.checking;
        mProgress.setVisibility(state.checking ? View.VISIBLE : View.GONE);
        mCheckingNote.setVisibility(state.checking ? View.VISIBLE : View.GONE);
        mAddressLayout.setEnabled(!state.checking);
        mUserLayout.setEnabled(!state.checking);
        mPasswordLayout.setEnabled(!state.checking);
        showError(state.error, state.movedTo);
        refreshForm();
        if (state.trust != null && mTrustDialog == null) {
            showTrust(state.trust);
        }
        if (state.connected) {
            mViewModel.consumeConnected();
            leaveConnected();
        }
    }

    /** The helper lines, the button's label and whether it can be pressed,
     *  from what is typed now. */
    private void refreshForm() {
        String address = textOf(mAddress);
        HttpUrl url = WebDavCheck.normalize(address);
        boolean insecure = url != null && "http".equals(url.scheme());
        if (mAddressLayout.getError() == null) {
            mAddressLayout.setHelperText(getString(insecure
                    ? R.string.webdav_insecure : R.string.webdav_address_help));
        }
        boolean keep = keepsSavedPassword();
        if (mPasswordLayout.getError() == null) {
            mPasswordLayout.setHelperText(getString(keep && textOf(mPassword).isEmpty()
                    ? R.string.webdav_password_keep : R.string.webdav_password_help));
        }
        if (mChecking) {
            mConnect.setText(R.string.webdav_checking);
            mConnect.setEnabled(false);
            return;
        }
        mConnect.setText(insecure ? R.string.webdav_connect_anyway : R.string.webdav_connect);
        mConnect.setEnabled(!address.isEmpty() && !textOf(mUser).isEmpty()
                && (!textOf(mPassword).isEmpty() || keep));
    }

    /** A server is saved, and the form still names it and its user: an empty
     *  password then means the saved one. */
    private boolean keepsSavedPassword() {
        if (mSavedUrl == null || mSavedUser == null) {
            return false;
        }
        if (!mSavedUser.equals(textOf(mUser))) {
            return false;
        }
        return WebDavCheck.sameServer(WebDavCheck.normalize(textOf(mAddress)),
                HttpUrl.parse(mSavedUrl));
    }

    private void connect(@Nullable String pin) {
        if (mChecking) {
            return;
        }
        String password = textOf(mPassword);
        mViewModel.connect(textOf(mAddress), textOf(mUser),
                password.isEmpty() && keepsSavedPassword() ? null : password, pin);
    }

    /** The failure on the field it is about; null clears both. */
    private void showError(@Nullable WebDavCheck.Kind kind, @Nullable String movedTo) {
        mAddressLayout.setError(null);
        mPasswordLayout.setError(null);
        if (kind == null) {
            return;
        }
        switch (kind) {
            case AUTH_FAILED:
                mPasswordLayout.setError(getString(R.string.webdav_error_auth));
                break;
            case NOT_WEBDAV:
                mAddressLayout.setError(getString(R.string.webdav_error_not_webdav));
                break;
            case UNREACHABLE:
                mAddressLayout.setError(getString(R.string.webdav_error_unreachable, hostOf()));
                break;
            case READ_ONLY:
                mAddressLayout.setError(getString(R.string.webdav_error_read_only, textOf(mUser)));
                break;
            case FULL:
                mAddressLayout.setError(getString(R.string.webdav_error_full));
                break;
            case BAD_ADDRESS:
                mAddressLayout.setError(getString(R.string.webdav_error_bad_address));
                break;
            case MOVED:
                mAddressLayout.setError(getString(R.string.webdav_error_moved,
                        movedTo != null ? movedTo : ""));
                break;
            default:
                break;
        }
    }

    /** "Trust this server?": the host, the fingerprint in the colon form
     *  servers print, and what trusting it means later. Trust re-runs the
     *  check with the pin; Cancel (or Back) leaves the form as it was. */
    private void showTrust(@NonNull ServerTrust.CertInfo cert) {
        Context context = getContext();
        if (context == null) {
            return;
        }
        View content = LayoutInflater.from(context).inflate(R.layout.dialog_webdav_trust, null, false);
        TextView message = content.findViewById(R.id.webdav_trust_message);
        TextView fingerprint = content.findViewById(R.id.webdav_trust_fingerprint);
        message.setText(getString(R.string.webdav_trust_message, hostOf()));
        fingerprint.setText(wrapFingerprint(cert.fingerprintDisplay()));
        mTrustDialog = new MaterialAlertDialogBuilder(context)
                .setTitle(R.string.webdav_trust_title)
                .setView(content)
                .setNegativeButton(R.string.cancel, (dialog, which) -> mViewModel.dismissTrust())
                .setPositiveButton(R.string.webdav_trust_confirm, (dialog, which) -> {
                    mViewModel.dismissTrust();
                    connect(cert.sha256);
                })
                .setOnCancelListener(dialog -> mViewModel.dismissTrust())
                .create();
        mTrustDialog.setOnDismissListener(dialog -> mTrustDialog = null);
        mTrustDialog.show();
    }

    /** Twelve bytes a line, as the server's own pages tend to print it. */
    @NonNull
    static String wrapFingerprint(@NonNull String display) {
        String[] bytes = display.split(":");
        StringBuilder sb = new StringBuilder(display.length() + 4);
        for (int i = 0; i < bytes.length; i++) {
            if (i > 0) {
                sb.append(i % 12 == 0 ? "\n" : ":");
            }
            sb.append(bytes[i]);
        }
        return sb.toString();
    }

    /** Saved: back to the Backup screen, which re-reads the place on resume,
     *  with the same snackbar a picked folder gets. */
    private void leaveConnected() {
        View anchor = requireActivity().findViewById(android.R.id.content);
        String label = mCloudBackup.providerLabel();
        NavHostFragment.findNavController(this).popBackStack();
        if (anchor != null && !TextUtils.isEmpty(label)) {
            Snackbar.make(anchor, getString(R.string.folder_backup_folder_set, label),
                    Snackbar.LENGTH_LONG).show();
        }
    }

    /** The server's name as typed, for the messages that name it. */
    @NonNull
    private String hostOf() {
        String typed = textOf(mAddress);
        HttpUrl url = WebDavCheck.normalize(typed);
        return url != null ? url.host() : typed;
    }

    @NonNull
    private static String textOf(@Nullable TextInputEditText field) {
        if (field == null) {
            return "";
        }
        Editable text = field.getText();
        return text == null ? "" : text.toString().trim();
    }
}
