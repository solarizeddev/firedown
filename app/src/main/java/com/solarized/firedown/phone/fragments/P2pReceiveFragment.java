package com.solarized.firedown.phone.fragments;

import android.graphics.Typeface;
import android.os.Bundle;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.format.Formatter;
import android.text.style.ForegroundColorSpan;
import android.text.style.StyleSpan;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.color.MaterialColors;
import com.solarized.firedown.R;
import com.solarized.firedown.p2pshare.P2pShareController;
import com.solarized.firedown.p2pshare.ShareState;
import com.solarized.firedown.utils.FileUriHelper;
import dagger.hilt.android.AndroidEntryPoint;

/**
 * "Receive a file" — the receiver side of the P2P share. While this screen
 * is open and idle the phone is VISIBLE to nearby senders (NSD + the LAN
 * {@code /offer} listener, see {@link P2pShareController#setNearbyListening});
 * a sender's tap lands here as a request to Accept or Decline, exactly like
 * a scanned or pasted code would. Accept, and the file streams in and lands
 * as a normal FINISHED entry in Downloads — the session then outlives this
 * screen (the service carries it).
 *
 * <p>Screen walk: IDLE (visible card + Scan; Paste code in the overflow) → PREVIEW
 * (accept/decline) → CONNECTING (with the human-relayed reply only when both
 * push paths failed) → TRANSFER → DONE (Open) / ERROR (Try again). Rendered
 * from the controller's {@link ShareState}.
 */
@AndroidEntryPoint
public class P2pReceiveFragment extends P2pShareBaseFragment {

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
    private String mRenderedReplyCode;
    private String mVisibleAs;
    private ShareState mLast;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {

        mView = inflater.inflate(R.layout.fragment_p2p_receive, container, false);
        mToolbar = mView.findViewById(R.id.toolbar);

        mView.findViewById(R.id.p2p_scan_code).setOnClickListener(v ->
                navigateToScanner(P2pShareController.OFFER_PREFIX, false));
        // Paste a bare code: rare (a link tapped in a chat opens this screen
        // by itself), so it lives in the overflow, shown while idle.
        mToolbar.inflateMenu(R.menu.menu_p2p_receive);
        mToolbar.setOnMenuItemClickListener(item -> {
            if (item.getItemId() == R.id.action_paste_code) {
                String code = readCodeFromClipboard(P2pShareController.OFFER_PREFIX);
                if (code != null && !refuseWhileBusy()) {
                    // Pasted from a messenger = phones apart.
                    mP2pController.startReceive(code, true);
                }
                return true;
            }
            return false;
        });
        mView.findViewById(R.id.p2p_accept).setOnClickListener(v -> {
            mP2pController.acceptOffer();
            v.setEnabled(false);
            mView.findViewById(R.id.p2p_decline).setEnabled(false);
        });
        mView.findViewById(R.id.p2p_decline).setOnClickListener(v -> mP2pController.declineOffer());
        mView.findViewById(R.id.p2p_send_reply).setOnClickListener(v -> {
            // The https form — the sender just taps it in the chat and their
            // waiting session connects (answer deep link via DownloadsActivity).
            if (mLast != null && mLast.answerCode != null) {
                shareCode(P2pShareController.toHttpsLink(mLast.answerCode));
            }
        });
        mView.findViewById(R.id.p2p_open).setOnClickListener(v -> openReceived());
        mView.findViewById(R.id.p2p_stop).setOnClickListener(v -> onStopTapped());
        // Try again after a failure: drop the outcome; the idle screen (and
        // nearby visibility) comes back through render.
        mView.findViewById(R.id.p2p_retry).setOnClickListener(v -> mP2pController.dismissFinished());

        return mView;
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        mP2pController.getVisibleAs().observe(getViewLifecycleOwner(), name -> {
            mVisibleAs = name;
            renderVisible();
        });
        // A deep-link arrival: a self-contained offer (ARG_OFFER_CODE), a
        // short offer link (ARG_OFFER_REF) or a relay link (ARG_SIGNALING_*).
        // Started ONCE — the nav args are re-delivered on a configuration
        // change, and a running session must not be restarted by a rotation
        // (it is attached to through the state instead).
        if (savedInstanceState != null) {
            return;
        }
        Bundle args = getArguments();
        if (args != null) {
            onArrival(args);
        }
    }

