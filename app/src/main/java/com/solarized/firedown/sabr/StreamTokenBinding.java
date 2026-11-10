package com.solarized.firedown.sabr;

/**
 * Which identifier one SABR download's PO token is minted over, and when to
 * switch to the other one.
 *
 * <p>The Google Video Server checks the token against ONE identifier, the
 * video id or the visitor data, depending on a player experiment of the
 * session that produced the streaming URL. Nothing on the request says
 * which, and a token over the wrong one is not refused at once: the server
 * answers status 2 ("attestation pending") from the first response, serves
 * about a minute of media, then demands attestation (status 3) and refuses
 * every token over that identifier however fresh. So the binding is tried:
 * start from the one the server last accepted in this process, and on a
 * demand decide from what the server said about the current token.
 *
 * <p>Pure: no Android, no network. One instance per download, used from the
 * download's worker thread; only {@link #sAccepted} is shared.
 */
public final class StreamTokenBinding {

    public enum Kind {
        VIDEO, VISITOR;

        public Kind other() {
            return this == VIDEO ? VISITOR : VIDEO;
        }
    }

    /** The binding the server last accepted, in any download of the process. */
    private static volatile Kind sAccepted = Kind.VIDEO;

    private final boolean hasVisitorData;
    private Kind current;
    /** A token over {@link #current} was minted and applied. */
    private boolean minted;
    /** The binding the server accepted earlier in THIS download. */
    private Kind proven;
    /** A binding whose fresh token was refused with no progress in THIS
     *  download: media on it proves nothing any more. */
    private Kind givenUp;

    /**
     * @param hasVisitorData false when the capture carried no visitor data:
     *                       the video id is then the only identifier.
     */
    public StreamTokenBinding(boolean hasVisitorData) {
        this.hasVisitorData = hasVisitorData;
        this.current = hasVisitorData ? sAccepted : Kind.VIDEO;
    }

    /** The binding to mint over now. */
    public Kind current() {
        return current;
    }

    /** A token over {@link #current()} was minted and applied. */
    public void onMinted() {
        minted = true;
    }

    /**
     * The server demanded attestation (status 3). Returns the binding the
     * fresh token must be minted over, which becomes {@link #current()}.
     *
     * @param accepted    media arrived on the current token while the server
     *                    reported nothing about it
     * @param pending     the server reported status 2 since the current
     *                    token was applied
     * @param consecutive demands in a row with no progress between them,
     *                    this one included
     */
    public Kind onDemand(boolean accepted, boolean pending, int consecutive) {
        if (!minted) {
            // No token of this binding ever reached the server (the first
            // mint failed): the demand says nothing about the binding.
            return current;
        }
        boolean refusedTwice = consecutive >= 2;
        if (refusedTwice) {
            // A fresh token over this binding was refused with no progress.
            // Whatever media it moved before (a server that serves a wrong
            // token for a while without saying "pending") proved nothing.
            givenUp = current;
            if (proven == current) {
                proven = null;
            }
        } else if (accepted && current != givenUp) {
            proven = current;
        }
        if (!hasVisitorData) {
            return current;
        }
        if (proven != null) {
            // The binding does not change during a download: a demand on
            // (or after a detour from) the proven binding is the ordinary
            // mid-stream one, answered with a fresh token over it.
            current = proven;
            return current;
        }
        if (pending || refusedTwice) {
            // Each further refusal in a row goes back to the other one, so
            // three attempts are: this binding fresh, the other, this again.
            current = current.other();
            minted = false;
        }
        return current;
    }

    /**
     * The download ended, finished or not. Remembers the binding for the
     * next download when the server accepted it.
     *
     * @param accepted as in {@link #onDemand}, for the token carried last
     */
    public void onEnded(boolean accepted) {
        if (minted && accepted) {
            sAccepted = current;
        } else if (proven != null) {
            sAccepted = proven;
        }
    }

    /** The binding the next download starts from. */
    public static Kind lastAccepted() {
        return sAccepted;
    }

    /** Tests only. */
    static void resetForTest(Kind kind) {
        sAccepted = kind;
    }
}
