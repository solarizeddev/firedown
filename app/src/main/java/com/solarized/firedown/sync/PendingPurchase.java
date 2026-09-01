package com.solarized.firedown.sync;

import android.content.Context;

import com.solarized.firedown.sync.crypto.Hex;

import org.json.JSONException;
import org.json.JSONObject;

import java.math.BigInteger;

/**
 * A durable snapshot of an in-flight credit purchase, so a paid credit is never
 * lost if the app dies / is backgrounded / loses connection between paying and
 * redeeming. It captures the quote + the FULL client-side blind state
 * ({@code secret}, blinding factor {@code r}, the {@code blinded} value sent to
 * the mint) and — once issue succeeds — the unblinded {@code sig}. From it,
 * {@link CreditPurchase#restore} rebuilds a session and resumes: issue (if still
 * unpaid) then redeem, or straight to redeem when {@code sig} is already present.
 *
 * <p><b>It is a bearer credit in flight</b> — the {@code secret} + {@code sig}
 * ARE the money. So it lives in the same keystore-wrapped, backup-EXCLUDED
 * {@code secret_shared_prefs} as the recovery code ({@link SyncSecrets} blob
 * methods): it must survive process death but must NEVER be backed up to the
 * cloud. Cleared only after a redeem returns success OR {@code credit-spent}
 * (both mean the credit was applied).
 */
public final class PendingPurchase {

    /** One purchase in flight at a time (the wizard is single-flow). */
    private static final String BLOB_NAME = "purchase.pending";

    // Quote (mirrors MintClient.Quote) — enough to rebuild it on resume.
    public final String quoteIdHex;
    public final String method;
    public final long amountCents;
    public final int denomGbMonths;
    public final int sizeGb;
    public final int durationMonths;
    public final String keysetIdHex;
    public final String payRequest;  // nullable (Lightning BOLT11)
    public final String checkoutUrl; // nullable (Stripe hosted Checkout)
    public final String expiresAt;   // nullable (RFC3339)

    // Client-side blind state (all hex).
    public final String secretHex;
    public final String rHex;
    public final String blindedHex;
    public final String sigHex; // nullable — set once issue succeeds

    /** True once the user SUBMITTED the payment (the Checkout success redirect
     *  was seen / the connected wallet reported the invoice paid) — i.e. money
     *  is plausibly in flight even though issue hasn't confirmed it yet. The
     *  ViewModel's leave-the-wizard cleanup only drops sig-less records that
     *  are ALSO unsubmitted: a submitted record still holds the only blinding
     *  secret for a charge that may settle any second, and clearing it would
     *  destroy the credit (the money-loss window between paying and issue). */
    public final boolean submitted;

    PendingPurchase(String quoteIdHex, String method, long amountCents, int denomGbMonths,
                    int sizeGb, int durationMonths, String keysetIdHex, String payRequest,
                    String checkoutUrl, String expiresAt, String secretHex, String rHex,
                    String blindedHex, String sigHex, boolean submitted) {
        this.quoteIdHex = quoteIdHex;
        this.method = method;
        this.amountCents = amountCents;
        this.denomGbMonths = denomGbMonths;
        this.sizeGb = sizeGb;
        this.durationMonths = durationMonths;
        this.keysetIdHex = keysetIdHex;
        this.payRequest = payRequest;
        this.checkoutUrl = checkoutUrl;
        this.expiresAt = expiresAt;
        this.secretHex = secretHex;
        this.rHex = rHex;
        this.blindedHex = blindedHex;
        this.sigHex = sigHex;
        this.submitted = submitted;
    }

    /** Builds a record from a freshly-started (pre-pay) session (sig not yet known). */
    static PendingPurchase fromSession(CreditPurchase.Session s) {
        MintClient.Quote q = s.quote;
        return new PendingPurchase(
                Hex.encode(q.quoteId), q.method, q.amountCents, q.denomGbMonths,
                q.sizeGb, q.durationMonths, s.keysetIdHex(), q.payRequest, q.checkoutUrl,
                q.expiresAt, Hex.encode(s.secret()), s.blindingR().toString(16),
                s.blindedValue().toString(16),
                s.sig() != null ? s.sig().toString(16) : null, false);
    }

