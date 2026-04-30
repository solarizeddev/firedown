package com.solarized.firedown.autocomplete;

import static java.util.stream.Collectors.collectingAndThen;
import static java.util.stream.Collectors.toCollection;

import android.text.TextUtils;
import android.util.Log;
import android.webkit.URLUtil;

import com.solarized.firedown.data.entity.GeckoStateEntity;
import com.solarized.firedown.data.entity.AutoCompleteEntity;
import com.solarized.firedown.data.entity.WebHistoryEntity;
import com.solarized.firedown.data.repository.GeckoStateDataRepository;
import com.solarized.firedown.data.repository.IncognitoStateRepository;
import com.solarized.firedown.data.repository.SearchRepository;
import com.solarized.firedown.data.repository.WebHistoryDataRepository;
import com.solarized.firedown.geckoview.GeckoState;
import com.solarized.firedown.utils.BrowserHeaders;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.inject.Inject;
import javax.inject.Singleton;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

@Singleton
public class AutoCompleteSearch {

    private static final String TAG = AutoCompleteSearch.class.getName();
    private static final Pattern PATTERN_JSON_BAIDU = Pattern.compile("(\\{.*?\\})");
    private static final Pattern PATTERN_JSON_YAHOO = Pattern.compile("\\((.*?)\\)");
    private static final int MAX_RESULTS = 3;
    private final SearchRepository mSearchRepository;
    private final WebHistoryDataRepository mWebHistoryDataRepository;
    private final GeckoStateDataRepository mGeckoStateDataRepository;
    private final IncognitoStateRepository mIncognitoStateDataRepository;
    private final OkHttpClient mHttpClient;

    private volatile boolean mIncognito;

    @Inject
    public AutoCompleteSearch(
            SearchRepository searchRepository,
            WebHistoryDataRepository webHistoryRepository,
            GeckoStateDataRepository geckoStateDataRepository,
            IncognitoStateRepository incognitoStateRepository,
            OkHttpClient httpClient) {
        this.mSearchRepository = searchRepository;
        this.mIncognitoStateDataRepository = incognitoStateRepository;
        this.mGeckoStateDataRepository = geckoStateDataRepository;
        this.mWebHistoryDataRepository = webHistoryRepository;
        this.mHttpClient = httpClient;
    }

    public void setIncognito(boolean incognito) {
        mIncognito = incognito;
    }

    /**
     * Blocking call — must be invoked from a background thread.
     */
    public List<AutoCompleteEntity> searchSync(String searchTerm) {
        if (TextUtils.isEmpty(searchTerm)) return null;

        final List<AutoCompleteEntity> result = new ArrayList<>();
        final String searchOption = mSearchRepository.getSearchType();
        final String searchFormat = mSearchRepository.getSearchFormat();

        ensureHeader(result, searchTerm, searchOption, searchFormat);

        Request request = new Request.Builder()
                .header(BrowserHeaders.USER_AGENT, BrowserHeaders.getDefaultUserAgentString())
                .url(URLUtil.composeSearchUrl(searchTerm, mSearchRepository.getSearchAutocomplete(), "%s"))
                .build();

        try (Response response = mHttpClient.newCall(request).execute()) {
            if (!response.isSuccessful()) return result;
            ResponseBody body = response.body();
            parseByEngine(result, body.string(), searchOption, searchFormat);
        } catch (IOException | JSONException e) {
            Log.e(TAG, "Autocomplete network/parse error", e);
        } finally {
            addHistory(result, searchTerm);
            // Add matching open tabs first (before network call, so they appear quickly in order)
            addOpenTabs(result, searchTerm, mIncognito);
        }
        return result;
    }

    private void addOpenTabs(List<AutoCompleteEntity> result, String input, boolean incognito) {
        if (TextUtils.isEmpty(input)) return;
        String lowerInput = input.toLowerCase();

        List<GeckoStateEntity> tabs = incognito
                ? mIncognitoStateDataRepository.getTabsLiveData().getValue()
                : mGeckoStateDataRepository.getTabsLiveData().getValue();

        if (tabs == null) return;

        for (GeckoStateEntity tab : tabs) {
            String uri = tab.getUri();
            String title = tab.getTitle();
            if (tab.isActive() || tab.isHome()) continue;

            boolean matchesUri = !TextUtils.isEmpty(uri) && uri.toLowerCase().contains(lowerInput);
            boolean matchesTitle = !TextUtils.isEmpty(title) && title.toLowerCase().contains(lowerInput);

            if (matchesUri || matchesTitle) {
                AutoCompleteEntity entity = new AutoCompleteEntity();
                entity.setType(AutoCompleteEntity.TAB);
                entity.setSessionId(tab.getId());
                entity.setTitle(title);
                entity.setSubText(uri);
                entity.setIcon(tab.getIcon());
                entity.setUid(tab.getId());
                result.add(entity);
            }
        }
    }

