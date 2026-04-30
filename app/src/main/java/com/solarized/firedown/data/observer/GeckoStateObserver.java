package com.solarized.firedown.data.observer;

import android.content.Context;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.lifecycle.Observer;

import com.solarized.firedown.BuildConfig;
import com.solarized.firedown.StoragePaths;
import com.solarized.firedown.data.di.Qualifiers;
import com.solarized.firedown.data.entity.GeckoStateEntity;
import com.solarized.firedown.data.repository.GeckoStateDataRepository;

import dagger.hilt.android.qualifiers.ApplicationContext;

import org.apache.commons.io.FileUtils;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.util.List;
import java.util.concurrent.Executor;

import javax.inject.Inject;

public final class GeckoStateObserver implements Observer<List<GeckoStateEntity>> {

    private static final String TAG = GeckoStateObserver.class.getSimpleName();

    private final Executor mDiskExecutor;
    private final Context mContext;

    @Inject
    public GeckoStateObserver(
            @Qualifiers.DiskIO Executor diskExecutor,
            @ApplicationContext Context context) {
        this.mDiskExecutor = diskExecutor;
        this.mContext = context;
    }

    @Override
    public void onChanged(List<GeckoStateEntity> entities) {
        Log.d(TAG, "onChanged");
        mDiskExecutor.execute(() -> persist(entities));
    }

    private void persist(List<GeckoStateEntity> entities) {
        final JSONArray jsonArray = new JSONArray();
        try {
            Log.d(TAG, "saveToDiskIO: " + (entities != null ? entities.size() : 0));
            if (entities != null) {
                for (GeckoStateEntity entity : entities) {
                    if (entity.isHome()) {
                        continue;
                    }
                    jsonArray.put(toJson(entity));
                }
            }
            jsonToFile(jsonArray);
        } catch (IOException | JSONException e) {
            Log.e(TAG, "saveToDiskIO", e);
        } finally {
            if (entities == null || entities.isEmpty()) {
                deleteThumbnails();
            }
        }
    }

    private JSONObject toJson(GeckoStateEntity e) throws JSONException {
        JSONObject o = new JSONObject();
        o.put(GeckoStateEntity.KEYS.DATE, e.getCreationDate());
        o.put(GeckoStateEntity.KEYS.ICON, e.getIcon());
        o.put(GeckoStateEntity.KEYS.ICON_RESOLUTION, e.getIconResolution());
        o.put(GeckoStateEntity.KEYS.THUMB, e.getThumb());
        o.put(GeckoStateEntity.KEYS.SESSION, e.getSessionState());
        o.put(GeckoStateEntity.KEYS.PREVIEW, e.getPreview());
        o.put(GeckoStateEntity.KEYS.URI, e.getUri());
        o.put(GeckoStateEntity.KEYS.ID, e.getId());
        o.put(GeckoStateEntity.KEYS.PARENT_ID, e.getParentId());
        o.put(GeckoStateEntity.KEYS.TITLE, e.getTitle());
        o.put(GeckoStateEntity.KEYS.BACKWARD, e.canGoBackward());
        o.put(GeckoStateEntity.KEYS.FORWARD, e.canGoForward());
        o.put(GeckoStateEntity.KEYS.FULLSCREEN, e.isFullScreen());
        o.put(GeckoStateEntity.KEYS.DESKTOP, e.isDesktop());
        o.put(GeckoStateEntity.KEYS.ACTIVE, e.isActive());
        o.put(GeckoStateEntity.KEYS.TRACKING_PROTECTION, e.useTrackingProtection());
        o.put(GeckoStateEntity.KEYS.HOME, e.isHome());
        return o;
    }

    private void jsonToFile(@NonNull JSONArray jsonArray) throws IOException {
        if (BuildConfig.DEBUG) {
            Log.d(TAG, "jsonToFile length: " + jsonArray.length());
        }
        File dir = mContext.getFilesDir();
        File targetFile = new File(dir, GeckoStateDataRepository.FILE);
        File tempFile = new File(dir, GeckoStateDataRepository.FILE + ".tmp");

        try (FileOutputStream fos = new FileOutputStream(tempFile);
             BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(fos))) {
            writer.write(jsonArray.toString());
            writer.flush();
            fos.getFD().sync();
        }

        if (!tempFile.renameTo(targetFile)) {
            if (targetFile.exists() && !targetFile.delete()) {
                throw new IOException("Failed to delete old session file");
            }
            if (!tempFile.renameTo(targetFile)) {
                throw new IOException("Failed to rename temp session file");
            }
        }
    }

    private void deleteThumbnails() {
        try {
            FileUtils.cleanDirectory(new File(StoragePaths.getThumbsPath(mContext)));
        } catch (IOException e) {
            Log.e(TAG, "deleteThumbnails", e);
        }
    }
}