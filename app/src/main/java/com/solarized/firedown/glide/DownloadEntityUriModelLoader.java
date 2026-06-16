package com.solarized.firedown.glide;



import android.content.Context;
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
import com.solarized.firedown.data.RestoredFileAccess;
import com.solarized.firedown.data.entity.DownloadEntity;

import java.io.FileNotFoundException;


public class DownloadEntityUriModelLoader implements ModelLoader<DownloadEntity, Uri> {


    private static final String TAG = DownloadEntityUriModelLoader.class.getSimpleName();

    private final Context mContext;

    public DownloadEntityUriModelLoader(@NonNull Context context) {
        mContext = context.getApplicationContext();
    }


    @Nullable
    @Override
    public LoadData<Uri> buildLoadData(@NonNull DownloadEntity downloadEntity, int width, int height, @NonNull Options options) {
        // See DownloadEntityModelLoader for the rationale: ObjectKey hashes
        // through Object.toString(), which on DownloadEntity is identity-based
        // and changes every process restart, defeating Glide's disk cache.
        return new LoadData<>(
                new ObjectKey("download_entity:" + downloadEntity.getId()),
                new DownloadEnitityDataFetcher(mContext, downloadEntity));
    }

    @Override
    public boolean handles(@NonNull DownloadEntity downloadEntity) {
        return !downloadEntity.isFileEncrypted();
    }


    private static class DownloadEnitityDataFetcher implements DataFetcher<Uri> {

        private final Context mContext;

        private final DownloadEntity mDownloadEntity;


        public DownloadEnitityDataFetcher(Context context, DownloadEntity downloadEntity){
            mContext = context;
            mDownloadEntity = downloadEntity;

        }
        @Override
        public void loadData(@NonNull Priority priority, @NonNull DataCallback<? super Uri> callback) {

            String filePath = mDownloadEntity.getFilePath();

            String fileImg = mDownloadEntity.getFileImg();

            String path = !TextUtils.isEmpty(filePath) ? filePath : fileImg;

            // file:// for an owned file, else the SAF content:// URI for a
            // foreign-owned RESTORED file (Glide's built-in ContentResolver
            // fetchers open it via the persisted grant). A bare Uri.fromFile
            // would EACCES on the restored case.
            Uri uri = RestoredFileAccess.openableUri(mContext, path);

            if (uri == null) {
                callback.onLoadFailed(new FileNotFoundException("Unreadable: " + path));
                return;
            }

            callback.onDataReady(uri);


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