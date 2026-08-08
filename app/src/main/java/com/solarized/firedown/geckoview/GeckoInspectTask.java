package com.solarized.firedown.geckoview;

import android.net.Uri;
import android.text.TextUtils;
import android.util.Base64;
import android.util.Log;

import com.caverock.androidsvg.SVG;
import com.solarized.firedown.data.di.NetworkModule;
import com.solarized.firedown.data.entity.AudioTrackEntity;
import com.solarized.firedown.data.entity.BrowserDownloadEntity;
import com.solarized.firedown.data.entity.FFmpegTagEntity;
import com.solarized.firedown.data.entity.GeckoInspectEntity;
import com.solarized.firedown.data.repository.BrowserDownloadRepository;
import com.solarized.firedown.ffmpegutils.FFmpegEntity;
import com.solarized.firedown.ffmpegutils.FFmpegMetaData;
import com.solarized.firedown.ffmpegutils.FFmpegMetaDataReader;
import com.solarized.firedown.manager.MegaCrypto;
import com.solarized.firedown.manager.UrlType;
import com.solarized.firedown.utils.BrowserHeaders;
import com.solarized.firedown.utils.DebugLog;
import com.solarized.firedown.utils.FileUriHelper;
import com.solarized.firedown.utils.JsonHelper;
import com.solarized.firedown.utils.M3U8Parser;
import com.solarized.firedown.utils.UrlStringUtils;
import com.solarized.firedown.utils.WebUtils;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Processes intercepted media URLs from WebExtensions.
 * Handles three paths:
 * - Variants (Twitter, Instagram, YouTube adaptive): pre-parsed streams, probed individually
 * - FFmpeg (HLS, DASH, direct media): single URL probed by FFmpegMetaDataReader
 * - Special types (SVG, timed text): custom handling
 */
public class GeckoInspectTask implements Runnable, ProbeRegistry {

    private static final String TAG = GeckoInspectTask.class.getSimpleName();

    /**
     * Filename-provenance logs share one tag so the whole chain — extension
     * emit → entity name → display-name rewrite → the name handed to the
     * download — can be read as a single stream. See {@code RunnableManager},
     * which continues it on the download side.
     */
    private static final String NAME_TAG = "FileNameTrace";

    private static final Set<String> BLOCKED_HEADERS = Set.of(
            BrowserHeaders.HOST, BrowserHeaders.CONNECTION,
            BrowserHeaders.ACCEPT_ENCODING, BrowserHeaders.ACCEPT,
            // The intercepted request from a <video> element is typically a
            // partial-range chunk request from the player (e.g.
            // bytes=4068494-4358829). If we forwarded that into a download
            // we'd only get the slice the player was streaming, hit EOF at
            // the slice boundary, and report success. Strip it here so
            // every downstream consumer (HTTP/Gecko strategies, FFmpeg
            // probes) starts from a clean header set.
            BrowserHeaders.RANGES
    );

    // Hop-by-hop / context-bound headers we always strip, regardless of
    // origin. Range is in here for the reason above; the rest would either
    // confuse okhttp (Host is set per URL) or break the connection (a stale
    // Connection: keep-alive value from an intercepted request).
    private static final Set<String> ALWAYS_BLOCKED_HEADERS = Set.of(
            BrowserHeaders.HOST, BrowserHeaders.CONNECTION, BrowserHeaders.RANGES
    );

    private final BrowserDownloadRepository mBrowserDownloadRepository;
    private final UrlType mUrlType;
    private final String mUrl;
    private final String mOrigin;
    private final String mDescription;
    private final String mName;
    private final String mImg;
    private final String mRequestId;
    private final int mTabId;
    private final int mVisitId;
    private final Map<String, String> mRequestHeaders;
    private final ArrayList<FFmpegEntity> mVariants;
    // Selectable audio tracks (multi-audio-track YouTube video) — null/empty
    // for everything else. Copied onto the BrowserDownloadEntity so the
    // variant picker can offer track selection.
    private final ArrayList<AudioTrackEntity> mAudioTracks;
    private final String mSabrUrl;
    private final String mSabrConfig;
    private final String mSabrClientVersion;
    private final String mSabrPoToken;
    private final String mSabrVideoId;
    private final String mSabrVisitorData;
    private final long mDuration;
    private final String mLanguage;
    private final boolean mIncognito;
    private final boolean mSkipProbe;
    // Parser-declared: the variants are HLS/DASH manifests (ffmpeg must mux).
    private final boolean mManifest;
    // Mega.nz folder link — share handle + 128-bit master key (base64url).
    private final String mMegaFolderHandle;
    private final String mMegaMasterKey;
    // Mega.nz single file / embed — public handle + 256-bit file key (base64url).
    private final String mMegaFileHandle;
    private final String mMegaFileKey;
    private FFmpegMetaDataReader mFFmpegMetaDataReader;

    /**
     * Cooperative cancellation for a *running* probe (the executor calls
     * {@link #cancel()} when this task's tab is closed). Both guarded by
     * {@link #mReaderLock}:
     *   - {@code mCancelled}    — once set, the run won't emit a (partial) entity.
     *   - {@code mActiveReader} — the reader currently inside a blocking native
     *     probe ({@code avformat_open_input}/{@code find_stream_info}), or null.
     *     {@link FFmpegMetaDataReader#stop()} flips the native interrupt flag the
     *     AVIO interrupt callback honors, so calling it mid-probe unwinds a wedged
     *     HLS/DASH reload loop at once — the same mechanism a user Stop uses,
     *     here driven by tab-close rather than waiting out the hls.c
     *     consecutive-failure bail.
     */
    private final Object mReaderLock = new Object();
    private boolean mCancelled = false;
    private FFmpegMetaDataReader mActiveReader;

