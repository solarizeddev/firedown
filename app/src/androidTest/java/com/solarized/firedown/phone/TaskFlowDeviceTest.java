package com.solarized.firedown.phone;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

import android.app.Activity;
import android.app.Application;
import android.app.Instrumentation;
import android.app.KeyguardManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.ComponentCallbacks2;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.os.PowerManager;
import android.os.SystemClock;
import android.service.notification.StatusBarNotification;
import android.util.Log;
import android.view.View;

import androidx.biometric.BiometricManager;
import androidx.core.app.NotificationManagerCompat;
import androidx.fragment.app.DialogFragment;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentActivity;
import androidx.fragment.app.FragmentManager;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.navigation.NavController;
import androidx.navigation.NavDestination;
import androidx.navigation.fragment.NavHostFragment;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.runner.lifecycle.ActivityLifecycleCallback;
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry;
import androidx.test.runner.lifecycle.Stage;

import com.solarized.firedown.App;
import com.solarized.firedown.IntentActions;
import com.solarized.firedown.Keys;
import com.solarized.firedown.Preferences;
import com.solarized.firedown.R;
import com.solarized.firedown.data.Download;
import com.solarized.firedown.data.TaskEvent;
import com.solarized.firedown.data.di.RepositoryEntryPoint;
import com.solarized.firedown.data.entity.DownloadEntity;
import com.solarized.firedown.data.repository.DownloadDataRepository;
import com.solarized.firedown.data.repository.TaskRepository;
import com.solarized.firedown.manager.DownloadEngineStressTest;
import com.solarized.firedown.manager.DownloadIntents;
import com.solarized.firedown.manager.DownloadRequest;
import com.solarized.firedown.manager.RunnableManager;
import com.solarized.firedown.manager.ServiceActions;
import com.solarized.firedown.manager.UrlType;
import com.solarized.firedown.phone.fragments.MediaViewerFragment;
import com.solarized.firedown.phone.player.PlaybackHub;
import com.solarized.firedown.phone.player.PlayerPlaybackService;
import com.solarized.firedown.utils.NotificationID;

import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.FixMethodOrder;
import org.junit.Rule;
import org.junit.Test;
import org.junit.internal.AssumptionViolatedException;
import org.junit.rules.TestRule;
import org.junit.runner.RunWith;
import org.junit.runners.MethodSorters;
import org.junit.runners.model.Statement;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.Predicate;

import dagger.hilt.android.EntryPointAccessors;

/**
 * The app's tasks, screens and entries on a real phone: the launcher icon,
 * Home and Back keys, a link from another app, a download's notification,
 * the lock screen and background playback. Each test drives the real app
 * the way a person and Android do (shell {@code am start} and
 * {@code input keyevent}, a notification's own PendingIntent) and checks
 * which activity instances exist afterwards, so "the same screen came back"
 * means the same object, not one that looks alike. It pins the rules in
 * CLAUDE.md's "Intents, tasks &amp; navigation":
 * <ul>
 *   <li>the icon opens the browser through {@link LauncherActivity}, and on
 *       return brings the task back as it was left;</li>
 *   <li>a start of the browser from outside closes the screens above it,
 *       never the browser, and never background playback;</li>
 *   <li>a download notification reuses Downloads where it is, shows the
 *       list, and leaves the browser alone;</li>
 *   <li>Back at the root of a Downloads opened from outside goes to the
 *       browser, and on one of its sub-screens pops the sub-screen, also
 *       after a recreation;</li>
 *   <li>Downloads shows a task that started before it opened, after the app
 *       was left and opened again from the icon;</li>
 *   <li>a lock screen stays up until someone authenticates, and one left
 *       behind finishes once the app was unlocked.</li>
 * </ul>
 *
 * <p>Run from Android Studio (right-click the class or a method → Run or
 * Debug, debug variant, phone attached) or:
 * <pre>
 *   ./gradlew connectedDebugAndroidTest \
 *     -Pandroid.testInstrumentationRunnerArguments.class=com.solarized.firedown.phone.TaskFlowDeviceTest
 * </pre>
 * Before running: the phone awake and unlocked, nothing else in front of
 * Firedown. The test presses Home and opens the home screen, plays a few
 * seconds of SILENT audio, and makes one 64 KB download from a server on
 * 127.0.0.1 (no network needed), deleted at the end with its notification.
 * Tests skip themselves (reported as skipped, not passed) when the phone
 * lacks what they need: Firedown's notifications allowed (the download and
 * player tests read the notification they tap), a screen lock set (the lock
 * tests). The app lock and the donation prompt's state are put back as they
 * were; installing the test restarts the app, so let a running download
 * finish first.
 *
 * <p>Reading a run: every step (each key press, shell command with what
 * {@code am start} answered, activity lifecycle change, wait and its
 * outcome) is logged as it happens, {@code adb logcat -s TaskFlowDeviceTest},
 * so a run that dies (an app crash ends the instrumentation) still shows how
 * far it got. A test that fails carries a report in its failure message,
 * taken BEFORE the clean-up: the step trail, every activity's last stage,
 * Android's own task stack ({@code dumpsys activity activities}, our lines),
 * the app's notifications and its lock, player and task state. Gradle prints
 * those messages after the run (app/device-tests.gradle), and the HTML report
 * has them too.
 */
