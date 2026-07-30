package com.solarized.firedown.nwc;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;

/**
 * A minimal Nostr Wallet Connect (NIP-47) client — just enough to let a user's
 * own Lightning wallet pay a Firedown storage-credit invoice without leaving
 * the app.
 *
 * <p><b>Deliberately tiny, and NOT a Nostr library.</b> It speaks exactly two
 * commands ({@code get_info} to validate a connection, {@code pay_invoice} to
 * pay one) over one short-lived relay socket per call. It is the mirror image
 * of the mint's server-side client (firedown-api {@code internal/mint/payment/
 * nwc}), which speaks the other two ({@code make_invoice}/{@code lookup_invoice})
 * — same protocol, same canonical form, opposite end. Keep them in step; a
 * divergence in either shows up only as a wallet that never answers.
 *
 * <p><b>What the wallet learns.</b> An amount and a BOLT11 — nothing about the
 * account, the credit, or what was bought. That is the mint's unlinkability
 * boundary and it survives here: the blinded credit is minted from the paid
 * quote regardless of which wallet paid it.
 *
 * <p><b>Transport is OkHttp's WebSocket</b>, on a DERIVED client (never the
 * shared one) so a relay's ping interval and read timeout can't perturb every
 * other request the app makes. Calls BLOCK — run them off the main thread.
 *
 * <p>Ceiling worth knowing: NIP-47 has moved toward NIP-44 encryption and
 * kind-23197 notifications. NIP-04 (what this implements) is what Alby Hub and
 * the other current wallets still accept, and it is what the spec's baseline
 * still describes. If a wallet ever rejects a request with an encryption error,
 * that is the upgrade to make — not a transport bug.
 */
public final class NwcClient {

    /** One request/response round trip, including the relay dial. */
    private static final long DEFAULT_TIMEOUT_MS = 30_000L;
    /** Paying can involve multi-hop routing; give it materially longer. */
    private static final long PAY_TIMEOUT_MS = 90_000L;
    /** A relay frame we care about is a few KB; anything vast is not for us. */
    private static final long MAX_FRAME_BYTES = 1L << 20;

    private final NwcUri connection;
    private final OkHttpClient client;

    public NwcClient(@NonNull NwcUri connection, @NonNull OkHttpClient base) {
        this.connection = connection;
        // Derived, never the shared client: a relay socket wants its own ping
        // interval and a read timeout long enough to wait out a payment.
        this.client = base.newBuilder()
                .pingInterval(20, TimeUnit.SECONDS)
                .readTimeout(0, TimeUnit.MILLISECONDS) // a socket, not a request
                .build();
    }

    /**
     * A wallet-side failure with the NIP-47 error code attached, so the caller
     * can tell "you have no balance" from "this connection isn't allowed to
     * pay" without parsing a message string.
     */
    public static final class WalletException extends IOException {
        /** NIP-47 code, e.g. {@code INSUFFICIENT_BALANCE}, {@code RESTRICTED}. */
        public final String code;

        WalletException(String code, String message) {
            super(message == null || message.isEmpty() ? code : message);
            this.code = code == null ? "" : code;
        }
    }

    /**
     * Validates the connection by asking the wallet what it can do. Returns the
     * raw {@code get_info} result. A wallet that answers at all proves the
     * relay, the keys, the encryption and the permissions line up — which is
     * why the connect flow calls this rather than trusting a parsed URI.
     */
    @NonNull
    public JSONObject getInfo() throws IOException {
        return call("get_info", new JSONObject(), DEFAULT_TIMEOUT_MS);
    }

    /**
     * Whether the wallet's {@code get_info} reply lists {@code pay_invoice}. A
     * connection minted read-only (a common Alby Hub option) parses perfectly
     * and then fails at the worst moment — the moment the user is paying — so
     * the connect flow checks it up front.
     *
     * <p>Absent/unparseable {@code methods} returns true: older wallets omit
     * the field, and refusing those would be worse than letting the pay attempt
     * report the truth.
     */
    public static boolean supportsPayInvoice(@Nullable JSONObject info) {
        if (info == null) {
            return true;
        }
        JSONArray methods = info.optJSONArray("methods");
        if (methods == null || methods.length() == 0) {
            return true;
        }
        for (int i = 0; i < methods.length(); i++) {
            if ("pay_invoice".equals(methods.optString(i))) {
                return true;
            }
        }
        return false;
    }