    public GeckoInspectTask(
            BrowserDownloadRepository repository,
            UrlType type,
            GeckoInspectEntity geckoInspectEntity) {

        mBrowserDownloadRepository = repository;
        mUrlType = type;
        mUrl = WebUtils.deParameterize(geckoInspectEntity.getUrl());
        mOrigin = geckoInspectEntity.getOrigin();
        mDescription = geckoInspectEntity.getDescription();
        mRequestId = geckoInspectEntity.getRequestId();
        mRequestHeaders = safeHeaders(geckoInspectEntity.getRequestHeaders());
        mTabId = geckoInspectEntity.getTabId();
        mVisitId = geckoInspectEntity.getVisitId();
        mName = geckoInspectEntity.getName();
        mImg = geckoInspectEntity.getImg();
        mVariants = geckoInspectEntity.getVariants();
        mAudioTracks = geckoInspectEntity.getAudioTracks();
        mSabrUrl = geckoInspectEntity.getSabrUrl();
        mSabrConfig = geckoInspectEntity.getSabrConfig();
        mSabrClientVersion = geckoInspectEntity.getSabrClientVersion();
        mSabrPoToken = geckoInspectEntity.getSabrPoToken();
        mSabrVideoId = geckoInspectEntity.getSabrVideoId();
        mSabrVisitorData = geckoInspectEntity.getSabrVisitorData();
        mDuration = geckoInspectEntity.getDuration();
        mLanguage = geckoInspectEntity.getLanguage();
        mIncognito = geckoInspectEntity.isIncognito();
        mSkipProbe = geckoInspectEntity.isSkipProbe();
        mManifest = geckoInspectEntity.isManifest();
        mMegaFolderHandle = geckoInspectEntity.getMegaFolderHandle();
        mMegaMasterKey = geckoInspectEntity.getMegaMasterKey();
        mMegaFileHandle = geckoInspectEntity.getMegaFileHandle();
        mMegaFileKey = geckoInspectEntity.getMegaFileKey();

        Log.d(TAG, "Task Created for URL: " + mUrl + " img: " + mImg
                + " variants: " + (mVariants != null ? mVariants.size() : 0)
                + " sabr: " + (mSabrUrl != null));

        // First link of the filename provenance chain: what the extension
        // actually emitted. Every later hop logs under NAME_TAG too, so
        // `adb logcat -s FileNameTrace:*` shows where a wrong download name
        // came from without guessing.
        DebugLog.d(NAME_TAG, "emit: name=" + DebugLog.preview(mName)
                + " description=" + DebugLog.preview(mDescription)
                + " type=" + mUrlType + " origin=" + DebugLog.preview(mOrigin));
    }

    @Override
    public void run() {
        if (!UrlStringUtils.isURLLike(mUrl)) {
            Log.w(TAG, "Aborting: Incorrect URL format: " + mUrl);
            return;
        }

        BrowserDownloadEntity entity = prepareEntity();

        if (mBrowserDownloadRepository.contains(entity)) {
            Log.w(TAG, "URL already intercepted, skipping: " + mUrl);
            return;
        }

        try {
            boolean processed = processTask(entity);
            // Don't emit a (possibly partial) entity for a tab that was closed
            // mid-probe — the cancel interrupted the probe precisely so this
            // capture is abandoned, and trimTabs already drops the tab's entries.
            if (processed && !isCancelled()) {
                applyDisplayName(entity);
                mBrowserDownloadRepository.addValue(entity);
            }
        } catch (Exception e) {
            Log.e(TAG, "Processing failed for: " + mUrl, e);
        } finally {
            cleanupFFmpeg();
        }
    }

    // ========================================================================
    // Entity preparation
    // ========================================================================

    private BrowserDownloadEntity prepareEntity() {
        BrowserDownloadEntity entity = new BrowserDownloadEntity();
        String mimeType = FileUriHelper.getMimeTypeFromFile(mUrl);

        // De-dup identity. Normally the media URL is the stable identity of a
        // capture, so uid = url.hashCode() and the repository collapses repeats.
        // But HLS-master sites (niconico / Twitch / Kick) mint a FRESH
        // session-signed master+rendition URL on every page load, so the same
        // video refreshed 3× yields 3 different URLs → 3 duplicate entries. For
        // these, the stable identity is the watch/channel page (the origin), so
        // key the uid on it: a refresh re-resolves to the same uid and the
        // contains()/addValue fast-path (uid match) drops it — before we even
        // re-fetch the master. (The 30s JS-side origin dedup only covers rapid
        // refreshes; this covers the rest and is per-tab via isPresent's tabId
        // guard.) Falls back to the URL when origin is missing.
        boolean originKeyed = mUrlType == UrlType.HLS_MASTER && !TextUtils.isEmpty(mOrigin);
        entity.setUid(originKeyed ? mOrigin.hashCode() : mUrl.hashCode());
        String initialName = TextUtils.isEmpty(mName) ? WebUtils.getFileNameFromURL(mUrl) : mName;
        DebugLog.d(NAME_TAG, "prepareEntity: name=" + DebugLog.preview(initialName)
                + " source=" + (TextUtils.isEmpty(mName) ? "url" : "emit"));
        entity.setFileName(initialName);
        entity.setFileUrl(mUrl);
        entity.setFileOrigin(mOrigin);
        entity.setFileThumbnail(mImg);
        entity.setMimeType(mimeType);
        entity.setHeaders(mRequestHeaders);
        entity.setUpdateTime(System.currentTimeMillis());
        entity.setTabId(mTabId);
        entity.setVisitId(mVisitId);
        entity.setRequestId(mRequestId);
        entity.setFileDescription(mDescription);
        entity.setIncognito(mIncognito);

        // videoId + visitorData identify the video for PoToken minting and
        // are needed by BOTH the SABR stream path and the timedtext caption
        // path. They must be copied unconditionally — gating them behind the
        // sabrUrl/sabrConfig presence (as before) dropped them for timedtext
        // entities, which carry videoId/visitorData but no SABR stream URL.
        // Symptom was TimedTextStrategy logging
        // "mintPoToken: skipping (videoId=false visitorData=false)".
        if (!TextUtils.isEmpty(mSabrVideoId)) {
            entity.setSabrVideoId(mSabrVideoId);
        }
        if (!TextUtils.isEmpty(mSabrVisitorData)) {
            entity.setSabrVisitorData(mSabrVisitorData);
        }

        // Selectable audio tracks (multi-track video) — shared across variants
        if (mAudioTracks != null && !mAudioTracks.isEmpty()) {
            entity.setAudioTracks(mAudioTracks);
        }

        // SABR shared data (same for all variants of this video)
        if (!TextUtils.isEmpty(mSabrUrl) && !TextUtils.isEmpty(mSabrConfig)) {
            entity.setSabrUrl(mSabrUrl);
            entity.setSabrConfig(mSabrConfig);
            if (!TextUtils.isEmpty(mSabrClientVersion)) {
                entity.setSabrClientVersion(mSabrClientVersion);
            }
            if (!TextUtils.isEmpty(mSabrPoToken)) {
                entity.setSabrPoToken(mSabrPoToken);
            }
        }

        // Duration from innertube (for SABR-only variants where FFprobe can't run)
        if (mDuration > 0) {
            entity.setFileDuration(mDuration * 1000); // ms → µs to match FFprobe
        }

        return entity;
    }

