package com.solarized.firedown.autocomplete;

import android.content.ClipData;
import android.content.ClipboardManager;
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

import javax.inject.Inject;

import dagger.hilt.android.lifecycle.HiltViewModel;

@HiltViewModel
public class AutoCompleteViewModel extends ViewModel {

    private static final String TAG = AutoCompleteViewModel.class.getName();

    private final MutableLiveData<String> mAutoCompleteData = new MutableLiveData<>();
    private final MutableLiveData<List<AutoCompleteEntity>> mSearchData = new MutableLiveData<>();

    private final WebHistoryDataRepository mWebHistoryDataRepository;
    private final AutoCompleteSearch mAutoCompleteSearch;
    private final ClipboardManager mClipboardManager;
    private final ExecutorService mAutoCompleteExecutor;
    private Future<?> mAutoCompleteFuture;
    private Future<?> mSearchFuture;
    private String mCurrentSearchTerm;

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

    public LiveData<String> getAutoComplete() {
        return mAutoCompleteData;
    }

    /**
     * Finds a history-based URL suggestion for the address bar.
     */
    public void autoComplete(final String stringToFind) {
        cancelFuture(mAutoCompleteFuture);

        if (TextUtils.isEmpty(stringToFind)) {
            mAutoCompleteData.postValue(null);
            return;
        }

        final String normalizedURL = UrlStringUtils.toNormalizedQUERY(stringToFind);

        mAutoCompleteFuture = mAutoCompleteExecutor.submit(() -> {
            try {
                WebHistoryEntity result = mWebHistoryDataRepository.searchHistory(
                        normalizedURL, stringToFind + "%");

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
                if (!Thread.currentThread().isInterrupted()) {
                    Log.e(TAG, "autoComplete failed:", e);
                    mAutoCompleteData.postValue(null);
                }
            }
        });
    }

    /**
     * Fetches search suggestions from the web search engine.
     */
    public void search(CharSequence constraint) {
        final String searchTerm = constraint != null
                ? StringUtils.stripEnd(constraint.toString(), " ")
                : null;

        if (TextUtils.isEmpty(searchTerm)) {
            resetEngines();
            return;
        }

        if (searchTerm.equals(mCurrentSearchTerm)) return;

        cancelFuture(mSearchFuture);
        mCurrentSearchTerm = searchTerm;

        mSearchFuture = mAutoCompleteExecutor.submit(() -> {
            try {
                List<AutoCompleteEntity> result = mAutoCompleteSearch.searchSync(searchTerm);
                mSearchData.postValue(result);
            } catch (Exception e) {
                if (!Thread.currentThread().isInterrupted()) {
                    Log.e(TAG, "Search suggestions failed:", e);
                }
            }
        });
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
        mCurrentSearchTerm = null;
        mSearchData.postValue(null);
    }

    @Override
    protected void onCleared() {
        super.onCleared();
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