    /**
     * Pays a BOLT11 invoice and returns the payment preimage — the proof of
     * payment. Blocks until the wallet answers or {@link #PAY_TIMEOUT_MS}.
     *
     * <p><b>A timeout is NOT a failed payment.</b> The wallet may still settle
     * after we stop waiting, so the caller must treat it as UNKNOWN and keep
     * polling the mint for settlement rather than presenting it as an error and
     * inviting a second payment.
     */
    @NonNull
    public String payInvoice(@NonNull String bolt11) throws IOException {
        JSONObject params = new JSONObject();
        try {
            params.put("invoice", bolt11);
        } catch (JSONException e) {
            throw new IOException("nwc: bad invoice", e);
        }
        JSONObject result = call("pay_invoice", params, PAY_TIMEOUT_MS);
        String preimage = result.optString("preimage", "");
        if (preimage.isEmpty()) {
            // Some wallets answer success with no preimage. The mint's own
            // settlement poll is the authority either way, so this is not fatal
            // — return a marker rather than inventing a failure.
            return "";
        }
        return preimage;
    }

    // ---- the one round trip ----

    /**
     * Encrypts + signs a kind-23194 request, publishes it, waits for the
     * matching kind-23195 response, verifies and decrypts it, and returns the
     * {@code result} object.
     */
    @NonNull
    private JSONObject call(String method, JSONObject params, long timeoutMs) throws IOException {
        NostrEvent request = new NostrEvent();
        request.pubkey = connection.clientPubkeyHex;
        request.createdAt = System.currentTimeMillis() / 1000L;
        request.kind = NostrEvent.KIND_REQUEST;
        request.tags.add(new String[]{"p", connection.walletPubkeyHex});
        try {
            JSONObject body = new JSONObject();
            body.put("method", method);
            body.put("params", params);
            request.content = Nip04.encrypt(connection.secret, connection.walletPubkey, body.toString());
        } catch (JSONException | Nip04.GeneralSecurityExceptionWrapper e) {
            throw new IOException("nwc: could not build request", e);
        }
        request.sign(connection.secret);

        NostrEvent response = roundtrip(request, timeoutMs);

        // Order matters: prove the event is what its author signed, THEN prove
        // the author is OUR wallet, and only then decrypt. NIP-04 has no
        // authenticated ciphertext of its own, so the signature is the only
        // thing standing between a hostile relay and our decryptor.
        if (!response.verify()) {
            throw new IOException("nwc: response signature is invalid");
        }
        if (!connection.walletPubkeyHex.equals(response.pubkey)) {
            throw new IOException("nwc: response came from a different key");
        }

        String plain;
        try {
            plain = Nip04.decrypt(connection.secret, connection.walletPubkey, response.content);
        } catch (Nip04.GeneralSecurityExceptionWrapper e) {
            throw new IOException("nwc: could not decrypt the wallet's reply", e);
        }

        try {
            JSONObject envelope = new JSONObject(plain);
            JSONObject error = envelope.optJSONObject("error");
            if (error != null && !error.optString("code", "").isEmpty()) {
                throw new WalletException(error.optString("code"), error.optString("message"));
            }
            JSONObject result = envelope.optJSONObject("result");
            return result != null ? result : new JSONObject();
        } catch (JSONException e) {
            throw new IOException("nwc: unreadable reply body", e);
        }
    }