    // ========================================================================
    // Task routing — each branch populates the entity, returns true if valid
    // ========================================================================

    /**
     * Routes to the correct processing strategy.
     * Returns true if the entity was successfully populated and should be committed.
     */
    private boolean processTask(BrowserDownloadEntity entity) throws Exception {
        if (mUrlType == UrlType.TIMEDTEXT) {
            entity.setMimeType(FileUriHelper.MIMETYPE_SRT);
            entity.setType(UrlType.TIMEDTEXT.getValue());
            appendLanguageTag(entity);
            return true;

        } else if (mUrlType == UrlType.SUBTITLE) {
            processSubtitle(entity);
            return true;

        } else if (mUrlType == UrlType.SVG) {
            processSvg(entity);
            return true;

        } else if (mUrlType == UrlType.HLS_MASTER) {
            return processHlsMaster(entity);

        } else if (mUrlType == UrlType.MEGA) {
            // Folder share vs. single file / embed — distinguished by which
            // page-world fields the bridge supplied.
            if (!TextUtils.isEmpty(mMegaFolderHandle)) {
                return processMegaFolder(entity);
            }
            return processMegaFile(entity);

        } else if (mVariants != null && !mVariants.isEmpty()) {
            new VariantProcessor(mRequestHeaders, mSkipProbe, mManifest)
                    .setProbeRegistry(this)
                    .process(entity, mVariants);
            return true;

        } else if (mSkipProbe && processMediaSkipProbe(entity)) {
            return true;

        } else {
            return processFFmpeg(entity, mUrl);
        }
    }

    // ========================================================================
    // FFmpeg probe — single URL (HLS, DASH, direct media)
    // ========================================================================

    private boolean processFFmpeg(BrowserDownloadEntity entity, String url) throws IOException {
        Log.d(TAG, "processFFmpeg: " + url);
        mFFmpegMetaDataReader = new FFmpegMetaDataReader();
        setActiveReader(mFFmpegMetaDataReader);
        FFmpegMetaData metadata = mFFmpegMetaDataReader.getStreamInfo(url, mRequestHeaders, false);

        if (metadata == null || !metadata.isValidMedia()) {
            Log.w(TAG, "processFFmpeg error");
            return false;
        }

        parseMetadata(entity, metadata);
        return true;
    }

    /**
     * Single-URL media capture with skipProbe set (Apple Podcasts): the
     * metadatareader probe's only output we actually need was the duration, and
     * the parser already supplied it (formatted onto the entity in
     * prepareEntity). So classify from the URL-derived mime instead of opening
     * the file.
     *
     * <p>Audio only: returns {@code false} for anything we can't positively type
     * as audio from the URL (e.g. an extensionless tracking enclosure), so
     * {@link #processTask} falls through to the probe and never misclassifies.
     * type FILE keeps it on the raw {@code HttpDownloadStrategy} — the same
     * strategy the probe yields for a progressive audio file. Rebuilds the one
     * duration tag {@link #parseTags} would have added for the Capture view.</p>
     */
    private boolean processMediaSkipProbe(BrowserDownloadEntity entity) {
        String mime = entity.getMimeType();
        if (!FileUriHelper.isAudio(mime)) {
            return false;
        }
        entity.setType(UrlType.FILE.getValue());
        entity.setAudio(true);
        String duration = entity.getFileDuration();
        if (!TextUtils.isEmpty(duration)) {
            ArrayList<FFmpegTagEntity> tags = new ArrayList<>();
            tags.add(new FFmpegTagEntity(entity.getUid(), duration, FFmpegTagEntity.TYPE_DURATION));
            entity.setTags(tags);
        }
        return true;
    }

