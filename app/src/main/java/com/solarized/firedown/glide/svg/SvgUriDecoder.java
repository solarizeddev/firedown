package com.solarized.firedown.glide.svg;

import static com.bumptech.glide.request.target.Target.SIZE_ORIGINAL;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.net.Uri;
import android.os.ParcelFileDescriptor;

import androidx.annotation.NonNull;

import com.bumptech.glide.load.Options;
import com.bumptech.glide.load.ResourceDecoder;
import com.bumptech.glide.load.engine.Resource;
import com.bumptech.glide.load.engine.bitmap_recycle.BitmapPool;
import com.bumptech.glide.load.resource.bitmap.BitmapResource;
import com.caverock.androidsvg.SVG;
import com.caverock.androidsvg.SVGParseException;
import com.solarized.firedown.GlideRequestOptions;
import com.solarized.firedown.utils.FileUriHelper;

import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;

/** Decodes an SVG internal representation from an {@link InputStream}. */
public class SvgUriDecoder implements ResourceDecoder<Uri, Bitmap> {

    private final BitmapPool mBitmapPool;


    public SvgUriDecoder(BitmapPool bitmapPool){
        mBitmapPool = bitmapPool;
    }

    @Override
    public boolean handles(@NonNull Uri source, @NonNull Options options) {
        String mimeType = options.get(GlideRequestOptions.MIMETYPE);
        return FileUriHelper.isSVG(mimeType);
    }

    public Resource<Bitmap> decode(
            @NonNull Uri source, int width, int height, @NonNull Options options)
            throws IOException {
        try {
            String filePath = options.get(GlideRequestOptions.FILEPATH);
            FileInputStream fileInputStream = new FileInputStream(filePath);
            SVG svg = SVG.getFromInputStream(fileInputStream);
            if (width != SIZE_ORIGINAL) {
                svg.setDocumentWidth(width);
            }
            if (height != SIZE_ORIGINAL) {
                svg.setDocumentHeight(height);
            }

            if (svg.getDocumentWidth() != -1) {

                Bitmap  newBM = Bitmap.createBitmap((int) Math.ceil(svg.getDocumentWidth()),
                        (int) Math.ceil(svg.getDocumentHeight()),
                        Bitmap.Config.ARGB_8888);

                Canvas  bmcanvas = new Canvas(newBM);

                // Clear background to white
                bmcanvas.drawRGB(0, 0, 0);

                // Render our document onto our canvas
                svg.renderToCanvas(bmcanvas);

                return BitmapResource.obtain(newBM, mBitmapPool);
            }

            return null;

        } catch (SVGParseException ex) {
            throw new IOException("Cannot load SVG from stream", ex);
        }
    }
}