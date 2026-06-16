package com.solarized.firedown.data.repository;

import android.text.TextUtils;
import androidx.lifecycle.LiveData;
import androidx.paging.PagingSource;
import androidx.sqlite.db.SimpleSQLiteQuery;
import com.solarized.firedown.Preferences;
import com.solarized.firedown.data.dao.WebHistoryDao;
import com.solarized.firedown.data.di.Qualifiers;
import com.solarized.firedown.data.entity.WebHistoryEntity;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
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

    // Max autocomplete history rows (mirrors the LIMIT in the LIKE DAO query).
    private static final int AUTOCOMPLETE_LIMIT = 3;

    // FTS-backed autocomplete lookup over webhistory_fts (see WebHistoryDatabase).
    // The old infix `LIKE '%term%'` was a full table scan on the (unbounded)
    // history table on every keystroke; the FTS index makes the common prefix
    // typeahead a seek instead.
    private static final String FTS_SQL =
            "SELECT * FROM webhistory WHERE uid IN "
                    + "(SELECT docid FROM webhistory_fts WHERE webhistory_fts MATCH ?) "
                    + "ORDER BY file_date DESC LIMIT ?";

    public List<WebHistoryEntity> getAutoCompleteSearch(String input) {
        String match = toFtsPrefixQuery(input);
        if (match == null) {
            // The term sanitised to nothing the FTS 'simple' tokenizer indexes
            // (e.g. all-punctuation, or a CJK-only term — the simple tokenizer
            // isn't word-segmenting). Fall back to the infix scan so such a term
            // never regresses to zero results.
            return mDao.getAutoCompleteSearch("%" + input + "%");
        }

        List<WebHistoryEntity> fts = mDao.getAutoCompleteFts(
                new SimpleSQLiteQuery(FTS_SQL, new Object[]{match, AUTOCOMPLETE_LIMIT}));
        if (fts.size() >= AUTOCOMPLETE_LIMIT) {
            return fts;
        }

        // FTS matches token PREFIXES (youtube* → youtube.com) but cannot match
        // mid-token (tube → youtube). When the indexed query underfills, top up
        // with the infix LIKE scan so that capability is preserved — this scan
        // only runs on the rare underfill, so the common prefix path stays fully
        // indexed. Dedup by uid; the FTS hits keep their leading (recency) order.
        return mergeDistinctById(fts, mDao.getAutoCompleteSearch("%" + input + "%"));
    }

    // Turn the typed term into an FTS4 prefix query: lowercase, split on the
    // ASCII non-alphanumerics the 'simple' tokenizer treats as separators, and
    // append '*' to each token so it prefix-matches ("github co" → "github* co*",
    // ANDed). Returns null when nothing indexable remains (caller falls back).
    private static String toFtsPrefixQuery(String input) {
        if (TextUtils.isEmpty(input)) return null;
        StringBuilder sb = new StringBuilder();
        for (String token : input.toLowerCase().split("[^\\p{Alnum}]+")) {
            if (token.isEmpty()) continue;
            if (sb.length() > 0) sb.append(' ');
            sb.append(token).append('*');
        }
        return sb.length() == 0 ? null : sb.toString();
    }

    private static List<WebHistoryEntity> mergeDistinctById(
            List<WebHistoryEntity> primary, List<WebHistoryEntity> extra) {
        List<WebHistoryEntity> out = new ArrayList<>(primary);
        Set<Integer> seen = new HashSet<>();
        for (WebHistoryEntity e : primary) seen.add(e.getId());
        for (WebHistoryEntity e : extra) {
            if (out.size() >= AUTOCOMPLETE_LIMIT) break;
            if (seen.add(e.getId())) out.add(e);
        }
        return out;
    }

    public LiveData<List<WebHistoryEntity>> getWebHistory(int limit) {
        return mDao.getHistory(limit);
    }

    public List<WebHistoryEntity> getAutoCompleteHistory() {
        return mDao.getAutoCompleteHistory();
    }

    public void updateTitle(String url, String title) {
        if (TextUtils.isEmpty(url) || TextUtils.isEmpty(title)) return;
        mDiskExecutor.execute(() -> mDao.updateTitleByUrl(url, title));
    }

    public WebHistoryEntity searchHistory(String url, String title) {
        return mDao.getHistorySync(url, title);
    }

    public void purgeDatabase() {
        // Purge records older than the retention window (HISTORY_RETENTION_INTERVAL).
        // Throttled to once/day by App.purgeDatabases().
        //
        // A non-positive window means "keep forever" — skip the purge entirely.
        // Without this guard a NEVER (-1) window would compute a cutoff of
        // now + 1ms and delete the ENTIRE history (file_date <= future).
        if (Preferences.HISTORY_RETENTION_INTERVAL <= 0) return;
        mDiskExecutor.execute(() -> mDao.purgeDatabase(System.currentTimeMillis() - Preferences.HISTORY_RETENTION_INTERVAL));
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