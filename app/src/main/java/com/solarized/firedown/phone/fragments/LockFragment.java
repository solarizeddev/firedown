package com.solarized.firedown.phone.fragments;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.provider.Settings;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;


import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.widget.AppCompatButton;
import androidx.appcompat.widget.Toolbar;
import androidx.biometric.BiometricManager;
import androidx.biometric.BiometricPrompt;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.lifecycle.Lifecycle;
import androidx.lifecycle.LifecycleEventObserver;
import androidx.navigation.NavBackStackEntry;
import androidx.navigation.NavDestination;

import com.solarized.firedown.R;
import com.solarized.firedown.data.entity.DownloadEntity;
import com.solarized.firedown.IntentActions;
import com.solarized.firedown.utils.BuildUtils;
import com.solarized.firedown.Keys;
import com.solarized.firedown.utils.NavigationUtils;

import java.util.ArrayList;

public class LockFragment extends BaseFocusFragment implements  View.OnClickListener {

    private static final String TAG = LockFragment.class.getName();

    private BiometricPrompt biometricPrompt;

    private AppCompatButton mLockButton;

    private boolean mSupported;


    private final ActivityResultLauncher<Intent> mEnrollForResult = registerForActivityResult(new ActivityResultContracts.StartActivityForResult(),
            result -> NavigationUtils.popBackStackSafe(mNavController, R.id.dialog_enroll));


    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        biometricPrompt = new BiometricPrompt(this, new BiometricPrompt.AuthenticationCallback() {
            @Override
            public void onAuthenticationError(int errorCode,
                                              @NonNull CharSequence errString) {
                super.onAuthenticationError(errorCode, errString);
                Log.e(TAG, "onAuthenticationError: " + errString);
                if(errorCode == BiometricPrompt.ERROR_USER_CANCELED){
                    biometricPrompt.cancelAuthentication();
                }

            }

            @Override
            public void onAuthenticationSucceeded(
                    @NonNull BiometricPrompt.AuthenticationResult result) {
                super.onAuthenticationSucceeded(result);

                Bundle bundle = getArguments();

                if (bundle != null && bundle.containsKey(Keys.ITEM_ID)) {
                    DownloadEntity mDownloadEntity = bundle.getParcelable(Keys.ITEM_ID);
                    Intent resultIntent = new Intent(IntentActions.START_ENCRYPTION);
                    resultIntent.putExtra(Keys.ITEM_ID, mDownloadEntity);
                    mActivity.setResult(mDownloadEntity != null ? Activity.RESULT_OK : Activity.RESULT_CANCELED, resultIntent);
                    mActivity.finish();
                } else if (bundle != null && bundle.containsKey(Keys.ITEM_LIST_ID)) {
                    ArrayList<DownloadEntity> mDownloadEntities = bundle.getParcelableArrayList(Keys.ITEM_LIST_ID);
                    Intent resultIntent = new Intent(IntentActions.START_ENCRYPTION);
                    resultIntent.putParcelableArrayListExtra(Keys.ITEM_LIST_ID, mDownloadEntities);
                    mActivity.setResult(mDownloadEntities != null && !mDownloadEntities.isEmpty() ?
                            Activity.RESULT_OK : Activity.RESULT_CANCELED, resultIntent);
                    mActivity.finish();
                }else{
                    NavigationUtils.navigateSafe(mNavController,R.id.action_lock_to_vault, R.id.lock, getArguments());
                }
            }

            @Override
            public void onAuthenticationFailed() {
                super.onAuthenticationFailed();
            }
        });
    }

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container,
                             Bundle savedInstanceState) {
        // Inflate the layout for this fragment
        View view = inflater.inflate(R.layout.fragment_lock, container, false);

        mLockButton = view.findViewById(R.id.lock_button);

        mLockButton.setOnClickListener(this);

        Toolbar mToolbar = view.findViewById(R.id.toolbar);

        mToolbar.setContentInsetsAbsolute(getResources().getDimensionPixelSize(R.dimen.address_bar_inset), 0);

        mToolbar.setNavigationOnClickListener(v12 -> mActivity.finish());


        ViewCompat.setOnApplyWindowInsetsListener(mToolbar, (v, windowInsets) -> {
            Insets insets = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars());
            // Apply the insets as a margin to the view. This solution sets only the
            // bottom, left, and right dimensions, but you can apply whichever insets are
            // appropriate to your layout. You can also update the view padding if that's
            // more appropriate.
            v.setPadding(insets.left, insets.top, insets.right, 0);

            // Managing statusbar icons colour based on the light/dark mode,
            //I am working on white label solution so this is helping me to set icons colour based on the app theme
            return WindowInsetsCompat.CONSUMED;
        });


        return view;
    }


    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        Log.d(TAG, "onViewCreated");

        // After a configuration change or process death, the currentBackStackEntry
        // points to the dialog destination, so you must use getBackStackEntry()
        // with the specific ID of your destination to ensure we always
        // get the right NavBackStackEntry

        final NavDestination navDestination = mNavController.getCurrentDestination();

        if(navDestination == null || navDestination.getId() != R.id.lock)
            return;

        final NavBackStackEntry navBackStackEntry = mNavController.getBackStackEntry(R.id.lock);

        // Create our observer and add it to the NavBackStackEntry's lifecycle
        final LifecycleEventObserver observer = (source, event) -> {
            Log.d(TAG, "event: " + event.name());
            if (event.equals(Lifecycle.Event.ON_RESUME)) {
                if (navBackStackEntry.getSavedStateHandle().contains(IntentActions.ENROLL)) {
                    navBackStackEntry.getSavedStateHandle().remove(IntentActions.ENROLL);
                    final Intent enrollIntent;
                    if(BuildUtils.hasAndroidR()){
                        enrollIntent = new Intent(Settings.ACTION_BIOMETRIC_ENROLL);
                        enrollIntent.putExtra(Settings.EXTRA_BIOMETRIC_AUTHENTICATORS_ALLOWED,
                                BiometricManager.Authenticators.BIOMETRIC_STRONG | BiometricManager.Authenticators.DEVICE_CREDENTIAL);
                    }else{
                        enrollIntent = new Intent(Settings.ACTION_SECURITY_SETTINGS);
                    }

                    mEnrollForResult.launch(enrollIntent);

                }
            }else if(event.equals(Lifecycle.Event.ON_START)){

                BiometricManager biometricManager = BiometricManager.from(mActivity);

                switch (biometricManager.canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG | BiometricManager.Authenticators.DEVICE_CREDENTIAL)) {
                    case BiometricManager.BIOMETRIC_SUCCESS:
                        mSupported = true;
                        promptUnlock();
                        break;
                    case BiometricManager.BIOMETRIC_ERROR_NO_HARDWARE:
                    case BiometricManager.BIOMETRIC_ERROR_HW_UNAVAILABLE:
                    case BiometricManager.BIOMETRIC_ERROR_UNSUPPORTED:
                    case BiometricManager.BIOMETRIC_STATUS_UNKNOWN:
                    case BiometricManager.BIOMETRIC_ERROR_SECURITY_UPDATE_REQUIRED:
                        mSupported = false;
                        break;
                    case BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED:
                        NavigationUtils.navigateSafe(mNavController,R.id.dialog_enroll);
                        mSupported = false;
                        break;

                }

            }

        };

        navBackStackEntry.getLifecycle().addObserver(observer);

        // As addObserver() does not automatically remove the observer, we
        // call removeObserver() manually when the view lifecycle is destroyed
        getViewLifecycleOwner().getLifecycle().addObserver((LifecycleEventObserver) (source, event) -> {
            if (event.equals(Lifecycle.Event.ON_DESTROY)) {
                navBackStackEntry.getLifecycle().removeObserver(observer);
            }
        });



    }


    @Override
    public void onClick(View v) {
        int id = v.getId();
        if (id == R.id.lock_button) {
            if(mSupported)
                promptUnlock();
            else
                NavigationUtils.navigateSafe(mNavController,R.id.dialog_enroll);
        }

    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        mLockButton = null;
    }

    public void promptUnlock(){
        BiometricPrompt.PromptInfo promptInfo = new BiometricPrompt.PromptInfo.Builder()
                .setTitle(getString(R.string.lock_vault_lock))
                .setSubtitle(getString(R.string.lock_biometric))
                .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG | BiometricManager.Authenticators.DEVICE_CREDENTIAL)
                .setConfirmationRequired(false)
                .build();
        biometricPrompt.authenticate(promptInfo);
    }

}


