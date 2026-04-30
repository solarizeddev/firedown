package com.solarized.firedown;

import android.content.Context;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.text.TextUtils;
import android.util.Log;
import android.widget.ImageView;

import androidx.annotation.NonNull;
import androidx.appcompat.widget.AppCompatImageView;

import com.bumptech.glide.Glide;
import com.bumptech.glide.RequestBuilder;
import com.bumptech.glide.load.DataSource;
import com.bumptech.glide.load.engine.DiskCacheStrategy;
import com.bumptech.glide.load.engine.GlideException;
import com.bumptech.glide.load.model.GlideUrl;
import com.bumptech.glide.load.model.LazyHeaders;
import com.bumptech.glide.request.RequestListener;
import com.bumptech.glide.request.RequestOptions;
import com.bumptech.glide.request.target.Target;
import com.bumptech.glide.signature.ObjectKey;
import com.solarized.firedown.data.entity.BrowserDownloadEntity;
import com.solarized.firedown.data.entity.DownloadEntity;
import com.solarized.firedown.glide.DomainThumbnail;
import com.solarized.firedown.glide.MimeTypeThumbnail;
import com.solarized.firedown.utils.BrowserHeaders;
import com.solarized.firedown.utils.FileUriHelper;
import com.solarized.firedown.utils.Utils;

import java.util.Map;

public class GlideHelper {

    private static final String TAG = GlideHelper.class.getSimpleName();

    /**
     * Fixed decode size for DownloadEntity thumbnails.
     * Using a single size across list and grid modes means the memory cache key
     * is identical in both modes — toggling view mode hits the cache instead of
     * re-decoding. 16:10 matches the grid cell aspect ratio so centerCrop doesn't
     * discard pixels there; the list's ~1:1 thumbnail crops the sides, which is
     * fine at 78dp where fine detail isn't visible anyway.
     */
    private static final int THUMB_WIDTH  = 512;
    private static final int THUMB_HEIGHT = 320;

    private GlideHelper() {}


    // ── Safe clear for onViewRecycled ────────────────────────────────────

    /**
     * Clears Glide load on the given ImageViews using application context.
     * Safe to call during activity destruction — never crashes.
     * Use this in onViewRecycled() instead of Glide.with(view).clear().
     */
    public static void clearSafe(@NonNull ImageView... views) {
        for (ImageView view : views) {
            if (view == null) continue;
            try {
                Glide.with(view).clear(view);
            } catch (IllegalArgumentException ignored) {
                Log.w(TAG, "clearSafe", ignored);
            }
        }
    }


    private static BitmapDrawable generateThumbnail(@NonNull String mimeType,
                                                    @NonNull AppCompatImageView image) {
        Context ctx = image.getContext();
        int width = image.getWidth();
        int height = image.getHeight();
        if (width <= 0) width = (int) (ctx.getResources().getDisplayMetrics().density * 256);
        if (height <= 0) height = (int) (ctx.getResources().getDisplayMetrics().density * 180);
        return new BitmapDrawable(ctx.getResources(),
                MimeTypeThumbnail.generate(ctx, mimeType, width, height));
    }

    private static <T> RequestListener<T> fallbackListener(@NonNull String mimeType,
                                                           @NonNull AppCompatImageView image) {
        return new RequestListener<>() {
            @Override
            public boolean onLoadFailed(GlideException e, Object model,
                                        @NonNull Target<T> target, boolean isFirstResource) {
                image.setImageDrawable(generateThumbnail(mimeType, image));
                return true; // handled
            }

            @Override
            public boolean onResourceReady(@NonNull T resource, @NonNull Object model,
                                           Target<T> target, @NonNull DataSource dataSource,
                                           boolean isFirstResource) {
                return false; // let Glide handle it
            }
        };
    }


    public static void load(String icon, AppCompatImageView image,
                            RequestOptions options, int placeholder) {
        Glide.with(image).load(icon)
                .placeholder(placeholder)
                .fallback(placeholder)
                .diskCacheStrategy(DiskCacheStrategy.AUTOMATIC)
                .apply(options)
                .into(image);
    }


    // ── Favicon / icon loading ──────────────────────────────────────────

    public static void load(String icon, String url,
                            AppCompatImageView image, RequestOptions options) {

        RequestListener<Drawable> listener = domainFallbackListener(url, image);

        if (TextUtils.isEmpty(icon)) {
            image.setImageDrawable(generateDomainThumbnail(url, image));
            return;
        }

        // Data URI — load directly as String, not as GlideUrl
        if (icon.startsWith("data:")) {
            Glide.with(image).load(icon)
                    .listener(listener)
                    .diskCacheStrategy(DiskCacheStrategy.NONE)
                    .apply(options)
                    .into(image);
            return;
        }

        GlideUrl glideUrl = new GlideUrl(icon, new LazyHeaders.Builder()
                .addHeader(BrowserHeaders.USER_AGENT, BrowserHeaders.getDefaultUserAgentString())
                .addHeader(BrowserHeaders.ACCEPT_LANGUAGE, "en-US,en;q=0.5")
                .build());

        Glide.with(image).load(glideUrl)
                .listener(listener)
                .diskCacheStrategy(DiskCacheStrategy.ALL)
                .apply(options.set(GlideRequestOptions.FILEPATH, icon))
                .into(image);
    }

