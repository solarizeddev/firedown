package com.solarized.firedown;

import androidx.annotation.Nullable;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Parsed, validated view of the update status.json descriptor.
 *
 * Deliberately pure (no Android dependencies) so the parse + "is this newer"
 * decision — the logic that governs whether the app downloads/prompts at all,
 * and which runs on untrusted remote JSON — is unit-testable on the JVM (see
 * UpdateManifestTest). UpdateWorker keeps the network IO and the DownloadManager
 * hand-off; this just turns a response body into a trustworthy little struct.
 */
public final class UpdateManifest {

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

    private UpdateManifest(int versionCode, String versionName, List<String> downloadUrls, String sha256) {
        this.versionCode = versionCode;
        this.versionName = versionName;
        this.downloadUrls = Collections.unmodifiableList(downloadUrls);
        this.sha256 = sha256;
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

            return new UpdateManifest(versionCode, versionName, urls, sha256);
        } catch (JSONException e) {
            return null;
        }
    }

    /** True iff this descriptor's version is newer than the installed one. */
    public boolean isNewerThan(int currentVersionCode) {
        return versionCode > currentVersionCode;
    }
}
