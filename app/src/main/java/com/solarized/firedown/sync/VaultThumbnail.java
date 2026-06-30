package com.solarized.firedown.sync;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.media.MediaMetadataRetriever;
import android.os.Build;
import android.util.Base64;

import java.io.ByteArrayOutputStream;

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

    /** Longest side of the stored preview, in pixels. */
    private static final int MAX_DIM = 160;
    private static final int JPEG_QUALITY = 60;
    /** Base64 flags — must match the list decoder. No newlines (it rides in JSON). */
    private static final int B64 = Base64.NO_WRAP;

    private VaultThumbnail() {
    }

    /** A base64 JPEG preview for {@code path}, or null if none applies / on error. */
    public static String generate(String path, String mime) {
        if (path == null || mime == null) {
            return null;
        }
        Bitmap bmp = null;
        try {
            if (mime.startsWith("image/")) {
                bmp = decodeImage(path);
            } else if (mime.startsWith("video/")) {
                bmp = decodeVideoFrame(path);
            } else if (mime.startsWith("audio/")) {
                bmp = decodeAudioArt(path);
            }
            if (bmp == null) {
                return null;
            }
            Bitmap scaled = scaleDown(bmp);
            String b64 = encode(scaled);
            if (scaled != bmp) {
                scaled.recycle();
            }
            return b64;
        } catch (Exception e) {
            return null;
        } finally {
            if (bmp != null) {
                bmp.recycle();
            }
        }
    }

    private static Bitmap decodeImage(String path) {
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(path, bounds);
        BitmapFactory.Options opts = new BitmapFactory.Options();
        opts.inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight);
        return BitmapFactory.decodeFile(path, opts);
    }

    private static Bitmap decodeVideoFrame(String path) {
        MediaMetadataRetriever mmr = new MediaMetadataRetriever();
        try {
            mmr.setDataSource(path);
            // NEXT_SYNC at an offset = the first keyframe AT/AFTER that point, so
            // the black opening keyframe (t=0) is skipped — CLOSEST_SYNC would snap
            // back to it on a sparse GOP, which is what produced black previews.
            // Same approach the list thumbnailer (GlideHelper) uses.
            long offsetUs = videoFrameOffsetUs(mmr);
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

    private static Bitmap decodeAudioArt(String path) {
        MediaMetadataRetriever mmr = new MediaMetadataRetriever();
        try {
            mmr.setDataSource(path);
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
}
