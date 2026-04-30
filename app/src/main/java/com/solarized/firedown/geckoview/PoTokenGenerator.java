package com.solarized.firedown.geckoview;

import android.util.Log;

import org.mozilla.geckoview.GeckoRuntime;
import org.mozilla.geckoview.GeckoSession;
import org.mozilla.geckoview.GeckoSessionSettings;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Generates PO tokens by running BotGuard in a hidden GeckoSession.
 *
 * Mirrors FreeTube's approach: creates a lightweight page at youtube.com origin
 * (robots.txt — plain text, no CSP), where the content script auto-injects
 * bgutils + BotGuard runner. The entire BotGuard flow runs in youtube.com
 * context with proper origin, cookies, and DOM.
 *
 * Flow:
 * 1. Java creates hidden GeckoSession, loads youtube.com/robots.txt
 * 2. Content script (matches *.youtube.com/*) auto-injects
 * 3. background.js sends generatePoToken message to content script
 * 4. Content script runs BotGuard via wrappedJSObject.eval() (no CSP on robots.txt)
 * 5. PO token flows: page → content script → background.js → native message → Java
 * 6. Java closes hidden session
 */
public class PoTokenGenerator {

    private static final String TAG = "PoTokenGenerator";
    private static final String ROBOTS_URL = "https://www.youtube.com/robots.txt";
    private static final long TIMEOUT_MS = 20_000; // 20 seconds

    private final GeckoRuntime runtime;
    private GeckoSession hiddenSession;
    private volatile String pendingToken;
    private volatile String pendingError;
    private CountDownLatch latch;

    public PoTokenGenerator(GeckoRuntime runtime) {
        this.runtime = runtime;
    }

    /**
     * Generate a PO token for the given video ID.
     * This is a BLOCKING call — run on a background thread.
     *
     * @param videoId    YouTube video ID
     * @param visitorData Base64 visitor data
     * @return PO token string, or null on failure
     */
    public String generate(String videoId, String visitorData) {
        Log.d(TAG, "Generating PO token for " + videoId);

        latch = new CountDownLatch(1);
        pendingToken = null;
        pendingError = null;

        // Create hidden session on UI thread (GeckoSession requires it)
        final AtomicReference<GeckoSession> sessionRef = new AtomicReference<>();

        try {
            // Post to main thread to create session
            android.os.Handler mainHandler = new android.os.Handler(android.os.Looper.getMainLooper());
            CountDownLatch sessionLatch = new CountDownLatch(1);

            mainHandler.post(() -> {
                try {
                    GeckoSessionSettings settings = new GeckoSessionSettings.Builder()
                            .usePrivateMode(false)
                            .suspendMediaWhenInactive(true)
                            .allowJavascript(true)
                            .build();

                    hiddenSession = new GeckoSession(settings);
                    hiddenSession.open(runtime);

                    // Set navigation delegate to know when page loads
                    hiddenSession.setNavigationDelegate(new GeckoSession.NavigationDelegate() {});
                    hiddenSession.setContentDelegate(new GeckoSession.ContentDelegate() {});

                    hiddenSession.loadUri(ROBOTS_URL);
                    sessionRef.set(hiddenSession);
                    Log.d(TAG, "Hidden session created, loading " + ROBOTS_URL);
                } catch (Exception e) {
                    Log.e(TAG, "Failed to create session", e);
                    pendingError = e.getMessage();
                    latch.countDown();
                }
                sessionLatch.countDown();
            });

            sessionLatch.await(5, TimeUnit.SECONDS);

            if (sessionRef.get() == null) {
                Log.e(TAG, "Session creation failed");
                return null;
            }

            // Wait for the PO token result (comes via native messaging from background.js)
            boolean completed = latch.await(TIMEOUT_MS, TimeUnit.MILLISECONDS);

            if (!completed) {
                Log.w(TAG, "PO token generation timed out (" + TIMEOUT_MS + "ms)");
                cleanup();
                return null;
            }

            if (pendingError != null) {
                Log.w(TAG, "PO token generation failed: " + pendingError);
                cleanup();
                return null;
            }

            Log.d(TAG, "PO token generated: " + (pendingToken != null ? pendingToken.length() + " chars" : "null"));
            cleanup();
            return pendingToken;

        } catch (InterruptedException e) {
            Log.e(TAG, "Interrupted", e);
            cleanup();
            return null;
        }
    }

    /**
     * Called by GeckoRuntimeHelper when the PO token result arrives
     * via native messaging from background.js.
     */
    public void onTokenResult(String token, String error) {
        if (error != null) {
            pendingError = error;
        } else {
            pendingToken = token;
        }
        if (latch != null) {
            latch.countDown();
        }
    }

    private void cleanup() {
        if (hiddenSession != null) {
            try {
                android.os.Handler mainHandler = new android.os.Handler(android.os.Looper.getMainLooper());
                final GeckoSession session = hiddenSession;
                hiddenSession = null;
                mainHandler.post(() -> {
                    try {
                        session.close();
                        Log.d(TAG, "Hidden session closed");
                    } catch (Exception e) {
                        Log.w(TAG, "Error closing session", e);
                    }
                });
            } catch (Exception e) {
                Log.w(TAG, "Error in cleanup", e);
            }
        }
    }
}