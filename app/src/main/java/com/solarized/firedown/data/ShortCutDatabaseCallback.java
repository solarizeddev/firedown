package com.solarized.firedown.data;

import android.content.Context;
import android.util.Log;
import androidx.annotation.NonNull;
import androidx.room.RoomDatabase;
import androidx.sqlite.db.SupportSQLiteDatabase;

import com.solarized.firedown.data.di.Qualifiers;
import com.solarized.firedown.data.entity.ShortCutsEntity;
import com.solarized.firedown.data.repository.ShortCutsDataRepository;
import com.solarized.firedown.utils.Utils;
import com.solarized.firedown.utils.WebUtils;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;
import javax.inject.Inject;
import javax.inject.Provider;

import dagger.hilt.android.qualifiers.ApplicationContext;

public class ShortCutDatabaseCallback extends RoomDatabase.Callback {
    private final Provider<ShortCutsDataRepository> repositoryProvider;
    private final Executor executor;
    private final Context mContext;

    @Inject
    public ShortCutDatabaseCallback(
            @ApplicationContext Context context,
            Provider<ShortCutsDataRepository> repositoryProvider,
            @Qualifiers.DiskIO Executor executor) {
        this.mContext = context;
        this.repositoryProvider = repositoryProvider;
        this.executor = executor;
    }

    @Override
    public void onCreate(@NonNull SupportSQLiteDatabase db) {
        super.onCreate(db);
        executor.execute(() -> {
            try {
                String json = Utils.AssetJSONFile(mContext,"db/shortcuts.json");
                JSONArray jsonArray = new JSONArray(json);
                List<ShortCutsEntity> mList = new ArrayList<>();

                for (int i = 0; i < jsonArray.length(); i++) {
                    JSONObject jsonObject = jsonArray.getJSONObject(i);
                    String url = jsonObject.getString("url");

                    ShortCutsEntity entity = new ShortCutsEntity();
                    entity.setFileDate(System.currentTimeMillis());
                    entity.setFileIcon(jsonObject.getString("icon"));
                    entity.setFileTitle(jsonObject.getString("title"));
                    entity.setFileUrl(url);
                    entity.setFileDomain(WebUtils.getDomainName(url));
                    entity.setId(url.hashCode());
                    mList.add(entity);
                }

                // Get the repository from the provider only when needed
                repositoryProvider.get().insertAll(mList);

            } catch (IOException | JSONException e) {
                Log.e("ShortCutCallback", "Initial data seeding failed", e);
            }
        });
    }
}