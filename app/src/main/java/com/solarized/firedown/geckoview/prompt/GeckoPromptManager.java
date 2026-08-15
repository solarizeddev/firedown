package com.solarized.firedown.geckoview.prompt;

import android.app.Activity;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.navigation.NavController;

import com.solarized.firedown.R;
import com.solarized.firedown.data.repository.GeckoStateDataRepository;
import com.solarized.firedown.data.repository.IncognitoStateRepository;
import com.solarized.firedown.geckoview.GeckoComponents;
import com.solarized.firedown.geckoview.GeckoState;

import org.mozilla.geckoview.GeckoResult;
import org.mozilla.geckoview.GeckoSession;

import javax.inject.Inject;
import javax.inject.Singleton;

@Singleton
public class GeckoPromptManager {

    private final GeckoStateDataRepository geckoStateRepository;
    private final IncognitoStateRepository incognitoStateRepository;

    @Inject
    public GeckoPromptManager(GeckoStateDataRepository repository,
                              IncognitoStateRepository incognitoRepository) {
        this.geckoStateRepository = repository;
        this.incognitoStateRepository = incognitoRepository;
    }


    /**
     * Completes a prompt's result — the ONE place a dialog's answer lands,
     * and the null-guard for the whole prompt pipeline.
     *
     * <p>{@code confirm()}/{@code dismiss()} return <b>null</b> when the
     * prompt was already answered (a double-tap landing before the dialog
     * tears down, or a prompt GeckoView invalidated because the page
     * navigated under the open dialog). Completing the result with that null
     * crashes GeckoView's PromptController on {@code null.dispatch()} — the
     * 1.1.88 crash — so a null is dropped: GeckoView has already handled
     * that prompt and is not waiting on the result.</p>
     */
    public static void completeResponse(GeckoResult<GeckoSession.PromptDelegate.PromptResponse> res,
                                        GeckoSession.PromptDelegate.PromptResponse response) {
        if (response == null) {
            return;
        }
        res.complete(response);
    }

    /**
     * Answers a prompt this manager declines to show a dialog for. Every
     * gate MUST end here rather than at a bare {@code prompt.dismiss()}:
     * dismiss() only BUILDS the response — Gecko is answered when it lands
     * in the result, and a gate that discards it leaves the page's JS
     * blocked in alert()/confirm() forever.
     */
    private static void dismissPrompt(GeckoResult<GeckoSession.PromptDelegate.PromptResponse> res,
                                      GeckoSession.PromptDelegate.BasePrompt prompt) {
        if (prompt.isComplete()) {
            return;
        }
        completeResponse(res, prompt.dismiss());
    }

    /**
     * The one door every dialog goes through: wires the tab-switch
     * dismissal hook, marks the state as showing a prompt (read by both the
     * re-entry gate and the delegate's post-notify sweep), feeds the flood
     * breaker, and shows.
     */
    private void showDialog(GeckoState state, AlertDialog dialog) {
        state.setOnDeactivateAction(dialog::dismiss);
        state.setPromptDisplaying(true);
        state.recordPromptShown();
        dialog.show();
    }

    private boolean canShowPrompt(GeckoState state, NavController controller) {
        if (controller.getCurrentDestination() == null) return false;
        // isPromptFlooding: a page nagging in a modal loop (3 dialogs inside
        // 10s) stops getting dialogs until it calms down — each declined
        // prompt is auto-dismissed at the caller's gate, so the page's JS
        // keeps running and the user keeps their browser.
        // The destination check also covers sheets and non-browser screens:
        // bottom sheets are <dialog> nav destinations, so while one is up
        // the current destination is not R.id.browser.
        return !state.isPromptDisplaying()
                && !state.isPromptFlooding()
                && controller.getCurrentDestination().getId() == R.id.browser
                && isCurrentGeckoState(state);
    }

    /**
     * Checks whether the given state is the current active state in its respective
     * repository (regular or incognito). Mirrors {@code GeckoComponents.isCurrentGeckoState}.
     */
    private boolean isCurrentGeckoState(GeckoState state) {
        if (state.getGeckoStateEntity().isIncognito()) {
            return incognitoStateRepository.isCurrentGeckoState(state);
        }
        return geckoStateRepository.isCurrentGeckoState(state);
    }

    public void onAlertPrompt(Activity activity, GeckoState state, NavController nav, GeckoSession.PromptDelegate.AlertPrompt prompt,
                          GeckoResult<GeckoSession.PromptDelegate.PromptResponse> res) {
        if (!canShowPrompt(state, nav)) {
            dismissPrompt(res, prompt);
            return;
        }

        AlertDialog dialog = PromptViewFactory.createAlertDialog(activity, state, prompt,
                (session, response) -> completeResponse(res, response));

        showDialog(state, dialog);
    }

