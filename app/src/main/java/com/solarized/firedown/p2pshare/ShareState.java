package com.solarized.firedown.p2pshare;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.solarized.firedown.data.entity.DownloadEntity;

/**
 * One share session as the screens and the foreground service see it — a
 * SNAPSHOT published by {@link P2pShareController} on every change (through
 * {@link P2pShareController#getState()}), never the controller's live
 * fields. The screens are windows onto the session, not its owners: a
 * screen that is created while a session is already running renders this
 * snapshot and is indistinguishable from one that started it, which is what
 * lets the sender leave the screen (the service carries the transfer) and
 * come back through the notification.
 *
 * <p>Everything a screen could need to repaint from scratch is here: the
 * role and stage, the file (the sender's {@link DownloadEntity} for its card,
 * the receiver's name/size/mime from the parsed offer), the peer's device
 * name, the offer code, its expiry and the link-shared flag (so a returning
 * sender sees the link row's countdown, not a fresh list), the receiver's
 * fallback reply code, progress, the live path's relayed/direct fact, the
 * error, the received row, and a one-shot {@link #notice} (a declined
 * request, an unreachable device) keyed by {@link #noticeSeq} so a screen
 * shows each once.
 *
 * <p>Mutable on purpose — the controller edits its private copy and
 * publishes {@link #copy()}; observers must treat what they receive as
 * read-only.
 */
public final class ShareState {

    public enum Role { SEND, RECEIVE }

    /**
     * Stages, in the order a session walks them. {@code READY} is the
     * sender's "offer minted, waiting for someone to accept" (the nearby
     * list and the link live here); {@code OFFERED} is the receiver's
     * preview (Accept / Decline). {@code DONE}/{@code ERROR} are TERMINAL:
     * the engine and servers are already torn down, the snapshot stays so
     * the screen can show the outcome until it is dismissed.
     */
    public enum Stage { PREPARING, READY, OFFERED, CONNECTING, CONNECTED, TRANSFERRING, DONE, ERROR }

    /** Error code for an offer nobody accepted within its lifetime. */
    public static final String ERROR_TIMEOUT = "timeout";
    /** Error code for an engine that failed to come up or died mid-share. */
    public static final String ERROR_ENGINE = "engine";
    /** Engine: a path that carried bytes died (the network changed under it). */
    public static final String ERROR_LOST = "lost";
    /** Engine: the channel stayed open but nothing moved for the watchdog's window. */
    public static final String ERROR_STALLED = "stalled";

    @NonNull public Role role;
    @NonNull public Stage stage = Stage.PREPARING;

    /** Sender only: the download being shared (the file card, re-attach). */
    @Nullable public DownloadEntity entity;
    /** The shared file as the OTHER side sees it (offer fields). */
    @Nullable public String fileName;
    public long fileSize;
    @Nullable public String mime;

    /**
     * The other device's name: the sender's {@code dev} for a receiver; for a
     * sender, the nearby device the offer was handed to (null for a link/QR
     * share — the answer code carries no device name).
     */
    @Nullable public String peerDevice;

    /** Sender: the minted {@code FDS1.} offer code (READY onward). */
    @Nullable public String offerCode;
    /** Sender: a link went out — the screen shows the waiting card. */
    public boolean linkShared;
    /**
     * Sender: wall-clock end of the offer's lifetime (READY onward); the link
     * row counts it down. 0 until the offer is minted.
     */
    public long offerExpiresAt;

    /**
     * Receiver: the {@code FDR1.} reply when BOTH push paths (LAN return,
     * rendezvous mailbox) failed and a human has to carry it back. Null in
     * the common case.
     */
    @Nullable public String answerCode;
    /** Receiver: the offer arrived by link/paste (phones apart). */
    public boolean arrivedRemote;
    /** Receiver: the offer arrived from a nearby sender over the LAN. */
    public boolean arrivedNearby;

    public long done;
    public long total;
    public long rate;
    /** A live-connection blip while transferring (bar frozen, not stuck). */
    public boolean reconnecting;
    /** The selected path, once known: TRUE relayed, FALSE direct, null unknown. */
    @Nullable public Boolean relayed;
    /**
     * Whether this session got relay (TURN) credentials: FALSE = the share
     * runs direct+STUN only (offline, the API unreachable, the relay not
     * deployed), so a later no-path names that instead of blaming the
     * network; null until the fetch settled.
     */
    @Nullable public Boolean relayAvailable;

    /** ERROR stage: the engine/controller error code. */
    @Nullable public String errorCode;
    /** DONE stage: bytes moved. */
    public long bytes;
    /** Receiver DONE: the FINISHED download row (Open acts on it). */
    @Nullable public DownloadEntity received;

    /** The receiver said no to a nearby/LAN offer (sender, READY stays). */
    public static final String NOTICE_DECLINED = "declined";
    /** A tapped nearby device did not answer its /offer POST. */
    public static final String NOTICE_UNREACHABLE = "unreachable";
    /** A tapped nearby device is already in another transfer (409). */
    public static final String NOTICE_BUSY = "busy";
    /** A pasted/scanned reply did not parse; the sender's offer stays valid. */
    public static final String NOTICE_BAD_CODE = "bad-code";

    /** One-shot message for the screen (see {@link #noticeSeq}); a NOTICE_* key. */
    @Nullable public String notice;
    /** The notice's argument (the device name for unreachable/busy). */
    @Nullable public String noticeArg;
    public int noticeSeq;
    /**
     * Wall-clock start of this session. The sender's clipboard auto-pickup
     * ignores a clip copied before it, so a reply left over from an earlier
     * share is never fed into this one.
     */
    public long startedAt;

    public ShareState(@NonNull Role role) {
        this.role = role;
        this.startedAt = System.currentTimeMillis();
    }

    @NonNull
    public ShareState copy() {
        ShareState s = new ShareState(role);
        s.stage = stage;
        s.entity = entity;
        s.fileName = fileName;
        s.fileSize = fileSize;
        s.mime = mime;
        s.peerDevice = peerDevice;
        s.offerCode = offerCode;
        s.linkShared = linkShared;
        s.offerExpiresAt = offerExpiresAt;
        s.answerCode = answerCode;
        s.arrivedRemote = arrivedRemote;
        s.arrivedNearby = arrivedNearby;
        s.done = done;
        s.total = total;
        s.rate = rate;
        s.reconnecting = reconnecting;
        s.relayed = relayed;
        s.relayAvailable = relayAvailable;
        s.errorCode = errorCode;
        s.bytes = bytes;
        s.received = received;
        s.notice = notice;
        s.noticeArg = noticeArg;
        s.noticeSeq = noticeSeq;
        s.startedAt = startedAt;
        return s;
    }

    /** True until the session reached DONE or ERROR. */
    public boolean isLive() {
        return stage != Stage.DONE && stage != Stage.ERROR;
    }

    /** True once bytes are moving (a progress event fired, not yet done). */
    public boolean isTransferring() {
        return stage == Stage.TRANSFERRING;
    }

    /**
     * True from the moment both sides committed (the sender's engine applied
     * an answer, the receiver accepted) until the end: CONNECTING, CONNECTED
     * or TRANSFERRING. A new session must not replace one in this phase
     * ({@link P2pSessionPolicy#blocksNewSession}).
     */
    public boolean isInTransfer() {
        return stage == Stage.CONNECTING || stage == Stage.CONNECTED
                || stage == Stage.TRANSFERRING;
    }
}
