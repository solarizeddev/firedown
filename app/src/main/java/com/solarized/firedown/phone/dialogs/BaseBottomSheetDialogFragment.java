package com.solarized.firedown.phone.dialogs;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.res.Configuration;
import android.os.Bundle;
import android.view.View;
import androidx.activity.result.ActivityResult;
import androidx.activity.result.ActivityResultCallback;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.fragment.app.Fragment;
import androidx.navigation.NavController;
import androidx.navigation.fragment.NavHostFragment;
import com.google.android.material.bottomsheet.BottomSheetBehavior;
import com.google.android.material.bottomsheet.BottomSheetDialogFragment;
import com.google.android.material.snackbar.Snackbar;
import com.solarized.firedown.BaseActivity;
import com.solarized.firedown.R;
import com.solarized.firedown.geckoview.GeckoRuntimeHelper;
import com.solarized.firedown.manager.DownloadRequest;
import com.solarized.firedown.manager.RunnableManager;
import com.solarized.firedown.phone.DownloadsActivity;
import com.solarized.firedown.IntentActions;
import com.solarized.firedown.Keys;
import com.solarized.firedown.phone.VaultActivity;
import com.solarized.firedown.ui.IncognitoColors;

import java.util.ArrayList;
import java.util.Optional;

import javax.inject.Inject;

import dagger.hilt.android.AndroidEntryPoint;

@AndroidEntryPoint
public class BaseBottomSheetDialogFragment extends BottomSheetDialogFragment {

    private static final String TAG = BaseBottomSheetDialogFragment.class.getName();
    protected BaseActivity mActivity;
    protected View mView;
    protected int mActionBarSize;
    protected boolean mIsIncognito;
    protected NavController mNavController;

    @Inject
    protected GeckoRuntimeHelper mGeckoRuntimeHelper;

