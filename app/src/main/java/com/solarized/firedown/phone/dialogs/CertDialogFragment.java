package com.solarized.firedown.phone.dialogs;

import android.app.Dialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.snackbar.Snackbar;
import com.solarized.firedown.R;
import com.solarized.firedown.Keys;
import com.solarized.firedown.data.entity.CertificateInfoEntity;

public class CertDialogFragment extends BaseBottomResizedDialogFragment {


    private static final String TAG = CertDialogFragment.class.getSimpleName();
    private CertificateInfoEntity mCertificateInfoEntity;



    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        Bundle args = getArguments();
        if (args == null)
            throw new IllegalStateException("Arguments required for " + TAG);

        mCertificateInfoEntity = args.getParcelable(Keys.ITEM_ID);
        if (mCertificateInfoEntity == null)
            throw new IllegalStateException("CertificateInfo not found in arguments");
    }



    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        mView = inflater.inflate(R.layout.fragment_dialog_cert, container, false);
        return mView;
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        bindHeader(view);
        bindSubject(view);
        bindIssuer(view);
        bindValidity(view);
        bindTechnical(view);
        bindFingerprints(view);
        bindSANs(view);
        bindActions(view);
    }

    // -----------------------------------------------------------------------
    //  Binding helpers
    // -----------------------------------------------------------------------

    private void bindHeader(@NonNull View root) {
        ImageView icon = root.findViewById(R.id.cert_status_icon);
        TextView title = root.findViewById(R.id.cert_status_title);
        TextView subtitle = root.findViewById(R.id.cert_status_subtitle);

        if (mCertificateInfoEntity.isSecure) {
            icon.setImageResource(R.drawable.encryption_24);
            title.setText(R.string.cert_connection_secure);
        } else {
            icon.setImageResource(R.drawable.no_encryption_24);
            title.setText(R.string.cert_connection_insecure);
        }

        if (mCertificateInfoEntity.isException) {
            title.setText(R.string.cert_connection_exception);
            title.setTextColor(ContextCompat.getColor(requireContext(), R.color.brand_orange));
        }

        // Subtitle: "CN · security mode"
        StringBuilder sub = new StringBuilder();
        if (!TextUtils.isEmpty(mCertificateInfoEntity.subjectCN)) sub.append(mCertificateInfoEntity.subjectCN);
        if (mCertificateInfoEntity.securityMode > 0) {
            if (sub.length() > 0) sub.append(" · ");
            sub.append(mCertificateInfoEntity.securityModeLabel());
        }
        subtitle.setText(sub.length() > 0 ? sub : mCertificateInfoEntity.host);
    }

    private void bindSubject(@NonNull View root) {
        setRow(root, R.id.row_subject_cn, R.string.cert_label_cn, mCertificateInfoEntity.subjectCN);
        setRow(root, R.id.row_subject_org, R.string.cert_label_org, mCertificateInfoEntity.subjectOrg);
        setRow(root, R.id.row_subject_ou, R.string.cert_label_ou, mCertificateInfoEntity.subjectOrgUnit);
        setRow(root, R.id.row_subject_country, R.string.cert_label_country, mCertificateInfoEntity.subjectCountry);
    }

    private void bindIssuer(@NonNull View root) {
        setRow(root, R.id.row_issuer_cn, R.string.cert_label_cn, mCertificateInfoEntity.issuerCN);
        setRow(root, R.id.row_issuer_org, R.string.cert_label_org, mCertificateInfoEntity.issuerOrg);
        setRow(root, R.id.row_issuer_country, R.string.cert_label_country, mCertificateInfoEntity.issuerCountry);
    }

    private void bindValidity(@NonNull View root) {
        setRow(root, R.id.row_valid_from, R.string.cert_label_valid_from,
                CertificateInfoEntity.formatDate(mCertificateInfoEntity.notBeforeMs));
        setRow(root, R.id.row_valid_to, R.string.cert_label_valid_to,
                CertificateInfoEntity.formatDate(mCertificateInfoEntity.notAfterMs));

        // Days remaining with color coding
        View daysRow = root.findViewById(R.id.row_days_remaining);
        long days = mCertificateInfoEntity.daysRemaining();
        String daysText;
        int daysColor;
        if (mCertificateInfoEntity.isExpired) {
            daysText = getString(R.string.cert_expired);
            daysColor = ContextCompat.getColor(requireContext(), R.color.brand_orange);
        } else if (mCertificateInfoEntity.isNotYetValid) {
            daysText = getString(R.string.cert_not_yet_valid);
            daysColor = ContextCompat.getColor(requireContext(), R.color.brand_orange);
        } else if (days <= 30) {
            daysText = getString(R.string.cert_days_remaining, days);
            daysColor = ContextCompat.getColor(requireContext(), R.color.brand_orange);
        } else {
            daysText = getString(R.string.cert_days_remaining, days);
            daysColor = ContextCompat.getColor(requireContext(), R.color.md_theme_onSurface);
        }
        setRow(daysRow, R.string.cert_label_status, daysText);
        TextView valueView = daysRow.findViewById(R.id.cert_row_value);
        if (valueView != null) valueView.setTextColor(daysColor);
    }

    private void bindTechnical(@NonNull View root) {
        setRow(root, R.id.row_version, R.string.cert_label_version,
                "v" + mCertificateInfoEntity.version);
        setRow(root, R.id.row_serial, R.string.cert_label_serial, mCertificateInfoEntity.serialNumber);
        setRow(root, R.id.row_sig_alg, R.string.cert_label_sig_algorithm, mCertificateInfoEntity.signatureAlgorithm);
        setRow(root, R.id.row_pub_key, R.string.cert_label_public_key, mCertificateInfoEntity.keyDescription());
        setRow(root, R.id.row_mixed_content, R.string.cert_label_mixed_content,
                mCertificateInfoEntity.mixedContentLabel());

        // Collapsible toggle
        setupCollapsible(root,
                R.id.section_technical_header,
                R.id.section_technical_content,
                R.id.section_technical_arrow);
    }

    private void bindFingerprints(@NonNull View root) {
        setRow(root, R.id.row_sha256, R.string.cert_label_sha256, mCertificateInfoEntity.sha256Fingerprint);
        setRow(root, R.id.row_sha1, R.string.cert_label_sha1, mCertificateInfoEntity.sha1Fingerprint);

        setupCollapsible(root,
                R.id.section_fingerprints_header,
                R.id.section_fingerprints_content,
                R.id.section_fingerprints_arrow);
    }

    private void bindSANs(@NonNull View root) {
        LinearLayout wrapper = root.findViewById(R.id.section_sans_wrapper);
        if (mCertificateInfoEntity.subjectAltNames.isEmpty()) {
            wrapper.setVisibility(View.GONE);
            return;
        }

        TextView title = root.findViewById(R.id.section_sans_title);
        title.setText(getString(R.string.cert_section_sans, mCertificateInfoEntity.subjectAltNames.size()));

        TextView sansList = root.findViewById(R.id.cert_sans_list);
        sansList.setText(TextUtils.join("\n", mCertificateInfoEntity.subjectAltNames));

        setupCollapsible(root,
                R.id.section_sans_header,
                R.id.cert_sans_list,
                R.id.section_sans_arrow);
    }

    private void bindActions(@NonNull View root) {
        MaterialButton copyBtn = root.findViewById(R.id.btn_copy_pem);
        if (TextUtils.isEmpty(mCertificateInfoEntity.pemEncoded)) {
            copyBtn.setVisibility(View.GONE);
            return;
        }
        copyBtn.setOnClickListener(v -> {
            ClipboardManager clipboard = (ClipboardManager)
                    requireContext().getSystemService(Context.CLIPBOARD_SERVICE);
            if (clipboard != null) {
                clipboard.setPrimaryClip(
                        ClipData.newPlainText("Certificate PEM", mCertificateInfoEntity.pemEncoded));
                Dialog dialog = getDialog();
                if(dialog == null)
                    return;
                Window window = getDialog().getWindow();
                Snackbar snackbar = Snackbar.make(window.getDecorView(), R.string.cert_copied, Snackbar.LENGTH_LONG);
                snackbar.show();
            }
        });
    }

    // -----------------------------------------------------------------------
    //  Row / section utility methods
    // -----------------------------------------------------------------------

    /**
     * Populate a cert_info_row include by its wrapper ID.
     * Hides the row entirely if value is null/empty.
     */
    private void setRow(@NonNull View root, int rowId, int labelResId, @Nullable String value) {
        View row = root.findViewById(rowId);
        setRow(row, labelResId, value);
    }

    private void setRow(@NonNull View row, int labelResId, @Nullable String value) {
        if (TextUtils.isEmpty(value)) {
            row.setVisibility(View.GONE);
            return;
        }
        row.setVisibility(View.VISIBLE);
        TextView label = row.findViewById(R.id.cert_row_label);
        TextView val = row.findViewById(R.id.cert_row_value);
        label.setText(labelResId);
        val.setText(value);
    }

    /**
     * Wire up a collapsible section: clicking the header toggles content visibility
     * and rotates the arrow indicator.
     */
    private void setupCollapsible(@NonNull View root,
                                  int headerId, int contentId, int arrowId) {
        View header = root.findViewById(headerId);
        View content = root.findViewById(contentId);
        ImageView arrow = root.findViewById(arrowId);

        header.setOnClickListener(v -> {
            boolean isVisible = content.getVisibility() == View.VISIBLE;
            content.setVisibility(isVisible ? View.GONE : View.VISIBLE);
            arrow.animate()
                    .rotation(isVisible ? 0f : 180f)
                    .setDuration(200)
                    .start();
        });
    }
}