    private void parseMetadata(BrowserDownloadEntity entity, FFmpegMetaData metadata) {
        entity.setType(metadata.getType());
        ArrayList<FFmpegEntity> streams = mFFmpegMetaDataReader.getStreams();
        String mime = mFFmpegMetaDataReader.getMimeType(entity.getMimeType());

        entity.setAudio(metadata.isAudio());
        entity.setStreams(streams);
        entity.setHasVariants(streams.size() > 1);
        entity.setMimeType(mime);
        entity.setFileDuration(metadata.getDuration());
        entity.setPHash(metadata.getPHash());

        parseTags(entity, streams, mime);
    }

    // ========================================================================
    // Subtitle
    // ========================================================================

    private void processSubtitle(BrowserDownloadEntity entity) {
        String lower = mUrl.toLowerCase(Locale.US);
        String mime = lower.contains(".srt") ? FileUriHelper.MIMETYPE_SRT : FileUriHelper.MIMETYPE_VTT;
        entity.setMimeType(mime);
        entity.setType(UrlType.SUBTITLE.getValue());
        appendLanguageTag(entity);
    }

    private void appendLanguageTag(BrowserDownloadEntity entity) {
        if (TextUtils.isEmpty(mLanguage)) return;
        String existing = entity.getFileName();
        if (!TextUtils.isEmpty(existing)) {
            entity.setFileName(existing + " [" + mLanguage + "]");
        }
        // Also surface the language as a visible chip in the Capture fragment
        // (the filename suffix only shows once the row is expanded). Humanised,
        // e.g. "en" -> "English", "en-auto" -> "English (auto)".
        String display = localizeLanguage(mLanguage);
        if (TextUtils.isEmpty(display)) return;
        ArrayList<FFmpegTagEntity> tags = entity.getTags();
        if (tags == null) tags = new ArrayList<>();
        tags.add(new FFmpegTagEntity(entity.getUid(), display, FFmpegTagEntity.TYPE_LANGUAGE));
        entity.setTags(tags);
    }

    /**
     * Humanise a caption language tag: "en" -> "English",
     * "pt-BR" -> "Portuguese (Brazil)", "en-auto" -> "English (auto)" (the
     * "-auto" suffix marks YouTube ASR/auto-generated tracks). Falls back to
     * the raw code if the locale can't be resolved.
     */
    private String localizeLanguage(String code) {
        if (TextUtils.isEmpty(code)) return null;
        boolean auto = code.endsWith("-auto");
        String base = auto ? code.substring(0, code.length() - "-auto".length()) : code;
        String display = base;
        try {
            String name = Locale.forLanguageTag(base).getDisplayName();
            if (!TextUtils.isEmpty(name) && !name.equalsIgnoreCase(base)) {
                display = name;
            }
        } catch (Exception ignored) {
            // keep the raw code
        }
        return auto ? display + " (auto)" : display;
    }

    // ========================================================================
    // HLS master (niconico / Kick / Twitch)
    // ========================================================================

    /**
     * Fetch the HLS master playlist (same fetch-and-parse-in-the-task shape as
     * {@link #processSvg}) and enumerate its qualities with {@link M3U8Parser} —
     * text only, never opening a segment, so a single-use AES key is never burned
     * at capture. The parsed variants run through {@link VariantProcessor} with
     * {@code skipProbe} (no ffmpeg). On any failure (fetch error / not a master)
     * we fall back to the ffmpeg probe of the master URL.
     */
    private boolean processHlsMaster(BrowserDownloadEntity entity) throws Exception {
        String master = null;
        try {
            master = WebUtils.getString(mUrl, mRequestHeaders);
        } catch (Exception e) {
            Log.w(TAG, "HLS master fetch failed, ffmpeg fallback: " + mUrl, e);
        }
        if (TextUtils.isEmpty(master)) {
            return processFFmpeg(entity, mUrl);
        }

        JSONArray arr = M3U8Parser.parseMaster(master, mUrl);
        ArrayList<FFmpegEntity> variants = JsonHelper.parseVariants(arr, null);
        if (variants == null || variants.isEmpty()) {
            Log.w(TAG, "HLS master had no variants, ffmpeg fallback: " + mUrl);
            return processFFmpeg(entity, mUrl);
        }

        // Represent the capture by its first (best) rendition, matching the
        // variant-message convention (entity url = a playable rendition, not the
        // master). skipProbe = true: trust the parsed metadata, don't decrypt.
        String first = variants.get(0).getStreamUrl();
        if (!TextUtils.isEmpty(first)) {
            entity.setFileUrl(first);
        }
        // Came from M3U8Parser on a fetched master → definitionally HLS manifests.
        new VariantProcessor(mRequestHeaders, true, true)
                .setProbeRegistry(this)
                .process(entity, variants);
        return true;
    }

    // ========================================================================
    // Mega.nz folder (zero-knowledge AES)
    // ========================================================================

    private static final String MEGA_API = "https://g.api.mega.co.nz/cs";