    /**
     * Opens the relay, subscribes for the response to this request, publishes
     * it, and returns the first matching event.
     *
     * <p>The subscription is sent BEFORE the event, deliberately: a fast wallet
     * can answer before a later-registered filter exists, and the reply would
     * be lost to a socket that never asked for it.
     */
    @NonNull
    private NostrEvent roundtrip(NostrEvent request, long timeoutMs) throws IOException {
        String subId = "fd" + request.id.substring(0, 16);
        AtomicReference<NostrEvent> received = new AtomicReference<>();
        AtomicReference<String> failure = new AtomicReference<>();
        CountDownLatch done = new CountDownLatch(1);

        Request handshake = new Request.Builder().url(connection.relay).build();
        WebSocket socket = client.newWebSocket(handshake, new WebSocketListener() {
            @Override
            public void onOpen(@NonNull WebSocket ws, @NonNull Response response) {
                ws.send(new JSONArray()
                        .put("REQ")
                        .put(subId)
                        .put(filterFor(request.id))
                        .toString());
                ws.send("[\"EVENT\"," + request.toJson() + "]");
            }

            @Override
            public void onMessage(@NonNull WebSocket ws, @NonNull String text) {
                if (text.length() > MAX_FRAME_BYTES) {
                    return;
                }
                try {
                    JSONArray message = new JSONArray(text);
                    String type = message.optString(0);
                    if ("EVENT".equals(type) && message.length() >= 3) {
                        NostrEvent event = parseEvent(message.optJSONObject(2));
                        if (event != null && event.kind == NostrEvent.KIND_RESPONSE
                                && event.referencesEvent(request.id)) {
                            received.set(event);
                            done.countDown();
                        }
                    } else if ("OK".equals(type) && message.length() >= 3
                            && !message.optBoolean(2, true)) {
                        // The relay refused to accept our publish: nothing will
                        // ever answer, so fail now instead of at the timeout.
                        failure.set(message.optString(3, "relay rejected the request"));
                        done.countDown();
                    } else if ("CLOSED".equals(type) && message.length() >= 3) {
                        failure.set(message.optString(2, "relay closed the subscription"));
                        done.countDown();
                    }
                } catch (JSONException ignored) {
                    // A frame we can't parse is a frame that isn't ours.
                }
            }

            @Override
            public void onFailure(@NonNull WebSocket ws, @NonNull Throwable t,
                                  @Nullable Response response) {
                failure.set(t.getMessage() == null ? "relay connection failed" : t.getMessage());
                done.countDown();
            }
        });

        try {
            if (!done.await(timeoutMs, TimeUnit.MILLISECONDS)) {
                throw new IOException("nwc: the wallet did not answer in time");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("nwc: interrupted", e);
        } finally {
            socket.close(1000, null);
        }

        String why = failure.get();
        if (why != null) {
            throw new IOException("nwc: " + why);
        }
        NostrEvent event = received.get();
        if (event == null) {
            throw new IOException("nwc: no reply");
        }
        return event;
    }

    /** The NIP-01 filter for "the wallet's response to exactly this request". */
    private JSONObject filterFor(String requestId) {
        JSONObject filter = new JSONObject();
        try {
            filter.put("kinds", new JSONArray().put(NostrEvent.KIND_RESPONSE));
            filter.put("authors", new JSONArray().put(connection.walletPubkeyHex));
            filter.put("#e", new JSONArray().put(requestId));
            filter.put("limit", 1);
        } catch (JSONException ignored) {
            // Every value here is a literal; this cannot throw in practice.
        }
        return filter;
    }

    @Nullable
    private static NostrEvent parseEvent(@Nullable JSONObject json) {
        if (json == null) {
            return null;
        }
        NostrEvent event = new NostrEvent();
        event.id = json.optString("id", null);
        event.pubkey = json.optString("pubkey", null);
        event.createdAt = json.optLong("created_at");
        event.kind = json.optInt("kind", -1);
        event.content = json.optString("content", "");
        event.sig = json.optString("sig", null);
        JSONArray tags = json.optJSONArray("tags");
        if (tags != null) {
            for (int i = 0; i < tags.length(); i++) {
                JSONArray tag = tags.optJSONArray(i);
                if (tag == null) {
                    continue;
                }
                String[] values = new String[tag.length()];
                for (int j = 0; j < tag.length(); j++) {
                    values[j] = tag.optString(j);
                }
                event.tags.add(values);
            }
        }
        return event;
    }
}
