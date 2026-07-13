package com.solarized.firedown.phone.fragments;

import android.os.Bundle;
import android.text.format.Formatter;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.progressindicator.LinearProgressIndicator;
import com.solarized.firedown.R;
import com.solarized.firedown.p2pshare.P2pShareController;
import com.solarized.firedown.utils.FileUriHelper;

import java.util.Locale;

import dagger.hilt.android.AndroidEntryPoint;

/**
 * "Receive a file" — the receiver side of the P2P share. Scans (or pastes)
 * the sender's offer code, previews what is being offered BEFORE anything
 * connects (the metadata rides inside the code), and on Accept shows the
 * reply code the sender scans back; then the file streams in and lands as a
 * normal FINISHED entry in Downloads.
 *
 * <p>Screen walk: ENTRY (scan/paste) → PREVIEW (accept/decline) → REPLY
 * (answer QR) → CONNECTING → TRANSFER → DONE / ERROR. Session dies with the
 * view (P2pShareBaseFragment).
 *
 * <p>Reached from the Downloads toolbar overflow ("Receive a file").
 */
@AndroidEntryPoint
public class P2pReceiveFragment extends P2pShareBaseFragment
        implements P2pShareController.Listener {

    private View mView;
    private String mReplyCode;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {

        mView = inflater.inflate(R.layout.fragment_p2p_receive, container, false);
        mToolbar = mView.findViewById(R.id.toolbar);

        mView.findViewById(R.id.p2p_scan_code).setOnClickListener(v ->
                navigateToScanner(P2pShareController.OFFER_PREFIX));
        mView.findViewById(R.id.p2p_paste_code).setOnClickListener(v -> {
            String code = readCodeFromClipboard(P2pShareController.OFFER_PREFIX);
            if (code != null) {
                mP2pController.startReceive(code, this);
            }
        });
        mView.findViewById(R.id.p2p_accept).setOnClickListener(v -> {
            mP2pController.acceptOffer();
            // Disable both so a double-tap can't fire acceptOffer twice while
            // the engine builds the answer.
            v.setEnabled(false);
            mView.findViewById(R.id.p2p_decline).setEnabled(false);
        });
        mView.findViewById(R.id.p2p_decline).setOnClickListener(v -> {
            // Back to square one: kill the parsed session, re-show entry.
            mP2pController.stop();
            showEntry();
        });
        mView.findViewById(R.id.p2p_share_reply).setOnClickListener(v -> {
            if (mReplyCode != null) {
                shareCode(mReplyCode);
            }
        });
        mView.findViewById(R.id.p2p_stop).setOnClickListener(v -> close());

        return mView;
    }

    @Override
    protected int destinationId() {
        return R.id.p2p_receive;
    }

    @Override
    protected void onCodeScanned(@NonNull String code) {
        mP2pController.startReceive(code, this);
    }

    private void showEntry() {
        mView.findViewById(R.id.p2p_entry_group).setVisibility(View.VISIBLE);
        mView.findViewById(R.id.p2p_preview_group).setVisibility(View.GONE);
        mView.findViewById(R.id.p2p_reply_group).setVisibility(View.GONE);
        mView.findViewById(R.id.p2p_status).setVisibility(View.GONE);
        mView.findViewById(R.id.p2p_accept).setEnabled(true);
        mView.findViewById(R.id.p2p_decline).setEnabled(true);
    }

    /* ── engine events (main thread) ────────────────────────────────────── */

    @Override
    public void onCode(@NonNull String role, @NonNull String code) {
        if (mView == null || !"answer".equals(role)) {
            return;
        }
        mReplyCode = code;
        mView.findViewById(R.id.p2p_preview_group).setVisibility(View.GONE);
        mView.findViewById(R.id.p2p_reply_group).setVisibility(View.VISIBLE);
        setQr(mView.findViewById(R.id.p2p_reply_qr), code);
    }

    @Override
    public void onOfferParsed(@NonNull String name, long size, @NonNull String mime, @NonNull String device) {
        if (mView == null) {
            return;
        }
        mView.findViewById(R.id.p2p_entry_group).setVisibility(View.GONE);
        mView.findViewById(R.id.p2p_preview_group).setVisibility(View.VISIBLE);
        ((TextView) mView.findViewById(R.id.p2p_preview_name)).setText(name);
        String sizeText = Formatter.formatShortFileSize(requireContext(), size);
        String tag = device.isEmpty()
                ? sizeText
                : String.format(Locale.ROOT, "%s · %s", sizeText,
                        getString(R.string.p2p_receive_from, device));
        ((TextView) mView.findViewById(R.id.p2p_preview_tag)).setText(tag);
        ((ImageView) mView.findViewById(R.id.p2p_preview_icon)).setImageResource(
                FileUriHelper.getMimeTypeIcon(mime));
    }

    @Override
    public void onConnectionState(@NonNull String state) {
        if (mView == null) {
            return;
        }
        if ("connecting".equals(state) || "connected".equals(state)) {
            TextView status = mView.findViewById(R.id.p2p_status);
            status.setVisibility(View.VISIBLE);
            status.setText("connected".equals(state)
                    ? R.string.p2p_state_connected : R.string.p2p_state_connecting);
            if ("connected".equals(state)) {
                // The sender scanned the reply — the QR's job is done.
                mView.findViewById(R.id.p2p_reply_group).setVisibility(View.GONE);
            }
        }
    }

    @Override
    public void onProgress(long done, long total, long rate) {
        if (mView == null) {
            return;
        }
        mView.findViewById(R.id.p2p_reply_group).setVisibility(View.GONE);
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
        ((TextView) mView.findViewById(R.id.p2p_done_text)).setText(R.string.p2p_done_received);
        ((MaterialButton) mView.findViewById(R.id.p2p_stop)).setText(R.string.p2p_done);
    }

    @Override
    public void onError(@NonNull String code, @NonNull String detail) {
        if (mView == null) {
            return;
        }
        if ("bad-code".equals(code)) {
            // Soft: stay wherever the user is (entry keeps its buttons), let
            // them retry with a better scan/paste.
            makeSnack(R.string.p2p_error_bad_code);
            showEntry();
            return;
        }
        mView.findViewById(R.id.p2p_entry_group).setVisibility(View.GONE);
        mView.findViewById(R.id.p2p_preview_group).setVisibility(View.GONE);
        mView.findViewById(R.id.p2p_reply_group).setVisibility(View.GONE);
        mView.findViewById(R.id.p2p_status).setVisibility(View.GONE);
        mView.findViewById(R.id.p2p_progress_group).setVisibility(View.GONE);
        TextView error = mView.findViewById(R.id.p2p_error);
        error.setVisibility(View.VISIBLE);
        error.setText(errorText(code));
    }
}
