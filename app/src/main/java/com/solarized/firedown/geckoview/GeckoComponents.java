package com.solarized.firedown.geckoview;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ActivityInfo;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.provider.OpenableColumns;
import android.text.TextUtils;
import android.util.Log;
import android.webkit.URLUtil;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.UiThread;

import com.solarized.firedown.BuildConfig;
import com.solarized.firedown.Preferences;
import com.solarized.firedown.R;
import com.solarized.firedown.data.di.Qualifiers;
import com.solarized.firedown.data.entity.CertificateInfoEntity;
import com.solarized.firedown.data.entity.ContextElementEntity;
import com.solarized.firedown.data.entity.GeckoStateEntity;
import com.solarized.firedown.data.entity.WebHistoryEntity;
import com.solarized.firedown.data.repository.GeckoStateDataRepository;
import com.solarized.firedown.data.repository.IncognitoStateRepository;
import com.solarized.firedown.data.repository.WebBookmarkDataRepository;
import com.solarized.firedown.data.repository.WebHistoryDataRepository;
import com.solarized.firedown.geckoview.media.GeckoMediaController;
import com.solarized.firedown.utils.DebugLog;
import com.solarized.firedown.utils.FileUriHelper;
import com.solarized.firedown.utils.UrlStringUtils;

import org.mozilla.geckoview.AllowOrDeny;
import org.mozilla.geckoview.ContentBlocking;
import org.mozilla.geckoview.GeckoResult;
import org.mozilla.geckoview.GeckoSession;
import org.mozilla.geckoview.MediaSession;
import org.mozilla.geckoview.WebRequestError;
import org.mozilla.geckoview.WebResponse;


import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Executor;

import javax.inject.Inject;
import javax.inject.Singleton;

import dagger.hilt.android.qualifiers.ApplicationContext;


@Singleton
public class GeckoComponents {

    private static final String TAG = GeckoComponents.class.getSimpleName();

    /**
     * A page that fires a Play Store navigation within this many
     * milliseconds of finishing its own load is treated as a tracker
     * /redirector page that the user has no reason to stay on — the
     * blocker will goBack() past it so the user lands on the page
     * that actually contained the link. 3 s is generous enough to
     * catch slow JS redirects on cold-cache loads but short enough
     * not to misfire on regular pages where the user clicked a
     * Play Store link deliberately.
     */
    private static final long REDIRECTOR_WINDOW_MS = 3000L;

    public final ContentDelegate mContentDelegate;
    public final ProgressDelegate mProgressDelegate;
    public final NavigationDelegate mNavigationDelegate;
    public final HistoryDelegate mHistoryDelegate;
    public final ScrollDelegate mScrollDelegate;
    private final PromptDelegate mPromptDelegate;
    public final MediaSessionDelegate mMediaSessionDelegate;
    public final ContentBlockingDelegate mContentBlockingDelegate;
    public final PermissionDelegate mPermissionDelegate;
    private final GeckoObserverRegistry mGeckoObserverRegistry;
    private final GeckoStateDataRepository mGeckoStateDataRepository;
    private final IncognitoStateRepository mIncognitoStateRepository;
    private final WebHistoryDataRepository mWebHistoryDataRepository;

    private final WebBookmarkDataRepository mWebBookmarkDataRepository;
    private final GeckoMediaController mGeckoMediaController;
    private final GeckoRuntimeHelper mGeckoRuntimeHelper;
    /** File-upload prompt staging (copyToCache of user-picked files, possibly
     *  multi-GB) — unbounded file IO, so it rides the HEAVY lane, never
     *  @DiskIO (which must stay free for short DB mutations). */
    private final Executor mHeavyExecutor;
    private final Executor mMainExecutor;
    private final Context mContext;
    private final SharedPreferences mSharedPreferences;


    @Inject
    public GeckoComponents(
            GeckoMediaController geckoMediaController,
            GeckoStateDataRepository geckoStateRepository,
            IncognitoStateRepository incognitoStateRepository,
            WebHistoryDataRepository webHistoryRepository,
            WebBookmarkDataRepository webBookmarkRepository,
            GeckoRuntimeHelper geckoRuntimeHelper,
            GeckoObserverRegistry observerRegistry,
            SharedPreferences sharedPreferences,
            @Qualifiers.HeavyIO Executor heavyExecutor,
            @Qualifiers.MainThread Executor mainExecutor,
            @ApplicationContext Context context) {
        this.mContext = context;
        this.mSharedPreferences = sharedPreferences;
        this.mGeckoStateDataRepository = geckoStateRepository;
        this.mIncognitoStateRepository = incognitoStateRepository;
        this.mWebHistoryDataRepository = webHistoryRepository;
        this.mWebBookmarkDataRepository = webBookmarkRepository;
        this.mGeckoRuntimeHelper = geckoRuntimeHelper;
        this.mGeckoObserverRegistry = observerRegistry;
        this.mGeckoMediaController = geckoMediaController;
        this.mProgressDelegate = new ProgressDelegate();
        this.mNavigationDelegate = new NavigationDelegate();
        this.mPermissionDelegate = new PermissionDelegate();
        this.mContentBlockingDelegate = new ContentBlockingDelegate();
        this.mHistoryDelegate = new HistoryDelegate();
        this.mPromptDelegate = new PromptDelegate();
        this.mScrollDelegate = new ScrollDelegate();
        this.mContentDelegate = new ContentDelegate();
        this.mMediaSessionDelegate = new MediaSessionDelegate();
        this.mHeavyExecutor = heavyExecutor;
        this.mMainExecutor = mainExecutor;
    }

    public MediaSessionDelegate getMediaSessionDelegate() {
        return mMediaSessionDelegate;
    }

    public ContentDelegate getContentDelegate() {
        return mContentDelegate;
    }

    public ContentBlockingDelegate getContentBlockingDelegate() {
        return mContentBlockingDelegate;
    }

    public ScrollDelegate getScrollDelegate() {
        return mScrollDelegate;
    }

    public ProgressDelegate getProgressDelegate(){
        return mProgressDelegate;
    }

    public HistoryDelegate getHistoryDelegate() {
        return mHistoryDelegate;
    }

    public NavigationDelegate getNavigationDelegate() {
        return mNavigationDelegate;
    }

    public PromptDelegate getPromptDelegate(){
        return mPromptDelegate;
    }

    public PermissionDelegate getPermissionDelegate(){
        return mPermissionDelegate;
    }

    // ── Incognito-aware session lookup helpers ──────────────────────────

    /**
     * Looks up a GeckoState by session, checking the regular repo first,
     * then the incognito repo. Returns null if the session is not found
     * in either.
     */
    private GeckoState findGeckoState(GeckoSession session) {
        GeckoState state = mGeckoStateDataRepository.getGeckoState(session);
        if (state == null) {
            state = mIncognitoStateRepository.getGeckoState(session);
        }
        return state;
    }

    /**
     * Checks if the given GeckoState is the current active state in its
     * respective repo (regular or incognito).
     */
    private boolean isCurrentGeckoState(GeckoState geckoState) {
        if (geckoState.getGeckoStateEntity().isIncognito()) {
            return mIncognitoStateRepository.isCurrentGeckoState(geckoState);
        }
        return mGeckoStateDataRepository.isCurrentGeckoState(geckoState);
    }

    /**
     * Notifies the correct repo to update its tab list LiveData.
     */
    private void notifyTabs(GeckoState geckoState) {
        if (geckoState != null && geckoState.getGeckoStateEntity().isIncognito()) {
            mIncognitoStateRepository.notifyTabs();
        } else {
            mGeckoStateDataRepository.notifyTabs();
        }
    }

    public final class PromptDelegate implements GeckoSession.PromptDelegate {

        private GeckoResult<PromptResponse> mPromptResponse;


        @Override
        public GeckoResult<PromptResponse> onDateTimePrompt(
                @NonNull final GeckoSession session, @NonNull final DateTimePrompt prompt) {
            final GeckoState geckoState = findGeckoState(session);

            if (geckoState == null || !isCurrentGeckoState(geckoState))
                return GeckoResult.fromValue(prompt.dismiss());

            final GeckoResult<PromptResponse> res = new GeckoResult<>();

            mPromptResponse = res;

            mGeckoObserverRegistry.notifyObservers(GeckoObserverInvoker.PROMPT_DATE, geckoState, prompt, res);

            return res;
        }


        @Override
        public GeckoResult<PromptResponse> onBeforeUnloadPrompt(
                @NonNull final GeckoSession session, @NonNull final BeforeUnloadPrompt prompt) {

            final GeckoState geckoState = findGeckoState(session);

            // Dismiss for a background tab (see onButtonPrompt). A beforeunload
            // from a non-current tab must not block / pop over the visible one.
            if (geckoState == null || !isCurrentGeckoState(geckoState))
                return GeckoResult.fromValue(prompt.dismiss());

            final GeckoResult<PromptResponse> res = new GeckoResult<>();

            mPromptResponse = res;

            mGeckoObserverRegistry.notifyObservers(GeckoObserverInvoker.PROMPT_UNLOAD, geckoState, prompt, res);

            return res;

        }


