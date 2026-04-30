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
import android.text.TextUtils;
import android.util.Log;
import android.webkit.URLUtil;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.UiThread;

import com.solarized.firedown.Preferences;
import com.solarized.firedown.R;
import com.solarized.firedown.data.di.Qualifiers;
import com.solarized.firedown.data.entity.CertificateInfoEntity;
import com.solarized.firedown.data.entity.ContextElementEntity;
import com.solarized.firedown.data.entity.GeckoStateEntity;
import com.solarized.firedown.data.entity.WebHistoryEntity;
import com.solarized.firedown.data.repository.GeckoStateDataRepository;
import com.solarized.firedown.data.repository.IncognitoStateRepository;
import com.solarized.firedown.data.repository.WebHistoryDataRepository;
import com.solarized.firedown.geckoview.media.GeckoMediaController;
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
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Executor;

import javax.inject.Inject;
import javax.inject.Singleton;

import dagger.hilt.android.qualifiers.ApplicationContext;


@Singleton
public class GeckoComponents {

    private static final String TAG = GeckoComponents.class.getSimpleName();

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
    private final GeckoMediaController mGeckoMediaController;
    private final GeckoRuntimeHelper mGeckoRuntimeHelper;
    private final Executor mDiskExecutor;
    private final Executor mMainExecutor;
    private final Context mContext;
    private final SharedPreferences mSharedPreferences;


