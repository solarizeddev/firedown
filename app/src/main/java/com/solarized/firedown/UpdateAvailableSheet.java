package com.solarized.firedown;

import android.content.Context;
import android.content.DialogInterface;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.os.Bundle;
import android.text.SpannableString;
import android.text.format.Formatter;
import android.text.Spanned;
import android.text.style.LeadingMarginSpan;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.MainThread;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.graphics.ColorUtils;
import androidx.fragment.app.FragmentManager;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.preference.PreferenceManager;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.color.MaterialColors;
import com.google.android.material.progressindicator.LinearProgressIndicator;
import com.solarized.firedown.UpdatePromptPolicy.Busy;
import com.solarized.firedown.UpdatePromptPolicy.Place;
import com.solarized.firedown.data.di.RepositoryEntryPoint;
import com.solarized.firedown.manager.RunnableManager;
import com.solarized.firedown.phone.dialogs.BaseBottomSheetDialogFragment;
import com.solarized.firedown.phone.player.PlaybackHub;

import java.text.NumberFormat;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import dagger.hilt.android.AndroidEntryPoint;
import dagger.hilt.android.EntryPointAccessors;

/**
 * In-app "Update ready" bottom sheet: a SHA+signature verified update APK is
 * downloaded and ready to install.
 *
 * <p>It opens by itself only where {@link UpdatePromptPolicy} allows (the
 * home screen, Downloads, Settings, or the first screen after the app starts;
 * never over a page being read, never on the lock screen) and only while
 * nothing runs that installing would stop. The ⋮ menu's "Update ready" row
 * opens it on purpose ({@link #openOnPurpose}); then it says what would stop.
 *
 * Why this exists (the notifications-denied gap): the install prompt is a
 * notification, and on Android 13+ a user who denied POST_NOTIFICATIONS never
 * sees it — the entire update path goes silent. This sheet is the fallback
 * surface: it reads the same verified "ready" record UpdateDownloader writes and
 * offers the install in-app, so a notification denial no longer means the user
 * can never update. It's modal but infrequent (only a ready, newer, not-yet
 * snoozed update triggers it) and follows the CrashReportSheet pattern:
 * idempotent {@link #showIfReady} from {@code onResume}, crash sheet has
 * priority.
 *
 * <p>A dismissal SNOOZES rather than suppresses — see {@link #onDismiss}. At
 * most {@link UpdatePromptPolicy#MAX_PROMPTS} appearances by itself per
 * version, a day apart; after that the ⋮ row, About and the notification
 * carry the update.
 */
@AndroidEntryPoint
public class UpdateAvailableSheet extends BaseBottomSheetDialogFragment {

    private static final String TAG = "UpdateAvailableSheet";
    /** The sheet's fragment tag (the crash sheet steps aside for it). */
    public static final String FRAGMENT_TAG = TAG;
    // CrashReportSheet's own tag (private there) — the crash sheet is also shown
    // from BaseActivity.onResume and takes priority over this one.
    private static final String CRASH_SHEET_TAG = "CrashReportSheet";
    private static final String ARG_PREVIEW = "preview";
    /** Opened by the user (the ⋮ row): no snooze is written on dismissal. */
    private static final String ARG_ON_PURPOSE = "on_purpose";
    /** Opened from About's check while the update downloads: the download face. */
    private static final String ARG_DOWNLOADING = "downloading";
    /** How often the download face reads the download's progress. */
    private static final long PROGRESS_POLL_MS = 1000L;
    private static final String STATE_SEEN_INSTALL = "seen_install";
    /** Debug builds: one line per footer render, read by scripts/update-install-test.sh. */
    private static final String TRACE_TAG = "UpdateSheetTrace";

    private int mReadyVersionCode;

    /**
     * The install this sheet has watched run (its generation), so a later
     * IDLE closes the sheet and a FAILED shows the error line only for an
     * install it saw. An older install's outcome left in
     * {@link UpdateInstaller#state()} must not reach a sheet opened after it.
     */
    private long mSeenInstall = -1;

    private View mActions;
    private MaterialButton mInstall;
    private TextView mBusy;
    private TextView mError;
    private View mProgress;
    private TextView mProgressLabel;
    private TextView mProgressPercent;
    private LinearProgressIndicator mProgressBar;
    private TextView mTitle;
    private MaterialButton mNotNow;

