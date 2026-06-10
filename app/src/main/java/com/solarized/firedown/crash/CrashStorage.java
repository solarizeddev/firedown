package com.solarized.firedown.crash;

import android.content.Context;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.FileInputStream;
import java.io.IOException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/**
 * Disk I/O for {@link CrashReport}s. Reports live under
 * {@code filesDir/crashes/<timestamp>-<uuid>.json}, written by
 * {@link CrashHandler} from whichever process the throwable bubbled
 * up in. {@code filesDir} is shared across every process of the app
 * so the bottom-sheet UI in the main process picks up reports written
 * by any of them on the next launch.
 *
 * <p>No locking — filenames are unique per crash (timestamp + UUID).</p>
 */
public final class CrashStorage {

    private static final String TAG = "CrashStorage";
    private static final String DIR_NAME = "crashes";
    private static final int MAX_REPORTS = 20;

    private CrashStorage() {}

    @NonNull
    public static File crashesDir(@NonNull Context context) {
        File dir = new File(context.getFilesDir(), DIR_NAME);
        if (!dir.exists() && !dir.mkdirs()) {
            Log.w(TAG, "Failed to create crashes dir: " + dir);
        }
        return dir;
    }

    /**
     * Writes the report to disk. Returns the resulting file or
     * {@code null} on I/O failure — never throws so callers in the
     * uncaught-exception path can chain to the original handler without
     * cascading another crash.
     */
    @Nullable
    public static File write(@NonNull Context context, @NonNull CrashReport report) {
        try {
            File dir = crashesDir(context);
            // Cap at MAX_REPORTS by sweeping the oldest before adding
            // a new one. Without this, a repeatedly-crashing loop could
            // fill the data partition.
            trim(dir, MAX_REPORTS - 1);

            File file = new File(dir, report.timestamp + "-" + UUID.randomUUID() + ".json");
            JSONObject json = report.toJson();
            try (FileOutputStream fos = new FileOutputStream(file);
                 OutputStreamWriter w = new OutputStreamWriter(fos, StandardCharsets.UTF_8)) {
                w.write(json.toString());
            }
            return file;
        } catch (Throwable t) {
            Log.e(TAG, "Failed to write crash report", t);
            return null;
        }
    }

    /**
     * Lists every pending crash report on disk, newest first.
     */
    @NonNull
    public static List<File> listPending(@NonNull Context context) {
        File dir = crashesDir(context);
        File[] files = dir.listFiles((d, name) -> name.endsWith(".json"));
        if (files == null || files.length == 0) return new ArrayList<>();
        Arrays.sort(files, Comparator.comparingLong(File::lastModified).reversed());
        return new ArrayList<>(Arrays.asList(files));
    }

    @Nullable
    public static CrashReport read(@NonNull File file) {
        try {
            byte[] bytes = readAll(file);
            return CrashReport.fromJson(new JSONObject(new String(bytes, StandardCharsets.UTF_8)));
        } catch (Throwable t) {
            Log.w(TAG, "Failed to read crash report " + file.getName(), t);
            return null;
        }
    }

    public static void delete(@NonNull File file) {
        if (!file.delete()) Log.w(TAG, "Failed to delete " + file);
    }

    public static void deleteAll(@NonNull Context context) {
        for (File f : listPending(context)) delete(f);
    }

    private static void trim(@NonNull File dir, int keep) {
        File[] files = dir.listFiles((d, name) -> name.endsWith(".json"));
        if (files == null || files.length <= keep) return;
        Arrays.sort(files, Comparator.comparingLong(File::lastModified));
        for (int i = 0; i < files.length - keep; i++) {
            delete(files[i]);
        }
    }

    private static byte[] readAll(@NonNull File file) throws IOException {
        try (FileInputStream fis = new FileInputStream(file)) {
            byte[] buf = new byte[(int) Math.min(file.length(), 1024 * 1024)];
            int read = 0;
            while (read < buf.length) {
                int n = fis.read(buf, read, buf.length - read);
                if (n < 0) break;
                read += n;
            }
            byte[] out = new byte[read];
            System.arraycopy(buf, 0, out, 0, read);
            return out;
        }
    }
}
