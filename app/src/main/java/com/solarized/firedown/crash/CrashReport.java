package com.solarized.firedown.crash;

import android.os.Build;
import android.os.Debug;

import androidx.annotation.NonNull;

import com.solarized.firedown.App;
import com.solarized.firedown.BuildConfig;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.PrintWriter;
import java.io.StringWriter;

/**
 * Structured crash report. Built from a Java {@link Throwable}
 * captured by {@link CrashHandler} in the main process.
 *
 * <p>Gecko native crashes are deliberately not tracked: without
 * {@code libcrashhelper.so} in the build, GeckoView's crash intent
 * arrives with no minidump / no extras, so there's nothing
 * diagnostic to report. Tab-level Gecko deaths are handled by
 * {@code ContentDelegate.onKill} which reloads the killed tab.</p>
 *
 * <p>Serialized as JSON to {@code filesDir/crashes/}. {@link CrashStorage}
 * owns the I/O; this class is just the shape.</p>
 */
public final class CrashReport {

    public static final String TYPE_JAVA = "java";

    public final long timestamp;
    public final String type;          // TYPE_JAVA
    public final String versionName;
    public final int versionCode;
    public final String device;
    public final String abi;
    public final int sdk;
    public final String installSource;

    /** Thread name where the throwable originated. */
    public final String origin;

    /** Stack trace text. */
    public final String trace;

    // Heap state at capture time, in bytes (0 = not captured — reports
    // written by older builds). Answers the one question an OOM trace
    // can't: did the Java heap fill slowly (a leak/accumulation) or was
    // it a one-off spike — and was the pressure Java-side or native.
    public final long heapUsed;
    public final long heapMax;
    public final long nativeHeap;

    private CrashReport(long timestamp, String type, String versionName, int versionCode,
                        String device, String abi, int sdk, String installSource,
                        String origin, String trace,
                        long heapUsed, long heapMax, long nativeHeap) {
        this.timestamp = timestamp;
        this.type = type;
        this.versionName = versionName;
        this.versionCode = versionCode;
        this.device = device;
        this.abi = abi;
        this.sdk = sdk;
        this.installSource = installSource;
        this.origin = origin;
        this.trace = trace;
        this.heapUsed = heapUsed;
        this.heapMax = heapMax;
        this.nativeHeap = nativeHeap;
    }

    public static CrashReport fromThrowable(@NonNull Thread thread, @NonNull Throwable t) {
        StringWriter sw = new StringWriter();
        t.printStackTrace(new PrintWriter(sw));
        Runtime runtime = Runtime.getRuntime();
        return new CrashReport(
                System.currentTimeMillis(),
                TYPE_JAVA,
                safeVersionName(),
                safeVersionCode(),
                Build.MANUFACTURER + " " + Build.MODEL,
                primaryAbi(),
                Build.VERSION.SDK_INT,
                safeInstallSource(),
                thread.getName(),
                sw.toString(),
                runtime.totalMemory() - runtime.freeMemory(),
                runtime.maxMemory(),
                Debug.getNativeHeapAllocatedSize());
    }

    public JSONObject toJson() throws JSONException {
        JSONObject o = new JSONObject();
        o.put("timestamp", timestamp);
        o.put("type", type);
        o.put("versionName", versionName);
        o.put("versionCode", versionCode);
        o.put("device", device);
        o.put("abi", abi);
        o.put("sdk", sdk);
        o.put("installSource", installSource);
        o.put("origin", origin);
        o.put("trace", trace);
        o.put("heapUsed", heapUsed);
        o.put("heapMax", heapMax);
        o.put("nativeHeap", nativeHeap);
        return o;
    }

    public static CrashReport fromJson(JSONObject o) throws JSONException {
        return new CrashReport(
                o.getLong("timestamp"),
                o.optString("type", TYPE_JAVA),
                o.optString("versionName", ""),
                o.optInt("versionCode", 0),
                o.optString("device", ""),
                o.optString("abi", ""),
                o.optInt("sdk", 0),
                o.optString("installSource", ""),
                o.optString("origin", ""),
                o.optString("trace", ""),
                o.optLong("heapUsed", 0),
                o.optLong("heapMax", 0),
                o.optLong("nativeHeap", 0));
    }

    /**
     * Short one-line summary used in the snackbar title and as the
     * GitHub issue title prefix. Picks the first non-empty line of the
     * trace and trims to a reasonable length.
     */
    public String headline() {
        if (trace == null || trace.isEmpty()) return type;
        String[] lines = trace.split("\\r?\\n", 2);
        String first = lines[0].trim();
        if (first.length() > 120) first = first.substring(0, 117) + "...";
        return first;
    }

    // ── Static helpers that fall back gracefully when called from the
    //    crash handler at process-death time where the Application may
    //    not be fully initialised.

    private static String safeVersionName() {
        try { return App.getVersionName(); } catch (Throwable ignored) {}
        return BuildConfig.VERSION_NAME;
    }

    private static int safeVersionCode() {
        try { return App.getVersionCode(); } catch (Throwable ignored) {}
        return BuildConfig.VERSION_CODE;
    }

    private static String safeInstallSource() {
        try { return App.getInstalledSource(); } catch (Throwable ignored) {}
        return "";
    }

    private static String primaryAbi() {
        String[] abis = Build.SUPPORTED_ABIS;
        return abis != null && abis.length > 0 ? abis[0] : "";
    }
}