    /**
     * Start a receive from a deep-link arrival. Called for this screen's own
     * nav args, and by DownloadsActivity for a link tapped while this screen
     * is ALREADY on top: that link used to stack a second Receive screen, and
     * the first one's onDestroyView, running after the new one had started
     * its session (FragmentManager brings the new fragment up before taking
     * the old one down), saw that session PREPARING/OFFERED and declined it,
     * leaving an idle screen with nothing to accept. Its onStop also landed
     * after the new screen's onStart and took the phone off the nearby list.
     */
    public void onArrival(@NonNull Bundle args) {
        String base = args.getString(ARG_SIGNALING_BASE);
        String id = args.getString(ARG_SIGNALING_ID);
        String offerRef = args.getString(ARG_OFFER_REF);
        String offer = args.getString(ARG_OFFER_CODE);
        boolean arrival = (base != null && !base.isEmpty() && id != null && !id.isEmpty())
                || (offerRef != null && !offerRef.isEmpty())
                || (offer != null && !offer.isEmpty());
        if (arrival && refuseWhileBusy()) {
            return; // a link tapped mid-transfer must not kill the transfer
        }
        if (base != null && !base.isEmpty() && id != null && !id.isEmpty()) {
            mP2pController.startReceiveFromRelay(base, id);
        } else if (offerRef != null && !offerRef.isEmpty()) {
            mP2pController.startReceiveFromOfferRef(offerRef);
        } else if (offer != null && !offer.isEmpty()) {
            mP2pController.startReceive(offer, true); // tapped link = phones (likely) apart
        }
    }

    /**
     * Visibility follows the screen being SEEN, not merely existing: Home
     * keeps the view (onStop, not onDestroyView), and a phone advertising its
     * name for hours from the background is the opposite of "ready to
     * receive". LiveData does not redeliver an unchanged value on restart, so
     * onStart decides explicitly.
     */
    @Override
    public void onStart() {
        super.onStart();
        mP2pController.setNearbyListening(shouldBeVisible(mP2pController.peekState()));
    }

    @Override
    public void onStop() {
        mP2pController.setNearbyListening(false);
        super.onStop();
    }

    /**
     * Visible to nearby senders exactly while nothing is in flight on THIS
     * phone, in either role: idle, or showing an outcome. (A live SEND used
     * to keep it visible, answering every nearby sender 409.)
     */
    private static boolean shouldBeVisible(@Nullable ShareState state) {
        return state == null || !state.isLive();
    }

    /** A transfer is moving bytes: say so and start nothing (true = refused). */
    private boolean refuseWhileBusy() {
        if (mP2pController.isBusyWithTransfer()) {
            makeSnack(R.string.p2p_busy_other_transfer);
            return true;
        }
        return false;
    }

    @Override
    public void onDestroyView() {
        // Visibility is this SCREEN's: nobody can send to a phone that isn't
        // showing it is ready to receive.
        mP2pController.setNearbyListening(false);
        ShareState state = mP2pController.peekState();
        if (state != null && state.role == ShareState.Role.RECEIVE) {
            if (state.stage == ShareState.Stage.PREPARING
                    || state.stage == ShareState.Stage.OFFERED) {
                // A request nobody answered is a no — the sender is told so it
                // can offer elsewhere, and nothing is left half-open.
                mP2pController.declineOffer();
            } else if (!state.isLive()) {
                mP2pController.dismissFinished();
            }
            // CONNECTING / TRANSFERRING keep running: the service carries them.
        }
        mView = null;
        super.onDestroyView();
    }

