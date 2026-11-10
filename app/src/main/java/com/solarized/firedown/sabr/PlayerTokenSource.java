package com.solarized.firedown.sabr;

/**
 * Which of the page player's PO tokens one download may still use.
 *
 * <p>The page's own player sends a token in its stream requests, and the
 * stream server accepts it where it keeps a token minted in the hidden page
 * pending. A download takes the newest one the player sent, once: a token
 * the server answered with an attestation demand is not offered again, so
 * the caller falls back to minting, and a newer one the player sends later
 * is offered when the server next asks.
 */
public final class PlayerTokenSource {

    private String lastUsed;

    /**
     * @param latest the newest token the page's player sent for the video,
     *               or null
     * @return {@code latest} when this download has not used it yet, else
     *         null
     */
    public String next(String latest) {
        if (latest == null || latest.isEmpty() || latest.equals(lastUsed)) {
            return null;
        }
        lastUsed = latest;
        return latest;
    }
}
