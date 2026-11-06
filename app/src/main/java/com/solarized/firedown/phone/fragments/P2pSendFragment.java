package com.solarized.firedown.phone.fragments;

import android.annotation.SuppressLint;
import android.content.res.ColorStateList;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.format.Formatter;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.view.Window;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.constraintlayout.widget.ConstraintLayout;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.ColorUtils;
import androidx.core.view.ViewCompat;
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat.AccessibilityActionCompat;
import androidx.preference.PreferenceManager;

import com.bumptech.glide.request.RequestOptions;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.card.MaterialCardView;
import com.google.android.material.color.MaterialColors;
import com.google.android.material.imageview.ShapeableImageView;
import com.google.android.material.progressindicator.CircularProgressIndicator;
import com.google.android.material.snackbar.Snackbar;
import com.solarized.firedown.GlideHelper;
import com.solarized.firedown.Keys;
import com.solarized.firedown.Preferences;
import com.solarized.firedown.R;
import com.solarized.firedown.StoragePaths;
import com.solarized.firedown.data.entity.DownloadEntity;
import com.solarized.firedown.p2pshare.P2pNearbyDiscovery;
import com.solarized.firedown.p2pshare.P2pSendRows;
import com.solarized.firedown.p2pshare.P2pSessionPolicy;
import com.solarized.firedown.p2pshare.P2pShareController;
import com.solarized.firedown.p2pshare.ShareState;
import com.solarized.firedown.utils.ClipboardHelper;
import com.solarized.firedown.utils.FileUriHelper;
import com.solarized.firedown.utils.FragmentArgs;
import com.solarized.firedown.utils.SheetGroups;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

import dagger.hilt.android.AndroidEntryPoint;

/**
 * "Send directly" — the sender side of the P2P share. The engine mints a
 * WebRTC offer and the screen shows ONE "Send to" list: the phones with
 * Receive open on this Wi-Fi (found over NSD; a tap POSTs them the offer),
 * "Share a link" (through any messenger) and "Show a QR code" (unfolds in
 * place). The answer comes back over the LAN or the rendezvous mailbox (or,
 * last resort, the overflow's Paste / Scan reply); then the file streams
 * device-to-device over the DataChannel.
 *
 * <p>What happened after a tap is that row's second line ({@link
 * P2pSendRows}), never another card: "Waiting for them to accept", the link's
 * countdown, "Connecting…". Nothing spins at rest — nearby discovery never
 * ends, so an empty list is just the link and the QR, and how a phone gets
 * listed is one line under "Send to". Once bytes move the file card
 * carries the bar, and the outcome.
 *
 * <p>Rendered from the controller's {@link ShareState}; the session outlives
 * this screen (the notification brings the user back, and this fragment then
 * ATTACHES to the running session instead of starting another).
 */
@AndroidEntryPoint
public class P2pSendFragment extends P2pShareBaseFragment {

    private DownloadEntity mDownloadEntity;
    private View mView;
    /** "Video", "Audio"…, for the file card's second line. */
    private String mMimeWord;
    /** The size the Downloads row stored, until the session measures it. */
    private long mStoredSize;
    /** Swallows a touch nothing inside the unfolded body took (see bindRow). */
    @SuppressLint("ClickableViewAccessibility")
    private static final View.OnTouchListener SWALLOW_TOUCH = (v, event) -> true;
    /** "Someone next to you" is unfolded. */
    private boolean mNearbyOpen;
    /** The unfolded body (phones found, then the QR), moved into the
     *  in-person card while it is open. */
    private ViewGroup mNearbyBody;
    /** What the cards in p2p_dest_list show (kind:name), to bind in place. */
    private List<String> mRowKeys = Collections.emptyList();
    /** The same for the phone cards inside the unfolded body. */
    private List<String> mPhoneKeys = Collections.emptyList();
    private boolean mLinkPreparing;
    private String mRenderedQrCode;
    private String mLastClipTried;
    private P2pNearbyDiscovery mDiscovery;
    private List<P2pNearbyDiscovery.Device> mDevices = Collections.emptyList();
    private ShareState mLast;
    // Decided in onCreateView (P2pSessionPolicy), acted on in onViewCreated.
    private boolean mStartOnCreate;

