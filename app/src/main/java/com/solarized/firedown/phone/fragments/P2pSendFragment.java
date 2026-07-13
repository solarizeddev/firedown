package com.solarized.firedown.phone.fragments;

import android.os.Bundle;
import android.text.format.Formatter;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.bumptech.glide.request.RequestOptions;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.imageview.ShapeableImageView;
import com.google.android.material.progressindicator.LinearProgressIndicator;
import com.solarized.firedown.GlideHelper;
import com.solarized.firedown.Keys;
import com.solarized.firedown.R;
import com.solarized.firedown.StoragePaths;
import com.solarized.firedown.data.RestoredFileAccess;
import com.solarized.firedown.data.entity.DownloadEntity;
import com.solarized.firedown.p2pshare.P2pShareController;
import com.solarized.firedown.utils.FileUriHelper;
import com.solarized.firedown.utils.FragmentArgs;

import java.io.File;
import java.util.Locale;

import dagger.hilt.android.AndroidEntryPoint;

/**
 * "Send directly" — the sender side of the P2P share. The engine creates a
 * WebRTC offer whose compressed form is shown as a QR (or sent through any
 * messenger via the share sheet); the receiver's reply comes back the same
 * two ways; then the file streams device-to-device over the DataChannel.
 *
 * <p>Screen walk: CODE (offer QR + share/scan-reply/paste-reply) →
 * CONNECTING (status chip) → TRANSFER (progress card) → DONE / ERROR. The
 * engine session dies with the view (P2pShareBaseFragment).
 *
 * <p>Reached from the Downloads options sheet's quick-action row, which only
 * exists for finished, non-safe entries — the vault can never get here (and
 * a defensive check below enforces that).
 */
