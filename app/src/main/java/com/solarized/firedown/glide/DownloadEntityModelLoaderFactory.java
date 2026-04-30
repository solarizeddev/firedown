package com.solarized.firedown.glide;

import android.os.ParcelFileDescriptor;

import androidx.annotation.NonNull;

import com.bumptech.glide.load.model.ModelLoader;
import com.bumptech.glide.load.model.ModelLoaderFactory;
import com.bumptech.glide.load.model.MultiModelLoaderFactory;
import com.solarized.firedown.data.entity.DownloadEntity;



public class DownloadEntityModelLoaderFactory implements ModelLoaderFactory<DownloadEntity, ParcelFileDescriptor> {
    @NonNull
    @Override
    public ModelLoader<DownloadEntity, ParcelFileDescriptor> build(@NonNull MultiModelLoaderFactory multiFactory) {
        return new DownloadEntityModelLoader();
    }

    @Override
    public void teardown() {

    }
}
