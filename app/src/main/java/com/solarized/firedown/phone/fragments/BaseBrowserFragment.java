package com.solarized.firedown.phone.fragments;


import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.view.KeyEvent;
import android.view.PointerIcon;
import android.view.View;

import androidx.interpolator.view.animation.FastOutSlowInInterpolator;
import androidx.lifecycle.ViewModelProvider;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;

import com.google.android.material.floatingactionbutton.FloatingActionButton;
import com.solarized.firedown.Preferences;
import com.solarized.firedown.autocomplete.AutoCompleteViewModel;
import com.solarized.firedown.data.models.GeckoStateViewModel;
import com.solarized.firedown.data.models.WebHistoryViewModel;
import com.solarized.firedown.data.repository.SearchRepository;
import com.solarized.firedown.geckoview.GeckoComponents;
import com.solarized.firedown.geckoview.GeckoObserver;
import com.solarized.firedown.geckoview.GeckoObserverRegistry;
import com.solarized.firedown.geckoview.GeckoRuntimeHelper;
import com.solarized.firedown.geckoview.GeckoState;
import com.solarized.firedown.geckoview.GeckoToolbar;
import com.solarized.firedown.geckoview.media.GeckoMediaController;
import com.solarized.firedown.geckoview.prompt.GeckoPromptManager;
import com.solarized.firedown.geckoview.toolbar.BottomNavigationBar;
import com.solarized.firedown.ui.adapters.SearchAutocompleteAdapter;
import com.solarized.firedown.autocomplete.AutoCompleteEditText;
import com.solarized.firedown.autocomplete.AutoCompleteView;
import com.solarized.firedown.utils.BrowserContextActions;

import org.mozilla.geckoview.GeckoSession;
import org.mozilla.geckoview.MediaSession;
import org.mozilla.geckoview.StorageController;
import org.mozilla.geckoview.WebResponse;

import javax.inject.Inject;

import dagger.hilt.android.AndroidEntryPoint;


