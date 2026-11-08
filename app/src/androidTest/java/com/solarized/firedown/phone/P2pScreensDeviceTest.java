package com.solarized.firedown.phone;

import static androidx.test.espresso.Espresso.onView;
import static androidx.test.espresso.action.ViewActions.click;
import static androidx.test.espresso.assertion.ViewAssertions.matches;
import static androidx.test.espresso.matcher.ViewMatchers.Visibility;
import static androidx.test.espresso.matcher.ViewMatchers.isDisplayed;
import static androidx.test.espresso.matcher.ViewMatchers.withEffectiveVisibility;
import static androidx.test.espresso.matcher.ViewMatchers.withId;
import static androidx.test.espresso.matcher.ViewMatchers.withText;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.app.KeyguardManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.os.SystemClock;
import android.view.View;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.navigation.NavController;
import androidx.navigation.fragment.NavHostFragment;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.solarized.firedown.Keys;
import com.solarized.firedown.Preferences;
import com.solarized.firedown.R;
import com.solarized.firedown.data.Download;
import com.solarized.firedown.data.di.RepositoryEntryPoint;
import com.solarized.firedown.data.entity.DownloadEntity;
import com.solarized.firedown.utils.FileUriHelper;
import com.solarized.firedown.p2pshare.P2pShareController;
import com.solarized.firedown.p2pshare.ShareState;
import com.solarized.firedown.phone.fragments.P2pReceiveFragment;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Random;
import java.util.concurrent.atomic.AtomicReference;

import dagger.hilt.android.EntryPointAccessors;

/**
 * The Send directly screens of 1.2.7 on a device, driven like a user: the
 * real Downloads activity, the real share session (hidden engine page, ICE,
 * the offer), the real Send and Receive screens. One phone is enough: the
 * Send screen's own offer is handed to the Receive screen, which shows it as
 * an incoming request.
 *
 * <ul>
 *   <li>Send: the list is "Share a link" and "Someone next to you"; the
 *       latter unfolds to the QR on its tile with the receiving path under
 *       it, and folds again.</li>
 *   <li>Receive: the request is the screen's hero — who is sending, the file
 *       type's tile with a TalkBack label, the name, Decline / Accept — and
 *       Decline returns to "Visible as".</li>
 * </ul>
 *
 * <p>Needs no second phone; with a network the relay credentials are
 * fetched, without one the offer is minted with local candidates only. A
 * phone on the same Wi-Fi with Receive a file open would be listed in the
 * unfolded row; the test accepts either shape.
 *
 * <p>Run:
 * {@code ./gradlew connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.solarized.firedown.phone.P2pScreensDeviceTest}
 */
@RunWith(AndroidJUnit4.class)
public class P2pScreensDeviceTest {

    private static final long OFFER_MS = 60_000;
    private static final long STAGE_MS = 30_000;
    private static final long POLL_MS = 200;
    private static final String FILE_NAME = "p2p-device-test.bin";

    private Context mApp;
    private P2pShareController mController;
    private File mFile;
    private ActivityScenario<DownloadsActivity> mScenario;
    private SharedPreferences mPrefs;
    private boolean mAppLockWas;

    @Before
    public void setUp() throws IOException {
        mApp = InstrumentationRegistry.getInstrumentation().getTargetContext().getApplicationContext();
        onMain(() -> mController = EntryPointAccessors.fromApplication(mApp, RepositoryEntryPoint.class)
                .getP2pShareController());
        assertNotNull(mController);
        if (Build.VERSION.SDK_INT >= 33) {
            InstrumentationRegistry.getInstrumentation().getUiAutomation()
                    .grantRuntimePermission(mApp.getPackageName(), "android.permission.POST_NOTIFICATIONS");
        }
        // A screen lock or the user's app lock puts an activity over
        // Downloads, which then never resumes (the first run failed so).
        shell("input keyevent KEYCODE_WAKEUP");
        shell("wm dismiss-keyguard");
        KeyguardManager keyguard = mApp.getSystemService(KeyguardManager.class);
        long unlockBy = SystemClock.elapsedRealtime() + 3_000;
        while (keyguard.isKeyguardLocked() && SystemClock.elapsedRealtime() < unlockBy) {
            SystemClock.sleep(POLL_MS);
        }
        assertFalse("the phone is locked: unlock it before running this test",
                keyguard.isKeyguardLocked());
        onMain(() -> mPrefs = EntryPointAccessors.fromApplication(mApp, RepositoryEntryPoint.class)
                .getSharedPreferences());
        mAppLockWas = mPrefs.getBoolean(Preferences.SETTINGS_APP_LOCK, false);
        mPrefs.edit().putBoolean(Preferences.SETTINGS_APP_LOCK, false).commit();

        mFile = new File(mApp.getFilesDir(), FILE_NAME);
        byte[] bytes = new byte[256 * 1024];
        new Random(7).nextBytes(bytes);
        try (FileOutputStream out = new FileOutputStream(mFile)) {
            out.write(bytes);
        }
        try {
            // A plain intent, never launch(Class): that one is ACTION_MAIN,
            // which IntentHandler clears (setAction("")) in onResume, and
            // ActivityScenario finds its activity by intent equality, so it
            // never saw RESUMED and timed out at STARTED.
            mScenario = ActivityScenario.launch(new Intent(mApp, DownloadsActivity.class));
        } catch (AssertionError notResumed) {
            // Something stayed on top of Downloads: name it, so the failure
            // says what to fix instead of only "never RESUMED".
            throw new AssertionError(notResumed.getMessage() + "\nOn top: " + whatIsOnTop(), notResumed);
        }
    }

