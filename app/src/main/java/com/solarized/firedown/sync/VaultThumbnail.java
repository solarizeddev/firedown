package com.solarized.firedown.sync;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.media.MediaMetadataRetriever;
import android.os.Build;
import android.os.ParcelFileDescriptor;
import android.util.Base64;

import com.solarized.firedown.data.RestoredFileAccess;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;

/**
 * Generates a tiny base64 JPEG preview for a local file, stored inside the
 * encrypted Cloud Backup manifest so the backed-up-files list can show a
 * thumbnail offline — even after the local copy is deleted (the whole point of
 * backing up). Kept small by design (~{@value #MAX_DIM}px longest side) so a
 * manifest with many files stays well under the 16 MiB server cap.
 *
 * <p>Video → a frame ~1s in (skips the black opening frame); image → the decoded
 * bitmap; audio → embedded album art if present. Anything else, or any failure,
 * yields {@code null} (the list falls back to a mime glyph). Generated on the
 * worker thread, never the main thread.
 */
public final class VaultThumbnail {

    /** Longest side of the stored preview, in pixels. Package-visible so the
     *  display-time backfill ({@code CloudBackupManager.resolveLocalThumb}) can
     *  size its Glide-decoded bitmaps to the same footprint. */
    static final int MAX_DIM = 160;
    private static final int JPEG_QUALITY = 60;
    /** Base64 flags — must match the list decoder. No newlines (it rides in JSON). */
    private static final int B64 = Base64.NO_WRAP;

    private VaultThumbnail() {
    }

    /** A base64 JPEG preview for {@code path}, or null if none applies / on error. */
    public static String generate(String path, String mime) {
        return generate(null, path, mime, 0L);
    }

    /**
     * A base64 JPEG preview for {@code path}, or null if none applies / on error.
     * {@code frameUs} is the exact video frame position (µs) to grab — pass the
     * value {@link com.solarized.firedown.GlideHelper#thumbnailFrameUs} gives for
     * the same download so the stored preview matches the Downloads list thumbnail
     * precisely; pass {@code <= 0} to let it pick a duration-aware offset.
     */
    public static String generate(String path, String mime, long frameUs) {
        return generate(null, path, mime, frameUs);
    }

    /**
     * As {@link #generate(String, String, long)}, but able to read a RESTORED
     * foreign-owned file through the persisted SAF grant ({@code
     * RestoredFileAccess}) when the direct path isn't readable — on Android 11+ a
     * reinstalled app doesn't own its old public files, so every path-based
     * decode here silently failed and restored files backed up with NO preview
     * (and the display backfill couldn't heal them either). {@code context} may
     * be null (direct-path behaviour only).
     */
    public static String generate(Context context, String path, String mime, long frameUs) {
        Bitmap scaled = generateBitmap(context, path, mime, frameUs);
        if (scaled == null) {
            return null;
        }
        try {
            return encode(scaled);
        } finally {
            scaled.recycle();
        }
    }

    /**
     * The decoded (≤{@link #MAX_DIM}px longest side) preview bitmap for
     * {@code path}, or null if none applies / on error. The caller OWNS the
     * returned bitmap. Split out of {@link #generate(Context, String, String,
     * long)} so the display-time backfill can hand the list adapter a bitmap
     * directly instead of encoding to base64 JPEG only for the row bind to
     * decode it straight back.
     */
    public static Bitmap generateBitmap(Context context, String path, String mime, long frameUs) {
        if (path == null || mime == null) {
            return null;
        }
        Bitmap bmp = null;
        try {
            if (mime.startsWith("image/")) {
                bmp = decodeImage(context, path);
            } else if (mime.startsWith("video/")) {
                bmp = decodeVideoFrame(context, path, frameUs);
            } else if (mime.startsWith("audio/")) {
                bmp = decodeAudioArt(context, path);
            }
            if (bmp == null) {
                return null;
            }
            Bitmap scaled = scaleDown(bmp);
            if (scaled != bmp) {
                bmp.recycle();
            }
            return scaled;
        } catch (Exception e) {
            if (bmp != null) {
                bmp.recycle();
            }
            return null;
        }
    }

