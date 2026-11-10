package com.solarized.firedown;

import android.content.Context;
import android.util.Log;

import com.solarized.firedown.update.UpdatePatch;

import java.io.Closeable;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.function.BooleanSupplier;

/**
 * The update download when the system Download Manager can't take it: the
 * user disabled the Download Manager app (enqueue then throws on every
 * check, and no update ever downloaded), or the phone has no external files
 * dir for it to write to. The same plan, the same files and the same
 * verification as a Download Manager download; only the transport differs.
 *
 * One run of {@link UpdateDirectDownloadWorker} calls {@link #run} once.
 * The bytes go to a partial file named after the download id
 * ({@link UpdateDownloader#directPartFile}) and are moved to the step's file
 * only once complete and only while the record still names this download, so
 * a stale run can never write where a newer download is going. A run the
 * system stops (WorkManager allows ten minutes, and stops a run when the
 * network goes) keeps its partial file, and the next run resumes it with a
 * Range request: a 205 MB APK on a slow connection takes several runs.
 *
 * Failures, as the Download Manager path has them: an HTTP refusal or a file
 * longer than status.json says is a failed attempt (the next mirror); a
 * connection that keeps failing with nothing downloaded is retried
 * {@link #MAX_STALLS} times, then a failed attempt; no room on the phone is a
 * device failure. The rebuild and the SHA-256 + signature checks are
 * UpdateFinalizeWorker's, unchanged, so a resumed file that went wrong is
 * caught there.
 */
public final class UpdateDirectDownload {

    private static final String TAG = "UpdateDirectDownload";

    /**
     * Runs in a row that ended with nothing downloaded before the attempt
     * counts as failed. WorkManager's backoff (30 s doubling) makes this about
     * a quarter of an hour of trying; a run the system stopped is not one.
     */
    static final int MAX_STALLS = 6;

    private static final int BUFFER = 64 * 1024;

    /** What one run did. */
    public enum Outcome {
        /** The file is complete and handed to UpdateFinalizeWorker. */
        DONE,
        /** Run again later (stopped, or a transient failure). */
        RETRY,
        /** The failure is reported to UpdateDownloader; nothing more to do. */
        FINISHED,
        /** The record names another download; this one is over. */
        STALE
    }

    /** An HTTP answer, as much of it as the download needs. */
    public static final class Answer implements Closeable {
        final int code;
        /** The first byte of a 206's Content-Range, else -1. */
        final long rangeStart;
        /** The whole file's length (a 200's Content-Length, a 206's Content-Range total), else -1. */
        final long total;
        final InputStream body;
        private final Closeable owner;

        public Answer(int code, long rangeStart, long total, InputStream body, Closeable owner) {
            this.code = code;
            this.rangeStart = rangeStart;
            this.total = total;
            this.body = body;
            this.owner = owner;
        }

        @Override
        public void close() throws IOException {
            if (owner != null) {
                owner.close();
            } else if (body != null) {
                body.close();
            }
        }
    }

    /** Opens {@code url}, from byte {@code from} (0: the whole file, no Range header). */
    public interface Http {
        Answer open(String url, long from) throws IOException;
    }

    private UpdateDirectDownload() {}

