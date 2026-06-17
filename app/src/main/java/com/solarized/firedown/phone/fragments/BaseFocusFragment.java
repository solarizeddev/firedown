package com.solarized.firedown.phone.fragments;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.res.Configuration;
import android.graphics.Color;
import android.net.Uri;

import android.os.Bundle;
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.util.Log;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.TextView;

import androidx.activity.result.ActivityResult;
import androidx.activity.result.ActivityResultCallback;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.widget.Toolbar;
import androidx.coordinatorlayout.widget.CoordinatorLayout;
import androidx.core.app.ActivityOptionsCompat;
import androidx.core.app.ShareCompat;
import androidx.core.content.FileProvider;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowInsetsControllerCompat;
import androidx.core.graphics.Insets;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;
import androidx.navigation.NavController;
import androidx.navigation.fragment.NavHostFragment;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.appbar.AppBarLayout;
import com.google.android.material.floatingactionbutton.FloatingActionButton;
import com.google.android.material.snackbar.Snackbar;
import com.solarized.firedown.BaseActivity;
import com.solarized.firedown.data.TaskEvent;
import com.solarized.firedown.data.models.TaskViewModel;
import com.solarized.firedown.geckoview.GeckoState;
import com.solarized.firedown.geckoview.media.GeckoMediaController;
import com.solarized.firedown.geckoview.media.GeckoMediaPlaybackService;
import com.solarized.firedown.manager.tasks.TaskManager;
import com.solarized.firedown.phone.DownloadsActivity;
import com.solarized.firedown.phone.PlayerActivity;
import com.solarized.firedown.R;
import com.solarized.firedown.data.RestoredFileAccess;
import com.solarized.firedown.data.entity.DownloadEntity;
import com.solarized.firedown.manager.DownloadRequest;
import com.solarized.firedown.manager.RunnableManager;
import com.solarized.firedown.phone.VaultActivity;
import com.solarized.firedown.phone.HtmlViewerActivity;
import com.solarized.firedown.ui.IncognitoColors;
import com.solarized.firedown.ui.LCEERecyclerView;
import com.solarized.firedown.ui.SearchMarketListener;
import com.solarized.firedown.geckoview.toolbar.BottomProgressView;
import com.solarized.firedown.IntentActions;
import com.solarized.firedown.utils.FileUriHelper;
import com.solarized.firedown.Keys;

import org.apache.commons.io.FilenameUtils;

import java.io.File;
import java.util.ArrayList;
import java.util.Optional;

import dagger.hilt.android.AndroidEntryPoint;


@AndroidEntryPoint
public class BaseFocusFragment extends Fragment {

    private static final String TAG = BaseFocusFragment.class.getSimpleName();

    protected BaseActivity mActivity;

    protected RecyclerView mRecyclerView;
    protected BottomProgressView mBottomProgressView;
    protected AppBarLayout mAppBarLayout;
    protected FloatingActionButton mFab;

    protected LCEERecyclerView mLCEERecyclerView;
    protected View mNavScrim;

    protected Toolbar mToolbar;

    protected NavController mNavController;

    protected TaskViewModel mTaskViewModel;

    /** In-place Material search bar: a field hosted INSIDE the toolbar
     *  (R.id.search_bar / R.id.search_edit) that takes over the toolbar row
     *  when opened — the Gmail/Contacts pattern. Shared by every list screen
     *  (Downloads, Vault, History, Bookmarks); each routes typing to its own
     *  ViewModel via {@link #onSearchQueryChanged}. Replaces the old
     *  collapsible appcompat SearchView action view. */
    protected View mSearchBar;
    protected EditText mSearchEdit;

    /** Toolbar title captured when search opens, restored when it closes. */
    private CharSequence mTitleBeforeSearch;

    protected boolean mActionModeEnabled;

    protected boolean mOperationActive;

    protected boolean mStop;


