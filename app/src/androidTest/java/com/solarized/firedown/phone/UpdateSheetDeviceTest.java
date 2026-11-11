package com.solarized.firedown.phone;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.app.KeyguardManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.ParcelFileDescriptor;
import android.os.SystemClock;
import android.view.View;
import android.widget.TextView;

import androidx.fragment.app.DialogFragment;
import androidx.fragment.app.Fragment;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.solarized.firedown.App;
import com.solarized.firedown.Keys;
import com.solarized.firedown.Preferences;
import com.solarized.firedown.R;
import com.solarized.firedown.UpdateAvailableSheet;
import com.solarized.firedown.UpdateDownloader;
import com.solarized.firedown.UpdatePromptPolicy;
import com.solarized.firedown.data.di.RepositoryEntryPoint;
import com.solarized.firedown.data.repository.TaskRepository;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.concurrent.atomic.AtomicReference;

import dagger.hilt.android.EntryPointAccessors;

/**
 * The "Update ready" sheet on a device: it opens by itself on Downloads with
 * no download size and Install as its only button; Back (the dialog's
 * cancel) snoozes it and it stays away; while a task runs it does not open by itself, and opened on purpose
 * (the ⋮ row's way in) it says what would stop and writes no snooze.
 *
 * <p>An update is faked as the debug UpdateTestReceiver does: the ready
 * record in the preferences and a file where the APK goes. Nothing is
 * installed. Needs the phone unlocked and nothing running in Firedown.
 *
 * <p>Run:
 * {@code ./gradlew connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.solarized.firedown.phone.UpdateSheetDeviceTest}
 */
@RunWith(AndroidJUnit4.class)
public class UpdateSheetDeviceTest {

    /** UpdateAvailableSheet's fragment tag. */
    private static final String SHEET_TAG = "UpdateAvailableSheet";
    private static final String TEST_NAME = "Firedown 99.9.9 (test)";
    private static final long SHOW_MS = 10_000;
    /** How long "does not open" is watched for. */
    private static final long QUIET_MS = 3_000;
    private static final long POLL_MS = 100;

    private Context mApp;
    private SharedPreferences mPrefs;
    private TaskRepository mTasks;
    private ActivityScenario<DownloadsActivity> mScenario;
    private boolean mAppLockWas;
    private boolean mTaskQueued;
    private int mReadyCode;

    @Before
    public void setUp() throws IOException {
        mApp = InstrumentationRegistry.getInstrumentation().getTargetContext().getApplicationContext();
        RepositoryEntryPoint ep = EntryPointAccessors.fromApplication(mApp, RepositoryEntryPoint.class);
        mPrefs = ep.getSharedPreferences();
        mTasks = ep.getTaskRepository();

        shell("input keyevent KEYCODE_WAKEUP");
        shell("wm dismiss-keyguard");
        KeyguardManager keyguard = mApp.getSystemService(KeyguardManager.class);
        long unlockBy = SystemClock.elapsedRealtime() + 3_000;
        while (keyguard.isKeyguardLocked() && SystemClock.elapsedRealtime() < unlockBy) {
            SystemClock.sleep(POLL_MS);
        }
        assertFalse("the phone is locked: unlock it before running this test",
                keyguard.isKeyguardLocked());
        mAppLockWas = mPrefs.getBoolean(Preferences.SETTINGS_APP_LOCK, false);
        mPrefs.edit().putBoolean(Preferences.SETTINGS_APP_LOCK, false).commit();

        AtomicReference<UpdatePromptPolicy.Busy> busy = new AtomicReference<>();
        onMain(() -> busy.set(UpdateAvailableSheet.busyNow(mApp)));
        assertEquals("something is running in Firedown; let it finish first",
                UpdatePromptPolicy.Busy.NONE, busy.get());

        mReadyCode = App.getVersionCode() + 1;
        File apk = Preferences.getUpdateApkFile(mApp);
        File dir = apk.getParentFile();
        if (dir != null && !dir.exists()) {
            assertTrue(dir.mkdirs());
        }
        try (FileOutputStream out = new FileOutputStream(apk)) {
            out.write(new byte[]{'P', 'K', 3, 4});
        }
        UpdateDownloader.setChangelog(mApp, mReadyCode, TEST_NAME,
                "• A first change\n• A second change long enough to wrap onto a second line on a phone");
        mPrefs.edit()
                .putBoolean(Keys.UPDATE_READY, true)
                .putInt(Keys.UPDATE_READY_VERSION_CODE, mReadyCode)
                .putString(Keys.UPDATE_READY_NAME, TEST_NAME)
                .remove(Keys.UPDATE_PROMPT_SNOOZE_VERSION)
                .remove(Keys.UPDATE_PROMPT_SNOOZE_UNTIL)
                .remove(Keys.UPDATE_PROMPT_SNOOZE_COUNT)
                .commit();
    }

    @After
    public void tearDown() {
        if (mScenario != null) {
            mScenario.close();
        }
        if (mTaskQueued) {
            mTasks.onTaskEnded();
        }
        UpdateDownloader.clearReady(mApp);
        mPrefs.edit()
                .remove(Keys.UPDATE_PROMPT_SNOOZE_VERSION)
                .remove(Keys.UPDATE_PROMPT_SNOOZE_UNTIL)
                .remove(Keys.UPDATE_PROMPT_SNOOZE_COUNT)
                .putBoolean(Preferences.SETTINGS_APP_LOCK, mAppLockWas)
                .commit();
    }

