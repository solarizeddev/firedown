package com.solarized.firedown.data.repository;

import android.text.TextUtils;

import androidx.lifecycle.LiveData;

import com.solarized.firedown.Preferences;
import com.solarized.firedown.data.ShortCutDatabase;
import com.solarized.firedown.data.di.Qualifiers;
import com.solarized.firedown.data.entity.ShortCutsEntity;
import com.solarized.firedown.geckoview.GeckoState;
import com.solarized.firedown.utils.WebUtils;

import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.stream.Collectors;

import javax.inject.Inject;
import javax.inject.Singleton;

@Singleton
public class ShortCutsDataRepository {

    private static final String TAG = "ShortCutsRepository";

    private final ShortCutDatabase mDatabase;
    private final Executor mDiskExecutor;

    // Thread-safe set to prevent ConcurrentModificationException during background sync
    private final Set<Integer> mSyncEntities = Collections.newSetFromMap(new ConcurrentHashMap<>());

    @Inject
    public ShortCutsDataRepository(
            ShortCutDatabase database,
            @Qualifiers.DiskIO Executor diskExecutor) {
        this.mDatabase = database;

        mDiskExecutor = diskExecutor;

        // Sync IDs from DB to memory cache on startup
        mDiskExecutor.execute(() -> {
            List<Integer> ids = mDatabase.shortCutsDao().getAllIds();
            if (ids != null) {
                mSyncEntities.addAll(ids);
            }
        });
    }

    public void insertAll(List<ShortCutsEntity> shortCutsEntities) {
        if (shortCutsEntities == null || shortCutsEntities.isEmpty()) return;

        List<Integer> ids = shortCutsEntities.stream()
                .map(entity -> entity.uid)
                .collect(Collectors.toList());

        mSyncEntities.addAll(ids);
        mDatabase.shortCutsDao().insertAll(shortCutsEntities);
    }

    public void add(GeckoState geckoState) {
        String url = geckoState.getEntityUri();
        if (TextUtils.isEmpty(url)) return;

        ShortCutsEntity entity = createEntityFromGecko(geckoState);
        mSyncEntities.add(entity.getId());
        mDiskExecutor.execute(() -> mDatabase.shortCutsDao().insert(entity));

    }

    public void delete(GeckoState geckoState) {
        String url = geckoState.getEntityUri();
        if (TextUtils.isEmpty(url)) return;

        int id = url.hashCode();
        mSyncEntities.remove(id);
        mDiskExecutor.execute(() -> mDatabase.shortCutsDao().deleteById(id));
    }

    public boolean contains(GeckoState geckoState) {
        if(geckoState == null)
            return false;
        String uri = geckoState.getEntityUri();
        return !TextUtils.isEmpty(uri) && contains(uri.hashCode());
    }

    public boolean contains(int id) {
        return mSyncEntities.contains(id);
    }

    public void deleteAll() {
        mSyncEntities.clear();
        mDiskExecutor.execute(() -> mDatabase.shortCutsDao().deleteAll());
    }

    public LiveData<List<ShortCutsEntity>> getShortCuts() {
        return mDatabase.shortCutsDao().getShortcuts();
    }

    public void add(ShortCutsEntity shortcutsEntity) {
        mDatabase.shortCutsDao().insert(shortcutsEntity);
        mDiskExecutor.execute(() -> mSyncEntities.add(shortcutsEntity.getId()));
    }

    public void update(ShortCutsEntity entity) {
        mDiskExecutor.execute(() -> mDatabase.shortCutsDao().updateShortcut(entity));
    }

    public boolean isFull(){
        return mSyncEntities.size() >= Preferences.SHORTCUTS_LIST_LIMIT;
    }

    public void delete(int id) {
        mSyncEntities.remove(id);
        mDiskExecutor.execute(() -> mDatabase.shortCutsDao().deleteById(id));
    }

    public void delete(ShortCutsEntity entity) {
        mSyncEntities.remove(entity.getId());
        mDiskExecutor.execute(() -> mDatabase.shortCutsDao().delete(entity));
    }

    private ShortCutsEntity createEntityFromGecko(GeckoState state) {
        String url = state.getEntityUri();
        ShortCutsEntity entity = new ShortCutsEntity();
        entity.setFileDate(System.currentTimeMillis());
        entity.setFileIcon(state.getEntityIcon());
        entity.setFileTitle(state.getEntityTitle());
        entity.setFileUrl(url);
        entity.setFileDomain(WebUtils.getDomainName(url));
        entity.setId(url.hashCode());
        return entity;
    }
}
