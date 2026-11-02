package com.solarized.firedown.addons;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.UiThread;

import com.bumptech.glide.Glide;
import com.bumptech.glide.request.target.CustomTarget;
import com.bumptech.glide.request.transition.Transition;
import com.solarized.firedown.BuildConfig;

import org.mozilla.geckoview.Image;

/**
 * Add-on icons, as drawables. An installed add-on's icon comes from Gecko
 * (it is in the add-on itself, no network); a listed one's from Mozilla's
 * CDN through Glide, only on the Add-ons screen.
 */
public final class AddonIcons {

    private static final String TAG = "AddonIcons";

    private AddonIcons() {}

    public interface Sink {
        void onIcon(@NonNull Drawable icon);
    }

    /** {@code sink} runs on the main thread; it must check its view is still there. */
    @UiThread
    public static void load(@NonNull Context context, @NonNull Image icon, int sizePx, @NonNull Sink sink) {
        Context app = context.getApplicationContext();
        icon.getBitmap(sizePx).accept(bitmap -> {
            if (bitmap != null) {
                sink.onIcon(drawable(app, bitmap, sizePx));
            }
        }, e -> {
            if (BuildConfig.DEBUG) {
                Log.d(TAG, "add-on icon failed", e);
            }
        });
    }

    @UiThread
    public static void load(@NonNull Context context, @Nullable String url, int sizePx, @NonNull Sink sink) {
        if (url == null || !url.startsWith("https://")) {
            return;
        }
        Context app = context.getApplicationContext();
        Glide.with(app).asBitmap().load(url).override(sizePx, sizePx)
                .into(new CustomTarget<Bitmap>(sizePx, sizePx) {
                    @Override
                    public void onResourceReady(@NonNull Bitmap resource,
                                                @Nullable Transition<? super Bitmap> transition) {
                        sink.onIcon(drawable(app, resource, sizePx));
                    }

                    @Override
                    public void onLoadCleared(@Nullable Drawable placeholder) {
                        // The row keeps the icon it was given.
                    }
                });
    }

    /**
     * Always a copy: Glide may reuse its bitmap once the request is gone, and
     * createScaledBitmap hands back the same bitmap when the size matches.
     */
    @NonNull
    private static Drawable drawable(@NonNull Context context, @NonNull Bitmap bitmap, int sizePx) {
        Bitmap own;
        if (bitmap.getWidth() == sizePx && bitmap.getHeight() == sizePx) {
            Bitmap.Config config = bitmap.getConfig() == null ? Bitmap.Config.ARGB_8888 : bitmap.getConfig();
            own = bitmap.copy(config, false);
        } else {
            own = Bitmap.createScaledBitmap(bitmap, sizePx, sizePx, true);
        }
        return new BitmapDrawable(context.getResources(), own);
    }
}
