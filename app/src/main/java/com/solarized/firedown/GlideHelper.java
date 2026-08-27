package com.solarized.firedown;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.media.MediaMetadataRetriever;
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

import java.io.File;
import java.io.FileNotFoundException;
import com.bumptech.glide.load.model.GlideUrl;
import com.bumptech.glide.load.model.LazyHeaders;
import com.bumptech.glide.load.resource.bitmap.VideoDecoder;
import com.bumptech.glide.request.FutureTarget;
import com.bumptech.glide.request.RequestListener;
import com.bumptech.glide.request.RequestOptions;
import com.bumptech.glide.request.target.Target;
import com.bumptech.glide.signature.ObjectKey;
import com.solarized.firedown.data.Download;
import com.solarized.firedown.data.RestoredFileAccess;
import com.solarized.firedown.data.entity.BrowserDownloadEntity;
import com.solarized.firedown.data.entity.DownloadEntity;
import com.solarized.firedown.data.repository.DownloadDataRepository;
import com.solarized.firedown.glide.DomainThumbnail;
import com.solarized.firedown.glide.MimeTypeThumbnail;
import com.solarized.firedown.manager.UrlType;
import com.solarized.firedown.utils.BrowserHeaders;
import com.solarized.firedown.utils.FileUriHelper;
import com.solarized.firedown.utils.Utils;

import dagger.hilt.EntryPoint;
import dagger.hilt.InstallIn;
import dagger.hilt.android.EntryPointAccessors;
import dagger.hilt.components.SingletonComponent;