        @Override
        public GeckoResult<PromptResponse> onAuthPrompt(
                @NonNull final GeckoSession session, @NonNull final AuthPrompt prompt) {

            final GeckoState geckoState = findGeckoState(session);

            if (geckoState == null || !isCurrentGeckoState(geckoState))
                return GeckoResult.fromValue(prompt.dismiss());

            final GeckoResult<PromptResponse> res = new GeckoResult<>();

            mPromptResponse = res;

            mGeckoObserverRegistry.notifyObservers(GeckoObserverInvoker.PROMPT_AUTH, geckoState, prompt, res);

            return res;
        }

        @Override
        public GeckoResult<PromptResponse> onRepostConfirmPrompt(
                @NonNull final GeckoSession session, @NonNull final RepostConfirmPrompt prompt) {

            final GeckoState geckoState = findGeckoState(session);

            // Dismiss for a background tab (see onButtonPrompt).
            if (geckoState == null || !isCurrentGeckoState(geckoState))
                return GeckoResult.fromValue(prompt.dismiss());

            final GeckoResult<PromptResponse> res = new GeckoResult<>();

            mPromptResponse = res;

            mGeckoObserverRegistry.notifyObservers(GeckoObserverInvoker.PROMPT_REPOST, geckoState, prompt, res);

            return res;
        }


        @Override
        public GeckoResult<PromptResponse> onChoicePrompt(
                @NonNull final GeckoSession session, @NonNull final ChoicePrompt prompt) {

            final GeckoState geckoState = findGeckoState(session);

            if (geckoState == null || !isCurrentGeckoState(geckoState))
                return GeckoResult.fromValue(prompt.dismiss());

            final GeckoResult<PromptResponse> res = new GeckoResult<>();

            mPromptResponse = res;

            mGeckoObserverRegistry.notifyObservers(GeckoObserverInvoker.PROMPT_CHOICE, geckoState, prompt, res);

            return res;
        }

        @NonNull
        @Override
        public GeckoResult<PromptResponse> onTextPrompt(@NonNull GeckoSession session, @NonNull TextPrompt prompt) {
            Log.d(TAG, "onTextPrompt: " + prompt.title + " , " + prompt.message);
            final GeckoState geckoState = findGeckoState(session);

            if (geckoState == null || !isCurrentGeckoState(geckoState))
                return GeckoResult.fromValue(prompt.dismiss());

            final GeckoResult<PromptResponse> res = new GeckoResult<>();

            mPromptResponse = res;

            mGeckoObserverRegistry.notifyObservers(GeckoObserverInvoker.PROMPT_TEXT, geckoState, prompt);

            return res;

        }

        @Override
        public GeckoResult<PromptResponse> onButtonPrompt(
                @NonNull final GeckoSession session, @NonNull final ButtonPrompt prompt) {
            Log.d(TAG, "onButtonPrompt: " + prompt.title + " , " + prompt.message);
            final GeckoState geckoState = findGeckoState(session);

            // Dismiss prompts from a background tab instead of popping them
            // over the foreground tab (same isCurrentGeckoState guard the
            // START/STOP/PROGRESS notifications use). A non-current tab's
            // alert/confirm shouldn't interrupt the visible page.
            if (geckoState == null || !isCurrentGeckoState(geckoState))
                return GeckoResult.fromValue(prompt.dismiss());

            final GeckoResult<PromptResponse> res = new GeckoResult<>();

            mPromptResponse = res;

            mGeckoObserverRegistry.notifyObservers(GeckoObserverInvoker.PROMPT_BUTTON, geckoState, prompt);

            return res;
        }

        @Override
        public GeckoResult<PromptResponse> onColorPrompt(@NonNull GeckoSession session, @NonNull ColorPrompt prompt) {
            Log.d(TAG, "onColorPrompt: " + prompt.title);
            final GeckoState geckoState = findGeckoState(session);

            if (geckoState == null || !isCurrentGeckoState(geckoState)) {
                return GeckoResult.fromValue(prompt.dismiss());
            }

            final GeckoResult<PromptResponse> res = new GeckoResult<>();

            mPromptResponse = res;

            mGeckoObserverRegistry.notifyObservers(GeckoObserverInvoker.PROMPT_COLOR, geckoState, prompt);

            return res;

        }


        @Override
        public GeckoResult<PromptResponse> onAlertPrompt(@NonNull GeckoSession session, @NonNull AlertPrompt prompt) {
            Log.d(TAG, "onAlertPrompt: " + prompt.title + " , " + prompt.message);
            final GeckoState geckoState = findGeckoState(session);

            if (geckoState == null || !isCurrentGeckoState(geckoState))
                return GeckoResult.fromValue(prompt.dismiss());

            final GeckoResult<PromptResponse> res = new GeckoResult<>();

            mPromptResponse = res;

            mGeckoObserverRegistry.notifyObservers(GeckoObserverInvoker.PROMPT_ALERT, geckoState, prompt);

            return res;

        }


        @Override
        public GeckoResult<GeckoSession.PromptDelegate.PromptResponse> onFilePrompt(@NonNull GeckoSession session, @NonNull GeckoSession.PromptDelegate.FilePrompt prompt) {
            final GeckoState geckoState = findGeckoState(session);
            // Dismiss for a background tab (see onButtonPrompt) — a non-current
            // tab must not raise the system file picker over the visible page.
            if (geckoState == null || !isCurrentGeckoState(geckoState)) {
                return GeckoResult.fromValue(prompt.dismiss());
            }

            // Convert all MIME types/extensions to proper MIME type strings
            String mimeType = null;
            List<String> convertedMimeTypes = new ArrayList<>();

            if (prompt.mimeTypes != null) {
                for (final String rawType : prompt.mimeTypes) {
                    final String trimmed = rawType.trim().toLowerCase(Locale.ROOT);
                    final String normalized = trimmed.startsWith(".")
                            ? FileUriHelper.getMimeTypeFromFile(trimmed)
                            : trimmed;

                    if (normalized == null || !normalized.contains("/")) continue;

                    convertedMimeTypes.add(normalized);

                    final int slash = normalized.indexOf('/');
                    final String newType = normalized.substring(0, slash);

                    if (mimeType == null) {
                        mimeType = newType;
                    } else if (!mimeType.equals(newType)) {
                        mimeType = "*";
                    }
                }
            }

            // Use category wildcard for setType so the picker shows all files of that category.
            // EXTRA_MIME_TYPES (with properly converted MIME strings) handles the fine filtering.
            final String intentType = (mimeType != null && !mimeType.equals("*"))
                    ? mimeType + "/*"
                    : "*/*";

            final Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            intent.addFlags(Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
            intent.setType(intentType);

            if (prompt.type == FilePrompt.Type.MULTIPLE) {
                intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
            }

            // Only set EXTRA_MIME_TYPES when there are multiple types — single type is
            // already covered by setType() with the category wildcard above.
            if (convertedMimeTypes.size() > 1) {
                intent.putExtra(Intent.EXTRA_MIME_TYPES,
                        convertedMimeTypes.toArray(new String[0]));
            }

            final GeckoResult<PromptResponse> res = new GeckoResult<>();
            try {
                mPromptResponse = res;
                mGeckoObserverRegistry.notifyObservers(GeckoObserverInvoker.PROMPT_FILE, geckoState, prompt, intent);
            } catch (final ActivityNotFoundException e) {
                Log.e(TAG, "Cannot launch activity", e);
                return GeckoResult.fromValue(prompt.dismiss());
            }

            return res;
        }


        private void cleanUploadCache(Activity activity) {
            try {
                File cacheDir = activity.getCacheDir();
                File[] files = cacheDir.listFiles((dir, name) -> name.startsWith("upload_"));
                if (files != null) {
                    long cutoff = System.currentTimeMillis() - 60_000; // 1 minute old
                    for (File file : files) {
                        if (file.lastModified() < cutoff) {
                            file.delete();
                        }
                    }
                }
            } catch (Exception ignored) {}
        }

        private Uri copyToCache(Activity activity, Uri sourceUri) {
            try {
                String fileName = "upload_" + System.currentTimeMillis();

                // Try to get the actual filename
                try (Cursor cursor = activity.getContentResolver().query(
                        sourceUri, null, null, null, null)) {
                    if (cursor != null && cursor.moveToFirst()) {
                        int nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                        if (nameIndex >= 0) {
                            fileName = cursor.getString(nameIndex);
                        }
                    }
                }

                File cacheFile = new File(activity.getCacheDir(), fileName);

                try (InputStream input = activity.getContentResolver().openInputStream(sourceUri);
                     OutputStream output = new FileOutputStream(cacheFile)) {
                    if (input == null) return null;

                    byte[] buffer = new byte[8192];
                    int bytesRead;
                    while ((bytesRead = input.read(buffer)) != -1) {
                        output.write(buffer, 0, bytesRead);
                    }
                }

                return Uri.fromFile(cacheFile);

            } catch (Exception e) {
                Log.e(TAG, "Failed to copy file to cache", e);
                return null;
            }
        }


        public void onPromptCallbackResult(PromptResponse promptResponse) {
            if (mPromptResponse == null) {
                return;
            }

            final GeckoResult<PromptResponse> res = mPromptResponse;

            mPromptResponse = null;

            res.complete(promptResponse);


        }

        public void onFileCallbackResult(final Activity activity, final int resultCode, final Intent data, final FilePrompt filePrompt) {
            if (mPromptResponse == null) {
                return;
            }

            final GeckoResult<PromptResponse> res = mPromptResponse;

            mPromptResponse = null;

            if (resultCode != Activity.RESULT_OK || data == null) {
                res.complete(filePrompt.dismiss());
                return;
            }

            final Uri uri = data.getData();

            if (uri == null) {
                res.complete(filePrompt.dismiss());
                return;
            }

            final ClipData clip = data.getClipData();

            mHeavyExecutor.execute(() -> {
                cleanUploadCache(activity);

                Uri cachedUri = null;
                Uri[] cachedUris = null;

                if (filePrompt.type == FilePrompt.Type.SINGLE
                        || (filePrompt.type == FilePrompt.Type.MULTIPLE && clip == null)) {
                    cachedUri = copyToCache(activity, uri);
                } else if (filePrompt.type == FilePrompt.Type.MULTIPLE) {
                    final int count = clip.getItemCount();
                    final ArrayList<Uri> uris = new ArrayList<>(count);
                    for (int i = 0; i < count; i++) {
                        Uri cached = copyToCache(activity, clip.getItemAt(i).getUri());
                        if (cached != null) {
                            uris.add(cached);
                        }
                    }
                    if (!uris.isEmpty()) {
                        cachedUris = uris.toArray(new Uri[0]);
                    }
                }

                // Capture for lambda
                final Uri finalCachedUri = cachedUri;
                final Uri[] finalCachedUris = cachedUris;

                mMainExecutor.execute(() -> {
                    if (filePrompt.type == FilePrompt.Type.SINGLE
                            || (filePrompt.type == FilePrompt.Type.MULTIPLE && clip == null)) {
                        if (finalCachedUri != null) {
                            res.complete(filePrompt.confirm(activity, finalCachedUri));
                        } else {
                            res.complete(filePrompt.dismiss());
                        }
                    } else if (filePrompt.type == FilePrompt.Type.MULTIPLE && finalCachedUris != null) {
                        res.complete(filePrompt.confirm(activity, finalCachedUris));
                    } else {
                        res.complete(filePrompt.dismiss());
                    }
                });
            });
        }
    }

