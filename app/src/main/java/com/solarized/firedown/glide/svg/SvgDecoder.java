package com.solarized.firedown.glide.svg;

import static com.bumptech.glide.request.target.Target.SIZE_ORIGINAL;

import androidx.annotation.NonNull;
import com.bumptech.glide.load.Options;
import com.bumptech.glide.load.ResourceDecoder;
import com.bumptech.glide.load.engine.Resource;
import com.bumptech.glide.load.resource.SimpleResource;
import com.caverock.androidsvg.SVG;
import com.caverock.androidsvg.SVGParseException;
import com.solarized.firedown.GlideRequestOptions;
import com.solarized.firedown.utils.FileUriHelper;
import com.solarized.firedown.utils.UrlStringUtils;


import java.io.IOException;
import java.io.InputStream;

/** Decodes an SVG internal representation from an {@link InputStream}. */
public class SvgDecoder implements ResourceDecoder<InputStream, SVG> {

    @Override
    public boolean handles(@NonNull InputStream source, @NonNull Options options) {
        // TODO: Can we tell?
        String mimeType = options.get(GlideRequestOptions.MIMETYPE);
        String filePath = options.get(GlideRequestOptions.FILEPATH);
        return FileUriHelper.isSVG(mimeType) || UrlStringUtils.isSVGLike(filePath);
    }

    public Resource<SVG> decode(
            @NonNull InputStream source, int width, int height, @NonNull Options options)
            throws IOException {
        try {
            SVG svg = SVG.getFromInputStream(source);
            if (width != SIZE_ORIGINAL) {
                svg.setDocumentWidth(width);
            }
            if (height != SIZE_ORIGINAL) {
                svg.setDocumentHeight(height);
            }
            return new SimpleResource<>(svg);
        } catch (SVGParseException ex) {
            throw new IOException("Cannot load SVG from stream", ex);
        }
    }
}