package com.solarized.firedown.sync;

import com.solarized.firedown.sync.crypto.BlindSignature;
import com.solarized.firedown.sync.crypto.Hex;
import com.solarized.firedown.sync.crypto.SyncIdentity;

import java.io.IOException;
import java.math.BigInteger;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.List;

/**
 * Orchestrates buying a storage credit: quote → blind → pay → issue → unblind →
 * redeem. Rail-agnostic — the only difference between Lightning and on-chain
 * Bitcoin is the "pay" affordance the UI shows ({@link Session#quote}'s BOLT11
 * {@code payRequest} vs a BIP21 URI + bare {@code address}/{@code amountSats})
 * and how long settlement takes; the quote/issue/redeem path is identical.
 *
 * <p>Two steps so the UI can drive the pay cadence + cancellation:
 * <ol>
 *   <li>{@link #start} opens the quote and blinds a fresh secret (blocking, run on
 *       a background thread). The UI then shows the pay UI from {@code session.quote}.
 *   <li>{@link #issueAndUnblind} is polled: it asks the mint to issue; while
 *       unpaid it returns {@code false}; once paid it unblinds + verifies the
 *       credit onto the session. Then {@link #redeem} burns it at storage for the
 *       balance. Split so a lost redeem retries redeem-only (the mint refuses a
 *       re-issue) — and the whole thing is persistable ({@link PendingPurchase} +
 *       {@link #restore}) so a paid credit survives process death.
 * </ol>
 *
 * <p>The mint only ever sees the BLINDED message, so it cannot link the credit to
 * the payment; storage only ever sees the finished credit, never the payment.
 */
public final class CreditPurchase {

    private final MintClient mint;
    private final StorageApiClient storage;
    private final SecureRandom rng = new SecureRandom();

    public CreditPurchase(MintClient mint, StorageApiClient storage) {
        this.mint = mint;
        this.storage = storage;
    }

    /** A prepared purchase — the quote (for the pay UI) + the client-side blind
     *  state. All of it is persistable (see {@link PendingPurchase}) so a paid
     *  credit survives process death: {@code sig} is the unblinded signature,
     *  null until issue succeeds, then carried so a lost redeem retries
     *  redeem-only (never re-issue, which the mint refuses). */
    public static final class Session {
        public final MintClient.Quote quote;
        private final BlindSignature keyset;
        private final byte[] secret;
        private final BlindSignature.Blinded blinded;
        private BigInteger sig; // unblinded signature; null until issued
        /** On-chain: the mint has SEEN the payment (mempool / short of the
         *  confirmation target) on the last issue poll. Drives the "payment
         *  detected, confirming" state; never true on Lightning. */
        private boolean paymentPending;

        /** The latest {@code expires_at} a pending 425 carried (null until one
         *  did) — the deadline the on-chain wait must honour. */
        private String pendingExpiresAt;

        /** True once an issue poll reported the payment as seen-but-unfinal. */
        public boolean paymentPending() {
            return paymentPending;
        }

        /** The extended expiry reported with the latest pending 425, or null. */
        public String pendingExpiresAt() {
            return pendingExpiresAt;
        }

        Session(MintClient.Quote quote, BlindSignature keyset, byte[] secret, BlindSignature.Blinded blinded) {
            this.quote = quote;
            this.keyset = keyset;
            this.secret = secret;
            this.blinded = blinded;
        }

        // Accessors so PendingPurchase can persist the blind state, and the
        // ViewModel can tell a fresh session (needs issue) from a resumed,
        // already-issued one (redeem-only).
        byte[] secret() {
            return secret;
        }

        BigInteger blindingR() {
            return blinded.r;
        }

        BigInteger blindedValue() {
            return blinded.blinded;
        }

        String keysetIdHex() {
            return Hex.encode(keyset.keysetId());
        }

        /** The unblinded signature once issued (null before). */
        public BigInteger sig() {
            return sig;
        }
    }

    /**
     * Opens a quote for {@code denomGbMonths} on {@code method}
     * ("lightning"|"onchain"|"test"), then blinds a fresh 32-byte secret against the
     * keyset THIS quote will sign with (matched by the quote's keyset id, so a
     * mid-flight key rotation is handled). Blocking — call off the main thread.
     */
    public Session start(int denomGbMonths, String method) throws IOException {
        List<MintClient.Keyset> keys = mint.fetchKeys();
        MintClient.Quote quote = mint.createQuote(denomGbMonths, method);
        return blindFor(keys, quote);
    }

    /**
     * Opens a quote for a SPECIFIC keyset (the plan-grid path — the client picked
     * an exact "Up to X GB for Y" tile) on {@code method}, then blinds a fresh
     * secret against that keyset. Unambiguous even when two tiles share a
     * GB-months value. Blocking — call off the main thread.
     */
    public Session startByKeyset(String keysetIdHex, String method) throws IOException {
        List<MintClient.Keyset> keys = mint.fetchKeys();
        MintClient.Quote quote = mint.createQuoteByKeyset(keysetIdHex, method);
        return blindFor(keys, quote);
    }

    /** Finds the quote's keyset (refetching once on a mid-flight rotation) and
     *  blinds a fresh 32-byte secret against it. */
    private Session blindFor(List<MintClient.Keyset> keys, MintClient.Quote quote) throws IOException {
        MintClient.Keyset keyset = findById(keys, quote.keysetId);
        if (keyset == null) {
            // The quote references a keyset our earlier fetch didn't have (a rotation
            // between the two calls) — refetch once.
            keyset = findById(mint.fetchKeys(), quote.keysetId);
        }
        if (keyset == null) {
            throw new IOException("quote references an unknown keyset");
        }
        // The pay instructions must agree with the amount they are quoted
        // for, before anything is shown to pay (PaymentRequests).
        PaymentRequests.check(quote);

        BlindSignature bs = keyset.blindSignature();
        byte[] secret = new byte[32];
        rng.nextBytes(secret);
        BlindSignature.Blinded blinded = bs.blind(secret, rng);
        return new Session(quote, bs, secret, blinded);
    }

