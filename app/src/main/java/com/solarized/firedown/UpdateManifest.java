package com.solarized.firedown;

import androidx.annotation.Nullable;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Parsed, validated view of the update status.json descriptor.
 *
 * Deliberately pure (no Android dependencies) so the parse + "is this newer"
 * decision — the logic that governs whether the app downloads/prompts at all,
 * and which runs on untrusted remote JSON — is unit-testable on the JVM (see
 * UpdateManifestTest). UpdateWorker keeps the network IO and the DownloadManager
 * hand-off; this just turns a response body into a trustworthy little struct.
 *
 * Two optional blocks make an update smaller than the whole APK (see
 * UpdatePlan for the order they are tried in):
 * <pre>
 *   "patches":    [{"fromVersionCode": 1204, "fromSha256": "…", "urls": […],
 *                   "sha256": "…", "size": 22050000}, …]   at most 3 read
 *   "compressed": {"format": "xz", "urls": […], "sha256": "…", "size": …}
 * </pre>
 * Both are additions an older app ignores, and a malformed entry is skipped
 * rather than failing the descriptor: the top-level APK fields alone must
 * always be enough to update. Every sha256 here is of the file DOWNLOADED (the
 * patch or the .xz); the rebuilt APK must still hash to the top-level sha256.
 */
public final class UpdateManifest {

    /** Patches past the first three entries are never read. */
    public static final int MAX_PATCHES = 3;

    private static final Pattern SHA256_HEX = Pattern.compile("^[0-9a-fA-F]{64}$");

    public final int versionCode;
    public final String versionName;
    /**
     * Download candidates in priority order: the required {@code updateUrl}
     * first, then any {@code updateUrlFallbacks}. The downloader tries them in
     * turn, so a host blocked for some users (e.g. a Cloudflare-fronted URL
     * behind the LaLiga IP blocks) can fail over to a mirror — mirroring the
     * fallback chain the status.json FETCH already has. Always non-empty.
     */
    public final List<String> downloadUrls;
    public final String sha256;
    /** Optional release notes for the in-app sheet; "" when not provided. */
    public final String changelog;
    /** Valid patches, in the order listed; empty when none. */
    public final List<Artifact> patches;
    /** The compressed APK, or null when absent or unusable. */
    @Nullable
    public final Artifact compressed;

    /** A file to download instead of the APK: a patch, or the APK compressed. */
    public static final class Artifact {
        /** The release the patch starts from; 0 for the compressed APK. */
        public final int fromVersionCode;
        /** Lower-case SHA-256 of the APK the patch starts from; null for the compressed APK. */
        @Nullable
        public final String fromSha256;
        /** Download candidates in order; never empty, https only. */
        public final List<String> urls;
        /** Lower-case SHA-256 of the downloaded file itself. */
        public final String sha256;
        /** Its size in bytes, or 0 when not given. */
        public final long size;

        Artifact(int fromVersionCode, @Nullable String fromSha256, List<String> urls,
                 String sha256, long size) {
            this.fromVersionCode = fromVersionCode;
            this.fromSha256 = fromSha256;
            this.urls = Collections.unmodifiableList(urls);
            this.sha256 = sha256;
            this.size = size;
        }
    }

    private UpdateManifest(int versionCode, String versionName, List<String> downloadUrls,
                           String sha256, String changelog, List<Artifact> patches,
                           @Nullable Artifact compressed) {
        this.versionCode = versionCode;
        this.versionName = versionName;
        this.downloadUrls = Collections.unmodifiableList(downloadUrls);
        this.sha256 = sha256;
        this.changelog = changelog;
        this.patches = Collections.unmodifiableList(patches);
        this.compressed = compressed;
    }

