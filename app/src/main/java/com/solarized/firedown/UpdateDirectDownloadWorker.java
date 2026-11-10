package com.solarized.firedown;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.hilt.work.HiltWorker;
import androidx.work.BackoffPolicy;
import androidx.work.Constraints;
import androidx.work.Data;
import androidx.work.ExistingWorkPolicy;
import androidx.work.NetworkType;
import androidx.work.OneTimeWorkRequest;
import androidx.work.WorkManager;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

import com.solarized.firedown.utils.BrowserHeaders;

import java.io.IOException;
import java.io.InputStream;
import java.util.concurrent.TimeUnit;

import dagger.assisted.Assisted;
import dagger.assisted.AssistedInject;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

/**
 * Runs {@link UpdateDirectDownload} over the app's OkHttp client: the update
 * download when the system Download Manager can't take it. One unique work
 * per download id, KEEP, so the check that finds the download recorded can
 * enqueue it again (a no-op while it is queued or running) without starting
 * a second copy.
 *
 * Not roaming, as the Download Manager request was. No notification: the
 * Download Manager download is hidden too, and the install prompt is the
 * one notification an update posts.
 */
@HiltWorker
public class UpdateDirectDownloadWorker extends Worker {

    static final String KEY_DOWNLOAD_ID = "download_id";

    private final OkHttpClient mClient;

    @AssistedInject
    public UpdateDirectDownloadWorker(@Assisted @NonNull Context context,
                                      @Assisted @NonNull WorkerParameters params,
                                      OkHttpClient client) {
        super(context, params);
        mClient = client;
    }

    static String workName(long downloadId) {
        return "update_direct_" + downloadId;
    }

    public static void enqueue(Context context, long downloadId) {
        Constraints constraints = new Constraints.Builder()
                .setRequiredNetworkType(NetworkType.NOT_ROAMING)
                .build();
        OneTimeWorkRequest request = new OneTimeWorkRequest.Builder(UpdateDirectDownloadWorker.class)
                .setInputData(new Data.Builder().putLong(KEY_DOWNLOAD_ID, downloadId).build())
                .setConstraints(constraints)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .build();
        WorkManager.getInstance(context).enqueueUniqueWork(workName(downloadId), ExistingWorkPolicy.KEEP, request);
    }

    public static void cancel(Context context, long downloadId) {
        WorkManager.getInstance(context).cancelUniqueWork(workName(downloadId));
    }

    @NonNull
    @Override
    public Result doWork() {
        long downloadId = getInputData().getLong(KEY_DOWNLOAD_ID, -1);
        UpdateDirectDownload.Outcome outcome = UpdateDirectDownload.run(
                getApplicationContext(), downloadId, this::open, this::isStopped);
        return outcome == UpdateDirectDownload.Outcome.RETRY ? Result.retry() : Result.success();
    }

    private UpdateDirectDownload.Answer open(String url, long from) throws IOException {
        Request.Builder builder = new Request.Builder()
                .url(url)
                .addHeader(BrowserHeaders.USER_AGENT, BrowserHeaders.getDefaultUserAgentString())
                .addHeader(BrowserHeaders.X_APP_VERSION, App.getVersionName())
                // The file's own bytes: a gzipped answer would count other
                // bytes than a resume asks for, and has no length.
                .addHeader("Accept-Encoding", "identity");
        if (from > 0) {
            builder.addHeader("Range", "bytes=" + from + "-");
        }
        Response response = mClient.newCall(builder.build()).execute();
        ResponseBody body = response.body();
        InputStream in = body == null ? null : body.byteStream();
        long rangeStart = -1L;
        long total = -1L;
        if (response.code() == 206) {
            long[] range = UpdateDirectDownload.parseContentRange(response.header("Content-Range"));
            rangeStart = range[0];
            total = range[1];
        } else if (response.code() == 200 && body != null) {
            total = body.contentLength();
        }
        return new UpdateDirectDownload.Answer(response.code(), rangeStart, total, in, response);
    }
}
