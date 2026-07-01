package com.solarized.firedown.sync;

import androidx.annotation.NonNull;

import com.solarized.firedown.sync.crypto.BlindSignature;
import com.solarized.firedown.sync.crypto.Hex;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import okhttp3.Interceptor;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/**
 * Client for the Firedown payments mint (mint.firedown.app, cloud-mint-spec.md).
 * UNAUTHENTICATED — the mint never sees accounts (that unlinkability is the whole
 * point), so there is no signing here (unlike {@link StorageApiClient}). It fetches
 * the verification keysets, opens a quote on a rail, and blind-signs the client's
 * blinded message once the payment settles.
 *
 * <p>Every field on the wire is hex ({@link Hex}). The purchase orchestration lives
 * in {@code CreditPurchase}; this class is transport only.
 */
public final class MintClient {

    private static final MediaType JSON = MediaType.parse("application/json; charset=utf-8");

    private static final String PATH_KEYS = "/v1/mint/keys";
    private static final String PATH_QUOTE = "/v1/mint/quote";
    private static final String PATH_ISSUE = "/v1/mint/issue";

    // Cloudflare rate-limits /v1/mint/* per IP; retry 429/503 with short bounded
    // backoff, same shape as StorageApiClient (OkHttp has no 429 handling).
    private static final int MAX_RETRIES = 3;
    private static final long BACKOFF_BASE_MS = 600L;
    private static final long BACKOFF_CAP_MS = 8_000L;

    private final OkHttpClient client;
    private final String baseUrl; // no trailing slash

    public MintClient(OkHttpClient client, String baseUrl) {
        OkHttpClient.Builder b = client.newBuilder();
        b.addInterceptor(new RetryInterceptor());
        this.client = b.build();
        this.baseUrl = stripTrailingSlash(baseUrl);
    }

    // ---- error types (mirror StorageApiClient) ----

    /** A transient failure the caller should retry after {@code retryAfterSeconds}. */
    public static final class TransientException extends IOException {
        public final int retryAfterSeconds;
        TransientException(String msg, int retryAfterSeconds) {
            super(msg);
            this.retryAfterSeconds = retryAfterSeconds;
        }
    }

    /** A permanent failure carrying the mint's error slug (rail-unavailable, …). */
    public static final class FatalException extends IOException {
        public final String slug;
        FatalException(String msg, String slug) {
            super(msg);
            this.slug = slug;
        }
    }

    // ---- result shapes ----

    /** A mint keyset (a denomination's verification key). */
    public static final class Keyset {
        public final byte[] id;           // 8 bytes
        public final int denomGbMonths;
        public final long priceCents;
        public final BigInteger n;
        public final int e;
        public final boolean active;
        Keyset(byte[] id, int denomGbMonths, long priceCents, BigInteger n, int e, boolean active) {
            this.id = id;
            this.denomGbMonths = denomGbMonths;
            this.priceCents = priceCents;
            this.n = n;
            this.e = e;
            this.active = active;
        }

        /** The blind-signature verifier for this keyset. */
        public BlindSignature blindSignature() {
            return new BlindSignature(n, e);
        }
    }

    /** A quote (a purchase in progress) + the rail-specific pay instructions. */
    public static final class Quote {
        public final byte[] quoteId;       // 16 bytes
        public final String method;        // "stripe" | "lightning" | "test"
        public final long amountCents;
        public final int denomGbMonths;
        public final byte[] keysetId;      // 8 bytes
        public final String payRequest;    // Lightning BOLT11 (null otherwise)
        public final String checkoutUrl;   // Stripe hosted Checkout (null otherwise)
        public final boolean autoSettled;  // test rail — already paid
        public final String expiresAt;     // RFC3339
        Quote(byte[] quoteId, String method, long amountCents, int denomGbMonths, byte[] keysetId,
              String payRequest, String checkoutUrl, boolean autoSettled, String expiresAt) {
            this.quoteId = quoteId;
            this.method = method;
            this.amountCents = amountCents;
            this.denomGbMonths = denomGbMonths;
            this.keysetId = keysetId;
            this.payRequest = payRequest;
            this.checkoutUrl = checkoutUrl;
            this.autoSettled = autoSettled;
            this.expiresAt = expiresAt;
        }
    }

    /** Outcome of an issue attempt: either the blind signature, or "keep polling". */
    public static final class IssueOutcome {
        public final boolean paid;
        public final BigInteger blindSignature; // set iff paid
        IssueOutcome(boolean paid, BigInteger blindSignature) {
            this.paid = paid;
            this.blindSignature = blindSignature;
        }
    }

    // ---- endpoints ----

