package com.solarized.firedown.sync;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.media.MediaMetadataRetriever;
import android.os.Build;
import android.os.ParcelFileDescriptor;
import android.util.Base64;
import android.util.Log;

import com.solarized.firedown.BuildConfig;
import com.solarized.firedown.data.RestoredFileAccess;
import com.solarized.firedown.ffmpegutils.FFmpegThumbnailer;

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

    /**
     * Longest side of the STORED preview, in pixels — the one that rides inside
     * the encrypted manifest, so it is bounded by the 16 MiB server cap AND by
     * the fact that the whole manifest is pulled and pushed on EVERY mutation
     * (OCC), on a metered store. That is the real cost, not the cap.
     *
     * <p>Sizing history, each step calibrated against the DISPLAY, not just the
     * budget: 160/q60 was sized purely against the manifest and read "very
     * poor" on-device (a list row is 78x64dp = 234x192 px on a 3x phone → ~1.5x
     * upscale; a grid tile ~172x110dp = 516x330 px → ~3.2x). 256/q80 covered
     * the list row outright but still upscaled ~2x on the grid tile — the one
     * surface left soft, and where a cloud-only entry (or any entry on a second
     * device) has no local file to rescue it. 384 brings the grid to ~1.34x —
     * under the ~1.5x threshold where a bilinear upscale of a photo stops being
     * visible — while the list gains nothing left to gain. The pixel-perfect
     * next step (512, matching the grid tile exactly) costs 4x the bytes of 256
     * for that last ~25% and was deliberately not taken.
     *
     * <p>Budget check at 384/q80, base64 included (it inflates by 4/3): ~25 KB
     * per entry (2.25x the area of 256's ~11 KB), so ~600 files still fit the
     * 16 MiB manifest; a 100-file account carries ~2.5 MB of base64 in the
     * JSON, which gzips back to roughly the raw JPEG bytes (~1.9 MB) per
     * manifest pull AND push. If this is ever raised again, do the same
     * arithmetic — area scales with the SQUARE of this number, and every byte
     * is paid on each pull and each push, not once.
     *
     * <p>Existing entries keep whatever they were stored with; nothing re-encodes
     * them, so this improves NEW backups (and any file re-backed-up, where
     * {@code VaultEngine.backupFile} rewrites the thumb without re-uploading).
     */
    private static final String TAG = VaultThumbnail.class.getSimpleName();

    static final int MAX_DIM = 384;
    /**
     * Longest side for a DISPLAY-ONLY bitmap decoded from the local file
     * ({@code CloudBackupManager.resolveLocalThumb}). It never enters the
     * manifest, so the stored budget above does not apply and there is no reason
     * to hand the list an upscaled image when the real file is right there —
     * this covers a grid tile at 3x with headroom. The consumer cache
     * ({@code CloudBackupFileAdapter}) is byte-bounded precisely because these
     * are ~4x the area of a stored thumb.
     */
    public static final int DISPLAY_DIM = 512;
    private static final int JPEG_QUALITY = 80;
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
        Bitmap scaled = generateBitmap(context, path, mime, frameUs, MAX_DIM);
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
        return generateBitmap(context, path, mime, frameUs, MAX_DIM);
    }

    /**
     * As above, but scaled to {@code maxDim} on the longest side — {@link
     * #MAX_DIM} for anything destined for the manifest, {@link #DISPLAY_DIM} for
     * a display-only backfill from the local file (which costs no manifest
     * bytes, so it has no reason to be stored-size).
     */
    public static Bitmap generateBitmap(Context context, String path, String mime, long frameUs,
                                        int maxDim) {
        if (path == null || mime == null) {
            return null;
        }
        Bitmap bmp = null;
        try {
            if (mime.startsWith("image/")) {
                bmp = decodeImage(context, path, maxDim);
            } else if (mime.startsWith("video/")) {
                bmp = decodeVideoFrame(context, path, frameUs, maxDim);
            } else if (mime.startsWith("audio/")) {
                bmp = decodeAudioArt(context, path);
            }
            if (bmp == null) {
                return null;
            }
            Bitmap scaled = scaleDown(bmp, maxDim);
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

    private static Bitmap decodeImage(Context context, String path, int maxDim) {
        if (new File(path).canRead()) {
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(path, bounds);
            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight, maxDim);
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
        opts.inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight, maxDim);
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

    private static Bitmap decodeVideoFrame(Context context, String path, long frameUs,
                                          int maxDim) throws IOException {
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
                        MediaMetadataRetriever.OPTION_NEXT_SYNC, maxDim, maxDim);
                if (scaled != null) {
                    return scaled;
                }
            }
            Bitmap frame = mmr.getFrameAtTime(offsetUs, MediaMetadataRetriever.OPTION_NEXT_SYNC);
            if (frame != null) {
                return frame;
            }
            // A short clip with no keyframe past the offset — take the head frame.
            frame = mmr.getFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC);
            if (frame != null) {
                return frame;
            }
        } finally {
            releaseQuietly(mmr);
            closeQuietly(pfd);
        }
        // MMR could not decode this clip at all. That is NOT an edge case: MMR is
        // the platform decoder, so a codec the device's MMR lacks (AV1 is the
        // documented one) fails here while the very same file thumbnails fine in
        // the Downloads list, which has always had the native-FFmpeg fallback
        // (GlideHelper -> FFmpegPfdDecoder / FFmpegUriDecoder). Without this
        // fallback the stored preview was silently null, and the Backups row only
        // LOOKED fine on the device holding the file, because it fell back to the
        // local-file backfill — the other device, restoring the same manifest, got
        // a mime glyph. Diagnosed exactly that way across two devices sharing one
        // recovery code.
        return decodeVideoFrameNative(context, path, frameUs, maxDim);
    }

    /**
     * Native-FFmpeg video frame — the fallback for anything the platform
     * MediaMetadataRetriever cannot open (see the call site). Mirrors the
     * Downloads list's own fallback chain, and uses the SAME {@code streamPos}
     * contract as {@code FFmpegThumbnailer}: a positive value is "first keyframe
     * at/after this µs", and a NEGATIVE value means "no mandate" so the native
     * side picks its duration-aware offset and skips the black opening frame.
     * Passing 0 would pin it to the head frame, which is the black one.
     */
    private static Bitmap decodeVideoFrameNative(Context context, String path, long frameUs,
                                                int maxDim) {
        FFmpegThumbnailer thumbnailer = new FFmpegThumbnailer();
        ParcelFileDescriptor pfd = null;
        try {
            thumbnailer.setTargetSizeHint(maxDim, maxDim);
            int rc;
            if (new File(path).canRead()) {
                rc = thumbnailer.setDataSource(path, null);
            } else {
                // Restored foreign-owned file — same SAF grant bindSource() uses.
                pfd = context != null ? RestoredFileAccess.openReadOnly(context, path) : null;
                if (pfd == null) {
                    return null;
                }
                rc = thumbnailer.setDataSource(pfd.getFileDescriptor(), null);
            }
            if (rc < 0) {
                if (BuildConfig.DEBUG) {
                    Log.w(TAG, "native thumbnailer setDataSource failed rc=" + rc);
                }
                return null;
            }
            return thumbnailer.getBitmap(frameUs > 0 ? frameUs : -1L);
        } catch (Exception e) {
            if (BuildConfig.DEBUG) {
                Log.w(TAG, "native thumbnail failed", e);
            }
            return null;
        } finally {
            thumbnailer.release();
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
            opts.inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight, MAX_DIM);
            return BitmapFactory.decodeByteArray(art, 0, art.length, opts);
        } finally {
            releaseQuietly(mmr);
            closeQuietly(pfd);
        }
    }

    /** inSampleSize that brings the longest side to roughly {@code maxDim}. */
    private static int sampleSize(int w, int h, int maxDim) {
        int longest = Math.max(w, h);
        int sample = 1;
        while (longest / sample > maxDim * 2) {
            sample *= 2;
        }
        return sample;
    }

    /** Scales the bitmap so its longest side is at most {@code maxDim}. */
    private static Bitmap scaleDown(Bitmap src, int maxDim) {
        int w = src.getWidth();
        int h = src.getHeight();
        int longest = Math.max(w, h);
        if (longest <= maxDim) {
            return src;
        }
        float ratio = (float) maxDim / longest;
        int nw = Math.max(1, Math.round(w * ratio));
        int nh = Math.max(1, Math.round(h * ratio));
        return Bitmap.createScaledBitmap(src, nw, nh, true);
    }

    /** Encodes a preview to the stored base64-JPEG form ({@link #decode}'s
     *  inverse) — also used to hand a display-backfilled bitmap to the item
     *  sheet in the same shape a manifest thumb travels in. */
    public static String encode(Bitmap bmp) {
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
