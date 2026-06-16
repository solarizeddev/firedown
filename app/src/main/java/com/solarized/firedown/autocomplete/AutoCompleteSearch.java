package com.solarized.firedown.autocomplete;

import static java.util.stream.Collectors.collectingAndThen;
import static java.util.stream.Collectors.toCollection;

import android.text.TextUtils;
import android.util.Log;
import android.webkit.URLUtil;

import com.solarized.firedown.BuildConfig;
import com.solarized.firedown.data.entity.GeckoStateEntity;
import com.solarized.firedown.data.entity.AutoCompleteEntity;
import com.solarized.firedown.data.entity.WebBookmarkEntity;
import com.solarized.firedown.data.entity.WebHistoryEntity;
import com.solarized.firedown.data.repository.GeckoStateDataRepository;
import com.solarized.firedown.data.repository.IncognitoStateRepository;
import com.solarized.firedown.data.repository.SearchRepository;
import com.solarized.firedown.data.repository.WebBookmarkDataRepository;
import com.solarized.firedown.data.repository.WebHistoryDataRepository;
import com.solarized.firedown.utils.BrowserHeaders;
import com.solarized.firedown.utils.UrlStringUtils;

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
    private static final int MAX_RESULTS = 3;
    private final SearchRepository mSearchRepository;
    private final WebHistoryDataRepository mWebHistoryDataRepository;
    private final WebBookmarkDataRepository mWebBookmarkDataRepository;
    private final GeckoStateDataRepository mGeckoStateDataRepository;
    private final IncognitoStateRepository mIncognitoStateDataRepository;
    private final OkHttpClient mHttpClient;

    private volatile boolean mIncognito;

    @Inject
    public AutoCompleteSearch(
            SearchRepository searchRepository,
            WebHistoryDataRepository webHistoryRepository,
            WebBookmarkDataRepository webBookmarkRepository,
            GeckoStateDataRepository geckoStateDataRepository,
            IncognitoStateRepository incognitoStateRepository,
            OkHttpClient httpClient) {
        this.mSearchRepository = searchRepository;
        this.mIncognitoStateDataRepository = incognitoStateRepository;
        this.mGeckoStateDataRepository = geckoStateDataRepository;
        this.mWebHistoryDataRepository = webHistoryRepository;
        this.mWebBookmarkDataRepository = webBookmarkRepository;
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
        final String suggestionUrl = mSearchRepository.getSearchAutocomplete();

        logDebug("engine=" + searchOption
                + " suggestTemplate=" + (TextUtils.isEmpty(suggestionUrl) ? "<none>" : suggestionUrl)
                + " term=" + preview(searchTerm));

        ensureHeader(result, searchTerm, searchOption, searchFormat);

        // An engine without a suggestions endpoint (Mojeek operates none —
        // privacy stance; a custom engine's suggestion field is optional)
        // still gets the search header + the local sources below — only the
        // network fetch is skipped. Composing a request from an empty
        // template would throw in Request.Builder.url().
        if (TextUtils.isEmpty(suggestionUrl)) {
            logDebug("no suggestions endpoint for " + searchOption
                    + " — skipping remote fetch (search header + local sources only)");
            addLocalSources(result, searchTerm);
            return result;
        }

        Request request = new Request.Builder()
                .header(BrowserHeaders.USER_AGENT, BrowserHeaders.getDefaultUserAgentString())
                .url(URLUtil.composeSearchUrl(searchTerm, suggestionUrl, "%s"))
                .build();

        try (Response response = mHttpClient.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                logDebug("suggest fetch HTTP " + response.code() + " for " + searchOption);
                return result;
            }
            ResponseBody body = response.body();
            int before = result.size();
            parseByEngine(result, body.string(), searchOption, searchFormat);
            logDebug("suggest fetch HTTP 200 for " + searchOption
                    + ", parsed " + (result.size() - before) + " suggestion(s)");
        } catch (IOException | JSONException e) {
            if (BuildConfig.DEBUG) {
                Log.e(TAG, "Autocomplete network/parse error", e);
            }
        } finally {
            addLocalSources(result, searchTerm);
        }
        return result;
    }

    /** Cap on logged user-typed text (the URL-bar paste lesson — never log it whole). */
    private static final int LOG_TERM_PREVIEW = 64;

    private static void logDebug(String message) {
        if (BuildConfig.DEBUG) {
            Log.d(TAG, message);
        }
    }

    private static String preview(String s) {
        if (s == null) return "null";
        if (s.length() <= LOG_TERM_PREVIEW) return s;
        return s.substring(0, LOG_TERM_PREVIEW) + "… (" + s.length() + " chars)";
    }

    /** History, matching open tabs, bookmarks — the non-network suggestion sources. */
    private void addLocalSources(List<AutoCompleteEntity> result, String searchTerm) {
        addHistory(result, searchTerm);
        // Add matching open tabs first (before network call, so they appear quickly in order)
        addOpenTabs(result, searchTerm, mIncognito);
        addBookmarks(result, searchTerm);
    }

    /** Cap on tab matches surfaced as switch-to-tab autocomplete
     *  entries. A search engine + history are also in the suggestion
     *  list; flooding it with every tab whose title contains the
     *  query (e.g. typing 'n' against 20 open tabs) drowns the
     *  other suggestions and makes the list scroll for no reason. */
    private static final int MAX_TAB_MATCHES = 3;

    private void addOpenTabs(List<AutoCompleteEntity> result, String input, boolean incognito) {
        if (TextUtils.isEmpty(input)) return;
        String lowerInput = input.toLowerCase();

        List<GeckoStateEntity> tabs = incognito
                ? mIncognitoStateDataRepository.getTabsLiveData().getValue()
                : mGeckoStateDataRepository.getTabsLiveData().getValue();

        if (tabs == null) return;

        int added = 0;
        for (GeckoStateEntity tab : tabs) {
            if (added >= MAX_TAB_MATCHES) break;
            String uri = tab.getUri();
            String title = tab.getTitle();
            // The home/start page tab is an internal about: URL — exposing it
            // as a switch-to-tab match leaks the internal scheme and the entry
            // is useless (the user already sees the start page when they open
            // a new tab). isHome() isn't always set on restored sessions, so
            // also match the URL directly as a backstop.
            if (tab.isActive() || tab.isHome() || UrlStringUtils.isURLResouceLike(uri)) continue;

            boolean matchesUri = !TextUtils.isEmpty(uri) && uri.toLowerCase().contains(lowerInput);
            boolean matchesTitle = !TextUtils.isEmpty(title) && title.toLowerCase().contains(lowerInput);

            if (matchesUri || matchesTitle) {
                AutoCompleteEntity entity = new AutoCompleteEntity();
                entity.setType(AutoCompleteEntity.TAB);
                entity.setSessionId(tab.getId());
                // Blank/about:blank title (a tab still loading) → show the URL,
                // never a blank suggestion (Firefox awesomebar parity).
                entity.setTitle(UrlStringUtils.isBlankTitle(title) ? uri : title);
                entity.setSubText(uri);
                entity.setIcon(tab.getIcon());
                entity.setUid(tab.getId());
                result.add(entity);
                added++;
            }
        }
    }

    private void parseByEngine(List<AutoCompleteEntity> result, String response, String engine, String format) throws JSONException {
        JSONArray jsonArray;
        Matcher m;

        // Optimized Switch using Java 17 syntax for readability
        switch (engine) {
            case "Google", "StartPage", "Brave", "Bing", "Yandex", "Ecosia" -> {
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
            default -> {
                // User-defined custom engine: best-effort parse of the
                // de-facto OpenSearch suggestion shape — ["query", ["s1", …]]
                // (what Google/Brave/Ecosia-class endpoints return, and what
                // Firefox expects of a custom engine's suggest URL). Any
                // other payload lands in the caller's JSONException catch
                // and degrades to the local sources.
                jsonArray = new JSONArray(response).optJSONArray(1);
                processJsonArray(result, jsonArray, engine, format);
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
            // Blank/about:blank title → show the URL (Firefox shows the URL for a
            // titleless entry); covers a mid-load row and legacy about:blank rows.
            String historyTitle = entity.getTitle();
            s.setTitle(UrlStringUtils.isBlankTitle(historyTitle) ? entity.getUrl() : historyTitle);
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

    private void addBookmarks(List<AutoCompleteEntity> result, String input) {
        List<WebBookmarkEntity> bookmarks = mWebBookmarkDataRepository.getAutoCompleteSearch(input);
        if (bookmarks == null || bookmarks.isEmpty()) return;

        List<AutoCompleteEntity> bookmarkItems = new ArrayList<>();
        for (WebBookmarkEntity entity : bookmarks) {
            AutoCompleteEntity s = new AutoCompleteEntity();
            s.setType(AutoCompleteEntity.BOOKMARK);
            // Blank/about:blank title → show the URL (covers a bookmark saved
            // mid-load, before its title backfilled, and legacy About:blank rows).
            String bookmarkTitle = entity.getTitle();
            s.setTitle(UrlStringUtils.isBlankTitle(bookmarkTitle) ? entity.getUrl() : bookmarkTitle);
            s.setIcon(entity.getIcon());
            s.setSubText(entity.getUrl());
            s.setUid(entity.getId());
            bookmarkItems.add(s);
        }

        result.addAll(bookmarkItems.stream().collect(collectingAndThen(
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