    /**
     * The download face: the update is still downloading or being checked,
     * Install waits, and the footer shows the download. It turns into the
     * ready face in place when the verified APK lands.
     */
    private boolean mDownloadingFace;
    private boolean mObservingInstall;
    private ExecutorService mPoller;
    private final Runnable mPoll = this::pollProgress;

    /**
     * Opens the sheet by itself if {@link UpdatePromptPolicy} allows it here
     * and now. Safe to call from every activity's {@code onResume}:
     * idempotent via the tag check, and self-bailing when nothing is ready.
     *
     * @param firstScreen the first screen resumed since the app started
     */
    @MainThread
    public static void showIfReady(@NonNull Context context, @NonNull FragmentManager fm,
                                   @NonNull Place place, boolean firstScreen) {
        if (fm.isStateSaved()) return;
        if (fm.findFragmentByTag(TAG) != null) return;
        if (fm.findFragmentByTag(CRASH_SHEET_TAG) != null) return;
        if (place == Place.NEVER) return;

        // isVerifiedReady(installed + 1) ⇒ readyVersion >= installed+1 ⇒ strictly
        // newer than installed, with a file actually on disk.
        boolean ready = UpdateDownloader.isVerifiedReady(context, App.getVersionCode() + 1);
        if (!ready) {
            return;
        }
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
        boolean snoozed = UpdatePromptPolicy.snoozed(
                UpdateDownloader.readyVersionCode(context),
                prefs.getInt(Keys.UPDATE_PROMPT_SNOOZE_VERSION, -1),
                prefs.getLong(Keys.UPDATE_PROMPT_SNOOZE_UNTIL, 0L),
                System.currentTimeMillis());
        if (UpdatePromptPolicy.autoShow(true, snoozed, place, firstScreen, busyNow(context))) {
            // showNow, not show: a second caller in the same frame (the
            // activity's resume and the home screen's) must find this one by
            // its tag, and show() only commits later.
            new UpdateAvailableSheet().showNow(fm, TAG);
        }
    }

    /**
     * Opens the sheet because the user asked (the ⋮ menu's "Update ready"
     * row): no place, busy or snooze rule applies, and closing it writes no
     * snooze. It still needs a ready update.
     */
    @MainThread
    public static void openOnPurpose(@NonNull Context context, @NonNull FragmentManager fm) {
        if (fm.isStateSaved() || fm.findFragmentByTag(TAG) != null) return;
        if (!UpdateDownloader.isVerifiedReady(context, App.getVersionCode() + 1)) return;
        UpdateAvailableSheet sheet = new UpdateAvailableSheet();
        Bundle args = new Bundle();
        args.putBoolean(ARG_ON_PURPOSE, true);
        sheet.setArguments(args);
        sheet.showNow(fm, TAG);
    }

    /**
     * Opens the sheet because the user asked for an update that is still
     * downloading (About's "Check for updates"): the download face, which
     * becomes the ready face when the update has been checked. Closing it
     * writes no snooze and leaves the download running.
     */
    @MainThread
    public static void openDownloading(@NonNull FragmentManager fm) {
        if (fm.isStateSaved() || fm.findFragmentByTag(TAG) != null) return;
        UpdateAvailableSheet sheet = new UpdateAvailableSheet();
        Bundle args = new Bundle();
        args.putBoolean(ARG_ON_PURPOSE, true);
        args.putBoolean(ARG_DOWNLOADING, true);
        sheet.setArguments(args);
        sheet.showNow(fm, TAG);
    }

    /**
     * What installing would stop right now. Installing closes the app (on
     * Android 12 and newer at once): a running download, a GIF, audio or
     * Safe Folder task, an open file share, background or web playback.
     */
    @MainThread
    @NonNull
    public static Busy busyNow(@NonNull Context context) {
        RepositoryEntryPoint ep = EntryPointAccessors.fromApplication(
                context.getApplicationContext(), RepositoryEntryPoint.class);
        // Playing, not merely a media notification: a paused session keeps
        // its notification (and its service) for hours, and would otherwise
        // hold the sheet back with nothing playing.
        ExoPlayer player = PlaybackHub.player();
        boolean backgroundPlaying = PlaybackHub.isBackgroundActive()
                && player != null && player.isPlaying();
        return UpdatePromptPolicy.busy(
                RunnableManager.isRunning(),
                ep.getTaskRepository().isTaskInFlight(),
                ep.getP2pShareController().hasLiveSession(),
                backgroundPlaying || ep.getGeckoMediaController().isPlaying());
    }

