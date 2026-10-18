package com.solarized.firedown.data.di;

import okhttp3.OkHttpClient;

/** Harness stand-in: the app's shared client (set by the harness). */
public final class NetworkModule {
    public static OkHttpClient globalClient;

    private NetworkModule() {
    }
}
