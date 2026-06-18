package com.solarized.firedown.autocomplete;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.util.Log;

import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.ViewModel;

import com.solarized.firedown.Preferences;
import com.solarized.firedown.data.di.Qualifiers;
import com.solarized.firedown.data.entity.AutoCompleteEntity;
import com.solarized.firedown.data.entity.WebHistoryEntity;
import com.solarized.firedown.data.repository.WebHistoryDataRepository;
import com.solarized.firedown.utils.BuildUtils;
import com.solarized.firedown.utils.UrlStringUtils;
import com.solarized.firedown.utils.WebUtils;

import org.apache.commons.lang3.StringUtils;

import java.util.List;
import java.util.concurrent.Future;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

import javax.inject.Inject;

import dagger.hilt.android.lifecycle.HiltViewModel;

@HiltViewModel
public class AutoCompleteViewModel extends ViewModel {

    private static final String TAG = AutoCompleteViewModel.class.getName();

    private final MutableLiveData<String> mAutoCompleteData = new MutableLiveData<>();
    private final MutableLiveData<List<AutoCompleteEntity>> mSearchData = new MutableLiveData<>();

    // Debounce window for the suggestion search: a burst of keystrokes collapses
    // to ONE query fired after the typing pauses. Kept short (below the ~100ms
    // perception threshold) so it feels instant while still sparing a DB lookup +
    // network call on every intermediate character.
    private static final long SEARCH_DEBOUNCE_MS = 90;

    private final WebHistoryDataRepository mWebHistoryDataRepository;
    private final AutoCompleteSearch mAutoCompleteSearch;
    private final ClipboardManager mClipboardManager;
    private final ExecutorService mAutoCompleteExecutor;
    private final Handler mDebounceHandler = new Handler(Looper.getMainLooper());
    private Runnable mPendingSearch;
    private Future<?> mAutoCompleteFuture;
    private Future<?> mSearchFuture;
    private volatile String mCurrentSearchTerm;

    // True while the suggestion list currently holds the empty-focus "most
    // visited" rows (Option A) rather than typed search results. Set/cleared on
    // the main thread (the callers below all run from the URL-bar handlers); the
    // getWebSearch observer reads it on the main thread too, so there is no race
    // — and a stale post is independently dropped by the mSearchGen guard.
    private volatile boolean mShowingMostVisited;

    // Generation counters: each new submission bumps the counter; tasks check
    // their captured generation before posting so a slow cancelled task can't
    // overwrite the result of a newer submission.
    private final AtomicLong mAutoCompleteGen = new AtomicLong();
    private final AtomicLong mSearchGen = new AtomicLong();

    @Inject
    public AutoCompleteViewModel(
            WebHistoryDataRepository repository,
            AutoCompleteSearch autoCompleteSearch,
            ClipboardManager clipboardManager,
            @Qualifiers.AutoComplete ExecutorService autoCompleteExecutor) {
        this.mWebHistoryDataRepository = repository;
        this.mAutoCompleteSearch = autoCompleteSearch;
        this.mClipboardManager = clipboardManager;
        this.mAutoCompleteExecutor = autoCompleteExecutor;
    }


    public LiveData<List<AutoCompleteEntity>> getWebSearch() {
        return mSearchData;
    }

    /** True when the current {@link #getWebSearch()} list is the empty-focus
     *  "most visited" set, so the view keeps the clipboard chip visible
     *  alongside it (vs. typed results, which hide the chip). */
    public boolean isShowingMostVisited() {
        return mShowingMostVisited;
    }

    /**
     * Empty-focus state (Option A): fill the suggestion list with top-frecency
     * "most visited" rows instead of leaving it blank. Routed through the SAME
     * {@link #mSearchData} LiveData and {@link #mSearchGen} generation guard as
     * the typed search, so a late most-visited post can't clobber a newer
     * typed-search result, nor vice-versa (whichever request is newest wins;
     * the older task's post is discarded by the generation check). Incognito
     * yields an empty list → {@code null} post → the list stays hidden, i.e. the
     * old empty state (clipboard chip only).
     */
    public void loadMostVisited() {
        cancelPendingSearch();
        cancelFuture(mSearchFuture);
        mCurrentSearchTerm = null;
        mShowingMostVisited = true;
        final long gen = mSearchGen.incrementAndGet();

        mSearchFuture = mAutoCompleteExecutor.submit(() -> {
            try {
                List<AutoCompleteEntity> list = mAutoCompleteSearch.mostVisited();
                if (gen == mSearchGen.get()) {
                    mSearchData.postValue(list.isEmpty() ? null : list);
                }
            } catch (Exception e) {
                if (!Thread.currentThread().isInterrupted() && gen == mSearchGen.get()) {
                    Log.e(TAG, "loadMostVisited failed:", e);
                    mSearchData.postValue(null);
                }
            }
        });
    }

    public LiveData<String> getAutoComplete() {
        return mAutoCompleteData;
    }

