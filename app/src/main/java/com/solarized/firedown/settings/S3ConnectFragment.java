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
import android.widget.ArrayAdapter;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat.AccessibilityActionCompat;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;
import androidx.navigation.fragment.NavHostFragment;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.progressindicator.LinearProgressIndicator;
import com.google.android.material.snackbar.Snackbar;
import com.google.android.material.textfield.MaterialAutoCompleteTextView;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;
import com.solarized.firedown.R;
import com.solarized.firedown.sync.CloudBackupManager;
import com.solarized.firedown.sync.S3Check;
import com.solarized.firedown.sync.S3Config;
import com.solarized.firedown.sync.ServerTrust;
import com.solarized.firedown.sync.WebDavCheck;

import javax.inject.Inject;

import dagger.hilt.android.AndroidEntryPoint;
import okhttp3.HttpUrl;

/**
 * Backup → "Where to back up" → S3 storage: a bucket on any S3-compatible
 * service (Backblaze B2, Cloudflare R2, Wasabi, MinIO…) that will keep the
 * backups. The WebDAV page's twin ({@link WebDavConnectFragment}): the
 * check ({@link CloudBackupManager#checkAndAdoptS3}) signs in, finds the
 * addressing style and the region when they are not given, and writes,
 * reads back and deletes a test object under the prefix; on success the
 * bucket is THE backup place and the page leaves with the snackbar a
 * picked folder gets.
 *
 * <p>Each failure lands on the field it is about (see the layout's note); a
 * certificate the phone does not accept (a home MinIO) opens the same
 * "Trust this server?" dialog as WebDAV, and trusting it re-runs the check
 * with that pin. A plain http endpoint is allowed behind a warning and
 * "Connect anyway". Opened again while a bucket is saved, the page is
 * prefilled from it, and an empty secret keeps the saved one as long as
 * the endpoint, bucket and access key ID still name it (the manager also
 * keeps a certificate already trusted for the same endpoint), so a
 * reconnect is one tap.
 */
@AndroidEntryPoint
public class S3ConnectFragment extends Fragment {

    private static final String STATE_MORE = "s3_more";
    private static final String STATE_ADDRESSING = "s3_addressing";
    /** The dropdown's rows, in the order of {@link #ADDRESSING_LABELS}. */
    private static final S3Config.Addressing[] ADDRESSING = {
            S3Config.Addressing.AUTO, S3Config.Addressing.PATH, S3Config.Addressing.VIRTUAL,
    };
    private static final int[] ADDRESSING_LABELS = {
            R.string.s3_addressing_auto, R.string.s3_addressing_path, R.string.s3_addressing_virtual,
    };

    @Inject
    CloudBackupManager mCloudBackup;

