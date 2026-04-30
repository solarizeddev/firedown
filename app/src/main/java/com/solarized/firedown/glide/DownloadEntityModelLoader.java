package com.solarized.firedown.glide;



import android.os.ParcelFileDescriptor;
import android.text.TextUtils;
import android.util.Log;

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
import java.io.IOException;


public class DownloadEntityModelLoader implements ModelLoader<DownloadEntity, ParcelFileDescriptor> {


    private static final String TAG = DownloadEntityModelLoader.class.getSimpleName();


    @Nullable
    @Override
    public LoadData<ParcelFileDescriptor> buildLoadData(@NonNull DownloadEntity downloadEntity, int width, int height, @NonNull Options options) {

        return new LoadData<>(new ObjectKey(downloadEntity), new DownloadEnitityDataFetcher(downloadEntity));
    }

    @Override
    public boolean handles(@NonNull DownloadEntity downloadEntity) {
        return !downloadEntity.isFileEncrypted();
    }


    private static class DownloadEnitityDataFetcher implements DataFetcher<ParcelFileDescriptor> {

        private final DownloadEntity mDownloadEntity;

        private ParcelFileDescriptor mParcelFileDescriptor;

        public DownloadEnitityDataFetcher(DownloadEntity downloadEntity){
            mDownloadEntity = downloadEntity;

        }
        @Override
        public void loadData(@NonNull Priority priority, @NonNull DataCallback<? super ParcelFileDescriptor> callback) {

            String filePath = mDownloadEntity.getFilePath();

            String fileImg = mDownloadEntity.getFileImg();

            File file = new File(!TextUtils.isEmpty(filePath) ? filePath : fileImg);

            try {
                mParcelFileDescriptor = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY);
            } catch (FileNotFoundException e) {
                Log.e(TAG, "loadData", e);
                callback.onLoadFailed(e);
                return;
            }


            callback.onDataReady(mParcelFileDescriptor);

        }

        @Override
        public void cleanup() {
            if(mParcelFileDescriptor != null){
                try {
                    mParcelFileDescriptor.close();
                } catch (IOException e) {
                    Log.e(TAG, "cleanup", e);
                }
                mParcelFileDescriptor = null;
            }
        }

        @Override
        public void cancel() {
        }

        @NonNull
        @Override
        public Class<ParcelFileDescriptor> getDataClass() {
            return ParcelFileDescriptor.class;
        }

        @NonNull
        @Override
        public DataSource getDataSource() {
            return DataSource.LOCAL;
        }
    }

}