    /**
     * Parses the descriptor body. Returns null on ANY problem — null/blank
     * input, malformed JSON, or a missing/empty required field — so the caller
     * treats a bad descriptor exactly like a failed fetch (retry) instead of
     * acting on half-parsed data. A null/empty updateUrl or sha256 is rejected
     * because both are load-bearing downstream (the download target and the
     * integrity gate); we never want to enqueue a download with neither.
     */
    @Nullable
    public static UpdateManifest parse(@Nullable String body) {
        if (body == null || body.trim().isEmpty()) {
            return null;
        }
        try {
            JSONObject json = new JSONObject(body);
            int versionCode = json.getInt("versionCode");
            String updateUrl = json.getString("updateUrl");
            String sha256 = json.getString("sha256");
            String versionName = json.getString("versionName");
            if (updateUrl.isEmpty() || sha256.isEmpty()) {
                return null;
            }

            List<String> urls = new ArrayList<>();
            urls.add(updateUrl);
            // Optional mirror list. Absent/!array/empty is fine — we just have
            // the one primary URL. A non-string or blank entry is skipped, and
            // a duplicate of the primary is ignored.
            JSONArray fallbacks = json.optJSONArray("updateUrlFallbacks");
            if (fallbacks != null) {
                for (int i = 0; i < fallbacks.length(); i++) {
                    String u = fallbacks.optString(i, "");
                    if (!u.isEmpty() && !urls.contains(u)) {
                        urls.add(u);
                    }
                }
            }

            String changelog = json.optString("changelog", "");

            List<Artifact> patches = new ArrayList<>();
            JSONArray patchList = json.optJSONArray("patches");
            if (patchList != null) {
                for (int i = 0; i < patchList.length() && i < MAX_PATCHES; i++) {
                    Artifact patch = parseArtifact(patchList.optJSONObject(i), true);
                    if (patch != null) {
                        patches.add(patch);
                    }
                }
            }

            Artifact compressed = null;
            JSONObject compressedJson = json.optJSONObject("compressed");
            if (compressedJson != null && "xz".equals(compressedJson.optString("format", ""))) {
                compressed = parseArtifact(compressedJson, false);
            }

            return new UpdateManifest(versionCode, versionName, urls, sha256, changelog,
                    patches, compressed);
        } catch (JSONException e) {
            return null;
        }
    }

    /** Null for anything incomplete or malformed: such an entry is simply not used. */
    @Nullable
    private static Artifact parseArtifact(@Nullable JSONObject json, boolean isPatch) {
        if (json == null) {
            return null;
        }
        String sha = json.optString("sha256", "");
        if (!SHA256_HEX.matcher(sha).matches()) {
            return null;
        }
        String fromSha = null;
        int fromVersionCode = 0;
        if (isPatch) {
            fromSha = json.optString("fromSha256", "");
            if (!SHA256_HEX.matcher(fromSha).matches()) {
                return null;
            }
            fromSha = fromSha.toLowerCase(Locale.ROOT);
            fromVersionCode = json.optInt("fromVersionCode", 0);
        }
        JSONArray urlList = json.optJSONArray("urls");
        if (urlList == null) {
            return null;
        }
        List<String> urls = new ArrayList<>();
        for (int i = 0; i < urlList.length(); i++) {
            String u = urlList.optString(i, "");
            if (u.startsWith("https://") && !urls.contains(u)) {
                urls.add(u);
            }
        }
        if (urls.isEmpty()) {
            return null;
        }
        long size = Math.max(0L, json.optLong("size", 0L));
        return new Artifact(fromVersionCode, fromSha, urls, sha.toLowerCase(Locale.ROOT), size);
    }

    /** The patch that starts from an APK with this hash, or null. */
    @Nullable
    public Artifact patchFrom(@Nullable String installedSha256) {
        if (installedSha256 == null) {
            return null;
        }
        for (Artifact patch : patches) {
            if (patch.fromSha256 != null && patch.fromSha256.equalsIgnoreCase(installedSha256)) {
                return patch;
            }
        }
        return null;
    }

    /** True iff this descriptor's version is newer than the installed one. */
    public boolean isNewerThan(int currentVersionCode) {
        return versionCode > currentVersionCode;
    }
}
