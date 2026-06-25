package com.solarized.firedown.sync;

import android.util.Base64;

import com.solarized.firedown.sync.crypto.Canonical;
import com.solarized.firedown.sync.crypto.Pow;
import com.solarized.firedown.sync.crypto.SyncIdentity;

import org.json.JSONObject;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/**
 * Signed HTTP client for the Firedown sync API (cloud-sync-spec-api.md). Reuses
 * the shared OkHttp client; every authenticated call carries the three
 * X-Firedown-* headers over the six-line canonical. The base URL is configurable
 * for the BYO-backend case.
 */
public final class SyncApiClient {

    private static final MediaType OCTET = MediaType.parse("application/octet-stream");
    private static final MediaType JSON = MediaType.parse("application/json; charset=utf-8");

    private static final String PATH_HEALTH = "/v1/health";
    private static final String PATH_CHALLENGE = "/v1/register/challenge";
    private static final String PATH_REGISTER = "/v1/account/register";
    private static final String PATH_BOOKMARKS = "/v1/sync/bookmarks";

    private final OkHttpClient client;
    private final String baseUrl; // no trailing slash

    public SyncApiClient(OkHttpClient client, String baseUrl) {
        this.client = client;
        this.baseUrl = stripTrailingSlash(baseUrl);
    }

    // ---- public flows ----

    /** A transient failure the caller should retry after {@code retryAfterSeconds}. */
    public static final class TransientException extends IOException {
        public final int retryAfterSeconds;
        TransientException(String msg, int retryAfterSeconds) {
            super(msg);
            this.retryAfterSeconds = retryAfterSeconds;
        }
    }

    /** A permanent failure (bad request, account-taken, payload-too-large, …). */
    public static final class FatalException extends IOException {
        public final String slug;
        FatalException(String msg, String slug) {
            super(msg);
            this.slug = slug;
        }
    }

    /** Result of a GET /v1/sync/bookmarks. */
    public static final class Pull {
        public final byte[] ciphertext; // null when notFound
        public final long version;      // 0 when notFound
        public final boolean notFound;
        Pull(byte[] ciphertext, long version, boolean notFound) {
            this.ciphertext = ciphertext;
            this.version = version;
            this.notFound = notFound;
        }
    }

    /** Result of a PUT /v1/sync/bookmarks. */
    public static final class Put {
        public final boolean ok;
        public final long version;        // on ok
        public final long serverVersion;  // on conflict
        Put(boolean ok, long version, long serverVersion) {
            this.ok = ok;
            this.version = version;
            this.serverVersion = serverVersion;
        }
    }

    /** Result of an unauthenticated GET /v1/health connection test. */
    public static final class Health {
        /** True only when the host answered 200 with the Firedown {@code {status:"ok"}} shape. */
        public final boolean firedown;
        /** Server-reported version string when present (may be empty). */
        public final String version;
        Health(boolean firedown, String version) {
            this.firedown = firedown;
            this.version = version;
        }
    }

    /**
     * Unauthenticated reachability + identity probe for the BYO-backend "Test
     * connection" action. GETs {@code /v1/health} and confirms the response is
     * the Firedown shape ({@code {"status":"ok",…}}). A 200 from an unrelated
     * HTTPS host or a captive portal does NOT pass — this answers "is a Firedown
     * sync server", not merely "answered something". Throws {@link IOException}
     * when the host is unreachable / times out; returns {@code firedown=false}
     * when it answered but isn't a Firedown API.
     */
    public Health health() throws IOException {
        Request req = new Request.Builder()
                .url(baseUrl + PATH_HEALTH)
                .get()
                .build();
        try (Response resp = client.newCall(req).execute()) {
            if (resp.code() != 200) {
                return new Health(false, "");
            }
            String body = bodyString(resp);
            if (body == null || !body.startsWith("{")) {
                return new Health(false, "");
            }
            try {
                JSONObject obj = new JSONObject(body);
                boolean ok = "ok".equals(obj.optString("status", ""));
                return new Health(ok, obj.optString("version", ""));
            } catch (org.json.JSONException e) {
                return new Health(false, "");
            }
        }
    }

