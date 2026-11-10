package com.solarized.firedown.geckoview;

import android.content.res.Resources;
import android.graphics.SurfaceTexture;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.Surface;

import androidx.annotation.NonNull;
import androidx.annotation.UiThread;

import com.solarized.firedown.BuildConfig;

import org.mozilla.geckoview.GeckoDisplay;
import org.mozilla.geckoview.GeckoSession;

import java.util.HashMap;
import java.util.Map;

/**
 * Gives a session that is shown in no view a window of the screen's size.
 *
 * <p>A GeckoSession with no display has a 0x0 window: its page reads
 * {@code innerWidth}, {@code innerHeight} and the outer sizes as 0. The
 * PO-token page runs YouTube's attestation VM, which reports the page it
 * runs in, and tokens attested from a 0x0 window were kept "pending" by the
 * video server. The display here draws into an off-screen surface nothing
 * reads; only its size matters.
 *
 * <p>Main thread only. {@link #release} must run before the session closes.
 */
final class HiddenSessionDisplay {

    private static final String TAG = "HiddenSessionDisplay";

    private static final Map<GeckoSession, HiddenSessionDisplay> sAttached = new HashMap<>();

    private final GeckoDisplay display;
    private final SurfaceTexture texture;
    private final Surface surface;

    private HiddenSessionDisplay(GeckoDisplay display, SurfaceTexture texture, Surface surface) {
        this.display = display;
        this.texture = texture;
        this.surface = surface;
    }

    /** Never throws: a session left without a display works as before. */
    @UiThread
    static void attach(@NonNull GeckoSession session) {
        if (sAttached.containsKey(session)) {
            return;
        }
        SurfaceTexture texture = null;
        Surface surface = null;
        GeckoDisplay display = null;
        try {
            DisplayMetrics metrics = Resources.getSystem().getDisplayMetrics();
            int width = Math.max(1, metrics.widthPixels);
            int height = Math.max(1, metrics.heightPixels);
            texture = new SurfaceTexture(false);
            texture.setDefaultBufferSize(width, height);
            surface = new Surface(texture);
            display = session.acquireDisplay();
            display.surfaceChanged(new GeckoDisplay.SurfaceInfo.Builder(surface)
                    .size(width, height)
                    .build());
            sAttached.put(session, new HiddenSessionDisplay(display, texture, surface));
            if (BuildConfig.DEBUG) {
                Log.d(TAG, "attached " + width + "x" + height);
            }
        } catch (RuntimeException e) {
            if (BuildConfig.DEBUG) {
                Log.w(TAG, "attach failed", e);
            }
            if (display != null) {
                try {
                    session.releaseDisplay(display);
                } catch (RuntimeException ignored) {
                    // Nothing left to undo.
                }
            }
            if (surface != null) {
                surface.release();
            }
            if (texture != null) {
                texture.release();
            }
        }
    }

    @UiThread
    static void release(@NonNull GeckoSession session) {
        HiddenSessionDisplay attached = sAttached.remove(session);
        if (attached == null) {
            return;
        }
        try {
            attached.display.surfaceDestroyed();
            session.releaseDisplay(attached.display);
        } catch (RuntimeException e) {
            if (BuildConfig.DEBUG) {
                Log.w(TAG, "release failed", e);
            }
        }
        attached.surface.release();
        attached.texture.release();
    }
}