@AndroidEntryPoint
public class P2pSendFragment extends P2pShareBaseFragment
        implements P2pShareController.Listener {

    private DownloadEntity mDownloadEntity;
    private View mView;
    private String mOfferCode;
    private long mTotalBytes;

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        mDownloadEntity = FragmentArgs.parcelable(this, Keys.ITEM_ID, DownloadEntity.class);
        if (mDownloadEntity == null && mNavController != null) {
            mNavController.popBackStack();
        }
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {

        // Defensive: vault entries must never be shareable, and a restored
        // screen without its entity has nothing to share.
        if (mDownloadEntity == null || mDownloadEntity.isFileSafe()
                || mDownloadEntity.getFilePath() == null) {
            return null;
        }
        if (RestoredFileAccess.openableUri(requireContext(), mDownloadEntity.getFilePath()) == null) {
            return null;
        }

        mView = inflater.inflate(R.layout.fragment_p2p_send, container, false);
        mToolbar = mView.findViewById(R.id.toolbar);

        File file = new File(mDownloadEntity.getFilePath());
        String mime = FileUriHelper.getMimeTypeFromFile(file.getName());

        ShapeableImageView thumb = mView.findViewById(R.id.p2p_thumb);
        GlideHelper.load(mDownloadEntity, new RequestOptions(), thumb);
        ((TextView) mView.findViewById(R.id.p2p_file_name)).setText(file.getName());
        String size = StoragePaths.convertToStringRepresentation(
                RestoredFileAccess.length(requireContext(), mDownloadEntity.getFilePath()));
        String mimeWord = FileUriHelper.getLongMimeText(requireContext(), mime);
        ((TextView) mView.findViewById(R.id.p2p_file_tag)).setText(
                mimeWord != null
                        ? String.format(Locale.ROOT, "%s · %s", mimeWord, size)
                        : size);

        mView.findViewById(R.id.p2p_share_code).setOnClickListener(v -> {
            if (mOfferCode != null) {
                shareCode(mOfferCode);
            }
        });
        mView.findViewById(R.id.p2p_scan_reply).setOnClickListener(v ->
                navigateToScanner(P2pShareController.ANSWER_PREFIX));
        mView.findViewById(R.id.p2p_paste_reply).setOnClickListener(v -> {
            String code = readCodeFromClipboard(P2pShareController.ANSWER_PREFIX);
            if (code != null) {
                mP2pController.provideAnswer(code);
            }
        });
        mView.findViewById(R.id.p2p_stop).setOnClickListener(v -> close());

        return mView;
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        // Start the session AFTER the base flipped the WebRTC pref on — the
        // engine's ensure/reload handshake needs the pref already enabled.
        mP2pController.startSend(mDownloadEntity, this);
    }

    @Override
    protected int destinationId() {
        return R.id.p2p_send;
    }

    @Override
    protected void onCodeScanned(@NonNull String code) {
        mP2pController.provideAnswer(code);
    }

    /* ── engine events (main thread) ────────────────────────────────────── */

    @Override
    public void onCode(@NonNull String role, @NonNull String code) {
        if (mView == null || !"offer".equals(role)) {
            return;
        }
        mOfferCode = code;
        setQr(mView.findViewById(R.id.p2p_qr), code);
    }

    @Override
    public void onOfferParsed(@NonNull String name, long size, @NonNull String mime, @NonNull String device) {
        // Sender never receives this.
    }

    @Override
    public void onConnectionState(@NonNull String state) {
        if (mView == null) {
            return;
        }
        if ("connecting".equals(state) || "connected".equals(state)) {
            mView.findViewById(R.id.p2p_code_group).setVisibility(View.GONE);
            TextView status = mView.findViewById(R.id.p2p_status);
            status.setVisibility(View.VISIBLE);
            status.setText("connected".equals(state)
                    ? R.string.p2p_state_connected : R.string.p2p_state_connecting);
        }
    }

    @Override
    public void onProgress(long done, long total, long rate) {
        if (mView == null) {
            return;
        }
        mTotalBytes = total;
        mView.findViewById(R.id.p2p_progress_group).setVisibility(View.VISIBLE);
        LinearProgressIndicator bar = mView.findViewById(R.id.p2p_progress_bar);
        if (total > 0) {
            bar.setProgress((int) (done * 100 / total));
        }
        ((TextView) mView.findViewById(R.id.p2p_progress_text)).setText(
                getString(R.string.p2p_progress_of,
                        Formatter.formatShortFileSize(requireContext(), done),
                        Formatter.formatShortFileSize(requireContext(), total),
                        Formatter.formatShortFileSize(requireContext(), rate)));
    }

    @Override
    public void onDone(@NonNull String role, long bytes) {
        if (mView == null) {
            return;
        }
        mView.findViewById(R.id.p2p_status).setVisibility(View.GONE);
        mView.findViewById(R.id.p2p_progress_group).setVisibility(View.GONE);
        mView.findViewById(R.id.p2p_done_group).setVisibility(View.VISIBLE);
        mView.findViewById(R.id.p2p_live_dot).setVisibility(View.GONE);
        ((TextView) mView.findViewById(R.id.p2p_done_text)).setText(
                getString(R.string.p2p_done_sent_detail,
                        Formatter.formatShortFileSize(requireContext(), bytes)));
        ((MaterialButton) mView.findViewById(R.id.p2p_stop)).setText(R.string.p2p_done);
    }

    @Override
    public void onError(@NonNull String code, @NonNull String detail) {
        if (mView == null) {
            return;
        }
        if ("bad-code".equals(code)) {
            // Soft: the offer QR is still valid, the user just re-scans.
            makeSnack(R.string.p2p_error_bad_code);
            return;
        }
        mView.findViewById(R.id.p2p_code_group).setVisibility(View.GONE);
        mView.findViewById(R.id.p2p_status).setVisibility(View.GONE);
        mView.findViewById(R.id.p2p_progress_group).setVisibility(View.GONE);
        mView.findViewById(R.id.p2p_live_dot).setVisibility(View.GONE);
        TextView error = mView.findViewById(R.id.p2p_error);
        error.setVisibility(View.VISIBLE);
        error.setText(errorText(code));
    }
}