    @Override
    protected int destinationId() {
        return R.id.p2p_receive;
    }

    @Override
    protected void onCodeScanned(@NonNull String code) {
        if (!refuseWhileBusy()) {
            mP2pController.startReceive(code, false); // scanned in person = phones adjacent
        }
    }

    private void onStopTapped() {
        if (mLast != null && !mLast.isLive()) {
            mP2pController.dismissFinished();
            close();
        } else {
            confirmCancel();
        }
    }

    private void openReceived() {
        if (mLast != null && mLast.received != null) {
            // BaseFocusFragment's shared "open with" path — same as the
            // Downloads options sheet's Open action.
            openItemWith(mLast.received);
        }
    }

    /* ── rendering ──────────────────────────────────────────────────────── */

    @Override
    protected void render(@Nullable ShareState state) {
        if (mView == null) {
            return;
        }
        boolean mine = state != null && state.role == ShareState.Role.RECEIVE;
        // render only runs while the screen is started (LiveData), so this
        // never re-advertises a phone in the background.
        mP2pController.setNearbyListening(shouldBeVisible(state));
        if (!mine) {
            mLast = null;
            showStage(R.id.p2p_idle_group);
            renderVisible();
            setMenuVisible(true);
            mView.findViewById(R.id.p2p_stop).setVisibility(View.GONE);
            bindFooter(mView, null);
            return;
        }
        mLast = state;
        setMenuVisible(false);
        MaterialButton stop = mView.findViewById(R.id.p2p_stop);
        TextView status = mView.findViewById(R.id.p2p_status);
        switch (state.stage) {
            case PREPARING, READY -> {
                showStage(R.id.p2p_status_group);
                status.setText(R.string.p2p_preparing);
                mView.findViewById(R.id.p2p_reply_group).setVisibility(View.GONE);
                stop.setText(R.string.cancel);
                stop.setVisibility(View.VISIBLE);
            }
            case OFFERED -> {
                showStage(R.id.p2p_preview_group);
                bindPreview(state);
                stop.setVisibility(View.GONE);
            }
            case CONNECTING, CONNECTED -> {
                showStage(R.id.p2p_status_group);
                status.setText(state.stage == ShareState.Stage.CONNECTED
                        ? R.string.p2p_state_connected : R.string.p2p_state_connecting);
                bindReplyFallback(state);
                stop.setText(R.string.cancel);
                stop.setVisibility(View.VISIBLE);
            }
            case TRANSFERRING -> {
                showStage(R.id.p2p_progress_group);
                bindProgress(mView, state);
                stop.setText(R.string.cancel);
                stop.setVisibility(View.VISIBLE);
            }
            case DONE -> {
                showStage(R.id.p2p_done_group);
                // Open is only useful if we actually have the entity handle.
                mView.findViewById(R.id.p2p_open)
                        .setVisibility(state.received != null ? View.VISIBLE : View.GONE);
                stop.setText(R.string.p2p_done);
                stop.setVisibility(View.VISIBLE);
            }
            case ERROR -> {
                showStage(R.id.p2p_error_group);
                ((TextView) mView.findViewById(R.id.p2p_error)).setText(errorText(state));
                stop.setVisibility(View.GONE);
            }
        }
        bindFooter(mView, state);
    }