    protected final ActivityResultLauncher<Intent> mStartForResult = registerForActivityResult(new ActivityResultContracts.StartActivityForResult(),
            new ActivityResultCallback<>() {
                @Override
                public void onActivityResult(ActivityResult result) {
                    // Defensive: under process recreation the result
                    // dispatch can race with onDetach (which nulls
                    // mActivity). mTaskViewModel can be null in the
                    // same window. Skip if either field has been torn
                    // down — the caller will retry on the next user
                    // interaction.
                    if (mActivity == null) return;
                    if (result.getResultCode() == Activity.RESULT_OK) {
                        Optional<Intent> optionalIntent = Optional.ofNullable(result.getData());
                        optionalIntent.ifPresent(intent -> mActivity.handleIntent(intent));
                    } else if (result.getResultCode() == Activity.RESULT_CANCELED) {
                        if (mTaskViewModel != null) {
                            mTaskViewModel.sendEvent(new TaskEvent.Cancelled());
                        }
                    }
                }
            });


    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);


        mNavScrim = view.findViewById(R.id.navigation_scrim);

        if(mNavScrim != null){
            ViewCompat.setOnApplyWindowInsetsListener(mNavScrim, (vv, insets) -> {

                // Apply this height to the scrim's layout parameters
                int bottomMargin = insets.getInsets(WindowInsetsCompat.Type.systemBars()).bottom;
                vv.getLayoutParams().height = bottomMargin;
                vv.requestLayout();

                Log.d(TAG, "navScrim: " + bottomMargin);

                if(mFab != null){
                    CoordinatorLayout.LayoutParams lp = (CoordinatorLayout.LayoutParams) mFab.getLayoutParams();

                    // 2. Calculate the base offset.
                    // This should be your standard margin (e.g., 16dp) + the height of the navigation bar
                    int baseMargin = getResources().getDimensionPixelSize(R.dimen.fab_margin_scrim);

                    lp.bottomMargin = bottomMargin + baseMargin;
                }

                // Return the original insets to continue dispatching them to other views
                return insets;
            });
        }

        if(mToolbar != null){
            ViewCompat.setOnApplyWindowInsetsListener(mToolbar, (v, windowInsets) -> {
                Insets insets = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars());
                // Apply the insets as a margin to the view. This solution sets only the
                // bottom, left, and right dimensions, but you can apply whichever insets are
                // appropriate to your layout. You can also update the view padding if that's
                // more appropriate.
                v.setPadding(insets.left, insets.top, insets.right, 0);


                return WindowInsetsCompat.CONSUMED;
            });

        }

        tightenToolbarTitle();

        if(mRecyclerView != null){
            ViewCompat.setOnApplyWindowInsetsListener(mRecyclerView, (v, windowInsets) -> {
                Insets insets = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars() |
                        WindowInsetsCompat.Type.displayCutout());
                // Apply the insets as a margin to the view. This solution sets only the
                // bottom, left, and right dimensions, but you can apply whichever insets are
                // appropriate to your layout. You can also update the view padding if that's
                // more appropriate.
                v.setPadding(insets.left, 0, insets.right, insets.bottom);
                return WindowInsetsCompat.CONSUMED;
            });
        }

    }

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        mTaskViewModel = new ViewModelProvider(this).get(TaskViewModel.class);
        mNavController = getNavController();
    }

    @NonNull
    public NavController getNavController() {
        Fragment fragment = mActivity.getSupportFragmentManager().findFragmentById(R.id.content_frame);
        if (!(fragment instanceof NavHostFragment)) {
            throw new IllegalStateException("Activity " + this
                    + " does not have a NavHostFragment");
        }
        return ((NavHostFragment) fragment).getNavController();
    }

    /**
     * Re-tones the navigation scrim. The scrim is a later sibling of the
     * bottom bar, so it paints OVER the bar's nav-inset strip — its XML
     * colorBackground left a black seam under the now-tonal
     * (surfaceContainer) BottomNavigationBar, while the tabs screen (no
     * scrim at all) showed the bar tone through the transparent system
     * nav. Bar-hosting fragments call this beside updateTheme with the
     * matching surfaceContainer; the scrim itself must STAY on the
     * browser — it is the opaque backdrop for the system nav when the
     * bottom bar scroll-hides over page content. Screens without a
     * bottom bar keep the XML colorBackground default.
     */
    protected void setNavScrimColor(int color) {
        if (mNavScrim != null) {
            mNavScrim.setBackgroundColor(color);
        }
    }

    /**
     * Paints the SYSTEM bar strips (status + navigation) for this screen.
     * Two painter layers exist and BOTH must agree:
     * <ul>
     * <li>Android &lt;= 14 honors the window's statusBarColor /
     *     navigationBarColor — an OPAQUE value (the OLED overlay used to
     *     pin both to #000000) paints the strips over anything the app
     *     draws, which is why the tonal chrome showed black strips on
     *     the OLED theme while the normal themes (transparent attrs)
     *     looked right.</li>
     * <li>Android 15+ (targetSdk 35+) IGNORES these setters; the strips
     *     show whatever the app draws beneath them — the decor
     *     background, a self-padded bar, the navigation scrim.</li>
     * </ul>
     * Chrome-owning screens call this on entry with the tone of the
     * chrome ADJACENT to each strip (Home: surface top / surfaceContainer
     * bottom; Browser and Tabs: surfaceContainer both). The theme attrs
     * stay quiet defaults (#000000 under OLED, transparent otherwise) for
     * the cold-start frame and for screens that don't paint chrome
     * (Downloads/Settings ride the window background).
     */
    @SuppressWarnings("deprecation")
    protected void paintSystemBars(int statusColor, int navColor) {
        if (mActivity == null) {
            return;
        }
        Window window = mActivity.getWindow();
        window.setStatusBarColor(statusColor);
        window.setNavigationBarColor(navColor);
    }

    @Override
    public void onAttach(@NonNull Context context) {
        super.onAttach(context);
        if(context instanceof BaseActivity)
            mActivity = (BaseActivity) context;
    }

    @Override
    public void onDetach(){
        super.onDetach();
        mActivity = null;
        mNavController = null;
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        mSearchBar = null;
        mSearchEdit = null;
        if(mRecyclerView != null){
            mRecyclerView = null;
        }
        if(mLCEERecyclerView != null){
            mLCEERecyclerView.destroyCallbacks();
            mLCEERecyclerView = null;
        }
        mFab = null;
        mNavScrim = null;
        mToolbar = null;
    }

    protected void stopMedia(GeckoMediaController geckoMediaController, GeckoState geckoState){

        if(!GeckoMediaPlaybackService.isRunning())
            return;

        // Delegate to the controller. It removes this session from the
        // playing set, hands off to a still-playing fallback if needed,
        // and only fires MEDIA_STOP when nothing is left to play. Sending
        // MEDIA_STOP unconditionally here used to tear down the foreground
        // service whenever any tab closed — stopping unrelated playback in
        // another tab.
        geckoMediaController.stopMediaForSession(geckoState.getEntityId());
    }

    /**
     * Wires the in-place Material search bar (R.id.search_bar, hosted inside
     * the toolbar). Each concrete fragment calls this once its view tree
     * exists. Typing routes to {@link #onSearchQueryChanged}; the trailing
     * clear button only empties the field (it does not exit search — that's
     * the toolbar up-arrow / system Back).
     */
    protected void setupSearchBar(View root) {
        mSearchBar = root.findViewById(R.id.search_bar);
        mSearchEdit = root.findViewById(R.id.search_edit);
        if (mSearchBar == null || mSearchEdit == null) {
            return;
        }
        final View clear = root.findViewById(R.id.search_clear);
        mSearchEdit.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                onSearchQueryChanged(s.toString());
                if (clear != null) {
                    clear.setVisibility(s.length() > 0 ? View.VISIBLE : View.GONE);
                }
            }
            @Override public void afterTextChanged(Editable s) {}
        });
        // Results filter live as you type, so the IME action just dismisses the
        // keyboard rather than running a separate query.
        mSearchEdit.setOnEditorActionListener((v, actionId, event) -> {
            hideKeyboard(mSearchEdit);
            return true;
        });
        if (clear != null) {
            clear.setOnClickListener(v -> mSearchEdit.setText(""));
        }
    }

    /** Reveal the search field; it takes over the toolbar row. */
    protected void openSearchBar() {
        if (mSearchBar == null || mSearchEdit == null) {
            return;
        }
        // Re-entrancy guard: tapping the search icon again must not re-snapshot
        // the (now-hidden) title or re-run the open hook.
        if (mSearchBar.getVisibility() != View.VISIBLE) {
            onSearchBarOpening();
            // Make the field VISIBLE before invalidating the menu: the
            // onCreateMenu re-run reads isSearchActive() to hide every toolbar
            // icon, so the field must already be showing or the search/delete
            // icons stay and the field can't take the full width.
            mSearchBar.setVisibility(View.VISIBLE);
            if (mToolbar != null) {
                mTitleBeforeSearch = mToolbar.getTitle();
                mToolbar.setTitle("");
                mToolbar.invalidateMenu();
            }
        }
        mSearchEdit.requestFocus();
        mSearchEdit.post(() -> showKeyboard(mSearchEdit));
    }

    /** Hide the search field, clear the query, restore the toolbar. */
    protected void closeSearchBar() {
        if (mSearchBar == null || mSearchEdit == null
                || mSearchBar.getVisibility() != View.VISIBLE) {
            return;
        }
        // Emptying the field drives onSearchQueryChanged("") so the list drops
        // back to the unfiltered view.
        mSearchEdit.setText("");
        hideKeyboard(mSearchEdit);
        mSearchBar.setVisibility(View.GONE);
        if (mToolbar != null) {
            mToolbar.setTitle(mTitleBeforeSearch);
            mToolbar.invalidateMenu();
        }
        onSearchBarClosing();
    }

    /** True while the in-place search field is showing. */
    protected boolean isSearchActive() {
        return mSearchBar != null && mSearchBar.getVisibility() == View.VISIBLE;
    }

    /** Route the live query to the screen's ViewModel. No-op by default. */
    protected void onSearchQueryChanged(String query) {}

    /** Hook: search bar opening (e.g. hide a filter chip rail). */
    protected void onSearchBarOpening() {}

    /** Hook: search bar closing (e.g. restore a filter chip rail). */
    protected void onSearchBarClosing() {}

    private void showKeyboard(View view) {
        if (mActivity == null) {
            return;
        }
        InputMethodManager imm = (InputMethodManager) mActivity.getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm != null) {
            imm.showSoftInput(view, InputMethodManager.SHOW_IMPLICIT);
        }
    }

    /**
     * Optically centre the toolbar title on the nav-arrow's line.
     *
     * <p>MaterialToolbar geometrically centres its title, but next to the
     * nav arrow the single-line title reads slightly HIGH (a gap beneath it) —
     * the usual cap-height optical illusion, not a real layout offset. The
     * toolbar doesn't expose its title view, so find it (the direct TextView
     * child whose text is the current title) and nudge it down a couple dp with
     * a pure {@code translationY} — no layout change, exactly tunable. Posted so
     * it runs after the title view is created/laid out; the toolbar reuses the
     * same title view for later setTitle() calls (action mode / search), so the
     * nudge sticks.</p>
     */
    protected void tightenToolbarTitle() {
        if (mToolbar == null) {
            return;
        }
        mToolbar.post(() -> {
            if (mToolbar == null) {
                return;
            }
            CharSequence title = mToolbar.getTitle();
            if (TextUtils.isEmpty(title)) {
                return;
            }
            float nudge = getResources().getDisplayMetrics().density * TITLE_VERTICAL_NUDGE_DP;
            for (int i = 0; i < mToolbar.getChildCount(); i++) {
                View child = mToolbar.getChildAt(i);
                if (child instanceof TextView && !(child instanceof EditText)
                        && TextUtils.equals(title, ((TextView) child).getText())) {
                    child.setTranslationY(nudge);
                    break;
                }
            }
        });
    }

    /** Downward optical nudge for the toolbar title (see tightenToolbarTitle). */
    private static final float TITLE_VERTICAL_NUDGE_DP = 2f;

    public ActivityResultLauncher<Intent> getActivityResultLauncher(){
        return mStartForResult;
    }


    protected View getSnackAnchorView(){
        if(mActivity != null){
            return mActivity.getSnackAnchorView();
        }
        return null;
    }


    public void setRequestedOrientation(int orientation){
        if(mActivity != null){
            mActivity.setRequestedOrientation(orientation);
        }
    }

    public void setActionModeTitle(int size){
        String text = String.format(getString(R.string.action_mode_selected), size);
        mToolbar.setTitle(text);
    }

    public void hideKeyboard(View view) {
        InputMethodManager imm = (InputMethodManager) mActivity.getSystemService(Activity.INPUT_METHOD_SERVICE);
        //Find the currently focused view, so we can grab the correct window token from it.
        //If no view currently has focus, create a new one, just so we can grab a window token from it
        if (view == null) {
            view = new View(mActivity);
        }
        imm.hideSoftInputFromWindow(view.getWindowToken(), 0);
    }

    /**
     * Resets window-level theming back to normal (non-incognito).
     *
     * <p>Resets the decor view background, status/nav bar colors, and
     * light bar icon appearance. Must be called by any fragment that
     * could be shown after an incognito context (HomeFragment,
     * BrowserFragment) because the window state persists across
     * fragment transactions.</p>
     *
     * <p>Idempotent — safe to call when already in normal mode.</p>
     */
    protected void resetWindowTheme() {
        applyWindowIncognitoTheme(false);
    }

    /**
     * Window-level incognito theming for fragments that don't own a
     * full toolbar/bottom-nav repaint cycle of their own (the bookmark
     * + history list surfaces). Paints the decor view, status bar and
     * nav bar to match the requested mode and forces light bars off
     * under incognito so the dark incognito surface reads correctly.
     *
     * <p>FLAG_SECURE is only cleared in non-incognito mode (and only
     * when AppLock isn't holding it) — the incognito-side fragments
     * leave FLAG_SECURE up so the system thumbnail of an incognito
     * surface stays blanked.</p>
     */
    protected void applyWindowIncognitoTheme(boolean incognito) {
        if (mActivity == null) return;

        int nightMode = getResources().getConfiguration().uiMode
                & Configuration.UI_MODE_NIGHT_MASK;
        boolean lightBars = !incognito && nightMode != Configuration.UI_MODE_NIGHT_YES;

        Window window = mActivity.getWindow();
        window.getDecorView().setBackgroundColor(
                IncognitoColors.getSurface(mActivity, incognito));

        if (incognito) {
            window.addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE);
        }

        WindowInsetsControllerCompat insetsController =
                WindowCompat.getInsetsController(window, window.getDecorView());
        insetsController.setAppearanceLightStatusBars(lightBars);
        insetsController.setAppearanceLightNavigationBars(lightBars);
    }

    /**
     * Clears the window's status-bar color so the edge-to-edge AppBarLayout
     * paints (and tracks) the strip itself.
     *
     * <p>Root cause this fixes: the History/Bookmark list screens never set
     * their own status-bar color — they only call {@link
     * #applyWindowIncognitoTheme(boolean)}, which paints the decor background
     * and light-bar appearance but leaves {@code window.setStatusBarColor}
     * untouched. So they INHERIT whatever opaque chrome tone the previously
     * shown chrome-owning screen (Home/Browser via {@link
     * #paintSystemBars(int, int)}) left on the window. On Android ≤14 that
     * persisted opaque strip sits ON TOP of the app-drawn chrome, so when the
     * {@code liftOnScroll} AppBarLayout elevates to its tonal surface on
     * scroll, the status strip stays the stale inherited color instead of
     * tracking the lifted toolbar. (Android 15+ ignores
     * {@code setStatusBarColor} entirely and already shows what's drawn
     * beneath, so it was never affected.) Downloads/Vault don't hit this
     * because their activity leaves the status bar transparent.</p>
     *
     * <p>Making the strip transparent lets the AppBarLayout — which draws
     * under the status bar in edge-to-edge — own that region, so it matches
     * the toolbar at rest AND while lifted. The navigation bar is left
     * untouched (only the status strip overlaps the AppBarLayout).</p>
     */
    @SuppressWarnings("deprecation")
    protected void clearStatusBarForLift() {
        if (mActivity == null) return;
        mActivity.getWindow().setStatusBarColor(Color.TRANSPARENT);
    }



    // ========================================================================
    // Download dispatch — new path (DownloadRequest)
    // ========================================================================

    protected void startDownload(DownloadRequest request, View anchorView, int anchorId) {
        if (mActivity == null || request == null) return;

        Intent intent = new Intent(mActivity, RunnableManager.class);
        intent.setAction(IntentActions.DOWNLOAD_START);
        intent.putExtra(Keys.DOWNLOAD_REQUEST, request);
        mActivity.startService(intent);

        boolean saveToVault = request.isSaveToVault();

        Snackbar snackbar = makeSnackbar(anchorView, R.string.downloading, saveToVault);
        snackbar.setAction(R.string.file_view, view -> {
            Intent downloadsIntent = new Intent(mActivity, saveToVault ? VaultActivity.class :
                    DownloadsActivity.class);
            mStartForResult.launch(downloadsIntent);
        });
        snackbar.setAnchorView(anchorId);
        snackbar.show();
    }

    protected void startDownload(DownloadRequest request, View anchorView) {
        if (mActivity == null || request == null) return;

        Intent intent = new Intent(mActivity, RunnableManager.class);
        intent.setAction(IntentActions.DOWNLOAD_START);
        intent.putExtra(Keys.DOWNLOAD_REQUEST, request);
        mActivity.startService(intent);

        boolean saveToVault = request.isSaveToVault();

        Snackbar snackbar = makeSnackbar(anchorView,  R.string.downloading, saveToVault);
        snackbar.setAction(R.string.file_view, view -> {
            Intent downloadsIntent = new Intent(mActivity, saveToVault ? VaultActivity.class :
                    DownloadsActivity.class);
            mStartForResult.launch(downloadsIntent);
        });
        snackbar.show();
    }



    protected Snackbar makeSnackbar(View anchor, String text, boolean incognito) {
        Snackbar snackbar = Snackbar.make(anchor, text, Snackbar.LENGTH_LONG);
        if (incognito) {
            snackbar.setBackgroundTint(IncognitoColors.getSurfaceContainerHigh(mActivity, incognito));
            snackbar.setTextColor(IncognitoColors.getOnSurface(mActivity, incognito));
            snackbar.setActionTextColor(IncognitoColors.getPrimary(mActivity, incognito));
        }
        return snackbar;
    }

    protected Snackbar makeSnackbar(View anchor, int textResId, boolean incognito) {
        return makeSnackbar(anchor, getString(textResId), incognito);
    }


    protected void openSourceUrl(DownloadEntity downloadEntity) {
        if (downloadEntity == null) {
            Snackbar snackbar = Snackbar.make(mActivity.getSnackAnchorView(), R.string.error_general, Snackbar.LENGTH_LONG);
            snackbar.show();
            return;
        }
        String url = !TextUtils.isEmpty(downloadEntity.getOriginUrl()) ? downloadEntity.getOriginUrl() : downloadEntity.getFileUrl();
        Intent resultIntent = new Intent(IntentActions.OPEN_URI);
        resultIntent.putExtra(Keys.ITEM_URL, url);
        mActivity.setResult(Activity.RESULT_OK, resultIntent);
        mActivity.finish();
    }

    /**
     * The URI to hand a viewer/ACTION_VIEW for a finished download. A
     * {@code FileProvider} URI for an owned file (as before); but a
     * foreign-owned RESTORED file can't be served by FileProvider (the app
     * can't read it), so resolve it to the persisted SAF {@code content://}
     * grant instead — readable in-process and forwardable to another app with
     * {@code FLAG_GRANT_READ_URI_PERMISSION} (which the external-open intents
     * already set).
     */
    private Uri viewerUri(String filePath, File file) {
        Uri openable = RestoredFileAccess.openableUri(mActivity, filePath);
        if (openable != null && "content".equals(openable.getScheme())) {
            return openable;
        }
        return FileProvider.getUriForFile(
                mActivity, mActivity.getPackageName() + ".fileprovider", file);
    }

    protected void openItemWith(DownloadEntity downloadEntity){
        if(downloadEntity == null){
            Snackbar snackbar = Snackbar.make(mActivity.getSnackAnchorView(),  R.string.error_general, Snackbar.LENGTH_LONG);
            snackbar.show();
            return;
        }
        String filePath = downloadEntity.getFilePath();
        String mimeType = downloadEntity.getFileMimeType();
        try {
            File file = new File(filePath);
            Intent intent = new Intent(Intent.ACTION_VIEW);
            Uri uri = viewerUri(filePath, file);
            intent.setDataAndType(uri, mimeType);
            intent.setFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(intent);
        } catch (ActivityNotFoundException e) {
            Log.w(TAG, "openItem", e);
            String mCurrentFileExtension = FilenameUtils.getExtension(filePath);
            if(TextUtils.isEmpty(mCurrentFileExtension)){
                Snackbar snackbar = Snackbar.make(mActivity.getSnackAnchorView(),  R.string.error_file_type_unknown, Snackbar.LENGTH_LONG);
                snackbar.show();
            }else{
                Snackbar snackbar = Snackbar.make(mActivity.getSnackAnchorView(),  R.string.error_file_type_unknown, Snackbar.LENGTH_LONG);
                snackbar.setAction(R.string.search_store, new SearchMarketListener(mActivity, mCurrentFileExtension));
                snackbar.show();
            }

        }
    }


    protected void openItem(DownloadEntity downloadEntity, RecyclerView.ViewHolder viewHolder){
        if(downloadEntity == null){
            Snackbar snackbar = Snackbar.make(mActivity.getSnackAnchorView(),  R.string.error_general, Snackbar.LENGTH_LONG);
            snackbar.show();
            return;
        }
        String filePath = downloadEntity.getFilePath();
        String mimeType = downloadEntity.getFileMimeType();
        try {
            File file = new File(filePath);
            Intent intent = new Intent(Intent.ACTION_VIEW);
            Uri uri = viewerUri(filePath, file);
            if(FileUriHelper.isSVG(mimeType) || FileUriHelper.isImage(mimeType)){
                if(viewHolder != null){
                    View image = viewHolder.itemView.findViewById(R.id.image);
                    if(image != null){
                        ActivityOptionsCompat options = ActivityOptionsCompat
                                .makeSceneTransitionAnimation(mActivity, image, "image_view");
                        startPlayerActivity(downloadEntity, options);
                    }else{
                        startPlayerActivity(downloadEntity);
                    }
                }else{
                    startPlayerActivity(downloadEntity);
                }
            } else if (FileUriHelper.isVideo(mimeType) || FileUriHelper.isAudio(mimeType)) {
                if(viewHolder != null){
                    View image = viewHolder.itemView.findViewById(R.id.image);
                    if(image != null){
                        ActivityOptionsCompat options = ActivityOptionsCompat
                                .makeSceneTransitionAnimation(mActivity, image, "video_view");
                        startPlayerActivity(downloadEntity, options);
                    }else{
                        startPlayerActivity(downloadEntity);
                    }
                }else{
                    startPlayerActivity(downloadEntity);
                }
            } else if(FileUriHelper.isText(mimeType) || FileUriHelper.isSubtitle(mimeType)){
                Intent textIntent = new Intent(getContext(), HtmlViewerActivity.class);
                textIntent.setDataAndType(uri, FileUriHelper.MIMETYPE_TXT);
                startActivity(textIntent);
            } else {
                intent.setDataAndType(uri, mimeType);
                intent.setFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                startActivity(intent);
            }
        } catch (ActivityNotFoundException | IllegalArgumentException e) {
            Log.w(TAG, "openItem", e);
            String mCurrentFileExtension = FilenameUtils.getExtension(filePath);
            if(TextUtils.isEmpty(mCurrentFileExtension)){
                Snackbar snackbar = Snackbar.make(mActivity.getSnackAnchorView(),  R.string.error_file_type_unknown, Snackbar.LENGTH_LONG);
                snackbar.show();
            }else{
                Snackbar snackbar = Snackbar.make(mActivity.getSnackAnchorView(),  R.string.error_file_type_unknown, Snackbar.LENGTH_LONG);
                snackbar.setAction(R.string.search_store, new SearchMarketListener(mActivity, mCurrentFileExtension));
                snackbar.show();
            }

        }
    }

    protected void startPlayerActivity(DownloadEntity downloadEntity){
        Intent playIntent = new Intent(getContext(), PlayerActivity.class);
        playIntent.putExtra(Keys.ITEM_ID, downloadEntity);
        startActivity(playIntent);
    }

    protected void startPlayerActivity(DownloadEntity downloadEntity, ActivityOptionsCompat options){
        Intent playIntent = new Intent(getContext(), PlayerActivity.class);
        playIntent.putExtra(Keys.ITEM_ID, downloadEntity);
        startActivity(playIntent, options.toBundle());
    }

    protected void shareItem(DownloadEntity downloadEntity){
        if(downloadEntity == null){
            Snackbar snackbar = Snackbar.make(mActivity.getSnackAnchorView(),  R.string.error_general, Snackbar.LENGTH_LONG);
            snackbar.show();
            return;
        }
        String mimeType = downloadEntity.getFileMimeType();
        String filePath = downloadEntity.getFilePath();
        if(filePath != null){
            File file = new File(filePath);
            Uri uri = viewerUri(filePath, file);
            Intent intent = new ShareCompat.IntentBuilder(mActivity)
                    .setType(mimeType)
                    .setChooserTitle(getString(R.string.share))
                    .setText(FilenameUtils.getName(filePath))
                    .setStream(uri)
                    .createChooserIntent();
            mActivity.startActivity(intent);
        }else{
            Snackbar snackbar = Snackbar.make(mActivity.getSnackAnchorView(),  R.string.error_unknown, Snackbar.LENGTH_LONG);
            snackbar.show();
        }
    }


    protected void shareItems(ArrayList<String> filesToSend){
        Intent intent = new Intent();
        intent.setAction(Intent.ACTION_SEND_MULTIPLE);
        intent.setType("*/*");
        ArrayList<Uri> files = new ArrayList<>();
        for(String path : filesToSend /* List of the files you want to send */) {
            File file = new File(path);
            Uri uri = viewerUri(path, file);
            files.add(uri);
        }
        intent.putParcelableArrayListExtra(Intent.EXTRA_STREAM, files);
        startActivity(intent);
    }

    protected void handleListItemsAction(String action, ArrayList<DownloadEntity> mDownloadEntities){
        if(mDownloadEntities == null || mActivity == null){
            Log.w(TAG, "handleItemAction NUlL");
            return;
        }
        Intent intent;
        switch(action) {
            case IntentActions.LOCK_FOR_ENCRYPTION:
                intent = new Intent(mActivity, VaultActivity.class);
                intent.putExtra(Keys.ITEM_LIST_ID, mDownloadEntities);
                intent.setAction(action);
                mStartForResult.launch(intent);
                break;
            case IntentActions.START_DECRYPTION:
            case IntentActions.DOWNLOAD_START_COMPRESS:
                intent = new Intent(mActivity, TaskManager.class);
                intent.putExtra(Keys.ITEM_LIST_ID, mDownloadEntities);
                intent.setAction(action);
                mActivity.startService(intent);
                break;
        }
    }



    protected void handleItemAction(String action, DownloadEntity entity) {

        if (mActivity == null) {
            Log.w(TAG, "handleItemAction Fragment not attached to Activity");
            return;
        }

        ArrayList<DownloadEntity> mDownloadEntities;

        Intent intent;

        switch(action){
            case IntentActions.LOCK_FOR_ENCRYPTION:
                intent = new Intent(mActivity, VaultActivity.class);
                mDownloadEntities = new ArrayList<>();
                mDownloadEntities.add(entity);
                intent.putExtra(Keys.ITEM_LIST_ID, mDownloadEntities);
                intent.setAction(action);
                mStartForResult.launch(intent);
                break;
            case IntentActions.DOWNLOAD_START_AUDIO_ENCODE:
            case IntentActions.DOWNLOAD_START_MAKE_GIF:
            case IntentActions.DOWNLOAD_START_COMPRESS:
            case IntentActions.DOWNLOAD_START_EXTRACT:
            case IntentActions.START_DECRYPTION:
                intent = new Intent(mActivity, TaskManager.class);
                mDownloadEntities = new ArrayList<>();
                mDownloadEntities.add(entity);
                intent.putExtra(Keys.ITEM_LIST_ID, mDownloadEntities);
                intent.setAction(action);
                mActivity.startService(intent);
                break;
            case IntentActions.DOWNLOAD_CANCEL_AUDIO_ENCODE:
            case IntentActions.DOWNLOAD_CANCEL_MAKE_GIF:
            case IntentActions.DOWNLOAD_CANCEL_COMPRESS:
            case IntentActions.DOWNLOAD_CANCEL_EXTRACT:
            case IntentActions.CANCEL_ENCRYPTION:
            case IntentActions.CANCEL_DECRYPTION:
                intent = new Intent(mActivity, TaskManager.class);
                intent.setAction(action);
                mActivity.startService(intent);
                break;
            default:
                intent = new Intent(mActivity, RunnableManager.class);
                mDownloadEntities = new ArrayList<>();
                mDownloadEntities.add(entity);
                intent.putExtra(Keys.ITEM_LIST_ID, mDownloadEntities);
                intent.setAction(action);
                mActivity.startService(intent);
                break;
        }
    }



}