package com.solarized.firedown.phone.dialogs;


import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.graphics.Rect;
import android.os.Bundle;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;

import androidx.activity.OnBackPressedCallback;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.FragmentManager;
import androidx.lifecycle.Lifecycle;
import androidx.lifecycle.LifecycleEventObserver;
import androidx.lifecycle.ViewModelProvider;
import androidx.navigation.NavBackStackEntry;
import androidx.navigation.NavDestination;
import androidx.preference.PreferenceManager;

import com.google.android.material.bottomsheet.BottomSheetBehavior;
import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.google.android.material.snackbar.Snackbar;
import com.solarized.firedown.BaseActivity;
import com.solarized.firedown.Preferences;
import com.solarized.firedown.R;
import com.solarized.firedown.data.entity.BrowserDownloadEntity;
import com.solarized.firedown.data.entity.OptionEntity;
import com.solarized.firedown.data.models.FragmentsOptionsViewModel;
import com.solarized.firedown.manager.DownloadRequest;
import com.solarized.firedown.IntentActions;
import com.solarized.firedown.Keys;
import com.solarized.firedown.utils.NavigationUtils;

import java.util.ArrayList;



public class BrowserOptionHolderSheetDialogFragment extends BaseBottomSheetDialogFragment {

    private static final String TAG = BrowserOptionHolderSheetDialogFragment.class.getSimpleName();

    private FragmentsOptionsViewModel mFragmentsViewModel;

    private SharedPreferences mSharedPreferences;

    private FrameLayout mFrameHolder;

    private View mDivider;

    private View mAnchorView;

    private int mFrameDecorWidth;

    private int mFrameDecorHeight;



