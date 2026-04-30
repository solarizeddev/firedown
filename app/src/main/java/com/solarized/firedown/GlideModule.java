package com.solarized.firedown;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.drawable.PictureDrawable;
import android.net.Uri;
import android.os.ParcelFileDescriptor;

import androidx.annotation.NonNull;

import com.bumptech.glide.Glide;
import com.bumptech.glide.GlideBuilder;
import com.bumptech.glide.Registry;
import com.bumptech.glide.load.model.GlideUrl;
import com.bumptech.glide.module.AppGlideModule;
import com.caverock.androidsvg.SVG;
import com.solarized.firedown.glide.ApkIconDecoder;
import com.solarized.firedown.glide.DataUriModelLoader;
import com.solarized.firedown.glide.DownloadEntityModelLoaderFactory;
import com.solarized.firedown.glide.DownloadEntityUriModelLoaderFactory;
import com.solarized.firedown.glide.FFmpegGlideUrlDecoder;
import com.solarized.firedown.data.entity.DownloadEntity;
import com.solarized.firedown.glide.FFmpegUriDecoder;
import com.solarized.firedown.glide.svg.SVGEncoder;
import com.solarized.firedown.glide.svg.SvgDecoder;
import com.solarized.firedown.glide.svg.SvgDrawableTranscoder;

import java.io.InputStream;

import dagger.hilt.EntryPoint;
import dagger.hilt.InstallIn;
import dagger.hilt.android.EntryPointAccessors;
import dagger.hilt.components.SingletonComponent;
import okhttp3.OkHttpClient;

@com.bumptech.glide.annotation.GlideModule
public class GlideModule extends AppGlideModule {

    // 1. Define an EntryPoint to bridge Hilt and Glide
    @EntryPoint
    @InstallIn(SingletonComponent.class)
    interface GlideInterceptor {
        OkHttpClient getOkHttpClient();
    }

    @Override
    public boolean isManifestParsingEnabled() {
        return false;
    }

    @Override
    public void applyOptions(@NonNull Context context, @NonNull GlideBuilder builder) {
        super.applyOptions(context, builder);
    }
    @Override
    public void registerComponents(@NonNull Context context, @NonNull Glide glide, Registry registry) {

        GlideInterceptor entryPoint = EntryPointAccessors.fromApplication(
                context.getApplicationContext(),
                GlideInterceptor.class
        );

        OkHttpClient client = entryPoint.getOkHttpClient();

        registry.register(SVG.class, PictureDrawable.class, new SvgDrawableTranscoder());
        registry.replace(GlideUrl.class, InputStream.class, new OkHttpUrlLoader.Factory(client));
        registry.append(DownloadEntity.class, ParcelFileDescriptor.class, new DownloadEntityModelLoaderFactory());
        registry.append(DownloadEntity.class, Uri.class, new DownloadEntityUriModelLoaderFactory());
        registry.append(ParcelFileDescriptor.class, Bitmap.class, new PdfDecoder(glide.getBitmapPool()));
        registry.append(Registry.BUCKET_BITMAP, GlideUrl.class, Bitmap.class, new FFmpegGlideUrlDecoder(glide.getBitmapPool()));
        registry.append(Registry.BUCKET_BITMAP, Uri.class, Bitmap.class, new FFmpegUriDecoder(glide.getBitmapPool()));
        registry.prepend(Registry.BUCKET_BITMAP, InputStream.class, Bitmap.class, new ApkIconDecoder(context, glide.getBitmapPool()));
        registry.append(SVG.class, new SVGEncoder(glide.getArrayPool(), glide.getBitmapPool()));
        registry.append(InputStream.class, SVG.class, new SvgDecoder());
        registry.prepend(String.class, InputStream.class, new DataUriModelLoader.Factory());
    }
}