    private S3ConnectViewModel mViewModel;
    private TextInputLayout mEndpointLayout;
    private TextInputLayout mBucketLayout;
    private TextInputLayout mKeyIdLayout;
    private TextInputLayout mSecretLayout;
    private TextInputLayout mPrefixLayout;
    private TextInputLayout mRegionLayout;
    private TextInputLayout mAddressingLayout;
    private TextInputEditText mEndpoint;
    private TextInputEditText mBucket;
    private TextInputEditText mKeyId;
    private TextInputEditText mSecret;
    private TextInputEditText mPrefix;
    private TextInputEditText mRegion;
    private MaterialAutoCompleteTextView mAddressingField;
    private MaterialButton mMore;
    private View mMoreGroup;
    private MaterialButton mConnect;
    private LinearProgressIndicator mProgress;
    private TextView mCheckingNote;
    @Nullable
    private AlertDialog mTrustDialog;
    private boolean mChecking;
    private boolean mMoreShown;
    @NonNull
    private S3Config.Addressing mAddressing = S3Config.Addressing.AUTO;
    /** The fields hold what the user (or a restore) put there: the saved
     *  bucket must not be written over them. */
    private boolean mPrefillDone;
    /** The saved bucket (its secret blanked), when one is saved: the form
     *  then may keep the saved secret. */
    @Nullable
    private S3Config mSaved;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_s3_connect, container, false);
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

        mEndpointLayout = view.findViewById(R.id.s3_endpoint_layout);
        mBucketLayout = view.findViewById(R.id.s3_bucket_layout);
        mKeyIdLayout = view.findViewById(R.id.s3_key_id_layout);
        mSecretLayout = view.findViewById(R.id.s3_secret_layout);
        mPrefixLayout = view.findViewById(R.id.s3_prefix_layout);
        mRegionLayout = view.findViewById(R.id.s3_region_layout);
        mAddressingLayout = view.findViewById(R.id.s3_addressing_layout);
        mEndpoint = view.findViewById(R.id.s3_endpoint);
        mBucket = view.findViewById(R.id.s3_bucket);
        mKeyId = view.findViewById(R.id.s3_key_id);
        mSecret = view.findViewById(R.id.s3_secret);
        mPrefix = view.findViewById(R.id.s3_prefix);
        mRegion = view.findViewById(R.id.s3_region);
        mAddressingField = view.findViewById(R.id.s3_addressing);
        mMore = view.findViewById(R.id.s3_more);
        mMoreGroup = view.findViewById(R.id.s3_more_group);
        mConnect = view.findViewById(R.id.s3_connect);
        mProgress = view.findViewById(R.id.s3_progress);
        mCheckingNote = view.findViewById(R.id.s3_checking_note);

        if (savedInstanceState != null) {
            mPrefillDone = true;
            mMoreShown = savedInstanceState.getBoolean(STATE_MORE);
            mAddressing = S3Config.Addressing.of(savedInstanceState.getString(STATE_ADDRESSING));
        }
        applyMore();
        mMore.setOnClickListener(v -> showMore(!mMoreShown));

        mViewModel = new ViewModelProvider(this).get(S3ConnectViewModel.class);

        TextView.OnEditorActionListener done = (v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_DONE && mConnect.isEnabled()) {
                connect(null);
                return true;
            }
            return false;
        };
        mSecret.setOnEditorActionListener(done);
        mRegion.setOnEditorActionListener(done);
        mConnect.setOnClickListener(v -> connect(null));

        mViewModel.saved().observe(getViewLifecycleOwner(), saved -> {
            mSaved = saved.config;
            prefill();
            refreshForm();
        });
        mViewModel.state().observe(getViewLifecycleOwner(), this::render);
        refreshForm();
    }

    @Override
    public void onViewStateRestored(@Nullable Bundle savedInstanceState) {
        super.onViewStateRestored(savedInstanceState);
        // The dropdown's adapter goes on AFTER the restore: a restored text
        // is set with filtering, which would leave the list showing only the
        // row that matches it.
        String[] labels = new String[ADDRESSING_LABELS.length];
        for (int i = 0; i < labels.length; i++) {
            labels[i] = getString(ADDRESSING_LABELS[i]);
        }
        mAddressingField.setAdapter(new ArrayAdapter<>(requireContext(),
                android.R.layout.simple_list_item_1, labels));
        mAddressingField.setText(labels[indexOf(mAddressing)], false);
        mAddressingField.setOnItemClickListener((parent, v, position, id) -> {
            mAddressing = ADDRESSING[position];
            mViewModel.clearError();
        });

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
        for (TextInputEditText field : new TextInputEditText[] {
                mEndpoint, mBucket, mKeyId, mSecret, mPrefix, mRegion}) {
            field.addTextChangedListener(watcher);
        }
        refreshForm();
    }

    @Override
    public void onSaveInstanceState(@NonNull Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putBoolean(STATE_MORE, mMoreShown);
        outState.putString(STATE_ADDRESSING, mAddressing.value);
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

    /** The saved bucket into empty fields, once per page (a rotation keeps
     *  what the fields hold). The region is shown only when it is not the
     *  one the endpoint implies (one the check followed), and "More
     *  options" opens when any of its fields carries something. */
    private void prefill() {
        if (mPrefillDone) {
            return;
        }
        mPrefillDone = true;
        S3Config saved = mSaved;
        if (saved == null || !textOf(mEndpoint).isEmpty()) {
            return;
        }
        mEndpoint.setText(saved.endpoint);
        mBucket.setText(saved.bucket);
        mKeyId.setText(saved.accessKeyId);
        String prefix = saved.prefix;
        while (prefix.endsWith("/")) {
            prefix = prefix.substring(0, prefix.length() - 1);
        }
        mPrefix.setText(prefix);
        HttpUrl url = HttpUrl.parse(saved.endpoint);
        boolean guessed = url != null && S3Check.guessRegion(url.host()).equals(saved.region);
        mRegion.setText(guessed ? "" : saved.region);
        mAddressing = saved.addressing;
        mAddressingField.setText(getString(ADDRESSING_LABELS[indexOf(mAddressing)]), false);
        if (!prefix.isEmpty() || !guessed || mAddressing != S3Config.Addressing.AUTO) {
            showMore(true);
        }
    }

    private void showMore(boolean show) {
        if (show == mMoreShown) {
            return;
        }
        mMoreShown = show;
        applyMore();
    }

    /** The fold's fields, its chevron, and the expand / collapse action a
     *  screen reader offers on the button. */
    private void applyMore() {
        mMoreGroup.setVisibility(mMoreShown ? View.VISIBLE : View.GONE);
        mMore.setIconResource(mMoreShown
                ? R.drawable.keyboard_arrow_up_24 : R.drawable.keyboard_arrow_down_24);
        AccessibilityActionCompat offered = mMoreShown
                ? AccessibilityActionCompat.ACTION_COLLAPSE : AccessibilityActionCompat.ACTION_EXPAND;
        AccessibilityActionCompat other = mMoreShown
                ? AccessibilityActionCompat.ACTION_EXPAND : AccessibilityActionCompat.ACTION_COLLAPSE;
        ViewCompat.replaceAccessibilityAction(mMore, other, null, null);
        ViewCompat.replaceAccessibilityAction(mMore, offered, null, (v, arguments) -> {
            showMore(!mMoreShown);
            return true;
        });
    }

    private void render(S3ConnectViewModel.State state) {
        mChecking = state.checking;
        mProgress.setVisibility(state.checking ? View.VISIBLE : View.GONE);
        mCheckingNote.setVisibility(state.checking ? View.VISIBLE : View.GONE);
        for (View v : new View[] {mEndpointLayout, mBucketLayout, mKeyIdLayout, mSecretLayout,
                mPrefixLayout, mRegionLayout, mAddressingLayout, mMore}) {
            v.setEnabled(!state.checking);
        }
        showError(state.error);
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
        HttpUrl url = S3Check.normalizeEndpoint(textOf(mEndpoint));
        boolean insecure = url != null && "http".equals(url.scheme());
        if (mEndpointLayout.getError() == null) {
            mEndpointLayout.setHelperText(getString(insecure
                    ? R.string.s3_insecure : R.string.s3_endpoint_help));
        }
        boolean keep = keepsSavedSecret();
        if (mSecretLayout.getError() == null) {
            mSecretLayout.setHelperText(getString(keep && textOf(mSecret).isEmpty()
                    ? R.string.s3_secret_keep : R.string.s3_secret_help));
        }
        if (mChecking) {
            mConnect.setText(R.string.s3_checking);
            mConnect.setEnabled(false);
            return;
        }
        mConnect.setText(insecure ? R.string.webdav_connect_anyway : R.string.webdav_connect);
        mConnect.setEnabled(!textOf(mEndpoint).isEmpty() && !textOf(mBucket).isEmpty()
                && !textOf(mKeyId).isEmpty() && (!textOf(mSecret).isEmpty() || keep));
    }

    /** A bucket is saved, and the form still names its endpoint, the bucket
     *  and the access key ID: an empty secret then means the saved one (the
     *  rule {@link S3Check#runReusing} enforces again). */
    private boolean keepsSavedSecret() {
        S3Config saved = mSaved;
        if (saved == null) {
            return false;
        }
        if (!saved.bucket.equals(textOf(mBucket)) || !saved.accessKeyId.equals(textOf(mKeyId))) {
            return false;
        }
        return WebDavCheck.sameServer(S3Check.normalizeEndpoint(textOf(mEndpoint)),
                HttpUrl.parse(saved.endpoint));
    }

    private void connect(@Nullable String pin) {
        if (mChecking) {
            return;
        }
        String secret = textOf(mSecret);
        mViewModel.connect(textOf(mEndpoint), textOf(mRegion), textOf(mBucket), textOf(mPrefix),
                textOf(mKeyId), secret.isEmpty() && keepsSavedSecret() ? null : secret,
                mAddressing, pin);
    }

    /** The failure on the field it is about; null clears every field. */
    private void showError(@Nullable S3Check.Kind kind) {
        for (TextInputLayout layout : new TextInputLayout[] {
                mEndpointLayout, mBucketLayout, mKeyIdLayout, mSecretLayout, mRegionLayout}) {
            layout.setError(null);
        }
        if (kind == null) {
            return;
        }
        switch (kind) {
            case BAD_ADDRESS:
                mEndpointLayout.setError(getString(R.string.s3_error_bad_address));
                break;
            case NOT_S3:
                mEndpointLayout.setError(getString(R.string.s3_error_not_s3));
                break;
            case UNREACHABLE:
                mEndpointLayout.setError(getString(R.string.webdav_error_unreachable, hostOf()));
                break;
            case AUTH_FAILED:
                mSecretLayout.setError(getString(R.string.s3_error_auth));
                break;
            case NO_SUCH_BUCKET:
                mBucketLayout.setError(getString(R.string.s3_error_no_bucket));
                break;
            case ACCESS_DENIED:
                mKeyIdLayout.setError(getString(R.string.s3_error_denied, textOf(mBucket)));
                break;
            case WRONG_REGION:
                showMore(true);
                mRegionLayout.setError(getString(R.string.s3_error_region));
                break;
            case FULL:
                mBucketLayout.setError(getString(R.string.s3_error_full));
                break;
            default:
                break;
        }
    }

    /** "Trust this server?", the WebDAV page's dialog: the host, the
     *  fingerprint, and what trusting it means later. Trust re-runs the
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
        fingerprint.setText(WebDavConnectFragment.wrapFingerprint(cert.fingerprintDisplay()));
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

    /** The endpoint's host as typed, for the messages that name it. */
    @NonNull
    private String hostOf() {
        String typed = textOf(mEndpoint);
        HttpUrl url = S3Check.normalizeEndpoint(typed);
        return url != null ? url.host() : typed;
    }

    private static int indexOf(@NonNull S3Config.Addressing addressing) {
        for (int i = 0; i < ADDRESSING.length; i++) {
            if (ADDRESSING[i] == addressing) {
                return i;
            }
        }
        return 0;
    }

    @NonNull
    private static String textOf(@Nullable TextView field) {
        if (field == null) {
            return "";
        }
        CharSequence text = field.getText();
        return text == null ? "" : text.toString().trim();
    }
}
