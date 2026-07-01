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
 * redeem. Rail-agnostic — the only difference between Lightning and Stripe is the
 * "pay" affordance the UI shows ({@link Session#quote}'s {@code payRequest} BOLT11
 * vs {@code checkoutUrl} to open in a tab); the quote/issue/redeem path is identical.
 *
 * <p>Two steps so the UI can drive the pay cadence + cancellation:
 * <ol>
 *   <li>{@link #start} opens the quote and blinds a fresh secret (blocking, run on
 *       a background thread). The UI then shows the pay UI from {@code session.quote}.
 *   <li>{@link #tryComplete} is polled: it asks the mint to issue; while unpaid it
 *       returns {@code null}; once paid it unblinds the credit and redeems it at
 *       storage, returning the new balance.
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

    /** A prepared purchase — the quote (for the pay UI) + the client-side blind state. */
    public static final class Session {
        public final MintClient.Quote quote;
        private final BlindSignature keyset;
        private final byte[] secret;
        private final BlindSignature.Blinded blinded;

        Session(MintClient.Quote quote, BlindSignature keyset, byte[] secret, BlindSignature.Blinded blinded) {
            this.quote = quote;
            this.keyset = keyset;
            this.secret = secret;
            this.blinded = blinded;
        }
    }

    /**
     * Opens a quote for {@code denomGbMonths} on {@code method}
     * ("lightning"|"stripe"|"test"), then blinds a fresh 32-byte secret against the
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

        BlindSignature bs = keyset.blindSignature();
        byte[] secret = new byte[32];
        rng.nextBytes(secret);
        BlindSignature.Blinded blinded = bs.blind(secret, rng);
        return new Session(quote, bs, secret, blinded);
    }

    /**
     * One issue attempt. Returns {@code null} while the payment hasn't settled (the
     * caller polls again after a delay). Once paid, unblinds the credit, verifies it
     * locally, and redeems it at storage — returning the new metered balance.
     * Blocking — call off the main thread.
     */
    public StorageApiClient.RedeemResult tryComplete(SyncIdentity id, Session s) throws IOException {
        MintClient.IssueOutcome out = mint.issue(s.quote.quoteId, s.blinded.blinded);
        if (!out.paid) {
            return null;
        }
        BigInteger sig = s.keyset.unblind(out.blindSignature, s.blinded.r);
        // Verify locally before spending a redeem round-trip — a bad credit would
        // just 400 at storage anyway, but this pins the failure to the mint.
        if (!s.keyset.verify(s.secret, sig)) {
            throw new IOException("mint returned an invalid credit");
        }
        String keysetIdHex = Hex.encode(s.keyset.keysetId());
        return storage.redeemCredit(id, keysetIdHex, Hex.encode(s.secret), sig.toString(16));
    }

    private static MintClient.Keyset findById(List<MintClient.Keyset> keys, byte[] id) {
        for (MintClient.Keyset k : keys) {
            if (Arrays.equals(k.id, id)) {
                return k;
            }
        }
        return null;
    }
}
