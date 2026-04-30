package com.solarized.firedown.data.repository;

import android.text.TextUtils;
import androidx.lifecycle.LiveData;
import androidx.paging.PagingSource;
import com.solarized.firedown.Preferences;
import com.solarized.firedown.data.dao.WebHistoryDao;
import com.solarized.firedown.data.di.Qualifiers;
import com.solarized.firedown.data.entity.WebHistoryEntity;
import com.solarized.firedown.utils.WebUtils;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import javax.inject.Inject;
import javax.inject.Singleton;

@Singleton
public class WebHistoryDataRepository {

    private final WebHistoryDao mDao;

    private final Executor mDiskExecutor;

    @Inject
    public WebHistoryDataRepository(WebHistoryDao dao, @Qualifiers.DiskIO Executor diskExecutor) {
        this.mDao = dao;
        mDiskExecutor = diskExecutor;
    }

    public void deleteAll() {
        mDiskExecutor.execute(mDao::deleteAll);
    }

    public void deleteRange(long range) {
        mDiskExecutor.execute(() -> mDao.deleteRange(range));
    }

    public PagingSource<Integer, WebHistoryEntity> get() {
        return mDao.getHistory();
    }

    public PagingSource<Integer, WebHistoryEntity> getSearch(String input) {
        return mDao.getSearch(input);
    }

    public List<WebHistoryEntity> getAutoCompleteSearch(String input) {
        return mDao.getAutoCompleteSearch("%" + input + "%");
    }

    public LiveData<List<WebHistoryEntity>> getWebHistory(int limit) {
        return mDao.getHistory(limit);
    }

    public List<WebHistoryEntity> getAutoCompleteHistory() {
        return mDao.getAutoCompleteHistory();
    }

    public void updateTitle(int id, String title) {
        if (TextUtils.isEmpty(title)) return;
        mDiskExecutor.execute(() -> mDao.updateTitle(id, title));
    }

    public WebHistoryEntity searchHistory(String url, String title) {
        return mDao.getHistorySync(url, title);
    }

    public void purgeDatabase() {
        // Purge records older than 180 days
        mDiskExecutor.execute(() -> mDao.purgeDatabase(System.currentTimeMillis() - TimeUnit.DAYS.toMillis(180)));
    }

    public void updateIconData(String url, String icon, int iconResolution) {
        String domainQuery = WebUtils.getSchemeDomainName(url) + "%";
        mDao.updateIconData(domainQuery, icon, iconResolution);
    }

    public void add(WebHistoryEntity web) {
        mDiskExecutor.execute(() -> mDao.insert(web));
    }

    public void delete(int id) {
        mDiskExecutor.execute(() -> mDao.deleteById(id));
    }

    public void delete(WebHistoryEntity web) {
        mDiskExecutor.execute(() -> mDao.delete(web));
    }

    public void deleteSelection(int selection) {
        mDiskExecutor.execute(() -> {
            long currentTime = System.currentTimeMillis();
            long deleteThreshold;

            switch (selection) {
                case 0: deleteThreshold = currentTime - TimeUnit.MINUTES.toMillis(15); break;
                case 1: deleteThreshold = currentTime - TimeUnit.HOURS.toMillis(1); break;
                case 2: deleteThreshold = currentTime - TimeUnit.DAYS.toMillis(1); break;
                case 3: deleteThreshold = currentTime - TimeUnit.DAYS.toMillis(7); break;
                case 4: deleteThreshold = currentTime - TimeUnit.DAYS.toMillis(30); break;
                case 5: mDao.deleteAll(); return;
                default: return;
            }
            mDao.deleteRange(deleteThreshold);
        });
    }

    public static int generateId(String url) {
        return (int) (getTodayStart() + url.hashCode());
    }

    public static long getTodayStart() {
        long now = System.currentTimeMillis();
        return now - (now % Preferences.ONE_DAY_INTERVAL);
    }
}