    /** The line that says what installing would stop, or 0. */
    private static int busyLine(@NonNull Busy busy) {
        switch (busy) {
            case DOWNLOAD:
                return R.string.update_busy_download;
            case TASK:
                return R.string.update_busy_task;
            case SHARE:
                return R.string.update_busy_share;
            case PLAYBACK:
                return R.string.update_busy_playback;
            default:
                return 0;
        }
    }

    /**
     * DEBUG-only: force-show the sheet with a fabricated version, BYPASSING the
     * verified-ready gate, so the layout can be previewed without a real
     * download. No-op in release builds. To try it, temporarily add to an
     * activity's onResume (e.g. BaseActivity):
     *   UpdateAvailableSheet.showPreview(getSupportFragmentManager());
     * In preview, "Install" plays the footer's install states on a timer and
     * closes (there is no real APK on disk).
     */
    public static void showPreview(@NonNull FragmentManager fm) {
        if (!BuildConfig.DEBUG) return;
        if (fm.isStateSaved() || fm.findFragmentByTag(TAG) != null) return;
        UpdateAvailableSheet sheet = new UpdateAvailableSheet();
        Bundle args = new Bundle();
        args.putBoolean(ARG_PREVIEW, true);
        sheet.setArguments(args);
        sheet.show(fm, TAG);
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        LayoutInflater themedInflater = container != null
                ? LayoutInflater.from(container.getContext())
                : inflater;
        mView = themedInflater.inflate(R.layout.fragment_dialog_update_available, container, false);
        return mView;
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        Context context = requireContext();

        boolean preview = getArguments() != null && getArguments().getBoolean(ARG_PREVIEW, false);
        boolean downloading = getArguments() != null && getArguments().getBoolean(ARG_DOWNLOADING, false);
        boolean ready = UpdateDownloader.isVerifiedReady(context, App.getVersionCode() + 1);

        // Process death between show() and here, or a just-installed update, can
        // leave nothing ready — bail rather than show an empty sheet. Skipped in
        // preview mode, which fabricates a name purely for layout testing, and
        // in the download face, whose first read of the download decides.
        if (!preview && !ready && !downloading) {
            dismissAllowingStateLoss();
            return;
        }

        mTitle = view.findViewById(R.id.update_sheet_title);
        mNotNow = view.findViewById(R.id.update_sheet_not_now);
        mActions = view.findViewById(R.id.update_sheet_actions);
        mInstall = view.findViewById(R.id.update_sheet_install);
        mBusy = view.findViewById(R.id.update_sheet_busy);
        mError = view.findViewById(R.id.update_sheet_error);
        mProgress = view.findViewById(R.id.update_sheet_progress);
        mProgressLabel = view.findViewById(R.id.update_sheet_progress_label);
        mProgressPercent = view.findViewById(R.id.update_sheet_progress_percent);
        mProgressBar = view.findViewById(R.id.update_sheet_progress_bar);
        // The Downloads row's track: colorPrimary at 20% under the
        // progress_indicator set in the layout.
        int primary = MaterialColors.getColor(view, android.R.attr.colorPrimary, Color.BLACK);
        mProgressBar.setTrackColor(ColorUtils.setAlphaComponent(primary, 0x33));

        // "Not now" does what a swipe does (see onDismiss), but says so.
        mNotNow.setOnClickListener(v -> dismiss());

        if (savedInstanceState != null) {
            mSeenInstall = savedInstanceState.getLong(STATE_SEEN_INSTALL, -1);
        }

        if (preview) {
            bindPreview(view);
        } else if (ready) {
            bindReadyFace(view);
        } else {
            bindDownloadingFace(view);
        }
    }