    public static Outcome run(Context context, long downloadId, Http http, BooleanSupplier stopped) {
        UpdateDownloader.Pending pending = UpdateDownloader.directPending(context, downloadId);
        File part = UpdateDownloader.directPartFile(context, downloadId);
        if (pending == null) {
            part.delete();
            return Outcome.STALE;
        }
        UpdatePlan.Step step = pending.plan.current();
        String url = pending.plan.currentUrl();
        long expected = step.size > 0 ? step.size : -1L;

        long have = part.isFile() ? part.length() : 0L;
        if (expected > 0 && have > expected) {
            part.delete();
            have = 0L;
        }
        if (expected > 0 && have == expected) {
            return complete(context, downloadId, part);
        }
        if (!roomFor(context, part, expected, have)) {
            Log.w(TAG, "not enough free space for the update download");
            UpdateDownloader.onDeviceFailed(context, downloadId);
            return Outcome.FINISHED;
        }

        long start = have;
        try (Answer answer = http.open(url, have)) {
            long total;
            if (answer.code == 206) {
                if (answer.rangeStart != have) {
                    // A range the request did not ask for: start the file over.
                    Log.w(TAG, "the server answered another range; starting over");
                    part.delete();
                    return stalled(context, downloadId, false);
                }
                total = answer.total;
            } else if (answer.code == 200) {
                // The whole file, Range or not: whatever was kept is dropped.
                have = 0L;
                start = 0L;
                total = answer.total;
            } else if (answer.code == 416 || answer.code == 408 || answer.code == 429 || answer.code >= 500) {
                // 416: the kept part no longer fits the file (it changed on the
                // server). The others are the server's own trouble. Retried;
                // a server that keeps answering so ends the attempt.
                if (answer.code == 416) {
                    part.delete();
                }
                Log.w(TAG, "the update server answered " + answer.code);
                return stalled(context, downloadId, false);
            } else {
                Log.w(TAG, "the update server refused the download: " + answer.code);
                part.delete();
                UpdateDownloader.onAttemptFailed(context, downloadId);
                return Outcome.FINISHED;
            }
            long limit = expected > 0 ? expected : (total > 0 ? total : UpdatePatch.MAX_FILE_SIZE);
            if (expected > 0 && total > 0 && total != expected) {
                Log.w(TAG, "the server's file is " + total + " bytes, status.json says " + expected);
                part.delete();
                UpdateDownloader.onAttemptFailed(context, downloadId);
                return Outcome.FINISHED;
            }
            if (answer.body == null) {
                throw new IOException("no body");
            }

            byte[] buffer = new byte[BUFFER];
            try (FileOutputStream out = new FileOutputStream(part, have > 0)) {
                while (true) {
                    if (stopped.getAsBoolean()) {
                        if (have > start) {
                            UpdateDownloader.resetDirectStalls(context, downloadId);
                        }
                        return Outcome.RETRY;
                    }
                    int n = answer.body.read(buffer);
                    if (n < 0) {
                        break;
                    }
                    if (n > limit - have) {
                        Log.w(TAG, "the server's file is longer than the update");
                        out.close();
                        part.delete();
                        UpdateDownloader.onAttemptFailed(context, downloadId);
                        return Outcome.FINISHED;
                    }
                    out.write(buffer, 0, n);
                    have += n;
                }
                out.getFD().sync();
            }
            long want = expected > 0 ? expected : total;
            if (want > 0 && have < want) {
                throw new IOException("the connection ended at " + have + " of " + want + " bytes");
            }
            return complete(context, downloadId, part);
        } catch (IOException | RuntimeException e) {
            if (stopped.getAsBoolean()) {
                return Outcome.RETRY;
            }
            boolean progressed = part.isFile() && part.length() > start;
            Log.w(TAG, "the update download was interrupted: " + e.getMessage());
            return stalled(context, downloadId, progressed);
        }
    }

    /** A run that ended early. Progress resets the count; enough runs without it end the attempt. */
    private static Outcome stalled(Context context, long downloadId, boolean progressed) {
        if (progressed) {
            UpdateDownloader.resetDirectStalls(context, downloadId);
            return Outcome.RETRY;
        }
        int stalls = UpdateDownloader.countDirectStall(context, downloadId);
        if (stalls < 0) {
            return Outcome.STALE;
        }
        if (stalls >= MAX_STALLS) {
            Log.w(TAG, "the update download failed " + stalls + " times in a row; trying the next");
            UpdateDownloader.onAttemptFailed(context, downloadId);
            return Outcome.FINISHED;
        }
        return Outcome.RETRY;
    }

    private static Outcome complete(Context context, long downloadId, File part) {
        return UpdateDownloader.completeDirect(context, downloadId, part) ? Outcome.DONE : Outcome.STALE;
    }

    /** Room for the rest of the file, plus the margin every update step keeps. */
    private static boolean roomFor(Context context, File part, long expected, long have) {
        File dir = part.getParentFile();
        if (dir == null) {
            return false;
        }
        long rest = expected > 0 ? expected - have : Math.max(0L, UpdateDownloader.expectedApkBytes(context) - have);
        return dir.getUsableSpace() >= rest + UpdateDownloader.SPACE_MARGIN_BYTES;
    }

    /** "bytes 100-199/1000" → {100, 1000}; "/*" → total -1; unreadable → {-1, -1}. */
    public static long[] parseContentRange(String value) {
        long[] out = {-1L, -1L};
        if (value == null) {
            return out;
        }
        String v = value.trim();
        if (!v.startsWith("bytes ")) {
            return out;
        }
        v = v.substring(6).trim();
        int dash = v.indexOf('-');
        int slash = v.indexOf('/');
        if (dash <= 0 || slash < dash) {
            return out;
        }
        try {
            out[0] = Long.parseLong(v.substring(0, dash).trim());
            String total = v.substring(slash + 1).trim();
            if (!"*".equals(total)) {
                out[1] = Long.parseLong(total);
            }
        } catch (NumberFormatException e) {
            out[0] = -1L;
            out[1] = -1L;
        }
        return out;
    }
}