    /** Repaints the link row's countdown on the minute boundary. */
    private final Handler mTickHandler = new Handler(Looper.getMainLooper());
    private final Runnable mTick = this::renderRows;

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
        // Null when reopened from the service notification — the live
        // session's entity is used then (onCreateView).
        mDownloadEntity = FragmentArgs.parcelable(this, Keys.ITEM_ID, DownloadEntity.class);
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {

        // Attach to the send session that exists (live, or its outcome when
        // the finished notification was tapped or the screen rotated), start
        // a share of the file the screen was opened for, or refuse while
        // another transfer moves bytes — never silently replace it.
        ShareState live = mP2pController.peekState();
        String argPath = mDownloadEntity == null ? null : mDownloadEntity.getFilePath();
        P2pSessionPolicy.SendEntry entry = P2pSessionPolicy.sendEntry(
                live != null,
                live != null && live.role == ShareState.Role.SEND,
                live != null && live.isLive(),
                live != null && live.isInTransfer(),
                live != null && live.entity != null ? live.entity.getFilePath() : null,
                argPath,
                savedInstanceState != null);
        switch (entry) {
            case ATTACH -> mDownloadEntity = live.entity;
            case START -> mStartOnCreate = true;
            case BUSY -> {
                Snackbar.make(requireActivity().findViewById(Window.ID_ANDROID_CONTENT),
                        R.string.p2p_busy_other_transfer, Snackbar.LENGTH_LONG).show();
                mDownloadEntity = null;
            }
            case NOTHING -> mDownloadEntity = null;
        }
        // Defensive: vault entries must never be shareable, and a screen with
        // nothing to send (no arg, no session, or another transfer busy) has
        // nothing to show.
        if (mDownloadEntity == null || mDownloadEntity.isFileSafe()
                || mDownloadEntity.getFilePath() == null) {
            mStartOnCreate = false;
            if (mNavController != null) {
                mNavController.popBackStack();
            }
            return null;
        }

        mView = inflater.inflate(R.layout.fragment_p2p_send, container, false);
        mNearbyBody = mView.findViewById(R.id.p2p_nearby_body);
        mRowKeys = Collections.emptyList();
        mPhoneKeys = Collections.emptyList();
        mToolbar = mView.findViewById(R.id.toolbar);

        File file = new File(mDownloadEntity.getFilePath());
        String mime = FileUriHelper.getMimeTypeFromFile(file.getName());

        ShapeableImageView thumb = mView.findViewById(R.id.p2p_thumb);
        GlideHelper.load(mDownloadEntity, new RequestOptions(), thumb);
        ((TextView) mView.findViewById(R.id.p2p_file_name)).setText(file.getName());
        // The size the Downloads row stored: no file access on the main thread
        // (a restored, foreign-owned file is a binder call to measure). The
        // session's own measurement, read on the disk thread, replaces it.
        mMimeWord = FileUriHelper.getLongMimeText(requireContext(), mime);
        mStoredSize = mDownloadEntity.getFileSize();
        ((TextView) mView.findViewById(R.id.p2p_file_tag)).setText(fileTag(mStoredSize));

        mToolbar.inflateMenu(R.menu.menu_p2p_send);
        mToolbar.setOnMenuItemClickListener(item -> {
            int id = item.getItemId();
            if (id == R.id.action_paste_reply) {
                String code = readCodeFromClipboard(P2pShareController.ANSWER_PREFIX);
                if (code != null) {
                    mP2pController.provideAnswer(code);
                }
                return true;
            }
            if (id == R.id.action_scan_reply) {
                navigateToScanner(P2pShareController.ANSWER_PREFIX, true);
                return true;
            }
            return false;
        });
        mView.findViewById(R.id.p2p_stop).setOnClickListener(v -> onStopTapped());
        mView.findViewById(R.id.p2p_retry).setOnClickListener(v -> restart());

        mView.getViewTreeObserver().addOnWindowFocusChangeListener(mFocusListener);

        return mView;
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        // Decided in onCreateView: attach, or start a share for this file.
        if (mStartOnCreate) {
            mStartOnCreate = false;
            mP2pController.startSend(mDownloadEntity);
        }
        // Discovery lives with the view and runs silently; found phones
        // simply appear as rows (shown only while the offer waits).
        mDiscovery = new P2pNearbyDiscovery(requireContext(), this::onDevicesChanged);
        mDiscovery.start();
    }