    /** DEBUG preview: real notes when stored, Install plays the footer on a timer. */
    private void bindPreview(@NonNull View view) {
        Context context = requireContext();
        // Prefer the REAL changelog/version the worker last stored from
        // status.json; fall back to a fabricated sample only on a fresh
        // install before any check has run (or a status.json with no
        // changelog field).
        String name;
        String changelog;
        String storedChangelog = UpdateDownloader.getLastChangelog(context);
        String storedName = UpdateDownloader.getLastChangelogName(context);
        if (!storedChangelog.isEmpty()) {
            name = storedName.isEmpty()
                    ? getString(R.string.app_name) + " (preview)" : storedName;
            changelog = storedChangelog;
        } else {
            name = getString(R.string.app_name) + " (preview)";
            changelog = "• Faster, more reliable updates\n"
                    + "• Fixed video capture on some sites\n"
                    + "• Smaller download size";
        }
        bindVersion(view, name, changelog);
        mInstall.setOnClickListener(v -> runPreview());
    }

    /** The verified APK is on disk: Install, and the install's own progress. */
    private void bindReadyFace(@NonNull View view) {
        Context context = requireContext();
        mDownloadingFace = false;
        stopPolling();
        mReadyVersionCode = UpdateDownloader.readyVersionCode(context);
        String name = UpdateDownloader.readyVersionName(context);
        bindVersion(view, name, UpdateDownloader.getChangelogFor(context, mReadyVersionCode));
        mTitle.setText(R.string.update_ready_title);
        mNotNow.setText(R.string.translate_not_now);
        mInstall.setEnabled(true);
        mInstall.setOnClickListener(v -> onInstall(name));
        showButton(false);
        updateBusyLine();
        if (!mObservingInstall) {
            mObservingInstall = true;
            UpdateInstaller.state().observe(getViewLifecycleOwner(), this::render);
        }
    }

    /**
     * The update is still downloading (or being rebuilt and checked): the
     * same sheet, titled for it, with the download in the footer and Install
     * waiting. Read every second; when the verified APK lands, the sheet
     * becomes the ready face in place.
     */
    private void bindDownloadingFace(@NonNull View view) {
        Context context = requireContext();
        mDownloadingFace = true;
        // The worker stores the newest status.json's name and notes on every
        // check, the one that started this download included.
        bindVersion(view, UpdateDownloader.getLastChangelogName(context),
                UpdateDownloader.getLastChangelog(context));
        mTitle.setText(R.string.update_downloading_title);
        mNotNow.setText(R.string.update_sheet_hide);
        mInstall.setEnabled(false);
        mInstall.setOnClickListener(null);
        mActions.setVisibility(View.VISIBLE);
        mBusy.setVisibility(View.GONE);
        mError.setVisibility(View.GONE);
        mProgress.setVisibility(View.VISIBLE);
        setProgressGap(true);
        mProgressLabel.setText(R.string.update_downloading_label);
        mProgressPercent.setText(null);
        mProgressBar.setVisibility(View.VISIBLE);
        if (!mProgressBar.isIndeterminate()) {
            mProgressBar.setIndeterminate(true);
        }
        mPoller = Executors.newSingleThreadExecutor();
        pollProgress();
    }

    /** The version line, the one it replaces, and the notes when there are any. */
    private void bindVersion(@NonNull View view, @Nullable String name, @Nullable String changelog) {
        String shown = (name == null || name.isEmpty())
                ? getString(R.string.app_name) : name;

        // The version being installed, with no size: it is already
        // downloaded, so a size reads as a cost still to pay, and the file on
        // disk is the rebuilt APK (~200 MB), not what was downloaded (often a
        // patch of a few MB).
        TextView version = view.findViewById(R.id.update_sheet_version);
        version.setText(shown);

        // ...and the version it replaces.
        TextView current = view.findViewById(R.id.update_sheet_current);
        current.setText(getString(R.string.update_available_sheet_current, App.getVersionName()));

        // "What's new" header + release notes — both shown only when status.json
        // carries a changelog for this version.
        TextView whatsNew = view.findViewById(R.id.update_sheet_whats_new);
        LinearLayout changelogContainer = view.findViewById(R.id.update_sheet_changelog);
        boolean hasChangelog = changelog != null && !changelog.trim().isEmpty();
        if (hasChangelog) {
            fillChangelog(changelogContainer, changelog.trim());
        } else {
            changelogContainer.removeAllViews();
        }
        int changelogVisibility = hasChangelog ? View.VISIBLE : View.GONE;
        whatsNew.setVisibility(changelogVisibility);
        changelogContainer.setVisibility(changelogVisibility);
    }