    /**
     * Enumerate a Mega.nz folder-link tree and emit one capture per media file.
     *
     * <p>Mega is zero-knowledge: the folder share key is in the URL fragment
     * (read page-world by the bridge and carried here as {@code mMegaMasterKey}),
     * never on the wire. We POST the anonymous cs {@code f} command (scoped by
     * {@code &n=<folder>}) to list every node, then for each <b>file</b> node
     * decrypt its key with the share key (AES-ECB), decrypt its attributes for the
     * filename (AES-CBC), and — for media files — emit a {@link BrowserDownloadEntity}
     * whose URL is a self-describing synthetic link carrying the per-file key. The
     * actual {@code g}-URL resolution + AES-CTR stream decrypt happen later in
     * {@code MegaStrategy}; nothing is fetched or decrypted at capture time.
     *
     * <p>Like the HLS-master path this builds its own entities and adds them
     * directly (one folder yields many downloads), so it returns {@code false}:
     * the prepared folder entity itself is not a download.
     */
    private boolean processMegaFolder(BrowserDownloadEntity folderEntity) {
        if (TextUtils.isEmpty(mMegaFolderHandle) || TextUtils.isEmpty(mMegaMasterKey)) {
            Log.w(TAG, "Mega: missing folder handle / master key");
            return false;
        }
        byte[] masterKey = MegaCrypto.b64(mMegaMasterKey);
        if (masterKey.length != 16) {
            Log.w(TAG, "Mega: master key is " + masterKey.length + " bytes, expected 16 (folder link)");
            return false;
        }

        // Anonymous tree listing — the &n=<folderHandle> scopes the share.
        String body = "[{\"a\":\"f\",\"c\":1,\"r\":1,\"ca\":1}]";
        String url = MEGA_API + "?id=" + Math.abs(new SecureRandom().nextInt()) + "&n=" + mMegaFolderHandle;
        String response;
        try {
            response = WebUtils.postContent(url, body, new HashMap<>());
        } catch (Exception e) {
            Log.w(TAG, "Mega: folder enumeration failed", e);
            return false;
        }

        JSONArray nodes = MegaCrypto.parseFolderNodes(response);
        if (nodes == null || nodes.length() == 0) {
            Log.w(TAG, "Mega: empty / error folder response for " + mMegaFolderHandle);
            return false;
        }

        String folderPage = "https://mega.nz/folder/" + mMegaFolderHandle;
        int emitted = 0;
        for (int i = 0; i < nodes.length(); i++) {
            try {
                JSONObject node = nodes.getJSONObject(i);
                if (node.optInt("t", -1) != 0) continue; // files only (t==0)

                String nodeHandle = node.optString("h", "");
                String encKey = MegaCrypto.shareKeyPart(node.optString("k", ""));
                long size = node.optLong("s", 0);
                if (TextUtils.isEmpty(nodeHandle) || TextUtils.isEmpty(encKey)) continue;

                byte[] nodeKey = MegaCrypto.decryptNodeKey(masterKey, encKey);
                if (nodeKey == null || nodeKey.length != 32) continue; // a file key is 256-bit

                String name = MegaCrypto.decryptName(nodeKey, node.optString("a", ""));
                if (TextUtils.isEmpty(name)) name = nodeHandle;

                // "All media files": keep video / audio / image, drop archives,
                // docs, etc. — the Captured sheet is a media surface.
                String mime = FileUriHelper.getMimeTypeFromFile(name);
                if (!FileUriHelper.isVideo(mime) && !FileUriHelper.isAudio(mime)
                        && !FileUriHelper.isImage(mime)) {
                    continue;
                }

                // Self-describing synthetic URL: a valid https URL (so
                // URLUtil.isValidUrl passes and uid = url.hashCode() dedups per
                // file) carrying the per-file key. MegaStrategy re-derives the AES
                // key + nonce from `fk` and does the g-URL fetch + CTR decrypt. The
                // "/folder/<h>/file/<node>" shape tells MegaStrategy to use the cs
                // `n` (folder-scoped) download call.
                String fileUrl = folderPage + "/file/" + nodeHandle
                        + "?fk=" + MegaCrypto.b64encode(nodeKey);
                String thumb = fetchMegaThumbnail(nodeKey, node.optString("fa", null), mMegaFolderHandle);
                emitMegaEntity(fileUrl, name, mime, size, folderPage, thumb);
                emitted++;
            } catch (Exception ex) {
                Log.w(TAG, "Mega: node parse failed", ex);
            }
        }

        Log.d(TAG, "Mega: emitted " + emitted + " media file(s) from folder " + mMegaFolderHandle);
        return false; // entities added above; the folder itself is not a download
    }

    /**
     * Single Mega.nz file / embed link. The 256-bit key in the URL fragment IS
     * the cleartext node key (no master-key decryption — that's the folder case),
     * so we just fetch the file's attributes ({@code [{"a":"g","p":<handle>}]} —
     * no {@code g:1}, so no temp download URL is minted at capture) for the
     * filename + size, decrypt the name with the file key, and emit one entity.
     * The synthetic URL uses the "/file/<handle>" shape, which tells MegaStrategy
     * to use the cs `p` (public-handle) download call rather than `n`.
     */
    private boolean processMegaFile(BrowserDownloadEntity fileEntity) {
        if (TextUtils.isEmpty(mMegaFileHandle) || TextUtils.isEmpty(mMegaFileKey)) {
            Log.w(TAG, "Mega: missing file handle / key");
            return false;
        }
        byte[] nodeKey = MegaCrypto.b64(mMegaFileKey);
        if (nodeKey.length != 32) {
            Log.w(TAG, "Mega: file key is " + nodeKey.length + " bytes, expected 32");
            return false;
        }

        String filePage = "https://mega.nz/file/" + mMegaFileHandle;
        String fileUrl = filePage + "?fk=" + MegaCrypto.b64encode(nodeKey);

        // Best-effort attributes fetch for the real name + size. A transient
        // failure shouldn't drop the only capture (unlike a folder, there's no
        // other file to fall back to), so on failure we still emit with a
        // page-title / handle name and let MegaStrategy resolve the rest.
        String name = null;
        long size = 0;
        String fa = null;
        String body = "[{\"a\":\"g\",\"p\":\"" + mMegaFileHandle + "\"}]";
        String url = MEGA_API + "?id=" + Math.abs(new SecureRandom().nextInt());
        try {
            String response = WebUtils.postContent(url, body, new HashMap<>());
            JSONArray arr = new JSONArray(response.trim());
            Object first = arr.length() > 0 ? arr.get(0) : null;
            if (first instanceof JSONObject) {
                JSONObject obj = (JSONObject) first;
                size = obj.optLong("s", 0);
                name = MegaCrypto.decryptName(nodeKey, obj.optString("at", ""));
                fa = obj.optString("fa", null);
            }
        } catch (Exception e) {
            Log.w(TAG, "Mega: file attribute fetch failed (emitting anyway)", e);
        }
        if (TextUtils.isEmpty(name)) {
            name = !TextUtils.isEmpty(mName) ? mName : mMegaFileHandle;
        }

        String mime = FileUriHelper.getMimeTypeFromFile(name);
        if (!FileUriHelper.isVideo(mime) && !FileUriHelper.isAudio(mime)
                && !FileUriHelper.isImage(mime)) {
            // A name we couldn't resolve to a media mime (e.g. attr fetch failed
            // and there's no extension) — default to mp4 so an embedded Mega video
            // still captures rather than being silently dropped.
            mime = FileUriHelper.MIMETYPE_MP4;
        }

        // A single-file g call is anonymous (public handle, no folder scope).
        String thumb = fetchMegaThumbnail(nodeKey, fa, null);
        emitMegaEntity(fileUrl, name, mime, size, filePage, thumb);
        Log.d(TAG, "Mega: emitted single file " + mMegaFileHandle + " (" + name + ")");
        return false; // entity added above; this capture row is the download
    }