    /** The resumed activity and the focused window, from the system's own dumps. */
    private static String whatIsOnTop() {
        StringBuilder top = new StringBuilder();
        for (String line : shellOut("dumpsys activity activities").split("\n")) {
            String t = line.trim();
            if (t.startsWith("mResumedActivity") || t.startsWith("topResumedActivity")
                    || t.startsWith("ResumedActivity")) {
                top.append(t).append("; ");
            }
        }
        for (String line : shellOut("dumpsys window windows").split("\n")) {
            String t = line.trim();
            if (t.startsWith("mCurrentFocus") || t.startsWith("mFocusedApp")) {
                top.append(t).append("; ");
            }
        }
        return top.length() == 0 ? "(nothing reported)" : top.toString();
    }

    private static String shellOut(String command) {
        ParcelFileDescriptor pfd = InstrumentationRegistry.getInstrumentation()
                .getUiAutomation().executeShellCommand(command);
        StringBuilder out = new StringBuilder();
        try (InputStream in = new ParcelFileDescriptor.AutoCloseInputStream(pfd)) {
            byte[] buffer = new byte[8192];
            int n = in.read(buffer);
            while (n != -1) {
                out.append(new String(buffer, 0, n, StandardCharsets.UTF_8));
                n = in.read(buffer);
            }
        } catch (IOException ignored) {
        }
        return out.toString();
    }