    private static BitmapDrawable generateDomainThumbnail(String url,
                                                          @NonNull AppCompatImageView image) {
        Context ctx = image.getContext();
        String domain = url != null ? Uri.parse(url).getHost() : null;
        if (TextUtils.isEmpty(domain)) domain = "#";
        int size = (int) (ctx.getResources().getDisplayMetrics().density * 48);
        return new BitmapDrawable(image.getResources(),
                DomainThumbnail.generate(ctx, domain, size));
    }

    private static RequestListener<Drawable> domainFallbackListener(String url,
                                                                    @NonNull AppCompatImageView image) {
        return new RequestListener<>() {
            @Override
            public boolean onLoadFailed(GlideException e, Object model,
                                        @NonNull Target<Drawable> target, boolean isFirstResource) {
                image.setImageDrawable(generateDomainThumbnail(url, image));
                return true;
            }

            @Override
            public boolean onResourceReady(@NonNull Drawable resource, @NonNull Object model,
                                           Target<Drawable> target, @NonNull DataSource dataSource,
                                           boolean isFirstResource) {
                return false;
            }
        };
    }


    // ── DownloadEntity thumbnail fallback ────────────────────────────────────────

    /**
     * Sets the generated mime-type thumbnail directly on the view, without going
     * through Glide. Used during PROGRESS / QUEUED / ERROR states where there is
     * no real thumbnail to load — the drawable is generated synchronously, so
     * routing it through Glide would cause flicker on each rebind (every progress
     * tick) as Glide treats the freshly-generated BitmapDrawable as a new model
     * and re-runs its placeholder → resource transition.
     *
     * Cancels any in-flight Glide request on the view first, otherwise a late
     * completion from a previous bind could paint over the fallback.
     */
    public static void loadFallback(DownloadEntity entity,
                                    AppCompatImageView image) {
        clearSafe(image);
        image.setImageDrawable(generateThumbnail(entity.getFileMimeType(), image));
    }

    // ── DownloadEntity thumbnail ────────────────────────────────────────

    public static void load(DownloadEntity entity, RequestOptions requestOptions,
                            AppCompatImageView image) {

        String mimeType = entity.getFileMimeType();
        long interval = entity.getThumbnailDuration();

        RequestOptions options = requestOptions
                .frame(interval)
                .override(THUMB_WIDTH, THUMB_HEIGHT)
                .set(GlideRequestOptions.FRAME, interval)
                .set(GlideRequestOptions.MIMETYPE, mimeType)
                .set(GlideRequestOptions.FILEPATH, entity.getFilePath());

        if (FileUriHelper.isGIF(mimeType) || FileUriHelper.isWEP(mimeType) || FileUriHelper.isSVG(mimeType)) {
            Glide.with(image).load(entity.getFilePath())
                    .listener(fallbackListener(mimeType, image))
                    .apply(options)
                    .into(image);

        } else if (FileUriHelper.isImage(mimeType) || FileUriHelper.isPdf(mimeType)) {
            Glide.with(image).load(entity)
                    .listener(fallbackListener(mimeType, image))
                    .apply(options)
                    .into(image);

        } else if (FileUriHelper.isVideo(mimeType)) {
            Glide.with(image).load(entity)
                    .signature(new ObjectKey(interval + entity.getFileUrl().hashCode()))
                    .listener(fallbackListener(mimeType, image))
                    .apply(options)
                    .into(image);

        } else if (FileUriHelper.isApk(mimeType)) {
            Glide.with(image).load(entity.getFilePath())
                    .signature(new ObjectKey(entity.getId()))
                    .listener(fallbackListener(mimeType, image))
                    .apply(options)
                    .into(image);

        } else {
            image.setImageDrawable(generateThumbnail(mimeType, image));
        }
    }

    // ── BrowserDownloadEntity thumbnail ─────────────────────────────────

    public static void load(BrowserDownloadEntity entity, RequestOptions requestOptions,
                            AppCompatImageView image) {

        String mimeType = entity.getMimeType();
        ObjectKey signature = new ObjectKey(entity.getUid());

        if (FileUriHelper.isGIF(mimeType) || FileUriHelper.isSVG(mimeType) || FileUriHelper.isWEP(mimeType)) {
            GlideUrl url = buildGlideUrl(entity);
            RequestBuilder<?> builder = Glide.with(image).load(url)
                    .signature(signature)
                    .listener(fallbackListener(mimeType, image));
            if (FileUriHelper.isSVG(mimeType)) {
                builder.apply(requestOptions).fitCenter().into(image);
            } else {
                builder.into(image);
            }

        } else if (FileUriHelper.isVideo(mimeType) || FileUriHelper.isImage(mimeType)) {
            String thumbnail = entity.getFileThumbnail();
            String source = TextUtils.isEmpty(thumbnail) ? entity.getFileUrl() : thumbnail;
            Glide.with(image).load(Uri.parse(source))
                    .override(THUMB_WIDTH, THUMB_HEIGHT)
                    .signature(signature)
                    .listener(fallbackListener(mimeType, image))
                    .apply(requestOptions).centerCrop()
                    .into(image);

        } else {
            image.setImageDrawable(generateThumbnail(mimeType, image));
        }
    }

    private static GlideUrl buildGlideUrl(@NonNull BrowserDownloadEntity entity) {
        Map<String, String> headers = Utils.stringToMap(entity.getFileHeaders());
        LazyHeaders.Builder builder = new LazyHeaders.Builder();
        for (Map.Entry<String, String> entry : headers.entrySet()) {
            builder.addHeader(entry.getKey(), entry.getValue());
        }
        return new GlideUrl(entity.getFileUrl(), builder.build());
    }
}