    public class ContentDelegate implements GeckoSession.ContentDelegate {


        @Override
        public void onPaintStatusReset(@NonNull GeckoSession session) {
            // Page is navigating — visual state is being torn down
            // Reset so thumbnail capture gate is closed until next onFirstContentfulPaint
            final GeckoState geckoState = findGeckoState(session);
            if (geckoState == null)
                return;
            geckoState.setFirstContentFulPaint(false);
        }


        @Override
        public void onFirstContentfulPaint(@NonNull GeckoSession session) {

            final GeckoState geckoState = findGeckoState(session);

            if(geckoState == null)
                return;

            geckoState.setFirstContentFulPaint(true);

            if(isCurrentGeckoState(geckoState)){
                mGeckoObserverRegistry.notifyObservers(GeckoObserverInvoker.THUMBNAIL, geckoState);
            }
        }

        @Override
        public void onHideDynamicToolbar(@NonNull GeckoSession geckoSession) {
            GeckoState geckoState = findGeckoState(geckoSession);

            if(geckoState == null)
                return;

            // Foreground-only UI rule (same gate as START/STOP/PROGRESS): a
            // background tab must not collapse the visible tab's chrome.
            if (isCurrentGeckoState(geckoState)) {
                mGeckoObserverRegistry.notifyObservers(GeckoObserverInvoker.HIDE_BARS, geckoState);
            }
        }

        @Override
        public void onPreviewImage(
                @NonNull final GeckoSession session, @NonNull final String previewImageUrl){
            final GeckoState geckoState = findGeckoState(session);
            if(geckoState != null){
                Log.d(TAG, "onPreviewImage: " + previewImageUrl + " url: " + geckoState.getEntityUri());
                geckoState.setEntityPreview(previewImageUrl);
            }
            notifyTabs(geckoState);
        }



        @Override
        public void onTitleChange(@NonNull GeckoSession session, String title) {

            final GeckoState geckoState = findGeckoState(session);

            if(geckoState == null)
                return;

            final String url = geckoState.getEntityUri();

            if(UrlStringUtils.isAboutBlank(url) || !URLUtil.isValidUrl(url) || UrlStringUtils.isAboutBlank(title))
                return;

            // The app's OWN error page is showing, and it kept the FAILED url as
            // the document url while errorPageScripts.js set document.title to
            // the generic "The Fire is Gone" headline. Persisting that would
            // stamp it onto the real site's history row — permanently, and for
            // every site that ever failed once, which is how a history list ends
            // up full of identical entries. The attempted visit still belongs in
            // history (Firefox records failed loads too); only the title is
            // ours, not the site's. The TAB title below is still set, so an
            // errored tab reads sensibly in the tab list.
            final boolean errorPage = geckoState.isShowingErrorPage();

            // Don't save history for incognito tabs
            if (!geckoState.isIncognito() && !errorPage) {
                // Repair the history row for THIS url. onHistoryStateChange may
                // have inserted it title-less (the title hadn't arrived yet —
                // GeckoView fires onTitleChange as a separate, later event), so
                // stamp the now-known title onto the row keyed by file_url. The
                // old id-keyed update never matched: a history row's uid is
                // generateId(url), NOT the tab's entity id, so the repair was a
                // silent no-op and the title-less/stale row survived into
                // autocomplete.
                mWebHistoryDataRepository.updateTitle(url, title);
            }

            // Backfill a PLACEHOLDER bookmark title once the page's real title
            // arrives. A bookmark made while the page was still loading captured
            // an empty/"About:blank" title — onTitleChange fires later than the
            // bookmark tap, and onLocationChange has already cleared any prior
            // page's title (see GeckoState) — and there is otherwise NO
            // bookmark-title repair (only the favicon is backfilled, via
            // IconsRepository), so it would read "About:blank" forever. The repo
            // updates ONLY a placeholder title (the DAO guards on empty/about:blank)
            // so a user's renamed bookmark is never overwritten, and no-ops when
            // the url isn't bookmarked — so this is safe to run regardless of
            // incognito (a bookmark persists either way). Skipped on an error
            // page for the same reason as the history repair above — a bookmark
            // to a site that happens to fail once must not be renamed to our
            // error headline.
            if (!errorPage) {
                mWebBookmarkDataRepository.updateTitle(url, title);
            }

            geckoState.setEntityTitle(title);

            notifyTabs(geckoState);
        }


        @Override
        public void onContextMenu(@NonNull final GeckoSession session, final int screenX, final int screenY, @NonNull final ContextElement element) {

            final GeckoState geckoState = findGeckoState(session);
            if (geckoState == null) return;

            if (!URLUtil.isValidUrl(element.baseUri) && !URLUtil.isValidUrl(element.linkUri)
                    && !URLUtil.isValidUrl(element.srcUri))
                return;

            geckoState.setContextElementEntity(new ContextElementEntity(element));

            String targetUrl = element.srcUri != null ? element.srcUri : element.linkUri;
            if (targetUrl != null) {
                mGeckoRuntimeHelper.setCookieContext(targetUrl, geckoState.getEntityId());
            }

            mGeckoObserverRegistry.notifyObservers(GeckoObserverInvoker.CONTEXT, geckoState, element);
        }


        @Override
        public void onFullScreen(@NonNull final GeckoSession session, final boolean fullScreen) {

            final GeckoState geckoState = findGeckoState(session);

            if(geckoState == null)
                return;

            geckoState.setEntityFullScreen(fullScreen);

            // Foreground-only UI rule: a background tab's late-resolving
            // fullscreen grant (or exit) must not flip the visible tab's
            // chrome — entityFullScreen above is per-tab and stays correct
            // either way; only the UI notification is gated. The state rides
            // along so the fragment can mode-filter (per-repo gate leak).
            if (isCurrentGeckoState(geckoState)) {
                mGeckoObserverRegistry.notifyObservers(
                        GeckoObserverInvoker.FULL_SCREEN, geckoState, fullScreen);
            }


        }


