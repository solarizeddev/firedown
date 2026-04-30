package com.solarized.firedown.geckoview;

import android.content.Intent;
import android.view.PointerIcon;

import org.mozilla.geckoview.Autocomplete;
import org.mozilla.geckoview.GeckoResult;
import org.mozilla.geckoview.GeckoSession;
import org.mozilla.geckoview.MediaSession;
import org.mozilla.geckoview.WebResponse;

public interface GeckoObserverInvoker {

    void callMethod(GeckoObserver geckoObserver, Object... object);

    GeckoObserverInvoker PROGRESS = (geckoObserver, objects) -> geckoObserver.updateProgress((int) objects[0]);

    GeckoObserverInvoker LOCATION = (geckoObserver, objects) -> geckoObserver.onLocationChange((GeckoState) objects[0]);

    GeckoObserverInvoker FULL_SCREEN = (geckoObserver, objects) -> geckoObserver.onFullScreen((boolean) objects[0]);

    GeckoObserverInvoker DYNAMIC_TOOLBAR = (geckoObserver, objects) -> geckoObserver.onShowDynamicToolbar();

    GeckoObserverInvoker VIEWPORT_FIT_CHANGE = (geckoObserver, objects) -> geckoObserver.onMetaViewportFitChange((String) objects[0]);

    GeckoObserverInvoker KILL_SESSION = (geckoObserver, objects) -> geckoObserver.onKill((GeckoState) objects[0]);

    GeckoObserverInvoker NEW_SESSION = (geckoObserver, objects) -> geckoObserver.onNew((GeckoState) objects[0], (String) objects[1]);

    GeckoObserverInvoker CLOSE_SESSION = (geckoObserver, objects) -> geckoObserver.onClose((GeckoState) objects[0]);

    GeckoObserverInvoker LOAD_REQUEST = (geckoObserver, objects)-> geckoObserver.onLoadRequest((GeckoState) objects[0], (String) objects[1]);

    GeckoObserverInvoker DOWNLOAD = (geckoObserver, objects) -> geckoObserver.onDownload((WebResponse) objects[0]);

    GeckoObserverInvoker THUMBNAIL = (geckoObserver, objects) -> geckoObserver.onThumbnail((GeckoState) objects[0]);

    GeckoObserverInvoker SCROLL_Y = (geckoObserver, objects) -> geckoObserver.onScrollChange((int) objects[0]);

    GeckoObserverInvoker CONTEXT = (geckoObserver, objects) -> geckoObserver.onContext((GeckoState) objects[0], (GeckoSession.ContentDelegate.ContextElement) objects[1]);

    GeckoObserverInvoker HIDE_BARS = (geckoObserver, objects) -> geckoObserver.onHideBars((GeckoState) objects[0]);

    GeckoObserverInvoker ORIENTATION = (geckoObserver, objects) -> geckoObserver.onOrientation((Integer) objects[0]);

    GeckoObserverInvoker START = ((geckoObserver, objects) -> geckoObserver.onStart((GeckoState) objects[0]));

    GeckoObserverInvoker STOP = ((geckoObserver, objects) -> geckoObserver.onStop((GeckoState) objects[0]));

    GeckoObserverInvoker FIRST_COMPOSITE = ((geckoObserver, objects) -> geckoObserver.onFirstComposite((GeckoState) objects[0]));

    GeckoObserverInvoker POINTER_ICON = ((geckoObserver, objects) -> geckoObserver.onPointerIconChange((GeckoState) objects[0], (PointerIcon) objects[1]));

    GeckoObserverInvoker SECURITY = ((geckoObserver, objects) -> geckoObserver.onSecurityChange((GeckoState) objects[0],(GeckoSession.ProgressDelegate.SecurityInformation) objects[1]));

    GeckoObserverInvoker PROMPT_FILE = (geckoObserver, objects) -> geckoObserver.onPromptFile((GeckoState) objects[0], (GeckoSession.PromptDelegate.FilePrompt) objects[1], (Intent) objects[2]);

    GeckoObserverInvoker PROMPT_CHOICE = (geckoObserver, objects) -> geckoObserver.onPromptChoice((GeckoState) objects[0], (GeckoSession.PromptDelegate.ChoicePrompt) objects[1]);

