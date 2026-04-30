package com.solarized.firedown.phone.dialogs;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
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
import com.google.android.material.bottomsheet.BottomSheetDialogFragment;
import com.google.android.material.snackbar.Snackbar;
import com.solarized.firedown.BaseActivity;
import com.solarized.firedown.R;
import com.solarized.firedown.data.entity.BrowserDownloadEntity;
import com.solarized.firedown.manager.DownloadRequest;
import com.solarized.firedown.manager.RunnableManager;
import com.solarized.firedown.phone.DownloadsActivity;
import com.solarized.firedown.IntentActions;
import com.solarized.firedown.Keys;
import com.solarized.firedown.phone.VaultActivity;
import com.solarized.firedown.ui.IncognitoColors;

import java.util.ArrayList;
import java.util.Optional;

import dagger.hilt.android.AndroidEntryPoint;

@AndroidEntryPoint
public class BaseBottomSheetDialogFragment extends BottomSheetDialogFragment {

    private static final String TAG = BaseBottomSheetDialogFragment.class.getName();

    protected BaseActivity mActivity;

    protected View mView;
    protected int mActionBarSize;
    protected boolean mIsIncognito;

    protected NavController mNavController;

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