    @Override
    public void onDestroyView() {
        mTickHandler.removeCallbacks(mTick);
        if (mDiscovery != null) {
            mDiscovery.stop();
            mDiscovery = null;
        }
        if (mView != null) {
            mView.getViewTreeObserver().removeOnWindowFocusChangeListener(mFocusListener);
        }
        // Leaving keeps a RUNNING session (the service carries it); a shown
        // outcome is consumed so the next Send opens clean.
        ShareState state = mP2pController.peekState();
        if (state != null && state.role == ShareState.Role.SEND && !state.isLive()) {
            mP2pController.dismissFinished();
        }
        mLinkPreparing = false;
        // A rebuilt view starts with an empty QR image: draw it again.
        mRenderedQrCode = null;
        mView = null;
        mNearbyBody = null;
        super.onDestroyView();
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
        if (mP2pController.isBusyWithTransfer()) {
            makeSnack(R.string.p2p_busy_other_transfer); // startSend would refuse silently
            return;
        }
        mNearbyOpen = false;
        mLinkPreparing = false;
        mRenderedQrCode = null;
        mLastClipTried = null;
        mP2pController.startSend(mDownloadEntity);
    }

    private void onStopTapped() {
        if (mLast != null && !mLast.isLive()) {
            mP2pController.dismissFinished();
            close();
        } else {
            confirmCancel();
        }
    }

    /**
     * "Share a link": the SHORT link (the controller brokers the offer on
     * first use, {@link P2pShareController#resolveShortLink}, and falls back
     * to the full link when the mailbox is slow or down), or the full
     * self-contained link when Settings → Direct share → Private links is on
     * (no offer is stored on the server). A preference, never a question
     * asked here. The row reads "Preparing link…" for the bounded broker
     * wait; the share sheet that opens is the feedback.
     */
    private void shareLink() {
        if (mLast == null || mLast.offerCode == null || mLinkPreparing) {
            return;
        }
        boolean privateLinks = PreferenceManager.getDefaultSharedPreferences(requireContext())
                .getBoolean(Preferences.SETTINGS_P2P_PRIVATE_LINKS, Preferences.DEFAULT_P2P_PRIVATE_LINKS);
        if (privateLinks) {
            completeShare(P2pShareController.toHttpsLink(mLast.offerCode));
            return;
        }
        mLinkPreparing = true;
        renderRows();
        mP2pController.resolveShortLink(link -> {
            mLinkPreparing = false;
            completeShare(link);
            renderRows();
        });
    }

    /** Fire the OS share sheet with {@code content} and mark the session
     *  link-shared. Guarded for a null content and a torn-down view (the
     *  short-link path resolves asynchronously). */
    private void completeShare(@Nullable String content) {
        if (mView == null || content == null) {
            return;
        }
        shareCode(content);
        mP2pController.markLinkShared();
    }

