package com.solarized.firedown.data.repository;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.TypedArray;
import android.text.TextUtils;
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
import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
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

    // ── User-defined ("custom") engine ──────────────────────────────────
    // Stored as three prefs (name / search template / optional suggestion
    // template); selection is the CUSTOM_SEARCH_ENGINE sentinel in
    // SETTINGS_SEARCH_ENGINE. When the sentinel is stored but the engine no
    // longer exists (cleared data edge), every getter falls through to the
    // default-engine path, same as an unknown built-in name.

    public boolean hasCustomEngine() {
        return !TextUtils.isEmpty(getCustomEngineName()) && !TextUtils.isEmpty(getCustomSearchUrl());
    }

    public String getCustomEngineName() {
        return mSharedPreferences.getString(Preferences.SETTINGS_SEARCH_ENGINE_CUSTOM_NAME, "");
    }

    public String getCustomSearchUrl() {
        return mSharedPreferences.getString(Preferences.SETTINGS_SEARCH_ENGINE_CUSTOM_URL, "");
    }

    public String getCustomSuggestionUrl() {
        return mSharedPreferences.getString(Preferences.SETTINGS_SEARCH_ENGINE_CUSTOM_SUGGESTION, "");
    }

    private boolean isCustomEngineSelected() {
        return Preferences.CUSTOM_SEARCH_ENGINE.equals(getCurrentEngineName()) && hasCustomEngine();
    }

    /** Persists the custom engine. Caller (SearchFragment) owns validation. */
    public void setCustomEngine(String name, String searchUrl, String suggestionUrl) {
        mSharedPreferences.edit()
                .putString(Preferences.SETTINGS_SEARCH_ENGINE_CUSTOM_NAME, name)
                .putString(Preferences.SETTINGS_SEARCH_ENGINE_CUSTOM_URL, searchUrl)
                .putString(Preferences.SETTINGS_SEARCH_ENGINE_CUSTOM_SUGGESTION, suggestionUrl)
                .apply();
    }

    /** Removes the custom engine; selection falls back to the default engine. */
    public void removeCustomEngine() {
        SharedPreferences.Editor editor = mSharedPreferences.edit()
                .remove(Preferences.SETTINGS_SEARCH_ENGINE_CUSTOM_NAME)
                .remove(Preferences.SETTINGS_SEARCH_ENGINE_CUSTOM_URL)
                .remove(Preferences.SETTINGS_SEARCH_ENGINE_CUSTOM_SUGGESTION);
        if (Preferences.CUSTOM_SEARCH_ENGINE.equals(getCurrentEngineName())) {
            editor.putString(Preferences.SETTINGS_SEARCH_ENGINE, Preferences.DEFAULT_SEARCH_ENGINE);
        }
        editor.apply();
    }

    /** True when the name matches a built-in engine (the custom-name collision check). */
    public boolean isBuiltInEngine(String name) {
        String[] engineMap = mContext.getResources().getStringArray(R.array.settings_search);
        for (String s : engineMap) {
            if (s.equalsIgnoreCase(name)) {
                return true;
            }
        }
        return false;
    }

    public String getSearchType() {
        if (isCustomEngineSelected()) {
            return getCustomEngineName();
        }
        EngineData data = mEngineCache.get(getCurrentEngineName());
        return (data != null) ? data.name : Preferences.DEFAULT_SEARCH_ENGINE;
    }

    public String getSearchFormat() {
        if (isCustomEngineSelected()) {
            return getCustomSearchUrl();
        }
        EngineData data = mEngineCache.get(getCurrentEngineName());
        return (data != null) ? data.searchUrl : Preferences.DEFAULT_SEARCH_FORMAT;
    }

    /**
     * The suggestion-URL template — may be EMPTY for an engine without a
     * suggestions endpoint (Mojeek runs none; a custom engine's field is
     * optional). Callers must skip the network fetch on empty, not compose
     * a request from it.
     */
    public String getSearchAutocomplete() {
        if (isCustomEngineSelected()) {
            return getCustomSuggestionUrl();
        }
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
        // The custom engine is selected via its sentinel, never by name.
        if (Preferences.CUSTOM_SEARCH_ENGINE.equals(searchEngine)) {
            if (hasCustomEngine()) {
                mSharedPreferences.edit().putString(Preferences.SETTINGS_SEARCH_ENGINE, searchEngine).apply();
            }
            return;
        }
        String[] engineMap = mContext.getResources().getStringArray(R.array.settings_search);
        for (String s : engineMap) {
            if (s.equals(searchEngine)) {
                mSharedPreferences.edit().putString(Preferences.SETTINGS_SEARCH_ENGINE, searchEngine).apply();
                return;
            }
        }
    }

    /**
     * Resolves user input to a URL.
     *
     * URL-like or valid-search-query input is returned normalised. Anything
     * else is treated as a search term and embedded into the configured
     * search engine's URL after URL-encoding — without the encoding,
     * queries with spaces (e.g. "cnn cnn") produced URLs containing
     * literal whitespace, which then failed {@link UrlStringUtils#isURLLike}
     * on a subsequent call to this method and got re-wrapped in the search
     * format on each pass. URL-encoding makes parseUri idempotent and
     * matches what {@code AutoCompleteSearch.encodeSearch} already does
     * for the autocomplete dropdown's URLs.
     */
    public String parseUri(String currentUri) {
        String uri = currentUri;
        if (!UrlStringUtils.isURLLike(uri) && !UrlStringUtils.isValidSearchQueryUrl(uri)) {
            String encoded;
            try {
                encoded = URLEncoder.encode(uri, "UTF-8");
            } catch (UnsupportedEncodingException e) {
                encoded = uri;
            }
            uri = String.format(getSearchFormat(), encoded);
        }
        return UrlStringUtils.toNormalizedURL(uri);
    }
}
