package com.solarized.firedown.glide;

import android.graphics.Bitmap;
import android.os.ParcelFileDescriptor;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.bumptech.glide.load.Options;
import com.bumptech.glide.load.ResourceDecoder;
import com.bumptech.glide.load.engine.Resource;
import com.bumptech.glide.load.engine.bitmap_recycle.BitmapPool;
import com.bumptech.glide.load.resource.bitmap.BitmapResource;
import com.bumptech.glide.load.resource.bitmap.DownsampleStrategy;
import com.solarized.firedown.BuildConfig;
import com.solarized.firedown.GlideRequestOptions;
import com.solarized.firedown.ffmpegutils.FFmpegThumbnailer;
import com.solarized.firedown.utils.BitmapUtils;
import com.solarized.firedown.utils.FileUriHelper;

import java.io.FileDescriptor;

/**
 * Native-FFmpeg video-frame fallback for the {@link ParcelFileDescriptor} decode
 * path — the path a FINISHED download uses ({@code DownloadEntity ->
 * ParcelFileDescriptor}, {@link DownloadEntityModelLoader}). Glide's built-in
 * MediaMetadataRetriever/skia decoders run first on that PFD; this is APPENDED
 * after them, so it only runs when they FAIL — e.g. a codec/container MMR + the
 * platform image decoder can't handle (AV1, some HEVC/odd MP4s), which otherwise
 * ends on the mime glyph. Symptom in logs: {@code skia: Failed to create image
 * decoder ... 'unimplemented'} then a still-image {@code ExifInterface} /
 * {@code magic number: 0} failure while decoding a video PFD, and no thumbnail.
 *
 * <p>The sibling {@link FFmpegUriDecoder} already does this for the Uri path, but
 * FFmpeg is only registered there — never for the PFD path finished downloads take
 * (see {@code GlideModule}), so MMR had no native backstop. Like FFmpegUriDecoder,
 * the native open uses the {@link GlideRequestOptions#FILEPATH} option, not the
 * source object; the PFD's {@link FileDescriptor} is used only as a SECOND open
 * target when the path can't be opened directly — the restored/foreign-owned
 * case, where only the content-URI fd is readable
 * ({@link com.solarized.firedown.utils.RestoredFileAccess}).
 *
 * <p>Gated to VIDEO mimes: images decode fine on the built-in still path, audio
 * cover art is MMR's job (FFmpeg's video-frame grab would just fail), and GIFs
 * must stay on Glide's animating path — so this never perturbs those LoadPaths.
 */
public class FFmpegPfdDecoder implements ResourceDecoder<ParcelFileDescriptor, Bitmap> {

    private static final String TAG = FFmpegPfdDecoder.class.getSimpleName();

    private final BitmapPool mBitmapPool;

    public FFmpegPfdDecoder(BitmapPool bitmapPool) {
        mBitmapPool = bitmapPool;
    }

    @Override
    public boolean handles(@NonNull ParcelFileDescriptor source, @NonNull Options options) {
        // Only a last-resort VIDEO frame grab. Audio/image/GIF/PDF PFDs are handled
        // by their own decoders (MMR cover art, the still-image path, the animating
        // GIF path, PdfDecoder), and adding FFmpeg to those LoadPaths would only add
        // a wasted failing attempt.
        String mime = options.get(GlideRequestOptions.MIMETYPE);
        return mime != null && FileUriHelper.isVideo(mime);
    }