    /** Build + commit one Mega capture entity (dedups by uid internally). */
    private void emitMegaEntity(String fileUrl, String name, String mime, long size,
                                String origin, String thumbnail) {
        BrowserDownloadEntity e = new BrowserDownloadEntity();
        e.setUid(fileUrl.hashCode());
        e.setFileUrl(fileUrl);
        e.setFileName(name);
        e.setFileNameForced(true); // the decrypted Mega name is authoritative
        e.setFileOrigin(origin);
        e.setMimeType(mime);
        e.setType(UrlType.MEGA.getValue());
        e.setAudio(FileUriHelper.isAudio(mime));
        e.setFileLength(size);
        // The synthetic URL can't be fetched for a frame, so a real thumbnail can
        // only come from Mega's stored file-attribute JPEG (a data: URI here).
        e.setFileThumbnail(!TextUtils.isEmpty(thumbnail) ? thumbnail : mImg);
        // Must be non-null: the Captured adapter feeds getFileHeaders() straight
        // into Glide's RequestOptions.set(HEADERS, …), which NPEs on a null value
        // (every other capture path sets it via prepareEntity). Mega's gfs
        // download URL is self-authorizing, so the content doesn't matter — only
        // that it isn't null; reuse the page's request headers like the rest do.
        e.setHeaders(mRequestHeaders);
        e.setTabId(mTabId);
        e.setVisitId(mVisitId);
        e.setRequestId(mRequestId);
        e.setIncognito(mIncognito);
        e.setUpdateTime(System.currentTimeMillis());
        mBrowserDownloadRepository.addValue(e);
    }

    /**
     * Best-effort fetch of a Mega file's stored thumbnail (the type-0
     * file-attribute JPEG, ~120px), returned as a {@code data:image/jpeg;base64,…}
     * URI for the Captured grid — the only real preview available pre-download,
     * since the encrypted media itself can't be frame-decoded over the wire.
     *
     * <p>Two steps against Mega's API, both anonymous: {@code ufa} resolves the
     * file-attribute storage URL for the thumbnail handle, then a binary POST of
     * the 8-byte handle returns {@code handle(8) + len(4 LE) + AES-CBC JPEG},
     * which we decrypt with the file key. Any failure (no thumbnail, network,
     * malformed framing) returns {@code null} and the row falls back to the mime
     * tile — never fatal.
     *
     * @param folderScope the share handle for a folder file (cs {@code &n}), or
     *                    {@code null} for an anonymous single-file link.
     */
    private String fetchMegaThumbnail(byte[] nodeKey, String fa, String folderScope) {
        Log.d(TAG, "Mega thumb: fa=" + fa);
        String handle = MegaCrypto.faHandle(fa, 0); // 0 = thumbnail
        if (handle == null) {
            Log.d(TAG, "Mega thumb: no type-0 (thumbnail) handle in fa — file has no stored thumbnail");
            return null;
        }
        try {
            // 1. Resolve the file-attribute storage URL.
            String reqUrl = MEGA_API + "?id=" + Math.abs(new SecureRandom().nextInt())
                    + (TextUtils.isEmpty(folderScope) ? "" : "&n=" + folderScope);
            String reqBody = "[{\"a\":\"ufa\",\"fah\":\"" + handle + "\",\"ssl\":2,\"r\":1}]";
            String resp = WebUtils.postContent(reqUrl, reqBody, new HashMap<>());
            Log.d(TAG, "Mega thumb: handle=" + handle + " ufa resp=" + resp);
            JSONArray arr = new JSONArray(resp.trim());
            Object first = arr.length() > 0 ? arr.get(0) : null;
            if (!(first instanceof JSONObject)) {
                Log.w(TAG, "Mega thumb: ufa returned no object (error code?) — " + resp);
                return null;
            }
            String faUrl = ((JSONObject) first).optString("p", null);
            if (TextUtils.isEmpty(faUrl)) {
                Log.w(TAG, "Mega thumb: ufa response had no `p` URL — " + resp);
                return null;
            }
            Log.d(TAG, "Mega thumb: faUrl=" + faUrl);

            // 2. POST the 8-byte handle; response is handle(8) + len(4 LE) + blob.
            byte[] handleBytes = MegaCrypto.b64(handle);
            byte[] raw = WebUtils.postBytes(faUrl, handleBytes);
            Log.d(TAG, "Mega thumb: handleBytes=" + handleBytes.length
                    + " raw resp len=" + (raw == null ? -1 : raw.length)
                    + " head=" + hex(raw, 28));
            if (raw == null || raw.length < 12) {
                Log.w(TAG, "Mega thumb: fa-server response too short");
                return null;
            }
            int len = (raw[8] & 0xFF) | ((raw[9] & 0xFF) << 8)
                    | ((raw[10] & 0xFF) << 16) | ((raw[11] & 0xFF) << 24);
            Log.d(TAG, "Mega thumb: framed blob len=" + len + " (raw=" + raw.length + ")");
            if (len <= 0 || 12 + len > raw.length) {
                Log.w(TAG, "Mega thumb: framed length out of range (framing/endianness wrong?)");
                return null;
            }
            byte[] enc = new byte[len];
            System.arraycopy(raw, 12, enc, 0, len);

            byte[] jpeg = MegaCrypto.decryptFileAttr(nodeKey, enc);
            boolean isJpeg = jpeg != null && jpeg.length > 2
                    && (jpeg[0] & 0xFF) == 0xFF && (jpeg[1] & 0xFF) == 0xD8;
            Log.d(TAG, "Mega thumb: decrypted len=" + (jpeg == null ? -1 : jpeg.length)
                    + " head=" + hex(jpeg, 6) + " jpegMagic=" + isJpeg);
            if (jpeg == null || jpeg.length == 0) {
                return null;
            }
            return "data:image/jpeg;base64," + Base64.encodeToString(
                    jpeg, Base64.NO_WRAP);
        } catch (Exception e) {
            Log.w(TAG, "Mega: thumbnail fetch failed", e);
            return null;
        }
    }