    /** One read of the download, off the main thread; the answer is shown on it. */
    private void pollProgress() {
        View root = getView();
        ExecutorService poller = mPoller;
        if (root == null || poller == null || poller.isShutdown()) {
            return;
        }
        Context app = requireContext().getApplicationContext();
        poller.execute(() -> {
            // Progress first: a download that turns ready in between reads as
            // ready, never as gone.
            UpdateDownloader.Progress progress = UpdateDownloader.progress(app);
            boolean ready = UpdateDownloader.isVerifiedReady(app, App.getVersionCode() + 1);
            root.post(() -> onProgress(progress, ready));
        });
    }

    private void onProgress(@NonNull UpdateDownloader.Progress progress, boolean ready) {
        View view = getView();
        if (view == null || !mDownloadingFace) {
            return;
        }
        if (ready) {
            bindReadyFace(view);
            return;
        }
        if (!progress.active()) {
            // Nothing downloads and nothing is ready: the download was
            // dropped (an update installed another way, a failure with no
            // retry left). About's row says what comes next.
            dismissAllowingStateLoss();
            return;
        }
        showDownload(progress);
        view.removeCallbacks(mPoll);
        view.postDelayed(mPoll, PROGRESS_POLL_MS);
    }

    /** The footer for the download: sizes and a bar, the check, or the wait. */
    private void showDownload(@NonNull UpdateDownloader.Progress progress) {
        Context context = requireContext();
        switch (progress.phase) {
            case DOWNLOADING: {
                int percent = progress.percent();
                mProgressBar.setVisibility(View.VISIBLE);
                if (percent >= 0) {
                    mProgressLabel.setText(getString(R.string.update_download_progress,
                            Formatter.formatShortFileSize(context, progress.bytes),
                            Formatter.formatShortFileSize(context, progress.total)));
                    mProgressPercent.setText(NumberFormat.getPercentInstance(
                            getResources().getConfiguration().getLocales().get(0))
                            .format(percent / 100.0));
                    if (mProgressBar.isIndeterminate()) {
                        mProgressBar.setIndeterminate(false);
                    }
                    mProgressBar.setProgressCompat(percent, true);
                } else {
                    mProgressLabel.setText(R.string.update_downloading_label);
                    mProgressPercent.setText(progress.bytes > 0
                            ? Formatter.formatShortFileSize(context, progress.bytes) : null);
                    if (!mProgressBar.isIndeterminate()) {
                        mProgressBar.setIndeterminate(true);
                    }
                }
                break;
            }
            case PREPARING:
                mProgressBar.setVisibility(View.VISIBLE);
                mProgressLabel.setText(R.string.update_download_verifying);
                mProgressPercent.setText(null);
                if (!mProgressBar.isIndeterminate()) {
                    mProgressBar.setIndeterminate(true);
                }
                break;
            case WAITING:
            default:
                mProgressBar.setVisibility(View.GONE);
                mProgressLabel.setText(R.string.update_download_waiting);
                mProgressPercent.setText(null);
                break;
        }
    }

    /**
     * The download face shows the progress ABOVE the buttons, with a gap;
     * the install swaps the two (the 48dp progress in the 48dp row's place).
     */
    private void setProgressGap(boolean gap) {
        ViewGroup.MarginLayoutParams lp = (ViewGroup.MarginLayoutParams) mProgress.getLayoutParams();
        int margin = gap ? Math.round(12 * getResources().getDisplayMetrics().density) : 0;
        if (lp.bottomMargin != margin) {
            lp.bottomMargin = margin;
            mProgress.setLayoutParams(lp);
        }
    }

    private void stopPolling() {
        View view = getView();
        if (view != null) {
            view.removeCallbacks(mPoll);
        }
        if (mPoller != null) {
            mPoller.shutdownNow();
            mPoller = null;
        }
    }

    @Override
    public void onDestroyView() {
        stopPolling();
        mObservingInstall = false;
        super.onDestroyView();
    }

    @Override
    public void onResume() {
        super.onResume();
        if (mBusy == null) {
            return; // onViewCreated found nothing ready and dismissed
        }
        updateBusyLine();
    }

