package com.solarized.firedown.data.repository;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.TypedArray;
import android.util.Log;

import com.solarized.firedown.Preferences;
import com.solarized.firedown.R;
import com.solarized.firedown.data.di.Qualifiers;
import com.solarized.firedown.utils.UrlStringUtils;
import com.solarized.firedown.utils.Utils;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Executor;

import javax.inject.Inject;
import javax.inject.Singleton;

import dagger.hilt.android.qualifiers.ApplicationContext;

@Singleton
public class SearchRepository {

    private static final String TAG = "SearchRepository";

    private final SharedPreferences mSharedPreferences;
    private final Context mContext;
    private final Map<String, EngineData> mEngineCache = new HashMap<>();

    // Internal data holder to avoid repeated JSON parsing
    private static class EngineData {
        final String name;
        final String searchUrl;
        final String suggestionUrl;

        EngineData(JSONObject obj) throws JSONException {
            this.name = obj.getString("name");
            this.searchUrl = obj.getString("search");
            this.suggestionUrl = obj.getString("suggestion");
        }
    }

    @Inject
    public SearchRepository(
            @ApplicationContext Context context,
            SharedPreferences sharedPreferences,
            @Qualifiers.DiskIO Executor diskExecutor) {
        this.mContext = context;
        this.mSharedPreferences = sharedPreferences;

        // Load JSON once in background
        diskExecutor.execute(this::loadSearchEngines);
    }

    private void loadSearchEngines() {
        try {
            String json = Utils.AssetJSONFile(mContext,"search/list.json");
            JSONArray array = new JSONArray(json);
            for (int i = 0; i < array.length(); i++) {
                EngineData data = new EngineData(array.getJSONObject(i));
                mEngineCache.put(data.name, data);
            }
        } catch (IOException | JSONException e) {
            Log.e(TAG, "Failed to load search engines", e);
        }
    }

    private String getCurrentEngineName() {
        return mSharedPreferences.getString(Preferences.SETTINGS_SEARCH_ENGINE, Preferences.DEFAULT_SEARCH_ENGINE);
    }

    public String getSearchType() {
        EngineData data = mEngineCache.get(getCurrentEngineName());
        return (data != null) ? data.name : Preferences.DEFAULT_SEARCH_ENGINE;
    }

    public String getSearchFormat() {
        EngineData data = mEngineCache.get(getCurrentEngineName());
        return (data != null) ? data.searchUrl : Preferences.DEFAULT_SEARCH_FORMAT;
    }

    public String getSearchAutocomplete() {
        EngineData data = mEngineCache.get(getCurrentEngineName());
        return (data != null) ? data.suggestionUrl : Preferences.DEFAULT_SEARCH_AUTOCOMPLETE;
    }

    public int getIcon(String searchEngine) {
        int resource = R.drawable.ic_search_24;
        TypedArray imgs = mContext.getResources().obtainTypedArray(R.array.settings_search_icon);
        String[] engineMap = mContext.getResources().getStringArray(R.array.settings_search);

        for (int i = 0; i < engineMap.length; i++) {
            if (engineMap[i].equals(searchEngine)) {
                resource = imgs.getResourceId(i, resource);
                break;
            }
        }
        imgs.recycle();
        return resource;
    }

    public boolean getSearchHosts(String host) {
        String[] engineMap = mContext.getResources().getStringArray(R.array.settings_host);
        String cleanedHost = host.replaceFirst("^(http[s]?://www\\.|http[s]?://|www\\.)", "");

        for (String s : engineMap) {
            if (s.equals(host) || s.equals(cleanedHost)) {
                return true;
            }
        }
        return false;
    }

    public void setSearchEngine(String searchEngine) {
        String[] engineMap = mContext.getResources().getStringArray(R.array.settings_search);
        for (String s : engineMap) {
            if (s.equals(searchEngine)) {
                mSharedPreferences.edit().putString(Preferences.SETTINGS_SEARCH_ENGINE, searchEngine).apply();
                return;
            }
        }
    }

    public String parseUri(String currentUri) {
        String uri = currentUri;
        if (!UrlStringUtils.isURLLike(uri) && !UrlStringUtils.isValidSearchQueryUrl(uri)) {
            uri = String.format(getSearchFormat(), uri);
        }
        return UrlStringUtils.toNormalizedURL(uri);
    }
}
