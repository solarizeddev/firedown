package com.solarized.firedown.glide;

import android.net.Uri;

import androidx.annotation.NonNull;

import com.bumptech.glide.load.model.ModelLoader;
import com.bumptech.glide.load.model.ModelLoaderFactory;
import com.bumptech.glide.load.model.MultiModelLoaderFactory;
import com.solarized.firedown.data.entity.DownloadEntity;


public class DownloadEntityUriModelLoaderFactory implements ModelLoaderFactory<DownloadEntity, Uri> {
    @NonNull
    @Override
    public ModelLoader<DownloadEntity, Uri> build(@NonNull MultiModelLoaderFactory multiFactory) {
        return new DownloadEntityUriModelLoader();
    }

    @Override
    public void teardown() {

    }
}