    /** A copy with the unblinded signature filled in — persisted after issue so a
     *  crash mid-redeem resumes at redeem-only (never re-issue). */
    public PendingPurchase withSig(BigInteger sig) {
        return new PendingPurchase(quoteIdHex, method, amountCents, denomGbMonths, sizeGb,
                durationMonths, keysetIdHex, payRequest, checkoutUrl, expiresAt, secretHex,
                rHex, blindedHex, sig.toString(16), submitted);
    }

    /** A copy marked payment-submitted — see {@link #submitted}. */
    public PendingPurchase withSubmitted() {
        return new PendingPurchase(quoteIdHex, method, amountCents, denomGbMonths, sizeGb,
                durationMonths, keysetIdHex, payRequest, checkoutUrl, expiresAt, secretHex,
                rHex, blindedHex, sigHex, true);
    }

    /** Rebuilds the MintClient.Quote for resume (autoSettled irrelevant here). */
    MintClient.Quote toQuote() {
        return new MintClient.Quote(Hex.decode(quoteIdHex), method, amountCents, denomGbMonths,
                sizeGb, durationMonths, Hex.decode(keysetIdHex), payRequest, checkoutUrl,
                false, expiresAt);
    }

    // ---- persistence (keystore-wrapped, backup-excluded) ----

    /** Persists this record (overwriting any existing one). */
    public void save(Context context) {
        new SyncSecrets(context).putBlob(BLOB_NAME, toJson().getBytes());
    }

    /** Loads the pending purchase, or null if none / unreadable. */
    public static PendingPurchase load(Context context) {
        byte[] bytes = new SyncSecrets(context).getBlob(BLOB_NAME);
        if (bytes == null) {
            return null;
        }
        try {
            return fromJson(new String(bytes));
        } catch (JSONException e) {
            return null;
        }
    }

    /** Removes the pending purchase (after redeem success / credit-spent). */
    public static void clear(Context context) {
        new SyncSecrets(context).removeBlob(BLOB_NAME);
    }

    String toJson() {
        try {
            JSONObject o = new JSONObject();
            o.put("quote_id", quoteIdHex);
            o.put("method", method);
            o.put("amount_cents", amountCents);
            o.put("denom_gb_months", denomGbMonths);
            o.put("size_gb", sizeGb);
            o.put("duration_months", durationMonths);
            o.put("keyset_id", keysetIdHex);
            if (payRequest != null) o.put("pay_request", payRequest);
            if (checkoutUrl != null) o.put("checkout_url", checkoutUrl);
            if (expiresAt != null) o.put("expires_at", expiresAt);
            o.put("secret", secretHex);
            o.put("r", rHex);
            o.put("blinded", blindedHex);
            if (sigHex != null) o.put("sig", sigHex);
            if (submitted) o.put("submitted", true);
            return o.toString();
        } catch (JSONException e) {
            throw new IllegalStateException("serialize pending purchase", e);
        }
    }

    static PendingPurchase fromJson(String json) throws JSONException {
        JSONObject o = new JSONObject(json);
        return new PendingPurchase(
                o.getString("quote_id"), o.getString("method"), o.getLong("amount_cents"),
                o.getInt("denom_gb_months"), o.getInt("size_gb"), o.getInt("duration_months"),
                o.getString("keyset_id"),
                o.has("pay_request") ? o.getString("pay_request") : null,
                o.has("checkout_url") ? o.getString("checkout_url") : null,
                o.has("expires_at") ? o.getString("expires_at") : null,
                o.getString("secret"), o.getString("r"), o.getString("blinded"),
                o.has("sig") ? o.getString("sig") : null,
                o.optBoolean("submitted", false));
    }
}
