package com.solarized.firedown.sync;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * Maps a purchase-flow failure to what the buy screen should SAY — switched on
 * the mint's error SLUG, never the HTTP status (the repo rule: the slug is
 * authoritative, the status advisory). Pure so it is unit-testable; the
 * ViewModel only turns a {@link Kind} into a string resource.
 *
 * <p>The bug this exists for: every 503 used to read "Too many requests just
 * now" — including {@code rail-unavailable}, whose {@code detail} ("On-chain
 * payments are temporarily unavailable while the Bitcoin node syncs. Pay with
 * Lightning, or try again later.") is written for the user and was never shown.
 */
public final class PurchaseError {

    public enum Kind {
        /** rate-limited / server-busy (or a bare 429/5xx): try again in a minute. */
        BUSY,
        /** The rail can't open a quote right now; {@link #detail} says why. */
        RAIL_UNAVAILABLE,
        /** An unpaid quote outlived its TTL; start again. */
        QUOTE_EXPIRED,
        /** Any other slug the server named ({@link #slug}). */
        OTHER_SLUG,
        /** No server answer at all — a network failure. */
        NETWORK
    }

    public final Kind kind;
    /** The server's user-facing explanation (RAIL_UNAVAILABLE), else null. */
    @Nullable
    public final String detail;
    /** The slug (OTHER_SLUG), else null. */
    @Nullable
    public final String slug;

    private PurchaseError(Kind kind, @Nullable String detail, @Nullable String slug) {
        this.kind = kind;
        this.detail = detail;
        this.slug = slug;
    }

    @NonNull
    public static PurchaseError classify(@NonNull Exception e) {
        if (e instanceof MintClient.TransientException
                || e instanceof StorageApiClient.TransientException) {
            return new PurchaseError(Kind.BUSY, null, null);
        }
        if (e instanceof MintClient.FatalException) {
            MintClient.FatalException fe = (MintClient.FatalException) e;
            return fromSlug(fe.slug, fe.detail);
        }
        if (e instanceof StorageApiClient.FatalException) {
            return fromSlug(((StorageApiClient.FatalException) e).slug, null);
        }
        return new PurchaseError(Kind.NETWORK, null, null);
    }

    @NonNull
    static PurchaseError fromSlug(@Nullable String slug, @Nullable String detail) {
        if (MintClient.SLUG_RATE_LIMITED.equals(slug) || MintClient.SLUG_SERVER_BUSY.equals(slug)) {
            return new PurchaseError(Kind.BUSY, null, null);
        }
        if (MintClient.SLUG_RAIL_UNAVAILABLE.equals(slug)) {
            return new PurchaseError(Kind.RAIL_UNAVAILABLE, detail, slug);
        }
        if (MintClient.SLUG_QUOTE_EXPIRED.equals(slug)) {
            return new PurchaseError(Kind.QUOTE_EXPIRED, null, slug);
        }
        if (slug != null && !slug.isEmpty()) {
            return new PurchaseError(Kind.OTHER_SLUG, null, slug);
        }
        return new PurchaseError(Kind.NETWORK, null, null);
    }
}