@AndroidEntryPoint
public class BaseBrowserFragment extends BaseFocusFragment implements AutoCompleteEditText.OnSearchStateChangeListener,
        AutoCompleteEditText.OnFilterListener, AutoCompleteEditText.OnCommitListener, AutoCompleteEditText.OnTextChangedListener,
        AutoCompleteEditText.OnWindowsFocusChangeListener, AutoCompleteEditText.OnFocusChangedListener,
        GeckoToolbar.OnToolbarListener, GeckoToolbar.OnClearFocusListener, BottomNavigationBar.OnBottomBarListener,
        SwipeRefreshLayout.OnRefreshListener, GeckoObserver {


    private static final String TAG = BaseBrowserFragment.class.getName();

    protected AutoCompleteView mAutoCompleteView;

    protected SearchAutocompleteAdapter mSearchAutocompleteAdapter;

    protected AutoCompleteViewModel mAutoCompleteViewModel;

    protected FloatingActionButton mDownloadButton;

    @Inject
    protected GeckoObserverRegistry mGeckoObserverRegistry;

    @Inject
    protected SearchRepository mSearchRepository;

    @Inject
    protected SharedPreferences mSharedPreferences;

    @Inject
    protected GeckoRuntimeHelper mGeckoRuntimeHelper;

    @Inject
    protected GeckoComponents mGeckoComponents;
    @Inject
    protected BrowserContextActions mContextActions;
    @Inject
    protected GeckoPromptManager mGeckoPromptManager;
    @Inject
    protected GeckoMediaController mGeckoMediaController;

    protected GeckoStateViewModel mGeckoStateViewModel;

    protected WebHistoryViewModel mWebHistoryViewModel;



    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        mWebHistoryViewModel = new ViewModelProvider(this).get(WebHistoryViewModel.class);
        mAutoCompleteViewModel = new ViewModelProvider(this).get(AutoCompleteViewModel.class);
        mGeckoStateViewModel = new ViewModelProvider(mActivity).get(GeckoStateViewModel.class);
    }



    @Override
    public void onCommit() {

    }

    @Override
    public void onTextChanged(String afterText, String currentText) {

    }

    @Override
    public void onWindowsFocusChanged(boolean hasFocus) {

    }

    @Override
    public void onRefreshAutoComplete(String text) {

    }

    @Override
    public void onSearchStateChanged(boolean isActive) {

    }

    @Override
    public void onFocusChanged(boolean hasFocus) {

    }

    @Override
    public void onRefresh() {

    }

    @Override
    public void updateProgress(int progress) {

    }

    @Override
    public void onLocationChange(GeckoState geckoState) {

    }


    @Override
    public void onFullScreen(boolean fullScreen) {

    }

    @Override
    public void onShowDynamicToolbar() {

    }

    @Override
    public void onMetaViewportFitChange(String viewPortFit) {

    }

    @Override
    public void onKill(GeckoState geckoState) {

    }

    @Override
    public void onNew(GeckoState geckoState, String uri) {

    }

    @Override
    public void onClose(GeckoState geckoState) {

    }

    @Override
    public void onDownload(WebResponse response) {

    }

    @Override
    public void onThumbnail(GeckoState geckoState) {

    }

    @Override
    public void onLoadRequest(GeckoState geckoState, String uri) {

    }

    @Override
    public void onScrollChange(int scrollY) {

    }

    @Override
    public void onContext(GeckoState geckoState, GeckoSession.ContentDelegate.ContextElement element) {

    }

    @Override
    public void onPromptFile(GeckoState geckoState, GeckoSession.PromptDelegate.FilePrompt filePrompt, Intent intent) {

    }

    @Override
    public void onPromptChoice(GeckoState geckoState, GeckoSession.PromptDelegate.ChoicePrompt prompt) {

    }

    @Override
    public void onPromptAlert(GeckoState geckoState, GeckoSession.PromptDelegate.AlertPrompt prompt) {

    }

    @Override
    public void onPromptButton(GeckoState geckoState, GeckoSession.PromptDelegate.ButtonPrompt prompt) {

    }

    @Override
    public void onPromptText(GeckoState geckoState, GeckoSession.PromptDelegate.TextPrompt prompt) {

    }

    @Override
    public void onPromptRepost(GeckoState geckoState, GeckoSession.PromptDelegate.RepostConfirmPrompt prompt) {

    }

    @Override
    public void onPromptAuth(GeckoState geckoState, GeckoSession.PromptDelegate.AuthPrompt prompt) {

    }

    @Override
    public void onPromptColor(GeckoState geckoState, GeckoSession.PromptDelegate.ColorPrompt prompt) {

    }

    @Override
    public void onPromptUnload(GeckoState geckoState, GeckoSession.PromptDelegate.BeforeUnloadPrompt prompt) {

    }

    @Override
    public void onPromptDate(GeckoState geckoState, GeckoSession.PromptDelegate.DateTimePrompt prompt) {

    }

    @Override
    public void onContentPermission(GeckoState geckoState, GeckoSession.PermissionDelegate.ContentPermission permission, int resId) {

    }

    @Override
    public void onPromptLoginSave(GeckoState geckoState, GeckoSession.PromptDelegate.AutocompleteRequest<?> request, boolean contains) {

    }

    @Override
    public void onMediaPause(GeckoState geckoState, MediaSession mediaSession) {

    }

    @Override
    public void onMediaPlay(GeckoState geckoState, MediaSession mediaSession) {

    }

    @Override
    public void onMediaActivated(GeckoState geckoState, MediaSession mediaSession) {

    }

    @Override
    public void onMediaDeactivated(GeckoState geckoState, MediaSession mediaSession) {

    }

    @Override
    public void onMediaStop(GeckoState geckoState, MediaSession mediaSession) {

    }

    @Override
    public void onMediaMetadata(GeckoState geckoState, MediaSession mediaSession, MediaSession.Metadata metadata) {

    }

    @Override
    public void onMediaPosition(GeckoState geckoState, MediaSession mediaSession, MediaSession.PositionState positionState) {

    }

    @Override
    public void onCrash(GeckoState geckoState) {

    }

    @Override
    public void onOrientation(Integer screenOrientation) {

    }

    @Override
    public void onHideBars(GeckoState geckoState) {

    }

    @Override
    public void onStart(GeckoState geckoState) {

    }

    @Override
    public void onStop(GeckoState geckoState) {

    }


    @Override
    public void onFirstComposite(GeckoState geckoState) {

    }

    @Override
    public void onPointerIconChange(GeckoState geckoState, PointerIcon icon) {

    }

    @Override
    public void onSecurityChange(GeckoState geckoState, GeckoSession.ProgressDelegate.SecurityInformation securityInfo) {

    }

    @Override
    public void onToolbarButtonClick(View v, int id) {

    }

    @Override
    public void onToolbarKey(int keyCode, KeyEvent event) {

    }


    @Override
    public void onBottomBarButtonClick(View v, int id) {

    }

    @Override
    public boolean onBottomBarButtonLongClick(View v, int id) {
        return false;
    }

    @Override
    public void onToolbarClearFocus() {

    }


    protected void flashNewTab(View flashView) {
        if (flashView == null) return;
        flashView.setAlpha(0f);
        flashView.setScaleY(0.95f);
        flashView.setVisibility(View.VISIBLE);
        flashView.animate()
                .alpha(0.8f)
                .scaleY(1f)
                .setDuration(120)
                .setInterpolator(new FastOutSlowInInterpolator())
                .withEndAction(() ->
                        flashView.animate()
                                .alpha(0f)
                                .setDuration(250)
                                .withEndAction(() -> flashView.setVisibility(View.GONE))
                                .start())
                .start();
    }


    protected void quitApp(){
        if (mSharedPreferences.getBoolean(Preferences.SETTINGS_QUIT_PREF_HISTORY, false)) {
            mWebHistoryViewModel.deleteAll();
        }
        if (mSharedPreferences.getBoolean(Preferences.SETTINGS_QUIT_PREF_TABS, false)) {
            mGeckoStateViewModel.deleteAll();
        }
        if (mSharedPreferences.getBoolean(Preferences.SETTINGS_QUIT_PREF_COOKIES, false)) {
            mGeckoRuntimeHelper.getGeckoRuntime().getStorageController().clearData(StorageController.ClearFlags.COOKIES);
        }
        if (mSharedPreferences.getBoolean(Preferences.SETTINGS_QUIT_PREF_CACHE, false)) {
            mGeckoRuntimeHelper.getGeckoRuntime().getStorageController().clearData(StorageController.ClearFlags.IMAGE_CACHE);
        }
        mActivity.finishAndRemoveTask();
    }
}