    @Test
    public void opensOnDownloadsWithNoSizeAndInstallAlone() {
        launch();
        View sheet = awaitSheet();
        AtomicReference<String> version = new AtomicReference<>();
        AtomicReference<Integer> install = new AtomicReference<>();
        AtomicReference<Integer> busyLine = new AtomicReference<>();
        onMain(() -> {
            version.set(((TextView) sheet.findViewById(R.id.update_sheet_version)).getText().toString());
            install.set(sheet.findViewById(R.id.update_sheet_install).getVisibility());
            busyLine.set(sheet.findViewById(R.id.update_sheet_busy).getVisibility());
        });
        // The version alone: no "· 213 MB" after it.
        assertEquals(TEST_NAME, version.get());
        assertEquals(View.VISIBLE, (int) install.get());
        assertEquals("nothing runs, so nothing would stop", View.GONE, (int) busyLine.get());
    }

    @Test
    public void backSnoozesAndTheSheetStaysAway() {
        launch();
        View sheet = awaitSheet();
        long before = System.currentTimeMillis();
        pressBackOnSheet();
        awaitNoSheet();
        assertEquals(mReadyCode, mPrefs.getInt(Keys.UPDATE_PROMPT_SNOOZE_VERSION, -1));
        assertEquals(1, mPrefs.getInt(Keys.UPDATE_PROMPT_SNOOZE_COUNT, 0));
        assertTrue("snoozed for about a day",
                mPrefs.getLong(Keys.UPDATE_PROMPT_SNOOZE_UNTIL, 0) >= before + UpdatePromptPolicy.SNOOZE_INTERVAL_MS - 1000);

        // Back to the screen: the snooze holds it back.
        mScenario.recreate();
        assertStaysAway("a snoozed update opened again");
    }

    @Test
    public void heldWhileATaskRunsAndSaysSoWhenOpenedOnPurpose() {
        mTasks.onTaskQueued();
        mTaskQueued = true;
        launch();
        assertStaysAway("the sheet opened by itself while a task ran");

        // The ⋮ row's way in: opens anyway, and says what would stop.
        mScenario.onActivity(activity ->
                UpdateAvailableSheet.openOnPurpose(activity, activity.getSupportFragmentManager()));
        View sheet = awaitSheet();
        AtomicReference<Integer> busyLine = new AtomicReference<>();
        AtomicReference<String> busyText = new AtomicReference<>();
        onMain(() -> {
            TextView line = sheet.findViewById(R.id.update_sheet_busy);
            busyLine.set(line.getVisibility());
            busyText.set(line.getText().toString());
        });
        assertEquals(View.VISIBLE, (int) busyLine.get());
        assertEquals(mApp.getString(R.string.update_busy_task), busyText.get());

        pressBackOnSheet();
        awaitNoSheet();
        assertEquals("a sheet the user opened writes no snooze",
                -1, mPrefs.getInt(Keys.UPDATE_PROMPT_SNOOZE_VERSION, -1));
    }

    // ── helpers ──────────────────────────────────────────────────────────

    /** A plain intent: launch(Class) sends ACTION_MAIN, which the app clears. */
    private void launch() {
        mScenario = ActivityScenario.launch(new Intent(mApp, DownloadsActivity.class));
    }

    private Fragment sheetFragment() {
        AtomicReference<Fragment> found = new AtomicReference<>();
        mScenario.onActivity(activity ->
                found.set(activity.getSupportFragmentManager().findFragmentByTag(SHEET_TAG)));
        return found.get();
    }

    /** Back on the sheet: the dialog's cancel, which is how it is closed now. */
    private void pressBackOnSheet() {
        Fragment f = sheetFragment();
        assertTrue("no update sheet to close", f instanceof DialogFragment);
        DialogFragment sheet = (DialogFragment) f;
        onMain(() -> sheet.requireDialog().cancel());
    }

    private View awaitSheet() {
        long deadline = SystemClock.elapsedRealtime() + SHOW_MS;
        while (SystemClock.elapsedRealtime() < deadline) {
            Fragment f = sheetFragment();
            if (f != null && f.getView() != null && f.isResumed()) {
                return f.getView();
            }
            SystemClock.sleep(POLL_MS);
        }
        throw new AssertionError("the update sheet did not open within " + SHOW_MS + " ms");
    }

    private void awaitNoSheet() {
        long deadline = SystemClock.elapsedRealtime() + SHOW_MS;
        while (SystemClock.elapsedRealtime() < deadline) {
            if (sheetFragment() == null) {
                return;
            }
            SystemClock.sleep(POLL_MS);
        }
        throw new AssertionError("the update sheet did not close");
    }

    private void assertStaysAway(String message) {
        long until = SystemClock.elapsedRealtime() + QUIET_MS;
        while (SystemClock.elapsedRealtime() < until) {
            assertNull(message, sheetFragment());
            SystemClock.sleep(POLL_MS);
        }
    }

    private static void shell(String command) {
        ParcelFileDescriptor pfd = InstrumentationRegistry.getInstrumentation()
                .getUiAutomation().executeShellCommand(command);
        try (InputStream in = new ParcelFileDescriptor.AutoCloseInputStream(pfd)) {
            byte[] buffer = new byte[1024];
            while (in.read(buffer) != -1) {
                // drain, so the command has finished when this returns
            }
        } catch (IOException ignored) {
        }
    }

    private static void onMain(Runnable r) {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(r);
    }
}
