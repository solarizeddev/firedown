package com.solarized.firedown.sync;

import androidx.annotation.Nullable;

import com.solarized.firedown.Preferences;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.util.concurrent.TimeUnit;

import java.nio.charset.StandardCharsets;

import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/**
 * The phone's half of the QR browser-pairing exchange: read the pairing the QR
 * named, announce this device's ephemeral public key, then deliver the sealed
 * key blob once the user has approved.
 *
 * <h3>The base URL is HARDCODED, and that is the whole anti-phishing story</h3>
 * Every request here goes to {@link Preferences#STORAGE_DEFAULT_BACKEND}. The
 * scanned QR carries ONLY a pairing id and a public key — never an endpoint —
 * so a hostile QR cannot route the phone to a mailbox of its own. That is what
 * makes the server's origin attestation meaningful: the origin this client
 * reads back was observed by OUR server from the browser's own {@code Origin}
 * header, which page script cannot forge.
 *
 * <p>Unauthenticated by necessity — the browser has no keys yet, and obtaining
 * them is the point — so these calls carry no signature. Nothing secret is
 * read (the lookup returns the same public key the QR already held), and the
 * blob posted is ciphertext the server cannot open.
 *
 * <p>Deliberately its own tiny client rather than a method on
 * {@link StorageApiClient}: that class exists to SIGN requests against an
 * account, and pairing has neither. Short timeouts, no retries — a pairing is
 * human-paced and a failure is retried by scanning again.
 */
public final class PairClient {

    private static final MediaType OCTET = MediaType.parse("application/octet-stream");
    private static final MediaType JSON = MediaType.parse("application/json");
    private static final int TIMEOUT_SECONDS = 15;

    private final OkHttpClient client;
    private final String baseUrl;

    public PairClient(OkHttpClient client) {
        this.client = client.newBuilder()
                .connectTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .readTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .build();
        this.baseUrl = Preferences.STORAGE_DEFAULT_BACKEND;
    }

    /** What the server recorded when the browser started this pairing. */
    public static final class Pairing {
        /** SERVER-OBSERVED browser origin — what the user is shown. */
        public final String origin;
        /** The public key the server recorded, to compare with the scanned one. */
        public final String pubkey;

        Pairing(String origin, String pubkey) {
            this.origin = origin;
            this.pubkey = pubkey;
        }
    }

    /** Thrown when the pairing is gone: expired, unknown, or already used. */
    public static final class GoneException extends IOException {
        public GoneException(String message) {
            super(message);
        }
    }

    /**
     * Thrown when another device already announced a DIFFERENT key for this
     * pairing id.
     *
     * <p>Distinct from {@link GoneException} because it means something quite
     * different to the user, and to us: the pairing is alive, but the code the
     * browser is displaying was computed over somebody else's key. Approving
     * would hand this account's keys to whoever won that race. So this is the
     * one condition where the right answer is "stop", not "try again".
     */
    public static final class ConflictException extends IOException {
        public ConflictException(String message) {
            super(message);
        }
    }

    /**
     * Reads the pairing. Returns null only for a malformed response; a pairing
     * the server no longer has throws {@link GoneException} so the caller can
     * say "expired" rather than "failed".
     */
    @Nullable
    public Pairing lookup(String pairId) throws IOException {
        Request req = new Request.Builder()
                .url(baseUrl + "/v1/pair/" + pairId)
                .get()
                .build();
        try (Response res = client.newCall(req).execute()) {
            if (res.code() == 404) {
                throw new GoneException("pairing expired or unknown");
            }
            if (!res.isSuccessful() || res.body() == null) {
                throw new IOException("pair lookup failed: HTTP " + res.code());
            }
            JSONObject json = new JSONObject(res.body().string());
            String origin = json.optString("origin", "");
            String pubkey = json.optString("pubkey", "");
            if (origin.isEmpty() || pubkey.isEmpty()) {
                return null;
            }
            return new Pairing(origin, pubkey);
        } catch (JSONException e) {
            return null;
        }
    }

    /**
     * Publishes this phone's EPHEMERAL PUBLIC key, before anything is sealed.
     *
     * <p>This is what lets the browser show the verification code while the
     * approval sheet is still up. Until it existed, the code — which covers
     * both ephemeral public keys — could not be computed by the browser until
     * the phone had ALREADY sealed and delivered, so the sheet asked the user
     * to compare six digits against a screen that had nothing on it, and
     * "Allow" was necessarily a blind decision.
     *
     * <p>Nothing secret leaves: an ephemeral public key is public by
     * construction, and the QR already published the browser's. What this buys
     * is ORDER.
     *
     * <p>A 409 is {@link ConflictException} and must ABORT the pairing — see
     * that class. Re-announcing the SAME key is a success on the server, so a
     * retry is never a self-inflicted conflict.
     */
    public void announce(String pairId, String phonePubkeyB64) throws IOException {
        String body = "{\"phone_pubkey\":\"" + phonePubkeyB64 + "\"}";
        Request req = new Request.Builder()
                .url(baseUrl + "/v1/pair/" + pairId + "/announce")
                .post(RequestBody.create(body.getBytes(StandardCharsets.UTF_8), JSON))
                .build();
        try (Response res = client.newCall(req).execute()) {
            if (res.code() == 404) {
                throw new GoneException("pairing expired or unknown");
            }
            if (res.code() == 409) {
                throw new ConflictException("another device already announced this pairing");
            }
            if (!res.isSuccessful()) {
                throw new IOException("pair announce failed: HTTP " + res.code());
            }
        }
    }

    /**
     * Delivers the sealed key blob.
     *
     * <p>A 409 means someone already completed this pairing — first delivery
     * wins on the server, so a second phone (or a replay) cannot substitute its
     * own keys into a pairing already answered. Reported as gone, because from
     * the user's side it is the same thing: this code is spent, scan a new one.
     */
    public void complete(String pairId, byte[] sealed) throws IOException {
        Request req = new Request.Builder()
                .url(baseUrl + "/v1/pair/" + pairId + "/complete")
                .post(RequestBody.create(sealed, OCTET))
                .build();
        try (Response res = client.newCall(req).execute()) {
            if (res.code() == 404 || res.code() == 409) {
                throw new GoneException("pairing expired or already completed");
            }
            if (!res.isSuccessful()) {
                throw new IOException("pair complete failed: HTTP " + res.code());
            }
        }
    }
}
