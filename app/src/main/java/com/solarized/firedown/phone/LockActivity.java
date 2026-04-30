package com.solarized.firedown.phone;

import android.app.Activity;
import android.os.Bundle;

import androidx.annotation.NonNull;
import androidx.activity.OnBackPressedCallback;
import androidx.biometric.BiometricPrompt;
import androidx.core.content.ContextCompat;
import androidx.lifecycle.ViewModelProvider;

import com.solarized.firedown.BaseActivity;
import com.solarized.firedown.R;
import com.solarized.firedown.data.models.LockViewModel;

import dagger.hilt.android.AndroidEntryPoint;

@AndroidEntryPoint
public class LockActivity extends BaseActivity {

    private LockViewModel mViewModel;
    private BiometricPrompt mBiometricPrompt;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_lock);

        mViewModel = new ViewModelProvider(this).get(LockViewModel.class);

        // Prevent bypassing lock via back button
        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override public void handleOnBackPressed() { /* Stay locked */ }
        });

        findViewById(R.id.empty_button).setOnClickListener(v -> showBiometricPrompt());

        mViewModel.getAuthenticatedStatus().observe(this, success -> {
            if (success) {
                setResult(Activity.RESULT_OK);
                finish();
            }
        });

        setupBiometricPrompt();

        if (!mViewModel.isBiometricAvailable()) {
            finish();
        } else {
            showBiometricPrompt();
        }
    }

    private void setupBiometricPrompt() {
        mBiometricPrompt = new BiometricPrompt(this, ContextCompat.getMainExecutor(this),
                new BiometricPrompt.AuthenticationCallback() {
                    @Override
                    public void onAuthenticationSucceeded(@NonNull BiometricPrompt.AuthenticationResult result) {
                        mViewModel.onAuthenticationSuccess();
                    }
                });
    }

    private void showBiometricPrompt() {
        BiometricPrompt.PromptInfo promptInfo = new BiometricPrompt.PromptInfo.Builder()
                .setTitle(getString(R.string.app_lock))
                .setSubtitle(getString(R.string.lock_biometric))
                .setAllowedAuthenticators(androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG
                        | androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL)
                .build();
        mBiometricPrompt.authenticate(promptInfo);
    }
}