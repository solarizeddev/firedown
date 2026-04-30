package com.solarized.firedown.data.repository;

import androidx.lifecycle.MutableLiveData;

import com.solarized.firedown.data.dao.TrackingPermissionDao;
import com.solarized.firedown.data.di.Qualifiers;
import com.solarized.firedown.data.entity.TrackingPermissionsEntity;
import com.solarized.firedown.utils.WebUtils;
import java.util.HashSet;
import java.util.concurrent.Executor;

import javax.inject.Inject;
import javax.inject.Singleton;

@Singleton
public class TrackingPermissionRepository {

    private final TrackingPermissionDao mDao;
    private final Executor mDiskExecutor;
    private final HashSet<Integer> mSyncEntities = new HashSet<>();
    private final MutableLiveData<Boolean> mTrackingMutableLiveData = new MutableLiveData<>();

    @Inject
    public TrackingPermissionRepository(TrackingPermissionDao dao, @Qualifiers.DiskIO Executor diskExecutor) {
        this.mDao = dao;
        this.mDiskExecutor = diskExecutor;

        // Initial load of IDs into the fast-access HashSet
        mDiskExecutor.execute(() -> {
            mSyncEntities.addAll(mDao.getAllIds());
        });
    }

    public void add(String url) {
        String domain = WebUtils.getDomainName(url);
        int id = domain.hashCode();

        TrackingPermissionsEntity entity = new TrackingPermissionsEntity();
        entity.setId(id);
        entity.setOrigin(domain);
        entity.setDate(System.currentTimeMillis());

        mSyncEntities.add(id);
        mDiskExecutor.execute(() -> mDao.insert(entity));

    }

    public void delete(String url) {
        String domain = WebUtils.getDomainName(url);
        int id = domain.hashCode();

        mSyncEntities.remove(id);
        mDiskExecutor.execute(() -> mDao.deleteById(id));
    }

    public boolean contains(String url) {
        if (url == null) return false;
        String domain = WebUtils.getDomainName(url);
        return mSyncEntities.contains(domain.hashCode());
    }

    public void setTrackingMutableData(boolean value){
        mTrackingMutableLiveData.postValue(value);
    }

    public MutableLiveData<Boolean> getTrackingMutableLiveData(){
        return mTrackingMutableLiveData;
    }
}
