package com.solarized.firedown.phone.fragments;

import android.os.Bundle;
import android.text.format.Formatter;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.widget.ImageView;
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
import com.solarized.firedown.utils.ClipboardHelper;
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
    // Remote-first state: the WAITING sub-stage appears once the link went
    // out; the clipboard is auto-checked on return (each clip value tried
    // once, so a soft bad-code can't loop); the QR lives behind "Nearby?".
    private boolean mLinkShared;
    // Set once bytes start flowing; keeps the UI on the progress stage across
    // connection blips (see onConnectionState) instead of flipping to "Connected".
    private boolean mTransferStarted;
    // Which link mode was last shared — so the waiting card's "Send link again"
    // re-shares the same (true = private/self-contained, false = short/brokered).
    private boolean mLastPrivate;
    private String mLastClipTried;
    private boolean mNearbyExpanded;

    /** Clipboard auto-pickup runs on WINDOW FOCUS gain, not onResume: since
     *  API 29 getPrimaryClip() returns null unless the app currently holds
     *  input focus, which is granted AFTER onResume — an onResume read comes
     *  back empty on modern devices and the pickup would silently never fire. */
    private final ViewTreeObserver.OnWindowFocusChangeListener mFocusListener =
            hasFocus -> {
                if (hasFocus) {
                    maybePickupReplyFromClipboard();
                }
            };

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

        mView.findViewById(R.id.p2p_send_short).setOnClickListener(v -> shareOfferLink(false));
        mView.findViewById(R.id.p2p_send_private).setOnClickListener(v -> shareOfferLink(true));
        // "Send link again" (waiting card) re-shares whichever mode was chosen.
        mView.findViewById(R.id.p2p_send_link_again).setOnClickListener(v -> shareOfferLink(mLastPrivate));
        // NB shareOfferLink(false) brokers the offer lazily via the controller —
        // a private share uploads nothing (see resolveShortLink).
        mView.findViewById(R.id.p2p_nearby_toggle).setOnClickListener(v -> {
            mNearbyExpanded = !mNearbyExpanded;
            updateNearby();
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

        mView.getViewTreeObserver().addOnWindowFocusChangeListener(mFocusListener);

        return mView;
    }

    @Override
    public void onDestroyView() {
        if (mView != null) {
            mView.getViewTreeObserver().removeOnWindowFocusChangeListener(mFocusListener);
        }
        mView = null;
        super.onDestroyView();
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
        mLinkShared = false;
        mTransferStarted = false;
        mLastClipTried = null;
        showPreparing();
        mP2pController.startSend(mDownloadEntity, this);
    }

    /**
     * Share the offer as an https link (firedown.app/s#&lt;code&gt;), in one of two
     * modes. {@code privateMode} false = the SHORT link: the controller brokers
     * the offer lazily ({@link P2pShareController#resolveShortLink}) and hands
     * back the compact {@code FDO1.<id>} reference (or the full link on
     * timeout/failure). {@code privateMode} true = the PRIVATE link: the full
     * self-contained {@code FDS1.} code, which uploads nothing to a server.
     * Sharing flips the screen into the WAITING sub-stage; the reply comes back
     * as a tapped link or off the clipboard. Wired to the two action rows and the
     * waiting card's re-share.
     */
    private void shareOfferLink(boolean privateMode) {
        if (privateMode) {
            // The whole offer rides in the link — nothing is uploaded to a server.
            if (mOfferCode != null) {
                completeShare(P2pShareController.toHttpsLink(mOfferCode), true);
            }
        } else {
            // Short: broker the offer LAZILY (first tap uploads it), bounded so a
            // slow network can't hang the share sheet — the controller hands back
            // the short link or the full one on timeout/failure. Never a dead tap.
            mP2pController.resolveShortLink(link -> completeShare(link, false));
        }
    }

    /** Fire the OS share sheet with {@code content} and flip to the WAITING
     *  sub-stage. Guarded for a null content and a torn-down view (the short-link
     *  path resolves asynchronously). */
    private void completeShare(@Nullable String content, boolean privateMode) {
        if (mView == null || content == null) {
            return;
        }
        shareCode(content);
        mLinkShared = true;
        mLastPrivate = privateMode;
        updateCodeSubstages();
    }

    /**
     * Auto-pickup: a sender who copied the receiver's reply in a messenger and
     * switched back shouldn't have to find a paste button. Fired from
     * {@link #mFocusListener} on window-focus gain (see its note on the API 29
     * focus requirement). Only while WAITING (offer out, link shared), silent
     * when the clipboard has no reply, and each clip value is tried once — a
     * soft bad-code snack must not re-fire on every focus flip. Android's
     * paste toast makes the read visible to the user; that's honest, not a bug.
     */
    private void maybePickupReplyFromClipboard() {
        if (mView == null || !mLinkShared
                || mView.findViewById(R.id.p2p_code_group).getVisibility() != View.VISIBLE) {
            return;
        }
        String code = P2pShareController.stripDeepLink(
                ClipboardHelper.readTrimmedText(getContext()));
        if (!code.startsWith(P2pShareController.ANSWER_PREFIX)
                || code.equals(mLastClipTried)) {
            return;
        }
        mLastClipTried = code;
        makeSnack(R.string.p2p_reply_detected);
        mP2pController.provideAnswer(code);
    }

    /* ── stage visibility ───────────────────────────────────────────────── */

    /** The code stage's two sub-states: pre-share (the two share-action rows +
     *  hint) and WAITING (the status card, which carries the re-share and paste
     *  fallbacks as text buttons). The action rows hide rather than relabelling,
     *  so a static status can never sit next to a control dressed the same way. */
    private void updateCodeSubstages() {
        mView.findViewById(R.id.p2p_waiting_group)
                .setVisibility(mLinkShared ? View.VISIBLE : View.GONE);
        mView.findViewById(R.id.p2p_send_actions)
                .setVisibility(mLinkShared ? View.GONE : View.VISIBLE);
        // The pre-share hint yields to the waiting copy — two stacked
        // explainers read as clutter (on-device review).
        mView.findViewById(R.id.p2p_send_link_hint)
                .setVisibility(mLinkShared ? View.GONE : View.VISIBLE);
    }

    private void updateNearby() {
        mView.findViewById(R.id.p2p_nearby_group)
                .setVisibility(mNearbyExpanded ? View.VISIBLE : View.GONE);
        ((ImageView) mView.findViewById(R.id.p2p_nearby_chevron))
                .setRotation(mNearbyExpanded ? 180f : 0f);
    }

    private void showPreparing() {
        setStage(R.id.p2p_status_group);
        TextView status = mView.findViewById(R.id.p2p_status);
        status.setText(R.string.p2p_preparing);
        mView.findViewById(R.id.p2p_stop).setVisibility(View.GONE);
    }

    private void setStage(int visibleId) {
        int[] stages = {R.id.p2p_status_group, R.id.p2p_code_group, R.id.p2p_progress_group,
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
        updateCodeSubstages();
        updateNearby();
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
        // Once bytes are flowing, NEVER leave the progress stage on a state
        // change — a lossy/VPN path flaps connected↔disconnected repeatedly, and
        // reverting to the "Connected" status card each time made the progress
        // bar vanish and reappear. Instead keep the bar (frozen) and toggle a
        // small "Reconnecting…" spinner: shown while not connected, hidden the
        // moment we're connected again (progress resumes and clears it too).
        if (mTransferStarted) {
            mView.findViewById(R.id.p2p_reconnecting)
                    .setVisibility("connected".equals(state) ? View.GONE : View.VISIBLE);
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
        mTransferStarted = true;
        setStage(R.id.p2p_progress_group);
        // Progress means the path is live again — clear any reconnecting spinner.
        mView.findViewById(R.id.p2p_reconnecting).setVisibility(View.GONE);
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
