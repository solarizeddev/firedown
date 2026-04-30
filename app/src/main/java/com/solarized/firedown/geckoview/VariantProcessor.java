package com.solarized.firedown.geckoview;

import android.text.TextUtils;
import android.util.Log;

import com.solarized.firedown.App;
import com.solarized.firedown.BuildConfig;
import com.solarized.firedown.R;
import com.solarized.firedown.data.entity.BrowserDownloadEntity;
import com.solarized.firedown.data.entity.FFmpegTagEntity;
import com.solarized.firedown.ffmpegutils.FFmpegEntity;
import com.solarized.firedown.ffmpegutils.FFmpegMetaData;
import com.solarized.firedown.ffmpegutils.FFmpegMetaDataReader;
import com.solarized.firedown.ffmpegutils.FFmpegStreamInfo;
import com.solarized.firedown.manager.UrlType;
import com.solarized.firedown.utils.FileUriHelper;

import java.util.ArrayList;
import java.util.Map;

/**
 * Probes pre-parsed stream variants and populates entity metadata.
 * Handles both single-URL variants (Twitter, Instagram) and
 * paired video+audio URL variants (YouTube adaptive).
 *
 * For SABR-only variants (empty URLs), skips FFprobe and uses
 * JS-provided metadata (codec, resolution) directly.
 */
public class VariantProcessor {

    private static final String TAG = VariantProcessor.class.getSimpleName();

    private final Map<String, String> mHeaders;

    public VariantProcessor(Map<String, String> headers) {
        mHeaders = headers;
    }

    /**
     * This is the right trade-off: one probe gives you duration, mime type, and codecs.
     * The user sees the download item instantly instead of waiting for 6 sequential FFprobe calls.
     * The codec assumption (all variants from the same source share codecs) is valid for Twitter, Instagram, and YouTube (we already filter to mp4 family).
     * If you later add a source where variants have mixed codecs, the JS-side pre-populated videoCodec/audioCodec fields would already be set and the variant.getVideoCodec() == null check would skip the override.
     */
    public void process(BrowserDownloadEntity entity, ArrayList<FFmpegEntity> variants) {
        if(BuildConfig.DEBUG){
            for(FFmpegEntity fFmpegEntity : variants){
                Log.d(TAG, "processVariants: " + fFmpegEntity.getStreamUrl());
            }
        }

        if (variants.isEmpty()) return;

        FFmpegEntity first = variants.get(0);
        String videoUrl = first.getStreamUrl();

        // SABR-only variants: URLs are empty but we have codec/resolution from JS.
        // Skip FFprobe entirely — populate entity from what we already know.
        if (TextUtils.isEmpty(videoUrl)) {
            if (first.hasSabrData()) {
                Log.d(TAG, "SABR-only variants (no URLs), skipping FFprobe");
                entity.setMimeType(FileUriHelper.MIMETYPE_MP4);
                entity.setType(UrlType.MEDIA.getValue());
                entity.setAudio(false);

                // Duration comes from the native message (set by JsonHelper/GeckoInspectTask)
                // Codecs are already set on each variant by JsonHelper.parseVariants()

                entity.setStreams(variants);
                entity.setHasVariants(variants.size() > 1);
                entity.setTags(buildTags(entity, variants));
                return;
            }
            // No SABR data and no URLs — nothing we can do
            return;
        }

        String audioUrl = first.getStreamAudioUrl();
        boolean hasAudioUrl = !TextUtils.isEmpty(audioUrl);

        FFmpegMetaDataReader reader = new FFmpegMetaDataReader();
        try {

            Log.d(TAG, "processVariants ffmpeg: " + (hasAudioUrl ? ( " videoUrl: " + videoUrl) : (" videoUrl: " + videoUrl + " audioUrl: " +audioUrl)));

            FFmpegMetaData metadata = hasAudioUrl
                    ? reader.getStreamInfo(new String[]{videoUrl, audioUrl}, mHeaders, false)
                    : reader.getStreamInfo(videoUrl, mHeaders, false);

            if (metadata != null && metadata.isValidMedia()) {
                entity.setMimeType(reader.getMimeType(entity.getMimeType()));
                entity.setFileDuration(metadata.getDuration());
                entity.setAudio(metadata.isAudio());
                entity.setType(metadata.getType());

                // Extract codecs from the probed stream
                String videoCodec = null;
                String audioCodec = null;
                for (FFmpegStreamInfo info : metadata.getFFmpegStreamInfo()) {
                    if (info == null) continue;
                    if (info.getMediaType() == FFmpegStreamInfo.CodecType.VIDEO && videoCodec == null) {
                        videoCodec = info.getCodecName();
                    } else if (info.getMediaType() == FFmpegStreamInfo.CodecType.AUDIO && audioCodec == null) {
                        audioCodec = info.getCodecName();
                    }
                }

                // Apply to all variants (same source, same codecs)
                for (FFmpegEntity variant : variants) {
                    if (videoCodec != null && variant.getVideoCodec() == null)
                        variant.setVideoCodec(videoCodec);
                    if (audioCodec != null && variant.getAudioCodec() == null)
                        variant.setAudioCodec(audioCodec);
                }
            } else {
                entity.setMimeType(FileUriHelper.MIMETYPE_MP4);
                entity.setType(UrlType.FILE.getValue());
            }
        } catch (Exception e) {
            Log.w(TAG, "Failed to probe variant: " + videoUrl, e);
            entity.setMimeType(FileUriHelper.MIMETYPE_MP4);
            entity.setType(UrlType.FILE.getValue());
        } finally {
            reader.stop();
            reader.release();
        }

        entity.setStreams(variants);
        entity.setHasVariants(variants.size() > 1);
        entity.setTags(buildTags(entity, variants));
    }

    private void applyCodecs(FFmpegEntity variant, FFmpegStreamInfo[] streams) {
        if (streams == null) return;
        for (FFmpegStreamInfo info : streams) {
            if (info == null) continue;
            if (info.getMediaType() == FFmpegStreamInfo.CodecType.VIDEO) {
                variant.setVideoCodec(info.getCodecName());
            } else if (info.getMediaType() == FFmpegStreamInfo.CodecType.AUDIO) {
                variant.setAudioCodec(info.getCodecName());
            }
        }
    }

    private ArrayList<FFmpegTagEntity> buildTags(BrowserDownloadEntity entity,
                                                 ArrayList<FFmpegEntity> variants) {
        ArrayList<FFmpegTagEntity> tags = new ArrayList<>();
        int uid = entity.getUid();
        String duration = entity.getFileDuration();

        if (!TextUtils.isEmpty(duration)) {
            tags.add(new FFmpegTagEntity(uid, duration, FFmpegTagEntity.TYPE_DURATION));
        }
        if (variants.size() > 1) {
            tags.add(new FFmpegTagEntity(uid,
                    App.getAppContext().getString(R.string.download_adaptative),
                    FFmpegTagEntity.TYPE_QUALITY));
        } else if (!variants.isEmpty()) {
            String info = variants.get(0).getInfo();
            if (!TextUtils.isEmpty(info)) {
                tags.add(new FFmpegTagEntity(uid, info, FFmpegTagEntity.TYPE_QUALITY));
            }
        }
        return tags;
    }
}