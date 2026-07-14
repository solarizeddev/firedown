package com.solarized.firedown.geckoview;

import android.text.TextUtils;
import android.util.Log;

import com.solarized.firedown.BuildConfig;
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
import java.util.regex.Pattern;

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
    private final boolean mSkipProbe;
    // Optional: lets the owning GeckoInspectTask interrupt our probe reader when
    // its tab is closed. Null for callers that don't need cancellation.
    private ProbeRegistry mProbeRegistry;
    // Parser/enumerator-declared: the variants are HLS/DASH manifests (ffmpeg
    // must mux them). Authoritative over the URL-extension fallback below.
    private final boolean mManifest;

    // An HLS/DASH manifest (ffmpeg must mux it) — as opposed to a progressive
    // container, downloaded raw via HttpDownloadStrategy. Matches the extension
    // followed by a query (?…), a fragment (#…), or end-of-string. The fragment
    // case is load-bearing: Dailymotion renditions end in
    // `…/manifest.m3u8#cell=cf3`, and a `?`-only test treated that as progressive
    // → FILE → HttpDownloadStrategy downloaded the playlist text instead of
    // muxing. We test for a *manifest* rather than a progressive extension
    // because progressive CDN URLs are frequently tokenized with no .mp4 at all
    // (TikTok), whereas HLS/DASH renditions reliably end in .m3u8/.mpd — so
    // "not a manifest" reliably means progressive.
    private static final Pattern MANIFEST_URL =
            Pattern.compile("https?://.*\\.(m3u8|mpd)(?:[?#].*|$)", Pattern.CASE_INSENSITIVE);

    private static boolean isManifestUrl(String url) {
        return !TextUtils.isEmpty(url) && MANIFEST_URL.matcher(url).matches();
    }

    public VariantProcessor(Map<String, String> headers) {
        this(headers, false, false);
    }

    public VariantProcessor(Map<String, String> headers, boolean skipProbe) {
        this(headers, skipProbe, false);
    }

    /**
     * @param manifest the caller/parser declares these variants are HLS/DASH
     *                 manifest playlists that ffmpeg must mux (entity type MEDIA).
     *                 This is the authoritative signal — the code that parsed the
     *                 master (M3U8Parser / parseHlsMaster) or knows the protocol
     *                 sets it, so we don't have to guess from the URL extension
     *                 (which fails on obfuscated/tokenized manifests with no
     *                 .m3u8/.mpd, and on #fragment / ?query tails).
     */
    public VariantProcessor(Map<String, String> headers, boolean skipProbe, boolean manifest) {
        mHeaders = headers;
        mSkipProbe = skipProbe;
        mManifest = manifest;
    }

    /**
     * This is the right trade-off: one probe gives you duration, mime type, and codecs.
     * The user sees the download item instantly instead of waiting for 6 sequential FFprobe calls.
     * The codec assumption (all variants from the same source share codecs) is valid for Twitter, Instagram, and YouTube (we already filter to mp4 family).
     * If you later add a source where variants have mixed codecs, the JS-side pre-populated videoCodec/audioCodec fields would already be set and the variant.getVideoCodec() == null check would skip the override.
     */
    /**
     * Register the owning task so it can interrupt our probe reader on tab
     * close. Chainable so call sites stay one expression. Optional — when unset,
     * the probe simply isn't externally cancellable (it still self-bounds via the
     * hls.c consecutive-failure bail).
     */
    public VariantProcessor setProbeRegistry(ProbeRegistry registry) {
        mProbeRegistry = registry;
        return this;
    }

    public void process(BrowserDownloadEntity entity, ArrayList<FFmpegEntity> variants) {
        if(BuildConfig.DEBUG){
            for(FFmpegEntity fFmpegEntity : variants){
                Log.d(TAG, "processVariants: " + fFmpegEntity.getStreamUrl());
            }
        }

        if (variants.isEmpty()) return;

        // Parser-supplied metadata (e.g. niconico enumerates renditions from the
        // master playlist): do NOT FFprobe. Probing opens+decrypts a segment,
        // which for a single-use-AES-key HLS (domand) burns the key the
        // downloader needs (→ "shows in Capture, then hangs on download"). The
        // download is then the first/only key consumer. Codecs are already set
        // per variant by JsonHelper.parseVariants; duration comes from the message.
        if (mSkipProbe) {
            // entity type drives strategy selection (DownloadTask.selectStrategy):
            // MEDIA → ffmpeg (FFmpegMux/Merge), FILE → HttpDownloadStrategy (raw,
            // byte-exact copy with truncation-resume). A variant needs ffmpeg
            // (MEDIA) when it's a separate video+audio pair OR an HLS/DASH
            // manifest; otherwise it's a single progressive file (FILE).
            //
            // The manifest decision is AUTHORITATIVELY the parser's: the code that
            // enumerated the master (M3U8Parser / parseHlsMaster) sets mManifest.
            // We do NOT trust the URL extension for it — obfuscated/tokenized
            // manifests don't end in .m3u8/.mpd, and Dailymotion renditions carry
            // a #fragment. isManifestUrl stays only as a best-effort fallback for
            // any caller that didn't declare it. Progressive (TikTok et al.) is
            // the default, so a tokenized extensionless mp4 isn't needlessly
            // remuxed.
            FFmpegEntity first = variants.get(0);
            boolean stream = mManifest
                    || !TextUtils.isEmpty(first.getStreamAudioUrl())
                    || isManifestUrl(first.getStreamUrl());
            boolean progressive = !stream;
            // A DECLARED audio-only progressive variant (JsonHelper parsed the
            // emit's `audioOnly` — the page-state bridge's AUDIO_RE / <audio>
            // classification, e.g. podverse's __NEXT_DATA__ mp3) is AUDIO. Keep
            // the URL-derived audio mime prepareEntity already set instead of
            // stamping video/mp4 — that stamp made a podcast mp3 show as a
            // VIDEO and save under a .mp4 name. Type stays FILE: the raw
            // byte-exact HttpDownloadStrategy, the same strategy the probe path
            // yields for a progressive audio file — no remux.
            boolean audioOnly = progressive && first.isAudioOnly();
            Log.d(TAG, "skipProbe set, using parser metadata (no FFprobe), manifest=" + mManifest + " progressive=" + progressive + " audioOnly=" + audioOnly);
            if (audioOnly) {
                if (!FileUriHelper.isAudio(entity.getMimeType())) {
                    // Declared audio but the URL carried no recognisable audio
                    // extension (tokenized enclosure) — default to the dominant
                    // podcast container rather than mislabel it video.
                    entity.setMimeType(FileUriHelper.AUDIO_MP3);
                }
                entity.setAudio(true);
            } else {
                entity.setMimeType(FileUriHelper.MIMETYPE_MP4);
                entity.setAudio(false);
            }
            entity.setType((progressive ? UrlType.FILE : UrlType.MEDIA).getValue());
            entity.setStreams(variants);
            entity.setHasVariants(variants.size() > 1);
            entity.setTags(buildTags(entity, variants));
            return;
        }

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
        if (mProbeRegistry != null) {
            mProbeRegistry.setActiveReader(reader);
        }
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
            // Unregister (under the task's lock) before releasing, so a
            // concurrent cancel() finishes its stop() before we free the reader.
            if (mProbeRegistry != null) {
                mProbeRegistry.setActiveReader(null);
            }
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
        if (variants.size() == 1) {
            String info = variants.get(0).getInfo();
            if (!TextUtils.isEmpty(info)) {
                tags.add(new FFmpegTagEntity(uid, info, FFmpegTagEntity.TYPE_QUALITY));
            }
        }
        return tags;
    }
}