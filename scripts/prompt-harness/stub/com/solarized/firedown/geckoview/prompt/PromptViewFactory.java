package com.solarized.firedown.geckoview.prompt;

import android.app.Activity;
import androidx.appcompat.app.AlertDialog;
import com.solarized.firedown.geckoview.GeckoState;
import org.mozilla.geckoview.GeckoSession;
import org.mozilla.geckoview.GeckoSession.PromptDelegate;
import java.util.ArrayList;
import java.util.List;

/**
 * Collaborator stub. The REAL factory builds Android UI (Material dialogs,
 * pickers) that cannot exist on a plain JVM; what the harness needs from it
 * is its CONTRACT, mirrored here and nowhere else:
 *   - a created dialog answers its prompt on dismissal when nothing else has
 *     (setupDismissListener: promptDisplaying=false, clear the deactivate
 *     hook, dismiss() the prompt if incomplete);
 *   - a button answer goes through respondOnce (isComplete-gated).
 * The logic under test — gates, flood ring, completion — is the REAL
 * GeckoPromptManager + PromptFloodGate.
 */
public class PromptViewFactory {

    public interface PromptResponseHandler {
        void onResponse(GeckoSession session, PromptDelegate.PromptResponse response);
    }
    public interface PermissionResponseHandler {
        void onPermission(GeckoSession session, int value);
    }

    /** One created dialog with everything the test needs to drive it. */
    public static final class Created {
        public final AlertDialog dialog; public final GeckoState state;
        public final PromptDelegate.BasePrompt prompt; public final PromptResponseHandler handler;
        Created(AlertDialog d, GeckoState s, PromptDelegate.BasePrompt p, PromptResponseHandler h) {
            dialog = d; state = s; prompt = p; handler = h;
        }
        /** User taps OK — the respondOnce mirror. */
        public void answer() {
            if (!prompt.isComplete()) handler.onResponse(null, prompt.dismiss());
            dialog.dismiss();
        }
    }
    public static final List<Created> created = new ArrayList<>();
    public static void reset() { created.clear(); }

    private static AlertDialog make(GeckoState state, PromptDelegate.BasePrompt prompt, PromptResponseHandler handler) {
        AlertDialog d = new AlertDialog();
        d.setOnDismissListener(x -> {
            state.setPromptDisplaying(false);
            state.setOnDeactivateAction(null);
            if (!prompt.isComplete()) handler.onResponse(null, prompt.dismiss());
        });
        created.add(new Created(d, state, prompt, handler));
        return d;
    }

    public static AlertDialog createAlertDialog(Activity a, GeckoState s, PromptDelegate.AlertPrompt p, PromptResponseHandler h) { return make(s, p, h); }
    public static AlertDialog createBeforeUnloadDialog(Activity a, GeckoState s, PromptDelegate.BeforeUnloadPrompt p, PromptResponseHandler h) { return make(s, p, h); }
    public static AlertDialog createColorDialog(Activity a, GeckoState s, PromptDelegate.ColorPrompt p, PromptResponseHandler h) { return make(s, p, h); }
    public static AlertDialog createTextDialog(Activity a, GeckoState s, PromptDelegate.TextPrompt p, PromptResponseHandler h) { return make(s, p, h); }
    public static AlertDialog createButtonDialog(Activity a, GeckoState s, PromptDelegate.ButtonPrompt p, PromptResponseHandler h) { return make(s, p, h); }
    public static AlertDialog createRepostDialog(Activity a, GeckoState s, PromptDelegate.RepostConfirmPrompt p, PromptResponseHandler h) { return make(s, p, h); }
    public static AlertDialog createDateTimeDialog(Activity a, GeckoState s, PromptDelegate.DateTimePrompt p, PromptResponseHandler h) { return make(s, p, h); }
    public static AlertDialog createChoiceDialog(Activity a, GeckoState s, PromptDelegate.ChoicePrompt p, PromptResponseHandler h) { return make(s, p, h); }
    public static AlertDialog createAuthDialog(Activity a, GeckoState s, PromptDelegate.AuthPrompt p, PromptResponseHandler h) { return make(s, p, h); }
    public static AlertDialog createContentPermissionDialog(Activity a, GeckoState s, Object permission, String message, PermissionResponseHandler h) {
        AlertDialog d = new AlertDialog();
        d.setOnDismissListener(x -> {
            s.setPromptDisplaying(false);
            s.setOnDeactivateAction(null);
            h.onPermission(null, GeckoSession.PermissionDelegate.ContentPermission.VALUE_DENY);
        });
        created.add(new Created(d, s, null, null));
        return d;
    }
}
