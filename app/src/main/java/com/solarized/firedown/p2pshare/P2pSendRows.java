package com.solarized.firedown.p2pshare;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * The Send screen's one "Send to" list: nearby phones, a link, a QR. Every
 * way out is a row, and what happened after a tap is that row's second line.
 * Pure Java (primitives in, rows out) so {@code scripts/p2p-harness} checks
 * the rules on a plain JVM; {@code P2pSendFragment} only maps each row to
 * views and strings.
 *
 * <p>The rules:
 * <ul>
 *   <li>Rows exist only while the offer waits ({@code READY}) or connects.
 *       Before the offer is minted the screen shows its preparing state; once
 *       bytes move the progress belongs to the file card, not to a list.</li>
 *   <li>Nothing spins at rest. Nearby discovery never ends, so with nobody
 *       found the list shows a still, untappable hint row. The one spinner is
 *       on the connecting row, bounded by the connect timeout.</li>
 *   <li>While connecting the list collapses to the one peer: the nearby phone
 *       the offer went to, else the link if one went out, else the QR.</li>
 *   <li>A phone the offer went to keeps its waiting row until it answers,
 *       even when it drops out of discovery.</li>
 *   <li>The link row counts the offer's remaining lifetime down in whole
 *       minutes, never below one while the offer is still open.</li>
 * </ul>
 */
public final class P2pSendRows {

    private P2pSendRows() {
    }

    public enum Kind {
        /** A phone with Receive open on this Wi-Fi. */
        DEVICE,
        /** Nobody found yet: the hint row, not tappable. */
        EMPTY,
        /** Share the offer as a link through any app. */
        LINK,
        /** Show the offer as a QR code (expands in place). */
        QR
    }

    /** A row's second line. */
    public enum Sub {
        /** A found phone, nothing sent to it. */
        ON_WIFI,
        /** The offer went to this phone; its answer is awaited. */
        WAITING_ACCEPT,
        /** The empty row's instruction. */
        NEARBY_HINT,
        /** The link row before anything went out. */
        LINK_IDLE,
        /** The short link is being brokered (bounded by the controller). */
        LINK_PREPARING,
        /** A link went out; {@link Row#minutesLeft} is the countdown. */
        LINK_SHARED,
        /** The QR row, folded. */
        QR_IDLE,
        /** The QR row, unfolded: how to scan it. */
        QR_OPEN,
        /** They accepted; the connection is being set up. */
        CONNECTING
    }

    public static final class Row {
        @NonNull public final Kind kind;
        /** The device name for {@link Kind#DEVICE}, else null. */
        @Nullable public final String name;
        @NonNull public final Sub sub;
        /** A tap does something. */
        public final boolean tappable;
        /** The row holds the session's attention (coral icon, coral subtitle). */
        public final boolean active;
        /** The one bounded spinner (connecting). */
        public final boolean spinner;
        /** {@link Sub#LINK_SHARED} only: whole minutes left, at least 1; 0 when
         *  the offer's expiry is unknown (the row then says "Shared" with no
         *  countdown). */
        public final int minutesLeft;

        Row(@NonNull Kind kind, @Nullable String name, @NonNull Sub sub, boolean tappable,
            boolean active, boolean spinner, int minutesLeft) {
            this.kind = kind;
            this.name = name;
            this.sub = sub;
            this.tappable = tappable;
            this.active = active;
            this.spinner = spinner;
            this.minutesLeft = minutesLeft;
        }

        @NonNull
        @Override
        public String toString() {
            return kind + (name == null ? "" : "(" + name + ")") + ":" + sub
                    + (tappable ? "" : " untappable") + (active ? " active" : "")
                    + (spinner ? " spin" : "") + (minutesLeft > 0 ? " " + minutesLeft + "min" : "");
        }
    }

    /** The screen stages the list cares about (a narrowing of ShareState.Stage). */
    public enum Phase {
        /** Offer not minted yet, or a terminal / transfer stage: no list. */
        NONE,
        /** The offer waits for an accept. */
        READY,
        /** They accepted; connecting. */
        CONNECTING
    }

