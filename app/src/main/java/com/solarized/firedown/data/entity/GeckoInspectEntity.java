package com.solarized.firedown.data.entity;
import com.solarized.firedown.ffmpegutils.FFmpegEntity;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.Map;
public class GeckoInspectEntity {
    private String name;
    private String origin;
    private String description;
    private String url;
    private String img;
    private String geckoType;
    private String requestId;
    private JSONObject message;
    private int tabId;
    // Navigation-visit id at capture time (GeckoState#getVisitId), stamped by
    // GeckoRuntimeHelper before the task runs and copied onto the persisted
    // BrowserDownloadEntity. Drives the session-aware "this page" grouping.
    private int visitId;
    private Map<String, String> requestHeaders;
    private ArrayList<FFmpegEntity> variants;
    // Selectable audio tracks of a multi-audio-track video (YouTube
    // auto-dubbing) — empty/null for single-track captures. Rides through to
    // BrowserDownloadEntity so the variant picker can offer track selection.
    private ArrayList<AudioTrackEntity> audioTracks;
    // SABR shared data (same for all variants of a video)
    private String sabrUrl;
    private String sabrConfig;
    private String sabrPoToken;
    private String sabrClientVersion;
    private String sabrVideoId;
    private String sabrVisitorData;
    // Duration in milliseconds from innertube (lengthSeconds * 1000)
    private long duration;
    // BCP-47 language tag for subtitle messages (e.g. "en", "es-MX"). Optional.
    private String language;
    private boolean incognito;
    // When true, the parser already supplied full stream metadata (codecs,
    // duration), so GeckoInspect/VariantProcessor must NOT FFprobe. Probing an
    // AES-HLS here decrypts a segment and would burn a single-use key the
    // downloader needs (niconico domand). See CLAUDE.md "Niconico domand AES key".
    private boolean skipProbe;
    // Parser declares these variants are HLS/DASH manifests (ffmpeg muxes them).
    private boolean manifest;
    // Mega.nz folder link: the share root handle (the cs `n` scope) + the 128-bit
    // folder master key (base64url, read from the page-world URL fragment). Used
    // by GeckoInspectTask.processMegaFolder to enumerate + decrypt the tree.
    private String megaFolderHandle;
    private String megaMasterKey;
    // Mega.nz single file / embed link: the public file handle (the cs `p` param)
    // + the 256-bit file key (base64url, the cleartext node key from the URL
    // fragment — no master-key decryption). Used by processMegaFile.
    private String megaFileHandle;
    private String megaFileKey;
    public String getRequestId() {
        return requestId;
    }
    public int getTabId() {
        return tabId;
    }
    public int getVisitId() {
        return visitId;
    }
    /** Load sequence pending in the tab at capture time (0 = none) — the
     *  pre-commit stamp, see GeckoState.mLoadSeq. */
    private int pendingLoadSeq;

    public int getPendingLoadSeq() {
        return pendingLoadSeq;
    }

    public void setPendingLoadSeq(int pendingLoadSeq) {
        this.pendingLoadSeq = pendingLoadSeq;
    }

    public void setVisitId(int visitId) {
        this.visitId = visitId;
    }
    public Map<String, String> getRequestHeaders() {
        return requestHeaders;
    }
    public String getDescription() {
        return description;
    }
    public String getGeckoType() {
        return geckoType;
    }
    public String getName() {
        return name;
    }
    public String getOrigin() {
        return origin;
    }
    public String getUrl() {
        return url;
    }
    public void setDescription(String description) {
        this.description = description;
    }
    public void setGeckoType(String geckoType) {
        this.geckoType = geckoType;
    }
    public void setName(String name) {
        this.name = name;
    }
    public void setRequestHeaders(Map<String, String> requestHeaders) {
        this.requestHeaders = requestHeaders;
    }
    public void setRequestId(String requestId) {
        this.requestId = requestId;
    }
    public void setTabId(int tabId) {
        this.tabId = tabId;
    }
    public void setUrl(String url) {
        this.url = url;
    }
    public void setOrigin(String origin) {
        this.origin = origin;
    }
    public void setImg(String img) {
        this.img = img;
    }
    public String getImg() {
        return img;
    }
    public JSONObject getMessage() {
        return message;
    }
    public void setMessage(JSONObject message) {
        this.message = message;
    }
    public ArrayList<FFmpegEntity> getVariants() {
        return variants;
    }
    public void setVariants(ArrayList<FFmpegEntity> variants) {
        this.variants = variants;
    }
    public ArrayList<AudioTrackEntity> getAudioTracks() {
        return audioTracks;
    }
    public void setAudioTracks(ArrayList<AudioTrackEntity> audioTracks) {
        this.audioTracks = audioTracks;
    }
    public String getSabrUrl() {
        return sabrUrl;
    }
    public void setSabrUrl(String sabrUrl) {
        this.sabrUrl = sabrUrl;
    }
    public String getSabrConfig() {
        return sabrConfig;
    }
    public void setSabrConfig(String sabrConfig) {
        this.sabrConfig = sabrConfig;
    }
    public String getSabrPoToken() {
        return sabrPoToken;
    }
    public void setSabrPoToken(String sabrPoToken) {
        this.sabrPoToken = sabrPoToken;
    }
    public String getSabrClientVersion() {
        return sabrClientVersion;
    }
    public void setSabrClientVersion(String v) {
        this.sabrClientVersion = v;
    }
    public String getSabrVideoId() {
        return sabrVideoId;
    }
    public void setSabrVideoId(String v) {
        this.sabrVideoId = v;
    }
    public String getSabrVisitorData() {
        return sabrVisitorData;
    }
    public void setSabrVisitorData(String v) {
        this.sabrVisitorData = v;
    }
    public long getDuration() {
        return duration;
    }
    public void setDuration(long duration) {
        this.duration = duration;
    }
    public String getLanguage() {
        return language;
    }
    public void setLanguage(String language) {
        this.language = language;
    }
    public boolean isIncognito() {
        return incognito;
    }
    public void setIncognito(boolean incognito) {
        this.incognito = incognito;
    }
    public boolean isSkipProbe() {
        return skipProbe;
    }
    public void setSkipProbe(boolean skipProbe) {
        this.skipProbe = skipProbe;
    }
    public boolean isManifest() {
        return manifest;
    }
    public void setManifest(boolean manifest) {
        this.manifest = manifest;
    }
    public String getMegaFolderHandle() {
        return megaFolderHandle;
    }
    public void setMegaFolderHandle(String megaFolderHandle) {
        this.megaFolderHandle = megaFolderHandle;
    }
    public String getMegaMasterKey() {
        return megaMasterKey;
    }
    public void setMegaMasterKey(String megaMasterKey) {
        this.megaMasterKey = megaMasterKey;
    }
    public String getMegaFileHandle() {
        return megaFileHandle;
    }
    public void setMegaFileHandle(String megaFileHandle) {
        this.megaFileHandle = megaFileHandle;
    }
    public String getMegaFileKey() {
        return megaFileKey;
    }
    public void setMegaFileKey(String megaFileKey) {
        this.megaFileKey = megaFileKey;
    }
}