        @Override
        public void onCloseRequest(@NonNull final GeckoSession session) {
            Log.i(TAG, "onCloseRequest");
            final GeckoState geckoState = findGeckoState(session);
            if(geckoState == null){
                return;
            }
            // getOrCreateGeckoSession() here would silently spawn a fresh
            // session (with restoreState's auto-nav queued behind it) if
            // mGeckoSession was ever null at this point — wasteful for a
            // pure identity comparison. findGeckoState above already matched
            // by session identity, so the non-creating getter is correct.
            if (session == geckoState.getGeckoSession()) {
                if (geckoState.getGeckoStateEntity().isIncognito()) {
                    mIncognitoStateRepository.closeGeckoState(geckoState);
                } else {
                    mGeckoStateDataRepository.closeGeckoState(geckoState);
                }
                // The repositories drop the GeckoState from the tab list
                // and (for regular tabs) archive it to disk, but they do
                // not close the GeckoSession itself — the UI tab-close
                // paths (TabsFragment / TabsIncognitoFragment) call
                // closeGeckoSession() separately. window.close() routes
                // here instead, so without this the session stays open
                // (and pinned in process memory) for every popup that
                // closes itself. onCloseRequest is @UiThread, so the
                // close call is safe on this thread.
                geckoState.closeGeckoSession();
            }
            mGeckoObserverRegistry.notifyObservers(GeckoObserverInvoker.CLOSE_SESSION, geckoState);
        }

        @Override
        public void onExternalResponse(@NonNull GeckoSession session, @NonNull WebResponse response) {
            //downloadFile(response);

            if(response.body == null){
                Log.w(TAG, "onExternalResonse null body");
                return;
            }

            mGeckoObserverRegistry.notifyObservers(GeckoObserverInvoker.DOWNLOAD, response);

        }

        @Override
        public void onCrash(@NonNull GeckoSession session) {
            Log.e(TAG, "Crashed, reopening session");
            final GeckoState geckoState = findGeckoState(session);
            if(geckoState == null){
                return;
            }
            // A mid-load crash fires no onPageStop — clear the per-tab load
            // truth here or the bar-pinning policy stays latched for a load
            // that no longer exists.
            geckoState.setLoading(false);
            geckoState.clearPendingUserLoad();
            geckoState.setShowingErrorPage(false);
            // Discard at the DATA layer, before notify: the observer is
            // view-lifecycle scoped, so a crash landing while the browser view
            // is destroyed (user in Settings) used to skip the fragment's
            // discard entirely — the GeckoState kept a non-null CLOSED session,
            // and the later reopen of that same session never replays
            // restoreState (fresh-construction-only), the blank-tab bug.
            // Observers must not discard again (and onCrash's reopen relies on
            // getOrCreateGeckoSession constructing fresh from here).
            geckoState.discardGeckoSession();
            mGeckoObserverRegistry.notifyObservers(GeckoObserverInvoker.CRASH, geckoState);
        }

        /**
         * The content process for this session was killed by the OS — typically
         * because Android reclaimed it while the app was backgrounded under
         * memory pressure. Per the GeckoView contract the session is now
         * closed and unusable (isOpen() returns false); the documented
         * recovery is to call {@code open(GeckoRuntime)} and then
         * {@code load} or {@code restoreState}. Route through the same
         * observer that handles onCrash so {@link BrowserFragment#onKill}
         * can drive the reopen.
         */
        @Override
        public void onKill(@NonNull GeckoSession session) {
            Log.w(TAG, "Killed by OS, reopening session");
            final GeckoState geckoState = findGeckoState(session);
            if (geckoState == null) {
                return;
            }
            // Same as onCrash: a killed content process never delivers
            // onPageStop for an in-flight load, and the discard must live at
            // the data layer so a kill during the destroyed-view window (the
            // common case — kills happen while backgrounded) still drops the
            // dead session reference. BrowserFragment.onKill's view-detach
            // guard no longer compares against this (nulled) reference — it
            // releases on the ATTACHED session being closed.
            geckoState.setLoading(false);
            geckoState.clearPendingUserLoad();
            geckoState.setShowingErrorPage(false);
            geckoState.discardGeckoSession();
            mGeckoObserverRegistry.notifyObservers(GeckoObserverInvoker.KILL_SESSION, geckoState);
        }



        @Override
        public void onMetaViewportFitChange(@NonNull final GeckoSession session, @NonNull final String viewportFit) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
                return;
            }

            final GeckoState geckoState = findGeckoState(session);

            if(geckoState == null)
                return;

            mGeckoObserverRegistry.notifyObservers(GeckoObserverInvoker.VIEWPORT_FIT_CHANGE, viewportFit);
        }

