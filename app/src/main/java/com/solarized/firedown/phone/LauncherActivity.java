package com.solarized.firedown.phone;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;

import androidx.annotation.NonNull;
import androidx.core.splashscreen.SplashScreen;

/**
 * The app icon's activity, registered under the {@code .phone.BrowserActivity}
 * alias: that is the component home-screen icons made by earlier versions
 * point at, when it was the browser itself.
 *
 * <p>The browser ({@link BrowserMainActivity}) is singleTask, and a singleTask
 * launcher activity closes every screen above it each time the icon is
 * tapped: a user who left the app from Downloads (a GIF running), the Safe
 * Folder, Settings or the player came back to the browser, and a player in
 * background playback stopped. This activity is standard, so a tap on the icon
 * while the app's task exists brings the task forward as it was left; Android
 * does not even create it then. It runs only to start the app (no task yet),
 * and when started explicitly into an existing task.
 */
public class LauncherActivity extends Activity {

    /** The alias in the manifest, this class's public name. */
    private static final String ALIAS = "com.solarized.firedown.phone.BrowserActivity";

    /**
     * What the app icon sends. For "open the app" from a notification: like
     * the icon, it shows the app as it was left, never closing a screen.
     */
    @NonNull
    public static Intent openAppIntent(@NonNull Context context) {
        Intent intent = Intent.makeMainActivity(new ComponentName(context.getPackageName(), ALIAS));
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
        return intent;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        SplashScreen splashScreen = SplashScreen.installSplashScreen(this);
        super.onCreate(savedInstanceState);
        // The documented routing-activity pattern: the system splash stays up
        // while the browser starts behind it.
        splashScreen.setKeepOnScreenCondition(() -> true);

        Intent intent = getIntent();
        if (!isTaskRoot() && isOpenApp(intent)) {
            // Started into a task that exists (an explicit start without the
            // launcher's flags): show it as it was left.
            finish();
            return;
        }

        // Starting the app, or an explicit start carrying something to open
        // (a page sent to the old component name): the browser takes it.
        Intent forward = new Intent(intent);
        forward.setComponent(new ComponentName(this, BrowserMainActivity.class));
        forward.setFlags(intent.getFlags()
                & (Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION));
        startActivity(forward);
        finish();
    }

    private static boolean isOpenApp(Intent intent) {
        String action = intent.getAction();
        return (action == null || Intent.ACTION_MAIN.equals(action)) && intent.getData() == null;
    }
}
