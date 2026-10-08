package com.solarized.firedown.phone.dialogs;

import android.app.Dialog;
import android.os.Bundle;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.lifecycle.ViewModelProvider;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.snackbar.Snackbar;
import com.solarized.firedown.R;
import com.solarized.firedown.data.FaviconStore;
import com.solarized.firedown.data.models.GeckoStateViewModel;

import org.mozilla.geckoview.StorageController;

import javax.inject.Inject;

import dagger.hilt.android.AndroidEntryPoint;

// Annotated itself, not only through BaseDialogFragment: a subclass's own
// @Inject fields are filled only by its own generated injector (the Hilt rule
// in CLAUDE.md — DownloadFragment shipped an NPE this way).
@AndroidEntryPoint
public class DeleteBrowsingDialogFragment extends BaseDialogFragment {

    private GeckoStateViewModel mGeckoStateViewModel;

    @Inject
    FaviconStore mFaviconStore;

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        mGeckoStateViewModel = new ViewModelProvider(this).get(GeckoStateViewModel.class);
    }

    @NonNull
    @Override
    public Dialog onCreateDialog(@Nullable Bundle savedInstanceState) {

        int themeResId = mIsIncognito
                ? R.style.Theme_FireDown_VaultDialogTheme
                : getTheme(); // or just use the default

        return new MaterialAlertDialogBuilder(requireContext(), themeResId)
                .setMessage(getString(R.string.delete_all_browsing))
                .setTitle(getString(R.string.delete_browsing))
                .setPositiveButton(getString(R.string.delete), (dialog, which) -> {
                    mGeckoRuntimeHelper.getGeckoRuntime().getStorageController().clearData(StorageController.ClearFlags.ALL);
                    // Also wipe Gecko's persisted content-blocking database (the
                    // cross-session tracker-block stats enabled in GeckoRuntimeHelper).
                    // "Delete browsing data" must clear it too, or a privacy-first app
                    // would leak an on-device history of what was blocked across sessions.
                    // clearTrackingDb() is @HandlerThread = "any thread with a Looper";
                    // this button callback runs on the main thread, which has one.
                    mGeckoRuntimeHelper.getGeckoRuntime().getContentBlockingController().clearTrackingDb();
                    mGeckoStateViewModel.clearStorage();
                    // Firedown's own favicon store is a cache of the sites this
                    // browser visited; "browsing data" includes it.
                    mFaviconStore.clearInBackground();
                    Snackbar snackbar = Snackbar.make(mActivity.getSnackAnchorView(), R.string.browser_cache_cleared, Snackbar.LENGTH_LONG);
                    snackbar.show();
                   dismiss();
                } )
                .setNegativeButton(getString(R.string.cancel), (dialog, which) -> {
                    dismiss();
                } )
                .create();
    }

}