    /**
     * Auto-pickup: a sender who copied the receiver's reply in a messenger and
     * switched back shouldn't have to find a paste button. Fired from
     * {@link #mFocusListener} on window-focus gain (see its note on the API 29
     * focus requirement). Only while READY with a link out, silent when the
     * clipboard has no reply, and each clip value is tried once — a soft
     * bad-code snack must not re-fire on every focus flip. Android's paste
     * toast makes the read visible to the user; that's honest, not a bug.
     */
    private void maybePickupReplyFromClipboard() {
        if (mView == null || mLast == null || !mLast.linkShared
                || mLast.stage != ShareState.Stage.READY) {
            return;
        }
        // A clip copied before this share started is a reply to an EARLIER
        // offer; feeding it in would only draw a "not valid" message (the
        // engine refuses another offer's answer).
        long copiedAt = ClipboardHelper.primaryClipCopiedAt(getContext());
        if (copiedAt > 0 && copiedAt < mLast.startedAt) {
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

    /* ── the "Send to" list ─────────────────────────────────────────────── */

    private void onDevicesChanged(@NonNull List<P2pNearbyDiscovery.Device> devices) {
        boolean firstFound = mDevices.isEmpty() && !devices.isEmpty();
        mDevices = devices;
        renderRows();
        if (firstFound && !mNearbyOpen && mView != null
                && phase() == P2pSendRows.Phase.READY
                && (mLast == null || mLast.peerDevice == null)) {
            // The folded row now names the phone; a screen reader hears it too.
            mView.findViewById(R.id.p2p_dest_list).announceForAccessibility(
                    foundText(devicesNames()));
        }
    }

    @NonNull
    private List<String> devicesNames() {
        List<String> names = new ArrayList<>(mDevices.size());
        for (P2pNearbyDiscovery.Device device : mDevices) {
            names.add(device.name);
        }
        return names;
    }

    /** "On this Wi-Fi: Ana's Pixel 8, Galaxy A52" — at most three names. */
    @NonNull
    private String foundText(@NonNull List<String> names) {
        int shown = Math.min(3, names.size());
        String joined = String.join(", ", names.subList(0, shown));
        if (names.size() > shown) {
            joined = joined + ", …";
        }
        return getString(R.string.p2p_nearby_found, joined);
    }

    @NonNull
    private P2pSendRows.Phase phase() {
        if (mLast == null) {
            return P2pSendRows.Phase.NONE;
        }
        return switch (mLast.stage) {
            case READY -> P2pSendRows.Phase.READY;
            case CONNECTING, CONNECTED -> P2pSendRows.Phase.CONNECTING;
            default -> P2pSendRows.Phase.NONE;
        };
    }

    @Nullable
    private P2pNearbyDiscovery.Device deviceNamed(@Nullable String name) {
        for (P2pNearbyDiscovery.Device device : mDevices) {
            if (device.name.equals(name)) {
                return device;
            }
        }
        return null;
    }

    /**
     * Draw the "Send to" cards from {@link P2pSendRows}, which owns every
     * rule; this only maps a row to its glyph, words and colours. While the
     * rows' kinds and names are unchanged the cards are BOUND IN PLACE, not
     * rebuilt: the countdown ticks every minute and discovery reports often,
     * and a rebuild loses TalkBack's place and cuts off a press. "Someone
     * next to you" unfolds in place: the phones found (or why none is
     * listed), then the QR.
     */
    private void renderRows() {
        mTickHandler.removeCallbacks(mTick);
        if (mView == null) {
            return;
        }
        LinearLayout list = mView.findViewById(R.id.p2p_dest_list);
        P2pSendRows.Phase phase = phase();
        long now = SystemClock.elapsedRealtime();
        long expiresAt = mLast == null ? 0 : mLast.offerExpiresAt;
        boolean linkShared = mLast != null && mLast.linkShared;
        String peer = mLast == null ? null : mLast.peerDevice;
        List<P2pSendRows.Row> rows = P2pSendRows.build(phase, devicesNames(), peer, linkShared,
                mLinkPreparing, mNearbyOpen, expiresAt, now);

        List<String> keys = new ArrayList<>(rows.size());
        for (P2pSendRows.Row row : rows) {
            keys.add(row.kind + ":" + row.name);
        }
        if (!keys.equals(mRowKeys)) {
            parkNearbyBody();
            list.removeAllViews();
            LayoutInflater inflater = LayoutInflater.from(requireContext());
            for (int i = 0; i < rows.size(); i++) {
                list.addView(inflater.inflate(R.layout.item_p2p_device, list, false));
            }
            mRowKeys = keys;
        }
        View nearbyCard = null;
        P2pSendRows.Row nearbyRow = null;
        for (int i = 0; i < rows.size(); i++) {
            View card = list.getChildAt(i);
            bindRow(card, rows.get(i));
            if (rows.get(i).sub == P2pSendRows.Sub.NEARBY_OPEN) {
                nearbyCard = card;
                nearbyRow = rows.get(i);
            }
        }
        SheetGroups.applyCorners(list);
        updateNearbyBody(nearbyCard, nearbyRow);
        mView.findViewById(R.id.p2p_waiting_note).setVisibility(
                phase == P2pSendRows.Phase.READY && P2pSendRows.somethingOut(phase, peer, linkShared)
                        ? View.VISIBLE : View.GONE);
        if (phase == P2pSendRows.Phase.READY && linkShared && expiresAt > 0) {
            mTickHandler.postDelayed(mTick, P2pSendRows.nextTickMs(expiresAt, now) + 50);
        }
    }

    /** Bind one card. Sets EVERY property it touches, since a card is reused
     *  for the same row from one render to the next. */
    private void bindRow(@NonNull View view, @NonNull P2pSendRows.Row row) {
        ImageView icon = view.findViewById(R.id.p2p_device_icon);
        TextView name = view.findViewById(R.id.p2p_device_name);
        TextView status = view.findViewById(R.id.p2p_device_status);
        CircularProgressIndicator spinner = view.findViewById(R.id.p2p_device_spinner);
        View chevron = view.findViewById(R.id.p2p_device_chevron);

        switch (row.kind) {
            case DEVICE -> {
                icon.setImageResource(R.drawable.ic_p2p_24);
                name.setText(row.name);
            }
            case LINK -> {
                icon.setImageResource(R.drawable.ic_share_24);
                name.setText(R.string.p2p_share_link);
            }
            case NEARBY -> {
                icon.setImageResource(R.drawable.ic_people_24);
                name.setText(R.string.p2p_nearby_title);
            }
        }
        CharSequence statusText = statusText(row);
        status.setText(statusText);
        status.setVisibility(statusText.length() == 0 ? View.GONE : View.VISIBLE);

        int onSurface = MaterialColors.getColor(view, com.google.android.material.R.attr.colorOnSurface);
        int onSurfaceVariant = MaterialColors.getColor(view, com.google.android.material.R.attr.colorOnSurfaceVariant);
        int brand = MaterialColors.getColor(view, R.attr.brandInk);
        if (row.active) {
            // The brand marks the glyph and its disc; the TEXT is the
            // theme-split status ink (coral is too faint for text in light).
            icon.setImageTintList(ColorStateList.valueOf(brand));
            icon.setBackgroundTintList(ColorStateList.valueOf(ColorUtils.setAlphaComponent(brand, 0x29)));
            status.setTextColor(ContextCompat.getColor(view.getContext(), R.color.p2p_status_active));
        } else {
            icon.setImageTintList(ColorStateList.valueOf(onSurface));
            icon.setBackgroundTintList(null);
            status.setTextColor(onSurfaceVariant);
        }

        spinner.setVisibility(row.spinner ? View.VISIBLE : View.GONE);
        chevron.setVisibility(row.tappable ? View.VISIBLE : View.GONE);
        boolean foldable = row.kind == P2pSendRows.Kind.NEARBY && row.tappable;
        float rotation = 0f;
        if (foldable && mNearbyOpen) {
            // The chevron is auto-mirrored: in RTL it points left, so "down"
            // is the opposite turn.
            boolean rtl = view.getLayoutDirection() == View.LAYOUT_DIRECTION_RTL;
            rotation = rtl ? -90f : 90f;
        }
        chevron.setRotation(rotation);

        view.setOnClickListener(row.tappable ? v -> onRowTapped(row) : null);
        view.setClickable(row.tappable);
        // The unfolded body (the QR, its lines) lives inside the card: a tap
        // there must not fold the card. Children that act (the phone cards)
        // take their touch first; what reaches the holder is swallowed.
        view.findViewById(R.id.p2p_device_extra).setOnTouchListener(SWALLOW_TOUCH);
        // Still reachable by a screen reader, which reads its status.
        view.setFocusable(true);
        ViewCompat.removeAccessibilityAction(view, AccessibilityActionCompat.ACTION_EXPAND.getId());
        ViewCompat.removeAccessibilityAction(view, AccessibilityActionCompat.ACTION_COLLAPSE.getId());
        if (foldable) {
            // Announced as an expand / collapse control in the screen
            // reader's own words: the platform actions carry their labels.
            ViewCompat.replaceAccessibilityAction(view,
                    mNearbyOpen ? AccessibilityActionCompat.ACTION_COLLAPSE
                            : AccessibilityActionCompat.ACTION_EXPAND,
                    null, (v, arguments) -> {
                        onRowTapped(row);
                        return true;
                    });
        }
    }

    @NonNull
    private CharSequence statusText(@NonNull P2pSendRows.Row row) {
        return switch (row.sub) {
            case ON_WIFI -> getString(R.string.p2p_on_this_wifi);
            case WAITING_ACCEPT -> getString(R.string.p2p_nearby_sent);
            case LINK_IDLE -> getString(R.string.p2p_share_link_hint);
            case LINK_PREPARING -> getString(R.string.p2p_preparing_link);
            case LINK_SHARED -> row.minutesLeft > 0
                    ? getResources().getQuantityString(R.plurals.p2p_link_open_for,
                            row.minutesLeft, row.minutesLeft)
                    : getString(R.string.p2p_link_shared);
            case NEARBY_IDLE -> getString(R.string.p2p_nearby_hint);
            case NEARBY_FOUND -> foundText(row.names);
            case NEARBY_WAITING -> getString(R.string.p2p_nearby_waiting,
                    row.name == null ? "" : row.name);
            // Open: no second line; what is inside says it.
            case NEARBY_OPEN -> "";
            case CONNECTING -> getString(mLast != null && mLast.stage == ShareState.Stage.CONNECTED
                    ? R.string.p2p_state_connected : R.string.p2p_state_connecting);
        };
    }

    private void onRowTapped(@NonNull P2pSendRows.Row row) {
        switch (row.kind) {
            case DEVICE -> {
                P2pNearbyDiscovery.Device device = deviceNamed(row.name);
                if (device != null) {
                    mP2pController.sendOfferToNearby(device);
                }
            }
            case LINK -> shareLink();
            case NEARBY -> {
                mNearbyOpen = !mNearbyOpen;
                renderRows();
            }
        }
    }

    /** Take the unfolded body out of whatever card holds it, back under the
     *  group (hidden), before the cards are rebuilt. */
    private void parkNearbyBody() {
        if (mNearbyBody == null || mView == null) {
            return;
        }
        ViewGroup home = mView.findViewById(R.id.p2p_ready_group);
        if (mNearbyBody.getParent() != home) {
            if (mNearbyBody.getParent() instanceof ViewGroup parent) {
                parent.removeView(mNearbyBody);
            }
            home.addView(mNearbyBody, home.indexOfChild(mView.findViewById(R.id.p2p_dest_list)) + 1);
        }
        mNearbyBody.setVisibility(View.GONE);
    }

    /**
     * The unfolded "Someone next to you" inside its card ({@code card}, null =
     * folded): the phones found, else the line saying none is listed, then
     * the QR, rendered lazily once per offer code.
     */
    private void updateNearbyBody(@Nullable View card, @Nullable P2pSendRows.Row row) {
        String code = mLast == null ? null : mLast.offerCode;
        if (card == null || row == null || code == null) {
            mNearbyBody.setVisibility(View.GONE);
            return;
        }
        ViewGroup holder = card.findViewById(R.id.p2p_device_extra);
        if (mNearbyBody.getParent() != holder) {
            if (mNearbyBody.getParent() instanceof ViewGroup parent) {
                parent.removeView(mNearbyBody);
            }
            holder.addView(mNearbyBody);
        }
        mNearbyBody.setVisibility(View.VISIBLE);

        boolean anyPhone = !row.children.isEmpty();
        mNearbyBody.findViewById(R.id.p2p_nearby_none).setVisibility(anyPhone ? View.GONE : View.VISIBLE);
        mNearbyBody.findViewById(R.id.p2p_nearby_or_scan).setVisibility(anyPhone ? View.VISIBLE : View.GONE);
        bindPhones(mNearbyBody.findViewById(R.id.p2p_nearby_devices), row.children);

        // With a phone listed the code is the fallback: a smaller tile.
        ImageView qr = mNearbyBody.findViewById(R.id.p2p_qr);
        ConstraintLayout.LayoutParams params = (ConstraintLayout.LayoutParams) qr.getLayoutParams();
        int maxWidth = getResources().getDimensionPixelSize(anyPhone
                ? R.dimen.p2p_qr_size_fallback : R.dimen.p2p_qr_size);
        if (params.matchConstraintMaxWidth != maxWidth) {
            params.matchConstraintMaxWidth = maxWidth;
            qr.setLayoutParams(params);
        }

        if (code.equals(mRenderedQrCode)) {
            return;
        }
        mRenderedQrCode = code;
        // Encode the deep link (not the bare code) so a scan with the phone's
        // own camera offers "open in Firedown"; the in-app scanner unwraps it.
        boolean rendered = setQr(qr, P2pShareController.toDeepLink(code));
        // A code too large for a QR leaves no blank white box: say so and
        // point at the link card above.
        mNearbyBody.findViewById(R.id.p2p_qr_tile).setVisibility(rendered ? View.VISIBLE : View.GONE);
        mNearbyBody.findViewById(R.id.p2p_qr_scan_line).setVisibility(rendered ? View.VISIBLE : View.GONE);
        mNearbyBody.findViewById(R.id.p2p_qr_too_big).setVisibility(rendered ? View.GONE : View.VISIBLE);
    }

    /** The phone cards inside the unfolded body: a group of their own on the
     *  QR tile's tone, bound in place while the names are unchanged. */
    private void bindPhones(@NonNull LinearLayout group, @NonNull List<P2pSendRows.Row> phones) {
        List<String> keys = new ArrayList<>(phones.size());
        for (P2pSendRows.Row phone : phones) {
            keys.add(phone.name);
        }
        if (!keys.equals(mPhoneKeys) || group.getChildCount() != phones.size()) {
            group.removeAllViews();
            LayoutInflater inflater = LayoutInflater.from(requireContext());
            int tone = MaterialColors.getColor(group,
                    com.google.android.material.R.attr.colorSurfaceContainerHighest);
            for (int i = 0; i < phones.size(); i++) {
                MaterialCardView phoneCard = (MaterialCardView) inflater.inflate(
                        R.layout.item_p2p_device, group, false);
                phoneCard.setCardBackgroundColor(tone);
                group.addView(phoneCard);
            }
            mPhoneKeys = keys;
        }
        for (int i = 0; i < phones.size(); i++) {
            bindRow(group.getChildAt(i), phones.get(i));
        }
        SheetGroups.applyCorners(group);
    }

    /* ── rendering ──────────────────────────────────────────────────────── */

    @Override
    protected void render(@Nullable ShareState state) {
        if (mView == null) {
            return;
        }
        MaterialButton stop = mView.findViewById(R.id.p2p_stop);
        TextView tag = mView.findViewById(R.id.p2p_file_tag);
        View doneIcon = mView.findViewById(R.id.p2p_done_icon);
        if (state == null || state.role != ShareState.Role.SEND) {
            // No session (cancelled from the notification while this screen
            // was open, or between stop() and close()): nothing to show.
            mLast = null;
            showStage(R.id.p2p_status_group);
            ((TextView) mView.findViewById(R.id.p2p_status)).setText(R.string.p2p_preparing);
            stop.setVisibility(View.GONE);
            bindFileCard(tag, doneIcon, null);
            setMenuVisible(false);
            renderRows();
            bindFooter(mView, null);
            return;
        }
        mLast = state;
        bindFileCard(tag, doneIcon, state);
        setMenuVisible(state.stage == ShareState.Stage.READY);
        switch (state.stage) {
            case PREPARING, OFFERED -> {
                showStage(R.id.p2p_status_group);
                ((TextView) mView.findViewById(R.id.p2p_status)).setText(R.string.p2p_preparing);
                stop.setVisibility(View.GONE);
            }
            case READY, CONNECTING, CONNECTED -> {
                showStage(R.id.p2p_ready_group);
                boolean out = P2pSendRows.somethingOut(phase(), state.peerDevice, state.linkShared);
                stop.setText(R.string.cancel);
                stop.setVisibility(out ? View.VISIBLE : View.GONE);
            }
            case TRANSFERRING -> {
                showStage(R.id.p2p_background_note);
                stop.setText(R.string.cancel);
                stop.setVisibility(View.VISIBLE);
            }
            case DONE -> {
                showStage(0);
                stop.setText(R.string.p2p_done);
                stop.setVisibility(View.VISIBLE);
            }
            case ERROR -> {
                showStage(R.id.p2p_error_group);
                ((TextView) mView.findViewById(R.id.p2p_error)).setText(errorText(state));
                stop.setVisibility(View.GONE);
            }
        }
        renderRows();
        bindFooter(mView, state);
    }

    /**
     * The file card carries the transfer and the outcome: the bar and numbers
     * under the name while bytes move, the check and "Sent · size" when done.
     */
    private void bindFileCard(@NonNull TextView tag, @NonNull View doneIcon,
                              @Nullable ShareState state) {
        boolean transferring = state != null && state.stage == ShareState.Stage.TRANSFERRING;
        boolean done = state != null && state.stage == ShareState.Stage.DONE;
        mView.findViewById(R.id.p2p_progress_group)
                .setVisibility(transferring ? View.VISIBLE : View.GONE);
        if (transferring) {
            bindProgress(mView, state);
        }
        doneIcon.setVisibility(done ? View.VISIBLE : View.GONE);
        if (done) {
            tag.setText(getString(R.string.p2p_done_sent_detail,
                    Formatter.formatShortFileSize(requireContext(), state.bytes)));
            tag.setTextColor(ContextCompat.getColor(tag.getContext(), R.color.p2p_status_active));
        } else {
            tag.setText(fileTag(state != null && state.fileSize > 0 ? state.fileSize : mStoredSize));
            tag.setTextColor(MaterialColors.getColor(tag,
                    com.google.android.material.R.attr.colorOnSurfaceVariant));
        }
    }

    /** "Video · 11.1 MB"; the type alone while no size is known. */
    @NonNull
    private String fileTag(long size) {
        if (size <= 0) {
            return mMimeWord == null ? "" : mMimeWord;
        }
        String sized = StoragePaths.convertToStringRepresentation(size);
        return mMimeWord != null ? String.format(Locale.ROOT, "%s · %s", mMimeWord, sized) : sized;
    }

    /** The reply fallbacks belong to the waiting offer only. */
    private void setMenuVisible(boolean visible) {
        if (mToolbar == null) {
            return;
        }
        Menu menu = mToolbar.getMenu();
        for (int i = 0; i < menu.size(); i++) {
            menu.getItem(i).setVisible(visible);
        }
    }

    /** Shows one stage group below the file card ({@code 0} = none). */
    private void showStage(int visibleId) {
        int[] stages = {R.id.p2p_status_group, R.id.p2p_ready_group,
                R.id.p2p_background_note, R.id.p2p_error_group};
        for (int id : stages) {
            mView.findViewById(id).setVisibility(id == visibleId ? View.VISIBLE : View.GONE);
        }
    }
}
