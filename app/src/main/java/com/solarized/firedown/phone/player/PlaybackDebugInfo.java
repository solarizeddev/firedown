package com.solarized.firedown.phone.player;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.media.MediaCodec;
import android.os.Build;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.OptIn;
import androidx.media3.common.C;
import androidx.media3.common.Format;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.common.Tracks;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.exoplayer.mediacodec.MediaCodecRenderer;

import com.solarized.firedown.App;

import java.util.List;
import java.util.Locale;

/**
 * The text behind the "Copy" action on a playback-error snackbar: everything
 * a decode failure needs to be diagnosed WITHOUT adb — the error code and its
 * cause chain (incl. MediaCodec's diagnostic info and the decoder that was
 * tried), the container's track formats as the extractor read them (codec
 * string, sample rate / channels / size, and the codec-specific init data in
 * hex — for AAC that is the AudioSpecificConfig, whose signaling is what an
 * OEM decoder trips on), the file, the device and the app build.
 *
 * <p>Exists because a release build on a device without USB debugging has no
 * other channel: the snackbar's generic "can't decode" line is all the user
 * ever saw, and the typed cause went only to logcat. Everything here is
 * already in the player's memory; nothing is probed or fetched.
 */
public final class PlaybackDebugInfo {

    /** Longest init-data blob rendered in full (an AAC config is a few bytes,
     *  an avcC / hvcC a few dozen); longer ones are truncated with a marker. */
    private static final int MAX_INIT_DATA_HEX = 96;

    private PlaybackDebugInfo() {
    }

    /** The report. {@code player} and {@code fileName}/{@code fileSize} may be
     *  absent; the error section is always present. */
    @NonNull
    @OptIn(markerClass = UnstableApi.class)
    public static String describe(@NonNull PlaybackException error, @Nullable Player player,
                                  @Nullable String fileName, long fileSize,
                                  @Nullable String mimeType) {
        StringBuilder sb = new StringBuilder(1024);
        sb.append("Firedown ").append(App.getVersionName())
                .append(" (").append(App.getVersionCode()).append(")\n");
        sb.append(Build.MANUFACTURER).append(' ').append(Build.MODEL)
                .append(" · Android ").append(Build.VERSION.RELEASE)
                .append(" (SDK ").append(Build.VERSION.SDK_INT).append(")\n");
        if (fileName != null) {
            sb.append("File: ").append(fileName);
            if (fileSize > 0) {
                sb.append(" · ").append(fileSize).append(" bytes");
            }
            if (mimeType != null) {
                sb.append(" · ").append(mimeType);
            }
            sb.append('\n');
        }

        sb.append("\nError: ").append(error.getErrorCodeName())
                .append(" (").append(error.errorCode).append(")\n");
        Throwable t = error;
        int depth = 0;
        while (t != null && depth < 8) {
            sb.append(depth == 0 ? "  " : "  caused by ")
                    .append(t.getClass().getName());
            if (t.getMessage() != null) {
                sb.append(": ").append(t.getMessage());
            }
            sb.append('\n');
            if (t instanceof MediaCodecRenderer.DecoderInitializationException) {
                MediaCodecRenderer.DecoderInitializationException die =
                        (MediaCodecRenderer.DecoderInitializationException) t;
                sb.append("    mime=").append(die.mimeType)
                        .append(" decoder=").append(die.codecInfo != null ? die.codecInfo.name : "none")
                        .append(" secure=").append(die.secureDecoderRequired).append('\n');
                if (die.diagnosticInfo != null) {
                    sb.append("    diagnostic=").append(die.diagnosticInfo).append('\n');
                }
            }
            if (t instanceof MediaCodec.CodecException) {
                MediaCodec.CodecException ce = (MediaCodec.CodecException) t;
                sb.append("    codec diagnostic=").append(ce.getDiagnosticInfo())
                        .append(" recoverable=").append(ce.isRecoverable())
                        .append(" transient=").append(ce.isTransient()).append('\n');
            }
            Throwable next = t.getCause();
            t = next == t ? null : next;
            depth++;
        }

        if (player != null) {
            try {
                appendTracks(sb, player.getCurrentTracks());
            } catch (RuntimeException e) {
                // A player whose internal thread already died throws on any
                // access; the error section above is the part that matters.
                sb.append("\nTracks: unavailable (").append(e.getClass().getSimpleName()).append(")\n");
            }
        }
        return sb.toString();
    }

    private static void appendTracks(StringBuilder sb, Tracks tracks) {
        List<Tracks.Group> groups = tracks.getGroups();
        sb.append("\nTracks: ").append(groups.size()).append(" group(s)\n");
        for (int g = 0; g < groups.size(); g++) {
            Tracks.Group group = groups.get(g);
            for (int i = 0; i < group.length; i++) {
                Format f = group.getTrackFormat(i);
                sb.append("  [").append(g).append('.').append(i).append("] ")
                        .append(trackType(group.getType()))
                        .append(group.isTrackSelected(i) ? " selected" : "")
                        .append(group.isTrackSupported(i) ? "" : " UNSUPPORTED")
                        .append('\n');
                sb.append("    ").append(Format.toLogString(f)).append('\n');
                if (f.codecs != null) {
                    sb.append("    codecs=").append(f.codecs).append('\n');
                }
                if (f.channelCount != Format.NO_VALUE || f.sampleRate != Format.NO_VALUE) {
                    sb.append("    channels=").append(f.channelCount)
                            .append(" sampleRate=").append(f.sampleRate)
                            .append(" pcmEncoding=").append(f.pcmEncoding).append('\n');
                }
                for (int d = 0; d < f.initializationData.size(); d++) {
                    byte[] data = f.initializationData.get(d);
                    sb.append("    init[").append(d).append("] ").append(data.length)
                            .append(" bytes: ").append(hex(data)).append('\n');
                }
            }
        }
    }

    private static String trackType(@C.TrackType int type) {
        switch (type) {
            case C.TRACK_TYPE_VIDEO: return "video";
            case C.TRACK_TYPE_AUDIO: return "audio";
            case C.TRACK_TYPE_TEXT: return "text";
            case C.TRACK_TYPE_METADATA: return "metadata";
            default: return "type " + type;
        }
    }

    private static String hex(byte[] data) {
        int n = Math.min(data.length, MAX_INIT_DATA_HEX);
        StringBuilder sb = new StringBuilder(n * 2 + 4);
        for (int i = 0; i < n; i++) {
            sb.append(String.format(Locale.US, "%02x", data[i]));
        }
        if (n < data.length) {
            sb.append("…");
        }
        return sb.toString();
    }

    /** Puts the report on the clipboard. */
    public static void copy(@NonNull Context context, @NonNull String text) {
        ClipboardManager cm = (ClipboardManager) context.getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm != null) {
            cm.setPrimaryClip(ClipData.newPlainText("Firedown playback error", text));
        }
    }
}