    private void parseByEngine(List<AutoCompleteEntity> result, String response, String engine, String format) throws JSONException {
        JSONArray jsonArray;
        Matcher m;

        // Optimized Switch using Java 17 syntax for readability
        switch (engine) {
            case "Google", "StartPage", "Brave", "Bing", "Yandex" -> {
                jsonArray = new JSONArray(response).optJSONArray(1);
                processJsonArray(result, jsonArray, engine, format);
            }
            case "DuckDuckGo" -> {
                jsonArray = new JSONArray(response);
                int length = Math.min(jsonArray.length(), MAX_RESULTS);
                for (int i = 0; i < length; i++) {
                    parse(result, engine, format, jsonArray.optJSONObject(i).optString("phrase"));
                }
            }
            case "Baidu" -> {
                m = PATTERN_JSON_BAIDU.matcher(response);
                if (m.find()) {
                    jsonArray = new JSONObject(m.group(1)).getJSONArray("s");
                    processJsonArray(result, jsonArray, engine, format);
                }
            }
            case "Yahoo" -> {
                m = PATTERN_JSON_YAHOO.matcher(response);
                if (m.find()) {
                    jsonArray = new JSONObject(m.group(1))
                            .getJSONObject("gossip").getJSONArray("results");
                    int length = Math.min(jsonArray.length(), MAX_RESULTS);
                    for (int i = 0; i < length; i++) {
                        parse(result, engine, format, jsonArray.getJSONObject(i).getString("key"));
                    }
                }
            }
        }
    }

    private void processJsonArray(List<AutoCompleteEntity> result, JSONArray array, String engine, String format) {
        if (array == null) return;
        int length = Math.min(array.length(), MAX_RESULTS);
        for (int i = 0; i < length; i++) {
            parse(result, engine, format, array.optString(i));
        }
    }

    private void addHistory(List<AutoCompleteEntity> result, String input) {
        List<WebHistoryEntity> history = mWebHistoryDataRepository.getAutoCompleteSearch(input);
        if (history.isEmpty()) return;

        List<AutoCompleteEntity> historyItems = new ArrayList<>();
        for (WebHistoryEntity entity : history) {
            AutoCompleteEntity s = new AutoCompleteEntity();
            s.setType(AutoCompleteEntity.HISTORY);
            s.setTitle(entity.getTitle());
            s.setIcon(entity.getIcon());
            s.setSubText(entity.getUrl());
            s.setUid(entity.getId());
            historyItems.add(s);
        }

        // De-duplicate history against network results based on URL
        result.addAll(historyItems.stream().collect(collectingAndThen(
                toCollection(() -> new TreeSet<>(Comparator.comparing(AutoCompleteEntity::getSubText))),
                ArrayList::new)));
    }

    private void parse(List<AutoCompleteEntity> result, String option, String format, String text) {
        if (TextUtils.isEmpty(text)) return;
        AutoCompleteEntity entity = new AutoCompleteEntity();
        entity.setType(AutoCompleteEntity.RESULTS);
        entity.setDrawableId(mSearchRepository.getIcon(option));
        entity.setTitle(text);
        entity.setSubText(encodeSearch(format, text));
        entity.setUid(text.hashCode());
        result.add(entity);
    }

    private void ensureHeader(List<AutoCompleteEntity> result, String s, String option, String format) {
        AutoCompleteEntity header = new AutoCompleteEntity();
        header.setDrawableId(mSearchRepository.getIcon(option));
        header.setTitle(s);
        header.setSubText(encodeSearch(format, s));
        header.setUid(s.hashCode());
        header.setType(AutoCompleteEntity.SEARCH);
        result.add(header);
    }

    private String encodeSearch(String format, String s) {
        try {
            return String.format(format, URLEncoder.encode(s, "UTF-8"));
        } catch (UnsupportedEncodingException e) {
            return String.format(format, s);
        }
    }
}