        @Override
        public void onShowDynamicToolbar(@NonNull final GeckoSession session) {

            final GeckoState geckoState = findGeckoState(session);

            if(geckoState == null)
                return;

            mGeckoObserverRegistry.notifyObservers(GeckoObserverInvoker.DYNAMIC_TOOLBAR, geckoState);

        }


    }


    public class ScrollDelegate implements GeckoSession.ScrollDelegate{

        @Override
        public void onScrollChanged(@NonNull GeckoSession session, int scrollX, int scrollY) {
            mGeckoObserverRegistry.notifyObservers(GeckoObserverInvoker.SCROLL_Y, scrollY);
        }
    }


    public class ProgressDelegate implements GeckoSession.ProgressDelegate {


        @Override
        public void onPageStart(@NonNull final GeckoSession session, @NonNull final String url) {

            final GeckoState geckoState = findGeckoState(session);

            if(geckoState == null)
                return;

            // A load has STARTED — from here on, location changes belong to it
            // (Gecko cancelled any previous in-flight load on InternalLoad), so
            // the stale-commit suppression window closes. Must run before the
            // isCurrentGeckoState gate: the flag is per-tab state, not UI.
            // (Deliberately URL-blind: if the start belongs to the abandoned
            // page's own queued navigation rather than the user's load, the
            // worst case is one transient repaint that the user's load corrects
            // on its own commit — the guard is best-effort by design.)
            geckoState.clearPendingUserLoad();

            // A fresh load means whatever error page was showing is gone, so the
            // next title belongs to a real document again. Cleared here rather
            // than on onPageStop: the error page's own onTitleChange fires
            // BETWEEN onLoadError and this tab's next start, so a stop-time
            // clear would open exactly the window this flag exists to close.
            geckoState.setShowingErrorPage(false);

            // Per-tab load truth, ungated — the foreground-gated START observer
            // below can't be re-derived later, but this can (openSession reads
            // it when switching onto a tab that started loading while
            // backgrounded).
            geckoState.setLoading(true);

            geckoState.setInitialLoad(false);           // no longer a brand new tab
            geckoState.setFirstContentFulPaint(false);  // reset — new page hasn't painted yet
            geckoState.resetBlockedTrackerCounts();     // counts are per-page

            if(isCurrentGeckoState(geckoState)) {
                mGeckoObserverRegistry.notifyObservers(GeckoObserverInvoker.START, geckoState);
                if (geckoState.getGeckoStateEntity().isIncognito()) {
                    mIncognitoStateRepository.postBlockedTrackerCounts(
                            geckoState.getBlockedTrackerCountsSnapshot());
                } else {
                    mGeckoStateDataRepository.postBlockedTrackerCounts(
                            geckoState.getBlockedTrackerCountsSnapshot());
                }
            }

        }

        @Override
        public void onPageStop(@NonNull final GeckoSession session, final boolean success) {

            Log.d(TAG, "onStop: " + success);


            final GeckoState geckoState =findGeckoState(session);

            if(geckoState == null)
                return;

            // Close the stale-commit window (redundant with the guard's
            // one-shot consumption and the onPageStart clear — kept as a free
            // backstop) and record per-tab load truth. Before the
            // isCurrentGeckoState gate — per-tab state, not UI.
            geckoState.clearPendingUserLoad();
            geckoState.setLoading(false);

            if(!isCurrentGeckoState(geckoState))
                return;

            Log.d(TAG, "onStop: " + success + " isFirstContent: " + geckoState.isFirstContentFulPaint() + " success: " + success);

            mGeckoObserverRegistry.notifyObservers(GeckoObserverInvoker.STOP, geckoState);
        }

        @Override
        public void onProgressChange(@NonNull GeckoSession session, int progress) {
            Log.i(TAG, "onProgressChange " + progress);


            final GeckoState geckoState = findGeckoState(session);

            if(geckoState == null){
                Log.w(TAG, "onProgressChange null session: " + progress);
                return;
            }

            if(isCurrentGeckoState(geckoState))
                // State rides along so the fragment can mode-filter: the gate
                // above is per-repo, so without it a background regular tab's
                // progress repainted the visible incognito progress bar and
                // could clear its in-flight pull-to-refresh spinner.
                mGeckoObserverRegistry.notifyObservers(
                        GeckoObserverInvoker.PROGRESS, geckoState, progress);



        }
        @Override
        public void onSecurityChange(
                @NonNull final GeckoSession session, @NonNull final SecurityInformation securityInfo) {

            Log.d(TAG, "onSecurityChange: " + securityInfo.isSecure);

            final GeckoState geckoState = findGeckoState(session);

            if(geckoState == null)
                return;

            String url = geckoState.getEntityUri();

            CertificateInfoEntity certificateInfoEntity = CertificateInfoEntity.from(
                    url,
                    securityInfo.host,
                    securityInfo.certificate,
                    securityInfo.isSecure,
                    securityInfo.isException,
                    securityInfo.securityMode,
                    securityInfo.mixedModeActive,
                    securityInfo.mixedModePassive
            );

            // Only persist cert info for regular tabs
            if (!geckoState.isIncognito()) {
                mGeckoStateDataRepository.notifyCert(certificateInfoEntity);
            }else{
                mIncognitoStateRepository.notifyCert(certificateInfoEntity);
            }

            geckoState.setCertificateState(certificateInfoEntity);

            if(isCurrentGeckoState(geckoState))
                mGeckoObserverRegistry.notifyObservers(GeckoObserverInvoker.SECURITY, geckoState, securityInfo);

        }

        @Override
        public void onSessionStateChange(@NonNull GeckoSession session,
                                         @NonNull GeckoSession.SessionState sessionState) {

            Log.d(TAG, "onSessionStateChange");

            final GeckoState geckoState = findGeckoState(session);

            if(geckoState == null)
                return;

            // Only persist session state for regular tabs — incognito is in-memory only
            if (!geckoState.isIncognito()) {
                geckoState.setEntityState(sessionState);
                // Push through notifyTabs so GeckoStateObserver writes the new
                // state to disk now. setEntityState alone only mutates the
                // in-memory entity; without this emission the sessions file is
                // only rewritten when something else triggers notifyTabs (a tab
                // open/close, or the onThumbnail capture on backgrounding) — so
                // killing the app straight from a page lost its latest URL /
                // history, while backgrounding first happened to flush it.
                notifyTabs(geckoState);
            }

            Log.d(TAG, "onSessionStateChange end");

        }

    }


    public class ContentBlockingDelegate implements ContentBlocking.Delegate{

        @Override
        public void onContentBlocked(
                @NonNull final GeckoSession session, @NonNull final ContentBlocking.BlockEvent event) {
            final GeckoState geckoState = findGeckoState(session);
            if (geckoState == null) return;

            // BlockEvent also fires for "cookie loaded" notifications
            // (STATE_COOKIES_LOADED_*) which carry a non-zero
            // cookieBehaviorCategory but represent allowed traffic, not
            // a block. isBlocking() is true only for STATE_COOKIES_BLOCKED_*
            // (and any antiTracking/safeBrowsing match), so gating here
            // keeps loaded-cookie chatter out of the visible total.
            if (!event.isBlocking()) return;

            // BlockEvent fires for cookie behaviour reasons too — those carry
            // a non-zero cookieBehaviorReason but no AntiTracking bits, so the
            // category mapper returns null for them and we just skip. The
            // common case (Ad/Analytic/Content/Social/Fingerprint/Crypto)
            // does have AntiTracking bits.
            int categoryMask = event.getAntiTrackingCategory();
            int cookieReason = event.getCookieBehaviorCategory();

            boolean changed = false;
            if (categoryMask != 0) {
                changed = geckoState.incrementBlockedTracker(categoryMask, event.uri);
            } else if (cookieReason != 0) {
                changed = geckoState.incrementBlockedCookie(event.uri);
            }

            if (changed && isCurrentGeckoState(geckoState)) {
                if (geckoState.getGeckoStateEntity().isIncognito()) {
                    mIncognitoStateRepository.postBlockedTrackerCounts(
                            geckoState.getBlockedTrackerCountsSnapshot());
                } else {
                    mGeckoStateDataRepository.postBlockedTrackerCounts(
                            geckoState.getBlockedTrackerCountsSnapshot());
                }
            }
        }

        /**
         * A content element that could be blocked has been loaded.
         *
         * @param session The GeckoSession that initiated the callback.
         * @param event The {@link ContentBlocking.BlockEvent} details.
         */
        @UiThread
        public void onContentLoaded(
                @NonNull final GeckoSession session, @NonNull final ContentBlocking.BlockEvent event) {
            Log.d(TAG, "onContentLoaded: " + event.uri);
        }

    }

    public class MediaSessionDelegate implements MediaSession.Delegate {

        @Override
        public void onActivated(@NonNull final GeckoSession session, @NonNull final MediaSession mediaSession) {
            Log.d(TAG, "MediaSessionDelegate onActivated");
            final GeckoState geckoState = findGeckoState(session);


            if(geckoState == null)
                return;

            mGeckoMediaController.onActivated(mediaSession, geckoState);

            if(isCurrentGeckoState(geckoState))
                mGeckoObserverRegistry.notifyObservers(GeckoObserverInvoker.MEDIA_ACTIVATED, geckoState, mediaSession);

        }

        @Override
        public void onPlay(@NonNull final GeckoSession session, @NonNull final MediaSession mediaSession) {
            Log.d(TAG, "MediaSessionDelegate onPlay");
            final GeckoState geckoState = findGeckoState(session);

            if(geckoState == null)
                return;

            mGeckoMediaController.onMediaPlay(mediaSession, geckoState);

            if(isCurrentGeckoState(geckoState)){
                mGeckoObserverRegistry.notifyObservers(GeckoObserverInvoker.MEDIA_PLAY, geckoState, mediaSession);
            }

        }

        @Override
        public void onStop(@NonNull final GeckoSession session, @NonNull final MediaSession mediaSession) {
            Log.d(TAG, "MediaSessionDelegate onStop");
            final GeckoState geckoState = findGeckoState(session);

            if(geckoState == null)
                return;

            mGeckoMediaController.onMediaPauseOrStop(mediaSession, geckoState);

            if(isCurrentGeckoState(geckoState))
                mGeckoObserverRegistry.notifyObservers(GeckoObserverInvoker.MEDIA_STOP, geckoState, mediaSession);

        }

        @Override
        public void onPositionState(@NonNull final GeckoSession session, @NonNull final MediaSession mediaSession, @NonNull final MediaSession.PositionState positionState) {
            Log.d(TAG, "MediaSessionDelegate onPositionState");
            final GeckoState geckoState = findGeckoState(session);

            if (geckoState == null)
                return;

            // Always keep controller state fresh
            mGeckoMediaController.onPositionChange(mediaSession, geckoState, positionState);


            if (isCurrentGeckoState(geckoState))
                mGeckoObserverRegistry.notifyObservers(GeckoObserverInvoker.MEDIA_POSITION,
                        geckoState, mediaSession, positionState);

        }

        @Override
        public void onDeactivated(@NonNull final GeckoSession session, @NonNull final MediaSession mediaSession) {
            Log.d(TAG, "MediaSessionDelegate onDeactivated");
            final GeckoState geckoState = findGeckoState(session);

            if(geckoState == null)
                return;

            mGeckoMediaController.onDeactivated(geckoState);

            if(isCurrentGeckoState(geckoState))
                mGeckoObserverRegistry.notifyObservers(GeckoObserverInvoker.MEDIA_DEACTIVATED, geckoState, mediaSession);

        }

        @Override
        public void onPause(@NonNull final GeckoSession session, @NonNull final MediaSession mediaSession) {
            Log.d(TAG, "MediaSessionDelegate onPause");

            final GeckoState geckoState = findGeckoState(session);

            if(geckoState == null)
                return;

            mGeckoMediaController.onMediaPauseOrStop(mediaSession, geckoState);

            if(isCurrentGeckoState(geckoState))
                mGeckoObserverRegistry.notifyObservers(GeckoObserverInvoker.MEDIA_PAUSE, geckoState, mediaSession);

        }


        @Override
        public void onMetadata(
                @NonNull final GeckoSession session,
                @NonNull final MediaSession mediaSession,
                @NonNull final MediaSession.Metadata meta) {


            Log.d(TAG, "MediaSessionDelegate onMetadata: Metadata=" + meta);

            final GeckoState geckoState = findGeckoState(session);

            if(geckoState == null)
                return;

            mGeckoMediaController.onMetaData(meta, geckoState);

            if(isCurrentGeckoState(geckoState))
                mGeckoObserverRegistry.notifyObservers(GeckoObserverInvoker.MEDIA_METADATA, geckoState, mediaSession, meta);


        }

        @Override
        public void onFullscreen(
                @NonNull final GeckoSession session,
                @NonNull final MediaSession mediaSession,
                final boolean enabled,
                @Nullable final MediaSession.ElementMetadata meta) {
            Log.d(TAG, "onFullscreen: Metadata=" + (meta != null ? meta.toString() : "null") + " source: " + (meta != null ? meta.source : "null"));

            final GeckoState geckoState = findGeckoState(session);

            if(geckoState == null)
                return;

            if (!enabled) {
                mGeckoObserverRegistry.notifyObservers(GeckoObserverInvoker.ORIENTATION, ActivityInfo.SCREEN_ORIENTATION_USER);
                return;
            }

            if (meta == null) {
                return;
            }

            if (meta.width > meta.height) {
                mGeckoObserverRegistry.notifyObservers(GeckoObserverInvoker.ORIENTATION, ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE);
            } else {
                mGeckoObserverRegistry.notifyObservers(GeckoObserverInvoker.ORIENTATION,ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED);
            }
        }
    }


    public final class HistoryDelegate implements GeckoSession.HistoryDelegate {

        // In-memory visited set for visited-link coloring (getVisited). This
        // is a session-lifetime convenience, NOT the history store — capping
        // it only means very old links lose their :visited tint. Uncapped it
        // grew one String per visited URL for the whole process lifetime
        // (GeckoComponents is a singleton) and contributed to a 128 MB-heap
        // OOM in marathon sessions. Insertion-order (FIFO) eviction.
        private static final int MAX_VISITED_URLS = 4096;

        private final LinkedHashSet<String> mVisitedURLs;


        public HistoryDelegate() {
            mVisitedURLs = new LinkedHashSet<>();
        }

        @Override
        public GeckoResult<Boolean> onVisited(
                @NonNull GeckoSession session, @NonNull String url, String lastVisitedURL, int flags) {
            Log.d(TAG, "Visited URL: " + url + " flags TOP LEVEL: " + (flags & VISIT_TOP_LEVEL) + " flags REDIRECT: " + ((flags & VISIT_REDIRECT_SOURCE) != 0) + " lastVisited: " + lastVisitedURL + " flags: " + flags);
            final GeckoState geckoState = findGeckoState(session);

            if (URLUtil.isAboutUrl(url) || !URLUtil.isValidUrl(url) || URLUtil.isContentUrl(url) || geckoState == null || lastVisitedURL == null)
                return GeckoResult.fromValue(false);

            // Don't track visited URLs for incognito
            if (geckoState.isIncognito())
                return GeckoResult.fromValue(false);

            if (((flags & VISIT_TOP_LEVEL) == 0))
                return GeckoResult.fromValue(false);

            if (!mVisitedURLs.contains(url) && mVisitedURLs.size() >= MAX_VISITED_URLS) {
                Iterator<String> oldest = mVisitedURLs.iterator();
                oldest.next();
                oldest.remove();
            }
            mVisitedURLs.add(url);

            return GeckoResult.fromValue(true);
        }

        @Override
        public GeckoResult<boolean[]> getVisited(@NonNull GeckoSession session, @NonNull String[] urls) {

            final GeckoState geckoState = findGeckoState(session);

            if(geckoState == null)
                return GeckoResult.fromValue(null);

            boolean[] visited = new boolean[urls.length];
            for (int i = 0; i < urls.length; i++) {
                visited[i] = mVisitedURLs.contains(urls[i]);
            }
            return GeckoResult.fromValue(visited);
        }

        @Override
        public void onHistoryStateChange(
                @NonNull final GeckoSession session, @NonNull final HistoryList historyList) {

            final GeckoState geckoState = findGeckoState(session);

            if(geckoState == null || geckoState.isIncognito() || historyList.isEmpty())
                return;


            HistoryItem historyItem = historyList.get(historyList.size() - 1);

            String uri = geckoState.getEntityUri();

            // The title may not have arrived yet for this url: onLocationChange
            // has already cleared the previous page's title (see GeckoState), and
            // GeckoView fires onTitleChange separately and later. Don't pair the
            // row with the "about:blank" sentinel getEntityTitle() returns when
            // unset — store no title and let the url-keyed onTitleChange repair
            // fill it in. A null/empty title is strictly better than a stale or
            // placeholder one in autocomplete (the previous behaviour persisted
            // whichever title the tab happened to hold at this instant).
            String title = geckoState.getEntityTitle();
            if (UrlStringUtils.isAboutBlank(title)) title = null;

            WebHistoryEntity webHistoryEntity = new WebHistoryEntity();
            webHistoryEntity.setId(WebHistoryDataRepository.generateId(uri));
            webHistoryEntity.setFileDate(System.currentTimeMillis());
            webHistoryEntity.setFileTitle(title);
            webHistoryEntity.setFileUrl(uri);
            webHistoryEntity.setFileIcon(geckoState.getEntityIcon());
            webHistoryEntity.setFileIconResolution(geckoState.getEntityIconResolution());


            String url = historyItem.getUri();

            if (!URLUtil.isAboutUrl(url) && URLUtil.isValidUrl(url) && !URLUtil.isContentUrl(url)) {
                mWebHistoryDataRepository.add(webHistoryEntity);
            }

        }

    }


    public class PermissionDelegate implements GeckoSession.PermissionDelegate{

        private GeckoResult<Integer> mPromptResponse;

        @Override
        public void onAndroidPermissionsRequest(
                @NonNull final GeckoSession session, final String[] permissions, @NonNull final Callback callback) {
            if(permissions != null){
                for(String p : permissions){
                    Log.d(TAG, "onAndroidPermission: " + p);
                }
            }
        }

        @Override
        public GeckoResult<Integer> onContentPermissionRequest(
                @NonNull final GeckoSession session, @NonNull final ContentPermission perm) {
            Log.d(TAG, "onContentRequest: " + perm.permission);

            GeckoState findGeckoState = findGeckoState(session);

            //Don't allow in incognito
            if(findGeckoState != null && findGeckoState.isIncognito())
                return GeckoResult.fromValue(ContentPermission.VALUE_DENY);

            final int resId;

            switch (perm.permission) {
                case PERMISSION_GEOLOCATION:
                    if(mSharedPreferences.getBoolean(Preferences.SETTINGS_BLOCK_LOCATION, Preferences.DEFAULT_BLOCK_LOCATION)){
                        return GeckoResult.fromValue(ContentPermission.VALUE_DENY);
                    }
                    resId = R.string.request_geolocation;
                    break;
                case PERMISSION_PERSISTENT_STORAGE:
                    resId = R.string.request_storage;
                    break;
                case PERMISSION_AUTOPLAY_AUDIBLE:
                case PERMISSION_AUTOPLAY_INAUDIBLE:
                    return GeckoResult.fromValue(ContentPermission.VALUE_ALLOW);
                case PERMISSION_MEDIA_KEY_SYSTEM_ACCESS:
                    // The EME pref cluster (media.eme.enabled,
                    // media.gmp-widevinecdm.*, media.gmp-manager.*,
                    // browser.eme.ui.enabled) doesn't gate this prompt —
                    // PERMISSION_MEDIA_KEY_SYSTEM_ACCESS is a separate
                    // per-page content permission that fires every time
                    // a page calls navigator.requestMediaKeySystemAccess,
                    // regardless of whether we have a CDM to grant. With
                    // the DRM toggle off, auto-deny silently so the user
                    // doesn't see a prompt for a feature they explicitly
                    // turned off.
                    if (!mSharedPreferences.getBoolean(Preferences.SETTINGS_ENABLE_DRM, false)) {
                        return GeckoResult.fromValue(ContentPermission.VALUE_DENY);
                    }
                    resId = R.string.request_media_key_system_access;
                    break;
                case PERMISSION_STORAGE_ACCESS:
                    resId = R.string.request_storage_access;
                    break;
                default:
                    return GeckoResult.fromValue(ContentPermission.VALUE_DENY);
            }

            final GeckoState geckoState = findGeckoState(session);

            if(geckoState == null)
                return GeckoResult.fromValue(ContentPermission.VALUE_DENY);

            mPromptResponse = new GeckoResult<>();

            mGeckoObserverRegistry.notifyObservers(GeckoObserverInvoker.CONTENT_PERMISSION, geckoState, perm, resId);

            return mPromptResponse;
        }

        public void onPermissionCallbackResult(Integer permissionValue){
            if(mPromptResponse == null){
                return;
            }

            final GeckoResult<Integer> res = mPromptResponse;
            mPromptResponse = null;

            res.complete(permissionValue);

        }



    }

    public class NavigationDelegate implements GeckoSession.NavigationDelegate {

        @Override
        public void onLocationChange(
                @NonNull GeckoSession session, final String url, @NonNull final List<GeckoSession.PermissionDelegate.ContentPermission> perms, @NonNull Boolean isUserGesture) {

            final GeckoState geckoState = findGeckoState(session);

            Log.d(TAG, "OnLocationChange: " + session + " location: " + url);

            if(url == null || geckoState == null)
                return;

            if(geckoState.isInitialLoad() && UrlStringUtils.isAboutBlank(url))
                return;

            if(UrlStringUtils.isURLDataLike(url))
                return;

            // Stale-commit guard (the "typed B while A was stuck loading" race).
            // Between BrowserFragment.openUri's loadUri(B) and Gecko's docshell
            // actually starting B (which cancels the in-flight A — verified
            // against Fenix/nsDocShell::InternalLoad → StopInternal), A's commit
            // can still land here. Writing it through would overwrite the
            // entity URI + toolbar with the URL the user just navigated AWAY
            // from.
            //
            // ONE-SHOT: the guard consumes itself on the first location change
            // it sees, whatever it is. If the url MATCHES the pending load, this
            // IS the user's load committing same-document (a #fragment commit
            // fires neither onPageStart nor onPageStop) — process it normally.
            // If it differs, it is the single stale commit the abandoned load
            // can still produce — suppress just that one event. Never suppress
            // more: a persistent flag wedged shut on loads that produce no
            // progress events and then swallowed every later SPA/pushState
            // location change on the tab (see GeckoState.mPendingUserLoadUri).
            String pendingUserLoadUri = geckoState.getPendingUserLoadUri();
            if (pendingUserLoadUri != null) {
                geckoState.clearPendingUserLoad();
                // Case-insensitive: Gecko's fix-up lowercases the host, so a
                // typed "Example.com/page#sec" commits as "example.com/…" and
                // an exact equals would mis-classify the user's own
                // same-document commit as stale. Residual canonicalization
                // gaps (trailing slash on bare hosts, punycode) remain —
                // acceptable because the guard is one-shot: the worst case is
                // this single commit's history/observer update being skipped,
                // never a wedge.
                if (!pendingUserLoadUri.equalsIgnoreCase(url)) {
                    if (BuildConfig.DEBUG) {
                        Log.d(TAG, "OnLocationChange: suppressed stale "
                                + DebugLog.preview(url) + " — user load pending: "
                                + DebugLog.preview(pendingUserLoadUri));
                    }
                    return;
                }
            }

            Log.d(TAG, "OnLocationChange: " + session + " here location: " + url);

            geckoState.setEntityUri(url);

            geckoState.onLocationChange(url);

            geckoState.setInitialLoad(false);

            if(isCurrentGeckoState(geckoState)) {
                // WASM pref is global to the Gecko runtime — there's no
                // per-session API. Re-evaluate on each navigation of the
                // active tab so the pref tracks "is the host I'm currently
                // looking at allowlisted?" Background tabs share the
                // runtime, which is the trade-off the user accepted by
                // picking the dynamic-global mode.
                mGeckoRuntimeHelper.setWebAssembly(
                        mGeckoRuntimeHelper.shouldEnableWasmFor(url));
                // Pass the event's url alongside the state: the observer must
                // paint the toolbar from THIS value, not re-read the mutable
                // entity URI (which another callback may have rewritten by the
                // time the observer runs) — single-writer, like Fenix's
                // UpdateUrlAction carrying the url in the action.
                mGeckoObserverRegistry.notifyObservers(
                        GeckoObserverInvoker.LOCATION, geckoState, url);
            }


        }

        @Override
        public void onCanGoBack(@NonNull GeckoSession session, boolean canGoBack) {

            final GeckoState geckoState = findGeckoState(session);

            if(geckoState == null)
                return;

            Log.d(TAG, "onCanGoBack session: " + geckoState.getEntityUri() + " value: " + canGoBack);

            geckoState.setEntityCanGoBackward(canGoBack);


        }

        @Override
        public void onCanGoForward(@NonNull GeckoSession session, boolean canGoForward) {
            final GeckoState geckoState = findGeckoState(session);

            if(geckoState == null)
                return;

            geckoState.setEntityCanGoForward(canGoForward);

        }



        @Override
        public GeckoResult<AllowOrDeny> onLoadRequest(
                @NonNull final GeckoSession session, final LoadRequest request) {


            Log.d(
                    TAG,
                    "onLoadRequest="
                            + request.uri
                            + " triggerUri="
                            + request.triggerUri
                            + " where="
                            + request.target
                            + " isRedirect="
                            + request.isRedirect
                            + " isDirectNavigation="
                            + request.isDirectNavigation);


            final GeckoState geckoState = findGeckoState(session);

            if(TextUtils.isEmpty(request.uri)){
                return GeckoResult.deny();
            }

            // Self-heal for an orphaned session. If the GeckoView is actively
            // loading a session that is no longer tracked in either repo
            // (an upstream tab-list removal dropped its GeckoState while the
            // session stayed attached to the view), the old hard-deny here
            // permanently bricked the tab: every reload / typed-URL / Go was
            // silently refused, with no crash and a fully responsive UI —
            // the "all tabs stop responding" failure mode. Allow valid web
            // content through so the tab keeps working even if its bookkeeping
            // was lost; only the external-app + Play Store interception below
            // (which needs a live GeckoState) is skipped when we can't
            // resolve one.
            if (geckoState == null) {
                Log.w(TAG, "onLoadRequest: no GeckoState for session, uri=" + request.uri
                        + " — allowing web content to avoid bricking an orphaned tab");
                if (URLUtil.isValidUrl(request.uri)
                        || UrlStringUtils.isURLDataLike(request.uri)
                        || UrlStringUtils.isURLResouceLike(request.uri)
                        || UrlStringUtils.isViewSource(request.uri)
                        || UrlStringUtils.isMozExtensionLike(request.uri)
                        || UrlStringUtils.isURLFileLike(request.uri)
                        || UrlStringUtils.isBlobLike(request.uri)) {
                    return GeckoResult.allow();
                }
                mGeckoObserverRegistry.notifyObservers(GeckoObserverInvoker.LOAD_REQUEST,
                        geckoState, request.uri, !request.isDirectNavigation, false);
                return GeckoResult.deny();
            }

            // Intercept "install our app" nag redirects to Play Store
            // BEFORE the http(s) allow branch. Gated on
            // !isDirectNavigation so the user can still type / bookmark
            // a Play Store URL directly. Both http(s) play.google.com
            // and market:// flow through here — market:// would
            // otherwise fall to the external-app dialog below, which
            // has the wrong framing ("open another app?") for the
            // anti-nag use case.
            //
            // wasRedirector: most nag sites don't navigate to Play
            // Store directly — they bounce through a tracker URL
            // (e.g. ivoox.com/rf/{id}) that loads, runs JS, and only
            // then redirects to play.google.com. If we just deny the
            // play.google.com hop the user is stranded on the
            // tracker. Detect this by checking whether the current
            // page loaded "just now" (within REDIRECTOR_WINDOW_MS)
            // AND there's a previous entry in history; if so, the
            // observer will goBack() before showing the dialog so
            // the user ends up on the page they actually wanted.
            if (UrlStringUtils.isPlayStoreUrl(request.uri) && !request.isDirectNavigation) {
                long ageMs = System.currentTimeMillis() - geckoState.getLastNavigationTime();
                boolean wasRedirector = geckoState.getLastNavigationTime() > 0
                        && ageMs < REDIRECTOR_WINDOW_MS
                        && geckoState.canGoBackward();
                // Only surface the redirect UI for the foreground tab. A
                // background tab that bounces to Play Store while the user has
                // switched away must still be denied, but its snackbar would
                // otherwise pop over the now-visible tab. Same guard the
                // START/STOP/PROGRESS notifications already use.
                if (isCurrentGeckoState(geckoState)) {
                    mGeckoObserverRegistry.notifyObservers(
                            GeckoObserverInvoker.PLAYSTORE_REDIRECT,
                            geckoState, request.uri, wasRedirector);
                }
                return GeckoResult.deny();
            }

            if(URLUtil.isValidUrl(request.uri)){
                return GeckoResult.allow();
            }else if(UrlStringUtils.isURLDataLike(request.uri) || UrlStringUtils.isURLResouceLike(request.uri)
                    || UrlStringUtils.isViewSource(request.uri) || UrlStringUtils.isMozExtensionLike(request.uri)
                    || UrlStringUtils.isURLFileLike(request.uri) || UrlStringUtils.isBlobLike(request.uri)) {
                // blob: URLs are engine-generated object URLs (e.g. a site
                // doing URL.createObjectURL on a file to trigger a download).
                // They are web-engine content, never external-app links — let
                // Gecko handle them so a downloadable blob reaches
                // onExternalResponse (download dialog) instead of falling to
                // the deny branch and the bogus "open in app" dialog.
                return GeckoResult.allow();
            }else{
                // External-app / unsupported-scheme link (bilibili://, market://,
                // etc.). Always deny the navigation, but only raise the
                // "open in app" dialog for the foreground tab — a deeplink fired
                // by a background tab (e.g. bilibili.com after the user switched
                // away mid-load) must not pop a dialog over the visible tab.
                // Same isCurrentGeckoState guard the START/STOP/PROGRESS
                // notifications use.
                //
                // Pass the same anti-nag signals the Play Store path computes so
                // the observer can honour the "block app redirects" toggle:
                // autoRedirect (!isDirectNavigation) gates the silent block —
                // a deliberately-tapped deeplink still prompts — and wasRedirector
                // (bounced here just after a load, with back-history) lets it
                // goBack() off the tracker page.
                long ageMs = System.currentTimeMillis() - geckoState.getLastNavigationTime();
                boolean wasRedirector = geckoState.getLastNavigationTime() > 0
                        && ageMs < REDIRECTOR_WINDOW_MS
                        && geckoState.canGoBackward();
                if (isCurrentGeckoState(geckoState)) {
                    mGeckoObserverRegistry.notifyObservers(GeckoObserverInvoker.LOAD_REQUEST,
                            geckoState, request.uri, !request.isDirectNavigation, wasRedirector);
                }
                // A denied load never starts, so neither onPageStart nor
                // onPageStop will fire for it — if THIS deny is the TYPED
                // deeplink that armed the stale-commit guard (openUri), disarm
                // it so the tab's next legitimate SPA/pushState location change
                // isn't sacrificed to the guard's one-shot suppression.
                // Equality-gated: an unconditional clear let a PAGE-initiated
                // deeplink (the TikTok-class first-view intent:// nag) disarm a
                // RACING typed load's guard — re-opening exactly the stale-
                // commit window the guard exists to close.
                if (request.uri.equals(geckoState.getPendingUserLoadUri())) {
                    geckoState.clearPendingUserLoad();
                }
                return GeckoResult.deny();
            }

        }

        @Override
        public GeckoResult<AllowOrDeny> onSubframeLoadRequest(
                @NonNull final GeckoSession session, final LoadRequest request) {
            Log.d(
                    TAG,
                    "onSubframeLoadRequest="
                            + request.uri
                            + " triggerUri="
                            + request.triggerUri
                            + " isRedirect="
                            + request.isRedirect
                            + "isDirectNavigation="
                            + request.isDirectNavigation);

            return GeckoResult.allow();
        }

        @Override
        public GeckoResult<GeckoSession> onNewSession(@NonNull final GeckoSession session, @NonNull final String uri) {
            Log.d(TAG, "onNewSession: " + uri);

            // Look up the parent by session. Do NOT fall back to a
            // "current" state — there is no single current in a
            // multi-repo world; picking one would misattribute the new
            // popup's incognito-ness (e.g. attribute an incognito child
            // to a regular parent just because an incognito tab exists
            // in the background, or vice versa).
            final GeckoState parentState = findGeckoState(session);
            if (parentState == null) {
                Log.w(TAG, "onNewSession: no parent state for session, skipping");
                return GeckoResult.fromValue(null);
            }

            GeckoStateEntity geckoStateEntity = new GeckoStateEntity(false);
            geckoStateEntity.setUri(uri);
            geckoStateEntity.setCreationDate(System.currentTimeMillis());
            geckoStateEntity.setParentId(parentState.getEntityId());

            // Inherit incognito from parent
            if (parentState.getGeckoStateEntity().isIncognito()) {
                geckoStateEntity.setIncognito(true);
            }

            GeckoState geckoState = new GeckoState(geckoStateEntity);

            mGeckoObserverRegistry.notifyObservers(GeckoObserverInvoker.NEW_SESSION, geckoState, uri);

            return GeckoResult.fromValue(null);
        }


        private GeckoError.ErrorType errorToType(final int error) {
            return switch (error) {
                case WebRequestError.ERROR_UNKNOWN -> GeckoError.ErrorType.UNKNOWN;
                case WebRequestError.ERROR_SECURITY_SSL -> GeckoError.ErrorType.ERROR_SECURITY_SSL;
                case WebRequestError.ERROR_SECURITY_BAD_CERT ->
                        GeckoError.ErrorType.ERROR_SECURITY_BAD_CERT;
                case WebRequestError.ERROR_NET_RESET -> GeckoError.ErrorType.ERROR_NET_RESET;
                case WebRequestError.ERROR_NET_INTERRUPT ->
                        GeckoError.ErrorType.ERROR_NET_INTERRUPT;
                case WebRequestError.ERROR_NET_TIMEOUT -> GeckoError.ErrorType.ERROR_NET_TIMEOUT;
                case WebRequestError.ERROR_CONNECTION_REFUSED ->
                        GeckoError.ErrorType.ERROR_CONNECTION_REFUSED;
                case WebRequestError.ERROR_UNKNOWN_PROTOCOL ->
                        GeckoError.ErrorType.ERROR_UNKNOWN_PROTOCOL;
                case WebRequestError.ERROR_UNKNOWN_HOST -> GeckoError.ErrorType.ERROR_UNKNOWN_HOST;
                case WebRequestError.ERROR_UNKNOWN_SOCKET_TYPE ->
                        GeckoError.ErrorType.ERROR_UNKNOWN_SOCKET_TYPE;
                case WebRequestError.ERROR_UNKNOWN_PROXY_HOST ->
                        GeckoError.ErrorType.ERROR_UNKNOWN_PROXY_HOST;
                case WebRequestError.ERROR_MALFORMED_URI ->
                        GeckoError.ErrorType.ERROR_MALFORMED_URI;
                case WebRequestError.ERROR_REDIRECT_LOOP ->
                        GeckoError.ErrorType.ERROR_REDIRECT_LOOP;
                case WebRequestError.ERROR_SAFEBROWSING_PHISHING_URI ->
                        GeckoError.ErrorType.ERROR_SAFEBROWSING_PHISHING_URI;
                case WebRequestError.ERROR_SAFEBROWSING_MALWARE_URI ->
                        GeckoError.ErrorType.ERROR_SAFEBROWSING_MALWARE_URI;
                case WebRequestError.ERROR_SAFEBROWSING_UNWANTED_URI ->
                        GeckoError.ErrorType.ERROR_SAFEBROWSING_UNWANTED_URI;
                case WebRequestError.ERROR_SAFEBROWSING_HARMFUL_URI ->
                        GeckoError.ErrorType.ERROR_SAFEBROWSING_HARMFUL_URI;
                case WebRequestError.ERROR_CONTENT_CRASHED ->
                        GeckoError.ErrorType.ERROR_CONTENT_CRASHED;
                case WebRequestError.ERROR_OFFLINE -> GeckoError.ErrorType.ERROR_OFFLINE;
                case WebRequestError.ERROR_PORT_BLOCKED -> GeckoError.ErrorType.ERROR_PORT_BLOCKED;
                case WebRequestError.ERROR_PROXY_CONNECTION_REFUSED ->
                        GeckoError.ErrorType.ERROR_PROXY_CONNECTION_REFUSED;
                case WebRequestError.ERROR_FILE_NOT_FOUND ->
                        GeckoError.ErrorType.ERROR_FILE_NOT_FOUND;
                case WebRequestError.ERROR_FILE_ACCESS_DENIED ->
                        GeckoError.ErrorType.ERROR_FILE_ACCESS_DENIED;
                case WebRequestError.ERROR_INVALID_CONTENT_ENCODING ->
                        GeckoError.ErrorType.ERROR_INVALID_CONTENT_ENCODING;
                case WebRequestError.ERROR_UNSAFE_CONTENT_TYPE ->
                        GeckoError.ErrorType.ERROR_UNSAFE_CONTENT_TYPE;
                case WebRequestError.ERROR_CORRUPTED_CONTENT ->
                        GeckoError.ErrorType.ERROR_CORRUPTED_CONTENT;
                case WebRequestError.ERROR_HTTPS_ONLY -> GeckoError.ErrorType.ERROR_HTTPS_ONLY;
                case WebRequestError.ERROR_BAD_HSTS_CERT ->
                        GeckoError.ErrorType.ERROR_BAD_HSTS_CERT;
                default -> GeckoError.ErrorType.UNKNOWN;
            };
        }

        @Override
        public GeckoResult<String> onLoadError(
                @NonNull final GeckoSession session, final String uri, final WebRequestError error) {
            if (BuildConfig.DEBUG) {
                Log.d(TAG, "onLoadError category=" + error.category + " error=" + error.code);
            }

            // Mark the tab so onTitleChange doesn't hand our error page's
            // generic headline to the history/bookmark repair — the error page
            // keeps the FAILED url, so that title would be stamped onto the real
            // site's row. Cleared by the next onPageStart. See
            // GeckoState.setShowingErrorPage.
            final GeckoState geckoState = findGeckoState(session);
            if (geckoState != null) {
                geckoState.setShowingErrorPage(true);
            }

            return GeckoResult.fromValue(GeckoError.createUrlEncodedErrorPage(mContext, errorToType(error.code), uri));
        }
    }


}