package com.solarized.firedown.glide;



import android.net.Uri;
import android.text.TextUtils;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.bumptech.glide.Priority;
import com.bumptech.glide.load.DataSource;
import com.bumptech.glide.load.Options;
import com.bumptech.glide.load.data.DataFetcher;
import com.bumptech.glide.load.model.ModelLoader;
import com.bumptech.glide.signature.ObjectKey;
import com.solarized.firedown.data.entity.DownloadEntity;

import java.io.File;
import java.io.FileNotFoundException;


public class DownloadEntityUriModelLoader implements ModelLoader<DownloadEntity, Uri> {


    private static final String TAG = DownloadEntityUriModelLoader.class.getSimpleName();


    @Nullable
    @Override
    public LoadData<Uri> buildLoadData(@NonNull DownloadEntity downloadEntity, int width, int height, @NonNull Options options) {

        return new LoadData<>(new ObjectKey(downloadEntity), new DownloadEnitityDataFetcher(downloadEntity));
    }

    @Override
    public boolean handles(@NonNull DownloadEntity downloadEntity) {
        return !downloadEntity.isFileEncrypted();
    }


    private static class DownloadEnitityDataFetcher implements DataFetcher<Uri> {

        private final DownloadEntity mDownloadEntity;


        public DownloadEnitityDataFetcher(DownloadEntity downloadEntity){
            mDownloadEntity = downloadEntity;

        }
        @Override
        public void loadData(@NonNull Priority priority, @NonNull DataCallback<? super Uri> callback) {

            String filePath = mDownloadEntity.getFilePath();

            String fileImg = mDownloadEntity.getFileImg();

            File file = new File(!TextUtils.isEmpty(filePath) ? filePath : fileImg);

            if (!file.exists()) {
                callback.onLoadFailed(new FileNotFoundException("File not found: " + file.getPath()));
                return;
            }

            callback.onDataReady(Uri.fromFile(file));


        }

        @Override
        public void cleanup() {

        }

        @Override
        public void cancel() {
        }

        @NonNull
        @Override
        public Class<Uri> getDataClass() {
            return Uri.class;
        }

        @NonNull
        @Override
        public DataSource getDataSource() {
            return DataSource.LOCAL;
        }
    }

}