    @After
    public void tearDown() {
        onMain(() -> {
            if (mController != null) {
                mController.stop();
                mController.dismissFinished();
            }
        });
        if (mScenario != null) {
            mScenario.close();
        }
        if (mFile != null) {
            //noinspection ResultOfMethodCallIgnored
            mFile.delete();
        }
        if (mPrefs != null) {
            mPrefs.edit().putBoolean(Preferences.SETTINGS_APP_LOCK, mAppLockWas).commit();
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

    @Test
    public void sendAndReceiveScreens() throws Exception {
        // ── Send ────────────────────────────────────────────────────────────
        DownloadEntity entity = new DownloadEntity();
        entity.setId(0x7E570001);
        entity.setFileName(FILE_NAME);
        entity.setFilePath(mFile.getAbsolutePath());
        entity.setFileMimeType(FileUriHelper.getMimeTypeFromFile(FILE_NAME));
        entity.setFileSize(mFile.length());
        entity.setFileStatus(Download.FINISHED);
        Bundle args = new Bundle();
        args.putParcelable(Keys.ITEM_ID, entity);
        mScenario.onActivity(activity -> navController(activity).navigate(R.id.p2p_send, args));

        ShareState ready = awaitStage(ShareState.Role.SEND, ShareState.Stage.READY, OFFER_MS);
        assertNotNull("an offer was minted", ready.offerCode);
        InstrumentationRegistry.getInstrumentation().waitForIdleSync();

        onView(withText(R.string.p2p_share_link)).check(matches(isDisplayed()));
        onView(withText(R.string.p2p_nearby_title)).check(matches(isDisplayed()));
        // Folded, nothing of the QR is on screen.
        onView(withId(R.id.p2p_nearby_body)).check(matches(withEffectiveVisibility(Visibility.GONE)));

        onView(withText(R.string.p2p_nearby_title)).perform(click());
        onView(withId(R.id.p2p_qr_tile)).check(matches(withEffectiveVisibility(Visibility.VISIBLE)));
        onView(withId(R.id.p2p_qr_scan_line)).check(matches(withText(R.string.p2p_qr_scan_line)));
        AtomicReference<String> shape = new AtomicReference<>();
        mScenario.onActivity(activity -> {
            View none = activity.findViewById(R.id.p2p_nearby_none);
            View orScan = activity.findViewById(R.id.p2p_nearby_or_scan);
            ImageView qr = activity.findViewById(R.id.p2p_qr);
            shape.set((none.getVisibility() == View.VISIBLE ? "none" : "")
                    + (orScan.getVisibility() == View.VISIBLE ? "phones" : "")
                    + (qr.getDrawable() != null ? "+qr" : "+noqr"));
        });
        assertTrue("unfolded: either no phone (and why) or phones then \"Or they scan\", and a QR: "
                        + shape.get(),
                "none+qr".equals(shape.get()) || "phones+qr".equals(shape.get()));

        onView(withText(R.string.p2p_nearby_title)).perform(click());
        onView(withId(R.id.p2p_nearby_body)).check(matches(withEffectiveVisibility(Visibility.GONE)));

        // ── Receive: this phone's own offer arrives as a request ────────────
        String offer = ready.offerCode;
        Bundle receiveArgs = new Bundle();
        receiveArgs.putString(P2pReceiveFragment.ARG_OFFER_CODE, offer);
        mScenario.onActivity(activity -> navController(activity).navigate(R.id.p2p_receive, receiveArgs));

        awaitStage(ShareState.Role.RECEIVE, ShareState.Stage.OFFERED, STAGE_MS);
        InstrumentationRegistry.getInstrumentation().waitForIdleSync();
        onView(withId(R.id.p2p_preview_group)).check(matches(isDisplayed()));
        onView(withId(R.id.p2p_preview_name)).check(matches(withText(FILE_NAME)));
        onView(withId(R.id.p2p_accept)).check(matches(isDisplayed()));
        onView(withId(R.id.p2p_decline)).check(matches(isDisplayed()));
        mScenario.onActivity(activity -> {
            TextView from = activity.findViewById(R.id.p2p_preview_from);
            String expectedTail = mApp.getString(R.string.p2p_wants_to_send, "").trim();
            assertTrue("\"<device> wants to send you\": " + from.getText(),
                    from.getText().toString().contains(expectedTail)
                            || from.getText().toString().equals(
                                    mApp.getString(R.string.p2p_wants_to_send_unknown)));
            CharSequence label = activity.findViewById(R.id.p2p_preview_icon).getContentDescription();
            assertTrue("the type tile has a TalkBack label", label != null && label.length() > 0);
            assertFalse("never \"BIN File\"", label.toString().startsWith("BIN"));
        });

        onView(withId(R.id.p2p_decline)).perform(click());
        onView(withId(R.id.p2p_visible_card)).check(matches(isDisplayed()));
        AtomicReference<ShareState> after = new AtomicReference<>();
        onMain(() -> after.set(mController.peekState()));
        assertTrue("declined: no request left",
                after.get() == null || after.get().role != ShareState.Role.RECEIVE
                        || after.get().stage != ShareState.Stage.OFFERED);
        assertEquals(FILE_NAME, ready.fileName);
    }

    private ShareState awaitStage(ShareState.Role role, ShareState.Stage stage, long timeoutMs)
            throws InterruptedException {
        long deadline = SystemClock.elapsedRealtime() + timeoutMs;
        AtomicReference<ShareState> state = new AtomicReference<>();
        while (SystemClock.elapsedRealtime() < deadline) {
            onMain(() -> state.set(mController.peekState()));
            ShareState s = state.get();
            if (s != null && s.role == role && s.stage == stage) {
                return s;
            }
            if (s != null && s.stage == ShareState.Stage.ERROR) {
                throw new AssertionError("share ended in an error: " + s.errorCode);
            }
            Thread.sleep(POLL_MS);
        }
        ShareState s = state.get();
        throw new AssertionError("never reached " + role + "/" + stage + "; last: "
                + (s == null ? "no session" : s.role + "/" + s.stage));
    }

    private static NavController navController(DownloadsActivity activity) {
        NavHostFragment host = (NavHostFragment) activity.getSupportFragmentManager()
                .findFragmentById(R.id.content_frame);
        assertNotNull(host);
        return host.getNavController();
    }

    private static void onMain(Runnable r) {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(r);
    }
}