    private static Bitmap decodeImage(Context context, String path) {
        if (new File(path).canRead()) {
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(path, bounds);
            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight);
            return BitmapFactory.decodeFile(path, opts);
        }
        if (context == null) {
            return null;
        }
        // Restored foreign-owned image — the fd isn't rewindable across the two
        // decode passes, so open the SAF grant once per pass.
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        try (ParcelFileDescriptor pfd = RestoredFileAccess.openReadOnly(context, path)) {
            if (pfd == null) {
                return null;
            }
            BitmapFactory.decodeFileDescriptor(pfd.getFileDescriptor(), null, bounds);
        } catch (IOException e) {
            return null;
        }
        BitmapFactory.Options opts = new BitmapFactory.Options();
        opts.inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight);
        try (ParcelFileDescriptor pfd = RestoredFileAccess.openReadOnly(context, path)) {
            if (pfd == null) {
                return null;
            }
            return BitmapFactory.decodeFileDescriptor(pfd.getFileDescriptor(), null, opts);
        } catch (IOException e) {
            return null;
        }
    }

    /**
     * Binds the retriever to the file: the direct path when readable, else the
     * persisted SAF grant (restored foreign-owned file). Returns the descriptor
     * the caller must close AFTER releasing the retriever, or null when the
     * direct path was used.
     */
    private static ParcelFileDescriptor bindSource(MediaMetadataRetriever mmr,
                                                   Context context, String path) throws IOException {
        if (new File(path).canRead()) {
            mmr.setDataSource(path);
            return null;
        }
        ParcelFileDescriptor pfd =
                context != null ? RestoredFileAccess.openReadOnly(context, path) : null;
        if (pfd == null) {
            throw new IOException("unreadable: " + path);
        }
        mmr.setDataSource(pfd.getFileDescriptor());
        return pfd;
    }

    private static Bitmap decodeVideoFrame(Context context, String path, long frameUs)
            throws IOException {
        MediaMetadataRetriever mmr = new MediaMetadataRetriever();
        ParcelFileDescriptor pfd = null;
        try {
            pfd = bindSource(mmr, context, path);
            // Prefer the exact frame the Downloads list uses (passed in µs); else
            // fall back to a duration-aware offset. NEXT_SYNC = the first keyframe
            // AT/AFTER that point, so the black opening keyframe (t=0) is skipped —
            // CLOSEST_SYNC would snap back to it on a sparse GOP, which is what
            // produced black previews. Same option the list thumbnailer
            // (GlideHelper, VideoDecoder.FRAME_OPTION = OPTION_NEXT_SYNC) uses, so
            // the stored preview matches the list frame.
            long offsetUs = frameUs > 0 ? frameUs : videoFrameOffsetUs(mmr);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
                Bitmap scaled = mmr.getScaledFrameAtTime(offsetUs,
                        MediaMetadataRetriever.OPTION_NEXT_SYNC, MAX_DIM, MAX_DIM);
                if (scaled != null) {
                    return scaled;
                }
            }
            Bitmap frame = mmr.getFrameAtTime(offsetUs, MediaMetadataRetriever.OPTION_NEXT_SYNC);
            if (frame != null) {
                return frame;
            }
            // A short clip with no keyframe past the offset — take the head frame.
            return mmr.getFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC);
        } finally {
            releaseQuietly(mmr);
            closeQuietly(pfd);
        }
    }

    /** ~3s in, but never past the clip's midpoint, so a short clip still resolves
     *  a keyframe (and never seeks beyond the end). */
    private static long videoFrameOffsetUs(MediaMetadataRetriever mmr) {
        long durationMs = 0;
        try {
            String d = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION);
            if (d != null) {
                durationMs = Long.parseLong(d);
            }
        } catch (NumberFormatException ignored) {
            // unknown duration — use the default offset
        }
        long defaultUs = 3_000_000L;
        if (durationMs <= 0) {
            return defaultUs;
        }
        return Math.min(defaultUs, durationMs * 1000L / 2L);
    }

    private static Bitmap decodeAudioArt(Context context, String path) throws IOException {
        MediaMetadataRetriever mmr = new MediaMetadataRetriever();
        ParcelFileDescriptor pfd = null;
        try {
            pfd = bindSource(mmr, context, path);
            byte[] art = mmr.getEmbeddedPicture();
            if (art == null) {
                return null;
            }
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            BitmapFactory.decodeByteArray(art, 0, art.length, bounds);
            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight);
            return BitmapFactory.decodeByteArray(art, 0, art.length, opts);
        } finally {
            releaseQuietly(mmr);
            closeQuietly(pfd);
        }
    }

    /** inSampleSize that brings the longest side to roughly {@link #MAX_DIM}. */
    private static int sampleSize(int w, int h) {
        int longest = Math.max(w, h);
        int sample = 1;
        while (longest / sample > MAX_DIM * 2) {
            sample *= 2;
        }
        return sample;
    }

    /** Scales the bitmap so its longest side is at most {@link #MAX_DIM}. */
    private static Bitmap scaleDown(Bitmap src) {
        int w = src.getWidth();
        int h = src.getHeight();
        int longest = Math.max(w, h);
        if (longest <= MAX_DIM) {
            return src;
        }
        float ratio = (float) MAX_DIM / longest;
        int nw = Math.max(1, Math.round(w * ratio));
        int nh = Math.max(1, Math.round(h * ratio));
        return Bitmap.createScaledBitmap(src, nw, nh, true);
    }

    private static String encode(Bitmap bmp) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        bmp.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out);
        return Base64.encodeToString(out.toByteArray(), B64);
    }

    /** Decodes a stored preview back to a bitmap (list side), or null. */
    public static Bitmap decode(String thumb) {
        if (thumb == null || thumb.isEmpty()) {
            return null;
        }
        try {
            byte[] bytes = Base64.decode(thumb, B64);
            return BitmapFactory.decodeByteArray(bytes, 0, bytes.length);
        } catch (Exception e) {
            return null;
        }
    }

    private static void releaseQuietly(MediaMetadataRetriever mmr) {
        try {
            mmr.release();
        } catch (Exception ignored) {
            // best-effort
        }
    }

    /** Closes the SAF descriptor backing a retriever, after release. */
    private static void closeQuietly(ParcelFileDescriptor pfd) {
        if (pfd == null) {
            return;
        }
        try {
            pfd.close();
        } catch (IOException ignored) {
            // best-effort
        }
    }
}