    /** Hex of the first {@code max} bytes, for protocol diagnostics. */
    private static String hex(byte[] b, int max) {
        if (b == null) return "null";
        StringBuilder sb = new StringBuilder();
        int n = Math.min(max, b.length);
        for (int i = 0; i < n; i++) {
            sb.append(String.format(Locale.US, "%02x", b[i] & 0xFF));
        }
        return sb.toString();
    }

    // ========================================================================
    // SVG
    // ========================================================================

    private void processSvg(BrowserDownloadEntity entity) throws Exception {
        String svgString = WebUtils.getString(mUrl, mRequestHeaders);
        if (svgString == null)
            throw new IllegalStateException("Failed to fetch SVG: " + mUrl);
        SVG svg = SVG.getFromString(svgString);
        int width = (int) svg.getDocumentWidth();
        int height = (int) svg.getDocumentHeight();

        if (width > 0 && height > 0) {
            ArrayList<FFmpegTagEntity> tags = new ArrayList<>();
            tags.add(new FFmpegTagEntity(entity.getUid(),
                    String.format(Locale.US, "%dx%d", width, height),
                    FFmpegTagEntity.TYPE_RESOLUTION));
            entity.setTags(tags);
        }
        entity.setMimeType(FileUriHelper.MIMETYPE_SVG);
        entity.setType(UrlType.SVG.getValue());
    }

    // ========================================================================
    // Tags
    // ========================================================================

    private void parseTags(BrowserDownloadEntity entity, ArrayList<FFmpegEntity> streams, String mime) {
        ArrayList<FFmpegTagEntity> tags = new ArrayList<>();
        String duration = entity.getFileDuration();
        int uid = entity.getUid();

        Log.d(TAG, "parseTags mime: " + mime + " url: " + mUrl + " info: " + streams.get(0).getInfo());
        if (FileUriHelper.isVideo(mime) || FileUriHelper.isAudio(mime)) {
            if (!TextUtils.isEmpty(duration)) {
                tags.add(new FFmpegTagEntity(uid, duration, FFmpegTagEntity.TYPE_DURATION));
            }
            if (streams.size() == 1) {
                tags.add(new FFmpegTagEntity(uid, streams.get(0).getInfo(), FFmpegTagEntity.TYPE_QUALITY));
            }
        } else if (FileUriHelper.isImage(mime) || FileUriHelper.isSVG(mime)) {
            if (!streams.isEmpty()) {
                String resolution = streams.get(0).getInfo();
                if (!TextUtils.isEmpty(resolution)) {
                    tags.add(new FFmpegTagEntity(uid, resolution, FFmpegTagEntity.TYPE_RESOLUTION));
                }
            }
        }
        entity.setTags(tags);
    }

    // ========================================================================
    // Display name
    // ========================================================================

