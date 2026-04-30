package com.solarized.firedown.phone.fragments;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.res.Configuration;
import android.net.Uri;

import android.os.Bundle;
import android.text.TextUtils;
import android.util.Log;
import android.view.MenuItem;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.view.inputmethod.InputMethodManager;

import androidx.activity.result.ActivityResult;
import androidx.activity.result.ActivityResultCallback;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.widget.SearchView;
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
import com.solarized.firedown.data.entity.DownloadEntity;
import com.solarized.firedown.data.entity.GeckoStateEntity;
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

    protected MenuItem mSearchItem;

    protected SearchView mSearchView;

    protected boolean mActionModeEnabled;

    protected boolean mOperationActive;

    protected boolean mStop;


    protected final ActivityResultLauncher<Intent> mStartForResult = registerForActivityResult(new ActivityResultContracts.StartActivityForResult(),
            new ActivityResultCallback<>() {
                @Override
                public void onActivityResult(ActivityResult result) {
                    if (result.getResultCode() == Activity.RESULT_OK) {
                        Optional<Intent> optionalIntent = Optional.ofNullable(result.getData());
                        optionalIntent.ifPresent(intent -> mActivity.handleIntent(intent));
                    }else if(result.getResultCode() == Activity.RESULT_CANCELED){
                        mTaskViewModel.sendEvent(new TaskEvent.Cancelled());
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

//        if(mScrollUpView != null){
//            ViewCompat.setOnApplyWindowInsetsListener(mScrollUpView, (v, windowInsets) -> {
//                Insets systemBars = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars());
//
//                // 1. Get the layout params
//                CoordinatorLayout.LayoutParams lp = (CoordinatorLayout.LayoutParams) v.getLayoutParams();
//
//                // 2. Calculate the base offset.
//                // This should be your standard margin (e.g., 16dp) + the height of the navigation bar
//                int baseMargin = getResources().getDimensionPixelSize(R.dimen.fab_margin);
//
//                // 3. Apply the bottom inset.
//                // We add the system navigation bar height to your desired margin.
//                lp.bottomMargin = baseMargin + systemBars.bottom;
//
//                // 4. Handle side insets (important for gesture nav or landscape mode)
//                lp.rightMargin = baseMargin + systemBars.right;
//                lp.leftMargin = baseMargin + systemBars.left;
//
//                v.setLayoutParams(lp);
//
//                // IMPORTANT: Return the insets unconsumed so the 'navigation_scrim'
//                // and 'bottom_progress_view' can also react to them.
//                return windowInsets;
//            });
//        }


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
        if(mSearchView != null){
            mSearchView.setOnQueryTextListener(null);
            mSearchView = null;
        }
        if(mRecyclerView != null){
            mRecyclerView = null;
        }
        if(mLCEERecyclerView != null){
            mLCEERecyclerView.destroyCallbacks();
            mLCEERecyclerView = null;
        }
        mFab = null;
        mNavScrim = null;
        mSearchItem = null;
        mToolbar = null;
    }

    protected void stopMedia(GeckoMediaController geckoMediaController, GeckoState geckoState){

        if(!GeckoMediaPlaybackService.isRunning())
            return;

        Intent intent = new Intent(mActivity, GeckoMediaPlaybackService.class);
        intent.setAction(IntentActions.MEDIA_STOP);
        geckoMediaController.stopMediaForSession(geckoState.getEntityId());

        mActivity.startService(intent);

    }

    protected void closeSearchView(){
        if (mSearchItem != null) {
            mSearchItem.collapseActionView();
        }
        if(mSearchView != null){
            mSearchView.setQuery("", false);
            mSearchView.clearFocus();
            mSearchView.setIconified(true);
        }
    }

    public ActivityResultLauncher<Intent> getActivityResultLauncher(){
        return mStartForResult;
    }

    protected void setSessionResult(GeckoStateEntity entity) {
        Intent intent = new Intent();
        intent.setAction(IntentActions.OPEN_URI);
        intent.putExtra(Keys.ITEM_ID, entity.getId());
        intent.putExtra(Keys.ITEM_IS_HOME, entity.isHome());
        intent.putExtra(Keys.ITEM_URL, entity.getUri());
        mActivity.setResult(Activity.RESULT_OK, intent);
        mActivity.finish();
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
        if (mActivity == null) return;

        int nightMode = getResources().getConfiguration().uiMode
                & Configuration.UI_MODE_NIGHT_MASK;
        boolean lightBars = nightMode != Configuration.UI_MODE_NIGHT_YES;

        Window window = mActivity.getWindow();
        window.getDecorView().setBackgroundColor(
                IncognitoColors.getSurface(mActivity, false));

        window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE);

        WindowInsetsControllerCompat insetsController =
                WindowCompat.getInsetsController(window, window.getDecorView());
        insetsController.setAppearanceLightStatusBars(lightBars);
        insetsController.setAppearanceLightNavigationBars(lightBars);
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
            Uri uri = FileProvider.getUriForFile(mActivity, mActivity.getPackageName() + ".fileprovider", file);
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
            Uri uri = FileProvider.getUriForFile(mActivity, mActivity.getPackageName() + ".fileprovider", file);
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
            Uri uri = FileProvider.getUriForFile(mActivity, mActivity.getPackageName() + ".fileprovider", file);
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
            Uri uri = FileProvider.getUriForFile(mActivity, mActivity.getPackageName() + ".fileprovider", file);
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
            case IntentActions.START_DECRYPTION:
                intent = new Intent(mActivity, TaskManager.class);
                mDownloadEntities = new ArrayList<>();
                mDownloadEntities.add(entity);
                intent.putExtra(Keys.ITEM_LIST_ID, mDownloadEntities);
                intent.setAction(action);
                mActivity.startService(intent);
                break;
            case IntentActions.DOWNLOAD_CANCEL_AUDIO_ENCODE:
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