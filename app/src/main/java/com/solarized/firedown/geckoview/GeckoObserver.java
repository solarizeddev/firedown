package com.solarized.firedown.geckoview;

import android.content.Intent;
import android.view.PointerIcon;


import org.mozilla.geckoview.GeckoSession;
import org.mozilla.geckoview.MediaSession;
import org.mozilla.geckoview.WebResponse;


public interface GeckoObserver {

    void updateProgress(int progress);

    /**
     * @param url the location the engine just committed — display code must use
     *            THIS value, never re-read {@code geckoState.getEntityUri()}
     *            (mutable shared state another callback may have rewritten by
     *            the time the observer runs; re-reading it is how a stale
     *            commit used to revert the toolbar after a user typed a new URL
     *            mid-load).
     */
    void onLocationChange(GeckoState geckoState, String url);

    void onFullScreen(boolean fullScreen);

    void onShowDynamicToolbar();

    void onMetaViewportFitChange(String viewPortFit);

    void onKill(GeckoState geckoState);

    void onNew(GeckoState geckoState, String uri);

    void onClose(GeckoState geckoState);

    void onDownload(WebResponse response);

    void onThumbnail(GeckoState geckoState);

    // autoRedirect: the navigation was NOT a direct user action (typed URL /
    // bookmark) — i.e. an unsolicited page/script-initiated app deeplink, the
    // signal the "block app redirects" toggle acts on. wasRedirector: the page
    // bounced here just after loading and has back-history, so the handler can
    // goBack() before/instead of prompting (same heuristic as the Play Store path).
    void onLoadRequest(GeckoState geckoState, String uri, boolean autoRedirect, boolean wasRedirector);

    /**
     * Fired when {@link NavigationDelegate#onLoadRequest} catches a
     * navigation towards a Play Store listing (play.google.com or
     * market://). The delegate has already denied the navigation by
     * the time this fires — the observer surfaces the block (Snackbar
     * with a one-shot "Open") when the block-app-redirects pref is on,
     * or just loads the listing in-browser when it's off.
     *
     * @param uri             the URL the site tried to redirect to
     * @param wasRedirector   true when the current loaded page looks
     *                        like a transient redirector — it fired
     *                        the navigation within a few seconds of
     *                        landing and there's a previous entry in
     *                        history to fall back to. The observer
     *                        should goBack() before surfacing the block
     *                        so the user ends up on the source page
     *                        instead of stranded on the redirector
     */
    void onPlayStoreRedirect(GeckoState geckoState, String uri, boolean wasRedirector);

    void onScrollChange(int scrollY);

    void onContext(GeckoState geckoState, GeckoSession.ContentDelegate.ContextElement element);

    void onOrientation(Integer screenOrientation);

    void onHideBars(GeckoState geckoState);

    void onStart(GeckoState geckoState);

    void onStop(GeckoState geckoState);

    void onFirstComposite(GeckoState geckoState);

    void onPointerIconChange(GeckoState geckoState, PointerIcon icon);

    void onSecurityChange(GeckoState geckoState, GeckoSession.ProgressDelegate.SecurityInformation securityInfo);

    void onPromptFile(GeckoState geckoState, GeckoSession.PromptDelegate.FilePrompt filePrompt, Intent intent);

    void onPromptChoice(GeckoState geckoState, GeckoSession.PromptDelegate.ChoicePrompt prompt);

    void onPromptAlert(GeckoState geckoState, GeckoSession.PromptDelegate.AlertPrompt prompt);

    void onPromptButton(GeckoState geckoState, GeckoSession.PromptDelegate.ButtonPrompt prompt);

    void onPromptText(GeckoState geckoState, GeckoSession.PromptDelegate.TextPrompt prompt);

    void onPromptRepost(GeckoState geckoState, GeckoSession.PromptDelegate.RepostConfirmPrompt prompt);

    void onPromptAuth(GeckoState geckoState, GeckoSession.PromptDelegate.AuthPrompt prompt);

    void onPromptColor(GeckoState geckoState, GeckoSession.PromptDelegate.ColorPrompt prompt);

    void onPromptUnload(GeckoState geckoState, GeckoSession.PromptDelegate.BeforeUnloadPrompt prompt);

    void onPromptDate(GeckoState geckoState, GeckoSession.PromptDelegate.DateTimePrompt prompt);

    void onContentPermission(GeckoState geckoState, GeckoSession.PermissionDelegate.ContentPermission permission, int resId);

    void onPromptLoginSave(GeckoState geckoState, GeckoSession.PromptDelegate.AutocompleteRequest<?> request, boolean contains);

    void onMediaPause(GeckoState geckoState, MediaSession mediaSession);

    void onMediaPlay(GeckoState geckoState, MediaSession mediaSession);

    void onMediaActivated(GeckoState geckoState, MediaSession mediaSession);

    void onMediaDeactivated(GeckoState geckoState, MediaSession mediaSession);

    void onMediaStop(GeckoState geckoState, MediaSession mediaSession);

    void onMediaMetadata(GeckoState geckoState, MediaSession mediaSession, MediaSession.Metadata metadata);

    void onMediaPosition(GeckoState geckoState, MediaSession mediaSession, MediaSession.PositionState positionState);

    void onCrash(GeckoState geckoState);
}