    /**
     * ISSUE step (idempotent-once at the mint). Returns {@code false} while the
     * payment hasn't settled (the caller polls again). Once paid, unblinds +
     * locally verifies the credit and stores it on the session ({@link
     * Session#sig()}). Split from {@link #redeem} on purpose: the mint mints one
     * credit per quote and REFUSES a re-issue with a different blinded message, so
     * once this succeeds the caller must persist the sig and only ever retry
     * REDEEM — never re-issue. Blocking — call off the main thread.
     */
    public boolean issueAndUnblind(Session s) throws IOException {
        if (s.sig != null) {
            return true; // already issued (resumed) — go straight to redeem
        }
        MintClient.IssueOutcome out = mint.issue(s.quote.quoteId, s.blinded.blinded);
        if (!out.paid) {
            // Sticky: once the mint has seen money it has extended the quote, and
            // a later throttled poll that omits the marker must not flip the UI
            // back to "waiting for payment".
            s.paymentPending |= out.pending;
            if (out.pending && out.expiresAt != null && !out.expiresAt.isEmpty()) {
                s.pendingExpiresAt = out.expiresAt;
            }
            return false;
        }
        BigInteger sig = s.keyset.unblind(out.blindSignature, s.blinded.r);
        // Verify locally before spending a redeem round-trip. A bad credit is
        // TERMINAL (a mint bug — retrying won't help), so throw a FatalException,
        // not a bare IOException: the poll loop treats a bare IOException as
        // "keep waiting" but a FatalException as "stop", which is what we want.
        if (!s.keyset.verify(s.secret, sig)) {
            throw new MintClient.FatalException("mint returned an invalid credit", "invalid-credit");
        }
        s.sig = sig;
        return true;
    }

    /**
     * REDEEM step — burns the (already-issued) credit at storage for balance.
     * Idempotent server-side: storage burns {@code sha256(secret)}, so a redeem
     * whose response was lost can be safely retried; the retry returns {@code
     * credit-spent} (a {@link StorageApiClient.FatalException}), which the caller
     * treats as success — the credit WAS applied. Call only after {@link
     * #issueAndUnblind} returned true. Blocking — call off the main thread.
     */
    public StorageApiClient.RedeemResult redeem(SyncIdentity id, Session s) throws IOException {
        if (s.sig == null) {
            throw new IllegalStateException("redeem before issue");
        }
        return storage.redeemCredit(id, s.keysetIdHex(), Hex.encode(s.secret), s.sig.toString(16));
    }

    /**
     * Rebuilds a session from a persisted {@link PendingPurchase} (after process
     * death). Refetches the keyset (by id) to rebuild the verification key, then
     * pairs the stored (blinded, r, secret[, sig]) back onto a session so the
     * caller can resume at issue (unpaid) or redeem (already issued). Blocking.
     */
    public Session restore(PendingPurchase p) throws IOException {
        MintClient.Keyset keyset = findByHex(mint.fetchKeys(), p.keysetIdHex);
        if (keyset == null) {
            throw new IOException("pending purchase references an unknown keyset");
        }
        BlindSignature bs = keyset.blindSignature();
        BlindSignature.Blinded blinded = BlindSignature.blindedFrom(
                new BigInteger(p.blindedHex, 16), new BigInteger(p.rHex, 16));
        MintClient.Quote quote = p.toQuote();
        Session s = new Session(quote, bs, Hex.decode(p.secretHex), blinded);
        if (p.sigHex != null && !p.sigHex.isEmpty()) {
            s.sig = new BigInteger(p.sigHex, 16);
        }
        return s;
    }

    /**
     * Finds a keyset by id — and REFUSES one whose advertised id is not the
     * hash of its own modulus. A keyset id is {@code SHA-256(n)[:8]} by
     * construction (the mint and storage both derive it), so an entry whose
     * modulus does not hash to its id is not a rotation, it is a substituted
     * key: a modulus we would blind a fresh secret against, or verify a
     * resumed credit with, that storage has never heard of — and worse, on
     * resume, a key whose signature the client would happily "verify" while
     * the real keyset's credit is never produced. Ignoring the entry makes
     * both paths fail closed as "unknown keyset".
     */
    private static MintClient.Keyset findById(List<MintClient.Keyset> keys, byte[] id) {
        for (MintClient.Keyset k : keys) {
            if (Arrays.equals(k.id, id)) {
                byte[] derived = k.blindSignature().keysetId();
                if (!Arrays.equals(derived, k.id)) {
                    return null; // id/modulus mismatch: not the key it claims to be
                }
                return k;
            }
        }
        return null;
    }

    private static MintClient.Keyset findByHex(List<MintClient.Keyset> keys, String idHex) {
        return findById(keys, Hex.decode(idHex));
    }

    /** Builds a persistable record from a freshly-started (pre-pay) session, so a
     *  paid credit survives process death. Captures the quote + the full blind
     *  state; {@code sig} is filled in later once issue succeeds
     *  ({@link PendingPurchase#withSig}). */
    public static PendingPurchase toPending(Session s) {
        return PendingPurchase.fromSession(s);
    }
}
