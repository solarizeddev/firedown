package com.solarized.firedown;

import androidx.annotation.Nullable;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;

/**
 * What to download for one update, in the order to try it, and where in that
 * order the current attempt is. Pure (org.json only) so the ladder is
 * testable on a JVM; UpdateDownloader persists it with the pending download.
 *
 * <p>The steps, best first:
 * <ol>
 *   <li>{@link Kind#PATCH} — only when status.json lists a patch whose
 *       fromSha256 is the installed APK's hash;</li>
 *   <li>{@link Kind#XZ} — the whole APK compressed;</li>
 *   <li>{@link Kind#APK} — the APK itself, exactly as before patches existed.
 *       Always present and always last.</li>
 * </ol>
 * A failed download moves to the step's next URL ({@link #nextUrl}); a patch
 * or .xz that downloads fine but does not rebuild the expected APK skips the
 * rest of its step ({@link #nextStep}) — every mirror serves the same file.
 * A kind that failed that way for a version is passed in {@code skip} when
 * the plan is built again, so it is not retried for that version.
 */
public final class UpdatePlan {

    public enum Kind { PATCH, XZ, APK }

    /** One way to get the new APK. */
    public static final class Step {
        public final Kind kind;
        public final List<String> urls;
        /** SHA-256 of the file this step downloads (for APK, the APK's). */
        public final String sha256;
        /** The installed APK's hash the patch starts from; PATCH only. */
        @Nullable
        public final String fromSha256;
        public final long size;

        Step(Kind kind, List<String> urls, String sha256, @Nullable String fromSha256, long size) {
            this.kind = kind;
            this.urls = Collections.unmodifiableList(new ArrayList<>(urls));
            this.sha256 = sha256;
            this.fromSha256 = fromSha256;
            this.size = size;
        }
    }

    private final List<Step> steps;
    private int step;
    private int url;

    private UpdatePlan(List<Step> steps, int step, int url) {
        this.steps = Collections.unmodifiableList(new ArrayList<>(steps));
        this.step = step;
        this.url = url;
    }

    /**
     * @param installedSha256 the installed APK's hash, or null when unknown
     *        (no patch is then chosen)
     * @param skip kinds that already failed to rebuild this version
     */
    public static UpdatePlan build(UpdateManifest manifest, @Nullable String installedSha256,
                                   Set<Kind> skip) {
        List<Step> steps = new ArrayList<>();
        if (!skip.contains(Kind.PATCH)) {
            UpdateManifest.Artifact patch = manifest.patchFrom(installedSha256);
            if (patch != null) {
                steps.add(new Step(Kind.PATCH, patch.urls, patch.sha256, patch.fromSha256, patch.size));
            }
        }
        if (!skip.contains(Kind.XZ) && manifest.compressed != null) {
            UpdateManifest.Artifact xz = manifest.compressed;
            steps.add(new Step(Kind.XZ, xz.urls, xz.sha256, null, xz.size));
        }
        steps.add(new Step(Kind.APK, manifest.downloadUrls, manifest.sha256, null, 0));
        return new UpdatePlan(steps, 0, 0);
    }

    /** The plan an APK-only download recorded before plans existed. */
    public static UpdatePlan apkOnly(List<String> urls, String sha256, int urlIndex) {
        List<Step> steps = new ArrayList<>();
        steps.add(new Step(Kind.APK, urls, sha256, null, 0));
        UpdatePlan plan = new UpdatePlan(steps, 0, 0);
        if (urlIndex > 0 && urlIndex < urls.size()) {
            plan.url = urlIndex;
        }
        return plan;
    }

    public List<Step> steps() {
        return steps;
    }

    public int stepIndex() {
        return step;
    }

    public int urlIndex() {
        return url;
    }

    public Step current() {
        return steps.get(step);
    }

    public String currentUrl() {
        return current().urls.get(url);
    }

    /** Moves to the next URL, or the next step's first; false when none is left. */
    public boolean nextUrl() {
        if (url + 1 < current().urls.size()) {
            url++;
            return true;
        }
        return nextStep();
    }

    /** Skips the rest of the current step; false when no step is left. */
    public boolean nextStep() {
        if (step + 1 < steps.size()) {
            step++;
            url = 0;
            return true;
        }
        return false;
    }

    public String toJson() {
        try {
            JSONObject json = new JSONObject();
            json.put("step", step);
            json.put("url", url);
            JSONArray list = new JSONArray();
            for (Step s : steps) {
                JSONObject o = new JSONObject();
                o.put("kind", s.kind.name());
                o.put("urls", new JSONArray(s.urls));
                o.put("sha256", s.sha256);
                if (s.fromSha256 != null) {
                    o.put("fromSha256", s.fromSha256);
                }
                o.put("size", s.size);
                list.put(o);
            }
            json.put("steps", list);
            return json.toString();
        } catch (JSONException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Null when the text is not a plan this code wrote. */
    @Nullable
    public static UpdatePlan fromJson(@Nullable String text) {
        if (text == null || text.isEmpty()) {
            return null;
        }
        try {
            JSONObject json = new JSONObject(text);
            JSONArray list = json.getJSONArray("steps");
            List<Step> steps = new ArrayList<>();
            for (int i = 0; i < list.length(); i++) {
                JSONObject o = list.getJSONObject(i);
                Kind kind = Kind.valueOf(o.getString("kind"));
                JSONArray urlList = o.getJSONArray("urls");
                List<String> urls = new ArrayList<>();
                for (int j = 0; j < urlList.length(); j++) {
                    urls.add(urlList.getString(j));
                }
                String sha = o.getString("sha256");
                String fromSha = o.has("fromSha256") ? o.getString("fromSha256") : null;
                if (urls.isEmpty() || sha.isEmpty() || (kind == Kind.PATCH && fromSha == null)) {
                    return null;
                }
                steps.add(new Step(kind, urls, sha, fromSha, o.optLong("size", 0)));
            }
            int step = json.getInt("step");
            int url = json.getInt("url");
            if (steps.isEmpty() || step < 0 || step >= steps.size()
                    || url < 0 || url >= steps.get(step).urls.size()) {
                return null;
            }
            return new UpdatePlan(steps, step, url);
        } catch (JSONException | IllegalArgumentException e) {
            return null;
        }
    }
}
