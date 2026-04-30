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


    public void onPermission(@NonNull GeckoSession session, int permissionValue) {
        GeckoComponents.PermissionDelegate permissionDelegate =
                (GeckoComponents.PermissionDelegate) session.getPermissionDelegate();
        if (permissionDelegate != null) {
            permissionDelegate.onPermissionCallbackResult(permissionValue);
        }
    }


    // A centralized response handler to avoid repeating the "session to delegate" casting
    public void sendResponse(GeckoSession session, GeckoSession.PromptDelegate.PromptResponse response) {
        GeckoComponents.PromptDelegate delegate = (GeckoComponents.PromptDelegate) session.getPromptDelegate();
        if (delegate != null) {
            delegate.onPromptCallbackResult(response);
        }
    }

    private boolean canShowPrompt(GeckoState state, NavController controller) {
        if (controller.getCurrentDestination() == null) return false;
        return !state.isPromptDisplaying()
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

    public void onAlertPrompt(Activity activity, GeckoState state, NavController nav, GeckoSession.PromptDelegate.AlertPrompt prompt) {
        if (!canShowPrompt(state, nav)) {
            prompt.dismiss();
            return;
        }

        AlertDialog dialog = PromptViewFactory.createAlertDialog(activity, state, prompt, this::sendResponse);

        state.setPromptDisplaying(true);
        dialog.show();
    }

    public void onPromptUnload(Activity activity, GeckoState state, NavController nav, GeckoSession.PromptDelegate.BeforeUnloadPrompt prompt){
        if (!canShowPrompt(state, nav)) {
            prompt.dismiss();
            return;
        }

        AlertDialog dialog = PromptViewFactory.createBeforeUnloadDialog(activity, state, prompt, this::sendResponse);

        state.setPromptDisplaying(true);
        dialog.show();
    }


    public void onColorPrompt(Activity activity, GeckoState state, NavController nav, GeckoSession.PromptDelegate.ColorPrompt prompt){
        if (!canShowPrompt(state, nav)) {
            prompt.dismiss();
            return;
        }

        AlertDialog dialog = PromptViewFactory.createColorDialog(activity, state, prompt, this::sendResponse);

        state.setPromptDisplaying(true);
        dialog.show();
    }

    public void onTextPrompt(Activity activity, GeckoState state, NavController nav, GeckoSession.PromptDelegate.TextPrompt prompt) {
        if (!canShowPrompt(state, nav)) {
            prompt.dismiss();
            return;
        }

        AlertDialog dialog = PromptViewFactory.createTextDialog(activity, state, prompt, this::sendResponse);

        state.setPromptDisplaying(true);
        dialog.show();
    }


    public void onButtonPrompt(Activity activity, GeckoState state, NavController nav, GeckoSession.PromptDelegate.ButtonPrompt prompt) {
        if (!canShowPrompt(state, nav)) {
            prompt.dismiss();
            return;
        }

        AlertDialog dialog = PromptViewFactory.createButtonDialog(activity, state, prompt, this::sendResponse);

        state.setPromptDisplaying(true);
        dialog.show();
    }

    public void onRepostPrompt(Activity activity, GeckoState state, NavController nav, GeckoSession.PromptDelegate.RepostConfirmPrompt prompt) {
        if (!canShowPrompt(state, nav)) {
            prompt.dismiss();
            return;
        }

        AlertDialog dialog = PromptViewFactory.createRepostDialog(activity, state, prompt, this::sendResponse);

        state.setPromptDisplaying(true);
        dialog.show();
    }

    public void onContentPermission(Activity activity, GeckoState state, NavController nav, String message, GeckoSession.PermissionDelegate.ContentPermission permission) {
        if (!canShowPrompt(state, nav)) {
            return;
        }

        AlertDialog dialog = PromptViewFactory.createContentPermissionDialog(activity, state, permission, message, this::onPermission);

        state.setPromptDisplaying(true);
        dialog.show();
    }

    public void onDatePrompt(Activity activity, GeckoState state, NavController nav, GeckoSession.PromptDelegate.DateTimePrompt prompt) {
        if (!canShowPrompt(state, nav)) {
            prompt.dismiss();
            return;
        }

        // We reuse the createDateTimeDialog since it handles DATE, TIME, and DATETIME types
        AlertDialog dialog = PromptViewFactory.createDateTimeDialog(activity, state, prompt, this::sendResponse);

        state.setPromptDisplaying(true);
        dialog.show();
    }

    public void onChoicePrompt(Activity activity, GeckoState state, NavController nav, GeckoSession.PromptDelegate.ChoicePrompt prompt) {
        if (!canShowPrompt(state, nav)) {
            prompt.dismiss();
            return;
        }

        // Delegate UI creation to a specialized builder
        AlertDialog dialog = PromptViewFactory.createChoiceDialog(activity, state, prompt, this::sendResponse);

        state.setPromptDisplaying(true);
        dialog.show();
    }

    public void onAuthPrompt(Activity activity, GeckoState state, NavController nav, GeckoSession.PromptDelegate.AuthPrompt prompt) {
        if (!canShowPrompt(state, nav)) {
            prompt.dismiss();
            return;
        }

        AlertDialog dialog = PromptViewFactory.createAuthDialog(activity, state, prompt, this::sendResponse);

        state.setPromptDisplaying(true);
        dialog.show();
    }
}