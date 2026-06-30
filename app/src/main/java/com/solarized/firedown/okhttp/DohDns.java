package com.solarized.firedown.okhttp;

import android.content.SharedPreferences;
import android.util.Log;

import androidx.annotation.NonNull;

import com.solarized.firedown.Preferences;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.List;

import okhttp3.Dns;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.dnsoverhttps.DnsOverHttps;

/**
 * DNS resolver for the singleton OkHttp client that mirrors the browser's
 * DNS-over-HTTPS setting.
 *
 * <p>When the user enables DoH (the {@code SETTINGS_DOH_SWITCH} toggle that
 * also drives GeckoView's TRR mode), every lookup the app's own networking
 * does — downloads, media segments, thumbnails, search, update checks — is
 * resolved through the same DoH endpoint the browser uses, instead of
 * leaking plaintext DNS to the local resolver for hosts the user is often
 * actively visiting.
 *
 * <p>Behaviour matches Gecko's {@code TRR_MODE_FIRST}: try DoH, fall back
 * to the system resolver on any failure, so a blocked or flaky DoH provider
 * degrades to working downloads rather than dead ones. When the toggle is
 * off the lookup is a straight pass-through to {@link Dns#SYSTEM}, so there
 * is zero added latency for users who never enabled DoH.
 *
 * <p>The server URL is resolved per-lookup from SharedPreferences via the
 * shared {@link Preferences#getDohUri} helper (the same single source of
 * truth GeckoView's TRR uses): the persisted SETTINGS_DOH value is itself
 * the endpoint URL for presets, or the user-entered SETTINGS_DOH_CUSTOM when
 * the {@link Preferences#SETTINGS_DOH_CUSTOM_VALUE} sentinel is selected. The
 * built {@link DnsOverHttps} instance is cached and only rebuilt when the
 * resolved URL changes, so flipping the setting at runtime is picked up
 * without rebuilding the (immutable) OkHttpClient.
 */
public final class DohDns implements Dns {

    private static final String TAG = "DohDns";

    private final SharedPreferences prefs;
    /**
     * Plain client used only to fetch from the DoH endpoint. Must NOT be the
     * singleton client (which routes through this resolver) — that would
     * recurse. It also deliberately carries none of the media interceptors;
     * a DoH request is a vanilla HTTPS GET/POST to the resolver.
     */
    private final OkHttpClient bootstrapClient;

    // Cache the built resolver; rebuild only when the resolved URL changes.
    // Volatile because lookup() runs on OkHttp's connection threads. A benign
    // race can build two instances briefly under a setting change — both are
    // valid, one wins the cache slot.
    private volatile String cachedUrl;
    private volatile DnsOverHttps cachedDoh;

    public DohDns(@NonNull SharedPreferences prefs,
                  @NonNull OkHttpClient bootstrapClient) {
        this.prefs = prefs;
        this.bootstrapClient = bootstrapClient;
    }

    @NonNull
    @Override
    public List<InetAddress> lookup(@NonNull String hostname) throws UnknownHostException {
        DnsOverHttps doh = currentResolver();
        if (doh != null) {
            try {
                return doh.lookup(hostname);
            } catch (Exception e) {
                // TRR_MODE_FIRST semantics: any DoH failure (unreachable
                // provider, blocked endpoint, malformed response) falls back
                // to the system resolver rather than failing the request.
                Log.w(TAG, "DoH lookup failed for " + hostname
                        + " — falling back to system DNS: " + e.getMessage());
            }
        }
        return Dns.SYSTEM.lookup(hostname);
    }

    /**
     * @return the DoH resolver to use for this lookup, or {@code null} when
     *         DoH is disabled or no usable server URL is configured (caller
     *         then uses the system resolver).
     */
    private DnsOverHttps currentResolver() {
        if (!Preferences.getDohEnabled(prefs)) {
            return null;
        }
        String url = Preferences.getDohUri(prefs);
        if (url == null || url.isEmpty()) {
            return null;
        }
        DnsOverHttps doh = cachedDoh;
        if (doh != null && url.equals(cachedUrl)) {
            return doh;
        }
        HttpUrl httpUrl = HttpUrl.parse(url);
        if (httpUrl == null) {
            return null;
        }
        doh = new DnsOverHttps.Builder()
                .client(bootstrapClient)
                .url(httpUrl)
                .build();
        cachedDoh = doh;
        cachedUrl = url;
        return doh;
    }
}
