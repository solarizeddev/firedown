package com.solarized.firedown.settings;


import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.os.Bundle;
import android.text.format.Formatter;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.Observer;
import androidx.preference.Preference;
import androidx.work.WorkInfo;
import androidx.work.WorkManager;

import com.google.android.material.snackbar.Snackbar;
import com.solarized.firedown.App;
import com.solarized.firedown.Preferences;
import com.solarized.firedown.R;
import com.solarized.firedown.UpdateAvailableSheet;
import com.solarized.firedown.UpdateDownloader;
import com.solarized.firedown.UpdateInstallProgress;
import com.solarized.firedown.UpdateInstaller;
import com.solarized.firedown.UpdateScheduler;
import com.solarized.firedown.UpdateWorker;
import com.solarized.firedown.phone.SettingsActivity;
import com.solarized.firedown.settings.ui.BusyPreference;
import com.solarized.firedown.utils.NavigationUtils;

import java.text.NumberFormat;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import javax.inject.Inject;

import dagger.hilt.android.AndroidEntryPoint;


@AndroidEntryPoint
public class AboutFragment extends BasePreferenceFragment implements Preference.OnPreferenceClickListener {

    private static final String TAG = AboutFragment.class.getName();

    @Inject
    UpdateScheduler mUpdateScheduler;

    /** How often the row reads the update download while one runs, and while none does. */
    private static final long POLL_ACTIVE_MS = 1000L;
    private static final long POLL_IDLE_MS = 3000L;

    /** Guards against stacking a second observer on a double-tap. */
    private boolean mUpdateCheckRunning;

    /**
     * The last check's answer for the row's summary ("You're up to date",
     * "No connection…"), 0 for none. Kept while the screen is open: the
     * place the user looks for the answer is the row they tapped.
     */
    private int mCheckSummary;

    /** The update download as last read; drives the row while one runs. */
    private UpdateDownloader.Progress mProgress = UpdateDownloader.Progress.NONE;

    /** Whether a verified update was on disk at the last read. */
    private boolean mReady;

    private ExecutorService mPoller;
    private final Runnable mPoll = this::pollProgress;

