package com.solarized.firedown;

import android.content.Context;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.text.TextUtils;
import android.util.Log;
import android.widget.ImageView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.widget.AppCompatImageView;

import com.bumptech.glide.Glide;
import com.bumptech.glide.RequestBuilder;
import com.bumptech.glide.RequestManager;
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

        // clone() so we don't mutate the caller's shared RequestOptions —
        // .frame()/.override()/.set() return `this` (in-place mutation),
        // and the adapter / preloader both reuse a single options instance
        // across every call, so without the clone the most-recent item's
        // mime/filepath/frame leak into every prior in-flight request.
        RequestOptions options = requestOptions.clone()
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

        } else if (FileUriHelper.isVideo(mimeType) || FileUriHelper.isAudio(mimeType)) {
            // Audio joins the video branch: FFmpegThumbnailer (via
            // FFmpegUriDecoder) extracts the first decodable frame of
            // any container. For audio that means the embedded cover
            // art stream (AV_DISPOSITION_ATTACHED_PIC — ID3 APIC,
            // M4A covr, FLAC PICTURE, Ogg METADATA_BLOCK_PICTURE)
            // since FFmpeg exposes attached pictures as video streams.
            // Files with no embedded art fail decode and the
            // fallbackListener renders the generic mime icon.
            //
            // Short-circuit raw audio formats with no possible embedded
            // art (raw ADTS, MIDI) — avoids paying for a guaranteed-
            // failing FFmpeg decode just to land on the same mime icon.
            if (FileUriHelper.isAudio(mimeType)
                    && !FileUriHelper.canHaveEmbeddedArt(mimeType)) {
                clearSafe(image);
                image.setImageDrawable(generateThumbnail(mimeType, image));
                return;
            }
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


    /**
     * Mirror of {@link #load(DownloadEntity, RequestOptions, AppCompatImageView)}'s
     * Glide setup but without an ImageView target — for warming Glide's
     * memory cache via RecyclerViewPreloader. Returns {@code null} when
     * the load path renders a static drawable instead of a thumbnail
     * (nothing to preload).
     *
     * Cache key + signature must match the load path exactly so the
     * subsequent bind hits the warmed bitmap. Listener is omitted —
     * preloads have no ImageView to apply a fallback drawable to.
     */
    @Nullable
    public static RequestBuilder<?> preloadDownload(@NonNull RequestManager glide,
                                                    @NonNull DownloadEntity entity,
                                                    @NonNull RequestOptions requestOptions) {

        String mimeType = entity.getFileMimeType();
        long interval = entity.getThumbnailDuration();

        // clone() so we don't mutate the caller's shared RequestOptions —
        // .frame()/.override()/.set() return `this` (in-place mutation),
        // and the adapter / preloader both reuse a single options instance
        // across every call, so without the clone the most-recent item's
        // mime/filepath/frame leak into every prior in-flight request.
        RequestOptions options = requestOptions.clone()
                .frame(interval)
                .override(THUMB_WIDTH, THUMB_HEIGHT)
                .set(GlideRequestOptions.FRAME, interval)
                .set(GlideRequestOptions.MIMETYPE, mimeType)
                .set(GlideRequestOptions.FILEPATH, entity.getFilePath());

        if (FileUriHelper.isGIF(mimeType) || FileUriHelper.isWEP(mimeType) || FileUriHelper.isSVG(mimeType)) {
            return glide.load(entity.getFilePath()).apply(options);
        }

        if (FileUriHelper.isImage(mimeType) || FileUriHelper.isPdf(mimeType)) {
            return glide.load(entity).apply(options);
        }

        if (FileUriHelper.isVideo(mimeType) || FileUriHelper.isAudio(mimeType)) {
            // Mirror the load() audio+video branch — FFmpeg extracts
            // the embedded cover art stream for audio files that have
            // one, falls back to the generic icon otherwise. Raw audio
            // with no possible art bypasses Glide entirely in load(),
            // so nothing to preload either.
            if (FileUriHelper.isAudio(mimeType)
                    && !FileUriHelper.canHaveEmbeddedArt(mimeType)) {
                return null;
            }
            return glide.load(entity)
                    .signature(new ObjectKey(interval + entity.getFileUrl().hashCode()))
                    .apply(options);
        }

        if (FileUriHelper.isApk(mimeType)) {
            return glide.load(entity.getFilePath())
                    .signature(new ObjectKey(entity.getId()))
                    .apply(options);
        }

        return null;
    }


    public static int downloadThumbWidth() {
        return THUMB_WIDTH;
    }

    public static int downloadThumbHeight() {
        return THUMB_HEIGHT;
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

        } else if (FileUriHelper.isVideo(mimeType) || FileUriHelper.isImage(mimeType)
                || FileUriHelper.isAudio(mimeType)) {
            // Audio joins the video/image branch: when the parser
            // didn't supply an explicit thumbnail URL we point Glide
            // at the audio URL itself and let FFmpegUriDecoder pull
            // the embedded cover art (AV_DISPOSITION_ATTACHED_PIC).
            // FFmpeg's HTTP demuxer only fetches as much as
            // avformat_find_stream_info needs to populate
            // stream->attached_pic — typically just the ID3v2 header
            // at the start of the file. Files with no embedded art
            // fail decode and the fallbackListener renders the
            // mime-tinted audio icon.
            String thumbnail = entity.getFileThumbnail();
            boolean hasThumbnail = !TextUtils.isEmpty(thumbnail);
            // Short-circuit audio with no possible embedded art (raw
            // ADTS, MIDI) when the parser didn't hand us a separate
            // cover URL — saves the network fetch FFmpeg would do
            // before deciding there's nothing to extract.
            if (!hasThumbnail
                    && FileUriHelper.isAudio(mimeType)
                    && !FileUriHelper.canHaveEmbeddedArt(mimeType)) {
                clearSafe(image);
                image.setImageDrawable(generateThumbnail(mimeType, image));
                return;
            }
            String source = hasThumbnail ? thumbnail : entity.getFileUrl();
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


    /**
     * Mirror of {@link #load(BrowserDownloadEntity, RequestOptions, AppCompatImageView)}'s
     * Glide setup but without an ImageView target — for warming Glide's
     * memory cache via RecyclerViewPreloader. Returns {@code null} for
     * mime types that don't have a thumbnail (the load path renders a
     * static drawable for those, nothing to preload).
     *
     * Cache key + signature must match the load path exactly so the
     * subsequent bind hits the warmed bitmap. Listener is omitted —
     * preloads have no ImageView to apply a fallback drawable to.
     */
    @Nullable
    public static RequestBuilder<?> preloadBrowser(@NonNull RequestManager glide,
                                                   @NonNull BrowserDownloadEntity entity,
                                                   @NonNull RequestOptions requestOptions) {

        String mimeType = entity.getMimeType();
        ObjectKey signature = new ObjectKey(entity.getUid());

        if (FileUriHelper.isGIF(mimeType) || FileUriHelper.isSVG(mimeType) || FileUriHelper.isWEP(mimeType)) {
            GlideUrl url = buildGlideUrl(entity);
            RequestBuilder<?> builder = glide.load(url).signature(signature);
            if (FileUriHelper.isSVG(mimeType)) {
                return builder.apply(requestOptions).fitCenter();
            }
            return builder;
        }

        if (FileUriHelper.isVideo(mimeType) || FileUriHelper.isImage(mimeType)
                || FileUriHelper.isAudio(mimeType)) {
            // Mirror the load() branch — audio URLs route through
            // FFmpegUriDecoder for embedded-art extraction. Raw audio
            // with no possible art and no parser-supplied cover URL
            // skips Glide in load(), so nothing to preload either.
            String thumbnail = entity.getFileThumbnail();
            boolean hasThumbnail = !TextUtils.isEmpty(thumbnail);
            if (!hasThumbnail
                    && FileUriHelper.isAudio(mimeType)
                    && !FileUriHelper.canHaveEmbeddedArt(mimeType)) {
                return null;
            }
            String source = hasThumbnail ? thumbnail : entity.getFileUrl();
            return glide.load(Uri.parse(source))
                    .override(THUMB_WIDTH, THUMB_HEIGHT)
                    .signature(signature)
                    .apply(requestOptions).centerCrop();
        }

        return null;
    }


    public static int browserThumbWidth() {
        return THUMB_WIDTH;
    }

    public static int browserThumbHeight() {
        return THUMB_HEIGHT;
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