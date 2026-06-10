package com.solarized.firedown.glide;

import android.graphics.Bitmap;
import android.net.Uri;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.bumptech.glide.load.Options;
import com.bumptech.glide.load.ResourceDecoder;
import com.bumptech.glide.load.engine.Resource;
import com.bumptech.glide.load.engine.bitmap_recycle.BitmapPool;
import com.bumptech.glide.load.resource.bitmap.BitmapResource;
import com.bumptech.glide.load.resource.bitmap.DownsampleStrategy;
import com.solarized.firedown.GlideRequestOptions;
import com.solarized.firedown.ffmpegutils.FFmpegThumbnailer;
import com.solarized.firedown.ffmpegutils.FFmpegUtils;
import com.solarized.firedown.utils.BitmapUtils;
import com.solarized.firedown.utils.FileUriHelper;
import com.solarized.firedown.utils.Utils;

import java.io.IOException;
import java.util.Map;

public class FFmpegUriDecoder implements ResourceDecoder<Uri, Bitmap> {

    private static final String TAG = FFmpegUriDecoder.class.getSimpleName();

    private final BitmapPool mBitmapPool;

    public FFmpegUriDecoder(BitmapPool bitmapPool){
        mBitmapPool = bitmapPool;
    }

    @Override
    public boolean handles(@NonNull Uri source, @NonNull Options options) throws IOException {
        /* Don't intercept GIFs. Glide's built-in StreamGifDecoder /
         * ByteBufferGifDecoder produce a GifDrawable that animates;
         * routing GIFs through FFmpegThumbnailer here would only get
         * a single static frame, AND on freshly-written GIFs the
         * native side fails until the file system fully syncs (which
         * is what the 1500 ms delay in ImageViewerFragment was working
         * around). Falling through to Glide's built-in path fixes
         * both — animation works and there's no flaky timing. */
        String mime = options.get(GlideRequestOptions.MIMETYPE);
        if (mime != null && FileUriHelper.isGIF(mime)) {
            return false;
        }
        return true;
    }

    @Nullable
    @Override
    public Resource<Bitmap> decode(@NonNull Uri source, int outWidth, int outHeight, @NonNull Options options) throws IOException {


        Log.d(TAG, "FFmpegGlide decode: " + source);

        FFmpegThumbnailer mFFmepgThumbnailer = new FFmpegThumbnailer();

        DownsampleStrategy downSampleStrategy = options.get(DownsampleStrategy.OPTION);

        String filePath = options.get(GlideRequestOptions.FILEPATH);
        String headers = options.get(GlideRequestOptions.HEADERS);
        Long length = options.get(GlideRequestOptions.LENGTH);

        Log.d(TAG, "FFmpegGlide filePath: " + filePath + " source: " + source);

        Map<String, String> mapHeaders = Utils.stringToMap(headers);

        // No explicit position requested: pass -1 so the native thumbnailer
        // auto-seeks a few seconds in (skipping the usual black opening frame)
        // and falls back to the head if that fails. A provided LENGTH is
        // honoured as before.
        if (length == null)
            length = -1L;

        if (downSampleStrategy == null)
            downSampleStrategy = DownsampleStrategy.NONE;

        try {

            Map<String, String> mDict = null;

            // Only set HTTP-specific options (User-Agent, headers) for remote URLs.
            // For local files (file:// or absolute paths), these options are
            // unrecognized by FFmpeg's file protocol and cause avformat_open_input
            // to fail with ERANGE (-34).
            boolean isRemote = FileUriHelper.isRemote(filePath);

            if (isRemote) {
                mDict = FFmpegUtils.buildFFmpegOptions(mapHeaders);
            }

            if (mFFmepgThumbnailer.setDataSource(filePath, mDict) < 0) {
                throw new IOException("FFmpegThumbnailer setDataSource error");
            }

            // Tell the native side our target size up front so sws_scale
            // produces a smaller bitmap directly, instead of allocating at
            // codec resolution and letting us re-scale in Java. The hint is
            // a "fit-within" cap preserving aspect ratio; if the source is
            // already smaller, native ignores it.
            if (outWidth > 0 && outHeight > 0) {
                mFFmepgThumbnailer.setTargetSizeHint(outWidth, outHeight);
            }

            Bitmap bitmap = mFFmepgThumbnailer.getBitmap(length);

            if (bitmap == null) {
                throw new IOException("FFmpegThumbnailer null bitmap");
            }

            if(outWidth <= 0 || outHeight <= 0)
                return BitmapResource.obtain(bitmap, mBitmapPool);

            int originalWidth = bitmap.getWidth();

            int originalHeight = bitmap.getHeight();

            float scaleFactor = downSampleStrategy.getScaleFactor(
                    originalWidth
                    , originalHeight
                    , outWidth
                    , outHeight
            );

            int decodeWidth = Math.round(scaleFactor * originalWidth);

            int decodeHeight = Math.round(scaleFactor * originalHeight);

            // Native may have already produced a bitmap at (or below) the
            // requested size via the target-size hint. Skip the no-op
            // re-scale — getResizedBitmap unconditionally allocates a new
            // Bitmap and recycles the input.
            if (decodeWidth == originalWidth && decodeHeight == originalHeight) {
                return BitmapResource.obtain(bitmap, mBitmapPool);
            }

            Bitmap resizedBitmap = BitmapUtils.getResizedBitmap(bitmap, decodeWidth, decodeHeight);

            return BitmapResource.obtain(resizedBitmap, mBitmapPool);


        } catch (Exception e) {
            Log.e(TAG, "FFmpegGlide decode", e);
        } finally {
            mFFmepgThumbnailer.release();
        }


        return null;
    }


}