import java.util.Map;
import java.util.concurrent.TimeUnit;

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

    /** When a download has no explicitly chosen thumbnail position
     *  ({@code thumbnailDuration == 0}), seek this far in (µs) and take the
     *  first keyframe at/after it (OPTION_NEXT_SYNC) — videos open on a
     *  black/blank frame, so {@code .frame(0)} yields a black thumbnail. Small
     *  on purpose: NEXT_SYNC lands on the first keyframe past this offset, so it
     *  only needs to clear the opening, and a large offset would clamp the many
     *  short clips this app captures (Twitch/Kick clips, Rumble shorts) to the
     *  head. Applied only to video; audio renders embedded cover art (frame
     *  position irrelevant) and images have none. */
    private static final long DEFAULT_THUMBNAIL_FRAME_US = 2_000_000L;

    private GlideHelper() {}

    /**
     * Effective frame position for a download thumbnail: the user-chosen
     * position when set, otherwise {@link #DEFAULT_THUMBNAIL_FRAME_US} for video
     * (skip the black opening frame), otherwise 0. Used by both {@link #load}
     * and {@link #preloadDownload} so the {@code .frame()} value AND the cache
     * signature stay identical between the two paths.
     */
    /**
     * Public view of {@link #effectiveThumbnailFrame}: the exact frame position
     * (µs) the Downloads list renders for this entity. Cloud Backup reuses it so a
     * backed-up file's stored preview is the SAME frame as the list thumbnail
     * (precise + never the black opening frame), instead of guessing an offset.
     */
    public static long thumbnailFrameUs(DownloadEntity entity) {
        return effectiveThumbnailFrame(entity);
    }

    private static long effectiveThumbnailFrame(DownloadEntity entity) {
        long interval = entity.getThumbnailDuration();
        if (interval > 0) {
            return interval;                 // user-chosen position (e.g. regenerate)
        }
        if (!FileUriHelper.isVideo(entity.getFileMimeType())) {
            return interval;                 // 0 — audio uses cover art, images have none
        }
        long duration = entity.getDuration();   // µs, 0 when unknown
        // Offset a few seconds in to skip the black opening frame — but only
        // when the clip is long enough to contain it. A clip shorter than the
        // offset just decodes the head: frame 0 is fine for a short clip, same
        // as the decode-error fallback. Unknown duration assumes a normal-length
        // video (FFmpegUriDecoder's -1 auto path is the backstop if it isn't).
        if (duration > 0 && duration <= DEFAULT_THUMBNAIL_FRAME_US) {
            return 0;
        }
        return DEFAULT_THUMBNAIL_FRAME_US;
    }


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


    private static Drawable generateThumbnail(@NonNull String mimeType,
                                              @NonNull AppCompatImageView image) {
        // Vector-style drawable: paints the tinted card + centred icon
        // straight to the host's canvas at its current bounds, so the
        // result is crisp at any view size (grid / list / sw600 / sw720)
        // without per-size raster caches or first-frame blur from a
        // raster scaled into a larger cell. Fills the whole rounded
        // thumbnail slot (a centred 16:10 card would float with
        // transparent bands and never reach the rounded corners) with ONE
        // ground everywhere — a single opaque colour, list and grid, both
        // themes. Deliberately not derived from the theme background: that
        // form resolved to a pale pastel in light theme which white caption
        // text cannot sit on, splitting the grid tile's ink. See
        // MimeTypeThumbnail.COLOR_FALLBACK_GROUND.
        return MimeTypeThumbnail.generateDrawable(image.getContext(), mimeType, true);
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

    /**
     * Variant of {@link #fallbackListener} for the audio/video branch
     * that ALSO persists a negative-cache flag on the entity once every
     * decoder fails for a {@link Download#FINISHED} file. Subsequent
     * paging accesses then short-circuit the whole Glide chain via
     * {@link DownloadEntity#isFileThumbnailUnavailable()} instead of
     * re-running {@code MediaMetadataRetriever} + {@code FFmpegThumbnailer}
     * (one binder round-trip + one Stagefright init + one FFmpeg context
     * each) per scroll past the row.
     *
     * <p>Gated on {@code STATUS_COMPLETE} so an in-flight download that
     * fails a probe on a partial file isn't permanently poisoned.</p>
     */
    private static <T> RequestListener<T> negativeCachingFallbackListener(
            @NonNull DownloadEntity entity,
            @NonNull String mimeType,
            @NonNull AppCompatImageView image) {
        return new RequestListener<>() {
            @Override
            public boolean onLoadFailed(GlideException e, Object model,
                                        @NonNull Target<T> target, boolean isFirstResource) {
                image.setImageDrawable(generateThumbnail(mimeType, image));
                // Only negative-cache a GENUINE decode failure (file opened,
                // no extractable frame). An ACCESS failure
                // (FileNotFoundException / EACCES) is transient — e.g. a
                // RESTORED foreign-owned file before its SAF grant resolves,
                // or shared storage before the permission is held. Poisoning
                // on that would permanently stick the mime icon even once the
                // file becomes readable (the short-circuit at the top of
                // load() never retries). Same "trust is per path" stance as
                // MediaListenerWorker.
                if (entity.getFileStatus() == Download.FINISHED
                        && !entity.isFileThumbnailUnavailable()
                        && !isAccessFailure(e)) {
                    markThumbnailUnavailableAsync(image.getContext(), entity);
                }
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

    /**
     * Whether a Glide load failed because the file couldn't be OPENED (an
     * access error: {@link FileNotFoundException}, which is how an EACCES on a
     * foreign-owned restored file surfaces) rather than because it decoded to
     * nothing. Access failures are transient and must not poison the
     * negative-cache. Walks the GlideException root causes since the real
     * cause is nested under the per-decoder wrapper exceptions.
     */
    private static boolean isAccessFailure(GlideException e) {
        if (e == null) {
            return false;
        }
        for (Throwable cause : e.getRootCauses()) {
            if (cause instanceof FileNotFoundException) {
                return true;
            }
        }
        return false;
    }

    /**
     * Hilt EntryPoint so this static helper can reach the singleton
     * {@link DownloadDataRepository} from a Glide listener callback —
     * same pattern {@link GlideModule} uses for OkHttpClient.
     */
    @EntryPoint
    @InstallIn(SingletonComponent.class)
    interface RepositoryEntryPoint {
        DownloadDataRepository getDownloadRepository();
    }

    /**
     * Mark the entity's row as "Glide decoders exhausted for this file."
     * The repo dispatches the Room write on its own disk executor, never
     * the main thread. Updates the in-memory entity too so subsequent
     * binds on the same instance short-circuit instantly without waiting
     * for the next PagingSource invalidation.
     */
    private static void markThumbnailUnavailableAsync(@NonNull Context context,
                                                      @NonNull DownloadEntity entity) {
        entity.setFileThumbnailUnavailable(true);
        try {
            RepositoryEntryPoint entryPoint = EntryPointAccessors.fromApplication(
                    context.getApplicationContext(), RepositoryEntryPoint.class);
            entryPoint.getDownloadRepository().setThumbnailUnavailable(entity.getId(), true);
        } catch (IllegalStateException e) {
            // Not in a Hilt context (shouldn't happen in app code, but guard
            // for tests / preview renders so a failed Glide load still
            // shows the icon).
            Log.w(TAG, "markThumbnailUnavailable: no Hilt entry point", e);
        }
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

        // Data URI — load directly as String, not as GlideUrl (DataUriModelLoader
        // decodes it — Base64 or percent-encoded). An SVG data URI (incl. the
        // common emoji favicon "data:image/svg+xml,<url-encoded svg>") must flag
        // an SVG mime so SvgDecoder engages — its handles() gates on the mime,
        // and a data URI has no .svg path to sniff.
        if (icon.startsWith("data:")) {
            RequestOptions dataOptions = icon.regionMatches(true, 0, "data:image/svg", 0, 14)
                    ? options.clone().set(GlideRequestOptions.MIMETYPE, "image/svg+xml")
                    : options;
            Glide.with(image).load(icon)
                    .listener(listener)
                    .diskCacheStrategy(DiskCacheStrategy.NONE)
                    .apply(dataOptions)
                    .into(image);
            return;
        }

        // Externalized icon file (TabIconStore — a data: favicon persisted as a
        // sidecar file, referenced by absolute path in the sessions file) or any
        // other local path. Load the File directly — the network branch below
        // would try it as a URL and fail. An .svg path needs the SVG mime flag
        // for SvgDecoder, same as the data: branch; DiskCacheStrategy.NONE
        // because the source already IS a local file.
        if (icon.startsWith("/")) {
            RequestOptions fileOptions = icon.regionMatches(true, icon.length() - 4, ".svg", 0, 4)
                    ? options.clone().set(GlideRequestOptions.MIMETYPE, "image/svg+xml")
                    : options;
            Glide.with(image).load(new File(icon))
                    .listener(listener)
                    .diskCacheStrategy(DiskCacheStrategy.NONE)
                    .apply(fileOptions)
                    .into(image);
            return;
        }

        // Fetch the favicon like a browser would: a Referer of the page that
        // declared it (so a hotlink-gated CDN — e.g. some site favicons — serves
        // it instead of 403'ing a bare request) and an image Accept. Without these
        // the standalone Glide fetch can fail and the row falls back to the
        // generated domain thumbnail.
        LazyHeaders.Builder headers = new LazyHeaders.Builder()
                .addHeader(BrowserHeaders.USER_AGENT, BrowserHeaders.getDefaultUserAgentString())
                .addHeader(BrowserHeaders.ACCEPT_LANGUAGE, "en-US,en;q=0.5")
                .addHeader(BrowserHeaders.ACCEPT,
                        "image/avif,image/webp,image/png,image/svg+xml,image/*,*/*;q=0.8");
        if (!TextUtils.isEmpty(url)) {
            headers.addHeader(BrowserHeaders.REFERER, url);
        }
        GlideUrl glideUrl = new GlideUrl(icon, headers.build());

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
        long interval = effectiveThumbnailFrame(entity);

        // clone() so we don't mutate the caller's shared RequestOptions —
        // .frame()/.override()/.set() return `this` (in-place mutation),
        // and the adapter / preloader both reuse a single options instance
        // across every call, so without the clone the most-recent item's
        // mime/filepath/frame leak into every prior in-flight request.
        RequestOptions options = requestOptions.clone()
                .frame(interval)
                // Take the first keyframe AT OR AFTER `interval` (NEXT_SYNC).
                // The default OPTION_CLOSEST_SYNC can snap back to the black t=0
                // keyframe on a sparse GOP; OPTION_CLOSEST would decode the exact
                // frame but walks the whole GOP (too heavy for a scrolling list).
                // NEXT_SYNC is a single-keyframe decode that's always past the
                // (small) offset — so past the black intro and never t=0.
                .set(VideoDecoder.FRAME_OPTION, MediaMetadataRetriever.OPTION_NEXT_SYNC)
                .override(THUMB_WIDTH, THUMB_HEIGHT)
                .set(GlideRequestOptions.FRAME, interval)
                .set(GlideRequestOptions.MIMETYPE, mimeType)
                .set(GlideRequestOptions.FILEPATH, entity.getFilePath());

        if (FileUriHelper.isGIF(mimeType) || FileUriHelper.isWEP(mimeType) || FileUriHelper.isSVG(mimeType)) {
            Glide.with(image).load(RestoredFileAccess.openableUri(App.getAppContext(), entity.getFilePath()))
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
            // Persistent negative cache: the entity remembers when every
            // decoder in the Glide chain has already failed for this
            // file. Skip the whole MediaMetadataRetriever +
            // FFmpegThumbnailer dance and render the mime icon directly.
            // Without this, every paging scroll past an audio-without-
            // cover-art row spends ~50-200ms on a guaranteed-failing
            // pipeline plus the GC pressure from churn.
            if (entity.isFileThumbnailUnavailable()) {
                clearSafe(image);
                image.setImageDrawable(generateThumbnail(mimeType, image));
                return;
            }
            Glide.with(image).load(entity)
                    .signature(new ObjectKey(interval + urlSignatureHash(entity)))
                    .listener(negativeCachingFallbackListener(entity, mimeType, image))
                    .apply(options)
                    .into(image);

        } else if (FileUriHelper.isApk(mimeType)) {
            Glide.with(image).load(RestoredFileAccess.openableUri(App.getAppContext(), entity.getFilePath()))
                    .signature(new ObjectKey(entity.getId()))
                    .listener(fallbackListener(mimeType, image))
                    .apply(options)
                    .into(image);

        } else {
            image.setImageDrawable(generateThumbnail(mimeType, image));
        }
    }

    /**
     * Null-safe source for the video/audio thumbnail signature. A FINISHED row
     * can carry a NULL {@code file_url} — a mirror-restored/imported row whose
     * snapshot predates the column, or any insert that never had a source URL —
     * and {@code getFileUrl().hashCode()} on one crashed the Downloads list
     * mid-layout (shipped 1.1.91 NPE, bindFinished → load()). Falls back to the
     * row id: stable per file, unique where the url is absent. Shared by
     * {@link #load} and {@link #preloadDownload} because their request keys must
     * stay IDENTICAL for the scroll preload to actually serve the bind.
     */
    private static int urlSignatureHash(DownloadEntity entity) {
        String url = entity.getFileUrl();
        if (url != null) {
            return url.hashCode();
        }
        return entity.getId();
    }

    /**
     * Whether {@link #load(DownloadEntity, RequestOptions, AppCompatImageView)}
     * will paint the generated {@link MimeTypeThumbnail} FALLBACK tile
     * for this entity RIGHT NOW (as opposed to a real image / video frame /
     * cover art / apk icon). This is the single synchronous source of truth for
     * "is this thumbnail slot a flat generated ground or a photo" — it mirrors
     * {@code load()}'s branch logic exactly and MUST be kept in lockstep with it.
     *
     * <p>Load-bearing subtlety: audio is NOT always a fallback. An audio file
     * with embedded cover art (ID3 APIC / M4A covr / FLAC PICTURE …) decodes to
     * a real picture, so a mime-only "audio ⇒ pastel" guess is wrong. Only audio
     * that CANNOT hold art ({@code !canHaveEmbeddedArt} — raw ADTS / MIDI), or an
     * audio/video whose decoders have already been exhausted (the persistent
     * {@code isFileThumbnailUnavailable} negative cache), is known-pastel here;
     * an as-yet-undecoded art-capable audio/video returns {@code false} (assume a
     * real tile — the safe default) until the negative cache confirms otherwise.
     * So a cover-art audio keeps the photo treatment and an art-less one converges
     * to the card treatment once its decode fails, matching what actually renders.
     */
    public static boolean rendersMimeFallback(@NonNull DownloadEntity entity) {
        String mimeType = entity.getFileMimeType();
        if (FileUriHelper.isGIF(mimeType) || FileUriHelper.isWEP(mimeType)
                || FileUriHelper.isSVG(mimeType)) {
            return false;
        }
        if (FileUriHelper.isImage(mimeType) || FileUriHelper.isPdf(mimeType)) {
            return false;
        }
        if (FileUriHelper.isVideo(mimeType) || FileUriHelper.isAudio(mimeType)) {
            if (FileUriHelper.isAudio(mimeType)
                    && !FileUriHelper.canHaveEmbeddedArt(mimeType)) {
                return true;
            }
            return entity.isFileThumbnailUnavailable();
        }
        if (FileUriHelper.isApk(mimeType)) {
            return false;
        }
        // else branch of load(): doc / subtitle / archive / unknown → mime tile.
        return true;
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
        long interval = effectiveThumbnailFrame(entity);

        // clone() so we don't mutate the caller's shared RequestOptions —
        // .frame()/.override()/.set() return `this` (in-place mutation),
        // and the adapter / preloader both reuse a single options instance
        // across every call, so without the clone the most-recent item's
        // mime/filepath/frame leak into every prior in-flight request.
        RequestOptions options = requestOptions.clone()
                .frame(interval)
                // Take the first keyframe AT OR AFTER `interval` (NEXT_SYNC).
                // The default OPTION_CLOSEST_SYNC can snap back to the black t=0
                // keyframe on a sparse GOP; OPTION_CLOSEST would decode the exact
                // frame but walks the whole GOP (too heavy for a scrolling list).
                // NEXT_SYNC is a single-keyframe decode that's always past the
                // (small) offset — so past the black intro and never t=0.
                .set(VideoDecoder.FRAME_OPTION, MediaMetadataRetriever.OPTION_NEXT_SYNC)
                .override(THUMB_WIDTH, THUMB_HEIGHT)
                .set(GlideRequestOptions.FRAME, interval)
                .set(GlideRequestOptions.MIMETYPE, mimeType)
                .set(GlideRequestOptions.FILEPATH, entity.getFilePath());

        if (FileUriHelper.isGIF(mimeType) || FileUriHelper.isWEP(mimeType) || FileUriHelper.isSVG(mimeType)) {
            return glide.load(RestoredFileAccess.openableUri(App.getAppContext(), entity.getFilePath())).apply(options);
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
            // Same negative-cache gate as load() — no point preloading
            // a chain we know will fail.
            if (entity.isFileThumbnailUnavailable()) {
                return null;
            }
            return glide.load(entity)
                    .signature(new ObjectKey(interval + urlSignatureHash(entity)))
                    .apply(options);
        }

        if (FileUriHelper.isApk(mimeType)) {
            return glide.load(RestoredFileAccess.openableUri(App.getAppContext(), entity.getFilePath()))
                    .signature(new ObjectKey(entity.getId()))
                    .apply(options);
        }

        return null;
    }


    /** Bound on {@link #downloadThumbSync}'s blocking wait — a wedged
     *  MediaMetadataRetriever/FFmpeg decode must not pin the caller's executor
     *  lane forever; the finally-clear cancels the load on timeout. */
    private static final long SYNC_THUMB_TIMEOUT_SECONDS = 20;

    /**
     * Synchronously decodes the SAME thumbnail the Downloads list renders for
     * this entity, through the SAME Glide pipeline and cache keys as
     * {@link #load(DownloadEntity, RequestOptions, AppCompatImageView)} — the
     * request is built by {@link #preloadDownload} with the adapter's bare
     * {@code RequestOptions}, so signature + options + override match the list
     * byte-for-byte. A file ever shown in Downloads is therefore served straight
     * from Glide's memory/disk cache instead of re-extracting a video frame, and
     * a cold miss populates that cache FOR the list. Background threads only
     * ({@code FutureTarget.get()} blocks).
     *
     * <p>The returned bitmap is scaled/copied to at most {@code maxDim} longest
     * side and is OWNED by the caller. The copy is mandatory, not an
     * optimisation: the Glide resource is released back to the bitmap pool in
     * the finally-clear, and a pooled bitmap's pixels can be reused under
     * anyone still holding it.
     *
     * <p>Null when the load path yields no bitmap — a mime with no thumbnail, a
     * negative-cached entity ({@code preloadDownload} returns null), a decode
     * failure, or a non-bitmap drawable (GIF/SVG); callers fall back to their
     * own decoder / the mime glyph.
     */
    @Nullable
    public static Bitmap downloadThumbSync(@NonNull Context context,
                                           @NonNull DownloadEntity entity,
                                           int maxDim) {
        RequestManager glide = Glide.with(context.getApplicationContext());
        FutureTarget<?> future = null;
        try {
            RequestBuilder<?> builder = preloadDownload(glide, entity, new RequestOptions());
            if (builder == null) {
                return null;
            }
            future = builder.submit();
            Object resource = future.get(SYNC_THUMB_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            if (!(resource instanceof BitmapDrawable)) {
                return null;
            }
            Bitmap pooled = ((BitmapDrawable) resource).getBitmap();
            if (pooled == null || pooled.isRecycled()) {
                return null;
            }
            return copyScaled(pooled, maxDim);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        } catch (Exception e) {
            return null;
        } finally {
            if (future != null) {
                // Releases the resource back to Glide (pool + cache). Documented
                // as safe from background threads for a FutureTarget.
                glide.clear(future);
            }
        }
    }

    /** A pool-detached copy of {@code src}, downscaled so its longest side is at
     *  most {@code maxDim} (createScaledBitmap/copy both allocate fresh pixels). */
    private static Bitmap copyScaled(@NonNull Bitmap src, int maxDim) {
        int longest = Math.max(src.getWidth(), src.getHeight());
        if (longest > maxDim) {
            float ratio = (float) maxDim / longest;
            int nw = Math.max(1, Math.round(src.getWidth() * ratio));
            int nh = Math.max(1, Math.round(src.getHeight() * ratio));
            return Bitmap.createScaledBitmap(src, nw, nh, true);
        }
        Bitmap.Config config = src.getConfig() != null ? src.getConfig() : Bitmap.Config.ARGB_8888;
        return src.copy(config, false);
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

        // A capture with no URL at all has nothing fetchable — render the mime
        // tile instead of handing Glide a null/empty model (GlideUrl's ctor
        // THROWS on one, and the source.startsWith model selection below NPEs).
        // Same defect class as the DownloadEntity null-file_url signature crash
        // (see urlSignatureHash); guarded here for the same reason even though a
        // capture normally always carries its URL.
        if (TextUtils.isEmpty(entity.getFileUrl())
                && TextUtils.isEmpty(entity.getFileThumbnail())) {
            clearSafe(image);
            image.setImageDrawable(generateThumbnail(mimeType, image));
            return;
        }

        if (FileUriHelper.isGIF(mimeType) || FileUriHelper.isSVG(mimeType) || FileUriHelper.isWEP(mimeType)) {
            // buildGlideUrl reads the file URL specifically (not the thumbnail),
            // so this branch needs the URL itself to be present.
            if (TextUtils.isEmpty(entity.getFileUrl())) {
                clearSafe(image);
                image.setImageDrawable(generateThumbnail(mimeType, image));
                return;
            }
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
            // A Mega capture's URL is a synthetic, un-fetchable handle and the
            // media bytes are AES-CTR ciphertext, so there's no frame to decode
            // from it. When the capture couldn't attach Mega's stored thumbnail
            // (a data: URI), render the mime tile directly rather than firing a
            // doomed network fetch that just errors and falls back anyway.
            if (!hasThumbnail && entity.getType() == UrlType.MEGA.getValue()) {
                clearSafe(image);
                image.setImageDrawable(generateThumbnail(mimeType, image));
                return;
            }
            String source = hasThumbnail ? thumbnail : entity.getFileUrl();
            // Model selection, three-way:
            // - data: URI (Mega's stored thumbnail JPEG) must load via the
            //   String model so Glide's DataUrlLoader decodes it.
            // - A remote plain-IMAGE fetch — a parser-supplied thumbnail URL,
            //   or an image capture's own URL — goes through GlideUrl so the
            //   capture's headers + a backfilled Referer ride along
            //   (buildGlideUrl): hotlink CDNs (pixiv's i.pximg.net) 403 a
            //   bare Uri fetch, which rendered every pixiv capture as the
            //   broken-image fallback.
            // - Video/audio WITHOUT a thumbnail keeps the Uri model — that's
            //   the FFmpegUriDecoder path (frame / embedded-art extraction),
            //   which reads its headers from GlideRequestOptions.HEADERS.
            boolean plainImageFetch = (hasThumbnail || FileUriHelper.isImage(mimeType))
                    && source.startsWith("http");
            RequestBuilder<Drawable> request;
            if (source.startsWith("data:")) {
                request = Glide.with(image).load(source);
            } else if (plainImageFetch) {
                request = Glide.with(image).load(buildGlideUrl(entity, source));
            } else {
                request = Glide.with(image).load(Uri.parse(source));
            }
            request.override(THUMB_WIDTH, THUMB_HEIGHT)
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

        // Mirror load()'s no-URL guard — a null/empty model would throw in
        // GlideUrl's ctor / NPE in the source model selection, and load()
        // renders the static mime tile for these, so there is nothing to
        // preload either.
        if (TextUtils.isEmpty(entity.getFileUrl())
                && TextUtils.isEmpty(entity.getFileThumbnail())) {
            return null;
        }

        if (FileUriHelper.isGIF(mimeType) || FileUriHelper.isSVG(mimeType) || FileUriHelper.isWEP(mimeType)) {
            if (TextUtils.isEmpty(entity.getFileUrl())) {
                return null;
            }
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
            // Mega capture with no stored thumbnail renders a static mime tile in
            // load() — nothing to preload (mirror the load() short-circuit).
            if (!hasThumbnail && entity.getType() == UrlType.MEGA.getValue()) {
                return null;
            }
            String source = hasThumbnail ? thumbnail : entity.getFileUrl();
            // Mirror load()'s three-way model selection (data: / headered
            // GlideUrl for remote image fetches / Uri for the FFmpeg path) so
            // the preload's cache key matches the bind exactly.
            boolean plainImageFetch = (hasThumbnail || FileUriHelper.isImage(mimeType))
                    && source.startsWith("http");
            RequestBuilder<?> request;
            if (source.startsWith("data:")) {
                request = glide.load(source);
            } else if (plainImageFetch) {
                request = glide.load(buildGlideUrl(entity, source));
            } else {
                request = glide.load(Uri.parse(source));
            }
            return request
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
        return buildGlideUrl(entity, entity.getFileUrl());
    }

    /**
     * GlideUrl for an http(s) source fetched as a plain image stream, carrying
     * the capture's cached request headers. A missing {@code Referer} is
     * backfilled from the capture's page origin (origin-only, trailing slash —
     * what a browser sends cross-origin): hotlink-protecting image CDNs
     * (pixiv's {@code i.pximg.net} is the canonical case) 403 a bare fetch,
     * the same check the page render and the save-image download already
     * reproduce. The backfill matters for parser-supplied thumbnail URLs
     * (never seen on the wire, so no cached headers) and captures made before
     * headers were cached.
     */
    private static GlideUrl buildGlideUrl(@NonNull BrowserDownloadEntity entity,
                                          @NonNull String source) {
        Map<String, String> headers = Utils.stringToMap(entity.getFileHeaders());
        LazyHeaders.Builder builder = new LazyHeaders.Builder();
        boolean hasReferer = false;
        for (Map.Entry<String, String> entry : headers.entrySet()) {
            builder.addHeader(entry.getKey(), entry.getValue());
            if (BrowserHeaders.REFERER.equalsIgnoreCase(entry.getKey())) {
                hasReferer = true;
            }
        }
        if (!hasReferer) {
            String referer = BrowserHeaders.originWithSlash(entity.getFileOrigin());
            if (referer != null) {
                builder.addHeader(BrowserHeaders.REFERER, referer);
            }
        }
        return new GlideUrl(source, builder.build());
    }
}