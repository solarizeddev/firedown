package com.solarized.firedown.sync;

import androidx.annotation.NonNull;

import com.solarized.firedown.okhttp.RateLimitInterceptor;
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
        // Derive from the shared client but DROP the global RateLimitInterceptor
        // (same as StorageApiClient). The mint has its OWN bounded RetryInterceptor
        // for 429/503; keeping the global one too NESTS two client-side retry loops,
        // which on a mint 429 (the per-IP quote limiter: burst 20, then 60/hour)
        // fires up to ~9 requests for a single tap — draining the limiter further
        // AND stacking both backoffs into a multi-minute hang (the "empty spinner"
        // on Continue). One request per call; a persistent 429 fails fast instead.
        OkHttpClient.Builder b = client.newBuilder();
        b.interceptors().removeIf(i -> i instanceof RateLimitInterceptor);
        // OUTERMOST, not appended: a retry must re-run whatever inner
        // interceptors prepare the request, and a test's scripted transport
        // (an interceptor that answers instead of proceeding) must sit INSIDE
        // the retry so the bounded-retry behaviour is what the tests exercise.
        b.interceptors().add(0, new RetryInterceptor());
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

    /** Issue refused because the UNPAID quote outlived its TTL — nothing was ever
     *  charged, so a pending purchase holding this quote is safe to discard. */
    public static final String SLUG_QUOTE_EXPIRED = "quote-expired";

    /** Issue refused because the mint has already minted this quote's ONE credit
     *  for a DIFFERENT blinded message (409). A replay with the same message
     *  re-signs instead, so this only fires on a corrupted/foreign record. */
    public static final String SLUG_ALREADY_ISSUED = "already-issued";

    /** Quote refused because the mint can't open one on that rail right now (503):
     *  the on-chain node is syncing, or the operator disabled the rail. Its
     *  {@code detail} is user-facing and says why — show it. NOT a rate limit,
     *  even though it rides a 503: the slug is authoritative, the status advisory. */
    public static final String SLUG_RAIL_UNAVAILABLE = "rail-unavailable";

    /** The ONLY two slugs that mean "too many requests / try again in a minute"
     *  (the per-IP limiter, and the challenge store at capacity). */
    public static final String SLUG_RATE_LIMITED = "rate-limited";
    public static final String SLUG_SERVER_BUSY = "server-busy";

    /** Issue's 425 while the payment hasn't settled. On the on-chain rail the
     *  same reply carries {@code pending:true} + an extended {@code expires_at}
     *  once the mint has SEEN the payment (mempool / short of the confirmation
     *  target) — see {@link IssueOutcome#pending}. */
    public static final String SLUG_NOT_PAID_YET = "not-paid-yet";

    /** A permanent failure carrying the mint's error slug (rail-unavailable, …)
     *  and, when the server sent one, its user-facing {@code detail}. */
    public static final class FatalException extends IOException {
        public final String slug;
        /** The server's human-readable explanation, or null. Shown verbatim
         *  ONLY for slugs whose detail is written for users (rail-unavailable). */
        public final String detail;
        FatalException(String msg, String slug) {
            this(msg, slug, null);
        }
        FatalException(String msg, String slug, String detail) {
            super(msg);
            this.slug = slug;
            this.detail = detail;
        }
    }

    /** {@code /v1/mint/keys}: the purchasable keysets plus the rails this mint is
     *  configured with. Offer exactly {@code methods}; a mint from before the
     *  field existed reports only Lightning (that was the one rail then). */
    public static final class Catalog {
        public final List<Keyset> keysets;
        public final List<String> methods;
        Catalog(List<Keyset> keysets, List<String> methods) {
            this.keysets = keysets;
            this.methods = methods;
        }
    }

    // ---- result shapes ----

    /** A mint keyset (a denomination's verification key). {@code sizeGb} +
     *  {@code durationMonths} are the plan-grid tile ("Up to X GB for Y") — both
     *  0 for a legacy denomination-only keyset (see cloud-mint-spec.md §6a). */
    public static final class Keyset {
        public final byte[] id;           // 8 bytes
        public final int denomGbMonths;
        public final long priceCents;
        public final int sizeGb;          // plan tile size, 0 if legacy denom-only
        public final int durationMonths;  // plan tile coverage, 0 if legacy denom-only
        public final BigInteger n;
        public final int e;
        public final boolean active;
        Keyset(byte[] id, int denomGbMonths, long priceCents, int sizeGb, int durationMonths,
               BigInteger n, int e, boolean active) {
            this.id = id;
            this.denomGbMonths = denomGbMonths;
            this.priceCents = priceCents;
            this.sizeGb = sizeGb;
            this.durationMonths = durationMonths;
            this.n = n;
            this.e = e;
            this.active = active;
        }

        /** True when this keyset carries plan-grid tile metadata (size × duration). */
        public boolean isPlan() {
            return sizeGb > 0 && durationMonths > 0;
        }

        /** The blind-signature verifier for this keyset. */
        public BlindSignature blindSignature() {
            return new BlindSignature(n, e);
        }
    }

    /** A quote (a purchase in progress) + the rail-specific pay instructions.
     *  Exactly one pay shape is populated per rail: a BOLT11 {@code payRequest}
     *  for Lightning; a BIP21 {@code bitcoin:} URI in {@code payRequest} PLUS the
     *  bare {@code address} / {@code amountSats} / {@code minConfirmations} for
     *  on-chain (the URI is what a wallet opens or a QR encodes; the bare fields
     *  let the UI offer copy-address and state the amount separately). */
    public static final class Quote {
        public final byte[] quoteId;       // 16 bytes
        public final String method;        // "lightning" | "onchain" | "test"
        public final long amountCents;
        public final int denomGbMonths;
        public final int sizeGb;           // plan tile size (0 if legacy denom-only)
        public final int durationMonths;   // plan tile coverage (0 if legacy denom-only)
        public final byte[] keysetId;      // 8 bytes
        public final String payRequest;    // Lightning BOLT11 / on-chain BIP21 URI (null on the test rail)
        public final String address;       // on-chain: the bare receive address (null otherwise)
        public final long amountSats;      // both BTC rails: the exact sats priced at quote time (0 otherwise)
        public final int minConfirmations; // on-chain: confirmations before the payment is final (0 otherwise)
        public final boolean autoSettled;  // test rail — already paid
        public final String expiresAt;     // RFC3339
        Quote(byte[] quoteId, String method, long amountCents, int denomGbMonths,
              int sizeGb, int durationMonths, byte[] keysetId,
              String payRequest, String address, long amountSats, int minConfirmations,
              boolean autoSettled, String expiresAt) {
            this.quoteId = quoteId;
            this.method = method;
            this.amountCents = amountCents;
            this.denomGbMonths = denomGbMonths;
            this.sizeGb = sizeGb;
            this.durationMonths = durationMonths;
            this.keysetId = keysetId;
            this.payRequest = payRequest;
            this.address = address;
            this.amountSats = amountSats;
            this.minConfirmations = minConfirmations;
            this.autoSettled = autoSettled;
            this.expiresAt = expiresAt;
        }
    }

    /** Outcome of an issue attempt: either the blind signature, or "keep polling"
     *  — and, while polling, whether the mint has SEEN the payment. */
    public static final class IssueOutcome {
        public final boolean paid;
        public final BigInteger blindSignature; // set iff paid
        /** On-chain only: the mint saw money at the address (mempool, or short
         *  of {@code min_confirmations}) and pushed the quote's expiry out for
         *  it. False on a plain "nothing yet" 425 and on every other rail. */
        public final boolean pending;
        /** The quote's (possibly extended) expiry as the mint reports it on a
         *  pending 425; null otherwise. */
        public final String expiresAt;
        IssueOutcome(boolean paid, BigInteger blindSignature, boolean pending, String expiresAt) {
            this.paid = paid;
            this.blindSignature = blindSignature;
            this.pending = pending;
            this.expiresAt = expiresAt;
        }
    }

    // ---- endpoints ----

    /** GET the published keysets (verification keys per denomination). */
    public List<Keyset> fetchKeys() throws IOException {
        return fetchCatalog().keysets;
    }

    /** GET the keysets AND the configured rails ({@code methods}). */
    public Catalog fetchCatalog() throws IOException {
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
                            k.optInt("size_gb", 0),
                            k.optInt("duration_months", 0),
                            new BigInteger(k.getString("n"), 16),
                            k.getInt("e"),
                            k.optBoolean("active", false)));
                }
                List<String> methods = new ArrayList<>();
                JSONArray m = obj.optJSONArray("methods");
                if (m == null) {
                    // Pre-field mint: Lightning was its only rail.
                    methods.add("lightning");
                } else {
                    for (int i = 0; i < m.length(); i++) {
                        String name = m.optString(i, "");
                        if (!name.isEmpty()) {
                            methods.add(name);
                        }
                    }
                }
                return new Catalog(out, methods);
            } catch (org.json.JSONException e) {
                throw new IOException("malformed keys response", e);
            }
        }
    }

    /** POST a quote for a denomination on the given rail ("lightning"|"onchain"|"test").
     *  Legacy path — resolves the ACTIVE keyset for the denomination server-side. */
    public Quote createQuote(int denomGbMonths, String method) throws IOException {
        JSONObject body = new JSONObject();
        try {
            body.put("denomination_gb_months", denomGbMonths);
            body.put("method", method);
        } catch (org.json.JSONException e) {
            throw new IOException("quote body", e);
        }
        return postQuote(body, method, denomGbMonths);
    }

    /** POST a quote for a SPECIFIC active keyset (the plan-grid path). The client
     *  names the exact tile it picked from {@link #fetchKeys()} — unambiguous even
     *  when two tiles share a GB-months value (cloud-mint-spec.md §6a). */
    public Quote createQuoteByKeyset(String keysetIdHex, String method) throws IOException {
        JSONObject body = new JSONObject();
        try {
            body.put("keyset_id", keysetIdHex);
            body.put("method", method);
        } catch (org.json.JSONException e) {
            throw new IOException("quote body", e);
        }
        return postQuote(body, method, 0);
    }

    private Quote postQuote(JSONObject body, String method, int denomFallback) throws IOException {
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
                        o.optString("method", method),
                        o.optLong("amount_cents", 0),
                        o.optInt("denomination_gb_months", denomFallback),
                        o.optInt("size_gb", 0),
                        o.optInt("duration_months", 0),
                        Hex.decode(o.getString("keyset_id")),
                        o.optString("pay_request", null),
                        o.optString("address", null),
                        o.optLong("amount_sats", 0),
                        o.optInt("min_confirmations", 0),
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
                    return new IssueOutcome(true, new BigInteger(o.getString("blind_signature"), 16),
                            false, null);
                } catch (org.json.JSONException e) {
                    throw new IOException("malformed issue response", e);
                }
            }
            // 425 Too Early / slug "not-paid-yet" → keep polling. The body is
            // read ONCE: the slug and the on-chain pending marker ride in the
            // same JSON object.
            JSONObject err = readErrorBody(resp);
            String slug = err != null ? err.optString("error", "") : "";
            if (code == 425 || SLUG_NOT_PAID_YET.equals(slug)) {
                boolean pending = err != null && err.optBoolean("pending", false);
                String expiresAt = err != null ? err.optString("expires_at", null) : null;
                return new IssueOutcome(false, null, pending, expiresAt);
            }
            throw classify(resp, err, slug, "issue");
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
            while (retryable(resp) && attempt < MAX_RETRIES) {
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

    /**
     * Whether a 429/503 is worth another attempt: only when the SLUG says
     * overload — {@code rate-limited}, {@code server-busy}, or no slug at all
     * (an edge page). A 503 carrying {@code rail-unavailable} is a verdict
     * ("the Bitcoin node is syncing"), not a hiccup; retrying it by status
     * alone made the user wait out ~4 s of backoff for the same answer. Peeks
     * the body so the real consumer still reads it.
     */
    private static boolean retryable(Response resp) {
        int code = resp.code();
        if (code != 429 && code != 503) {
            return false;
        }
        String slug = "";
        try {
            String s = resp.peekBody(4096).string();
            if (s.startsWith("{")) {
                slug = new JSONObject(s).optString("error", "");
            }
        } catch (Exception ignored) {
            // unreadable body: treat as a bare overload page
        }
        return slug.isEmpty() || SLUG_RATE_LIMITED.equals(slug) || SLUG_SERVER_BUSY.equals(slug);
    }

    private void throwForStatus(Response resp, String op) throws IOException {
        int code = resp.code();
        if (code >= 200 && code < 300) {
            return;
        }
        JSONObject err = readErrorBody(resp);
        String slug = err != null ? err.optString("error", "") : "";
        throw classify(resp, err, slug, op);
    }

    /**
     * Turns a non-2xx reply into the exception the callers switch on. The SLUG
     * decides, never the status (the repo rule — "status codes are advisory; the
     * slug is authoritative"): {@code rate-limited} / {@code server-busy} are the
     * only "try again in a minute" answers, and a bare 429/5xx with no slug at
     * all (an edge/proxy page) is treated the same. Everything else is a
     * {@link FatalException} carrying the slug + detail — including
     * {@code rail-unavailable}, which rides a 503 and used to land in the
     * transient bucket, so the purchase screen said "Too many requests just
     * now" while the truth ("the Bitcoin node is syncing, pay with Lightning")
     * sat unread in {@code detail}.
     */
    private static IOException classify(Response resp, JSONObject err, String slug, String op) {
        int code = resp.code();
        boolean busySlug = SLUG_RATE_LIMITED.equals(slug) || SLUG_SERVER_BUSY.equals(slug);
        boolean bareOverload = slug.isEmpty() && (code == 429 || code >= 500);
        if (busySlug || bareOverload) {
            int retryAfter = parseInt(resp.header("Retry-After"), 0);
            if (err != null) {
                retryAfter = err.optInt("retry_after", retryAfter);
            }
            return new TransientException(op + ": " + code + " " + slug, retryAfter);
        }
        String detail = err != null ? err.optString("detail", null) : null;
        return new FatalException(op + ": " + code + " " + slug, slug,
                detail != null && !detail.isEmpty() ? detail : null);
    }

    /** The mint's JSON error object ({@code {"error": slug, …extras}}), or null
     *  when the body isn't one. Consumes the body. */
    private static JSONObject readErrorBody(Response resp) {
        try {
            String s = bodyString(resp);
            if (s != null && s.startsWith("{")) {
                return new JSONObject(s);
            }
        } catch (Exception ignored) {
            // no parseable body
        }
        return null;
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