    /**
     * @param phase        where the session is
     * @param devices      names of the phones found nearby, in discovery order
     * @param pendingPeer  the nearby phone the offer was handed to, or null
     * @param linkShared   a link went out through the share sheet
     * @param linkPreparing the short link is being brokered right now
     * @param qrOpen       the QR row is unfolded
     * @param expiresAt    end of the offer's lifetime on the elapsed-realtime
     *                     clock, 0 = unknown
     * @param now          the same clock now
     */
    @NonNull
    public static List<Row> build(@NonNull Phase phase,
                                  @NonNull List<String> devices,
                                  @Nullable String pendingPeer,
                                  boolean linkShared,
                                  boolean linkPreparing,
                                  boolean qrOpen,
                                  long expiresAt,
                                  long now) {
        if (phase == Phase.NONE) {
            return Collections.emptyList();
        }
        List<Row> rows = new ArrayList<>();
        if (phase == Phase.CONNECTING) {
            if (pendingPeer != null && !pendingPeer.isEmpty()) {
                rows.add(new Row(Kind.DEVICE, pendingPeer, Sub.CONNECTING, false, true, true, 0));
            } else if (linkShared) {
                rows.add(new Row(Kind.LINK, null, Sub.CONNECTING, false, true, true, 0));
            } else {
                // No phone was asked and no link went out: the QR was scanned
                // (or a reply pasted) — never "Share a link · Connecting…".
                rows.add(new Row(Kind.QR, null, Sub.CONNECTING, false, true, true, 0));
            }
            return rows;
        }
        // The phone the offer went to keeps its waiting row even when it drops
        // out of discovery (its screen went off): the request is still out.
        boolean pendingListed = pendingPeer == null || pendingPeer.isEmpty()
                || devices.contains(pendingPeer);
        if (!pendingListed) {
            rows.add(new Row(Kind.DEVICE, pendingPeer, Sub.WAITING_ACCEPT, false, true, false, 0));
        }
        if (devices.isEmpty() && pendingListed) {
            rows.add(new Row(Kind.EMPTY, null, Sub.NEARBY_HINT, false, false, false, 0));
        }
        for (String device : devices) {
            boolean waiting = device.equals(pendingPeer);
            // A second tap on the phone already asked would only meet it busy
            // with our own first request.
            rows.add(new Row(Kind.DEVICE, device,
                    waiting ? Sub.WAITING_ACCEPT : Sub.ON_WIFI,
                    !waiting, waiting, false, 0));
        }
        if (linkPreparing) {
            rows.add(new Row(Kind.LINK, null, Sub.LINK_PREPARING, false, true, false, 0));
        } else if (linkShared) {
            rows.add(new Row(Kind.LINK, null, Sub.LINK_SHARED, true, true, false,
                    minutesLeft(expiresAt, now)));
        } else {
            rows.add(new Row(Kind.LINK, null, Sub.LINK_IDLE, true, false, false, 0));
        }
        rows.add(new Row(Kind.QR, null, qrOpen ? Sub.QR_OPEN : Sub.QR_IDLE, true, qrOpen, false, 0));
        return rows;
    }

    /**
     * Whole minutes left in the offer, rounded UP so "14 min" is shown for
     * 13:01 and the last minute reads 1, never 0 (at 0 the controller ends
     * the session). An unknown expiry (0) returns 0, which means "no
     * countdown": none is invented.
     */
    public static int minutesLeft(long expiresAt, long now) {
        if (expiresAt <= 0) {
            return 0;
        }
        long left = expiresAt - now;
        if (left <= 0) {
            return 1;
        }
        long minutes = (left + 59_999) / 60_000;
        return (int) Math.max(1, Math.min(Integer.MAX_VALUE, minutes));
    }

    /** True when the screen should offer Cancel: something has gone out. */
    public static boolean somethingOut(@NonNull Phase phase, @Nullable String pendingPeer,
                                       boolean linkShared) {
        return phase == Phase.CONNECTING
                || (phase == Phase.READY && (linkShared
                        || (pendingPeer != null && !pendingPeer.isEmpty())));
    }

    /** Milliseconds until the countdown's text next changes (for the screen's tick). */
    public static long nextTickMs(long expiresAt, long now) {
        if (expiresAt <= 0) {
            return 60_000;
        }
        long left = expiresAt - now;
        if (left <= 0) {
            return 60_000;
        }
        long intoMinute = left % 60_000;
        return intoMinute == 0 ? 60_000 : intoMinute;
    }
}
