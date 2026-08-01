package com.solarized.firedown.settings;


import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.Observer;
import androidx.preference.Preference;
import androidx.work.WorkInfo;
import androidx.work.WorkManager;

import com.google.android.material.snackbar.Snackbar;
import com.solarized.firedown.App;
import com.solarized.firedown.Preferences;
import com.solarized.firedown.R;
import com.solarized.firedown.IntentActions;
import com.solarized.firedown.Keys;
import com.solarized.firedown.UpdateScheduler;
import com.solarized.firedown.UpdateWorker;
import com.solarized.firedown.utils.NavigationUtils;

import java.util.UUID;

import javax.inject.Inject;

import dagger.hilt.android.AndroidEntryPoint;


@AndroidEntryPoint
public class AboutFragment extends BasePreferenceFragment implements Preference.OnPreferenceClickListener {

    private static final String TAG = AboutFragment.class.getName();

    @Inject
    UpdateScheduler mUpdateScheduler;

    /** Guards against stacking a second observer on a double-tap. */
    private boolean mUpdateCheckRunning;


    @Override
    public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
        super.onCreatePreferences(savedInstanceState, rootKey);

        setPreferencesFromResource(R.xml.settings_about, rootKey);

        Preference versionPreference = getPreferenceManager().findPreference(Preferences.SETTINGS_VERSION);

        if(versionPreference != null) {
            versionPreference.setSummary(App.getVersionName());
            versionPreference.setOnPreferenceClickListener(this);
        }

        Preference geckoVersion = getPreferenceManager().findPreference(Preferences.SETTINGS_GECKO);

        if(geckoVersion != null){
            geckoVersion.setSummary(String.format("Build #%s", org.mozilla.geckoview.BuildConfig.MOZ_APP_VERSION + "-" + org.mozilla.geckoview.BuildConfig.MOZ_APP_BUILDID));
            geckoVersion.setOnPreferenceClickListener(this);
        }

        Preference updateCheckPreference = getPreferenceManager().findPreference(Preferences.SETTINGS_UPDATE_CHECK);

        if(updateCheckPreference != null) {
            updateCheckPreference.setOnPreferenceClickListener(this);
        }

        Preference licensePreference = getPreferenceManager().findPreference(Preferences.SETTINGS_LICENSE);

        if(licensePreference != null) {
            licensePreference.setOnPreferenceClickListener(this);
        }

        Preference contactPreference = getPreferenceManager().findPreference(Preferences.SETTINGS_CONTACT);

        if(contactPreference != null) {
            contactPreference.setOnPreferenceClickListener(this);
        }

        Preference websitePreference = getPreferenceManager().findPreference(Preferences.SETTINGS_WEBSITE);

        if(websitePreference != null){
            websitePreference.setOnPreferenceClickListener(this);
        }

    }



    @Override
    public boolean onPreferenceClick(Preference preference) {
        if(preference.getKey().equals(Preferences.SETTINGS_VERSION)){
            ClipboardManager clipboard = (ClipboardManager) mActivity.getSystemService(Context.CLIPBOARD_SERVICE);
            ClipData clip = ClipData.newPlainText("version", String.valueOf(App.getVersionCode()));
            if(clipboard != null){
                clipboard.setPrimaryClip(clip);
            }
            Snackbar snackbar = Snackbar.make(mActivity.getSnackAnchorView(), R.string.clipboard, Snackbar.LENGTH_LONG);
            snackbar.show();
        } else if (preference.getKey().equals(Preferences.SETTINGS_UPDATE_CHECK)) {
            runManualUpdateCheck();
        } else if (preference.getKey().equals(Preferences.SETTINGS_LICENSE)) {
            NavigationUtils.navigateSafe(mNavController, R.id.action_about_to_license);
        } else if (preference.getKey().equals(Preferences.SETTINGS_GECKO)) {
            Intent resultIntent = new Intent(IntentActions.OPEN_URI);
            String uri = getString(R.string.settings_mozilla_geckoview);
            resultIntent.putExtra(Keys.ITEM_URL, uri);
            mActivity.setResult(Activity.RESULT_OK, resultIntent);
            mActivity.finish();
        }

        return false;
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        // The result observer is bound to the view lifecycle and died with the
        // view — reset the guard so the row works on a recreated view (e.g.
        // returning from the Licenses screen). The in-flight check itself
        // keeps running; a found update still lands via the notification
        // pipeline, only the snackbar is dropped.
        mUpdateCheckRunning = false;
    }

    /**
     * User-initiated update check. Enqueues a fresh {@link UpdateWorker} run
     * and reports the outcome as a snackbar — the one thing the background
     * pipeline never reports is "no update", which is exactly what a user
     * tapping this row wants to hear. A found update hands off to the
     * existing download → verify → install-prompt pipeline unchanged.
     *
     * <p>The work is observed BY ID (not by unique-work name — that LiveData
     * replays the previous check's terminal state first), on the view
     * lifecycle owner so a torn-down screen just drops the result. A
     * {@code Result.retry()} (every endpoint failed) is surfaced as a
     * failure immediately and the retry is cancelled — leaving the user
     * silently waiting through WorkManager's backoff reads as a dead button;
     * the periodic background job keeps checking regardless.</p>
     */
    private void runManualUpdateCheck() {
        if (mUpdateCheckRunning) {
            return;
        }
        mUpdateCheckRunning = true;

        Snackbar.make(mActivity.getSnackAnchorView(), R.string.update_check_running,
                Snackbar.LENGTH_SHORT).show();

        UUID workId = mUpdateScheduler.enqueueManualCheck();
        WorkManager workManager = WorkManager.getInstance(requireContext());
        LiveData<WorkInfo> liveData = workManager.getWorkInfoByIdLiveData(workId);

        liveData.observe(getViewLifecycleOwner(), new Observer<WorkInfo>() {
            @Override
            public void onChanged(WorkInfo info) {
                if (info == null) {
                    return;
                }

                String message = null;

                if (info.getState() == WorkInfo.State.SUCCEEDED) {
                    boolean available = info.getOutputData()
                            .getBoolean(UpdateWorker.KEY_UPDATE_AVAILABLE, false);
                    String versionName = info.getOutputData()
                            .getString(UpdateWorker.KEY_LATEST_VERSION_NAME);
                    if (available && versionName != null) {
                        message = getString(R.string.update_check_found, versionName);
                    } else {
                        message = getString(R.string.update_check_up_to_date);
                    }
                } else if (info.getState() == WorkInfo.State.ENQUEUED
                        && info.getRunAttemptCount() > 0) {
                    // Re-enqueued = Result.retry() landed. Report + cancel.
                    workManager.cancelWorkById(workId);
                    message = getString(R.string.update_check_failed);
                } else if (info.getState() == WorkInfo.State.FAILED
                        || info.getState() == WorkInfo.State.CANCELLED) {
                    message = getString(R.string.update_check_failed);
                }

                if (message != null) {
                    liveData.removeObserver(this);
                    mUpdateCheckRunning = false;
                    if (mActivity != null) {
                        Snackbar.make(mActivity.getSnackAnchorView(), message,
                                Snackbar.LENGTH_LONG).show();
                    }
                }
            }
        });
    }

}