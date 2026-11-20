package com.solarized.firedown.readbench;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.text.TextUtils;

import com.solarized.firedown.manager.DownloadIntents;
import com.solarized.firedown.manager.DownloadRequest;
import com.solarized.firedown.manager.UrlType;

/**
 * Debug builds only: lets firedown-ffmpeg's scripts/okhttp-read-bench/
 * device-run.sh start a download without a tap, so the device comparison of
 * read strategies runs unattended. It queues an ordinary download through
 * {@link DownloadIntents#start}, the same door the browser's Download button
 * uses, with {@link UrlType#MEDIA} so it always takes the FFmpeg path
 * (FFmpegMuxStrategy): that is the path whose reads the comparison measures
 * (a direct file left to HttpDownloadStrategy never touches FFmpeg).
 *
 * <pre>
 * adb shell am broadcast -n com.solarized.firedown/.readbench.ReadBenchReceiver \
 *     -a com.solarized.firedown.readbench.DOWNLOAD \
 *     --es url https://example.com/video.m3u8 --es name okread-hls-1.mp4
 * </pre>
 *
 * <p>The answer ("ok ..." or "error ...") is the broadcast's result data,
 * which {@code am broadcast} prints. The manifest entry requires
 * {@code android.permission.DUMP} from the sender: the adb shell holds it, no
 * installed app can. Starting the download service needs the app in the
 * foreground (Android 8+), so the script brings it up first.
 */
public class ReadBenchReceiver extends BroadcastReceiver {

    static final String DOWNLOAD = "com.solarized.firedown.readbench.DOWNLOAD";

    @Override
    public void onReceive(Context context, Intent intent) {
        String answer;
        if (DOWNLOAD.equals(intent.getAction())) {
            answer = download(context, intent);
        } else {
            answer = "error unknown action " + intent.getAction();
        }
        setResultCode(answer.startsWith("error") ? 0 : 1);
        setResultData(answer);
    }

    private static String download(Context context, Intent intent) {
        String url = intent.getStringExtra("url");
        if (TextUtils.isEmpty(url) || !(url.startsWith("http://") || url.startsWith("https://"))) {
            return "error missing or non-http url extra";
        }
        String name = intent.getStringExtra("name");
        DownloadRequest.Builder builder = new DownloadRequest.Builder(url)
                .fileType(UrlType.MEDIA.getValue())
                .mimeType("video/mp4")
                .origin(url);
        if (!TextUtils.isEmpty(name)) {
            builder.name(name).fileNameForced(true);
        }
        try {
            DownloadIntents.start(context, builder.build());
        } catch (RuntimeException e) {
            // IllegalStateException: Android refused to start the service
            // because the app is in the background.
            return "error " + e;
        }
        return "ok queued " + url;
    }
}
