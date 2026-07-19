package com.solarized.firedown.data;

import android.content.Context;

import com.solarized.firedown.data.repository.GeckoStateDataRepository;

import org.apache.commons.io.FileUtils;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.Locale;
import java.util.Set;

/**
 * Sidecar store for tab favicons that arrive as inline {@code data:} URIs, so
 * the sessions file never carries image bytes — the Fenix model: Firefox for
 * Android inlines NO image data in its session file (favicons live in the
 * BrowserIcons disk cache, thumbnails in ThumbnailStorage, both referenced by
 * key, never embedded). Inlined base64 images are exactly what grew the
 * sessions file to the point of the issue #292 OOM boot loop, so the persist
 * path ({@code GeckoStateObserver}) swaps a {@code data:} icon for a small
 * file in {@code filesDir/tab_icons/} and writes the file PATH instead.
 *
 * <p>Files are named by a content hash of the source URI, so re-persisting the
 * same icon is a no-op (exists-check, no rewrite) and identical favicons across
 * tabs share one file. {@link #prune} deletes everything the current persist no
 * longer references — the same "mirror of the live set" contract the tab-thumb
 * store follows — so the store cannot accumulate.
 *
 * <p>The decode half ({@link #externalizeToDir}) is deliberately pure java.io /
 * java.util (no android.*) so the verification harness can run the REAL code on
 * the JVM — the same rule the parser smoke tests follow.
 */
public final class TabIconStore {

    private static final String DIR = "tab_icons";

    private TabIconStore() {
    }

    /**
     * Returns the persistable reference for a tab icon: a {@code data:} URI is
     * decoded into a sidecar file and its absolute path returned; anything else
     * passes through {@link GeckoStateDataRepository#sanitizeInlineField} (real
     * URLs and already-externalized paths survive, oversized junk is dropped).
     * Never throws; a failed decode/write degrades to {@code ""} (the icon
     * re-derives when the restored tab loads, same as a dropped one).
     */
    public static String externalize(Context context, String icon) {
        return externalizeToDir(storeDir(context), icon);
    }

    /** True if {@code ref} points into this store (used to build the referenced
     *  set {@link #prune} keeps). */
    public static boolean isStorePath(Context context, String ref) {
        if (ref == null || ref.isEmpty()) {
            return false;
        }
        return ref.startsWith(storeDir(context).getAbsolutePath() + File.separator);
    }

    /**
     * Deletes every stored icon file the current persist did not reference.
     * Runs on the DiskIO executor right after the sessions file is committed,
     * so the store always mirrors the live tab set (an empty set clears it —
     * the all-tabs-closed case, matching deleteThumbnails).
     */
    public static void prune(Context context, Set<String> referencedPaths) {
        File[] files = storeDir(context).listFiles();
        if (files == null) {
            return;
        }
        for (File file : files) {
            if (!referencedPaths.contains(file.getAbsolutePath())) {
                FileUtils.deleteQuietly(file);
            }
        }
    }

    private static File storeDir(Context context) {
        return new File(context.getFilesDir(), DIR);
    }

    // ── pure half (JVM-testable) ──────────────────────────────────────────

    /** See {@link #externalize}; {@code dir} is the store directory. */
    public static String externalizeToDir(File dir, String icon) {
        if (icon == null || icon.isEmpty()) {
            return "";
        }
        if (!icon.startsWith("data:")) {
            return GeckoStateDataRepository.sanitizeInlineField(icon);
        }
        // A "favicon" data URI beyond this is not a favicon — drop it rather
        // than materialize it (same bound the read-side sanitizer uses).
        if (icon.length() > GeckoStateDataRepository.MAX_INLINE_FIELD_CHARS) {
            return "";
        }
        int comma = icon.indexOf(',');
        if (comma < 0) {
            return "";
        }
        String header = icon.substring(5, comma).toLowerCase(Locale.ROOT);
        byte[] bytes = decodePayload(header, icon.substring(comma + 1));
        if (bytes == null || bytes.length == 0) {
            return "";
        }
        try {
            if (!dir.isDirectory() && !dir.mkdirs()) {
                return "";
            }
            File out = new File(dir, "icon_" + sha1Hex(icon) + extensionFor(header));
            // Content-hash name: an existing file IS this content — skip the write.
            if (!out.exists()) {
                File tmp = new File(dir, out.getName() + ".tmp");
                try (FileOutputStream fos = new FileOutputStream(tmp)) {
                    fos.write(bytes);
                }
                if (!tmp.renameTo(out)) {
                    FileUtils.deleteQuietly(tmp);
                    return "";
                }
            }
            return out.getAbsolutePath();
        } catch (IOException | RuntimeException e) {
            return "";
        }
    }

    /** Decodes a data-URI payload: base64 (lenient MIME decoder — data URIs in
     *  the wild carry stray whitespace) or percent-encoding (the common inline
     *  SVG/emoji favicon form). Returns null on undecodable input. */
    private static byte[] decodePayload(String header, String payload) {
        try {
            if (header.endsWith(";base64")) {
                return Base64.getMimeDecoder().decode(payload);
            }
            return URLDecoder.decode(payload, StandardCharsets.UTF_8.name())
                    .getBytes(StandardCharsets.UTF_8);
        } catch (IllegalArgumentException | IOException e) {
            return null;
        }
    }

    /** Extension only steers decoders that sniff the path (Glide's SVG decoder);
     *  raster formats are content-sniffed, so the fallback is harmless. */
    private static String extensionFor(String header) {
        if (header.startsWith("image/svg")) {
            return ".svg";
        }
        if (header.startsWith("image/png")) {
            return ".png";
        }
        if (header.startsWith("image/jpeg") || header.startsWith("image/jpg")) {
            return ".jpg";
        }
        if (header.startsWith("image/gif")) {
            return ".gif";
        }
        if (header.startsWith("image/webp")) {
            return ".webp";
        }
        if (header.startsWith("image/x-icon") || header.startsWith("image/vnd.microsoft.icon")) {
            return ".ico";
        }
        return ".img";
    }

    private static String sha1Hex(String s) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-1");
            byte[] hash = digest.digest(s.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                hex.append(String.format(Locale.ROOT, "%02x", b));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            // SHA-1 is mandatory on every Java/Android runtime.
            throw new AssertionError(e);
        }
    }
}
