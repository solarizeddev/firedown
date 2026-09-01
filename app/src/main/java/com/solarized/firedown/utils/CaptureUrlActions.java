package com.solarized.firedown.utils;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.text.TextUtils;
import android.widget.Toast;

import androidx.annotation.Nullable;

import com.solarized.firedown.R;
import com.solarized.firedown.data.entity.BrowserDownloadEntity;
import com.solarized.firedown.ffmpegutils.FFmpegEntity;
import com.solarized.firedown.ui.adapters.BrowserOptionAdapter;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Copy / Share / Open-external for a captured resource's URL (issue #302) —
 * shared by the Captured sheet's per-item ⋮ menu. Hands the URL out of the app
 * without downloading: the internet-radio / accessibility / open-in-VLC cases.
 *
 * <p>{@link #externalUrl} — "the URL worth handing outside the app" — is
 * deliberately just "carries a plain http(s) URL", no per-shape gating
 * (maintainer's call): everything with a URL is honest to hand out — a
 * signed/expiring or key-gated URL fails immediately and self-explanatorily
 * in the external tool, where a host/shape list here would be maintenance.
 * Note SABR/YouTube does NOT fall out of this: the variant STREAM urls are
 * empty by design, but the youtube emit's entity url falls back to the
 * WATCH-PAGE url ({@code variants[0].url || videoUrl} in background.js), so
 * a YouTube capture exposes {@code youtube.com/watch?v=…} — the shareable
 * page link, which is exactly what Copy/Share/Open should hand out there.
 */
public final class CaptureUrlActions {

    private CaptureUrlActions() {
    }

    /**
     * The URL the actions expose: the entity's PRIMARY URL — for an HLS master
     * capture that is the ROOT manifest, never an individual rendition's child
     * playlist (maintainer's rule: bitrate selection belongs to whatever the
     * URL is handed to — VLC and friends do their own ABR from the master —
     * not to us at copy time; the picker's per-quality choice is a DOWNLOAD
     * concern only). The selected stream's URL is only a fallback for a
     * capture whose entity URL is missing/opaque. Null unless the result is a
     * plain http(s) URL (an intent://, blob: or empty url falls out here; a
     * SABR entity does NOT — its entity url is the YouTube watch-page url,
     * see the class doc).
     */
    public static @Nullable String externalUrl(BrowserDownloadEntity entity) {
        String url = entity.getFileUrl();
        if (TextUtils.isEmpty(url)) {
            FFmpegEntity stream = entity.getSelectedStream();
            if (stream != null) {
                url = stream.getStreamUrl();
            }
        }
        if (TextUtils.isEmpty(url)) {
            return null;
        }
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            return null;
        }
        return url;
    }

    public static void copy(Context context, String url) {
        // A clipboard write is a binder call into system_server; a dying
        // clipboard service must never crash the app (the AutoCompleteView
        // hardening rule).
        try {
            ClipboardManager cm = (ClipboardManager)
                    context.getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm == null) {
                return;
            }
            cm.setPrimaryClip(ClipData.newPlainText(
                    context.getString(R.string.capture_copy_url), url));
        } catch (RuntimeException ignored) {
            return;
        }
        // Android 13+ draws its own clipboard confirmation overlay; a toast on
        // top of it is a duplicate, so confirm only below that.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            Toast.makeText(context, R.string.capture_url_copied,
                    Toast.LENGTH_SHORT).show();
        }
    }

    public static void share(Context context, String url) {
        Intent send = new Intent(Intent.ACTION_SEND);
        send.setType("text/plain");
        send.putExtra(Intent.EXTRA_TEXT, url);
        try {
            context.startActivity(Intent.createChooser(send, null));
        } catch (RuntimeException ignored) {
            // No resolvable share target — nothing useful to do.
        }
    }

    /**
     * Whether anything on the device would take the VIEW intent — the deeplink
     * snackbar's resolveActivity gating rule, so the menu never offers a
     * dead-end chooser (backed by the manifest's http(s) VIEW
     * {@code <queries>} entry).
     */
    public static boolean canOpenExternal(Context context, BrowserDownloadEntity entity,
                                          String url) {
        return viewIntent(entity, url)
                .resolveActivity(context.getPackageManager()) != null;
    }

    /**
     * Hands the URL to an external player via ACTION_VIEW — the ceiling of what
     * Android can pass across apps: the platform intent contract carries a URI
     * + MIME only, headers were never standardized into it. The one de-facto
     * convention is MX Player's {@code "headers"} String[] extra (alternating
     * key/value, adopted by a few MX-API players; VLC has no intent header
     * channel at all), attached here as a free upgrade where supported and
     * ignored everywhere else. {@code Cookie} is deliberately never exported —
     * session credentials must not leave the app for an arbitrary third-party
     * player.
     */
    public static void openExternal(Context context, BrowserDownloadEntity entity,
                                    String url) {
        Intent view = viewIntent(entity, url);
        String[] headers = externalHeaders(entity);
        if (headers != null) {
            view.putExtra("headers", headers);
        }
        try {
            context.startActivity(Intent.createChooser(view,
                    context.getString(R.string.open_with)));
        } catch (RuntimeException ignored) {
            // Chooser race / no target — nothing useful to do.
        }
    }

    private static Intent viewIntent(BrowserDownloadEntity entity, String url) {
        Intent view = new Intent(Intent.ACTION_VIEW);
        view.setDataAndType(Uri.parse(url), externalMimeType(entity, url));
        return view;
    }

    /**
     * The MIME the VIEW intent advertises, classified from the SAME resolved
     * mime the Captured row displays ({@link BrowserOptionAdapter
     * #resolveMimeType} — the stored mime, else inferred from the URL's
     * extension), so the chooser family always matches the row's own chip.
     * Families via the {@link FileUriHelper} classifiers:
     * SUBTITLES advertise {@code text/plain} — their real mimes
     * ({@code text/vtt}, {@code application/x-subrip}) are registered by
     * nothing, and a subtitle URL handed out alone is a document to read
     * (browsers/text viewers), not something a player can attach to
     * anything. IMAGES advertise the {@code image/*} wildcard — obscure
     * members ({@code image/svg+xml} was the shipped case: it advertised
     * {@code video/*} and filled the chooser with video players) aren't
     * registered by every gallery app, while anything that opens images
     * registers the wildcard. A precise video/audio mime passes through —
     * deliberately a {@code video/}/{@code audio/} prefix test, NOT
     * {@link FileUriHelper#isVideo}/{@code isAudio}, because those also
     * match the whole m3u8/mpd manifest family, which must stay on the
     * {@code video/*} fallback below: a precise-but-obscure manifest type
     * would empty the chooser on players that only register the wildcard
     * media types, and the receiving player sniffs the stream itself anyway.
     */
    private static String externalMimeType(BrowserDownloadEntity entity, String url) {
        String mime = BrowserOptionAdapter.resolveMimeType(entity.getMimeType(), url);
        if (FileUriHelper.isSubtitle(mime)) {
            return "text/plain";
        }
        if (FileUriHelper.isImage(mime)) {
            return "image/*";
        }
        if (!TextUtils.isEmpty(mime)
                && (mime.startsWith("video/") || mime.startsWith("audio/"))) {
            return mime;
        }
        // A capture the bridge/parser DECLARED audio (the audioOnly mark —
        // same signal BrowserDownloadViewModel.typeRank ranks by) whose mime
        // resolution still failed: say audio/*, not the video default.
        if (entity.isAudio()) {
            return "audio/*";
        }
        return "video/*";
    }

    /**
     * The capture's cached request headers in the MX-Player intent-API shape
     * (alternating key/value), minus {@code Cookie}. Null when nothing useful
     * remains.
     */
    private static @Nullable String[] externalHeaders(BrowserDownloadEntity entity) {
        Map<String, String> headers = Utils.stringToMap(entity.getFileHeaders());
        if (headers == null || headers.isEmpty()) {
            return null;
        }
        List<String> flat = new ArrayList<>();
        for (Map.Entry<String, String> entry : headers.entrySet()) {
            String key = entry.getKey();
            if (TextUtils.isEmpty(key) || "cookie".equalsIgnoreCase(key)
                    || TextUtils.isEmpty(entry.getValue())) {
                continue;
            }
            flat.add(key);
            flat.add(entry.getValue());
        }
        if (flat.isEmpty()) {
            return null;
        }
        return flat.toArray(new String[0]);
    }
}
