package com.solarized.firedown.glide;



import android.content.Context;
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
import com.solarized.firedown.data.RestoredFileAccess;
import com.solarized.firedown.data.entity.DownloadEntity;
import java.io.FileNotFoundException;
import java.io.IOException;


public class DownloadEntityModelLoader implements ModelLoader<DownloadEntity, ParcelFileDescriptor> {


    private static final String TAG = DownloadEntityModelLoader.class.getSimpleName();

    private final Context mContext;

    public DownloadEntityModelLoader(@NonNull Context context) {
        mContext = context.getApplicationContext();
    }


    @Nullable
    @Override
    public LoadData<ParcelFileDescriptor> buildLoadData(@NonNull DownloadEntity downloadEntity, int width, int height, @NonNull Options options) {
        // ObjectKey hashes via object.toString().getBytes(). DownloadEntity does
        // not override toString(), so Object.toString() includes
        // System.identityHashCode — a per-process value that differs every cold
        // start, causing Glide's RESOURCE disk cache to never hit. Use a stable
        // string key derived from the row id; the per-frame ".signature(...)"
        // applied at the call site discriminates regenerate-thumbnail changes.
        return new LoadData<>(
                new ObjectKey("download_entity:" + downloadEntity.getId()),
                new DownloadEnitityDataFetcher(mContext, downloadEntity));
    }

    @Override
    public boolean handles(@NonNull DownloadEntity downloadEntity) {
        return !downloadEntity.isFileEncrypted();
    }


    private static class DownloadEnitityDataFetcher implements DataFetcher<ParcelFileDescriptor> {

        private final Context mContext;

        private final DownloadEntity mDownloadEntity;

        private ParcelFileDescriptor mParcelFileDescriptor;

        public DownloadEnitityDataFetcher(Context context, DownloadEntity downloadEntity){
            mContext = context;
            mDownloadEntity = downloadEntity;

        }
        @Override
        public void loadData(@NonNull Priority priority, @NonNull DataCallback<? super ParcelFileDescriptor> callback) {

            String filePath = mDownloadEntity.getFilePath();

            String fileImg = mDownloadEntity.getFileImg();

            String path = !TextUtils.isEmpty(filePath) ? filePath : fileImg;

            // openReadOnly opens the owned file directly when this install can
            // read it, and falls back to the persisted SAF restore grant for a
            // foreign-owned RESTORED file (which a bare File open would EACCES).
            mParcelFileDescriptor = RestoredFileAccess.openReadOnly(mContext, path);

            if (mParcelFileDescriptor == null) {
                FileNotFoundException e = new FileNotFoundException("Unreadable: " + path);
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