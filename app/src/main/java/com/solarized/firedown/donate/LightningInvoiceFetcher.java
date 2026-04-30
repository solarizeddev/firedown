package com.solarized.firedown.donate;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import androidx.annotation.NonNull;

import org.json.JSONObject;

import java.io.IOException;

import okhttp3.Call;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

/**
 * Fetches a BOLT11 Lightning invoice for a Lightning Address.
 *
 * <p>Implements the LNURL-pay flow as described in
 * <a href="https://github.com/lnurl/luds/blob/luds/06.md">LUD-06</a> /
 * <a href="https://github.com/lnurl/luds/blob/luds/16.md">LUD-16</a>:
 * given an address {@code user@domain}, GET
 * {@code https://domain/.well-known/lnurlp/user} to retrieve the LNURL
 * metadata, then GET the {@code callback} URL with {@code amount=<msats>}
 * to receive a fresh invoice in the {@code pr} field.</p>
 *
 * <p>Networking is mostly identical to the JavaScript {@code fetchInvoice}
 * function in {@code donate.html} — same two requests, same JSON shape.</p>
 *
 * <p>If your project already shells through a singleton {@code OkHttpClient}
 * (e.g. to add interceptors or share the connection pool), inject it via
 * the constructor instead of creating a fresh one here.</p>
 */
public class LightningInvoiceFetcher {

    private static final String TAG = "LightningInvoiceFetcher";

    public interface Callback {
        void onSuccess(@NonNull String bolt11Invoice);
        void onError(@NonNull String message);
    }

    private final OkHttpClient mClient;
    private final Handler mMainHandler;
    private Call mActiveCall;

    public LightningInvoiceFetcher(@NonNull OkHttpClient client) {
        mClient = client;
        mMainHandler = new Handler(Looper.getMainLooper());
    }

    /**
     * Cancels any in-flight request. Safe to call from any thread.
     */
    public void cancel() {
        Call call = mActiveCall;
        if (call != null && !call.isCanceled()) call.cancel();
    }

    /**
     * Fetches a fresh invoice. Result is delivered asynchronously on a
     * background thread — wrap UI updates in {@code runOnUiThread}.
     *
     * @param lightningAddress e.g. {@code solarized@getalby.com}
     * @param sats             whole-sat amount, must be in the LNURL
     *                         server's {@code minSendable / maxSendable}
     *                         range (otherwise {@link Callback#onError}
     *                         fires)
     * @param cb               result callback
     */
    public void fetchInvoice(@NonNull String lightningAddress,
                             int sats,
                             @NonNull Callback cb) {
        String[] parts = lightningAddress.split("@");
        if (parts.length != 2 || parts[0].isEmpty() || parts[1].isEmpty()) {
            cb.onError("Invalid lightning address");
            return;
        }
        String user = parts[0], domain = parts[1];
        HttpUrl metaUrl = new HttpUrl.Builder()
                .scheme("https")
                .host(domain)
                .addPathSegments(".well-known/lnurlp/" + user)
                .build();

        Request metaReq = new Request.Builder().url(metaUrl).build();
        mActiveCall = mClient.newCall(metaReq);
        mActiveCall.enqueue(new okhttp3.Callback() {
            @Override
            public void onFailure(@NonNull Call call, @NonNull IOException e) {
                Log.w(TAG, "LNURL meta fetch failed", e);
                deliverError(cb, "Network error: " + e.getMessage());
            }

            @Override
            public void onResponse(@NonNull Call call, @NonNull Response response) {
                try (ResponseBody body = response.body()) {
                    if (!response.isSuccessful() || body == null) {
                        deliverError(cb, "LNURL fetch failed (" + response.code() + ")");
                        return;
                    }
                    JSONObject meta = new JSONObject(body.string());
                    if ("ERROR".equalsIgnoreCase(meta.optString("status"))) {
                        deliverError(cb, meta.optString("reason", "Server returned error"));
                        return;
                    }
                    long minSendable = meta.optLong("minSendable", 1);     // msats
                    long maxSendable = meta.optLong("maxSendable", Long.MAX_VALUE);
                    long msats = (long) sats * 1000L;
                    if (msats < minSendable || msats > maxSendable) {
                        deliverError(cb, "Amount out of range ("
                                + (minSendable / 1000) + "–" + (maxSendable / 1000) + " sats)");
                        return;
                    }
                    String callback = meta.optString("callback", null);
                    if (callback == null || callback.isEmpty()) {
                        deliverError(cb, "Server returned no callback URL");
                        return;
                    }
                    fetchInvoiceCallback(callback, msats, cb);
                } catch (Exception e) {
                    Log.w(TAG, "LNURL meta parse failed", e);
                    deliverError(cb, "Could not parse LNURL response");
                }
            }
        });
    }

    private void fetchInvoiceCallback(@NonNull String callbackUrl,
                                      long msats,
                                      @NonNull Callback cb) {
        HttpUrl base = HttpUrl.parse(callbackUrl);
        if (base == null) {
            deliverError(cb, "Bad callback URL");
            return;
        }
        HttpUrl invoiceUrl = base.newBuilder()
                .setQueryParameter("amount", String.valueOf(msats))
                .setQueryParameter("comment", "Firedown donation")
                .build();
        Request req = new Request.Builder().url(invoiceUrl).build();
        mActiveCall = mClient.newCall(req);
        mActiveCall.enqueue(new okhttp3.Callback() {
            @Override
            public void onFailure(@NonNull Call call, @NonNull IOException e) {
                Log.w(TAG, "Invoice fetch failed", e);
                deliverError(cb, "Network error: " + e.getMessage());
            }

            @Override
            public void onResponse(@NonNull Call call, @NonNull Response response) {
                try (ResponseBody body = response.body()) {
                    if (!response.isSuccessful() || body == null) {
                        deliverError(cb, "Invoice fetch failed (" + response.code() + ")");
                        return;
                    }
                    JSONObject inv = new JSONObject(body.string());
                    if ("ERROR".equalsIgnoreCase(inv.optString("status"))) {
                        deliverError(cb, inv.optString("reason", "Server returned error"));
                        return;
                    }
                    String pr = inv.optString("pr", null);
                    if (pr == null || pr.isEmpty()) {
                        deliverError(cb, "No invoice returned");
                        return;
                    }
                    deliverSuccess(cb, pr);
                } catch (Exception e) {
                    Log.w(TAG, "Invoice parse failed", e);
                    deliverError(cb, "Could not parse invoice response");
                }
            }
        });
    }

    private void deliverSuccess(@NonNull Callback cb, @NonNull String invoice) {
        mMainHandler.post(() -> cb.onSuccess(invoice));
    }

    private void deliverError(@NonNull Callback cb, @NonNull String msg) {
        mMainHandler.post(() -> cb.onError(msg));
    }
}