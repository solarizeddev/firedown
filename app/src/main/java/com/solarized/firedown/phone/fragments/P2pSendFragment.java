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
 * <p>Screen walk: PREPARING (spinner) → CODE (offer QR + step-2 "scan reply")
 * → CONNECTING → TRANSFER → DONE / ERROR (with "Try again"). The engine
 * session dies with the view (P2pShareBaseFragment).
 */
@AndroidEntryPoint
public class P2pSendFragment extends P2pShareBaseFragment
        implements P2pShareController.Listener {

    private DownloadEntity mDownloadEntity;
    private View mView;
    private String mOfferCode;

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

        // Defensive: vault entries must never be shareable.
        if (mDownloadEntity == null || mDownloadEntity.isFileSafe()
                || mDownloadEntity.getFilePath() == null) {
            if (mNavController != null) {
                mNavController.popBackStack();
            }
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
            // Prefer the controller's share content: the https relay LINK when
            // a relay is configured and the offer is up (works across networks,
            // the receiver just taps it), otherwise the self-contained offer
            // deep link. Either way, opening it in Firedown lands on the receive
            // preview. Falls back to the offer deep link before the upload lands.
            String content = mP2pController.getShareContent();
            if (content == null && mOfferCode != null) {
                content = P2pShareController.toDeepLink(mOfferCode);
            }
            if (content != null) {
                shareCode(content);
            }
        });
        mView.findViewById(R.id.p2p_scan_reply).setOnClickListener(v ->
                navigateToScanner(P2pShareController.ANSWER_PREFIX, true));
        mView.findViewById(R.id.p2p_paste_reply).setOnClickListener(v -> {
            String code = readCodeFromClipboard(P2pShareController.ANSWER_PREFIX);
            if (code != null) {
                mP2pController.provideAnswer(code);
            }
        });
        // Mid-transfer this is "Cancel" (confirmed, like back); on done, "Done".
        mView.findViewById(R.id.p2p_stop).setOnClickListener(v -> confirmThenClose());
        mView.findViewById(R.id.p2p_retry).setOnClickListener(v -> restart());

        return mView;
    }

    @Override
    protected void onEngineReady() {
        // Start AFTER the base applied the WebRTC pref (the ordering that fixes
        // the pref-gated-global race).
        showPreparing();
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

    private void restart() {
        mOfferCode = null;
        showPreparing();
        mP2pController.startSend(mDownloadEntity, this);
    }

    /* ── stage visibility ───────────────────────────────────────────────── */

    private void showPreparing() {
        setStage(R.id.p2p_status);
        TextView status = mView.findViewById(R.id.p2p_status);
        status.setText(R.string.p2p_preparing);
        mView.findViewById(R.id.p2p_stop).setVisibility(View.GONE);
    }

    private void setStage(int visibleId) {
        int[] stages = {R.id.p2p_status, R.id.p2p_code_group, R.id.p2p_progress_group,
                R.id.p2p_done_group, R.id.p2p_error_group};
        for (int id : stages) {
            View v = mView.findViewById(id);
            v.setVisibility(id == visibleId ? View.VISIBLE : View.GONE);
        }
    }

    /* ── engine events (main thread) ────────────────────────────────────── */

    @Override
    public void onCode(@NonNull String role, @NonNull String code) {
        if (mView == null || !"offer".equals(role)) {
            return;
        }
        mOfferCode = code;
        setStage(R.id.p2p_code_group);
        // Encode the deep link (not the bare code) so a scan with the phone's
        // own camera offers "open in Firedown"; the in-app scanner unwraps it.
        boolean rendered = setQr(mView.findViewById(R.id.p2p_qr),
                P2pShareController.toDeepLink(code));
        // If the code is too large to fit a QR, don't leave a blank white box
        // — hide the QR card and steer the user to the share-sheet path.
        mView.findViewById(R.id.p2p_qr_card).setVisibility(rendered ? View.VISIBLE : View.GONE);
        ((TextView) mView.findViewById(R.id.p2p_send_hint)).setText(
                rendered ? R.string.p2p_code_hint_send : R.string.p2p_qr_too_large);
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
            setStage(R.id.p2p_status);
            TextView status = mView.findViewById(R.id.p2p_status);
            status.setText("connected".equals(state)
                    ? R.string.p2p_state_connected : R.string.p2p_state_connecting);
        }
    }

    @Override
    public void onProgress(long done, long total, long rate) {
        if (mView == null) {
            return;
        }
        setStage(R.id.p2p_progress_group);
        // Mid-transfer, an explicit Cancel is warranted (it goes through the
        // same abandon confirm as back). It stays hidden on the QR stage,
        // where back is the natural exit.
        MaterialButton stop = mView.findViewById(R.id.p2p_stop);
        stop.setText(R.string.cancel);
        stop.setVisibility(View.VISIBLE);
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
        setStage(R.id.p2p_done_group);
        mView.findViewById(R.id.p2p_live_dot).setVisibility(View.GONE);
        ((TextView) mView.findViewById(R.id.p2p_done_text)).setText(
                getString(R.string.p2p_done_sent_detail,
                        Formatter.formatShortFileSize(requireContext(), bytes)));
        // The bottom button exists only for this stage (hidden in the layout):
        // a standing Cancel would duplicate back/toolbar-up.
        MaterialButton done = mView.findViewById(R.id.p2p_stop);
        done.setText(R.string.p2p_done);
        done.setVisibility(View.VISIBLE);
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
        setStage(R.id.p2p_error_group);
        mView.findViewById(R.id.p2p_live_dot).setVisibility(View.GONE);
        mView.findViewById(R.id.p2p_stop).setVisibility(View.GONE);
        ((TextView) mView.findViewById(R.id.p2p_error)).setText(errorText(code));
    }
}
