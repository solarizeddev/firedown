package org.mozilla.geckoview;
/** Prompt model mirroring GeckoView's contract: confirm()/dismiss() answer a
 *  prompt ONCE; a second call returns null (the already-answered case the
 *  null-guard in completeResponse exists for). */
public class GeckoSession {
    public interface PromptDelegate {
        class PromptResponse { public final BasePrompt prompt; public PromptResponse(BasePrompt p){ prompt = p; } }
        class BasePrompt {
            private boolean complete;
            public boolean isComplete() { return complete; }
            public PromptResponse dismiss() {
                if (complete) return null;
                complete = true;
                return new PromptResponse(this);
            }
        }
        class AlertPrompt extends BasePrompt {}
        class ButtonPrompt extends BasePrompt {}
        class TextPrompt extends BasePrompt {}
        class ChoicePrompt extends BasePrompt {}
        class ColorPrompt extends BasePrompt {}
        class DateTimePrompt extends BasePrompt {}
        class AuthPrompt extends BasePrompt {}
        class BeforeUnloadPrompt extends BasePrompt {}
        class RepostConfirmPrompt extends BasePrompt {}
    }
    public interface PermissionDelegate {
        class ContentPermission {
            public static final int VALUE_PROMPT = 3, VALUE_ALLOW = 1, VALUE_DENY = 2;
        }
    }
}
