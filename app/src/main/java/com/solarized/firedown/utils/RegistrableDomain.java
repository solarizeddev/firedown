package com.solarized.firedown.utils;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;

import okhttp3.HttpUrl;

/**
 * A host's registrable domain (its eTLD+1: {@code googlesyndication.com} for
 * {@code pagead2.googlesyndication.com}, {@code bbc.co.uk} for
 * {@code static.files.bbc.co.uk}), from the public suffix list OkHttp ships
 * in the APK ({@link HttpUrl#topPrivateDomain()}; on Android the list is
 * OkHttp's {@code PublicSuffixDatabase.list} asset).
 *
 * <p>The list is read once per process, ~130 KB. {@link #warmUp} reads it on
 * a background thread at startup, and {@link #of} answers {@code null} until
 * that read is done rather than doing it on the caller's thread: the callers
 * bind rows on the main thread, and a missing domain only means the host is
 * shown whole for that bind.</p>
 */
public final class RegistrableDomain {

    private static final int CACHE_MAX = 512;
    /** Cached "no registrable domain" (an IP, a public suffix itself). */
    private static final String NONE = "";

    private static final AtomicBoolean sWarming = new AtomicBoolean();
    private static volatile boolean sReady;

    private static final Map<String, String> sCache =
            new LinkedHashMap<>(64, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, String> eldest) {
                    return size() > CACHE_MAX;
                }
            };

    private RegistrableDomain() {}

    /** Reads the suffix list on {@code executor}, once per process. */
    public static void warmUp(@NonNull Executor executor) {
        if (sReady || !sWarming.compareAndSet(false, true)) {
            return;
        }
        executor.execute(() -> {
            lookup("www.example.com");
            sReady = true;
        });
    }

    /**
     * The registrable domain of {@code host}, or {@code null} when it has
     * none (an IP address, a public suffix, an invalid host) or the list is
     * not read yet.
     */
    @Nullable
    public static String of(@Nullable String host) {
        if (!sReady || host == null || host.isEmpty()) {
            return null;
        }
        String key = host.toLowerCase(Locale.ROOT);
        String cached;
        synchronized (sCache) {
            cached = sCache.get(key);
        }
        if (cached == null) {
            String domain = lookup(key);
            cached = domain == null ? NONE : domain;
            synchronized (sCache) {
                sCache.put(key, cached);
            }
        }
        return cached.isEmpty() ? null : cached;
    }

    @Nullable
    private static String lookup(@NonNull String host) {
        try {
            HttpUrl url = new HttpUrl.Builder().scheme("https").host(host).build();
            return url.topPrivateDomain();
        } catch (RuntimeException e) {
            // An invalid host (IllegalArgumentException), or a list that
            // could not be read (IllegalStateException): no domain.
            return null;
        }
    }
}