    private final BottomSheetBehavior.BottomSheetCallback mBottomSheetCallback = new BottomSheetBehavior.BottomSheetCallback() {
        @Override
        public void onStateChanged(@NonNull View bottomSheet, int newState) {
            if (newState == BottomSheetBehavior.STATE_EXPANDED) {
                mView.setBackgroundResource(R.drawable.dialog_rectangle);
            } else {
                mView.setBackgroundResource(R.drawable.dialog_rounded_top);
            }
        }

        @Override
        public void onSlide(@NonNull View bottomSheet, float slideOffset) {
        }
    };

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        mSharedPreferences = PreferenceManager.getDefaultSharedPreferences(mActivity);
        mFragmentsViewModel = new ViewModelProvider(mActivity).get(FragmentsOptionsViewModel.class);
    }



    @Override
    public void onConfigurationChanged(@NonNull Configuration newConfig) {
        super.onConfigurationChanged(newConfig);

        boolean isLandscape = newConfig.orientation == Configuration.ORIENTATION_LANDSCAPE;

        FrameLayout.LayoutParams layoutParams = (FrameLayout.LayoutParams) mFrameHolder.getLayoutParams();

        if (isLandscape) {
            layoutParams.height = mFrameDecorWidth - mActionBarSize;
        } else {
            layoutParams.height = mFrameDecorHeight - mActionBarSize;
        }

        mFrameHolder.setLayoutParams(layoutParams);
        mFrameHolder.requestLayout();
    }

    @Override
    public void onStart() {
        super.onStart();
        if (mView != null) {
            BottomSheetBehavior<View> mBottomBehavior = BottomSheetBehavior.from((View) mView.getParent());
            mBottomBehavior.addBottomSheetCallback(mBottomSheetCallback);
            mBottomBehavior.setState(BottomSheetBehavior.STATE_EXPANDED);
            mView.setBackgroundResource(R.drawable.dialog_rectangle);
        }
    }


    @Override
    public void onDestroyView() {
        super.onDestroyView();
        mAnchorView = null;
        mDivider = null;
        mFrameHolder = null;
        mView = null;
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


    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {

        LayoutInflater themedInflater = container != null
                ? LayoutInflater.from(container.getContext())
                : inflater;

        mView = themedInflater.inflate(R.layout.fragment_dialog_browser_options_holder, container, false);

        mAnchorView = mView.findViewById(R.id.anchor_view);
        mDivider = mView.findViewById(R.id.divider);
        mFrameHolder = mView.findViewById(R.id.content_frame);

        FrameLayout.LayoutParams layoutParams = (FrameLayout.LayoutParams) mFrameHolder.getLayoutParams();

        Rect visibleRect = new Rect();
        mActivity.getWindow().getDecorView().getWindowVisibleDisplayFrame(visibleRect);

        mFrameDecorHeight = visibleRect.height();
        mFrameDecorWidth = visibleRect.width();

        layoutParams.height = visibleRect.height() - mActionBarSize;
        mFrameHolder.setLayoutParams(layoutParams);
        mFrameHolder.requestLayout();

        return mView;
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        BrowserOptionFragment browserOptionFragment = new BrowserOptionFragment();

        Bundle childArgs = new Bundle();
        childArgs.putBoolean(Keys.IS_INCOGNITO, mIsIncognito);
        browserOptionFragment.setArguments(childArgs);

        getChildFragmentManager().beginTransaction()
                .replace(R.id.content_frame, browserOptionFragment, BrowserOptionFragment.class.getSimpleName())
                .commit();

        mFragmentsViewModel.getOptionsEvent().observe(getViewLifecycleOwner(), optionEntity -> {
            int id = optionEntity.getId();

            if (id == R.id.item_download_more) {
                // Open variant picker
                Bundle bundle = new Bundle();
                bundle.putBoolean(Keys.IS_INCOGNITO, mIsIncognito);
                bundle.putParcelable(Keys.ITEM_ID, optionEntity.getBrowserDownloadEntity());
                BrowserOptionVariantsFragment variantsFragment = new BrowserOptionVariantsFragment();
                variantsFragment.setArguments(bundle);
                getChildFragmentManager().beginTransaction()
                        .setCustomAnimations(R.anim.slide_in_right, R.anim.slide_out_left, R.anim.slide_in_left, R.anim.slide_out_right)
                        .replace(R.id.content_frame, variantsFragment)
                        .addToBackStack(BrowserOptionVariantsFragment.class.getSimpleName())
                        .commit();

            } else if (id == R.id.cancel_button) {
                getChildFragmentManager().popBackStack();

            } else if (id == R.id.button) {
                // Variant picker "Download" button
                getChildFragmentManager().popBackStack();
                DownloadRequest request = optionEntity.getDownloadRequest();
                if (request != null) {
                    if (mSharedPreferences.getBoolean(Preferences.SETTINGS_SAVE_ASK, Preferences.DEFAULT_SETTINGS_SAVE_ASK)) {
                        // Need to navigate to save dialog — pass entity for display, request for download
                        BrowserDownloadEntity entity = optionEntity.getBrowserDownloadEntity();
                        Bundle bundle = new Bundle();
                        bundle.putParcelable(Keys.ITEM_ID, entity);
                        bundle.putParcelable(Keys.DOWNLOAD_REQUEST, request);
                        NavigationUtils.navigateSafe(mNavController, R.id.dialog_save_file, bundle);
                    } else {
                        startDownload(request, mAnchorView);
                    }
                }

            } else if (id == R.id.empty_button) {
                BrowserOptionHelpFragment helpFragment = new BrowserOptionHelpFragment();
                getChildFragmentManager().beginTransaction()
                        .setCustomAnimations(R.anim.slide_in_right, R.anim.slide_out_left, R.anim.slide_in_left, R.anim.slide_out_right)
                        .replace(R.id.content_frame, helpFragment)
                        .addToBackStack(BrowserOptionHelpFragment.class.getSimpleName())
                        .commit();

            } else if (id == R.id.divider) {
                int visibility = mDivider.getVisibility();
                mDivider.setVisibility(visibility == View.GONE ? View.VISIBLE : View.GONE);

            } else if (id == R.id.start_multiple_download) {
                // Batch download — new path uses DownloadRequests
                ArrayList<DownloadRequest> requests = optionEntity.getDownloadRequests();
                if (requests != null && !requests.isEmpty()) {
                    startDownloads(requests, mAnchorView);
                }

            } else if (id == R.id.start_download) {
                // Direct download — new path uses DownloadRequest
                DownloadRequest request = optionEntity.getDownloadRequest();
                if (request != null) {
                    startDownload(request, mAnchorView);
                }
            }
        });

        ((BottomSheetDialog) requireDialog()).getOnBackPressedDispatcher().addCallback(new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                FragmentManager fragmentManager = getChildFragmentManager();
                if (fragmentManager.getBackStackEntryCount() > 0) {
                    fragmentManager.popBackStack();
                } else if (fragmentManager.findFragmentByTag(BrowserOptionFragment.class.getSimpleName()) != null) {
                    BrowserOptionFragment childFragment = (BrowserOptionFragment) fragmentManager.findFragmentByTag(BrowserOptionFragment.class.getSimpleName());
                    if (childFragment != null && childFragment.isActionMode()) {
                        childFragment.invalidateActionMode();
                    } else {
                        mNavController.popBackStack();
                    }
                } else {
                    mNavController.popBackStack();
                }
            }
        });

        NavDestination currentDestination = mNavController.getCurrentDestination();

        if (currentDestination == null || currentDestination.getId() != R.id.dialog_browser_options)
            return;

        final NavBackStackEntry navBackStackEntry = mNavController.getBackStackEntry(R.id.dialog_browser_options);

        final LifecycleEventObserver observer = (source, event) -> {
            Log.d(TAG, "LifecycleEventObserver: " + event.name());
            if (event.equals(Lifecycle.Event.ON_RESUME)) {
                if (navBackStackEntry.getSavedStateHandle().contains(IntentActions.DOWNLOAD)) {
                    OptionEntity optionEntity = navBackStackEntry.getSavedStateHandle().get(IntentActions.DOWNLOAD);
                    navBackStackEntry.getSavedStateHandle().remove(IntentActions.DOWNLOAD);
                    if (optionEntity != null) {
                        // Try new path first, fall back to legacy
                        DownloadRequest request = optionEntity.getDownloadRequest();
                        if (request != null) {
                            startDownload(request, mAnchorView);
                        }
                    } else {
                        Snackbar snackbar = Snackbar.make(mFrameHolder.getRootView(), R.string.error_general, Snackbar.LENGTH_LONG);
                        snackbar.show();
                    }
                }
            }
        };
        navBackStackEntry.getLifecycle().addObserver(observer);

        getViewLifecycleOwner().getLifecycle().addObserver((LifecycleEventObserver) (source, event) -> {
            if (event.equals(Lifecycle.Event.ON_DESTROY)) {
                navBackStackEntry.getLifecycle().removeObserver(observer);
            }
        });
    }
}