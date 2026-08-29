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

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Copy / Share / Open-external for a captured resource's URL (issue #302) —
 * shared by the Captured sheet's per-item ⋮ menu. Hands the URL out of the app
 * without downloading: the internet-radio / accessibility / open-in-VLC cases.
 *
 * <p>"Copyable" is deliberately just "carries a plain http(s) URL" — no
 * per-shape gating (maintainer's call): SABR excludes itself (its media URLs
 * are empty by design), and everything else with a URL is honest to hand out —
 * a signed/expiring or key-gated URL fails immediately and self-explanatorily
 * in the external tool, where a host/shape list here would be maintenance.
 */
public final class CaptureUrlActions {

    private CaptureUrlActions() {
    }

    /**
     * The URL the actions expose: the selected (default = best) stream's URL,
     * falling back to the entity's primary URL; null unless it is a plain
     * http(s) URL (SABR's empty media URLs fall out here).
     */
    public static @Nullable String copyableUrl(BrowserDownloadEntity entity) {
        String url = null;
        FFmpegEntity stream = entity.getSelectedStream();
        if (stream != null) {
            url = stream.getStreamUrl();
        }
        if (TextUtils.isEmpty(url)) {
            url = entity.getFileUrl();
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
        view.setDataAndType(Uri.parse(url), externalMimeType(entity));
        return view;
    }

    /**
     * The MIME the VIEW intent advertises. The capture's own video/audio mime
     * passes through; everything else (manifest mimes, octet-stream, the
     * obfuscated-manifest text/html class) coarsens to {@code video/*} — a
     * precise-but-obscure type would empty the chooser on players that only
     * register the wildcard media types, and the receiving player sniffs the
     * stream itself anyway.
     */
    private static String externalMimeType(BrowserDownloadEntity entity) {
        String mime = entity.getMimeType();
        if (!TextUtils.isEmpty(mime)
                && (mime.startsWith("video/") || mime.startsWith("audio/"))) {
            return mime;
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
