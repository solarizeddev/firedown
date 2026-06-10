package com.solarized.firedown.crash;

import android.content.Context;
import android.os.Process;
import android.util.Log;

import androidx.annotation.NonNull;
import java.io.File;

/**
 * {@link Thread.UncaughtExceptionHandler} that captures Java crashes
 * to disk before delegating back to the platform default (so the
 * process still terminates and Android still shows the standard
 * crash dialog). Installed once from {@link com.solarized.firedown.App}
 * at startup.
 *
 * <p>Native Gecko crashes are not tracked — without
 * {@code libcrashhelper.so} in the build there's no minidump to
 * surface, and a "Gecko died" report with no diagnostic data isn't
 * useful to a maintainer. Tab-level Gecko process deaths are
 * handled separately by {@code ContentDelegate.onKill} which
 * reloads the killed tab.</p>
 */
public final class CrashHandler implements Thread.UncaughtExceptionHandler {

    private static final String TAG = "CrashHandler";

    private final Context mContext;
    private final Thread.UncaughtExceptionHandler mPrevious;

    private CrashHandler(@NonNull Context context,
                         Thread.UncaughtExceptionHandler previous) {
        this.mContext = context.getApplicationContext();
        this.mPrevious = previous;
    }

    /**
     * Installs the handler as the JVM-wide default. Safe to call once
     * from {@code App.onCreate}; calling again is a no-op (we skip if
     * the current handler is already our class).
     */
    public static void install(@NonNull Context context) {
        Thread.UncaughtExceptionHandler current = Thread.getDefaultUncaughtExceptionHandler();
        if (current instanceof CrashHandler) return;
        Thread.setDefaultUncaughtExceptionHandler(new CrashHandler(context, current));
    }

    @Override
    public void uncaughtException(@NonNull Thread t, @NonNull Throwable e) {
        try {
            CrashReport report = CrashReport.fromThrowable(t, e);
            File f = CrashStorage.write(mContext, report);
            Log.i(TAG, "captured " + e.getClass().getSimpleName()
                    + " on thread " + t.getName()
                    + " → " + (f != null ? f.getName() : "WRITE FAILED"));
        } catch (Throwable inner) {
            // Never let our handler block the platform default — if
            // capture fails we still want Android to terminate the
            // process and surface the system crash dialog.
            Log.e(TAG, "Failed to capture crash", inner);
        }
        if (mPrevious != null) {
            mPrevious.uncaughtException(t, e);
        } else {
            Process.killProcess(Process.myPid());
            System.exit(10);
        }
    }
}
