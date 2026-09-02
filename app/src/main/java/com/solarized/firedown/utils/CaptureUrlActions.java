package com.solarized.firedown.utils;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.os.Build;
import android.text.TextUtils;
import android.widget.Toast;

import androidx.annotation.Nullable;

import com.solarized.firedown.R;
import com.solarized.firedown.data.entity.BrowserDownloadEntity;
import com.solarized.firedown.ffmpegutils.FFmpegEntity;

import java.util.List;

/**
 * Copy URL for a captured resource (issue #302) — the Captured sheet's way of
 * handing a URL out of the app without downloading: the internet-radio /
 * Radio Browser / paste-into-VLC / accessibility cases.
 *
 * <p><b>Copy is the ONLY URL action, on purpose.</b> The first cut also
 * shipped Share URL and Open in another app behind a per-item menu page
 * (1.1.93), and both were removed: a capture is URL + request headers +
 * cookies, and neither a share text nor an ACTION_VIEW intent can carry the
 * headers a gated URL depends on (Android's intent contract is URI + MIME;
 * only MX-API players read a {@code "headers"} extra, and Cookie must never
 * leave the app anyway), so Open failed silently on anything signed or
 * header-gated, double-played the stream the page was already playing, and
 * needed a mime-inference round of its own — while Share is one tap away
 * inside the system clipboard/share flow the copied URL already feeds. The
 * reporter's whole workflow is copy → paste into VLC / Radio Browser, and
 * Copy covers it in ONE tap now that the menu page is gone (the row's
 * action slot copies directly; multi-variant captures copy from the
 * quality picker's toolbar; multi-select copies every selected URL, one
 * per line). Don't reintroduce Share/Open without a header channel to hand
 * them.
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
 * page link, which is exactly what Copy should hand out there.
 */
public final class CaptureUrlActions {

    private CaptureUrlActions() {
    }

    /**
     * The URL Copy exposes: the entity's PRIMARY URL — for an HLS master
     * capture that is the ROOT manifest, never an individual rendition's child
     * playlist (maintainer's rule: bitrate selection belongs to whatever the
     * URL is handed to — VLC and friends do their own ABR from the master —
     * not to us at copy time; the picker's per-quality choice is a DOWNLOAD
     * concern only). The selected stream's URL is only a fallback for a
     * capture whose entity url is empty. Null when nothing plain-http(s) is
     * available (a blob:/data: source, an empty SABR variant with no page
     * url) — then the slot shows nothing.
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

    /** Copies one URL. */
    public static void copy(Context context, String url) {
        copy(context, List.of(url));
    }

    /**
     * Copies the URLs as one clip, one per line — the multi-select shape
     * (paste N stream addresses into a playlist / a Radio Browser batch).
     * A clipboard write is a binder call into system_server; a dying
     * clipboard service must never crash the app (the AutoCompleteView
     * hardening rule). Nothing to copy → nothing happens.
     */
    public static void copy(Context context, List<String> urls) {
        if (urls == null || urls.isEmpty()) {
            return;
        }
        try {
            ClipboardManager cm = (ClipboardManager)
                    context.getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm == null) {
                return;
            }
            cm.setPrimaryClip(ClipData.newPlainText(
                    context.getString(R.string.capture_copy_url),
                    TextUtils.join("\n", urls)));
        } catch (RuntimeException ignored) {
            return;
        }
        // Android 13+ draws its own clipboard confirmation overlay; a toast on
        // top of it is a duplicate, so confirm only below that. TalkBack
        // announces the toast, which is the screen-reader confirmation.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            int n = urls.size();
            Toast.makeText(context, context.getResources().getQuantityString(
                    R.plurals.capture_urls_copied, n, n), Toast.LENGTH_SHORT).show();
        }
    }
}