    private void applyDisplayName(BrowserDownloadEntity entity) {
        // Subtitles and YouTube timedtext already had their filename built in
        // processSubtitle / the TIMEDTEXT branch (with optional [lang] suffix).
        // Don't let the variant/page-title rename logic overwrite them.
        if (mUrlType == UrlType.SUBTITLE || mUrlType == UrlType.TIMEDTEXT) {
            DebugLog.d(NAME_TAG, "applyDisplayName: kept (subtitle/timedtext) name="
                    + DebugLog.preview(entity.getFileName()));
            return;
        }

        String current = entity.getFileName();
        if (!TextUtils.isEmpty(current) && !WebUtils.isUrlDerivedName(current)) {
            DebugLog.d(NAME_TAG, "applyDisplayName: kept (already descriptive) name="
                    + DebugLog.preview(current));
            return;
        }
        DebugLog.d(NAME_TAG, "applyDisplayName: rewriting, current=" + DebugLog.preview(current)
                + " urlDerived=" + WebUtils.isUrlDerivedName(current));

        // Variant flow (Twitter / IG / YouTube) already populates mName +
        // mDescription from the parser extension. For these we keep the
        // existing "author - text" build to preserve the established
        // filename shape downstream code may depend on.
        String name = buildFileName(mName, mDescription);
        if (!TextUtils.isEmpty(name)) {
            DebugLog.d(NAME_TAG, "applyDisplayName: buildFileName -> " + DebugLog.preview(name));
            entity.setFileName(name);
            return;
        }

        // Generic captured-media flow: the webrequests content script pushed
        // the live page title into mName (and optional meta description into
        // mDescription). Only apply for audio/video — image filenames take
        // their cue from alt text / URL slug, not the page they appeared on.
        String mime = entity.getMimeType();
        if (!FileUriHelper.isVideo(mime) && !FileUriHelper.isAudio(mime)) {
            DebugLog.d(NAME_TAG, "applyDisplayName: kept (not audio/video) mime=" + mime
                    + " name=" + DebugLog.preview(current));
            return;
        }

        String hostname = null;
        try {
            hostname = Uri.parse(entity.getFileOrigin()).getHost();
        } catch (Exception ignored) {
        }
        String descriptive = WebUtils.sanitizeTitleForFilename(mName, hostname);
        DebugLog.d(NAME_TAG, "applyDisplayName: sanitizeTitleForFilename(title="
                + DebugLog.preview(mName) + ", host=" + hostname + ") -> "
                + DebugLog.preview(descriptive));
        if (descriptive != null) {
            entity.setFileName(descriptive);
        }
    }

    private String buildFileName(String author, String text) {
        if (TextUtils.isEmpty(text)) return author;
        String clean = text.replaceAll("https?://\\S+", "")
                .replaceAll("[\\n\\r]+", " ")
                .trim();
        if (clean.length() > 50) clean = clean.substring(0, 50).trim();
        if (TextUtils.isEmpty(clean)) return author;
        if (!TextUtils.isEmpty(author)) return author + " - " + clean;
        return clean;
    }

    // ========================================================================
    // Cleanup & utilities
    // ========================================================================

    private void cleanupFFmpeg() {
        // Drop the registration (under the lock) before releasing, so an
        // in-flight cancel() can't call stop() on a reader we then free.
        setActiveReader(null);
        if (mFFmpegMetaDataReader != null) {
            mFFmpegMetaDataReader.stop();
            mFFmpegMetaDataReader.release();
            mFFmpegMetaDataReader = null;
        }
        // Cancelled probe (tab closed mid-probe): release() above closed the
        // probe's FFmpegOkhttp responses, but on HTTP/2 that only RSTs the
        // *streams* — the pooled *connection* survives, and a live-stream CDN
        // that keeps pushing segments for the dead stream spins OkHttp's
        // DATA→RST_STREAM discard loop until the pool's 5-minute idle
        // eviction. Evict the now-idle connection so the socket closes at
        // once. Cancel-only: a normal probe completion keeps the pool warm
        // for the download that usually follows.
        if (isCancelled()) {
            NetworkModule.evictIdleConnections();
        }
    }

    /**
     * Interrupt a probe in flight. Called by the executor when this task's tab
     * is closed (see {@code PriorityTaskThreadPoolExecutor.cancelTab}). Safe to
     * call from any thread and at any time — if no probe is running it just sets
     * the no-emit flag; if one is, {@code stop()} flips the native interrupt flag
     * so {@code find_stream_info} returns promptly instead of spinning a dead
     * live HLS/DASH reload loop. Idempotent ({@code stop()} just re-sets a flag).
     */
    public void cancel() {
        synchronized (mReaderLock) {
            mCancelled = true;
            if (mActiveReader != null) {
                mActiveReader.stop();
            }
        }
    }

    private boolean isCancelled() {
        synchronized (mReaderLock) {
            return mCancelled;
        }
    }

    /**
     * Register (or, with null, unregister) the reader about to enter / leaving a
     * blocking probe, so {@link #cancel()} can interrupt it. If cancellation
     * already arrived, stop the just-registered reader immediately so a probe
     * that started racing the close still unwinds. The lock pairs with
     * {@link #cancel()}: a concurrent cancel finishes its {@code stop()} before
     * an unregister (null) can return, so the caller's subsequent
     * {@code release()} never races that {@code stop()}.
     */
    @Override
    public void setActiveReader(FFmpegMetaDataReader reader) {
        synchronized (mReaderLock) {
            mActiveReader = reader;
            if (reader != null && mCancelled) {
                reader.stop();
            }
        }
    }

    private Map<String, String> safeHeaders(Map<String, String> headers) {
        if (headers == null) return new HashMap<>();
        // For TIMEDTEXT and SUBTITLE the YouTube/parser extension sets the
        // exact header set deliberately (mirroring SabrDownloader's
        // proven-working MWEB envelope). Stripping Accept / Accept-Encoding
        // / Accept-Language here breaks the response: YouTube's timedtext
        // endpoint returns HTTP 200 with an empty body when those signals
        // are missing or default. Pass them through verbatim — only the
        // hop-by-hop / context-bound headers (Host, Connection, Range) are
        // always unsafe to forward.
        Set<String> blocked = (mUrlType == UrlType.TIMEDTEXT || mUrlType == UrlType.SUBTITLE)
                ? ALWAYS_BLOCKED_HEADERS
                : BLOCKED_HEADERS;
        return headers.entrySet().stream()
                .filter(e -> !blocked.contains(e.getKey()))
                .collect(Collectors.toMap(
                        Map.Entry::getKey,
                        e -> e.getValue().replace("\n", "")
                ));
    }
}