    @Nullable
    @Override
    public Resource<Bitmap> decode(@NonNull ParcelFileDescriptor source, int outWidth, int outHeight,
                                   @NonNull Options options) {
        String filePath = options.get(GlideRequestOptions.FILEPATH);
        DownsampleStrategy downSampleStrategy = options.get(DownsampleStrategy.OPTION);
        if (downSampleStrategy == null) {
            downSampleStrategy = DownsampleStrategy.NONE;
        }

        // Seek position = the SAME frame the list/MMR path uses: GlideRequestOptions
        // .FRAME (= GlideHelper.effectiveThumbnailFrame — the ~2s black-skip offset,
        // OR the user-chosen / "Regenerate thumbnail" random position). Reading it
        // is what makes regenerate actually MOVE the frame here instead of always
        // decoding the same one. NOT the LENGTH option: it defaults to 0 (never
        // null), which would pass streamPos 0 = the black t=0 head frame every time.
        // FRAME <= 0 (a short clip, or unset) → -1 so native auto-seeks past the
        // black intro and falls back to the head for a genuinely short clip.
        Long frame = options.get(GlideRequestOptions.FRAME);
        long streamPos = (frame != null && frame > 0) ? frame : -1L;

        // Entry log: this decoder is only REACHED when the built-in MMR + still-
        // image PFD decoders already failed on this video. If you see the mime
        // glyph AND this line never prints, the fallback isn't being reached (build
        // / registration); if it prints and the frame still doesn't show, the two
        // decodeBy* failures below say why.
        if (BuildConfig.DEBUG) {
            Log.d(TAG, "reached (built-in decoders failed): filePath=" + filePath
                    + " streamPos=" + streamPos + " out=" + outWidth + "x" + outHeight);
        }

        // Prefer the real path (owned local files — the common case). Fall back to
        // the open fd for a restored/foreign file whose absolute path FFmpeg can't
        // open directly but whose content-URI PFD is readable.
        Bitmap bitmap = decodeByPath(filePath, streamPos, outWidth, outHeight);
        if (bitmap == null && source.getFileDescriptor() != null) {
            bitmap = decodeByFd(source.getFileDescriptor(), streamPos, outWidth, outHeight);
        }
        if (bitmap == null) {
            return null; // both open targets failed → Glide falls to the mime glyph
        }
        return resize(bitmap, downSampleStrategy, outWidth, outHeight);
    }

    @Nullable
    private Bitmap decodeByPath(@Nullable String path, long streamPos, int outWidth, int outHeight) {
        if (path == null || path.isEmpty()) {
            return null;
        }
        FFmpegThumbnailer thumbnailer = new FFmpegThumbnailer();
        try {
            if (thumbnailer.setDataSource(path, null) < 0) {
                return null;
            }
            return extract(thumbnailer, streamPos, outWidth, outHeight);
        } catch (Exception e) {
            if (BuildConfig.DEBUG) {
                Log.e(TAG, "FFmpeg PFD path decode", e);
            }
            return null;
        } finally {
            thumbnailer.release();
        }
    }

    @Nullable
    private Bitmap decodeByFd(@NonNull FileDescriptor fd, long streamPos, int outWidth, int outHeight) {
        FFmpegThumbnailer thumbnailer = new FFmpegThumbnailer();
        try {
            if (thumbnailer.setDataSource(fd, null) < 0) {
                return null;
            }
            return extract(thumbnailer, streamPos, outWidth, outHeight);
        } catch (Exception e) {
            if (BuildConfig.DEBUG) {
                Log.e(TAG, "FFmpeg PFD fd decode", e);
            }
            return null;
        } finally {
            thumbnailer.release();
        }
    }

    /** Applies the target-size hint (native scales down during sws_scale rather
     *  than allocating at codec resolution — same as FFmpegUriDecoder) and grabs
     *  the frame at {@code streamPos}. */
    @Nullable
    private Bitmap extract(FFmpegThumbnailer thumbnailer, long streamPos, int outWidth, int outHeight) {
        if (outWidth > 0 && outHeight > 0) {
            thumbnailer.setTargetSizeHint(outWidth, outHeight);
        }
        return thumbnailer.getBitmap(streamPos);
    }

    /** Downsamples the native bitmap to the requested size (skips a no-op rescale
     *  when native already produced it at/below target via the size hint). */
    private Resource<Bitmap> resize(Bitmap bitmap, DownsampleStrategy strategy, int outWidth, int outHeight) {
        if (outWidth <= 0 || outHeight <= 0) {
            return BitmapResource.obtain(bitmap, mBitmapPool);
        }
        int originalWidth = bitmap.getWidth();
        int originalHeight = bitmap.getHeight();
        float scaleFactor = strategy.getScaleFactor(originalWidth, originalHeight, outWidth, outHeight);
        int decodeWidth = Math.round(scaleFactor * originalWidth);
        int decodeHeight = Math.round(scaleFactor * originalHeight);
        if (decodeWidth == originalWidth && decodeHeight == originalHeight) {
            return BitmapResource.obtain(bitmap, mBitmapPool);
        }
        Bitmap resized = BitmapUtils.getResizedBitmap(bitmap, decodeWidth, decodeHeight);
        return BitmapResource.obtain(resized, mBitmapPool);
    }
}