    /** GET the published keysets (verification keys per denomination). */
    public List<Keyset> fetchKeys() throws IOException {
        Request req = new Request.Builder().url(baseUrl + PATH_KEYS).get().build();
        try (Response resp = client.newCall(req).execute()) {
            throwForStatus(resp, "keys");
            try {
                JSONObject obj = new JSONObject(bodyString(resp));
                JSONArray arr = obj.getJSONArray("keysets");
                List<Keyset> out = new ArrayList<>(arr.length());
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject k = arr.getJSONObject(i);
                    out.add(new Keyset(
                            Hex.decode(k.getString("id")),
                            k.getInt("denomination_gb_months"),
                            k.optLong("price_cents", 0),
                            new BigInteger(k.getString("n"), 16),
                            k.getInt("e"),
                            k.optBoolean("active", false)));
                }
                return out;
            } catch (org.json.JSONException e) {
                throw new IOException("malformed keys response", e);
            }
        }
    }

    /** POST a quote for a denomination on the given rail ("stripe"|"lightning"|"test"). */
    public Quote createQuote(int denomGbMonths, String method) throws IOException {
        JSONObject body = new JSONObject();
        try {
            body.put("denomination_gb_months", denomGbMonths);
            body.put("method", method);
        } catch (org.json.JSONException e) {
            throw new IOException("quote body", e);
        }
        byte[] bodyBytes = body.toString().getBytes(StandardCharsets.UTF_8);
        Request req = new Request.Builder()
                .url(baseUrl + PATH_QUOTE)
                .post(RequestBody.create(bodyBytes, JSON))
                .build();
        try (Response resp = client.newCall(req).execute()) {
            throwForStatus(resp, "quote");
            try {
                JSONObject o = new JSONObject(bodyString(resp));
                return new Quote(
                        Hex.decode(o.getString("quote_id")),
                        o.getString("method"),
                        o.optLong("amount_cents", 0),
                        o.optInt("denomination_gb_months", denomGbMonths),
                        Hex.decode(o.getString("keyset_id")),
                        o.optString("pay_request", null),
                        o.optString("checkout_url", null),
                        o.optBoolean("auto_settled", false),
                        o.optString("expires_at", null));
            } catch (org.json.JSONException e) {
                throw new IOException("malformed quote response", e);
            }
        }
    }

    /**
     * POST issue: ask the mint to blind-sign {@code blinded} for {@code quoteId}.
     * Returns {@code paid=false} while the payment hasn't settled (the mint's
     * "not-paid-yet" 425 — the caller retries); {@code paid=true} with the blind
     * signature once paid. Any other 4xx (already-issued, no-such-quote) throws.
     */
    public IssueOutcome issue(byte[] quoteId, BigInteger blinded) throws IOException {
        JSONObject body = new JSONObject();
        try {
            body.put("quote_id", Hex.encode(quoteId));
            body.put("blinded_message", blinded.toString(16));
        } catch (org.json.JSONException e) {
            throw new IOException("issue body", e);
        }
        byte[] bodyBytes = body.toString().getBytes(StandardCharsets.UTF_8);
        Request req = new Request.Builder()
                .url(baseUrl + PATH_ISSUE)
                .post(RequestBody.create(bodyBytes, JSON))
                .build();
        try (Response resp = client.newCall(req).execute()) {
            int code = resp.code();
            if (code == 200) {
                try {
                    JSONObject o = new JSONObject(bodyString(resp));
                    return new IssueOutcome(true, new BigInteger(o.getString("blind_signature"), 16));
                } catch (org.json.JSONException e) {
                    throw new IOException("malformed issue response", e);
                }
            }
            // 425 Too Early / slug "not-paid-yet" → keep polling.
            String slug = readErrorSlug(resp);
            if (code == 425 || "not-paid-yet".equals(slug)) {
                return new IssueOutcome(false, null);
            }
            if (code == 429 || code == 503) {
                throw new TransientException("issue: " + code + " " + slug, parseInt(resp.header("Retry-After"), 0));
            }
            if (code >= 500) {
                throw new TransientException("issue: server " + code, 0);
            }
            throw new FatalException("issue: " + code + " " + slug, slug);
        }
    }

    // ---- helpers (mirror StorageApiClient) ----

    private static final class RetryInterceptor implements Interceptor {
        @NonNull
        @Override
        public Response intercept(@NonNull Chain chain) throws IOException {
            Request req = chain.request();
            Response resp = chain.proceed(req);
            int attempt = 0;
            while ((resp.code() == 429 || resp.code() == 503) && attempt < MAX_RETRIES) {
                int retryAfter = parseInt(resp.header("Retry-After"), 0);
                resp.close();
                if (chain.call().isCanceled()) {
                    throw new IOException("cancelled during rate-limit backoff");
                }
                long exp = Math.min(BACKOFF_CAP_MS, BACKOFF_BASE_MS << attempt);
                long delay = Math.min(BACKOFF_CAP_MS, Math.max(exp, (long) Math.max(0, retryAfter) * 1000L));
                try {
                    Thread.sleep(delay);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IOException("interrupted during rate-limit backoff", e);
                }
                attempt++;
                resp = chain.proceed(req);
            }
            return resp;
        }
    }

    private void throwForStatus(Response resp, String op) throws IOException {
        int code = resp.code();
        if (code >= 200 && code < 300) {
            return;
        }
        String slug = readErrorSlug(resp);
        if (code == 429 || code == 503) {
            throw new TransientException(op + ": " + code + " " + slug, parseInt(resp.header("Retry-After"), 0));
        }
        if (code >= 500) {
            throw new TransientException(op + ": server " + code, 0);
        }
        throw new FatalException(op + ": " + code + " " + slug, slug);
    }

    private static String readErrorSlug(Response resp) {
        try {
            String s = bodyString(resp);
            if (s != null && s.startsWith("{")) {
                return new JSONObject(s).optString("error", "");
            }
        } catch (Exception ignored) {
            // no parseable body
        }
        return "";
    }

    private static String bodyString(Response resp) throws IOException {
        return resp.body() != null ? resp.body().string() : "";
    }

    private static int parseInt(String s, int def) {
        try {
            return s == null ? def : Integer.parseInt(s.trim());
        } catch (NumberFormatException e) {
            return def;
        }
    }

    private static String stripTrailingSlash(String url) {
        if (url == null || url.isEmpty()) {
            return "https://mint.firedown.app";
        }
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }
}