    /**
     * Finds a history-based URL suggestion for the address bar.
     */
    public void autoComplete(final String stringToFind) {
        cancelFuture(mAutoCompleteFuture);
        final long gen = mAutoCompleteGen.incrementAndGet();

        if (TextUtils.isEmpty(stringToFind)) {
            mAutoCompleteData.postValue(null);
            return;
        }

        final String normalizedURL = UrlStringUtils.toNormalizedQUERY(stringToFind);

        mAutoCompleteFuture = mAutoCompleteExecutor.submit(() -> {
            try {
                WebHistoryEntity result = mWebHistoryDataRepository.searchHistory(
                        normalizedURL, stringToFind + "%");

                if (gen != mAutoCompleteGen.get()) return;

                if (result == null) {
                    mAutoCompleteData.postValue(null);
                    return;
                }

                String suggestion = WebUtils.getUriNoScheme(result.getUrl());
                if (!TextUtils.isEmpty(suggestion)) {
                    int index = suggestion.indexOf(stringToFind);
                    if (index >= 0) {
                        suggestion = suggestion.substring(index);
                    }
                    mAutoCompleteData.postValue(suggestion);
                } else {
                    mAutoCompleteData.postValue(null);
                }
            } catch (Exception e) {
                if (!Thread.currentThread().isInterrupted() && gen == mAutoCompleteGen.get()) {
                    Log.e(TAG, "autoComplete failed:", e);
                    mAutoCompleteData.postValue(null);
                }
            }
        });
    }

    /**
     * Fetches search suggestions from the web search engine.
     *
     * <p>Called from the URL-bar TextWatcher (main thread) on every keystroke.
     * Debounced: the actual lookup is scheduled after a short quiet window and
     * rescheduled on each new keystroke, so fast typing fires one query, not one
     * per character. Clearing the field is handled immediately (no debounce).
     */
    public void search(CharSequence constraint) {
        // Leaving the most-visited (empty) state — these are typed results.
        mShowingMostVisited = false;
        final String searchTerm = constraint != null
                ? StringUtils.stripEnd(constraint.toString(), " ")
                : null;

        if (TextUtils.isEmpty(searchTerm)) {
            resetEngines();
            return;
        }

        if (searchTerm.equals(mCurrentSearchTerm)) return;

        cancelPendingSearch();
        mPendingSearch = () -> runSearch(searchTerm);
        mDebounceHandler.postDelayed(mPendingSearch, SEARCH_DEBOUNCE_MS);
    }

    /**
     * The debounced body of {@link #search(CharSequence)} — runs on the main
     * thread after the quiet window, then submits the blocking lookup to the
     * background executor.
     *
     * <p>The local-first phase (posting the on-device history/tabs/bookmarks
     * before the network suggestions arrive) is emitted ONLY when the dropdown is
     * currently EMPTY — i.e. its first paint, where showing instant on-device
     * results matters. While results are already showing (steady-state typing),
     * a local-only phase would briefly DROP the network suggestions and re-add
     * them on the second post, which reads as a flicker on every keystroke. There
     * we post just ONCE — the merged list — for a single smooth update. The
     * generation counter discards posts of a search that has since been
     * superseded.
     */
    private void runSearch(String searchTerm) {
        mPendingSearch = null;
        cancelFuture(mSearchFuture);
        mCurrentSearchTerm = searchTerm;
        final long gen = mSearchGen.incrementAndGet();

        List<AutoCompleteEntity> shown = mSearchData.getValue();
        final boolean emitLocalFirst = (shown == null || shown.isEmpty());

        mSearchFuture = mAutoCompleteExecutor.submit(() -> {
            try {
                Consumer<List<AutoCompleteEntity>> localEmitter = emitLocalFirst
                        ? local -> {
                            if (gen == mSearchGen.get()) {
                                mSearchData.postValue(local);
                            }
                        }
                        : null;
                List<AutoCompleteEntity> full = mAutoCompleteSearch.searchSync(searchTerm, localEmitter);
                if (gen == mSearchGen.get()) {
                    mSearchData.postValue(full);
                }
            } catch (Exception e) {
                if (!Thread.currentThread().isInterrupted()) {
                    Log.e(TAG, "Search suggestions failed:", e);
                }
            }
        });
    }

    private void cancelPendingSearch() {
        if (mPendingSearch != null) {
            mDebounceHandler.removeCallbacks(mPendingSearch);
            mPendingSearch = null;
        }
    }

    public void clearClipboard() {
        if (mClipboardManager == null) return;
        if (BuildUtils.hasAndroidP()) {
            mClipboardManager.clearPrimaryClip();
        } else {
            ClipData clipData = ClipData.newPlainText(Preferences.CLIPBOARD_LABEL, "");
            mClipboardManager.setPrimaryClip(clipData);
        }
    }

    public void resetEngines() {
        // Drop any debounced search still waiting to fire — without this a pending
        // runnable would run AFTER the reset and re-populate the just-cleared list
        // (it sets its own fresh generation, so the gen bump below can't stop it).
        cancelPendingSearch();
        mCurrentSearchTerm = null;
        mShowingMostVisited = false;
        mSearchGen.incrementAndGet();
        mSearchData.postValue(null);
    }

    @Override
    protected void onCleared() {
        super.onCleared();
        cancelPendingSearch();
        cancelFuture(mAutoCompleteFuture);
        cancelFuture(mSearchFuture);
    }

    private static void cancelFuture(Future<?> future) {
        if (future != null && !future.isDone()) {
            future.cancel(true);
        }
    }


    public void setIncognito(boolean incognito) {
        mAutoCompleteSearch.setIncognito(incognito);
    }
}