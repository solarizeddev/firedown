package com.solarized.firedown.data.capture;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.solarized.firedown.data.entity.BrowserDownloadEntity;
import com.solarized.firedown.data.entity.FFmpegTagEntity;
import com.solarized.firedown.ffmpegutils.FFmpegEntity;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * What the Captured sheet and the quality picker say about a capture besides
 * its title: the quality a tap downloads ("1080p") and how big the download
 * is. Users pick between two copies of one video by exactly these two facts
 * (a 1.2.2 report: "I'll need to choose one at random or all of them to
 * download, and then see its resolution and size").
 *
 * <p>A size is EXACT when the source states it — a rendition's byte size (a
 * parser's contentLength or DataSize) or the capture's own length (the
 * catcher's Content-Length) — and an ESTIMATE when only a bitrate is known (a
 * variant's bitrate, HLS BANDWIDTH, a DASH MPD): bitrate × duration. The UI
 * marks an estimate; it never shows a size it has neither.
 *
 * <p>Pure: no Android, so the capture harness runs it.
 */
public final class CaptureFacts {

    private CaptureFacts() {
    }

    /** A download size in bytes, and whether it is bitrate × duration. */
    public static final class Size {
        public final long bytes;
        public final boolean estimate;

        Size(long bytes, boolean estimate) {
            this.bytes = bytes;
            this.estimate = estimate;
        }
    }

    /**
     * The rendition a tap on the capture downloads: the first of the streams
     * in {@code order}, the order DownloadRequest.from sorts them in (a copy
     * is sorted here; the entity's list is left alone). Null with no streams.
     */
    @Nullable
    public static FFmpegEntity defaultVariant(@NonNull BrowserDownloadEntity entity,
                                              @NonNull Comparator<FFmpegEntity> order) {
        List<FFmpegEntity> streams = entity.getStreams();
        if (streams == null || streams.isEmpty()) {
            return null;
        }
        List<FFmpegEntity> sorted = new ArrayList<>(streams.size());
        for (FFmpegEntity stream : streams) {
            if (stream != null) {
                sorted.add(stream);
            }
        }
        if (sorted.isEmpty()) {
            return null;
        }
        sorted.sort(order);
        return sorted.get(0);
    }

    /**
     * The video quality a tap downloads ("1080p"): the default rendition's
     * label, else the capture's quality tag. Null for an audio-only rendition
     * (its label is a sample rate) and when nothing names a quality.
     */
    @Nullable
    public static String quality(@NonNull BrowserDownloadEntity entity,
                                 @NonNull Comparator<FFmpegEntity> order) {
        FFmpegEntity variant = defaultVariant(entity, order);
        if (variant != null) {
            if (variant.isAudioOnly()) {
                return null;
            }
            String info = variant.getInfo();
            if (info != null && !info.isEmpty()) {
                return info;
            }
        }
        List<FFmpegTagEntity> tags = entity.getTags();
        if (tags == null) {
            return null;
        }
        for (FFmpegTagEntity tag : tags) {
            if (tag != null && tag.getType() == FFmpegTagEntity.TYPE_QUALITY) {
                String text = tag.getText();
                if (text != null && !text.isEmpty()) {
                    return text;
                }
            }
        }
        return null;
    }

    /**
     * How big the download a tap starts is: the default rendition's exact
     * size, else the capture's own length (one file, the catcher's
     * Content-Length), else the rendition's bitrate × the capture's duration.
     */
    @Nullable
    public static Size size(@NonNull BrowserDownloadEntity entity,
                            @NonNull Comparator<FFmpegEntity> order) {
        FFmpegEntity variant = defaultVariant(entity, order);
        if (variant != null && variant.getByteSize() > 0) {
            return new Size(variant.getByteSize(), false);
        }
        if (entity.getFileLength() > 0) {
            return new Size(entity.getFileLength(), false);
        }
        return variant != null ? estimate(variant, entity.getDurationTime()) : null;
    }

    /** One rendition's size in the quality picker: exact, else estimated. */
    @Nullable
    public static Size variantSize(@Nullable FFmpegEntity variant, long durationUs) {
        if (variant == null) {
            return null;
        }
        if (variant.getByteSize() > 0) {
            return new Size(variant.getByteSize(), false);
        }
        return estimate(variant, durationUs);
    }

    /** Bitrate (bits/s) × duration (µs), or null when either is unknown. */
    @Nullable
    private static Size estimate(@NonNull FFmpegEntity variant, long durationUs) {
        int bitrate = variant.getBitrate();
        if (bitrate <= 0 || durationUs <= 0) {
            return null;
        }
        long bytes = Math.round((double) bitrate * (double) durationUs / 8_000_000d);
        return bytes > 0 ? new Size(bytes, true) : null;
    }

    /**
     * "1280x720" → "720p", the label a rendition of that frame size wears in
     * the picker (its height, as JsonHelper and the probe name it). Null for
     * anything that is not a frame size.
     */
    @Nullable
    public static String qualityOfResolution(@Nullable String resolution) {
        if (resolution == null) {
            return null;
        }
        int x = resolution.indexOf('x');
        if (x <= 0 || x == resolution.length() - 1) {
            return null;
        }
        try {
            int width = Integer.parseInt(resolution.substring(0, x).trim());
            int height = Integer.parseInt(resolution.substring(x + 1).trim());
            if (width <= 0 || height <= 0) {
                return null;
            }
            return height + "p";
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * Whether the ladder mixes video codec FAMILIES (YouTube: H.264 up to
     * 1080p, AV1 above): then the codec tells two tiles apart and the picker
     * names it beside the size; one family everywhere is boilerplate.
     */
    public static boolean mixedVideoCodecs(@Nullable List<FFmpegEntity> variants) {
        if (variants == null) {
            return false;
        }
        String first = null;
        for (FFmpegEntity variant : variants) {
            if (variant == null || variant.isAudioOnly()) {
                continue;
            }
            String family = codecFamily(variant.getVideoCodec());
            if (family == null) {
                continue;
            }
            if (first == null) {
                first = family;
            } else if (!first.equals(family)) {
                return true;
            }
        }
        return false;
    }

    /** "avc1.640028" → "avc1", "av01.0.08M.08" → "av01"; null when blank. */
    @Nullable
    public static String codecFamily(@Nullable String codec) {
        if (codec == null) {
            return null;
        }
        String trimmed = codec.trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        int dot = trimmed.indexOf('.');
        return dot > 0 ? trimmed.substring(0, dot) : trimmed;
    }
}