    public void onPromptUnload(Activity activity, GeckoState state, NavController nav, GeckoSession.PromptDelegate.BeforeUnloadPrompt prompt,
                          GeckoResult<GeckoSession.PromptDelegate.PromptResponse> res) {
        if (!canShowPrompt(state, nav)) {
            dismissPrompt(res, prompt);
            return;
        }

        AlertDialog dialog = PromptViewFactory.createBeforeUnloadDialog(activity, state, prompt,
                (session, response) -> completeResponse(res, response));

        showDialog(state, dialog);
    }


    public void onColorPrompt(Activity activity, GeckoState state, NavController nav, GeckoSession.PromptDelegate.ColorPrompt prompt,
                          GeckoResult<GeckoSession.PromptDelegate.PromptResponse> res) {
        if (!canShowPrompt(state, nav)) {
            dismissPrompt(res, prompt);
            return;
        }

        AlertDialog dialog = PromptViewFactory.createColorDialog(activity, state, prompt,
                (session, response) -> completeResponse(res, response));

        showDialog(state, dialog);
    }

    public void onTextPrompt(Activity activity, GeckoState state, NavController nav, GeckoSession.PromptDelegate.TextPrompt prompt,
                          GeckoResult<GeckoSession.PromptDelegate.PromptResponse> res) {
        if (!canShowPrompt(state, nav)) {
            dismissPrompt(res, prompt);
            return;
        }

        AlertDialog dialog = PromptViewFactory.createTextDialog(activity, state, prompt,
                (session, response) -> completeResponse(res, response));

        showDialog(state, dialog);
    }


    public void onButtonPrompt(Activity activity, GeckoState state, NavController nav, GeckoSession.PromptDelegate.ButtonPrompt prompt,
                          GeckoResult<GeckoSession.PromptDelegate.PromptResponse> res) {
        if (!canShowPrompt(state, nav)) {
            dismissPrompt(res, prompt);
            return;
        }

        AlertDialog dialog = PromptViewFactory.createButtonDialog(activity, state, prompt,
                (session, response) -> completeResponse(res, response));

        showDialog(state, dialog);
    }

    public void onRepostPrompt(Activity activity, GeckoState state, NavController nav, GeckoSession.PromptDelegate.RepostConfirmPrompt prompt,
                          GeckoResult<GeckoSession.PromptDelegate.PromptResponse> res) {
        if (!canShowPrompt(state, nav)) {
            dismissPrompt(res, prompt);
            return;
        }

        AlertDialog dialog = PromptViewFactory.createRepostDialog(activity, state, prompt,
                (session, response) -> completeResponse(res, response));

        showDialog(state, dialog);
    }

    public void onContentPermission(Activity activity, GeckoState state, NavController nav, String message,
                                    GeckoSession.PermissionDelegate.ContentPermission permission,
                                    GeckoComponents.PermissionResult res) {
        if (!canShowPrompt(state, nav)) {
            // No decision — nothing is stored, so the site can re-ask when
            // the tab is actually in front of the user. The old bare return
            // left the permission GeckoResult uncompleted, hanging the
            // page's getUserMedia/geolocation call forever.
            res.complete(GeckoSession.PermissionDelegate.ContentPermission.VALUE_PROMPT);
            return;
        }

        // res.complete is one-shot internally — required, because the
        // permission dialog answers twice by construction (button click,
        // then the dismiss listener's no-choice DENY default).
        AlertDialog dialog = PromptViewFactory.createContentPermissionDialog(activity, state, permission, message,
                (session, value) -> res.complete(value));

        showDialog(state, dialog);
    }

    public void onDatePrompt(Activity activity, GeckoState state, NavController nav, GeckoSession.PromptDelegate.DateTimePrompt prompt,
                          GeckoResult<GeckoSession.PromptDelegate.PromptResponse> res) {
        if (!canShowPrompt(state, nav)) {
            dismissPrompt(res, prompt);
            return;
        }

        // We reuse the createDateTimeDialog since it handles DATE, TIME, and DATETIME types
        AlertDialog dialog = PromptViewFactory.createDateTimeDialog(activity, state, prompt,
                (session, response) -> completeResponse(res, response));

        showDialog(state, dialog);
    }

    public void onChoicePrompt(Activity activity, GeckoState state, NavController nav, GeckoSession.PromptDelegate.ChoicePrompt prompt,
                          GeckoResult<GeckoSession.PromptDelegate.PromptResponse> res) {
        if (!canShowPrompt(state, nav)) {
            dismissPrompt(res, prompt);
            return;
        }

        // Delegate UI creation to a specialized builder
        AlertDialog dialog = PromptViewFactory.createChoiceDialog(activity, state, prompt,
                (session, response) -> completeResponse(res, response));

        showDialog(state, dialog);
    }

    public void onAuthPrompt(Activity activity, GeckoState state, NavController nav, GeckoSession.PromptDelegate.AuthPrompt prompt,
                          GeckoResult<GeckoSession.PromptDelegate.PromptResponse> res) {
        if (!canShowPrompt(state, nav)) {
            dismissPrompt(res, prompt);
            return;
        }

        AlertDialog dialog = PromptViewFactory.createAuthDialog(activity, state, prompt,
                (session, response) -> completeResponse(res, response));

        showDialog(state, dialog);
    }
}