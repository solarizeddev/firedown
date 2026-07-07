package com.solarized.firedown.crash;

import android.net.Uri;

import androidx.annotation.NonNull;

/**
 * Builds the GitHub pre-filled new-issue URL for a {@link CrashReport}.
 *
 * <p>Hits {@code github.com/solarizeddev/firedown/issues/new} with the
 * title, body, and a {@code crash} label pre-filled. GitHub will create
 * the label on first use if the repo doesn't have one yet.</p>
 *
 * <p>Body length: GitHub renders up to 65535 chars per issue body, but
 * the practical URL cap in Android Intents and most browsers sits
 * around 6–8 KB. We truncate to {@link #MAX_BODY} and surface a
 * "paste the rest from your clipboard" footer — {@link CrashReportSheet}
 * copies the full report alongside opening the URL, so the user just
 * pastes if anything was cut.</p>
 */
public final class CrashReportUrlBuilder {

    private static final String BASE =
            "https://github.com/solarizeddev/firedown/issues/new";
    private static final int MAX_BODY = 6000;
    private static final int MAX_TITLE = 200;

    private CrashReportUrlBuilder() {}

    @NonNull
    public static Uri build(@NonNull CrashReport report) {
        return Uri.parse(BASE).buildUpon()
                .appendQueryParameter("title", buildTitle(report))
                .appendQueryParameter("body", buildBody(report))
                .appendQueryParameter("labels", "crash")
                .build();
    }

    @NonNull
    public static String buildBody(@NonNull CrashReport report) {
        StringBuilder sb = new StringBuilder();
        sb.append("**Type:** ").append(report.type).append('\n');
        sb.append("**Origin:** ").append(report.origin).append('\n');
        sb.append("**Version:** ").append(report.versionName)
                .append(" (").append(report.versionCode).append(")\n");
        sb.append("**Device:** ").append(report.device).append('\n');
        sb.append("**Android:** SDK ").append(report.sdk)
                .append(" · ").append(report.abi).append('\n');
        if (report.installSource != null && !report.installSource.isEmpty()) {
            sb.append("**Source:** ").append(report.installSource).append('\n');
        }
        if (report.heapMax > 0) {
            sb.append("**Memory:** ").append(memoryLine(report)).append('\n');
        }
        sb.append('\n').append("```\n");

        // Reserve ~120 chars for the closing fence + truncation footer.
        int budget = MAX_BODY - sb.length() - 180;
        String trace = report.trace == null ? "" : report.trace;
        boolean truncated = trace.length() > budget;
        sb.append(truncated ? trace.substring(0, budget) : trace);
        sb.append("\n```\n");

        if (truncated) {
            sb.append('\n')
              .append("_Trace truncated to fit the URL. The full report ")
              .append("was copied to your clipboard — paste it above this line._\n");
        }
        return sb.toString();
    }

    @NonNull
    private static String buildTitle(@NonNull CrashReport report) {
        String head = "[" + report.type + "] " + report.headline();
        if (head.length() > MAX_TITLE) head = head.substring(0, MAX_TITLE - 3) + "...";
        return head;
    }

    /**
     * Full uncut report text, suitable for clipboard. Mirrors
     * {@link #buildBody} structure but without the size cap.
     */
    @NonNull
    public static String fullText(@NonNull CrashReport report) {
        StringBuilder sb = new StringBuilder();
        sb.append("Type: ").append(report.type).append('\n');
        sb.append("Origin: ").append(report.origin).append('\n');
        sb.append("Version: ").append(report.versionName)
                .append(" (").append(report.versionCode).append(")\n");
        sb.append("Device: ").append(report.device).append('\n');
        sb.append("Android: SDK ").append(report.sdk)
                .append(" · ").append(report.abi).append('\n');
        if (report.installSource != null && !report.installSource.isEmpty()) {
            sb.append("Source: ").append(report.installSource).append('\n');
        }
        if (report.heapMax > 0) {
            sb.append("Memory: ").append(memoryLine(report)).append('\n');
        }
        sb.append('\n').append(report.trace == null ? "" : report.trace).append('\n');
        return sb.toString();
    }

    /**
     * One-line heap snapshot, e.g. {@code java 126/128 MB · native 210 MB}.
     * A java figure at ~max on an OOM says the heap filled with live objects
     * (leak/accumulation); a low one says the failed allocation was a spike.
     */
    @NonNull
    private static String memoryLine(@NonNull CrashReport report) {
        StringBuilder sb = new StringBuilder();
        sb.append("java ").append(toMb(report.heapUsed))
                .append('/').append(toMb(report.heapMax)).append(" MB");
        if (report.nativeHeap > 0) {
            sb.append(" · native ").append(toMb(report.nativeHeap)).append(" MB");
        }
        return sb.toString();
    }

    private static long toMb(long bytes) {
        return bytes / (1024 * 1024);
    }
}
