package com.solarized.firedown;

import android.graphics.Bitmap;
import android.graphics.pdf.PdfRenderer;

import android.os.ParcelFileDescriptor;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.bumptech.glide.load.Options;
import com.bumptech.glide.load.ResourceDecoder;
import com.bumptech.glide.load.engine.Resource;
import com.bumptech.glide.load.engine.bitmap_recycle.BitmapPool;
import com.bumptech.glide.load.resource.bitmap.BitmapResource;
import com.solarized.firedown.utils.FileUriHelper;

import java.io.IOException;

public class PdfDecoder implements ResourceDecoder<ParcelFileDescriptor, Bitmap> {

    private static final String TAG = PdfDecoder.class.getSimpleName();
    private final BitmapPool mBitmapPool;

    public PdfDecoder(BitmapPool bitmapPool){
        mBitmapPool = bitmapPool;
    }

    @Override
    public boolean handles(@NonNull ParcelFileDescriptor source, @NonNull Options options) throws IOException {
        String mimeType = options.get(GlideRequestOptions.MIMETYPE);
        return FileUriHelper.isPdf(mimeType) || FileUriHelper.isBinary(mimeType);
    }

    @Nullable
    @Override
    public Resource<Bitmap> decode(@NonNull ParcelFileDescriptor source, int width, int height, @NonNull Options options) throws IOException {

        PdfRenderer pdfRenderer = new PdfRenderer(source);

        PdfRenderer.Page page = pdfRenderer.openPage(0);

        Bitmap pageBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);

        page.render(pageBitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY);

        return BitmapResource.obtain(pageBitmap, mBitmapPool);

    }
}
