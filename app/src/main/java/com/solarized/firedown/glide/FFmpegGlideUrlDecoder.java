package com.solarized.firedown.glide;

import android.graphics.Bitmap;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.bumptech.glide.load.Options;
import com.bumptech.glide.load.ResourceDecoder;
import com.bumptech.glide.load.engine.Resource;
import com.bumptech.glide.load.engine.bitmap_recycle.BitmapPool;
import com.bumptech.glide.load.model.GlideUrl;
import com.bumptech.glide.load.resource.bitmap.BitmapResource;
import com.bumptech.glide.load.resource.bitmap.DownsampleStrategy;
import com.solarized.firedown.GlideRequestOptions;
import com.solarized.firedown.ffmpegutils.FFmpegConstants;
import com.solarized.firedown.ffmpegutils.FFmpegThumbnailer;
import com.solarized.firedown.ffmpegutils.FFmpegUtils;
import com.solarized.firedown.utils.BitmapUtils;
import com.solarized.firedown.utils.BrowserHeaders;
import com.solarized.firedown.utils.Utils;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

public class FFmpegGlideUrlDecoder implements ResourceDecoder<GlideUrl, Bitmap> {

    private static final String TAG = FFmpegGlideUrlDecoder.class.getSimpleName();

    private final BitmapPool mBitmapPool;

    public FFmpegGlideUrlDecoder(BitmapPool bitmapPool){
        mBitmapPool = bitmapPool;
    }

    @Override
    public boolean handles(@NonNull GlideUrl source, @NonNull Options options) throws IOException {
        return true;
    }

    @Nullable
    @Override
    public Resource<Bitmap> decode(@NonNull GlideUrl source, int outWidth, int outHeight, @NonNull Options options) throws IOException {

        Log.d(TAG, "FFmpegGlide decode: " + source);

        FFmpegThumbnailer mFFmepgThumbnailer = new FFmpegThumbnailer();

        DownsampleStrategy downSampleStrategy = options.get(DownsampleStrategy.OPTION);

        String headers = options.get(GlideRequestOptions.HEADERS);

        Long length = options.get(GlideRequestOptions.LENGTH);

        Map<String, String> mapHeaders = Utils.stringToMap(headers);

        Map<String, String> mDict = FFmpegUtils.buildFFmpegOptions(mapHeaders);

        String filePath = source.toStringUrl();

        if (length == null)
            length = 0L;

        if (downSampleStrategy == null)
            downSampleStrategy = DownsampleStrategy.NONE;

        try {

            if (mFFmepgThumbnailer.setDataSource(filePath, mDict) < 0) {
                throw new IOException("FFmpegThumbnailer setDataSource error");
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