    /**
     * Opened on purpose while something runs: say what installing stops.
     * Read again on every resume (a download can start or end meanwhile).
     * Only beside an Install that can be tapped.
     */
    private void updateBusyLine() {
        int line = mDownloadingFace ? 0 : busyLine(busyNow(requireContext()));
        if (line != 0) {
            mBusy.setText(line);
        }
        mBusy.setVisibility(line != 0 && mActions.getVisibility() == View.VISIBLE
                ? View.VISIBLE : View.GONE);
    }

    @Override
    public void onSaveInstanceState(@NonNull Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putLong(STATE_SEEN_INSTALL, mSeenInstall);
    }

    private void onInstall(String name) {
        // UpdateInstaller.install does disk IO (reads the APK into a
        // PackageInstaller session) — off the main thread. Use the application
        // context so the worker thread can't outlive and leak this fragment.
        //
        // The sheet STAYS: the copy into Android's installer takes seconds on
        // a slow phone, and a sheet that closed on the tap left the screen
        // doing nothing for that time. render() closes it when Android
        // answers. The footer flips now, not on the first posted state, so a
        // second tap can't land on the button.
        showPreparing(0);
        Context app = requireContext().getApplicationContext();
        new Thread(() -> UpdateInstaller.install(app, name, false)).start();
    }

    /**
     * Footer for the install's state. Busy: the progress in place of the
     * button. IDLE after a busy state this sheet saw: Android answered (its
     * dialog is up, or it is replacing the app), so the sheet goes. FAILED
     * after one: the button back, with the error line.
     */
    private void render(UpdateInstallProgress.Snapshot s) {
        if (s.busy()) {
            mSeenInstall = s.generation;
            if (s.phase == UpdateInstallProgress.Phase.PREPARING) {
                showPreparing(s.percent);
                trace("progress", s);
            } else {
                showInstalling();
                trace("installing", s);
            }
            return;
        }
        boolean ours = mSeenInstall > 0 && s.generation == mSeenInstall;
        if (ours && s.phase == UpdateInstallProgress.Phase.IDLE) {
            trace("closed", s);
            dismissAllowingStateLoss();
            return;
        }
        boolean failed = ours && s.phase == UpdateInstallProgress.Phase.FAILED;
        showButton(failed);
        trace(failed ? "error" : "button", s);
    }

    /**
     * Debug builds: what the footer shows, per sheet instance. The device
     * test reads it instead of the screen: a screen dump waits for the
     * screen to go still, which it never does while the bar counts up.
     */
    private void trace(String footer, UpdateInstallProgress.Snapshot s) {
        if (BuildConfig.DEBUG) {
            Log.d(TRACE_TAG, "sheet=" + Integer.toHexString(System.identityHashCode(this))
                    + " footer=" + footer + " percent=" + s.percent + " gen=" + s.generation);
        }
    }

    private void showButton(boolean failed) {
        mProgress.setVisibility(View.GONE);
        mActions.setVisibility(View.VISIBLE);
        mError.setVisibility(failed ? View.VISIBLE : View.GONE);
    }

    private void showPreparing(int percent) {
        mProgressBar.setVisibility(View.VISIBLE);
        setProgressGap(false);
        mActions.setVisibility(View.GONE);
        mBusy.setVisibility(View.GONE);
        mError.setVisibility(View.GONE);
        mProgress.setVisibility(View.VISIBLE);
        mProgressLabel.setText(R.string.update_install_preparing);
        mProgressPercent.setText(NumberFormat.getPercentInstance(
                getResources().getConfiguration().getLocales().get(0)).format(percent / 100.0));
        if (mProgressBar.isIndeterminate()) {
            mProgressBar.setIndeterminate(false);
        }
        mProgressBar.setProgressCompat(percent, true);
    }

    private void showInstalling() {
        mProgressBar.setVisibility(View.VISIBLE);
        setProgressGap(false);
        mActions.setVisibility(View.GONE);
        mBusy.setVisibility(View.GONE);
        mError.setVisibility(View.GONE);
        mProgress.setVisibility(View.VISIBLE);
        mProgressLabel.setText(R.string.update_install_installing);
        mProgressPercent.setText(null);
        if (!mProgressBar.isIndeterminate()) {
            mProgressBar.setIndeterminate(true);
        }
    }

