package com.solarized.firedown.glide.svg;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Picture;

import androidx.annotation.NonNull;

import com.bumptech.glide.load.EncodeStrategy;
import com.bumptech.glide.load.Options;
import com.bumptech.glide.load.ResourceEncoder;
import com.bumptech.glide.load.engine.Resource;
import com.bumptech.glide.load.engine.bitmap_recycle.ArrayPool;
import com.bumptech.glide.load.engine.bitmap_recycle.BitmapPool;
import com.bumptech.glide.load.resource.bitmap.BitmapEncoder;
import com.bumptech.glide.load.resource.bitmap.BitmapResource;
import com.caverock.androidsvg.SVG;

import java.io.File;

public class SVGEncoder implements ResourceEncoder<SVG> {

    private final BitmapEncoder mBitmapEncoder;

    private  final BitmapPool mBitmapPool;

    public SVGEncoder(ArrayPool arrayPool, BitmapPool bitmapPool){
        mBitmapEncoder = new BitmapEncoder(arrayPool);
        mBitmapPool = bitmapPool;
    }

    @NonNull
    @Override
    public EncodeStrategy getEncodeStrategy(@NonNull Options options) {
        return mBitmapEncoder.getEncodeStrategy(options);
    }

    @Override
    public boolean encode(@NonNull Resource<SVG> data, @NonNull File file, @NonNull Options options) {
        SVG svg = data.get();
        Picture picture = svg.renderToPicture();
        int width = picture.getWidth();
        int height = picture.getHeight();
        Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        picture.draw(canvas);
        return mBitmapEncoder.encode(BitmapResource.obtain(bitmap, mBitmapPool), file, options);
    }
}
