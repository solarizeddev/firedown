package com.solarized.firedown.geckoview;

import android.content.Intent;
import android.view.PointerIcon;


import org.mozilla.geckoview.GeckoResult;
import org.mozilla.geckoview.GeckoSession;
import org.mozilla.geckoview.MediaSession;
import org.mozilla.geckoview.TranslationsController;
import org.mozilla.geckoview.WebResponse;


public interface GeckoObserver {

    /** @param geckoState the tab the progress belongs to — fragments must mode-filter it. */
    void updateProgress(GeckoState geckoState, int progress);

    /**
     * @param url the location the engine just committed — display code must use
     *            THIS value, never re-read {@code geckoState.getEntityUri()}
     *            (mutable shared state another callback may have rewritten by
     *            the time the observer runs; re-reading it is how a stale
     *            commit used to revert the toolbar after a user typed a new URL
     *            mid-load).
     */
    void onLocationChange(GeckoState geckoState, String url);

    /** @param geckoState the tab whose DOM-fullscreen state changed — fragments must mode-filter it. */
    void onFullScreen(GeckoState geckoState, boolean fullScreen);

    void onShowDynamicToolbar();

    void onMetaViewportFitChange(String viewPortFit);

    void onKill(GeckoState geckoState);

    void onNew(GeckoState geckoState, String uri);

    void onClose(GeckoState geckoState);

    void onDownload(WebResponse response);

    void onThumbnail(GeckoState geckoState);

    /**
     * The NavigationDelegate denied a link that would leave the browser for
     * an app: a non-web scheme ({@code intent://}, {@code market://},
     * {@code tiktok://}, {@code mailto:}…) or a Play Store listing the page
     * navigated to. The observer decides what to do with it
     * ({@link com.solarized.firedown.applinks.AppLinkPolicy}).
     *
     * @param pageFired     no tap and not typed: the page fired it by itself
     *                      (GeckoView's {@code hasUserGesture} false). The
     *                      "Block app redirects" setting acts on these only.
     * @param typed         the app's own load (typed, a bookmark, a context
     *                      menu choice), not a link on the page
     * @param wasRedirector the page fired it within a few seconds of loading
     *                      and has back history, so a block can goBack() off
     *                      a tracker page instead of stranding the user there
     */
    void onLoadRequest(GeckoState geckoState, String uri, boolean pageFired, boolean typed,
                       boolean wasRedirector);

    void onScrollChange(int scrollY);

    void onContext(GeckoState geckoState, GeckoSession.ContentDelegate.ContextElement element);

    void onOrientation(Integer screenOrientation);

    void onHideBars(GeckoState geckoState);

    void onStart(GeckoState geckoState);

    void onStop(GeckoState geckoState);

    void onFirstComposite(GeckoState geckoState);

    void onPointerIconChange(GeckoState geckoState, PointerIcon icon);

    void onSecurityChange(GeckoState geckoState, GeckoSession.ProgressDelegate.SecurityInformation securityInfo);

    void onPromptFile(GeckoState geckoState, GeckoSession.PromptDelegate.FilePrompt filePrompt, Intent intent, GeckoResult<GeckoSession.PromptDelegate.PromptResponse> res);

    void onPromptChoice(GeckoState geckoState, GeckoSession.PromptDelegate.ChoicePrompt prompt, GeckoResult<GeckoSession.PromptDelegate.PromptResponse> res);

    void onPromptAlert(GeckoState geckoState, GeckoSession.PromptDelegate.AlertPrompt prompt, GeckoResult<GeckoSession.PromptDelegate.PromptResponse> res);

    void onPromptButton(GeckoState geckoState, GeckoSession.PromptDelegate.ButtonPrompt prompt, GeckoResult<GeckoSession.PromptDelegate.PromptResponse> res);

    void onPromptText(GeckoState geckoState, GeckoSession.PromptDelegate.TextPrompt prompt, GeckoResult<GeckoSession.PromptDelegate.PromptResponse> res);

    void onPromptRepost(GeckoState geckoState, GeckoSession.PromptDelegate.RepostConfirmPrompt prompt, GeckoResult<GeckoSession.PromptDelegate.PromptResponse> res);

    void onPromptAuth(GeckoState geckoState, GeckoSession.PromptDelegate.AuthPrompt prompt, GeckoResult<GeckoSession.PromptDelegate.PromptResponse> res);

    void onPromptColor(GeckoState geckoState, GeckoSession.PromptDelegate.ColorPrompt prompt, GeckoResult<GeckoSession.PromptDelegate.PromptResponse> res);

    void onPromptUnload(GeckoState geckoState, GeckoSession.PromptDelegate.BeforeUnloadPrompt prompt, GeckoResult<GeckoSession.PromptDelegate.PromptResponse> res);

    void onPromptDate(GeckoState geckoState, GeckoSession.PromptDelegate.DateTimePrompt prompt, GeckoResult<GeckoSession.PromptDelegate.PromptResponse> res);

    void onContentPermission(GeckoState geckoState, GeckoSession.PermissionDelegate.ContentPermission permission, int resId, GeckoComponents.PermissionResult res);

    void onPromptLoginSave(GeckoState geckoState, GeckoSession.PromptDelegate.AutocompleteRequest<?> request, boolean contains);

    void onMediaPause(GeckoState geckoState, MediaSession mediaSession);

    void onMediaPlay(GeckoState geckoState, MediaSession mediaSession);

    void onMediaActivated(GeckoState geckoState, MediaSession mediaSession);

    void onMediaDeactivated(GeckoState geckoState, MediaSession mediaSession);

    void onMediaStop(GeckoState geckoState, MediaSession mediaSession);

    void onMediaMetadata(GeckoState geckoState, MediaSession mediaSession, MediaSession.Metadata metadata);

    void onMediaPosition(GeckoState geckoState, MediaSession mediaSession, MediaSession.PositionState positionState);

    void onCrash(GeckoState geckoState);

    /**
     * Gecko's built-in translator decided this (foreground) tab's page should
     * be OFFERED for translation — it's in a language the user doesn't read,
     * a model exists for it, and neither the site nor the language is marked
     * never-translate. Fired at most once per host per session by Gecko.
     */
    void onTranslationOffer(GeckoState geckoState);

    /**
     * New translation state for the (foreground) tab: detected languages on
     * first visit, then engine readiness / visible change / error after a
     * translate request. The same state is stored on the {@link GeckoState}
     * ungated, so a tab switched onto later can still read it.
     */
    void onTranslationStateChange(GeckoState geckoState,
                                  TranslationsController.SessionTranslation.TranslationState state);
}
