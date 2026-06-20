package com.solarized.firedown.autocomplete;

import static java.util.stream.Collectors.collectingAndThen;
import static java.util.stream.Collectors.toCollection;

import android.net.Uri;
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
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
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
    // Typeahead suggestion fetch is a best-effort enrichment of an already-shown
    // dropdown, not a download — so it gets a SHORT overall budget. The shared
    // client (NetworkModule) is tuned for video streaming (no callTimeout, 30s
    // read) which would let a slow/hung engine pin a worker thread for tens of
    // seconds; a dedicated 1.5s callTimeout bounds the whole call.
    private static final long SUGGEST_CALL_TIMEOUT_MS = 1500;
    private final SearchRepository mSearchRepository;
    private final WebHistoryDataRepository mWebHistoryDataRepository;
    private final WebBookmarkDataRepository mWebBookmarkDataRepository;
    private final GeckoStateDataRepository mGeckoStateDataRepository;
    private final IncognitoStateRepository mIncognitoStateDataRepository;
    private final OkHttpClient mSuggestClient;

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
        // Reuse the shared client's connection pool / dispatcher (cheap newBuilder
        // share) but cap the suggestion call so typeahead never stalls on it.
        this.mSuggestClient = httpClient.newBuilder()
                .callTimeout(SUGGEST_CALL_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                .build();
    }

    public void setIncognito(boolean incognito) {
        mIncognito = incognito;
    }

    /** How many "most visited" rows fill the empty-focus suggestion list. */
    private static final int MAX_MOST_VISITED = 5;
    // mostVisited() over-fetches this many candidates and then drops still-blank
    // titles and caps to one row per host, so the query must return well more
    // than the MAX_MOST_VISITED rows actually shown.
    private static final int MOST_VISITED_CANDIDATES = 30;
    // Stable uid for the prepended "Most visited" header (a real history uid is
    // hash(url)+day, so this fixed sentinel can't collide). Keeps DiffUtil happy.
    private static final int MOST_VISITED_HEADER_UID = "firedown.most_visited.header".hashCode();

    /**
     * Top-frecency history rows for the EMPTY-focus suggestion list (Option A:
     * the existing list is filled with these instead of left blank when the
     * address bar is focused and empty). Blocking — call off the main thread.
     *
     * <p>Suppressed in incognito (no history surface there): returns empty, so
     * the caller posts {@code null} and the list stays hidden — the old
     * empty state (clipboard chip only). Rows carry {@code mostVisited=true} and
     * a non-clickable {@code sectionHeader} row is prepended, so the adapter
     * renders them as a labeled "Most visited" section of clean favicon+title
     * rows (its own view types) rather than as history suggestions.
     */
    public List<AutoCompleteEntity> mostVisited() {
        if (mIncognito) return new ArrayList<>();

        // Over-fetch: the per-host cap and blank-title skip below thin the list,
        // so we ask for many candidates and stop once MAX_MOST_VISITED are kept.
        List<WebHistoryEntity> history =
                mWebHistoryDataRepository.getMostVisited(MOST_VISITED_CANDIDATES);
        List<AutoCompleteEntity> items = new ArrayList<>();
        if (history == null) return items;

        // One row per host — a "most visited" rail should show distinct SITES,
        // not several deep links of one binge-watched site (which the exact-URL
        // GROUP BY would otherwise let crowd out everything else). Matches the
        // new-tab "top sites" behaviour of Chrome/Firefox.
        Set<String> seenHosts = new HashSet<>();
        for (WebHistoryEntity entity : history) {
            if (items.size() >= MAX_MOST_VISITED) break;
            String url = entity.getUrl();
            // No "about:blank" tile — the URL fallback can't rescue a row whose
            // URL is itself about:blank (same guard as addHistory; the query
            // already excludes about: URLs, this covers legacy rows).
            if (UrlStringUtils.isAboutBlank(url)) continue;
            // Skip blank-title rows here (unlike typed search, which shows the
            // URL as the title): in a "top sites" rail next to titled rows a
            // bare-URL row reads as broken, and a page that never got a title is
            // the weakest top-site candidate anyway. The query already sources
            // the title from each url's MOST RECENT visit, so this only drops
            // urls that have NEVER had a title.
            String title = entity.getTitle();
            if (UrlStringUtils.isBlankTitle(title)) continue;
            String host = hostOf(url);
            if (host != null && !seenHosts.add(host)) continue;
            AutoCompleteEntity s = new AutoCompleteEntity();
            s.setType(AutoCompleteEntity.HISTORY);
            // Renders via the dedicated MOST_VISITED row (favicon-in-a-badge +
            // title, no URL/glyph); subtext still carries the URL for the tap.
            s.setMostVisited(true);
            s.setTitle(title);
            s.setIcon(entity.getIcon());
            s.setSubText(url);
            s.setUid(entity.getId());
            items.add(s);
        }
        // Prepend the "Most visited" section header (only when there are rows;
        // an empty list stays empty so the observer falls back to showEmpty).
        // The label text is a UI string set by the adapter; the entity just
        // carries the flag + a stable uid for DiffUtil.
        if (!items.isEmpty()) {
            AutoCompleteEntity header = new AutoCompleteEntity();
            header.setSectionHeader(true);
            header.setUid(MOST_VISITED_HEADER_UID);
            items.add(0, header);
        }
        return items;
    }

    /**
     * Registrable-ish host of a URL for the most-visited per-host cap, lowercased
     * and with a leading {@code www.} stripped so {@code www.site.com} and
     * {@code site.com} collapse to one site. Other subdomains stay distinct
     * (no public-suffix list here). Returns {@code null} when the URL has no host
     * (the caller then skips the cap and keeps the row).
     */
    private static String hostOf(String url) {
        if (url == null) return null;
        String host;
        try {
            host = Uri.parse(url).getHost();
        } catch (Exception e) {
            return null;
        }
        if (host == null) return null;
        host = host.toLowerCase(Locale.ROOT);
        if (host.startsWith("www.")) host = host.substring(4);
        return host.isEmpty() ? null : host;
    }

    /**
     * Blocking call — must be invoked from a background thread.
     *
     * <p>Two-phase by design. The on-device sources (history / open tabs /
     * bookmarks) are built and handed to {@code localEmitter} FIRST, so the
     * dropdown fills in single-digit milliseconds and is never gated behind the
     * network suggestion fetch (the old code added the local rows only in a
     * {@code finally} after the HTTP call, so a slow engine delayed the user's
     * OWN history). The returned list is the full, merged result (header +
     * network suggestions + local rows); the caller posts the partial first and
     * the full second, and the adapter's DiffUtil collapses the second post to a
     * no-op when the network added nothing.
     *
     * @param localEmitter receives the header + local rows the instant they are
     *                     ready (may be {@code null} to skip the early emit).
     */
    public List<AutoCompleteEntity> searchSync(String searchTerm,
                                               Consumer<List<AutoCompleteEntity>> localEmitter) {
        if (TextUtils.isEmpty(searchTerm)) return null;

        final List<AutoCompleteEntity> result = new ArrayList<>();
        final String searchOption = mSearchRepository.getSearchType();
        final String searchFormat = mSearchRepository.getSearchFormat();
        final String suggestionUrl = mSearchRepository.getSearchAutocomplete();

        logDebug("engine=" + searchOption
                + " suggestTemplate=" + (TextUtils.isEmpty(suggestionUrl) ? "<none>" : suggestionUrl)
                + " term=" + preview(searchTerm));

        ensureHeader(result, searchTerm, searchOption, searchFormat);
        // Local sources up front (they were previously appended in the network
        // finally) — see the two-phase rationale above.
        addLocalSources(result, searchTerm);

        // Phase 1: emit header + local rows now. A defensive copy, because we keep
        // mutating `result` below to splice the network suggestions into it.
        if (localEmitter != null) {
            localEmitter.accept(new ArrayList<>(result));
        }

        // An engine without a suggestions endpoint (Mojeek operates none —
        // privacy stance; a custom engine's suggestion field is optional)
        // still gets the search header + the local sources above — only the
        // network fetch is skipped. Composing a request from an empty
        // template would throw in Request.Builder.url().
        if (TextUtils.isEmpty(suggestionUrl)) {
            logDebug("no suggestions endpoint for " + searchOption
                    + " — skipping remote fetch (search header + local sources only)");
            return result;
        }

        Request request = new Request.Builder()
                .header(BrowserHeaders.USER_AGENT, BrowserHeaders.getDefaultUserAgentString())
                .url(URLUtil.composeSearchUrl(searchTerm, suggestionUrl, "%s"))
                .build();

        // Phase 2: fetch the engine's suggestions and splice them in just AFTER
        // the search header (index 1) — preserving the historical ordering of
        // header, engine suggestions, then the local history/tabs/bookmarks rows.
        try (Response response = mSuggestClient.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                logDebug("suggest fetch HTTP " + response.code() + " for " + searchOption);
                return result;
            }
            ResponseBody body = response.body();
            List<AutoCompleteEntity> networkItems = new ArrayList<>();
            parseByEngine(networkItems, body.string(), searchOption, searchFormat);
            result.addAll(1, networkItems);
            logDebug("suggest fetch HTTP 200 for " + searchOption
                    + ", parsed " + networkItems.size() + " suggestion(s)");
        } catch (IOException | JSONException e) {
            if (BuildConfig.DEBUG) {
                Log.e(TAG, "Autocomplete network/parse error", e);
            }
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
            // Never surface an about:blank tab (a new/loading tab) — the URL
            // fallback below would otherwise render "about:blank" as the label.
            if (tab.isActive() || tab.isHome() || UrlStringUtils.isURLResouceLike(uri)
                    || UrlStringUtils.isAboutBlank(uri)) continue;

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
            // Skip an about:blank row entirely — never show "about:blank" as a
            // suggestion (the URL fallback can't rescue a row whose URL is itself
            // about:blank). Such rows shouldn't exist (the history insert excludes
            // about: URLs) but legacy/edge rows are guarded here too.
            if (UrlStringUtils.isAboutBlank(entity.getUrl())) continue;
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
            // Same about:blank guard as history — no "about:blank" suggestion.
            if (UrlStringUtils.isAboutBlank(entity.getUrl())) continue;
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

    /**
     * Builds JUST the search header row for {@code searchTerm}, synchronously and
     * with NO DB/network hit (only the engine's icon/format from prefs). Used to
     * swap the empty-focus most-visited list for the typed state in the SAME
     * frame as the first keystroke, so the stale top-sites list never lingers
     * while the background lookup runs; {@link #searchSync} then grows the local
     * + network rows under it.
     */
    public List<AutoCompleteEntity> buildHeaderOnly(String searchTerm) {
        List<AutoCompleteEntity> result = new ArrayList<>();
        if (TextUtils.isEmpty(searchTerm)) return result;
        ensureHeader(result, searchTerm,
                mSearchRepository.getSearchType(), mSearchRepository.getSearchFormat());
        return result;
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