    /**
     * A check still queued this long after the tap is waiting for a network
     * that went away after {@link #hasNetwork} said yes: answered as offline.
     */
    private static final long CHECK_QUEUED_TIMEOUT_MS = 20_000L;
    private Runnable mCheckTimeout;
    private WorkInfo.State mCheckState;


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
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        // The Install row says what its tap started: the copy into Android's
        // installer, then Android's own check (see refreshUpdateRow).
        UpdateInstaller.state().observe(getViewLifecycleOwner(), s -> refreshUpdateRow());
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
            } else if (isDownloading(mProgress)) {
                // Downloading or being checked: the update sheet shows it,
                // and turns into Install when ready. Waiting for a retry, a
                // tap checks again instead.
                UpdateAvailableSheet.openDownloading(mActivity.getSupportFragmentManager());
            } else {
                runManualUpdateCheck();
            }
        } else if (preference.getKey().equals(Preferences.SETTINGS_LICENSE)) {
            NavigationUtils.navigateSafe(mNavController, R.id.action_about_to_license);
        } else if (preference.getKey().equals(Preferences.SETTINGS_GECKO)) {
            SettingsActivity.openInBrowser(mActivity, getString(R.string.settings_mozilla_geckoview));
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
        // pipeline and the row's poll, only the answer on the row is dropped.
        mUpdateCheckRunning = false;
        mCheckSummary = 0;
        mCheckTimeout = null;
    }

    @Override
    public void onResume() {
        super.onResume();
        // The ready record can change while this screen sits open (a background
        // check finishing, or the user returning from Android's package
        // installer), so the row is re-derived rather than bound once.
        refreshUpdateRow();
        mPoller = Executors.newSingleThreadExecutor();
        pollProgress();
    }

    @Override
    public void onPause() {
        super.onPause();
        View view = getView();
        if (view != null) {
            view.removeCallbacks(mPoll);
        }
        if (mPoller != null) {
            mPoller.shutdownNow();
            mPoller = null;
        }
    }

    /**
     * Reads the update download off the main thread (a Download Manager
     * download is a query to its provider) and repaints the row with it,
     * every second while one runs. That is what moves the row from "Check
     * for updates" to the download and then to Install while the screen
     * stays open: nothing else here would notice.
     */
    private void pollProgress() {
        View view = getView();
        ExecutorService poller = mPoller;
        if (view == null || poller == null || poller.isShutdown()) {
            return;
        }
        Context app = requireContext().getApplicationContext();
        poller.execute(() -> {
            UpdateDownloader.Progress progress = UpdateDownloader.progress(app);
            boolean ready = UpdateDownloader.isVerifiedReady(app, App.getVersionCode() + 1);
            view.post(() -> {
                if (getView() != view || mPoller != poller) {
                    return;
                }
                // Ready read too: a small patch can download and be checked
                // between two reads, and the progress then never changes.
                if (!progress.equals(mProgress) || ready != mReady) {
                    mProgress = progress;
                    mReady = ready;
                    refreshUpdateRow();
                }
                view.removeCallbacks(mPoll);
                view.postDelayed(mPoll, progress.active() ? POLL_ACTIVE_MS : POLL_IDLE_MS);
            });
        });
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
            // While an install runs (started here, from the sheet or from the
            // notification) the summary is what it is doing. A tap meanwhile
            // does nothing: UpdateInstaller runs one install at a time.
            UpdateInstallProgress.Snapshot install = UpdateInstaller.state().getValue();
            if (install != null && install.phase == UpdateInstallProgress.Phase.PREPARING) {
                String percent = NumberFormat.getPercentInstance(
                        getResources().getConfiguration().getLocales().get(0))
                        .format(install.percent / 100.0);
                row.setSummary(getString(R.string.update_install_preparing) + " " + percent);
            } else if (install != null && install.phase == UpdateInstallProgress.Phase.INSTALLING) {
                row.setSummary(R.string.update_install_installing);
            }
        } else if (mProgress.active() && !mUpdateCheckRunning) {
            // A download or its check: the row says so, and a tap opens the
            // update sheet on it. Waiting for a retry, the row stays "Check
            // for updates" (a tap checks) with the wait as its summary.
            boolean downloading = isDownloading(mProgress);
            row.setTitle(downloading
                    ? R.string.update_downloading_title : R.string.settings_update_check_title);
            row.setIcon(downloading ? R.drawable.download_24 : R.drawable.ic_refresh_24);
            String state = progressSummary(context, mProgress);
            row.setSummary(mProgress.name.isEmpty() ? state : mProgress.name + " · " + state);
        } else {
            row.setTitle(R.string.settings_update_check_title);
            row.setIcon(R.drawable.ic_refresh_24);
            if (mUpdateCheckRunning) {
                row.setSummary(R.string.update_check_running);
            } else if (mCheckSummary != 0) {
                row.setSummary(mCheckSummary);
            } else {
                row.setSummary(null);
            }
        }
        if (row instanceof BusyPreference) {
            ((BusyPreference) row).setBusy(!ready && mUpdateCheckRunning);
        }
    }

    private static boolean isDownloading(UpdateDownloader.Progress progress) {
        return progress.phase == UpdateDownloader.Progress.Phase.DOWNLOADING
                || progress.phase == UpdateDownloader.Progress.Phase.PREPARING;
    }

    /** "42% of 19 MB", "Checking the download…", or the wait. */
    private String progressSummary(@Nullable Context context, UpdateDownloader.Progress progress) {
        if (context == null) {
            return "";
        }
        switch (progress.phase) {
            case DOWNLOADING: {
                int percent = progress.percent();
                if (percent < 0) {
                    return progress.bytes > 0
                            ? Formatter.formatShortFileSize(context, progress.bytes)
                            : getString(R.string.update_downloading_label);
                }
                String shown = NumberFormat.getPercentInstance(
                        getResources().getConfiguration().getLocales().get(0))
                        .format(percent / 100.0);
                return getString(R.string.update_download_progress, shown,
                        Formatter.formatShortFileSize(context, progress.total));
            }
            case PREPARING:
                return getString(R.string.update_download_verifying);
            case WAITING:
            default:
                return getString(R.string.update_download_waiting);
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
        new Thread(() -> UpdateInstaller.install(app, name, false)).start();
    }

    /**
     * User-initiated update check. Enqueues a fresh {@link UpdateWorker} run
     * and answers ON THE ROW: "Checking…" with a spinner, then "You're up to
     * date", "Couldn't check…" or "No connection…" as its summary (read out
     * to a screen reader too). A found update opens the update sheet: in
     * its download face while it downloads, which becomes Install when the
     * update has been checked. The row follows the download as well
     * ({@link #pollProgress}), so leaving the sheet loses nothing.
     *
     * <p>The work is observed BY ID (not by unique-work name — that LiveData
     * replays the previous check's terminal state first), on the view
     * lifecycle owner so a torn-down screen just drops the result. A
     * {@code Result.retry()} (every endpoint failed) is surfaced as a
     * failure immediately and the retry is cancelled — leaving the user
     * silently waiting through WorkManager's backoff reads as a dead button;
     * the periodic background job keeps checking regardless.</p>
     *
     * <p>No connection is decided BEFORE queueing: the check waits for a
     * network, and offline it sat queued with nothing on screen.</p>
     */
    private void runManualUpdateCheck() {
        if (mUpdateCheckRunning) {
            return;
        }
        if (!hasNetwork()) {
            answer(R.string.update_check_offline);
            return;
        }
        mUpdateCheckRunning = true;
        mCheckSummary = 0;
        refreshUpdateRow();

        UUID workId = mUpdateScheduler.enqueueManualCheck();
        WorkManager workManager = WorkManager.getInstance(requireContext());
        LiveData<WorkInfo> liveData = workManager.getWorkInfoByIdLiveData(workId);
        mCheckState = WorkInfo.State.ENQUEUED;
        View view = getView();
        if (view != null) {
            Runnable timeout = () -> {
                if (mUpdateCheckRunning && mCheckState == WorkInfo.State.ENQUEUED) {
                    workManager.cancelWorkById(workId);
                    mUpdateCheckRunning = false;
                    answer(R.string.update_check_offline);
                }
            };
            mCheckTimeout = timeout;
            view.postDelayed(timeout, CHECK_QUEUED_TIMEOUT_MS);
        }

        liveData.observe(getViewLifecycleOwner(), new Observer<WorkInfo>() {
            @Override
            public void onChanged(WorkInfo info) {
                if (info == null) {
                    return;
                }
                mCheckState = info.getState();
                boolean done = false;
                if (info.getState() == WorkInfo.State.SUCCEEDED) {
                    done = true;
                    boolean available = info.getOutputData()
                            .getBoolean(UpdateWorker.KEY_UPDATE_AVAILABLE, false);
                    String versionName = info.getOutputData()
                            .getString(UpdateWorker.KEY_LATEST_VERSION_NAME);
                    mUpdateCheckRunning = false;
                    if (available) {
                        onUpdateFound(versionName);
                    } else {
                        answer(R.string.update_check_up_to_date);
                    }
                } else if (info.getState() == WorkInfo.State.ENQUEUED
                        && info.getRunAttemptCount() > 0) {
                    // Re-enqueued = Result.retry() landed. Report + cancel.
                    done = true;
                    workManager.cancelWorkById(workId);
                    mUpdateCheckRunning = false;
                    answer(R.string.update_check_failed);
                } else if (info.getState() == WorkInfo.State.FAILED
                        || info.getState() == WorkInfo.State.CANCELLED) {
                    done = true;
                    mUpdateCheckRunning = false;
                    answer(R.string.update_check_failed);
                }
                if (done) {
                    liveData.removeObserver(this);
                    View v = getView();
                    if (v != null && mCheckTimeout != null) {
                        v.removeCallbacks(mCheckTimeout);
                    }
                    mCheckTimeout = null;
                }
            }
        });
    }

    /**
     * The check found a newer version. Already downloaded and checked: the
     * update sheet with Install. Downloading: the sheet in its download face
     * (the worker has started the download by the time it succeeds). Neither
     * (an install is running, or the download waits out a failure the row
     * then shows): the old one-line answer.
     */
    private void onUpdateFound(@Nullable String versionName) {
        Context app = requireContext().getApplicationContext();
        View view = getView();
        if (view == null) {
            return;
        }
        new Thread(() -> {
            UpdateDownloader.Progress progress = UpdateDownloader.progress(app);
            boolean ready = UpdateDownloader.isVerifiedReady(app, App.getVersionCode() + 1);
            view.post(() -> {
                if (getView() != view || mActivity == null) {
                    return;
                }
                mProgress = progress;
                mReady = ready;
                refreshUpdateRow();
                if (!isResumed()) {
                    return;
                }
                if (ready) {
                    UpdateAvailableSheet.openOnPurpose(app, mActivity.getSupportFragmentManager());
                } else if (isDownloading(progress)) {
                    UpdateAvailableSheet.openDownloading(mActivity.getSupportFragmentManager());
                } else if (versionName != null && !progress.active()) {
                    Snackbar.make(mActivity.getSnackAnchorView(),
                            getString(R.string.update_check_found, versionName),
                            Snackbar.LENGTH_LONG).show();
                }
            });
        }).start();
    }

    /** The check's answer, on the row and to a screen reader. */
    private void answer(int message) {
        mCheckSummary = message;
        refreshUpdateRow();
        View view = getView();
        if (view != null) {
            view.announceForAccessibility(getString(message));
        }
    }

    /** False only when Android knows of no network at all. */
    private boolean hasNetwork() {
        ConnectivityManager cm = (ConnectivityManager)
                requireContext().getSystemService(Context.CONNECTIVITY_SERVICE);
        if (cm == null) {
            return true;
        }
        Network network = cm.getActiveNetwork();
        if (network == null) {
            return false;
        }
        NetworkCapabilities caps = cm.getNetworkCapabilities(network);
        return caps == null || caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET);
    }

}