    protected final ActivityResultLauncher<Intent> mStartForResult = registerForActivityResult(new ActivityResultContracts.StartActivityForResult(),
            new ActivityResultCallback<>() {
                @Override
                public void onActivityResult(ActivityResult result) {
                    if (result.getResultCode() == Activity.RESULT_OK) {
                        Optional<Intent> optionalIntent = Optional.ofNullable(result.getData());
                        optionalIntent.ifPresent(intent -> mActivity.handleIntent(intent));
                    }
                }
            });


    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        ViewCompat.setOnApplyWindowInsetsListener(mView, (v, windowInsets) -> {
            Insets insets = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars() |
                    WindowInsetsCompat.Type.displayCutout());
            v.setPadding(insets.left, 0, insets.right, insets.bottom);
            return WindowInsetsCompat.CONSUMED;
        });
    }


    @Override
    public void onStart() {
        super.onStart();
        applyBottomSheetWidth();
        applyBottomSheetMaxHeight();
        if (mView == null) return;
        BottomSheetBehavior<View> behavior = BottomSheetBehavior.from((View) mView.getParent());

        // Skip the half-collapsed state on drag-down (user lands directly on
        // dismissed) and open fully on first show. Without this, in landscape
        // the sheet appears at its peek height — a tiny strip at the bottom
        // of an already-short viewport that's hard to even see.
        behavior.setSkipCollapsed(true);
        behavior.setState(BottomSheetBehavior.STATE_EXPANDED);
    }


    @Override
    public void onConfigurationChanged(@NonNull Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        // Activities are declared android:configChanges="orientation|screenSize|..."
        // in the manifest, so rotation doesn't recreate the activity / fragment
        // and onStart never re-runs. Without this hook, the maxWidth picked up
        // when the sheet first opened (e.g. portrait → no cap) sticks across
        // the rotation and the user sees an edge-to-edge sheet in landscape
        // until they dismiss and reopen. Re-resolve the dimen against the new
        // configuration's resources and re-apply.
        applyBottomSheetWidth();
        applyBottomSheetMaxHeight();
    }


    /**
     * Reads {@code R.dimen.bottom_sheet_max_width} from the current
     * configuration and applies it to the sheet's {@link BottomSheetBehavior}.
     * A value of 0 (the portrait default sentinel) clears the cap by
     * passing {@code -1} (BottomSheetBehavior's NO_MAX_SIZE), which is
     * what we want when transitioning landscape → portrait.
     */
    private void applyBottomSheetWidth() {
        if (mView == null || mView.getParent() == null) return;
        BottomSheetBehavior<View> behavior = BottomSheetBehavior.from((View) mView.getParent());
        int maxWidthPx = getResources().getDimensionPixelSize(R.dimen.bottom_sheet_max_width);
        behavior.setMaxWidth(maxWidthPx > 0 ? maxWidthPx : -1);
        // setMaxWidth doesn't trigger a relayout itself in current Material
        // versions; nudge the parent to remeasure so the new cap takes
        // effect immediately rather than on the next layout pass.
        ((View) mView.getParent()).requestLayout();
    }

    /**
     * Mirror of {@link #applyBottomSheetWidth()} for height. Stops sheets
     * with very long content (e.g. file-info descriptions, blocked-trackers
     * lists) from growing to fill the entire viewport, which would push the
     * drag handle off-screen and make the sheet stop reading as a sheet —
     * past the cap, the sheet's own scrollable content scrolls.
     *
     * <p>Same sentinel as the width path: 0 means "no cap" (used in
     * landscape, where the viewport is already height-constrained and
     * any further cap would feel cramped).</p>
     */
    private void applyBottomSheetMaxHeight() {
        if (mView == null || mView.getParent() == null) return;
        BottomSheetBehavior<View> behavior = BottomSheetBehavior.from((View) mView.getParent());
        int maxHeightPx = getResources().getDimensionPixelSize(R.dimen.bottom_sheet_max_height);
        behavior.setMaxHeight(maxHeightPx > 0 ? maxHeightPx : -1);
        ((View) mView.getParent()).requestLayout();
    }

    @Override
    public int getTheme() {
        return mIsIncognito
                ? R.style.Theme_FireDown_BottomSheetVaultDialogTheme
                : super.getTheme();
    }

    @Override
    public void onAttach(@NonNull Context context) {
        super.onAttach(context);
        if (context instanceof Activity)
            mActivity = (BaseActivity) context;
    }

    @Override
    public void onDetach() {
        super.onDetach();
        mActivity = null;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        mView = null;
        mNavController = null;
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        mIsIncognito = getArguments() != null && getArguments().getBoolean(Keys.IS_INCOGNITO, false);
        mActionBarSize = getResources().getDimensionPixelSize(R.dimen.app_bar_size);
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

    // ========================================================================
    // Download dispatch — new path (DownloadRequest)
    // ========================================================================

    protected void startDownload(DownloadRequest request, View anchorView) {
        if (mActivity == null) return;

        Intent intent = new Intent(mActivity, RunnableManager.class);
        intent.setAction(IntentActions.DOWNLOAD_START);
        intent.putExtra(Keys.DOWNLOAD_REQUEST, request);
        mActivity.startService(intent);

        showDownloadSnackbar(anchorView, request.isSaveToVault());
    }

    protected void startDownloads(ArrayList<DownloadRequest> requests, View anchorView) {
        if (mActivity == null || requests == null || requests.isEmpty()) return;


        Intent intent = new Intent(mActivity, RunnableManager.class);
        intent.setAction(IntentActions.DOWNLOAD_START);
        intent.putParcelableArrayListExtra(Keys.DOWNLOAD_REQUEST_LIST, requests);
        mActivity.startService(intent);

        boolean anyVault = false;
        for (DownloadRequest r : requests) {
            if (r.isSaveToVault()) { anyVault = true; break; }
        }
        showDownloadSnackbar(anchorView, anyVault);
    }


    // ========================================================================
    // Shared snackbar
    // ========================================================================

    private void showDownloadSnackbar(View anchorView) {
        if (anchorView == null) return;

        Snackbar snackbar = Snackbar.make(anchorView.getRootView(), R.string.downloading, Snackbar.LENGTH_LONG);
        snackbar.setAnchorView(anchorView);
        snackbar.setAction(R.string.file_view, view -> {
            Intent downloadsIntent = new Intent(mActivity, DownloadsActivity.class);
            mStartForResult.launch(downloadsIntent);
        });
        snackbar.show();
    }

    protected void showDownloadSnackbar(View anchorView, boolean vault) {
        if (anchorView == null) return;
        if (vault) {
            Snackbar.make(anchorView.getRootView(), R.string.download_saved_to_vault, Snackbar.LENGTH_LONG)
                    .setAnchorView(anchorView)
                    .setTextColor(IncognitoColors.getOnSurface(mActivity, vault))
                    .setBackgroundTint(IncognitoColors.getSurface(mActivity, vault))
                    .setActionTextColor(IncognitoColors.getPrimary(mActivity, vault))
                    .setAction(R.string.open, v -> {
                        Intent safeIntent = new Intent(mActivity, VaultActivity.class);
                        mActivity.startActivity(safeIntent);
                    })
                    .show();
        } else {
            showDownloadSnackbar(anchorView);
        }
    }


}