    GeckoObserverInvoker PROMPT_ALERT = (geckoObserver, objects) -> geckoObserver.onPromptAlert((GeckoState) objects[0], (GeckoSession.PromptDelegate.AlertPrompt) objects[1]);

    GeckoObserverInvoker PROMPT_BUTTON = (geckoObserver, objects) -> geckoObserver.onPromptButton((GeckoState) objects[0], (GeckoSession.PromptDelegate.ButtonPrompt) objects[1]);

    GeckoObserverInvoker PROMPT_TEXT = (geckoObserver, objects) -> geckoObserver.onPromptText((GeckoState) objects[0], (GeckoSession.PromptDelegate.TextPrompt) objects[1]);

    GeckoObserverInvoker PROMPT_REPOST = (geckoObserver, objects) -> geckoObserver.onPromptRepost((GeckoState) objects[0], (GeckoSession.PromptDelegate.RepostConfirmPrompt) objects[1]);

    GeckoObserverInvoker PROMPT_AUTH = (geckoObserver, objects) -> geckoObserver.onPromptAuth((GeckoState) objects[0], (GeckoSession.PromptDelegate.AuthPrompt) objects[1]);

    GeckoObserverInvoker PROMPT_COLOR = (geckoObserver, objects) -> geckoObserver.onPromptColor((GeckoState) objects[0], (GeckoSession.PromptDelegate.ColorPrompt) objects[1]);

    GeckoObserverInvoker PROMPT_UNLOAD = (geckoObserver, objects) -> geckoObserver.onPromptUnload((GeckoState) objects[0], (GeckoSession.PromptDelegate.BeforeUnloadPrompt) objects[1]);

    GeckoObserverInvoker PROMPT_DATE = (geckoObserver, objects) -> geckoObserver.onPromptDate((GeckoState) objects[0], (GeckoSession.PromptDelegate.DateTimePrompt) objects[1]);

    GeckoObserverInvoker PROMPT_LOGIN_SAVE = (geckoObserver, objects) -> geckoObserver.onPromptLoginSave((GeckoState) objects[0], (GeckoSession.PromptDelegate.AutocompleteRequest<?>) objects[1], (Boolean) objects[2]);

    GeckoObserverInvoker CONTENT_PERMISSION = (geckoObserver, objects) -> geckoObserver.onContentPermission((GeckoState) objects[0], (GeckoSession.PermissionDelegate.ContentPermission) objects[1], (Integer) objects[2]);

    GeckoObserverInvoker MEDIA_ACTIVATED = (geckoObserver, objects) -> geckoObserver.onMediaActivated((GeckoState) objects[0], (MediaSession) objects[1]);

    GeckoObserverInvoker MEDIA_DEACTIVATED = (geckoObserver, objects) -> geckoObserver.onMediaDeactivated((GeckoState) objects[0], (MediaSession) objects[1]);

    GeckoObserverInvoker MEDIA_PAUSE = (geckoObserver, objects) -> geckoObserver.onMediaPause((GeckoState) objects[0], (MediaSession) objects[1]);

    GeckoObserverInvoker MEDIA_PLAY = (geckoObserver, objects) -> geckoObserver.onMediaPlay((GeckoState) objects[0], (MediaSession) objects[1]);

    GeckoObserverInvoker MEDIA_STOP = (geckoObserver, objects) -> geckoObserver.onMediaStop((GeckoState) objects[0], (MediaSession) objects[1]);

    GeckoObserverInvoker MEDIA_METADATA = (geckoObserver, objects) -> geckoObserver.onMediaMetadata((GeckoState) objects[0], (MediaSession) objects[1], (MediaSession.Metadata) objects[2]);

    GeckoObserverInvoker MEDIA_POSITION = (geckoObserver, objects) -> geckoObserver.onMediaPosition((GeckoState) objects[0], (MediaSession) objects[1], (MediaSession.PositionState) objects[2]);

    GeckoObserverInvoker CRASH = (geckoObserver, objects) -> geckoObserver.onCrash((GeckoState) objects[0]);
}
