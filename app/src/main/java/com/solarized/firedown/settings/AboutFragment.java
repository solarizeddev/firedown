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
import com.solarized.firedown.UpdateDownloader;
import com.solarized.firedown.UpdateInstaller;
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
        refreshUpdateRow();

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
            // One row, two jobs — see refreshUpdateRow. With a verified APK
            // already on disk, "check for updates" would re-run a check whose
            // only outcome is the download we already have; install it instead.
            if (UpdateDownloader.isVerifiedReady(requireContext(), App.getVersionCode() + 1)) {
                installReadyUpdate();
            } else {
                runManualUpdateCheck();
            }
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

    @Override
    public void onResume() {
        super.onResume();
        // The ready record can change while this screen sits open (a background
        // check finishing, or the user returning from Android's package
        // installer), so the row is re-derived rather than bound once.
        refreshUpdateRow();
    }

    /**
     * Points the single update row at whichever job is actually useful right
     * now: INSTALL when a verified APK is already on disk, CHECK otherwise.
     *
     * <p>This is the standing "an update is waiting" surface, and it exists
     * because the other two are not durable. The notification is absent
     * outright for a user who denied POST_NOTIFICATIONS — the gap
     * {@link com.solarized.firedown.UpdateAvailableSheet} was built for — and
     * that sheet deliberately stops appearing after a few dismissals so it
     * doesn't nag. Before this, that combination left a verified update on
     * disk with nothing in the app willing to mention it again, and the row
     * here still said "Check for updates" while the check's own answer was
     * sitting downloaded. A user who goes looking for an update looks in
     * About, next to the version number — so it belongs here, and NOT on the
     * home screen: home is deliberately bare, and a standing status widget
     * there is the shape the cloud-backup pill was twice demoted for
     * ("fill is earned by WORK … never by a standing state").
     *
     * <p>Summary is the bare version name, matching the version row above it;
     * no format string, so the state costs exactly one new translation.
     */
    private void refreshUpdateRow() {
        Preference row = getPreferenceManager().findPreference(Preferences.SETTINGS_UPDATE_CHECK);
        if (row == null) {
            return;
        }
        Context context = getContext();
        // isVerifiedReady(installed + 1) ⇒ strictly newer than what's running,
        // with a signature-checked file actually on disk — the same gate the
        // sheet uses, so the two surfaces can never disagree.
        boolean ready = context != null
                && UpdateDownloader.isVerifiedReady(context, App.getVersionCode() + 1);
        if (ready) {
            // markReady stores "" for a manifest with no versionName, and an
            // empty summary renders as a blank second line — fall back to no
            // summary at all rather than a gap under the title.
            String versionName = UpdateDownloader.readyVersionName(context);
            row.setTitle(R.string.settings_update_install_title);
            row.setSummary(versionName == null || versionName.isEmpty() ? null : versionName);
            row.setIcon(R.drawable.ic_download_done_24);
        } else {
            row.setTitle(R.string.settings_update_check_title);
            row.setSummary(null);
            row.setIcon(R.drawable.ic_refresh_24);
        }
    }

    /**
     * Hands the already-downloaded, already-verified APK to Android's package
     * installer. Mirrors {@code UpdateAvailableSheet.onInstall}: the read of
     * the APK into a PackageInstaller session is disk IO, so it runs off the
     * main thread on the APPLICATION context — a worker thread must not
     * outlive and leak this fragment.
     */
    private void installReadyUpdate() {
        Context app = requireContext().getApplicationContext();
        String name = UpdateDownloader.readyVersionName(app);
        new Thread(() -> UpdateInstaller.install(app, name)).start();
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
                    // A check that lands on an already-downloaded update flips
                    // this row to Install without leaving the screen; onResume
                    // can't cover that, the user never left.
                    refreshUpdateRow();
                    if (mActivity != null) {
                        Snackbar.make(mActivity.getSnackAnchorView(), message,
                                Snackbar.LENGTH_LONG).show();
                    }
                }
            }
        });
    }

}