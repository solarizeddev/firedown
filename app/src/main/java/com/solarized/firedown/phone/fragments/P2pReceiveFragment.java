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
import com.solarized.firedown.data.entity.DownloadEntity;
import com.solarized.firedown.p2pshare.P2pShareController;
import com.solarized.firedown.utils.FileUriHelper;

import java.util.Locale;

import dagger.hilt.android.AndroidEntryPoint;

/**
 * "Receive a file" — the receiver side of the P2P share. Scans (or pastes)
 * the sender's offer code, previews what's offered BEFORE anything connects,
 * and on Accept shows the reply code the sender scans back; then the file
 * streams in and lands as a normal FINISHED entry in Downloads.
 *
 * <p>Screen walk: ENTRY (scan/paste) → PREVIEW (accept/decline) → REPLY
 * (answer QR) → CONNECTING → TRANSFER → DONE (Open) / ERROR (Try again).
 */
@AndroidEntryPoint
public class P2pReceiveFragment extends P2pShareBaseFragment
        implements P2pShareController.Listener {

    /** Nav arg: an offer code delivered by the firedown://p2p/ deep link. */
    public static final String ARG_OFFER_CODE = "p2p.offer.code";
    /**
     * Nav arg: a SHORT offer reference ({@code FDO1.<id>}) from a short share
     * link — the receiver fetches the full offer from the rendezvous offer
     * mailbox by this id before connecting. The value is the bare id.
     */
    public static final String ARG_OFFER_REF = "p2p.offer.ref";
    /**
     * Nav args: a signaling-relay link ({@code firedown://p2p/r/<id>?s=<base>}
     * or {@code https://<relay>/s/<id>}) — the receiver fetches the offer from
     * the relay instead of carrying it inline. Both must be present together.
     */
    public static final String ARG_SIGNALING_BASE = "p2p.sig.base";
    public static final String ARG_SIGNALING_ID = "p2p.sig.id";

    private View mView;
    private String mReplyCode;
    private String mLastCode;
    // HOW the offer arrived decides the reply stage's shape: via link/paste
    // (phones apart) -> "Send reply" share button primary, QR folded; via the
    // in-app scanner (phones adjacent) -> QR expanded, remote widgets hidden.
    private boolean mArrivedRemote;
    private boolean mReplyNearbyExpanded;
    // A relay link's rendezvous coordinates, when the deep link was a relay
    // link rather than a self-contained offer. Both null = no relay.
    private String mSignalingBase;
    private String mSignalingId;
    // A short offer link's rendezvous id ({@code FDO1.<id>}) — the receiver
    // fetches the full offer from the offer mailbox. Null = not a short link.
    private String mOfferRefId;
    private DownloadEntity mReceived;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {

        mView = inflater.inflate(R.layout.fragment_p2p_receive, container, false);
        mToolbar = mView.findViewById(R.id.toolbar);

        mView.findViewById(R.id.p2p_scan_code).setOnClickListener(v ->
                navigateToScanner(P2pShareController.OFFER_PREFIX, false));
        mView.findViewById(R.id.p2p_paste_code).setOnClickListener(v -> {
            String code = readCodeFromClipboard(P2pShareController.OFFER_PREFIX);
            if (code != null) {
                mLastCode = code;
                mArrivedRemote = true; // pasted from a messenger = phones apart
                mP2pController.startReceive(code, this);
            }
        });
        mView.findViewById(R.id.p2p_accept).setOnClickListener(v -> {
            mP2pController.acceptOffer();
            v.setEnabled(false);
            mView.findViewById(R.id.p2p_decline).setEnabled(false);
        });
        mView.findViewById(R.id.p2p_decline).setOnClickListener(v -> {
            mP2pController.stop();
            showEntry();
        });
        mView.findViewById(R.id.p2p_share_reply).setOnClickListener(v -> {
            if (mReplyCode != null) {
                shareCode(P2pShareController.toHttpsLink(mReplyCode));
            }
        });
        mView.findViewById(R.id.p2p_send_reply).setOnClickListener(v -> {
            // The https form — the sender just taps it in the chat and their
            // waiting session connects (answer deep link via DownloadsActivity).
            if (mReplyCode != null) {
                shareCode(P2pShareController.toHttpsLink(mReplyCode));
            }
        });
        mView.findViewById(R.id.p2p_reply_nearby_toggle).setOnClickListener(v -> {
            mReplyNearbyExpanded = !mReplyNearbyExpanded;
            updateReplyNearby();
        });
        mView.findViewById(R.id.p2p_open).setOnClickListener(v -> openReceived());
        // Mid-transfer this is "Cancel" (confirmed, like back); on done, "Done".
        mView.findViewById(R.id.p2p_stop).setOnClickListener(v -> confirmThenClose());
        mView.findViewById(R.id.p2p_retry).setOnClickListener(v -> showEntry());

        // Arrived via the firedown://p2p/ deep link (scanned with any scanner)?
        // Either a self-contained offer (ARG_OFFER_CODE) or a relay link
        // (ARG_SIGNALING_BASE/ID, offer fetched from the relay). Stash it so
        // onEngineReady kicks off once the WebRTC pref applies — same replay
        // path as a code scanned before ready.
        Bundle args = getArguments();
        if (args != null) {
            String offer = args.getString(ARG_OFFER_CODE);
            if (offer != null && !offer.isEmpty()) {
                mLastCode = offer;
                mArrivedRemote = true; // tapped link = phones (likely) apart
            }
            String base = args.getString(ARG_SIGNALING_BASE);
            String id = args.getString(ARG_SIGNALING_ID);
            if (base != null && !base.isEmpty() && id != null && !id.isEmpty()) {
                mSignalingBase = base;
                mSignalingId = id;
                mArrivedRemote = true; // a relay link is inherently remote
            }
            String offerRef = args.getString(ARG_OFFER_REF);
            if (offerRef != null && !offerRef.isEmpty()) {
                mOfferRefId = offerRef;
                mArrivedRemote = true; // a short offer link is inherently remote
            }
        }

        // A code/relay/offer link is already in hand (deep-link arrival) → show
        // the working state right away, NOT the scan/paste entry. A plain open
        // with nothing in hand shows the entry so the user can scan.
        if (mLastCode != null || mSignalingBase != null || mOfferRefId != null) {
            showReading();
        } else {
            setStage(R.id.p2p_entry_group);
        }

        return mView;
    }

    @Override
    protected void onEngineReady() {
        // Nothing to start unless a code/relay link arrived — otherwise receive
        // waits for a scanned/pasted offer. Replayed here so it runs only after
        // the WebRTC pref applied. A relay link takes precedence: it fetches the
        // offer from the relay, then proceeds as a normal receive.
        if (mSignalingBase != null && mSignalingId != null) {
            mP2pController.startReceiveFromRelay(mSignalingBase, mSignalingId, this);
        } else if (mOfferRefId != null) {
            mP2pController.startReceiveFromOfferRef(mOfferRefId, this);
        } else if (mLastCode != null) {
            mP2pController.startReceive(mLastCode, this);
        }
    }

    @Override
    protected int destinationId() {
        return R.id.p2p_receive;
    }

    @Override
    protected void onCodeScanned(@NonNull String code) {
        mLastCode = code;
        mArrivedRemote = false; // scanned in person = phones adjacent
        mP2pController.startReceive(code, this);
    }

    private void showEntry() {
        setStage(R.id.p2p_entry_group);
        mView.findViewById(R.id.p2p_accept).setEnabled(true);
        mView.findViewById(R.id.p2p_decline).setEnabled(true);
        mView.findViewById(R.id.p2p_stop).setVisibility(View.GONE);
    }

    private void openReceived() {
        if (mReceived != null) {
            // BaseFocusFragment's shared "open with" path — same as the
            // Downloads options sheet's Open action.
            openItemWith(mReceived);
        }
    }

    /** Reading/decoding the scanned offer, before the preview appears. */
    private void showReading() {
        setStage(R.id.p2p_status_group);
        ((TextView) mView.findViewById(R.id.p2p_status)).setText(R.string.p2p_preparing);
    }

    private void setStage(int visibleId) {
        int[] stages = {R.id.p2p_entry_group, R.id.p2p_preview_group, R.id.p2p_status_group,
                R.id.p2p_reply_group, R.id.p2p_progress_group, R.id.p2p_done_group,
                R.id.p2p_error_group};
        for (int id : stages) {
            mView.findViewById(id).setVisibility(id == visibleId ? View.VISIBLE : View.GONE);
        }
    }

    /* ── engine events (main thread) ────────────────────────────────────── */

    @Override
    public void onCode(@NonNull String role, @NonNull String code) {
        if (mView == null || !"answer".equals(role)) {
            return;
        }
        mReplyCode = code;
        setStage(R.id.p2p_reply_group);
        boolean rendered = setQr(mView.findViewById(R.id.p2p_reply_qr), code);
        mView.findViewById(R.id.p2p_reply_qr_card).setVisibility(rendered ? View.VISIBLE : View.GONE);
        ((TextView) mView.findViewById(R.id.p2p_reply_hint)).setText(
                rendered ? R.string.p2p_code_hint_reply : R.string.p2p_qr_too_large);
        // Remote arrival: "Send reply" is the primary, QR folded. Scan
        // arrival: the phones are adjacent — QR expanded, remote widgets gone.
        mView.findViewById(R.id.p2p_reply_remote_group)
                .setVisibility(mArrivedRemote ? View.VISIBLE : View.GONE);
        mReplyNearbyExpanded = !mArrivedRemote;
        updateReplyNearby();
    }

    private void updateReplyNearby() {
        mView.findViewById(R.id.p2p_reply_nearby_group)
                .setVisibility(mReplyNearbyExpanded ? View.VISIBLE : View.GONE);
        ((ImageView) mView.findViewById(R.id.p2p_reply_nearby_chevron))
                .setRotation(mReplyNearbyExpanded ? 180f : 0f);
    }

    @Override
    public void onOfferParsed(@NonNull String name, long size, @NonNull String mime, @NonNull String device) {
        if (mView == null) {
            return;
        }
        setStage(R.id.p2p_preview_group);
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
            setStage(R.id.p2p_status_group);
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
        // same abandon confirm as back). Hidden on the scan/reply stages.
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
        mReceived = mP2pController.getReceivedEntity();
        // Open is only useful if we actually have the entity handle.
        mView.findViewById(R.id.p2p_open).setVisibility(mReceived != null ? View.VISIBLE : View.GONE);
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
            // Soft: stay on entry, let the user retry the scan/paste.
            makeSnack(R.string.p2p_error_bad_code);
            showEntry();
            return;
        }
        setStage(R.id.p2p_error_group);
        mView.findViewById(R.id.p2p_stop).setVisibility(View.GONE);
        ((TextView) mView.findViewById(R.id.p2p_error)).setText(errorText(code));
    }
}