    /** DEBUG preview: the footer's states on a timer, then the sheet closes. */
    private void runPreview() {
        View root = getView();
        if (root == null) {
            return;
        }
        int[] percent = {0};
        root.post(new Runnable() {
            @Override
            public void run() {
                if (getView() == null) {
                    return;
                }
                if (percent[0] <= 100) {
                    showPreparing(percent[0]);
                    percent[0] += 2;
                    root.postDelayed(this, 30);
                    return;
                }
                showInstalling();
                root.postDelayed(() -> {
                    if (isAdded()) {
                        dismissAllowingStateLoss();
                    }
                }, 2000);
            }
        });
    }

    /**
     * Populates the changelog container with one row per bullet, separated by
     * a real top margin. Spacing is a view margin (not line spacing) because
     * the Material3 textAppearance's lineHeight overrides lineSpacingExtra/
     * Multiplier. A wrapped line starts under the text, not under the bullet:
     * the bullet status.json carries ("• ") is taken off and put back with a
     * leading margin as wide as itself.
     */
    private void fillChangelog(LinearLayout container, String raw) {
        container.removeAllViews();
        LayoutInflater inflater = LayoutInflater.from(container.getContext());
        int gap = Math.round(12 * getResources().getDisplayMetrics().density);
        boolean first = true;
        for (String line : raw.split("\n")) {
            String item = line.trim();
            if (item.isEmpty()) {
                continue;
            }
            // Only a bullet followed by a space: "-5% size" keeps its sign.
            if (item.startsWith("•")) {
                item = item.substring(1).trim();
            } else if (item.startsWith("- ") || item.startsWith("* ")) {
                item = item.substring(2).trim();
            }
            TextView row = (TextView) inflater.inflate(
                    R.layout.item_update_changelog_bullet, container, false);
            String bullet = "\u2022  ";
            int indent = Math.round(row.getPaint().measureText(bullet));
            SpannableString text = new SpannableString(bullet + item);
            text.setSpan(new LeadingMarginSpan.Standard(0, indent), 0, text.length(),
                    Spanned.SPAN_INCLUSIVE_EXCLUSIVE);
            row.setText(text);
            if (!first) {
                ViewGroup.MarginLayoutParams lp = (ViewGroup.MarginLayoutParams) row.getLayoutParams();
                lp.topMargin = gap;
                row.setLayoutParams(lp);
            }
            container.addView(row);
            first = false;
        }
    }

    /**
     * Any dismissal — Not now, Install, swipe-down, back, tap-outside —
     * SNOOZES the sheet for this version for a day, and the
     * {@link UpdatePromptPolicy#MAX_PROMPTS}th makes that permanent (the ⋮
     * row, About and the notification still offer the update). A sheet the
     * user opened on purpose writes nothing: it was asked for.
     *
     * <p>Install snoozes too, deliberately: a successful install needs no
     * suppression (the new versionCode fails {@code showIfReady}'s
     * newer-than-installed check), while an install the user cancels at
     * Android's prompt is exactly a case that SHOULD be offered again
     * tomorrow, not on the resume that follows the prompt.
     */
    @Override
    public void onDismiss(@NonNull DialogInterface dialog) {
        Context context = getContext();
        boolean onPurpose = getArguments() != null && getArguments().getBoolean(ARG_ON_PURPOSE, false);
        if (context != null && mReadyVersionCode > 0 && !onPurpose) {
            SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
            UpdatePromptPolicy.Snooze snooze = UpdatePromptPolicy.afterDismissal(
                    mReadyVersionCode,
                    prefs.getInt(Keys.UPDATE_PROMPT_SNOOZE_VERSION, -1),
                    prefs.getInt(Keys.UPDATE_PROMPT_SNOOZE_COUNT, 0),
                    System.currentTimeMillis());
            prefs.edit()
                    .putInt(Keys.UPDATE_PROMPT_SNOOZE_VERSION, mReadyVersionCode)
                    .putInt(Keys.UPDATE_PROMPT_SNOOZE_COUNT, snooze.count)
                    .putLong(Keys.UPDATE_PROMPT_SNOOZE_UNTIL, snooze.until)
                    .apply();
        }
        super.onDismiss(dialog);
    }
}