    @Inject
    public GeckoComponents(
            GeckoMediaController geckoMediaController,
            GeckoStateDataRepository geckoStateRepository,
            IncognitoStateRepository incognitoStateRepository,
            WebHistoryDataRepository webHistoryRepository,
            GeckoRuntimeHelper geckoRuntimeHelper,
            GeckoObserverRegistry observerRegistry,
            SharedPreferences sharedPreferences,
            @Qualifiers.DiskIO Executor diskExecutor,
            @Qualifiers.MainThread Executor mainExecutor,
            @ApplicationContext Context context) {
        this.mContext = context;
        this.mSharedPreferences = sharedPreferences;
        this.mGeckoStateDataRepository = geckoStateRepository;
        this.mIncognitoStateRepository = incognitoStateRepository;
        this.mWebHistoryDataRepository = webHistoryRepository;
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
        this.mDiskExecutor = diskExecutor;
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

            if (geckoState == null)
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

            if (geckoState == null)
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

            if (geckoState == null)
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

            if (geckoState == null)
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

            if (geckoState == null)
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

            if (geckoState == null)
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

            if (geckoState == null)
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

            if (geckoState == null) {
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

            if (geckoState == null)
                return GeckoResult.fromValue(prompt.dismiss());

            final GeckoResult<PromptResponse> res = new GeckoResult<>();

            mPromptResponse = res;

            mGeckoObserverRegistry.notifyObservers(GeckoObserverInvoker.PROMPT_ALERT, geckoState, prompt);

            return res;

        }


        @Override
        public GeckoResult<GeckoSession.PromptDelegate.PromptResponse> onFilePrompt(@NonNull GeckoSession session, @NonNull GeckoSession.PromptDelegate.FilePrompt prompt) {
            final GeckoState geckoState = findGeckoState(session);
            if (geckoState == null) {
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
                        int nameIndex = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME);
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

            mDiskExecutor.execute(() -> {
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

            mGeckoObserverRegistry.notifyObservers(GeckoObserverInvoker.HIDE_BARS, geckoState);
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
            final int id = geckoState.getEntityId();

            Log.i(TAG, "Content title changed to " + title + " url: " + url);

            if(UrlStringUtils.isAboutBlank(url) || !URLUtil.isValidUrl(url) || UrlStringUtils.isAboutBlank(title))
                return;

            // Don't save history for incognito tabs
            if (!geckoState.isIncognito()) {
                mWebHistoryDataRepository.updateTitle(id, title);
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

            mGeckoObserverRegistry.notifyObservers(GeckoObserverInvoker.FULL_SCREEN, fullScreen);


        }


        @Override
        public void onCloseRequest(@NonNull final GeckoSession session) {
            Log.i(TAG, "onCloseRequest");
            final GeckoState geckoState = findGeckoState(session);
            if(geckoState == null){
                return;
            }
            if (session == geckoState.getOrCreateGeckoSession()) {
                if (geckoState.getGeckoStateEntity().isIncognito()) {
                    mIncognitoStateRepository.closeGeckoState(geckoState);
                } else {
                    mGeckoStateDataRepository.closeGeckoState(geckoState);
                }
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
            mGeckoObserverRegistry.notifyObservers(GeckoObserverInvoker.CRASH, geckoState);
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

            geckoState.setInitialLoad(false);           // no longer a brand new tab
            geckoState.setFirstContentFulPaint(false);  // reset — new page hasn't painted yet

            if(isCurrentGeckoState(geckoState))
                mGeckoObserverRegistry.notifyObservers(GeckoObserverInvoker.START, geckoState);

        }

        @Override
        public void onPageStop(@NonNull final GeckoSession session, final boolean success) {

            Log.d(TAG, "onStop: " + success);


            final GeckoState geckoState =findGeckoState(session);

            if(geckoState == null || !isCurrentGeckoState(geckoState))
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
                mGeckoObserverRegistry.notifyObservers(GeckoObserverInvoker.PROGRESS, progress);



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
            }

            Log.d(TAG, "onSessionStateChange end");

        }

    }


    public static class ContentBlockingDelegate implements ContentBlocking.Delegate{

        @Override
        public void onContentBlocked(
                @NonNull final GeckoSession session, @NonNull final ContentBlocking.BlockEvent event) {
            Log.d(TAG, "onContentBlocked: " + event.uri);
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

            mGeckoMediaController.onMediaStateChanged(mediaSession, geckoState);

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

            mGeckoMediaController.onMediaStateChanged(mediaSession, geckoState);

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

            mGeckoMediaController.onMediaStateChanged(mediaSession, geckoState);

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

        private final HashSet<String> mVisitedURLs;


        public HistoryDelegate() {
            mVisitedURLs = new HashSet<>();
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

            WebHistoryEntity webHistoryEntity = new WebHistoryEntity();
            webHistoryEntity.setId(WebHistoryDataRepository.generateId(uri));
            webHistoryEntity.setFileDate(System.currentTimeMillis());
            webHistoryEntity.setFileTitle(geckoState.getEntityTitle());
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
                    if(mSharedPreferences.getBoolean(Preferences.SETTINGS_BLOCK_LOCATION, false)){
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

            Log.d(TAG, "OnLocationChange: " + session + " here location: " + url);

            geckoState.setEntityUri(url);

            geckoState.onLocationChange(url);

            geckoState.setInitialLoad(false);

            if(isCurrentGeckoState(geckoState))
                mGeckoObserverRegistry.notifyObservers(GeckoObserverInvoker.LOCATION, geckoState);


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

            if(geckoState == null){
                mGeckoObserverRegistry.notifyObservers(GeckoObserverInvoker.LOAD_REQUEST, geckoState, request.uri);
                return GeckoResult.deny();
            }


            if(TextUtils.isEmpty(request.uri)){
                return GeckoResult.deny();
            }else if(URLUtil.isValidUrl(request.uri)){
                return GeckoResult.allow();
            }else if(UrlStringUtils.isURLDataLike(request.uri) || UrlStringUtils.isURLResouceLike(request.uri)
                    || UrlStringUtils.isViewSource(request.uri) || UrlStringUtils.isMozExtensionLike(request.uri)
                    || UrlStringUtils.isURLFileLike(request.uri)) {
                return GeckoResult.allow();
            }else{
                mGeckoObserverRegistry.notifyObservers(GeckoObserverInvoker.LOAD_REQUEST, geckoState, request.uri);

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
            Log.d(
                    TAG,
                    "onLoadError=" + uri + " error category=" + error.category + " error=" + error.code + "createUrl: " + GeckoError.createUrlEncodedErrorPage(mContext, errorToType(error.code), uri));

            return GeckoResult.fromValue(GeckoError.createUrlEncodedErrorPage(mContext, errorToType(error.code), uri));
        }
    }


}