    /**
     * Registers the account: fetch a challenge, solve the hashcash, POST the
     * signed registration. Idempotent server-side (201 registered / 200
     * already-registered both succeed). Throws on hard failure.
     */
    public void register(SyncIdentity id) throws IOException {
        // 1. challenge (unauthenticated)
        String q = "account_id=" + id.accountBase32();
        Request chReq = new Request.Builder()
                .url(baseUrl + PATH_CHALLENGE + "?" + q)
                .get()
                .build();
        byte[] challenge;
        int powBits;
        try (Response resp = client.newCall(chReq).execute()) {
            throwForStatus(resp, "challenge");
            JSONObject obj = new JSONObject(bodyString(resp));
            challenge = b64urlDecode(obj.getString("challenge"));
            powBits = obj.getInt("pow_bits");
        } catch (org.json.JSONException e) {
            throw new IOException("malformed challenge response", e);
        }

        // 2. solve PoW
        byte[] nonce = Pow.solve(id.accountId(), challenge, powBits);

        // 3. register (signed against the body's auth_pubkey)
        JSONObject body = new JSONObject();
        try {
            body.put("account_id", id.accountBase32());
            body.put("auth_pubkey", b64urlEncode(id.authPublicKey()));
            body.put("challenge", b64urlEncode(challenge));
            body.put("pow_nonce", b64urlEncode(nonce));
        } catch (org.json.JSONException e) {
            throw new IOException("register body", e);
        }
        byte[] bodyBytes = body.toString().getBytes(StandardCharsets.UTF_8);
        Request req = signed(id, "POST", PATH_REGISTER, "", bodyBytes)
                .post(RequestBody.create(bodyBytes, JSON))
                .build();
        try (Response resp = client.newCall(req).execute()) {
            // 201 registered / 200 already-registered both succeed.
            if (resp.code() == 200 || resp.code() == 201) {
                return;
            }
            throwForStatus(resp, "register");
        }
    }

    /** GET the current encrypted document (404 -&gt; notFound = empty/version 0). */
    public Pull pull(SyncIdentity id) throws IOException {
        Request req = signed(id, "GET", PATH_BOOKMARKS, "", null).get().build();
        try (Response resp = client.newCall(req).execute()) {
            if (resp.code() == 404) {
                return new Pull(null, 0, true);
            }
            throwForStatus(resp, "pull");
            long version = parseLong(resp.header("X-Firedown-Version"), 0);
            byte[] body = resp.body() != null ? resp.body().bytes() : new byte[0];
            return new Pull(body, version, false);
        }
    }

    /** PUT a new document with optimistic-concurrency on {@code prevVersion}. */
    public Put push(SyncIdentity id, byte[] ciphertext, long prevVersion) throws IOException {
        Request req = signed(id, "PUT", PATH_BOOKMARKS, "", ciphertext)
                .header("X-Firedown-Prev-Version", Long.toString(prevVersion))
                .put(RequestBody.create(ciphertext, OCTET))
                .build();
        try (Response resp = client.newCall(req).execute()) {
            int code = resp.code();
            if (code == 200) {
                try {
                    JSONObject obj = new JSONObject(bodyString(resp));
                    return new Put(true, obj.optLong("version", 0), 0);
                } catch (org.json.JSONException e) {
                    throw new IOException("malformed put response", e);
                }
            }
            if (code == 409) {
                long serverVersion = 0;
                try {
                    serverVersion = new JSONObject(bodyString(resp)).optLong("server_version", 0);
                } catch (org.json.JSONException ignored) {
                    // fall through with 0; the engine re-pulls anyway
                }
                return new Put(false, 0, serverVersion);
            }
            throwForStatus(resp, "push");
            throw new IOException("unexpected push status " + code); // unreachable
        }
    }

    /**
     * Deletes the encrypted document for this identity (right-to-erasure,
     * DELETE /v1/sync/bookmarks). 404 means the server already has nothing —
     * treated as success. Signs like a GET (no body).
     */
    public void delete(SyncIdentity id) throws IOException {
        Request req = signed(id, "DELETE", PATH_BOOKMARKS, "", null).delete().build();
        try (Response resp = client.newCall(req).execute()) {
            if (resp.code() == 404) {
                return; // nothing to delete
            }
            throwForStatus(resp, "delete");
        }
    }

    // ---- signing + helpers ----

    private Request.Builder signed(SyncIdentity id, String method, String path, String query, byte[] body) {
        long ts = System.currentTimeMillis() / 1000L;
        byte[] canonical = Canonical.build(id.accountId(), method, path, query, ts, body);
        byte[] sig = id.sign(canonical);
        String url = baseUrl + path + (query.isEmpty() ? "" : "?" + query);
        return new Request.Builder()
                .url(url)
                .header("X-Firedown-Account", id.accountBase32())
                .header("X-Firedown-Timestamp", Long.toString(ts))
                .header("X-Firedown-Signature", b64urlEncode(sig));
    }

    /** Maps non-2xx into transient/fatal exceptions per the spec's status semantics. */
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
            // no parseable body (e.g. a Cloudflare 429)
        }
        return "";
    }

    private static String bodyString(Response resp) throws IOException {
        return resp.body() != null ? resp.body().string() : "";
    }

    private static String b64urlEncode(byte[] b) {
        return Base64.encodeToString(b, Base64.URL_SAFE | Base64.NO_PADDING | Base64.NO_WRAP);
    }

    private static byte[] b64urlDecode(String s) {
        return Base64.decode(s, Base64.URL_SAFE | Base64.NO_PADDING | Base64.NO_WRAP);
    }

    private static long parseLong(String s, long def) {
        try {
            return s == null ? def : Long.parseLong(s.trim());
        } catch (NumberFormatException e) {
            return def;
        }
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
            return "https://api.firedown.app";
        }
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }
}