@RunWith(AndroidJUnit4.class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
public class TaskFlowDeviceTest {

    private static final String TAG = "TaskFlowDeviceTest";

    private static final long WAIT_MS = 20_000;
    private static final long SETTLE_MS = 1_500;

    private static final String ALIAS = "com.solarized.firedown.phone.BrowserActivity";
    private static final String BROWSER = "com.solarized.firedown.phone.BrowserMainActivity";
    private static final String DOWNLOADS = "com.solarized.firedown.phone.DownloadsActivity";
    /* The launcher's own flags: NEW_TASK | RESET_TASK_IF_NEEDED. */
    private static final String LAUNCHER_FLAGS = "0x10200000";
    /* BaseActivity.screenIntent: NEW_TASK | CLEAR_TOP | SINGLE_TOP. */
    private static final String SCREEN_FLAGS = "0x34000000";
    /* NEW_TASK | NO_USER_ACTION: leaves without onUserLeaveHint, so the
     * player goes to background playback instead of picture-in-picture. */
    private static final String HOME_NO_HINT_FLAGS = "0x10040000";

    private static final String[] LOCK_KEYS = {
            Preferences.SETTINGS_APP_LOCK,
            Preferences.SETTINGS_APP_LOCK_TIME,
            Preferences.SETTINGS_APP_LOCK_UPDATE_TIME,
            Preferences.SETTINGS_APP_LOCK_REQUIRED,
    };
    private static final String DONATION_PREFS = "backup_local";
    private static final String DONATION_PREFIX = "donation_";

    private static String sStayOn;

    private Instrumentation mInstrumentation;
    private Context mApp;
    private String mPkg;
    private SharedPreferences mPrefs;
    private RepositoryEntryPoint mEntryPoint;
    private final Recorder mRecorder = new Recorder();
    private final Map<String, Object> mSavedLock = new HashMap<>();
    private final Map<String, Object> mSavedDonation = new HashMap<>();
    private final List<String> mDownloadUrls = new ArrayList<>();
    private final List<Integer> mNotificationIds = new ArrayList<>();
    private DownloadEngineStressTest.ByteServer mServer;
    private File mWav;
    private boolean mFakeTaskRunning;
    private String mTestName = "";
    private long mTestStart;
    private final List<String> mSteps = Collections.synchronizedList(new ArrayList<>());

    /* setUp, the test, a report of the state at a failure, then tearDown: a
     * rule rather than @Before/@After so the report sees the activities and
     * notifications as the test left them, before the clean-up closes them. */
    @Rule
    public final TestRule mFlow = (base, description) -> new Statement() {
        @Override
        public void evaluate() throws Throwable {
            mTestName = description.getMethodName();
            mTestStart = SystemClock.uptimeMillis();
            mSteps.clear();
            Log.i(TAG, "──── " + mTestName);
            Throwable failure = null;
            try {
                setUp();
                base.evaluate();
                Log.i(TAG, "PASS " + mTestName + " in " + elapsed());
            } catch (AssumptionViolatedException skipped) {
                Log.i(TAG, "SKIP " + mTestName + ": " + skipped.getMessage());
                failure = skipped;
            } catch (Throwable t) {
                failure = withReport(t);
            } finally {
                try {
                    tearDown();
                } catch (Throwable t) {
                    Log.e(TAG, "clean-up after " + mTestName + " failed", t);
                    if (failure == null) {
                        failure = t;
                    } else {
                        failure.addSuppressed(t);
                    }
                }
            }
            if (failure != null) {
                throw failure;
            }
        }
    };

    // ── Setup ───────────────────────────────────────────────────────────────

    @BeforeClass
    public static void keepScreenOn() {
        // Plugged in (USB debugging) the screen stays on for the whole run,
        // so a Home or Back key never lands on a sleeping phone.
        sStayOn = shellStatic("settings get global stay_on_while_plugged_in").trim();
        shellStatic("settings put global stay_on_while_plugged_in 7");
    }

    @AfterClass
    public static void restoreScreenTimeout() {
        if (sStayOn != null && sStayOn.matches("\\d+")) {
            shellStatic("settings put global stay_on_while_plugged_in " + sStayOn);
        } else {
            shellStatic("settings delete global stay_on_while_plugged_in");
        }
    }

    private void setUp() {
        mInstrumentation = InstrumentationRegistry.getInstrumentation();
        mApp = mInstrumentation.getTargetContext().getApplicationContext();
        mPkg = mApp.getPackageName();
        mEntryPoint = EntryPointAccessors.fromApplication(mApp, RepositoryEntryPoint.class);
        mPrefs = mEntryPoint.getSharedPreferences();

        shell("input keyevent KEYCODE_WAKEUP");
        shell("wm dismiss-keyguard");
        KeyguardManager keyguard = mApp.getSystemService(KeyguardManager.class);
        assertTrue("the phone is locked: unlock it before running this test",
                waitUntil(() -> !keyguard.isKeyguardLocked(), 3_000));

        save(mPrefs, LOCK_KEYS, null, mSavedLock);
        SharedPreferences donation = mApp.getSharedPreferences(DONATION_PREFS, Context.MODE_PRIVATE);
        save(donation, null, DONATION_PREFIX, mSavedDonation);
        // The user's own app lock would put a lock screen over every step.
        mPrefs.edit().putBoolean(Preferences.SETTINGS_APP_LOCK, false).commit();

        ActivityLifecycleMonitorRegistry.getInstance().addLifecycleCallback(mRecorder);
        finishAll();
        mRecorder.clear();
        step("set up: app lock off, no activity alive");
    }

    private void tearDown() throws Exception {
        try {
            if (mFakeTaskRunning) {
                mEntryPoint.getTaskRepository().onTaskEnded();
                mFakeTaskRunning = false;
            }
            mPrefs.edit().putBoolean(Preferences.SETTINGS_APP_LOCK, false).commit();
            stopPlayback();
            deleteDownloads();
            NotificationManager nm = mApp.getSystemService(NotificationManager.class);
            for (int id : mNotificationIds) {
                nm.cancel(id);
            }
            finishAll();
            waitUntil(() -> onMain(PlaybackHub::player) == null, 5_000);
        } finally {
            restore(mPrefs, mSavedLock, LOCK_KEYS, null);
            restore(mApp.getSharedPreferences(DONATION_PREFS, Context.MODE_PRIVATE),
                    mSavedDonation, null, DONATION_PREFIX);
            ActivityLifecycleMonitorRegistry.getInstance().removeLifecycleCallback(mRecorder);
            mRecorder.clear();
            Log.i(TAG, "clean-up done after " + mTestName);
            if (mServer != null) {
                mServer.stop();
            }
            if (mWav != null) {
                mWav.delete();
            }
        }
    }

    // ── The launcher icon ───────────────────────────────────────────────────

    /** No task: the icon's routing activity starts the browser and leaves. */
    @Test
    public void t01_coldIconTapOpensTheBrowserThroughTheRoutingActivity() {
        iconTap();
        BrowserMainActivity browser = awaitResumed(BrowserMainActivity.class);
        List<LauncherActivity> launchers = createdOf(LauncherActivity.class);
        assertEquals("routing activities created. " + describe(), 1, launchers.size());
        awaitDestroyed(launchers.get(0));
        assertTrue("the browser must be the bottom of its task", onMain(browser::isTaskRoot));
        assertEquals(1, createdOf(BrowserMainActivity.class).size());
    }

    /** Browser, Downloads, Settings; Home; the icon brings back that very Settings. */
    @Test
    public void t02_warmIconTapBringsBackTheScreenThatWasLeft() {
        BrowserMainActivity browser = openBrowser();
        DownloadsActivity downloads = start(browser, new Intent(browser, DownloadsActivity.class),
                DownloadsActivity.class);
        SettingsActivity settings = start(downloads, new Intent(downloads, SettingsActivity.class),
                SettingsActivity.class);

        home(settings);
        iconTap();

        assertSame("the icon must bring back the Settings that was left. " + describe(),
                settings, awaitResumed(SettingsActivity.class));
        assertAlive(downloads);
        assertAlive(browser);
        assertEquals(describe(), 1, createdOf(BrowserMainActivity.class).size());
        assertEquals(describe(), 1, createdOf(DownloadsActivity.class).size());
        assertEquals(describe(), 1, createdOf(SettingsActivity.class).size());
    }

    /**
     * The claim CLAUDE.md marks as an AOSP reading: with a task to bring
     * back, Android adds no routing activity. If this fails while t02 passes,
     * Android did add one and it finished itself (a splash flash, nothing
     * lost): correct the claim in CLAUDE.md rather than the code.
     */
    @Test
    public void t03_warmIconTapCreatesNoRoutingActivity() {
        BrowserMainActivity browser = openBrowser();
        DownloadsActivity downloads = start(browser, new Intent(browser, DownloadsActivity.class),
                DownloadsActivity.class);
        int before = createdOf(LauncherActivity.class).size();

        home(downloads);
        iconTap();

        assertSame(downloads, awaitResumed(DownloadsActivity.class));
        SystemClock.sleep(SETTLE_MS);
        assertEquals("a warm icon tap created a routing activity. " + describe(),
                before, createdOf(LauncherActivity.class).size());
    }

    // ── Entries from outside ────────────────────────────────────────────────

    /** A link from another app lands on the browser: the screens above it close, it stays. */
    @Test
    public void t04_browserStartedFromOutsideClosesTheScreensAboveIt() {
        BrowserMainActivity browser = openBrowser();
        DownloadsActivity downloads = start(browser, new Intent(browser, DownloadsActivity.class),
                DownloadsActivity.class);
        SettingsActivity settings = start(downloads, new Intent(downloads, SettingsActivity.class),
                SettingsActivity.class);

        home(settings);
        startBrowserFromOutside();

        awaitDestroyed(settings);
        awaitDestroyed(downloads);
        assertSame("the browser must be reused, not replaced. " + describe(),
                browser, awaitResumed(BrowserMainActivity.class));
        assertEquals(describe(), 1, createdOf(BrowserMainActivity.class).size());
    }

    /**
     * A finished download's notification, tapped with Settings over a
     * Downloads left on Storage: Settings closes, the same Downloads shows
     * its list, the browser underneath is untouched.
     */
    @Test
    public void t05_downloadNotificationReturnsToTheListAndKeepsTheBrowser() throws Exception {
        assumeNotificationsAllowed(App.DOWNLOADS_NOTIFICATION_ID);
        BrowserMainActivity browser = openBrowser();
        DownloadsActivity downloads = start(browser, new Intent(browser, DownloadsActivity.class),
                DownloadsActivity.class);
        navigate(downloads, R.id.storage);
        SettingsActivity settings = start(downloads, new Intent(downloads, SettingsActivity.class),
                SettingsActivity.class);

        String name = "taskflow-" + System.nanoTime() + ".bin";
        mServer = new DownloadEngineStressTest.ByteServer();
        mServer.start();
        String url = mServer.base() + "/taskflow.bin?size=65536&delay=0&name=" + name;
        mDownloadUrls.add(url);
        DownloadIntents.start(mApp, new DownloadRequest.Builder(url)
                .name(name)
                .mimeType("application/octet-stream")
                .fileType(UrlType.FILE.getValue())
                .build());
        step("download " + url);
        DownloadDataRepository repo = mEntryPoint.getDownloadDataRepository();
        assertTrue("the download never finished", waitUntil(() -> {
            for (DownloadEntity e : repo.getAllRawList()) {
                if (url.equals(e.getFileUrl()) && e.getFileStatus() == Download.FINISHED) {
                    return true;
                }
            }
            return false;
        }, 90_000));
        step("download finished");

        StatusBarNotification finished = awaitNotification("the finished download " + name,
                sbn -> name.contentEquals(titleOf(sbn)));
        mNotificationIds.add(finished.getId());
        step("tap the finished-download notification");
        finished.getNotification().contentIntent.send();

        awaitDestroyed(settings);
        assertSame("the notification must reuse Downloads. " + describe(),
                downloads, awaitResumed(DownloadsActivity.class));
        assertTrue("Downloads must show its list, not Storage",
                waitUntil(() -> destinationOf(downloads) == R.id.downloads, WAIT_MS));
        assertAlive(browser);
        assertEquals(describe(), 1, createdOf(BrowserMainActivity.class).size());
        assertEquals(describe(), 1, createdOf(DownloadsActivity.class).size());
    }

    // ── Back ────────────────────────────────────────────────────────────────

    /** Downloads opened with no task (a notification): Back goes to the browser. */
    @Test
    public void t06_backAtARootDownloadsOpensTheBrowser() {
        DownloadsActivity downloads = startRootDownloads();
        assertNoDialog(downloads);

        back();

        BrowserMainActivity browser = awaitResumed(BrowserMainActivity.class);
        awaitDestroyed(downloads);
        assertTrue("the browser must take the task's bottom", onMain(browser::isTaskRoot));
    }

    /**
     * The same Downloads on Storage: Back pops Storage and never leaves for
     * the browser, also after a recreation, which restores the navigation
     * stack and with it its Back callback (the root callback must still run
     * after it).
     */
    @Test
    public void t07_backOnASubScreenOfARootDownloadsStaysInDownloads() {
        DownloadsActivity downloads = startRootDownloads();
        navigate(downloads, R.id.storage);
        assertNoDialog(downloads);

        back();

        assertTrue("Back must pop Storage. " + describe(),
                waitUntil(() -> destinationOf(downloads) == R.id.downloads, WAIT_MS));
        SystemClock.sleep(SETTLE_MS);
        assertAlive(downloads);
        assertTrue("Back on Storage left Downloads. " + describe(),
                createdOf(BrowserMainActivity.class).isEmpty());

        navigate(downloads, R.id.storage);
        step("recreate Downloads");
        onMain(() -> {
            downloads.recreate();
            return null;
        });
        DownloadsActivity recreated = awaitNew(DownloadsActivity.class, 1);
        assertEquals("the recreation must keep Storage", R.id.storage, destinationOf(recreated));
        assertNoDialog(recreated);

        back();

        assertTrue("Back after a recreation must pop Storage. " + describe(),
                waitUntil(() -> destinationOf(recreated) == R.id.downloads, WAIT_MS));
        SystemClock.sleep(SETTLE_MS);
        assertAlive(recreated);
        assertTrue("Back after a recreation left Downloads. " + describe(),
                createdOf(BrowserMainActivity.class).isEmpty());
    }

    // ── A task running in the background ───────────────────────────────────

    /**
     * A GIF task (its events, without the encoder) started before Downloads
     * opened: the bar shows, and is still there after Home and the icon.
     */
    @Test
    public void t08_runningTaskBarSurvivesLeavingTheApp() {
        BrowserMainActivity browser = openBrowser();
        TaskRepository tasks = mEntryPoint.getTaskRepository();
        assumeTrue("a real task is running; run this when none is", !tasks.isTaskInFlight());
        step("fake GIF task: queued, started, 40%");
        tasks.onTaskQueued();
        mFakeTaskRunning = true;
        tasks.sendEvent(new TaskEvent.Started(ServiceActions.MAKE_GIF));
        tasks.sendEvent(new TaskEvent.Progress(40));

        DownloadsActivity downloads = start(browser, new Intent(browser, DownloadsActivity.class),
                DownloadsActivity.class);
        assertTrue("Downloads opened mid-task must show the bar",
                waitUntil(() -> barShown(downloads), WAIT_MS));

        home(downloads);
        iconTap();

        assertSame(downloads, awaitResumed(DownloadsActivity.class));
        assertTrue("the bar must still show after the icon", waitUntil(() -> barShown(downloads), WAIT_MS));

        step("fake GIF task ended");
        tasks.onTaskEnded();
        mFakeTaskRunning = false;
        assertTrue("the bar must go when the task ends",
                waitUntil(() -> !barShown(downloads), WAIT_MS));
    }

    // ── The lock screen ─────────────────────────────────────────────────────

    /**
     * Locked app: Home on the lock screen, then the icon within the lock
     * timeout. Leaving the app restarts the timeout window, so a lock screen
     * that judged by the timeout let the app open with no authentication.
     */
    @Test
    public void t09_lockScreenStaysUpWhenTheAppComesBack() {
        assumeScreenLock();
        lockApp();
        iconTap();
        LockActivity lock = awaitCreated(LockActivity.class);
        SystemClock.sleep(SETTLE_MS);
        assertAlive(lock);

        long left = System.currentTimeMillis();
        step("Home");
        shell("input keyevent KEYCODE_HOME");
        awaitStage(lock, Stage.STOPPED);
        sendUiHidden();
        assertTrue("leaving the app must mark the lock as required again",
                waitUntil(() -> mPrefs.getLong(Preferences.SETTINGS_APP_LOCK_UPDATE_TIME, 0) >= left
                        && mPrefs.getBoolean(Preferences.SETTINGS_APP_LOCK_REQUIRED, false), WAIT_MS));
        iconTap();

        assertTrue("the lock screen must come back. " + describe(),
                waitUntil(() -> stageOf(lock) == Stage.RESUMED, WAIT_MS));
        SystemClock.sleep(SETTLE_MS);
        assertAlive(lock);
        for (BrowserMainActivity browser : createdOf(BrowserMainActivity.class)) {
            assertTrue("the browser must stay under the lock. " + describe(),
                    stageOf(browser) != Stage.RESUMED);
        }
    }

    /**
     * A lock screen another one already unlocked (as a notification can
     * bring a screen up over it): it finishes when it shows again.
     */
    @Test
    public void t10_lockScreenLeftBehindFinishesOnceUnlocked() {
        assumeScreenLock();
        lockApp();
        iconTap();
        LockActivity lock = awaitCreated(LockActivity.class);
        SystemClock.sleep(SETTLE_MS);
        assertAlive(lock);

        // What a successful authentication on another lock screen writes.
        step("unlocked elsewhere: lock no longer required");
        mPrefs.edit()
                .putBoolean(Preferences.SETTINGS_APP_LOCK_REQUIRED, false)
                .putLong(Preferences.SETTINGS_APP_LOCK_UPDATE_TIME, System.currentTimeMillis())
                .commit();
        SettingsActivity settings = start(lock, new Intent(lock, SettingsActivity.class),
                SettingsActivity.class);
        step("finish Settings, uncovering the lock screen");
        onMain(() -> {
            settings.finish();
            return null;
        });

        awaitDestroyed(lock);
        awaitResumed(BrowserMainActivity.class);
    }

    // ── Background playback ─────────────────────────────────────────────────

    /**
     * Playing a download, the app is left (screen on, no picture-in-picture)
     * and a link from another app brings the browser forward, which closes
     * the player screen: playback goes on, and the notification's tap opens
     * a player that takes over the same playing player.
     */
    @Test
    public void t11_backgroundPlaybackSurvivesTheBrowserComingForward() throws Exception {
        assumeNotificationsAllowed(null);
        BrowserMainActivity browser = openBrowser();
        // Not the cache folder: the app empties it each time it leaves the screen.
        mWav = new File(mApp.getFilesDir(), "taskflow-silence.wav");
        writeSilentWav(mWav, 30);
        DownloadEntity entity = new DownloadEntity();
        entity.setFilePath(mWav.getAbsolutePath());
        entity.setFileUrl(Uri.fromFile(mWav).toString());
        entity.setFileName(mWav.getName());
        entity.setFileMimeType("audio/wav");
        entity.setFileSize(mWav.length());

        PlayerActivity player = start(browser,
                new Intent(browser, PlayerActivity.class).putExtra(Keys.ITEM_ID, entity),
                PlayerActivity.class);
        assertTrue("the player never started playing", waitUntil(() -> onMain(() -> {
            ExoPlayer p = PlaybackHub.player();
            return p != null && p.isPlaying();
        }), WAIT_MS));
        ExoPlayer exo = onMain(PlaybackHub::player);

        step("Home without a leave hint (so no picture-in-picture)");
        shell("am start -W -a android.intent.action.MAIN -c android.intent.category.HOME -f "
                + HOME_NO_HINT_FLAGS);
        assertTrue("the player never left the screen. " + describe(), waitUntil(() ->
                stageOf(player) == Stage.STOPPED || onMain(player::isInPictureInPictureMode), WAIT_MS));
        assertFalse("the player went to picture-in-picture: this phone delivered "
                        + "onUserLeaveHint despite NO_USER_ACTION",
                onMain(player::isInPictureInPictureMode));
        assertTrue("leaving while playing must start background playback",
                waitUntil(() -> onMain(PlaybackHub::isBackgroundActive), WAIT_MS));
        StatusBarNotification playing = awaitNotification("the playback notification",
                sbn -> sbn.getId() == NotificationID.PLAYER_MEDIA_ID);

        step("playing in the background, notification up");
        startBrowserFromOutside();

        awaitDestroyed(player);
        assertSame(browser, awaitResumed(BrowserMainActivity.class));
        assertTrue("closing the player screen from outside must not end background playback",
                onMain(PlaybackHub::isBackgroundActive));
        assertSame("the player must be the same object", exo, onMain(PlaybackHub::player));
        assertTrue("playback must go on", onMain(exo::isPlaying));

        step("tap the playback notification");
        playing.getNotification().contentIntent.send();

        PlayerActivity reopened = awaitNew(PlayerActivity.class, 1);
        assertSame("the reopened screen must take over the playing player",
                exo, onMain(PlaybackHub::player));
        assertTrue("the reopened screen must own the player", onMain(() -> {
            Fragment f = reopened.getSupportFragmentManager()
                    .findFragmentByTag(MediaViewerFragment.class.getSimpleName());
            return f != null && PlaybackHub.isOwner(f);
        }));
        assertTrue("background playback must end once the screen is back",
                waitUntil(() -> !onMain(PlaybackHub::isBackgroundActive), WAIT_MS));
        assertTrue("playback must go on", onMain(exo::isPlaying));
        assertTrue("the playback notification must go with the service",
                waitUntil(() -> findNotification(sbn -> sbn.getId() == NotificationID.PLAYER_MEDIA_ID) == null,
                        WAIT_MS));
    }

    // ── Driving the app ─────────────────────────────────────────────────────

    /* The launcher's intent for the icon, from the shell (another app). */
    private void iconTap() {
        step("icon tap");
        shell("am start -W -a android.intent.action.MAIN -c android.intent.category.LAUNCHER -f "
                + LAUNCHER_FLAGS + " -n " + mPkg + "/" + ALIAS);
    }

    private BrowserMainActivity openBrowser() {
        iconTap();
        BrowserMainActivity browser = awaitResumed(BrowserMainActivity.class);
        SystemClock.sleep(SETTLE_MS);
        return browser;
    }

    /* What another app's link does to the task, without opening a page. */
    private void startBrowserFromOutside() {
        step("start the browser from another app");
        shell("am start -W -a android.intent.action.MAIN -f 0x10000000 -n " + mPkg + "/" + BROWSER);
    }

    /* Downloads as a download notification opens it when the app has no task. */
    private DownloadsActivity startRootDownloads() {
        step("start Downloads with no task, as a notification does");
        shell("am start -W -a " + IntentActions.DOWNLOAD_FINISH + " -f " + SCREEN_FLAGS
                + " -n " + mPkg + "/" + DOWNLOADS);
        DownloadsActivity downloads = awaitResumed(DownloadsActivity.class);
        assertTrue("Downloads must be the bottom of its task", onMain(downloads::isTaskRoot));
        SystemClock.sleep(SETTLE_MS);
        return downloads;
    }

    private void home(Activity top) {
        step("Home");
        shell("input keyevent KEYCODE_HOME");
        awaitStage(top, Stage.STOPPED);
        SystemClock.sleep(SETTLE_MS);
    }

    private void back() {
        step("Back");
        shell("input keyevent KEYCODE_BACK");
    }

    private <T extends Activity> T start(Activity from, Intent intent, Class<T> cls) {
        int before = createdOf(cls).size();
        step("start " + cls.getSimpleName() + " from " + name(from));
        onMain(() -> {
            from.startActivity(intent);
            return null;
        });
        T started = awaitNew(cls, before);
        SystemClock.sleep(SETTLE_MS);
        return started;
    }

    private void navigate(FragmentActivity activity, int destination) {
        step("navigate " + name(activity) + " to " + resName(destination));
        onMain(() -> {
            navController(activity).navigate(destination);
            return null;
        });
        assertTrue(name(activity) + " never reached " + resName(destination) + ", it is on "
                        + resName(destinationOf(activity)),
                waitUntil(() -> destinationOf(activity) == destination, WAIT_MS));
        SystemClock.sleep(SETTLE_MS);
    }

    private int destinationOf(FragmentActivity activity) {
        return onMain(() -> {
            NavDestination d = navController(activity).getCurrentDestination();
            return d == null ? 0 : d.getId();
        });
    }

    private static NavController navController(FragmentActivity activity) {
        NavHostFragment host = (NavHostFragment) activity.getSupportFragmentManager()
                .findFragmentById(R.id.content_frame);
        assertNotNull(host);
        return host.getNavController();
    }

    private boolean barShown(Activity activity) {
        return onMain(() -> {
            View bar = activity.findViewById(R.id.bottom_progress_view);
            return bar != null && bar.getVisibility() == View.VISIBLE && bar.isShown();
        });
    }

    /* A sheet up (a crash report, an update) would take the Back press. It is
     * not dismissed here: dismissing the crash sheet deletes its reports. */
    private void assertNoDialog(FragmentActivity activity) {
        String showing = onMain(() -> dialogsIn(activity.getSupportFragmentManager()));
        assertTrue("a sheet is showing (" + showing + "); close it on the phone and run again",
                showing.isEmpty());
    }

    private static String dialogsIn(FragmentManager fm) {
        StringBuilder out = new StringBuilder();
        for (Fragment f : fm.getFragments()) {
            if (f instanceof DialogFragment) {
                DialogFragment dialog = (DialogFragment) f;
                if (dialog.getDialog() != null && dialog.getDialog().isShowing()) {
                    out.append(f.getClass().getSimpleName()).append(' ');
                }
            }
            if (f.isAdded()) {
                out.append(dialogsIn(f.getChildFragmentManager()));
            }
        }
        return out.toString().trim();
    }

    private void lockApp() {
        step("app lock on, required, 5 min timeout");
        mPrefs.edit()
                .putBoolean(Preferences.SETTINGS_APP_LOCK, true)
                .putString(Preferences.SETTINGS_APP_LOCK_TIME, "300000")
                .putBoolean(Preferences.SETTINGS_APP_LOCK_REQUIRED, true)
                .putLong(Preferences.SETTINGS_APP_LOCK_UPDATE_TIME, 0)
                .commit();
    }

    private void assumeScreenLock() {
        int can = BiometricManager.from(mApp).canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG
                | BiometricManager.Authenticators.DEVICE_CREDENTIAL);
        assumeTrue("the app lock needs a screen lock set on the phone",
                can == BiometricManager.BIOMETRIC_SUCCESS);
    }

    /* Not granted here: revoking a permission again kills the app, which
     * would end the run, and the user's choice is theirs. */
    private void assumeNotificationsAllowed(String channelId) {
        assumeTrue("allow Firedown's notifications to run this test",
                NotificationManagerCompat.from(mApp).areNotificationsEnabled());
        if (channelId != null) {
            NotificationChannel channel = mApp.getSystemService(NotificationManager.class)
                    .getNotificationChannel(channelId);
            assumeTrue("the " + channelId + " notification channel is off",
                    channel == null || channel.getImportance() != NotificationManager.IMPORTANCE_NONE);
        }
    }

    private StatusBarNotification awaitNotification(String what, Predicate<StatusBarNotification> match) {
        AtomicReference<StatusBarNotification> found = new AtomicReference<>();
        assertTrue("no notification: " + what, waitUntil(() -> {
            found.set(findNotification(match));
            return found.get() != null;
        }, WAIT_MS));
        StatusBarNotification sbn = found.get();
        step("notification up: id=" + sbn.getId() + " \"" + titleOf(sbn) + "\"");
        assertNotNull("the notification has no tap action: " + what, sbn.getNotification().contentIntent);
        return sbn;
    }

    private StatusBarNotification findNotification(Predicate<StatusBarNotification> match) {
        NotificationManager nm = mApp.getSystemService(NotificationManager.class);
        for (StatusBarNotification sbn : nm.getActiveNotifications()) {
            if (match.test(sbn)) {
                return sbn;
            }
        }
        return null;
    }

    private static CharSequence titleOf(StatusBarNotification sbn) {
        CharSequence title = sbn.getNotification().extras.getCharSequence(Notification.EXTRA_TITLE);
        return title == null ? "" : title;
    }

    // ── Clean-up ────────────────────────────────────────────────────────────

    private void stopPlayback() {
        ExoPlayer exo = onMain(PlaybackHub::player);
        if (exo == null) {
            return;
        }
        onMain(() -> {
            exo.pause();
            return null;
        });
        if (onMain(PlaybackHub::isBackgroundActive)) {
            try {
                mApp.startService(new Intent(mApp, PlayerPlaybackService.class)
                        .setAction(PlayerPlaybackService.ACTION_STOP));
            } catch (IllegalStateException e) {
                Log.w(TAG, "could not stop background playback", e);
            }
            waitUntil(() -> !onMain(PlaybackHub::isBackgroundActive), 5_000);
        }
    }

    /* Through the service's own delete, while an activity is still up (a
     * service start from the background is refused). */
    private void deleteDownloads() {
        if (mDownloadUrls.isEmpty()) {
            return;
        }
        DownloadDataRepository repo = mEntryPoint.getDownloadDataRepository();
        ArrayList<DownloadEntity> ours = new ArrayList<>();
        for (DownloadEntity e : repo.getAllRawList()) {
            if (mDownloadUrls.contains(e.getFileUrl())) {
                ours.add(e);
            }
        }
        if (ours.isEmpty()) {
            return;
        }
        try {
            Intent delete = new Intent(mApp, RunnableManager.class);
            delete.setAction(IntentActions.DOWNLOAD_DELETE);
            delete.putParcelableArrayListExtra(Keys.ITEM_LIST_ID, ours);
            mApp.startService(delete);
            waitUntil(() -> {
                for (DownloadEntity e : repo.getAllRawList()) {
                    if (mDownloadUrls.contains(e.getFileUrl())) {
                        return false;
                    }
                }
                return true;
            }, 30_000);
        } catch (IllegalStateException e) {
            Log.w(TAG, "could not delete the test download", e);
        }
        for (DownloadEntity e : ours) {
            if (e.getFilePath() != null) {
                new File(e.getFilePath()).delete();
            }
        }
    }

    /* Every activity Android knows of, the ones a previous test left included. */
    private void finishAll() {
        onMain(() -> {
            for (Activity a : aliveActivities()) {
                if (!a.isFinishing()) {
                    a.finish();
                }
            }
            return null;
        });
        if (!waitUntil(() -> onMain(this::aliveActivities).isEmpty(), WAIT_MS)) {
            Log.w(TAG, "activities still alive after finishing: " + describe());
        }
    }

    /* Main thread only. */
    private List<Activity> aliveActivities() {
        List<Activity> out = new ArrayList<>();
        for (Stage stage : Stage.values()) {
            if (stage == Stage.DESTROYED) {
                continue;
            }
            Collection<Activity> in = ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(stage);
            out.addAll(in);
        }
        return out;
    }

    private static void save(SharedPreferences prefs, String[] keys, String prefix, Map<String, Object> into) {
        into.clear();
        for (Map.Entry<String, ?> e : prefs.getAll().entrySet()) {
            boolean ours = keys != null ? Arrays.asList(keys).contains(e.getKey()) : e.getKey().startsWith(prefix);
            if (ours) {
                into.put(e.getKey(), e.getValue());
            }
        }
    }

    private static void restore(SharedPreferences prefs, Map<String, Object> saved, String[] keys, String prefix) {
        SharedPreferences.Editor editor = prefs.edit();
        for (String key : prefs.getAll().keySet()) {
            boolean ours = keys != null ? Arrays.asList(keys).contains(key) : key.startsWith(prefix);
            if (ours && !saved.containsKey(key)) {
                editor.remove(key);
            }
        }
        for (Map.Entry<String, Object> e : saved.entrySet()) {
            Object v = e.getValue();
            if (v instanceof Boolean) {
                editor.putBoolean(e.getKey(), (Boolean) v);
            } else if (v instanceof Long) {
                editor.putLong(e.getKey(), (Long) v);
            } else if (v instanceof Integer) {
                editor.putInt(e.getKey(), (Integer) v);
            } else if (v instanceof Float) {
                editor.putFloat(e.getKey(), (Float) v);
            } else if (v instanceof String) {
                editor.putString(e.getKey(), (String) v);
            }
        }
        editor.commit();
    }

    /* 16-bit mono PCM at 8 kHz, all zeros: plays without a sound. */
    private static void writeSilentWav(File file, int seconds) throws IOException {
        int rate = 8000;
        int dataLength = rate * 2 * seconds;
        ByteBuffer header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN);
        header.put("RIFF".getBytes(StandardCharsets.US_ASCII));
        header.putInt(36 + dataLength);
        header.put("WAVE".getBytes(StandardCharsets.US_ASCII));
        header.put("fmt ".getBytes(StandardCharsets.US_ASCII));
        header.putInt(16);
        header.putShort((short) 1);
        header.putShort((short) 1);
        header.putInt(rate);
        header.putInt(rate * 2);
        header.putShort((short) 2);
        header.putShort((short) 16);
        header.put("data".getBytes(StandardCharsets.US_ASCII));
        header.putInt(dataLength);
        try (FileOutputStream out = new FileOutputStream(file)) {
            out.write(header.array());
            out.write(new byte[dataLength]);
        }
    }

    // ── Watching the activities ─────────────────────────────────────────────

    /* Every activity the app creates and the last lifecycle stage it reached,
     * by instance: "the same screen" is the same object. */
    private final class Recorder implements ActivityLifecycleCallback {
        final List<Activity> created = Collections.synchronizedList(new ArrayList<>());
        final Map<Activity, Stage> stages = Collections.synchronizedMap(new IdentityHashMap<>());

        @Override
        public void onActivityLifecycleChanged(Activity activity, Stage stage) {
            if (stage == Stage.PRE_ON_CREATE) {
                created.add(activity);
            }
            stages.put(activity, stage);
            step("  " + name(activity) + " → " + stage);
        }

        void clear() {
            created.clear();
            stages.clear();
        }
    }

    private <T extends Activity> List<T> createdOf(Class<T> cls) {
        List<T> out = new ArrayList<>();
        synchronized (mRecorder.created) {
            for (Activity a : mRecorder.created) {
                if (a.getClass() == cls) {
                    out.add(cls.cast(a));
                }
            }
        }
        return out;
    }

    private Stage stageOf(Activity activity) {
        return mRecorder.stages.get(activity);
    }

    private <T extends Activity> T resumedOf(Class<T> cls) {
        for (T a : createdOf(cls)) {
            if (stageOf(a) == Stage.RESUMED) {
                return a;
            }
        }
        return null;
    }

    private <T extends Activity> T awaitResumed(Class<T> cls) {
        assertTrue(cls.getSimpleName() + " never resumed. " + describe(),
                waitUntil(() -> resumedOf(cls) != null, WAIT_MS));
        T resumed = resumedOf(cls);
        step("resumed: " + name(resumed));
        return resumed;
    }

    /* A new instance (more than {@code before} created) that reached RESUMED. */
    private <T extends Activity> T awaitNew(Class<T> cls, int before) {
        assertTrue("no new " + cls.getSimpleName() + " resumed. " + describe(), waitUntil(() -> {
            List<T> all = createdOf(cls);
            return all.size() > before && stageOf(all.get(all.size() - 1)) == Stage.RESUMED;
        }, WAIT_MS));
        List<T> all = createdOf(cls);
        T created = all.get(all.size() - 1);
        step("new and resumed: " + name(created));
        return created;
    }

    /* A lock screen may stay paused under the system's authentication prompt. */
    private <T extends Activity> T awaitCreated(Class<T> cls) {
        assertTrue("no " + cls.getSimpleName() + " was created. " + describe(),
                waitUntil(() -> !createdOf(cls).isEmpty(), WAIT_MS));
        List<T> all = createdOf(cls);
        T created = all.get(all.size() - 1);
        step("created: " + name(created) + " (" + stageOf(created) + ")");
        return created;
    }

    private void awaitStage(Activity activity, Stage stage) {
        assertTrue(activity.getClass().getSimpleName() + " never reached " + stage + ". " + describe(),
                waitUntil(() -> stageOf(activity) == stage, WAIT_MS));
    }

    private void awaitDestroyed(Activity activity) {
        awaitStage(activity, Stage.DESTROYED);
    }

    private void assertAlive(Activity activity) {
        String name = activity.getClass().getSimpleName();
        assertTrue(name + " was destroyed. " + describe(), stageOf(activity) != Stage.DESTROYED);
        assertFalse(name + " is finishing. " + describe(), onMain(activity::isFinishing));
    }

    private String describe() {
        StringBuilder out = new StringBuilder("Activities:");
        synchronized (mRecorder.created) {
            for (Activity a : mRecorder.created) {
                out.append(' ').append(name(a)).append('=').append(stageOf(a));
            }
        }
        return out.toString();
    }

    // ── Telling what happened ───────────────────────────────────────────────

    /* One line of the trail: logged now, and kept for the failure report. */
    private void step(String what) {
        String line = String.format(Locale.US, "%7.2fs  %s",
                (SystemClock.uptimeMillis() - mTestStart) / 1000.0, what);
        mSteps.add(line);
        Log.i(TAG, mTestName + " " + line);
    }

    private String elapsed() {
        return String.format(Locale.US, "%.1f s", (SystemClock.uptimeMillis() - mTestStart) / 1000.0);
    }

    private static String name(Activity activity) {
        if (activity == null) {
            return "null";
        }
        return activity.getClass().getSimpleName() + "@" + Integer.toHexString(System.identityHashCode(activity));
    }

    private String resName(int id) {
        if (id == 0) {
            return "nothing";
        }
        try {
            return mApp.getResources().getResourceEntryName(id);
        } catch (RuntimeException e) {
            return "0x" + Integer.toHexString(id);
        }
    }

    /* Android sends TRIM_MEMORY_UI_HIDDEN only to a process in the
     * background, and a process under instrumentation never is: the activity
     * manager keeps it at foreground-service importance for the whole run.
     * So leaving the app here never reaches the app's "left the screen"
     * handler (ApplicationLifeCycleHandler.onTrimMemory: the lock marked
     * required, the cache folder emptied, the backup mirror written). Sent
     * as Android sends it, through the Application to every callback. */
    private void sendUiHidden() {
        step("send TRIM_MEMORY_UI_HIDDEN (Android withholds it from an instrumented process)");
        onMain(() -> {
            ((Application) mApp).onTrimMemory(ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN);
            return null;
        });
    }

    /* The failure with what the phone looked like at that moment. */
    private Throwable withReport(Throwable failure) {
        String report = report(failure);
        for (String line : report.split("\n")) {
            Log.e(TAG, line);
        }
        String message = failure instanceof AssertionError && failure.getMessage() != null
                ? failure.getMessage() : failure.toString();
        AssertionError out = new AssertionError(message + "\n\n" + report);
        out.setStackTrace(failure.getStackTrace());
        if (failure.getCause() != null) {
            out.initCause(failure.getCause());
        }
        for (Throwable suppressed : failure.getSuppressed()) {
            out.addSuppressed(suppressed);
        }
        return out;
    }

    private String report(Throwable failure) {
        StringBuilder out = new StringBuilder();
        out.append("FAIL ").append(mTestName).append(" after ").append(elapsed()).append(": ")
                .append(failure).append('\n');
        out.append("Steps:\n");
        synchronized (mSteps) {
            for (String line : mSteps) {
                out.append("  ").append(line).append('\n');
            }
        }
        out.append(describe()).append('\n');
        out.append("Android's task stack (dumpsys activity activities, our lines):\n");
        out.append(safely(this::taskStack));
        out.append("Our notifications:\n");
        out.append(safely(this::notifications));
        out.append("App state:\n");
        out.append(safely(this::appState));
        return out.toString();
    }

    private static String safely(Callable<String> part) {
        try {
            return part.call();
        } catch (Throwable t) {
            return "  (could not read: " + t + ")\n";
        }
    }

    private String taskStack() {
        String dump = runShell("dumpsys activity activities");
        StringBuilder out = new StringBuilder();
        int kept = 0;
        for (String line : dump.split("\n")) {
            String t = line.trim();
            boolean ours = t.contains(mPkg) && (t.startsWith("* Task{") || t.contains("Hist"));
            boolean focus = t.startsWith("ResumedActivity") || t.startsWith("mResumedActivity")
                    || t.startsWith("topResumedActivity") || t.startsWith("mFocusedApp");
            if (ours || focus) {
                out.append("  ").append(t).append('\n');
                kept++;
                if (kept >= 40) {
                    out.append("  …\n");
                    break;
                }
            }
        }
        return kept == 0 ? "  (none)\n" : out.toString();
    }

    private String notifications() {
        StringBuilder out = new StringBuilder();
        for (StatusBarNotification sbn : mApp.getSystemService(NotificationManager.class).getActiveNotifications()) {
            out.append("  id=").append(sbn.getId())
                    .append(" channel=").append(sbn.getNotification().getChannelId())
                    .append(" \"").append(titleOf(sbn)).append("\"\n");
        }
        return out.length() == 0 ? "  (none)\n" : out.toString();
    }

    private String appState() {
        PowerManager power = mApp.getSystemService(PowerManager.class);
        KeyguardManager keyguard = mApp.getSystemService(KeyguardManager.class);
        long left = mPrefs.getLong(Preferences.SETTINGS_APP_LOCK_UPDATE_TIME, 0);
        String player = onMainWithin(() -> {
            ExoPlayer p = PlaybackHub.player();
            return (p == null ? "none" : (p.isPlaying() ? "playing" : "not playing, state " + p.getPlaybackState()))
                    + ", background playback " + PlaybackHub.isBackgroundActive();
        }, 3_000, "(the main thread did not answer in 3 s)");
        return "  screen on " + power.isInteractive() + ", keyguard locked " + keyguard.isKeyguardLocked() + "\n"
                + "  app lock: on " + mPrefs.getBoolean(Preferences.SETTINGS_APP_LOCK, false)
                + ", required " + mPrefs.getBoolean(Preferences.SETTINGS_APP_LOCK_REQUIRED, true)
                + ", timeout " + mPrefs.getString(Preferences.SETTINGS_APP_LOCK_TIME, "0") + " ms"
                + ", left " + (left == 0 ? "never" : (System.currentTimeMillis() - left) + " ms ago") + "\n"
                + "  player: " + player + "\n"
                + "  task in flight " + mEntryPoint.getTaskRepository().isTaskInFlight() + "\n";
    }

    // ── Threads and the shell ───────────────────────────────────────────────

    private <T> T onMain(Callable<T> call) {
        AtomicReference<T> result = new AtomicReference<>();
        AtomicReference<Throwable> error = new AtomicReference<>();
        mInstrumentation.runOnMainSync(() -> {
            try {
                result.set(call.call());
            } catch (Throwable t) {
                error.set(t);
            }
        });
        if (error.get() != null) {
            throw new AssertionError(error.get());
        }
        return result.get();
    }

    /* For the failure report: a main thread that is stuck must not hang it. */
    private static <T> T onMainWithin(Callable<T> call, long ms, T otherwise) {
        AtomicReference<T> result = new AtomicReference<>(otherwise);
        CountDownLatch done = new CountDownLatch(1);
        new Handler(Looper.getMainLooper()).post(() -> {
            try {
                result.set(call.call());
            } catch (Throwable t) {
                Log.w(TAG, "report read failed", t);
            } finally {
                done.countDown();
            }
        });
        try {
            done.await(ms, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return result.get();
    }

    private static boolean waitUntil(BooleanSupplier condition, long ms) {
        long deadline = SystemClock.uptimeMillis() + ms;
        while (!condition.getAsBoolean()) {
            if (SystemClock.uptimeMillis() > deadline) {
                return false;
            }
            SystemClock.sleep(200);
        }
        return true;
    }

    private String shell(String command) {
        String out = shellStatic(command);
        StringBuilder answer = new StringBuilder();
        for (String line : out.split("\n")) {
            String t = line.trim();
            if (t.startsWith("Status:") || t.startsWith("LaunchState:") || t.startsWith("Activity:")
                    || t.startsWith("Warning:") || t.startsWith("Error:") || t.startsWith("Exception")) {
                answer.append(answer.length() == 0 ? "" : ", ").append(t);
            }
        }
        if (answer.length() > 0) {
            step("  → " + answer);
        }
        return out;
    }

    /* As the shell user: another app for Android, allowed to start
     * activities from the background like a launcher. */
    private static String shellStatic(String command) {
        Log.i(TAG, "$ " + command);
        String result = runShell(command);
        if (!result.isEmpty()) {
            Log.i(TAG, result.trim());
        }
        return result;
    }

    private static String runShell(String command) {
        ParcelFileDescriptor pfd = InstrumentationRegistry.getInstrumentation()
                .getUiAutomation().executeShellCommand(command);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (InputStream in = new ParcelFileDescriptor.AutoCloseInputStream(pfd)) {
            byte[] buffer = new byte[4096];
            int n = in.read(buffer);
            while (n != -1) {
                out.write(buffer, 0, n);
                n = in.read(buffer);
            }
        } catch (IOException e) {
            throw new AssertionError("shell: " + command, e);
        }
        return out.toString();
    }
}