    private void bindPreview(@NonNull ShareState state) {
        mView.findViewById(R.id.p2p_accept).setEnabled(true);
        mView.findViewById(R.id.p2p_decline).setEnabled(true);
        // Who is sending leads: the device name the sender's phone claims,
        // in the title's weight (the copy never calls it verified).
        String device = state.peerDevice == null ? "" : state.peerDevice.trim();
        CharSequence from;
        if (device.isEmpty()) {
            from = getString(R.string.p2p_wants_to_send_unknown);
        } else {
            String text = getString(R.string.p2p_wants_to_send, device);
            SpannableString styled = new SpannableString(text);
            int at = text.indexOf(device);
            if (at >= 0) {
                styled.setSpan(new StyleSpan(Typeface.BOLD), at, at + device.length(),
                        Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                styled.setSpan(new ForegroundColorSpan(MaterialColors.getColor(mView,
                                com.google.android.material.R.attr.colorOnSurface)),
                        at, at + device.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            }
            from = styled;
        }
        ((TextView) mView.findViewById(R.id.p2p_preview_from)).setText(from);
        ((TextView) mView.findViewById(R.id.p2p_preview_name)).setText(
                state.fileName == null ? "" : state.fileName);
        ((TextView) mView.findViewById(R.id.p2p_preview_tag)).setText(
                Formatter.formatShortFileSize(requireContext(), state.fileSize));
        ((ImageView) mView.findViewById(R.id.p2p_preview_icon)).setImageResource(
                FileUriHelper.getMimeTypeIcon(state.mime == null ? "" : state.mime));
    }

    /**
     * The human-relayed reply, ONLY when the controller fell all the way back
     * to it ({@link ShareState#answerCode}): "Send reply" (a link the sender
     * taps) plus the QR for a sender standing here. A scan arrival shows the
     * QR first; a link arrival the share button — both are present either
     * way, the order is the only difference worth making.
     */
    private void bindReplyFallback(@NonNull ShareState state) {
        View group = mView.findViewById(R.id.p2p_reply_group);
        if (state.answerCode == null) {
            group.setVisibility(View.GONE);
            return;
        }
        group.setVisibility(View.VISIBLE);
        if (state.answerCode.equals(mRenderedReplyCode)) {
            return;
        }
        mRenderedReplyCode = state.answerCode;
        boolean rendered = setQr(mView.findViewById(R.id.p2p_reply_qr), state.answerCode);
        mView.findViewById(R.id.p2p_reply_qr_card).setVisibility(rendered ? View.VISIBLE : View.GONE);
        // Too big for a QR: the "Send reply" button above is the way back.
        mView.findViewById(R.id.p2p_reply_hint).setVisibility(rendered ? View.VISIBLE : View.GONE);
    }

    /** The idle card: "Visible as <name>" with the live dot, or not visible. */
    private void renderVisible() {
        if (mView == null) {
            return;
        }
        TextView text = mView.findViewById(R.id.p2p_visible_text);
        TextView hint = mView.findViewById(R.id.p2p_visible_hint);
        View dot = mView.findViewById(R.id.p2p_visible_dot);
        if (mVisibleAs != null && !mVisibleAs.isEmpty()) {
            text.setText(getString(R.string.p2p_visible_as, mVisibleAs));
            hint.setText(R.string.p2p_visible_hint_link);
            hint.setVisibility(View.VISIBLE);
            dot.setVisibility(View.VISIBLE);
        } else {
            // Not advertised: the "phones on this Wi-Fi can send to you" hint
            // would contradict the line above, which already says what to do.
            text.setText(R.string.p2p_not_visible);
            hint.setVisibility(View.GONE);
            dot.setVisibility(View.GONE);
            return;
        }
    }

    /** Paste code belongs to the idle screen only. */
    private void setMenuVisible(boolean visible) {
        if (mToolbar == null) {
            return;
        }
        Menu menu = mToolbar.getMenu();
        for (int i = 0; i < menu.size(); i++) {
            menu.getItem(i).setVisible(visible);
        }
    }

    private void showStage(int visibleId) {
        int[] stages = {R.id.p2p_idle_group, R.id.p2p_preview_group, R.id.p2p_status_group,
                R.id.p2p_progress_group, R.id.p2p_done_group, R.id.p2p_error_group};
        for (int id : stages) {
            mView.findViewById(id).setVisibility(id == visibleId ? View.VISIBLE : View.GONE);
        }
    }
}
